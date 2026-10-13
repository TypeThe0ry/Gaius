package dev.gaius.gpu.webgpu;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * The command encoder. The glue keeps one GPUCommandEncoder open between submits.
 *
 * <p>Ordering (DESIGN D10): vanilla expects {@code writeToBuffer} and write mappings to take effect
 * between the commands recorded before and after them, while {@code queue.writeBuffer} runs
 * before the whole next submission. {@link #writeBuffer} therefore copies the bytes into the
 * staging ring and encodes a {@code copyBufferToBuffer} in command order; when the ring is full it
 * stages them in transient memory instead (uploaded ahead of the submit) and encodes the copy
 * from there. A write while a render pass is open ends the pass, encodes the copy and continues
 * the pass with {@code loadOp: load}. Buffers and textures closed by their users are destroyed
 * after the submit that may still reference them.</p>
 */
public final class CoreEncoder {
    private final CoreDevice device;
    private final TransientArena transientMemory;
    private final StagingRing ring;
    private final CorePass renderPass;
    private int[] pendingBuffers = new int[64];
    private int pendingBufferCount;
    private int[] pendingTextures = new int[16];
    private int pendingTextureCount;
    private boolean inRenderPass;
    private int attachmentWidth;
    private int attachmentHeight;
    private String pendingDepthFormat = "";
    private CoreBuffer fanIndices;
    private int fanVertices;
    private final ArrayList<Readback> readbacks = new ArrayList<>();
    private final ArrayList<Runnable> runnable = new ArrayList<>();
    long submits;
    long passSplits;

    /** A texture readback on its way: its glue id, where its bytes go and what runs then. */
    private record Readback(int id, CoreBuffer destination, int offset, int length, Runnable callback) {
    }

    CoreEncoder(CoreDevice device, StagingRing ring) {
        this.device = device;
        this.ring = ring;
        this.transientMemory = new TransientArena(device);
        this.renderPass = new CorePass(this);
    }

    public CoreDevice device() {
        return this.device;
    }

    public TransientArena transientMemory() {
        return this.transientMemory;
    }

    public boolean inRenderPass() {
        return this.inRenderPass;
    }

    /** The pass object this encoder reuses for every render pass (diagnostics: skipped draws). */
    public CorePass pass() {
        return this.renderPass;
    }

    public void submit() {
        if (this.inRenderPass) {
            throw new IllegalStateException("Cannot submit with an open render pass");
        }
        this.runCompletedReadbacks();
        this.transientMemory.flush();
        WebGpuGlue.submit();
        this.submits++;
        this.transientMemory.rotate();
        for (int i = 0; i < this.pendingBufferCount; i++) {
            WebGpuGlue.destroyBuffer(this.pendingBuffers[i]);
        }
        this.pendingBufferCount = 0;
        for (int i = 0; i < this.pendingTextureCount; i++) {
            WebGpuGlue.destroyTexture(this.pendingTextures[i]);
        }
        this.pendingTextureCount = 0;
    }

    /**
     * Copies the readbacks the glue finished since the last call into their destinations' read
     * data, then runs their callbacks in the order the readbacks were recorded, as vanilla's
     * Vulkan backend runs them when the frame that copied retires. Called at the start of each
     * submit, before anything of that submit is sent.
     */
    public void runCompletedReadbacks() {
        if (this.readbacks.isEmpty() && this.runnable.isEmpty()) {
            return;
        }
        for (int id = WebGpuGlue.readbackPoll(); id != 0; id = WebGpuGlue.readbackPoll()) {
            for (int i = 0; i < this.readbacks.size(); i++) {
                Readback readback = this.readbacks.get(i);
                if (readback.id() == id) {
                    ByteBuffer target = readback.destination().readData().duplicate();
                    target.limit(readback.offset() + readback.length()).position(readback.offset());
                    if (WebGpuGlue.readbackFetch(id, target) != readback.length()) {
                        this.readbacks.remove(i);
                        throw new IllegalStateException("WebGPU texture readback " + id + " failed");
                    }
                    this.readbacks.set(i, new Readback(id, readback.destination(), readback.offset(), -1,
                            readback.callback()));
                    break;
                }
            }
        }
        // Callbacks in recording order: stop at the first readback still on its way.
        while (!this.readbacks.isEmpty()) {
            Readback readback = this.readbacks.get(0);
            if (readback.id() < 0) {
                int status = WebGpuGlue.fenceStatus(-readback.id());
                if (status == 0) {
                    break;
                }
                WebGpuGlue.closeFence(-readback.id());
                if (status < 0) {
                    this.readbacks.remove(0);
                    throw new IllegalStateException("WebGPU texture copy completion failed");
                }
            } else if (readback.length() >= 0) {
                break;
            }
            this.runnable.add(this.readbacks.remove(0).callback());
        }
        while (!this.runnable.isEmpty()) {
            this.runnable.remove(0).run();
        }
    }

    /** Readbacks recorded whose callbacks have not run yet (diagnostics). */
    public int pendingReadbacks() {
        return this.readbacks.size() + this.runnable.size();
    }

    public CoreFence createFence() {
        return new CoreFence(WebGpuGlue.createFence());
    }

    void destroyBufferAfterSubmit(int bufferHandle) {
        if (this.pendingBufferCount == this.pendingBuffers.length) {
            this.pendingBuffers = Arrays.copyOf(this.pendingBuffers, this.pendingBufferCount * 2);
        }
        this.pendingBuffers[this.pendingBufferCount++] = bufferHandle;
    }

    void destroyTextureAfterSubmit(int textureHandle) {
        if (this.pendingTextureCount == this.pendingTextures.length) {
            this.pendingTextures = Arrays.copyOf(this.pendingTextures, this.pendingTextureCount * 2);
        }
        this.pendingTextures[this.pendingTextureCount++] = textureHandle;
    }

    /**
     * A 32-bit index buffer that draws a triangle fan of up to {@code vertices} vertices as a
     * triangle list: (0, k, k + 1) for k = 1 .. n - 2, the triangles and winding of OpenGL and
     * Vulkan fans. It grows to the next power of two; a replaced buffer is destroyed after the
     * submit, since commands recorded earlier may still use it.
     */
    CoreBuffer fanIndices(int vertices) {
        if (this.fanIndices == null || vertices > this.fanVertices) {
            int capacity = 64;
            while (capacity < vertices) {
                capacity <<= 1;
            }
            int triangles = capacity - 2;
            ByteBuffer data = ByteBuffer.allocateDirect(triangles * 12).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int k = 1; k <= triangles; k++) {
                data.putInt(0).putInt(k).putInt(k + 1);
            }
            data.flip();
            if (this.fanIndices != null) {
                this.fanIndices.close();
            }
            this.fanIndices = this.device.createBuffer("gaius fan indices", CoreUsage.BUFFER_INDEX, data);
            this.fanVertices = capacity;
        }
        return this.fanIndices;
    }

    // ---- render passes: stage the attachments, then begin ----------------------------------------

    /** Colour attachment {@code index}; a {@code null} view leaves the slot empty. */
    public void passColor(int index, CoreTextureView view, boolean clear, float r, float g, float b, float a) {
        if (view == null) {
            WebGpuGlue.passColor(index, 0, false, 0.0F, 0.0F, 0.0F, 0.0F);
            return;
        }
        this.attachmentWidth = view.width(0);
        this.attachmentHeight = view.height(0);
        WebGpuGlue.passColor(index, view.attachmentHandle(), clear, r, g, b, a);
    }

    /** The depth attachment; {@code clear} clears it to {@code depth}. */
    public void passDepth(CoreTextureView view, boolean clear, double depth) {
        CoreFormat format = view.texture().format();
        this.attachmentWidth = view.width(0);
        this.attachmentHeight = view.height(0);
        this.pendingDepthFormat = format.webTextureFormat();
        WebGpuGlue.passDepth(view.attachmentHandle(), clear, depth, format.hasStencilAspect());
    }

    /**
     * Begins the pass with the staged attachments; draws are confined to the render area. The
     * returned pass object is reused for every pass of this encoder.
     */
    public CorePass beginPass(int colorCount, int x, int y, int width, int height) {
        if (this.inRenderPass) {
            throw new IllegalStateException("A render pass is already open");
        }
        String depthFormat = this.pendingDepthFormat;
        this.pendingDepthFormat = "";
        WebGpuGlue.passBegin(colorCount);
        this.inRenderPass = true;
        boolean full = x == 0 && y == 0 && width == this.attachmentWidth && height == this.attachmentHeight;
        this.renderPass.begin(depthFormat, x, y, width, height, full);
        return this.renderPass;
    }

    public void endPass() {
        WebGpuGlue.passEnd();
        this.inRenderPass = false;
    }

    /** Ends the open pass (if any) so that a copy can be encoded; returns whether it was open. */
    private boolean suspendPass() {
        if (!this.inRenderPass) {
            return false;
        }
        this.renderPass.suspend();
        this.passSplits++;
        return true;
    }

    private void resumePass(boolean suspended) {
        if (suspended) {
            this.renderPass.resume();
        }
    }

    // ---- clears: a load-op clear pass per call --------------------------------------------------

    public void clearColor(CoreTexture texture, float r, float g, float b, float a) {
        this.requireNoPass();
        WebGpuGlue.passColor(0, texture.attachmentView(0), true, r, g, b, a);
        WebGpuGlue.passBegin(1);
        WebGpuGlue.passEnd();
    }

    public void clearColorAndDepth(CoreTexture color, float r, float g, float b, float a, CoreTexture depth,
            double depthValue) {
        this.requireNoPass();
        WebGpuGlue.passColor(0, color.attachmentView(0), true, r, g, b, a);
        WebGpuGlue.passDepth(depth.attachmentView(0), true, depthValue, depth.format().hasStencilAspect());
        WebGpuGlue.passBegin(1);
        WebGpuGlue.passEnd();
    }

    /**
     * Clears a region of one mip level of a colour texture and a depth texture. A region that is
     * the whole level is a clear-load-op pass on that level; a smaller one is a scissored draw that
     * writes the colour and the depth (frag_depth, compare "always"), so texels outside it keep
     * their values; stencil is kept. The region is clipped to the level.
     */
    public void clearColorAndDepthRegion(CoreTexture color, float r, float g, float b, float a, CoreTexture depth,
            double depthValue, int regionX, int regionY, int regionWidth, int regionHeight, int mipLevel) {
        this.requireNoPass();
        int levelWidth = color.width(mipLevel);
        int levelHeight = color.height(mipLevel);
        int x0 = Math.max(0, regionX);
        int y0 = Math.max(0, regionY);
        int x1 = Math.min(levelWidth, regionX + regionWidth);
        int y1 = Math.min(levelHeight, regionY + regionHeight);
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        if (x0 == 0 && y0 == 0 && x1 == levelWidth && y1 == levelHeight) {
            WebGpuGlue.passColor(0, color.attachmentView(mipLevel), true, r, g, b, a);
            if (depth != null) {
                WebGpuGlue.passDepth(depth.attachmentView(mipLevel), true, depthValue, depth.format().hasStencilAspect());
            }
            WebGpuGlue.passBegin(1);
            WebGpuGlue.passEnd();
            return;
        }
        WebGpuGlue.clearRegion(color.attachmentView(mipLevel), color.format().webTextureFormat(),
                depth != null ? depth.attachmentView(mipLevel) : 0,
                depth != null ? depth.format().webTextureFormat() : "",
                depth != null && depth.format().hasStencilAspect(), r, g, b, a, depthValue, x0, y0, x1 - x0, y1 - y0);
        WebGpuGlue.count("partialClears", 1);
    }

    public void clearDepth(CoreTexture depth, double depthValue) {
        this.requireNoPass();
        WebGpuGlue.passDepth(depth.attachmentView(0), true, depthValue, depth.format().hasStencilAspect());
        WebGpuGlue.passBegin(0);
        WebGpuGlue.passEnd();
    }

    private void requireNoPass() {
        if (this.inRenderPass) {
            throw new IllegalStateException("Close the open render pass before clearing");
        }
    }

    // ---- buffers --------------------------------------------------------------------------------

    /**
     * Writes {@code data} (position to limit) into {@code destination} at {@code offset}, in
     * command order. WebGPU copies whole 4-byte words: the offset must be a multiple of four and a
     * length that is not is padded with zeros only at the logical buffer's end, within its
     * rounded allocation. Interior writes must be aligned so padding cannot overwrite live bytes.
     */
    public void writeBuffer(CoreBuffer destination, long offset, ByteBuffer data) {
        int length = data.remaining();
        if (length == 0) {
            return;
        }
        int size = (length + 3) & ~3;
        checkCopyRange(destination, offset, size, length);
        destination.contentsChanged();
        boolean suspended = this.suspendPass();
        if (!this.ring.writeOrdered(destination, (int) offset, data, size)) {
            CoreSlice staged = this.transientMemory.stage(data, 4L);
            WebGpuGlue.copyBufferToBuffer(staged.buffer().handle, (int) staged.offset(), destination.handle,
                    (int) offset, size);
        }
        this.resumePass(suspended);
    }

    private static void checkCopyRange(CoreBuffer destination, long offset, int size, int length) {
        if ((offset & 3L) != 0L || offset < 0L || length > destination.size() - offset
                || offset > destination.size() || size < length
                || (size != length && offset + length != destination.size())) {
            throw new UnsupportedOperationException("Unaligned buffer write of " + length + " bytes at " + offset
                    + " into " + destination.size() + " bytes is not supported on WebGPU");
        }
    }

    /** Copies {@code length} bytes between buffers in command order. */
    public void copyBuffer(CoreBuffer source, long sourceOffset, CoreBuffer target, long targetOffset, long length) {
        target.contentsChanged();
        boolean suspended = this.suspendPass();
        WebGpuGlue.copyBufferToBuffer(source.handle, (int) sourceOffset, target.handle, (int) targetOffset, (int) length);
        this.resumePass(suspended);
    }

    // ---- textures -------------------------------------------------------------------------------

    /**
     * Writes texels with {@code queue.writeTexture}, which runs before the next submission rather
     * than in command order; vanilla only adds texels to regions nothing earlier in the frame
     * samples (atlases, glyph caches). Command-ordered texture writes through the ring and
     * {@code copyBufferToTexture} come with M3.
     */
    public void writeTexture(CoreTexture destination, ByteBuffer source, int mipLevel, int depthOrLayer, int destX,
            int destY, int width, int height) {
        WebGpuGlue.writeTexture(destination.handle, mipLevel, depthOrLayer, destX, destY, width, height,
                width * destination.format().blockSize(), source);
    }

    /**
     * Copies {@code width} x {@code height} texels of a buffer into layer {@code layer} (array layer
     * or cube face) of a texture mip level at ({@code destX}, {@code destY}), in command order. The
     * texels start {@code sourceX + sourceY * rowLength} texels into {@code source}; rows are
     * {@code rowLength} texels apart and images {@code imageHeight} rows (vanilla's GL backend:
     * UNPACK_ROW_LENGTH and UNPACK_IMAGE_HEIGHT).
     */
    public void copyBufferToTexture(CoreSlice source, int sourceX, int sourceY, int rowLength, int imageHeight,
            CoreTexture destination, int destX, int destY, int width, int height, int mipLevel, int layer) {
        int texel = destination.format().blockSize();
        long offset = source.offset() + ((long) sourceX + (long) sourceY * rowLength) * texel;
        boolean suspended = this.suspendPass();
        WebGpuGlue.copyBufferToTexture(source.buffer().handle, (int) offset, rowLength * texel, imageHeight,
                destination.handle, mipLevel, layer, destX, destY, width, height);
        this.resumePass(suspended);
    }

    /**
     * Copies {@code width} x {@code height} texels of a mip level into {@code destination} at
     * {@code offset}, rows packed tightly (vanilla's layout), and runs {@code callback} once the
     * bytes are there. A destination that is mapped for reading (screenshots, texture dumps, Tracy
     * frames) gets the bytes as its read data ({@link CoreBuffer#readData()}): the glue copies the
     * texels into a staging buffer in command order, maps it after the submit and the callback runs
     * at the first submit after the mapping resolved ({@link #runCompletedReadbacks()}). Any other
     * destination gets a GPU copy and its callback runs only after the queue completion promise
     * for the submit that carries it resolves. Both paths preserve callback recording order.
     */
    public void copyTextureToBuffer(CoreTexture source, CoreBuffer destination, long offset, Runnable callback,
            int mipLevel, int x, int y, int width, int height) {
        this.requireNoPass();
        int texel = source.format().blockSize();
        long length = (long) width * height * texel;
        if (offset < 0L || offset + length > destination.size()) {
            throw new IllegalArgumentException("Readback of " + length + " bytes at " + offset + " does not fit into "
                    + destination.size() + " bytes");
        }
        if ((destination.usage() & CoreUsage.BUFFER_MAP_READ) != 0) {
            int id = WebGpuGlue.readbackTexture(source.handle, mipLevel, 0, x, y, width, height, texel);
            this.readbacks.add(new Readback(id, destination, (int) offset, (int) length, callback));
            return;
        }
        destination.contentsChanged();
        WebGpuGlue.copyTextureToBufferRows(source.handle, mipLevel, x, y, width, height, texel, destination.handle,
                (int) offset);
        this.readbacks.add(new Readback(-WebGpuGlue.createFence(), destination, 0, 0, callback));
    }

    /** {@code timestamp-query} writes are not recorded on WebGPU; counted for telemetry. */
    public void timestampWritten() {
        WebGpuGlue.count("timestampWrites", 1);
    }

    public void copyTextureToTexture(CoreTexture source, CoreTexture destination, int mipLevel, int destX, int destY,
            int sourceX, int sourceY, int width, int height) {
        boolean suspended = this.suspendPass();
        WebGpuGlue.copyTextureToTexture(source.handle, destination.handle, mipLevel, destX, destY, sourceX, sourceY,
                width, height);
        this.resumePass(suspended);
    }
}
