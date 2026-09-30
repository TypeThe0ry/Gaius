package dev.gaius.browser;

import java.util.UUID;
import net.minecraft.client.Minecraft;
import org.teavm.jso.JSBody;

/**
 * Player identity owned by the browser shell (window.__gaiusProfile in the launcher).
 * Name and skin changes are applied to the running client without a page reload; they take
 * effect on the next world join or server connection, like the vanilla session would.
 */
public final class BrowserProfile {
    private BrowserProfile() {
    }

    public static boolean isOnline() {
        return isOnlineSession();
    }

    public static String savedSkinDataUrl() {
        String value = savedSkin();
        return value == null ? "" : value;
    }

    public static boolean savedSkinSlim() {
        return "slim".equals(savedSkinModel());
    }

    /** Starts the browser file picker; poll {@link #pickState()} for the result. */
    public static void beginSkinPick() {
        startSkinPick();
    }

    /** JSON: {"state":"idle|pending|done|error|cancelled","dataUrl":"…","slim":bool,"error":"…"}. */
    public static String pickState() {
        String state = readPickState();
        return state == null ? "{\"state\":\"idle\"}" : state;
    }

    public static void clearPickState() {
        resetPickState();
    }

    /** Textures property value for a data-URL skin, or "" when there is no custom skin. */
    public static String texturesValue(String dataUrl, boolean slim) {
        if (dataUrl == null || dataUrl.isEmpty()) {
            return "";
        }
        String value = encodeTextures(dataUrl, slim);
        return value == null ? "" : value;
    }

    /** True once per first launch, so the title screen can open the profile editor. */
    public static boolean consumeFirstRun() {
        return takeFirstRun();
    }

    /**
     * Validates, persists and applies a new identity. Returns an error message, or null when
     * the running client now uses the new name/skin.
     */
    public static String apply(Minecraft minecraft, String name, String skinDataUrl, boolean slim) {
        String result = persist(name == null ? "" : name, skinDataUrl == null ? "" : skinDataUrl, slim);
        if (result == null || !result.startsWith("ok:")) {
            return result == null || result.isEmpty() ? "Could not save the profile" : result;
        }
        if (!isOnline()) {
            // Minecraft 26.3: the patched Minecraft still declares
            // gaius$replaceIdentity(User, com.mojang.authlib.yggdrasil.ProfileResult), a type that
            // authlib 10 no longer has.  Work package P7a takes the bridge descriptor from
            // ModernSymbols (D7) and builds a services ProfileResult here; until then an offline
            // identity change fails loudly instead of linking against the missing type.
            throw new UnsupportedOperationException("P7a");
        }
        return null;
    }

    public static UUID uuidFromHex(String hex) {
        String clean = hex.replace("-", "");
        long most = (Long.parseLong(clean.substring(0, 8), 16) << 32)
                | Long.parseLong(clean.substring(8, 16), 16);
        long least = (Long.parseLong(clean.substring(16, 24), 16) << 32)
                | Long.parseLong(clean.substring(24, 32), 16);
        return new UUID(most, least);
    }

    @JSBody(script = "return String(globalThis.__gaiusSessionMode || 'offline') === 'online';")
    private static native boolean isOnlineSession();

    @JSBody(script = """
            const p = globalThis.__gaiusProfile;
            return p && typeof p.savedSkin === 'function' ? String(p.savedSkin() || '') : '';
            """)
    private static native String savedSkin();

    @JSBody(script = """
            const p = globalThis.__gaiusProfile;
            return p && typeof p.savedSkinModel === 'function' ? String(p.savedSkinModel() || '') : '';
            """)
    private static native String savedSkinModel();

    @JSBody(script = """
            const p = globalThis.__gaiusProfile;
            if (p && typeof p.pickSkin === 'function') p.pickSkin();
            """)
    private static native void startSkinPick();

    @JSBody(script = """
            const p = globalThis.__gaiusProfile;
            return p && p.pick ? JSON.stringify(p.pick) : null;
            """)
    private static native String readPickState();

    @JSBody(script = """
            const p = globalThis.__gaiusProfile;
            if (p) p.pick = {state: 'idle'};
            """)
    private static native void resetPickState();

    @JSBody(params = {"dataUrl", "slim"}, script = """
            const p = globalThis.__gaiusProfile;
            return p && typeof p.texturesValue === 'function'
              ? String(p.texturesValue(String(dataUrl || ''), !!slim) || '') : '';
            """)
    private static native String encodeTextures(String dataUrl, boolean slim);

    @JSBody(script = """
            const p = globalThis.__gaiusProfile;
            if (!p || !p.firstRun) return false;
            p.firstRun = false;
            return true;
            """)
    private static native boolean takeFirstRun();

    @JSBody(params = {"name", "dataUrl", "slim"}, script = """
            const p = globalThis.__gaiusProfile;
            if (!p || typeof p.apply !== 'function') return 'Profile storage is unavailable';
            try {
              const result = p.apply(String(name || ''), String(dataUrl || ''), !!slim);
              return result && result.ok ? 'ok:' + result.uuid : String(result && result.error || 'Could not save the profile');
            } catch (error) {
              return String(error && error.message || error);
            }
            """)
    private static native String persist(String name, String dataUrl, boolean slim);
}
