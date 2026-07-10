package org.lwjgl.stb;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.lwjgl.system.MemoryUtil;

/**
 * Browser-safe replacement for the small part of stb_image used by Minecraft.
 *
 * <p>The normal LWJGL class is a native binding. In TeaVM it otherwise falls
 * back to null-returning native stubs, which breaks resource reload as soon as
 * Minecraft decodes PNG assets such as the 1-bit grayscale clouds texture.</p>
 */
public final class STBImage {
    public static final int STBI_default = 0;
    public static final int STBI_grey = 1;
    public static final int STBI_grey_alpha = 2;
    public static final int STBI_rgb = 3;
    public static final int STBI_rgb_alpha = 4;

    private static String failureReason = "";
    private static boolean flipVertically;

    private STBImage() {
        throw new UnsupportedOperationException();
    }

    public static ByteBuffer stbi_load_from_memory(ByteBuffer input, IntBuffer x, IntBuffer y, IntBuffer channels, int desiredChannels) {
        try {
            DecodedPng image = decodePng(input);
            int outputChannels = desiredChannels == STBI_default ? image.components : desiredChannels;
            if (outputChannels < STBI_grey || outputChannels > STBI_rgb_alpha) {
                fail("unsupported desired channel count: " + desiredChannels);
                return null;
            }

            set(x, image.width);
            set(y, image.height);
            set(channels, image.components);

            ByteBuffer output = MemoryUtil.memAlloc(Math.multiplyExact(Math.multiplyExact(image.width, image.height), outputChannels));
            writePixels(image, output, outputChannels);
            output.flip();
            failureReason = "";
            return output;
        } catch (Throwable throwable) {
            fail(throwable.getMessage() == null ? throwable.getClass().getName() : throwable.getMessage());
            return null;
        }
    }

    public static ByteBuffer stbi_load_from_memory(ByteBuffer input, int[] x, int[] y, int[] channels, int desiredChannels) {
        IntBuffer xb = IntBuffer.allocate(1);
        IntBuffer yb = IntBuffer.allocate(1);
        IntBuffer cb = IntBuffer.allocate(1);
        ByteBuffer result = stbi_load_from_memory(input, xb, yb, cb, desiredChannels);
        if (x != null && x.length > 0) {
            x[0] = xb.get(0);
        }
        if (y != null && y.length > 0) {
            y[0] = yb.get(0);
        }
        if (channels != null && channels.length > 0) {
            channels[0] = cb.get(0);
        }
        return result;
    }

    public static long nstbi_load_from_memory(long address, int length, long x, long y, long channels, int desiredChannels) {
        ByteBuffer input = MemoryUtil.memByteBuffer(address, length);
        IntBuffer xb = IntBuffer.allocate(1);
        IntBuffer yb = IntBuffer.allocate(1);
        IntBuffer cb = IntBuffer.allocate(1);
        ByteBuffer result = stbi_load_from_memory(input, xb, yb, cb, desiredChannels);
        if (result == null) {
            return 0L;
        }
        if (x != 0L) {
            MemoryUtil.memPutInt(x, xb.get(0));
        }
        if (y != 0L) {
            MemoryUtil.memPutInt(y, yb.get(0));
        }
        if (channels != 0L) {
            MemoryUtil.memPutInt(channels, cb.get(0));
        }
        return MemoryUtil.memAddress(result);
    }

    public static void nstbi_image_free(long address) {
        MemoryUtil.nmemFree(address);
    }

    public static void stbi_image_free(ByteBuffer data) {
        MemoryUtil.memFree(data);
    }

    public static void stbi_image_free(ShortBuffer data) {
        MemoryUtil.memFree(data);
    }

    public static void stbi_image_free(FloatBuffer data) {
        MemoryUtil.memFree(data);
    }

    public static long nstbi_failure_reason() {
        return 0L;
    }

    public static String stbi_failure_reason() {
        return failureReason;
    }

    public static void stbi_set_flip_vertically_on_load(boolean flip) {
        flipVertically = flip;
    }

