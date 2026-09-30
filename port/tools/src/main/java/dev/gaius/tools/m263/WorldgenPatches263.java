package dev.gaius.tools.m263;

import dev.gaius.tools.ModernSymbols;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Minecraft 26.3 worldgen patches, owned by work package P6.
 *
 * <p>Planned scope (migration plan): CarvingMask.visit/visitSegment deep pulses;
 * MaterialRuleContext int shadow counters; LazyXZCondition/LazyYCondition test() replacements;
 * static guard that no scheduler/pulse call lands in density functions, PerlinNoise, NoiseStack,
 * InterpolatedFunction$Sampler, DensityFunctionCompiler or RandomState lock regions.
 *
 * <p>Contract C2: {@code Minecraft263BrowserPatcher} calls {@link #apply} at the tail of the
 * modern chain, after MinecraftClientPatcher, MinecraftChunkDrawTelemetryPatcher,
 * Minecraft262BrowserPatcher and MinecraftServerWorkerPatcher, in the order Render, Input,
 * Terrain, Worldgen, Server, Ui. {@code jar} is the chain's current client jar, {@code root} the
 * directory whose classes are merged back into it. Wrap every patch in
 * {@code PatchRegistry.run("WorldgenPatches263.<method>", () -> ...)} and read owners, descriptors
 * and constants from {@code symbols}.
 */
public final class WorldgenPatches263 {
    private WorldgenPatches263() {
    }

    public static void apply(String jar, Path root, ModernSymbols symbols) throws IOException {
        System.out.println("WorldgenPatches263: no 26.3 worldgen patches yet (P6)");
    }
}
