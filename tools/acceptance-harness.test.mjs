import assert from "node:assert/strict";
import test from "node:test";
import vm from "node:vm";
import {mkdir, readFile, writeFile} from "node:fs/promises";
import {join, resolve} from "node:path";
import {configureWorldSeed, seedScreenSupportsShortcut} from "./configure-browser-world-seed.mjs";
import {stageAnimationCapture, captureViews, validateCaptureViews, captureCameraReadyExpression, captureDeterministicWorld, CAPTURE_OPTIONS, freezeCaptureWorld} from "./deterministic-world-capture.mjs";
import {decodePng, encodePng, compareCaptures, reportCaptureDiff} from "./capture-pixel-diff.mjs";

const output = resolve(process.env.OUT || "acceptance-harness-test-out");
await mkdir(output, {recursive: true});
const source = `let nmc_Minecraft_getInstance = () => {return minecraft;};
let nmc_Options_cloudStatus = $this => {return $this.$cloud;};
let nmc_Options_particles = $this => {return $this.$particles;};
let nmc_OptionInstance_get = $this => {return $this.$value;};
let jl_Enum_ordinal = $this => {return $this.$ordinal;};
function init($this){nmc_Options__init_(options, $this, dir);$this.$options = options;}
let nmc_SpriteContents$AnimationState_tick = $this => {
    $this.$subFrame = $this.$subFrame + 1 | 0;
    $this.$dirty = 0;
    const current = $this.$frames[$this.$frame];
    if($this.$subFrame>=current.time){
      const previous=current.index;
      $this.$frame = ($this.$frame + 1 | 0) % $this.$frames.length;
      $this.$subFrame=0;$this.$dirty=previous!==$this.$frames[$this.$frame].index?1:0;
    }
};`;
test("staged animation replays vanilla durations and preserves disabled behavior", () => {
  const staged = stageAnimationCapture(source);
  const context = vm.createContext({minecraft: {$options: {$cloud: {$value: {$ordinal: 0}}, $particles: {$value: {$ordinal: 2}}}}});
  vm.runInContext(staged.source + ";globalThis.tick=nmc_SpriteContents$AnimationState_tick;", context);
  const baseline = vm.createContext({}); vm.runInContext(source + ";globalThis.tick=nmc_SpriteContents$AnimationState_tick;", baseline);
  const create = () => ({$frame: 0, $subFrame: 0, $dirty: 1, $frames: [{index: 0, time: 2}, {index: 1, time: 3}, {index: 1, time: 1}]});
  const actual = create(), expected = create();
  for (let count = 0; count < 100; count++) { context.tick(actual); baseline.tick(expected); assert.deepEqual(actual, expected); }
  for (const target of [0, 1, 2, 4, 9, 19, 1200]) {
    const expectedPinned = create(); for (let count = 0; count < target; count++) baseline.tick(expectedPinned);
    context.__gaiusCapture = {enabled: true, animationTick: target};
    for (let repeat = 0; repeat < 3; repeat++) {
      const changed = actual.$frame !== expectedPinned.$frame || actual.$subFrame !== expectedPinned.$subFrame;
      context.tick(actual); assert.equal(actual.$frame, expectedPinned.$frame); assert.equal(actual.$subFrame, expectedPinned.$subFrame); assert.equal(actual.$dirty, Number(changed));
    }
    assert.ok(context.__gaiusCapture.optionsObserved.cloudsOff && context.__gaiusCapture.optionsObserved.particlesMinimal);
    context.__gaiusCapture.enabled = false; baseline.tick(expectedPinned); context.tick(actual); assert.deepEqual(actual, expectedPinned);
  }
  assert.throws(() => stageAnimationCapture(source + source), /expected one/);
  assert.throws(() => stageAnimationCapture(source.replace("$subFrame + 1", "$subFrame + 2")), /structure changed/);
});

