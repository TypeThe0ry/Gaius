package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Removes Java 25 and desktop-only paths that remain reachable in Minecraft 26.2. */
public final class Minecraft262BrowserPatcher {
    private static final String JDK_COMPAT = "dev/gaius/browser/BrowserJdkCompat";

    private Minecraft262BrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "usage: Minecraft262BrowserPatcher INPUT_JAR OUTPUT_ROOT");
        }
        String jar = args[0];
        Path root = Path.of(args[1]);
        patchNativeLibrariesBootstrap(jar, root);
        patchPreferredGraphicsApi(jar, root);
        patchVulkanBackend(jar, root);
        patchGlDeviceCapabilities(jar, root);
        patchFramerateLimiter(jar, root);
        patchVanillaTargetingObservation(jar, root);
        patchSectionRenderTaskRetryYields(jar, root);
        patchSectionRenderEmergencyUpload(jar, root);
        patchStagingBuffer(jar, root);
        patchTemplateSource(jar, root);
        patchRemoteFriendList(jar, root);
        patchNativeModuleLister(jar, root);
        patchMacosUtil(jar, root);
        patchVulkanDebug(jar, root);
        patchDetailedMemoryDebug(jar, root);
        patchSystemSpecsDebug(jar, root);
        patchFileFixAccess(jar, root);
        patchFileFixerUpperHardLinks(jar, root);
        patchIdentifierResolveAgainst(jar, root);
        patchCopyOnWriteFileSystem(jar, root);
        patchCopyOnWriteProvider(jar, root);
    }

    private static void patchNativeLibrariesBootstrap(String jar, Path root) throws IOException {
        String owner = "com/mojang/blaze3d/platform/NativeLibrariesBootstrap";
        ClassNode node = read(jar, owner + ".class");
        replaceVoid(find(node, "loadLibraries", "()V"));
        replaceBoolean(find(node, "isVulkanLoaderAvailable", "()Z"), false);
        write(node, root.resolve(owner + ".class"));
        System.out.println("Disabled desktop native-library bootstrap for Minecraft 26.2");
    }

    private static void patchPreferredGraphicsApi(String jar, Path root) throws IOException {
        String owner = "net/minecraft/client/PreferredGraphicsApi";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "getBackendsToTry",
                "()[Lcom/mojang/blaze3d/systems/GpuBackend;");
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new TypeInsnNode(
                Opcodes.ANEWARRAY, "com/mojang/blaze3d/systems/GpuBackend"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new TypeInsnNode(Opcodes.NEW, "com/mojang/blaze3d/opengl/GlBackend"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "com/mojang/blaze3d/opengl/GlBackend",
                "<init>",
                "()V",
                false));
        code.add(new InsnNode(Opcodes.AASTORE));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 5, 1);
        write(node, root.resolve(owner + ".class"));
        System.out.println("Forced Minecraft 26.2 browser graphics backend to OpenGL");
    }

    private static void patchVulkanBackend(String jar, Path root) throws IOException {
        String owner = "com/mojang/blaze3d/vulkan/VulkanBackend";
        String exception = "com/mojang/blaze3d/systems/BackendCreationException";
        String reason = exception + "$Reason";
        ClassNode node = read(jar, owner + ".class");
        replaceVoid(find(node, "<clinit>", "()V"));
        replaceVoid(find(node, "setWindowHints", "()V"));

        MethodNode check = find(node, "checkBackendAvailable", "()L" + exception + ";");
        InsnList unavailable = new InsnList();
        unavailable.add(new TypeInsnNode(Opcodes.NEW, exception));
        unavailable.add(new InsnNode(Opcodes.DUP));
        unavailable.add(new org.objectweb.asm.tree.LdcInsnNode(
                "Vulkan is unavailable in the browser runtime"));
        unavailable.add(new FieldInsnNode(
                Opcodes.GETSTATIC, reason, "VULKAN_LOADER_MISSING", "L" + reason + ";"));
        unavailable.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                exception,
                "<init>",
                "(Ljava/lang/String;L" + reason + ";)V",
                false));
        unavailable.add(new InsnNode(Opcodes.ARETURN));
        replace(check, unavailable, 4, 0);

        MethodNode create = find(node, "createDevice",
                "(JLcom/mojang/blaze3d/shaders/ShaderSource;"
                        + "Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)"
                        + "Lcom/mojang/blaze3d/systems/GpuDevice;");
        InsnList reject = new InsnList();
        reject.add(new TypeInsnNode(Opcodes.NEW, exception));
        reject.add(new InsnNode(Opcodes.DUP));
        reject.add(new org.objectweb.asm.tree.LdcInsnNode(
                "Vulkan is unavailable in the browser runtime"));
        reject.add(new FieldInsnNode(
                Opcodes.GETSTATIC, reason, "VULKAN_LOADER_MISSING", "L" + reason + ";"));
        reject.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                exception,
                "<init>",
                "(Ljava/lang/String;L" + reason + ";)V",
                false));
        reject.add(new InsnNode(Opcodes.ATHROW));
        replace(create, reject, 4, create.maxLocals);
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchGlDeviceCapabilities(String jar, Path root) throws IOException {
        String owner = "com/mojang/blaze3d/opengl/GlDevice";
        ClassNode node = read(jar, owner + ".class");
        MethodNode initializer = find(node, "<clinit>", "()V");
        int returns = 0;
        for (AbstractInsnNode instruction = initializer.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            InsnList disable = new InsnList();
            for (String field : new String[] {
                    "USE_GL_ARB_base_instance",
                    "USE_GL_ARB_draw_indirect",
                    "USE_GL_ARB_multi_draw_indirect",
                    "USE_GL_ARB_shader_draw_parameters"
            }) {
                disable.add(new InsnNode(Opcodes.ICONST_0));
                disable.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, field, "Z"));
            }
            initializer.instructions.insertBefore(instruction, disable);
            returns++;
        }
        if (returns == 0) {
            throw new IllegalStateException("GlDevice initializer return was not found");
        }
        initializer.maxStack = Math.max(initializer.maxStack, 1);
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchFramerateLimiter(String jar, Path root) throws IOException {
        String owner = "net/minecraft/client/FramerateLimiter";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "limitDisplayFPS", "(I)V");
        int removed = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;) {
            AbstractInsnNode next = instruction.getNext();
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("java/lang/Thread")
                    && call.name.equals("onSpinWait")
                    && call.desc.equals("()V")) {
                method.instructions.remove(call);
                removed++;
            }
            instruction = next;
        }
        if (removed == 0) {
            throw new IllegalStateException("FramerateLimiter spin call was not found");
        }
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchVanillaTargetingObservation(String jar, Path root)
            throws IOException {
        String owner = "net/minecraft/client/renderer/GameRenderer";
        ClassNode node = read(jar, owner + ".class");
        MethodNode extract = find(
                node,
                "extract",
                "(Lnet/minecraft/client/DeltaTracker;Z)V");
        MethodInsnNode extractCamera = null;
        for (AbstractInsnNode instruction = extract.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals(owner)
                    && call.name.equals("extractCamera")
                    && call.desc.equals("(Lnet/minecraft/client/DeltaTracker;FF)V")) {
                if (extractCamera != null) {
                    throw new IllegalStateException(
                            "GameRenderer.extract has multiple extractCamera calls");
                }
                extractCamera = call;
            }
        }
        if (extractCamera == null) {
            throw new IllegalStateException(
                    "GameRenderer.extract current camera observation point was not found");
        }
        AbstractInsnNode partialTick = extractCamera.getPrevious();
        while (partialTick != null && partialTick.getOpcode() < 0) {
            partialTick = partialTick.getPrevious();
        }
        if (!(partialTick instanceof VarInsnNode partialTickLoad)
                || partialTickLoad.getOpcode() != Opcodes.FLOAD) {
            throw new IllegalStateException(
                    "GameRenderer.extract camera partial tick load was not found");
        }

        InsnList observe = new InsnList();
        observe.add(new VarInsnNode(Opcodes.ALOAD, 0));
        observe.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        observe.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                "net/minecraft/client/Minecraft",
                "hitResult",
                "Lnet/minecraft/world/phys/HitResult;"));
        observe.add(new VarInsnNode(Opcodes.ALOAD, 0));
        observe.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "mainCamera",
                "Lnet/minecraft/client/Camera;"));
        observe.add(new VarInsnNode(Opcodes.FLOAD, partialTickLoad.var));
        observe.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserTargeting",
                "observeVanillaPick",
                "(Lnet/minecraft/world/phys/HitResult;Lnet/minecraft/client/Camera;F)V",
                false));
        extract.instructions.insert(extractCamera, observe);
        extract.maxStack = Math.max(extract.maxStack, 3);
        write(node, root.resolve(owner + ".class"));
        System.out.println("Instrumented Minecraft 26.2 vanilla single-raycast targeting");
    }

    private static void patchSectionRenderTaskRetryYields(String jar, Path root)
            throws IOException {
        String prefix = "net/minecraft/client/renderer/chunk/"
                + "SectionRenderDispatcher$RenderSection$";
        for (String task : new String[] {"CompileTask", "ResortTransparencyTask"}) {
            String owner = prefix + task;
            ClassNode node = read(jar, owner + ".class");
            MethodNode method = find(node, "doTask",
                    "(Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)"
                            + "Lnet/minecraft/client/renderer/chunk/"
                            + "SectionRenderDispatcher$RenderSection$SectionTask$"
                            + "SectionTaskResult;");
            int expectedResultLocal = task.equals("CompileTask") ? 12 : 9;
            int patched = replaceRenderThreadRetryWithYield(method, expectedResultLocal);
            int cancelled = replaceSpinWaitWithBoundedCancellation(method, owner);
            int cleared = clearUploadRetryOnReturns(method);
            requireOne(owner + " upload retry yield", patched);
            requireOne(owner + " bounded upload retry cancellation", cancelled);
            if (cleared < 2) {
                throw new IllegalStateException(owner + " return cleanup changed: " + cleared);
            }
            method.maxStack = Math.max(method.maxStack, 3);
            write(node, root.resolve(owner + ".class"));
        }
    }

    private static int replaceRenderThreadRetryWithYield(
            MethodNode method, int expectedResultLocal) {
        int patched = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !call.owner.equals("com/mojang/blaze3d/systems/RenderSystem")
                    || !call.name.equals("isOnRenderThread")
                    || !call.desc.equals("()Z")
                    || !(nextOpcode(call) instanceof JumpInsnNode retry)
                    || retry.getOpcode() != Opcodes.IFNE) {
                continue;
            }
            AbstractInsnNode uploadResultJump = previousOpcode(call);
            AbstractInsnNode uploadResultLoad = previousOpcode(uploadResultJump);
            int callIndex = method.instructions.indexOf(call);
            int retryIndex = method.instructions.indexOf(retry.label);
            if (!(uploadResultJump instanceof JumpInsnNode uploadSucceeded)
                    || uploadSucceeded.getOpcode() != Opcodes.IFNE
                    || !(uploadResultLoad instanceof VarInsnNode resultLoad)
                    || resultLoad.getOpcode() != Opcodes.ILOAD
                    || resultLoad.var != expectedResultLocal
                    || retryIndex < 0
                    || retryIndex >= callIndex) {
                throw new IllegalStateException(
                        method.name + " upload retry control-flow shape changed");
            }
            method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 0));
            call.owner = "dev/gaius/browser/BrowserRenderScheduler";
            call.name = "awaitUploadRetry";
            call.desc = "(Ljava/lang/Object;)Z";
            patched++;
        }
        return patched;
    }

    private static int replaceSpinWaitWithBoundedCancellation(
            MethodNode method, String owner) {
        int replaced = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;) {
            AbstractInsnNode next = instruction.getNext();
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("java/lang/Thread")
                    && call.name.equals("onSpinWait")
                    && call.desc.equals("()V")) {
                InsnList cancel = new InsnList();
                cancel.add(new VarInsnNode(Opcodes.ALOAD, 0));
                cancel.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        owner,
                        "cancel",
                        "()V",
                        false));
                method.instructions.insertBefore(call, cancel);
                method.instructions.remove(call);
                replaced++;
            }
            instruction = next;
        }
        return replaced;
    }

    private static int clearUploadRetryOnReturns(MethodNode method) {
        int patched = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.ARETURN) {
                continue;
            }
            InsnList clear = new InsnList();
            clear.add(new VarInsnNode(Opcodes.ALOAD, 0));
            clear.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserRenderScheduler",
                    "clearUploadRetry",
                    "(Ljava/lang/Object;)V",
                    false));
            method.instructions.insertBefore(instruction, clear);
            patched++;
        }
        return patched;
    }

    private static void patchSectionRenderEmergencyUpload(String jar, Path root)
            throws IOException {
        String owner = "net/minecraft/client/renderer/chunk/"
                + "SectionRenderDispatcher$RenderSection";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(
                node,
                "addSectionBuffersToUberBuffer",
                "(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;"
                        + "Lnet/minecraft/client/renderer/chunk/CompiledSectionMesh;"
                        + "Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;)Z");
        int patched = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals(
                            "net/minecraft/client/renderer/chunk/SectionRenderDispatcher")
                    || !call.name.equals("uploadTerrainBuffersToGpu")
                    || !call.desc.equals("()V")) {
                continue;
            }
            method.instructions.insertBefore(call, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserRenderScheduler",
                    "requestEmergencyUpload",
                    "()V",
                    false));
            patched++;
        }
        requireOne("RenderSection staging-capacity emergency upload", patched);
        write(node, root.resolve(owner + ".class"));
        System.out.println("Guarded current section staging retries with one progress upload");
    }

    private static void patchStagingBuffer(String jar, Path root) throws IOException {
        String owner = "com/mojang/blaze3d/vertex/StagingBuffer$Cpu";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "copyTo",
                "(Lcom/mojang/blaze3d/systems/CommandEncoder;"
                        + "Lcom/mojang/blaze3d/buffers/GpuBuffer;JJJ)V");
        int replaced = replaceCall(
                method,
                Opcodes.INVOKEVIRTUAL,
                "java/nio/ByteBuffer",
                "slice",
                "(II)Ljava/nio/ByteBuffer;",
                Opcodes.INVOKESTATIC,
                JDK_COMPAT,
                "slice",
                "(Ljava/nio/ByteBuffer;II)Ljava/nio/ByteBuffer;");
        requireOne("StagingBuffer ByteBuffer.slice", replaced);
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchTemplateSource(String jar, Path root) throws IOException {
        String owner =
                "net/minecraft/world/level/levelgen/structure/templatesystem/loader/TemplateSource";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "readTextStructure",
                "(Ljava/io/InputStream;)Lnet/minecraft/nbt/CompoundTag;");
        int replaced = replaceCall(
                method,
                Opcodes.INVOKEVIRTUAL,
                "java/io/Reader",
                "readAllAsString",
                "()Ljava/lang/String;",
                Opcodes.INVOKESTATIC,
                JDK_COMPAT,
                "readAll",
                "(Ljava/io/Reader;)Ljava/lang/String;");
        requireOne("TemplateSource Reader.readAllAsString", replaced);
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchRemoteFriendList(String jar, Path root) throws IOException {
        String owner = "net/minecraft/client/gui/screens/social/RemoteFriendListUpdateHandler";
        ClassNode node = read(jar, owner + ".class");
        MethodNode constructor = find(node, "<init>",
                "(Lcom/mojang/authlib/yggdrasil/FriendsService;"
                        + "Lnet/minecraft/client/Minecraft;)V");
        int types = 0;
        int calls = 0;
        for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals("java/util/concurrent/CopyOnWriteArraySet")) {
                type.desc = "java/util/HashSet";
                types++;
            } else if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.owner.equals("java/util/concurrent/CopyOnWriteArraySet")
                    && call.name.equals("<init>")
                    && call.desc.equals("()V")) {
                call.owner = "java/util/HashSet";
                calls++;
            }
        }
        requireOne("RemoteFriendListUpdateHandler set allocation", types);
        requireOne("RemoteFriendListUpdateHandler set constructor", calls);
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchNativeModuleLister(String jar, Path root) throws IOException {
        String owner = "net/minecraft/util/NativeModuleLister";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "tryGetModuleVersion",
                "(Ljava/lang/String;)Ljava/util/Optional;");
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/util/Optional",
                "empty",
                "()Ljava/util/Optional;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 1, 1);
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchMacosUtil(String jar, Path root) throws IOException {
        String owner = "com/mojang/blaze3d/platform/MacosUtil";
        ClassNode node = read(jar, owner + ".class");
        for (String descriptor : new String[] {
                "(Lcom/mojang/blaze3d/platform/Window;)Ljava/util/Optional;",
                "(J)Ljava/util/Optional;"
        }) {
            MethodNode method = find(node, "getNsWindow", descriptor);
            InsnList code = new InsnList();
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/util/Optional",
                    "empty",
                    "()Ljava/util/Optional;",
                    false));
            code.add(new InsnNode(Opcodes.ARETURN));
            replace(method, code, 1, descriptor.equals("(J)Ljava/util/Optional;") ? 2 : 1);
        }
        replaceVoid(find(node, "setWindowColorSpaceForOpenGLBecauseGLFWDoesnt", "(J)V"));
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchVulkanDebug(String jar, Path root) throws IOException {
        String owner = "com/mojang/blaze3d/vulkan/VulkanDebug";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "create",
                "(IZLjava/util/Set;Ljava/util/Set;)Lcom/mojang/blaze3d/vulkan/VulkanDebug;");
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, owner + "$Disabled"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, owner + "$Disabled", "<init>", "()V", false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 2, 4);
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchDetailedMemoryDebug(String jar, Path root) throws IOException {
        String owner = "net/minecraft/client/gui/components/debug/DebugEntryDetailedMemory";
        ClassNode node = read(jar, owner + ".class");
        node.fields.removeIf(field -> field.name.equals("memoryBean")
                && field.desc.equals("Ljava/lang/management/MemoryMXBean;"));

        MethodNode constructor = find(node, "<init>", "()V");
        InsnList constructorCode = new InsnList();
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructorCode.add(new InsnNode(Opcodes.RETURN));
        replace(constructor, constructorCode, 1, 1);

        replaceVoid(find(node, "display",
                "(Lnet/minecraft/client/gui/components/debug/DebugScreenDisplayer;"
                        + "Lnet/minecraft/world/level/Level;"
                        + "Lnet/minecraft/world/level/chunk/LevelChunk;"
                        + "Lnet/minecraft/world/level/chunk/LevelChunk;)V"));
        replaceBoolean(find(node, "isAllowed", "(Z)Z"), false);
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchSystemSpecsDebug(String jar, Path root) throws IOException {
        String owner = "net/minecraft/client/gui/components/debug/DebugEntrySystemSpecs";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "firstLine", "(Ljava/lang/String;)Ljava/lang/String;");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                JDK_COMPAT,
                "firstLine",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 1, 2);
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchFileFixAccess(String jar, Path root) throws IOException {
        String providerOwner = "net/minecraft/util/filefix/access/FileAccessProvider";
        ClassNode provider = read(jar, providerOwner + ".class");
        boolean removedField = provider.fields.removeIf(field -> field.name.equals("baseDirectory")
                && field.desc.equals("Ljava/lang/ScopedValue;"));
        if (!removedField) {
            throw new IllegalStateException("FileAccessProvider ScopedValue field was not found");
        }
        provider.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE,
                "gaius$baseDirectory",
                "Ljava/nio/file/Path;",
                null,
                null));
        MethodNode constructor = find(provider, "<init>", "(I)V");
        InsnList constructorCode = new InsnList();
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
        constructorCode.add(new InsnNode(Opcodes.DUP));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false));
        constructorCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, providerOwner, "accessedFiles", "Ljava/util/List;"));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        constructorCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, providerOwner, "dataVersion", "I"));
        constructorCode.add(new InsnNode(Opcodes.RETURN));
        replace(constructor, constructorCode, 3, 2);

        provider.methods.remove(find(provider, "baseDirectory", "()Ljava/lang/ScopedValue;"));
        MethodNode setter = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "gaius$setBaseDirectory",
                "(Ljava/nio/file/Path;)V",
                null,
                null);
        setter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        setter.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, providerOwner, "gaius$baseDirectory", "Ljava/nio/file/Path;"));
        setter.instructions.add(new InsnNode(Opcodes.RETURN));
        setter.maxStack = 2;
        setter.maxLocals = 2;
        provider.methods.add(setter);
        MethodNode getter = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "gaius$getBaseDirectory",
                "()Ljava/nio/file/Path;",
                null,
                null);
        getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getter.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, providerOwner, "gaius$baseDirectory", "Ljava/nio/file/Path;"));
        getter.instructions.add(new InsnNode(Opcodes.ARETURN));
        getter.maxStack = 1;
        getter.maxLocals = 1;
        provider.methods.add(getter);
        write(provider, root.resolve(providerOwner + ".class"));

        String accessOwner = "net/minecraft/util/filefix/access/FileAccess";
        ClassNode access = read(jar, accessOwner + ".class");
        MethodNode get = find(access, "get", "()Ljava/util/List;");
        boolean patchedGet = false;
        for (AbstractInsnNode instruction = get.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals(providerOwner)
                    || !call.name.equals("baseDirectory")
                    || !call.desc.equals("()Ljava/lang/ScopedValue;")) {
                continue;
            }
            AbstractInsnNode scopedGet = nextOpcode(call);
            AbstractInsnNode pathCast = nextOpcode(scopedGet);
            if (!(scopedGet instanceof MethodInsnNode getCall)
                    || !getCall.owner.equals("java/lang/ScopedValue")
                    || !getCall.name.equals("get")
                    || !getCall.desc.equals("()Ljava/lang/Object;")
                    || !(pathCast instanceof TypeInsnNode cast)
                    || cast.getOpcode() != Opcodes.CHECKCAST
                    || !cast.desc.equals("java/nio/file/Path")) {
                throw new IllegalStateException("FileAccess ScopedValue read shape changed");
            }
            call.name = "gaius$getBaseDirectory";
            call.desc = "()Ljava/nio/file/Path;";
            get.instructions.remove(scopedGet);
            get.instructions.remove(pathCast);
            patchedGet = true;
            break;
        }
        if (!patchedGet) {
            throw new IllegalStateException("FileAccess base-directory read was not found");
        }
        write(access, root.resolve(accessOwner + ".class"));

        String modifyOwner = "net/minecraft/util/filefix/operations/ModifyContent";
        ClassNode modify = read(jar, modifyOwner + ".class");
        MethodNode fix = find(modify, "fix",
                "(Ljava/nio/file/Path;Lnet/minecraft/util/worldupdate/UpgradeProgress;)V");
        InsnList fixCode = new InsnList();
        fixCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fixCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, modifyOwner, "fileAccessProvider",
                "L" + providerOwner + ";"));
        fixCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fixCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                providerOwner,
                "gaius$setBaseDirectory",
                "(Ljava/nio/file/Path;)V",
                false));
        fixCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fixCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, modifyOwner, "fixFunction",
                "Lnet/minecraft/util/filefix/operations/ModifyContent$FixFunction;"));
        fixCode.add(new VarInsnNode(Opcodes.ALOAD, 2));
        fixCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/util/filefix/operations/ModifyContent$FixFunction",
                "run",
                "(Lnet/minecraft/util/worldupdate/UpgradeProgress;)V",
                true));
        fixCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fixCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, modifyOwner, "fileAccessProvider",
                "L" + providerOwner + ";"));
        fixCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, providerOwner, "close", "()V", false));
        fixCode.add(new InsnNode(Opcodes.RETURN));
        replace(fix, fixCode, 2, 3);
        write(modify, root.resolve(modifyOwner + ".class"));
        System.out.println("Replaced Minecraft 26.2 ScopedValue file-fix context");
    }

    private static void patchCopyOnWriteFileSystem(String jar, Path root) throws IOException {
        String owner = "net/minecraft/util/filefix/virtualfilesystem/CopyOnWriteFileSystem";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "hardLinkFiles", "(Ljava/util/List;)V");
        int replacements = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("java/nio/file/Files")
                    && call.name.equals("createLink")
                    && call.desc.equals(
                            "(Ljava/nio/file/Path;Ljava/nio/file/Path;)Ljava/nio/file/Path;")) {
                InsnList prepareCopy = new InsnList();
                prepareCopy.add(new InsnNode(Opcodes.SWAP));
                prepareCopy.add(new InsnNode(Opcodes.ICONST_0));
                prepareCopy.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/nio/file/CopyOption"));
                method.instructions.insertBefore(call, prepareCopy);
                call.name = "copy";
                call.desc = "(Ljava/nio/file/Path;Ljava/nio/file/Path;"
                        + "[Ljava/nio/file/CopyOption;)Ljava/nio/file/Path;";
                replacements++;
            }
        }
        if (replacements == 0) {
            throw new IllegalStateException("CopyOnWriteFileSystem hard-link calls were not found");
        }
        method.maxStack = Math.max(method.maxStack, 3);
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchFileFixerUpperHardLinks(String jar, Path root) throws IOException {
        String owner = "net/minecraft/util/filefix/FileFixerUpper";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "supportsHardLinks", "(Ljava/nio/file/Path;)Z");
        replaceBoolean(method, false);
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchIdentifierResolveAgainst(String jar, Path root) throws IOException {
        String owner = "net/minecraft/resources/Identifier";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "resolveAgainst",
                "(Ljava/nio/file/Path;)Ljava/nio/file/Path;");
        int replaced = replaceCall(
                method,
                Opcodes.INVOKEINTERFACE,
                "java/nio/file/Path",
                "resolve",
                "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;",
                Opcodes.INVOKESTATIC,
                JDK_COMPAT,
                "resolve",
                "(Ljava/nio/file/Path;Ljava/lang/String;[Ljava/lang/String;)"
                        + "Ljava/nio/file/Path;");
        requireOne("Identifier Path.resolve(String, String[])", replaced);
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchCopyOnWriteProvider(String jar, Path root) throws IOException {
        String owner = "net/minecraft/util/filefix/virtualfilesystem/CopyOnWriteFSProvider";
        ClassNode node = read(jar, owner + ".class");
        MethodNode move = find(node, "move",
                "(Ljava/nio/file/Path;Ljava/nio/file/Path;[Ljava/nio/file/CopyOption;)V");
        TypeInsnNode unsupported = null;
        for (AbstractInsnNode instruction = move.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals("java/nio/file/AtomicMoveNotSupportedException")) {
                unsupported = type;
                break;
            }
        }
        if (unsupported == null) {
            throw new IllegalStateException("CopyOnWriteFSProvider atomic-move branch was not found");
        }
        JumpInsnNode branch = null;
        for (AbstractInsnNode instruction = unsupported.getPrevious(); instruction != null;
                instruction = instruction.getPrevious()) {
            if (instruction instanceof JumpInsnNode jump) {
                branch = jump;
                break;
            }
        }
        if (branch == null) {
            throw new IllegalStateException("CopyOnWriteFSProvider atomic-move jump was not found");
        }
        AbstractInsnNode cursor = branch.getNext();
        while (cursor != null && cursor != branch.label) {
            AbstractInsnNode next = cursor.getNext();
            move.instructions.remove(cursor);
            cursor = next;
        }
        move.instructions.set(branch, new InsnNode(Opcodes.POP));

        MethodNode initializer = find(node, "<clinit>", "()V");
        InsnList initializerCode = new InsnList();
        initializerCode.add(new InsnNode(Opcodes.ACONST_NULL));
        initializerCode.add(new FieldInsnNode(
                Opcodes.PUTSTATIC,
                owner,
                "DUMMY_DIRECTORY_VIEW",
                "Ljava/nio/file/attribute/BasicFileAttributeView;"));
        initializerCode.add(new InsnNode(Opcodes.RETURN));
        replace(initializer, initializerCode, 1, 0);
        write(node, root.resolve(owner + ".class"));
    }

    private static int replaceCall(
            MethodNode method,
            int oldOpcode,
            String oldOwner,
            String oldName,
            String oldDescriptor,
            int newOpcode,
            String newOwner,
            String newName,
            String newDescriptor) {
        int replacements = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == oldOpcode
                    && call.owner.equals(oldOwner)
                    && call.name.equals(oldName)
                    && call.desc.equals(oldDescriptor)) {
                call.setOpcode(newOpcode);
                call.owner = newOwner;
                call.name = newName;
                call.desc = newDescriptor;
                call.itf = false;
                replacements++;
            }
        }
        return replacements;
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getNext();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getNext();
        }
        return cursor;
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getPrevious();
        }
        return cursor;
    }

    private static void replaceVoid(MethodNode method) {
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 0, method.maxLocals);
    }

    private static void replaceBoolean(MethodNode method, boolean value) {
        InsnList code = new InsnList();
        code.add(new InsnNode(value ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(method, code, 1, method.maxLocals);
    }

    private static void replace(
            MethodNode method, InsnList code, int maxStack, int maxLocals) {
        method.access &= ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
        method.instructions = code;
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        method.maxStack = maxStack;
        method.maxLocals = maxLocals;
    }

    private static void requireOne(String target, int count) {
        if (count != 1) {
            throw new IllegalStateException(target + " changed: " + count);
        }
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        node.name + "." + name + descriptor + " was not found"));
    }

    private static ClassNode read(String jarPath, String entryName) throws IOException {
        byte[] bytes;
        try (ZipFile jar = new ZipFile(jarPath)) {
            var entry = jar.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException(entryName + " was not found");
            }
            try (var stream = jar.getInputStream(entry)) {
                bytes = stream.readAllBytes();
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static void write(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
