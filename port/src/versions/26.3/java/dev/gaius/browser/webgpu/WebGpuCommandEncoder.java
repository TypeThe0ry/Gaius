package dev.gaius.browser.webgpu;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.joml.Vector4fc;

/**
 * The command encoder. The glue keeps one GPUCommandEncoder open between submits.
 *
 * <p>Ordering: vanilla expects {@code writeToBuffer} to take effect between the commands
 * recorded before and after it, while {@code queue.writeBuffer} runs before the whole next
 * submission. Writes to persistent buffers are therefore staged in transient memory and copied
 * with an encoded {@code copyBufferToBuffer}; the staging data itself is uploaded once per block
 * at {@link #submit()}. Buffers and textures closed by vanilla are destroyed after the submit
 * that may still reference them.</p>
 */
final class WebGpuCommandEncoder implements CommandEncoderBackend {
    private final WebGpuDevice device;
    private final WebGpuTransientMemory transientMemory;
    private final WebGpuRenderPass renderPass;
    private int[] pendingBuffers = new int[64];
    private int pendingBufferCount;
    private int[] pendingTextures = new int[16];
    private int pendingTextureCount;
    private boolean inRenderPass;

    WebGpuCommandEncoder(WebGpuDevice device) {
        this.device = device;
        this.transientMemory = new WebGpuTransientMemory(device);
        this.renderPass = new WebGpuRenderPass(this);
    }

    WebGpuTransientMemory webTransientMemory() {
        return this.transientMemory;
    }

    @Override
    public void submit() {
        if (this.inRenderPass) {
            throw new IllegalStateException("Cannot submit with an open render pass");
        }
        this.transientMemory.flush();
        WebGpuGlue.submit();
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

    void destroyAfterSubmit(int bufferHandle) {
        if (this.pendingBufferCount == this.pendingBuffers.length) {
            this.pendingBuffers = java.util.Arrays.copyOf(this.pendingBuffers, this.pendingBufferCount * 2);
        }
        this.pendingBuffers[this.pendingBufferCount++] = bufferHandle;
    }

    void destroyTextureAfterSubmit(int textureHandle) {
        if (this.pendingTextureCount == this.pendingTextures.length) {
            this.pendingTextures = java.util.Arrays.copyOf(this.pendingTextures, this.pendingTextureCount * 2);
        }
        this.pendingTextures[this.pendingTextureCount++] = textureHandle;
    }

    @Override
    public TransientMemory transientMemory() {
        return this.transientMemory;
    }

    @Override
    public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
        List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
        int width = 0;
        int height = 0;
        for (int i = 0; i < colors.size(); i++) {
            RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colors.get(i);
            if (attachment == null) {
                WebGpuGlue.passColor(i, 0, false, 0.0F, 0.0F, 0.0F, 0.0F);
                continue;
            }
            GpuTextureView view = attachment.textureView();
            width = view.getWidth(0);
            height = view.getHeight(0);
            Optional<Vector4fc> clear = attachment.clearValue();
            Vector4fc color = clear.orElse(null);
            WebGpuGlue.passColor(i, attachmentView(view), color != null, color != null ? color.x() : 0.0F,
                    color != null ? color.y() : 0.0F, color != null ? color.z() : 0.0F, color != null ? color.w() : 0.0F);
        }
        String depthFormat = "";
        RenderPassDescriptor.Attachment<OptionalDouble> depth = descriptor.depthAttachment();
        if (depth != null) {
            GpuTextureView view = depth.textureView();
            WebGpuTexture texture = (WebGpuTexture) view.texture();
            depthFormat = texture.webFormat;
            width = view.getWidth(0);
            height = view.getHeight(0);
            OptionalDouble clear = depth.clearValue();
            WebGpuGlue.passDepth(attachmentView(view), clear.isPresent(), clear.orElse(0.0),
                    texture.getFormat().hasStencilAspect());
        }
        WebGpuGlue.passBegin(colors.size());
        this.inRenderPass = true;
        RenderPass.RenderArea area = descriptor.renderArea();
        boolean full = area.x() == 0 && area.y() == 0 && area.width() == width && area.height() == height;
        this.renderPass.begin(depthFormat, area.x(), area.y(), area.width(), area.height(), full);
        return this.renderPass;
    }

    /** Attachments must be single-mip views. */
    private static int attachmentView(GpuTextureView view) {
        WebGpuTextureView webView = (WebGpuTextureView) view;
        return view.mipLevels() == 1 ? webView.handle : webView.webTexture().attachmentView(view.baseMipLevel());
    }

    @Override
    public void submitRenderPass() {
        WebGpuGlue.passEnd();
        this.inRenderPass = false;
    }

    // ---- clears: a load-op clear pass per call ----------------------------------------------------

