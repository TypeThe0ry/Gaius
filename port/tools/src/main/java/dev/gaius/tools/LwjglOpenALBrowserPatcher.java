package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Makes the LWJGL OpenAL facade behave as a silent browser device. */
public final class LwjglOpenALBrowserPatcher {
    private LwjglOpenALBrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: LwjglOpenALBrowserPatcher INPUT_JAR OUTPUT_ROOT");
        }
        ClassNode alc10 = read(args[0], "org/lwjgl/openal/ALC10.class");
        int replacements = 0;
        for (MethodNode method : alc10.methods) {
            String key = method.name + method.desc;
            switch (key) {
                case "nalcOpenDevice(J)J",
                        "alcOpenDevice(Ljava/nio/ByteBuffer;)J",
                        "alcOpenDevice(Ljava/lang/CharSequence;)J",
                        "nalcCreateContext(JJ)J",
                        "alcCreateContext(JLjava/nio/IntBuffer;)J",
                        "alcCreateContext(J[I)J",
                        "alcGetCurrentContext()J",
                        "alcGetContextsDevice(J)J" -> {
                    replaceConstant(method, Type.LONG_TYPE, 1L);
                    replacements++;
                }
                case "alcCloseDevice(J)Z",
                        "alcMakeContextCurrent(J)Z" -> {
                    replaceConstant(method, Type.BOOLEAN_TYPE, 1);
                    replacements++;
                }
                case "alcGetError(J)I",
                        "alcGetInteger(JI)I",
                        "nalcGetEnumValue(JJ)I",
                        "alcGetEnumValue(JLjava/nio/ByteBuffer;)I",
                        "alcGetEnumValue(JLjava/lang/CharSequence;)I" -> {
                    replaceConstant(method, Type.INT_TYPE, 0);
                    replacements++;
                }
                case "alcGetString(JI)Ljava/lang/String;" -> {
                    replaceString(method, "Gaius Browser OpenAL");
                    replacements++;
                }
                case "alcProcessContext(J)V",
                        "alcSuspendContext(J)V",
                        "alcDestroyContext(J)V",
                        "nalcGetIntegerv(JIIJ)V",
                        "alcGetIntegerv(JILjava/nio/IntBuffer;)V",
                        "alcGetIntegerv(JI[I)V" -> {
                    replaceVoid(method);
                    replacements++;
                }
                default -> {
                    // Leave extension/proc-address queries on the native fallback path.
                }
            }
        }
        write(alc10, Path.of(args[1]).resolve("org/lwjgl/openal/ALC10.class"));
        if (replacements < 18) {
            throw new IllegalStateException("Too few OpenAL methods patched: " + replacements);
        }
        System.out.println("Patched " + replacements + " OpenAL methods");
    }

    private static ClassNode read(String jarPath, String entryName) throws IOException {
        try (ZipFile jar = new ZipFile(jarPath)) {
            var entry = jar.getEntry(entryName);
            if (entry == null) {
                throw new IOException("Missing class in jar: " + entryName);
            }
            ClassNode node = new ClassNode();
            try (var stream = jar.getInputStream(entry)) {
                new ClassReader(stream.readAllBytes()).accept(node, 0);
            }
            return node;
        }
    }

    private static void write(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static void replaceConstant(MethodNode method, Type type, Object value) {
        InsnList code = new InsnList();
        if (type == Type.LONG_TYPE) {
            if (Long.valueOf(1L).equals(value)) {
                code.add(new InsnNode(Opcodes.LCONST_1));
            } else {
                code.add(new InsnNode(Opcodes.LCONST_0));
            }
            code.add(new InsnNode(Opcodes.LRETURN));
        } else {
            if (Integer.valueOf(1).equals(value)) {
                code.add(new InsnNode(Opcodes.ICONST_1));
            } else {
                code.add(new InsnNode(Opcodes.ICONST_0));
            }
            code.add(new InsnNode(type.getOpcode(Opcodes.IRETURN)));
        }
        replace(method, code, type == Type.LONG_TYPE ? 2 : 1);
    }

    private static void replaceString(MethodNode method, String value) {
        InsnList code = new InsnList();
        code.add(new LdcInsnNode(value));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 1);
    }

    private static void replaceVoid(MethodNode method) {
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 0);
    }

    private static void replace(MethodNode method, InsnList code, int maxStack) {
        method.instructions = code;
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.maxStack = maxStack;
        method.maxLocals = Math.max(
                method.maxLocals,
                Type.getArgumentsAndReturnSizes(method.desc) >> 2);
    }
}
