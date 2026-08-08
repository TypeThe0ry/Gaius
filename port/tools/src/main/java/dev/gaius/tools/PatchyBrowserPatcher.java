package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Routes Mojang's blocked-server list through the browser authentication proxy. */
public final class PatchyBrowserPatcher {
    private static final String ENTRY = "com/mojang/patchy/MojangBlockListSupplier.class";

    private PatchyBrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "usage: PatchyBrowserPatcher INPUT_JAR OUTPUT_CLASS");
        }

        byte[] input;
        try (ZipFile jar = new ZipFile(args[0])) {
            var entry = jar.getEntry(ENTRY);
            if (entry == null) {
                throw new IllegalStateException(ENTRY + " not found in " + args[0]);
            }
            try (var stream = jar.getInputStream(entry)) {
                input = stream.readAllBytes();
            }
        }

        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, 0);
        MethodNode createBlockList = node.methods.stream()
                .filter(method -> method.name.equals("createBlockList")
                        && method.desc.equals("()Ljava/util/function/Predicate;"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Mojang block-list factory was not found"));
        int patched = 0;
        for (var instruction = createBlockList.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.owner.equals("java/net/URL")
                    || !call.name.equals("<init>")
                    || !call.desc.equals("(Ljava/lang/String;)V")) {
                continue;
            }
            createBlockList.instructions.insert(call, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserHttpProxy",
                    "proxyAuthentication",
                    "(Ljava/net/URL;)Ljava/net/URL;",
                    false));
            patched++;
        }
        if (patched != 1) {
            throw new IllegalStateException(
                    "Mojang blocked-server URL patch point changed: " + patched);
        }

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
