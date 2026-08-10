package dev.gaius.browser;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import org.teavm.classlib.java.lang.TModernRuntimeSupport;
import org.teavm.jso.JSBody;
import org.teavm.jso.browser.Window;
import org.teavm.platform.Platform;

/** Defers expensive renderer work so one client frame cannot drain the whole compile queue. */
public final class BrowserRenderScheduler {
    private static final int MAX_TASKS_PER_FRAME = 8;
    private static final int QUEUE_HIGH_WATER = 8;
    private static final long FRAME_WORK_BUDGET_NANOS = 2_000_000L;
    private static final int MAX_UPLOAD_ALLOCATIONS_PER_FRAME = 8;
    private static final int MAX_COMPILE_RUNS_DURING_UPLOAD_PER_FRAME = 1;
    private static final long UPLOAD_WORK_BUDGET_NANOS = 2_000_000L;
    private static final int MAX_UPLOAD_RETRY_YIELDS = 2_048;
    private static final long MAX_UPLOAD_RETRY_NANOS = 5_000_000_000L;
    private static final Deque<Runnable> QUEUE = new ArrayDeque<>();
    private static final Map<Object, Integer> UPLOAD_BACKLOGS = new IdentityHashMap<>();
    private static final Map<Object, Integer> UPLOAD_FRAME_DRAIN_COUNTS = new IdentityHashMap<>();
    private static final Map<Object, UploadRetryState> UPLOAD_RETRY_STATES = new IdentityHashMap<>();
    private static final Map<Object, DispatcherState> DISPATCHER_STATES =
            new IdentityHashMap<>();
    private static final Executor DEFERRED_EXECUTOR = BrowserRenderScheduler::enqueue;
    private static boolean pumpScheduled;
    private static boolean runningTask;
    private static int peakQueuedTasks;
    private static int compileBacklog;
    private static int peakCompileBacklog;
    private static int uploadBacklog;
    private static int peakUploadBacklog;
    private static long enqueuedTasks;
    private static long completedTasks;
    private static long backpressureEvents;
    private static long overBudgetTasks;
    private static long totalTaskNanos;
    private static long lastTaskNanos;
    private static long longestTaskNanos;
    private static long uploadPasses;
    private static long uploadPassStartedAt;
    private static long totalUploadPassNanos;
    private static long lastUploadPassNanos;
    private static long longestUploadPassNanos;
    private static long uploadAllocationsQueued;
    private static long uploadAllocationsDrained;
    private static boolean highWaterActive;
    private static long highWaterStartedAt;
    private static long totalHighWaterNanos;
    private static long longestHighWaterNanos;
    private static long renderFrames;
    private static int lastTaskDrainCount;
    private static int peakTaskDrainCount;
    private static boolean lastTaskBudgetExhausted;
    private static long taskBudgetExhaustions;
    private static boolean uploadFrameInitialized;
    private static int currentUploadDrainCount;
    private static int lastUploadDrainCount;
    private static int peakUploadDrainCount;
    private static long uploadDrainDeadlineNanos;
    private static boolean uploadBudgetExhaustedThisFrame;
    private static long uploadBudgetExhaustions;
    private static long uploadEntryBudgetExhaustions;
    private static long uploadTimeBudgetExhaustions;
    private static long dispatcherRunnerRequests;
    private static long dispatcherRunnerEnqueued;
    private static long dispatcherRunnerCoalesced;
    private static long dispatcherRunnerDisposals;
    private static long dispatcherUploadDeferrals;
    private static int compileRunsDuringUploadThisFrame;
    private static long compileRunsDuringUpload;
    private static long uploadFairShareDeferrals;
    private static long uploadAllocationsDiscarded;
    private static int emergencyUploadEntriesRemaining;
    private static boolean emergencyUploadGrantedThisFrame;
    private static long emergencyUploadRequests;
    private static long emergencyUploadDrains;
    private static long emergencyUploadDeferrals;
    private static long uploadRetryYields;
    private static long uploadRetryNoProgressResumes;
    private static long uploadRetryCancellations;
    private static long uploadProgressEpoch;

    private BrowserRenderScheduler() {
    }

    public static Executor defer(Executor ignored) {
        return DEFERRED_EXECUTOR;
    }

    public static void executeDeferred(Executor ignored, Runnable command) {
        enqueue(command);
    }

