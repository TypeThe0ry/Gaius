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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Routes authlib HTTP traffic through the browser bridge to avoid CORS failures. */
public final class AuthlibBrowserPatcher {
    private static final String ENTRY =
            "com/mojang/authlib/minecraft/client/MinecraftClient.class";

    private AuthlibBrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "usage: AuthlibBrowserPatcher INPUT_JAR OUTPUT_CLASS");
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
        boolean found = false;
        boolean removedJavaProxy = false;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("createUrlConnection")
                    || !method.desc.equals(
                            "(Ljava/net/URL;)Ljava/net/HttpURLConnection;")) {
                continue;
            }
            InsnList code = new InsnList();
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserHttpProxy",
                    "proxyAuthentication",
                    "(Ljava/net/URL;)Ljava/net/URL;",
                    false));
            code.add(new VarInsnNode(Opcodes.ASTORE, 1));
            method.instructions.insert(code);
            method.maxStack = Math.max(method.maxStack, 1);
            for (var instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || !call.owner.equals("java/net/URL")
                        || !call.name.equals("openConnection")
                        || !call.desc.equals("(Ljava/net/Proxy;)Ljava/net/URLConnection;")) {
                    continue;
                }
                method.instructions.insertBefore(call, new org.objectweb.asm.tree.InsnNode(
                        Opcodes.POP));
                call.desc = "()Ljava/net/URLConnection;";
                removedJavaProxy = true;
            }
            found = true;
        }
        if (!found || !removedJavaProxy) {
            throw new IllegalStateException(
                    "MinecraftClient browser connection patch points were not found: method="
                            + found + " proxy=" + removedJavaProxy);
        }

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
