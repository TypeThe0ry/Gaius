package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
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
 * Fixes TeaVM 0.15 reflection metadata generation when a class-value type is absent, and adds
 * the opt-in synchronous class-initialization edges ({@value #SYNC_CLINITS_PROPERTY}).
 */
public final class TeaVMCoreBrowserPatcher {
    private static final String CLASS =
            "org/teavm/backend/javascript/intrinsics/reflection/ClassInfoGenerator.class";
    private static final String VFS_CLASS =
            "org/teavm/runtime/fs/memory/InMemoryVirtualFileSystem.class";
    private static final String ASYNC_FINDER_CLASS = "org/teavm/model/util/AsyncMethodFinder";
    private static final String RENDERER_CLASS = "org/teavm/backend/javascript/rendering/Renderer";
    private static final String SOURCE_WRITER = "org/teavm/backend/javascript/codegen/SourceWriter";
    /**
     * When this system property is "true" (the server Worker build sets it), a class
     * initializer that TeaVM compiles as async no longer makes every method that triggers the
     * class initialization async. In the Worker that edge alone (Mth.<clinit> reaches
     * Util.make, and Util.<clinit> is async) turned 36k of 83k methods, including every
     * worldgen kernel, into coroutines. The class initializer itself stays async; the
     * rendered call is wrapped in the globalThis.__gaiusClinitDepth counter so that
     * TModernRuntimeSupport.yieldToEventLoop and TLockSupport do not suspend inside it,
     * because its caller may now be synchronous.
     */
    static final String SYNC_CLINITS_PROPERTY = "gaius.teavm.syncClinits";
    static final String CLINIT_DEPTH_OPEN =
            "globalThis.__gaiusClinitDepth=(globalThis.__gaiusClinitDepth|0)+1;try{";
    static final String CLINIT_DEPTH_CLOSE = "}finally{globalThis.__gaiusClinitDepth--;}";

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
        writePatched(patchRoot, RENDERER_CLASS,
                patchRendererClinitDepth(readClass(jarPath, RENDERER_CLASS)));
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

    private static void writePatched(Path patchRoot, String internalName, ClassNode node)
            throws IOException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Path output = patchRoot.resolve(internalName + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream()
                .filter(candidate -> candidate.name.equals(name) && candidate.desc.equals(desc))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        node.name + "." + name + desc + " not found"));
    }

    /**
     * AsyncMethodFinder.add marks a method async and then every caller of it. With the
     * property set, a class initializer is still marked but its callers are not.
     */
    static ClassNode patchAsyncMethodFinder(ClassNode node) {
        MethodNode add = method(node, "add",
                "(Lorg/teavm/model/MethodReference;Lorg/teavm/model/util/AsyncMethodFinder$CallStack;)V");
        LabelNode added = null;
        for (AbstractInsnNode insn = add.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals("java/util/Set")
                    && call.name.equals("add")
                    && call.getNext() instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.IFNE) {
                if (added != null) {
                    throw new IllegalStateException("AsyncMethodFinder.add has several Set.add checks");
                }
                added = jump.label;
            }
        }
        if (added == null) {
            throw new IllegalStateException("AsyncMethodFinder.add: asyncMethods.add check not found");
        }
        LabelNode propagate = new LabelNode();
        InsnList barrier = new InsnList();
        barrier.add(new LdcInsnNode(SYNC_CLINITS_PROPERTY));
        barrier.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "getBoolean",
                "(Ljava/lang/String;)Z", false));
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
        return node;
    }

    /**
     * Renderer.renderCallClinit writes the class initializer call of X_$callClinit. For an
     * async initializer (local 3) and the property set, the call is wrapped in the
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
        code.add(new LdcInsnNode(SYNC_CLINITS_PROPERTY));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "getBoolean",
                "(Ljava/lang/String;)Z", false));
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
}
