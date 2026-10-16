package dev.gaius.browser.kernel.mesh;

import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The few block and quad predicates whose vanilla form differs between 26.2 and 26.3 (this is
 * the 26.2 copy); everything else in this package is identical across the two profiles apart
 * from the render API imports.
 */
final class MeshProfile {
    static final int PROFILE = MeshTableWriter.PROFILE_26_2;

    private MeshProfile() {
    }

    /** The AO corner test of BlockModelLighter: 26.2 not view blocking or no light dampening. */
    static boolean lightPermeable(BlockState state) {
        return !state.isViewBlocking(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) || state.getLightDampening() == 0;
    }

    /** FlowingFluid's flow blocker: 26.2 {@code blocksMotion()}. */
    static boolean blocksFluidFlow(BlockState state) {
        return state.blocksMotion();
    }

    /** The CardinalLighting face of a quad: 26.2 its direction when shaded, else UP. */
    static int shadeFace(BakedQuad quad) {
        return quad.materialInfo().shade() ? quad.direction().ordinal() : Direction.UP.ordinal();
    }
}
