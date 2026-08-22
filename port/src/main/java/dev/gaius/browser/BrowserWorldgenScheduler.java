package dev.gaius.browser;

import io.netty.channel.browser.BrowserWebSocketChannel;
import org.teavm.classlib.java.lang.TModernRuntimeSupport;
import org.teavm.jso.JSBody;

/** Keeps integrated-server world generation cooperative in browser Workers. */
public final class BrowserWorldgenScheduler {
    private static final double DEFAULT_SLICE_MILLIS = 8.0;
    private static final double MIN_ADAPTIVE_SLICE_MILLIS = 2.0;
    private static final double BUDGET_RECOVERY_MILLIS = 0.5;
    private static final double MODERATE_YIELD_DELAY_MILLIS = 4.0;
    private static final double BUSY_YIELD_DELAY_MILLIS = 8.0;
    private static final int CLOCK_CHECK_INTERVAL = 1;
    private static final int NETWORK_CHECK_INTERVAL = 1;
    private static final int MIN_PROGRESS_PULSES_BEFORE_NETWORK_PREEMPTION = 2;
    private static final int MAX_NETWORK_WAIT_PULSES = 2;
    // Explicit patch points now represent bounded rows, columns, queues, or stage units.
    private static final int MAX_PULSES_PER_TURN = 4096;
    private static final int DEFAULT_DISTANCE_MANAGER_UPDATE_BUDGET = 64;
    private static final int MIN_DISTANCE_MANAGER_UPDATE_BUDGET = 8;
    private static final int MAX_DISTANCE_MANAGER_UPDATE_BUDGET = 512;

    private static final int YIELD_DEADLINE = 0;
    private static final int YIELD_NETWORK = 1;
    private static final int YIELD_HARD_CAP = 2;
    private static final int YIELD_CHECKPOINT = 3;

    // Task-scope entry points are instrumented into methods which can be
    // resumed independently by TeaVM.  Keep the token deliberately small and
    // categorical: NONE means this invocation is nested inside an already
    // active normal scope, NORMAL owns the active-work clock, and REENTRANT is
    // isolated from that clock while a scheduler continuation is pumping.
    private static final int TASK_SCOPE_NONE = 0;
    private static final int TASK_SCOPE_NORMAL = 1;
    private static final int TASK_SCOPE_REENTRANT = 2;

    private static double sliceStartedAtMillis;
    // ``sliceStartedAtMillis`` is the start of the current scheduler slice, not a
    // license to charge every wall-clock interval after the server tick begins.
    // Task scopes accumulate only the intervals in which a world-generation task
    // is actually running.  This is intentionally separate from the pulse
    // counters: a task can yield and resume without resetting either budget.
    private static double activeWorkElapsedMillis;
    // -1 marks a paused segment; zero is a valid performance.now() value.
    private static double activeWorkStartedAtMillis = -1.0;
    private static int taskWorkDepth;
    // A packet callback can enter a task while requestYield is pumping.  Keep
    // those scopes in a separate counter so they cannot move the outer task's
    // active segment or deadline underneath its continuation.
    private static int reentrantTaskWorkDepth;
    private static int deferredTaskScopeEnds;
    private static double deadlineMillis;
    private static double currentBudgetMillis;
    private static double previousYieldDelayMillis;
    private static double previousOverrunMillis;
    private static int pulsesUntilClockCheck = 1;
    private static int pulsesUntilNetworkCheck = 1;
    private static int pulsesInTurn;
    private static int maxPulsesInTurn;
    private static int progressPulsesInSlice;
    private static int networkWaitPulses;
    private static int reentrantYieldRequests;
    private static int reentrantYieldDepth;
    private static int maxReentrantYieldDepth;
    // Maximum nested continuation depth observed by the currently active
    // requestYield call.  The cumulative max above remains the ordinary slice
    // contract; this per-yield value keeps checkpoint-only telemetry scoped to
    // the checkpoint that produced it.
    private static int maxReentrantYieldDepthInYield;
    private static boolean networkPreemptionPending;
    private static boolean yieldActive;
    private static boolean deferredYield;
    private static int lastDistanceManagerUpdateBudget = DEFAULT_DISTANCE_MANAGER_UPDATE_BUDGET;

    private BrowserWorldgenScheduler() {
    }

    public static void checkpoint() {
        recordSchedulerMarker(
                "server-work-turn-checkpoint",
                YIELD_CHECKPOINT,
                taskWorkDepth,
                reentrantTaskWorkDepth,
                yieldActive,
                activeWorkElapsedMillis);
        requestYield(YIELD_CHECKPOINT, networkQueueDepth());
        recordSchedulerMarker(
                "server-work-turn-end",
                YIELD_CHECKPOINT,
                taskWorkDepth,
                reentrantTaskWorkDepth,
                yieldActive,
                activeWorkElapsedMillis);
    }

    /**
     * Bounds the vanilla all-updates distance pass without changing its queue ordering. Any
     * remaining ticket work stays in the vanilla tracker and is resumed by the next server tick.
     */
    public static int distanceManagerUpdateBudget() {
        int budget = configuredDistanceManagerUpdateBudget(
                DEFAULT_DISTANCE_MANAGER_UPDATE_BUDGET);
        lastDistanceManagerUpdateBudget = budget;
        return budget;
    }

    /** Records the actual number of distance updates completed in one vanilla pass. */
    public static void recordDistanceManagerUpdates(int processed) {
        recordDistanceManagerUpdatesJs(
                lastDistanceManagerUpdateBudget,
                Math.max(0, processed));
    }

    /** Keeps the remaining vanilla distance-manager loops cooperative on the server thread. */
    public static void pulseDistanceManager() {
        recordDistanceManagerPulse();
        pulse();
    }

    /** Starts a snapshot-backed changed-chunk broadcast batch. */
    public static void beginChunkBroadcast(int entries) {
        recordChunkBroadcastStart(Math.max(0, entries));
    }

    /** Allows long changed-chunk broadcasts to yield without retaining a live set iterator. */
    public static void pulseChunkBroadcast() {
        recordChunkBroadcastItem();
        pulse();
    }

    /** Marks a fully processed broadcast snapshot; updates queued during it remain for next tick. */
    public static void finishChunkBroadcast(int entries) {
        recordChunkBroadcastFinish(Math.max(0, entries));
    }