for (const layout of ["shortcut", "button"]) test(`seed input uses ${layout} layout without a tab-bar telemetry requirement`, async () => {
  const seedWidget = {type: "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab$1", text: "Seed for the world generator", x: 10, y: 20, width: 100, height: 20, active: true, visible: true, focused: false};
  const context = vm.createContext({window: {__gaiusMinecraftState: {screen: "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen", screenSize: {width: 320, height: 180}, screenWidgets: []}},
    document: {querySelector: () => ({getBoundingClientRect: () => ({left: 0, top: 0, width: 640, height: 360})})}});
  let controlHeld = false, typed = "";
  const openTab = () => { context.window.__gaiusMinecraftState.screenWidgets = [seedWidget]; };
  const result = await configureWorldSeed({send: async (method, args) => {
    if (method === "Input.dispatchMouseEvent") seedWidget.focused = true;
    if (args?.text) { assert.equal(controlHeld, false); typed += args.text; }
    if (method === "Page.captureScreenshot") return {data: Buffer.from("mock").toString("base64")};
  }}, "20261004", {
    findButton: async () => layout === "button" ? {text: "World", x: 20, y: 10} : null,
    click: async () => openTab(), dispatchKey: async (_, code, type) => { if (code === "ControlLeft") controlHeld = type === "keyDown"; if (code === "Digit2" && type === "keyUp") openTab(); },
    evaluate: async (_, expression) => vm.runInContext(expression, context),
    waitFor: async (_, expression) => assert.equal(vm.runInContext(expression, context), true), sleep: async () => {}, outputPath: join(output, layout),
  });
  assert.equal(typed, "20261004"); assert.equal(result.usedWorldTabShortcut, layout === "shortcut");
  assert.equal(seedScreenSupportsShortcut({screen: "UnrelatedScreen"}), false);
});

test("PNG round-trip and repeat masks separate stable residuals from temporal noise", () => {
  const image = {width: 2, height: 3, pixels: Buffer.alloc(24, 255)};
  assert.deepEqual(decodePng(encodePng(image)), image);
  const clone = () => ({...image, pixels: Buffer.from(image.pixels)});
  const webgl = [clone(), clone(), clone()], webgpu = [clone(), clone(), clone()];
  for (const snapshot of webgpu) snapshot.pixels[0] = 0;
  webgpu[1].pixels[4] = 0;
  const result = compareCaptures(webgl, webgpu);
  assert.equal(result.regions.full.rawDifferent, 1); assert.equal(result.regions.full.stableDifferent, 1); assert.equal(result.regions.full.unstable, 1);
  assert.equal(result.regions.full.stableFraction, 0.2);
  assert.deepEqual([...result.heatmap.pixels.subarray(0, 8)], [255, 0, 0, 255, 255, 170, 0, 255]);
  assert.equal(captureViews({x: 4.2, y: 70, z: -2}).topdown[1], 110);
  assert.deepEqual(validateCaptureViews(captureViews({x: 4.2, y: 70, z: -2})), captureViews({x: 4.2, y: 70, z: -2}));
  assert.throws(() => validateCaptureViews({horizon: [0, 0, 0, 0, 0]}), /four named/);
  const invalid = captureViews({x: 4.2, y: 70, z: -2}); invalid.high[1] = Infinity;
  assert.throws(() => validateCaptureViews(invalid), /four named/);
});

test("M4 position persistence tolerance remains strict", async () => {
  const harness = await readFile(new URL("../port/scripts/minecraft-263-singleplayer-cdp.mjs", import.meta.url), "utf8");
  assert.match(harness, /< 2\.5/);
});

test("camera settlement expressions handle negative coordinates and pitch", () => {
  for (const camera of Object.values(captureViews({x: -12.2, y: -60, z: -2.3}))) {
    const player = Object.fromEntries(["x", "y", "z", "yaw", "pitch"].map((name, index) => [name, camera[index]]));
    const context = vm.createContext({window: {__gaiusMinecraftState: {player},
      __gaiusSectionAudit: {uncompiled: 0, at: Date.now()}, __gaiusChunkPipelineTelemetry: {compileBacklog: 0, uploadBacklog: 0}}});
    assert.equal(vm.runInContext(captureCameraReadyExpression(camera), context), true);
    player.pitch += 1;
    assert.equal(vm.runInContext(captureCameraReadyExpression(camera), context), false);
  }
});