    /** Starts the hard upload budget shared by all terrain upload calls in one rendered frame. */
    public static void beginFrame() {
        if (uploadFrameInitialized) {
            lastUploadDrainCount = currentUploadDrainCount;
            peakUploadDrainCount = Math.max(peakUploadDrainCount, currentUploadDrainCount);
        }
        uploadFrameInitialized = true;
        currentUploadDrainCount = 0;
        UPLOAD_FRAME_DRAIN_COUNTS.clear();
        compileRunsDuringUploadThisFrame = 0;
        uploadDrainDeadlineNanos = 0L;
        uploadBudgetExhaustedThisFrame = false;
        emergencyUploadEntriesRemaining = 0;
        emergencyUploadGrantedThisFrame = false;
        renderFrames++;
    }

    /** Coalesces every 26.2 dispatcher onto one queued or running drain token. */
    public static void scheduleDispatcher(
            Executor ignored,
            Runnable command,
            Object dispatcher,
            int currentCompileBacklog) {
        if (dispatcher == null || command == null) {
            enqueue(command);
            return;
        }
        compileBacklog = Math.max(0, currentCompileBacklog);
        peakCompileBacklog = Math.max(peakCompileBacklog, compileBacklog);
        dispatcherRunnerRequests++;
        DispatcherState state = DISPATCHER_STATES.get(dispatcher);
        if (state == null) {
            state = new DispatcherState(dispatcher);
            DISPATCHER_STATES.put(dispatcher, state);
        }
        if (state.disposed) {
            return;
        }
        state.command = command;
        state.requested = true;
        if (state.queued || state.running) {
            dispatcherRunnerCoalesced++;
            return;
        }
        enqueueDispatcher(state);
    }

    /** Remembers vanilla's continuation without enqueuing the empty tail runner it normally adds. */
    public static void rememberDispatcherContinuation(
            Executor ignored,
            Runnable command,
            Object dispatcher) {
        DispatcherState state = DISPATCHER_STATES.get(dispatcher);
        if (state == null || state.disposed) {
            return;
        }
        state.command = command;
    }

    /** Requests another runner only when the dispatcher still owns real compile work. */
    public static void finishDispatcherRun(Object dispatcher, int currentCompileBacklog) {
        compileBacklog = Math.max(0, currentCompileBacklog);
        peakCompileBacklog = Math.max(peakCompileBacklog, compileBacklog);
        DispatcherState state = DISPATCHER_STATES.get(dispatcher);
        if (state != null && !state.disposed && compileBacklog > 0) {
            state.requested = true;
        }
    }

    /** Releases queued closures and the strong dispatcher reference during renderer disposal. */
    public static void disposeDispatcher(Object dispatcher) {
        DispatcherState state = DISPATCHER_STATES.remove(dispatcher);
        if (state == null) {
            return;
        }
        state.disposed = true;
        state.requested = false;
        state.command = null;
        if (state.frameRequestId != 0) {
            Window.cancelAnimationFrame(state.frameRequestId);
            state.frameRequestId = 0;
        }
        state.waitingForFrame = false;
        if (state.queued) {
            QUEUE.remove(state.runner);
            state.queued = false;
        }
        if (DISPATCHER_STATES.isEmpty()) {
            compileBacklog = 0;
        }
        dispatcherRunnerDisposals++;
        updateHighWaterState();
    }

    /** Stops LevelRenderer from creating work faster than browser frames can consume it. */
    public static boolean canScheduleSection() {
        return canScheduleSection(0);
    }

    /** Accounts for section compiles selected earlier in the current extraction pass. */
    public static boolean canScheduleSection(int alreadyPlanned) {
        updateHighWaterState();
        int planned = Math.max(0, alreadyPlanned);
        int queuedWork = Math.max(Math.max(pendingTasks(), compileBacklog), uploadBacklog);
        boolean allowed = planned < 4 && queuedWork + planned < QUEUE_HIGH_WATER;
        if (!allowed) {
            backpressureEvents++;
        }
        return allowed;
    }

    public static int pendingTasks() {
        return QUEUE.size() + (runningTask ? 1 : 0);
    }

    public static int peakQueuedTasks() {
        return peakQueuedTasks;
    }

    public static long longestTaskNanos() {
        return longestTaskNanos;
    }

    /** Records the dispatcher queue and begins timing one terrain upload pass. */
    public static void beginUploadPass(int currentCompileBacklog) {
        ensureUploadFrameBudget();
        compileBacklog = Math.max(0, currentCompileBacklog);
        peakCompileBacklog = Math.max(peakCompileBacklog, compileBacklog);
        uploadPassStartedAt = System.nanoTime();
    }