    /**
     * Starts the synchronous server work turn containing its chunk-generation tasks.
     *
     * <p>This is called once immediately before {@code processPacketsAndTick}, not from every
     * {@code ChunkGenerationTask.runUntilWait} invocation. A task can be resumed by the server
     * on a later tick after returning a pending future; the elapsed wall-clock time between those
     * ticks is idle time, not work in the current slice. Advance the wall-clock origin here while
     * retaining any active task milliseconds left by the wait loop; pulse counters and the
     * adaptive budget remain shared across task scopes. The reentrancy guard is important because
     * an urgent-packet callback can enter another task while a TeaVM continuation is suspended.
     * In that case the active continuation owns the clock and a nested task must not move its
     * deadline underneath it.
     */
    public static void beginServerWorkTurn() {
        if (yieldActive) {
            return;
        }

        double now = nowMillis();
        // A server work-turn boundary is outside the task scopes.  It may reset
        // the timestamp for a new tick, but never resets the adaptive budget or
        // pulse counters shared by all tasks in that tick.  If an unusual
        // continuation re-enters here while a task is still active, leave its
        // active segment intact rather than moving the deadline underneath it.
        if (taskWorkDepth > 0) {
            return;
        }
        if (currentBudgetMillis <= 0.0) {
            beginSlice(now, networkQueueDepth());
        } else {
            sliceStartedAtMillis = now;
            // Do not clear activeWorkElapsedMillis here.  A task can run from
            // waitUntilNextTick after the previous checkpoint and return before
            // this next server-turn boundary; that work belongs to the same
            // adaptive slice and must be combined with the next task.
            activeWorkStartedAtMillis = -1.0;
            deadlineMillis = now + currentBudgetMillis;
            pulsesUntilClockCheck = CLOCK_CHECK_INTERVAL;
        }
        recordSchedulerMarker(
                "server-work-turn-start",
                0,
                taskWorkDepth,
                reentrantTaskWorkDepth,
                false,
                activeWorkElapsedMillis);
    }

    /**
     * Enters a world-generation task scope.  Scopes are deliberately nestable:
     * a task may synchronously invoke another task while processing an urgent
     * packet, but only the outermost scope owns the active wall-clock segment.
     */
    public static int beginTaskWork() {
        return beginTaskWork("unlabeled");
    }

    /**
     * Enters a labelled task scope for opt-in slow-probe attribution.
     *
     * <p>The label is diagnostic only: the release browser leaves the telemetry flag unset, and
     * the scheduler token/depth/deadline behavior remains identical to {@link #beginTaskWork()}.
     */
    public static int beginTaskWork(String taskLabel) {
        recordSchedulerTaskLabel(taskLabel);
        if (yieldActive) {
            if (reentrantTaskWorkDepth < Integer.MAX_VALUE) {
                reentrantTaskWorkDepth++;
            }
            recordSchedulerMarker(
                    "task-start-reentrant",
                    TASK_SCOPE_REENTRANT,
                    taskWorkDepth,
                    reentrantTaskWorkDepth,
                    true,
                    activeWorkElapsedMillis);
            return TASK_SCOPE_REENTRANT;
        }
        if (taskWorkDepth > 0) {
            recordSchedulerMarker(
                    "task-start-nested",
                    TASK_SCOPE_NONE,
                    taskWorkDepth,
                    reentrantTaskWorkDepth,
                    false,
                    activeWorkElapsedMillis);
            return TASK_SCOPE_NONE;
        }
        taskWorkDepth = 1;

        double now = nowMillis();
        if (deadlineMillis == 0.0) {
            beginSlice(now, networkQueueDepth());
            recordSchedulerMarker(
                    "task-start-normal",
                    TASK_SCOPE_NORMAL,
                    taskWorkDepth,
                    reentrantTaskWorkDepth,
                    false,
                    activeWorkElapsedMillis);
            return TASK_SCOPE_NORMAL;
        }
        // The clock was paused at endTaskWork (or at the previous checkpoint).
        // Starting a new scope records a fresh active segment; the paused gap is
        // intentionally absent from activeWorkElapsedMillis.
        activeWorkStartedAtMillis = now;
        recordSchedulerMarker(
                "task-start-normal",
                TASK_SCOPE_NORMAL,
                taskWorkDepth,
                reentrantTaskWorkDepth,
                false,
                activeWorkElapsedMillis);
        return TASK_SCOPE_NORMAL;
    }

    /**
     * Leaves a world-generation task scope.  The defensive underflow guard is
     * useful on browser shutdown/error paths where a continuation can be
     * cancelled after its normal return has already been observed.
     */
    public static void endTaskWork(int token) {
        if (token == TASK_SCOPE_NONE) {
            if (taskWorkDepth <= 0) {
                recordSchedulerMarker(
                        "task-end-underflow",
                        token,
                        taskWorkDepth,
                        reentrantTaskWorkDepth,
                        yieldActive,
                        activeWorkElapsedMillis);
                return;
            }
            recordSchedulerMarker(
                    "task-end-nested",
                    token,
                    taskWorkDepth,
                    reentrantTaskWorkDepth,
                    yieldActive,
                    activeWorkElapsedMillis);
            return;
        }
        if (token == TASK_SCOPE_REENTRANT) {
            // A reentrant callback may resume after the outer requestYield has
            // already dropped yieldActive.  Its token still closes only its
            // own isolated scope and can never decrement the outer task.
            if (reentrantTaskWorkDepth <= 0) {
                recordSchedulerMarker(
                        "task-end-underflow",
                        token,
                        taskWorkDepth,
                        reentrantTaskWorkDepth,
                        yieldActive,
                        activeWorkElapsedMillis);
                return;
            }
            reentrantTaskWorkDepth--;
            recordSchedulerMarker(
                    "task-end-reentrant",
                    token,
                    taskWorkDepth,
                    reentrantTaskWorkDepth,
                    yieldActive,
                    activeWorkElapsedMillis);
            return;
        }
        if (token != TASK_SCOPE_NORMAL) {
            recordSchedulerMarker(
                    "task-end-invalid",
                    token,
                    taskWorkDepth,
                    reentrantTaskWorkDepth,
                    yieldActive,
                    activeWorkElapsedMillis);
            return;
        }
        if (yieldActive) {
            // Only a live NORMAL scope may be deferred.  In particular, an
            // unmatched REENTRANT/NONE close must not leave a stale deferred
            // close which a later continuation could apply to another task.
            if (taskWorkDepth <= 0) {
                recordSchedulerMarker(
                        "task-end-underflow",
                        token,
                        taskWorkDepth,
                        reentrantTaskWorkDepth,
                        true,
                        activeWorkElapsedMillis);
                return;
            }
            if (deferredTaskScopeEnds == 0) {
                deferredTaskScopeEnds++;
            }
            recordSchedulerMarker(
                    "task-end-deferred",
                    token,
                    taskWorkDepth,
                    reentrantTaskWorkDepth,
                    true,
                    activeWorkElapsedMillis);
            return;
        }
        if (taskWorkDepth <= 0) {
            recordSchedulerMarker(
                    "task-end-underflow",
                    token,
                    taskWorkDepth,
                    reentrantTaskWorkDepth,
                    false,
                    activeWorkElapsedMillis);
            return;
        }
        taskWorkDepth = 0;

        double now = nowMillis();
        activeWorkElapsedMillis += activeSegmentElapsedMillis(now);
        activeWorkStartedAtMillis = -1.0;
        recordSchedulerMarker(
                "task-end-normal",
                token,
                taskWorkDepth,
                reentrantTaskWorkDepth,
                false,
                activeWorkElapsedMillis);
    }

