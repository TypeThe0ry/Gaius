# Wasm GC memory budget

## Scope and acceptance status

This change targets the Wasm GC client without changing kernel or vanilla data
semantics. It adds bounded allocation reuse, owner accounting, smaller idle
staging and CPU-shadow budgets, and an automatic low-memory default tier.
JavaScript-target build outputs are unchanged. Its allocator receives only a
no-op owner-tag bridge; its existing pool and shadow budgets remain unchanged.

The local measurement on 2026-10-08 did **not** reach the approximately 450 MiB
in-world target. The client measured 606.077 MiB before and 601.017 MiB after
quality/shadow-policy injection and explicit GC. These are old-binary injection
results, not measurements of the new compiled allocator. Full TeaVM builds were
excluded from this task, and no distribution was rebuilt or modified.

## Accounting contract

The comparison metric is `Runtime.getHeapUsage.usedSize` plus the main client's
`__gaiusWasmMemory.buffer.byteLength`. All numbers here use MiB (1,048,576 bytes).
This is not renderer RSS, GPU residency, browser-wide memory, or a whole-device
budget. CDP backing-storage and embedder-heap fields are recorded separately and
must not be blindly added to the metric. Server and kernel workers are separate
CDP sessions; unknown worker linear-memory coverage is `null`, never zero.

`__gaiusMemoryTelemetry.browserMemory` retains the existing counters and adds:

- `reservedBytes`, `peakReservedBytes`: allocation capacities retained by
  BrowserMemory, including idle free-list blocks, excluding allocator headers
  and alignment padding. A moving native realloc contributes a conservative
  old-plus-new reservation bound, not a sampled physical peak.
- `pooledBytes`, `poolLimitBytes`, `reuseHits`, `inPlaceReallocations`.
- `owners`: fixed entries `native`, `heap-buffer-copy`, `aligned`,
  `decoded-image`, and `mapped-staging`, each with `liveBytes` and `peakBytes`.

Owner live-byte totals equal logical live bytes. Peaks are per-owner high-water
marks and are not simultaneous or additive. STB pixel output and GL mapped
staging are explicitly tagged; all remaining native callers, including any
untagged kernel-result or mesh buffers, stay in `native`. This is allocation
accounting, not a call-stack profiler or a retained-object dominator analysis.
Aligned allocations retain their allocator identity when callers attempt to
retag them, so the native realloc path cannot lose their alignment constraint.

`__gaiusMemoryTelemetry.linearMemory` records initial/current/peak bytes and a
growth count. Its history is bounded to the first 32 transitions, observed at
the loader import/resize callbacks. Injection into an older dist uses a 100 ms
poll, which can miss intermediate transitions.

## Allocation and retention policy

The Wasm GC allocator uses 16-byte through 64-KiB power-of-two free lists, eight
slots per class, with a configurable ceiling of 2 MiB. Per-class slots make the
actual maximum smaller than that ceiling. Large allocations trim idle blocks
first. Reallocation within a suitably sized reservation avoids a copy; ordinary
large allocations use TeaVM `Heap.realloc` and correct moved-base alignment.
Aligned reallocations retain the previous allocate/copy/free fallback. Failure
leaves the original allocation usable. Released CPU shadows remain recoverable
through the existing byte-exact GPU readback/copy paths; required shadows retain
priority over optional, reconstructible shadows.

Wasm GL mapped staging now retains at most 4 MiB in the low-memory tier and
8 MiB otherwise, instead of the legacy 32 MiB. Counters publish on both acquire
and release-to-pool. The aggregate CPU buffer-shadow policy is capped at 16 MiB
for low-memory and 32 MiB otherwise, further limited by the existing GPU policy.
These are idle-retention/shadow caps, not caps on all active vertex buffers or
all native allocations. Kernel framing, result contents and fallback decisions
are unchanged; kernel and shared-index caches are not newly pooled here.

No smaller initial memory or alternative growth policy is implemented. The
measured wasm imports `env.memory` with a minimum of 558 pages (34.875 MiB),
already much smaller than its 316.8 MiB startup high-water mark. Reducing an
imported memory below its declared minimum would prevent instantiation.
Releasing allocations cannot shrink a grown `WebAssembly.Memory`.

## Low-memory defaults and switches

Only an actually selected Wasm runtime is eligible. A positive
`navigator.deviceMemory <= 4`, or a positive
`performance.memory.jsHeapSizeLimit <= 1 GiB`, selects the low tier. Unknown
values retain GPU-tier selection. An explicit JavaScript fallback wins over a
stale `runtime=wasm` query. This is a coarse device hint, not available-RAM
measurement or a guarantee of physical 4-GB-device performance.

