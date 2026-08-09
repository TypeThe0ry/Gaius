package dev.gaius.browser;

import org.teavm.classlib.java.lang.TModernRuntimeSupport;
import org.teavm.jso.JSBody;

/** Marks integrated-server world generation scheduling points for browser builds. */
public final class BrowserWorldgenScheduler {
    private static final double DEFAULT_SLICE_MILLIS = 12.0;
    private static final int CLOCK_CHECK_INTERVAL = 8;
    private static final int NETWORK_FAIRNESS_INTERVAL = 4;
    private static final int NETWORK_PRIORITY_DELAY_MILLIS = 1;

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
        } else if (now >= deadlineMillis || hasPendingNetworkInput()) {
            yieldNow();
        }
    }

    private static void yieldNow() {
        // TeaVM's Thread.sleep stores one interrupt handler on the emulated Thread. Worldgen and
        // the server packet loop share that Thread, so overlapping sleeps can strand the wrong
        // continuation. Use an independent platform callback for every cooperative yield.
        boolean pendingNetworkInput = hasPendingNetworkInput();
        boolean networkActive = pendingNetworkInput || networkPumpCount() > 0;
        boolean networkFairness = false;
        if (networkActive && --yieldsUntilNetworkFairness <= 0) {
            yieldsUntilNetworkFairness = NETWORK_FAIRNESS_INTERVAL;
            networkFairness = true;
        }
        boolean prioritizeNetwork = networkActive
                && (networkFairness || pendingNetworkInput);
        TModernRuntimeSupport.yieldToEventLoop(
                prioritizeNetwork ? NETWORK_PRIORITY_DELAY_MILLIS : 0);
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