    /** Finishes terrain upload timing and publishes one coherent pipeline snapshot. */
    public static void endUploadPass() {
        if (uploadPassStartedAt != 0L) {
            lastUploadPassNanos = Math.max(0L, System.nanoTime() - uploadPassStartedAt);
            uploadPassStartedAt = 0L;
            uploadPasses++;
            totalUploadPassNanos += lastUploadPassNanos;
            longestUploadPassNanos = Math.max(longestUploadPassNanos, lastUploadPassNanos);
        }
        if (uploadBacklog > 0 && currentUploadDrainCount >= MAX_UPLOAD_ALLOCATIONS_PER_FRAME) {
            markUploadBudgetExhausted(false);
        } else if (uploadBacklog > 0
                && uploadDrainDeadlineNanos != 0L
                && System.nanoTime() >= uploadDrainDeadlineNanos) {
            markUploadBudgetExhausted(true);
        }
        emergencyUploadEntriesRemaining = 0;
        publishTelemetry();
    }

    /** Guarantees one staged entry can drain when a full staging buffer blocks compilation. */
    public static void requestEmergencyUpload() {
        ensureUploadFrameBudget();
        emergencyUploadRequests++;
        long now = System.nanoTime();
        boolean hardLimitReached = currentUploadDrainCount >= MAX_UPLOAD_ALLOCATIONS_PER_FRAME;
        boolean timeLimitReached = uploadDrainDeadlineNanos != 0L
                && now >= uploadDrainDeadlineNanos;
        if (emergencyUploadGrantedThisFrame || hardLimitReached || timeLimitReached) {
            emergencyUploadDeferrals++;
            if (hardLimitReached || timeLimitReached) {
                markUploadBudgetExhausted(timeLimitReached);
            }
            return;
        }
        emergencyUploadGrantedThisFrame = true;
        emergencyUploadEntriesRemaining = 1;
    }

    /** Suspends a failed staging retry and bounds how long its mesh can remain retained. */
    public static boolean awaitUploadRetry(Object task) {
        if (task == null) {
            return false;
        }
        long now = System.nanoTime();
        UploadRetryState state = UPLOAD_RETRY_STATES.get(task);
        if (state == null) {
            state = new UploadRetryState(now);
            UPLOAD_RETRY_STATES.put(task, state);
        }
        long progressBeforeYield = uploadProgressEpoch;
        uploadRetryYields++;
        state.yields++;
        TModernRuntimeSupport.yieldToEventLoop(1);
        if (uploadProgressEpoch == progressBeforeYield) {
            uploadRetryNoProgressResumes++;
        }
        now = System.nanoTime();
        if (state.yields >= MAX_UPLOAD_RETRY_YIELDS
                || now - state.startedAtNanos >= MAX_UPLOAD_RETRY_NANOS) {
            UPLOAD_RETRY_STATES.remove(task);
            uploadRetryCancellations++;
            return false;
        }
        return true;
    }

    public static void clearUploadRetry(Object task) {
        if (task != null) {
            UPLOAD_RETRY_STATES.remove(task);
        }
    }

    /** Called immediately before consuming one 26.2 staged-allocation map entry. */
    public static boolean shouldUploadNext(Object buffer) {
        if (buffer == null) {
            return false;
        }
        ensureUploadFrameBudget();
        long now = System.nanoTime();
        if (currentUploadDrainCount >= MAX_UPLOAD_ALLOCATIONS_PER_FRAME) {
            emergencyUploadEntriesRemaining = 0;
            markUploadBudgetExhausted(false);
            return false;
        }
        if (uploadDrainDeadlineNanos == 0L) {
            uploadDrainDeadlineNanos = now + UPLOAD_WORK_BUDGET_NANOS;
        } else if (now >= uploadDrainDeadlineNanos) {
            emergencyUploadEntriesRemaining = 0;
            markUploadBudgetExhausted(true);
            return false;
        }
        if (emergencyUploadEntriesRemaining > 0) {
            emergencyUploadEntriesRemaining--;
            emergencyUploadDrains++;
            currentUploadDrainCount++;
            int bufferDrainCount = UPLOAD_FRAME_DRAIN_COUNTS.getOrDefault(buffer, 0) + 1;
            UPLOAD_FRAME_DRAIN_COUNTS.put(buffer, bufferDrainCount);
            peakUploadDrainCount = Math.max(peakUploadDrainCount, currentUploadDrainCount);
            return true;
        }
        int activeUploadBuffers = 0;
        for (Integer backlog : UPLOAD_BACKLOGS.values()) {
            if (backlog != null && backlog > 0) {
                activeUploadBuffers++;
            }
        }
        activeUploadBuffers = Math.max(1, activeUploadBuffers);
        int fairShare = Math.max(1, MAX_UPLOAD_ALLOCATIONS_PER_FRAME / activeUploadBuffers);
        int bufferDrainCount = UPLOAD_FRAME_DRAIN_COUNTS.getOrDefault(buffer, 0);
        if (activeUploadBuffers > 1 && bufferDrainCount >= fairShare) {
            uploadFairShareDeferrals++;
            return false;
        }
        currentUploadDrainCount++;
        UPLOAD_FRAME_DRAIN_COUNTS.put(buffer, bufferDrainCount + 1);
        peakUploadDrainCount = Math.max(peakUploadDrainCount, currentUploadDrainCount);
        return true;
    }

