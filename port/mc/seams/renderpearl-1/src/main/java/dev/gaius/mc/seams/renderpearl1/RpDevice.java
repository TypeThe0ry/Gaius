package dev.gaius.mc.seams.renderpearl1;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import dev.gaius.gpu.webgpu.CoreCaps;
import dev.gaius.gpu.webgpu.CoreDevice;
import dev.gaius.gpu.webgpu.CorePipeline;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;

/**
 * The renderpearl device backend: a delegating adapter from vanilla's {@link GpuDeviceBackend}
 * to the WebGPU core's {@link CoreDevice}.
 */
public final class RpDevice implements GpuDeviceBackend {
    private final CoreDevice core;
    private final boolean frameYield;
    private final DeviceInfo deviceInfo;
    private final RpEncoder encoder;

    /** frameYield: present() ends the browser task through the SDL shim (the game, not harnesses). */
    RpDevice(CoreDevice core, boolean frameYield) {
        this.core = core;
        this.frameYield = frameYield;
        this.deviceInfo = deviceInfo(core.caps());
        this.encoder = new RpEncoder(core.encoder());
    }

    public CoreDevice core() {
        return this.core;
    }

    private static DeviceInfo deviceInfo(CoreCaps caps) {
        DeviceLimits limits = new DeviceLimits(
                16,
                caps.minUniformOffsetAlignment(),
                caps.maxTextureDimension2D(),
                caps.maxBufferSize(),
                0,
                caps.maxColorAttachments(),
                // DrawSeparate first: indirect terrain draws come with the render-bundle path.
                0);
        DeviceFeatures features = new DeviceFeatures(
                false, // wireframeFillMode: WebGPU has no line polygon mode
                true, // shaderDrawParameters: WGSL instance_index includes firstInstance
                false,
                false,
                false,
                false,
                true, // nonZeroFirstInstance: always allowed for direct draws
                false); // persistentMapping: vanilla then stages vertex data in CPU memory (S8)
        HintsAndWorkarounds hints = new HintsAndWorkarounds(false, false, false, false);
        return new DeviceInfo(caps.name(), caps.vendor(), caps.architecture(), true, "WebGPU", 1.0F, limits, features,
                caps.features(), hints, caps.fallbackAdapter() ? DeviceType.CPU : DeviceType.OTHER);
    }

    @Override
    public GpuSurfaceBackend createSurface(long windowHandle, BooleanSupplier isIconified) {
        return new RpSurface(this.core.createSurface(isIconified,
                this.frameYield ? () -> SDLVideo.SDL_GL_SwapWindow(windowHandle) : null));
    }

    @Override
    public CommandEncoderBackend createCommandEncoder() {
        return this.encoder;
    }

    @Override
    public GpuSampler createSampler(AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter,
            FilterMode magFilter, int maxAnisotropy, OptionalDouble maxLod) {
        return new RpSampler(addressModeU, addressModeV, minFilter, magFilter, maxLod,
                this.core.createSampler(RpEnums.ADDRESS_MODE.toCore(addressModeU), RpEnums.ADDRESS_MODE.toCore(addressModeV),
                        RpEnums.FILTER_MODE.toCore(minFilter), RpEnums.FILTER_MODE.toCore(magFilter), maxAnisotropy,
                        maxLod.isPresent() ? maxLod.getAsDouble() : -1.0));
    }

    @Override
    public GpuTexture createTexture(@Nullable String label, @GpuTexture.Usage int usage, GpuFormat format, int width,
            int height, int depthOrLayers, int mipLevels) {
        return new RpTexture(usage, format, this.core.createTexture(label, RpEnums.textureUsage(usage),
                RpEnums.FORMAT.toCore(format), width, height, depthOrLayers, mipLevels));
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        RpTexture rpTexture = (RpTexture) texture;
        return new RpTextureView(rpTexture, this.core.createView(rpTexture.core(), baseMipLevel, mipLevels));
    }

    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, long size) {
        return new RpBuffer(usage, size, this.core.createBuffer(this.label(label), RpEnums.bufferUsage(usage), size));
    }

    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, ByteBuffer data) {
        int length = data.remaining();
        return new RpBuffer(usage, length, this.core.createBuffer(this.label(label), RpEnums.bufferUsage(usage), data));
    }

    private @Nullable String label(@Nullable Supplier<String> label) {
        return this.core.debug() && label != null ? label.get() : null;
    }

    @Override
    public List<String> getLastDebugMessages() {
        return this.core.takeDebugMessages();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return this.core.debug();
    }

    /**
     * compilePipeline captures the description and lets the core prefetch the shader
     * translation; finishCompile builds the pipeline synchronously (D11: WebGPU compiles in the
     * GPU process and a pipeline is usable immediately). {@code null} marks it unavailable.
     */
    @Override
    public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo createInfo) {
        CorePipeline.Pending pending = this.core.compile(RpPipelines.describe(createInfo));
        return () -> {
            CorePipeline pipeline = pending.finish();
            return pipeline != null ? new RpPipeline(pipeline) : null;
        };
    }

    @Override
    public void close() {
    }

    /**
     * Timestamp pools report no values: WebGPU only writes timestamps at pass boundaries
     * ({@code timestampWrites}), not where vanilla asks for them; vanilla then shows no GPU time.
     */
    @Override
    public GpuQueryPool createTimestampQueryPool(int size) {
        return new GpuQueryPool() {
            @Override
            public int size() {
                return size;
            }

            @Override
            public OptionalLong getValue(int index) {
                return OptionalLong.empty();
            }

            @Override
            public OptionalLong[] getValues(int index, int count) {
                OptionalLong[] values = new OptionalLong[count];
                Arrays.fill(values, OptionalLong.empty());
                return values;
            }

            @Override
            public void close() {
            }
        };
    }

    @Override
    public long getTimestampCalibrationOffset() {
        return 0L;
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return this.deviceInfo;
    }
}