    public static void pulse() {
        progressPulsesInSlice++;
        pulsesInTurn++;
        maxPulsesInTurn = Math.max(maxPulsesInTurn, pulsesInTurn);
        if (yieldActive) {
            yieldReentrantContinuation();
            return;
        }

        if (networkPreemptionPending) {
            networkWaitPulses++;
            if (progressPulsesInSlice >= MIN_PROGRESS_PULSES_BEFORE_NETWORK_PREEMPTION) {
                requestYield(YIELD_NETWORK, Math.max(1, networkQueueDepth()));
                return;
            }
        } else if (--pulsesUntilNetworkCheck <= 0) {
            pulsesUntilNetworkCheck = NETWORK_CHECK_INTERVAL;
            int queueDepth = networkQueueDepth();
            if (queueDepth > 0 || hasPendingNetworkInput()) {
                networkPreemptionPending = true;
                networkWaitPulses = 1;
                if (progressPulsesInSlice >= MIN_PROGRESS_PULSES_BEFORE_NETWORK_PREEMPTION) {
                    requestYield(YIELD_NETWORK, Math.max(1, queueDepth));
                    return;
                }
            }
        }

        if (pulsesInTurn >= MAX_PULSES_PER_TURN) {
            requestYield(YIELD_HARD_CAP, networkQueueDepth());
            return;
        }
        if (--pulsesUntilClockCheck > 0) {
            return;
        }
        pulsesUntilClockCheck = CLOCK_CHECK_INTERVAL;

        double now = nowMillis();
        if (deadlineMillis == 0.0) {
            beginSlice(now, networkQueueDepth());
        } else if (activeSliceElapsedMillis(now) >= currentBudgetMillis) {
            requestYield(YIELD_DEADLINE, networkQueueDepth());
        }
    }

