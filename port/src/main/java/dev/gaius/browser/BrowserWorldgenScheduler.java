package dev.gaius.browser;

import io.netty.channel.browser.BrowserWebSocketChannel;
import org.teavm.classlib.java.lang.TModernRuntimeSupport;
import org.teavm.jso.JSBody;

/** Keeps integrated-server world generation cooperative in browser Workers. */
public final class BrowserWorldgenScheduler {
    private static final double DEFAULT_SLICE_MILLIS = 12.0;
    private static final double MIN_ADAPTIVE_SLICE_MILLIS = 2.0;
    private static final double BUDGET_RECOVERY_MILLIS = 0.5;
    private static final double MODERATE_YIELD_DELAY_MILLIS = 4.0;
    private static final double BUSY_YIELD_DELAY_MILLIS = 8.0;
    private static final int CLOCK_CHECK_INTERVAL = 4;
    private static final int NETWORK_CHECK_INTERVAL = 2;
    private static final int MIN_PROGRESS_PULSES_BEFORE_NETWORK_PREEMPTION = 2;
    private static final int MAX_NETWORK_WAIT_PULSES = 2;
    private static final int MAX_PULSES_PER_TURN = 64;

    private static final int YIELD_DEADLINE = 0;
    private static final int YIELD_NETWORK = 1;
    private static final int YIELD_HARD_CAP = 2;
    private static final int YIELD_CHECKPOINT = 3;

    private static double sliceStartedAtMillis;
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
    private static boolean networkPreemptionPending;
    private static boolean yieldActive;
    private static boolean deferredYield;

    private BrowserWorldgenScheduler() {
    }

    public static void checkpoint() {
        requestYield(YIELD_CHECKPOINT, networkQueueDepth());
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
        } else if (now >= deadlineMillis) {
            requestYield(YIELD_DEADLINE, networkQueueDepth());
        }
    }

    private static void requestYield(int reason, int observedQueueDepth) {
        if (yieldActive) {
            yieldReentrantContinuation();
            return;
        }
        yieldActive = true;
        try {
            double configuredBudgetMillis = sliceMillis();
            double beforePumpAt = nowMillis();
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
            double sliceElapsedMillis = Math.max(0.0, beforePumpAt - sliceStartedAtMillis);
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
            }

            boolean madeProgress = progressPulsesInSlice > 0;
            int pressureDepth = Math.max(queueDepthBefore, queueDepthAfter);
            previousYieldDelayMillis = yieldDelayMillis;
            previousOverrunMillis = overrunMillis;
            currentBudgetMillis = adaptiveBudgetMillis(
                    configuredBudgetMillis,
                    pressureDepth,
                    yieldDelayMillis,
                    overrunMillis,
                    madeProgress);
            boolean networkPreemption = reason == YIELD_NETWORK || pendingBefore || pendingAfter;
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

            double nextSliceStartedAt = nowMillis();
            sliceStartedAtMillis = nextSliceStartedAt;
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
            if (deferredYield) {
                deferredYield = false;
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
        deadlineMillis = now + currentBudgetMillis;
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
        try {
            TModernRuntimeSupport.yieldToEventLoop(0);
        } finally {
            reentrantYieldDepth--;
        }
    }

    @JSBody(params = "fallback", script = """
            const configured = Number(globalThis.__gaiusWorldgenSliceMillis);
            return Number.isFinite(configured) && configured >= 4 && configured <= 50
              ? configured
              : fallback;
            """)
    private static native double configuredSliceMillis(double fallback);

    private static double sliceMillis() {
        return configuredSliceMillis(DEFAULT_SLICE_MILLIS);
    }

    @JSBody(script = """
            return typeof performance !== 'undefined' && performance.now
              ? performance.now()
              : Date.now();
            """)
    private static native double nowMillis();

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
            const histogram = stats.__sliceHistogram;
            const bucket = Math.min(histogram.length - 1, Math.floor(sliceElapsedMillis));
            if (histogram[bucket] < 0xffffffff) histogram[bucket]++;

            stats.slices = (Number(stats.slices) || 0) + 1;
            stats.sliceElapsedMillis = sliceElapsedMillis;
            stats.totalSliceElapsedMillis =
              (Number(stats.totalSliceElapsedMillis) || 0) + sliceElapsedMillis;
            stats.averageSliceElapsedMillis = stats.totalSliceElapsedMillis / stats.slices;
            stats.maxSliceElapsedMillis = Math.max(
              Number(stats.maxSliceElapsedMillis) || 0,
              sliceElapsedMillis
            );
            const percentile = function(fraction) {
              const target = Math.max(1, Math.ceil(stats.slices * fraction));
              let seen = 0;
              for (let index = 0; index < histogram.length; index++) {
                seen += histogram[index];
                if (seen >= target) return index + 1;
              }
              return histogram.length;
            };
            stats.p95SliceElapsedMillis = percentile(0.95);
            stats.p99SliceElapsedMillis = percentile(0.99);

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
