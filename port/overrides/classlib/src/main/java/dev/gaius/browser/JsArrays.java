package dev.gaius.browser;

import org.teavm.jso.typedarrays.Float32Array;
import org.teavm.jso.typedarrays.Float64Array;
import org.teavm.jso.typedarrays.Int16Array;
import org.teavm.jso.typedarrays.Int32Array;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Typed-array views of Java arrays for JS calls, shared by the JavaScript and the WebAssembly GC
 * builds (TeaVM's {@code @JSByRef} only exists in the JavaScript backend).
 *
 * <p>This is the JavaScript implementation: a Java primitive array is backed by a typed array,
 * so {@code int8(array)} returns that typed array itself (zero copy) and JS writes into it are
 * Java writes; {@code readBack} has nothing to do. The WebAssembly GC implementation
 * ({@code src/target-wasmgc/java}) copies the array into a new typed array, and
 * {@code readBack} copies a typed array the JS side wrote back into the Java array. Every call
 * site therefore uses the same pattern:
 * <ul>
 * <li>JS only reads the data: pass {@code int8(array)};</li>
 * <li>JS writes the result: {@code Int8Array view = int8(array); call(view);
 * readBack(view, array);}.</li>
 * </ul>
 * A view must not be kept across calls: on WebAssembly GC it is a detached copy.
 */
public final class JsArrays {
    private JsArrays() {
    }

    public static Int8Array int8(byte[] array) {
        return array == null ? null : Int8Array.fromJavaArray(array);
    }

    public static Int16Array int16(short[] array) {
        return array == null ? null : Int16Array.fromJavaArray(array);
    }

    public static Int32Array int32(int[] array) {
        return array == null ? null : Int32Array.fromJavaArray(array);
    }

    public static Float32Array float32(float[] array) {
        return array == null ? null : Float32Array.fromJavaArray(array);
    }

    public static Float64Array float64(double[] array) {
        return array == null ? null : Float64Array.fromJavaArray(array);
    }

    public static void readBack(Int8Array view, byte[] array) {
    }

    public static void readBack(Int16Array view, short[] array) {
    }

    public static void readBack(Int32Array view, int[] array) {
    }

    public static void readBack(Float32Array view, float[] array) {
    }

    public static void readBack(Float64Array view, double[] array) {
    }

    /** True when {@link #int8} and the other views share the Java array's storage. */
    public static boolean zeroCopy() {
        return true;
    }
}