    private static void writePixels(DecodedPng image, ByteBuffer output, int outputChannels) {
        for (int y = 0; y < image.height; y++) {
            int sourceY = flipVertically ? image.height - 1 - y : y;
            int row = sourceY * image.width * 4;
            for (int x = 0; x < image.width; x++) {
                int index = row + x * 4;
                int r = image.rgba[index] & 0xFF;
                int g = image.rgba[index + 1] & 0xFF;
                int b = image.rgba[index + 2] & 0xFF;
                int a = image.rgba[index + 3] & 0xFF;
                switch (outputChannels) {
                    case STBI_grey -> output.put((byte) luminance(r, g, b));
                    case STBI_grey_alpha -> {
                        output.put((byte) luminance(r, g, b));
                        output.put((byte) a);
                    }
                    case STBI_rgb -> {
                        output.put((byte) r);
                        output.put((byte) g);
                        output.put((byte) b);
                    }
                    case STBI_rgb_alpha -> {
                        output.put((byte) r);
                        output.put((byte) g);
                        output.put((byte) b);
                        output.put((byte) a);
                    }
                    default -> throw new IllegalArgumentException("unsupported output channels: " + outputChannels);
                }
            }
        }
    }

    private static DecodedPng decodePng(ByteBuffer input) throws DataFormatException {
        byte[] png = copyRemaining(input);
        if (png.length < 33 || read64(png, 0) != 0x89504E470D0A1A0AL) {
            throw new IllegalArgumentException("not a PNG image");
        }

        int width = 0;
        int height = 0;
        int bitDepth = 0;
        int colorType = 0;
        int[] palette = null;
        byte[] paletteAlpha = null;
        int transparentGray = -1;
        int transparentRed = -1;
        int transparentGreen = -1;
        int transparentBlue = -1;
        ByteArrayOutputStream idat = new ByteArrayOutputStream();

        int offset = 8;
        while (offset + 12 <= png.length) {
            int length = read32(png, offset);
            offset += 4;
            if (length < 0 || offset + 4 + length + 4 > png.length) {
                throw new IllegalArgumentException("invalid PNG chunk length");
            }
            int type = read32(png, offset);
            offset += 4;
            int dataOffset = offset;
            offset += length;
            offset += 4; // CRC is already validated by the asset pipeline; skip in browser runtime.

            if (type == 0x49484452) { // IHDR
                width = read32(png, dataOffset);
                height = read32(png, dataOffset + 4);
                bitDepth = png[dataOffset + 8] & 0xFF;
                colorType = png[dataOffset + 9] & 0xFF;
                int compression = png[dataOffset + 10] & 0xFF;
                int filter = png[dataOffset + 11] & 0xFF;
                int interlace = png[dataOffset + 12] & 0xFF;
                if (width <= 0 || height <= 0 || compression != 0 || filter != 0 || interlace != 0) {
                    throw new IllegalArgumentException("unsupported PNG header");
                }
                validateColor(bitDepth, colorType);
            } else if (type == 0x504C5445) { // PLTE
                int count = length / 3;
                palette = new int[count];
                for (int i = 0; i < count; i++) {
                    int base = dataOffset + i * 3;
                    palette[i] = ((png[base] & 0xFF) << 16) | ((png[base + 1] & 0xFF) << 8) | (png[base + 2] & 0xFF);
                }
            } else if (type == 0x74524E53) { // tRNS
                if (colorType == 3) {
                    paletteAlpha = Arrays.copyOfRange(png, dataOffset, dataOffset + length);
                } else if (colorType == 0 && length >= 2) {
                    transparentGray = read16(png, dataOffset);
                } else if (colorType == 2 && length >= 6) {
                    transparentRed = read16(png, dataOffset);
                    transparentGreen = read16(png, dataOffset + 2);
                    transparentBlue = read16(png, dataOffset + 4);
                }
            } else if (type == 0x49444154) { // IDAT
                idat.write(png, dataOffset, length);
            } else if (type == 0x49454E44) { // IEND
                break;
            }
        }

        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("missing PNG header");
        }