    /** Keeps skipped markers only for entries that remain staged for a later frame. */
    public static void finishUploadBuffer(
            Object buffer,
            Map<?, ?> stagedAllocations,
            Set<?> skippedStagedAllocations) {
        if (stagedAllocations == null || skippedStagedAllocations == null) {
            return;
        }
        skippedStagedAllocations.retainAll(stagedAllocations.keySet());
    }

    /** Drops the telemetry map's strong reference before UberGpuBuffer closes its allocations. */
    public static void releaseUploadBuffer(Object buffer) {
        Integer previousBacklog = UPLOAD_BACKLOGS.remove(buffer);
        UPLOAD_FRAME_DRAIN_COUNTS.remove(buffer);
        if (previousBacklog == null) {
            return;
        }
        int released = Math.max(0, previousBacklog);
        uploadBacklog = Math.max(0, uploadBacklog - released);
        uploadAllocationsDiscarded += released;
    }

    /**
     * Tracks the exact staged-allocation map size for each 26.2 UberGpuBuffer while preserving
     * the boolean already on that method's operand stack.
     */
    public static boolean recordUploadBacklogResult(
            boolean result,
            Object buffer,
            int currentBacklog) {
        if (buffer == null) {
            return result;
        }
        int boundedBacklog = Math.max(0, currentBacklog);
        Integer previousValue = UPLOAD_BACKLOGS.get(buffer);
        int previousBacklog = previousValue == null ? 0 : previousValue;
        int delta = boundedBacklog - previousBacklog;
        if (delta > 0) {
            uploadAllocationsQueued += delta;
        } else if (delta < 0) {
            uploadAllocationsDrained -= delta;
            uploadProgressEpoch -= delta;
        }
        uploadBacklog = Math.max(0, uploadBacklog + delta);
        peakUploadBacklog = Math.max(peakUploadBacklog, uploadBacklog);
        if (boundedBacklog == 0) {
            UPLOAD_BACKLOGS.remove(buffer);
        } else {
            UPLOAD_BACKLOGS.put(buffer, boundedBacklog);
        }
        return result;
    }

    private static void enqueue(Runnable command) {
        if (command == null) {
            return;
        }
        QUEUE.addLast(command);
        enqueuedTasks++;
        peakQueuedTasks = Math.max(peakQueuedTasks, QUEUE.size());
        updateHighWaterState();
        if (QUEUE.size() == 1) {
            publishTelemetry();
        }
        schedulePump();
    }

    private static void enqueueDispatcher(DispatcherState state) {
        if (queueDispatcher(state)) {
            schedulePump();
        }
    }

    private static boolean queueDispatcher(DispatcherState state) {
        if (state.disposed || state.queued || state.running
                || state.waitingForFrame || state.command == null) {
            return false;
        }
        state.queued = true;
        QUEUE.addFirst(state.runner);
        enqueuedTasks++;
        dispatcherRunnerEnqueued++;
        peakQueuedTasks = Math.max(peakQueuedTasks, QUEUE.size());
        updateHighWaterState();
        return true;
    }

