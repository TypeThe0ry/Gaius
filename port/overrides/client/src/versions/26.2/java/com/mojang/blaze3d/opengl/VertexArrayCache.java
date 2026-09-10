package com.mojang.blaze3d.opengl;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.util.VisibleForDebug;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.ARBVertexAttribBinding;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GLCapabilities;

/** Minecraft 26.2 vertex-array binding with bounded browser VAO caches. */
public abstract class VertexArrayCache {
    private static final int MAX_CACHED_WEBGL_VAOS = 2048;
    private static final int HOT_CACHE_SIZE = 256;
    private static final int HOT_CACHE_MASK = HOT_CACHE_SIZE - 1;

    public static VertexArrayCache create(
            GLCapabilities capabilities,
            GlDebugLabel debugLabels,
            Set<String> enabledExtensions) {
        if (capabilities.GL_ARB_vertex_attrib_binding
                && GlDevice.USE_GL_ARB_vertex_attrib_binding) {
            enabledExtensions.add("GL_ARB_vertex_attrib_binding");
            return new Separate(debugLabels);
        }
        return new Emulated(debugLabels);
    }

    public abstract VertexArray bindVertexArray(
            @Nullable VertexFormat[] vertexBindings,
            @Nullable GpuBufferSlice[] vertexBuffers,
            @Nullable VertexArray lastBoundVertexArray);

    private static final class Separate extends VertexArrayCache {
        private final BrowserVaoCache cache = new BrowserVaoCache();
        private final GlDebugLabel debugLabels;
        private final boolean needsMesaWorkaround;

        private Separate(GlDebugLabel debugLabels) {
            this.debugLabels = debugLabels;
            if ("Mesa".equals(GlStateManager._getString(7936))) {
                String version = GlStateManager._getString(7938);
                this.needsMesaWorkaround = version.contains("25.0.0")
                        || version.contains("25.0.1")
                        || version.contains("25.0.2");
            } else {
                this.needsMesaWorkaround = false;
            }
        }

        @Override
        public VertexArray bindVertexArray(
                @Nullable VertexFormat[] vertexBindings,
                @Nullable GpuBufferSlice[] vertexBuffers,
                @Nullable VertexArray lastBoundVertexArray) {
            VertexArray vertexArray = this.cache.get(vertexBindings);
            if (vertexArray == null) {
                int id = GL30.glGenVertexArrays();
                GL30.glBindVertexArray(id);
                int attributeLocation = 0;
                for (int bindingIndex = 0; bindingIndex < vertexBindings.length; bindingIndex++) {
                    VertexFormat binding = vertexBindings[bindingIndex];
                    if (binding == null) {
                        continue;
                    }
                    for (VertexFormatElement element : binding.getElements()) {
                        if (element == null) {
                            continue;
                        }
                        GlStateManager._enableVertexAttribArray(attributeLocation);
                        int externalFormat = GlConst.toGlExternalId(element.format());
                        int type = GlConst.toGlType(element.format());
                        int components = GlConst.glFormatChannelCount(externalFormat);
                        if (GlConst.isGlFormatInteger(externalFormat)) {
                            ARBVertexAttribBinding.glVertexAttribIFormat(
                                    attributeLocation, components, type, element.offset());
                        } else {
                            ARBVertexAttribBinding.glVertexAttribFormat(
                                    attributeLocation,
                                    components,
                                    type,
                                    GlConst.isFormatNormalized(element.format()),
                                    element.offset());
                        }
                        ARBVertexAttribBinding.glVertexAttribBinding(
                                attributeLocation, bindingIndex);
                        attributeLocation++;
                    }
                    ARBVertexAttribBinding.glVertexBindingDivisor(
                            bindingIndex, binding.getStepRate());
                }
                bindSeparateBuffers(vertexBindings, vertexBuffers, false);
                VertexArray created = new VertexArray(id, vertexBindings);
                this.debugLabels.applyLabel(created);
                this.cache.put(vertexBindings, created);
                return created;
            }

            GL30.glBindVertexArray(vertexArray.id);
            if (vertexArray != lastBoundVertexArray) {
                bindSeparateBuffers(vertexBindings, vertexBuffers, this.needsMesaWorkaround);
            }
            return vertexArray;
        }

