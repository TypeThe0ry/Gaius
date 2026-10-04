package dev.gaius.browser.webgpu;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;

/** Translation of renderpearl enums and usage bits to WebGPU values. */
final class WebGpuFormats {
    // GPUBufferUsage
    static final int BUFFER_MAP_READ = 0x0001;
    static final int BUFFER_COPY_SRC = 0x0004;
    static final int BUFFER_COPY_DST = 0x0008;
    static final int BUFFER_INDEX = 0x0010;
    static final int BUFFER_VERTEX = 0x0020;
    static final int BUFFER_UNIFORM = 0x0040;
    static final int BUFFER_STORAGE = 0x0080;
    static final int BUFFER_INDIRECT = 0x0100;

    // GPUTextureUsage
    static final int TEXTURE_COPY_SRC = 0x01;
    static final int TEXTURE_COPY_DST = 0x02;
    static final int TEXTURE_BINDING = 0x04;
    static final int TEXTURE_RENDER_ATTACHMENT = 0x10;

    private WebGpuFormats() {
    }

    /**
     * WebGPU usage for a renderpearl buffer. Mapped writes are emulated with a CPU copy that is
     * uploaded by the queue, so every writable buffer is a copy destination; a read-mapped buffer
     * may only be a copy destination in WebGPU.
     */
    static int bufferUsage(@GpuBuffer.Usage int usage) {
        if ((usage & GpuBuffer.USAGE_MAP_READ) != 0) {
            return BUFFER_MAP_READ | BUFFER_COPY_DST;
        }
        int result = BUFFER_COPY_DST;
        if ((usage & GpuBuffer.USAGE_COPY_SRC) != 0) {
            result |= BUFFER_COPY_SRC;
        }
        if ((usage & GpuBuffer.USAGE_VERTEX) != 0) {
            result |= BUFFER_VERTEX;
        }
        if ((usage & GpuBuffer.USAGE_INDEX) != 0) {
            result |= BUFFER_INDEX;
        }
        if ((usage & GpuBuffer.USAGE_UNIFORM) != 0) {
            result |= BUFFER_UNIFORM;
        }
        if ((usage & GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER) != 0) {
            result |= BUFFER_STORAGE;
        }
        if ((usage & GpuBuffer.USAGE_INDIRECT_PARAMETERS) != 0) {
            result |= BUFFER_INDIRECT;
        }
        return result;
    }

    static int textureUsage(@GpuTexture.Usage int usage) {
        int result = 0;
        if ((usage & GpuTexture.USAGE_COPY_DST) != 0) {
            result |= TEXTURE_COPY_DST;
        }
        if ((usage & GpuTexture.USAGE_COPY_SRC) != 0) {
            result |= TEXTURE_COPY_SRC;
        }
        if ((usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0) {
            result |= TEXTURE_BINDING;
        }
        if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
            result |= TEXTURE_RENDER_ATTACHMENT;
        }
        return result;
    }

    /**
     * WebGPU texture format, or {@code null} when WebGPU has none (the three-component formats,
     * which need an expanding upload path).
     */
    static String textureFormat(GpuFormat format) {
        return switch (format) {
            case R8_UNORM -> "r8unorm";
            case R8_SNORM -> "r8snorm";
            case RG8_UNORM -> "rg8unorm";
            case RG8_SNORM -> "rg8snorm";
            case RGBA8_UNORM -> "rgba8unorm";
            case RGBA8_SNORM -> "rgba8snorm";
            case R16_UNORM -> "r16unorm";
            case RG16_UNORM -> "rg16unorm";
            case RGBA16_UNORM -> "rgba16unorm";
            case R8_UINT -> "r8uint";
            case R8_SINT -> "r8sint";
            case RG8_UINT -> "rg8uint";
            case RG8_SINT -> "rg8sint";
            case RGBA8_UINT -> "rgba8uint";
            case RGBA8_SINT -> "rgba8sint";
            case R16_UINT -> "r16uint";
            case R16_SINT -> "r16sint";
            case RG16_UINT -> "rg16uint";
            case RG16_SINT -> "rg16sint";
            case RGBA16_UINT -> "rgba16uint";
            case RGBA16_SINT -> "rgba16sint";
            case R32_UINT -> "r32uint";
            case R32_SINT -> "r32sint";
            case RG32_UINT -> "rg32uint";
            case RG32_SINT -> "rg32sint";
            case RGBA32_UINT -> "rgba32uint";
            case RGBA32_SINT -> "rgba32sint";
            case R16_FLOAT -> "r16float";
            case RG16_FLOAT -> "rg16float";
            case RGBA16_FLOAT -> "rgba16float";
            case R32_FLOAT -> "r32float";
            case RG32_FLOAT -> "rg32float";
            case RGBA32_FLOAT -> "rgba32float";
            case RGB10A2_UNORM -> "rgb10a2unorm";
            case RGB10A2_UINT -> "rgb10a2uint";
            case RG11B10_FLOAT -> "rg11b10ufloat";
            case D32_FLOAT -> "depth32float";
            case D32_FLOAT_S8_UINT -> "depth32float-stencil8";
            case D24_UNORM_S8_UINT -> "depth24plus-stencil8";
            case D16_UNORM -> "depth16unorm";
            case S8_UINT -> "stencil8";
            default -> null;
        };
    }

