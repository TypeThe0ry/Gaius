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
| `fixtures/<profile>/*.jsonl` | golden data, written by `golden/run-golden.sh` |
| `build-wasm.sh`, `wasm-check.mjs` | release wasm build and its check |
| `wasm-fixture-check.mjs`, `js/fixture-jobs.mjs` | the built wasm against the golden fixtures, jobs encoded by `port/web/kernels/noise-job.js` |
| `golden/strict/` | JVM proof that the `StrictMath263` worldgen rewrite of the TeaVM path keeps vanilla results |
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
./golden/strict/strict-math-check.sh              # StrictMath263 rewrite still dumps the 26.3 fixtures on the JVM
node teavm-fixture-check.mjs [classes.js]         # 26.3 fixtures through a TeaVM client build (./check.sh --teavm)
```

`check.sh` regenerates a profile's fixtures (`golden/run-golden.sh`) when they
are missing or older than the golden harness or the client jar; `--golden`
forces it and `--no-golden` skips it.

The parity test reads `GAIUS_FIXTURES` instead of `fixtures/` when set, and
fails if any profile has no fixtures.

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

`StrictMath263` (in the 26.3 patch chain, `port/tools`) therefore rewrites the
worldgen classes (`levelgen`, `biome`, `data/worldgen`, `valueproviders`,
`Mth`, `RandomSource`) to call `dev.gaius.browser.BrowserStrictMath` after
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
