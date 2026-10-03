package dev.gaius.browser.kernel.worldgen;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSByRef;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * Java side of the worldgen kernel facade ({@code port/web/kernels/worldgen-kernel.js},
 * {@code globalThis.GaiusWorldgenKernel}) in the integrated server worker.
 *
 * <p>The facade owns the kernel worker pool; this class registers generator IRs and submits
 * jobs. Results arrive on a JS callback and are handed to a fresh Java thread, so installing
 * them into a proto chunk (and completing the generation future) never runs inside the JS
 * callback's stack.
 *
 * <p>Every submitted job settles exactly once (result, error, or a {@code transient:timeout} once
 * its deadline passes), so a seam that falls back to the Java path on errors never leaves a chunk
 * future pending; the server worker drains the facade before the final save.
 *
 * <p>Switches: {@code worldgenKernel=0} in the page or worker URL (also {@code gaiusKernels=0} /
 * {@code gaiusKernelsOff=worldgen} for the shared kernel runtime),
 * {@code __gaiusWorldgenKernelConfig.enabled = false}, or {@link #disable(String)} (called after a
 * rejected result) keep worldgen on the Java path. The facade also disables itself after repeated
 * kernel failures.
 */
public final class BrowserWorldgenKernel {
    private static boolean disabled;
    private static String disabledReason = "";
    private static int nextKey = 1;

    private BrowserWorldgenKernel() {
    }

    /** Whether new jobs may go to the kernel. Cheap enough to call per chunk. */
    public static boolean available() {
        return !disabled && facadeUsable();
    }

    /** Keeps every later chunk on the Java path. */
    public static void disable(String reason) {
        if (!disabled) {
            disabled = true;
            disabledReason = reason == null ? "" : reason;
            logOnce("[Gaius] worldgen kernel disabled: " + disabledReason);
        }
    }

    public static String disabledReason() {
        return disabledReason;
    }

    /**
     * Whether a job error only refused that one job (memory budget, cancelled or superseded job,
     * runtime briefly unavailable): the chunk takes the Java path but the generator stays on the
     * kernel. The facade prefixes such messages with {@code transient:}.
     */
    public static boolean isTransient(String message) {
        return message != null && message.startsWith("transient:");
    }

    /** A new generator slot key (one per dimension and seed). */
    public static int allocateKey() {
        return nextKey++;
    }

    /** Registers a generator IR under {@code key} and starts the pool. */
    public static boolean registerGenerator(int key, byte[] ir) {
        if (!facadeUsable()) {
            return false;
        }
        boolean ok = registerGeneratorNative(key, ir);
        startNative();
        return ok;
    }

    /** Whether the facade still accepts jobs for {@code key} (false after a kernel failure). */
    public static boolean generatorUsable(int key) {
        return available() && generatorUsableNative(key);
    }

    /** Runs {@code task} on a new Java thread (outside the JS callback that delivered a result). */
    public static void runOnJavaThread(Runnable task) {
        Thread thread = new Thread(task, "gaius-worldgen-kernel");
        thread.setDaemon(true);
        thread.start();
    }

    // ---- jobs ----

    /** Terrain job flags. */
    public static final int FLAG_SURFACE = 1;
    public static final int FLAG_BIOMES = 2;
    /**
     * Facade-side flag: keep the decoded noise chunk for a later {@link #submitSurface} (26.2,
     * where SURFACE is its own step); the result's {@code token} field names it (0: not kept).
     */
    public static final int FLAG_KEEP_NOISE = 1 << 8;
    private static final int[] NO_RING = new int[0];

    /**
     * Submits a terrain job. {@code beard} is the packed beardifier
     * ({@code [flags, affected x6, rigidCount, (minX, minY, minZ, maxX, maxY, maxZ, adjustment,
     * groundDelta)*, junctionCount, (x, groundY, z)*]}). {@code ringBiomes} are the biome registry
     * ids the neighbouring chunks store around the chunk (20 quart columns, each over the level
     * height; {@code gaius-worldgen-wasm} job.rs documents the order) for the surface rules, or
     * {@code null} to let the kernel compute them. A neighbour biome the kernel does not know is a
     * transient refusal ({@code transient:worldgen-fallback:...}).
     */
    public static void submitTerrain(int key, int chunkX, int chunkZ, int flags, int[] beard, int[] ringBiomes,
            ChunkCallback onResult, ErrorCallback onError) {
        submitTerrainNative(key, chunkX, chunkZ, flags, beard, ringBiomes == null ? NO_RING : ringBiomes,
                onResult, onError);
    }

