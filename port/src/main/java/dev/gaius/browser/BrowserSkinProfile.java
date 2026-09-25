package dev.gaius.browser;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import org.teavm.jso.JSBody;

/** Applies the public, short-lived textures property supplied by the browser shell. */
public final class BrowserSkinProfile {
    private BrowserSkinProfile() {
    }

    /** Adds the host profile's public textures property to a freshly-created login profile. */
    public static GameProfile apply(GameProfile profile) {
        if (profile == null) {
            return null;
        }
        String ownerName = descriptorName();
        String ownerUuid = descriptorUuid();
        if (ownerName == null || !ownerName.equals(profile.name())
                || ownerUuid == null || profile.id() == null
                || !ownerUuid.equals(profile.id().toString().replace("-", "").toLowerCase())) {
            return profile;
        }
        String value = descriptorValue();
        if (value == null || value.isBlank() || value.length() > 16_384) {
            return profile;
        }
        String signature = descriptorSignature();
        profile.properties().removeAll("textures");
        profile.properties().put("textures", signature == null || signature.isBlank()
                ? new Property("textures", value)
                : new Property("textures", value, signature));
        return profile;
    }

    @JSBody(script = """
            const descriptor = globalThis.__gaiusSkinDescriptor;
            return descriptor && typeof descriptor === 'object'
              ? String(descriptor.value || '') : '';
            """)
    private static native String descriptorValue();

    @JSBody(script = """
            const descriptor = globalThis.__gaiusSkinDescriptor;
            return descriptor && typeof descriptor === 'object'
              ? String(descriptor.signature || '') : '';
            """)
    private static native String descriptorSignature();

    @JSBody(script = """
            const descriptor = globalThis.__gaiusSkinDescriptor;
            return descriptor && typeof descriptor === 'object'
              ? String(descriptor.username || '') : '';
            """)
    private static native String descriptorName();

    @JSBody(script = """
            const descriptor = globalThis.__gaiusSkinDescriptor;
            return descriptor && typeof descriptor === 'object'
              ? String(descriptor.uuid || '') : '';
            """)
    private static native String descriptorUuid();
}
