package net.minecraft.client.renderer.block.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.Identifier;

/** Texture-slot parsing with a narrow compatibility path for modern resource packs. */
public class TextureSlots {
    public static final TextureSlots EMPTY = new TextureSlots(Map.of());
    private static final char REFERENCE_CHAR = '#';

    private final Map<String, Material> resolvedValues;
    private final Material fallbackParticle;

    TextureSlots(Map<String, Material> resolvedValues) {
        this.resolvedValues = resolvedValues;
        this.fallbackParticle = resolvedValues.values().stream().findFirst().orElse(null);
    }

    public Material getMaterial(String name) {
        if (isTextureReference(name)) {
            name = name.substring(1);
        }
        Material material = resolvedValues.get(name);
        // Blockbench-generated models often omit particle despite having a
        // complete texture table. Vanilla then renders their break particles
        // with the missing sprite. Use the first resolved model texture only
        // for particle; all other missing slots remain strict and visible.
        return material != null || !"particle".equals(name) ? material : fallbackParticle;
    }

    public static TextureSlots.Data parseTextureMap(JsonObject textures) {
        TextureSlots.Data.Builder builder = new TextureSlots.Data.Builder();
        for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
            parseEntry(entry.getKey(), textureValue(entry.getValue(), entry.getKey()), builder);
        }
        return builder.build();
    }

    private static String textureValue(JsonElement value, String slot) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return value.getAsString();
        }
        // Blockbench and 1.21.6+ packs can attach rendering hints such as
        // force_translucent around a standard sprite identifier. Gaius does not
        // implement those optional hints yet, but the sprite remains valid.
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            JsonElement sprite = object.get("sprite");
            if (sprite != null && sprite.isJsonPrimitive()
                    && sprite.getAsJsonPrimitive().isString()) {
                return sprite.getAsString();
            }
        }
        throw new JsonParseException("Texture slot '" + slot + "' must be a string or sprite object");
    }

    private static void parseEntry(String slot, String value, TextureSlots.Data.Builder builder) {
        if (isTextureReference(value)) {
            builder.addReference(slot, value.substring(1));
            return;
        }
        Identifier id = Identifier.tryParse(value);
        if (id == null) {
            throw new JsonParseException("Invalid texture reference: " + value);
        }
        builder.addTexture(slot, new Material(ModelManager.BLOCK_OR_ITEM, id));
    }

    private static boolean isTextureReference(String value) {
        return !value.isEmpty() && value.charAt(0) == REFERENCE_CHAR;
    }

    public interface SlotContents {
    }

    static final record Reference(String target) implements SlotContents {
    }

    static final record Value(Material material) implements SlotContents {
    }

    public static final record Data(Map<String, SlotContents> values) {
        public static final Data EMPTY = new Data(Map.of());

        public static class Builder {
            private final Map<String, SlotContents> textureMap = new LinkedHashMap<>();

            public Builder addReference(String slot, String target) {
                textureMap.put(slot, new Reference(target));
                return this;
            }

            public Builder addTexture(String slot, Material material) {
                textureMap.put(slot, new Value(material));
                return this;
            }

            public Data build() {
                return textureMap.isEmpty()
                        ? EMPTY
                        : new Data(Collections.unmodifiableMap(new LinkedHashMap<>(textureMap)));
            }
        }
    }
}
