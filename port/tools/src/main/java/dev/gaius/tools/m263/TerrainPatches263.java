package dev.gaius.tools.m263;

import dev.gaius.tools.ModernSymbols;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Minecraft 26.3 terrain patches, owned by work package P5.
 *
 * <p>Planned scope (migration plan): 26.3-only terrain pipeline and frame-loop transforms that do
 * not fit an in-place 26.3 branch of the MinecraftClientPatcher/Minecraft262BrowserPatcher terrain
 * patches.
 *
 * <p>Contract C2: {@code Minecraft263BrowserPatcher} calls {@link #apply} at the tail of the
 * modern chain, after MinecraftClientPatcher, MinecraftChunkDrawTelemetryPatcher,
 * Minecraft262BrowserPatcher and MinecraftServerWorkerPatcher, in the order Render, Input,
 * Terrain, Worldgen, Server, Ui. {@code jar} is the chain's current client jar, {@code root} the
 * directory whose classes are merged back into it. Wrap every patch in
 * {@code PatchRegistry.run("TerrainPatches263.<method>", () -> ...)} and read owners, descriptors
 * and constants from {@code symbols}.
 */
public final class TerrainPatches263 {
    private TerrainPatches263() {
    }

    public static void apply(String jar, Path root, ModernSymbols symbols) throws IOException {
        System.out.println("TerrainPatches263: no 26.3 terrain patches yet (P5)");
    }
}