    private static void runDispatcher(DispatcherState state) {
        state.queued = false;
        if (state.disposed || DISPATCHER_STATES.get(state.dispatcher) != state) {
            return;
        }
        Runnable command = state.command;
        state.requested = false;
        state.running = true;
        try {
            if (uploadBacklog > 0
                    && compileRunsDuringUploadThisFrame
                    >= MAX_COMPILE_RUNS_DURING_UPLOAD_PER_FRAME) {
                state.requested = true;
                deferDispatcherUntilNextFrame(state);
                dispatcherUploadDeferrals++;
            } else if (command != null) {
                if (uploadBacklog > 0) {
                    compileRunsDuringUploadThisFrame++;
                    compileRunsDuringUpload++;
                }
                command.run();
            }
        } finally {
            state.running = false;
            if (!state.disposed
                    && DISPATCHER_STATES.get(state.dispatcher) == state
                    && !state.waitingForFrame
                    && state.requested) {
                enqueueDispatcher(state);
            }
        }
    }

    private static void deferDispatcherUntilNextFrame(DispatcherState state) {
        if (state.waitingForFrame) {
            return;
        }
        state.waitingForFrame = true;
        state.frameRequestId = Window.requestAnimationFrame(timestamp -> {
            state.frameRequestId = 0;
            state.waitingForFrame = false;
            if (state.disposed
                    || DISPATCHER_STATES.get(state.dispatcher) != state
                    || !state.requested) {
                return;
            }
            if (!queueDispatcher(state) || pumpScheduled) {
                return;
            }
            pumpScheduled = true;
            Platform.schedule(BrowserRenderScheduler::runAfterPaint, 0);
        });
    }

    private static void schedulePump() {
        if (pumpScheduled) {
            return;
        }
        pumpScheduled = true;
        Window.requestAnimationFrame(timestamp -> Platform.schedule(BrowserRenderScheduler::runAfterPaint, 0));
    }

    private static void runAfterPaint() {
        long startedAt = System.nanoTime();
        int completed = 0;
        try {
            while (shouldContinueDrain(completed, System.nanoTime() - startedAt)) {
                Runnable command = QUEUE.pollFirst();
                if (command == null) {
                    return;
                }
                long taskStartedAt = System.nanoTime();
                runningTask = true;
                updateHighWaterState();
                try {
                    command.run();
                } finally {
                    runningTask = false;
                    lastTaskNanos = Math.max(0L, System.nanoTime() - taskStartedAt);
                    completedTasks++;
                    totalTaskNanos += lastTaskNanos;
                    longestTaskNanos = Math.max(longestTaskNanos, lastTaskNanos);
                    if (lastTaskNanos > FRAME_WORK_BUDGET_NANOS) {
                        overBudgetTasks++;
                    }
                    updateHighWaterState();
                }
                completed++;
            }
        } finally {
            lastTaskDrainCount = completed;
            peakTaskDrainCount = Math.max(peakTaskDrainCount, completed);
            lastTaskBudgetExhausted = !QUEUE.isEmpty();
            if (lastTaskBudgetExhausted) {
                taskBudgetExhaustions++;
            }
            pumpScheduled = false;
            publishTelemetry();
            if (!QUEUE.isEmpty()) {
                schedulePump();
            }
        }
    }

    static boolean shouldContinueDrain(int completed, long elapsedNanos) {
        return completed < MAX_TASKS_PER_FRAME
                && (completed == 0 || elapsedNanos < FRAME_WORK_BUDGET_NANOS);
    }

    private static void ensureUploadFrameBudget() {
        if (uploadFrameInitialized) {
            return;
        }
        uploadFrameInitialized = true;
        currentUploadDrainCount = 0;
        UPLOAD_FRAME_DRAIN_COUNTS.clear();
        compileRunsDuringUploadThisFrame = 0;
        uploadDrainDeadlineNanos = 0L;
        uploadBudgetExhaustedThisFrame = false;
        emergencyUploadEntriesRemaining = 0;
        emergencyUploadGrantedThisFrame = false;
    }

    private static void markUploadBudgetExhausted(boolean timeBudget) {
        if (uploadBudgetExhaustedThisFrame) {
            return;
        }
        uploadBudgetExhaustedThisFrame = true;
        uploadBudgetExhaustions++;
        if (timeBudget) {
            uploadTimeBudgetExhaustions++;
        } else {
            uploadEntryBudgetExhaustions++;
        }
    }

