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
      private static final int MAX_CACHED_WEBGL_VAOS = 512;
      private final LinkedHashMap<VertexArrayCache.VertexArrayKey, VertexArrayCache.VertexArray> cache =
         new LinkedHashMap<>(64, 0.75F, true);
      private final Map<VertexFormat, VertexArrayCache.VertexArray> overflowCache = new LinkedHashMap<>();
      private final GlDebugLabel debugLabels;

      public Emulated(final GlDebugLabel debugLabels) {
         this.debugLabels = debugLabels;
      }

      @Override
      public void bindVertexArray(final VertexFormat format, final @Nullable GlBuffer vertexBuffer) {
         VertexArrayCache.VertexArrayKey key = new VertexArrayCache.VertexArrayKey(format, vertexBuffer == null ? 0 : vertexBuffer.handle);
         VertexArrayCache.VertexArray vertexArray = this.cache.get(key);
         if (vertexArray != null) {
            GlStateManager._glBindVertexArray(vertexArray.id);
            vertexArray.lastVertexBuffer = vertexBuffer;
            return;
         }

         if (this.cache.size() >= MAX_CACHED_WEBGL_VAOS) {
            this.bindOverflowVertexArray(format, vertexBuffer);
            return;
         }

         int id = GlStateManager._glGenVertexArrays();
         vertexArray = new VertexArrayCache.VertexArray(id, format, vertexBuffer);
         this.debugLabels.applyLabel(vertexArray);
         GlStateManager._glBindVertexArray(vertexArray.id);
         if (vertexBuffer != null) {
            GlStateManager._glBindBuffer(34962, vertexBuffer.handle);
            setupCombinedAttributes(format, true);
         }

         this.cache.put(key, vertexArray);
      }

      private void bindOverflowVertexArray(final VertexFormat format, final @Nullable GlBuffer vertexBuffer) {
         VertexArrayCache.VertexArray vertexArray = this.overflowCache.get(format);
         if (vertexArray == null) {
            int id = GlStateManager._glGenVertexArrays();
            GlStateManager._glBindVertexArray(id);
            if (vertexBuffer != null) {
               GlStateManager._glBindBuffer(34962, vertexBuffer.handle);
               setupCombinedAttributes(format, true);
            }

            vertexArray = new VertexArrayCache.VertexArray(id, format, vertexBuffer);
            this.debugLabels.applyLabel(vertexArray);
            this.overflowCache.put(format, vertexArray);
         } else {
            GlStateManager._glBindVertexArray(vertexArray.id);
            if (vertexBuffer != null && vertexArray.lastVertexBuffer != vertexBuffer) {
               GlStateManager._glBindBuffer(34962, vertexBuffer.handle);
               vertexArray.lastVertexBuffer = vertexBuffer;
               setupCombinedAttributes(format, false);
            }
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
            GlStateManager._glBindVertexArray(vertexArray.id);
            if (vertexBuffer != null && vertexArray.lastVertexBuffer != vertexBuffer) {
               if (this.needsMesaWorkaround && vertexArray.lastVertexBuffer != null && vertexArray.lastVertexBuffer.handle == vertexBuffer.handle) {
                  ARBVertexAttribBinding.glBindVertexBuffer(0, 0, 0L, 0);
               }

               ARBVertexAttribBinding.glBindVertexBuffer(0, vertexBuffer.handle, 0L, format.getVertexSize());
               vertexArray.lastVertexBuffer = vertexBuffer;
            }
         } else {
            int id = GlStateManager._glGenVertexArrays();
            GlStateManager._glBindVertexArray(id);
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
      return element.usage() == VertexFormatElement.Usage.COLOR
         || element.usage() == VertexFormatElement.Usage.UV
         || element.usage() == VertexFormatElement.Usage.GENERIC;
   }

   private record VertexArrayKey(VertexFormat format, int bufferHandle) {
   }

   public static class VertexArray {
      final int id;
      final VertexFormat format;
      @Nullable GlBuffer lastVertexBuffer;

      private VertexArray(final int id, final VertexFormat format, final @Nullable GlBuffer lastVertexBuffer) {
         this.id = id;
         this.format = format;
         this.lastVertexBuffer = lastVertexBuffer;
      }
   }
}
