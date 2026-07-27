package org.lwjgl.system;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4fc;

/**
 * Managed address space used by the browser LWJGL overlay.
 *
 * <p>TeaVM does not expose JVM Unsafe addresses.  This class keeps stable
 * pseudo-addresses for byte buffers and implements the small native-memory
 * surface that the patched LWJGL {@link MemoryUtil} calls.</p>
 */
public final class BrowserMemory {
    private static final ByteOrder ORDER = ByteOrder.LITTLE_ENDIAN;
    private static final List<Block> BLOCKS = new ArrayList<>();
    private static final Map<Buffer, BufferRef> BUFFER_REFS = new IdentityHashMap<>();
    private static long nextAddress = 0x100000L;
    private static long threadJniEnv;
    private static final ThreadLocal<byte[]> BYTE_ARRAYS =
            ThreadLocal.withInitial(() -> new byte[8192]);
    private static final ThreadLocal<char[]> CHAR_ARRAYS =
            ThreadLocal.withInitial(() -> new char[4096]);

    private BrowserMemory() {
    }

    public static ThreadLocal<byte[]> byteArrays() {
        return BYTE_ARRAYS;
    }

    public static ThreadLocal<char[]> charArrays() {
        return CHAR_ARRAYS;
    }

    public static Class<?> bufferClass(int index) {
        return switch (index) {
            case 0 -> ByteBuffer.class;
            case 1 -> ShortBuffer.class;
            case 2 -> CharBuffer.class;
            case 3 -> IntBuffer.class;
            case 4 -> LongBuffer.class;
            case 5 -> FloatBuffer.class;
            case 6 -> DoubleBuffer.class;
            default -> throw new IllegalArgumentException("Unknown buffer kind: " + index);
        };
    }

    public static long allocate(long size) {
        int capacity = checkedSize(size);
        long address = nextAddress;
        nextAddress += Math.max(16L, capacity + 16L);
        BLOCKS.add(new Block(address, ByteBuffer.allocate(capacity).order(ORDER)));
        return address;
    }

    public static long calloc(long count, long size) {
        return allocate(Math.multiplyExact(count, size));
    }

    /**
     * Returns the browser-side stand-in for LWJGL's {@code JNIEnv**}.
     *
     * <p>{@code ThreadLocalUtil} dereferences this value during static
     * initialization, before any graphics call is made. The desktop native
     * implementation is unavailable under TeaVM, so returning the generic
     * native fallback value ({@code 0}) crashes immediately. A zero-filled,
     * managed function table is sufficient because browser graphics calls are
     * dispatched by the LWJGL browser overlays instead of through JNI.</p>
     */
    public static long threadJniEnv() {
        if (threadJniEnv == 0L) {
            long functionTable = allocate(32L * 1024L);
            threadJniEnv = allocate(Long.BYTES);
            putLong(threadJniEnv, functionTable);
        }
        return threadJniEnv;
    }

    /** Allocates and installs a per-thread JNI table stand-in for LWJGL. */
    public static long setupThreadEnv(int functionCount) {
        int entries = Math.max(4, functionCount + 4);
        long functionTable = allocate(Math.multiplyExact((long) entries, Long.BYTES));
        putLong(threadJniEnv(), functionTable);
        return functionTable;
    }

    public static long reallocate(long address, long size) {
        if (address == 0L) {
            return allocate(size);
        }
        Block old = blockFor(address);
        long replacement = allocate(size);
        copy(address, replacement, Math.min(old.buffer.capacity() - offset(address), size));
        free(address);
        return replacement;
    }

    public static void free(long address) {
        if (address == 0L) {
            return;
        }
        Block block = blockFor(address);
        BLOCKS.remove(block);
        BUFFER_REFS.entrySet().removeIf(entry -> entry.getValue().block == block);
    }

    public static void free(Buffer buffer) {
        BufferRef ref = BUFFER_REFS.remove(buffer);
        if (ref != null) {
            BLOCKS.remove(ref.block);
        }
    }

    public static long address0(Buffer buffer) {
        BufferRef ref = refFor(buffer);
        return ref.baseAddress + (long) buffer.position() * ref.elementSize;
    }