    private static void requestYield(int reason, int observedQueueDepth) {
        if (yieldActive) {
            yieldReentrantContinuation();
            return;
        }

        double beforePumpAt = nowMillis();
        double sliceElapsedMillis = activeSliceElapsedMillis(beforePumpAt);
        // Freeze the active segment while the scheduler pumps packets and yields
        // to the browser event loop.  The continuation starts a new segment only
        // after it resumes, so the event-loop delay is never charged to work.
        activeWorkElapsedMillis = sliceElapsedMillis;
        activeWorkStartedAtMillis = -1.0;
        yieldActive = true;
        recordSchedulerMarker(
                "yield-start",
                reason,
                taskWorkDepth,
                reentrantTaskWorkDepth,
                true,
                activeWorkElapsedMillis);
        maxReentrantYieldDepthInYield = 0;
        try {
            double configuredBudgetMillis = sliceMillis();
            if (sliceStartedAtMillis == 0.0) {
                sliceStartedAtMillis = beforePumpAt;
            }
            if (currentBudgetMillis <= 0.0) {
                currentBudgetMillis = adaptiveBudgetMillis(
                        configuredBudgetMillis,
                        observedQueueDepth,
                        previousYieldDelayMillis,
                        previousOverrunMillis,
                        true);
            }

            double completedBudgetMillis = currentBudgetMillis;
            double overrunMillis = Math.max(0.0, sliceElapsedMillis - completedBudgetMillis);
            int queueDepthBefore = Math.max(observedQueueDepth, networkQueueDepth());
            boolean pendingBefore = queueDepthBefore > 0 || hasPendingNetworkInput();
            if (pendingBefore && queueDepthBefore == 0) {
                queueDepthBefore = 1;
            }
            if (pendingBefore) {
                BrowserIntegratedServerMain.pumpUrgentPackets();
            }

            // A zero-delay continuation yields to MessagePort and heartbeat callbacks without
            // relying on the browser's clamp-prone positive timers.
            double yieldStartedAt = nowMillis();
            TModernRuntimeSupport.yieldToEventLoop(0);
            double resumedAt = nowMillis();
            double yieldDelayMillis = Math.max(0.0, resumedAt - yieldStartedAt);

            int queueDepthAfter = networkQueueDepth();
            boolean pendingAfter = queueDepthAfter > 0 || hasPendingNetworkInput();
            if (pendingAfter && queueDepthAfter == 0) {
                queueDepthAfter = 1;
            }
            if (pendingAfter) {
                BrowserIntegratedServerMain.pumpUrgentPackets();
                queueDepthAfter = networkQueueDepth();
                if (queueDepthAfter == 0 && hasPendingNetworkInput()) {
                    queueDepthAfter = 1;
                }
            }

            // A checkpoint is a server-turn boundary, not evidence that a
            // worldgen slice completed.  Decide this only after both urgent
            // packet pumps and the event-loop continuation: a callback may
            // have produced a pulse while yieldActive was true, in which case
            // the checkpoint is an ordinary progress-bearing slice.
            boolean madeProgress = progressPulsesInSlice > 0;
            boolean checkpointOnly = reason == YIELD_CHECKPOINT
                    && progressPulsesInSlice == 0;
            int pressureDepth = Math.max(queueDepthBefore, queueDepthAfter);
            boolean networkPreemption = reason == YIELD_NETWORK || pendingBefore || pendingAfter;
            if (checkpointOnly) {
                // Keep checkpoint-only waits out of the adaptive controller and
                // ordinary slice counters.  They still record the pressure and
                // event-loop delay needed by the checkpoint-only evaluator.
                recordCheckpointOnlyYield(
                        networkWaitPulses,
                        yieldDelayMillis,
                        queueDepthBefore,
                        queueDepthAfter,
                        maxReentrantYieldDepthInYield);
            } else {
                previousYieldDelayMillis = yieldDelayMillis;
                previousOverrunMillis = overrunMillis;
                currentBudgetMillis = adaptiveBudgetMillis(
                        configuredBudgetMillis,
                        pressureDepth,
                        yieldDelayMillis,
                        overrunMillis,
                        madeProgress);
                reportSlice(
                        reason,
                        networkPreemption,
                        progressPulsesInSlice,
                        networkWaitPulses,
                        sliceElapsedMillis,
                        completedBudgetMillis,
                        currentBudgetMillis,
                        overrunMillis,
                        yieldDelayMillis,
                        queueDepthBefore,
                        queueDepthAfter,
                        reentrantYieldRequests,
                        MAX_NETWORK_WAIT_PULSES,
                        maxPulsesInTurn,
                        maxReentrantYieldDepth);
            }

            double nextSliceStartedAt = nowMillis();
            sliceStartedAtMillis = nextSliceStartedAt;
            activeWorkElapsedMillis = 0.0;
            // A checkpoint-only boundary is intentionally a paused clock.  The
            // following server turn will explicitly start a task segment; an
            // ordinary progress-bearing yield retains the existing nested-task
            // behavior.
            activeWorkStartedAtMillis = !checkpointOnly && taskWorkDepth > 0
                    ? nextSliceStartedAt
                    : -1.0;
            deadlineMillis = nextSliceStartedAt + currentBudgetMillis;
            pulsesUntilClockCheck = CLOCK_CHECK_INTERVAL;
            pulsesUntilNetworkCheck = NETWORK_CHECK_INTERVAL;
            pulsesInTurn = 0;
            maxPulsesInTurn = 0;
            progressPulsesInSlice = 0;
            networkWaitPulses = 0;
            networkPreemptionPending = false;
        } finally {
            yieldActive = false;
            boolean appliedDeferredTaskEnd = false;
            while (deferredTaskScopeEnds > 0 && taskWorkDepth > 0) {
                deferredTaskScopeEnds--;
                taskWorkDepth = 0;
                activeWorkStartedAtMillis = -1.0;
                appliedDeferredTaskEnd = true;
            }
            if (appliedDeferredTaskEnd) {
                recordSchedulerMarker(
                        "task-end-deferred-applied",
                        TASK_SCOPE_NORMAL,
                        taskWorkDepth,
                        reentrantTaskWorkDepth,
                        false,
                        activeWorkElapsedMillis);
            } else {
                recordSchedulerMarker(
                        "yield-end",
                        reason,
                        taskWorkDepth,
                        reentrantTaskWorkDepth,
                        false,
                        activeWorkElapsedMillis);
            }
            // A continuation can be cancelled after its normal close.  Do not
            // carry unmatched deferred closes into the next invocation.
            deferredTaskScopeEnds = 0;
            if (deferredYield) {
                deferredYield = false;
                // A nested callback requested another yield while the outer
                // continuation was suspended.  Carry that request into the
                // active-work clock so the next pulse cannot clear it merely
                // because the event-loop delay was excluded from elapsed work.
                activeWorkElapsedMillis = Math.max(
                        activeWorkElapsedMillis,
                        currentBudgetMillis);
                deadlineMillis = sliceStartedAtMillis;
                pulsesUntilClockCheck = 1;
            }
        }
    }

    private static void beginSlice(double now, int queueDepth) {
        double configuredBudgetMillis = sliceMillis();
        currentBudgetMillis = adaptiveBudgetMillis(
                configuredBudgetMillis,
                queueDepth,
                previousYieldDelayMillis,
                previousOverrunMillis,
                true);
        sliceStartedAtMillis = now;
        activeWorkElapsedMillis = 0.0;
        activeWorkStartedAtMillis = taskWorkDepth > 0 ? now : -1.0;
        deadlineMillis = now + currentBudgetMillis;
    }

    private static double activeSegmentElapsedMillis(double now) {
        if (activeWorkStartedAtMillis < 0.0) {
            return 0.0;
        }
        return Math.max(0.0, now - activeWorkStartedAtMillis);
    }

    private static double activeSliceElapsedMillis(double now) {
        return activeWorkElapsedMillis
                + (taskWorkDepth > 0 ? activeSegmentElapsedMillis(now) : 0.0);
    }

    private static double adaptiveBudgetMillis(
            double configuredBudgetMillis,
            int queueDepth,
            double yieldDelayMillis,
            double overrunMillis,
            boolean madeProgress) {
        double floorMillis = Math.min(configuredBudgetMillis, MIN_ADAPTIVE_SLICE_MILLIS);
        double targetMillis = configuredBudgetMillis;
        if (queueDepth > 0) {
            targetMillis = Math.min(targetMillis, configuredBudgetMillis * 0.35);
        }
        if (yieldDelayMillis >= BUSY_YIELD_DELAY_MILLIS || overrunMillis >= 2.0) {
            targetMillis = Math.min(targetMillis, configuredBudgetMillis * 0.25);
        } else if (yieldDelayMillis >= MODERATE_YIELD_DELAY_MILLIS || overrunMillis > 0.0) {
            targetMillis = Math.min(targetMillis, configuredBudgetMillis * 0.5);
        }
        if (!madeProgress) {
            targetMillis = Math.min(targetMillis, configuredBudgetMillis * 0.5);
        }
        targetMillis = Math.max(floorMillis, targetMillis);
        if (currentBudgetMillis <= 0.0 || targetMillis < currentBudgetMillis) {
            return targetMillis;
        }
        return Math.min(targetMillis, currentBudgetMillis + BUDGET_RECOVERY_MILLIS);
    }

    private static void deferReentrantYield() {
        deferredYield = true;
        if (reentrantYieldRequests < Integer.MAX_VALUE) {
            reentrantYieldRequests++;
        }
    }

