# Performance Targets

This document defines the browser-runtime performance contract. It is based on
the game render loop, not a page-level `requestAnimationFrame` counter: Chrome
can throttle page RAF callbacks while a diagnostic extension is attached even
though Minecraft continues to execute its own render loop.

## Measurement Contract

Use desktop Chrome with the game in the foreground and the Video Settings
framerate option set to `Unlimited`. Keep the default `Fast` preset unless the
scenario says otherwise. Enable the temporary `window.__gaiusFrameTelemetry`
object before the measured interval, warm up for ten seconds, then capture at
least sixty seconds of `BrowserGlfw.swapBuffers` frame data. Clear the object
after exporting the result.

Every report records the following values:

| Metric | Meaning |
| --- | --- |
| Average FPS | Completed Minecraft render frames divided by elapsed measurement time. |
| 1% low FPS | Reciprocal of the 99th-percentile frame time. |
| P95/P99 | 95th and 99th percentile `swapBuffers` frame times. |
| Longest frame | Worst observed frame time, reported separately from percentile values. |
| Worker transport | Inbound queue peak, deferred pumps, peak pump duration, and flow pauses. |
| Gameplay authority | Chunk-batch progress plus block break/place acknowledgement latency. |

The result must also include Chrome version, operating system, resolution,
device pixel ratio, render distance, simulation distance, and whether the
route crossed newly generated chunks.

## Release Targets

These are the acceptance targets for a desktop-class Chrome machine. They are
gates for player-visible regressions, not promises that every low-end machine
will hit the same absolute FPS.

| Scenario | Configuration | Target |
| --- | --- | --- |
| Steady local world | 6 render / 4 simulation | 1% low >= 90 FPS, P99 <= 11.1 ms, no frame over 100 ms after warm-up. |
| New-chunk traversal | 6 render / 4 simulation | 1% low >= 60 FPS, P99 <= 16.7 ms, no frame over 150 ms, no unbounded Worker backlog. |
| Higher visual range | 8 render / 4 simulation | 1% low >= 45 FPS, P99 <= 22.2 ms, no frame over 200 ms. |
| Stress range | 12 render / 4 simulation | 1% low >= 30 FPS, P99 <= 33.3 ms, no persistent queue growth. |
| New single-player world | default 6 / 4 | Initial interactive terrain within 15 seconds on the reference machine; no browser unresponsive event. |
| Break/place during generation | default 6 / 4 | P95 server confirmation <= 250 ms; P99 <= 500 ms; confirmed actions never roll back. |

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
