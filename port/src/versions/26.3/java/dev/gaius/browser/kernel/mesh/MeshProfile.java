package dev.gaius.browser.kernel.mesh;

import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The few block and quad predicates whose vanilla form differs between 26.2 and 26.3 (this is
 * the 26.3 copy); everything else in this package is identical across the two profiles apart
 * from the render API imports.
 */
final class MeshProfile {
    static final int PROFILE = MeshTableWriter.PROFILE_26_3;

    private MeshProfile() {
    }

    /** The AO corner test of BlockModelLighter: 26.3 {@code isLightPermeable()}. */
    static boolean lightPermeable(BlockState state) {
        return state.isLightPermeable();
    }

    /** FlowingFluid's flow blocker: 26.3 {@code is(BlockTags.BLOCKS_FLUID_FLOW)}. */
    static boolean blocksFluidFlow(BlockState state) {
        return state.is(BlockTags.BLOCKS_FLUID_FLOW);
    }

    /** The CardinalLighting face of a quad: 26.3 shadeDirectionOverride, else its direction. */
    static int shadeFace(BakedQuad quad) {
        Direction override = quad.materialInfo().shadeDirectionOverride();
        return (override != null ? override : quad.direction()).ordinal();
    }
}