    private static void yieldReentrantContinuation() {
        deferReentrantYield();
        // A nested packet callback must not continue world generation inside the active
        // slice. Suspending this continuation prevents the guard from hiding a long task.
        reentrantYieldDepth++;
        maxReentrantYieldDepth = Math.max(maxReentrantYieldDepth, reentrantYieldDepth);
        maxReentrantYieldDepthInYield = Math.max(
                maxReentrantYieldDepthInYield,
                reentrantYieldDepth);
        try {
            TModernRuntimeSupport.yieldToEventLoop(0);
        } finally {
            reentrantYieldDepth--;
        }
    }

    @JSBody(params = "fallback", script = """
            const configured = Number(globalThis.__gaiusWorldgenSliceMillis);
            return Number.isFinite(configured) && configured >= 2 && configured <= 50
              ? configured
              : fallback;
            """)
    private static native double configuredSliceMillis(double fallback);

    private static double sliceMillis() {
        return configuredSliceMillis(DEFAULT_SLICE_MILLIS);
    }

    @JSBody(params = "fallback", script = """
            const configured = Number(globalThis.__gaiusDistanceManagerUpdateBudget);
            if (!Number.isFinite(configured)) return fallback;
            return Math.max(8, Math.min(512, Math.floor(configured)));
            """)
    private static native int configuredDistanceManagerUpdateBudget(int fallback);

    @JSBody(params = {"budget", "processed"}, script = """
            const stats = globalThis.__gaiusWorldgenStats ||
              (globalThis.__gaiusWorldgenStats = {});
            const safeBudget = Math.max(1, Number(budget) || 0);
            const safeProcessed = Math.max(0, Number(processed) || 0);
            stats.distanceManagerBatches = (Number(stats.distanceManagerBatches) || 0) + 1;
            stats.distanceManagerUpdateBudget = safeBudget;
            stats.lastDistanceManagerUpdates = safeProcessed;
            stats.totalDistanceManagerUpdates =
              (Number(stats.totalDistanceManagerUpdates) || 0) + safeProcessed;
            stats.maxDistanceManagerUpdates = Math.max(
              Number(stats.maxDistanceManagerUpdates) || 0,
              safeProcessed
            );
            if (safeProcessed >= safeBudget) {
              stats.distanceManagerBudgetExhaustions =
                (Number(stats.distanceManagerBudgetExhaustions) || 0) + 1;
            }
            """)
    private static native void recordDistanceManagerUpdatesJs(int budget, int processed);

    @JSBody(script = """
            const stats = globalThis.__gaiusWorldgenStats ||
              (globalThis.__gaiusWorldgenStats = {});
            stats.distanceManagerLoopPulses =
              (Number(stats.distanceManagerLoopPulses) || 0) + 1;
            """)
    private static native void recordDistanceManagerPulse();

    @JSBody(params = "entries", script = """
            const stats = globalThis.__gaiusWorldgenStats ||
              (globalThis.__gaiusWorldgenStats = {});
            const safeEntries = Math.max(0, Number(entries) || 0);
            stats.chunkBroadcastBatches = (Number(stats.chunkBroadcastBatches) || 0) + 1;
            stats.lastChunkBroadcastEntries = safeEntries;
            stats.totalChunkBroadcastEntries =
              (Number(stats.totalChunkBroadcastEntries) || 0) + safeEntries;
            stats.maxChunkBroadcastEntries = Math.max(
              Number(stats.maxChunkBroadcastEntries) || 0,
              safeEntries
            );
            """)
    private static native void recordChunkBroadcastStart(int entries);

    @JSBody(script = """
            const stats = globalThis.__gaiusWorldgenStats ||
              (globalThis.__gaiusWorldgenStats = {});
            stats.chunkBroadcastItems = (Number(stats.chunkBroadcastItems) || 0) + 1;
            """)
    private static native void recordChunkBroadcastItem();

    @JSBody(params = "entries", script = """
            const stats = globalThis.__gaiusWorldgenStats ||
              (globalThis.__gaiusWorldgenStats = {});
            stats.lastChunkBroadcastCompleted = Math.max(0, Number(entries) || 0);
            stats.completedChunkBroadcastBatches =
              (Number(stats.completedChunkBroadcastBatches) || 0) + 1;
            """)
    private static native void recordChunkBroadcastFinish(int entries);

    @JSBody(script = """
            return typeof performance !== 'undefined' && performance.now
              ? performance.now()
              : Date.now();
            """)
    private static native double nowMillis();

    /** Stores one bounded label for the immediately following task-start marker. */
    @JSBody(params = {"taskLabel"}, script = """
            try {
              if (globalThis.__gaiusSlowProbeTelemetryEnabled !== true) return;
              const marker = globalThis.__gaiusWorldgenSchedulerMarker ||
                (globalThis.__gaiusWorldgenSchedulerMarker = {});
              const label = String(taskLabel || 'unlabeled').slice(0, 160);
              Object.defineProperty(marker, '__pendingTaskLabel', {
                configurable: true,
                enumerable: false,
                writable: true,
                value: label
              });
            } catch (_) {
              // Diagnostic telemetry is fail-open and may never perturb the scheduler.
            }
            """)
    private static native void recordSchedulerTaskLabel(String taskLabel);