test("pixel acceptance rejects temporal changes and mismatched capture provenance", async () => {
  const root = join(output, "synthetic-pixel-policy");
  const directories = [join(root, "webgl2"), join(root, "webgpu")];
  for (const directory of directories) await mkdir(directory, {recursive: true});
  const cameras = captureViews({x: 0, y: 67, z: 0});
  const captures = ["webgl2", "webgpu"].map(backend => ({complete: true, profile: "fixture", seed: "fixture",
    settings: {animationTick: 0}, options: [], stageReceipt: {originalSha256: "1".repeat(64), stagedSha256: "2".repeat(64)},
    views: Object.fromEntries(Object.entries(cameras).map(([name, camera]) => [name, {camera: [...camera],
      files: [0, 1, 2].map(repeat => `${name}-${repeat}.png`),
      states: [0, 1, 2].map(() => ({backend, animationTick: 0, animationApplications: 1,
        options: {cloudsOff: true, particlesMinimal: true},
        player: Object.fromEntries(["x", "y", "z", "yaw", "pitch"].map((field, index) => [field, camera[index]]))})),
    }]))}));
  const save = async mode => {
    for (const [backendIndex, capture] of captures.entries()) {
      await writeFile(join(directories[backendIndex], "capture.json"), JSON.stringify(capture));
      for (const [name, view] of Object.entries(capture.views)) for (const [repeat, filename] of view.files.entries()) {
        const image = {width: 1, height: 3, pixels: Buffer.alloc(12, 255)};
        if (backendIndex === 1 && name === "horizon" && (mode === "different" || mode === "unstable" && repeat === 1)) image.pixels[0] = 0;
        await writeFile(join(directories[backendIndex], filename), encodePng(image));
      }
    }
  };
  for (const [mode, verdict] of [["same", "PASS"], ["different", "FAIL"], ["unstable", "UNSTABLE"]]) {
    await save(mode);
    assert.equal((await reportCaptureDiff(...directories, join(root, mode))).verdict, verdict);
  }
  captures[1].stageReceipt.stagedSha256 = "3".repeat(64); await save("same");
  await assert.rejects(reportCaptureDiff(...directories, join(root, "wrong-stage")), /same staged/);
  for (const capture of captures) delete capture.stageReceipt; await save("same");
  await assert.rejects(reportCaptureDiff(...directories, join(root, "no-identity")), /identity is required/);
  for (const capture of captures) capture.clientIdentity = {artifact: {sha256: "4".repeat(64)}}; await save("same");
  assert.equal((await reportCaptureDiff(...directories, join(root, "native-identity"))).verdict, "PASS");
  captures[1].views.horizon.camera[4] -= 1; await save("same");
  await assert.rejects(reportCaptureDiff(...directories, join(root, "wrong-camera")), /camera horizon/);
});

test("failed vanilla freeze commands leave an incomplete receipt and restore ticking", async () => {
  const directory = join(output, "synthetic-capture-failure"); await mkdir(directory, {recursive: true});
  const commands = [], evaluations = [];
  await assert.rejects(captureDeterministicWorld({
    command: async text => { commands.push(text); return {ok: text.startsWith("/tick ")}; },
    evaluate: async expression => { evaluations.push(expression); return true; },
    waitFor: async () => {}, key: async () => {}, sleep: async () => {}, screenshot: async () => true,
    player: {x: 0, y: 67, z: 0}, outputPath: directory, seed: "fixture", profile: "fixture",
  }), /not acknowledged/);
  const receipt = JSON.parse(await readFile(join(directory, "capture.json"), "utf8"));
  assert.equal(receipt.complete, false); assert.match(receipt.error, /not acknowledged/);
  assert.equal(receipt.cleanup.unfreeze.ok, true);
  assert.deepEqual(commands, ["/tick freeze", "/gamerule advance_time false", "/tick unfreeze"]);
  assert.match(evaluations.at(-1), /enabled=false/);
});


test("capture writes the vanilla cloud option and freezes before settling", async () => {
  assert.ok(CAPTURE_OPTIONS.includes("renderClouds:false"));
  assert.ok(!CAPTURE_OPTIONS.some(option => option.startsWith("clouds:")));
  const calls = [];
  await freezeCaptureWorld(async text => { calls.push(text); return {ok: true}; });
  calls.push("settle");
  assert.deepEqual(calls, ["/tick freeze", "settle"]);
  await assert.rejects(freezeCaptureWorld(async () => ({ok: false})), /not acknowledged/);
  const runner = await readFile(new URL("../port/scripts/minecraft-263-singleplayer-cdp.mjs", import.meta.url), "utf8");
  assert.ok(runner.indexOf("report.captureEarlyFreeze") < runner.indexOf("Date.now() + settleMs"),
    "capture freezes before the world settling interval");
});
