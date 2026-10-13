package com.mojang.blaze3d.opengl;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.ARBVertexAttribBinding;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GL30;

public abstract class VertexArrayCache {
   public static VertexArrayCache create(final GLCapabilities capabilities, final GlDebugLabel debugLabels, final Set<String> enabledExtensions) {
      if (capabilities.GL_ARB_vertex_attrib_binding && GlDevice.USE_GL_ARB_vertex_attrib_binding) {
         enabledExtensions.add("GL_ARB_vertex_attrib_binding");
         return new VertexArrayCache.Separate(debugLabels);
      } else {
         return new VertexArrayCache.Emulated(debugLabels);
      }
   }

   public abstract void bindVertexArray(final VertexFormat format, final @Nullable GlBuffer vertexBuffer);

   private static class Emulated extends VertexArrayCache {
      private static final int MAX_CACHED_WEBGL_VAOS = 2048;
      private static final int HOT_CACHE_SIZE = 8192;
      private static final int HOT_CACHE_MASK = HOT_CACHE_SIZE - 1;
      private final LinkedHashMap<VertexArrayCache.VertexArrayKey, VertexArrayCache.VertexArray> cache =
         new LinkedHashMap<>(64, 0.75F, true);
      private final VertexArrayCache.VertexArrayKey lookupKey = new VertexArrayCache.VertexArrayKey();
      private final VertexFormat[] hotFormats = new VertexFormat[HOT_CACHE_SIZE];
      private final VertexArrayCache.VertexArray[] hotVertexArrays = new VertexArrayCache.VertexArray[HOT_CACHE_SIZE];
      private final byte[] hotAccessCounts = new byte[HOT_CACHE_SIZE];
      private final GlDebugLabel debugLabels;

      public Emulated(final GlDebugLabel debugLabels) {
         this.debugLabels = debugLabels;
      }

      @Override
      public void bindVertexArray(final VertexFormat format, final @Nullable GlBuffer vertexBuffer) {
         int bufferHandle = vertexBuffer == null ? 0 : vertexBuffer.handle;
         int hotIndex = hotIndex(bufferHandle);
         VertexArrayCache.VertexArray vertexArray = this.hotVertexArrays[hotIndex];
         if (vertexArray != null
            && this.hotFormats[hotIndex] == format
            && vertexArray.cacheKey != null
            && vertexArray.cacheKey.bufferHandle == bufferHandle) {
            int accessCount = (this.hotAccessCounts[hotIndex] & 255) + 1;
            this.hotAccessCounts[hotIndex] = (byte)accessCount;
            if ((accessCount & 63) == 0) {
               this.cache.get(vertexArray.cacheKey);
            }

            GL30.glBindVertexArray(vertexArray.id);
            vertexArray.lastVertexBuffer = vertexBuffer;
            return;
         }

         vertexArray = this.cache.get(this.lookupKey.set(format, bufferHandle));
         if (vertexArray != null) {
            this.cacheHot(hotIndex, format, vertexArray);
            GL30.glBindVertexArray(vertexArray.id);
            vertexArray.lastVertexBuffer = vertexBuffer;
            return;
         }

         VertexArrayCache.VertexArray evicted = null;
         if (this.cache.size() >= MAX_CACHED_WEBGL_VAOS) {
            var iterator = this.cache.entrySet().iterator();
            if (iterator.hasNext()) {
               evicted = iterator.next().getValue();
               iterator.remove();
            }
         }

         int id = GlStateManager._glGenVertexArrays();
         vertexArray = new VertexArrayCache.VertexArray(id, format, vertexBuffer);
         this.debugLabels.applyLabel(vertexArray);
         GL30.glBindVertexArray(vertexArray.id);
         if (vertexBuffer != null) {
            GlStateManager._glBindBuffer(34962, vertexBuffer.handle);
            setupCombinedAttributes(format, true);
         }

         VertexArrayCache.VertexArrayKey cacheKey = new VertexArrayCache.VertexArrayKey(format, bufferHandle);
         vertexArray.cacheKey = cacheKey;
         this.cache.put(cacheKey, vertexArray);
         this.cacheHot(hotIndex, format, vertexArray);
         if (evicted != null) {
            this.clearHot(evicted);
            GL30.glDeleteVertexArrays(evicted.id);
         }
      }

      private static int hotIndex(final int bufferHandle) {
         int hash = bufferHandle * -1640531527;
         return (hash ^ hash >>> 16) & HOT_CACHE_MASK;
      }

      private void cacheHot(final int hotIndex, final VertexFormat format, final VertexArrayCache.VertexArray vertexArray) {
         this.hotFormats[hotIndex] = format;
         this.hotVertexArrays[hotIndex] = vertexArray;
         this.hotAccessCounts[hotIndex] = 0;
      }

      private void clearHot(final VertexArrayCache.VertexArray vertexArray) {
         if (vertexArray.cacheKey == null) {
            return;
         }

         int hotIndex = hotIndex(vertexArray.cacheKey.bufferHandle);
         if (this.hotVertexArrays[hotIndex] == vertexArray) {
            this.hotFormats[hotIndex] = null;
            this.hotVertexArrays[hotIndex] = null;
            this.hotAccessCounts[hotIndex] = 0;
         }
      }

