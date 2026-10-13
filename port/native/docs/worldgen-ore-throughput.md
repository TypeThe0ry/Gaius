# Full-status worldgen and ordered ore experiment

Measurement date: 2026-10-08. Raw evidence: `D:/gaius-next/v040/evidence/wgperf2`.

## Result and scope

The browser full-chunk 2x target is **not achieved or verified**. This change adds a reproducible full-status JVM measurement, a bounded ordinary-ore geometry/ordered-delta ABI, and an independent vanilla oracle. The ore mirror gate remains **off by default**. Production browser ore hooks, TeaVM/JSPI/Worker acceptance and full-chunk correctness promotion are not part of the evidence below.

The largest measured feature class still in Java is SculkPatch, not ordinary ore. Sculk spreading has mutable world/tick context that this snapshot experiment does not model. Carvers include Java aquifer/fluid queries, but their measured exclusive share is small. Ordinary ore was selected as a tractable exact snapshot boundary, not as the largest end-to-end hotspot. Moving only its geometry cannot deliver 2x; target-rule evaluation and RNG remain in Java.

## JVM measurement

`port/scripts/worldgen-status-perf.mjs` derives profiles, hook classes and rule shapes from `port/versions/*.json`, descending by semantics code. It uses the local official named client jar and its cached classpath, starts a headless vanilla server, and requests a row-major 20x20 FULL region for seeds `12345` and `987654321`. Each run has a fresh universe; existing universes are rejected, never deleted. Spawn-search during initial load is disabled equally for all variants. A separate 10x10 FULL region is warmed before the 400 target requests. Required neighbors can therefore exceed 400; all their status work within the request windows is counted. World loading, warmup, final hashing and report serialization are outside generation wall time.

The JVM runs with `-Xmx3g -XX:ActiveProcessorCount=2 -XX:CICompilerCount=2 -XX:+UseSerialGC -Dmax.bg.threads=1`. The ASM agent measures thread CPU and wall time, subtracts same-thread nested calls and records balanced exceptional exits. Process CPU during each FULL request is the denominator. Background JIT/GC, uninstrumented methods and some scheduling work remain explicitly `untracked`. Windows thread CPU accounting has coarse timer resolution: a small or zero bucket does not establish that a status is free. Per-status CPU share is **not** a critical-path wall share.

Plain throughput runs omit the ASM status agent, but retain bounded kernel host counters/timers and the ore dispatcher where enabled. The original instrumented combined runs are retained as diagnostics; `plain-combined-*` uses the final implementation holding section locks through snapshot and installation. These are independent batches on a shared machine, not a paired causal benchmark.

Native runs use the actual Rust DLL through Java FFM and the existing Java patcher/hooks. Wasm runs use the actual modules in a Node child, with synchronous pipe framing. Node child CPU is not included in JVM process CPU: wasm status shares describe **Java-side CPU only**, while generation wall includes the pipe wait. This is not a browser Worker timing substitute. There is no fake kernel implementation.

### Exclusive CPU shares, kernels off

Two seeds x 400 FULL requests per profile, weighted by JVM process CPU:

| Status | 26.3 | 26.2 | 1.21.11 |
| --- | ---: | ---: | ---: |
| structure_starts | 0.87% | 1.36% | 1.64% |
| structure_references | 0.09% | 0.10% | 0.02% |
| biomes | 1.00% | 3.21% | 3.47% |
| noise | 17.84% | 32.62% | 35.71% |
| surface | 13.01% | 7.43% | 8.49% |
| carvers | 1.78% | 2.19% | 1.62% |
| features: ore | 2.65% | 3.10% | 2.94% |
| features: tree | 1.70% | 1.36% | 1.28% |
| features: vegetation | 0.74% | 1.15% | 1.16% |
| features: other | 10.18% | 8.45% | 7.51% |
| initialize_light | 0.22% | 0.03% | 0.07% |
| light | 1.65% | 1.12% | 1.19% |
| spawn | 0.26% | 0.16% | 0.27% |
| FULL finalization | 0.04% | 0.00% | 0.00% |
| result_install | 0.00% | 0.00% | 0.00% |
| untracked | 47.95% | 37.73% | 34.63% |

