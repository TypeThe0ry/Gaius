package dev.gaius.browser.webgpu;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.lwjgl.sdl.SDLVideo;

/**
 * The game canvas as a renderpearl surface. The canvas presents when the browser task ends, so
 * {@link #present()} only has to end the task: in the game it calls the browser SDL shim's
 * {@code SDL_GL_SwapWindow}, which on the WebGL 2 path is the per-frame cooperative yield and frame
 * telemetry (it issues no GL call), so frame pacing is shared by both paths. Both present modes
 * are reported so vanilla's vsync option keeps working.
 */
final class WebGpuSurface implements GpuSurfaceBackend {
    private static final Set<GpuSurface.PresentMode> PRESENT_MODES = EnumSet.of(GpuSurface.PresentMode.FIFO,
            GpuSurface.PresentMode.IMMEDIATE);
    private final long windowHandle;
    private final BooleanSupplier isIconified;
    private final boolean frameYield;

    WebGpuSurface(long windowHandle, BooleanSupplier isIconified, boolean frameYield) {
        this.windowHandle = windowHandle;
        this.isIconified = isIconified;
        this.frameYield = frameYield;
    }

    @Override
    public void configure(GpuSurface.Configuration config) throws SurfaceException {
        if (!WebGpuGlue.configureSurface(config.width(), config.height())) {
            throw new SurfaceException("The WebGPU canvas context is not available");
        }
    }

    @Override
    public boolean isSuboptimal() {
        return false;
    }

    @Override
    public void acquireNextTexture() throws SurfaceException {
        if (this.isIconified.getAsBoolean()) {
            throw new SurfaceException("Cannot acquire minimized window");
        }
        if (!WebGpuGlue.acquireSurface()) {
            throw new SurfaceException("The WebGPU canvas context is not available");
        }
    }

    @Override
    public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView textureView) {
        WebGpuGlue.blitToSurface(((WebGpuTextureView) textureView).handle);
    }

    @Override
    public void present() {
        WebGpuGlue.present();
        if (this.frameYield) {
            SDLVideo.SDL_GL_SwapWindow(this.windowHandle);
        }
    }

    @Override
    public Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return PRESENT_MODES;
    }

    @Override
    public void close() {
    }
}