        private static void bindSeparateBuffers(
                VertexFormat[] vertexBindings,
                GpuBufferSlice[] vertexBuffers,
                boolean unbindFirst) {
            for (int index = 0; index < vertexBuffers.length; index++) {
                GpuBufferSlice slice = vertexBuffers[index];
                if (slice == null) {
                    continue;
                }
                GlBuffer buffer = (GlBuffer) slice.buffer();
                if (unbindFirst) {
                    ARBVertexAttribBinding.glBindVertexBuffer(index, 0, 0L, 0);
                }
                ARBVertexAttribBinding.glBindVertexBuffer(
                        index,
                        buffer.handle(),
                        slice.offset(),
                        vertexBindings[index].getVertexSize());
            }
        }
    }

    private static final class Emulated extends VertexArrayCache {
        private final BrowserVaoCache cache = new BrowserVaoCache();
        private final GlDebugLabel debugLabels;

        private Emulated(GlDebugLabel debugLabels) {
            this.debugLabels = debugLabels;
        }

        @Override
        public VertexArray bindVertexArray(
                @Nullable VertexFormat[] vertexBindings,
                @Nullable GpuBufferSlice[] vertexBuffers,
                @Nullable VertexArray lastBoundVertexArray) {
            VertexArray vertexArray = this.cache.get(vertexBindings);
            if (vertexArray == null) {
                int id = GL30.glGenVertexArrays();
                GL30.glBindVertexArray(id);
                setupCombinedAttributes(vertexBindings, true, vertexBuffers);
                VertexArray created = new VertexArray(id, vertexBindings);
                this.debugLabels.applyLabel(created);
                this.cache.put(vertexBindings, created);
                return created;
            }

            GL30.glBindVertexArray(vertexArray.id);
            if (vertexArray != lastBoundVertexArray) {
                setupCombinedAttributes(vertexBindings, false, vertexBuffers);
            }
            return vertexArray;
        }

        private static void setupCombinedAttributes(
                VertexFormat[] vertexBindings,
                boolean enable,
                GpuBufferSlice[] vertexBuffers) {
            int attributeIndex = 0;
            for (int bindingIndex = 0; bindingIndex < vertexBindings.length; bindingIndex++) {
                VertexFormat binding = vertexBindings[bindingIndex];
                if (binding == null) {
                    continue;
                }
                GpuBufferSlice slice = vertexBuffers[bindingIndex];
                GlBuffer buffer = (GlBuffer) slice.buffer();
                GlStateManager._glBindBuffer(34962, buffer.handle());
                int vertexSize = binding.getVertexSize();
                for (VertexFormatElement element : binding.getElements()) {
                    long totalOffset = slice.offset() + element.offset();
                    int externalFormat = GlConst.toGlExternalId(element.format());
                    int type = GlConst.toGlType(element.format());
                    int components = GlConst.glFormatChannelCount(externalFormat);
                    if (enable) {
                        GlStateManager._enableVertexAttribArray(attributeIndex);
                    }
                    if (GlConst.isGlFormatInteger(externalFormat)) {
                        GlStateManager._vertexAttribIPointer(
                                attributeIndex, components, type, vertexSize, totalOffset);
                    } else {
                        GlStateManager._vertexAttribPointer(
                                attributeIndex,
                                components,
                                type,
                                GlConst.isFormatNormalized(element.format()),
                                vertexSize,
                                totalOffset);
                    }
                    GL33C.glVertexAttribDivisor(attributeIndex, binding.getStepRate());
                    attributeIndex++;
                }
            }
        }
    }

    private static final class BrowserVaoCache {
        private final LinkedHashMap<VertexArrayKey, VertexArray> entries =
                new LinkedHashMap<>(64, 0.75F, true);
        private final VertexArrayKey lookupKey = new VertexArrayKey();
        private final VertexArrayKey[] hotKeys = new VertexArrayKey[HOT_CACHE_SIZE];
        private final VertexArray[] hotVertexArrays = new VertexArray[HOT_CACHE_SIZE];
        private final byte[] hotAccessCounts = new byte[HOT_CACHE_SIZE];