`result_install` above only represents Rust result decoding/palette/section/surface install methods; the off path has no Rust result. In on runs these methods are nested out of noise/surface and reported separately. General vanilla block writes remain in their enclosing status. Feature categories use actual placed Feature classes; nested placed features are charged exclusively, not double-counted. SculkPatch alone accounts for 4.40% / 3.68% / 3.51%.

`status-summary.json` contains all on/off per-status shares, job counts, top feature classes, provenance and hash differences. `status-shares.csv` provides a flat table for every variant. The summary script rejects incomplete two-seed runs instead of silently dropping a failed seed.

### Exclusive CPU shares, existing hooks on with native Rust

The same two-seed coverage, without experimental ore replacement:

| Status | 26.3 | 26.2 | 1.21.11 (hooks still off) |
| --- | ---: | ---: | ---: |
| structure_starts | 1.04% | 1.15% | 1.40% |
| structure_references | 0.00% | 0.03% | 0.05% |
| biomes | 1.35% | 2.24% | 3.27% |
| noise | 25.28% | 25.59% | 35.91% |
| surface | 0.00% | 8.20% | 9.15% |
| carvers | 1.31% | 1.83% | 2.03% |
| features: ore | 2.78% | 3.24% | 3.05% |
| features: tree | 1.54% | 1.23% | 1.51% |
| features: vegetation | 0.77% | 1.23% | 1.15% |
| features: other | 10.07% | 7.48% | 7.03% |
| initialize_light | 0.15% | 0.03% | 0.05% |
| light | 1.35% | 0.89% | 1.10% |
| spawn | 0.46% | 0.20% | 0.19% |
| FULL finalization | 0.00% | 0.00% | 0.00% |
| result_install | 0.08% | 2.26% | 0.00% |
| untracked | 53.80% | 44.40% | 34.09% |

In the newest profile, surface is inside the combined terrain call, not a separate surface kernel status. These percentages do not establish that replacing noise removes the entire original noise share: hooks still snapshot/install/context-check, and other thread work is outside these buckets.

### Existing Rust coverage

| Profile | Current hook-enabled Rust jobs | Still Java |
| --- | --- | --- |
| 26.3 | 0x0401 generator load, 0x0402 biomes, 0x0403 terrain with surface/material | carvers and their on-demand aquifer context, structures, placed features, lighting, spawn, status coordination, result install |
| 26.2 | 0x0401 generator load, 0x0402 biomes, 0x0403 terrain, 0x0404 surface | same remaining statuses and context/install work |
| 1.21.11 | existing hooks remain disabled; new experimental 0x0405 can run independently | all existing full-chunk stages |

Enablement is read from the actual profile/mirror metadata. An eligible status can still decline; coverage is not inferred solely from the requested backend. Load jobs performed during warmup may not appear in measured job counts because the generator is cached.

## Combined throughput

Final plain runs, 800 requested chunks per cell. Values are chunks/s; parentheses are relative to that profile's plain Java batch:

| Profile | Plain Java | Existing hooks + ore, native | Existing hooks + ore, Node baseline | Existing hooks + ore, Node SIMD |
| --- | ---: | ---: | ---: | ---: |
| 26.3 | 40.774 | 31.934 (0.783x) | 31.513 (0.773x) | 27.767 (0.681x) |
| 26.2 | 29.570 | 23.924 (0.809x) | 17.643 (0.597x) | 18.101 (0.612x) |
| 1.21.11 | 23.125 | 25.535 (1.104x) | 19.691 (0.851x) | 19.029 (0.823x) |

No combined variant reaches 2x. The legacy native batch records 1.104x, but independent-batch variance prevents treating it as a verified causal improvement. Native accepted ore counts were 25,690 / 73,923 / 73,931, with 76,803 / 28,600 / 28,591 vanilla declines. Ordered installation took 171.041 / 222.283 / 149.448 ms in total; this excludes snapshot evaluation, host/queue/encoding and all other status work. Newest-profile air-discard/rule differences admit substantially fewer ores.

The instrumented existing-hook-only baseline/native/Node-baseline/Node-SIMD throughputs were 35.50/32.94/29.54/28.87, 18.89/20.51/17.09/17.72 and 15.97/18.89/21.99/18.78 chunks/s. The legacy profile has **zero existing Rust jobs in all four cells**, so its apparent 1.18-1.38x ratios are shared-machine/JVM/instrumentation variation, not kernel acceleration. This control prevents attributing independent batch differences to code changes. Negative combined ratios are reported honestly, not converted to positive microbenchmark claims.

