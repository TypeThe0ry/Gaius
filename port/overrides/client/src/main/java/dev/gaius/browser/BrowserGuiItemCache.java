package dev.gaius.browser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Browser-only hook for GUI item render states.
 *
 * <p>Modern creative inventory rendering resolves many item models every frame.
 * In the browser that dominates the inventory screen. The resolved
 * {@link TrackingItemStackRenderState} is mutable and is retained by
 * {@code GuiItemRenderState} until the GUI batch is drawn, so reusing one state
 * across multiple slots can make item icons disappear even though hover data and
 * tooltips still work. Keep this hook as the single place to add a future
 * immutable model cache, but return a fresh render state for every item.</p>
 */
public final class BrowserGuiItemCache {
    private BrowserGuiItemCache() {
    }

    public static TrackingItemStackRenderState guiState(
            Minecraft minecraft, ItemStack stack, Level level, LivingEntity entity, int seed) {
        TrackingItemStackRenderState state = new TrackingItemStackRenderState();
        minecraft.getItemModelResolver().updateForTopItem(
                state, stack, ItemDisplayContext.GUI, level, entity, seed);
        return state;
    }
}
