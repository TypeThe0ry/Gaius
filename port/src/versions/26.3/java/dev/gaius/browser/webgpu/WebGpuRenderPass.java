package dev.gaius.browser.webgpu;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;

/**
 * The open render pass. One instance per command encoder is reused for every pass.
 *
 * <p>Uniform buffers live in bind group 0 with dynamic offsets: a draw that only moves a
 * uniform slice inside the same buffer (vanilla's per-section {@code ChunkSection} slices) costs a
 * {@code setBindGroup} with new offsets and no new object. Textures, samplers and texel buffers
 * live in group 1. Bind groups are cached by their resources.</p>
 */
final class WebGpuRenderPass implements RenderPassBackend {
    private static final int MAX_INLINE_OFFSETS = 4;
    private static final int MAX_CACHED_BIND_GROUPS = 4096;

    private final WebGpuCommandEncoder encoder;
    private final HashMap<String, Integer> bindGroupCache = new HashMap<>();
    private final StringBuilder keyBuilder = new StringBuilder(64);

    private String depthFormat = "";
    private int areaX;
    private int areaY;
    private int areaWidth;
    private int areaHeight;

    private @Nullable WebGpuRenderPipeline pipeline;
    private int pipelineHandle;
    private String layoutKey = "";
    private Object[] uniforms = new Object[32];
    private boolean group0Dirty;
    private boolean group1Dirty;
    private int boundGroup0;
    private int boundGroup1;
    private final int[] offsets = new int[16];
    private final int[] boundOffsets = new int[16];
    private int boundOffsetCount = -1;
    private IndexType indexType = IndexType.INT;
    private boolean skipDraws;

    int drawsSkipped;

    WebGpuRenderPass(WebGpuCommandEncoder encoder) {
        this.encoder = encoder;
    }

    void begin(String depthFormat, int x, int y, int width, int height, boolean full) {
        this.depthFormat = depthFormat;
        this.areaX = x;
        this.areaY = y;
        this.areaWidth = width;
        this.areaHeight = height;
        this.pipeline = null;
        this.pipelineHandle = 0;
        this.layoutKey = "";
        java.util.Arrays.fill(this.uniforms, null);
        this.group0Dirty = true;
        this.group1Dirty = true;
        this.boundGroup0 = 0;
        this.boundGroup1 = 0;
        this.boundOffsetCount = -1;
        this.skipDraws = true;
        if (!full) {
            WebGpuGlue.setScissor(x, y, width, height);
        }
    }

    @Override
    public void pushDebugGroup(Supplier<String> label) {
    }

    @Override
    public void popDebugGroup() {
    }

    @Override
    public void setPipeline(BackendRenderPipeline pipeline) {
        if (!(pipeline instanceof WebGpuRenderPipeline webPipeline)) {
            throw new IllegalArgumentException("Pipeline must be a WebGpuRenderPipeline");
        }
        if (this.pipeline != webPipeline) {
            if (this.uniforms.length < webPipeline.uniformKinds.length) {
                this.uniforms = new Object[webPipeline.uniformKinds.length];
            } else {
                java.util.Arrays.fill(this.uniforms, null);
            }
            String key = webPipeline.layoutKey();
            if (!key.equals(this.layoutKey)) {
                this.layoutKey = key;
                this.boundGroup0 = 0;
                this.boundGroup1 = 0;
                this.boundOffsetCount = -1;
            }
            this.group0Dirty = true;
            this.group1Dirty = true;
        }
        this.pipeline = webPipeline;
        int handle = webPipeline.variant(this.depthFormat);
        this.skipDraws = handle == 0;
        if (handle != 0 && handle != this.pipelineHandle) {
            WebGpuGlue.setPipeline(handle);
        }
        this.pipelineHandle = handle;
    }

    @Override
    public void setUniform(int index, @Nullable Object value) {
        WebGpuRenderPipeline current = this.pipeline;
        if (current == null || index >= current.uniformKinds.length) {
            return;
        }
        Object previous = this.uniforms[index];
        this.uniforms[index] = value;
        if (current.uniformKinds[index] == WebGpuRenderPipeline.UNIFORM_BUFFER) {
            if (previous instanceof GpuBufferSlice before && value instanceof GpuBufferSlice after
                    && before.buffer() == after.buffer() && before.length() == after.length()) {
                // Same binding, only the dynamic offset moves.
                return;
            }
            this.group0Dirty = true;
        } else if (!java.util.Objects.equals(previous, value)) {
            // The frontend wraps texture and sampler in a fresh record per call; equal records
            // keep the bound group.
            this.group1Dirty = true;
        }
    }

    @Override
    public void pushConstants(ByteBuffer value) {
        throw new UnsupportedOperationException("Push constants are not used by vanilla and not implemented for WebGPU");
    }

