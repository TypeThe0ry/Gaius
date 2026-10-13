# Acceptance harness diagnostics

## Seed input

`configure-browser-world-seed.mjs` is the shared implementation used by the
singleplayer and chunk benchmark drivers. The script-path adapter exports the
same implementation. It selects a visible World button when available, otherwise
uses the vanilla CreateWorldScreen Ctrl+2 shortcut. It never requires tab-bar
telemetry or branches on a profile ID. Control is released before editing the
unique visible, active seed input. Focus is checked before and after typing.

The receipt records the requested seed, input structure and screenshot. It does
not claim to have read the effective world seed: `effectiveSeed` remains null.

## Saved position regression

The seed 20261004 M4 failure was a spawn patch deviation, not permission to relax
`playerPositionPersisted`. The earlier implementation applied searched-spawn
fixup to a returning player's saved position. Commit `db78be23` already fixes this
in the baseline: PrepareSpawnTask records whether SavedPosition.position is
absent; Preparing applies fixup only to a searched spawn. Returning positions
must reach Ready unchanged, even when unsafe. The M4 distance tolerance remains
strictly less than 2.5 blocks.

Evidence is in `m1/out2/sp/notes-zh.md` and `m1/out3/bugs/NOTES.md` under the task
workspace. `acceptance-harness-jvm-smoke.mjs` exercises the existing spawn fixture
against every catalog profile and checks the saved-position guard in bytecode.
It also executes actual vanilla animation methods and their patched counterparts,
compiles the cloud/particle option API, and checks the seed widget and Ctrl+digit
keyboard structure against each vanilla jar.

## Deterministic capture

Set `CAPTURE_MODE=1` on `port/scripts/minecraft-263-singleplayer-cdp.mjs`. This is
a separate four-view diagnostic, not a substitute for M4 save/reload acceptance.
`DIST` selects an existing distribution and its build identity supplies the
profile. `QUERY` selects runtime/serverRuntime/gpu in the launch URL. Use an
independent `OUT` and browser profile for each backend.

Before Java startup, the boot barrier persists the capture options: clouds off,
minimal particles, no view bobbing, fixed FOV, GUI scale and render distance.
The animation hook reads the actual vanilla cloud/particle option values, rather
than assuming the options file was consumed.

The scene uses vanilla commands with checked chat acknowledgements:

- `advance_time false` and `advance_weather false` are the catalog jars' names
  for doDaylightCycle/doWeatherCycle.
- `random_tick_speed 0`, `spawn_mobs false`, `/time set 6000`, clear weather.
- Spectator mode, removal of non-player entities, stationary pig/armor stand.
- `/tick freeze` before scene setup and animation pinning. Teleports still run
  through vanilla commands; actual player coordinates and renderer settlement
  must be confirmed before each capture.
- HUD hidden for three repeat PNGs per view; unfreeze and unpin during cleanup.

The default cameras are horizon, entities, high and topdown relative to spawn.
After the first capture, save its `views[name].camera` arrays as a JSON object
and pass that exact file through `CAPTURE_CAMERAS` to the second backend. The
comparison refuses different camera coordinates, options, profile or seed.
It also refuses different staged-response hashes or missing native build identity,
and checks every sampled player pose against the requested camera.

`globalThis.__gaiusCapture = {enabled:true, animationTick:0}` enables the Gaius
debug hook. A bounded integer tick (0..1200) pins every sprite's frame/subframe by
resetting to the vanilla initial state and replaying the unchanged vanilla tick
method. This retains variable frame durations and interpolation semantics. When
the pinned phase is unchanged, non-interpolated sprites do not redraw their atlas
tiles unnecessarily. When disabled, the original method runs once with no field
reset. The patched source
is independent of profile literals and fails closed on changed class structure.

For existing JavaScript distributions that predate the hook, set
`CAPTURE_STAGE_JS=1`. Only the localhost response for classes.js is adapted;
the source file and all JavaScript-target build outputs stay unchanged. The
staging receipt binds both source and served-response SHA-256. This is explicitly
a diagnostic response, not a rebuilt or release-tested WasmGC client.

On this shared machine, launch Chrome only through the slot wrapper:

```powershell
$env:DIST = 'D:/path/to/existing/dist'
$env:OUT = 'D:/gaius-next/v040/evidence/harness/capture-webgl2'
$env:SEED = '20261004'
$env:CAPTURE_MODE = '1'
$env:CAPTURE_STAGE_JS = '1'
$env:QUERY = 'runtime=js&serverRuntime=wasm&gpu=webgl2&gaiusPost=0&gaiusRenderScale=1'
bash D:/gaius-next/v040/m1/slot.sh chrome harness-capture -- node port/scripts/minecraft-263-singleplayer-cdp.mjs
```

Repeat with `gpu=webgpu`, its own OUT and the shared camera file. Preserve the
runtime, serverRuntime and all other query parameters.

```powershell
node tools/capture-pixel-diff.mjs <webgl-output> <webgpu-output> <comparison-output>
```

The dependency-free PNG tool reports full-frame and upper/middle/lower thirds.
The pixel tolerance is max RGB channel difference >8; the acceptance threshold
is 0.5% of pixels. Repeat-frame changes within either backend form an independent
temporal mask. Heatmaps show stable cross-backend residuals in red and temporal
changes in orange. Stable residuals are renderer candidates, not proven causes.
Any temporal change makes the verdict UNSTABLE; masking cannot turn a noisy run
into PASS. With no temporal changes, all four full-frame diffs must meet 0.5%.
The tool writes JSON, Markdown and four heatmaps before returning a nonzero exit
status for FAIL/UNSTABLE.

## Offline checks

```powershell
$env:OUT = 'D:/gaius-next/v040/evidence/harness/tests'
node --test tools/acceptance-harness.test.mjs
node port/tools/patchkit/patchkit.mjs lint
node port/tools/patchkit/patchkit.mjs check --write --keep --quiet --out <output>
node port/scripts/acceptance-harness-jvm-smoke.mjs <retained-patchkit-scratch> <output>
node port/scripts/teavm-jsbody-parser-smoke.mjs port/overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java
```

These are Node/JVM/parser checks, not full TeaVM or browser-WasmGC acceptance.
The Java fixtures and compilers use at most `-Xmx3g`; dependencies must already
exist in local caches. No full client/server TeaVM build is required.
