package dev.gaius.browser;

import org.teavm.jso.JSBody;

/** Browser hot paths for vanilla bit-packed integer storage. */
public final class BrowserBitStorage {
    private BrowserBitStorage() {
    }

    @JSBody(params = {"packed", "output", "size", "bits", "mask", "valuesPerLong"}, script = """
            try {
              var source = packed && packed.data ? packed.data : packed;
              var target = output && output.data ? output.data : output;
              var valueCount = size | 0;
              var bitCount = bits | 0;
              var perLong = valuesPerLong | 0;
              if (!source || !target || valueCount <= 0 || bitCount <= 0 || bitCount > 32 || perLong <= 0) {
                return false;
              }

              var hotpath = window.__gaiusWasmHotpath;
              if (hotpath && hotpath.ready && hotpath.unpackBitStorage) {
                if (hotpath.unpackBitStorage(source, target, valueCount, bitCount, perLong)) {
                  return true;
                }
              }

              var counters = window.__gaiusMinecraftCounters || (window.__gaiusMinecraftCounters = {});
              counters.bitStorageJsUnpack = (counters.bitStorageJsUnpack || 0) + 1;
              var bitMask = typeof mask === "bigint"
                ? BigInt.asIntN(64, mask)
                : BigInt.asIntN(64, (BigInt(1) << BigInt(bitCount)) - BigInt(1));
              var out = 0;
              var fullCells = Math.floor(valueCount / perLong);
              for (var cell = 0; cell < fullCells; cell++) {
                var value = BigInt.asIntN(64, source[cell]);
                var base = out;
                for (var i = 0; i < perLong; i++) {
                  target[base + i] = Number(BigInt.asIntN(32, value & bitMask)) | 0;
                  value = BigInt.asIntN(64, BigInt.asUintN(64, value) >> BigInt(bitCount));
                }
                out = base + perLong;
              }
              var remaining = valueCount - out;
              if (remaining > 0) {
                var tail = BigInt.asIntN(64, source[fullCells]);
                for (var j = 0; j < remaining; j++) {
                  target[out + j] = Number(BigInt.asIntN(32, tail & bitMask)) | 0;
                  tail = BigInt.asIntN(64, BigInt.asUintN(64, tail) >> BigInt(bitCount));
                }
              }
              return true;
            } catch (e) {
              try {
                var events = window.__gaiusMinecraftEvents || (window.__gaiusMinecraftEvents = []);
                events.push({ event: "bit-storage-hotpath-fallback", detail: String(e && e.message ? e.message : e), at: Date.now() });
                if (events.length > 500) events.splice(0, events.length - 500);
              } catch (ignored) {}
              return false;
            }
            """)
    public static native boolean unpack(
            long[] packed, int[] output, int size, int bits, long mask, int valuesPerLong);
}
