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
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Fixes TeaVM 0.15 reflection metadata generation when a class-value type is absent. */
public final class TeaVMCoreBrowserPatcher {
    private static final String CLASS =
            "org/teavm/backend/javascript/intrinsics/reflection/ClassInfoGenerator.class";

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
    }
}
