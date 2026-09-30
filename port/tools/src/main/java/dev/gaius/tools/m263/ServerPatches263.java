package dev.gaius.tools.m263;

import dev.gaius.tools.ModernSymbols;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Minecraft 26.3 server patches, owned by work package P7a.
 *
 * <p>Planned scope (migration plan): yield in
 * RegistryLoadTask$PendingRegistration.loadFromResource; PlayerList.isOp reads the Gaius
 * allow-commands flag; optional short-circuit of the full movement check after a teleport
 * confirmation.
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
    private ServerPatches263() {
    }

    public static void apply(String jar, Path root, ModernSymbols symbols) throws IOException {
        System.out.println("ServerPatches263: no 26.3 server patches yet (P7a)");
    }
}
