# PNG kernel integration

## Reference contract

The browser does not call native stb_image, canvas, ImageBitmap, or a JavaScript PNG library.
NativeImage calls the library override at
port/overrides/libraries/lwjgl-stb/src/main/java/org/lwjgl/stb/STBImage.java.
That source copies the compressed bytes, concatenates IDAT, uses java.util.zip.Inflater,
unfilters scanlines, expands to RGBA, then converts the requested channels into MemoryUtil
storage. This implementation, rather than a different decoder's interpretation, is the oracle.

The kernel preserves these observable rules:

- Desired channels 0 selects the source component count; 1, 2, 3, 4 explicitly select output channels.
- Grayscale is (299 * red + 587 * green + 114 * blue + 500) / 1000, rounded down after the addition.
- Source components are grayscale=1, RGB=3, grayscale-alpha=2, RGBA=4, and palette=3 or 4
  depending on tRNS presence. Grayscale/RGB tRNS does NOT increase the reported component count.
  Thus desired=0 drops their transparency, while desired=2/4 keeps it.
- Packed 1/2/4-bit samples expand to the full byte range; 16-bit samples use their high byte.
  tRNS compares the original full sample before 16-bit reduction. Missing palette alpha is 255.
- Gamma, sRGB, text and ICC information do not color-transform the bytes. Alpha is not premultiplied.
- Java owns vertical flipping, output allocation, input/buffer-position semantics and metadata writes.

The Rust decoder uses the offline-resolvable png crate with EXPAND | STRIP_16. It also decodes
Adam7 and tests every legal color/depth combination with every filter. The current Java path
rejects Adam7: the browser adapter deliberately declines interlaced input, preserving that
behavior instead of silently admitting images that the reference rejects. Standalone Rust
supports Adam7 but makes no claim of a Java oracle result for those rejected inputs.

## ABI and execution

gaius-kernel-abi defines PNG_FAMILY=0x0700 and PNG_DECODE=0x0701. The common ABI version 1
GKJB header, run descriptor and GKRS result header are unchanged. All payload integers are
little-endian:

| Job payload | Result payload |
| --- | --- |
| u32 desired_channels, then encoded PNG bytes | u32 width, height, source_channels, output_channels, then row-major pixels |

The module exports memory, alloc, dealloc, release, gaius_abi_version and run_png_decode.
Job and result allocations have separate ownership: the caller deallocates the input and releases
the descriptor after copying the output. Views are refreshed after memory growth. No pointer into
kernel memory is returned to Java or retained after release.

NativeImage.read calls STBImage.stbi_load_from_memory synchronously. This opt-in integration runs one Rust wasm instance synchronously
in the client's JavaScript realm, not in the asynchronous Worker pool: it requires no new JSPI
suspension or synchronous Worker wait. It can block that realm during a decode, just as the old
Java path does. Workers can reuse the same framed module, but Worker offload is not part of this
change. The current conservative Java bridge copies managed arrays in both directions; Node
kernel benchmarks include framing, allocation and JS result copying, not TeaVM managed-array
copies or texture upload. Actual client A/B measurements are still necessary.

## Build and stage offline

Use an output directory outside the worktree/shared port/work junction. For example, in PowerShell:

    $env:CARGO_TARGET_DIR = 'D:/gaius-next/v040/evidence/pngdec/target'
    cargo build --manifest-path port/native/Cargo.toml --offline --locked -j 4 --release -p gaius-png-wasm
    cargo build --manifest-path port/native/Cargo.toml --offline --locked -j 4 --release --target wasm32-unknown-unknown -p gaius-png-wasm --lib

The resulting module is target/wasm32-unknown-unknown/release/gaius_png_wasm.wasm under
CARGO_TARGET_DIR. For an existing WebAssembly GC distribution, stage the optional module:

    python port/scripts/stage-web-runtime.py <wasmgc-dist> --wasm --png <absolute-module-path>

The WasmGC client must first be compiled with this library override. Staging an optional module
beside an older classes.wasm cannot add the Java hook; kernel.ready can be true while attempts
remain zero. No full client rebuild was performed in this task.

Staging puts png-kernel.js and gaius-png.wasm next to gaius-wasm-boot.js. Do not point staging at
port/work. --no-wasm never adds PNG resources; --png with --no-wasm is rejected. Library source
selection already overlays src/target-wasmgc/java over src/main/java. JavaScript-target BrowserPng
is a null-returning stub without JSBody imports; its AVAILABLE constant is false, so javac drops
the bridge branch entirely. The WasmGC overlay sets the same constant true. No JavaScript build
artifact is modified here, and the off-target decoder instructions retain their original behavior.
No full TeaVM build is required for the golden or benchmarks.

## Runtime A/B switch (default off)

Use ?runtime=wasm&gaiusPng=1 in the page URL, or set window.__gaiusPngKernelEnabled=true before
the WasmGC boot script. Without either opt-in, boot neither fetches nor instantiates the PNG
module and the old decoder handles every image. gaiusPng=0 is off. The JavaScript runtime never
loads this integration, even when that query parameter is present.

