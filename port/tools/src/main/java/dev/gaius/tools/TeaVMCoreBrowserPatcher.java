package dev.gaius.tools;

import dev.gaius.tools.runtime.GaiusTeaVMOptions;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Patches TeaVM 0.15 core for the browser build.
 *
 * <ul>
 * <li>ClassInfoGenerator: reflection metadata generation skips a class-value type that is
 * absent.</li>
 * <li>InMemoryVirtualFileSystem.getFile restores persisted files on demand.</li>
 * <li>Role-level compiler options ({@link GaiusTeaVMOptions}): the TeaVM plugin
 * {@code <properties>} written by generate-pom.sh reach the compiler through the patched
 * {@code TeaVM.getPlatformTags} and {@code Renderer.setProperties}. They drive
 * syncClinits (class-initialization edges stay synchronous), syncMonitors (the
 * java.lang.Object monitor primitives are compiled synchronously), the async family barrier
 * (virtual families such as toString/equals/hashCode stop propagating async-ness), runtime
 * name pinning (TeaVM runtime functions keep top-level bindings past maxTopLevelNames) and
 * the telemetry platform tag.</li>
 * <li>thread.js: a suspension inside a method that those options compiled synchronously
 * throws instead of corrupting the coroutine stack.</li>
 * </ul>
 *
 * <p>Every patch locates its bytecode or text anchor exactly and throws when the TeaVM shape
 * differs; the patched jar only exists for TeaVM 0.15.
 */
public final class TeaVMCoreBrowserPatcher {
    private static final String CLASS =
            "org/teavm/backend/javascript/intrinsics/reflection/ClassInfoGenerator.class";
    private static final String VFS_CLASS =
            "org/teavm/runtime/fs/memory/InMemoryVirtualFileSystem.class";
    private static final String ASYNC_FINDER_CLASS = "org/teavm/model/util/AsyncMethodFinder";
    private static final String RENDERER_CLASS = "org/teavm/backend/javascript/rendering/Renderer";
    private static final String SOURCE_WRITER = "org/teavm/backend/javascript/codegen/SourceWriter";
    private static final String TEAVM_CLASS = "org/teavm/vm/TeaVM";
    private static final String MINIFYING_ALIASES =
            "org/teavm/backend/javascript/codegen/MinifyingAliasProvider";
    private static final String DEFAULT_ALIASES =
            "org/teavm/backend/javascript/codegen/DefaultAliasProvider";
    private static final String SCOPED_NAME = "org/teavm/backend/javascript/codegen/ScopedName";
    private static final String RENDERING_UTIL =
            "org/teavm/backend/javascript/rendering/RenderingUtil";
    private static final String THREAD_JS = "org/teavm/backend/javascript/thread.js";
    private static final String OPTIONS = "dev/gaius/tools/runtime/GaiusTeaVMOptions";
    /** Helper classes the patched TeaVM classes call; copied into the patched jar. */
    private static final List<String> RUNTIME_HELPERS = List.of(OPTIONS);
    private static final String PINNED_NAME_METHOD = "gaiusPinnedTopLevelName";
    private static final String PINNED_SUFFIX_FIELD = "gaiusPinnedSuffix";

    /**
     * Role option (POM property, see {@link GaiusTeaVMOptions#SYNC_CLINITS}): a class
     * initializer that TeaVM compiles as async no longer makes every method that triggers the
     * class initialization async. In the Worker that edge alone (Mth.<clinit> reaches
     * Util.make, and Util.<clinit> is async) turned 36k of 83k methods, including every
     * worldgen kernel, into coroutines. The class initializer itself stays async; the
     * rendered call is wrapped in the globalThis.__gaiusClinitDepth counter so that
     * TModernRuntimeSupport.yieldToEventLoop and TLockSupport do not suspend inside it,
     * because its caller may now be synchronous.
     */
    static final String SYNC_CLINITS_PROPERTY = GaiusTeaVMOptions.SYNC_CLINITS;
    static final String CLINIT_DEPTH_OPEN =
            "globalThis.__gaiusClinitDepth=(globalThis.__gaiusClinitDepth|0)+1;try{";
    static final String CLINIT_DEPTH_CLOSE = "}finally{globalThis.__gaiusClinitDepth--;}";

