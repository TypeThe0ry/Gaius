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
import org.teavm.classlib.java.lang.TModernRuntimeSupport;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSExport;

/** TeaVM entry point for the official dedicated-server runtime inside a Web Worker. */
public final class BrowserIntegratedServerMain {
    private static final int INITIAL_VIEW_DISTANCE = 1;
    private static final int INITIAL_SIMULATION_DISTANCE = 1;
    private static final long DEFAULT_DISTANCE_RAMP_INTERVAL_MILLIS = 750L;
    private static final long STORAGE_FLUSH_ACK_TIMEOUT_MILLIS = 5000L;
    private static final long INDEXED_DB_FALLBACK_HYDRATION_TIMEOUT_MILLIS = 12000L;
    private static final int INDEXED_DB_FALLBACK_REHYDRATION_BUDGET_BYTES = 64 * 1024 * 1024;
    private static final int INDEXED_DB_FALLBACK_REHYDRATION_MAX_ENTRIES = 4096;
    private static final int MAX_NETWORK_INPUT_FOLLOWUPS = 4;
    private static final int MAX_NETWORK_INPUT_DEFERRED_RETRIES = 4;
    private static MinecraftServer server;
    private static Thread serverThread;
    private static boolean serverThreadExited = true;
    private static int configuredViewDistance = 6;
    private static int configuredSimulationDistance = 4;
    private static int activeViewDistance = INITIAL_VIEW_DISTANCE;
    private static int activeSimulationDistance = INITIAL_SIMULATION_DISTANCE;
    private static boolean configuredDistancesActive;
    private static boolean distanceAdvancePending;
    private static long nextDistanceAdvanceAtMillis;
    private static boolean urgentPacketPumpActive;
    private static final AtomicBoolean NETWORK_INPUT_TASK_SCHEDULED = new AtomicBoolean();
    private static boolean networkInputBurstActive;
    private static int networkInputFollowupsRemaining;
    private static int networkInputDeferredRetriesRemaining;
    private static boolean storageFlushRequested;
    private static boolean storageFlushTimeoutReported;
    private static boolean indexedDbFallbackHydrationPending;
    private static String indexedDbFallbackHydrationFailure;
    private static final Deque<Integer> sentChunkBatches = new ArrayDeque<>();
    private static int acknowledgedChunkCount;
    private static final Runnable NETWORK_INPUT_TASK =
            BrowserIntegratedServerMain::runScheduledNetworkInput;

    private static String serverProperties() {
        int viewDistance = clampDistance(workerViewDistance(), 6);
        int simulationDistance = clampDistance(workerSimulationDistance(), 4);
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
        configuredViewDistance = clampDistance(workerViewDistance(), 6);
        configuredSimulationDistance = clampDistance(workerSimulationDistance(), 4);
        activeViewDistance = INITIAL_VIEW_DISTANCE;
        activeSimulationDistance = INITIAL_SIMULATION_DISTANCE;
        configuredDistancesActive = false;
        distanceAdvancePending = false;
        nextDistanceAdvanceAtMillis = 0L;
        urgentPacketPumpActive = false;
        NETWORK_INPUT_TASK_SCHEDULED.set(false);
        networkInputBurstActive = false;
        networkInputFollowupsRemaining = 0;
        networkInputDeferredRetriesRemaining = 0;
        recordNetworkPumpState(-1, false);
        recordNetworkInputPending(false);
        storageFlushRequested = false;
        storageFlushTimeoutReported = false;
        indexedDbFallbackHydrationPending = false;
        indexedDbFallbackHydrationFailure = null;
        sentChunkBatches.clear();
        acknowledgedChunkCount = 0;
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
        configuredViewDistance = clampDistance(viewDistance, 6);
        configuredSimulationDistance = clampDistance(simulationDistance, 4);
        if (configuredDistancesActive) {
            activeViewDistance = Math.min(activeViewDistance, configuredViewDistance);
            activeSimulationDistance = Math.min(
                    activeSimulationDistance,
                    configuredSimulationDistance);
            if (distancesFullyApplied()) {
                distanceAdvancePending = false;
            }
        }
        applyActiveDistances();
    }

