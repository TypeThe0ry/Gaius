package dev.gaius.browser.render;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import dev.gaius.browser.BrowserChunkDrawTelemetry;
import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.client.renderer.DynamicUniforms;

/**
 * Minecraft 26.2 (blaze3d) side of the batched terrain draw path; see
 * {@link BrowserTerrainBatch}. Called from code patched by TerrainBatchPatches:
 * <ul>
 *   <li>LevelRenderer.prepareChunkRenders: {@link #chunkUniform} replaces the per-draw
 *       ChunkSection uniform lambda, {@link #captureSections} and {@link #bindSections}
 *       record the section table around DynamicUniforms.writeChunkSections;</li>
 *   <li>ChunkSectionsToRender.renderGroup: {@link #drawMultipleIndexed} replaces
 *       RenderPass.drawMultipleIndexed (26.2 groups every translucent section, of every
 *       heap, into one list, so runs split at heap changes).</li>
 * </ul>
 */
public final class BrowserTerrainBatchGlue {
    private static ChunkUniform[] uniforms = new ChunkUniform[1024];

    private BrowserTerrainBatchGlue() {
    }

    /** The ChunkSection uniform callback of one draw, shared per section index. */
    public static BiConsumer<GpuBufferSlice[], RenderPass.UniformUploader> chunkUniform(int index) {
        if (index < 0) {
            return new ChunkUniform(index);
        }
        if (index >= uniforms.length) {
            ChunkUniform[] grown = new ChunkUniform[Math.max(index + 1, uniforms.length * 2)];
            System.arraycopy(uniforms, 0, grown, 0, uniforms.length);
            uniforms = grown;
        }
        ChunkUniform uniform = uniforms[index];
        if (uniform == null) {
            uniform = new ChunkUniform(index);
            uniforms[index] = uniform;
        }
        return uniform;
    }

    /** Copies the frame's section records before vanilla writes them into uniform slices. */
    public static void captureSections(List<DynamicUniforms.ChunkSectionInfo> sections) {
        if (sections == null || !BrowserTerrainBatch.enabled()) {
            BrowserTerrainBatch.beginSections(0);
            return;
        }
        int count = sections.size();
        BrowserTerrainBatch.beginSections(count);
        for (int index = 0; index < count; index++) {
            DynamicUniforms.ChunkSectionInfo section = sections.get(index);
            BrowserTerrainBatch.addSection(
                    section.x(), section.y(), section.z(), section.visibility());
        }
    }

    /** Ties the captured records to the slice array; returns it unchanged. */
    public static GpuBufferSlice[] bindSections(GpuBufferSlice[] slices) {
        BrowserTerrainBatch.bindSections(slices);
        return slices;
    }

    /**
     * Replacement for {@code pass.drawMultipleIndexed(draws, indexBuffer, indexType,
     * uniformNames, sectionSlices)} in ChunkSectionsToRender.renderGroup.
     */
    public static void drawMultipleIndexed(
            RenderPass pass,
            Collection<RenderPass.Draw<GpuBufferSlice[]>> draws,
            GpuBuffer indexBuffer,
            IndexType indexType,
            Collection<String> uniformNames,
            GpuBufferSlice[] sectionSlices) {
        int size = draws.size();
        if (size < 2 || !(draws instanceof List) || !BrowserTerrainBatch.enabled()
                || !BrowserTerrainBatch.hasSections(sectionSlices)) {
            pass.drawMultipleIndexed(draws, indexBuffer, indexType, uniformNames, sectionSlices);
            return;
        }
        @SuppressWarnings("unchecked")
        List<RenderPass.Draw<GpuBufferSlice[]>> list =
                (List<RenderPass.Draw<GpuBufferSlice[]>>) draws;
        int start = 0;
        while (start < size) {
            RenderPass.Draw<GpuBufferSlice[]> first = list.get(start);
            boolean custom = first.indexBuffer() != null;
            GpuBuffer runIndexBuffer = custom ? first.indexBuffer() : indexBuffer;
            IndexType runIndexType = first.indexType() != null ? first.indexType() : indexType;
            int end = start + 1;
            while (end < size) {
                RenderPass.Draw<GpuBufferSlice[]> next = list.get(end);
                if (next.vertexBuffer() != first.vertexBuffer()
                        || next.slot() != first.slot()
                        || (next.indexBuffer() != null) != custom
                        || (custom ? next.indexBuffer() : indexBuffer) != runIndexBuffer
                        || (next.indexType() != null ? next.indexType() : indexType)
                                != runIndexType) {
                    break;
                }
                end++;
            }
            // The run's first draw stays vanilla: it binds pipeline, vertex array, index
            // buffer and every uniform the batch then reuses.
            pass.drawMultipleIndexed(
                    list.subList(start, start + 1), indexBuffer, indexType, uniformNames,
                    sectionSlices);
            if (end - start > 1
                    && !submitRun(list, start + 1, end, runIndexType, custom)) {
                pass.drawMultipleIndexed(
                        list.subList(start + 1, end), indexBuffer, indexType, uniformNames,
                        sectionSlices);
            }
            start = end;
        }
    }

    private static boolean submitRun(
            List<RenderPass.Draw<GpuBufferSlice[]>> list, int from, int to,
            IndexType indexType, boolean custom) {
        if (indexType == null) {
            BrowserTerrainBatch.noteFallback();
            return false;
        }
        int count = to - from;
        int[] records = BrowserTerrainBatch.records(count);
        for (int slot = 0; slot < count; slot++) {
            RenderPass.Draw<GpuBufferSlice[]> draw = list.get(from + slot);
            if (!(draw.uniformUploaderConsumer() instanceof ChunkUniform uniform)
                    || !BrowserTerrainBatch.putRecord(records, slot, draw.indexCount(),
                            draw.firstIndex(), draw.baseVertex(), indexType.bytes,
                            uniform.index)) {
                BrowserTerrainBatch.noteFallback();
                return false;
            }
        }
        return BrowserTerrainBatch.submit(
                custom ? BrowserTerrainBatch.KIND_CUSTOM_INDEX : BrowserTerrainBatch.KIND_SEQUENTIAL,
                records, count);
    }

    /** Uploads one section's ChunkSection slice, as the vanilla lambda did. */
    static final class ChunkUniform
            implements BiConsumer<GpuBufferSlice[], RenderPass.UniformUploader> {
        final int index;

        ChunkUniform(int index) {
            this.index = index;
        }

        @Override
        public void accept(GpuBufferSlice[] slices, RenderPass.UniformUploader uploader) {
            BrowserChunkDrawTelemetry.armUniformIndex(index);
            uploader.upload("ChunkSection", slices[index]);
        }
    }
}
