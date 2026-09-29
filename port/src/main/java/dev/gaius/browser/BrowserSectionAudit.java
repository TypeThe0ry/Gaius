package dev.gaius.browser;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.util.Util;
import org.teavm.jso.JSBody;

/**
 * Once per second, audits the visible sections after LevelExtractor ran. A visible section whose
 * chunk is ready but which stays uncompiled and not dirty for several audits has lost its dirty
 * flag (a hole in the world); it is marked dirty again so it gets compiled. Counts are published
 * to window.__gaiusSectionAudit for diagnostics and acceptance tests.
 */
public final class BrowserSectionAudit {
    private static final long INTERVAL_MILLIS = 1000L;
    private static final int STALE_AUDITS = 3;

    private static final Long2IntOpenHashMap STALE = new Long2IntOpenHashMap();
    private static long lastRun;
    private static long redirtied;
    private static long audits;

    private BrowserSectionAudit() {
    }

    public static void afterExtract(SectionUpdateTracker tracker) {
        long now = Util.getMillis();
        if (tracker == null || now - lastRun < INTERVAL_MILLIS) {
            return;
        }
        lastRun = now;
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LevelRenderer renderer = minecraft.levelRenderer;
        if (level == null || renderer == null) {
            STALE.clear();
            return;
        }
        audits++;
        int visible = 0;
        int uncompiled = 0;
        int uncompiledDirty = 0;
        int uncompiledWaiting = 0;
        int uncompiledLost = 0;
        int fixed = 0;
        Long2IntOpenHashMap seen = new Long2IntOpenHashMap();
        for (SectionRenderDispatcher.RenderSection section : renderer.visibleSections()) {
            visible++;
            if (section.getSectionMesh() != CompiledSectionMesh.UNCOMPILED) {
                continue;
            }
            uncompiled++;
            long node = section.getSectionNode();
            SectionUpdateTracker.SectionDirtyState state = tracker.getDirtyState(node);
            if (state == null) {
                continue;
            }
            if (state.isDirty()) {
                uncompiledDirty++;
                continue;
            }
            if (!tracker.hasAllNeighbors(level, node)) {
                uncompiledWaiting++;
                continue;
            }
            uncompiledLost++;
            int count = STALE.get(node) + 1;
            if (count >= STALE_AUDITS) {
                state.setDirty(false);
                redirtied++;
                fixed++;
            } else {
                seen.put(node, count);
            }
        }
        STALE.clear();
        STALE.putAll(seen);
        publish(visible, uncompiled, uncompiledDirty, uncompiledWaiting, uncompiledLost,
                fixed, (double) redirtied, (double) audits);
    }

    @JSBody(params = {"visible", "uncompiled", "uncompiledDirty", "uncompiledWaiting",
            "uncompiledLost", "fixed", "redirtied", "audits"}, script = """
            globalThis.__gaiusSectionAudit = {
              visible: visible, uncompiled: uncompiled, uncompiledDirty: uncompiledDirty,
              uncompiledWaiting: uncompiledWaiting, uncompiledLost: uncompiledLost,
              fixedThisAudit: fixed, redirtiedTotal: redirtied, audits: audits,
              at: Date.now()
            };
            """)
    private static native void publish(int visible, int uncompiled, int uncompiledDirty,
            int uncompiledWaiting, int uncompiledLost, int fixed, double redirtied, double audits);
}