Fresh vanilla options use the existing low preset: render distance 6,
simulation distance 4, mipmaps 2, decreased particles and fast graphics.
Quality defaults use DPR 1, native world render scale, post-processing off and
OIT off. Existing saved vanilla options are not rewritten; persisted quality
settings and existing explicit visual overrides retain their normal precedence.

`__gaiusMemoryTier` and `__gaiusQualitySettings.memoryTier` expose `chosenTier`,
`gpuTier`, `reason`, `deviceMemoryGiB`, `heapLimitBytes`, `lowMemory`, and the
requested `vanillaDefaults`. These defaults are not a claim that saved options
have those values; the CDP probe separately records actual client distances.

| Switch | Effect |
| --- | --- |
| `gaiusMemoryTier=off` or `0` | Retain GPU-tier defaults, bypass automatic memory selection |
| `gaiusMemoryTier=low` | Force low defaults for a Wasm runtime |
| `gaiusMemoryReuse=0` | Disable new free-list and native/in-place realloc paths |
| `gaiusMappedPoolMB=0..32` | Override idle staging cap; 32 restores legacy retention |
| `gaiusShadowBudgetMB=off` | Restore the existing GPU-derived shadow budget |
| `gaiusShadowBudgetMB=0..64` | Set a bounded aggregate shadow cap, including zero |
| `gaiusPost`, `gaiusOit`, `gaiusMaxDpr`, `gaiusRenderScale` | Existing visual overrides |

The Java property `gaius.browser.memory.poolBytes` sets a 0..2-MiB pool ceiling.
The reuse URL switch is the complete allocator comparison switch; visual policy
can be reverted separately with the tier/shadow switches.

## Local evidence

`out3/train-T3` had no runnable wasm output when inspected. The newest runnable
artifact was `D:/gaius-next/v040/m1/out2/train-T2/dist/wasm-26.3-r2`.
Its client `classes.wasm` was 62,003,661 bytes, SHA-256
`86f36fb2e2ed251a50afd1dd54dd3fab2d9bdb71006d41e1b6e6389653be2834`.
The singleplayer server in that artifact is a JS worker, not a Wasm server.
Evidence is under `D:/gaius-next/v040/evidence/mem`, including full artifact
hashes, `comparison.json`, per-run CDP samples, worker samples and screenshots.

| Main client | Baseline GC | Low-memory injection GC |
| --- | ---: | ---: |
| Run duration, seconds | 91 | 55 |
| Title-screen metric, MiB | 584.619 | 557.629 |
| World-entry metric, MiB | 605.701 | 614.764 |
| Six steady samples: mean, MiB | 612.769 | 614.622 |
| Steady sample range, MiB | 604.250..624.438 | 602.243..623.861 |
| After explicit GC, MiB | 606.077 | 601.017 |
| CDP used heap after GC, MiB | 289.202 | 284.205 |
| Linear memory, MiB | 316.875 | 316.813 |
| BrowserMemory live / peak, MiB | 215.873 / 280.283 | 215.353 / 280.235 |
| CPU GL shadows, MiB | 6.097 | 5.417 |
| Idle mapped staging, MiB | 0.996 | 0.496 |
| Shared quad indices, MiB | 3.433 | 3.433 |
| Baked CPU indices, MiB | 0.866 | 0.592 |
| Actual render / simulation distance | 12 / 6 | 6 / 4 |

The post-GC difference is -5.060 MiB, but the steady mean is +1.853 MiB.
The GPU benchmark chose ultra and mid respectively, and the short runs have
different durations. This is preliminary local evidence, not a controlled
allocator A/B or a statistically reliable overall memory saving. Both runs
entered a world with a player; screenshots show terrain, trees, HUD and hand.
Desktop device-memory overrides test policy selection, not actual 4-GB hardware.
The approximately 391/526-MB historical figures were not reproduced here.

The original baseline includes a 1,046,212,257-byte streamed V8 snapshot with
12,475,872 nodes. Self-size totals are 1,092,789,992 bytes; notable buckets are
`object` 259,855,500 bytes, `array` 8,610,240 bytes, and `native` 812,470,664 bytes.
Largest labels include `JSArrayBufferData` 674,818,148 bytes, nine
`WasmNativeModule` nodes totaling 119,746,949 bytes, and Wasm GC canonical-type
objects. Native self sizes are not additive physical RAM or Java owner names.
The parser does not resolve retained ownership or map `$canon` types to classes.

The measured dist predates owner counters. Therefore its 215.873-MiB native
live bytes cannot honestly be subdivided into the new five owner buckets.
The difference between linear size and native live bytes (101.002 MiB in the
baseline GC run) includes static memory, allocator metadata, free space and
unattributed contents, not one named owner. GL caches may overlap native or
snapshot backing-store accounting; do not add them again. The low-GC server
worker separately reports 309,202,212 used-heap bytes; neither that heap nor
kernel-worker memory is included in the main-client comparison.

