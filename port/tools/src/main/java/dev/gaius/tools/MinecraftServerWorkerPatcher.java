package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Removes dedicated-server services that cannot run inside the browser Worker. */
public final class MinecraftServerWorkerPatcher {
    private static final String JSON_RPC = "net/minecraft/server/jsonrpc/JsonRpc";
    private static final String CREATE_DESCRIPTOR =
            "(Lnet/minecraft/server/dedicated/DedicatedServerSettings;"
                    + "Lnet/minecraft/server/notifications/NotificationManager;)"
                    + "Lnet/minecraft/server/jsonrpc/ManagementServer;";
    private static final String ABSTRACT_EXECUTOR =
            "net/minecraft/util/thread/AbstractConsecutiveExecutor";
    private static final String CHUNK_TASK_DISPATCHER =
            "net/minecraft/server/level/ChunkTaskDispatcher";
    private static final String TASK_SCHEDULER = "net/minecraft/util/thread/TaskScheduler";
    private static final String WORLDGEN_EXECUTOR_NAME = "worldgen-dispatcher";

    private MinecraftServerWorkerPatcher() {
    }

    public static void main(String[] args) throws IOException {
        Path jar = Path.of(args[0]);
        Path outputRoot = Path.of(args[1]);
        boolean jsonRpcPatched = hasEntry(jar, JSON_RPC + ".class");
        if (jsonRpcPatched) {
            ClassNode node = read(jar, JSON_RPC + ".class");
            MethodNode create = find(node, "create", CREATE_DESCRIPTOR);

            // A null result is JsonRpc.create's normal result when the management
            // server is disabled. Browser integrated servers never expose a TCP
            // management endpoint, so make that configuration decision explicit.
            InsnList code = new InsnList();
            code.add(new InsnNode(Opcodes.ACONST_NULL));
            code.add(new InsnNode(Opcodes.ARETURN));
            replace(create, code);
            write(node, outputRoot.resolve(JSON_RPC + ".class"));
        }
        ClassNode dispatcher = read(jar, CHUNK_TASK_DISPATCHER + ".class");
        patchChunkTaskDispatcher(dispatcher);
        write(dispatcher, outputRoot.resolve(CHUNK_TASK_DISPATCHER + ".class"));
        ClassNode executor = read(jar, ABSTRACT_EXECUTOR + ".class");
        patchAbstractExecutor(executor);
        write(executor, outputRoot.resolve(ABSTRACT_EXECUTOR + ".class"));
        if (jsonRpcPatched) {
            System.out.println("Disabled the dedicated JSON-RPC management server for the browser Worker");
        }
        System.out.println("Patched worldgen PriorityConsecutiveExecutor with single-task cooperative turns");
    }

