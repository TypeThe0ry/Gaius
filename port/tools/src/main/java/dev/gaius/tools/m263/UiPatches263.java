package dev.gaius.tools.m263;

import dev.gaius.tools.ModernSymbols;
import dev.gaius.tools.PatchRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Minecraft 26.3 ui patches, owned by work package P8.
 *
 * <ul>
 *   <li>{@link #patchClientLevelBreakingSoundVolume}: the browser mining hit sound volume
 *       ({@code (volume + 1) / 8} to {@code / 4}). 26.3 moved it from
 *       {@code MultiPlayerGameMode.continueDestroyBlock} (where MinecraftClientPatcher changes it
 *       on 26.2, and registers the patch as dropped on 26.3) to
 *       {@code ClientLevel.playBreakingSound}, which plays both the first hit and the server's
 *       periodic level event 2020. This reads the ClientLevel that MinecraftClientPatcher's
 *       patchClientLevelBrowserBlockBreakEffects already wrote, so the two patches compose.</li>
 * </ul>
 *
 * <p>Contract C2: {@code Minecraft263BrowserPatcher} calls {@link #apply} at the tail of the
 * modern chain, after MinecraftClientPatcher, MinecraftChunkDrawTelemetryPatcher,
 * Minecraft262BrowserPatcher and MinecraftServerWorkerPatcher, in the order Render, Input,
 * Terrain, Worldgen, Server, Ui. {@code jar} is the chain's current client jar, {@code root} the
 * directory whose classes are merged back into it. Every patch is wrapped in
 * {@code PatchRegistry.run("UiPatches263.<method>", () -> ...)}.
 */
public final class UiPatches263 {
    static final String CLIENT_LEVEL = "net/minecraft/client/multiplayer/ClientLevel";

    private UiPatches263() {
    }

    public static void apply(String jar, Path root, ModernSymbols symbols) throws IOException {
        PatchRegistry.run("UiPatches263.patchClientLevelBreakingSoundVolume",
                () -> patchClientLevelBreakingSoundVolume(jar, root));
        System.out.println("UiPatches263: ClientLevel.playBreakingSound hit volume /8 -> /4");
    }

    /** {@code ClientLevel.playBreakingSound}: {@code ldc 8.0f; fdiv} after getHitSound becomes 4.0f. */
    static void patchClientLevelBreakingSoundVolume(String jar, Path root) throws IOException {
        ClassNode node = read(jar, CLIENT_LEVEL);
        MethodNode method = find(node, "playBreakingSound",
                "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V");
        boolean hitSound = false;
        int divisors = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/world/level/block/SoundType")
                    && call.name.equals("getHitSound")
                    && call.desc.equals("()Lnet/minecraft/sounds/SoundEvent;")) {
                hitSound = true;
                continue;
            }
            if (hitSound
                    && instruction instanceof LdcInsnNode constant
                    && Float.valueOf(8.0f).equals(constant.cst)
                    && nextOpcode(instruction) != null
                    && nextOpcode(instruction).getOpcode() == Opcodes.FDIV) {
                constant.cst = Float.valueOf(4.0f);
                divisors++;
            }
        }
        if (!hitSound || divisors != 1) {
            throw new IllegalStateException("ClientLevel.playBreakingSound hit sound volume"
                    + " patch point was not found (getHitSound=" + hitSound + ", 8.0f divisors="
                    + divisors + ")");
        }
        write(node, root);
    }

    static ClassNode read(String jar, String owner) throws IOException {
        try (ZipFile zip = new ZipFile(jar)) {
            var entry = zip.getEntry(owner + ".class");
            if (entry == null) {
                throw new IOException("Missing class entry " + owner + ".class in " + jar);
            }
            ClassNode node = new ClassNode();
            try (var input = zip.getInputStream(entry)) {
                new ClassReader(input.readAllBytes()).accept(node, 0);
            }
            return node;
        }
    }

    static MethodNode find(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        throw new IllegalStateException(node.name + "." + name + desc + " was not found");
    }

    static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction.getNext();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getNext();
        }
        return cursor;
    }

    static void write(ClassNode node, Path root) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Path output = root.resolve(node.name + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
