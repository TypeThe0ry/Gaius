package dev.gaius.browser;

import dev.gaius.browser.render.BrowserMeshInstallQueue;
import dev.gaius.browser.render.BrowserNeighborReadiness;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
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
 * <li>{@link #requeueAfterKernelDrop}: mesh kernel results dropped for a cancelled task that left
 *     its section uncompiled with no other compile on the way;</li>
 * <li>{@link #staleMeshRejected}: counts older meshes that finished uploading after a newer
 *     compile and were discarded instead of replacing it.</li>
 * </ul>
 * <p>Once per second, {@link #afterExtract} also audits the visible sections: a section whose
 * chunk is ready but which stays uncompiled, not dirty and without a compile in flight for
 * {@value #STALE_AUDITS} audits has lost its update and is marked dirty again. The in-flight
 * check is per section (RenderSection.gaius$hasPendingCompile, added by TerrainBatchPatches,
 * plus BrowserMeshInstallQueue for asynchronous meshes), so lost sections are repaired while
 * the rest of the pipeline is still busy. Counts are published to
 * window.__gaiusSectionAudit.</p>
 *
 * <p>{@link #afterExtract} also feeds the player's chunk to BrowserNeighborReadiness every
 * frame, before its once-per-second gate.</p>
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
    private static long kernelDropRequeued;
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

    /**
     * Called by the mesh kernel hooks when a result is dropped because its task was cancelled
     * and the section, still uncompiled at the same position, has no compile or newer kernel
     * request on the way.
     */
    public static void requeueAfterKernelDrop(SectionRenderDispatcher.RenderSection section) {
        if (section == null) {
            return;
        }
        LevelExtractor extractor = Minecraft.getInstance().levelExtractor;
        if (extractor == null) {
            return;
        }
        long node = section.getSectionNode();
        extractor.setSectionDirty(SectionPos.x(node), SectionPos.y(node), SectionPos.z(node));
        kernelDropRequeued++;
    }

    /** Called when an uploaded mesh is dropped because a newer compile superseded it. */
    public static void staleMeshRejected() {
        staleMeshesRejected++;
    }

    public static void afterExtract(SectionUpdateTracker tracker) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player != null) {
            BrowserNeighborReadiness.setCamera(player.getBlockX() >> 4, player.getBlockZ() >> 4);
        }
        long now = Util.getMillis();
        if (tracker == null || now - lastRun < INTERVAL_MILLIS) {
            return;
        }
        lastRun = now;
        ClientLevel level = minecraft.level;
        LevelRenderer renderer = minecraft.levelRenderer;
        if (level == null || renderer == null) {
            STALE.clear();
            return;
        }
        audits++;
        // With compile or upload work queued, an uncompiled section may simply be waiting its
        // turn; re-dirtying it would cancel and requeue that task. A busy pipeline only
        // protects sections that actually have a compile or mesh request in flight.
        boolean pipelineIdle = BrowserRenderScheduler.queuedSectionWork() == 0;
        int uncompiledInFlight = 0;
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
            // Staged uploads drain within a few frames; a section whose compile finished may
            // still be waiting for one, so only compile backlog alone no longer blocks repair.
            if (!pipelineIdle && (section.gaius$hasPendingCompile()
                    || BrowserMeshInstallQueue.isPending(node)
                    || BrowserRenderScheduler.uploadBacklog() > 0)) {
                uncompiledInFlight++;
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
                fixed, (double) redirtied, (double) audits, (double) unconsumedRequeued,
                (double) uploadTimeoutRequeued, (double) staleMeshesRejected);
        publishReadiness(uncompiledInFlight, BrowserNeighborReadiness.waitingColumns(),
                (double) BrowserNeighborReadiness.nearbyGrants(),
                (double) BrowserNeighborReadiness.graceGrants(),
                (double) BrowserNeighborReadiness.waits(), (double) kernelDropRequeued,
                BrowserMeshInstallQueue.inFlight());
    }

    @JSBody(params = {"inFlight", "waitingColumns", "nearbyGrants", "graceGrants", "waits",
            "kernelDrops", "meshRequestsOut"}, script = """
            const audit=globalThis.__gaiusSectionAudit;
            if (!audit) return;
            audit.uncompiledInFlight=inFlight;
            audit.neighborWaitingColumns=waitingColumns;
            audit.neighborNearbyGrants=nearbyGrants;
            audit.neighborGraceGrants=graceGrants;
            audit.neighborWaits=waits;
            audit.kernelDropRequeued=kernelDrops;
            audit.meshRequestsOut=meshRequestsOut;
            """)
    private static native void publishReadiness(int inFlight, int waitingColumns,
            double nearbyGrants, double graceGrants, double waits, double kernelDrops,
            int meshRequestsOut);

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