## Ordered ordinary ore ABI

`gaius-kernel-abi::kind::ORE = 0x0405`, exported as `run_ore` by the worldgen wasm crate. All numeric fields below are little endian, after the existing GKJB header. Result payloads use the normal GKRS header/status/job identity.

| Phase | Immutable request payload | Ordered result payload |
| --- | --- | --- |
| 0: rasterize | u32 phase=0, context=1, sphere count; i32 minimum x/y/z, width, height, min/max build height; count x (f64 x/y/z/radius) | u32 count followed by count x (i32 x/y/z) |
| 1: compact | u32 phase=1, context=1, count; count x (i32 x/y/z/replacement state ID), with -1 meaning skip | u32 count followed by count x (i32 x/y/z, u32 state ID) |

Rust preserves Java containment-pruning arithmetic, floor/clipping, strict sphere tests, x/y/z traversal and first-visit deduplication. It neither reorders writes nor uses reassociation/FMA/fast-math. Phase 1 removes skipped entries in order and rejects duplicate coordinates or invalid negative states. It does **not** claim to port the complete target-rule/RNG implementation: Java builds sphere inputs with the original random draws and Mth sine lookup, evaluates deterministic rules against locked section snapshots, then installs the Rust delta in vanilla order.

Budgets: 1-64 spheres; width/height 1-64; finite positive radius <=64; bounded coordinates; <=262,144 points. Unknown phases/contexts, malformed lengths, unsupported geometry and snapshot-bound overflow decline. Java only admits a standard overworld WorldGenRegion with all relevant chunks already present and no upgrading, retrogen or blending. Custom dimensions, nonzero air-exposure discard, random/unknown target rules and oversized ores use vanilla. Rule allowlists and optional union rule shape come from profile metadata.

The kernel path holds the same BulkSectionAccess locks from snapshot acquisition through ordered delta installation. It does not release/reacquire locks between reading and writing. Geometry decline after random draws invokes vanilla with a bounded replay of exactly those draws. Unsupported contexts are rejected before consuming random state. The independent golden path releases snapshot locks before calling vanilla to avoid a nested section-lock deadlock; only that oracle path captures vanilla visits/writes.

### Independent vanilla golden

The ASM oracle retains the real OreFeature method and observes its original visited-bit and section-write operations. It compares both complete result frames, return value, random draw count and installed state IDs. Existing worldgen hooks are disabled for this oracle. These are official-JVM-produced expectations, not a Java copy of the Rust rasterizer.

| Profile | Target FULL requests | Distinct accepted feature-center chunks | Paired geometry + delta cases |
| --- | ---: | ---: | ---: |
| 26.3 | 400 | 484 | 16,217 |
| 26.2 | 400 | 484 | 41,824 |
| 1.21.11 | 400 | 484 | 41,832 |

All three native fixture replays and all six wasm baseline/SIMD replays passed exact equality. Total: 99,873 paired cases. The extra feature-center chunks are required neighbors; each profile exceeds the 300-chunk coverage gate. Unsupported ores remain vanilla and are counted separately; this is not an all-ore or all-dimension parity claim.

Native FFM host time for geometry/delta was respectively 342.580/174.964 ms, 472.788/245.279 ms and 557.692/316.598 ms in the three oracle runs, using the earlier artifact pinned in each oracle's provenance. It includes host/framing overhead and is not directly comparable to Node compute-only time. Oracle vanilla time includes observation overhead and is **not** a valid uninstrumented vanilla-ore performance denominator. Final release artifacts replay the same independent expectations without mismatch.

| Profile | Node baseline geometry/delta ms | Node SIMD geometry/delta ms |
| --- | ---: | ---: |
| 26.3 | 230.437 / 119.121 | 225.407 / 122.367 |
| 26.2 | 248.318 / 140.161 | 243.510 / 141.284 |
| 1.21.11 | 248.419 / 140.959 | 244.038 / 141.566 |

These sums time only `run_ore`; allocation, input/output copies, snapshot rules, JVM/Node IPC and installation are excluded. Each variant ran in an independent batch; the columns do not prove an intrinsic SIMD benefit. The new ore loops themselves are scalar; the SIMD build also includes the pre-existing worldgen module's SIMD-capable paths.

