# Native kernels

Rust replacements for the CPU-heavy world-generation code, compiled to
`wasm32-unknown-unknown` with SIMD128 and run by the kernel worker pool in
`port/web/kernels/`. Every worker owns its own wasm instance and no memory is
shared, so the kernels work on GitHub Pages and from `file://`. Results must
equal vanilla Java bit for bit; parity is checked against golden data dumped
from the real client jars.

## Layout

| Path | Contents |
| --- | --- |
| `crates/gaius-kernel-abi` | `no_std` job framing shared with the JS workers: exports, job/result headers, status codes |
| `crates/gaius-noise` | bit-exact ports of the random sources, `Mth` and `levelgen.synth` for 1.21.11, 26.2 and 26.3 |
| `crates/gaius-noise-wasm` | the `run_noise_points` kernel (cdylib) |
| `crates/gaius-worldgen` | chunk generation from a generator IR exported by the Java side (`src/ir/mod.rs` documents the format): density evaluators for the 26.3 float sampler graph and the 26.2 double router, aquifers, ore veins, surface rules, multi-noise biomes, heightmaps |
| `crates/gaius-worldgen-wasm` | the `load_generator` / `biomes` / `terrain` / `surface` kernels (cdylib); jobs encoded by `port/web/kernels/worldgen-job.js` |
| `crates/gaius-mesher` | the 26.2 / 26.3 `SectionCompiler.compile` over flat section snapshots: model table, block and fluid quads, smooth lighting, tints, `VisGraph`, translucency sort |
| `crates/gaius-mesher-wasm` | the `load_model_table` / `mesh_section` kernels and `mesh_trim` (cdylib); jobs encoded by `port/web/kernels/mesh-job.js` |
| `crates/gaius-light` | sky and block light of one chunk column plus a one-block ring of its neighbours, following the 26.2 / 26.3 light engine |
| `crates/gaius-light-wasm` | the `light_column` kernel and `light_trim` (cdylib); jobs encoded by `port/web/kernels/light-job.js` and by `BrowserLightKernel` on the Java side |
| `crates/gaius-png` | bounded PNG decoding with the current Java STB override's channel, transparency and grayscale semantics |
| `crates/gaius-png-wasm` | the opt-in synchronous `png_decode` kernel; deliberately excluded from the default worker manifest |
| `golden/png/` | offline all-asset PNG byte parity against the current Java decoder and native/Node benchmarks |
| `golden/worldgen/` | vanilla comparison: exports the overworld IR and dumps density, biomes, blocks and heightmaps from the real 26.3 / 26.2 classes, then runs `tests/vanilla_reference.rs` |
| `worldgen-perf-reference.mjs`, `crates/gaius-worldgen-wasm/examples/perf_ir.rs` | manifest-selected offline JVM references and native core/framed stage benchmarks |
| `worldgen-perf.mjs`, `worldgen-perf-paired.mjs`, `worldgen-perf-summary.mjs` | Node baseline/SIMD throughput, interleaved before/after pairs and byte-exact result checks |
| `fixtures/<profile>/*.jsonl` | golden data, written by `golden/run-golden.sh` |
| `build-wasm.sh`, `wasm-check.mjs` | release wasm build of the noise and mesher kernels, and the check that every probe job answers like the native build |
| `build-light-wasm.sh`, `build-worldgen-wasm.sh` | SIMD and baseline builds of the light and worldgen kernels, with their node checks |
| `build-wasm-variants.sh`, `js/kernel-manifest.mjs` | default worker kernels in both builds plus the `kernels.json` manifest the kernel runtime loads |
| `wasm-fixture-check.mjs`, `js/fixture-jobs.mjs` | the built wasm against the golden fixtures, jobs encoded by `port/web/kernels/noise-job.js` |
| `golden/strict/` | JVM proof that the worldgen float rewrite (`WorldgenStrictMath`) of the TeaVM path keeps vanilla results |
| `teavm-fixture-check.mjs`, `js/teavm-*.mjs` | the golden fixtures through the TeaVM-compiled Java noise of a client build |
| `check.sh` | everything above plus the kernel pool smokes, in one command |

## Commands

Run from `port/native/`:

