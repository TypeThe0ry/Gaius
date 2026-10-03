# Performance Targets

This document defines the browser-runtime performance contract. It is based on
the game render loop, not a page-level `requestAnimationFrame` counter: Chrome
can throttle page RAF callbacks while a diagnostic extension is attached even
though Minecraft continues to execute its own render loop.

## Measurement Contract

Use desktop Chrome with the game in the foreground and the Video Settings
framerate option set to `Unlimited`. The reference display must refresh at
120 Hz or faster and Chrome must report foreground, non-throttled frame
callbacks. Use the `Fancy` preset values the benchmark seeds (see "Graphics
quality pins" below) unless the scenario says otherwise.
Enable the temporary `window.__gaiusFrameTelemetry` object before the measured
interval, warm up for 30 seconds, then capture five uninterrupted minutes of
frame data from the present boundary: `BrowserGlfw.swapBuffers` on the 26.2
profile, `BrowserSdl.SDL_GL_SwapWindow` on the 26.3 profile (both write the same
`window.__gaiusFrameTelemetry` fields; see `measurement.frameSourceByProfile` in
`port/scripts/performance-contract.json`). Clear the object after exporting the
result.

The default 6 render / 4 simulation distances are part of the contract. A run
is invalid if the client silently reduces either distance, skips visible chunk
work, disables entities or particles, changes the requested resolution, loses
the world connection, or measures a hidden/background tab.

On 26.2 and 26.3 the `Fancy` and `Fabulous` graphics presets are pinned to 8
render / 6 simulation distance (`Fancy` keeps `mipmapLevels` 4), matching the
browser distance contract. They previously forced the vanilla 16 / 12 and 32 / 12
distances, which multiplied worldgen and render work far beyond the target
quality; 8 / 6 is the intended quality floor, not a reduction below it. A run
that finds either preset requesting more than 8 / 6 is measuring the old
contract-violating behavior. 1.21.11 keeps the vanilla preset distances.

### Graphics quality pins

Since v0.4.0 `Minecraft.<init>` no longer re-applies the saved graphics preset
at startup (`?gaiusPresetReplay=1` restores that vanilla behavior), so the
options a run seeds are the options it measures. `chrome-chunk-benchmark.mjs`
therefore seeds every value the `Fancy` preset sets (identical on 1.21.11, 26.2
and 26.3) next to the profile's own distances, and opens the page with
`?gaiusPresetReplay=0`. Profiles whose distances differ from 8 / 6 (for example
`steady-12-4`) now request their seeded distances instead of the preset's.

The run also pins the GPU quality tier with `?gaiusTier=` (default `mid`,
`--gpu-tier low|mid|high|ultra` or `environment.gpuTier` in the contract), because
the tier selects Improved Transparency, the post-processing stages, the
inventory world refresh rate and the DPR cap. Reports record
`environment.quality` (`__gaiusGpuCaps.tier`, `__gaiusQualityStats.renderScale`
and `lastStages`) and flag a run whose loaded tier differs from the pin. For
screenshots that are compared against vanilla, also disable the 26.3
post-processing chain with `?gaiusPost=0` (`--post 0`); it changes tone mapping
and anti-aliasing.

World-load timing stops only after strict readiness, not when `ClientLevel`
first becomes non-null. Strict readiness requires at least one loaded client
chunk, a finite and collision-free player pose, no screen or overlay, a live
visible frame loop with 16 consecutive frames across at least 250 ms, three
distinct non-air block hits from the current crosshair, and three terrain-valid
canvas-only compositor captures. Any frame gap over 100 ms or invalid state
resets the sequence.

Every report records the following values:

| Metric | Meaning |
| --- | --- |
| Average FPS | Completed Minecraft render frames divided by the sum of complete inter-frame intervals; a separate wall-clock coverage gate detects missing time. |
| 1% low FPS | `1000 / mean(frame time of the slowest 1% of completed frames)`. |
| P95/P99 | 95th and 99th percentile `swapBuffers` frame times. |
| Longest frame | Worst observed frame time, reported separately from percentile values. |
| Freeze count | Frames or main-thread gaps of at least 500 ms. |
| Worker transport | Inbound queue peak, deferred pumps, peak pump duration, and flow pauses. |
| Gameplay authority | Chunk-batch progress plus block break/place acknowledgement latency. |
| Memory stability | JS heap, TeaVM linear/native memory, GPU resource counters, Worker heap, and post-GC trend. |
| Visual output | Chrome compositor screenshots before and after measurement; blank or near-constant world frames fail. |

The result must also include Chrome version, operating system, resolution,
device pixel ratio, display refresh rate, render distance, simulation distance,
resource preset, and whether the route crossed newly generated chunks. The raw
frame-time series and telemetry snapshot are release artifacts; rounded summary
numbers alone are not sufficient evidence.

Uncapped FPS evidence has an additional runtime proof requirement. The report
must contain at least two **complete** measured frame-pacing samples and a
complete final snapshot with `swapInterval=0`, `uncappedYieldCount>0`,
`messageChannelYieldCount>0`, `fairYieldCount=0`, `schedulerYieldCount=0`,
`timerYieldCount=0`, `vsyncYieldCount=0`, `hiddenYieldCount=0`, and
`presentToRafCount=0`. The only permitted incomplete sample is a leading reset
sentinel with `swapInterval=null` and every required counter present as a safe
integer equal to zero. That sentinel is retained as a diagnostic but excluded
from the minimum-sample, monotonicity, and accounting proof. Any other
incomplete sample—including an all-zero sentinel after the first complete
sample, or a null snapshot carrying hidden/timer activity—fails the window. An
incomplete final snapshot remains inconclusive.

The strict release parent does not trust the child's derived pacing summary by
itself. `report.samples` must contain the raw, successful, sub-500 ms measured
samples (objects with strictly increasing nonnegative safe-integer timestamps), and
`report.telemetry` must contain the raw final snapshot. The parent repeats the
benchmark's timestamp merge and `runtimeInvariants.framePacing` + `frame`
mapping, reruns the same evaluator,
and requires the recomputed verdict, sample accounting, integrity fields, and
final snapshot to match the child report exactly. Summary-only reports, error
samples, stalled sampling calls, and good summaries paired with bad raw data
are release failures.

Every raw sample, final scalar, settlement sample/final, and cleanup scalar
carries the reset-created `measurementId` and `measurementEpochId`. The two IDs
must be identical, nonempty, and exactly equal across the complete evidence
chain. Both `frame` and `runtimeInvariants.framePacing` must independently carry
the IDs and every required field with exact values; one source cannot silently
overwrite contradictory or incomplete evidence from the other. The derived
summary records `epochMismatchCount=0` for a passing run.

`telemetry.capturedAt` must be a nonnegative safe integer later than the last
raw sample timestamp. Each final, settlement, and cleanup scalar also records
`controllerRequestedAt`, browser `capturedAt`, `controllerReceivedAt`, and
`evaluationLatencyMillis`. These are safe integers ordered request <= capture
<= response, latency is exactly response minus request, and the response must
arrive within the configured settlement timeout plus one poll interval. The
settlement additionally records exact controller start/completion/elapsed
times; an evaluation that returns after that finite grace is retained as
failure evidence rather than being accepted late. Settlement scalar requests
are serialized: the first request is not earlier than controller start, every
later request is not earlier than the previous response (equality is allowed),
and request timestamps never move backwards. Overlapping controller ranges are
invalid even when each scalar's individual request/capture/response tuple looks
valid.

Every complete snapshot must contain nonnegative safe-integer counters, and all
cumulative counters must be monotonic nondecreasing across the window. The
accounting identities are checked per snapshot:

- `yieldRequestCount == uncappedYieldCount + vsyncYieldCount`
- `yieldRequestCount == visibleYieldCount + hiddenYieldCount`
- `yieldCompletionCount == messageChannelYieldCount + schedulerYieldCount + timerYieldCount`
- `pendingYieldCount == yieldRequestCount - yieldCompletionCount`, with pending
  in `0..1`, zero duplicate callbacks, and `maxPendingYieldCount` exactly `0`
  before the first request or exactly `1` after any request (the asynchronous
  request increments pending before a continuation can complete)
- for the visible uncapped window, including settlement and cleanup,
  `swapInterval == requiredSwapInterval == 0` and
  `uncappedYieldCount - messageChannelYieldCount == pendingYieldCount`, with no
  hidden, VSync, rAF, fair-scheduler, scheduler, or timer completions

This permits one legitimately in-flight `MessageChannel` continuation at a
snapshot boundary (for example requests `100`, completions `99`, pending `1`)
while rejecting mismatched, reset, fractional, negative, overlapping, or mixed
task-path telemetry. These exact-once gates prove TeaVM continuation health.
The Browser gates separately check the observed FPS, input, and networking
windows for regressions; they do not prove browser task-source round-robin
fairness. Audio correctness remains an independent manual gate until underrun,
callback-gap, and audible-timing telemetry is part of the release contract.

The final snapshot is followed, in the same active measurement epoch, by a
bounded `framePacingSettlement`. If the final snapshot has one pending
continuation, the harness polls a lightweight pacing scalar every 5--10 ms for
at most 150--250 ms and must observe at least one additional MessageChannel
completion. A final snapshot with no pending continuation may settle on the
first later scalar capture. Settlement timestamps must strictly follow the
final telemetry timestamp; cumulative counters must not decrease; pending must
remain in `0..1`; and all fallback, watchdog, rebuild, cancellation, duplicate,
hidden, VSync, rAF, scheduler, and timer counters must remain zero. A dead
MessageChannel therefore cannot masquerade as success: its 100 ms watchdog or
timer fallback is captured before the settlement timeout and fails the gate.

Settlement is not the end of the accounting window. After leave-world cleanup,
the harness captures a final lightweight `cleanupTelemetry` scalar in the same
measurement epoch. Its `cutoffYieldRequestCount` is exactly the settlement
final's `yieldRequestCount`; its timestamp is strictly later than settlement;
all cumulative counters remain monotonic and satisfy the same per-snapshot and
visible-uncapped identities; and every alternate, health, watchdog, rebuild,
cancellation, and duplicate counter remains zero. Cleanup may itself observe
one newly pending continuation, but both `messageChannelYieldCount` and
`yieldCompletionCount` must be at least the finite cutoff. This closes the case
where settlement began with pending zero, a new request appeared in its first
poll, and a later watchdog would otherwise fall outside the evidence window.
The strict parent recomputes this cutoff closure from raw `cleanupTelemetry`.
The diagnostic `cleanupTelemetry.framePacingClosure`, the cleanup top-level
dual-source scalar, and `telemetry.cleanupFramePacing` are all schema-14 raw
evidence and must normalize to the same canonical object; the result must also
match the child summary canonically. A forged or stale nested closure therefore
fails instead of being ignored.

The scheduler health counters must also be present and remain zero throughout
the measured window: `messageChannelCreateFailureCount`,
`messageChannelPostFailureCount`, `messageChannelRebuildCount`,
`cancelledMessageTaskCount`, and `watchdogYieldCount`. A nonzero health counter
fails release evidence even when the FPS thresholds pass. A missing or null
runtime field is `inconclusive`, never an implicit zero; in particular,
`swapInterval=null` before the first real frame cannot satisfy uncapped
evidence. `maxFps=Unlimited`, `enableVsync=false`, or a positive in-game FPS
counter by themselves are configuration evidence only and cannot make a
release run pass.

Every benchmark failure writes a structured `failureEvidence` section. It
includes the actual and required average FPS, 1% low, P99, longest frame,
coverage, freeze count, and new-chunk traversal stall; the V8 heap trend,
post-GC slope, plateau/leak signal, retained growth, peak, coverage, native
memory and Chrome RSS verdicts; and the complete client/Worker/Wasm
`buildIdentity`. This keeps a failed run diagnosable even when Chrome crashes
before the normal analysis section is produced.

## Release Targets

These are the acceptance targets for a desktop-class Chrome machine. They are
gates for player-visible regressions, not promises that every low-end machine
will hit the same absolute FPS.

| Scenario | Configuration | Target |
| --- | --- | --- |
| Steady local world | 6 render / 4 simulation | Average >= 120 FPS, 1% low >= 60 FPS, P99 <= 16.7 ms, longest frame <= 50 ms, and zero freezes after warm-up. |
| New-chunk traversal | 6 render / 4 simulation | Average >= 120 FPS, 1% low >= 60 FPS, P99 <= 16.7 ms, longest frame <= 50 ms, zero >= 500 ms freezes, and no traversal stall over 10 seconds. |
| Higher visual range | 8 render / 4 simulation | 1% low >= 45 FPS, P99 <= 22.2 ms, no frame over 200 ms. |
| Stress range | 12 render / 4 simulation | 1% low >= 30 FPS, P99 <= 33.3 ms, no persistent queue growth. |
| New single-player world | default 6 / 4 | Initial interactive terrain within 15 seconds on the reference machine; no browser unresponsive event. |
| Break/place during generation | default 6 / 4 | P95 server confirmation <= 250 ms; P99 <= 500 ms; confirmed actions never roll back. |
| Single-player soak | default 6 / 4 | 30 minutes of movement, generation, block actions, entities, and audio with no crash, freeze, disconnect, OOM, or monotonically growing post-GC heap. |
| Multiplayer soak | default 6 / 4 | 30 minutes through the RelayNode route with the same stability and memory requirements and no unbounded decoded-packet queue. |

Both 6/4 FPS rows are hard release gates. The 8/4 and 12/4 rows are pressure
tiers used to identify CPU, GPU, memory, and queue scaling limits; they cannot
substitute for the 6/4 gate.

## Runtime Invariants

The FPS gates are valid only when the runtime also satisfies these invariants:

- A visible game frame yields through the browser paint scheduler; no fixed
  positive timer is inserted into every frame. Hidden tabs may be throttled,
  but returning to the foreground must not duplicate or lose a continuation.
- The section-task queue uses bounded priority work. With 1,000 queued section
  tasks, queue selection P99 must remain below 0.25 ms and may not fall back to
  a full scan plus indexed list removal for every completed task.
- Minecraft 26.2 performs exactly one gameplay raycast per rendered frame.
  Target observation may record the existing result, but may not perform a
  second raycast or update only `hitResult` without `crosshairPickEntity`.
- World-generation checkpoints run on the single server Worker thread, make
  forward progress, and yield before a pending network action waits more than
  two scheduler pulses. A scheduler turn and all queues have hard bounds.
- CPU buffer shadows and derived base-vertex index buffers obey byte budgets
  (64 MiB and 32 MiB by default), never count-only limits. Source deletion,
  cache eviction, and world exit must release every derived WebGL object.
- Terrain uploads may consume at most eight staged allocations and one
  emergency progress credit per rendered frame. Failed section uploads yield
  through the event loop, retain at most eight concurrent retry tasks, and
  cancel after a five-second/2,048-yield bound instead of spinning or retaining
  a mesh indefinitely. Any retry-bound cancellation fails release evidence.
- An unsignaled GPU fence keeps its frame resources alive and is checked again
  later. `TIMEOUT_EXPIRED` is not a crash condition and must never be reported
  as successful completion. Each signaled fence is deleted exactly once.
- Browser native regions, OpenAL source nodes, WebGL objects, single-player
  Workers, MessagePorts, OPFS cache entries, timers, and telemetry rings are
  bounded. Leaving a world must return lifecycle-owned live counters to their
  pre-world baseline after the cleanup window.

Any violation fails the release even when the aggregate FPS happens to meet
the numerical threshold.

## Stability And Memory Gate

The soak harness samples memory and queue telemetry every five seconds and
records an explicit post-GC sample when Chrome exposes a supported collection
hook. A release run fails when any of the following occurs:

- an uncaught exception, renderer crash, Worker crash, WebGL context loss,
  audio-context failure, disconnect, or browser unresponsive interval;
- a missing compositor screenshot or any sampled active-world screenshot that
  is black, transparent, or effectively a single constant color;
- a main-thread or Worker heartbeat gap of 500 ms or more during measured
  new-chunk traversal;
- a transport, decoded-packet, chunk-generation, section-compile, upload, or
  deferred-task queue that remains above its high-water mark for 10 seconds;
- a post-GC heap slope that remains positive across the final three five-minute
  windows, retained memory grows by more than 15% or 256 MiB between the first
  and last stable post-GC windows, or post-GC heap exceeds 8 GiB; loading new
  chunks does not exempt retained growth;
- TeaVM/native allocations, WebGL objects, decoded audio buffers, or detached
  single-player sessions remain live after leaving the world and a cleanup/GC
  observation window.

The harness also samples operating-system RSS for Chrome browser, renderer,
GPU, and utility processes throughout a 30-minute soak. Missing process data is
inconclusive, never zero. Sustained growth fails when either the configured
percentage or absolute byte threshold is exceeded, and every process class has
an independent absolute peak limit. The raw slope and all samples remain in the
report for slower-growth review.

When explicit GC is unavailable, the report must mark the heap verdict as
`inconclusive` instead of claiming a pass. Crash, OOM, queue, context-loss, and
heartbeat gates still apply.

The report labels a non-leaking stable post-GC trend as a `plateau` only when
its measured slope is within the contract's plateau bound. A plateau is
diagnostic evidence, not permission to ignore an absolute peak, native-memory
failure, RSS growth, or incomplete sample coverage.

## Required Scenarios

Each release candidate runs the following deterministic scenarios with raw
telemetry retained:

1. Stand still in a fully loaded local world at 6/4 for 30 seconds of warm-up
   and five minutes of measurement.
2. Travel continuously into never-generated terrain at 6/4 for five minutes.
   Break and place at least three blocks while that workload is active, retaining
   packet emission, server confirmation, state transition, and rollback evidence
   for every action.
3. Repeat steady and traversal samples at 8/4, then traversal at 12/4 as a
   pressure test.
4. Run 30-minute single-player and RelayNode multiplayer soaks, including
   repeated world/server leave and rejoin cycles.
5. Verify that selection outlines, block authority, item drops, textures,
   entities, and positional audio remain correct during the measured work.

## Attribution Rules

Do not optimise based on a single aggregate FPS number.

- A high `swapBuffers` rate with poor page RAF under remote automation is an
  automation scheduling artifact, not evidence of a fast or slow visible game.
- Growing inbound queue, deferred pumps, or confirmation latency makes the
  Worker and MessagePort path the first investigation target.
- Stable Worker transport with poor frame percentiles moves the investigation
  to section compilation/upload, draw count, texture uploads, and WebGL state.
- Changes must preserve real Chrome gameplay: first chunks, movement, block
  break/place authority, terrain/material rendering, and audio all remain
  regression checks.

An average of 20 TPS does not establish smooth simulation. Catch-up ticks can
hide long pauses in an aggregate. The worker smoke's `serverTickWindow.hitchCounts`
reports differences in interval, tick-work, and wait-phase counters for the
measured window. Missing or reset counters are `null`, never zero. Pair these
counts with visible entity movement and message-delay evidence.

## Runtime Switches

The runtime paths below can be switched back to their vanilla behavior or pinned
for a run, so a measurement can attribute a change to one feature. Switches are
page URL query parameters; where a setting persists, the `localStorage` key is
listed. Unless a row lists other values, a switch is turned off with `0`,
`false` or `off`, and parameter names are case-sensitive.

### Terrain draw path

These apply to the 26.2 and 26.3 renderers; 1.21.11 keeps its legacy section
renderer.

| Switch | Effect |
| --- | --- |
| `gaiusTerrainBatch=0` | No terrain shader rewrite and no draw batches: every section is drawn by its vanilla call. `globalThis.__gaiusTerrainBatch = false` does the same. |
| `gaiusMultiDraw=0` | Batches do not use `WEBGL_multi_draw`; each section is drawn with the `gaius_DrawId` uniform instead. |
| `gaiusQuadIndex=0` | Sequential quad element buffers are not mapped onto the shared quad index buffer. |
| `gaiusBakedIndex=0` | Custom index heaps (translucent terrain) get no baked base-vertex index copies. |
| `gaiusElementShadow=always` | Keeps a CPU shadow of every element buffer, as before v0.4.0, when the context has no usable base-vertex extension. |
| `gaiusTerrainAlign=0` | Keeps the vanilla section vertex heap alignment instead of 4-vertex alignment (`0` or `false` only). |
| `gaiusNeighborGate=vanilla` | Compiles a dirty section only once all eight neighbor chunks are ready, like the desktop game. `gaiusNeighborGate=provisional` restores the v0.2 center-only gate. |
| `gaiusAnisotropy=0` | Does not enable `EXT_texture_filter_anisotropic`. |

### Quality layer

`port/web/runtime/quality/` turns the GPU tier into runtime settings. The world
render scale, its upscaler and the post-processing chain are used by the 26.3
renderer; `?gaiusPost=0&gaiusRenderScale=1&gaiusOit=0` is the vanilla setup.

| Switch | Effect |
| --- | --- |
| `gaiusTier=low\|mid\|high\|ultra` | Pins the GPU tier for the page. A tier chosen in the game is stored as `gaius.quality.tierOverride`; `gaiusTier=auto` clears it. |
| `gaiusGpuRebench=1` | Discards the remembered GPU benchmark (`gaius.quality.gpuTier.v1`) and measures again. |
| `gaiusOit=0\|1` | Forces order-independent (improved) transparency off, or lifts its high/ultra tier condition. The float colour-buffer requirements still apply. |
| `gaiusPresetReplay=1` | Re-applies the saved graphics preset at startup, the vanilla behavior (see Graphics quality pins). |
| `gaiusRenderScale=1\|0.85\|0.75\|0.67\|0.6\|0.5\|auto` | World render scale (default 1); `auto` follows `gaiusTargetFps`. |
| `gaiusTargetFps=<n>` | Frame-rate target of the automatic render scale (default 60, 20 to 240). |
| `gaiusSharpness=<0..2>` | RCAS sharpening in stops, 0 being the strongest (default 0.25). |
| `gaiusPost=0\|1` | Post-processing chain off or on (default on for the high and ultra tiers only). |
| `gaiusPostTier=low\|mid\|high\|ultra` | Post stage set independent of the GPU tier. |
| `gaiusFxaa=`, `gaiusTonemap=`, `gaiusSsao=`, `gaiusBloom=`, `gaiusSsr=` | Single post stages, `0` or `1`; screen-space reflections are off by default. |
| `gaiusInventoryWorldFps=<n>` | World refresh rate behind inventory screens (0 freezes it; the default depends on the tier). |
| `gaiusMaxDpr=<number>` | Canvas device-pixel-ratio cap (0.5 to 4; the default depends on the tier on 26.3 and is 1 on the other profiles). |

The settings a player can change at runtime (`post`, `ssr`, `renderScale`,
`sharpness`) persist in `gaius.quality.settings.v1`; a URL parameter wins over
the stored value.

### Wasm kernels

| Switch | Effect |
| --- | --- |
| `gaiusKernels=0` | All wasm kernels off: meshing, light and world generation use the vanilla Java paths. `gaiusKernels=1` overrides a stored `enabled: false`. |
| `gaiusKernelsOff=<names>` | Comma-separated kernels to turn off: `mesher`, `light`, `worldgen`, `noise`. |
| `gaiusKernelSimd=0\|1` | `0` uses the baseline (non-SIMD) builds; `1` uses the SIMD builds without the feature probe. |
| `gaiusKernelWorkers=<n>` | Fixed number of kernel workers instead of the adaptive count. |
| `gaiusKernelBudgetMB=<n>` | Memory budget of the kernel runtime in MB. |
| `meshKernel=0` | Sections compile on the vanilla Java path (`0`, `false`, `off` or `vanilla`). Stored setting: `gaius.meshKernel` = `off`. |
| `gaiusMesher=0` | Turns off the mesh job codec, which also keeps the vanilla Java path (`0`, `false` or `vanilla`). Stored setting: `gaius.mesher.enabled` = `0`. |
| `lightKernel=0` | First light of new chunks on the vanilla light engine (also spelled `lightkernel`; `0`, `off`, `false` or `no`). Stored setting: `gaius.lightKernel` = `off`. |
| `worldgenKernel=0` | Chunk generation on the vanilla Java path (`0`, `off`, `false` or `no`), in the page or the server Worker URL. |
| `worldgenKernelHost=auto\|shared\|worker` | Where worldgen jobs run: `shared` only through the page's kernel runtime, `worker` in kernel workers the server Worker starts itself, `auto` (default) lets the worldgen facade choose. |

The kernel runtime settings persist in `gaius.kernels.settings.v1` as JSON
`{enabled, off, simd, workers, budgetMB}` (`simd` is `"off"` or `"force"`,
`off` a list of kernel names). Every kernel also falls back to its vanilla path
on its own when its module cannot be compiled or loaded, or after repeated
failures.

### Boot and site

| Switch | Effect |
| --- | --- |
| `gaiusSw=0` | Unregisters the Service Worker of the multi-file site (`0` only). |
| `gaiusCoi=0\|credentialless\|require-corp` | Cross-origin isolation mode of the Service Worker (default `credentialless`; `0` or `off` turns it off). A stored `gaius.sw.coep` of `require-corp` or `off` is used when the parameter is absent. |
| `gaiusSiteGzip=0\|1` | Forces whether the site loads its gzip copies of large text assets; otherwise the value learned for the host is kept in `gaius.site.gzip.v1`. |
| `gaiusSounds=eager\|deferred` | `eager` makes the game start wait until the site's separate sound pack is merged into the vanilla assets; `deferred` starts without waiting and calls `window.__gaiusSoundReloadHook` once the pack is merged. Without the parameter the mode is `deferred` when that hook exists and `eager` otherwise. |
| `gaiusPrefetch=0` | Does not prefetch the single-player and kernel payloads while the title screen idles (`0` only). |

## Optional structure-template preloading experiment

The browser server can warm explicitly selected template IDs through its own
`StructureTemplateManager` before level creation. This is disabled by default.
Repeat the `gaiusStructurePreload` page query parameter for each measured ID;
`gaiusStructurePreloadBudgetMillis` sets a cumulative budget (default 5000 ms,
maximum 30000 ms). For example, a diagnostic page can use
`?gaiusStructurePreload=minecraft:trial_chambers/chamber/assembly`.

For the Node worker smoke, set `GAIUS_SMOKE_STRUCTURE_PRELOAD_IDS` to a JSON
array of IDs and optionally `GAIUS_SMOKE_STRUCTURE_PRELOAD_BUDGET_MS`. Configuration
must arrive before the worker accepts `start`; later changes are ignored.
At most 64 IDs are accepted. The helper yields between entries when a native
TeaVM continuation exists. It skips the experiment in ordinary JS callbacks.
The time budget is checked between entries and cannot interrupt one synchronous
template parse. Missing templates, failed entries, remaining entries and the
stop reason are available in the diagnostic snapshot's `structurePreload` field.

Preloading moves work into startup and uses the manager's existing cache; it
does not by itself reduce total work or prove improved gameplay. Compare the
same seed, distances and traversal with the experiment off and on, include the
extra startup cost, and retain all normal release gates above. The helper smoke
(`node port/scripts/browser-structure-preloader-smoke.mjs`) checks its control
flow using real TeaVM continuations; full profile and Chrome tests remain required.
