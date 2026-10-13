package dev.gaius.mc.seams.renderpearl1;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import dev.gaius.gpu.webgpu.CoreSurface;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * The game canvas as a renderpearl surface. Both present modes are reported so vanilla's vsync
 * option keeps working; the canvas presents when the browser task ends.
 */
final class RpSurface implements GpuSurfaceBackend {
    private static final Set<GpuSurface.PresentMode> PRESENT_MODES = EnumSet.of(GpuSurface.PresentMode.FIFO,
            GpuSurface.PresentMode.IMMEDIATE);
    private final CoreSurface core;

    RpSurface(CoreSurface core) {
        this.core = core;
    }

    @Override
    public void configure(GpuSurface.Configuration config) throws SurfaceException {
        if (!this.core.configure(config.width(), config.height())) {
            throw new SurfaceException("The WebGPU canvas context is not available");
        }
    }

    @Override
    public boolean isSuboptimal() {
        return false;
    }

    @Override
    public void acquireNextTexture() throws SurfaceException {
        if (this.core.isIconified()) {
            throw new SurfaceException("Cannot acquire minimized window");
        }
        if (!this.core.acquire()) {
            throw new SurfaceException("The WebGPU canvas context is not available");
        }
    }

    @Override
    public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView textureView) {
        this.core.blit(((RpTextureView) textureView).core());
    }

    @Override
    public void present() {
        this.core.present();
    }

    @Override
    public Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return PRESENT_MODES;
    }

    @Override
    public void close() {
    }
}