        byte[] inflated = inflate(idat.toByteArray(), expectedInflatedSize(width, height, bitDepth, colorType));
        int channels = sourceChannels(colorType);
        int bitsPerPixel = bitDepth * channels;
        int rowBytes = (width * bitsPerPixel + 7) / 8;
        int filterBytesPerPixel = Math.max(1, (bitsPerPixel + 7) / 8);
        byte[] unfiltered = unfilter(inflated, width, height, rowBytes, filterBytesPerPixel);
        byte[] rgba = decodeRows(unfiltered, width, height, bitDepth, colorType, palette, paletteAlpha,
                transparentGray, transparentRed, transparentGreen, transparentBlue);
        return new DecodedPng(width, height, componentsFor(colorType, paletteAlpha != null), rgba);
    }

    private static byte[] unfilter(byte[] inflated, int width, int height, int rowBytes, int bytesPerPixel) {
        byte[] result = new byte[Math.multiplyExact(height, rowBytes)];
        int source = 0;
        int target = 0;
        for (int y = 0; y < height; y++) {
            if (source + 1 + rowBytes > inflated.length) {
                throw new IllegalArgumentException("truncated PNG data");
            }
            int filter = inflated[source++] & 0xFF;
            for (int x = 0; x < rowBytes; x++) {
                int raw = inflated[source++] & 0xFF;
                int left = x >= bytesPerPixel ? result[target + x - bytesPerPixel] & 0xFF : 0;
                int up = y > 0 ? result[target + x - rowBytes] & 0xFF : 0;
                int upLeft = y > 0 && x >= bytesPerPixel ? result[target + x - rowBytes - bytesPerPixel] & 0xFF : 0;
                int value = switch (filter) {
                    case 0 -> raw;
                    case 1 -> raw + left;
                    case 2 -> raw + up;
                    case 3 -> raw + ((left + up) >>> 1);
                    case 4 -> raw + paeth(left, up, upLeft);
                    default -> throw new IllegalArgumentException("unsupported PNG filter: " + filter);
                };
                result[target + x] = (byte) value;
            }
            target += rowBytes;
        }
        return result;
    }

    private static byte[] decodeRows(byte[] data, int width, int height, int bitDepth, int colorType, int[] palette,
            byte[] paletteAlpha, int transparentGray, int transparentRed, int transparentGreen, int transparentBlue) {
        byte[] rgba = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
        int source = 0;
        int target = 0;
        int rowBytes = (width * bitDepth * sourceChannels(colorType) + 7) / 8;
        for (int y = 0; y < height; y++) {
            int rowStart = source;
            BitReader bits = new BitReader(data, rowStart);
            for (int x = 0; x < width; x++) {
                int r;
                int g;
                int b;
                int a = 255;
                switch (colorType) {
                    case 0 -> {
                        int raw = readSample(data, bits, bitDepth, source);
                        if (bitDepth >= 8) {
                            source += bitDepth / 8;
                        }
                        int gray = scaleSample(raw, bitDepth);
                        r = gray;
                        g = gray;
                        b = gray;
                        if (raw == transparentGray) {
                            a = 0;
                        }
                    }
                    case 2 -> {
                        int rawR = readSample(data, bits, bitDepth, source);
                        source += bitDepth / 8;
                        int rawG = readSample(data, bits, bitDepth, source);
                        source += bitDepth / 8;
                        int rawB = readSample(data, bits, bitDepth, source);
                        source += bitDepth / 8;
                        r = scaleSample(rawR, bitDepth);
                        g = scaleSample(rawG, bitDepth);
                        b = scaleSample(rawB, bitDepth);
                        if (rawR == transparentRed && rawG == transparentGreen && rawB == transparentBlue) {
                            a = 0;
                        }
                    }
                    case 3 -> {
                        int index = readSample(data, bits, bitDepth, source);
                        if (bitDepth >= 8) {
                            source += bitDepth / 8;
                        }
                        if (palette == null || index < 0 || index >= palette.length) {
                            throw new IllegalArgumentException("invalid PNG palette index");
                        }
                        int rgb = palette[index];
                        r = (rgb >>> 16) & 0xFF;
                        g = (rgb >>> 8) & 0xFF;
                        b = rgb & 0xFF;
                        if (paletteAlpha != null && index < paletteAlpha.length) {
                            a = paletteAlpha[index] & 0xFF;
                        }
                    }
                    case 4 -> {
                        int rawGray = readSample(data, bits, bitDepth, source);
                        source += bitDepth / 8;
                        int rawAlpha = readSample(data, bits, bitDepth, source);
                        source += bitDepth / 8;
                        int gray = scaleSample(rawGray, bitDepth);
                        r = gray;
                        g = gray;
                        b = gray;
                        a = scaleSample(rawAlpha, bitDepth);
                    }
                    case 6 -> {
                        int rawR = readSample(data, bits, bitDepth, source);
                        source += bitDepth / 8;
                        int rawG = readSample(data, bits, bitDepth, source);
                        source += bitDepth / 8;
                        int rawB = readSample(data, bits, bitDepth, source);
                        source += bitDepth / 8;
                        int rawA = readSample(data, bits, bitDepth, source);
                        source += bitDepth / 8;
                        r = scaleSample(rawR, bitDepth);
                        g = scaleSample(rawG, bitDepth);
                        b = scaleSample(rawB, bitDepth);
                        a = scaleSample(rawA, bitDepth);
                    }
                    default -> throw new IllegalArgumentException("unsupported PNG color type: " + colorType);
                }
                rgba[target++] = (byte) r;
                rgba[target++] = (byte) g;
                rgba[target++] = (byte) b;
                rgba[target++] = (byte) a;
            }
            source = rowStart + rowBytes;
        }
        return rgba;
    }

    private static int readSample(byte[] data, BitReader bits, int bitDepth, int offset) {
        return switch (bitDepth) {
            case 1, 2, 4 -> bits.read(bitDepth);
            case 8 -> data[offset] & 0xFF;
            case 16 -> read16(data, offset);
            default -> throw new IllegalArgumentException("unsupported PNG bit depth: " + bitDepth);
        };
    }

    private static int scaleSample(int sample, int bitDepth) {
        return switch (bitDepth) {
            case 1 -> sample == 0 ? 0 : 255;
            case 2 -> sample * 85;
            case 4 -> sample * 17;
            case 8 -> sample;
            case 16 -> sample >>> 8;
            default -> throw new IllegalArgumentException("unsupported PNG bit depth: " + bitDepth);
        };
    }

    private static byte[] inflate(byte[] compressed, int expectedSize) throws DataFormatException {
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        byte[] buffer = new byte[Math.max(8192, Math.min(expectedSize, 65536))];
        ByteArrayOutputStream output = new ByteArrayOutputStream(expectedSize);
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count > 0) {
                    output.write(buffer, 0, count);
                } else if (inflater.needsInput()) {
                    break;
                } else if (inflater.needsDictionary()) {
                    throw new DataFormatException("PNG zlib stream needs dictionary");
                } else {
                    throw new DataFormatException("PNG zlib stream stalled");
                }
            }
        } finally {
            inflater.end();
        }
        return output.toByteArray();
    }

    private static void validateColor(int bitDepth, int colorType) {
        boolean valid = switch (colorType) {
            case 0 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8 || bitDepth == 16;
            case 2, 4, 6 -> bitDepth == 8 || bitDepth == 16;
            case 3 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8;
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("unsupported PNG color type/depth: " + colorType + "/" + bitDepth);
        }
    }

    private static int sourceChannels(int colorType) {
        return switch (colorType) {
            case 0, 3 -> 1;
            case 2 -> 3;
            case 4 -> 2;
            case 6 -> 4;
            default -> throw new IllegalArgumentException("unsupported PNG color type: " + colorType);
        };
    }

    private static int componentsFor(int colorType, boolean paletteHasAlpha) {
        return switch (colorType) {
            case 0 -> 1;
            case 2 -> 3;
            case 3 -> paletteHasAlpha ? 4 : 3;
            case 4 -> 2;
            case 6 -> 4;
            default -> 4;
        };
    }

    private static int expectedInflatedSize(int width, int height, int bitDepth, int colorType) {
        int rowBytes = (width * bitDepth * sourceChannels(colorType) + 7) / 8;
        return Math.multiplyExact(height, rowBytes + 1);
    }

    private static int luminance(int r, int g, int b) {
        return (r * 299 + g * 587 + b * 114 + 500) / 1000;
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) {
            return a;
        }
        return pb <= pc ? b : c;
    }

    private static void set(IntBuffer buffer, int value) {
        if (buffer != null) {
            buffer.put(buffer.position(), value);
        }
    }

    private static byte[] copyRemaining(ByteBuffer input) {
        ByteBuffer copy = input.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    private static int read16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private static int read32(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }

    private static long read64(byte[] bytes, int offset) {
        return ((long) read32(bytes, offset) << 32) | (read32(bytes, offset + 4) & 0xFFFFFFFFL);
    }

    private static void fail(String reason) {
        failureReason = reason == null ? "unknown image decode error" : reason;
    }

    private record DecodedPng(int width, int height, int components, byte[] rgba) {
    }

    private static final class BitReader {
        private final byte[] data;
        private int byteOffset;
        private int bitOffset;

        private BitReader(byte[] data, int byteOffset) {
            this.data = data;
            this.byteOffset = byteOffset;
        }

        private int read(int bits) {
            int value = 0;
            for (int i = 0; i < bits; i++) {
                value <<= 1;
                value |= (data[byteOffset] >>> (7 - bitOffset)) & 1;
                bitOffset++;
                if (bitOffset == 8) {
                    bitOffset = 0;
                    byteOffset++;
                }
            }
            return value;
        }
    }
}