    private static void applyActiveDistances() {
        MinecraftServer current = server;
        if (current != null && current.getPlayerList() != null) {
            int view = configuredDistancesActive
                    ? activeViewDistance
                    : INITIAL_VIEW_DISTANCE;
            int simulation = configuredDistancesActive
                    ? activeSimulationDistance
                    : INITIAL_SIMULATION_DISTANCE;
            current.getPlayerList().setViewDistance(view);
            current.getPlayerList().setSimulationDistance(simulation);
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

    /**
     * Advances one distance ring after the client has consumed the preceding chunk batch.
     * This keeps unexplored-area generation behind the browser client's real packet throughput.
     */
    public static void recordChunkBatchSent(int batchSize) {
        if (isWorkerRuntime() && batchSize > 0) {
            sentChunkBatches.addLast(batchSize);
        }
    }

    public static void acknowledgeChunkBatch() {
        if (!isWorkerRuntime()) {
            return;
        }
        Integer batchSize = sentChunkBatches.pollFirst();
        if (batchSize == null) {
            reportRuntimeEvent("chunk-batch-ack-without-send", "queued=0");
            return;
        }
        acknowledgedChunkCount += batchSize;
        if (!configuredDistancesActive) {
            configuredDistancesActive = true;
            activeViewDistance = Math.min(configuredViewDistance, INITIAL_VIEW_DISTANCE + 1);
            activeSimulationDistance = INITIAL_SIMULATION_DISTANCE;
            nextDistanceAdvanceAtMillis = System.currentTimeMillis()
                    + distanceRampIntervalMillis();
            distanceAdvancePending = false;
            applyActiveDistances();
            return;
        }
        if (distancesFullyApplied()) {
            distanceAdvancePending = false;
            return;
        }
        if (!activeViewDistanceAcknowledged()) {
            distanceAdvancePending = true;
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextDistanceAdvanceAtMillis) {
            distanceAdvancePending = true;
            return;
        }
        distanceAdvancePending = false;
        advanceConfiguredDistances();
        nextDistanceAdvanceAtMillis = now + distanceRampIntervalMillis();
    }

    /** Applies a deferred distance ring only after the preceding ring has had CPU time. */
    public static void tickIntegratedServerDistances() {
        if (!isWorkerRuntime() || !distanceAdvancePending || distancesFullyApplied()) {
            return;
        }
        if (!activeViewDistanceAcknowledged()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextDistanceAdvanceAtMillis) {
            return;
        }
        distanceAdvancePending = false;
        advanceConfiguredDistances();
        nextDistanceAdvanceAtMillis = now + distanceRampIntervalMillis();
    }

    private static void advanceConfiguredDistances() {
        if (!isWorkerRuntime() || !configuredDistancesActive) {
            return;
        }
        int nextView = Math.min(configuredViewDistance, activeViewDistance + 1);
        int nextSimulation = Math.min(
                configuredSimulationDistance,
                activeSimulationDistance + 1);
        if (nextView == activeViewDistance && nextSimulation == activeSimulationDistance) {
            return;
        }
        activeViewDistance = nextView;
        activeSimulationDistance = nextSimulation;
        applyActiveDistances();
    }

    private static boolean distancesFullyApplied() {
        return activeViewDistance >= configuredViewDistance
                && activeSimulationDistance >= configuredSimulationDistance;
    }

    private static boolean activeViewDistanceAcknowledged() {
        int diameter = Math.max(1, activeViewDistance * 2 - 1);
        return acknowledgedChunkCount >= diameter * diameter;
    }

    @JSBody(params = "fallback", script = """
            const configured = Number(globalThis.__gaiusDistanceRampIntervalMillis);
            return Number.isFinite(configured) && configured >= 100 && configured <= 2000
              ? Math.round(configured)
              : fallback;
            """)
    private static native double configuredDistanceRampIntervalMillis(double fallback);

    private static long distanceRampIntervalMillis() {
        return (long) configuredDistanceRampIntervalMillis(
                DEFAULT_DISTANCE_RAMP_INTERVAL_MILLIS);
    }

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
        urgentPacketPumpActive = true;
        try {
            BrowserWebSocketChannel.pumpAll();
            current.packetProcessor().processQueuedPackets();
            return true;
        } finally {
            urgentPacketPumpActive = false;
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
            current.schedule(new TickTask(Integer.MIN_VALUE, NETWORK_INPUT_TASK));
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
            recordNetworkPumpState(4, false);
            reportRuntimeEvent("network-pump-schedule-error", String.valueOf(exception));
            return false;
        }
    }

    private static void runScheduledNetworkInput() {
        boolean pumped = false;
        try {
            pumped = drainUrgentPackets();
            if (!pumped) {
                recordNetworkPumpState(8, true);
                reportRuntimeEvent(
                        "network-pump-wrong-thread",
                        "Scheduled input task did not run on the integrated server thread");
            }
        } catch (RuntimeException | Error exception) {
            reportRuntimeEvent("network-pump-error", String.valueOf(exception));
        } finally {
            NETWORK_INPUT_TASK_SCHEDULED.set(false);
            recordNetworkPumpState(5, false);
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
            recordNetworkPumpState(11, false);
            finishNetworkInputBurst();
            reportRuntimeEvent(
                    "network-pump-retry-exhausted",
                    "Integrated server input remains queued after bounded retries");
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
            finishNetworkInputBurst();
            NETWORK_INPUT_TASK_SCHEDULED.set(false);
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

    @JSBody(script = "return Number(globalThis.__gaiusServerViewDistance || 6) | 0;")
    private static native int workerViewDistance();

    @JSBody(script = "return Number(globalThis.__gaiusServerSimulationDistance || 4) | 0;")
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