    public static void submitBiomes(int key, int chunkX, int chunkZ, IdsCallback onResult, ErrorCallback onError) {
        submitBiomesNative(key, chunkX, chunkZ, onResult, onError);
    }

    /** Whether the facade can run the surface rules on a kept noise chunk. */
    public static boolean surfaceSupported() {
        return surfaceSupportedNative();
    }

    /**
     * Runs the surface rules on the noise chunk kept under {@code token}. The result lists the
     * changed blocks: {@code count}, {@code positions} ({@code section << 12 | y << 8 | z << 4 | x})
     * and {@code states} (block state ids). The kept chunk is consumed. {@code ringBiomes} as for
     * {@link #submitTerrain}.
     */
    public static void submitSurface(int key, int token, int chunkX, int chunkZ, int[] beard, int[] ringBiomes,
            ChunkCallback onResult, ErrorCallback onError) {
        submitSurfaceNative(key, token, chunkX, chunkZ, beard, ringBiomes == null ? NO_RING : ringBiomes,
                onResult, onError);
    }

    /** Releases a kept noise chunk that will not reach the kernel surface step. */
    public static void dropKeptNoise(int token) {
        if (token != 0) {
            dropKeptNoiseNative(token);
        }
    }

    /** Kernel jobs that have not settled yet (telemetry). */
    public static int pendingJobs() {
        return pendingNative();
    }

    /** A decoded chunk result (flat arrays; see {@code worldgen-kernel.js#flatten}). */
    public interface KernelChunk extends JSObject {
    }

    @JSFunctor
    public interface ChunkCallback extends JSObject {
        void accept(KernelChunk chunk);
    }

    @JSFunctor
    public interface IdsCallback extends JSObject {
        void accept(JSObject ids);
    }

    @JSFunctor
    public interface ErrorCallback extends JSObject {
        void accept(String message);
    }

    // ---- reading results into Java arrays ----

    public static int chunkInt(KernelChunk chunk, String field) {
        return intField(chunk, field);
    }

    /** Copies an Int32Array field of a chunk result; {@code null} when absent. */
    public static int[] chunkInts(KernelChunk chunk, String field) {
        int length = arrayLength(chunk, field);
        if (length < 0) {
            return null;
        }
        int[] out = new int[length];
        copyInts(chunk, field, out);
        return out;
    }

    /** Copies the section index array (u16 values, read them with {@code & 0xFFFF}). */
    public static short[] chunkIndices(KernelChunk chunk) {
        int length = arrayLength(chunk, "indices");
        short[] out = new short[Math.max(0, length)];
        if (length > 0) {
            copyShorts(chunk, "indices", out);
        }
        return out;
    }

    public static byte[] chunkBytes(KernelChunk chunk, String field) {
        int length = arrayLength(chunk, field);
        byte[] out = new byte[Math.max(0, length)];
        if (length > 0) {
            copyBytes(chunk, field, out);
        }
        return out;
    }

    /** Copies a biome id result. */
    public static int[] ids(JSObject ids) {
        int length = typedLength(ids);
        int[] out = new int[Math.max(0, length)];
        if (length > 0) {
            copyTypedInts(ids, out);
        }
        return out;
    }

    // ---- JS bindings (Rhino-compatible scripts) ----

    @JSBody(script = "var k = globalThis.GaiusWorldgenKernel;"
            + "return !!k && k.usable();")
    private static native boolean facadeUsable();

    @JSBody(script = "var k = globalThis.GaiusWorldgenKernel; if (k) { k.start(); }")
    private static native void startNative();

    @JSBody(params = {"key", "ir"}, script = "var k = globalThis.GaiusWorldgenKernel;"
            + "if (!k) { return false; }"
            + "return k.registerGenerator(key, new Uint8Array(ir.buffer, ir.byteOffset, ir.length).slice());")
    private static native boolean registerGeneratorNative(int key, @JSByRef byte[] ir);