    private static void publishTelemetry() {
        updateHighWaterState();
        long activeHighWaterNanos = highWaterActive
                ? Math.max(0L, System.nanoTime() - highWaterStartedAt)
                : 0L;
        publishTelemetryJs(
                pendingTasks(),
                QUEUE_HIGH_WATER,
                runningTask,
                peakQueuedTasks,
                compileBacklog,
                peakCompileBacklog,
                uploadBacklog,
                peakUploadBacklog,
                enqueuedTasks,
                completedTasks,
                backpressureEvents,
                overBudgetTasks,
                nanosToMillis(lastTaskNanos),
                nanosToMillis(longestTaskNanos),
                nanosToMillis(totalTaskNanos),
                uploadPasses,
                nanosToMillis(lastUploadPassNanos),
                nanosToMillis(longestUploadPassNanos),
                nanosToMillis(totalUploadPassNanos),
                uploadAllocationsQueued,
                uploadAllocationsDrained,
                highWaterActive,
                nanosToMillis(activeHighWaterNanos),
                nanosToMillis(totalHighWaterNanos + activeHighWaterNanos),
                nanosToMillis(Math.max(longestHighWaterNanos, activeHighWaterNanos)),
                renderFrames,
                lastTaskDrainCount,
                peakTaskDrainCount,
                lastTaskBudgetExhausted,
                taskBudgetExhaustions,
                currentUploadDrainCount,
                lastUploadDrainCount,
                peakUploadDrainCount,
                MAX_UPLOAD_ALLOCATIONS_PER_FRAME,
                nanosToMillis(UPLOAD_WORK_BUDGET_NANOS),
                uploadBudgetExhaustedThisFrame,
                uploadBudgetExhaustions,
                uploadEntryBudgetExhaustions,
                uploadTimeBudgetExhaustions,
                dispatcherRunnerRequests,
                dispatcherRunnerEnqueued,
                dispatcherRunnerCoalesced,
                dispatcherRunnerDisposals,
                dispatcherUploadDeferrals,
                DISPATCHER_STATES.size(),
                uploadAllocationsDiscarded,
                compileRunsDuringUploadThisFrame,
                compileRunsDuringUpload,
                uploadFairShareDeferrals,
                UPLOAD_FRAME_DRAIN_COUNTS.size(),
                emergencyUploadRequests,
                emergencyUploadDrains,
                emergencyUploadDeferrals,
                uploadRetryYields,
                uploadRetryNoProgressResumes,
                uploadRetryCancellations,
                UPLOAD_RETRY_STATES.size(),
                MAX_UPLOAD_RETRY_YIELDS,
                nanosToMillis(MAX_UPLOAD_RETRY_NANOS),
                uploadProgressEpoch);
    }

