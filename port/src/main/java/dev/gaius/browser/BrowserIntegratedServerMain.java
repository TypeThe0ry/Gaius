package dev.gaius.browser;

import com.mojang.datafixers.DataFixer;
import io.netty.channel.browser.BrowserWebSocketChannel;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.server.Main;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.teavm.classlib.java.lang.TModernRuntimeSupport;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSExport;

/** TeaVM entry point for the official dedicated-server runtime inside a Web Worker. */
public final class BrowserIntegratedServerMain {
    private static final int INITIAL_VIEW_DISTANCE = 1;
    private static final int INITIAL_SIMULATION_DISTANCE = 1;
    private static final long STORAGE_FLUSH_ACK_TIMEOUT_MILLIS = 5000L;
    private static final long INDEXED_DB_FALLBACK_HYDRATION_TIMEOUT_MILLIS = 12000L;
    private static final int INDEXED_DB_FALLBACK_REHYDRATION_BUDGET_BYTES = 64 * 1024 * 1024;
    private static final int INDEXED_DB_FALLBACK_REHYDRATION_MAX_ENTRIES = 4096;
    private static final int MAX_NETWORK_INPUT_FOLLOWUPS = 4;
    private static final int MAX_NETWORK_INPUT_DEFERRED_RETRIES = 4;
    private static MinecraftServer server;
    private static Thread serverThread;
    private static boolean serverThreadExited = true;
    private static int configuredViewDistance = 8;
    private static int configuredSimulationDistance = 6;
    private static int activeViewDistance = INITIAL_VIEW_DISTANCE;
    private static int activeSimulationDistance = INITIAL_SIMULATION_DISTANCE;
    private static PlayerList appliedDistancePlayerList;
    private static int appliedViewDistance = Integer.MIN_VALUE;
    private static int appliedSimulationDistance = Integer.MIN_VALUE;
    private static boolean configuredDistancesActive;
    private static boolean distanceAdvancePending;
    private static boolean urgentPacketPumpActive;
    private static final AtomicBoolean NETWORK_INPUT_TASK_SCHEDULED = new AtomicBoolean();
    /** Runnable currently inside the browser network wakeup permit. */
    private static Runnable scheduledNetworkInputTask;
    /** Short lease held only while BlockableEventLoop.doRunTask dispatches that exact instance. */
    private static Runnable activeNetworkInputTask;
    private static boolean networkInputBurstActive;
    private static int networkInputFollowupsRemaining;
    private static int networkInputDeferredRetriesRemaining;
    /** Set when a queued input task is observed during the outer packet pump. */
    private static boolean networkInputReschedulePending;
    /** Last section tracked for the integrated player; movement packets within one section do not
     * change the ChunkMap view and must not synchronously enumerate the whole entity map. */
    private static ServerPlayer lastTrackedPlayer;
    private static int lastTrackedSectionX = Integer.MIN_VALUE;
    private static int lastTrackedSectionZ = Integer.MIN_VALUE;
    private static long lastTrackedAtNanos;
    private static ServerChunkCache pendingTrackedChunkCache;
    private static ServerPlayer pendingTrackedPlayer;
    private static int pendingTrackedSectionX = Integer.MIN_VALUE;
    private static int pendingTrackedSectionZ = Integer.MIN_VALUE;
    /**
     * ChunkMap.move() is a resumable TeaVM continuation.  While it is suspended at a
     * cooperative pulse, a browser network callback can deliver another movement packet.
     * Never enter a second move invocation on top of that continuation: the vanilla
     * PlayerChunkSender/pending-set state is not re-entrant.  New movement only updates the
     * pending center and is flushed by the next integrated-server tick after the active move
     * has returned.
     */
    private static boolean trackingMoveInFlight;
    private static long trackingMoveSequence;
    private static long trackingMoveInFlightSequence;
    private static int trackingMoveInFlightSectionX = Integer.MIN_VALUE;
    private static int trackingMoveInFlightSectionZ = Integer.MIN_VALUE;
    private static boolean storageFlushRequested;
    private static boolean storageFlushTimeoutReported;
    private static boolean indexedDbFallbackHydrationPending;
    private static String indexedDbFallbackHydrationFailure;
    private static final Deque<Integer> sentChunkBatches = new ArrayDeque<>();
    private static int acknowledgedChunkCount;
    private static final Runnable NETWORK_INPUT_TASK =
            BrowserIntegratedServerMain::runScheduledNetworkInput;

    private static String serverProperties() {
        int viewDistance = clampDistance(workerViewDistance(), 8);
        int simulationDistance = clampDistance(workerSimulationDistance(), 6);
        String properties = String.join("\n",
            "allow-flight=true",
            "enable-command-block=true",
            "enable-query=false",
            "enable-rcon=false",
            "enable-status=false",
            "enforce-secure-profile=false",
            "force-gamemode=false",
            "gamemode=survival",
            "generate-structures=true",
            "hardcore=false",
            "level-name=world",
            "max-players=1",
            "max-tick-time=-1",
            "motd=Gaius Integrated Server",
            "network-compression-threshold=256",
            "online-mode=false",
            "pause-when-empty-seconds=-1",
            "player-idle-timeout=0",
            "prevent-proxy-connections=false",
            "simulation-distance=" + simulationDistance,
            "spawn-protection=0",
            "sync-chunk-writes=false",
            "view-distance=" + viewDistance) + "\n";
        String seed = workerSeed();
        if (seed != null && !seed.isEmpty() && seed.length() <= 128
                && seed.indexOf('\n') < 0 && seed.indexOf('\r') < 0) {
            properties += "level-seed=" + seed + "\n";
        }
        return properties;
    }

    private BrowserIntegratedServerMain() {
    }

    public static InetSocketAddress tunnelAddress() {
        String sessionId = workerSessionId();
        if (!isSafeSessionId(sessionId)) {
            throw new IllegalStateException("Browser server worker session is unavailable");
        }
        return InetSocketAddress.createUnresolved(
                "server-" + sessionId + ".gaius-local",
                25565);
    }

    /** Signals that the Worker-side MessagePort endpoint can accept the browser client. */
    public static void markServerListenerReady() {
        if (isWorkerRuntime()) {
            report("server-listener-ready", workerSessionId());
        }
    }

    public static void registerServer(MinecraftServer minecraftServer) {
        if (!isWorkerRuntime()) {
            return;
        }
        server = minecraftServer;
        serverThread = minecraftServer.getRunningThread();
        serverThreadExited = false;
        configuredViewDistance = clampDistance(workerViewDistance(), 8);
        configuredSimulationDistance = clampDistance(workerSimulationDistance(), 6);
        activeViewDistance = INITIAL_VIEW_DISTANCE;
        activeSimulationDistance = INITIAL_SIMULATION_DISTANCE;
        appliedDistancePlayerList = null;
        appliedViewDistance = Integer.MIN_VALUE;
        appliedSimulationDistance = Integer.MIN_VALUE;
        configuredDistancesActive = false;
        distanceAdvancePending = false;
        urgentPacketPumpActive = false;
        NETWORK_INPUT_TASK_SCHEDULED.set(false);
        scheduledNetworkInputTask = null;
        activeNetworkInputTask = null;
        networkInputBurstActive = false;
        networkInputFollowupsRemaining = 0;
        networkInputDeferredRetriesRemaining = 0;
        networkInputReschedulePending = false;
        lastTrackedPlayer = null;
        lastTrackedSectionX = Integer.MIN_VALUE;
        lastTrackedSectionZ = Integer.MIN_VALUE;
        lastTrackedAtNanos = 0L;
        pendingTrackedChunkCache = null;
        pendingTrackedPlayer = null;
        pendingTrackedSectionX = Integer.MIN_VALUE;
        pendingTrackedSectionZ = Integer.MIN_VALUE;
        trackingMoveInFlight = false;
        trackingMoveSequence = 0L;
        trackingMoveInFlightSequence = 0L;
        trackingMoveInFlightSectionX = Integer.MIN_VALUE;
        trackingMoveInFlightSectionZ = Integer.MIN_VALUE;
        recordNetworkPumpState(-1, false);
        recordNetworkInputPending(false);
        storageFlushRequested = false;
        storageFlushTimeoutReported = false;
        indexedDbFallbackHydrationPending = false;
        indexedDbFallbackHydrationFailure = null;
        sentChunkBatches.clear();
        acknowledgedChunkCount = 0;
        recordDistanceRampTelemetry("reset");
        configurePlayerList(minecraftServer.getPlayerList());
        setIntegratedServerDistances(workerViewDistance(), workerSimulationDistance());
        BrowserStartupScheduler.complete();
        report("server-created", workerWorldId());
    }

