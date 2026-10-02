package dev.gaius.browser.kernel.worldgen;

import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Added to the 26.2 {@code NoiseBasedChunkGenerator} by {@code dev.gaius.tools.kernel.WorldgenKernelPatches}:
 * the vanilla {@code fillFromNoise} and {@code createBiomes}, renamed when the kernel hooks took
 * their place.
 */
public interface WorldgenKernelHooks262 {
    CompletableFuture<ChunkAccess> gaius$fillFromNoiseVanilla(
            Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk);

    CompletableFuture<ChunkAccess> gaius$createBiomesVanilla(
            RandomState randomState, Blender blender, StructureManager structureManager, ChunkAccess chunk);
}
