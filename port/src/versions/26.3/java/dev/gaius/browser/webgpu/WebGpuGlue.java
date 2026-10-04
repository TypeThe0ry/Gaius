package dev.gaius.browser.webgpu;

import java.nio.ByteBuffer;
import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Imports of the WebGPU glue ({@code port/web/runtime/webgpu/gpu-glue.js}, global
 * {@code GaiusWebGpu}).
 *
 * <p>Every GPU object is an integer handle into the glue's table, sizes and offsets are ints
 * (no {@code long} crosses the boundary) and byte data crosses as a view of a direct buffer, so
 * the same calls compile for the TeaVM JS target and as plain imports for the WasmGC target.
 * None of the scripts captures Java objects.</p>
 */
final class WebGpuGlue {
    private WebGpuGlue() {
    }

    // ---- device ------------------------------------------------------------------------------

    @JSBody(script = """
            var g = globalThis.GaiusWebGpu;
            return g && typeof g.state === "function" ? g.state() : 0;
            """)
    static native int state();

    @JSBody(script = """
            var g = globalThis.GaiusWebGpu;
            return g && g.failure ? String(g.failure) : "WebGPU glue not loaded or not initialised";
            """)
    static native String failure();

    @JSBody(params = "name", script = "return globalThis.GaiusWebGpu.hasFeature(name);")
    static native boolean hasFeature(String name);

    @JSBody(params = "name", script = "return globalThis.GaiusWebGpu.limit(name);")
    static native double limit(String name);

    @JSBody(params = "key", script = "return globalThis.GaiusWebGpu.adapterString(key);")
    static native String adapterString(String key);

    @JSBody(script = "return globalThis.GaiusWebGpu.takeMessage();")
    static native String takeMessage();

    @JSBody(params = "handle", script = "globalThis.GaiusWebGpu.releaseHandle(handle);")
    static native void releaseHandle(int handle);

    // ---- buffers -----------------------------------------------------------------------------

    @JSBody(params = { "size", "usage", "label" },
            script = "return globalThis.GaiusWebGpu.createBuffer(size, usage, label);")
    static native int createBuffer(int size, int usage, String label);

    @JSBody(params = "handle", script = "globalThis.GaiusWebGpu.destroyBuffer(handle);")
    static native void destroyBuffer(int handle);

    @JSBody(params = { "handle", "offset", "data" },
            script = "globalThis.GaiusWebGpu.writeBuffer(handle, offset, data);")
    private static native void writeBuffer(int handle, int offset, Int8Array data);

    /**
     * Queues a write of {@code data} from its position to its limit. The length must be a
     * multiple of four.
     */
    static void writeBuffer(int handle, int offset, ByteBuffer data) {
        writeBuffer(handle, offset, view(data));
    }

    @JSBody(params = { "handle", "offset", "data" },
            script = "globalThis.GaiusWebGpu.upload(handle, offset, data);")
    private static native void upload(int handle, int offset, Int8Array data);

    /**
     * Uploads {@code data} (position to limit, a multiple of four bytes) ahead of the commands of
     * the next submit, through the glue's mapped staging ring.
     */
    static void upload(int handle, int offset, ByteBuffer data) {
        upload(handle, offset, view(data));
    }

    @JSBody(params = { "source", "sourceOffset", "target", "targetOffset", "size" },
            script = "globalThis.GaiusWebGpu.copyBufferToBuffer(source, sourceOffset, target, targetOffset, size);")
    static native void copyBufferToBuffer(int source, int sourceOffset, int target, int targetOffset, int size);

    // ---- textures and samplers ---------------------------------------------------------------

    @JSBody(params = { "width", "height", "layers", "mips", "format", "usage", "label" },
            script = "return globalThis.GaiusWebGpu.createTexture(width, height, layers, mips, format, usage, label);")
    static native int createTexture(int width, int height, int layers, int mips, String format, int usage, String label);

    @JSBody(params = "handle", script = "globalThis.GaiusWebGpu.destroyTexture(handle);")
    static native void destroyTexture(int handle);

    @JSBody(params = { "texture", "baseMip", "mipCount", "cube" },
            script = "return globalThis.GaiusWebGpu.createView(texture, baseMip, mipCount, cube);")
    static native int createView(int texture, int baseMip, int mipCount, boolean cube);

