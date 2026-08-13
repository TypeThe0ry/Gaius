package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Replaces LWJGL's JVM Unsafe memory primitives with BrowserMemory. */
public final class LwjglMemoryPatcher {
    private static final String MEMORY_UTIL = "org/lwjgl/system/MemoryUtil";
    private static final String BROWSER_MEMORY = "org/lwjgl/system/BrowserMemory";
    private static final String BROWSER_MEMORY_ALLOCATOR =
            "org/lwjgl/system/BrowserMemoryAllocator";
    private static final Map<String, String> DELEGATES = delegates();

    private LwjglMemoryPatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: LwjglMemoryPatcher INPUT_JAR OUTPUT_ROOT");
        }
        Path root = Path.of(args[1]);
        patchMemoryUtil(args[0], root.resolve("org/lwjgl/system/MemoryUtil.class"));
        patchMemoryUtilTunables(
                args[0], root.resolve("org/lwjgl/system/MemoryUtilTunables.class"));
        patchPointer(args[0], root.resolve("org/lwjgl/system/Pointer$Default.class"));
        patchDecoder(args[0], root.resolve("org/lwjgl/system/MultiReleaseTextDecoding.class"));
        patchLibrary(args[0], root.resolve("org/lwjgl/system/Library.class"));
        patchVersion(args[0], root.resolve("org/lwjgl/Version.class"));
        patchCallback(args[0], root.resolve("org/lwjgl/system/Callback.class"));
        patchCallbackInterface(args[0], root.resolve("org/lwjgl/system/CallbackI.class"));
        patchPlatform(args[0], root.resolve("org/lwjgl/system/Platform.class"));
        patchPlatformArchitecture(
                args[0], root.resolve("org/lwjgl/system/Platform$Architecture.class"));
        patchMemCopy(args[0], root.resolve("org/lwjgl/system/MultiReleaseMemCopy.class"));
    }

    private static void patchMemoryUtil(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "org/lwjgl/system/MemoryUtil.class");
        int replaced = 0;
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                replace(method, memoryUtilInitializer(), 2);
                replaced++;
                continue;
            }
            if (method.name.equals("getUnsafeInstance")
                    && method.desc.equals("()Lsun/misc/Unsafe;")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1);
                replaced++;
                continue;
            }
            if (method.name.equals("memGlobalRefToObject")
                    && method.desc.equals("(J)Ljava/lang/Object;")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1);
                replaced++;
                continue;
            }
            if (method.name.equals("getAllocator")
                    && method.desc.equals("(Z)Lorg/lwjgl/system/MemoryUtil$MemoryAllocator;")) {
                InsnList code = new InsnList();
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        BROWSER_MEMORY_ALLOCATOR,
                        "instance",
                        "()Lorg/lwjgl/system/MemoryUtil$MemoryAllocator;",
                        false));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1);
                replaced++;
                continue;
            }
            if (isUnsafeOffsetHelper(method)) {
                replaceZeroReturn(method);
                replaced++;
                continue;
            }
            if (method.name.equals("memFree")
                    && method.desc.startsWith("(Ljava/nio/")
                    && method.desc.endsWith("Buffer;)V")) {
                replaceBufferFree(method);
                replaced++;
                continue;
            }
            if (method.name.equals("memAlignedFree")
                    && method.desc.equals("(Ljava/nio/ByteBuffer;)V")) {
                replaceBufferFree(method);
                replaced++;
                continue;
            }
            if (method.name.equals("memAddress0")
                    && hasNioBufferArguments(method, 1)
                    && Type.getReturnType(method.desc).getSort() == Type.LONG) {
                replaceBufferAddress(method, "address0");
                replaced++;
                continue;
            }
            if ((method.name.equals("memAddress") || method.name.equals("memAddressSafe"))
                    && hasNioBufferArguments(method, 1)
                    && Type.getArgumentTypes(method.desc).length == 1
                    && Type.getReturnType(method.desc).getSort() == Type.LONG) {
                replaceBufferAddress(
                        method, method.name.equals("memAddressSafe") ? "addressSafe" : "address");
                replaced++;
                continue;
            }
            if (method.name.equals("memAddress")
                    && hasNioBufferArguments(method, 1)
                    && hasIntOffsetArgument(method)
                    && Type.getReturnType(method.desc).getSort() == Type.LONG) {
                replaceBufferAddressAt(method);
                replaced++;
                continue;
            }
            if (method.name.equals("memDuplicate")
                    && hasNioBufferArguments(method, 1)
                    && Type.getArgumentTypes(method.desc).length == 1) {
                replaceBufferDuplicate(method);
                replaced++;
                continue;
            }
            if (method.name.startsWith("wrapBuffer")
                    && method.desc.startsWith("(JI)Ljava/nio/")
                    && method.desc.endsWith("Buffer;")) {
                replaceBufferWrap(method);
                replaced++;
                continue;
            }
            String target = DELEGATES.get(method.name + method.desc);
            if (target != null) {
                replaceWithDelegate(method, target);
                replaced++;
            }
        }
        if (replaced < 20) {
            throw new IllegalStateException("Too few MemoryUtil methods replaced: " + replaced);
        }
        write(node, output);
    }

    private static void patchMemoryUtilTunables(String jar, Path output) throws IOException {
        String entry = "org/lwjgl/system/MemoryUtilTunables.class";
        if (!contains(jar, entry)) {
            return;
        }
        ClassNode node = read(jar, entry);
        MethodNode initializer = node.methods.stream()
                .filter(method -> method.name.equals("<clinit>") && method.desc.equals("()V"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "MemoryUtilTunables initializer not found"));
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(0x01010101));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, "org/lwjgl/system/MemoryUtilTunables",
                "FILL_PATTERN_32", "I"));
        code.add(new LdcInsnNode(0x0101010101010101L));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, "org/lwjgl/system/MemoryUtilTunables",
                "FILL_PATTERN_64", "J"));
        for (String field : new String[] {
                "BASE_OFFSET_BYTE", "BASE_OFFSET_SHORT", "BASE_OFFSET_INT",
                "BASE_OFFSET_LONG", "BASE_OFFSET_FLOAT", "BASE_OFFSET_DOUBLE"
        }) {
            code.add(new InsnNode(Opcodes.LCONST_0));
            code.add(new FieldInsnNode(
                    Opcodes.PUTSTATIC, "org/lwjgl/system/MemoryUtilTunables", field, "J"));
        }
        code.add(new InsnNode(Opcodes.RETURN));
        replace(initializer, code, 2);
        write(node, output);
    }

    private static void replaceBufferFree(MethodNode method) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                BROWSER_MEMORY,
                "free",
                "(Ljava/nio/Buffer;)V",
                false));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 1);
    }

    private static void replaceBufferAddress(MethodNode method, String target) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                BROWSER_MEMORY,
                target,
                "(Ljava/nio/Buffer;)J",
                false));
        code.add(new InsnNode(Opcodes.LRETURN));
        replace(method, code, 2);
    }

    private static void replaceBufferAddressAt(MethodNode method) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                BROWSER_MEMORY,
                "addressAt",
                "(Ljava/nio/Buffer;I)J",
                false));
        code.add(new InsnNode(Opcodes.LRETURN));
        replace(method, code, 3);
    }

    private static void replaceBufferDuplicate(MethodNode method) {
        Type argument = Type.getArgumentTypes(method.desc)[0];
        Type result = Type.getReturnType(method.desc);
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                BROWSER_MEMORY,
                "duplicate",
                "(" + argument.getDescriptor() + ")" + result.getDescriptor(),
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 2);
    }

    private static boolean hasNioBufferArguments(MethodNode method, int expected) {
        Type[] arguments = Type.getArgumentTypes(method.desc);
        return arguments.length >= expected && isNioBuffer(arguments[0]);
    }

    private static boolean hasIntOffsetArgument(MethodNode method) {
        Type[] arguments = Type.getArgumentTypes(method.desc);
        return arguments.length == 2 && arguments[1].getSort() == Type.INT;
    }

    private static boolean isNioBuffer(Type type) {
        return type.getSort() == Type.OBJECT
                && type.getInternalName().startsWith("java/nio/")
                && type.getInternalName().endsWith("Buffer");
    }

    private static void replaceBufferWrap(MethodNode method) {
        String bufferName = method.name.substring("wrapBuffer".length());
        int kind = switch (bufferName) {
            case "Short" -> 1;
            case "Char" -> 2;
            case "Int" -> 3;
            case "Long" -> 4;
            case "Float" -> 5;
            case "Double" -> 6;
            default -> 0;
        };
        Type result = Type.getReturnType(method.desc);
        InsnList code = new InsnList();
        code.add(new IntInsnNode(Opcodes.BIPUSH, kind));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                BROWSER_MEMORY,
                "bufferClass",
                "(I)Ljava/lang/Class;",
                false));
        code.add(new VarInsnNode(Opcodes.LLOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                BROWSER_MEMORY,
                "wrap",
                "(Ljava/lang/Class;JI)Ljava/nio/Buffer;",
                false));
        code.add(new org.objectweb.asm.tree.TypeInsnNode(
                Opcodes.CHECKCAST, result.getInternalName()));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 4);
    }

    private static boolean isUnsafeOffsetHelper(MethodNode method) {
        if (method.name.equals("getFieldOffset")
                || method.name.equals("getFieldOffsetInt")
                || method.name.equals("getFieldOffsetObject")
                || method.name.equals("getAddressOffset")
                || method.name.equals("getMarkOffset")
                || method.name.equals("getPositionOffset")
                || method.name.equals("getLimitOffset")
                || method.name.equals("getCapacityOffset")) {
            return Type.getReturnType(method.desc).getSort() == Type.LONG;
        }
        return method.name.startsWith("lambda$get")
                && Type.getReturnType(method.desc).getSort() == Type.BOOLEAN;
    }

    private static void replaceZeroReturn(MethodNode method) {
        InsnList code = new InsnList();
        Type result = Type.getReturnType(method.desc);
        if (result.getSort() == Type.LONG) {
            code.add(new InsnNode(Opcodes.LCONST_0));
            code.add(new InsnNode(Opcodes.LRETURN));
        } else if (result.getSort() == Type.BOOLEAN) {
            code.add(new InsnNode(Opcodes.ICONST_0));
            code.add(new InsnNode(Opcodes.IRETURN));
        } else {
            throw new IllegalArgumentException("Unexpected Unsafe helper return type: " + result);
        }
        replace(method, code, 2);
    }

    private static void patchPointer(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "org/lwjgl/system/Pointer$Default.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("<clinit>")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC, "org/lwjgl/system/Pointer$Default",
                        "UNSAFE", "Lsun/misc/Unsafe;"));
                for (String field : new String[] {
                        "ADDRESS", "BUFFER_CONTAINER", "BUFFER_MARK", "BUFFER_POSITION",
                        "BUFFER_LIMIT", "BUFFER_CAPACITY"
                }) {
                    code.add(new InsnNode(Opcodes.LCONST_0));
                    code.add(new FieldInsnNode(
                            Opcodes.PUTSTATIC, "org/lwjgl/system/Pointer$Default", field, "J"));
                }
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 2);
            }
        }
        write(node, output);
    }

    private static void patchDecoder(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "org/lwjgl/system/MultiReleaseTextDecoding.class");
        for (MethodNode method : node.methods) {
            if (method.name.equals("decodeUTF8") && method.desc.equals("(JI)Ljava/lang/String;")) {
                replaceWithDelegate(method, "decodeUtf8");
            }
        }
        write(node, output);
    }

    private static void patchLibrary(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "org/lwjgl/system/Library.class");
        int replaced = 0;
        for (MethodNode method : node.methods) {
            Type result = Type.getReturnType(method.desc);
            if (method.name.startsWith("loadSystem") && result.getSort() == Type.VOID) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.RETURN));
                replace(method, code, 0);
                replaced++;
            } else if (method.name.startsWith("loadNative")
                    && result.getDescriptor().equals("Lorg/lwjgl/system/SharedLibrary;")) {
                InsnList code = new InsnList();
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "org/lwjgl/system/BrowserSharedLibrary",
                        "open",
                        "()Lorg/lwjgl/system/SharedLibrary;",
                        false));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1);
                replaced++;
            }
        }
        if (replaced < 10) {
            throw new IllegalStateException("Too few Library methods replaced: " + replaced);
        }
        write(node, output);
    }

    private static void patchVersion(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "org/lwjgl/Version.class");
        boolean found = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("findImplementationFromManifest")
                    && method.desc.equals("()Ljava/lang/String;")) {
                InsnList code = new InsnList();
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new InsnNode(Opcodes.ARETURN));
                replace(method, code, 1);
                found = true;
            }
        }
        if (!found) {
            throw new IllegalStateException("Version.findImplementationFromManifest not found");
        }
        write(node, output);
    }

    private static void patchCallback(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "org/lwjgl/system/Callback.class");
        Set<String> expected = new HashSet<>();
        Set<String> replaced = new HashSet<>();
        for (MethodNode method : node.methods) {
            String signature = method.name + method.desc;
            if (method.name.equals("<clinit>")
                    || method.name.equals("<init>")
                    || method.name.equals("free")
                    || method.name.equals("create")
                    || method.name.equals("get")
                    || method.name.equals("getSafe")
                    || method.name.equals("getCallbackHandler")) {
                expected.add(signature);
            }
            InsnList code = new InsnList();
            if (method.name.equals("<clinit>")) {
                code.add(new InsnNode(Opcodes.ICONST_0));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC, "org/lwjgl/system/Callback", "DEBUG_ALLOCATOR", "Z"));
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC,
                        "org/lwjgl/system/Callback",
                        "CLOSURE_REGISTRY",
                        "Lorg/lwjgl/system/Callback$ClosureRegistry;"));
                code.add(new InsnNode(Opcodes.LCONST_0));
                code.add(new FieldInsnNode(
                        Opcodes.PUTSTATIC, "org/lwjgl/system/Callback", "CALLBACK_HANDLER", "J"));
                code.add(new InsnNode(Opcodes.RETURN));
            } else if (method.name.equals("<init>")) {
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new MethodInsnNode(
                        Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                if (method.desc.equals("(J)V")) {
                    code.add(new VarInsnNode(Opcodes.LLOAD, 1));
                } else {
                    code.add(new InsnNode(Opcodes.LCONST_1));
                }
                code.add(new FieldInsnNode(
                        Opcodes.PUTFIELD, "org/lwjgl/system/Callback", "address", "J"));
                code.add(new InsnNode(Opcodes.RETURN));
            } else if (method.name.equals("free") && method.desc.equals("()V")) {
                code.add(new InsnNode(Opcodes.RETURN));
            } else if (method.name.equals("free") && method.desc.equals("(J)V")) {
                code.add(new InsnNode(Opcodes.RETURN));
            } else if (method.name.equals("create")
                    && method.desc.equals("(Lorg/lwjgl/system/libffi/FFICIF;Ljava/lang/Object;)J")) {
                code.add(new InsnNode(Opcodes.LCONST_1));
                code.add(new InsnNode(Opcodes.LRETURN));
            } else if ((method.name.equals("get") || method.name.equals("getSafe"))
                    && method.desc.equals("(J)Lorg/lwjgl/system/CallbackI;")) {
                code.add(new InsnNode(Opcodes.ACONST_NULL));
                code.add(new InsnNode(Opcodes.ARETURN));
            } else if (method.name.equals("getCallbackHandler")
                    && method.desc.equals("(Ljava/lang/reflect/Method;)J")) {
                code.add(new InsnNode(Opcodes.LCONST_0));
                code.add(new InsnNode(Opcodes.LRETURN));
            } else {
                continue;
            }
            replace(method, code, 4);
            replaced.add(signature);
        }
        Set<String> missed = new HashSet<>(expected);
        missed.removeAll(replaced);
        if (!missed.isEmpty()) {
            throw new IllegalStateException("Unsupported Callback methods: " + missed);
        }
        for (String required : new String[] {
                "<init>(J)V",
                "free()V",
                "free(J)V",
                "get(J)Lorg/lwjgl/system/CallbackI;",
                "getSafe(J)Lorg/lwjgl/system/CallbackI;"
        }) {
            if (!replaced.contains(required)) {
                throw new IllegalStateException("Required Callback method not found: " + required);
            }
        }
        boolean patchedDescriptorConstructor = replaced.stream()
                .anyMatch(signature -> signature.startsWith("<init>(L")
                        && signature.endsWith(")V"));
        if (!patchedDescriptorConstructor) {
            throw new IllegalStateException("Callback descriptor constructor not found");
        }
        System.out.println("Patched " + replaced.size() + " LWJGL Callback methods");
        write(node, output);
    }

    private static void patchCallbackInterface(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "org/lwjgl/system/CallbackI.class");
        MethodNode address = node.methods.stream()
                .filter(method -> method.name.equals("address") && method.desc.equals("()J"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("CallbackI.address not found"));
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.LCONST_1));
        code.add(new InsnNode(Opcodes.LRETURN));
        replace(address, code, 2);
        write(node, output);
    }

    private static void patchPlatform(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "org/lwjgl/system/Platform.class");
        boolean replaced = false;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("<clinit>")) {
                continue;
            }
            for (var instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals("java/lang/System")
                        && call.name.equals("getProperty")
                        && call.desc.equals("(Ljava/lang/String;)Ljava/lang/String;")) {
                    InsnList browserOs = new InsnList();
                    browserOs.add(new InsnNode(Opcodes.POP));
                    browserOs.add(new LdcInsnNode("Linux"));
                    method.instructions.insert(call, browserOs);
                    replaced = true;
                    break;
                }
            }
        }
        if (!replaced) {
            throw new IllegalStateException("Platform os.name lookup not found");
        }
        write(node, output);
    }

    private static void patchPlatformArchitecture(String jar, Path output) throws IOException {
        ClassNode node = read(jar, "org/lwjgl/system/Platform$Architecture.class");
        MethodNode initializer = node.methods.stream()
                .filter(method -> method.name.equals("<clinit>"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Platform.Architecture initializer not found"));
        boolean replaced = false;
        for (var instruction = initializer.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("java/lang/System")
                    && call.name.equals("getProperty")
                    && call.desc.equals("(Ljava/lang/String;)Ljava/lang/String;")) {
                InsnList browserArchitecture = new InsnList();
                browserArchitecture.add(new InsnNode(Opcodes.POP));
                browserArchitecture.add(new LdcInsnNode("wasm64"));
                initializer.instructions.insert(call, browserArchitecture);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            throw new IllegalStateException("Platform.Architecture os.arch lookup not found");
        }
        write(node, output);
    }

    private static void patchMemCopy(String jar, Path output) throws IOException {
        if (!contains(jar, "org/lwjgl/system/MultiReleaseMemCopy.class")) {
            ClassNode memoryUtil = read(jar, "org/lwjgl/system/MemoryUtil.class");
            boolean delegatedByMemoryUtil = memoryUtil.methods.stream()
                    .anyMatch(method -> method.name.equals("memCopy")
                            && method.desc.equals("(JJJ)V"))
                    && DELEGATES.containsKey("memCopy(JJJ)V");
            if (!delegatedByMemoryUtil) {
                throw new IllegalStateException(
                        "No browser-safe LWJGL memory-copy entry point found");
            }
            System.out.println("Verified MemoryUtil.memCopy browser delegation");
            return;
        }
        ClassNode node = read(jar, "org/lwjgl/system/MultiReleaseMemCopy.class");
        boolean found = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("copy") && method.desc.equals("(JJJ)V")) {
                replaceWithDelegate(method, "copy");
                found = true;
            }
        }
        if (!found) {
            throw new IllegalStateException("MultiReleaseMemCopy.copy not found");
        }
        write(node, output);
    }

    private static void replaceWithDelegate(MethodNode method, String targetName) {
        InsnList code = new InsnList();
        Type[] arguments = Type.getArgumentTypes(method.desc);
        int local = 0;
        for (Type argument : arguments) {
            code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
            local += argument.getSize();
        }
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, BROWSER_MEMORY, targetName, method.desc, false));
        Type result = Type.getReturnType(method.desc);
        code.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
        replace(method, code, Math.max(2, local));
    }

    private static InsnList memoryUtilInitializer() {
        InsnList code = new InsnList();
        putInt(code, "ARRAY_TLC_SIZE", 8192);
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, BROWSER_MEMORY, "byteArrays",
                "()Ljava/lang/ThreadLocal;", false));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, MEMORY_UTIL, "ARRAY_TLC_BYTE", "Ljava/lang/ThreadLocal;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, BROWSER_MEMORY, "charArrays",
                "()Ljava/lang/ThreadLocal;", false));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, MEMORY_UTIL, "ARRAY_TLC_CHAR", "Ljava/lang/ThreadLocal;"));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, MEMORY_UTIL, "UNSAFE", "Lsun/misc/Unsafe;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "java/nio/ByteOrder", "nativeOrder",
                "()Ljava/nio/ByteOrder;", false));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, MEMORY_UTIL, "NATIVE_ORDER", "Ljava/nio/ByteOrder;"));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets",
                "UTF_16LE", "Ljava/nio/charset/Charset;"));
        code.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, MEMORY_UTIL, "UTF16", "Ljava/nio/charset/Charset;"));

        String[] bufferFields = {
            "BUFFER_BYTE", "BUFFER_SHORT", "BUFFER_CHAR", "BUFFER_INT",
            "BUFFER_LONG", "BUFFER_FLOAT", "BUFFER_DOUBLE"
        };
        for (int index = 0; index < bufferFields.length; index++) {
            code.add(new IntInsnNode(Opcodes.BIPUSH, index));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, BROWSER_MEMORY, "bufferClass",
                    "(I)Ljava/lang/Class;", false));
            code.add(new FieldInsnNode(
                    Opcodes.PUTSTATIC, MEMORY_UTIL, bufferFields[index], "Ljava/lang/Class;"));
        }
        for (String field : new String[] {
                "MARK", "POSITION", "LIMIT", "CAPACITY", "ADDRESS", "PARENT_BYTE",
                "PARENT_SHORT", "PARENT_CHAR", "PARENT_INT", "PARENT_LONG",
                "PARENT_FLOAT", "PARENT_DOUBLE"
        }) {
            code.add(new InsnNode(Opcodes.LCONST_0));
            code.add(new FieldInsnNode(Opcodes.PUTSTATIC, MEMORY_UTIL, field, "J"));
        }
        putInt(code, "PAGE_SIZE", 65536);
        putInt(code, "CACHE_LINE_SIZE", 64);
        code.add(new InsnNode(Opcodes.RETURN));
        return code;
    }

    private static void putInt(InsnList code, String field, int value) {
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            code.add(new IntInsnNode(Opcodes.SIPUSH, value));
        } else {
            code.add(new org.objectweb.asm.tree.LdcInsnNode(value));
        }
        code.add(new FieldInsnNode(Opcodes.PUTSTATIC, MEMORY_UTIL, field, "I"));
    }

    private static void replace(MethodNode method, InsnList code, int maxStack) {
        method.access &= ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
        method.instructions = code;
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        method.maxStack = maxStack;
        method.maxLocals = Math.max(method.maxLocals, Type.getArgumentsAndReturnSizes(method.desc) >> 2);
    }

    private static ClassNode read(String jarPath, String entryName) throws IOException {
        byte[] bytes;
        try (ZipFile jar = new ZipFile(jarPath)) {
            var entry = jar.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException(entryName + " not found");
            }
            try (var stream = jar.getInputStream(entry)) {
                bytes = stream.readAllBytes();
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static boolean contains(String jarPath, String entryName) throws IOException {
        try (ZipFile jar = new ZipFile(jarPath)) {
            return jar.getEntry(entryName) != null;
        }
    }

    private static void write(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static Map<String, String> delegates() {
        Map<String, String> methods = new HashMap<>();
        methods.put("nmemAlloc(J)J", "allocate");
        methods.put("nmemAllocChecked(J)J", "allocate");
        methods.put("nmemCalloc(JJ)J", "calloc");
        methods.put("nmemCallocChecked(JJ)J", "calloc");
        methods.put("nmemRealloc(JJ)J", "reallocate");
        methods.put("nmemReallocChecked(JJ)J", "reallocate");
        methods.put("nmemAlignedAlloc(JJ)J", "alignedAllocate");
        methods.put("nmemAlignedAllocChecked(JJ)J", "alignedAllocate");
        methods.put("nmemFree(J)V", "free");
        methods.put("nmemAlignedFree(J)V", "free");
        methods.put("memFree(Ljava/nio/Buffer;)V", "free");
        methods.put("memAddress0(Ljava/nio/Buffer;)J", "address0");
        methods.put("memByteBuffer(JI)Ljava/nio/ByteBuffer;", "byteBuffer");
        methods.put("memRealloc(Ljava/nio/ByteBuffer;I)Ljava/nio/ByteBuffer;", "reallocate");
        methods.put("memRealloc(Ljava/nio/ShortBuffer;I)Ljava/nio/ShortBuffer;", "reallocate");
        methods.put("memRealloc(Ljava/nio/IntBuffer;I)Ljava/nio/IntBuffer;", "reallocate");
        methods.put("memRealloc(Ljava/nio/LongBuffer;I)Ljava/nio/LongBuffer;", "reallocate");
        methods.put("memRealloc(Ljava/nio/FloatBuffer;I)Ljava/nio/FloatBuffer;", "reallocate");
        methods.put("memRealloc(Ljava/nio/DoubleBuffer;I)Ljava/nio/DoubleBuffer;", "reallocate");
        methods.put("memGetByte(J)B", "getByte");
        methods.put("memGetBoolean(J)Z", "getBoolean");
        methods.put("memGetShort(J)S", "getShort");
        methods.put("memGetInt(J)I", "getInt");
        methods.put("memGetLong(J)J", "getLong");
        methods.put("memGetFloat(J)F", "getFloat");
        methods.put("memGetDouble(J)D", "getDouble");
        methods.put("memGetAddress(J)J", "getLong");
        methods.put("memPutByte(JB)V", "putByte");
        methods.put("memPutShort(JS)V", "putShort");
        methods.put("memPutInt(JI)V", "putInt");
        methods.put("memPutLong(JJ)V", "putLong");
        methods.put("memPutFloat(JF)V", "putFloat");
        methods.put("memPutDouble(JD)V", "putDouble");
        methods.put("memPutAddress(JJ)V", "putLong");
        methods.put("memSet(JIJ)V", "set");
        methods.put("memCopy(JJJ)V", "copy");
        methods.put("memLengthNT1(JI)I", "lengthNt1");
        methods.put("memLengthNT2(JI)I", "lengthNt2");
        methods.put("strlen64NT1(JI)I", "lengthNt1");
        methods.put("strlen32NT1(JI)I", "lengthNt1");
        methods.put("strlen64NT2(JI)I", "lengthNt2");
        methods.put("strlen32NT2(JI)I", "lengthNt2");
        methods.put("memASCII(JI)Ljava/lang/String;", "decodeAscii");
        methods.put("memGetCLong(J)J", "getCLong");
        methods.put("memPutCLong(JJ)V", "putCLong");
        methods.put("memSlice(Ljava/nio/ByteBuffer;)Ljava/nio/ByteBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/ShortBuffer;)Ljava/nio/ShortBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/CharBuffer;)Ljava/nio/CharBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/IntBuffer;)Ljava/nio/IntBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/LongBuffer;)Ljava/nio/LongBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/FloatBuffer;)Ljava/nio/FloatBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/DoubleBuffer;)Ljava/nio/DoubleBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/ByteBuffer;II)Ljava/nio/ByteBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/ShortBuffer;II)Ljava/nio/ShortBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/CharBuffer;II)Ljava/nio/CharBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/IntBuffer;II)Ljava/nio/IntBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/LongBuffer;II)Ljava/nio/LongBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/FloatBuffer;II)Ljava/nio/FloatBuffer;", "slice");
        methods.put("memSlice(Ljava/nio/DoubleBuffer;II)Ljava/nio/DoubleBuffer;", "slice");
        methods.put("write8(JII)I", "write8");
        methods.put("write8Safe(JIII)I", "write8Safe");
        methods.put("write16(JIC)I", "write16");
        methods.put(
                "slice(Ljava/nio/ByteBuffer;JI)Ljava/nio/ByteBuffer;", "slice");
        methods.put(
                "slice(Ljava/lang/Class;Ljava/nio/Buffer;JIJ)Ljava/nio/Buffer;", "slice");
        methods.put(
                "duplicate(Ljava/lang/Class;Ljava/nio/Buffer;J)Ljava/nio/Buffer;", "duplicate");
        methods.put("wrap(Ljava/lang/Class;JI)Ljava/nio/Buffer;", "wrap");
        return methods;
    }
}