      private static void setupCombinedAttributes(final VertexFormat format, final boolean enable) {
         int vertexSize = format.getVertexSize();
         List<VertexFormatElement> elements = format.getElements();

         for (int i = 0; i < elements.size(); i++) {
            VertexFormatElement element = elements.get(i);
            if (enable) {
               GlStateManager._enableVertexAttribArray(i);
            }

            boolean normalized = shouldNormalize(element);
            if (!normalized && element.type() != VertexFormatElement.Type.FLOAT) {
               GlStateManager._vertexAttribIPointer(i, element.count(), GlConst.toGl(element.type()), vertexSize, format.getOffset(element));
            } else {
               GlStateManager._vertexAttribPointer(
                  i, element.count(), GlConst.toGl(element.type()), normalized, vertexSize, format.getOffset(element)
               );
            }
         }
      }
   }

   private static class Separate extends VertexArrayCache {
      private final Map<VertexFormat, VertexArrayCache.VertexArray> cache = new LinkedHashMap<>();
      private final GlDebugLabel debugLabels;
      private final boolean needsMesaWorkaround;

      public Separate(final GlDebugLabel debugLabels) {
         this.debugLabels = debugLabels;
         if ("Mesa".equals(GlStateManager._getString(7936))) {
            String version = GlStateManager._getString(7938);
            this.needsMesaWorkaround = version.contains("25.0.0") || version.contains("25.0.1") || version.contains("25.0.2");
         } else {
            this.needsMesaWorkaround = false;
         }
      }

      @Override
      public void bindVertexArray(final VertexFormat format, final @Nullable GlBuffer vertexBuffer) {
         VertexArrayCache.VertexArray vertexArray = this.cache.get(format);
         if (vertexArray != null) {
            GL30.glBindVertexArray(vertexArray.id);
            if (vertexBuffer != null && vertexArray.lastVertexBuffer != vertexBuffer) {
               if (this.needsMesaWorkaround && vertexArray.lastVertexBuffer != null && vertexArray.lastVertexBuffer.handle == vertexBuffer.handle) {
                  ARBVertexAttribBinding.glBindVertexBuffer(0, 0, 0L, 0);
               }

               ARBVertexAttribBinding.glBindVertexBuffer(0, vertexBuffer.handle, 0L, format.getVertexSize());
               vertexArray.lastVertexBuffer = vertexBuffer;
            }
         } else {
            int id = GlStateManager._glGenVertexArrays();
            GL30.glBindVertexArray(id);
            if (vertexBuffer != null) {
               List<VertexFormatElement> elements = format.getElements();

               for (int i = 0; i < elements.size(); i++) {
                  VertexFormatElement element = elements.get(i);
                  GlStateManager._enableVertexAttribArray(i);
                  boolean normalized = shouldNormalize(element);
                  if (!normalized && element.type() != VertexFormatElement.Type.FLOAT) {
                     ARBVertexAttribBinding.glVertexAttribIFormat(i, element.count(), GlConst.toGl(element.type()), format.getOffset(element));
                  } else {
                     ARBVertexAttribBinding.glVertexAttribFormat(
                        i, element.count(), GlConst.toGl(element.type()), normalized, format.getOffset(element)
                     );
                  }

                  ARBVertexAttribBinding.glVertexAttribBinding(i, 0);
               }
            }

            if (vertexBuffer != null) {
               ARBVertexAttribBinding.glBindVertexBuffer(0, vertexBuffer.handle, 0L, format.getVertexSize());
            }

            VertexArrayCache.VertexArray vao = new VertexArrayCache.VertexArray(id, format, vertexBuffer);
            this.debugLabels.applyLabel(vao);
            this.cache.put(format, vao);
         }
      }
   }

   private static boolean shouldNormalize(final VertexFormatElement element) {
      return element.usage() == VertexFormatElement.Usage.NORMAL
         || element.usage() == VertexFormatElement.Usage.COLOR;
   }

   private static final class VertexArrayKey {
      private VertexFormat format;
      private int bufferHandle;
      private int hashCode;

      private VertexArrayKey() {
      }

      private VertexArrayKey(final VertexFormat format, final int bufferHandle) {
         this.set(format, bufferHandle);
      }

      private VertexArrayKey set(final VertexFormat format, final int bufferHandle) {
         this.format = format;
         this.bufferHandle = bufferHandle;
         this.hashCode = 31 * format.hashCode() + Integer.hashCode(bufferHandle);
         return this;
      }

      @Override
      public boolean equals(final Object object) {
         if (this == object) {
            return true;
         }

         if (!(object instanceof VertexArrayCache.VertexArrayKey other) || this.bufferHandle != other.bufferHandle) {
            return false;
         }

         return this.format == other.format || this.format != null && this.format.equals(other.format);
      }

      @Override
      public int hashCode() {
         return this.hashCode;
      }
   }

   public static class VertexArray {
      final int id;
      final VertexFormat format;
      @Nullable GlBuffer lastVertexBuffer;
      VertexArrayCache.VertexArrayKey cacheKey;

      private VertexArray(final int id, final VertexFormat format, final @Nullable GlBuffer lastVertexBuffer) {
         this.id = id;
         this.format = format;
         this.lastVertexBuffer = lastVertexBuffer;
      }
   }
}