    /**
     * Publishes fixed scalar task/turn markers only for the Node runtime smoke.
     * The browser release leaves the flag unset; malformed diagnostic state is
     * swallowed so evidence collection can never alter scheduler control flow.
     */
    @JSBody(params = {
            "event",
            "token",
            "taskDepth",
            "reentrantDepth",
            "yielding",
            "activeWorkMillis"
    }, script = """
            try {
              if (globalThis.__gaiusSlowProbeTelemetryEnabled !== true) return;
              const marker = globalThis.__gaiusWorldgenSchedulerMarker ||
                (globalThis.__gaiusWorldgenSchedulerMarker = {});
              const eventName = String(event || 'unknown');
              const now = Date.now();
              const workMillis = Math.max(0, Number(activeWorkMillis) || 0);
              const pendingTaskLabel = String(
                marker.__pendingTaskLabel || 'unlabeled'
              ).slice(0, 160);
              if (eventName.startsWith('task-start-')) {
                delete marker.__pendingTaskLabel;
              }
              marker.schemaVersion = 2;
              marker.eventSequence = (Number(marker.eventSequence) || 0) + 1;
              marker.lastEvent = eventName;
              marker.lastEventAtEpochMs = now;
              marker.token = Number(token) || 0;
              marker.taskWorkDepth = Math.max(0, Number(taskDepth) || 0);
              marker.reentrantTaskWorkDepth = Math.max(0, Number(reentrantDepth) || 0);
              marker.normalTaskScopeActive = marker.taskWorkDepth > 0;
              marker.activeTaskScope = marker.normalTaskScopeActive ||
                marker.reentrantTaskWorkDepth > 0;
              marker.yieldActive = !!yielding;
              marker.activeWorkMillis = workMillis;
            if (eventName === 'server-work-turn-start') {
              marker.serverWorkTurnSequence =
                (Number(marker.serverWorkTurnSequence) || 0) + 1;
              marker.serverWorkTurnActive = true;
              marker.lastServerWorkTurnStartedAtEpochMs = now;
            } else if (eventName === 'server-work-turn-end') {
              const serverWorkTurnWallMillis = Math.max(
                0,
                now - (Number(marker.lastServerWorkTurnStartedAtEpochMs) || now)
              );
              marker.serverWorkTurnActive = false;
              marker.lastServerWorkTurnEndedAtEpochMs = now;
              marker.lastServerWorkTurnWallMillis = serverWorkTurnWallMillis;
              marker.maxServerWorkTurnWallMillis = Math.max(
                Number(marker.maxServerWorkTurnWallMillis) || 0,
                serverWorkTurnWallMillis
              );
            } else if (eventName === 'task-start-normal') {
              marker.taskScopesStarted = (Number(marker.taskScopesStarted) || 0) + 1;
              marker.taskScopeSequence = (Number(marker.taskScopeSequence) || 0) + 1;
              marker.currentTaskScopeId = marker.taskScopeSequence;
              marker.currentTaskLabel = pendingTaskLabel;
              marker.currentNestedTaskLabel = '';
              marker.currentReentrantTaskLabel = '';
              marker.lastTaskStartedAtEpochMs = now;
              marker.__taskActiveWorkBaselineMillis = workMillis;
              marker.__taskActiveWorkAccumulatedMillis = 0;
            } else if (eventName === 'task-start-nested') {
              marker.currentNestedTaskLabel = pendingTaskLabel;
            } else if (eventName === 'task-end-nested') {
              marker.currentNestedTaskLabel = '';
            } else if (eventName === 'task-start-reentrant') {
              marker.reentrantTaskScopesStarted =
                (Number(marker.reentrantTaskScopesStarted) || 0) + 1;
              marker.currentReentrantTaskLabel = pendingTaskLabel;
            } else if (eventName === 'task-end-reentrant') {
              marker.reentrantTaskScopesEnded =
                (Number(marker.reentrantTaskScopesEnded) || 0) + 1;
              marker.currentReentrantTaskLabel = '';
            } else if (eventName === 'yield-start' && marker.normalTaskScopeActive) {
              marker.__taskActiveWorkAccumulatedMillis =
                Math.max(0, Number(marker.__taskActiveWorkAccumulatedMillis) || 0) +
                Math.max(0, workMillis -
                  (Number(marker.__taskActiveWorkBaselineMillis) || 0));
              marker.__taskActiveWorkBaselineMillis = workMillis;
            } else if (eventName === 'yield-end' && marker.normalTaskScopeActive) {
              // requestYield starts the resumed active segment from zero.  Keep
              // the next delta scoped to this task rather than the whole slice.
              marker.__taskActiveWorkBaselineMillis = workMillis;
            } else if (eventName === 'task-end-normal' ||
                       eventName === 'task-end-deferred-applied') {
              marker.__taskActiveWorkAccumulatedMillis =
                Math.max(0, Number(marker.__taskActiveWorkAccumulatedMillis) || 0) +
                Math.max(0, workMillis -
                  (Number(marker.__taskActiveWorkBaselineMillis) || 0));
              const taskActiveWorkMillis =
                Math.max(0, Number(marker.__taskActiveWorkAccumulatedMillis) || 0);
              const taskScopeWallMillis = Math.max(
                0,
                now - (Number(marker.lastTaskStartedAtEpochMs) || now)
              );
              marker.taskScopesEnded = (Number(marker.taskScopesEnded) || 0) + 1;
              marker.lastTaskEndedAtEpochMs = now;
              marker.lastTaskActiveWorkMillis = taskActiveWorkMillis;
              const previousMaxTaskActiveWorkMillis =
                Number(marker.maxTaskActiveWorkMillis) || 0;
              marker.maxTaskActiveWorkMillis = Math.max(
                previousMaxTaskActiveWorkMillis, taskActiveWorkMillis);
              marker.lastTaskScopeWallMillis = taskScopeWallMillis;
              const previousMaxTaskScopeWallMillis =
                Number(marker.maxTaskScopeWallMillis) || 0;
              marker.maxTaskScopeWallMillis = Math.max(
                previousMaxTaskScopeWallMillis, taskScopeWallMillis);
              if (taskActiveWorkMillis >= previousMaxTaskActiveWorkMillis ||
                  taskScopeWallMillis >= previousMaxTaskScopeWallMillis) {
                marker.maxTaskContext = JSON.stringify({
                  schemaVersion: 1,
                  taskScopeId: Math.max(0, Number(marker.currentTaskScopeId) || 0),
                  taskLabel: String(marker.currentTaskLabel || 'unlabeled').slice(0, 160),
                  taskActiveWorkMillis: taskActiveWorkMillis,
                  taskScopeWallMillis: taskScopeWallMillis
                }).slice(0, 512);
              }
              marker.currentTaskScopeId = 0;
              marker.currentTaskLabel = '';
              marker.currentNestedTaskLabel = '';
              marker.currentReentrantTaskLabel = '';
              delete marker.__taskActiveWorkBaselineMillis;
              delete marker.__taskActiveWorkAccumulatedMillis;
            } else if (eventName === 'task-end-underflow') {
              marker.taskScopeUnderflows =
                (Number(marker.taskScopeUnderflows) || 0) + 1;
            } else if (eventName === 'task-end-invalid') {
              marker.taskScopeInvalidEnds =
                (Number(marker.taskScopeInvalidEnds) || 0) + 1;
            }
            } catch (_) {
              // Diagnostic telemetry is fail-open and may never perturb the scheduler.
            }
            """)
    private static native void recordSchedulerMarker(
            String event,
            int token,
            int taskDepth,
            int reentrantDepth,
            boolean yielding,
            double activeWorkMillis);