    @Override
    public void clearColorTexture(GpuTexture texture, Vector4fc color) {
        WebGpuGlue.passColor(0, ((WebGpuTexture) texture).attachmentView(0), true, color.x(), color.y(), color.z(), color.w());
        WebGpuGlue.passBegin(1);
        WebGpuGlue.passEnd();
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc color, GpuTexture depthTexture, double depth) {
        WebGpuTexture webDepth = (WebGpuTexture) depthTexture;
        WebGpuGlue.passColor(0, ((WebGpuTexture) colorTexture).attachmentView(0), true, color.x(), color.y(), color.z(),
                color.w());
        WebGpuGlue.passDepth(webDepth.attachmentView(0), true, depth, webDepth.getFormat().hasStencilAspect());
        WebGpuGlue.passBegin(1);
        WebGpuGlue.passEnd();
    }

    @Override
    public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc color, GpuTexture depthTexture, double depth,
            int regionX, int regionY, int regionWidth, int regionHeight, int mipLevel) {
        if (mipLevel == 0 && regionX == 0 && regionY == 0 && regionWidth == colorTexture.getWidth(0)
                && regionHeight == colorTexture.getHeight(0)) {
            this.clearColorAndDepthTextures(colorTexture, color, depthTexture, depth);
            return;
        }
        // A partial clear needs a scissored quad draw (M3).
        throw new UnsupportedOperationException("Partial clears are not implemented on the WebGPU backend yet");
    }

    @Override
    public void clearDepthTexture(GpuTexture depthTexture, double depth) {
        WebGpuTexture webDepth = (WebGpuTexture) depthTexture;
        WebGpuGlue.passDepth(webDepth.attachmentView(0), true, depth, webDepth.getFormat().hasStencilAspect());
        WebGpuGlue.passBegin(0);
        WebGpuGlue.passEnd();
    }

    // ---- buffers ---------------------------------------------------------------------------------

    @Override
    public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
        int length = data.remaining();
        if (length == 0) {
            return;
        }
        this.copyStaged(this.transientMemory.stage(data, 4L), destination, length);
    }

    /** Encodes the copy of {@code length} staged bytes into {@code destination}. */
    void copyStaged(GpuBufferSlice staged, GpuBufferSlice destination, int length) {
        int size = (length + 3) & ~3;
        long end = destination.offset() + size;
        if ((destination.offset() & 3L) != 0L || end > destination.buffer().size()) {
            // WebGPU copies whole 4-byte words; padding past the end of the buffer is impossible.
            throw new UnsupportedOperationException("Unaligned buffer write of " + length + " bytes at "
                    + destination.offset() + " is not supported on WebGPU");
        }
        WebGpuGlue.copyBufferToBuffer(((WebGpuBuffer) staged.buffer()).handle, (int) staged.offset(),
                ((WebGpuBuffer) destination.buffer()).handle, (int) destination.offset(), size);
    }

    @Override
    public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
        WebGpuGlue.copyBufferToBuffer(((WebGpuBuffer) source.buffer()).handle, (int) source.offset(),
                ((WebGpuBuffer) target.buffer()).handle, (int) target.offset(), (int) source.length());
    }

    // ---- textures --------------------------------------------------------------------------------

    @Override
    public void writeToTexture(GpuTexture destination, ByteBuffer source, int mipLevel, int depthOrLayer, int destX,
            int destY, int width, int height) {
        // queue.writeTexture runs before the next submission rather than in command order; vanilla
        // only adds texels to regions nothing earlier in the frame samples (atlases, glyph caches).
        WebGpuTexture texture = (WebGpuTexture) destination;
        WebGpuGlue.writeTexture(texture.handle, mipLevel, depthOrLayer, destX, destY, width, height,
                width * texture.getFormat().blockSize(), source);
    }

    @Override
    public void copyBufferToTexture(GpuBufferSlice source, int bytesPerRow, int rowsPerImage, int width, int height,
            GpuTexture destination, int mipLevel, int depthOrLayer, int destX, int destY, int copyWidth, int copyHeight) {
        throw new UnsupportedOperationException("copyBufferToTexture is not implemented on the WebGPU backend yet");
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel) {
        throw new UnsupportedOperationException("Texture readback is not implemented on the WebGPU backend yet");
    }

    @Override
    public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel,
            int x, int y, int width, int height) {
        throw new UnsupportedOperationException("Texture readback is not implemented on the WebGPU backend yet");
    }

    @Override
    public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY,
            int sourceX, int sourceY, int width, int height) {
        WebGpuGlue.copyTextureToTexture(((WebGpuTexture) source).handle, ((WebGpuTexture) destination).handle, mipLevel,
                destX, destY, sourceX, sourceY, width, height);
    }

    /**
     * CPU-visible data is captured when it is queued, never read back from the GPU, so nothing a
     * fence guards in the native backends can still be in flight: the fence is always signalled.
     */
    @Override
    public GpuFence createFence() {
        return new GpuFence() {
            @Override
            public void close() {
            }

            @Override
            public boolean awaitCompletion(long timeoutNS) {
                return true;
            }
        };
    }

    @Override
    public void writeTimestamp(GpuQueryPool pool, int index) {
    }
}