Final artifact SHA-256 pins (also in `release-validation-artifacts-after.json`): native DLL `e2bf54d1dd588e46b4870bec5cadbe0764701b316b9ce2c7d91bd8f7ef157521`; baseline wasm `0806e378c2601a5bc3927f43dd317748f6af869205963e24be473653753d6d28` (424,473 bytes); SIMD wasm `8552b50496d4df5c444538610951207aaad25f25a188a41aca6451e80820b2de` (424,243 bytes). `ore-release-final-*` reports pin both fixture and module hashes. Earlier plain evidence is preserved under `pre-rebuild-plain-*`; it is not mixed into final combined throughput.

### Mirror and default-off policy

The ore kernel has a conservative watch set of 210 exact vanilla method identities across the feature/configuration hierarchy, supported rule classes and Mth, plus a dependency on the existing worldgen mirror. Profile locks pin bytecode and descriptors. BODY_CHANGED, SIGNATURE_CHANGED, METHOD_GONE and dependency changes disable ore; tests prove that an ore-only change does not disable unrelated worldgen. Every profile keeps `kernelMirror.ore = false` and emits `PROFILE_DEFAULT_OFF`.

`worldgen-ore-mirror.mjs` inventories the local jars and writes an initial additive TOML patch under the selected output directory. It never edits the mirror registry itself. Once the registry contains ore, review the inventory instead of blindly reapplying that initial patch. The JVM experiment permits only the intentional default-off reason, never a changed-body/signature/missing-method gate.

## Correctness and Amdahl limits

The deterministic correctness follow-up on 2026-10-08 supersedes the historical hash comparison below. A FEATURES-before-FULL barrier proves OFF/OFF zero on both seeds for all profiles. Passing complete stored biome context fixes the surface mismatch; multi-noise BIOMES requests deliberately stay on vanilla to preserve thread-local RTree history. Worldgen is approved again on the two hooked profiles only after native and baseline-wasm 2-seed x 400-chunk matrices report zero block and biome differences. The independent ordinary-ore family remains default-off. Root causes, strict regression goldens, coverage and remaining limits are documented in [worldgen-correctness.md](worldgen-correctness.md).

The historical throughput harness took whole-chunk hashes after all 400 requested chunks finished, without first imposing the FEATURES barrier across their neighbors. Changed block-hash chunks for seeds 12345 / 987654321 were 5/179, 49/5 and 0/1 for the three profiles. Biome aggregates matched; OFF/OFF repeats differed by 0/0, 0/2 and 0/1. Those nondeterministic figures cannot certify kernel parity. Under the corrected protocol, the pre-fix sampler terrain/all probes isolate one chunk with 35 final block differences, beginning at (51,90,-148), vanilla dirt versus kernel stone; the repaired matrices match. An ore golden still does not certify arbitrary existing hooks or asynchronous browser completion.

The final plain combined native/baseline/SIMD runs differ from their final plain control by 4/57, 4/54 and 4/50 chunks on the newest profile, and 7/3, 28/67 and 2/34 on the middle profile. All six legacy combined samples match their plain control's complete block and biome hashes. This isolates a successful ore-only full-chunk result for those legacy samples, not correctness of the existing hooks enabled on the other profiles or a browser acceptance pass.

Using the measured JVM CPU shares as a hypothetical browser-share proxy, and pretending currently covered biomes/noise/surface plus all ordinary-ore cost become free, the Amdahl estimates are about 1.53x / 1.86x / 1.03x. Making only all ore free gives roughly 1.03x. These are optimistic **CPU-proxy estimates**, not strict browser wall-time bounds: actual browser shares, declines, JSPI/GC, snapshots, transport and installation are unknown. The new kernel covers less than the entire ore bucket. A 2x browser claim needs an actual >=50-75% accelerated critical-path share and fresh correctness evidence.

The next useful work is finer accounting of untracked CPU/status coordination and Sculk/mutable-feature context, exact biome-history handoff if that acceleration is restored, and a production default-off ore seam with cancellation/identity/lock-equivalence tests. Browser acceptance is still required. No full TeaVM build, Chrome run, download or JavaScript-target output change was performed.

## Reproduction

PowerShell from the repository root; use a new output subdirectory for reruns. All artifacts and helper classes stay outside the shared `port/work` junction. Profiles are selected from metadata, not hard-coded in scripts.

