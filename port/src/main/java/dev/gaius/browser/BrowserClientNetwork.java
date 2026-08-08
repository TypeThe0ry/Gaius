package dev.gaius.browser;

import org.teavm.jso.JSBody;

/** Records browser transport wakeups without entering packet handlers from JavaScript callbacks. */
public final class BrowserClientNetwork {
    private static boolean installed;

    private BrowserClientNetwork() {
    }

    public static void install() {
        if (installed) {
            return;
        }
        // Minecraft starts ticking before the first browser socket constructs its bridge.
        // Keep retrying from runTick until that bridge is available instead of permanently
        // missing the callback for a later multiplayer connection.
        installed = installInboundPump();
    }

    @JSBody(script = """
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
            };
            return true;
            """)
    private static native boolean installInboundPump();
}
