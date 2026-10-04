package dev.gaius.browser.webgpu;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;

/**
 * A WebGPU buffer. WebGPU has no synchronous mapping, so a write mapping hands out a CPU block
 * whose contents the command encoder uploads, in command order, when the view is closed.
 */
class WebGpuBuffer extends BaseGpuBuffer {
    private final WebGpuDevice device;
    final int handle;
    private boolean closed;

    WebGpuBuffer(WebGpuDevice device, @GpuBuffer.Usage int usage, long size, int handle) {
        super(usage, size);
        this.device = device;
        this.handle = handle;
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            this.device.encoder().destroyAfterSubmit(this.handle);
        }
    }

    @Override
    public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
        if (this.closed) {
            throw new IllegalStateException("Buffer already closed");
        }
        if (read) {
            // Read mappings follow copyTextureToBuffer(..., callback): the callback path copies the
            // result into a CPU block first. Not part of the S3 backend.
            throw new UnsupportedOperationException("WebGPU read mapping is not implemented yet");
        }
        WebGpuCommandEncoder encoder = this.device.encoder();
        GpuBufferSlice.MappedView staging = encoder.webTransientMemory().stagingView(length, 4L, length, 1L);
        GpuBufferSlice slice = new GpuBufferSlice(this, offset, length);
        return new GpuBufferSlice.MappedView(slice, staging.data(),
                () -> encoder.copyStaged(staging.slice(), slice, (int) length));
    }
}