    static final String THREAD_SUSPEND_ANCHOR = """
            TeaVMThread.prototype.suspend = function(callback) {
                this.suspendCallback = callback;
                this.status = 1;
            };""";
    /**
     * A method that the async barrier or syncMonitors compiled synchronously keeps
     * globalThis.__gaiusNoSuspendDepth positive while it runs. Reaching a real suspension
     * there would leave its frame out of the saved coroutine stack, so the suspension fails
     * fast with the stack of the offending call instead.
     *
     * <p>TeaVM wraps the thrown JS Error into a java.lang.RuntimeException, so Java code that
     * catches Exception or Throwable (CompletableFuture, tick and reload loops) can swallow
     * it. The guard therefore also counts the violation in
     * globalThis.__gaiusNoSuspendViolations, keeps the last stack in
     * __gaiusLastNoSuspendViolation and logs the first ones to the console; the browser
     * acceptance scripts fail on either (tools/teavm-runtime-guards.mjs).
     *
     * <p>A suspension inside a class initializer that syncClinits rendered behind the
     * globalThis.__gaiusClinitDepth counter is only counted
     * (__gaiusClinitSuspensions, first stack in __gaiusFirstClinitSuspension) and logged once
     * as a warning: it is valid when the initializer was triggered from an async caller and
     * corrupts the coroutine stack ("Invalid recorded state") when the caller is synchronous,
     * which the runtime cannot tell apart.
     */
    static final String THREAD_SUSPEND_GUARDED = """
            TeaVMThread.prototype.suspend = function(callback) {
                if ((globalThis.__gaiusNoSuspendDepth | 0) > 0) {
                    let gaiusError = new teavm_globals.Error("Gaius: a TeaVM suspension was "
                        + "reached inside a method that the role build options (async barrier "
                        + "or syncMonitors) compiled synchronously. Under syncMonitors this "
                        + "means a monitor was held across a suspension and another green "
                        + "thread waited for it. Remove the method's family from "
                        + "gaius.teavm.asyncBarrier or disable gaius.teavm.syncMonitors for "
                        + "this role.");
                    globalThis.__gaiusNoSuspendViolations =
                        (globalThis.__gaiusNoSuspendViolations | 0) + 1;
                    globalThis.__gaiusLastNoSuspendViolation =
                        teavm_globals.String(gaiusError.stack || gaiusError);
                    if (globalThis.__gaiusNoSuspendViolations <= 8
                            && typeof teavm_globals.console === "object") {
                        teavm_globals.console.error(gaiusError);
                    }
                    throw gaiusError;
                }
                if ((globalThis.__gaiusClinitDepth | 0) > 0) {
                    globalThis.__gaiusClinitSuspensions =
                        (globalThis.__gaiusClinitSuspensions | 0) + 1;
                    if (globalThis.__gaiusClinitSuspensions === 1) {
                        let gaiusNotice = new teavm_globals.Error("Gaius: a TeaVM suspension "
                            + "was reached inside a class initializer. It is only safe when "
                            + "the initializer was triggered from an async caller "
                            + "(gaius.teavm.syncClinits); an 'Invalid recorded state' error "
                            + "or a half-initialized class after this points here.");
                        globalThis.__gaiusFirstClinitSuspension =
                            teavm_globals.String(gaiusNotice.stack || gaiusNotice);
                        if (typeof teavm_globals.console === "object") {
                            teavm_globals.console.warn(gaiusNotice);
                        }
                    }
                }
                this.suspendCallback = callback;
                this.status = 1;
            };""";

    private TeaVMCoreBrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        Path jarPath = Path.of(args[0]);
        Path output = Path.of(args[1]);
        byte[] bytes;
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            try (var stream = jar.getInputStream(jar.getEntry(CLASS))) {
                bytes = stream.readAllBytes();
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("writeSimpleConstructors"))
                .findFirst()
                .orElseThrow();

