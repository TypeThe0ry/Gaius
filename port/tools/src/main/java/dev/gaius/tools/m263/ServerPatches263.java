package dev.gaius.tools.m263;

import dev.gaius.tools.ModernSymbols;
import dev.gaius.tools.PatchRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Minecraft 26.3 server patches, owned by work package P7a.
 *
 * <ul>
 *   <li>{@link #patchRegistryLoadTaskBrowserStartupYield}: 26.3 decodes every datapack JSON
 *       registry of the server Worker (world, dimension, and the recipes and loot tables that
 *       26.2 still scanned through SimpleJsonResourceReloadListener.scanDirectory) in
 *       {@code RegistryLoadTask$PendingRegistration.loadFromResource}. A
 *       {@code BrowserStartupScheduler.datapackResourceDecoded()} call before each of its two
 *       returns restores the cooperative startup yield (batched, 64 resources per yield).</li>
 *   <li>{@link #patchPlayerListIsOpBrowserCommands}: 26.3 removed
 *       {@code PlayerList.setAllowCommandsForAllPlayers}; {@code isOp} now ends in
 *       {@code iconst_0; ireturn}. The final {@code iconst_0} becomes
 *       {@code BrowserPlayerListCompat.commandsAllowedForAllPlayers()}, a Gaius flag that the
 *       26.3 {@code BrowserPlayerListCompat.allowCommandsForAllPlayers(PlayerList)} sets for the
 *       browser Worker. That is the 26.2 semantics (isOp &rarr; the dedicated server's
 *       operator permissions) without writing ops.json.</li>
 * </ul>
 *
 * <p>Not patched (recorded in the P7a notes): 26.3's teleport confirmation now runs the full
 * movement check through handlePlayerPositionChange, and the Worker movement fast path bypasses
 * the new one-position-packet-per-tick rule; both stay vanilla until measured.
 *
 * <p>Contract C2: {@code Minecraft263BrowserPatcher} calls {@link #apply} at the tail of the
 * modern chain, after MinecraftClientPatcher, MinecraftChunkDrawTelemetryPatcher,
 * Minecraft262BrowserPatcher and MinecraftServerWorkerPatcher, in the order Render, Input,
 * Terrain, Worldgen, Server, Ui. {@code jar} is the chain's current client jar, {@code root} the
 * directory whose classes are merged back into it. Wrap every patch in
 * {@code PatchRegistry.run("ServerPatches263.<method>", () -> ...)} and read owners, descriptors
 * and constants from {@code symbols}.
 */
public final class ServerPatches263 {
    static final String PENDING_REGISTRATION =
            "net/minecraft/resources/RegistryLoadTask$PendingRegistration";
    static final String LOAD_FROM_RESOURCE_DESCRIPTOR =
            "(Lcom/mojang/serialization/Decoder;Lnet/minecraft/resources/RegistryOps;"
                    + "Lnet/minecraft/resources/ResourceKey;"
                    + "Lnet/minecraft/server/packs/resources/Resource;)"
                    + "Lcom/mojang/datafixers/util/Either;";
    static final String PLAYER_LIST = "net/minecraft/server/players/PlayerList";
    static final String IS_OP_DESCRIPTOR = "(Lnet/minecraft/server/players/NameAndId;)Z";
    static final String STARTUP_SCHEDULER = "dev/gaius/browser/BrowserStartupScheduler";
    static final String PLAYER_LIST_COMPAT = "dev/gaius/browser/BrowserPlayerListCompat";

    private ServerPatches263() {
    }

    public static void apply(String jar, Path root, ModernSymbols symbols) throws IOException {
        // Domain banner: patch-registry-smoke checks the order of these lines.
        System.out.println("ServerPatches263: 26.3 server patches (P7a)");
        PatchRegistry.run("ServerPatches263.patchRegistryLoadTaskBrowserStartupYield",
                () -> patchRegistryLoadTaskBrowserStartupYield(jar, root));
        PatchRegistry.run("ServerPatches263.patchPlayerListIsOpBrowserCommands",
                () -> patchPlayerListIsOpBrowserCommands(jar, root));
    }

    static void patchRegistryLoadTaskBrowserStartupYield(String jar, Path root) throws IOException {
        ClassNode node = read(jar, PENDING_REGISTRATION);
        MethodNode load = find(node, "loadFromResource", LOAD_FROM_RESOURCE_DESCRIPTOR);
        if ((load.access & Opcodes.ACC_STATIC) == 0) {
            throw new IllegalStateException(PENDING_REGISTRATION + ".loadFromResource is not static");
        }
        for (AbstractInsnNode instruction : load.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(STARTUP_SCHEDULER)) {
                throw new IllegalStateException(PENDING_REGISTRATION
                        + ".loadFromResource already calls " + STARTUP_SCHEDULER);
            }
        }
        List<AbstractInsnNode> returns = new ArrayList<>();
        for (AbstractInsnNode instruction : load.instructions) {
            if (instruction.getOpcode() == Opcodes.ARETURN) {
                returns.add(instruction);
            }
        }
        // 26.3: Either.left(decoded) after closing the reader, and Either.right(error) from the
        // parse-failure handler. Both count as one decoded resource.
        if (returns.size() != 2) {
            throw new IllegalStateException(PENDING_REGISTRATION
                    + ".loadFromResource return count changed: " + returns.size());
        }
        for (AbstractInsnNode areturn : returns) {
            // The Either stays on the operand stack across the ()V call: no stack or frame change.
            load.instructions.insertBefore(areturn, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, STARTUP_SCHEDULER, "datapackResourceDecoded", "()V", false));
        }
        write(node, root);
        System.out.println("Patched 26.3 RegistryLoadTask datapack decode with browser startup yields");
    }

    static void patchPlayerListIsOpBrowserCommands(String jar, Path root) throws IOException {
        ClassNode node = read(jar, PLAYER_LIST);
        for (MethodNode method : node.methods) {
            if (method.name.equals("setAllowCommandsForAllPlayers")) {
                throw new IllegalStateException(PLAYER_LIST
                        + " still has setAllowCommandsForAllPlayers; use the vanilla setter");
            }
        }
        MethodNode isOp = find(node, "isOp", IS_OP_DESCRIPTOR);
        for (AbstractInsnNode instruction : isOp.instructions) {
            if (instruction instanceof FieldInsnNode field && field.owner.equals(PLAYER_LIST)
                    && field.name.equals("allowCommandsForAllPlayers")) {
                throw new IllegalStateException("PlayerList.isOp still reads allowCommandsForAllPlayers");
            }
            if (instruction instanceof MethodInsnNode call && call.owner.equals(PLAYER_LIST_COMPAT)) {
                throw new IllegalStateException("PlayerList.isOp is already patched");
            }
        }
        AbstractInsnNode last = isOp.instructions.getLast();
        while (last != null && last.getOpcode() < 0) {
            last = last.getPrevious();
        }
        AbstractInsnNode denied = last == null ? null : previousOpcode(last);
        if (last == null || last.getOpcode() != Opcodes.IRETURN
                || denied == null || denied.getOpcode() != Opcodes.ICONST_0) {
            throw new IllegalStateException(
                    "PlayerList.isOp no longer ends in iconst_0; ireturn");
        }
        // Both push one int: max stack and frames are unchanged.
        isOp.instructions.set(denied, new MethodInsnNode(Opcodes.INVOKESTATIC, PLAYER_LIST_COMPAT,
                "commandsAllowedForAllPlayers", "()Z", false));
        write(node, root);
        System.out.println("Patched 26.3 PlayerList.isOp to honour the browser allow-commands flag");
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getPrevious();
        }
        return cursor;
    }

    private static ClassNode read(String jar, String owner) throws IOException {
        try (ZipFile zip = new ZipFile(jar)) {
            var entry = zip.getEntry(owner + ".class");
            if (entry == null) {
                throw new IOException("Missing class entry " + owner + ".class in " + jar);
            }
            try (var stream = zip.getInputStream(entry)) {
                ClassNode node = new ClassNode();
                new ClassReader(stream.readAllBytes()).accept(node, 0);
                return node;
            }
        }
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        node.name + "." + name + descriptor + " was not found"));
    }

    /** Writes without recomputing frames or maxima: every patch here keeps both unchanged. */
    private static void write(ClassNode node, Path root) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Path output = root.resolve(node.name + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