    private static boolean hasPendingNetworkInput() {
        return BrowserWebSocketChannel.hasPendingInput()
                || BrowserPacketScheduler.hasPendingPackets();
    }

    @JSBody(script = """
            const bridge = globalThis.__gaiusNettyBridge;
            const stats = bridge && (bridge.stats || globalThis.__gaiusNetworkStats) ||
              globalThis.__gaiusNetworkStats;
            if (!stats) return 0;
            const packets = Math.max(0, Number(stats.decodedPacketQueue) || 0);
            const slices = Math.max(0, Number(stats.decodedSliceBacklog) || 0);
            const byteUnits = Math.ceil(
              Math.max(0, Number(stats.inboundQueuedBytes) || 0) / 16384
            );
            return Math.min(2147483647, Math.max(packets, slices, byteUnits));
            """)
    private static native int networkQueueDepth();

    @JSBody(params = {
            "networkWaitPulses",
            "yieldDelayMillis",
            "queueDepthBefore",
            "queueDepthAfter",
            "maximumReentrantYieldDepth"
    }, script = """
            const stats = globalThis.__gaiusWorldgenStats ||
              (globalThis.__gaiusWorldgenStats = {});
            if (!stats.__checkpointOnlyYieldDelayHistogram) {
              Object.defineProperty(stats, '__checkpointOnlyYieldDelayHistogram', {
                value: new Uint32Array(256),
                enumerable: false
              });
            }
            const histogram = stats.__checkpointOnlyYieldDelayHistogram;
            const safeDelay = Math.max(0, Number(yieldDelayMillis) || 0);
            const bucket = Math.min(histogram.length - 1, Math.floor(safeDelay));
            if (histogram[bucket] < 0xffffffff) histogram[bucket]++;

            stats.checkpointOnlyYields =
              (Number(stats.checkpointOnlyYields) || 0) + 1;
            stats.checkpointOnlyYieldDelayMillis = safeDelay;
            stats.totalCheckpointOnlyYieldDelayMillis =
              (Number(stats.totalCheckpointOnlyYieldDelayMillis) || 0) + safeDelay;
            stats.averageCheckpointOnlyYieldDelayMillis =
              stats.totalCheckpointOnlyYieldDelayMillis / stats.checkpointOnlyYields;
            stats.checkpointOnlyMaxYieldDelayMillis = Math.max(
              Number(stats.checkpointOnlyMaxYieldDelayMillis) || 0,
              safeDelay
            );
            const percentile = function(values, fraction) {
              const target = Math.max(
                1,
                Math.ceil(stats.checkpointOnlyYields * fraction)
              );
              let seen = 0;
              for (let index = 0; index < values.length; index++) {
                seen += values[index];
                if (seen >= target) return index + 1;
              }
              return values.length;
            };
            stats.checkpointOnlyP99YieldDelayMillis = percentile(histogram, 0.99);
            stats.checkpointOnlyQueueDepth = Math.max(
              0,
              Number(queueDepthAfter) || 0
            );
            stats.checkpointOnlyMaxQueueDepth = Math.max(
              Number(stats.checkpointOnlyMaxQueueDepth) || 0,
              Number(queueDepthBefore) || 0,
              Number(queueDepthAfter) || 0
            );
            stats.checkpointOnlyMaxNetworkWaitPulses = Math.max(
              Number(stats.checkpointOnlyMaxNetworkWaitPulses) || 0,
              Number(networkWaitPulses) || 0
            );
            stats.checkpointOnlyMaxReentrantYieldDepth = Math.max(
              Number(stats.checkpointOnlyMaxReentrantYieldDepth) || 0,
              Number(maximumReentrantYieldDepth) || 0
            );
            stats.checkpointOnlyLastYieldAt = Date.now();
            """)
    private static native void recordCheckpointOnlyYield(
            int networkWaitPulses,
            double yieldDelayMillis,
            int queueDepthBefore,
            int queueDepthAfter,
            int maximumReentrantYieldDepth);

