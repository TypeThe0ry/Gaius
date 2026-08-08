package dev.gaius.browser;

import org.teavm.jso.JSBody;

/** Marks integrated-server world generation scheduling points for browser builds. */
public final class BrowserWorldgenScheduler {
    private static final double DEFAULT_SLICE_MILLIS = 12.0;
    private static final int CLOCK_CHECK_INTERVAL = 8;
    private static final int NETWORK_FAIRNESS_INTERVAL = 4;
    private static final long NETWORK_PRIORITY_SLEEP_MILLIS = 1L;

    private static double deadlineMillis;
    private static int pulsesUntilClockCheck = 1;
    private static int yieldsUntilNetworkFairness = NETWORK_FAIRNESS_INTERVAL;

    private BrowserWorldgenScheduler() {
    }

    public static void checkpoint() {
        yieldNow();
    }

    public static void pulse() {
        if (--pulsesUntilClockCheck > 0) {
            return;
        }
        pulsesUntilClockCheck = CLOCK_CHECK_INTERVAL;

        double now = nowMillis();
        if (deadlineMillis == 0.0) {
            deadlineMillis = now + sliceMillis();
        } else if (now >= deadlineMillis) {
            yieldNow();
        }
    }

    private static void yieldNow() {
        // TeaVM's Thread.yield only switches after a large call/time threshold. A zero-delay
        // sleep resumes this server thread through the Worker event loop. Periodically give
        // MessagePort one millisecond, then drain a bounded packet batch on this same thread.
        boolean networkActive = networkPumpCount() > 0;
        boolean networkFairness = false;
        if (networkActive && --yieldsUntilNetworkFairness <= 0) {
            yieldsUntilNetworkFairness = NETWORK_FAIRNESS_INTERVAL;
            networkFairness = true;
        }
        boolean prioritizeNetwork = networkActive
                && (networkFairness || hasPendingNetworkInput());
        try {
            Thread.sleep(prioritizeNetwork ? NETWORK_PRIORITY_SLEEP_MILLIS : 0L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        if (prioritizeNetwork) {
            BrowserIntegratedServerMain.pumpUrgentPackets();
        }
        deadlineMillis = nowMillis() + sliceMillis();
        pulsesUntilClockCheck = CLOCK_CHECK_INTERVAL;
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

    @JSBody(script = """
            const bridge = globalThis.__gaiusNettyBridge;
            const stats = bridge && (bridge.stats || globalThis.__gaiusNetworkStats);
            return !!stats && Number(stats.inboundQueuedBytes || 0) > 0;
            """)
    private static native boolean hasPendingNetworkInput();

    @JSBody(script = """
            const bridge = globalThis.__gaiusNettyBridge;
            const stats = bridge && (bridge.stats || globalThis.__gaiusNetworkStats);
            return stats ? (Number(stats.pumpCalls || 0) | 0) : 0;
            """)
    private static native int networkPumpCount();
}
