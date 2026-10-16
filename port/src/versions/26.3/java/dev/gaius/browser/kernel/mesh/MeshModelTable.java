package dev.gaius.browser.kernel.mesh;

import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;

/**
 * One exported model table: its bytes (kept so the page facade can be given the table again),
 * the resource objects it was exported from, and the per-state bits the main-thread snapshot
 * needs (air for the non-air count, block entities to collect them in vanilla order).
 */
final class MeshModelTable {
    final int epoch;
    final byte[] bytes;
    final int stateCount;
    final BlockStateModelSet models;
    final FluidStateModelSet fluidModels;
    final BlockColors colors;
    private final long[] blockEntityStates;
    private final long[] airStates;
    final int airId;

    MeshModelTable(int epoch, byte[] bytes, int stateCount, BlockStateModelSet models,
            FluidStateModelSet fluidModels, BlockColors colors, long[] blockEntityStates, long[] airStates,
            int airId) {
        this.epoch = epoch;
        this.bytes = bytes;
        this.stateCount = stateCount;
        this.models = models;
        this.fluidModels = fluidModels;
        this.colors = colors;
        this.blockEntityStates = blockEntityStates;
        this.airStates = airStates;
        this.airId = Math.max(airId, 0);
    }

    boolean matches(BlockStateModelSet models, FluidStateModelSet fluidModels, BlockColors colors) {
        return this.models == models && this.fluidModels == fluidModels && this.colors == colors;
    }

    /** State ids above 65535 need the wide id layout. */
    boolean wideIds() {
        return stateCount > 0x10000;
    }

    boolean hasBlockEntity(int id) {
        return id >= 0 && id < stateCount && (blockEntityStates[id >>> 6] & (1L << (id & 63))) != 0;
    }

    boolean isAir(int id) {
        return id >= 0 && id < stateCount && (airStates[id >>> 6] & (1L << (id & 63))) != 0;
    }
}