    @JSBody(params = {
            "reason",
            "networkPreemption",
            "progressPulses",
            "networkWaitPulses",
            "sliceElapsedMillis",
            "completedBudgetMillis",
            "nextBudgetMillis",
            "overrunMillis",
            "yieldDelayMillis",
            "queueDepthBefore",
            "queueDepthAfter",
            "reentrantRequests",
            "networkWaitPulseLimit",
            "maximumPulsesInTurn",
            "maximumReentrantYieldDepth"
    }, script = """
            const stats = globalThis.__gaiusWorldgenStats ||
              (globalThis.__gaiusWorldgenStats = {});
            if (!stats.__sliceHistogram) {
              Object.defineProperty(stats, '__sliceHistogram', {
                value: new Uint32Array(128),
                enumerable: false
              });
            }
            if (!stats.__yieldDelayHistogram) {
              Object.defineProperty(stats, '__yieldDelayHistogram', {
                value: new Uint32Array(256),
                enumerable: false
              });
            }
            const histogram = stats.__sliceHistogram;
            const yieldDelayHistogram = stats.__yieldDelayHistogram;
            const bucket = Math.min(histogram.length - 1, Math.floor(sliceElapsedMillis));
            if (histogram[bucket] < 0xffffffff) histogram[bucket]++;
            const yieldDelayBucket = Math.min(
              yieldDelayHistogram.length - 1,
              Math.floor(yieldDelayMillis)
            );
            if (yieldDelayHistogram[yieldDelayBucket] < 0xffffffff) {
              yieldDelayHistogram[yieldDelayBucket]++;
            }

            stats.slices = (Number(stats.slices) || 0) + 1;
            stats.sliceElapsedMillis = sliceElapsedMillis;
            stats.totalSliceElapsedMillis =
              (Number(stats.totalSliceElapsedMillis) || 0) + sliceElapsedMillis;
            stats.averageSliceElapsedMillis = stats.totalSliceElapsedMillis / stats.slices;
            const previousMaxSliceElapsedMillis =
              Number(stats.maxSliceElapsedMillis) || 0;
            stats.maxSliceElapsedMillis = Math.max(
              previousMaxSliceElapsedMillis, sliceElapsedMillis);
            const percentile = function(values, fraction) {
              const target = Math.max(1, Math.ceil(stats.slices * fraction));
              let seen = 0;
              for (let index = 0; index < values.length; index++) {
                seen += values[index];
                if (seen >= target) return index + 1;
              }
              return values.length;
            };
            stats.p95SliceElapsedMillis = percentile(histogram, 0.95);
            stats.p99SliceElapsedMillis = percentile(histogram, 0.99);
            stats.p95YieldDelayMillis = percentile(yieldDelayHistogram, 0.95);
            stats.p99YieldDelayMillis = percentile(yieldDelayHistogram, 0.99);

            stats.configuredBudgetMillis = Number(globalThis.__gaiusWorldgenSliceMillis) ||
              completedBudgetMillis;
            stats.completedBudgetMillis = completedBudgetMillis;
            stats.budgetMillis = nextBudgetMillis;
            stats.minimumBudgetMillis = Number.isFinite(stats.minimumBudgetMillis)
              ? Math.min(stats.minimumBudgetMillis, nextBudgetMillis)
              : nextBudgetMillis;
            stats.budgetOverruns = (Number(stats.budgetOverruns) || 0) +
              (overrunMillis > 0 ? 1 : 0);
            stats.lastBudgetOverrunMillis = overrunMillis;
            stats.maxBudgetOverrunMillis = Math.max(
              Number(stats.maxBudgetOverrunMillis) || 0,
              overrunMillis
            );

            stats.yieldDelayMillis = yieldDelayMillis;
            stats.totalYieldDelayMillis =
              (Number(stats.totalYieldDelayMillis) || 0) + yieldDelayMillis;
            stats.averageYieldDelayMillis = stats.totalYieldDelayMillis / stats.slices;
            stats.maxYieldDelayMillis = Math.max(
              Number(stats.maxYieldDelayMillis) || 0,
              yieldDelayMillis
            );
            stats.queueDepth = queueDepthAfter;
            stats.maxQueueDepth = Math.max(
              Number(stats.maxQueueDepth) || 0,
              queueDepthBefore,
              queueDepthAfter
            );

            stats.progressPulses = progressPulses;
            stats.maxTurnPulses = Math.max(
              Number(stats.maxTurnPulses) || 0,
              maximumPulsesInTurn
            );
            stats.totalProgressPulses =
              (Number(stats.totalProgressPulses) || 0) + progressPulses;
            if (progressPulses > 0) {
              stats.progressSlices = (Number(stats.progressSlices) || 0) + 1;
              stats.consecutiveNoProgressSlices = 0;
            } else {
              stats.noProgressSlices = (Number(stats.noProgressSlices) || 0) + 1;
              stats.consecutiveNoProgressSlices =
                (Number(stats.consecutiveNoProgressSlices) || 0) + 1;
              stats.maxConsecutiveNoProgressSlices = Math.max(
                Number(stats.maxConsecutiveNoProgressSlices) || 0,
                stats.consecutiveNoProgressSlices
              );
            }

            if (networkPreemption) {
              stats.networkPreemptions = (Number(stats.networkPreemptions) || 0) + 1;
            }
            stats.lastNetworkWaitPulses = networkWaitPulses;
            stats.maxNetworkWaitPulses = Math.max(
              Number(stats.maxNetworkWaitPulses) || 0,
              networkWaitPulses
            );
            stats.networkWaitPulseLimit = networkWaitPulseLimit;
            stats.deadlineYields = (Number(stats.deadlineYields) || 0) +
              (reason === 0 ? 1 : 0);
            stats.hardCapYields = (Number(stats.hardCapYields) || 0) +
              (reason === 2 ? 1 : 0);
            stats.checkpointYields = (Number(stats.checkpointYields) || 0) +
              (reason === 3 ? 1 : 0);
            stats.reentrantYieldRequests = reentrantRequests;
            stats.maxReentrantYieldDepth = Math.max(
              Number(stats.maxReentrantYieldDepth) || 0,
              maximumReentrantYieldDepth
            );
            stats.lastYieldAt = Date.now();
            try {
              if (globalThis.__gaiusSlowProbeTelemetryEnabled === true &&
                  sliceElapsedMillis >= previousMaxSliceElapsedMillis) {
                const marker = globalThis.__gaiusWorldgenSchedulerMarker ||
                  (globalThis.__gaiusWorldgenSchedulerMarker = {});
                const reasonNames = ['deadline', 'network', 'hard-cap', 'checkpoint'];
                const taskLabel = String(
                  marker.currentNestedTaskLabel ||
                  marker.currentReentrantTaskLabel ||
                  marker.currentTaskLabel ||
                  'unlabeled'
                ).slice(0, 160);
                marker.maxSliceContext = JSON.stringify({
                  schemaVersion: 1,
                  sequence: Math.max(0, Number(stats.slices) || 0),
                  reason: reasonNames[Number(reason)] || 'unknown',
                  reasonCode: Number(reason) || 0,
                  taskScopeId: Math.max(0, Number(marker.currentTaskScopeId) || 0),
                  taskLabel: taskLabel,
                  sliceElapsedMillis: Math.max(0, Number(sliceElapsedMillis) || 0),
                  budgetOverrunMillis: Math.max(0, Number(overrunMillis) || 0),
                  progressPulses: Math.max(0, Number(progressPulses) || 0),
                  networkWaitPulses: Math.max(0, Number(networkWaitPulses) || 0),
                  queueDepthBefore: Math.max(0, Number(queueDepthBefore) || 0),
                  queueDepthAfter: Math.max(0, Number(queueDepthAfter) || 0),
                  networkPreemption: !!networkPreemption
                }).slice(0, 512);
              }
            } catch (_) {
              // Slow-probe attribution is diagnostic and fail-open.
            }
            """)
    private static native void reportSlice(
            int reason,
            boolean networkPreemption,
            int progressPulses,
            int networkWaitPulses,
            double sliceElapsedMillis,
            double completedBudgetMillis,
            double nextBudgetMillis,
            double overrunMillis,
            double yieldDelayMillis,
            int queueDepthBefore,
            int queueDepthAfter,
            int reentrantRequests,
            int networkWaitPulseLimit,
            int maximumPulsesInTurn,
            int maximumReentrantYieldDepth);
}