    /** Applies local-only permissions after DedicatedServer has created its player list. */
    public static void configurePlayerList(PlayerList playerList) {
        if (!isWorkerRuntime() || playerList == null) {
            return;
        }
        // The Worker uses the dedicated-server implementation, so vanilla cannot
        // recognize the browser player as its integrated-server owner. This
        // isolated server accepts one local player only.
        playerList.setAllowCommandsForAllPlayers(true);
        applyActiveDistances();
        report("local-player-list-ready", "commands=true");
    }

    @JSExport
    public static void setIntegratedServerDistances(int viewDistance, int simulationDistance) {
        configuredViewDistance = clampDistance(viewDistance, 8);
        configuredSimulationDistance = clampDistance(simulationDistance, 6);
        if (configuredDistancesActive) {
            // Once the first real batch ACK activates the session, settings changes
            // apply immediately. Vanilla tracking/backpressure remains authoritative.
            activeViewDistance = configuredViewDistance;
            activeSimulationDistance = configuredSimulationDistance;
            distanceAdvancePending = false;
        }
        applyActiveDistances();
        recordDistanceRampTelemetry("configured");
    }

    private static void applyActiveDistances() {
        MinecraftServer current = server;
        if (current != null && !serverThreadExited && current.getPlayerList() != null) {
            PlayerList playerList = current.getPlayerList();
            if (playerList != appliedDistancePlayerList) {
                appliedDistancePlayerList = playerList;
                appliedViewDistance = Integer.MIN_VALUE;
                appliedSimulationDistance = Integer.MIN_VALUE;
            }
            int view = configuredDistancesActive
                    ? activeViewDistance
                    : INITIAL_VIEW_DISTANCE;
            int simulation = configuredDistancesActive
                    ? activeSimulationDistance
                    : INITIAL_SIMULATION_DISTANCE;
            // Both supported vanilla PlayerList implementations rebroadcast and
            // traverse every ServerLevel even when the requested value is unchanged.
            // Worker bootstrap, profile sync, and chunk acknowledgements can all
            // converge on the same staged pair, so keep those idempotent calls out
            // of the single Worker event loop.
            if (appliedViewDistance != view || playerList.getViewDistance() != view) {
                boolean recordDuration = distanceApplyTelemetryEnabled();
                double startedAt = recordDuration ? distanceApplyNowMillis() : 0.0;
                try {
                    playerList.setViewDistance(view);
                    appliedViewDistance = view;
                } finally {
                    if (recordDuration) {
                        recordDistanceApplyDuration(
                                0,
                                Math.max(0.0, distanceApplyNowMillis() - startedAt));
                    }
                }
            }
            if (appliedSimulationDistance != simulation
                    || playerList.getSimulationDistance() != simulation) {
                boolean recordDuration = distanceApplyTelemetryEnabled();
                double startedAt = recordDuration ? distanceApplyNowMillis() : 0.0;
                try {
                    playerList.setSimulationDistance(simulation);
                    appliedSimulationDistance = simulation;
                } finally {
                    if (recordDuration) {
                        recordDistanceApplyDuration(
                                1,
                                Math.max(0.0, distanceApplyNowMillis() - startedAt));
                    }
                }
            }
            if (configuredDistancesActive) {
                String event = view == configuredViewDistance
                                && simulation == configuredSimulationDistance
                        ? "server-distances"
                        : "server-distances-ramping";
                report(event, view + "/" + simulation);
            } else {
                report(
                        "server-distances-staged",
                        view + "/" + simulation + "->"
                                + configuredViewDistance + "/" + configuredSimulationDistance);
            }
        }
    }

    /** Records real batch accounting; activation no longer uses a synthetic ring gate. */
    public static void recordChunkBatchSent(int batchSize) {
        if (isWorkerRuntime() && batchSize > 0) {
            sentChunkBatches.addLast(batchSize);
            recordDistanceRampTelemetry("sent");
        }
    }

    /** Applies the configured distances after the first matching batch ACK. */
    public static void acknowledgeChunkBatch() {
        if (!isWorkerRuntime()) {
            return;
        }
        Integer batchSize = sentChunkBatches.pollFirst();
        if (batchSize == null) {
            reportRuntimeEvent("chunk-batch-ack-without-send", "queued=0");
            recordDistanceRampTelemetry("ack-without-send");
            return;
        }
        acknowledgedChunkCount += batchSize;
        if (!configuredDistancesActive) {
            configuredDistancesActive = true;
            activeViewDistance = configuredViewDistance;
            activeSimulationDistance = configuredSimulationDistance;
            distanceAdvancePending = false;
            applyActiveDistances();
            recordDistanceRampTelemetry("ack-initial-activation");
            return;
        }
        // ACKs remain real accounting/backpressure observations. They no longer
        // gate another private distance ring or synthesize a completion signal.
        distanceAdvancePending = false;
        recordDistanceRampTelemetry("ack-configured");
    }

    /** Patcher compatibility hook; vanilla tracking owns later distance changes. */
    public static void tickIntegratedServerDistances() {
        flushPendingPlayerChunkTracking();
        if (isWorkerRuntime() && configuredDistancesActive) {
            distanceAdvancePending = false;
        }
    }

    /** Compatibility predicate retained for existing patcher call sites. */
    public static boolean isWorkerServer() {
        return isWorkerRuntime();
    }


    /** Processes browser actions between synchronous worldgen slices on the server thread. */
    public static void pumpUrgentPackets() {
        if (drainUrgentPackets()) {
            recordNetworkInputPending(hasPendingNetworkInput());
        }
    }

    private static boolean drainUrgentPackets() {
        MinecraftServer current = server;
        if (!isWorkerRuntime() || current == null || Thread.currentThread() != serverThread
                || urgentPacketPumpActive) {
            return false;
        }
        return drainUrgentPacketsFromServerLoop(current);
    }

    /**
     * Drains the private task enqueued through {@link MinecraftServer#schedule}.
     *
     * <p>TeaVM may resume a queued {@link TickTask} with a different Java {@link Thread} wrapper
     * even though {@code BlockableEventLoop.pollTask} is consuming that task on the integrated
     * server loop. The private runnable reference is the execution capability here: JavaScript
     * wakeups can only enqueue it and cannot call this method. Keep lifecycle and one-task permit
     * checks, but do not reject a genuine scheduled task solely because its wrapper identity
     * differs from the one observed at the patched {@code pollTask} boundary.</p>
     */
    private static boolean drainScheduledNetworkInput() {
        MinecraftServer current = server;
        if (!isWorkerRuntime() || current == null || serverThreadExited) {
            return false;
        }
        if (urgentPacketPumpActive) {
            networkInputReschedulePending = true;
            reportRuntimeEvent("network-pump-deferred", "urgent-reentrant");
            return false;
        }
        return drainUrgentPacketsFromServerLoop(current);
    }

