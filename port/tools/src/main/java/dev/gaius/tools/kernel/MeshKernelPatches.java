package dev.gaius.tools.kernel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Routes terrain section compiles of Minecraft 26.2 and 26.3 through the Rust mesher kernel
 * ({@code dev.gaius.browser.kernel.mesh.MeshKernelHooks}, version source sets). 1.21.11 and any
 * other profile are skipped with a log line; on 26.2 and 26.3 every expected field, method and
 * call must be present or the build fails.
 *
 * <ul>
 *   <li>{@code SectionRenderDispatcher$RenderSection$CompileTask}: vanilla {@code doTask} becomes
 *       {@code gaius$doTaskVanilla}, with its single {@code SectionCompiler.compile} call routed
 *       through {@code MeshKernelHooks.compile(compiler, pos, region, sorting, pack, this)}. The
 *       new {@code doTask} returns {@code SectionTaskResult.SUCCESSFUL} when
 *       {@code MeshKernelHooks.beforeCompile(this, dispatcher.sectionCompiler,
 *       dispatcher.cameraPosition.get())} took the section, and runs the vanilla method otherwise.
 *       The task implements {@code MeshKernelAccess$Task}: a {@code gaius$meshKernel} state field,
 *       {@code isCancelled.get()}, {@code isCompleted.set(false)} plus
 *       {@code SectionRenderDispatcher.schedule(this)} (the second run that installs a kernel
 *       mesh), and its region and section.</li>
 *   <li>Read-only accessors: {@code SectionCompiler} ({@code MeshKernelAccess$Compiler}: AO,
 *       cutout leaves, model set, fluid model set, block colors), {@code RenderSectionRegion}
 *       ({@code $Region}: section copies, min section, level), {@code SectionCopy} ({@code $Copy}:
 *       copied states, debug flag), {@code PalettedContainer} ({@code $Paletted}: storage and
 *       palette of its data) and {@code BiomeManager} ({@code MeshBiomeAccess}: the zoom
 *       seed).</li>
 * </ul>
 *
 * <p>Classes are read from {@code root} when an earlier patch step wrote them there (the 26.2
 * chain patches CompileTask.doTask for upload retries and RenderSection for the latest-mesh
 * guard), otherwise from the jar, so this must run after those patches. It adds no branch to
 * an existing method, so existing stack map frames stay valid; the new methods carry their own
 * frames.</p>
 *
 * <p>Runtime switch: {@code ?meshKernel=0} (and the kernel runtime's own switches) keep every
 * section on the vanilla path; see {@code MeshKernelBridge}.</p>
 */
public final class MeshKernelPatches {
    static final String CHUNK = "net/minecraft/client/renderer/chunk/";
    static final String DISPATCHER = CHUNK + "SectionRenderDispatcher";
    static final String RENDER_SECTION = DISPATCHER + "$RenderSection";
    static final String SECTION_TASK = RENDER_SECTION + "$SectionTask";
    static final String COMPILE_TASK = RENDER_SECTION + "$CompileTask";
    static final String TASK_RESULT = SECTION_TASK + "$SectionTaskResult";
    static final String COMPILER = CHUNK + "SectionCompiler";
    static final String RESULTS = COMPILER + "$Results";
    static final String REGION = CHUNK + "RenderSectionRegion";
    static final String COPY = CHUNK + "SectionCopy";
    static final String PACK = "net/minecraft/client/renderer/SectionBufferBuilderPack";
    static final String SECTION_POS = "net/minecraft/core/SectionPos";
    static final String VERTEX_SORTING = "com/mojang/blaze3d/vertex/VertexSorting";
    static final String VEC3 = "net/minecraft/world/phys/Vec3";
    static final String CLIENT_LEVEL = "net/minecraft/client/multiplayer/ClientLevel";
    static final String PALETTED = "net/minecraft/world/level/chunk/PalettedContainer";
    static final String PALETTED_DATA = PALETTED + "$Data";
    static final String BIT_STORAGE = "net/minecraft/util/BitStorage";
    static final String PALETTE = "net/minecraft/world/level/chunk/Palette";
    static final String BIOME_MANAGER = "net/minecraft/world/level/biome/BiomeManager";
    static final String ATOMIC_BOOLEAN = "java/util/concurrent/atomic/AtomicBoolean";
    static final String ATOMIC_REFERENCE = "java/util/concurrent/atomic/AtomicReference";

    static final String ACCESS = "dev/gaius/browser/kernel/mesh/MeshKernelAccess";
    static final String TASK_ACCESS = ACCESS + "$Task";
    static final String COMPILER_ACCESS = ACCESS + "$Compiler";
    static final String REGION_ACCESS = ACCESS + "$Region";
    static final String COPY_ACCESS = ACCESS + "$Copy";
    static final String PALETTED_ACCESS = ACCESS + "$Paletted";
    static final String BIOME_ACCESS = "dev/gaius/browser/kernel/mesh/MeshBiomeAccess";
    static final String HOOKS = "dev/gaius/browser/kernel/mesh/MeshKernelHooks";

    static final String DO_TASK = "doTask";
    static final String DO_TASK_DESCRIPTOR = "(L" + PACK + ";)L" + TASK_RESULT + ";";
    static final String VANILLA_DO_TASK = "gaius$doTaskVanilla";
    static final String STATE_FIELD = "gaius$meshKernel";
    static final String COMPILE_DESCRIPTOR = "(L" + SECTION_POS + ";L" + REGION + ";L" + VERTEX_SORTING + ";L"
            + PACK + ";)L" + RESULTS + ";";
    static final String HOOK_COMPILE_DESCRIPTOR = "(L" + COMPILER + ";L" + SECTION_POS + ";L" + REGION + ";L"
            + VERTEX_SORTING + ";L" + PACK + ";Ljava/lang/Object;)L" + RESULTS + ";";
    static final String HOOK_BEFORE_DESCRIPTOR = "(Ljava/lang/Object;L" + COMPILER + ";L" + VEC3 + ";)Z";

    private MeshKernelPatches() {
    }

    /** Patch step entry point: {@code INPUT_JAR OUTPUT_ROOT MINECRAFT_VERSION}. */
    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: MeshKernelPatches INPUT_JAR OUTPUT_ROOT MINECRAFT_VERSION");
        }
        apply(args[0], Path.of(args[1]), args[2]);
    }

    /** Applies every mesh kernel patch for {@code profile}; false when the profile is skipped. */
    public static boolean apply(String jar, Path root, String profile) throws IOException {
        if (!"26.2".equals(profile) && !"26.3".equals(profile)) {
            System.out.println("MeshKernelPatches: skipped for profile " + profile
                    + " (the mesh kernel covers 26.2 and 26.3; sections keep the vanilla compiler)");
            return false;
        }
        patchCompileTask(jar, root);
        patchSectionCompiler(jar, root);
        patchRegion(jar, root);
        patchSectionCopy(jar, root);
        patchPalettedContainer(jar, root);
        patchBiomeManager(jar, root);
        System.out.println("MeshKernelPatches: routed " + profile
                + " section compiles through the mesh kernel (vanilla doTask kept as " + VANILLA_DO_TASK + ")");
        return true;
    }

    // --- CompileTask ----------------------------------------------------------------------------

    static void patchCompileTask(String jar, Path root) throws IOException {
        ClassNode task = read(jar, root, COMPILE_TASK);
        ClassNode sectionTask = read(jar, root, SECTION_TASK);
        ClassNode section = read(jar, root, RENDER_SECTION);
        ClassNode dispatcher = read(jar, root, DISPATCHER);
        ClassNode results = read(jar, root, TASK_RESULT);
        if (!SECTION_TASK.equals(task.superName)) {
            throw new IllegalStateException(COMPILE_TASK + " no longer extends " + SECTION_TASK);
        }
        if (task.interfaces.contains(TASK_ACCESS) || hasMethod(task, VANILLA_DO_TASK)) {
            throw new IllegalStateException(COMPILE_TASK + " is already patched for the mesh kernel");
        }
        requireField(task, "region", "L" + REGION + ";");
        requireField(task, "this$1", "L" + RENDER_SECTION + ";");
        requireField(sectionTask, "isCancelled", "L" + ATOMIC_BOOLEAN + ";");
        requireField(sectionTask, "isCompleted", "L" + ATOMIC_BOOLEAN + ";");
        requireField(section, "this$0", "L" + DISPATCHER + ";");
        requireField(dispatcher, "sectionCompiler", "L" + COMPILER + ";");
        requireField(dispatcher, "cameraPosition", "L" + ATOMIC_REFERENCE + ";");
        find(dispatcher, "schedule", "(L" + SECTION_TASK + ";)V");
        requireField(results, "SUCCESSFUL", "L" + TASK_RESULT + ";");

        MethodNode vanilla = find(task, DO_TASK, DO_TASK_DESCRIPTOR);
        int routed = 0;
        for (AbstractInsnNode instruction : vanilla.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals(COMPILER)
                    && call.name.equals("compile")
                    && call.desc.equals(COMPILE_DESCRIPTOR)) {
                vanilla.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 0));
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = HOOKS;
                call.desc = HOOK_COMPILE_DESCRIPTOR;
                call.itf = false;
                routed++;
            }
        }
        if (routed != 1) {
            throw new IllegalStateException(COMPILE_TASK + ".doTask expected one SectionCompiler.compile call, found "
                    + routed);
        }
        vanilla.name = VANILLA_DO_TASK;
        vanilla.maxStack += 1;

        task.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, STATE_FIELD, "Ljava/lang/Object;", null, null));
        task.methods.add(doTaskWrapper(vanilla));
        task.methods.add(getter(COMPILE_TASK, "gaius$meshKernelState", STATE_FIELD, "Ljava/lang/Object;"));
        task.methods.add(stateSetter());
        task.methods.add(cancelledAccessor());
        task.methods.add(requeue());
        task.methods.add(getter(COMPILE_TASK, "gaius$meshRegion", "region", "L" + REGION + ";"));
        task.methods.add(getter(COMPILE_TASK, "gaius$meshSection", "this$1", "L" + RENDER_SECTION + ";"));
        task.interfaces.add(TASK_ACCESS);
        write(task, root);
        System.out.println("MeshKernelPatches: CompileTask.doTask asks MeshKernelHooks.beforeCompile first"
                + " and compiles through MeshKernelHooks.compile");
    }

    /**
     * {@code if (MeshKernelHooks.beforeCompile(this, this$1.this$0.sectionCompiler,
     * (Vec3) this$1.this$0.cameraPosition.get())) return SUCCESSFUL; return gaius$doTaskVanilla(pack);}
     */
    private static MethodNode doTaskWrapper(MethodNode vanilla) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, DO_TASK, DO_TASK_DESCRIPTOR, null, null);
        InsnList code = method.instructions;
        LabelNode useVanilla = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        dispatcher(code);
        code.add(new FieldInsnNode(Opcodes.GETFIELD, DISPATCHER, "sectionCompiler", "L" + COMPILER + ";"));
        dispatcher(code);
        code.add(new FieldInsnNode(Opcodes.GETFIELD, DISPATCHER, "cameraPosition", "L" + ATOMIC_REFERENCE + ";"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ATOMIC_REFERENCE, "get", "()Ljava/lang/Object;", false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, VEC3));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "beforeCompile", HOOK_BEFORE_DESCRIPTOR, false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, useVanilla));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, TASK_RESULT, "SUCCESSFUL", "L" + TASK_RESULT + ";"));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(useVanilla);
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, COMPILE_TASK, VANILLA_DO_TASK, DO_TASK_DESCRIPTOR, false));
        code.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 3;
        method.maxLocals = 2;
        method.exceptions = vanilla.exceptions;
        return method;
    }

    /** Pushes {@code this.this$1.this$0}. */
    private static void dispatcher(InsnList code) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, COMPILE_TASK, "this$1", "L" + RENDER_SECTION + ";"));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, RENDER_SECTION, "this$0", "L" + DISPATCHER + ";"));
    }

    private static MethodNode stateSetter() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "gaius$setMeshKernelState", "(Ljava/lang/Object;)V",
                null, null);
        InsnList code = method.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, COMPILE_TASK, STATE_FIELD, "Ljava/lang/Object;"));
        code.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 2;
        method.maxLocals = 2;
        return method;
    }

    private static MethodNode cancelledAccessor() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "gaius$meshKernelCancelled", "()Z", null, null);
        InsnList code = method.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, SECTION_TASK, "isCancelled", "L" + ATOMIC_BOOLEAN + ";"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ATOMIC_BOOLEAN, "get", "()Z", false));
        code.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 1;
        method.maxLocals = 1;
        return method;
    }

    /** {@code isCompleted.set(false); this$1.this$0.schedule(this);} */
    private static MethodNode requeue() {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "gaius$requeueForMeshKernel", "()V", null, null);
        InsnList code = method.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, SECTION_TASK, "isCompleted", "L" + ATOMIC_BOOLEAN + ";"));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ATOMIC_BOOLEAN, "set", "(Z)V", false));
        dispatcher(code);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, DISPATCHER, "schedule", "(L" + SECTION_TASK + ";)V",
                false));
        code.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 2;
        method.maxLocals = 1;
        return method;
    }

    // --- read-only accessors --------------------------------------------------------------------

    static void patchSectionCompiler(String jar, Path root) throws IOException {
        ClassNode node = read(jar, root, COMPILER);
        requireUnpatched(node, COMPILER_ACCESS);
        String models = "Lnet/minecraft/client/renderer/block/BlockStateModelSet;";
        String fluids = "Lnet/minecraft/client/renderer/block/FluidStateModelSet;";
        String colors = "Lnet/minecraft/client/color/block/BlockColors;";
        requireField(node, "ambientOcclusion", "Z");
        requireField(node, "cutoutLeaves", "Z");
        requireField(node, "blockModelSet", models);
        requireField(node, "fluidModelSet", fluids);
        requireField(node, "blockColors", colors);
        node.methods.add(getter(COMPILER, "gaius$ambientOcclusion", "ambientOcclusion", "Z"));
        node.methods.add(getter(COMPILER, "gaius$cutoutLeaves", "cutoutLeaves", "Z"));
        node.methods.add(getter(COMPILER, "gaius$blockModelSet", "blockModelSet", models));
        node.methods.add(getter(COMPILER, "gaius$fluidModelSet", "fluidModelSet", fluids));
        node.methods.add(getter(COMPILER, "gaius$blockColors", "blockColors", colors));
        node.interfaces.add(COMPILER_ACCESS);
        write(node, root);
    }

    static void patchRegion(String jar, Path root) throws IOException {
        ClassNode node = read(jar, root, REGION);
        requireUnpatched(node, REGION_ACCESS);
        requireField(node, "sections", "[L" + COPY + ";");
        requireField(node, "minSectionX", "I");
        requireField(node, "minSectionY", "I");
        requireField(node, "minSectionZ", "I");
        requireField(node, "level", "L" + CLIENT_LEVEL + ";");
        node.methods.add(getter(REGION, "gaius$sections", "sections", "[L" + COPY + ";"));
        node.methods.add(getter(REGION, "gaius$minSectionX", "minSectionX", "I"));
        node.methods.add(getter(REGION, "gaius$minSectionY", "minSectionY", "I"));
        node.methods.add(getter(REGION, "gaius$minSectionZ", "minSectionZ", "I"));
        node.methods.add(getter(REGION, "gaius$level", "level", "L" + CLIENT_LEVEL + ";"));
        node.interfaces.add(REGION_ACCESS);
        write(node, root);
    }

    static void patchSectionCopy(String jar, Path root) throws IOException {
        ClassNode node = read(jar, root, COPY);
        requireUnpatched(node, COPY_ACCESS);
        requireField(node, "section", "L" + PALETTED + ";");
        requireField(node, "debug", "Z");
        node.methods.add(getter(COPY, "gaius$states", "section", "L" + PALETTED + ";"));
        node.methods.add(getter(COPY, "gaius$debug", "debug", "Z"));
        node.interfaces.add(COPY_ACCESS);
        write(node, root);
    }

    static void patchPalettedContainer(String jar, Path root) throws IOException {
        ClassNode node = read(jar, root, PALETTED);
        ClassNode data = read(jar, root, PALETTED_DATA);
        requireUnpatched(node, PALETTED_ACCESS);
        requireField(node, "data", "L" + PALETTED_DATA + ";");
        find(data, "storage", "()L" + BIT_STORAGE + ";");
        find(data, "palette", "()L" + PALETTE + ";");
        node.methods.add(dataAccessor("gaius$storage", "storage", "()L" + BIT_STORAGE + ";"));
        node.methods.add(dataAccessor("gaius$palette", "palette", "()L" + PALETTE + ";"));
        node.interfaces.add(PALETTED_ACCESS);
        write(node, root);
    }

    /** {@code return this.data.<component>();} */
    private static MethodNode dataAccessor(String name, String component, String descriptor) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        InsnList code = method.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, PALETTED, "data", "L" + PALETTED_DATA + ";"));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, PALETTED_DATA, component, descriptor, false));
        code.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 1;
        method.maxLocals = 1;
        return method;
    }

    static void patchBiomeManager(String jar, Path root) throws IOException {
        ClassNode node = read(jar, root, BIOME_MANAGER);
        requireUnpatched(node, BIOME_ACCESS);
        requireField(node, "biomeZoomSeed", "J");
        node.methods.add(getter(BIOME_MANAGER, "gaius$biomeZoomSeed", "biomeZoomSeed", "J"));
        node.interfaces.add(BIOME_ACCESS);
        write(node, root);
    }

    // --- helpers ----------------------------------------------------------------------------------

    /** A public instance getter {@code return this.field;}. */
    private static MethodNode getter(String owner, String name, String field, String descriptor) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, "()" + descriptor, null, null);
        InsnList code = method.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, field, descriptor));
        int wide = descriptor.equals("J") || descriptor.equals("D") ? 2 : 1;
        int opcode;
        switch (descriptor.charAt(0)) {
            case 'Z', 'B', 'C', 'S', 'I' -> opcode = Opcodes.IRETURN;
            case 'J' -> opcode = Opcodes.LRETURN;
            case 'F' -> opcode = Opcodes.FRETURN;
            case 'D' -> opcode = Opcodes.DRETURN;
            default -> opcode = Opcodes.ARETURN;
        }
        code.add(new InsnNode(opcode));
        method.maxStack = wide;
        method.maxLocals = 1;
        return method;
    }

    private static void requireUnpatched(ClassNode node, String accessInterface) {
        if (node.interfaces.contains(accessInterface)) {
            throw new IllegalStateException(node.name + " is already patched for the mesh kernel");
        }
    }

    private static void requireField(ClassNode node, String name, String descriptor) {
        for (FieldNode field : node.fields) {
            if (field.name.equals(name) && field.desc.equals(descriptor)) {
                return;
            }
        }
        throw new IllegalStateException(node.name + "." + name + " " + descriptor + " was not found");
    }

    private static boolean hasMethod(ClassNode node, String name) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                return method;
            }
        }
        throw new IllegalStateException(node.name + "." + name + descriptor + " was not found");
    }

    /** Reads {@code owner} from {@code root} when an earlier step wrote it, else from the jar. */
    static ClassNode read(String jar, Path root, String owner) throws IOException {
        Path written = root.resolve(owner + ".class");
        byte[] bytes;
        if (Files.isRegularFile(written)) {
            bytes = Files.readAllBytes(written);
        } else {
            try (ZipFile zip = new ZipFile(jar)) {
                ZipEntry entry = zip.getEntry(owner + ".class");
                if (entry == null) {
                    throw new IllegalStateException("Missing class entry " + owner + ".class in " + jar);
                }
                try (var stream = zip.getInputStream(entry)) {
                    bytes = stream.readAllBytes();
                }
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    /** Writes without recomputing frames: existing methods keep theirs, new ones carry their own. */
    static void write(ClassNode node, Path root) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Path output = root.resolve(node.name + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
