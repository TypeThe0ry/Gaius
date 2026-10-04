package dev.gaius.browser.webgpu;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;

/** A WebGPU texture plus lazily created single-mip views used for clears and attachments. */
class WebGpuTexture extends BaseGpuTexture {
    private final WebGpuDevice device;
    final int handle;
    final String webFormat;
    /** Single-mip views for attachments and clears, created on first use. */
    private final int[] mipViews;
    private boolean closed;

    WebGpuTexture(WebGpuDevice device, @GpuTexture.Usage int usage, String label, GpuFormat format, int width, int height,
            int depthOrLayers, int mipLevels, int handle, String webFormat) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.device = device;
        this.handle = handle;
        this.webFormat = webFormat;
        this.mipViews = new int[mipLevels];
    }

    /** View of mip level {@code mip} only, as render pass attachments require. */
    int attachmentView(int mip) {
        if (this.mipViews[mip] == 0) {
            this.mipViews[mip] = WebGpuGlue.createView(this.handle, mip, 1, false);
        }
        return this.mipViews[mip];
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            for (int i = 0; i < this.mipViews.length; i++) {
                if (this.mipViews[i] != 0) {
                    WebGpuGlue.releaseHandle(this.mipViews[i]);
                    this.mipViews[i] = 0;
                }
            }
            this.device.encoder().destroyTextureAfterSubmit(this.handle);
        }
    }
}
