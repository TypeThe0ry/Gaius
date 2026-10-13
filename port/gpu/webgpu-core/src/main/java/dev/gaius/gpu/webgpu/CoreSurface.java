package dev.gaius.gpu.webgpu;

import java.util.function.BooleanSupplier;

/**
 * The game canvas as a surface. The canvas presents when the browser task ends, so
 * {@link #present()} only has to end the task: the seam passes a frame yield (in the game the
 * browser SDL or GLFW shim's swap call, which on the WebGL 2 path is the per-frame cooperative
 * yield and frame telemetry and issues no GL call), so frame pacing is shared by both paths.
 * Harnesses pass none.
 */
public final class CoreSurface {
    private final BooleanSupplier iconified;
    private final Runnable frameYield;

    CoreSurface(BooleanSupplier iconified, Runnable frameYield) {
        this.iconified = iconified;
        this.frameYield = frameYield;
    }

    /** Sizes the canvas and configures its context; {@code false} when the context is unavailable. */
    public boolean configure(int width, int height) {
        return WebGpuGlue.configureSurface(width, height);
    }

    public boolean isIconified() {
        return this.iconified.getAsBoolean();
    }

    /** Takes the canvas texture of this frame; {@code false} when the context is unavailable. */
    public boolean acquire() {
        return WebGpuGlue.acquireSurface();
    }

    /** Encodes the blit of {@code view} to the acquired canvas texture, flipped once (GL row order). */
    public void blit(CoreTextureView view) {
        WebGpuGlue.blitToSurface(view.handle);
    }

    public void present() {
        WebGpuGlue.present();
        if (this.frameYield != null) {
            this.frameYield.run();
        }
    }
}