    @JSBody(params = { "texture", "mip", "layer", "x", "y", "width", "height", "bytesPerRow", "data" },
            script = "globalThis.GaiusWebGpu.writeTexture(texture, mip, layer, x, y, width, height, bytesPerRow, data);")
    private static native void writeTexture(int texture, int mip, int layer, int x, int y, int width, int height,
            int bytesPerRow, Int8Array data);

    static void writeTexture(int texture, int mip, int layer, int x, int y, int width, int height, int bytesPerRow,
            ByteBuffer data) {
        writeTexture(texture, mip, layer, x, y, width, height, bytesPerRow, view(data));
    }

    @JSBody(params = { "source", "target", "mip", "destX", "destY", "sourceX", "sourceY", "width", "height" },
            script = "globalThis.GaiusWebGpu.copyTextureToTexture(source, target, mip, destX, destY, sourceX, sourceY, width, height);")
    static native void copyTextureToTexture(int source, int target, int mip, int destX, int destY, int sourceX,
            int sourceY, int width, int height);

    @JSBody(params = { "addressU", "addressV", "minFilter", "magFilter", "mipmapFilter", "anisotropy", "maxLod" },
            script = "return globalThis.GaiusWebGpu.createSampler(addressU, addressV, minFilter, magFilter, mipmapFilter, anisotropy, maxLod);")
    static native int createSampler(String addressU, String addressV, String minFilter, String magFilter,
            String mipmapFilter, int anisotropy, double maxLod);

    // ---- shaders, pipelines, bind groups -----------------------------------------------------

    @JSBody(params = { "code", "label" },
            script = "return globalThis.GaiusWebGpu.createShaderModule(code, label);")
    static native int createShaderModule(String code, String label);

    @JSBody(params = { "json", "depthFormat" },
            script = "return globalThis.GaiusWebGpu.createPipeline(json, depthFormat);")
    static native int createPipeline(String json, String depthFormat);

    @JSBody(params = { "binding", "buffer", "offset", "size" },
            script = "globalThis.GaiusWebGpu.bindBuffer(binding, buffer, offset, size);")
    static native void bindBuffer(int binding, int buffer, int offset, int size);

    @JSBody(params = { "binding", "view", "sampler" },
            script = "globalThis.GaiusWebGpu.bindTexture(binding, view, sampler);")
    static native void bindTexture(int binding, int view, int sampler);

    @JSBody(params = { "pipeline", "group" },
            script = "return globalThis.GaiusWebGpu.createBindGroup(pipeline, group);")
    static native int createBindGroup(int pipeline, int group);

    // ---- render passes (hot path) ------------------------------------------------------------

    @JSBody(params = { "index", "view", "clear", "r", "g", "b", "a" },
            script = "globalThis.GaiusWebGpu.passColor(index, view, clear, r, g, b, a);")
    static native void passColor(int index, int view, boolean clear, float r, float g, float b, float a);

    @JSBody(params = { "view", "clear", "depth", "hasStencil" },
            script = "globalThis.GaiusWebGpu.passDepth(view, clear, depth, hasStencil);")
    static native void passDepth(int view, boolean clear, double depth, boolean hasStencil);

    @JSBody(params = "colorCount", script = "globalThis.GaiusWebGpu.passBegin(colorCount);")
    static native void passBegin(int colorCount);

    @JSBody(script = "globalThis.GaiusWebGpu.passEnd();")
    static native void passEnd();

    @JSBody(params = "pipeline", script = "globalThis.GaiusWebGpu.setPipeline(pipeline);")
    static native void setPipeline(int pipeline);

    @JSBody(params = { "slot", "buffer", "offset", "size" },
            script = "globalThis.GaiusWebGpu.setVertexBuffer(slot, buffer, offset, size);")
    static native void setVertexBuffer(int slot, int buffer, int offset, int size);

    @JSBody(params = { "buffer", "wide", "offset" },
            script = "globalThis.GaiusWebGpu.setIndexBuffer(buffer, wide, offset);")
    static native void setIndexBuffer(int buffer, boolean wide, int offset);

