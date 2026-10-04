package dev.gaius.browser.webgpu;

import com.mojang.renderpearl.backend.common.BaseGpuTextureView;

/** A WebGPU texture view; cube-compatible textures get a cube view. */
class WebGpuTextureView extends BaseGpuTextureView {
    final int handle;
    private boolean closed;

    WebGpuTextureView(WebGpuTexture texture, int baseMipLevel, int mipLevels, int handle) {
        super(texture, baseMipLevel, mipLevels);
        this.handle = handle;
    }

    WebGpuTexture webTexture() {
        return (WebGpuTexture) this.texture();
    }

    @Override
    public boolean isClosed() {
        return this.closed || this.texture().isClosed();
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            WebGpuGlue.releaseHandle(this.handle);
        }
    }
}
