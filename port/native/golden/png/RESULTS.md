# PNG parity and throughput results

## Complete local corpus

The checked asset set comes from each configured client-named.jar and every PNG named by
its configured official asset index. The shared port/work object roots contain seven PNG
objects per profile; the remaining 25 were found by exact SHA-1 paths in the existing local
Minecraft objects cache. All 32 indexed PNG objects per profile passed size and SHA-1 checks.
No downloads were used. Profile IDs and index IDs are read from port/versions/*.json.

Reference: the current browser STBImage Java override on a stock JVM, with a test-only heap
MemoryUtil shim. Both native Rust and the real JS adapter plus wasm matched every output byte
and all width/height/source/output channel fields for desired channels 0 through 4.

| Profile | Jar PNGs | Indexed PNGs | Total PNGs | Encoded bytes | Channel cases | Java ms/round | Native ms/round | Node wasm ms/round |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1.21.11 | 3518 | 32 | 3550 | 14,283,592 | 17,750 | 289.24 | 163.08 | 215.52 |
| 26.2 | 3856 | 32 | 3888 | 14,223,410 | 19,440 | 287.17 | 169.94 | 220.47 |
| 26.3 | 3965 | 32 | 3997 | 15,778,736 | 19,985 | 319.24 | 187.70 | 242.99 |

Total: 11,435 logical PNG entries, 44,285,738 encoded bytes and 57,175 channel cases.
There are zero missing indexed PNG objects and zero byte mismatches or vanilla kernel declines.
Duplicate hashes reduce stored fixture size, not the number of checked logical entries.

75 synthetic images add 375 Java/native/wasm cases: every legal color/depth combination,
each PNG filter, split IDAT, full-depth tRNS, partial palette alpha and ignored gamma.
Total oracle cases: 57,550. Java materialization and vertical flip were also checked at
nonzero input/output metadata positions. Native tests cover Adam7 and tiny pass dimensions;
no vanilla corpus entry is interlaced, and the current Java decoder rejects Adam7, so the
browser adapter declines Adam7 rather than changing Java admission semantics.

## Measurement boundary

Numbers are means over 15 measured rounds after two warmup rounds, desired channels 4,
preloaded inputs and reference outputs. File I/O, module compilation and parity work are
outside the measured interval. Native includes framed payload decoding; Node includes the
actual browser adapter, wasm allocation, framing, input copying, result copying and release.
Java includes the existing override allocation, inflate, scanline/filter and channel path.

| Profile | Java MiB/s | Native MiB/s | Node wasm MiB/s | Native speedup | Node wasm speedup |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1.21.11 | 47.10 | 83.53 | 63.21 | 1.77x | 1.34x |
| 26.2 | 47.24 | 79.82 | 61.52 | 1.69x | 1.30x |
| 26.3 | 47.14 | 80.17 | 61.93 | 1.70x | 1.31x |

These are decode-throughput measurements, NOT a measured TeaVM startup improvement. The JVM
uses JDK Inflater, while the browser uses its TeaVM class-library implementation. Node does
not include the managed Java/JsArrays bridge copies or texture upload. All vanilla PNGs were
used as the throughput corpus; this does not assert that every asset is decoded at startup.
The shared machine can introduce run-to-run variation. A rebuilt WasmGC client A/B remains
the performance and renderer acceptance gate; full TeaVM and Chrome were not run.

Environment: Windows, Node v24.19.0, Rust 1.98.0, Temurin OpenJDK 25.0.4.1, Python 3.12.7.
Java ran with -Xmx3g, javac with -J-Xmx3g, and cargo builds were offline, locked and limited
to four jobs. The release wasm module is 138,626 bytes.

STBImage source SHA-256: 716df63f56cb2dfd76651418f674e11cc5da378b2e4ebc638cd833195de3c148

Wasm SHA-256: 4cbdb291dca52eadeac17fd46a907b396af0ffb9c3518e3848077abcac3eda4d

## Reproduce and supporting checks

Run from the repository root; all scratch/build products stay in the stated output directory.

    $env:CARGO_TARGET_DIR = "D:/gaius-next/v040/evidence/pngdec/target-native"
    cargo fmt --manifest-path port/native/Cargo.toml --all --check
    cargo clippy --manifest-path port/native/Cargo.toml --offline --locked -j 4 --workspace --all-targets -- -D warnings
    cargo test --manifest-path port/native/Cargo.toml --offline --locked -j 4 --workspace
    python port/native/golden/png/check.py --out D:/gaius-next/v040/evidence/pngdec --objects C:/Users/admin/AppData/Roaming/.minecraft/assets/objects --rounds 15 --require-complete-index

The full command built native and wasm offline and passed the complete corpus. The final
source/adapter verification repeated it with --skip-build because the Rust artifact did not
change. Both runs required complete index coverage. results.json contains the final timings;
catalog.json retains logical asset names, layouts and jar/index/input hashes; logs retain
each command, stdout/stderr and exit status. Vanilla input/reference binaries stay outside Git.

Additional verified checks:

- Workspace Rust tests: 112 passed, zero failed or ignored; fmt and clippy -D warnings passed.
- test-stage-web-runtime.py: all six tests passed, including WasmGC-only optional PNG staging.
- wasmgc_rules.py source --copy-rule enforce: all source rules passed; the bounded startup
  bridge copy has a method-specific, non-per-frame exception.
- runtime-check.mjs: real wasm ownership/framing, corrupt input/results, growth, 1,000 repeated
  releases, throwing cleanup, off/no-fetch and boot failure/fallback checks passed in Node.
- The actual WasmGC BrowserPng, PngPixels, STBImage and JsArrays sources compiled with cached
  TeaVM 0.15.0 APIs and --release 21. This is API/source validation, not a TeaVM or browser run.
- All 20 JavaScript-target staged runtime files remain byte-identical to the baseline.
  JavaScript-target STB instructions, offsets and resolved references remain identical after
  constant-pool numbering normalization; javac removes the bridge call via AVAILABLE=false.
- Default worker kernel discovery excludes the opt-in PNG module. No JavaScript-target build
  outputs were changed. JS syntax, shell syntax and git diff whitespace checks passed.

Enablement, synchronous execution limits and fallback are specified in
[INTEGRATION.md](../../crates/gaius-png/INTEGRATION.md).