    /**
     * Called by the patched BlockableEventLoop.doRunTask immediately before dispatching a queued
     * runnable. TeaVM may restore a queued TickTask as a fresh Java wrapper, so neither wrapper
     * identity nor the concrete TickTask type is stable across a resumed Worker continuation.
     * The one outstanding permit is therefore the ownership marker; the caller must pair this
     * with endScheduledNetworkInputTask in finally.
     */
    public static boolean beginScheduledNetworkInputTask(Runnable task) {
        MinecraftServer current = server;
        if (!isWorkerRuntime() || current == null || serverThreadExited
                || !NETWORK_INPUT_TASK_SCHEDULED.get() || activeNetworkInputTask != null) {
            return false;
        }
        activeNetworkInputTask = task;
        return true;
    }

    /** Releases the exact-dispatch lease, including when the queued runnable throws. */
    public static void endScheduledNetworkInputTask(Runnable task, boolean entered) {
        if (entered && activeNetworkInputTask == task) {
            activeNetworkInputTask = null;
            if (scheduledNetworkInputTask == task) {
                scheduledNetworkInputTask = null;
            }
        }
    }

    private static boolean drainUrgentPacketsFromServerLoop(MinecraftServer current) {
        urgentPacketPumpActive = true;
        try {
            BrowserClientNetwork.pumpBrowserChannelsAtFrameBoundary();
            current.packetProcessor().processQueuedPackets();
            return true;
        } finally {
            urgentPacketPumpActive = false;
            if (networkInputReschedulePending && server == current
                    && !serverThreadExited) {
                networkInputReschedulePending = false;
                scheduleNetworkInputTask(false, false);
            }
        }
    }

    /** Keeps player input moving while the server thread waits on asynchronous chunk futures. */
    public static void pumpUrgentPacketsIfPending() {
        MinecraftServer current = server;
        if (!isWorkerRuntime() || current == null
                || !bindServerThreadFromServerLoop(current)) {
            return;
        }
        if (BrowserWebSocketChannel.hasPendingInput()
                || BrowserPacketScheduler.hasPendingPackets()) {
            pumpUrgentPackets();
        }
    }

    /**
     * Updates server-side chunk tracking only when the player enters a new section. The vanilla
     * movement listener invokes {@code ServerChunkCache.move} for every position packet; in the
     * browser Worker that call walks the complete tracking view synchronously and can hold one
     * packet handler for seconds while worldgen is active. Physics and position validation remain
     * in the listener; this wrapper only removes redundant same-section view walks.
     */
    public static void moveServerPlayerChunkTracking(
            ServerChunkCache chunkCache, ServerPlayer player) {
        if (chunkCache == null || player == null) {
            return;
        }
        int sectionX = floorSectionCoordinate(player.getX());
        int sectionZ = floorSectionCoordinate(player.getZ());
        if (lastTrackedPlayer == player
                && lastTrackedSectionX == sectionX
                && lastTrackedSectionZ == sectionZ) {
            return;
        }
        long nowNanos = System.nanoTime();
        if (lastTrackedPlayer == player
                && lastTrackedAtNanos != 0L
                && nowNanos - lastTrackedAtNanos < 100_000_000L) {
            return;
        }
        pendingTrackedChunkCache = chunkCache;
        pendingTrackedPlayer = player;
        pendingTrackedSectionX = sectionX;
        pendingTrackedSectionZ = sectionZ;
    }

    private static void flushPendingPlayerChunkTracking() {
        // ChunkMap.move can be suspended by BrowserWorldgenScheduler.pulse().  A resumed
        // continuation owns the live tracking view until its call returns; starting another
        // invocation here would let two difference walks mutate PlayerChunkSender in parallel.
        if (trackingMoveInFlight) {
            recordTrackingMoveTelemetry(1, pendingTrackedPlayer != null);
            return;
        }
        ServerChunkCache chunkCache = pendingTrackedChunkCache;
        ServerPlayer player = pendingTrackedPlayer;
        // This runs only at the integrated-server tick boundary.  The packet handler has
        // already been kept off the synchronous ChunkMap walk; delaying the pending view update
        // here when the worldgen queue is busy leaves the client stranded over unloaded terrain.
        // ChunkMap.move itself is instrumented with cooperative pulses, so let the tick-boundary
        // operation make progress rather than dropping the latest tracking center indefinitely.
        if (chunkCache == null || player == null) {
            return;
        }
        int sectionX = pendingTrackedSectionX;
        int sectionZ = pendingTrackedSectionZ;
        pendingTrackedChunkCache = null;
        pendingTrackedPlayer = null;
        long invocation = ++trackingMoveSequence;
        trackingMoveInFlight = true;
        trackingMoveInFlightSequence = invocation;
        trackingMoveInFlightSectionX = sectionX;
        trackingMoveInFlightSectionZ = sectionZ;
        recordTrackingMoveTelemetry(0, true);
        try {
            // This call may suspend and resume at any of the cooperative ChunkMap pulses.  Do
            // not move the completion bookkeeping above the call: lastTracked* must describe
            // only a fully returned vanilla walk, never a partially enumerated view.
            chunkCache.move(player);
            if (trackingMoveInFlightSequence == invocation) {
                lastTrackedPlayer = player;
                lastTrackedSectionX = sectionX;
                lastTrackedSectionZ = sectionZ;
                lastTrackedAtNanos = System.nanoTime();
            }
        } finally {
            if (trackingMoveInFlightSequence == invocation) {
                trackingMoveInFlight = false;
                trackingMoveInFlightSectionX = Integer.MIN_VALUE;
                trackingMoveInFlightSectionZ = Integer.MIN_VALUE;
                recordTrackingMoveTelemetry(2, pendingTrackedPlayer != null);
            }
        }
    }

    /** Optional diagnostics for proving move serialization; never affects the hot path. */
    @JSBody(params = {"event", "pending"}, script = """
            try {
              const stats = globalThis.__gaiusWorldgenStats;
              if (!stats) return;
              stats.trackingMoveTelemetryVersion = 1;
              if ((event | 0) === 0) {
                stats.trackingMoveStarts = (Number(stats.trackingMoveStarts) || 0) + 1;
                stats.trackingMoveInFlight = 1;
              } else if ((event | 0) === 1) {
                stats.trackingMoveInFlightSkips =
                  (Number(stats.trackingMoveInFlightSkips) || 0) + 1;
              } else if ((event | 0) === 2) {
                stats.trackingMoveCompletions =
                  (Number(stats.trackingMoveCompletions) || 0) + 1;
                stats.trackingMoveInFlight = 0;
              }
              stats.trackingMovePending = pending ? 1 : 0;
            } catch (_) {
              // Diagnostics must never perturb chunk tracking.
            }
            """)
    private static native void recordTrackingMoveTelemetry(int event, boolean pending);

    /**
     * Captures the send-side state that is otherwise invisible from packet counters.  A
     * selected count of zero with a non-empty pending set means the ready filter/worldgen path
     * is the bottleneck; a selected count followed by a batch boundary proves the sender chose
     * ready chunks and moves the investigation to transport or client decode.
     */
    @JSBody(params = {"phase", "pending", "selected", "unacknowledged", "playerChunkX", "playerChunkZ"}, script = """
            try {
              const stats = globalThis.__gaiusWorldgenStats;
              if (!stats) return;
              const state = stats.chunkSender || (stats.chunkSender = {});
              state.telemetryVersion = 1;
              state.last = {
                phase: phase | 0,
                pending: Math.max(0, pending | 0),
                selected: Math.max(-1, selected | 0),
                unacknowledgedBatches: Math.max(0, unacknowledged | 0),
                playerChunkX: playerChunkX | 0,
                playerChunkZ: playerChunkZ | 0
              };
              const key = (phase | 0) === 0 ? 'entry'
                : ((phase | 0) === 1 ? 'selected' : 'batch');
              state[key + 'Count'] = (Number(state[key + 'Count']) || 0) + 1;
              if ((phase | 0) === 1) {
                state.readySelectedChunks =
                  (Number(state.readySelectedChunks) || 0) + Math.max(0, selected | 0);
              }
              state.maxPending = Math.max(
                Number(state.maxPending) || 0, Math.max(0, pending | 0));
              state.maxUnacknowledgedBatches = Math.max(
                Number(state.maxUnacknowledgedBatches) || 0,
                Math.max(0, unacknowledged | 0));
            } catch (_) {
              // Optional diagnostics must never perturb PlayerChunkSender.
            }
            """)
    private static native void recordChunkSenderState(
            int phase, int pending, int selected, int unacknowledged,
            int playerChunkX, int playerChunkZ);