    public static ByteBuffer byteBuffer(long address, int capacity) {
        if (address == 0L && capacity == 0) {
            return ByteBuffer.allocate(0).order(ORDER);
        }
        Block block = blockFor(address);
        int start = offset(address);
        if (capacity < 0 || start + capacity > block.buffer.capacity()) {
            throw new IndexOutOfBoundsException("buffer outside managed allocation");
        }
        ByteBuffer result = block.buffer.duplicate().order(ORDER);
        result.position(start).limit(start + capacity);
        result = result.slice().order(ORDER);
        register(result, address, 1, block);
        return result;
    }

    public static ByteBuffer reallocate(ByteBuffer buffer, int size) {
        return byteBuffer(reallocate(address0(buffer), size), size);
    }

    public static ShortBuffer reallocate(ShortBuffer buffer, int size) {
        return (ShortBuffer) wrap(ShortBuffer.class, reallocate(address0(buffer), (long) size * 2), size);
    }

    public static IntBuffer reallocate(IntBuffer buffer, int size) {
        return (IntBuffer) wrap(IntBuffer.class, reallocate(address0(buffer), (long) size * 4), size);
    }

    public static LongBuffer reallocate(LongBuffer buffer, int size) {
        return (LongBuffer) wrap(LongBuffer.class, reallocate(address0(buffer), (long) size * 8), size);
    }

    public static FloatBuffer reallocate(FloatBuffer buffer, int size) {
        return (FloatBuffer) wrap(FloatBuffer.class, reallocate(address0(buffer), (long) size * 4), size);
    }

    public static DoubleBuffer reallocate(DoubleBuffer buffer, int size) {
        return (DoubleBuffer) wrap(DoubleBuffer.class, reallocate(address0(buffer), (long) size * 8), size);
    }

    public static byte getByte(long address) {
        return bytes(address).get(offset(address));
    }

    public static short getShort(long address) {
        return bytes(address).getShort(offset(address));
    }

    public static int getInt(long address) {
        return bytes(address).getInt(offset(address));
    }

    public static long getLong(long address) {
        return bytes(address).getLong(offset(address));
    }

    public static float getFloat(long address) {
        return bytes(address).getFloat(offset(address));
    }

    public static double getDouble(long address) {
        return bytes(address).getDouble(offset(address));
    }

    public static void putByte(long address, byte value) {
        bytes(address).put(offset(address), value);
    }

    public static void putShort(long address, short value) {
        bytes(address).putShort(offset(address), value);
    }

    public static void putInt(long address, int value) {
        bytes(address).putInt(offset(address), value);
    }

    public static void putLong(long address, long value) {
        bytes(address).putLong(offset(address), value);
    }

    public static void putFloat(long address, float value) {
        bytes(address).putFloat(offset(address), value);
    }

    public static void putDouble(long address, double value) {
        bytes(address).putDouble(offset(address), value);
    }

    public static void set(long address, int value, long size) {
        byte byteValue = (byte) value;
        for (long index = 0; index < size; index++) {
            putByte(address + index, byteValue);
        }
    }

    public static void copy(long source, long destination, long size) {
        if (size == 0L) {
            return;
        }
        int length = checkedSize(size);
        byte[] temporary = new byte[length];
        for (int index = 0; index < length; index++) {
            temporary[index] = getByte(source + index);
        }
        for (int index = 0; index < length; index++) {
            putByte(destination + index, temporary[index]);
        }
    }

    public static int lengthNt1(long address, int maxLength) {
        for (int index = 0; index < maxLength; index++) {
            if (getByte(address + index) == 0) {
                return index;
            }
        }
        return maxLength;
    }