    @Override
    public void enableScissor(int x, int y, int width, int height) {
        // Clip to the render area: WebGPU rejects scissor rectangles outside the attachment.
        int x0 = Math.max(x, this.areaX);
        int y0 = Math.max(y, this.areaY);
        int x1 = Math.min(x + width, this.areaX + this.areaWidth);
        int y1 = Math.min(y + height, this.areaY + this.areaHeight);
        WebGpuGlue.setScissor(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
    }

    @Override
    public void disableScissor() {
        WebGpuGlue.setScissor(this.areaX, this.areaY, this.areaWidth, this.areaHeight);
    }

    @Override
    public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {
        if (vertexBuffer != null) {
            WebGpuGlue.setVertexBuffer(slot, ((WebGpuBuffer) vertexBuffer.buffer()).handle, (int) vertexBuffer.offset(),
                    (int) vertexBuffer.length());
        }
    }

    @Override
    public void setIndexBuffer(@Nullable GpuBuffer indexBuffer, IndexType indexType) {
        this.indexType = indexType;
        if (indexBuffer != null) {
            WebGpuGlue.setIndexBuffer(((WebGpuBuffer) indexBuffer).handle, indexType == IndexType.INT, 0);
        }
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
        int pending = this.prepareDraw();
        if (pending == 0) {
            WebGpuGlue.drawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
        } else if (pending > 0) {
            int[] o = this.offsets;
            WebGpuGlue.drawIndexedWithOffsets(this.boundGroup0, pending, o[0], o[1], o[2], o[3], indexCount, instanceCount,
                    firstIndex, vertexOffset, firstInstance);
        }
    }

    @Override
    public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        throw new UnsupportedOperationException("WebGPU does not support the multiDrawDirectInterleaved device feature");
    }

