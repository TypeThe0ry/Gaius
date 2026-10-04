package dev.gaius.browser;

import org.teavm.jso.typedarrays.Float32Array;
import org.teavm.jso.typedarrays.Float64Array;
import org.teavm.jso.typedarrays.Int16Array;
import org.teavm.jso.typedarrays.Int32Array;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * WebAssembly GC implementation of the typed-array views (see the JavaScript one in
 * {@code src/main/java} for the call-site contract). A WebAssembly GC array lives in the GC
 * heap, not in a JS ArrayBuffer, so {@code int8(array)} copies the array into a new typed array
 * and {@code readBack} copies a view that JS wrote back into the array. Per-frame paths should
 * use direct buffers (linear memory, {@code fromJavaBuffer}) instead (design D7).
 */
public final class JsArrays {
    private JsArrays() {
    }

    public static Int8Array int8(byte[] array) {
        return array == null ? null : Int8Array.copyFromJavaArray(array);
    }

    public static Int16Array int16(short[] array) {
        return array == null ? null : Int16Array.copyFromJavaArray(array);
    }

    public static Int32Array int32(int[] array) {
        return array == null ? null : Int32Array.copyFromJavaArray(array);
    }

    public static Float32Array float32(float[] array) {
        return array == null ? null : Float32Array.copyFromJavaArray(array);
    }

    public static Float64Array float64(double[] array) {
        return array == null ? null : Float64Array.copyFromJavaArray(array);
    }

    public static void readBack(Int8Array view, byte[] array) {
        if (view != null && array != null) {
            byte[] copy = view.copyToJavaArray();
            System.arraycopy(copy, 0, array, 0, Math.min(copy.length, array.length));
        }
    }

    public static void readBack(Int16Array view, short[] array) {
        if (view != null && array != null) {
            short[] copy = view.copyToJavaArray();
            System.arraycopy(copy, 0, array, 0, Math.min(copy.length, array.length));
        }
    }

    public static void readBack(Int32Array view, int[] array) {
        if (view != null && array != null) {
            int[] copy = view.copyToJavaArray();
            System.arraycopy(copy, 0, array, 0, Math.min(copy.length, array.length));
        }
    }

    public static void readBack(Float32Array view, float[] array) {
        if (view != null && array != null) {
            float[] copy = view.copyToJavaArray();
            System.arraycopy(copy, 0, array, 0, Math.min(copy.length, array.length));
        }
    }

    public static void readBack(Float64Array view, double[] array) {
        if (view != null && array != null) {
            double[] copy = view.copyToJavaArray();
            System.arraycopy(copy, 0, array, 0, Math.min(copy.length, array.length));
        }
    }

    /** True when {@link #int8} and the other views share the Java array's storage. */
    public static boolean zeroCopy() {
        return false;
    }
}