    /**
     * Applies the local browser player's absolute movement without entering vanilla collision
     * lookup.  The browser integrated player is a creative owner; the normal movement packet
     * path performs a synchronous ServerLevel collision scan and can pull the cooperative Worker
     * into a multi-second chunk wait.  Chunk priority still receives the exact position, while
     * chunk-view maintenance remains staged outside the packet handler.
     */
    public static void applyWorkerMovement(
            ServerPlayer player, ServerboundMovePlayerPacket packet) {
        if (player == null || packet == null) {
            return;
        }
        double x = packet.getX(player.getX());
        double y = packet.getY(player.getY());
        double z = packet.getZ(player.getZ());
        float yaw = packet.getYRot(player.getYRot());
        float pitch = packet.getXRot(player.getXRot());
        if (Double.isNaN(x) || Double.isInfinite(x)
                || Double.isNaN(y) || Double.isInfinite(y)
                || Double.isNaN(z) || Double.isInfinite(z)
                || Float.isNaN(yaw) || Float.isInfinite(yaw)
                || Float.isNaN(pitch) || Float.isInfinite(pitch)) {
            return;
        }
        player.absSnapTo(x, y, z, yaw, pitch);
        player.setOnGround(packet.isOnGround());
        BrowserChunkTaskPriority.recordPlayerPosition(x, z);
        moveServerPlayerChunkTracking(player.level().getChunkSource(), player);
    }