    @Override
    public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
        throw new UnsupportedOperationException("WebGPU does not support the multiDrawDirectSeparate device feature");
    }

    @Override
    public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
        throw new UnsupportedOperationException("Indirect draws are not enabled on the WebGPU backend yet");
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        int pending = this.prepareDraw();
        if (pending == 0) {
            WebGpuGlue.draw(vertexCount, instanceCount, firstVertex, firstInstance);
        } else if (pending > 0) {
            int[] o = this.offsets;
            WebGpuGlue.drawWithOffsets(this.boundGroup0, pending, o[0], o[1], o[2], o[3], vertexCount, instanceCount,
                    firstVertex, firstInstance);
        }
    }

    @Override
    public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
        throw new UnsupportedOperationException("WebGPU does not support the multiDrawDirectInterleaved device feature");
    }

    @Override
    public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
        throw new UnsupportedOperationException("WebGPU does not support the multiDrawDirectSeparate device feature");
    }

    @Override
    public void drawIndirect(GpuBufferSlice commands, int drawCount) {
        throw new UnsupportedOperationException("Indirect draws are not enabled on the WebGPU backend yet");
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
    }

    IndexType indexType() {
        return this.indexType;
    }

    /**
     * Binds what changed since the last draw. Returns -1 when the draw must be skipped, 0 when
     * everything is bound, or the number of group 0 dynamic offsets (at most four, in
     * {@link #offsets}) that the draw call itself applies: the common terrain case, a draw that
     * only moves uniform slices, then costs a single glue call.
     */
    private int prepareDraw() {
        WebGpuRenderPipeline current = this.pipeline;
        if (current == null || this.skipDraws) {
            this.drawsSkipped++;
            return -1;
        }
        int pending = 0;
        int[] bufferUniforms = current.uniformBufferIndices;
        int count = bufferUniforms.length;
        if (count > 0) {
            if (this.group0Dirty) {
                int group = this.group0(current);
                if (group == 0) {
                    this.drawsSkipped++;
                    return -1;
                }
                if (group != this.boundGroup0) {
                    this.boundGroup0 = group;
                    this.boundOffsetCount = -1;
                }
                this.group0Dirty = false;
            }
            boolean changed = this.boundOffsetCount != count;
            for (int i = 0; i < count; i++) {
                int offset = (int) ((GpuBufferSlice) this.uniforms[bufferUniforms[i]]).offset();
                this.offsets[i] = offset;
                if (offset != this.boundOffsets[i]) {
                    changed = true;
                }
            }
            if (changed) {
                if (count <= MAX_INLINE_OFFSETS) {
                    pending = count;
                } else {
                    for (int i = 0; i < count; i++) {
                        WebGpuGlue.stageOffset(i, this.offsets[i]);
                    }
                    WebGpuGlue.setBindGroupStaged(0, this.boundGroup0, count);
                }
                System.arraycopy(this.offsets, 0, this.boundOffsets, 0, count);
                this.boundOffsetCount = count;
            }
        } else if (this.group0Dirty) {
            // Pipelines without uniform buffers still have an (empty) group 0 in their layout.
            int group = this.cachedBindGroup(current, 0, this.layoutKey + "|0");
            WebGpuGlue.setBindGroup(0, group, 0, 0, 0, 0, 0);
            this.boundGroup0 = group;
            this.group0Dirty = false;
        }
        if (this.group1Dirty) {
            int group = this.group1(current);
            if (group == 0) {
                this.drawsSkipped++;
                // Offsets recorded above were not applied: the next draw must apply them.
                this.boundOffsetCount = -1;
                return -1;
            }
            if (group != this.boundGroup1) {
                WebGpuGlue.setBindGroup(1, group, 0, 0, 0, 0, 0);
                this.boundGroup1 = group;
            }
            this.group1Dirty = false;
        }
        return pending;
    }

    private int group0(WebGpuRenderPipeline current) {
        StringBuilder key = this.keyBuilder;
        key.setLength(0);
        key.append(this.layoutKey).append("|0");
        for (int index : current.uniformBufferIndices) {
            if (!(this.uniforms[index] instanceof GpuBufferSlice slice)) {
                return 0;
            }
            key.append(',').append(((WebGpuBuffer) slice.buffer()).handle).append(':').append(bindingSize(slice));
        }
        String cacheKey = key.toString();
        Integer cached = this.bindGroupCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        for (int index : current.uniformBufferIndices) {
            GpuBufferSlice slice = (GpuBufferSlice) this.uniforms[index];
            WebGpuGlue.bindBuffer(index, ((WebGpuBuffer) slice.buffer()).handle, 0, bindingSize(slice));
        }
        return this.remember(cacheKey, WebGpuGlue.createBindGroup(this.pipelineHandle, 0));
    }

    private int group1(WebGpuRenderPipeline current) {
        if (!current.hasGroup1) {
            return this.cachedBindGroup(current, 1, this.layoutKey + "|1");
        }
        StringBuilder key = this.keyBuilder;
        key.setLength(0);
        key.append(this.layoutKey).append("|1");
        byte[] kinds = current.uniformKinds;
        for (int i = 0; i < kinds.length; i++) {
            if (kinds[i] == WebGpuRenderPipeline.TEXTURE) {
                if (!(this.uniforms[i] instanceof TextureViewAndSampler pair)) {
                    return 0;
                }
                key.append(',').append(((WebGpuTextureView) pair.view()).handle).append(':')
                        .append(((WebGpuSampler) pair.sampler()).handle);
            } else if (kinds[i] == WebGpuRenderPipeline.TEXEL_BUFFER) {
                if (!(this.uniforms[i] instanceof GpuBufferSlice slice)) {
                    return 0;
                }
                key.append(',').append(((WebGpuBuffer) slice.buffer()).handle).append('@').append(slice.offset())
                        .append(':').append(slice.length());
            }
        }
        String cacheKey = key.toString();
        Integer cached = this.bindGroupCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        for (int i = 0; i < kinds.length; i++) {
            if (kinds[i] == WebGpuRenderPipeline.TEXTURE) {
                TextureViewAndSampler pair = (TextureViewAndSampler) this.uniforms[i];
                WebGpuGlue.bindTexture(2 * i, ((WebGpuTextureView) pair.view()).handle, ((WebGpuSampler) pair.sampler()).handle);
            } else if (kinds[i] == WebGpuRenderPipeline.TEXEL_BUFFER) {
                GpuBufferSlice slice = (GpuBufferSlice) this.uniforms[i];
                WebGpuGlue.bindBuffer(2 * i, ((WebGpuBuffer) slice.buffer()).handle, (int) slice.offset(), (int) slice.length());
            }
        }
        return this.remember(cacheKey, WebGpuGlue.createBindGroup(this.pipelineHandle, 1));
    }

    private int cachedBindGroup(WebGpuRenderPipeline current, int group, String cacheKey) {
        Integer cached = this.bindGroupCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        return this.remember(cacheKey, WebGpuGlue.createBindGroup(this.pipelineHandle, group));
    }

    private int remember(String key, int handle) {
        if (this.bindGroupCache.size() >= MAX_CACHED_BIND_GROUPS) {
            // Resources churn (resized targets, re-uploaded atlases): start over rather than track
            // liveness per entry. Released handles drop the glue's last reference.
            for (Integer stale : this.bindGroupCache.values()) {
                WebGpuGlue.releaseHandle(stale);
            }
            this.bindGroupCache.clear();
            this.boundGroup0 = 0;
            this.boundGroup1 = 0;
            this.boundOffsetCount = -1;
            this.group0Dirty = true;
            this.group1Dirty = true;
        }
        this.bindGroupCache.put(key, handle);
        return handle;
    }

    /** Uniform bindings are capped at the 64 KiB WebGPU guarantees. */
    private static int bindingSize(GpuBufferSlice slice) {
        return (int) Math.min(slice.length(), 65536L);
    }
}
