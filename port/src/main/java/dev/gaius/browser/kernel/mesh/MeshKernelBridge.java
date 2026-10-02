package dev.gaius.browser.kernel.mesh;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSByRef;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.platform.Platform;

/**
 * Java side of the page's mesh kernel facade ({@code port/web/kernels/mesh-kernel-job.js},
 * {@code window.__gaiusMeshKernel}). Profile independent: the 26.2 and 26.3 section compiler
 * hooks ({@code MeshKernelHooks} in their version source sets) build the snapshot arrays and
 * call {@link #submit}; finished jobs come back through {@link #poll} as opaque records whose
 * fields the accessors below read.
 *
 * <p>Runtime switch and fallback: {@link #ready()} is false when the facade is missing, the
 * kernel runtime or the mesher kernel is unavailable, {@code ?meshKernel=0} (or
 * {@code ?gaiusMesher=0}) is set, or after {@value #MAX_FAILURES} kernel failures in a row; every
 * caller then keeps the vanilla Java compiler.</p>
 *
 * <p>Results are pulled, never pushed into Java from a JS callback: the facade wakes Java once
 * when a result lands in an empty queue, and the wake starts a TeaVM thread that runs the
 * registered {@link #setDrainer drainer}. {@link #pumpResults()} runs the drainer directly from
 * a Java frame (BrowserRenderScheduler.beginFrame) so results reach the install queue in the
 * same frame.</p>
 */
public final class MeshKernelBridge {
    /** Header ints of a submitted job (mirrors HEADER in mesh-kernel-job.js). */
    public static final int H_TICKET = 0;
    public static final int H_EPOCH = 1;
    public static final int H_WIDE = 2;
    public static final int H_PALETTE_COUNT = 3;
    public static final int H_FLAGS = 4;
    public static final int H_SX = 5;
    public static final int H_SY = 6;
    public static final int H_SZ = 7;
    public static final int H_SEED_HI = 8;
    public static final int H_SEED_LO = 9;
    public static final int H_REQUEST_SEQ = 10;
    public static final int H_NON_AIR = 11;
    public static final int H_DISTANCE = 12;
    public static final int HEADER_LENGTH = 13;

    public static final int FLAG_AO = 1;
    public static final int FLAG_CUTOUT_LEAVES = 2;
    public static final int FLAG_NEARBY = 4;

    /** Record status: meshed, the kernel asked for the vanilla compiler, or the job failed. */
    public static final int STATUS_MESHED = 0;
    public static final int STATUS_NEEDS_VANILLA = 2;
    public static final int STATUS_FAILED = -1;

    public static final int LAYER_SOLID = 0;
    public static final int LAYER_CUTOUT = 1;
    public static final int LAYER_TRANSLUCENT = 2;

    private static final int MAX_FAILURES = 3;
    private static final int PUBLISH_INTERVAL = 64;

    private static boolean disabled;
    private static boolean facadeMissingLogged;
    private static int failuresInARow;
    private static boolean wakeInstalled;
    private static Runnable drainer;
    private static boolean draining;
    private static long submitted;
    private static long meshed;
    private static long vanillaFallbacks;
    private static long failures;
    private static long snapshotNanos;
    private static long installNanos;
    private static int sinceLastPublish;

    private MeshKernelBridge() {
    }

    @JSFunctor
    interface Wake extends JSObject {
        void wake();
    }

    /** True when sections should go to the mesh kernel. */
    public static boolean ready() {
        if (disabled) {
            return false;
        }
        boolean ready;
        try {
            ready = facadeReady();
        } catch (RuntimeException error) {
            if (!facadeMissingLogged) {
                facadeMissingLogged = true;
                System.out.println("[mesh-kernel] facade check failed, sections use the vanilla compiler: "
                        + error);
            }
            return false;
        }
        if (ready && !wakeInstalled) {
            wakeInstalled = true;
            installWake(MeshKernelBridge::onWake);
        }
        return ready;
    }

    /** Disables the kernel for this page; sections use the vanilla compiler from now on. */
    public static void disable(String reason) {
        if (!disabled) {
            disabled = true;
            System.out.println("[mesh-kernel] disabled, sections use the vanilla compiler: " + reason);
            try {
                facadeDisable(reason);
            } catch (RuntimeException ignored) {
                // The facade is gone; the Java flag is enough.
            }
        }
    }

    /** A kernel job or its installation failed; three in a row disable the kernel. */
    public static void noteFailure(String reason) {
        failures++;
        failuresInARow++;
        if (failuresInARow >= MAX_FAILURES) {
            disable(failuresInARow + " failures in a row, last: " + reason);
        } else {
            System.out.println("[mesh-kernel] job failed, section compiled by vanilla: " + reason);
        }
    }

