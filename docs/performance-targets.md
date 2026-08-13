# Performance Targets

This document defines the browser-runtime performance contract. It is based on
the game render loop, not a page-level `requestAnimationFrame` counter: Chrome
can throttle page RAF callbacks while a diagnostic extension is attached even
though Minecraft continues to execute its own render loop.

## Measurement Contract

Use desktop Chrome with the game in the foreground and the Video Settings
framerate option set to `Unlimited`. The reference display must refresh at
120 Hz or faster and Chrome must report foreground, non-throttled frame
callbacks. Keep the default `Fast` preset unless the scenario says otherwise.
Enable the temporary `window.__gaiusFrameTelemetry` object before the measured
interval, warm up for 30 seconds, then capture five uninterrupted minutes of
`BrowserGlfw.swapBuffers` frame data. Clear the object after exporting the
result.

The default 6 render / 4 simulation distances are part of the contract. A run
is invalid if the client silently reduces either distance, skips visible chunk
work, disables entities or particles, changes the requested resolution, loses
the world connection, or measures a hidden/background tab.

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
must contain at least two measured frame-pacing samples and a final snapshot
with `swapInterval=0`, `uncappedYieldCount>0`, `fairYieldCount>0`,
`vsyncYieldCount=0`, and `presentToRafCount=0`. The fair-yield counter proves
that the uncapped loop periodically returns control to input, networking, and
audio task sources instead of running an uninterrupted microtask chain.

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