```powershell
$output = 'D:/gaius-next/v040/evidence/wgperf2'
$env:GAIUS_JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot'
$env:CARGO_TARGET_DIR = "$output/cargo-native"
$env:CARGO_BUILD_JOBS = '2'
Push-Location port/native
cargo build --offline --locked -j 2 --release -p gaius-worldgen-wasm --features native-host
cargo test --offline --locked -j 2 -p gaius-worldgen -p gaius-worldgen-wasm -p gaius-kernel-abi --features gaius-worldgen-wasm/native-host
cargo fmt --all -- --check
cargo clippy --offline --locked -j 2 -p gaius-worldgen -p gaius-worldgen-wasm -p gaius-kernel-abi --all-targets --features gaius-worldgen-wasm/native-host -- -D warnings
Pop-Location
$dll = "$output/cargo-native/release/gaius_worldgen_wasm.dll"
$env:CARGO_TARGET_DIR = "$output/cargo-wasm"
bash port/native/build-worldgen-wasm.sh --check
$baseline = "$output/cargo-wasm/wasm-worldgen/gaius_worldgen_wasm.baseline.wasm"
$simd = "$output/cargo-wasm/wasm-worldgen/gaius_worldgen_wasm.simd.wasm"
node port/scripts/worldgen-status-perf.mjs "$output/rerun-off" '' 12345,987654321 20 off
node port/scripts/worldgen-status-perf.mjs "$output/rerun-native" '' 12345,987654321 20 native $dll
node port/scripts/worldgen-status-perf.mjs "$output/rerun-baseline" '' 12345,987654321 20 baseline $baseline
node port/scripts/worldgen-status-perf.mjs "$output/rerun-simd" '' 12345,987654321 20 simd $simd
node port/scripts/worldgen-status-perf.mjs "$output/rerun-plain-off" '' 12345,987654321 20 off '' plain
node port/scripts/worldgen-status-perf.mjs "$output/rerun-plain-native" '' 12345,987654321 20 native $dll ore-kernel-plain
node port/scripts/worldgen-status-perf.mjs "$output/rerun-plain-baseline" '' 12345,987654321 20 baseline $baseline ore-kernel-plain
node port/scripts/worldgen-status-perf.mjs "$output/rerun-plain-simd" '' 12345,987654321 20 simd $simd ore-kernel-plain
$env:GAIUS_PERF_WARMUP_SIDE = '0'
node port/scripts/worldgen-status-perf.mjs "$output/rerun-ore-golden" '' 12345 20 native $dll ore-golden
Remove-Item Env:GAIUS_PERF_WARMUP_SIDE
$profiles = Get-ChildItem port/versions -Filter '*.json' | ForEach-Object { Get-Content $_.FullName -Raw | ConvertFrom-Json } | Where-Object worldgenReference
foreach ($profile in $profiles) {
    $fixture = "$output/rerun-ore-golden/$($profile.id)/ore-12345.bin"
    $env:GAIUS_ORE_GOLDEN = $fixture
    $env:CARGO_TARGET_DIR = "$output/cargo-native"
    Push-Location port/native
    cargo test --offline --locked -j 2 -p gaius-worldgen-wasm --test ore_golden -- --nocapture
    Pop-Location
    node port/scripts/worldgen-ore-wasm-golden.mjs $baseline $fixture "$output/rerun-ore-baseline-$($profile.id).json"
    node port/scripts/worldgen-ore-wasm-golden.mjs $simd $fixture "$output/rerun-ore-simd-$($profile.id).json"
}
Remove-Item Env:GAIUS_ORE_GOLDEN
$env:KERNELMIRROR_TEST_DIR = "$output/mirror-tests"
node port/tools/kernelmirror/test-kernelmirror.mjs
node port/tools/kernelmirror/kernelmirror.mjs generate --check
node port/tools/kernelmirror/kernelmirror.mjs gate --check
node port/tools/profilegen/profilegen.mjs --check
node port/scripts/worldgen-status-profiler-check.mjs "$output/final-control-repeat/$($profiles[0].id)" "$output/profiler-recheck"
node port/scripts/worldgen-status-summary.mjs $output
```

The summary reads the retained `final-*`, `combined-*` and `plain-*` evidence directories. It does not automatically include arbitrary rerun names. Without `GAIUS_ORE_GOLDEN`, the Rust test explicitly skips external parity; that empty invocation is not the golden evidence. Build tools use only locally cached dependencies and retain normal native linker environment requirements.
