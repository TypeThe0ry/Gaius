package dev.gaius.browser;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Browser-only cache for GUI item render states.
 *
 * <p>Modern creative inventory rendering resolves many item models every frame.
 * In the browser that dominates the inventory screen. Immutable, non-animated
 * GUI item states are safe to reuse across frames; animated states keep the
 * vanilla path so clocks, compasses, and similar items stay live.</p>
 */
public final class BrowserGuiItemCache {
    private static final int MAX_ENTRIES = 2048;
    private static final Map<Integer, TrackingItemStackRenderState> GUI_STATES =
            new LinkedHashMap<Integer, TrackingItemStackRenderState>(MAX_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, TrackingItemStackRenderState> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    private BrowserGuiItemCache() {
    }

    public static TrackingItemStackRenderState guiState(
            Minecraft minecraft, ItemStack stack, Level level, LivingEntity entity, int seed) {
        int key = 31 * ItemStack.hashItemAndComponents(stack) + stack.getCount();
        TrackingItemStackRenderState cached = GUI_STATES.get(key);
        if (cached != null) {
            return cached;
        }

        TrackingItemStackRenderState state = new TrackingItemStackRenderState();
        minecraft.getItemModelResolver().updateForTopItem(
                state, stack, ItemDisplayContext.GUI, level, entity, seed);
        if (!containsAnimatedLayer(state)) {
            GUI_STATES.put(key, state);
        }
        return state;
    }

    private static boolean containsAnimatedLayer(ItemStackRenderState state) {
        return state.isAnimated();
    }
}
