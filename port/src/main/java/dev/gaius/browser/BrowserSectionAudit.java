package dev.gaius.browser;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SectionUpdateRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Util;
import org.teavm.jso.JSBody;

/**
 * Keeps terrain section rebuild requests from being lost in the browser pipeline.
 *
 * <p>LevelExtractor clears a section's dirty flag when it extracts the update, and only
 * LevelRenderer.compileSections turns the extracted update into a compile task. Hooks patched
 * into the client put a section back into the dirty set whenever that hand-off fails:</p>
 * <ul>
 * <li>{@link #requeueUnconsumed}: updates extracted for a frame whose world render was skipped
 *     (inventory screens) or threw before compileSections;</li>
 * <li>{@link #requeueAfterUploadTimeout}: compiles cancelled because their GPU upload never got
 *     staging space;</li>
 * <li>{@link #staleMeshRejected}: counts older meshes that finished uploading after a newer
 *     compile and were discarded instead of replacing it.</li>
 * </ul>
 * <p>Once per second, {@link #afterExtract} also audits the visible sections: a section whose
 * chunk is ready but which stays uncompiled and not dirty while the pipeline is idle has lost its
 * update and is marked dirty again. Counts are published to window.__gaiusSectionAudit.</p>
 */
public final class BrowserSectionAudit {
    private static final long INTERVAL_MILLIS = 1000L;
    private static final int STALE_AUDITS = 3;

    private static final Long2IntOpenHashMap STALE = new Long2IntOpenHashMap();
    private static long lastRun;
    private static long redirtied;
    private static long audits;
    private static long unconsumedRequeued;
    private static long uploadTimeoutRequeued;
    private static long staleMeshesRejected;

    private BrowserSectionAudit() {
    }

    /** Called at the start of LevelExtractor.extract, before the frame state is reset. */
    public static void requeueUnconsumed(LevelRenderState state, SectionUpdateTracker tracker) {
        if (state == null || tracker == null) {
            return;
        }
        List<SectionUpdateRenderState> updates = state.sectionUpdateRenderStates;
        if (updates == null || updates.isEmpty()) {
            return;
        }
        for (SectionUpdateRenderState update : updates) {
            long node = update.sectionNode();
            tracker.setDirty(SectionPos.x(node), SectionPos.y(node), SectionPos.z(node),
                    update.playerChanged());
        }
        unconsumedRequeued += updates.size();
        updates.clear();
    }

    /** Called by a compile task that gives up waiting for upload space, before it cancels. */
    public static void requeueAfterUploadTimeout(
            SectionRenderDispatcher.RenderSection section, boolean alreadyCancelled) {
        if (section == null || alreadyCancelled) {
            return;
        }
        LevelExtractor extractor = Minecraft.getInstance().levelExtractor;
        if (extractor == null) {
            return;
        }
        long node = section.getSectionNode();
        extractor.setSectionDirty(SectionPos.x(node), SectionPos.y(node), SectionPos.z(node));
        uploadTimeoutRequeued++;
    }

    /** Called when an uploaded mesh is dropped because a newer compile superseded it. */
    public static void staleMeshRejected() {
        staleMeshesRejected++;
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
        // With compile or upload work queued, an uncompiled section may simply be waiting its
        // turn; re-dirtying it would cancel and requeue that task.
        boolean pipelineIdle = BrowserRenderScheduler.queuedSectionWork() == 0;
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
            if (!pipelineIdle) {
                continue;
            }
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
                fixed, (double) redirtied, (double) audits, (double) unconsumedRequeued,
                (double) uploadTimeoutRequeued, (double) staleMeshesRejected);
    }

    @JSBody(params = {"visible", "uncompiled", "uncompiledDirty", "uncompiledWaiting",
            "uncompiledLost", "fixed", "redirtied", "audits", "unconsumed", "uploadTimeouts",
            "staleMeshes"}, script = """
            globalThis.__gaiusSectionAudit = {
              visible: visible, uncompiled: uncompiled, uncompiledDirty: uncompiledDirty,
              uncompiledWaiting: uncompiledWaiting, uncompiledLost: uncompiledLost,
              fixedThisAudit: fixed, redirtiedTotal: redirtied, audits: audits,
              unconsumedRequeued: unconsumed, uploadTimeoutRequeued: uploadTimeouts,
              staleMeshesRejected: staleMeshes, at: Date.now()
            };
            """)
    private static native void publish(int visible, int uncompiled, int uncompiledDirty,
            int uncompiledWaiting, int uncompiledLost, int fixed, double redirtied, double audits,
            double unconsumed, double uploadTimeouts, double staleMeshes);
}
