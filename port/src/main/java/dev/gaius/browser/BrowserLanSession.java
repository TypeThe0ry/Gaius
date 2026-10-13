package dev.gaius.browser;

import dev.gaius.browser.ops.VersionOps;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.network.chat.Component;
import java.util.UUID;

/**
 * Browser equivalent of vanilla's Open to LAN action for the Worker-hosted world.
 *
 * <p>One source for every profile. Two details are not fixed:
 * <ul>
 *   <li>the profile it reports when the page did not define {@code __gaiusProfileId} is the
 *       running client's own version id, never a fixed one, so a page cannot publish an invite
 *       for another version;</li>
 *   <li>how many pause-menu grid columns the full-width (204) button spans depends on where the
 *       pause-menu hook (MinecraftClientPatcher's patchPauseScreenBrowserSingleplayer) sits in
 *       the vanilla menu of the render seam generation, so {@link VersionOps} answers it. With a
 *       one-column span GridLayout widens column 0 to 204 for every row; where the hook runs
 *       before the Options row the button spans both columns, like Return to Game and
 *       Disconnect.</li>
 * </ul>
 */
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
                .build(), VersionOps.INSTANCE.pauseMenuLanButtonColumns());
    }

    /** Publishes a relay-backed LAN/share invitation through the browser shell. */
    public static void open(Minecraft minecraft) {
        if (minecraft == null || !BrowserSingleplayerClient.hasActiveWorkerSession()) {
            return;
        }
        String brokerSessionId = UUID.randomUUID().toString().replace("-", "");
        if (brokerSessionId.length() != 32
                || !BrowserSingleplayerClient.requestLanServerConnection(brokerSessionId)) {
            return;
        }
        publishLanInvite(brokerSessionId, clientVersionId());
    }

    /** The running client's version id (SharedConstants), or "" when it is not known yet. */
    static String clientVersionId() {
        try {
            String id = SharedConstants.getCurrentVersion().id();
            return id == null ? "" : id;
        } catch (RuntimeException unknownVersion) {
            return "";
        }
    }

    /** Kept as a separate hook so the browser shell can expose a stable acceptance contract. */
    @org.teavm.jso.JSBody(params = {"brokerSessionId", "clientVersionId"}, script = """
            const root = globalThis;
            if (typeof root.__gaiusOpenToLan === 'function') {
              const session = root.__gaiusSession && typeof root.__gaiusSession === 'object'
                ? root.__gaiusSession : {};
              root.__gaiusOpenToLan({
                profile: String(root.__gaiusProfileId || clientVersionId || ''),
                username: String(session.username || ''),
                brokerSessionId: String(brokerSessionId || '')
              });
            } else {
              console.warn('[Gaius] Open to LAN shell is unavailable');
            }
            """)
    private static native void publishLanInvite(String brokerSessionId, String clientVersionId);
}