    private static void patchChunkTaskDispatcher(ClassNode node) {
        boolean patched = false;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<init>")
                    || !method.desc.equals("(Lnet/minecraft/util/thread/TaskScheduler;"
                            + "Ljava/util/concurrent/Executor;)V")) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof LdcInsnNode ldc)
                        || !"dispatcher".equals(ldc.cst)) {
                    continue;
                }
                AbstractInsnNode next = instruction.getNext();
                if (!(next instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKESPECIAL
                        || !call.owner.equals("net/minecraft/util/thread/PriorityConsecutiveExecutor")
                        || !call.name.equals("<init>")) {
                    continue;
                }
                LabelNode defaultName = new LabelNode();
                LabelNode join = new LabelNode();
                InsnList names = new InsnList();
                names.add(new LdcInsnNode("worldgen"));
                names.add(new VarInsnNode(Opcodes.ALOAD, 1));
                names.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE, TASK_SCHEDULER, "name",
                        "()Ljava/lang/String;", true));
                names.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                        "(Ljava/lang/Object;)Z", false));
                names.add(new JumpInsnNode(Opcodes.IFEQ, defaultName));
                names.add(new LdcInsnNode(WORLDGEN_EXECUTOR_NAME));
                names.add(new JumpInsnNode(Opcodes.GOTO, join));
                names.add(defaultName);
                names.add(new LdcInsnNode("dispatcher"));
                names.add(join);
                method.instructions.insertBefore(ldc, names);
                method.instructions.remove(ldc);
                patched = true;
                break;
            }
        }
        if (!patched) {
            throw new IllegalStateException("ChunkTaskDispatcher priority executor constructor not found");
        }
    }

    private static void patchAbstractExecutor(ClassNode node) {
        MethodNode run = find(node, "run", "()V");
        InsnList code = new InsnList();
        LabelNode vanilla = new LabelNode();
        LabelNode worldgenStart = new LabelNode();
        LabelNode worldgenDone = new LabelNode();
        LabelNode vanillaStart = new LabelNode();
        LabelNode vanillaDone = new LabelNode();
        LabelNode worldgenCatch = new LabelNode();
        LabelNode vanillaCatch = new LabelNode();
        LabelNode end = new LabelNode();

        code.add(new LdcInsnNode(WORLDGEN_EXECUTOR_NAME));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, ABSTRACT_EXECUTOR, "name", "Ljava/lang/String;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                "(Ljava/lang/Object;)Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, vanilla));

        // A worldgen runnable can suspend through ChunkGenerationTask.runUntilWait().
        // Execute one dispatcher task and return the executor turn immediately.  The
        // existing setSleeping/registerForExecution pair schedules a later turn when the
        // queue still has work; continuing this loop here would drain resumed generation
        // futures inside the same MinecraftServer tick.
        code.add(worldgenStart);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, ABSTRACT_EXECUTOR, "pollTask", "()Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, worldgenDone));
        code.add(worldgenDone);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, ABSTRACT_EXECUTOR, "setSleeping", "()V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, ABSTRACT_EXECUTOR,
                "registerForExecution", "()V", false));
        code.add(new JumpInsnNode(Opcodes.GOTO, end));

        code.add(vanilla);
        code.add(vanillaStart);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, ABSTRACT_EXECUTOR, "pollTask", "()Z", false));
        code.add(new InsnNode(Opcodes.POP));
        code.add(vanillaDone);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, ABSTRACT_EXECUTOR, "setSleeping", "()V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, ABSTRACT_EXECUTOR,
                "registerForExecution", "()V", false));
        code.add(new JumpInsnNode(Opcodes.GOTO, end));

        code.add(worldgenCatch);
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, ABSTRACT_EXECUTOR, "setSleeping", "()V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, ABSTRACT_EXECUTOR,
                "registerForExecution", "()V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new InsnNode(Opcodes.ATHROW));

        code.add(vanillaCatch);
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, ABSTRACT_EXECUTOR, "setSleeping", "()V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, ABSTRACT_EXECUTOR,
                "registerForExecution", "()V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new InsnNode(Opcodes.ATHROW));
        code.add(end);
        code.add(new InsnNode(Opcodes.RETURN));

        run.instructions = code;
        run.tryCatchBlocks.clear();
        if (run.localVariables != null) {
            run.localVariables.clear();
        }
        if (run.visibleLocalVariableAnnotations != null) {
            run.visibleLocalVariableAnnotations.clear();
        }
        if (run.invisibleLocalVariableAnnotations != null) {
            run.invisibleLocalVariableAnnotations.clear();
        }
        run.tryCatchBlocks.add(new org.objectweb.asm.tree.TryCatchBlockNode(
                worldgenStart, worldgenDone, worldgenCatch, null));
        run.tryCatchBlocks.add(new org.objectweb.asm.tree.TryCatchBlockNode(
                vanillaStart, vanillaDone, vanillaCatch, null));
        run.maxStack = 3;
        run.maxLocals = 5;
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        node.name + "." + name + descriptor + " not found"));
    }

    private static ClassNode read(Path jarPath, String entryName) throws IOException {
        byte[] bytes;
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            var entry = jar.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException(entryName + " not found in " + jarPath);
            }
            try (var stream = jar.getInputStream(entry)) {
                bytes = stream.readAllBytes();
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static boolean hasEntry(Path jarPath, String entryName) throws IOException {
        try (ZipFile jar = new ZipFile(jarPath.toFile())) {
            return jar.getEntry(entryName) != null;
        }
    }

    private static void replace(MethodNode method, InsnList code) {
        method.instructions = code;
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.maxStack = 1;
        method.maxLocals = Math.max(method.maxLocals, 2);
    }

    private static void write(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
