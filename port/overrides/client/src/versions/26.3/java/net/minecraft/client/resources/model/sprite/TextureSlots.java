package net.minecraft.client.resources.model.sprite;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.client.resources.model.ModelDebugName;
import net.minecraft.util.GsonHelper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** Texture-slot resolution with a particle fallback for incomplete resource-pack models. */
public class TextureSlots {
    public static final TextureSlots EMPTY = new TextureSlots(Map.of());
    private static final char REFERENCE_CHAR = '#';

    private final Map<String, Material> resolvedValues;
    private final Material fallbackParticle;

    private TextureSlots(Map<String, Material> resolvedValues) {
        this.resolvedValues = resolvedValues;
        this.fallbackParticle = resolvedValues.values().stream().findFirst().orElse(null);
    }

    public @Nullable Material getMaterial(String reference) {
        if (isTextureReference(reference)) {
            reference = reference.substring(1);
        }
        Material material = this.resolvedValues.get(reference);
        return material != null || !"particle".equals(reference) ? material : this.fallbackParticle;
    }

    private static boolean isTextureReference(String texture) {
        return !texture.isEmpty() && texture.charAt(0) == REFERENCE_CHAR;
    }

    public static Data parseTextureMap(JsonObject textures) {
        Data.Builder builder = new Data.Builder();
        for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
            parseEntry(entry.getKey(), entry.getValue(), builder);
        }
        return builder.build();
    }

    private static void parseEntry(String slot, JsonElement value, Data.Builder output) {
        if (GsonHelper.isStringValue(value) && isTextureReference(value.getAsString())) {
            output.addReference(slot, value.getAsString().substring(1));
            return;
        }
        Material material = Material.CODEC.parse(JsonOps.INSTANCE, value)
                .getOrThrow(JsonParseException::new);
        output.addTexture(slot, material);
    }

    public static final record Data(Map<String, SlotContents> values) {
        public static final Data EMPTY = new Data(Map.of());

        public static class Builder {
            private final Map<String, SlotContents> textureMap = new HashMap<>();

            public Builder addReference(String slot, String reference) {
                this.textureMap.put(slot, new Reference(reference));
                return this;
            }

            public Builder addTexture(String slot, Material material) {
                this.textureMap.put(slot, new Value(material));
                return this;
            }

            public Data build() {
                return this.textureMap.isEmpty() ? EMPTY : new Data(Map.copyOf(this.textureMap));
            }
        }
    }

    public static class Resolver {
        private static final Logger LOGGER = LogUtils.getLogger();
        private final List<Data> entries = new ArrayList<>();

        public Resolver addLast(Data data) {
            this.entries.add(data);
            return this;
        }

        public Resolver addFirst(Data data) {
            this.entries.add(0, data);
            return this;
        }

        public TextureSlots resolve(ModelDebugName debugName) {
            if (this.entries.isEmpty()) {
                return EMPTY;
            }
            Map<String, Material> resolved = new LinkedHashMap<>();
            Map<String, Reference> unresolved = new LinkedHashMap<>();
            for (int index = this.entries.size() - 1; index >= 0; index--) {
                for (Map.Entry<String, SlotContents> entry : this.entries.get(index).values().entrySet()) {
                    String slot = entry.getKey();
                    SlotContents contents = entry.getValue();
                    if (contents instanceof Value value) {
                        unresolved.remove(slot);
                        resolved.put(slot, value.material());
                    } else if (contents instanceof Reference reference) {
                        resolved.remove(slot);
                        unresolved.put(slot, reference);
                    }
                }
            }
            boolean changed;
            do {
                changed = false;
                var iterator = unresolved.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<String, Reference> entry = iterator.next();
                    Material material = resolved.get(entry.getValue().target());
                    if (material != null) {
                        resolved.put(entry.getKey(), material);
                        iterator.remove();
                        changed = true;
                    }
                }
            } while (changed);
            if (!unresolved.isEmpty()) {
                String details = unresolved.entrySet().stream()
                        .map(entry -> "\t#" + entry.getKey() + " -> #"
                                + entry.getValue().target() + "\n")
                        .collect(Collectors.joining());
                LOGGER.warn("Unresolved texture references in {}:\n{}", debugName.debugName(), details);
            }
            return new TextureSlots(Map.copyOf(resolved));
        }
    }

    private static final record Reference(String target) implements SlotContents {
    }

    private static final record Value(Material material) implements SlotContents {
    }

    public interface SlotContents {
    }
}
