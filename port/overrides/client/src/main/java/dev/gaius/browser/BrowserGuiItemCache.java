package dev.gaius.browser;

import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
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
    private static final int MAX_KEY_ENTRIES = 4096;
    private static final Map<Integer, TrackingItemStackRenderState> GUI_STATES =
            new LinkedHashMap<Integer, TrackingItemStackRenderState>(MAX_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, TrackingItemStackRenderState> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };
    private static final IdentityHashMap<ItemStack, StackKey> STACK_KEYS = new IdentityHashMap<>(MAX_KEY_ENTRIES);
    private static int stackKeyReads;

    private BrowserGuiItemCache() {
    }

    public static TrackingItemStackRenderState guiState(
            Minecraft minecraft, ItemStack stack, Level level, LivingEntity entity, int seed) {
        int key = guiKey(stack);
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

    private static int guiKey(ItemStack stack) {
        if ((++stackKeyReads & 2047) == 0) {
            STACK_KEYS.clear();
        }
        Object item = stack.getItem();
        int count = stack.getCount();
        StackKey cached = STACK_KEYS.get(stack);
        if (cached != null && cached.item == item && cached.count == count) {
            return cached.key;
        }
        int key = 31 * ItemStack.hashItemAndComponents(stack) + count;
        if (STACK_KEYS.size() >= MAX_KEY_ENTRIES) {
            STACK_KEYS.clear();
        }
        STACK_KEYS.put(stack, new StackKey(item, count, key));
        return key;
    }

    private static boolean containsAnimatedLayer(ItemStackRenderState state) {
        return state.isAnimated();
    }

    private record StackKey(Object item, int count, int key) {
    }
}