```sh
./check.sh                 # all of the below, the pool smokes, and fixture regeneration when stale
./check.sh --verify        # ... and prove a fresh JVM dump reproduces the fixtures
cargo test --workspace     # unit tests, golden parity for all profiles, kernel framing
./build-wasm.sh            # release wasm (simd128) into target/wasm32-unknown-unknown/release/
./build-wasm.sh --check    # build, then check exports and native == wasm output (needs node)
node wasm-fixture-check.mjs                       # wasm module vs golden fixtures
node ../scripts/native-noise-pool-smoke.mjs       # wasm module through the kernel pool (worker threads)
./golden/strict/strict-math-check.sh              # WorldgenStrictMath rewrite still dumps the 26.3 fixtures on the JVM
./build-worldgen-wasm.sh --check                   # worldgen kernel, simd128 and baseline builds, exports checked
./build-light-wasm.sh --check                      # light kernel, both builds, through ../scripts/light-kernel-smoke.mjs
./build-wasm-variants.sh [--out DIR]               # all kernels, both builds, and kernels.json (default target/kernels)
GAIUS_WORLDGEN_PROFILE=26.2 ./golden/worldgen/run-worldgen-reference.sh   # kernel vs vanilla chunks (default 26.3)
node teavm-fixture-check.mjs [classes.js]         # 26.3 fixtures through a TeaVM client build (./check.sh --teavm)
```

`check.sh` regenerates a profile's fixtures (`golden/run-golden.sh`) when they
are missing or older than the golden harness or the client jar; `--golden`
forces it and `--no-golden` skips it.

PNG is an optional WasmGC startup path, not a default worker kernel. Its build,
staging, runtime switch, fallback rules and offline parity commands are in
[INTEGRATION.md](crates/gaius-png/INTEGRATION.md). Setting `GAIUS_PNG_OUT` to an
absolute output directory also enables the PNG check in `check.sh`.

The parity test reads `GAIUS_FIXTURES` instead of `fixtures/` when set, and
fails if any profile has no fixtures.

## Offline worldgen performance

See [the Chinese throughput report](docs/worldgen-throughput.md) for measured
before/after results, the Java hook coverage map and the remaining acceptance
gates. The tools need only cached jars, Cargo and Node; no client TeaVM build or
browser is required.

Set `OUT` to a dedicated output directory and `PROFILE_ID` to an ID read from
`../versions/*.json`. Keep build artifacts outside shared `port/work`.

```sh
node worldgen-perf-reference.mjs "$OUT/reference"
CARGO_TARGET_DIR="$OUT/native" cargo run --release --offline --locked -j 2 \
  -p gaius-worldgen-wasm --example perf_ir -- \
  "$OUT/reference/$PROFILE_ID" "$OUT/jobs/$PROFILE_ID" 7 6
node worldgen-perf.mjs "$WASM" "$OUT/jobs/$PROFILE_ID" 7 6
node worldgen-perf-paired.mjs "$BEFORE_WASM" "$AFTER_WASM" "$OUT/jobs/$PROFILE_ID" 21 6
```

The reference runner copies harness sources into the output directory and uses
`javac -J-Xmx3g` and `java -Xmx3g`. Unknown profiles and adapter mismatches fail
before a misleading successful measurement can be reported. Build wasm with
`--offline --locked -j 2` and target features `-simd128` / `+simd128` for the
baseline / SIMD variants.

`terrain` includes aquifers and block filling; `combined` uses the existing
surface/biome flags. LOAD, Worker messaging, Java result installation and the
complete chunk status chain are outside these timers. Independent-process
results are susceptible to shared-machine load; use the paired runner to
check a small change without treating offline jobs/s as browser chunks/s.

## Kernel semantics

Kernels never see a Minecraft version. Each version profile declares its
`kernelSemantics` in `port/versions/<id>.json`: the wire `code` that job and IR
headers carry (noise job byte 0, generator IR header, model table header), the
`floorMode` (`cast-adjust` or `math-floor`), the `synth` classes (`f64` or
`f32`), the `densityGraph` (`router-f64` or `sampler-f32`) and the generator
`irVersion`. `crates/gaius-noise/build.rs` compiles those declarations into
`Semantics::from_code` and `semantics::profiles()`, so the decoders accept
exactly the declared codes, the parity tests run every profile with its
declared semantics, and `check.sh`, `golden/run-golden.sh` and
`js/fixture-jobs.mjs` enumerate the same profiles. A new profile whose
semantics match an existing one reuses its code and needs no Rust change; a
declaration the kernels do not implement fails the build.

## Parity rules

- Arithmetic follows the bytecode: operation order, wrapping `int`/`long`,
  logical shifts, saturating float-to-int casts, `Math.min` NaN and signed-zero
  rules, exact `Math.pow(2, n)`. Rust never fuses multiply-adds and the build
  enables no fast-math, so float results match the JVM's strict IEEE 754.