    /**
     * WebGPU vertex format. Vanilla pads three-component 8- and 16-bit attributes to four
     * bytes per component group, so those read as four-component formats.
     */
    static String vertexFormat(GpuFormat format) {
        return switch (format) {
            case R8_UNORM -> "unorm8";
            case R8_SNORM -> "snorm8";
            case RG8_UNORM -> "unorm8x2";
            case RG8_SNORM -> "snorm8x2";
            case RGB8_UNORM, RGBA8_UNORM -> "unorm8x4";
            case RGB8_SNORM, RGBA8_SNORM -> "snorm8x4";
            case R16_UNORM -> "unorm16";
            case R16_SNORM -> "snorm16";
            case RG16_UNORM -> "unorm16x2";
            case RG16_SNORM -> "snorm16x2";
            case RGB16_UNORM, RGBA16_UNORM -> "unorm16x4";
            case RGB16_SNORM, RGBA16_SNORM -> "snorm16x4";
            case R8_UINT -> "uint8";
            case R8_SINT -> "sint8";
            case RG8_UINT -> "uint8x2";
            case RG8_SINT -> "sint8x2";
            case RGB8_UINT, RGBA8_UINT -> "uint8x4";
            case RGB8_SINT, RGBA8_SINT -> "sint8x4";
            case R16_UINT -> "uint16";
            case R16_SINT -> "sint16";
            case RG16_UINT -> "uint16x2";
            case RG16_SINT -> "sint16x2";
            case RGB16_UINT, RGBA16_UINT -> "uint16x4";
            case RGB16_SINT, RGBA16_SINT -> "sint16x4";
            case R32_UINT -> "uint32";
            case R32_SINT -> "sint32";
            case RG32_UINT -> "uint32x2";
            case RG32_SINT -> "sint32x2";
            case RGB32_UINT -> "uint32x3";
            case RGB32_SINT -> "sint32x3";
            case RGBA32_UINT -> "uint32x4";
            case RGBA32_SINT -> "sint32x4";
            case R16_FLOAT -> "float16";
            case RG16_FLOAT -> "float16x2";
            case RGB16_FLOAT, RGBA16_FLOAT -> "float16x4";
            case R32_FLOAT -> "float32";
            case RG32_FLOAT -> "float32x2";
            case RGB32_FLOAT -> "float32x3";
            case RGBA32_FLOAT -> "float32x4";
            case RGB10A2_UNORM -> "unorm10-10-10-2";
            default -> throw new IllegalArgumentException("No WebGPU vertex format for " + format);
        };
    }

    static String compare(CompareOp op) {
        return switch (op) {
            case ALWAYS_PASS -> "always";
            case LESS_THAN -> "less";
            case LESS_THAN_OR_EQUAL -> "less-equal";
            case EQUAL -> "equal";
            case NOT_EQUAL -> "not-equal";
            case GREATER_THAN_OR_EQUAL -> "greater-equal";
            case GREATER_THAN -> "greater";
            case NEVER_PASS -> "never";
        };
    }

    /** WebGPU has a single blend constant, so the colour and alpha constant factors share it. */
    static String blendFactor(BlendFactor factor) {
        return switch (factor) {
            case CONSTANT_ALPHA, CONSTANT_COLOR -> "constant";
            case ONE_MINUS_CONSTANT_ALPHA, ONE_MINUS_CONSTANT_COLOR -> "one-minus-constant";
            case DST_ALPHA -> "dst-alpha";
            case DST_COLOR -> "dst";
            case ONE -> "one";
            case ONE_MINUS_DST_ALPHA -> "one-minus-dst-alpha";
            case ONE_MINUS_DST_COLOR -> "one-minus-dst";
            case ONE_MINUS_SRC_ALPHA -> "one-minus-src-alpha";
            case ONE_MINUS_SRC_COLOR -> "one-minus-src";
            case SRC_ALPHA -> "src-alpha";
            case SRC_ALPHA_SATURATE -> "src-alpha-saturated";
            case SRC_COLOR -> "src";
            case ZERO -> "zero";
        };
    }

    static String blendOp(BlendOp op) {
        return switch (op) {
            case ADD -> "add";
            case SUBTRACT -> "subtract";
            case REVERSE_SUBTRACT -> "reverse-subtract";
            case MIN -> "min";
            case MAX -> "max";
        };
    }

    /**
     * WebGPU topology. QUADS and LINES arrive as triangle lists (shared quad index buffer and
     * vertex-shader line expansion); TRIANGLE_FAN has no WebGPU equivalent and needs an index
     * conversion that this backend does not do yet, so it returns {@code null}.
     */
    static String topology(PrimitiveTopology topology) {
        return switch (topology) {
            case TRIANGLES, QUADS, LINES -> "triangle-list";
            case TRIANGLE_STRIP -> "triangle-strip";
            case DEBUG_LINES -> "line-list";
            case DEBUG_LINE_STRIP -> "line-strip";
            case POINTS -> "point-list";
            case TRIANGLE_FAN -> null;
        };
    }

    static boolean isLineOrPoint(PrimitiveTopology topology) {
        return topology == PrimitiveTopology.DEBUG_LINES || topology == PrimitiveTopology.DEBUG_LINE_STRIP
                || topology == PrimitiveTopology.POINTS;
    }

    static String address(AddressMode mode) {
        return mode == AddressMode.REPEAT ? "repeat" : "clamp-to-edge";
    }

    static String filter(FilterMode mode) {
        return mode == FilterMode.LINEAR ? "linear" : "nearest";
    }
}