    public static String decodeAscii(long address, int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) {
            bytes[index] = getByte(address + index);
        }
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    public static String decodeUtf8(long address, int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) {
            bytes[index] = getByte(address + index);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static long getCLong(long address) {
        return getLong(address);
    }

    public static void putCLong(long address, long value) {
        putLong(address, value);
    }

    public static int write8(long address, int index, int value) {
        putByte(address + Integer.toUnsignedLong(index), (byte) value);
        return index + 1;
    }

    public static int write16(long address, int index, char value) {
        putShort(address + Integer.toUnsignedLong(index), (short) value);
        return index + 2;
    }

    public static ByteBuffer slice(ByteBuffer buffer) {
        return slice(buffer, 0, buffer.remaining());
    }

    public static ShortBuffer slice(ShortBuffer buffer) {
        return slice(buffer, 0, buffer.remaining());
    }

    public static CharBuffer slice(CharBuffer buffer) {
        return slice(buffer, 0, buffer.remaining());
    }

    public static IntBuffer slice(IntBuffer buffer) {
        return slice(buffer, 0, buffer.remaining());
    }

    public static LongBuffer slice(LongBuffer buffer) {
        return slice(buffer, 0, buffer.remaining());
    }

    public static FloatBuffer slice(FloatBuffer buffer) {
        return slice(buffer, 0, buffer.remaining());
    }

    public static DoubleBuffer slice(DoubleBuffer buffer) {
        return slice(buffer, 0, buffer.remaining());
    }

    public static ByteBuffer slice(ByteBuffer buffer, int index, int length) {
        ByteBuffer duplicate = buffer.duplicate().position(buffer.position() + index)
                .limit(buffer.position() + index + length).slice().order(ORDER);
        register(duplicate, address0(buffer) + index, 1, blockFor(address0(buffer)));
        return duplicate;
    }

    public static ShortBuffer slice(ShortBuffer buffer, int index, int length) {
        ShortBuffer duplicate = buffer.duplicate().position(buffer.position() + index)
                .limit(buffer.position() + index + length).slice();
        register(duplicate, address0(buffer) + (long) index * 2, 2, blockFor(address0(buffer)));
        return duplicate;
    }

    public static CharBuffer slice(CharBuffer buffer, int index, int length) {
        CharBuffer duplicate = buffer.duplicate().position(buffer.position() + index)
                .limit(buffer.position() + index + length).slice();
        register(duplicate, address0(buffer) + (long) index * 2, 2, blockFor(address0(buffer)));
        return duplicate;
    }

    public static IntBuffer slice(IntBuffer buffer, int index, int length) {
        IntBuffer duplicate = buffer.duplicate().position(buffer.position() + index)
                .limit(buffer.position() + index + length).slice();
        register(duplicate, address0(buffer) + (long) index * 4, 4, blockFor(address0(buffer)));
        return duplicate;
    }

    public static LongBuffer slice(LongBuffer buffer, int index, int length) {
        LongBuffer duplicate = buffer.duplicate().position(buffer.position() + index)
                .limit(buffer.position() + index + length).slice();
        register(duplicate, address0(buffer) + (long) index * 8, 8, blockFor(address0(buffer)));
        return duplicate;
    }

    public static FloatBuffer slice(FloatBuffer buffer, int index, int length) {
        FloatBuffer duplicate = buffer.duplicate().position(buffer.position() + index)
                .limit(buffer.position() + index + length).slice();
        register(duplicate, address0(buffer) + (long) index * 4, 4, blockFor(address0(buffer)));
        return duplicate;
    }

    public static DoubleBuffer slice(DoubleBuffer buffer, int index, int length) {
        DoubleBuffer duplicate = buffer.duplicate().position(buffer.position() + index)
                .limit(buffer.position() + index + length).slice();
        register(duplicate, address0(buffer) + (long) index * 8, 8, blockFor(address0(buffer)));
        return duplicate;
    }

    @SuppressWarnings("unchecked")
    public static <T extends Buffer> T wrap(Class<? extends T> type, long address, int capacity) {
        ByteBuffer bytes = byteBuffer(address, capacity * elementSize(type));
        Buffer result;
        if (type == ByteBuffer.class) {
            result = bytes;
        } else if (type == ShortBuffer.class) {
            result = bytes.asShortBuffer();
        } else if (type == CharBuffer.class) {
            result = bytes.asCharBuffer();
        } else if (type == IntBuffer.class) {
            result = bytes.asIntBuffer();
        } else if (type == LongBuffer.class) {
            result = bytes.asLongBuffer();
        } else if (type == FloatBuffer.class) {
            result = bytes.asFloatBuffer();
        } else if (type == DoubleBuffer.class) {
            result = bytes.asDoubleBuffer();
        } else {
            throw new IllegalArgumentException("Unsupported buffer type: " + type);
        }
        register(result, address, elementSize(type), blockFor(address));
        return (T) result;
    }

    /** Writes the compact/full vertex layout used by Minecraft's fast buffer path. */
    public static void putFastVertex(
            long address,
            float x, float y, float z,
            int color,
            float u, float v,
            int light, int overlay,
            float normalX, float normalY, float normalZ,
            boolean full) {
        putFloat(address, x);
        putFloat(address + 4, y);
        putFloat(address + 8, z);
        putInt(address + 12, color);
        putFloat(address + 16, u);
        putFloat(address + 20, v);
        if (full) {
            putInt(address + 24, light);
            putInt(address + 28, overlay);
            putByte(address + 32, normalByte(normalX));
            putByte(address + 33, normalByte(normalY));
            putByte(address + 34, normalByte(normalZ));
        } else {
            putInt(address + 24, overlay);
        }
    }

    /** Writes the position portion of a GUI/text vertex. */
    public static void putPosition(long address, float x, float y, float z) {
        putFloat(address, x);
        putFloat(address + 4, y);
        putFloat(address + 8, z);
    }

    /** Writes a position after applying the supplied pose matrix. */
    public static void putTransformedPosition(long address, Matrix4fc pose, float x, float y, float z) {
        putPosition(address,
                pose.m00() * x + pose.m10() * y + pose.m20() * z + pose.m30(),
                pose.m01() * x + pose.m11() * y + pose.m21() * z + pose.m31(),
                pose.m02() * x + pose.m12() * y + pose.m22() * z + pose.m32());
    }

    public static void putRgba(long address, int color) {
        putInt(address, color);
    }

    public static void putFloatPair(long address, float first, float second) {
        putFloat(address, first);
        putFloat(address + 4, second);
    }

    public static void putPackedUv(long address, int packedUv) {
        putInt(address, packedUv);
    }

    public static void putShortPair(long address, short first, short second) {
        putShort(address, first);
        putShort(address + 2, second);
    }

    public static void putNormal(long address, float x, float y, float z) {
        putByte(address, normalByte(x));
        putByte(address + 1, normalByte(y));
        putByte(address + 2, normalByte(z));
    }

    private static byte normalByte(float value) {
        float clamped = Math.max(-1.0f, Math.min(1.0f, value));
        return (byte) (clamped * 127.0f);
    }

    private static int checkedSize(long size) {
        if (size < 0 || size > Integer.MAX_VALUE - 16L) {
            throw new IllegalArgumentException("Managed allocation is too large: " + size);
        }
        return (int) Math.max(1L, size);
    }

    private static ByteBuffer bytes(long address) {
        return blockFor(address).buffer;
    }

    private static int offset(long address) {
        Block block = blockFor(address);
        return (int) (address - block.address);
    }

    private static Block blockFor(long address) {
        for (Block block : BLOCKS) {
            if (address >= block.address && address < block.address + block.buffer.capacity()) {
                return block;
            }
        }
        throw new IllegalArgumentException("Unknown managed address: " + address);
    }

    private static BufferRef refFor(Buffer buffer) {
        BufferRef ref = BUFFER_REFS.get(buffer);
        if (ref != null) {
            return ref;
        }
        int elementSize = elementSize(buffer);
        long address = allocate((long) buffer.capacity() * elementSize);
        ref = new BufferRef(address, blockFor(address), elementSize);
        BUFFER_REFS.put(buffer, ref);
        return ref;
    }

    private static void register(Buffer buffer, long address, int elementSize, Block block) {
        BUFFER_REFS.put(buffer, new BufferRef(address, block, elementSize));
    }

    private static int elementSize(Class<?> type) {
        if (type == ByteBuffer.class) return 1;
        if (type == ShortBuffer.class || type == CharBuffer.class) return 2;
        if (type == IntBuffer.class || type == FloatBuffer.class) return 4;
        if (type == LongBuffer.class || type == DoubleBuffer.class) return 8;
        throw new IllegalArgumentException("Unsupported buffer type: " + type);
    }

    private static int elementSize(Buffer buffer) {
        if (buffer instanceof ByteBuffer) return 1;
        if (buffer instanceof ShortBuffer || buffer instanceof CharBuffer) return 2;
        if (buffer instanceof IntBuffer || buffer instanceof FloatBuffer) return 4;
        if (buffer instanceof LongBuffer || buffer instanceof DoubleBuffer) return 8;
        throw new IllegalArgumentException("Unsupported buffer type: " + buffer.getClass());
    }

    private static final class Block {
        private final long address;
        private final ByteBuffer buffer;

        private Block(long address, ByteBuffer buffer) {
            this.address = address;
            this.buffer = buffer;
        }
    }

    private static final class BufferRef {
        private final long baseAddress;
        private final Block block;
        private final int elementSize;

        private BufferRef(long baseAddress, Block block, int elementSize) {
            this.baseAddress = baseAddress;
            this.block = block;
            this.elementSize = elementSize;
        }
    }
}