    private static int floorSectionCoordinate(double coordinate) {
        double section = Math.floor(coordinate / 16.0D);
        if (section <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (section >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) section;
    }

    /**
     * {@code MinecraftServer.pollTask} is a patched server-loop boundary. TeaVM can resume the
     * helper coroutine with a Java {@link Thread} object that is not the one which actually runs
     * that boundary, so the constructor-provided thread is not a reliable execution identity.
     * Only this server-loop callback is allowed to refresh the binding; JavaScript wakeups and
     * the queued input task must never adopt their own helper thread.
     */
    private static boolean bindServerThreadFromServerLoop(MinecraftServer current) {
        if (current != server || serverThreadExited || !current.isRunning()) {
            return false;
        }
        Thread actualServerThread = Thread.currentThread();
        if (actualServerThread == null) {
            return false;
        }
        if (serverThread != actualServerThread) {
            serverThread = actualServerThread;
            reportRuntimeEvent(
                    "network-pump-server-thread-bound",
                    "bound from MinecraftServer.pollTask");
        }
        return true;
    }

    /** Wakes the parked server thread without executing packet handlers from JavaScript. */
    @JSExport
    public static void signalIntegratedServerNetworkInput() {
        recordNetworkPumpState(0, NETWORK_INPUT_TASK_SCHEDULED.get());
        recordNetworkInputPending(true);
        scheduleNetworkInputTask(false, true);
    }

    private static boolean scheduleNetworkInputTask(boolean followup, boolean externalSignal) {
        MinecraftServer current = server;
        Thread currentServerThread = serverThread;
        if (current == null || currentServerThread == null || serverThreadExited
                || !current.isRunning()) {
            recordNetworkPumpState(7, false);
            return false;
        }
        if (!NETWORK_INPUT_TASK_SCHEDULED.compareAndSet(false, true)) {
            // Coalesced input can arrive after the queued task consumed its original permit but
            // before the server reaches another cooperative wait. Refresh that permit without
            // ever decoding or handling packets on this helper coroutine.
            LockSupport.unpark(currentServerThread);
            recordNetworkPumpState(1, true);
            recordNetworkPumpState(2, true);
            return false;
        }
        if (!followup) {
            if (!externalSignal) {
                networkInputBurstActive = true;
                networkInputFollowupsRemaining = MAX_NETWORK_INPUT_FOLLOWUPS;
            } else if (!networkInputBurstActive) {
                networkInputBurstActive = true;
                networkInputFollowupsRemaining = MAX_NETWORK_INPUT_FOLLOWUPS;
                networkInputDeferredRetriesRemaining =
                        MAX_NETWORK_INPUT_DEFERRED_RETRIES;
            }
        }
        try {
            // MinecraftServer.shouldRun delays current-tick tasks whenever worldgen exhausts the
            // tick budget. Mark this internal pump as overdue so player input cannot starve while
            // the server is waiting on chunk work; execution still remains on the server thread.
            TickTask task = new TickTask(Integer.MIN_VALUE, NETWORK_INPUT_TASK);
            scheduledNetworkInputTask = task;
            current.schedule(task);
            // Vanilla schedule wakes after enqueueing. Keep an explicit post-enqueue wake here so
            // this browser-specific contract does not depend on an incidental scheduler detail.
            LockSupport.unpark(currentServerThread);
            recordNetworkPumpState(1, true);
            recordNetworkPumpState(3, true);
            if (followup) {
                recordNetworkPumpState(6, true);
            }
            return true;
        } catch (RuntimeException | Error exception) {
            NETWORK_INPUT_TASK_SCHEDULED.set(false);
            scheduledNetworkInputTask = null;
            activeNetworkInputTask = null;
            recordNetworkPumpState(4, false);
            reportRuntimeEvent("network-pump-schedule-error", String.valueOf(exception));
            return false;
        }
    }

    private static void runScheduledNetworkInput() {
        boolean pumped = false;
        try {
            pumped = drainScheduledNetworkInput();
            if (!pumped && !networkInputReschedulePending) {
                recordNetworkPumpState(8, true);
                reportRuntimeEvent(
                    "network-pump-wrong-thread",
                    "Scheduled input task lost its integrated server lifecycle permit");
            }
        } catch (RuntimeException | Error exception) {
            reportRuntimeEvent("network-pump-error", String.valueOf(exception));
        } finally {
            NETWORK_INPUT_TASK_SCHEDULED.set(false);
            recordNetworkPumpState(5, false);
        }
        if (!pumped && networkInputReschedulePending) {
            return;
        }
        if (!pumped) {
            retryNetworkInputAfterTaskFailure();
            return;
        }
        boolean inputPending = hasPendingNetworkInput();
        recordNetworkInputPending(inputPending);
        if (!inputPending) {
            finishNetworkInputBurst();
            return;
        }
        MinecraftServer current = server;
        if (current == null || serverThread == null || serverThreadExited
                || !current.isRunning()) {
            finishNetworkInputBurst();
            recordNetworkPumpState(7, false);
            return;
        }
        if (networkInputFollowupsRemaining <= 0) {
            recordNetworkPumpState(9, false);
            deferNetworkInputRetry();
            return;
        }
        networkInputFollowupsRemaining--;
        scheduleNetworkInputTask(true, false);
    }

    /**
     * A TickTask can be resumed by a stale TeaVM continuation before the server-loop binding has
     * been refreshed. The old path cleared the task permit and returned, silently leaving the
     * browser input queue behind. Keep the pending signal, then use the existing bounded delayed
     * retry path. The retry resumes the same continuation and never starts a Java helper thread.
     */
    private static void retryNetworkInputAfterTaskFailure() {
        boolean inputPending = hasPendingNetworkInput();
        recordNetworkInputPending(inputPending);
        if (!inputPending) {
            finishNetworkInputBurst();
            return;
        }
        MinecraftServer current = server;
        if (current == null || serverThread == null || serverThreadExited
                || !current.isRunning()) {
            finishNetworkInputBurst();
            recordNetworkPumpState(7, false);
            reportRuntimeEvent(
                    "network-pump-lifecycle-drop",
                    "Pending input remained after the integrated server stopped");
            return;
        }
        if (!networkInputBurstActive) {
            networkInputBurstActive = true;
            networkInputFollowupsRemaining = 0;
            networkInputDeferredRetriesRemaining = MAX_NETWORK_INPUT_DEFERRED_RETRIES;
        }
        deferNetworkInputRetry();
    }

    private static void deferNetworkInputRetry() {
        if (networkInputDeferredRetriesRemaining <= 0) {
            // World generation can hold the event loop longer than the short retry
            // ladder. Keep the pending input burst alive and restart the ladder with
            // a bounded backoff instead of declaring a failure and dropping the
            // browser wakeup permit.
            recordNetworkPumpState(11, false);
            networkInputDeferredRetriesRemaining = MAX_NETWORK_INPUT_DEFERRED_RETRIES;
            TModernRuntimeSupport.yieldToEventLoop(8);
            if (hasPendingNetworkInput()) {
                scheduleNetworkInputTask(false, false);
            } else {
                finishNetworkInputBurst();
                recordNetworkInputPending(false);
            }
            return;
        }
        int retry = MAX_NETWORK_INPUT_DEFERRED_RETRIES
                - networkInputDeferredRetriesRemaining;
        networkInputDeferredRetriesRemaining--;
        int delayMillis = 1 << Math.min(3, retry);
        recordNetworkPumpState(10, false);
        TModernRuntimeSupport.yieldToEventLoop(delayMillis);
        if (!hasPendingNetworkInput()) {
            finishNetworkInputBurst();
            recordNetworkInputPending(false);
            return;
        }
        MinecraftServer current = server;
        if (current == null || serverThread == null || serverThreadExited
                || !current.isRunning()) {
            finishNetworkInputBurst();
            recordNetworkPumpState(7, false);
            return;
        }
        scheduleNetworkInputTask(false, false);
    }

    private static void finishNetworkInputBurst() {
        networkInputBurstActive = false;
        networkInputFollowupsRemaining = 0;
        networkInputDeferredRetriesRemaining = 0;
    }

    private static boolean hasPendingNetworkInput() {
        return BrowserWebSocketChannel.hasPendingInput()
                || BrowserPacketScheduler.hasPendingPackets();
    }

    /** The helper coroutine only wakes the server thread; Netty decoding stays on that thread. */
    @JSExport
    public static void pumpIntegratedServerNetworkInput() {
        signalIntegratedServerNetworkInput();
    }

    public static DataFixer dataFixer() {
        return isWorkerRuntime()
                ? BrowserLazyDataFixer.instance()
                : net.minecraft.util.datafix.DataFixers.getDataFixer();
    }

    static void reportRuntimeEvent(String event, String detail) {
        if (isWorkerRuntime()) {
            report(event, detail);
        }
    }

    @JSBody(params = {"event", "pending"}, script = """
            const stats = globalThis.__gaiusNetworkStats;
            if (!stats) return;
            if (stats.integratedServerTaskTelemetryVersion !== 1) {
              stats.integratedServerPumpRequests =
                Number(stats.integratedServerPumpRequests) || 0;
              stats.integratedServerPumpStarts =
                Number(stats.integratedServerPumpStarts) || 0;
              stats.integratedServerPumpFailures =
                Number(stats.integratedServerPumpFailures) || 0;
              stats.integratedServerPumpRetrySchedules =
                Number(stats.integratedServerPumpRetrySchedules) || 0;
              stats.integratedServerPumpRetryExhaustions =
                Number(stats.integratedServerPumpRetryExhaustions) || 0;
              stats.integratedServerTaskSignals =
                Number(stats.integratedServerTaskSignals) || 0;
              stats.integratedServerTaskUnparks =
                Number(stats.integratedServerTaskUnparks) || 0;
              stats.integratedServerTaskCoalesced =
                Number(stats.integratedServerTaskCoalesced) || 0;
              stats.integratedServerTaskSchedules =
                Number(stats.integratedServerTaskSchedules) || 0;
              stats.integratedServerTaskScheduleFailures =
                Number(stats.integratedServerTaskScheduleFailures) || 0;
              stats.integratedServerTaskRuns =
                Number(stats.integratedServerTaskRuns) || 0;
              stats.integratedServerTaskFollowups =
                Number(stats.integratedServerTaskFollowups) || 0;
              stats.integratedServerTaskLifecycleDrops =
                Number(stats.integratedServerTaskLifecycleDrops) || 0;
              stats.integratedServerTaskWrongThread =
                Number(stats.integratedServerTaskWrongThread) || 0;
              stats.integratedServerTaskBudgetExhaustions =
                Number(stats.integratedServerTaskBudgetExhaustions) || 0;
              stats.integratedServerTaskDeferredRetries =
                Number(stats.integratedServerTaskDeferredRetries) || 0;
              stats.integratedServerTaskRetryExhaustions =
                Number(stats.integratedServerTaskRetryExhaustions) || 0;
              stats.integratedServerTaskPending = 0;
              stats.integratedServerInputPending = 0;
              stats.integratedServerTaskTelemetryVersion = 1;
            }
            var field = '';
            switch (event | 0) {
              case 0: field = 'integratedServerTaskSignals'; break;
              case 1: field = 'integratedServerTaskUnparks'; break;
              case 2: field = 'integratedServerTaskCoalesced'; break;
              case 3: field = 'integratedServerTaskSchedules'; break;
              case 4: field = 'integratedServerTaskScheduleFailures'; break;
              case 5: field = 'integratedServerTaskRuns'; break;
              case 6: field = 'integratedServerTaskFollowups'; break;
              case 7: field = 'integratedServerTaskLifecycleDrops'; break;
              case 8: field = 'integratedServerTaskWrongThread'; break;
              case 9: field = 'integratedServerTaskBudgetExhaustions'; break;
              case 10: field = 'integratedServerTaskDeferredRetries'; break;
              case 11: field = 'integratedServerTaskRetryExhaustions'; break;
            }
            if (field) stats[field] = (Number(stats[field]) || 0) + 1;
            stats.integratedServerTaskPending = pending ? 1 : 0;
            """)
    private static native void recordNetworkPumpState(int event, boolean pending);

    @JSBody(params = "pending", script = """
            const stats = globalThis.__gaiusNetworkStats;
            if (stats) stats.integratedServerInputPending = pending ? 1 : 0;
            """)
    private static native void recordNetworkInputPending(boolean pending);

    @JSBody(script = "return globalThis.__gaiusSlowProbeTelemetryEnabled === true;")
    private static native boolean distanceApplyTelemetryEnabled();

    @JSBody(script = """
            return typeof performance !== 'undefined' && performance.now
              ? performance.now()
              : Date.now();
            """)
    private static native double distanceApplyNowMillis();

    @JSBody(params = {"kind", "durationMillis"}, script = """
            try {
              if (globalThis.__gaiusSlowProbeTelemetryEnabled !== true) return;
              const stats = globalThis.__gaiusNetworkStats;
              if (!stats) return;
              const field = (kind | 0) === 0
                ? 'integratedServerDistanceMaxViewApplyMillis'
                : 'integratedServerDistanceMaxSimulationApplyMillis';
              const duration = Math.max(0, Number(durationMillis) || 0);
              stats[field] = Math.max(Number(stats[field]) || 0, duration);
            } catch (ignored) {
              // Diagnostic telemetry is fail-open.
            }
            """)
    private static native void recordDistanceApplyDuration(int kind, double durationMillis);

    /** Keeps one opt-in diagnostic snapshot for the staged server-distance ramp. */
    private static void recordDistanceRampTelemetry(String reason) {
        if (!isWorkerRuntime() || !distanceRampTelemetryEnabled()) {
            return;
        }
        // No synthetic ACK cardinality gate is used by this policy.
        int requiredChunkCount = 0;
        int queuedEntries = 0;
        for (Integer batch : sentChunkBatches) {
            if (batch != null && batch > 0) {
                queuedEntries += batch;
            }
        }
        recordDistanceRampTelemetrySnapshot(
                reason,
                configuredViewDistance,
                configuredSimulationDistance,
                activeViewDistance,
                activeSimulationDistance,
                configuredDistancesActive,
                distanceAdvancePending,
                acknowledgedChunkCount,
                sentChunkBatches.size(),
                queuedEntries,
                requiredChunkCount);
    }

    @JSBody(params = {
            "reason", "configuredView", "configuredSimulation", "activeView",
            "activeSimulation", "configuredActive", "advancePending", "acknowledged",
            "sentQueueLength", "sentQueueEntries", "requiredChunkCount"
    }, script = """
            try {
              if (globalThis.__gaiusServerTickTelemetryEnabled !== true &&
                  globalThis.__gaiusSlowProbeTelemetryEnabled !== true) return;
              globalThis.__gaiusServerDistanceTelemetry = {
                schemaVersion: 1,
                reason: String(reason || ''),
                configuredViewDistance: configuredView | 0,
                configuredSimulationDistance: configuredSimulation | 0,
                activeViewDistance: activeView | 0,
                activeSimulationDistance: activeSimulation | 0,
                configuredDistancesActive: configuredActive === true,
                distanceAdvancePending: advancePending === true,
                acknowledgedChunkCount: Math.max(0, acknowledged | 0),
                sentQueueLength: Math.max(0, sentQueueLength | 0),
                sentQueueEntries: Math.max(0, sentQueueEntries | 0),
                requiredChunkCount: Math.max(0, requiredChunkCount | 0),
                updatedAt: typeof performance !== 'undefined' && performance.now
                  ? performance.now() : Date.now()
              };
            } catch (ignored) {
              // Diagnostic telemetry is fail-open.
            }
            """)
    private static native void recordDistanceRampTelemetrySnapshot(
            String reason,
            int configuredView,
            int configuredSimulation,
            int activeView,
            int activeSimulation,
            boolean configuredActive,
            boolean advancePending,
            int acknowledged,
            int sentQueueLength,
            int sentQueueEntries,
            int requiredChunkCount);

    @JSBody(script = "return globalThis.__gaiusServerTickTelemetryEnabled === true || "
            + "globalThis.__gaiusSlowProbeTelemetryEnabled === true;")
    private static native boolean distanceRampTelemetryEnabled();

    /** Vanilla's minimum of two forces 25 chunks before a browser player can enter. */
    public static int minimumServerViewDistance() {
        return isWorkerRuntime() ? INITIAL_VIEW_DISTANCE : 2;
    }

    /** Keeps local block breaking tied to wall time when world generation lowers server TPS. */
    public static int adjustDestroyTicks(int serverTicks, long startedAtMillis) {
        if (!isWorkerRuntime() || startedAtMillis <= 0L) {
            return serverTicks;
        }
        long elapsedMillis = Math.max(0L, System.currentTimeMillis() - startedAtMillis);
        int wallTicks = (int) Math.min(Integer.MAX_VALUE, elapsedMillis / 50L);
        return Math.max(serverTicks, wallTicks);
    }

    /** The local client sends STOP only after its validated break progress has completed. */
    public static float completeLocalDestroyProgress(float progress) {
        return isWorkerRuntime() ? Math.max(progress, 0.7F) : progress;
    }

    /** Re-throws the failure swallowed by the vanilla dedicated-server entry point. */
    public static void rethrowStartupFailure(Throwable exception) {
        if (exception instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (exception instanceof Error error) {
            throw error;
        }
        throw new RuntimeException(exception);
    }

    @JSExport
    public static void stopIntegratedServer() {
        finishNetworkInputBurst();
        recordNetworkInputPending(false);
        MinecraftServer current = server;
        if (current != null && current.isRunning()) {
            report("stopping", workerWorldId());
            current.halt(false);
        }
    }

    @JSExport
    public static boolean isIntegratedServerStopped() {
        MinecraftServer current = server;
        if (current == null || !serverThreadExited) {
            return current == null;
        }
        if (!isWorkerRuntime() || !storageFlushRequested) {
            return true;
        }
        String phase = integratedServerStorageFlushPhase();
        if ("pending".equals(phase)) {
            if (integratedServerStorageFlushElapsedMillis() >= STORAGE_FLUSH_ACK_TIMEOUT_MILLIS) {
                if (!storageFlushTimeoutReported) {
                    storageFlushTimeoutReported = true;
                    expireIntegratedServerStorageFlush();
                    report(
                            "storage-flush-timeout",
                            STORAGE_FLUSH_ACK_TIMEOUT_MILLIS + "ms");
                }
                return true;
            }
            return false;
        }
        return true;
    }

    /** Called after MinecraftServer.runServer has completed all save and exit work. */
    public static void markIntegratedServerStopped(MinecraftServer minecraftServer) {
        if (server == minecraftServer) {
            serverThreadExited = true;
            serverThread = null;
            appliedDistancePlayerList = null;
            appliedViewDistance = Integer.MIN_VALUE;
            appliedSimulationDistance = Integer.MIN_VALUE;
            finishNetworkInputBurst();
            NETWORK_INPUT_TASK_SCHEDULED.set(false);
            scheduledNetworkInputTask = null;
            activeNetworkInputTask = null;
            recordNetworkPumpState(-1, false);
            recordNetworkInputPending(false);
            if (isWorkerRuntime()) {
                storageFlushRequested = true;
                beginIntegratedServerStorageFlush();
            }
            report("server-thread-exited", workerWorldId());
        }
    }

    public static void main(String[] args) {
        try {
            awaitIndexedDbFallbackHydration();
            BrowserFilePersistence.mount();
            String worldId = workerWorldId();
            String sessionId = workerSessionId();
            if (!isSafeIdentifier(worldId) || !isSafeSessionId(sessionId)) {
                throw new IllegalArgumentException("Invalid browser singleplayer worker initialization");
            }
            writeServerConfiguration();
            report("booting", worldId);
            Main.main(new String[] {
                    "--nogui",
                    "--universe", "/gaius/saves",
                    "--world", worldId,
                    "--port", "25565"
            });
        } catch (Throwable exception) {
            report("crash", describeWithStack(exception));
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (exception instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(exception);
        }
    }

    /**
     * IndexedDB has no synchronous read API. The bootstrap therefore keeps a bounded
     * rehydration mirror for regions that its compatibility LRU had to evict before Java
     * opened the world. A failed or over-budget rehydration stops startup explicitly.
     */
    private static void awaitIndexedDbFallbackHydration() {
        if (!isWorkerRuntime()) {
            return;
        }
        indexedDbFallbackHydrationFailure = null;
        indexedDbFallbackHydrationPending = beginIndexedDbFallbackHydration(
                INDEXED_DB_FALLBACK_REHYDRATION_BUDGET_BYTES,
                INDEXED_DB_FALLBACK_REHYDRATION_MAX_ENTRIES);
        if (!indexedDbFallbackHydrationPending) {
            return;
        }
        long deadline = System.currentTimeMillis()
                + INDEXED_DB_FALLBACK_HYDRATION_TIMEOUT_MILLIS;
        while (indexedDbFallbackHydrationPending && System.currentTimeMillis() < deadline) {
            TModernRuntimeSupport.yieldToEventLoop(0);
        }
        if (indexedDbFallbackHydrationPending) {
            indexedDbFallbackHydrationPending = false;
            cancelIndexedDbFallbackHydration();
            throw new IllegalStateException(
                    "IndexedDB region rehydration timed out after "
                            + INDEXED_DB_FALLBACK_HYDRATION_TIMEOUT_MILLIS + " ms");
        }
        if (indexedDbFallbackHydrationFailure != null) {
            throw new IllegalStateException(indexedDbFallbackHydrationFailure);
        }
    }

    @JSExport
    public static void completeIndexedDbFallbackHydration(boolean success, String detail) {
        if (!indexedDbFallbackHydrationPending) {
            return;
        }
        indexedDbFallbackHydrationPending = false;
        indexedDbFallbackHydrationFailure = success
                ? null
                : (detail == null || detail.isEmpty()
                        ? "IndexedDB region rehydration failed"
                        : detail);
    }

    private static void writeServerConfiguration() throws Exception {
        Path properties = Path.of("server.properties");
        Files.writeString(
                properties,
                serverProperties(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        Files.writeString(
                Path.of("eula.txt"),
                "eula=true\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private static boolean isSafeIdentifier(String value) {
        return value != null
                && !value.isEmpty()
                && value.length() <= 128
                && value.indexOf('/') < 0
                && value.indexOf('\\') < 0
                && !value.equals(".")
                && !value.equals("..");
    }

    private static int clampDistance(int value, int fallback) {
        int selected = value > 0 ? value : fallback;
        return Math.max(2, Math.min(32, selected));
    }

    private static boolean isSafeSessionId(String value) {
        if (value == null || value.length() != 32) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static String describe(Throwable exception) {
        String message = exception.getMessage();
        return exception.getClass().getName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    private static String describeWithStack(Throwable exception) {
        StringBuilder description = new StringBuilder();
        String nativeStack = nativeStack(exception);
        if (nativeStack != null && !nativeStack.isEmpty()) {
            description.append(nativeStack);
        }
        Throwable current = exception;
        int causeCount = 0;
        while (current != null && causeCount < 4 && description.length() < 12_000) {
            if (description.length() > 0) {
                description.append("\nCaused by: ");
            }
            description.append(describe(current));
            StackTraceElement[] stack = current.getStackTrace();
            int frameCount = Math.min(stack.length, 32);
            for (int index = 0; index < frameCount && description.length() < 12_000; index++) {
                description.append("\n  at ").append(stack[index]);
            }
            current = current.getCause();
            causeCount++;
        }
        return description.toString();
    }

    @JSBody(params = "exception", script = """
            try {
              var nativeError = exception && exception.$jsException;
              return String(nativeError && (nativeError.stack || nativeError.message) || '');
            } catch (ignored) {
              return '';
            }
            """)
    private static native String nativeStack(Throwable exception);

    @JSBody(script = "return String(globalThis.__gaiusServerWorldId || '');")
    private static native String workerWorldId();

    @JSBody(script = "return typeof WorkerGlobalScope !== 'undefined' && globalThis instanceof WorkerGlobalScope;")
    private static native boolean isWorkerRuntime();

    @JSBody(script = "return String(globalThis.__gaiusServerSessionId || '');")
    private static native String workerSessionId();

    @JSBody(script = "return String(globalThis.__gaiusServerSeed || '');")
    private static native String workerSeed();

    @JSBody(script = "return Number(globalThis.__gaiusServerViewDistance || 8) | 0;")
    private static native int workerViewDistance();

    @JSBody(script = "return Number(globalThis.__gaiusServerSimulationDistance || 6) | 0;")
    private static native int workerSimulationDistance();

    @JSBody(params = {"maxBytes", "maxEntries"}, script = """
            try {
              const root = globalThis;
              if (String(root.__gaiusFsBackend || '') !== 'indexeddb-worker-lru') return false;
              const originalFiles = root.__gaiusPersistentFiles;
              const worldId = String(root.__gaiusServerWorldId || '');
              if (!originalFiles || !worldId || typeof indexedDB === 'undefined') return false;
              const profileId = String(root.__gaiusProfileId || '').trim();
              const worldVersion = Number(root.__gaiusWorldVersion);
              const storageDatabaseName = String(root.__gaiusStorageDatabaseName || '').trim();
              const storagePrefix = String(root.__gaiusStoragePrefix || '');
              const storageOpfsDirectory = String(root.__gaiusStorageOpfsDirectory || '').trim();
              const storageSchema = Number(root.__gaiusStorageSchema);
              const storageMatchesProfile =
                (profileId === '1.21.11' && worldVersion === 4671 &&
                  storageSchema === 2 &&
                  storageDatabaseName === 'gaius-fs-v2-1.21.11' &&
                  storagePrefix === 'gaius.fs.v2:1.21.11:' &&
                  storageOpfsDirectory === 'regions-v2-1.21.11') ||
                (profileId === '26.2' && worldVersion === 4903 &&
                  storageSchema === 2 &&
                  storageDatabaseName === 'gaius-fs-v2-26.2' &&
                  storagePrefix === 'gaius.fs.v2:26.2:' &&
                  storageOpfsDirectory === 'regions-v2-26.2');
              if (!storageMatchesProfile) {
                Promise.resolve().then(() => {
                  if (typeof completeIndexedDbFallbackHydration === 'function') {
                    completeIndexedDbFallbackHydration(false,
                      'IndexedDB storage configuration does not match profile');
                  }
                });
                return true;
              }
              const prefix = '/gaius/saves/' + worldId + '/';
              const state = {cancelled: false};
              root.__gaiusIndexedDbFallbackHydrationState = state;
              const isRegionPath = path => String(path || '').endsWith('.mca') ||
                String(path || '').endsWith('.mcc');
              const normalize = path => {
                const value = String(path || '/').replace(/\\\\/g, '/');
                return value.startsWith('/') ? value : '/' + value;
              };
              const storedByteLength = value => {
                if (typeof value === 'string') {
                  if (!value.length) return 0;
                  const padding = value.endsWith('==') ? 2 : (value.endsWith('=') ? 1 : 0);
                  return Math.max(0, Math.floor(value.length * 3 / 4) - padding);
                }
                if (value && value.encoding === 'gzip') {
                  return storedByteLength(value.bytes);
                }
                if (value instanceof ArrayBuffer) return value.byteLength;
                if (ArrayBuffer.isView(value)) return value.byteLength;
                return -1;
              };
              const copyValue = value => {
                if (typeof value === 'string' || value == null) return value;
                if (value instanceof Uint8Array) return value.slice();
                if (value instanceof ArrayBuffer) return value.slice(0);
                if (ArrayBuffer.isView(value)) {
                  return new Uint8Array(value.buffer.slice(
                    value.byteOffset,
                    value.byteOffset + value.byteLength));
                }
                return value;
              };
              const decodeValue = value => {
                if (!value || value.encoding !== 'gzip') return Promise.resolve(value);
                const compressed = value.bytes instanceof Uint8Array
                  ? value.bytes
                  : new Uint8Array(value.bytes);
                if (typeof DecompressionStream !== 'function' ||
                    typeof Blob !== 'function' || typeof Response !== 'function') {
                  return Promise.reject(new Error('Compressed IndexedDB region is unavailable'));
                }
                const stream = new Blob([compressed]).stream()
                  .pipeThrough(new DecompressionStream('gzip'));
                return new Response(stream).arrayBuffer().then(bytes => new Uint8Array(bytes));
              };
              const open = () => new Promise((resolve, reject) => {
                const request = indexedDB.open(storageDatabaseName, storageSchema);
                request.onsuccess = () => resolve(request.result);
                request.onerror = () => reject(request.error || new Error('IndexedDB open failed'));
                request.onblocked = () => reject(new Error('IndexedDB open blocked'));
              });
              const collect = database => new Promise((resolve, reject) => {
                try {
                  const transaction = database.transaction('files', 'readonly');
                  const store = transaction.objectStore('files');
                  const range = typeof IDBKeyRange !== 'undefined'
                    ? IDBKeyRange.bound(prefix, prefix + String.fromCharCode(65535))
                    : undefined;
                  const request = store.openCursor(range);
                  const missing = [];
                  var rawBytes = 0;
                  request.onsuccess = () => {
                    const cursor = request.result;
                    if (!cursor) {
                      resolve(missing);
                      return;
                    }
                    const entry = cursor.value || {};
                    const path = normalize(cursor.key !== undefined ? cursor.key : entry.path);
                    if (path.startsWith(prefix) && isRegionPath(path)) {
                      var cached;
                      try { cached = originalFiles[path]; } catch (ignored) {}
                      const value = cached === undefined || cached === null
                        ? entry.value
                        : cached;
                      const length = storedByteLength(value);
                      if (length < 0) {
                        reject(new Error('Unsupported IndexedDB region value: ' + path));
                        return;
                      }
                      rawBytes += length;
                      if (missing.length >= Number(maxEntries) ||
                          rawBytes > Number(maxBytes)) {
                        reject(new Error(
                          'IndexedDB region rehydration exceeds bounded memory budget'));
                        return;
                      }
                      missing.push({path: path, value: value});
                    }
                    cursor.continue();
                  };
                  request.onerror = () => reject(
                    request.error || new Error('IndexedDB region cursor failed'));
                } catch (error) {
                  reject(error);
                }
              });
              const install = hydrated => {
                const fallbackFiles = new Proxy(originalFiles, {
                  get: function(target, property, receiver) {
                    const value = Reflect.get(target, property, receiver);
                    if (value !== undefined && value !== null) return value;
                    return hydrated.has(property) ? hydrated.get(property) : value;
                  },
                  has: function(target, property) {
                    return hydrated.has(property) || Reflect.has(target, property);
                  },
                  ownKeys: function(target) {
                    const keys = Reflect.ownKeys(target);
                    hydrated.forEach(function(ignoredValue, path) {
                      if (!keys.includes(path)) keys.push(path);
                    });
                    return keys;
                  },
                  getOwnPropertyDescriptor: function(target, property) {
                    const descriptor = Reflect.getOwnPropertyDescriptor(target, property);
                    if (descriptor) return descriptor;
                    if (hydrated.has(property)) {
                      return {configurable: true, enumerable: true,
                        value: hydrated.get(property), writable: false};
                    }
                    return undefined;
                  },
                });
                root.__gaiusPersistentFiles = fallbackFiles;
                const previousPutBytes = root.__gaiusFsPutBytes;
                if (typeof previousPutBytes === 'function') {
                  root.__gaiusFsPutBytes = (path, value) => {
                    const normalized = normalize(path);
                    const stored = previousPutBytes(path, value);
                    if (stored && isRegionPath(normalized)) {
                      hydrated.set(normalized, copyValue(value));
                    }
                    return stored;
                  };
                }
                const previousPut = root.__gaiusFsPut;
                if (typeof previousPut === 'function') {
                  root.__gaiusFsPut = (path, value) => {
                    const normalized = normalize(path);
                    const stored = previousPut(path, value);
                    if (stored && isRegionPath(normalized)) hydrated.set(normalized, value);
                    return stored;
                  };
                }
                const previousDelete = root.__gaiusFsDelete;
                if (typeof previousDelete === 'function') {
                  root.__gaiusFsDelete = path => {
                    const normalized = normalize(path);
                    const deleted = previousDelete(path);
                    if (deleted) hydrated.delete(normalized);
                    return deleted;
                  };
                }
              };
              var openedDatabase;
              open().then(database => {
                openedDatabase = database;
                return collect(database).then(missing => {
                  database.close();
                  if (state.cancelled) return;
                  const hydrated = new Map();
                  var hydratedBytes = 0;
                  var chain = Promise.resolve();
                  missing.forEach(function(entry) {
                    chain = chain.then(() => decodeValue(entry.value)).then(value => {
                      if (state.cancelled) return;
                      const length = storedByteLength(value);
                      if (length < 0 || hydrated.size >= Number(maxEntries) ||
                          hydratedBytes + length > Number(maxBytes)) {
                        throw new Error(
                          'Decoded IndexedDB regions exceed bounded memory budget');
                      }
                      hydratedBytes += length;
                      hydrated.set(entry.path, copyValue(value));
                    });
                  });
                  return chain.then(() => {
                    if (state.cancelled) return;
                    install(hydrated);
                    if (typeof postMessage === 'function') {
                      postMessage({type: 'storage-index-rehydrated',
                        detail: hydrated.size + ' regions'});
                    }
                    if (typeof completeIndexedDbFallbackHydration === 'function') {
                      completeIndexedDbFallbackHydration(true, hydrated.size + ' regions');
                    }
                  });
                });
              }).catch(error => {
                try { if (openedDatabase) openedDatabase.close(); } catch (ignored) {}
                if (state.cancelled) return;
                const detail = String(error && (error.stack || error.message) || error);
                if (typeof postMessage === 'function') {
                  postMessage({type: 'storage-index-rehydration-failed', detail: detail});
                }
                if (typeof completeIndexedDbFallbackHydration === 'function') {
                  completeIndexedDbFallbackHydration(false, detail);
                }
              });
              return true;
            } catch (error) {
              if (typeof completeIndexedDbFallbackHydration === 'function') {
                completeIndexedDbFallbackHydration(false,
                  String(error && (error.stack || error.message) || error));
              }
              return true;
            }
            """)
    private static native boolean beginIndexedDbFallbackHydration(
            int maxBytes, int maxEntries);

    @JSBody(script = """
            try {
              const state = globalThis.__gaiusIndexedDbFallbackHydrationState;
              if (state) state.cancelled = true;
            } catch (ignored) {}
            """)
    private static native void cancelIndexedDbFallbackHydration();

    @JSBody(script = """
            try {
              const root = globalThis;
              const existing = root.__gaiusIntegratedServerStorageFlush;
              if (existing && existing.phase === 'pending') return;
              const state = {phase: 'pending', startedAt: Date.now()};
              root.__gaiusIntegratedServerStorageFlush = state;
              const flush = root.__gaiusFsFlush;
              if (typeof flush !== 'function') {
                state.phase = 'unavailable';
                return;
              }
              Promise.resolve(flush()).then(() => {
                if (state.phase !== 'pending') return;
                state.phase = 'ack';
                try { postMessage({type: 'storage-flush-ack', detail: root.__gaiusServerWorldId || ''}); }
                catch (ignored) {}
              }, error => {
                if (state.phase !== 'pending') return;
                state.phase = 'error';
                state.detail = String(error && (error.stack || error.message) || error);
                try { postMessage({type: 'storage-flush-error', detail: state.detail}); }
                catch (ignored) {}
              });
            } catch (error) {
              globalThis.__gaiusIntegratedServerStorageFlush = {
                phase: 'error',
                startedAt: Date.now(),
                detail: String(error && (error.stack || error.message) || error)
              };
            }
            """)
    private static native void beginIntegratedServerStorageFlush();

    @JSBody(script = """
            try {
              const state = globalThis.__gaiusIntegratedServerStorageFlush;
              return state && typeof state.phase === 'string' ? state.phase : 'unavailable';
            } catch (ignored) {
              return 'unavailable';
            }
            """)
    private static native String integratedServerStorageFlushPhase();

    @JSBody(script = """
            try {
              const state = globalThis.__gaiusIntegratedServerStorageFlush;
              return state && Number.isFinite(state.startedAt)
                ? Math.max(0, Date.now() - state.startedAt)
                : 0;
            } catch (ignored) {
              return 0;
            }
            """)
    private static native long integratedServerStorageFlushElapsedMillis();

    @JSBody(script = """
            try {
              const state = globalThis.__gaiusIntegratedServerStorageFlush;
              if (state && state.phase === 'pending') {
                state.phase = 'timeout';
                try { postMessage({type: 'storage-flush-timeout'}); } catch (ignored) {}
              }
            } catch (ignored) {}
            """)
    private static native void expireIntegratedServerStorageFlush();

    @JSBody(params = {"event", "detail"}, script = """
            try {
              postMessage({type: String(event), detail: String(detail), at: Date.now()});
            } catch (ignored) {}
            """)
    private static native void report(String event, String detail);
}