Optional pre-boot inputs are window.__gaiusPngScriptUrl, window.__gaiusPngWasmUrl,
window.__gaiusPngWasmBytes (Uint8Array/ArrayBuffer), or window.__gaiusPngWasmModule (compiled
WebAssembly.Module). For file:// or an embedded portable page, supply the script inline or by
an accessible script URL and supply embedded wasm bytes/module; a plain file:// fetch may fail.
Existing portable packaging does not automatically embed the optional PNG module.

Opt-in loading runs in the background, outside the required client boot barrier.
While it is pending, STBImage immediately uses Java. Initialization has a five-second
deadline covering fetch, body reading and instantiation; timeout aborts the fetch and
keeps the Java fallback. Late results cannot activate a timed-out instance.
`window.__gaiusPngReady` observes optional readiness without delaying client readiness.
Disabled boot creates no PNG script, fetch, instantiation, abort controller or timer.

After the script is present, manual initialization and toggling are also possible:

    GaiusPngKernel.setEnabled(true);
    await GaiusPngKernel.initialize({ url: 'gaius-png.wasm' });
    GaiusPngKernel.setEnabled(false);

Read GaiusPngKernel.metrics (attempts, successes, declines, failures, bytes, totalMs), ready and
lastError. Metrics measure only the synchronous JS/kernel section, not the Java bridge. Set
the switch before main to compare startup; toggling afterward is useful for resource reloads.

## Decline and fallback

Absent, disabled, still-loading or failed modules return null to STBImage, which executes its
original decodePng/writePixels path. Initialization failures do not reject client readiness.
Non-PNG input, interlace in the browser adapter, invalid desired channels, malformed streams,
stricter CRC checks, dimensions above 16384 or decoded RGBA above 64 MiB decline. Encoded input
also has a 64 MiB cap. These are admission limits, not a change to the fallback's limits.
BadPayload/OutOfMemory/framing errors never escape as successful pixels. A corrupt result or
trap disables the module until explicit reinitialization; normal rejected input does not.
Failure-reason semantics stay in STBImage: a successful fast decode clears the reason, and a
fallback failure reports the original Java error. Original asset files remain immutable.

## Golden equality and benchmarks

The harness discovers profile IDs and asset index IDs solely from port/versions/*.json. It reads
every PNG entry of each client-named.jar and every .png object named by its asset index. Use
--objects for other existing local objects roots; each object is checked against index size/SHA-1.
No discovery, build or test downloads assets. Missing entries are named explicitly in the report;
--require-complete-index fails rather than pretending partial coverage is complete.

    python port/native/golden/png/check.py --out D:/gaius-next/v040/evidence/pngdec --objects C:/Users/admin/AppData/Roaming/.minecraft/assets/objects --rounds 15 --require-complete-index

The JVM compiles the actual STBImage source, the off stub and PngPixels, with a test-only
MemoryUtil heap-ByteBuffer shim. It uses java -Xmx3g and javac -J-Xmx3g. Each logical asset is
compared at all five desired-channel values, including width/height/component counts, not merely
hashes. Java output materialization is additionally compared with the reference for both flip
states and nonzero buffer positions. Shared hashes deduplicate stored bytes, not logical coverage.

75 synthetic PNGs add 375 independent Java/native/wasm comparisons covering all 15 legal
color/depth combinations, all five filters, split IDAT, palette alpha, full-depth transparency and
ignored gamma. Rust tests additionally cover Adam7/tiny dimensions, grayscale rounding, truncated
and corrupt data. runtime-check.mjs tests the real wasm allocator/framing, fail-closed results,
memory growth, repeated releases, off/no-fetch and boot fallback. Corpus and outputs are written
only into --out, never committed as vanilla texture fixtures.

catalog.json records logical names, layouts and SHA-256 provenance; results.json and logs record
commands and results. benchmark.tsv uses desired=4, two warmup rounds and the requested measured
rounds. Timers exclude file I/O and module loading. Java measures the current source on a stock JVM
with JDK Inflater, native measures the framed Rust handler, Node measures the actual browser adapter
with its wasm framing/copies. The JVM is a reference implementation timing, NOT a TeaVM startup
measurement, and scanning every vanilla PNG is a throughput corpus, NOT an assertion that every
asset is decoded at startup. Shared-machine timings should not be treated as a fixed release gate.

Targeted checks:

    cargo fmt --manifest-path port/native/Cargo.toml --all --check
    cargo clippy --manifest-path port/native/Cargo.toml --offline --locked -j 4 -p gaius-png -p gaius-png-wasm --all-targets -- -D warnings
    cargo test --manifest-path port/native/Cargo.toml --offline --locked -j 4 -p gaius-png -p gaius-png-wasm

The existing port/native/check.sh also runs the standalone PNG golden when GAIUS_PNG_OUT is set.
For extra object roots and strict complete-index coverage use the explicit check.py command above.
Full TeaVM/browser acceptance was intentionally not run under the resumed task's load limits.
Measured corpus counts, timings, module hash and the verification boundary are recorded in
[RESULTS.md](../../golden/png/RESULTS.md).