    @JSBody(params = "key", script = "var k = globalThis.GaiusWorldgenKernel;"
            + "return !!k && k.generatorUsable(key);")
    private static native boolean generatorUsableNative(int key);

    @JSBody(params = {"key", "chunkX", "chunkZ", "flags", "beard", "ring", "onResult", "onError"},
            script = "var k = globalThis.GaiusWorldgenKernel;"
            + "if (!k) { onError('worldgen kernel facade missing'); return; }"
            + "k.submitTerrain(key, chunkX, chunkZ, flags, new Int32Array(beard),"
            + " function (chunk) { onResult(chunk); }, function (message) { onError(String(message)); },"
            + " ring.length > 0 ? new Int32Array(ring) : null);")
    private static native void submitTerrainNative(int key, int chunkX, int chunkZ, int flags, @JSByRef int[] beard,
            @JSByRef int[] ring, ChunkCallback onResult, ErrorCallback onError);

    @JSBody(params = {"key", "chunkX", "chunkZ", "onResult", "onError"},
            script = "var k = globalThis.GaiusWorldgenKernel;"
            + "if (!k) { onError('worldgen kernel facade missing'); return; }"
            + "k.submitBiomes(key, chunkX, chunkZ,"
            + " function (ids) { onResult(ids); }, function (message) { onError(String(message)); });")
    private static native void submitBiomesNative(int key, int chunkX, int chunkZ, IdsCallback onResult,
            ErrorCallback onError);

    @JSBody(script = "var k = globalThis.GaiusWorldgenKernel;"
            + "return !!k && typeof k.submitSurfaceStored === 'function';")
    private static native boolean surfaceSupportedNative();

    @JSBody(params = {"key", "token", "chunkX", "chunkZ", "beard", "ring", "onResult", "onError"},
            script = "var k = globalThis.GaiusWorldgenKernel;"
            + "if (!k || !k.submitSurfaceStored) { onError('transient:worldgen kernel surface missing'); return; }"
            + "k.submitSurfaceStored(key, token, chunkX, chunkZ, new Int32Array(beard),"
            + " function (diff) { onResult(diff); }, function (message) { onError(String(message)); },"
            + " ring.length > 0 ? new Int32Array(ring) : null);")
    private static native void submitSurfaceNative(int key, int token, int chunkX, int chunkZ, @JSByRef int[] beard,
            @JSByRef int[] ring, ChunkCallback onResult, ErrorCallback onError);

    @JSBody(params = "token", script = "var k = globalThis.GaiusWorldgenKernel;"
            + "if (k && k.dropStored) { k.dropStored(token); }")
    private static native void dropKeptNoiseNative(int token);

    @JSBody(script = "var k = globalThis.GaiusWorldgenKernel;"
            + "return k && k.pending ? k.pending() : 0;")
    private static native int pendingNative();

    @JSBody(params = {"chunk", "field"}, script = "return chunk[field] | 0;")
    private static native int intField(KernelChunk chunk, String field);

    @JSBody(params = {"chunk", "field"}, script = "var a = chunk[field]; return a ? a.length : -1;")
    private static native int arrayLength(KernelChunk chunk, String field);

    @JSBody(params = {"chunk", "field", "out"}, script = "out.set(chunk[field]);")
    private static native void copyInts(KernelChunk chunk, String field, @JSByRef int[] out);

    @JSBody(params = {"chunk", "field", "out"}, script = "out.set(chunk[field]);")
    private static native void copyShorts(KernelChunk chunk, String field, @JSByRef short[] out);

    @JSBody(params = {"chunk", "field", "out"}, script = "out.set(chunk[field]);")
    private static native void copyBytes(KernelChunk chunk, String field, @JSByRef byte[] out);

    @JSBody(params = "a", script = "return a ? a.length : -1;")
    private static native int typedLength(JSObject a);

    @JSBody(params = {"a", "out"}, script = "out.set(a);")
    private static native void copyTypedInts(JSObject a, @JSByRef int[] out);

    @JSBody(params = "message", script = "if (typeof console !== 'undefined') { console.warn(message); }")
    private static native void logOnce(String message);
}