    public static void noteMeshed() {
        failuresInARow = 0;
        meshed++;
        maybePublish();
    }

    public static void noteVanillaFallback() {
        vanillaFallbacks++;
        maybePublish();
    }

    public static void noteSnapshotNanos(long nanos) {
        snapshotNanos += nanos;
    }

    public static void noteInstallNanos(long nanos) {
        installNanos += nanos;
    }

    /** Registers the version-specific result drainer (MeshKernelHooks.drainResults). */
    public static void setDrainer(Runnable runnable) {
        drainer = runnable;
    }

    /** Moves finished kernel results into the install queue; cheap when nothing finished. */
    public static void pumpResults() {
        Runnable current = drainer;
        if (current == null || draining) {
            return;
        }
        draining = true;
        try {
            current.run();
        } finally {
            draining = false;
        }
    }

    private static void onWake() {
        Platform.startThread(MeshKernelBridge::pumpResults);
    }

    public static boolean hasTable(int epoch) {
        return facadeHasTable(epoch);
    }

    /** Hands a new model table to the facade (copied there); false when the facade is missing. */
    public static boolean setTable(byte[] bytes, int length, int epoch) {
        try {
            return facadeSetTable(bytes, length, epoch);
        } catch (RuntimeException error) {
            noteFailure("model table rejected: " + error);
            return false;
        }
    }

    /**
     * Submits one section job. {@code ids16} is used unless {@code header[H_WIDE]} is set, then
     * {@code ids32}. All arrays are reused by the caller; the facade copies them before returning.
     */
    public static boolean submit(int[] header, short[] ids16, int[] ids32, byte[] light, byte[] quarts,
            int[] palette, byte[] swamp, float[] floats) {
        boolean sent;
        if (header[H_WIDE] != 0) {
            sent = facadeSubmitWide(header, ids32, light, quarts, palette, swamp, floats);
        } else {
            sent = facadeSubmit(header, ids16, light, quarts, palette, swamp, floats);
        }
        if (sent) {
            submitted++;
        }
        return sent;
    }

    /** Next finished record, or null. */
    public static JSObject poll() {
        return facadePoll();
    }

    // --- record accessors ----------------------------------------------------------------------

    @JSBody(params = "record", script = "return record.ticket | 0;")
    public static native int ticket(JSObject record);

    @JSBody(params = "record", script = "return record.status | 0;")
    public static native int status(JSObject record);

    @JSBody(params = "record", script = "return record.code ? String(record.code) : '';")
    public static native String code(JSObject record);

    @JSBody(params = "record", script = "return record.message ? String(record.message) : '';")
    public static native String message(JSObject record);

    @JSBody(params = {"record", "layer"}, script = "return record.quads[layer] | 0;")
    public static native int quadCount(JSObject record, int layer);

    @JSBody(params = "record", script = "return record.visLo | 0;")
    public static native int visibilityLo(JSObject record);

    @JSBody(params = "record", script = "return record.visHi | 0;")
    public static native int visibilityHi(JSObject record);

    @JSBody(params = "record", script = "return record.detail | 0;")
    public static native int detail(JSObject record);

    /**
     * Copies the vanilla BLOCK vertices of {@code layer} into {@code target} at {@code offset};
     * returns the bytes copied, or -1 when the record has no vertices for the layer.
     */
    @JSBody(params = {"record", "layer", "target", "offset"}, script = ""
            + "var names = ['solid', 'cutout', 'translucent'];"
            + "if (!record.result) return -1;"
            + "var l = record.result.layers[names[layer]];"
            + "if (!l || !l.vertices) return -1;"
            + "var v = l.vertices;"
            + "if (offset < 0 || offset + v.byteLength > target.length) return -1;"
            + "target.set(new Int8Array(v.buffer, v.byteOffset, v.byteLength), offset);"
            + "return v.byteLength;")
    public static native int copyVertices(JSObject record, int layer, @JSByRef byte[] target, int offset);

    /** Copies the translucent quad order (farthest first) into {@code target}; -1 when absent. */
    @JSBody(params = {"record", "target"}, script = ""
            + "if (!record.result) return -1;"
            + "var o = record.result.layers.translucent.order;"
            + "if (!o || o.length > target.length) return -1;"
            + "target.set(new Int32Array(o.buffer, o.byteOffset, o.length));"
            + "return o.length;")
    public static native int copyTranslucentOrder(JSObject record, @JSByRef int[] target);