        VarInsnNode classLoad = null;
        LabelNode continueLabel = null;
        boolean sawClassLookup = false;
        for (var instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("org/teavm/model/ListableClassReaderSource")
                    && call.name.equals("get")) {
                sawClassLookup = true;
            } else if (sawClassLookup
                    && instruction instanceof VarInsnNode variable
                    && variable.getOpcode() == Opcodes.ALOAD
                    && variable.var == 10) {
                classLoad = variable;
                sawClassLookup = false;
            } else if (instruction instanceof IincInsnNode increment
                    && increment.var == 7
                    && increment.incr == 1) {
                continueLabel = new LabelNode();
                method.instructions.insertBefore(increment, continueLabel);
                break;
            }
        }
        if (classLoad == null || continueLabel == null) {
            throw new IllegalStateException("TeaVM ClassInfoGenerator patch point not found");
        }
        InsnList nullGuard = new InsnList();
        nullGuard.add(new VarInsnNode(Opcodes.ALOAD, 10));
        nullGuard.add(new JumpInsnNode(Opcodes.IFNULL, continueLabel));
        method.instructions.insertBefore(classLoad, nullGuard);

        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());

        Path patchRoot = output;
        for (int index = 0; index < Path.of(CLASS).getNameCount(); index++) {
            patchRoot = patchRoot.getParent();
        }
        byte[] vfsBytes;
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            try (var stream = jar.getInputStream(jar.getEntry(VFS_CLASS))) {
                vfsBytes = stream.readAllBytes();
            }
        }
        ClassNode vfsNode = new ClassNode();
        new ClassReader(vfsBytes).accept(vfsNode, 0);
        MethodNode getFile = vfsNode.methods.stream()
                .filter(candidate -> candidate.name.equals("getFile")
                        && candidate.desc.equals("(Ljava/lang/String;)Lorg/teavm/runtime/fs/VirtualFile;"))
                .findFirst()
                .orElseThrow();
        InsnList restoreHook = new InsnList();
        restoreHook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        restoreHook.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserFilePersistence",
                "restoreOnDemand",
                "(Ljava/lang/String;)Z",
                false));
        restoreHook.add(new InsnNode(Opcodes.POP));
        getFile.instructions.insert(restoreHook);

        ClassWriter vfsWriter = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        vfsNode.accept(vfsWriter);
        Path vfsOutput = patchRoot.resolve(VFS_CLASS);
        Files.createDirectories(vfsOutput.getParent());
        Files.write(vfsOutput, vfsWriter.toByteArray());

        writePatched(patchRoot, ASYNC_FINDER_CLASS,
                patchAsyncMethodFinder(readClass(jarPath, ASYNC_FINDER_CLASS)));
        ClassNode renderer = readClass(jarPath, RENDERER_CLASS);
        patchRendererClinitDepth(renderer);
        patchRendererOptions(renderer);
        patchRendererSyncGuard(renderer);
        writePatched(patchRoot, RENDERER_CLASS, renderer);
        writePatched(patchRoot, TEAVM_CLASS,
                patchTeaVMPlatformTags(readClass(jarPath, TEAVM_CLASS)));
        writePatched(patchRoot, MINIFYING_ALIASES,
                patchMinifyingAliasProvider(readClass(jarPath, MINIFYING_ALIASES)));
        writePatched(patchRoot, DEFAULT_ALIASES,
                patchDefaultAliasProvider(readClass(jarPath, DEFAULT_ALIASES)));
        writeText(patchRoot, THREAD_JS, patchThreadRuntime(readText(jarPath, THREAD_JS)));
        copyRuntimeHelpers(patchRoot);
    }

    private static ClassNode readClass(Path jarPath, String internalName) throws IOException {
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            var entry = jar.getEntry(internalName + ".class");
            if (entry == null) {
                throw new IllegalStateException(internalName + " not found in " + jarPath);
            }
            try (var stream = jar.getInputStream(entry)) {
                ClassNode node = new ClassNode();
                new ClassReader(stream.readAllBytes()).accept(node, 0);
                return node;
            }
        }
    }

    private static String readText(Path jarPath, String name) throws IOException {
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            var entry = jar.getEntry(name);
            if (entry == null) {
                throw new IllegalStateException(name + " not found in " + jarPath);
            }
            try (var stream = jar.getInputStream(entry)) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private static void writePatched(Path patchRoot, String internalName, ClassNode node)
            throws IOException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Path output = patchRoot.resolve(internalName + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static void writeText(Path patchRoot, String name, String text) throws IOException {
        Path output = patchRoot.resolve(name);
        Files.createDirectories(output.getParent());
        Files.writeString(output, text, StandardCharsets.UTF_8);
    }

    /**
     * The patched TeaVM classes call {@link GaiusTeaVMOptions}, so the helper must be inside
     * the patched jar that the TeaVM Maven plugin loads.
     */
    private static void copyRuntimeHelpers(Path patchRoot) throws IOException {
        ClassLoader loader = TeaVMCoreBrowserPatcher.class.getClassLoader();
        // Keeps javac compiling the helper with the patchers (build-overlays.sh compiles the
        // top-level patcher sources and resolves referenced classes from the source path).
        if (GaiusTeaVMOptions.class.getClassLoader() != loader) {
            throw new IllegalStateException("GaiusTeaVMOptions is not on the patcher classpath");
        }
        for (String helper : RUNTIME_HELPERS) {
            try (InputStream stream = loader.getResourceAsStream(helper + ".class")) {
                if (stream == null) {
                    throw new IllegalStateException(helper + ".class is not on the patcher classpath");
                }
                Path output = patchRoot.resolve(helper + ".class");
                Files.createDirectories(output.getParent());
                Files.write(output, stream.readAllBytes());
            }
        }
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream()
                .filter(candidate -> candidate.name.equals(name) && candidate.desc.equals(desc))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        node.name + "." + name + desc + " not found"));
    }

    private static void requireField(ClassNode node, String name, String desc) {
        boolean present = node.fields.stream()
                .anyMatch(field -> field.name.equals(name) && field.desc.equals(desc));
        if (!present) {
            throw new IllegalStateException(node.name + "." + name + " " + desc + " not found");
        }
    }

    private static AbstractInsnNode single(MethodNode method, String what,
            java.util.function.Predicate<AbstractInsnNode> filter) {
        AbstractInsnNode found = null;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (filter.test(insn)) {
                if (found != null) {
                    throw new IllegalStateException(method.name + ": several " + what);
                }
                found = insn;
            }
        }
        if (found == null) {
            throw new IllegalStateException(method.name + ": " + what + " not found");
        }
        return found;
    }

    /** The next instruction that is not a label, line number or frame. */
    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next;
    }

    private static MethodInsnNode options(String name, String desc) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, OPTIONS, name, desc, false);
    }

    /**
     * AsyncMethodFinder:
     * <ul>
     * <li>find() resets and finally reports the methods the options compiled synchronously;</li>
     * <li>add() first asks {@link GaiusTeaVMOptions#cutAsync}: a monitor primitive (syncMonitors)
     * or an async-barrier family member is neither marked async nor propagated to its callers
     * (its body is guarded at render time, see {@link #patchRendererSyncGuard});</li>
     * <li>with syncClinits, a class initializer is still marked async but its callers are
     * not.</li>
     * </ul>
     */
    static ClassNode patchAsyncMethodFinder(ClassNode node) {
        requireField(node, "classSource", "Lorg/teavm/model/ListableClassReaderSource;");
        MethodNode find = method(node, "find", "(Lorg/teavm/model/ListableClassReaderSource;)V");
        AbstractInsnNode findReturn = single(find, "RETURN",
                insn -> insn.getOpcode() == Opcodes.RETURN);
        find.instructions.insertBefore(findReturn, options("reportCuts", "()V"));
        find.instructions.insert(options("beginAsyncAnalysis", "()V"));

        MethodNode add = method(node, "add",
                "(Lorg/teavm/model/MethodReference;Lorg/teavm/model/util/AsyncMethodFinder$CallStack;)V");
        AbstractInsnNode setAdd = single(add, "asyncMethods.add check",
                insn -> insn instanceof MethodInsnNode call && call.owner.equals("java/util/Set")
                        && call.name.equals("add")
                        && call.getNext() instanceof JumpInsnNode jump
                        && jump.getOpcode() == Opcodes.IFNE);
        LabelNode added = ((JumpInsnNode) setAdd.getNext()).label;
        LabelNode propagate = new LabelNode();
        InsnList barrier = new InsnList();
        barrier.add(options("syncClinits", "()Z"));
        barrier.add(new JumpInsnNode(Opcodes.IFEQ, propagate));
        barrier.add(new LdcInsnNode("<clinit>"));
        barrier.add(new VarInsnNode(Opcodes.ALOAD, 1));
        barrier.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "org/teavm/model/MethodReference",
                "getName", "()Ljava/lang/String;", false));
        barrier.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                "(Ljava/lang/Object;)Z", false));
        barrier.add(new JumpInsnNode(Opcodes.IFEQ, propagate));
        barrier.add(new InsnNode(Opcodes.RETURN));
        barrier.add(propagate);
        add.instructions.insert(added, barrier);

        LabelNode notCut = new LabelNode();
        InsnList cut = new InsnList();
        cut.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cut.add(new FieldInsnNode(Opcodes.GETFIELD, ASYNC_FINDER_CLASS, "classSource",
                "Lorg/teavm/model/ListableClassReaderSource;"));
        cut.add(new VarInsnNode(Opcodes.ALOAD, 1));
        cut.add(options("cutAsync", "(Ljava/lang/Object;Ljava/lang/Object;)Z"));
        cut.add(new JumpInsnNode(Opcodes.IFEQ, notCut));
        cut.add(new InsnNode(Opcodes.RETURN));
        cut.add(notCut);
        add.instructions.insert(cut);
        return node;
    }

    /**
     * Renderer.renderCallClinit writes the class initializer call of X_$callClinit. For an
     * async initializer (local 3) and syncClinits, the call is wrapped in the
     * globalThis.__gaiusClinitDepth counter. The counter is also correct across a suspension
     * of an async caller: the finally block runs when the initializer unwinds, and the resumed
     * case re-enters the try block.
     */
    static ClassNode patchRendererClinitDepth(ClassNode node) {
        MethodNode render = method(node, "renderCallClinit",
                "(Lorg/teavm/model/MethodReader;Lorg/teavm/model/ClassReader;)V");
        TypeInsnNode newReference = null;
        for (AbstractInsnNode insn = render.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals("org/teavm/model/MethodReference")) {
                if (newReference != null) {
                    throw new IllegalStateException("renderCallClinit has several MethodReference allocations");
                }
                newReference = type;
            }
        }
        if (newReference == null
                || !(newReference.getPrevious() instanceof FieldInsnNode writerField)
                || !writerField.name.equals("writer")
                || !(writerField.getPrevious() instanceof VarInsnNode self)
                || self.var != 0) {
            throw new IllegalStateException("renderCallClinit: clinit call rendering not found");
        }
        AbstractInsnNode end = newReference;
        String[] expected = {"appendMethod", "append", "softNewLine"};
        int matched = 0;
        while (matched < expected.length) {
            end = end.getNext();
            if (end == null) {
                throw new IllegalStateException("renderCallClinit: clinit call statement not found");
            }
            if (end instanceof MethodInsnNode call && call.owner.equals(SOURCE_WRITER)) {
                if (!call.name.equals(expected[matched])) {
                    throw new IllegalStateException("renderCallClinit: unexpected " + call.name);
                }
                matched++;
            }
        }
        if (!(end.getNext() instanceof InsnNode pop) || pop.getOpcode() != Opcodes.POP) {
            throw new IllegalStateException("renderCallClinit: clinit call statement end not found");
        }
        render.instructions.insertBefore(self, clinitDepthText(CLINIT_DEPTH_OPEN));
        render.instructions.insert(pop, clinitDepthText(CLINIT_DEPTH_CLOSE));
        return node;
    }

    private static InsnList clinitDepthText(String text) {
        LabelNode skip = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        code.add(options("syncClinits", "()Z"));
        code.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, RENDERER_CLASS, "writer", "L" + SOURCE_WRITER + ";"));
        code.add(new LdcInsnNode(text));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SOURCE_WRITER, "append",
                "(Ljava/lang/String;)L" + SOURCE_WRITER + ";", false));
        code.add(new InsnNode(Opcodes.POP));
        code.add(skip);
        return code;
    }

    /**
     * Renderer.setProperties receives the TeaVM compiler properties before rendering, the
     * async analysis and name allocation; it passes them to {@link GaiusTeaVMOptions}.
     */
    static ClassNode patchRendererOptions(ClassNode node) {
        MethodNode setProperties = method(node, "setProperties", "(Ljava/util/Properties;)V");
        InsnList configure = new InsnList();
        configure.add(new VarInsnNode(Opcodes.ALOAD, 1));
        configure.add(options("configure", "(Ljava/util/Properties;)V"));
        setProperties.instructions.insert(configure);
        return node;
    }

    /**
     * Renderer.renderRegularBody renders a synchronous or coroutine method body between the
     * prologue ("(params) => {") and the closing brace of renderBody. For a method that the
     * options compiled synchronously, the body is wrapped in the no-suspend guard
     * ({@link GaiusTeaVMOptions#guardOpen}), which thread.js checks on every suspension.
     */
    static ClassNode patchRendererSyncGuard(ClassNode node) {
        MethodNode body = method(node, "renderRegularBody",
                "(Lorg/teavm/model/MethodHolder;Lorg/teavm/ast/decompilation/Decompiler;Z)V");
        AbstractInsnNode prologue = single(body, "renderMethodPrologue call",
                insn -> insn instanceof MethodInsnNode call && call.owner.equals(RENDERER_CLASS)
                        && call.name.equals("renderMethodPrologue"));
        single(body, "MethodBodyRenderer.render call",
                insn -> insn instanceof MethodInsnNode call
                        && call.owner.equals("org/teavm/backend/javascript/rendering/MethodBodyRenderer")
                        && call.name.equals("render"));
        AbstractInsnNode end = single(body, "RETURN", insn -> insn.getOpcode() == Opcodes.RETURN);
        body.instructions.insert(prologue, guardText("guardOpen"));
        body.instructions.insertBefore(end, guardText("guardClose"));
        return node;
    }

    private static InsnList guardText(String helper) {
        LabelNode none = new LabelNode();
        LabelNode done = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "org/teavm/model/MethodHolder",
                "getReference", "()Lorg/teavm/model/MethodReference;", false));
        code.add(options(helper, "(Ljava/lang/Object;)Ljava/lang/String;"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new JumpInsnNode(Opcodes.IFNULL, none));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, RENDERER_CLASS, "writer", "L" + SOURCE_WRITER + ";"));
        code.add(new InsnNode(Opcodes.SWAP));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SOURCE_WRITER, "append",
                "(Ljava/lang/String;)L" + SOURCE_WRITER + ";", false));
        code.add(new InsnNode(Opcodes.POP));
        code.add(new JumpInsnNode(Opcodes.GOTO, done));
        code.add(none);
        code.add(new InsnNode(Opcodes.POP));
        code.add(done);
        return code;
    }

    /**
     * TeaVM.getPlatformTags is what the class library plugin reads when TeaVM installs plugins,
     * after TeaVMTool has set the compiler properties (the target controller only exists from
     * build() on, later). The patched method configures {@link GaiusTeaVMOptions} from the
     * live properties and appends the role tags, which drive {@code @PlatformMarker}
     * constants such as the telemetry tag.
     */
    static ClassNode patchTeaVMPlatformTags(ClassNode node) {
        requireField(node, "properties", "Ljava/util/Properties;");
        MethodNode tags = method(node, "getPlatformTags", "()[Ljava/lang/String;");
        AbstractInsnNode result = single(tags, "ARETURN", insn -> insn.getOpcode() == Opcodes.ARETURN);
        InsnList extend = new InsnList();
        extend.add(new VarInsnNode(Opcodes.ALOAD, 0));
        extend.add(new FieldInsnNode(Opcodes.GETFIELD, TEAVM_CLASS, "properties",
                "Ljava/util/Properties;"));
        extend.add(options("platformTags",
                "([Ljava/lang/String;Ljava/util/Properties;)[Ljava/lang/String;"));
        tags.instructions.insertBefore(result, extend);
        return node;
    }

    /**
     * MinifyingAliasProvider puts every top-level name past maxTopLevelNames on the
     * additional scope object ("A.x"), which V8 keeps in dictionary mode. Names are assigned
     * by textual frequency, so TeaVM runtime functions that are referenced from only a few
     * places but executed on every coroutine check (the current-thread slot) end up there.
     * The patch records the top-level counter when the scope switch happens and gives a
     * pinned runtime function ({@link GaiusTeaVMOptions#isPinnedRuntimeName}) a fresh
     * top-level name from that counter.
     *
     * <p>The switch happens inside createTopLevelName, so a pinned name requested before it
     * still takes the regular path; when that very call switched to the scope (the returned
     * name is scoped), the scoped name is dropped and the pinned allocation runs instead.
     * The dropped scope suffix is never rendered.
     */
    static ClassNode patchMinifyingAliasProvider(ClassNode node) {
        requireField(node, "lastSuffix", "I");
        requireField(node, "topLevelNames", "I");
        requireField(node, "additionalScopeStarted", "Z");
        requireField(node, "usedAliases", "Ljava/util/Set;");
        String startLetters = node.fields.stream()
                .filter(field -> field.name.equals("startLetters") && field.value instanceof String)
                .map(field -> (String) field.value)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "MinifyingAliasProvider.startLetters constant not found"));
        if (node.fields.stream().anyMatch(field -> field.name.equals(PINNED_SUFFIX_FIELD))) {
            throw new IllegalStateException("MinifyingAliasProvider is already patched");
        }
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, PINNED_SUFFIX_FIELD, "I", null, null));

        MethodNode create = method(node, "createTopLevelName", "()L" + SCOPED_NAME + ";");
        AbstractInsnNode scopeSwitch = single(create, "additional scope switch",
                insn -> insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
                        && field.name.equals("additionalScopeStarted"));
        if (!(nextReal(scopeSwitch) instanceof VarInsnNode self && self.var == 0
                && nextReal(self) instanceof InsnNode zero && zero.getOpcode() == Opcodes.ICONST_0
                && nextReal(zero) instanceof FieldInsnNode reset
                && reset.getOpcode() == Opcodes.PUTFIELD && reset.name.equals("lastSuffix"))) {
            throw new IllegalStateException(
                    "MinifyingAliasProvider.createTopLevelName: lastSuffix reset not found");
        }
        InsnList freeze = new InsnList();
        freeze.add(new VarInsnNode(Opcodes.ALOAD, 0));
        freeze.add(new VarInsnNode(Opcodes.ALOAD, 0));
        freeze.add(new FieldInsnNode(Opcodes.GETFIELD, MINIFYING_ALIASES, "lastSuffix", "I"));
        freeze.add(new FieldInsnNode(Opcodes.PUTFIELD, MINIFYING_ALIASES, PINNED_SUFFIX_FIELD, "I"));
        create.instructions.insert(scopeSwitch, freeze);

        MethodNode pinned = new MethodNode(Opcodes.ACC_PRIVATE, PINNED_NAME_METHOD,
                "()L" + SCOPED_NAME + ";", null, null);
        LabelNode next = new LabelNode();
        InsnList code = pinned.instructions;
        code.add(next);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, MINIFYING_ALIASES, PINNED_SUFFIX_FIELD, "I"));
        code.add(new InsnNode(Opcodes.DUP_X1));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, MINIFYING_ALIASES, PINNED_SUFFIX_FIELD, "I"));
        code.add(new LdcInsnNode(startLetters));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RENDERING_UTIL, "indexToId",
                "(ILjava/lang/String;)Ljava/lang/String;", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, MINIFYING_ALIASES, "usedAliases", "Ljava/util/Set;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "contains",
                "(Ljava/lang/Object;)Z", true));
        code.add(new JumpInsnNode(Opcodes.IFNE, next));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, RENDERING_UTIL, "KEYWORDS", "Ljava/util/Set;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "contains",
                "(Ljava/lang/Object;)Z", true));
        code.add(new JumpInsnNode(Opcodes.IFNE, next));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, MINIFYING_ALIASES, "topLevelNames", "I"));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, MINIFYING_ALIASES, "topLevelNames", "I"));
        code.add(new TypeInsnNode(Opcodes.NEW, SCOPED_NAME));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, SCOPED_NAME, "<init>",
                "(Ljava/lang/String;Z)V", false));
        code.add(new InsnNode(Opcodes.ARETURN));
        node.methods.add(pinned);

        MethodNode functionAlias = method(node, "getFunctionAlias",
                "(Ljava/lang/String;)L" + SCOPED_NAME + ";");
        requireCall(functionAlias, MINIFYING_ALIASES, "createTopLevelName", "()L" + SCOPED_NAME + ";");
        InsnList redirect = new InsnList();
        LabelNode regular = new LabelNode();
        LabelNode pinnedName = new LabelNode();
        LabelNode dropScoped = new LabelNode();
        redirect.add(new VarInsnNode(Opcodes.ALOAD, 1));
        redirect.add(options("isPinnedRuntimeName", "(Ljava/lang/String;)Z"));
        redirect.add(new JumpInsnNode(Opcodes.IFEQ, regular));
        redirect.add(new VarInsnNode(Opcodes.ALOAD, 0));
        redirect.add(new FieldInsnNode(Opcodes.GETFIELD, MINIFYING_ALIASES, "additionalScopeStarted", "Z"));
        redirect.add(new JumpInsnNode(Opcodes.IFNE, pinnedName));
        redirect.add(new VarInsnNode(Opcodes.ALOAD, 0));
        redirect.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, MINIFYING_ALIASES, "createTopLevelName",
                "()L" + SCOPED_NAME + ";", false));
        redirect.add(dropScopedName(dropScoped));
        redirect.add(pinnedName);
        redirect.add(new VarInsnNode(Opcodes.ALOAD, 0));
        redirect.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, MINIFYING_ALIASES, PINNED_NAME_METHOD,
                "()L" + SCOPED_NAME + ";", false));
        redirect.add(new InsnNode(Opcodes.ARETURN));
        redirect.add(regular);
        functionAlias.instructions.insert(redirect);
        return node;
    }

    /**
     * Stack: the ScopedName of the regular allocation. Returns it when it is top-level;
     * otherwise pops it and falls through at {@code dropScoped} (placed at the end of the
     * returned list) to the pinned allocation that follows.
     */
    private static InsnList dropScopedName(LabelNode dropScoped) {
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, SCOPED_NAME, "scoped", "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, dropScoped));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(dropScoped);
        code.add(new InsnNode(Opcodes.POP));
        return code;
    }

    /** The regular alias allocation the redirect falls back to must still be this call. */
    private static void requireCall(MethodNode method, String owner, String name, String desc) {
        single(method, owner + "." + name + " call",
                insn -> insn instanceof MethodInsnNode call && call.owner.equals(owner)
                        && call.name.equals(name) && call.desc.equals(desc));
    }

    /**
     * DefaultAliasProvider (readable names) has the same top-level budget. A pinned runtime
     * function past the budget keeps its own name as a top-level binding: runtime function
     * names are unique, valid identifiers and no Java member alias starts with "$rt_" or
     * "Long_". As in {@link #patchMinifyingAliasProvider}, the pinned name requested by the
     * call that switches to the scope (inside makeUnique) drops the scoped result.
     */
    static ClassNode patchDefaultAliasProvider(ClassNode node) {
        requireField(node, "additionalScopeStarted", "Z");
        String makeUniqueDesc = "(Ljava/lang/String;)L" + SCOPED_NAME + ";";
        MethodNode functionAlias = method(node, "getFunctionAlias", makeUniqueDesc);
        requireCall(functionAlias, DEFAULT_ALIASES, "makeUnique", makeUniqueDesc);
        InsnList redirect = new InsnList();
        LabelNode regular = new LabelNode();
        LabelNode pinnedName = new LabelNode();
        LabelNode dropScoped = new LabelNode();
        redirect.add(new VarInsnNode(Opcodes.ALOAD, 1));
        redirect.add(options("isPinnedRuntimeName", "(Ljava/lang/String;)Z"));
        redirect.add(new JumpInsnNode(Opcodes.IFEQ, regular));
        redirect.add(new VarInsnNode(Opcodes.ALOAD, 0));
        redirect.add(new FieldInsnNode(Opcodes.GETFIELD, DEFAULT_ALIASES, "additionalScopeStarted", "Z"));
        redirect.add(new JumpInsnNode(Opcodes.IFNE, pinnedName));
        redirect.add(new VarInsnNode(Opcodes.ALOAD, 0));
        redirect.add(new VarInsnNode(Opcodes.ALOAD, 1));
        redirect.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, DEFAULT_ALIASES, "makeUnique",
                makeUniqueDesc, false));
        redirect.add(dropScopedName(dropScoped));
        redirect.add(pinnedName);
        redirect.add(new TypeInsnNode(Opcodes.NEW, SCOPED_NAME));
        redirect.add(new InsnNode(Opcodes.DUP));
        redirect.add(new VarInsnNode(Opcodes.ALOAD, 1));
        redirect.add(new InsnNode(Opcodes.ICONST_0));
        redirect.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, SCOPED_NAME, "<init>",
                "(Ljava/lang/String;Z)V", false));
        redirect.add(new InsnNode(Opcodes.ARETURN));
        redirect.add(regular);
        functionAlias.instructions.insert(redirect);
        return node;
    }

    /** thread.js: the suspension guard for methods the options compiled synchronously. */
    static String patchThreadRuntime(String source) {
        String normalized = source.replace("\r\n", "\n");
        int first = normalized.indexOf(THREAD_SUSPEND_ANCHOR);
        if (first < 0 || normalized.indexOf(THREAD_SUSPEND_ANCHOR, first + 1) >= 0) {
            throw new IllegalStateException("thread.js: TeaVMThread.prototype.suspend anchor not"
                    + " found exactly once");
        }
        List<String> required = new ArrayList<>(List.of(
                "$rt_currentNativeThread = this;", "let $rt_currentNativeThread = null;",
                "let $rt_nativeThread = () => $rt_currentNativeThread;"));
        for (String text : required) {
            if (!normalized.contains(text)) {
                throw new IllegalStateException("thread.js: expected runtime text missing: " + text);
            }
        }
        return normalized.replace(THREAD_SUSPEND_ANCHOR, THREAD_SUSPEND_GUARDED);
    }
}