    private static void updateHighWaterState() {
        boolean atHighWater = pendingTasks() >= QUEUE_HIGH_WATER;
        if (atHighWater == highWaterActive) {
            return;
        }
        long now = System.nanoTime();
        if (atHighWater) {
            highWaterActive = true;
            highWaterStartedAt = now;
            return;
        }
        long elapsed = Math.max(0L, now - highWaterStartedAt);
        totalHighWaterNanos += elapsed;
        longestHighWaterNanos = Math.max(longestHighWaterNanos, elapsed);
        highWaterActive = false;
        highWaterStartedAt = 0L;
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    @JSBody(params = {
            "pendingTasks", "queueCapacity", "taskRunning", "peakPendingTasks",
            "compileBacklog", "peakCompileBacklog", "uploadBacklog", "peakUploadBacklog",
            "enqueuedTasks", "completedTasks", "backpressureEvents", "overBudgetTasks",
            "lastTaskMillis", "longestTaskMillis", "totalTaskMillis", "uploadPasses",
            "lastUploadPassMillis", "longestUploadPassMillis", "totalUploadPassMillis",
            "uploadAllocationsQueued", "uploadAllocationsDrained", "highWaterActive",
            "currentHighWaterMillis", "totalHighWaterMillis", "longestHighWaterMillis",
            "renderFrames", "lastTaskDrainCount", "peakTaskDrainCount",
            "lastTaskBudgetExhausted", "taskBudgetExhaustions",
            "currentUploadDrainCount", "lastUploadDrainCount", "peakUploadDrainCount",
            "maxUploadAllocationsPerFrame", "uploadWorkBudgetMillis",
            "uploadBudgetExhausted", "uploadBudgetExhaustions",
            "uploadEntryBudgetExhaustions", "uploadTimeBudgetExhaustions",
            "dispatcherRunnerRequests", "dispatcherRunnerEnqueued",
            "dispatcherRunnerCoalesced", "dispatcherRunnerDisposals",
            "dispatcherUploadDeferrals", "activeDispatchers", "uploadAllocationsDiscarded",
            "compileRunsDuringUploadThisFrame", "compileRunsDuringUpload",
            "uploadFairShareDeferrals", "activeUploadBuffers",
            "emergencyUploadRequests", "emergencyUploadDrains", "emergencyUploadDeferrals",
            "uploadRetryYields", "uploadRetryNoProgressResumes", "uploadRetryCancellations",
            "activeUploadRetryTasks", "maxUploadRetryYields", "maxUploadRetryMillis",
            "uploadProgressEpoch"
    }, script = """
            const state=globalThis.__gaiusChunkPipelineTelemetry ||
              (globalThis.__gaiusChunkPipelineTelemetry={});
            const recordDuration=function(kind,count,duration) {
              const countKey=kind+'HistogramCount';
              const previous=Number(state[countKey])||0;
              const current=Number(count)||0;
              const added=Math.max(0,current-previous);
              let histogram=state[kind+'Histogram'];
              if (!histogram || histogram.length!==4001) {
                histogram=new Uint32Array(4001);
                state[kind+'Histogram']=histogram;
              }
              if (added>0 && Number.isFinite(duration) && duration>=0) {
                const bucket=Math.min(4000,Math.floor(Number(duration)*4));
                histogram[bucket]=histogram[bucket]+added;
              }
              state[countKey]=current;
            };
            recordDuration('task',completedTasks,lastTaskMillis);
            recordDuration('uploadPass',uploadPasses,lastUploadPassMillis);
            state.pendingTasks=Number(pendingTasks)||0;
            state.queueCapacity=Number(queueCapacity)||0;
            state.taskRunning=!!taskRunning;
            state.peakPendingTasks=Number(peakPendingTasks)||0;
            state.compileBacklog=Number(compileBacklog)||0;
            state.peakCompileBacklog=Number(peakCompileBacklog)||0;
            state.uploadBacklog=Number(uploadBacklog)||0;
            state.peakUploadBacklog=Number(peakUploadBacklog)||0;
            state.enqueuedTasks=Number(enqueuedTasks)||0;
            state.completedTasks=Number(completedTasks)||0;
            state.backpressureEvents=Number(backpressureEvents)||0;
            state.overBudgetTasks=Number(overBudgetTasks)||0;
            state.lastTaskMillis=Number(lastTaskMillis)||0;
            state.longestTaskMillis=Number(longestTaskMillis)||0;
            state.totalTaskMillis=Number(totalTaskMillis)||0;
            state.uploadPasses=Number(uploadPasses)||0;
            state.lastUploadPassMillis=Number(lastUploadPassMillis)||0;
            state.longestUploadPassMillis=Number(longestUploadPassMillis)||0;
            state.totalUploadPassMillis=Number(totalUploadPassMillis)||0;
            state.uploadAllocationsQueued=Number(uploadAllocationsQueued)||0;
            state.uploadAllocationsDrained=Number(uploadAllocationsDrained)||0;
            state.highWaterActive=!!highWaterActive;
            state.currentHighWaterMillis=Number(currentHighWaterMillis)||0;
            state.totalHighWaterMillis=Number(totalHighWaterMillis)||0;
            state.longestHighWaterMillis=Number(longestHighWaterMillis)||0;
            state.renderFrames=Number(renderFrames)||0;
            state.lastTaskDrainCount=Number(lastTaskDrainCount)||0;
            state.peakTaskDrainCount=Number(peakTaskDrainCount)||0;
            state.lastTaskBudgetExhausted=!!lastTaskBudgetExhausted;
            state.taskBudgetExhaustions=Number(taskBudgetExhaustions)||0;
            state.currentUploadDrainCount=Number(currentUploadDrainCount)||0;
            state.lastUploadDrainCount=Number(lastUploadDrainCount)||0;
            state.peakUploadDrainCount=Number(peakUploadDrainCount)||0;
            state.maxUploadAllocationsPerFrame=Number(maxUploadAllocationsPerFrame)||0;
            state.uploadWorkBudgetMillis=Number(uploadWorkBudgetMillis)||0;
            state.uploadBudgetExhausted=!!uploadBudgetExhausted;
            state.uploadBudgetExhaustions=Number(uploadBudgetExhaustions)||0;
            state.uploadEntryBudgetExhaustions=Number(uploadEntryBudgetExhaustions)||0;
            state.uploadTimeBudgetExhaustions=Number(uploadTimeBudgetExhaustions)||0;
            state.dispatcherRunnerRequests=Number(dispatcherRunnerRequests)||0;
            state.dispatcherRunnerEnqueued=Number(dispatcherRunnerEnqueued)||0;
            state.dispatcherRunnerCoalesced=Number(dispatcherRunnerCoalesced)||0;
            state.dispatcherRunnerDisposals=Number(dispatcherRunnerDisposals)||0;
            state.dispatcherUploadDeferrals=Number(dispatcherUploadDeferrals)||0;
            state.activeDispatchers=Number(activeDispatchers)||0;
            state.uploadAllocationsDiscarded=Number(uploadAllocationsDiscarded)||0;
            state.compileRunsDuringUploadThisFrame=
              Number(compileRunsDuringUploadThisFrame)||0;
            state.compileRunsDuringUpload=Number(compileRunsDuringUpload)||0;
            state.uploadFairShareDeferrals=Number(uploadFairShareDeferrals)||0;
            state.activeUploadBuffers=Number(activeUploadBuffers)||0;
            state.emergencyUploadRequests=Number(emergencyUploadRequests)||0;
            state.emergencyUploadDrains=Number(emergencyUploadDrains)||0;
            state.emergencyUploadDeferrals=Number(emergencyUploadDeferrals)||0;
            state.uploadRetryYields=Number(uploadRetryYields)||0;
            state.uploadRetryNoProgressResumes=Number(uploadRetryNoProgressResumes)||0;
            state.uploadRetryCancellations=Number(uploadRetryCancellations)||0;
            state.activeUploadRetryTasks=Number(activeUploadRetryTasks)||0;
            state.maxUploadRetryYields=Number(maxUploadRetryYields)||0;
            state.maxUploadRetryMillis=Number(maxUploadRetryMillis)||0;
            state.uploadProgressEpoch=Number(uploadProgressEpoch)||0;
            state.droppedTasks=0;
            state.updatedAt=(typeof performance!=='undefined' && performance.now)
              ? performance.now() : Date.now();
            """)
    private static native void publishTelemetryJs(
            int pendingTasks,
            int queueCapacity,
            boolean taskRunning,
            int peakPendingTasks,
            int compileBacklog,
            int peakCompileBacklog,
            int uploadBacklog,
            int peakUploadBacklog,
            long enqueuedTasks,
            long completedTasks,
            long backpressureEvents,
            long overBudgetTasks,
            double lastTaskMillis,
            double longestTaskMillis,
            double totalTaskMillis,
            long uploadPasses,
            double lastUploadPassMillis,
            double longestUploadPassMillis,
            double totalUploadPassMillis,
            long uploadAllocationsQueued,
            long uploadAllocationsDrained,
            boolean highWaterActive,
            double currentHighWaterMillis,
            double totalHighWaterMillis,
            double longestHighWaterMillis,
            long renderFrames,
            int lastTaskDrainCount,
            int peakTaskDrainCount,
            boolean lastTaskBudgetExhausted,
            long taskBudgetExhaustions,
            int currentUploadDrainCount,
            int lastUploadDrainCount,
            int peakUploadDrainCount,
            int maxUploadAllocationsPerFrame,
            double uploadWorkBudgetMillis,
            boolean uploadBudgetExhausted,
            long uploadBudgetExhaustions,
            long uploadEntryBudgetExhaustions,
            long uploadTimeBudgetExhaustions,
            long dispatcherRunnerRequests,
            long dispatcherRunnerEnqueued,
            long dispatcherRunnerCoalesced,
            long dispatcherRunnerDisposals,
            long dispatcherUploadDeferrals,
            int activeDispatchers,
            long uploadAllocationsDiscarded,
            int compileRunsDuringUploadThisFrame,
            long compileRunsDuringUpload,
            long uploadFairShareDeferrals,
            int activeUploadBuffers,
            long emergencyUploadRequests,
            long emergencyUploadDrains,
            long emergencyUploadDeferrals,
            long uploadRetryYields,
            long uploadRetryNoProgressResumes,
            long uploadRetryCancellations,
            int activeUploadRetryTasks,
            int maxUploadRetryYields,
            double maxUploadRetryMillis,
            long uploadProgressEpoch);

    private static final class UploadRetryState {
        final long startedAtNanos;
        int yields;

        UploadRetryState(long startedAtNanos) {
            this.startedAtNanos = startedAtNanos;
        }
    }

    private static final class DispatcherState {
        final Object dispatcher;
        final Runnable runner;
        Runnable command;
        boolean queued;
        boolean running;
        boolean requested;
        boolean disposed;
        boolean waitingForFrame;
        int frameRequestId;

        DispatcherState(Object dispatcher) {
            this.dispatcher = dispatcher;
            this.runner = () -> runDispatcher(this);
        }
    }
}