        private VertexArray get(VertexFormat[] vertexBindings) {
            VertexArrayKey lookup = this.lookupKey.set(vertexBindings);
            int hotIndex = hotIndex(lookup.hashCode());
            VertexArrayKey hotKey = this.hotKeys[hotIndex];
            VertexArray vertexArray = this.hotVertexArrays[hotIndex];
            if (vertexArray != null && lookup.equals(hotKey)) {
                int accessCount = (this.hotAccessCounts[hotIndex] & 255) + 1;
                this.hotAccessCounts[hotIndex] = (byte) accessCount;
                if ((accessCount & 63) == 0) {
                    this.entries.get(hotKey);
                }
                return vertexArray;
            }

            vertexArray = this.entries.get(lookup);
            if (vertexArray != null) {
                cacheHot(vertexArray.cacheKey, vertexArray);
            }
            return vertexArray;
        }

        private void put(VertexFormat[] vertexBindings, VertexArray vertexArray) {
            VertexArrayKey key = new VertexArrayKey(vertexBindings);
            vertexArray.cacheKey = key;
            this.entries.put(key, vertexArray);
            cacheHot(key, vertexArray);
            if (this.entries.size() <= MAX_CACHED_WEBGL_VAOS) {
                return;
            }

            var iterator = this.entries.entrySet().iterator();
            if (iterator.hasNext()) {
                VertexArray evicted = iterator.next().getValue();
                iterator.remove();
                clearHot(evicted);
                GL30.glDeleteVertexArrays(evicted.id);
            }
        }

        private void cacheHot(VertexArrayKey key, VertexArray vertexArray) {
            int hotIndex = hotIndex(key.hashCode());
            this.hotKeys[hotIndex] = key;
            this.hotVertexArrays[hotIndex] = vertexArray;
            this.hotAccessCounts[hotIndex] = 0;
        }

        private void clearHot(VertexArray vertexArray) {
            VertexArrayKey key = vertexArray.cacheKey;
            if (key == null) {
                return;
            }
            int hotIndex = hotIndex(key.hashCode());
            if (this.hotVertexArrays[hotIndex] == vertexArray) {
                this.hotKeys[hotIndex] = null;
                this.hotVertexArrays[hotIndex] = null;
                this.hotAccessCounts[hotIndex] = 0;
            }
        }

        private static int hotIndex(int hashCode) {
            return (hashCode ^ hashCode >>> 16) & HOT_CACHE_MASK;
        }
    }

    private static final class VertexArrayKey {
        private VertexFormat[] vertexBindings;
        private int hashCode;

        private VertexArrayKey() {
        }

        private VertexArrayKey(VertexFormat[] vertexBindings) {
            set(vertexBindings.clone());
        }

        private VertexArrayKey set(VertexFormat[] vertexBindings) {
            this.vertexBindings = vertexBindings;
            int hash = 1;
            for (VertexFormat vertexBinding : vertexBindings) {
                hash = 31 * hash + System.identityHashCode(vertexBinding);
            }
            this.hashCode = hash;
            return this;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof VertexArrayKey other)
                    || this.vertexBindings.length != other.vertexBindings.length) {
                return false;
            }
            for (int index = 0; index < this.vertexBindings.length; index++) {
                if (this.vertexBindings[index] != other.vertexBindings[index]) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            return this.hashCode;
        }
    }

    public static class VertexArray {
        @VisibleForDebug
        final int id;
        @VisibleForDebug
        final String formatName;
        private VertexArrayKey cacheKey;

        private VertexArray(int id, @Nullable VertexFormat[] vertexBindings) {
            this.id = id;
            this.formatName = Arrays.stream(vertexBindings)
                    .filter(Objects::nonNull)
                    .map(VertexFormat::toString)
                    .collect(Collectors.joining(", "));
        }
    }
}
