package dev.gaius.browser;

import io.netty.channel.browser.BrowserWebSocketChannel;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/** Keeps client packets flowing while resource-pack work yields to the browser event loop. */
public final class BrowserClientNetwork {
    // A chunk packet may synchronously decode palettes, light, and section state.
    // Deliver one bridge frame per browser turn. Game packets are then handled by
    // the client tick, rather than extending this networking callback with chunk work.
    private static final int MAX_PUMPS_PER_SAFE_POINT = 1;
    private static boolean installed;
    private static boolean pumping;

    private BrowserClientNetwork() {
    }

    public static void install() {
        if (installed) {
            return;
        }
        // Minecraft starts ticking before the first browser socket constructs its bridge.
        // Keep retrying from runTick until that bridge is available instead of permanently
        // missing the callback for a later multiplayer connection.
        installed = installInboundPump(BrowserClientNetwork::pumpInbound);
    }

    /**
     * Delivers a pending browser socket frame while a cooperative reload owns the main loop.
     *
     * <p>Server resource packs can take longer than a server keepalive interval. The normal
     * browser callback is scheduled with a timer, but resource reload continuations need an
     * explicit safe point as well so timer delivery is never delayed behind the full reload.
     */
    public static void pumpNow() {
        pumpInbound();
    }

    private static void pumpInbound() {
        if (pumping) {
            recordPumpSkipped();
            return;
        }
        pumping = true;
        try {
            recordPumpStarted();
            for (int pump = 0; pump < MAX_PUMPS_PER_SAFE_POINT; pump++) {
                BrowserWebSocketChannel.pumpAll();
            }
            recordPumpCompleted();
        } finally {
            pumping = false;
        }
    }

    @JSBody(params = "callback", script = """
            const bridge = globalThis.__gaiusNettyBridge;
            if (!bridge) return false;
            const stats = bridge.stats || globalThis.__gaiusNetworkStats || {};
            stats.inboundPumpInstalled = (stats.inboundPumpInstalled|0) + 1;
            function report(stage) {
              const root = typeof document !== 'undefined' ? document.documentElement : null;
              if (!root) return;
              root.setAttribute('data-gaius-network-pump', JSON.stringify({
                stage: stage,
                requested: stats.inboundPumpRequested|0,
                callback: stats.inboundPumpCallback|0,
                started: stats.inboundPumpJavaStarted|0,
                completed: stats.inboundPumpJavaCompleted|0,
                skipped: stats.inboundPumpJavaSkipped|0,
                received: stats.receivedFrames|0,
                pumped: stats.pumpCalls|0
              }));
            }
            bridge.inboundPump = function() {
              stats.inboundPumpRequested = (stats.inboundPumpRequested|0) + 1;
              report('requested');
              if (bridge.inboundPumpPending) return;
              bridge.inboundPumpPending = true;
              setTimeout(function() {
                bridge.inboundPumpPending = false;
                stats.inboundPumpCallback = (stats.inboundPumpCallback|0) + 1;
                report('callback');
                callback();
              }, 0);
            };
            return true;
            """)
    private static native boolean installInboundPump(BrowserPumpCallback callback);

    @JSBody(script = """
            const stats = globalThis.__gaiusNetworkStats;
            if (stats) {
              stats.inboundPumpJavaStarted = (stats.inboundPumpJavaStarted|0) + 1;
              if (typeof document !== 'undefined' && document.documentElement) {
                document.documentElement.setAttribute('data-gaius-network-pump-stage', 'java-started');
              }
            }
            """)
    private static native void recordPumpStarted();

    @JSBody(script = """
            const stats = globalThis.__gaiusNetworkStats;
            if (stats) {
              stats.inboundPumpJavaCompleted = (stats.inboundPumpJavaCompleted|0) + 1;
              if (typeof document !== 'undefined' && document.documentElement) {
                document.documentElement.setAttribute('data-gaius-network-pump-stage', 'java-completed');
              }
            }
            """)
    private static native void recordPumpCompleted();

    @JSBody(script = """
            const stats = globalThis.__gaiusNetworkStats;
            if (stats) {
              stats.inboundPumpJavaSkipped = (stats.inboundPumpJavaSkipped|0) + 1;
              if (typeof document !== 'undefined' && document.documentElement) {
                document.documentElement.setAttribute('data-gaius-network-pump-stage', 'java-skipped');
              }
            }
            """)
    private static native void recordPumpSkipped();

    @JSFunctor
    private interface BrowserPumpCallback extends JSObject {
        void run();
    }
}
