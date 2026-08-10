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
import org.objectweb.asm.tree.MethodNode;

/** Removes dedicated-server services that cannot run inside the browser Worker. */
public final class MinecraftServerWorkerPatcher {
    private static final String JSON_RPC = "net/minecraft/server/jsonrpc/JsonRpc";
    private static final String CREATE_DESCRIPTOR =
            "(Lnet/minecraft/server/dedicated/DedicatedServerSettings;"
                    + "Lnet/minecraft/server/notifications/NotificationManager;)"
                    + "Lnet/minecraft/server/jsonrpc/ManagementServer;";

    private MinecraftServerWorkerPatcher() {
    }

    public static void main(String[] args) throws IOException {
        Path jar = Path.of(args[0]);
        Path outputRoot = Path.of(args[1]);
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
        System.out.println("Disabled the dedicated JSON-RPC management server for the browser Worker");
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
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