    @JSBody(params = { "group", "bindGroup", "count", "o0", "o1", "o2", "o3" },
            script = "globalThis.GaiusWebGpu.setBindGroup(group, bindGroup, count, o0, o1, o2, o3);")
    static native void setBindGroup(int group, int bindGroup, int count, int o0, int o1, int o2, int o3);

    @JSBody(params = { "index", "value" }, script = "globalThis.GaiusWebGpu.stageOffset(index, value);")
    static native void stageOffset(int index, int value);

    @JSBody(params = { "group", "bindGroup", "count" },
            script = "globalThis.GaiusWebGpu.setBindGroupStaged(group, bindGroup, count);")
    static native void setBindGroupStaged(int group, int bindGroup, int count);

    @JSBody(params = { "x", "y", "width", "height" },
            script = "globalThis.GaiusWebGpu.setScissor(x, y, width, height);")
    static native void setScissor(int x, int y, int width, int height);

    @JSBody(params = { "vertexCount", "instanceCount", "firstVertex", "firstInstance" },
            script = "globalThis.GaiusWebGpu.draw(vertexCount, instanceCount, firstVertex, firstInstance);")
    static native void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance);

    @JSBody(params = { "indexCount", "instanceCount", "firstIndex", "baseVertex", "firstInstance" },
            script = "globalThis.GaiusWebGpu.drawIndexed(indexCount, instanceCount, firstIndex, baseVertex, firstInstance);")
    static native void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance);

    @JSBody(params = { "bindGroup", "count", "o0", "o1", "o2", "o3", "indexCount", "instanceCount", "firstIndex",
            "baseVertex", "firstInstance" },
            script = "globalThis.GaiusWebGpu.drawIndexedWithOffsets(bindGroup, count, o0, o1, o2, o3, indexCount, "
                    + "instanceCount, firstIndex, baseVertex, firstInstance);")
    static native void drawIndexedWithOffsets(int bindGroup, int count, int o0, int o1, int o2, int o3, int indexCount,
            int instanceCount, int firstIndex, int baseVertex, int firstInstance);

    @JSBody(params = { "bindGroup", "count", "o0", "o1", "o2", "o3", "vertexCount", "instanceCount", "firstVertex",
            "firstInstance" },
            script = "globalThis.GaiusWebGpu.drawWithOffsets(bindGroup, count, o0, o1, o2, o3, vertexCount, "
                    + "instanceCount, firstVertex, firstInstance);")
    static native void drawWithOffsets(int bindGroup, int count, int o0, int o1, int o2, int o3, int vertexCount,
            int instanceCount, int firstVertex, int firstInstance);

    @JSBody(script = "globalThis.GaiusWebGpu.submit();")
    static native void submit();

    // ---- surface -----------------------------------------------------------------------------

    @JSBody(params = { "width", "height" },
            script = "return globalThis.GaiusWebGpu.configureSurface(width, height);")
    static native boolean configureSurface(int width, int height);

    @JSBody(script = "return globalThis.GaiusWebGpu.acquireSurface();")
    static native boolean acquireSurface();

    @JSBody(params = "view", script = "globalThis.GaiusWebGpu.blitToSurface(view);")
    static native void blitToSurface(int view);

    @JSBody(script = "globalThis.GaiusWebGpu.present();")
    static native void present();

    // ---- byte views --------------------------------------------------------------------------

    @JSBody(params = { "target", "offset", "source" }, script = "target.set(source, offset);")
    private static native void copyBytes(Int8Array target, int offset, Int8Array source);

    /**
     * Copies {@code source} (position to limit) into {@code target} at an absolute offset of its
     * backing view (position and limit of {@code target} are ignored).
     */
    static void copyBytes(ByteBuffer target, int offset, ByteBuffer source) {
        copyBytes(Int8Array.fromJavaBuffer(target), offset, view(source));
    }

    /**
     * A view of {@code data} from its position to its limit. TeaVM exports a buffer's whole
     * backing view and ignores position and limit, so a non-trivial window is sliced first.
     */
    static Int8Array view(ByteBuffer data) {
        if (data.position() == 0 && data.limit() == data.capacity()) {
            return Int8Array.fromJavaBuffer(data);
        }
        return Int8Array.fromJavaBuffer(data.slice());
    }
}