- 1.21.11 `Mth.floor`/`lfloor` cast then adjust; 26.2 and later use
  `Math.floor`. They differ below `Integer.MIN_VALUE`.
- 26.3 rewrote `levelgen.synth`: single-octave float `PerlinNoise`,
  `SmearedPerlinNoise` and `SimplexNoise` over `GradientNoise`, layered by
  `NoiseStack`; `NormalNoise` became a recipe that builds a stack;
  `BlendedNoise` compiles to a sampler tree. These live in `synth32`, the
  older double-precision classes in `synth64`. The 26.3 point path (`get`) and
  volume path (`addToVolume`) are both ported because they round differently.
- 26.3 `NormalNoise` sums amplitudes with `DoubleStream.sum()`, which is
  Kahan-compensated on the JVM; `java::double_stream_sum` reproduces it.
- `nextGaussian` is not ported: it relies on `Math.log`, which the JVM only
  bounds to one ulp. No noise constructor uses it.

## The TeaVM path

The browser also runs the same worldgen as TeaVM-compiled Java, and both
paths must agree. TeaVM compiles `float` to a JS number without rounding and
`(int)`/`(long)` casts without saturation, and its `DoubleStream.sum()` is not
compensated, so a 26.3 client built without help computes float noise in
double precision: `teavm-fixture-check.mjs --report` on such a build shows
6767 of the 7616 noise and `Mth` rows off (up to 32650 float ulps after
the octaves add up), and the normalization factor of 15 of the 146
`NormalNoise` lines off by an ulp.

`WorldgenStrictMath` (patch `StrictMath263.roundWorldgenArithmetic` of the 26.3 chain,
`port/tools`) therefore rewrites the worldgen classes (`levelgen`, `biome`,
`data/worldgen`, `valueproviders`, `Mth`, `RandomSource`) to call `dev.gaius.browser.BrowserStrictMath` after
every float operation (`Math.fround`), for every float conversion and cast, for
floats entering from outside that scope, and for `DoubleStream.sum()`.
`golden/strict/strict-math-check.sh` proves on the JVM that the rewritten
classes still dump the golden fixtures byte for byte;
`teavm-fixture-check.mjs` proves on a rebuilt client that the TeaVM output
matches them bit for bit (it fails on a build from before the rewrite).

## Kernel ABI

A kernel module exports `memory`, `alloc(len)` (0 when memory cannot grow; the
worker then drops the instance instead of writing to address 0), `dealloc(ptr, len)`,
`release(desc)`, `gaius_abi_version()` and one `run_<kind>(ptr, len)` per job
kind. The job buffer is a 16-byte header (magic `GKJB`, ABI version, kind, job
id, payload length) followed by the payload. `run_<kind>` returns a descriptor
`{status, data_ptr, data_len}`: on success the data is a 16-byte result header
(magic `GKRS`) followed by the result payload, otherwise it is a UTF-8 error
message. Details are in `crates/gaius-kernel-abi/src/lib.rs`; the
`noise_points` payload is documented in `crates/gaius-noise-wasm/src/job.rs`.

On the page, `port/web/kernels/noise-job.js` (`GaiusNoiseJob`) frames
`noise_points` jobs and reads their results for `kernel-pool.js`.

Job kinds are defined once in `gaius_kernel_abi::kind`; the high byte names the
kernel family:

| Kind | Export | Kernel |
| --- | --- | --- |
| `0x0101` | `run_noise_points` | noise |
| `0x0201` | `run_load_model_table` | mesher |
| `0x0202` | `run_mesh_section` | mesher |
| `0x0301` | `run_light_column` | light |
| `0x0401` to `0x0404` | `run_load_generator`, `run_biomes`, `run_terrain`, `run_surface` | worldgen |
| `0x0701` | `run_png_decode` | PNG (opt-in, synchronous WasmGC startup path) |

A kernel may also export `trim` or `<name>_trim` (`mesh_trim`, `light_trim`):
under memory pressure the kernel runtime asks every worker to call them, and
they drop the cached tables and scratch buffers of the instance.

The mesher and the light kernel keep a table per instance (the model table, the
light table) and answer a job that names a table they do not hold with "table
missing" instead of an error, so a fresh, respawned or trimmed worker recovers by
getting the same job again with the table attached. `light_column` jobs (job
version 2) carry only the one-block ring of the four neighbours that the column
reads: per neighbour section a palette of the 256 cells touching the column and
one index byte per cell, and per stored light layer the 128 bytes of those cells,
instead of whole sections and 2048-byte layers. The kernel refuses version 1 jobs.
