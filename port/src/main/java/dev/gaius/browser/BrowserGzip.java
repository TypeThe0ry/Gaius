package dev.gaius.browser;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSByRef;
import org.teavm.jso.JSObject;

/** Uses the browser's native gzip stream for compressed built-in NBT resources. */
public final class BrowserGzip {
    private BrowserGzip() {
    }

    public static CompoundTag readCompressedNbt(InputStream input) throws IOException {
        NbtAccounter accounter = NbtAccounter.unlimitedHeap();
        if (!isSupported()) {
            return NbtIo.readCompressed(input, accounter);
        }
        byte[] compressed = input.readAllBytes();
        DecompressionState state = startDecompression(compressed);
        try {
            while (!isDone(state)) {
                Thread.sleep(0L);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Browser gzip decompression was interrupted", exception);
        }
        String error = error(state);
        if (error != null && !error.isEmpty()) {
            throw new IOException("Browser gzip decompression failed: " + error);
        }
        byte[] decompressed = new byte[resultLength(state)];
        copyResult(state, decompressed);
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(decompressed))) {
            return NbtIo.read(data, accounter);
        }
    }

    @JSBody(script = "return typeof DecompressionStream === 'function';")
    private static native boolean isSupported();

    @JSBody(params = "compressed", script = """
            const sourceData = compressed && compressed.data ? compressed.data : compressed;
            const source = new Uint8Array(sourceData.length);
            source.set(sourceData);
            const state = {done: false, bytes: null, error: ''};
            try {
              const stream = new Blob([source])
                .stream()
                .pipeThrough(new DecompressionStream('gzip'));
              new Response(stream).arrayBuffer().then(function(buffer) {
                state.bytes = new Uint8Array(buffer);
                state.done = true;
              }, function(error) {
                state.error = String(error && (error.stack || error.message) || error);
                state.done = true;
              });
            } catch (error) {
              state.error = String(error && (error.stack || error.message) || error);
              state.done = true;
            }
            return state;
            """)
    private static native DecompressionState startDecompression(@JSByRef byte[] compressed);

    @JSBody(params = "state", script = "return !!state.done;")
    private static native boolean isDone(DecompressionState state);

    @JSBody(params = "state", script = "return String(state.error || '');")
    private static native String error(DecompressionState state);

    @JSBody(params = "state", script = "return state.bytes ? state.bytes.length | 0 : 0;")
    private static native int resultLength(DecompressionState state);

    @JSBody(params = {"state", "output"}, script = """
            const target = output && output.data ? output.data : output;
            target.set(state.bytes);
            """)
    private static native void copyResult(DecompressionState state, @JSByRef byte[] output);

    private interface DecompressionState extends JSObject {
    }
}
