package dev.gaius.browser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.network.chat.Component;
import java.util.UUID;

/** Browser equivalent of vanilla's Open to LAN action for the Worker-hosted world. */
public final class BrowserLanSession {
    private static final Component OPEN_TO_LAN = Component.literal("Open to LAN");

    private BrowserLanSession() {
    }

    /** Adds the action to the pause menu only when a live local Worker world exists. */
    public static void maybeAddButton(Minecraft minecraft, GridLayout.RowHelper row) {
        if (minecraft == null || row == null || !BrowserSingleplayerClient.hasActiveWorkerSession()) {
            return;
        }
        row.addChild(Button.builder(OPEN_TO_LAN, ignored -> open(minecraft))
                .width(204)
                .build());
    }

    /** Publishes a relay-backed LAN/share invitation through the browser shell. */
    public static void open(Minecraft minecraft) {
        if (minecraft == null || !BrowserSingleplayerClient.hasActiveWorkerSession()) {
            return;
        }
        String brokerSessionId = UUID.randomUUID().toString().replace("-", "");
        if (brokerSessionId.length() != 32
                || !BrowserIntegratedServerMain.openLanServerConnection(brokerSessionId)) {
            return;
        }
        publishLanInvite(brokerSessionId);
    }

    /** Kept as a separate hook so the browser shell can expose a stable acceptance contract. */
    @org.teavm.jso.JSBody(params = {"brokerSessionId"}, script = """
            const root = globalThis;
            if (typeof root.__gaiusOpenToLan === 'function') {
              const session = root.__gaiusSession && typeof root.__gaiusSession === 'object'
                ? root.__gaiusSession : {};
              root.__gaiusOpenToLan({
                profile: String(root.__gaiusProfileId || '26.2'),
                username: String(session.username || ''),
                brokerSessionId: String(brokerSessionId || '')
              });
            } else {
              console.warn('[Gaius] Open to LAN shell is unavailable');
            }
            """)
    private static native void publishLanInvite(String brokerSessionId);
}
