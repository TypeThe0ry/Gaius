package org.lwjgl.stb;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import dev.gaius.browser.JsArrays;
import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Int8Array;

final class BrowserPng {
    static final boolean AVAILABLE = true;

    private BrowserPng() {
    }

    static ByteBuffer tryDecode(ByteBuffer input, IntBuffer x, IntBuffer y, IntBuffer channels,
            int desiredChannels, boolean flip) {
        if (!available() || input == null || input.remaining() > 64 * 1024 * 1024) {
            return null;
        }
        try {
            byte[] encoded = new byte[input.remaining()];
            input.duplicate().get(encoded);
            Int8Array result = decode(JsArrays.int8(encoded), desiredChannels);
            if (result == null || result.getLength() < 16 || result.getLength() > 64 * 1024 * 1024 + 16) {
                return null;
            }
            byte[] payload = new byte[result.getLength()];
            JsArrays.readBack(result, payload);
            return PngPixels.materialize(payload, desiredChannels, flip, x, y, channels);
        } catch (Throwable throwable) {
            return null;
        }
    }

    @JSBody(script = "return !!(globalThis.GaiusPngKernel && globalThis.GaiusPngKernel.enabled && globalThis.GaiusPngKernel.ready);")
    private static native boolean available();

    @JSBody(params = { "encoded", "desiredChannels" }, script =
            "var kernel = globalThis.GaiusPngKernel; if (!kernel) return null;"
            + "var result = kernel.decode(new Uint8Array(encoded.buffer, encoded.byteOffset, encoded.byteLength), desiredChannels);"
            + "return result ? new Int8Array(result.buffer, result.byteOffset, result.byteLength) : null;")
    private static native Int8Array decode(Int8Array encoded, int desiredChannels);
}