    // --- facade ---------------------------------------------------------------------------------

    @JSBody(script = "var g = typeof globalThis !== 'undefined' ? globalThis : self;"
            + "var k = g.__gaiusMeshKernel;"
            + "return !!k && typeof k.ready === 'function' && k.ready() === true;")
    private static native boolean facadeReady();

    @JSBody(params = "reason", script = "var g = typeof globalThis !== 'undefined' ? globalThis : self;"
            + "var k = g.__gaiusMeshKernel;"
            + "if (k && typeof k.disable === 'function') k.disable(reason);")
    private static native void facadeDisable(String reason);

    @JSBody(params = "wake", script = "var g = typeof globalThis !== 'undefined' ? globalThis : self;"
            + "var k = g.__gaiusMeshKernel;"
            + "if (k && typeof k.setWake === 'function') k.setWake(wake);")
    private static native void installWake(Wake wake);

    @JSBody(params = "epoch", script = "var g = typeof globalThis !== 'undefined' ? globalThis : self;"
            + "var k = g.__gaiusMeshKernel;"
            + "return !!k && k.hasTable(epoch) === true;")
    private static native boolean facadeHasTable(int epoch);

    @JSBody(params = {"bytes", "length", "epoch"}, script = ""
            + "var g = typeof globalThis !== 'undefined' ? globalThis : self;"
            + "var k = g.__gaiusMeshKernel;"
            + "return !!k && k.setTable(bytes, length, epoch) === true;")
    private static native boolean facadeSetTable(@JSByRef byte[] bytes, int length, int epoch);

    @JSBody(params = {"header", "ids", "light", "quarts", "palette", "swamp", "floats"}, script = ""
            + "var g = typeof globalThis !== 'undefined' ? globalThis : self;"
            + "var k = g.__gaiusMeshKernel;"
            + "return !!k && k.submitJob(header, ids, light, quarts, palette, swamp, floats) === true;")
    private static native boolean facadeSubmit(@JSByRef int[] header, @JSByRef short[] ids, @JSByRef byte[] light,
            @JSByRef byte[] quarts, @JSByRef int[] palette, @JSByRef byte[] swamp, @JSByRef float[] floats);

    @JSBody(params = {"header", "ids", "light", "quarts", "palette", "swamp", "floats"}, script = ""
            + "var g = typeof globalThis !== 'undefined' ? globalThis : self;"
            + "var k = g.__gaiusMeshKernel;"
            + "return !!k && k.submitJob(header, ids, light, quarts, palette, swamp, floats) === true;")
    private static native boolean facadeSubmitWide(@JSByRef int[] header, @JSByRef int[] ids, @JSByRef byte[] light,
            @JSByRef byte[] quarts, @JSByRef int[] palette, @JSByRef byte[] swamp, @JSByRef float[] floats);

    @JSBody(script = "var g = typeof globalThis !== 'undefined' ? globalThis : self;"
            + "var k = g.__gaiusMeshKernel;"
            + "if (!k) return null;"
            + "var r = k.poll();"
            + "return r ? r : null;")
    private static native JSObject facadePoll();

    // --- telemetry ------------------------------------------------------------------------------

    private static void maybePublish() {
        if (++sinceLastPublish < PUBLISH_INTERVAL) {
            return;
        }
        sinceLastPublish = 0;
        try {
            publish((double) submitted, (double) meshed, (double) vanillaFallbacks, (double) failures,
                    snapshotNanos / 1.0e6, installNanos / 1.0e6, disabled);
        } catch (RuntimeException ignored) {
            // Telemetry is optional.
        }
    }

    @JSBody(params = {"submitted", "meshed", "fallbacks", "failures", "snapshotMs", "installMs", "disabled"},
            script = "var g = typeof globalThis !== 'undefined' ? globalThis : self;"
                    + "var state = g.__gaiusChunkPipelineTelemetry || (g.__gaiusChunkPipelineTelemetry = {});"
                    + "state.meshKernelSubmitted = submitted;"
                    + "state.meshKernelMeshed = meshed;"
                    + "state.meshKernelVanillaFallbacks = fallbacks;"
                    + "state.meshKernelFailures = failures;"
                    + "state.meshKernelSnapshotMs = snapshotMs;"
                    + "state.meshKernelInstallMs = installMs;"
                    + "state.meshKernelDisabled = disabled;")
    private static native void publish(double submitted, double meshed, double fallbacks, double failures,
            double snapshotMs, double installMs, boolean disabled);
}
