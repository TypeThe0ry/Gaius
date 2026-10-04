package dev.gaius.browser.webgpu;

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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The renderpearl device backend on WebGPU. The device itself is created by the glue before the
 * game starts; this class reads its capabilities and creates resources through handles.
 */
public final class WebGpuDevice implements GpuDeviceBackend {
    private static final String[] REPORTED_FEATURES = {
        "float32-blendable", "float32-filterable", "indirect-first-instance", "timestamp-query",
        "depth32float-stencil8", "rg11b10ufloat-renderable", "texture-compression-bc"
    };

    private final boolean debug;
    private final boolean frameYield;
    private final DeviceInfo deviceInfo;
    private final WebGpuCommandEncoder encoder;
    private final List<String> debugMessages = new ArrayList<>();

    /** frameYield: present() ends the browser task through the SDL shim (the game, not harnesses). */
    WebGpuDevice(boolean debug, boolean frameYield) {
        this.debug = debug;
        this.frameYield = frameYield;
        this.deviceInfo = readDeviceInfo();
        this.encoder = new WebGpuCommandEncoder(this);
    }

    private static DeviceInfo readDeviceInfo() {
        String vendor = WebGpuGlue.adapterString("vendor");
        String architecture = WebGpuGlue.adapterString("architecture");
        String description = WebGpuGlue.adapterString("description");
        boolean fallback = "true".equals(WebGpuGlue.adapterString("isFallbackAdapter"));
        Set<String> features = new HashSet<>();
        for (String feature : REPORTED_FEATURES) {
            if (WebGpuGlue.hasFeature(feature)) {
                features.add(feature);
            }
        }
        DeviceLimits limits = new DeviceLimits(
                16,
                (int) WebGpuGlue.limit("minUniformBufferOffsetAlignment"),
                (int) WebGpuGlue.limit("maxTextureDimension2D"),
                (long) WebGpuGlue.limit("maxBufferSize"),
                0,
                (int) WebGpuGlue.limit("maxColorAttachments"),
                // DrawSeparate first: indirect terrain draws come with the render-bundle path.
                0);
        DeviceFeatures deviceFeatures = new DeviceFeatures(
                false, // wireframeFillMode: WebGPU has no line polygon mode
                true, // shaderDrawParameters: WGSL instance_index includes firstInstance
                false,
                false,
                false,
                false,
                true, // nonZeroFirstInstance: always allowed for direct draws
                false);
        HintsAndWorkarounds hints = new HintsAndWorkarounds(false, false, false, false);
        String name = description.isEmpty() ? ("WebGPU " + vendor + " " + architecture).trim() : description;
        return new DeviceInfo(name, vendor, architecture, true, "WebGPU", 1.0F, limits, deviceFeatures, features, hints,
                fallback ? DeviceType.CPU : DeviceType.OTHER);
    }

    WebGpuCommandEncoder encoder() {
        return this.encoder;
    }

    void debugMessage(String message) {
        if (this.debugMessages.size() < 256) {
            this.debugMessages.add(message);
        }
    }

    @Override
    public GpuSurfaceBackend createSurface(long windowHandle, BooleanSupplier isIconified) {
        return new WebGpuSurface(windowHandle, isIconified, this.frameYield);
    }

    @Override
    public CommandEncoderBackend createCommandEncoder() {
        return this.encoder;
    }

    @Override
    public GpuSampler createSampler(AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter,
            FilterMode magFilter, int maxAnisotropy, OptionalDouble maxLod) {
        return new WebGpuSampler(addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    @Override
    public GpuTexture createTexture(@Nullable String label, @GpuTexture.Usage int usage, GpuFormat format, int width,
            int height, int depthOrLayers, int mipLevels) {
        String webFormat = WebGpuFormats.textureFormat(format);
        if (webFormat == null) {
            throw new UnsupportedOperationException("WebGPU has no texture format for " + format);
        }
        int handle = WebGpuGlue.createTexture(width, height, depthOrLayers, mipLevels, webFormat,
                WebGpuFormats.textureUsage(usage), label);
        return new WebGpuTexture(this, usage, label, format, width, height, depthOrLayers, mipLevels, handle, webFormat);
    }

    @Override
    public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        WebGpuTexture webTexture = (WebGpuTexture) texture;
        boolean cube = (texture.usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
        return new WebGpuTextureView(webTexture, baseMipLevel, mipLevels,
                WebGpuGlue.createView(webTexture.handle, baseMipLevel, mipLevels, cube));
    }

    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, long size) {
        int allocated = (int) ((size + 3L) & ~3L);
        int handle = WebGpuGlue.createBuffer(allocated, WebGpuFormats.bufferUsage(usage),
                this.debug && label != null ? label.get() : null);
        return new WebGpuBuffer(this, usage, size, handle);
    }

    @Override
    public GpuBuffer createBuffer(@Nullable Supplier<String> label, @GpuBuffer.Usage int usage, ByteBuffer data) {
        int length = data.remaining();
        GpuBuffer buffer = this.createBuffer(label, usage, length);
        // A new buffer is not referenced by any recorded command yet, so a direct queue write is
        // ordered correctly.
        ByteBuffer source = data;
        if ((length & 3) != 0) {
            source = ByteBuffer.allocateDirect((length + 3) & ~3).order(ByteOrder.nativeOrder());
            WebGpuGlue.copyBytes(source, 0, data);
        }
        WebGpuGlue.writeBuffer(((WebGpuBuffer) buffer).handle, 0, source);
        return buffer;
    }

    @Override
    public List<String> getLastDebugMessages() {
        List<String> messages = new ArrayList<>(this.debugMessages);
        this.debugMessages.clear();
        for (String message = WebGpuGlue.takeMessage(); message != null; message = WebGpuGlue.takeMessage()) {
            messages.add(message);
        }
        return messages;
    }

    @Override
    public boolean isDebuggingEnabled() {
        return this.debug;
    }

    /**
     * Pipeline creation is synchronous in finishCompile (WebGPU compiles in the GPU process and
     * a pipeline is usable immediately); compilePipeline only captures the description.
     */
    @Override
    public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo createInfo) {
        return () -> WebGpuRenderPipeline.compile(this, createInfo);
    }

    @Override
    public void close() {
    }

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
                java.util.Arrays.fill(values, OptionalLong.empty());
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