Startup growth is already complete before the title sample: baseline polling
observed 34.875 to 263.750 MiB at 6.30 s, then 316.875 MiB by 49.61 s; the low
run reached 316.813 MiB at 14.18 s. Avoiding startup native-allocation overlap
must therefore precede title/world-entry quality improvements.
Holding the low run's heap and native live allocations fixed, even zero linear
overhead would total 499.557 MiB. Reuse and idle-cache trimming alone therefore
cannot promise 450 MiB; actual live allocation or GC-object residency must also
fall while preserving the required vanilla data.

## Reproduction and checks

Run every real Chrome measurement through the machine's Chrome slot. No network
downloads or full TeaVM builds are required by these probes. In PowerShell:

```powershell
$env:DIST='D:/gaius-next/v040/m1/out2/train-T2/dist/wasm-26.3-r2'
$env:PROFILE='26.3'
$env:MEMORY_QUERY='runtime=wasm'
$env:HEAP_SNAPSHOT='0'
$env:MEMORY_DEVICE_GIB='8'; $env:MEMORY_BASELINE='1'
node port/scripts/memory-budget-inject.mjs D:/gaius-next/v040/evidence/mem/baseline-gc-inject.js
$env:OUT='D:/gaius-next/v040/evidence/mem/baseline-gc'
$env:MEMORY_INJECT='D:/gaius-next/v040/evidence/mem/baseline-gc-inject.js'
bash D:/gaius-next/v040/m1/slot.sh chrome w4-mem-baseline-gc -- node port/scripts/memory-budget-cdp.mjs
$env:MEMORY_DEVICE_GIB='4'; $env:MEMORY_BASELINE='0'
node port/scripts/memory-budget-inject.mjs D:/gaius-next/v040/evidence/mem/low-gc-inject.js
$env:OUT='D:/gaius-next/v040/evidence/mem/low-gc'
$env:MEMORY_INJECT='D:/gaius-next/v040/evidence/mem/low-gc-inject.js'
bash D:/gaius-next/v040/m1/slot.sh chrome w4-mem-low-gc -- node port/scripts/memory-budget-cdp.mjs
```

Use a new output directory for each additional trial to preserve old evidence.
For the large snapshot run, omit `MEMORY_INJECT` and `MEMORY_QUERY`, set
`HEAP_SNAPSHOT=1`, and choose a separate `OUT`. Summarize it with:

```text
node --max-old-space-size=384 port/scripts/memory-heap-summary.mjs <world.heapsnapshot> <heap-summary.json>
```

Local checks that passed:

```text
OUT=<scratch> node port/scripts/browser-memory-wasmgc-pool-smoke.mjs
OUT=<scratch> node port/scripts/memory-tooling-smoke.mjs
node port/scripts/memory-budget-smoke.mjs
node port/scripts/browser-opengl-shadow-retention-smoke.mjs
node port/scripts/browser-opengl-buffer-layer-smoke.mjs
node port/scripts/browser-opengl-cross-kind-copy-smoke.mjs
node port/scripts/browser-opengl-indexed-smoke.mjs
node port/scripts/quality-gl-shadow-smoke.mjs
node port/scripts/launcher-quality-smoke.mjs
node port/scripts/browser-buffer-shadow-policy-smoke.mjs
bash port/scripts/browser-memory-lifecycle-smoke.sh
```

The pool test compiles the actual allocator source against a bounded JVM heap
model with `javac -J-Xmx3g` and `java -Xmx3g`. It checks ownership, alignment,
calloc, OOM preservation, moving/in-place realloc and complete pool release.
One thousand 1,000-byte allocate/free cycles use one native allocation with
reuse and 1,000 without it. A 100,000-to-300,000-byte realloc produces logical
peak live bytes of 300,000 versus 400,000 (25% lower in this model only).
It is not a TeaVM integration test. The JS lifecycle check uses the selected
profile's cached jars, an explicit local JDK and an output-directory TMPDIR.

## Remaining acceptance gates

1. On a later build-authorized task, rebuild the current source through the
   shared build slot and rerun the probes without old-binary injection. Check
   owner sums, reservation bounds and byte-exact fallback paths under pressure.
2. Compare identical GPU tier, seed, viewport, settle interval and GC protocol;
   use fresh browser profiles and paired `gaiusMemoryReuse=0`/enabled runs.
3. Record growth at startup, title, world entry and sustained movement. Tag
   remaining large kernel-result/mesh/native-image callers where direct
   allocation evidence identifies their actual ownership.
4. A smaller initial/growth configuration needs its own compiled-layout check;
   do not lower the imported-memory minimum or expect freed bytes to shrink it.
5. Reach the 450-MiB target and test physical 4-GB hardware, saved-option
   precedence, FPS, GC pauses, OOM and worker-inclusive memory. These gates are
   open; the current measurement remains approximately 151 MiB above target.
