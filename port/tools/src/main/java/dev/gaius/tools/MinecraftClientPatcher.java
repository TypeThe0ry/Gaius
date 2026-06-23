package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Removes desktop-only diagnostics from the official browser client graph. */
public final class MinecraftClientPatcher {
    private MinecraftClientPatcher() {
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args[1]);
        patchNativeModuleLister(
                args[0], root.resolve("net/minecraft/util/NativeModuleLister.class"));
        patchJvmProfiler(
                args[0], root.resolve("net/minecraft/util/profiling/jfr/JvmProfiler.class"));
        patchEventLoopGroupHolder(
                args[0], root.resolve("net/minecraft/server/network/EventLoopGroupHolder.class"));
        patchGlx(args[0], root.resolve("com/mojang/blaze3d/platform/GLX.class"));
        patchTracyZoneFiller(
                args[0], root.resolve("net/minecraft/util/profiling/TracyZoneFiller.class"));
        patchMacosUtil(
                args[0], root.resolve("com/mojang/blaze3d/platform/MacosUtil.class"));
        patchInputConstants(
                args[0], root.resolve("com/mojang/blaze3d/platform/InputConstants.class"));
        patchMemoryDebug(args[0], root.resolve(
                "net/minecraft/client/gui/components/debug/"
                        + "DebugEntryMemory$AllocationRateCalculator.class"));
        patchMinecraft(args[0], root.resolve("net/minecraft/client/Minecraft.class"));
        patchFreeTypeUtil(args[0], root.resolve(
                "net/minecraft/client/gui/font/providers/FreeTypeUtil.class"));
        patchDebugMemoryUntracker(args[0], root.resolve(
                "com/mojang/blaze3d/platform/DebugMemoryUntracker.class"));
        patchMinecraftServer(args[0], root.resolve(
                "net/minecraft/server/MinecraftServer.class"));
        patchChaseClient(args[0], root.resolve(
                "net/minecraft/server/chase/ChaseClient.class"));
        patchLanServerPinger(args[0], root.resolve(
                "net/minecraft/client/server/LanServerPinger.class"));
        patchHttpUtil(args[0], root.resolve("net/minecraft/util/HttpUtil.class"));
        patchLanServerDetector(args[0], root.resolve(
                "net/minecraft/client/server/LanServerDetection$LanServerDetector.class"));
        patchPackWatcher(args[0], root.resolve(
                "net/minecraft/client/gui/screens/packs/PackSelectionScreen$Watcher.class"));
        patchChaseServer(args[0], root.resolve(
                "net/minecraft/server/chase/ChaseServer.class"));
        patchOpenUri(args[0], root.resolve("net/minecraft/util/Util$OS.class"));
        patchRealmsNetwork(args[0], root);
        generateSoundApiStubs(root);
    }

    private static void patchNativeModuleLister(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/util/NativeModuleLister.class");
        boolean found = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("listModules") && method.desc.equals("()Ljava/util/List;")) {
                InsnList code = new InsnList();
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList",
                        "()Ljava/util/List;", false));
                code.add(new InsnNode(Opcodes.ARETURN));
                method.instructions = code;
                method.tryCatchBlocks.clear();
                method.maxStack = 1;
                method.maxLocals = 0;
                found = true;
            }
        }
        if (!found) {
            throw new IllegalStateException("NativeModuleLister.listModules was not found");
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static void patchJvmProfiler(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/util/profiling/jfr/JvmProfiler.class");
        boolean found = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                code.add(new TypeInsnNode(
                        Opcodes.NEW,
                        "net/minecraft/util/profiling/jfr/JvmProfiler$NoOpProfiler"));
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL,
                        "net/minecraft/util/profiling/jfr/JvmProfiler$NoOpProfiler",
                        "<init>",
                        "()V",
                        false));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "net/minecraft/util/profiling/jfr/JvmProfiler",
                        "INSTANCE",
                        "Lnet/minecraft/util/profiling/jfr/JvmProfiler;"));
                code.add(new InsnNode(Opcodes.RETURN));
                method.instructions = code;
                method.tryCatchBlocks.clear();
                method.maxStack = 2;
                method.maxLocals = 0;
                found = true;
            }
        }
        if (!found) {
            throw new IllegalStateException("JvmProfiler.<clinit> was not found");
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static void patchEventLoopGroupHolder(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/network/EventLoopGroupHolder.class");
        boolean clinitFound = false;
        boolean remoteFound = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                code.add(new TypeInsnNode(
                        Opcodes.NEW,
                        "net/minecraft/server/network/EventLoopGroupHolder$4"));
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new LdcInsnNode("Local"));
                code.add(new LdcInsnNode(Type.getObjectType("io/netty/channel/local/LocalChannel")));
                code.add(new LdcInsnNode(
                        Type.getObjectType("io/netty/channel/local/LocalServerChannel")));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL,
                        "net/minecraft/server/network/EventLoopGroupHolder$4",
                        "<init>",
                        "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Class;)V",
                        false));
                code.add(new VarInsnNode(Opcodes.ASTORE, 0));
                for (String field : new String[] {"NIO", "EPOLL", "KQUEUE", "LOCAL"}) {
                    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    code.add(new FieldInsnNode(
                            Opcodes.PUTSTATIC,
                            "net/minecraft/server/network/EventLoopGroupHolder",
                            field,
                            "Lnet/minecraft/server/network/EventLoopGroupHolder;"));
                }
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 5, 1);
                clinitFound = true;
            } else if (method.name.equals("remote")
                    && method.desc.equals("(Z)Lnet/minecraft/server/network/EventLoopGroupHolder;")) {
                InsnList code = new InsnList();
                code.add(new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        "net/minecraft/server/network/EventLoopGroupHolder",
                        "LOCAL",
                        "Lnet/minecraft/server/network/EventLoopGroupHolder;"));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1, 1);
                remoteFound = true;
            }
        }
        if (!clinitFound || !remoteFound) {
            throw new IllegalStateException("EventLoopGroupHolder patch points were not found");
        }
        write(node, output);
    }

    private static void patchGlx(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/platform/GLX.class");
        boolean found = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("_getCpuInfo") && method.desc.equals("()Ljava/lang/String;")) {
                InsnList code = new InsnList();
                code.add(new LdcInsnNode("Browser runtime"));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1, 0);
                found = true;
            }
        }
        if (!found) {
            throw new IllegalStateException("GLX._getCpuInfo was not found");
        }
        write(node, output);
    }

    private static void patchTracyZoneFiller(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/util/profiling/TracyZoneFiller.class");
        boolean pushFound = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("push") && method.desc.equals("(Ljava/lang/String;)V")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                code.add(new LdcInsnNode(""));
                code.add(new LdcInsnNode(""));
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "com/mojang/jtracy/TracyClient",
                        "beginZone",
                        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)"
                                + "Lcom/mojang/jtracy/Zone;",
                        false));
                code.add(new VarInsnNode(Opcodes.ASTORE, 2));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new FieldInsnNode(
                        Opcodes.GETFIELD,
                        "net/minecraft/util/profiling/TracyZoneFiller",
                        "activeZones",
                        "Ljava/util/List;"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 2));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "java/util/List",
                        "add",
                        "(Ljava/lang/Object;)Z",
                        true));
                code.add(new InsnNode(Opcodes.POP));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 4, 3);
                pushFound = true;
            } else if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "com/mojang/logging/LogUtils",
                        "getLogger",
                        "()Lorg/slf4j/Logger;",
                        false));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "net/minecraft/util/profiling/TracyZoneFiller",
                        "LOGGER",
                        "Lorg/slf4j/Logger;"));
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "net/minecraft/util/profiling/TracyZoneFiller",
                        "STACK_WALKER",
                        "Ljava/lang/StackWalker;"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 1, 0);
            }
        }
        if (!pushFound) {
            throw new IllegalStateException("TracyZoneFiller.push was not found");
        }
        write(node, output);
    }

    private static void patchMacosUtil(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/platform/MacosUtil.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "com/mojang/blaze3d/platform/MacosUtil",
                        "IS_MACOS",
                        "Z"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 1, 0);
            } else if ((method.name.equals("exitNativeFullscreen")
                    || method.name.equals("clearResizableBit")
                    || method.name.equals("loadIcon"))
                    && (method.access & Opcodes.ACC_STATIC) != 0) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, method.maxLocals);
            }
        }
        write(node, output);
    }

    private static void patchInputConstants(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/platform/InputConstants.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "com/mojang/blaze3d/platform/InputConstants",
                        "GLFW_RAW_MOUSE_MOTION_SUPPORTED",
                        "Ljava/lang/invoke/MethodHandle;"));
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "com/mojang/blaze3d/platform/InputConstants",
                        "GLFW_RAW_MOUSE_MOTION",
                        "I"));
                code.add(new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        "com/mojang/blaze3d/platform/InputConstants$Type",
                        "KEYSYM",
                        "Lcom/mojang/blaze3d/platform/InputConstants$Type;"));
                code.add(new InsnNode(Opcodes.ICONST_M1));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "com/mojang/blaze3d/platform/InputConstants$Type",
                        "getOrCreate",
                        "(I)Lcom/mojang/blaze3d/platform/InputConstants$Key;",
                        false));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "com/mojang/blaze3d/platform/InputConstants",
                        "UNKNOWN",
                        "Lcom/mojang/blaze3d/platform/InputConstants$Key;"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 2, 0);
            } else if (method.name.equals("isRawMouseInputSupported")
                    && method.desc.equals("()Z")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new InsnNode(Opcodes.IRETURN));
                replace(method, code, 1, 0);
            }
        }
        write(node, output);
    }

    private static void patchMemoryDebug(String jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/gui/components/debug/"
                        + "DebugEntryMemory$AllocationRateCalculator.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/util/Collections",
                        "emptyList",
                        "()Ljava/util/List;",
                        false));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "net/minecraft/client/gui/components/debug/"
                                + "DebugEntryMemory$AllocationRateCalculator",
                        "GC_MBEANS",
                        "Ljava/util/List;"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 1, 0);
            }
        }
        write(node, output);
    }

    private static void patchMinecraft(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/Minecraft.class");
        boolean found = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("lambda$fillUptime$40")
                    && method.desc.equals("()Ljava/lang/String;")) {
                InsnList code = new InsnList();
                code.add(new LdcInsnNode("Browser runtime"));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1, 0);
                found = true;
            }
        }
        if (!found) {
            throw new IllegalStateException("Minecraft uptime lambda was not found");
        }
        write(node, output);
    }

    private static void patchFreeTypeUtil(String jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/gui/font/providers/FreeTypeUtil.class");
        boolean found = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("destroy") && method.desc.equals("()V")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, 0);
                found = true;
            }
        }
        if (!found) {
            throw new IllegalStateException("FreeTypeUtil.destroy was not found");
        }
        write(node, output);
    }

    private static void patchDebugMemoryUntracker(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "com/mojang/blaze3d/platform/DebugMemoryUntracker.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new FieldInsnNode(Opcodes.PUTSTATIC,
                        "com/mojang/blaze3d/platform/DebugMemoryUntracker",
                        "UNTRACK", "Ljava/lang/invoke/MethodHandle;"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 1, 0);
            } else if (method.name.equals("untrack")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, method.maxLocals);
            }
        }
        write(node, output);
    }

    private static void patchMinecraftServer(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/MinecraftServer.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("dumpThreads")
                    && method.desc.equals("(Ljava/nio/file/Path;)V")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, 2);
            }
        }
        write(node, output);
    }

    private static void patchChaseClient(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/chase/ChaseClient.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("start") || method.name.equals("stop")
                    || method.name.equals("run")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, method.maxLocals);
            }
        }
        write(node, output);
    }

    private static void patchLanServerPinger(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/client/server/LanServerPinger.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>")
                    && method.desc.equals("(Ljava/lang/String;Ljava/lang/String;)V")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new LdcInsnNode("LanServerPinger-browser"));
                code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                        "java/lang/Thread", "<init>", "(Ljava/lang/String;)V", false));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new FieldInsnNode(Opcodes.PUTFIELD,
                        "net/minecraft/client/server/LanServerPinger",
                        "isRunning", "Z"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                code.add(new FieldInsnNode(Opcodes.PUTFIELD,
                        "net/minecraft/client/server/LanServerPinger",
                        "motd", "Ljava/lang/String;"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new VarInsnNode(Opcodes.ALOAD, 2));
                code.add(new FieldInsnNode(Opcodes.PUTFIELD,
                        "net/minecraft/client/server/LanServerPinger",
                        "serverAddress", "Ljava/lang/String;"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new FieldInsnNode(Opcodes.PUTFIELD,
                        "net/minecraft/client/server/LanServerPinger",
                        "socket", "Ljava/net/DatagramSocket;"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 2, 3);
            } else if (method.name.equals("run")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, 1);
            }
        }
        write(node, output);
    }

    private static void patchHttpUtil(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/util/HttpUtil.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("getAvailablePort") && method.desc.equals("()I")) {
                InsnList code = new InsnList();
                code.add(new IntInsnNode(Opcodes.SIPUSH, 25564));
                code.add(new InsnNode(Opcodes.IRETURN));
                replace(method, code, 1, 0);
            } else if (method.name.equals("isPortAvailable") && method.desc.equals("(I)Z")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ICONST_1));
                code.add(new InsnNode(Opcodes.IRETURN));
                replace(method, code, 1, 1);
            }
        }
        write(node, output);
    }

    private static void patchLanServerDetector(String jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/server/LanServerDetection$LanServerDetector.class");
        String owner = "net/minecraft/client/server/LanServerDetection$LanServerDetector";
        for (MethodNode method : node.methods) {
            if (method.name.equals("<init>")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL, "java/lang/Thread", "<init>", "()V", false));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                code.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, owner, "serverList",
                        "Lnet/minecraft/client/server/LanServerDetection$LanServerList;"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, owner, "pingGroup", "Ljava/net/InetAddress;"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, owner, "socket", "Ljava/net/MulticastSocket;"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 2, 2);
            } else if (method.name.equals("run") && method.desc.equals("()V")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, 1);
            }
        }
        write(node, output);
    }

    private static void patchPackWatcher(String jar, Path output) throws IOException {
        ClassNode node = read(jar,
                "net/minecraft/client/gui/screens/packs/PackSelectionScreen$Watcher.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("create")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1, method.maxLocals);
            } else if (method.name.equals("pollForChanges")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new InsnNode(Opcodes.IRETURN));
                replace(method, code, 1, method.maxLocals);
            } else if (method.name.equals("close") || method.name.equals("watchDir")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, method.maxLocals);
            }
        }
        write(node, output);
    }

    private static void patchChaseServer(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/server/chase/ChaseServer.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("start") || method.name.equals("stop")
                    || method.name.equals("runSender") || method.name.equals("runAcceptor")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, method.maxLocals);
            }
        }
        write(node, output);
    }

    private static void patchOpenUri(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "net/minecraft/util/Util$OS.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("openUri") && method.desc.equals("(Ljava/net/URI;)V")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0, method.maxLocals);
            }
        }
        write(node, output);
    }

    private static void patchRealmsNetwork(String jar, Path root) throws IOException {
        ClassNode download = read(jar, "com/mojang/realmsclient/client/FileDownload.class");
        for (MethodNode method : download.methods) {
            if (method.name.equals("contentLength")
                    && method.desc.equals("(Ljava/lang/String;)Ljava/util/OptionalLong;")) {
                InsnList code = new InsnList();
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC, "java/util/OptionalLong", "empty",
                        "()Ljava/util/OptionalLong;", false));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1, method.maxLocals);
            } else if (method.name.equals("download")
                    && method.desc.startsWith("(Lcom/mojang/realmsclient/dto/WorldDownload;")) {
                InsnList code = new InsnList();
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new InsnNode(Opcodes.ICONST_1));
                code.add(new FieldInsnNode(
                        Opcodes.PUTFIELD,
                        "com/mojang/realmsclient/client/FileDownload",
                        "error",
                        "Z"));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new InsnNode(Opcodes.ICONST_1));
                code.add(new FieldInsnNode(
                        Opcodes.PUTFIELD,
                        "com/mojang/realmsclient/client/FileDownload",
                        "finished",
                        "Z"));
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 2, method.maxLocals);
            }
        }
        write(download, root.resolve("com/mojang/realmsclient/client/FileDownload.class"));

        ClassNode upload = read(jar, "com/mojang/realmsclient/client/FileUpload.class");
        for (MethodNode method : upload.methods) {
            if (method.name.equals("startUpload")) {
                InsnList code = new InsnList();
                code.add(new TypeInsnNode(Opcodes.NEW, "java/lang/UnsupportedOperationException"));
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new LdcInsnNode("Realms upload is unavailable in the browser"));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL,
                        "java/lang/UnsupportedOperationException",
                        "<init>",
                        "(Ljava/lang/String;)V",
                        false));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/util/concurrent/CompletableFuture",
                        "failedFuture",
                        "(Ljava/lang/Throwable;)Ljava/util/concurrent/CompletableFuture;",
                        false));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 3, method.maxLocals);
            }
        }
        write(upload, root.resolve("com/mojang/realmsclient/client/FileUpload.class"));

        ClassNode ping = read(jar, "com/mojang/realmsclient/client/Ping.class");
        for (MethodNode method : ping.methods) {
            if ((method.name.equals("ping") && method.access == (method.access | Opcodes.ACC_STATIC))
                    || method.name.equals("pingAllRegions")) {
                if (Type.getReturnType(method.desc).getDescriptor().equals("Ljava/util/List;")) {
                    InsnList code = new InsnList();
                    code.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList",
                            "()Ljava/util/List;", false));
                    code.add(new InsnNode(Opcodes.ARETURN));
                    replace(method, code, 1, method.maxLocals);
                }
            }
        }
        write(ping, root.resolve("com/mojang/realmsclient/client/Ping.class"));
    }

    private static void generateSoundApiStubs(Path root) throws IOException {
        ClassWriter encoding = new ClassWriter(0);
        encoding.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "javax/sound/sampled/AudioFormat$Encoding", null,
                "java/lang/Object", null);
        encoding.visitField(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "PCM_SIGNED", "Ljavax/sound/sampled/AudioFormat$Encoding;", null, null).visitEnd();
        encoding.visitField(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "PCM_UNSIGNED", "Ljavax/sound/sampled/AudioFormat$Encoding;", null, null).visitEnd();
        var constructor = encoding.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        var init = encoding.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        for (String field : new String[] {"PCM_SIGNED", "PCM_UNSIGNED"}) {
            init.visitTypeInsn(Opcodes.NEW, "javax/sound/sampled/AudioFormat$Encoding");
            init.visitInsn(Opcodes.DUP);
            init.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    "javax/sound/sampled/AudioFormat$Encoding",
                    "<init>",
                    "()V",
                    false);
            init.visitFieldInsn(
                    Opcodes.PUTSTATIC,
                    "javax/sound/sampled/AudioFormat$Encoding",
                    field,
                    "Ljavax/sound/sampled/AudioFormat$Encoding;");
        }
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(2, 0);
        init.visitEnd();
        encoding.visitEnd();
        writeBytes(root.resolve("javax/sound/sampled/AudioFormat$Encoding.class"),
                encoding.toByteArray());

        ClassWriter format = new ClassWriter(0);
        String owner = "javax/sound/sampled/AudioFormat";
        format.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                owner, null, "java/lang/Object", null);
        format.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "encoding",
                "Ljavax/sound/sampled/AudioFormat$Encoding;", null, null).visitEnd();
        format.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "sampleRate",
                "F", null, null).visitEnd();
        format.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "sampleSize",
                "I", null, null).visitEnd();
        format.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "channels",
                "I", null, null).visitEnd();
        var formatConstructor = format.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "(FIIZZ)V", null, null);
        formatConstructor.visitCode();
        formatConstructor.visitVarInsn(Opcodes.ALOAD, 0);
        formatConstructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        formatConstructor.visitVarInsn(Opcodes.ALOAD, 0);
        formatConstructor.visitVarInsn(Opcodes.ILOAD, 4);
        org.objectweb.asm.Label unsigned = new org.objectweb.asm.Label();
        org.objectweb.asm.Label encodingDone = new org.objectweb.asm.Label();
        formatConstructor.visitJumpInsn(Opcodes.IFEQ, unsigned);
        formatConstructor.visitFieldInsn(
                Opcodes.GETSTATIC,
                "javax/sound/sampled/AudioFormat$Encoding",
                "PCM_SIGNED",
                "Ljavax/sound/sampled/AudioFormat$Encoding;");
        formatConstructor.visitJumpInsn(Opcodes.GOTO, encodingDone);
        formatConstructor.visitLabel(unsigned);
        formatConstructor.visitFieldInsn(
                Opcodes.GETSTATIC,
                "javax/sound/sampled/AudioFormat$Encoding",
                "PCM_UNSIGNED",
                "Ljavax/sound/sampled/AudioFormat$Encoding;");
        formatConstructor.visitLabel(encodingDone);
        formatConstructor.visitFieldInsn(
                Opcodes.PUTFIELD, owner, "encoding",
                "Ljavax/sound/sampled/AudioFormat$Encoding;");
        putConstructorField(formatConstructor, owner, "sampleRate", "F", Opcodes.FLOAD, 1);
        putConstructorField(formatConstructor, owner, "sampleSize", "I", Opcodes.ILOAD, 2);
        putConstructorField(formatConstructor, owner, "channels", "I", Opcodes.ILOAD, 3);
        formatConstructor.visitInsn(Opcodes.RETURN);
        formatConstructor.visitMaxs(3, 6);
        formatConstructor.visitEnd();
        getter(format, owner, "getEncoding",
                "()Ljavax/sound/sampled/AudioFormat$Encoding;",
                "encoding", "Ljavax/sound/sampled/AudioFormat$Encoding;", Opcodes.ARETURN);
        getter(format, owner, "getSampleRate", "()F", "sampleRate", "F", Opcodes.FRETURN);
        getter(format, owner, "getSampleSizeInBits", "()I", "sampleSize", "I", Opcodes.IRETURN);
        getter(format, owner, "getChannels", "()I", "channels", "I", Opcodes.IRETURN);
        format.visitEnd();
        writeBytes(root.resolve("javax/sound/sampled/AudioFormat.class"), format.toByteArray());
    }

    private static void putConstructorField(
            org.objectweb.asm.MethodVisitor method, String owner,
            String field, String descriptor, int loadOpcode, int local) {
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(loadOpcode, local);
        method.visitFieldInsn(Opcodes.PUTFIELD, owner, field, descriptor);
    }

    private static void getter(
            ClassWriter writer, String owner, String name, String descriptor,
            String field, String fieldDescriptor, int returnOpcode) {
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, owner, field, fieldDescriptor);
        method.visitInsn(returnOpcode);
        method.visitMaxs(1, 1);
        method.visitEnd();
    }

    private static void writeBytes(Path output, byte[] bytes) throws IOException {
        Files.createDirectories(output.getParent());
        Files.write(output, bytes);
    }

    private static void replace(MethodNode method, InsnList code, int maxStack, int maxLocals) {
        method.instructions = code;
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.maxStack = maxStack;
        method.maxLocals = maxLocals;
    }

    private static ClassNode read(String jarPath, String entryName) throws IOException {
        byte[] bytes;
        try (ZipFile jar = new ZipFile(jarPath)) {
            try (var stream = jar.getInputStream(jar.getEntry(entryName))) {
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
