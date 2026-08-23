#!/usr/bin/env node

import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {access, copyFile, mkdir, mkdtemp, readFile, rm} from "node:fs/promises";
import {existsSync} from "node:fs";
import {homedir, tmpdir} from "node:os";
import {basename, delimiter, join} from "node:path";
import {fileURLToPath} from "node:url";

const repositoryRoot = fileURLToPath(new URL("../..", import.meta.url));
const nativePath = (value) => {
  if (!value) return value;
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
};
const profileIdFromPath = (value) => basename(nativePath(value).replaceAll("\\", "/"))
  .replace(/\.json$/, "");
const requestedProfile = process.env.GAIUS_MINECRAFT_VERSION
  || (process.env.GAIUS_VERSION_PROFILE_PATH
    ? profileIdFromPath(process.env.GAIUS_VERSION_PROFILE_PATH) : "26.2");
if (requestedProfile !== "26.2") {
  throw new Error(`Minecraft 26.2 P1 patcher smoke is 26.2-only; got ${requestedProfile}`);
}
const rawClientJar = join(repositoryRoot, "port/work/26.2/client-named.jar");
const raw121ClientJar = join(repositoryRoot, "port/work/1.21.11/client-named.jar");
const toolsSource = join(repositoryRoot, "port/tools/src/main/java/dev/gaius/tools");
const asmRoot = join(homedir(), ".m2/repository/org/ow2/asm");
const asm = join(asmRoot, "asm/9.8/asm-9.8.jar");
const asmTree = join(asmRoot, "asm-tree/9.8/asm-tree-9.8.jar");
const asmAnalysis = join(asmRoot, "asm-analysis/9.8/asm-analysis-9.8.jar");
const verifierSource = join(repositoryRoot, "port/scripts/fixtures/GaiusChunkLayerBytecodeVerifier.java");
const forbiddenDeepWorldgenPatchHelpers = Object.freeze([
  "patchFeatureDecorationCooperation",
  "patchCarverCooperation",
  "patchJigsawCooperation",
  "patchTemplatePlacementCooperation",
]);
const synchronousDeepWorldgenClasses = Object.freeze([
  "net.minecraft.world.level.chunk.ChunkGenerator",
  "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator",
  "net.minecraft.world.level.levelgen.carver.WorldCarver",
  "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement",
  "net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate",
  "net.minecraft.world.level.lighting.LightEngine",
  "net.minecraft.world.level.chunk.LevelChunkSection",
  "net.minecraft.world.level.levelgen.NoiseChunk",
  "net.minecraft.world.level.levelgen.SurfaceSystem",
  "net.minecraft.world.level.biome.Climate$RTree$SubTree",
]);

function jdkTool(name) {
  for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME]
    .filter(Boolean).map(nativePath)) {
    const candidate = join(home, "bin", name);
    // `jar -version` is not a portable probe (JDK 25 exits non-zero), while
    // the executable itself is perfectly usable.  Prefer an explicit JDK
    // binary whenever it exists and let execFileSync resolve `.exe` on Windows.
    if (existsSync(candidate) || existsSync(`${candidate}.exe`)) return candidate;
    try {
      execFileSync(candidate, ["-version"], {encoding: "utf8", stdio: "ignore"});
      return candidate;
    } catch {
      // Try the next configured JDK, then PATH.
    }
  }
  return name;
}

function method(bytecode, signature, nextSignature) {
  const start = bytecode.indexOf(signature);
  assert.notEqual(start, -1, `missing bytecode method: ${signature}`);
  const end = nextSignature ? bytecode.indexOf(nextSignature, start + signature.length) : -1;
  return bytecode.slice(start, end === -1 ? bytecode.length : end);
}

function occurrences(haystack, needle) {
  return haystack.split(needle).length - 1;
}

function bytecodeInstructions(methodBytecode) {
  return methodBytecode.split(/\r?\n/).flatMap(line => {
    const match = line.match(/^\s*(\d+):\s+(.*)$/);
    return match ? [{offset: Number(match[1]), instruction: match[2]}] : [];
  });
}

function graphicsPresetApply(bytecode) {
  return method(bytecode,
    "public void apply(net.minecraft.client.Minecraft);",
    "private static <T> void set(");
}

function graphicsPresetDistanceConstants(applyBytecode, getter) {
  const instructions = bytecodeInstructions(applyBytecode);
  const matches = instructions.flatMap((entry, index) =>
    entry.instruction.includes(`Options.${getter}:`) ? [index] : []);
  assert.equal(matches.length, 3,
    `GraphicsPreset.apply must have three ${getter} setter calls`);
  return matches.map(index => (instructions[index + 1]?.instruction ?? "").replace(/\s+/g, " "));
}

function graphicsPresetCustomReturn(applyBytecode) {
  const target = applyBytecode.match(/default:\s+(\d+)/);
  assert.ok(target, "GraphicsPreset.apply ordinal switch has no CUSTOM default");
  const entry = bytecodeInstructions(applyBytecode)
    .find(instruction => instruction.offset === Number(target[1]));
  assert.equal(entry?.instruction, "return",
    "GraphicsPreset.apply CUSTOM arm is no longer the default return arm");
  return entry.instruction;
}

const HOLDERS_PER_TURN = 16;

function cursorModel(radius, stop = null, batchLimit = HOLDERS_PER_TURN) {
  const visits = [];
  const batches = [];
  let batch = [];
  let active = true;
  let yieldPending = false;
  for (let x = -radius; x <= radius && active; x++) {
    for (let z = -radius; z <= radius && active; z++) {
      const index = visits.length;
      const outcome = stop?.index === index ? stop.kind : "success";
      const visit = {x, z, outcome};
      visits.push(visit);
      if (outcome !== "success") {
        active = false;
        yieldPending = false;
        if (batch.length) batches.push(batch);
        continue;
      }
      batch.push(visit);
      if (x === radius && z === radius) {
        active = false;
      }
      if (batch.length === batchLimit || !active) {
        batches.push(batch);
        batch = [];
        // One zero-delay continuation separates bounded batches.  The final
        // batch also crosses this barrier before scheduled futures are drained.
        yieldPending = true;
        yieldPending = false;
      }
    }
  }
  return {visits, batches, active, yieldPending};
}

function pendingDependencyLayerModel(
  turns,
  dependencyOf = new Map(),
  {drainAfterEachTurn = false} = {},
) {
  const submitted = [];
  const submittedSet = new Set();
  const completed = [];
  const completedSet = new Set();
  const pending = [];
  // Keep the planned turn shape separate from the turns actually submitted.
  // The old one-holder await stops after turn 0, but its intended schedule is
  // still [1, ...] and must be visible to the regression assertion.
  const batches = turns.map(turn => [...turn]);
  const submittedBatches = [];
  const drains = [];

  function drainPending(stage) {
    let progress = true;
    const drained = [];
    while (progress) {
      progress = false;
      const remaining = [];
      for (const item of pending) {
        const dependency = item.dependsOn;
        if (dependency == null || completedSet.has(dependency)) {
          completed.push(item.holder);
          completedSet.add(item.holder);
          drained.push(item.holder);
          progress = true;
        } else {
          remaining.push(item);
        }
      }
      pending.splice(0, pending.length, ...remaining);
    }
    drains.push({stage, drained, pending: pending.map(item => item.holder)});
  }

  for (const [turnIndex, turn] of turns.entries()) {
    const submittedBatch = [];
    for (const holder of turn) {
      assert.equal(submittedSet.has(holder), false,
        `layer model submitted holder ${holder} more than once`);
      submittedSet.add(holder);
      submitted.push(holder);
      submittedBatch.push(holder);
      pending.push({holder, dependsOn: dependencyOf.get(holder) ?? null});
    }
    submittedBatches.push(submittedBatch);
    if (drainAfterEachTurn) {
      drainPending(`turn-${turnIndex}`);
      if (pending.length) {
        return {
          blocked: true,
          blockedAtTurn: turnIndex,
          waitedAt: submitted.length,
          submitted,
          completed,
          pending: pending.map(item => ({...item})),
          batches,
          submittedBatches,
          drains,
        };
      }
    }
  }

  // The fixed barrier drains only after every holder in the layer was submitted.
  drainPending("final-layer-barrier");
  return {
    blocked: pending.length !== 0,
    blockedAtTurn: null,
    waitedAt: submitted.length,
    submitted,
    completed,
    pending: pending.map(item => ({...item})),
    batches,
    submittedBatches,
    drains,
  };
}

function boundedLayerTurns(holderCount, batchLimit) {
  const turns = [];
  for (let holder = 0; holder < holderCount; holder += batchLimit) {
    turns.push(Array.from(
      {length: Math.min(batchLimit, holderCount - holder)},
      (_, offset) => holder + offset,
    ));
  }
  return turns;
}

function oneHolderTurns(holderCount) {
  return boundedLayerTurns(holderCount, 1);
}

function layerBarrierSafetyModel(holderCount, batchLimit, waitBetweenBatches) {
  const run = pendingDependencyLayerModel(
    boundedLayerTurns(holderCount, batchLimit),
    new Map([[0, holderCount - 1]]),
    {drainAfterEachTurn: waitBetweenBatches},
  );
  return {
    blocked: run.blocked,
    waitedAt: run.waitedAt,
    scheduled: run.submitted,
    batches: run.batches,
    submittedBatches: run.submittedBatches,
    completed: run.completed,
    pending: run.pending,
    drains: run.drains,
  };
}

for (const radius of [0, 1, 2]) {
  const run = cursorModel(radius);
  const expected = (radius * 2 + 1) ** 2;
  assert.equal(run.visits.length, expected,
    `cursor radius ${radius} must visit every holder exactly once`);
  assert.equal(run.active, false,
    `cursor radius ${radius} must close after its final holder`);
  assert.equal(new Set(run.visits.map(({x, z}) => `${x},${z}`)).size, expected,
    `cursor radius ${radius} must not duplicate holders`);
  assert.equal(run.visits.at(-1)?.outcome, "success",
    `cursor radius ${radius} must process its final holder before closing`);
  assert.equal(run.batches.length, Math.ceil(expected / HOLDERS_PER_TURN),
    `cursor radius ${radius} must use the minimum bounded continuation count`);
  assert.ok(run.batches.every(batch => batch.length > 0 && batch.length <= HOLDERS_PER_TURN),
    `cursor radius ${radius} exceeded the holder batch limit`);
}
const cancelledCursor = cursorModel(2, {index: 3, kind: "cancel"});
assert.equal(cancelledCursor.visits.length, 4,
  "cursor cancellation must stop at the cancellation holder");
assert.equal(cancelledCursor.visits.at(-1)?.outcome, "cancel",
  "cursor cancellation outcome must be observable at the holder boundary");
assert.equal(cancelledCursor.active, false,
  "cursor cancellation must clear active state");
assert.equal(cancelledCursor.yieldPending, false,
  "cursor cancellation must not retain a continuation future");
const failedCursor = cursorModel(2, {index: 4, kind: "failure"});
assert.equal(failedCursor.visits.length, 5,
  "cursor failure must stop at the failed holder");
assert.equal(failedCursor.visits.at(-1)?.outcome, "failure",
  "cursor failure outcome must be observable at the holder boundary");
assert.equal(failedCursor.active, false,
  "cursor failure must clear active state");
assert.equal(failedCursor.yieldPending, false,
  "cursor failure must not retain a continuation future");

const unsafeLayerOrder = layerBarrierSafetyModel(17, HOLDERS_PER_TURN, true);
assert.equal(unsafeLayerOrder.blocked, true,
  "the dependency fixture must expose per-batch await starvation");
assert.equal(unsafeLayerOrder.waitedAt, HOLDERS_PER_TURN,
  "the unsafe model must wait before the final holder is submitted");
const fixedLayerOrder = layerBarrierSafetyModel(17, HOLDERS_PER_TURN, false);
assert.equal(fixedLayerOrder.blocked, false,
  "the active cursor must submit the complete layer before waiting");
assert.deepEqual(fixedLayerOrder.batches.map(batch => batch.length), [16, 1],
  "a 17-holder layer must span two bounded turns without an intermediate layer wait");
assert.deepEqual(fixedLayerOrder.submittedBatches.map(batch => batch.length), [16, 1],
  "the fixed barrier must submit both bounded turns before draining");
assert.deepEqual(fixedLayerOrder.scheduled,
  Array.from({length: 17}, (_, holder) => holder),
  "the fixed barrier must submit all 17 holders before waiting");
assert.equal(fixedLayerOrder.waitedAt, 17,
  "the fixed model must reach the layer barrier only after every holder is submitted");
assert.equal(fixedLayerOrder.pending.length, 0,
  "the fixed layer barrier must drain every pending holder future");
assert.deepEqual(fixedLayerOrder.completed.sort((a, b) => a - b),
  Array.from({length: 17}, (_, holder) => holder),
  "the fixed layer barrier must complete all holders after the final batch");
assert.equal(fixedLayerOrder.drains.length, 1,
  "the fixed model must not drain any holder batch before the final barrier");
assert.equal(fixedLayerOrder.drains.at(-1)?.stage, "final-layer-barrier",
  "the fixed layer must drain pending futures only at its final barrier");

const oldOneHolderOrder = pendingDependencyLayerModel(
  oneHolderTurns(17),
  new Map([[0, 16]]),
  {drainAfterEachTurn: true},
);
assert.equal(oldOneHolderOrder.blocked, true,
  "the old one-holder await order must block on a same-layer dependency");
assert.equal(oldOneHolderOrder.blockedAtTurn, 0,
  "the old order must await holder 0 before submitting its dependency holder");
assert.deepEqual(oldOneHolderOrder.batches.map(batch => batch.length),
  Array.from({length: 17}, () => 1),
  "the old regression model must use one holder per browser turn");
assert.deepEqual(oldOneHolderOrder.pending, [{holder: 0, dependsOn: 16}],
  "the old blocked order must retain holder 0 pending on holder 16");
assert.deepEqual(oldOneHolderOrder.submitted, [0],
  "the old await model must not submit the dependency holder after blocking");

await Promise.all([
  access(rawClientJar),
  access(raw121ClientJar),
  access(asm),
  access(asmTree),
  access(asmAnalysis),
  access(verifierSource),
]);
const browserPatcherSource = await readFile(
  join(toolsSource, "Minecraft262BrowserPatcher.java"),
  "utf8",
);
assert.match(browserPatcherSource, /BROWSER_HOLDERS_PER_TURN = 16/,
  "26.2 holder batching must retain the reviewed 16-holder upper bound");
const clientPatcherSource = await readFile(
  join(toolsSource, "MinecraftClientPatcher.java"),
  "utf8",
);
for (const helper of forbiddenDeepWorldgenPatchHelpers) {
  assert.equal(browserPatcherSource.includes(helper), false,
    `${helper} must not exist under the synchronous deep-worldgen policy`);
}
for (const contract of [
  "browserWorldgenBeginServerWorkTurn()",
  "method.instructions.insertBefore(instruction, browserWorldgenBeginServerWorkTurn())",
  "method.instructions.insert(instruction, browserWorldgenCheckpoint())",
  "browserWorldgenBeginTaskWork()",
  "browserWorldgenEndTaskWork()",
  "instrumentBrowserTaskScope(",
  '"MinecraftServer.pollTask"',
  '"beginTaskWork",\n                "(Ljava/lang/String;)I"',
  '"endTaskWork",\n                "(I)V"',
]) {
  assert.ok(clientPatcherSource.includes(contract),
    `missing server tick scheduler boundary contract: ${contract}`);
}
assert.ok(browserPatcherSource.includes("requireNoServerWorkTurnReset"),
  "26.2 task patcher does not guard the shared server-work clock from per-task resets");
for (const contract of [
  '"beginTaskWork",\n                "(Ljava/lang/String;)I"',
  '"endTaskWork",\n                "(I)V"',
]) {
  assert.ok(browserPatcherSource.includes(contract),
    `missing 26.2 task-scope token contract: ${contract}`);
}
assert.ok(browserPatcherSource.includes(
  'writeComputeFrames(node, root.resolve(owner + ".class"))',
), "26.2 task patcher does not recompute frames after task-scope instrumentation");
const root = await mkdtemp(join(tmpdir(), "gaius-mc262-p1-"));
try {
  const classes = join(root, "classes");
  const clientPatches = join(root, "client-patches");
  const browserPatches = join(root, "browser-patches");
  const generic121Patches = join(root, "generic-121-patches");
  const clientJar = join(root, "client.jar");
  const generic121Jar = join(root, "client-1.21.11.jar");
  await Promise.all([
    mkdir(classes, {recursive: true}),
    mkdir(clientPatches, {recursive: true}),
    mkdir(browserPatches, {recursive: true}),
    mkdir(generic121Patches, {recursive: true}),
    copyFile(rawClientJar, clientJar),
    copyFile(raw121ClientJar, generic121Jar),
  ]);

  const javac = jdkTool("javac");
  const java = jdkTool("java");
  const jar = jdkTool("jar");
  const javap = jdkTool("javap");
  // Java's classpath separator follows the host OS, not the path separator
  // used to compose the individual jar paths.  The smoke is also run from
  // PowerShell on Windows, where ':' silently turns the second jar into an
  // invalid drive-qualified classpath entry.
  const classpath = [asm, asmTree].join(delimiter);
  execFileSync(javac, [
    "--release", "21", "-proc:none", "-classpath", classpath, "-d", classes,
    join(toolsSource, "MinecraftClientPatcher.java"),
    join(toolsSource, "Minecraft262BrowserPatcher.java"),
  ], {encoding: "utf8", timeout: 30_000});
  execFileSync(java, ["-classpath", [classes, classpath].join(delimiter),
    "dev.gaius.tools.MinecraftClientPatcher", clientJar, clientPatches], {
    encoding: "utf8", timeout: 30_000,
  });
  execFileSync(jar, ["--update", "--file", clientJar, "-C", clientPatches, "."], {
    encoding: "utf8", timeout: 30_000,
  });
  execFileSync(java, ["-classpath", [classes, classpath].join(delimiter),
    "dev.gaius.tools.Minecraft262BrowserPatcher", clientJar, browserPatches], {
    encoding: "utf8", timeout: 30_000,
  });
  execFileSync(jar, ["--update", "--file", clientJar, "-C", browserPatches, "."], {
    encoding: "utf8", timeout: 30_000,
  });

  const verifierClasspath = [asm, asmTree, asmAnalysis].join(delimiter);
  execFileSync(javac, [
    "--release", "21", "-proc:none", "-classpath", verifierClasspath,
    "-d", classes, verifierSource,
  ], {encoding: "utf8", timeout: 30_000});
  const verifierOutput = execFileSync(java, [
    "-classpath", [classes, verifierClasspath].join(delimiter),
    "GaiusChunkLayerBytecodeVerifier", clientJar, "26.2",
  ], {encoding: "utf8", timeout: 30_000});
  assert.match(verifierOutput, /BASIC_VERIFIER_OK .*ChunkGenerationTask\.class/,
    "ASM BasicVerifier did not validate ChunkGenerationTask");
  assert.match(verifierOutput, /BASIC_VERIFIER_OK .*BrowserChunkGenerationYield\.class/,
    "ASM BasicVerifier did not validate BrowserChunkGenerationYield");
  assert.match(verifierOutput, /CFG_VERIFIER_OK net\/minecraft\/server\/level\/ChunkGenerationTask/,
    "ASM CFG verifier did not validate the chunk layer barrier");
  assert.match(verifierOutput,
    /PROFILE_CFG_OK 26\.2 net\/minecraft\/server\/level\/ChunkGenerationTask/,
    "ASM profile verifier did not validate 26.2 holder pulse paths");
  process.stdout.write(verifierOutput);

  const rawGraphics = execFileSync(javap, ["-classpath", rawClientJar, "-p", "-c",
    "net.minecraft.client.GraphicsPreset"], {
    encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000,
  });
  const patchedGraphics = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.client.GraphicsPreset"], {
    encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000,
  });
  const rawGraphicsApply = graphicsPresetApply(rawGraphics);
  const patchedGraphicsApply = graphicsPresetApply(patchedGraphics);
  assert.deepEqual(graphicsPresetDistanceConstants(patchedGraphicsApply, "renderDistance"),
    ["bipush 6", "bipush 16", "bipush 32"],
    "26.2 FAST render distance was not overlaid to 6");
  assert.deepEqual(graphicsPresetDistanceConstants(patchedGraphicsApply, "simulationDistance"),
    ["bipush 4", "bipush 12", "bipush 12"],
    "26.2 FAST simulation distance was not overlaid to 4");
  assert.deepEqual(graphicsPresetDistanceConstants(rawGraphicsApply, "renderDistance"),
    ["bipush 8", "bipush 16", "bipush 32"],
    "26.2 raw FAST render distance shape changed");
  assert.deepEqual(graphicsPresetDistanceConstants(rawGraphicsApply, "simulationDistance"),
    ["bipush 6", "bipush 12", "bipush 12"],
    "26.2 raw FAST simulation distance shape changed");
  assert.equal(graphicsPresetCustomReturn(patchedGraphicsApply),
    graphicsPresetCustomReturn(rawGraphicsApply),
    "26.2 CUSTOM graphics preset arm changed");

  // The generic 1.21.11 path never invokes Minecraft262BrowserPatcher.  Run it on
  // a disposable 1.21 jar and verify its FAST values remain vanilla 8/6.
  execFileSync(java, ["-classpath", [classes, classpath].join(delimiter),
    "dev.gaius.tools.MinecraftClientPatcher", generic121Jar, generic121Patches,
    "1.21.11"], {encoding: "utf8", timeout: 60_000});
  execFileSync(jar, ["--update", "--file", generic121Jar, "-C", generic121Patches, "."], {
    encoding: "utf8", timeout: 30_000,
  });
  const generic121Graphics = execFileSync(javap, ["-classpath", generic121Jar, "-p", "-c",
    "net.minecraft.client.GraphicsPreset"], {
    encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000,
  });
  const generic121Apply = graphicsPresetApply(generic121Graphics);
  assert.deepEqual(graphicsPresetDistanceConstants(generic121Apply, "renderDistance"),
    ["bipush 8", "bipush 16", "bipush 32"],
    "1.21.11 FAST render distance was changed by the 26.2 overlay path");
  assert.deepEqual(graphicsPresetDistanceConstants(generic121Apply, "simulationDistance"),
    ["bipush 6", "bipush 12", "bipush 12"],
    "1.21.11 FAST simulation distance was changed by the 26.2 overlay path");

  const deepWorldgenBytecode = execFileSync(javap, [
    "-classpath", clientJar, "-p", "-c", ...synchronousDeepWorldgenClasses,
  ], {
    encoding: "utf8", maxBuffer: 32 * 1024 * 1024, timeout: 30_000,
  });
  assert.doesNotMatch(deepWorldgenBytecode, /BrowserWorldgenScheduler/,
    "deep worldgen bytecode must contain zero scheduler calls");

  const bytecode = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.server.level.ChunkGenerationTask",
    "net.minecraft.server.MinecraftServer",
    "net.minecraft.server.level.DistanceManager",
    "net.minecraft.server.level.LoadingChunkTracker",
    "net.minecraft.server.level.ServerChunkCache",
    "com.mojang.blaze3d.vertex.UberGpuBuffer",
    "net.minecraft.client.renderer.GameRenderer"], {
    encoding: "utf8", maxBuffer: 32 * 1024 * 1024, timeout: 30_000,
  });
  const generationWait = method(bytecode,
    "private java.util.concurrent.CompletableFuture<?> waitForScheduledLayer();",
    "private static net.minecraft.server.level.GenerationChunkHolder lambda$create$0");
  const generationWaitInstructions = bytecodeInstructions(generationWait);
  const generationWaitBackedges = generationWaitInstructions.flatMap((entry, index) => {
    const match = entry.instruction.match(/^goto(?:_w)?\s+(\d+)\s*$/);
    return match && Number(match[1]) < entry.offset ? [{entry, index}] : [];
  });
  assert.equal(generationWaitBackedges.length, 1,
    "ChunkGenerationTask.waitForScheduledLayer loop topology changed");
  for (const {entry, index} of generationWaitBackedges) {
    const previous = generationWaitInstructions[index - 1]?.instruction ?? "";
    const beforePrevious = generationWaitInstructions[index - 2]?.instruction ?? "";
    assert.match(previous, /BrowserWorldgenScheduler\.pulse/,
      `ChunkGenerationTask.waitForScheduledLayer backedge ${entry.offset} is missing its cooperative pulse`);
    assert.doesNotMatch(beforePrevious, /BrowserWorldgenScheduler\.pulse/,
      `ChunkGenerationTask.waitForScheduledLayer backedge ${entry.offset} received a duplicate cooperative pulse`);
  }
  assert.equal(occurrences(generationWait, "BrowserWorldgenScheduler.pulse"), 1,
    "waitForScheduledLayer must pulse once per completed-future drain backedge");
  assert.doesNotMatch(generationWait, /BrowserWorldgenScheduler\.checkpoint/,
    "waitForScheduledLayer must use bounded pulses rather than unconditional checkpoints");
  const runUntilWait = method(bytecode,
    "public java.util.concurrent.CompletableFuture<?> runUntilWait();",
    "private void scheduleNextLayer");
  const scheduleLayer = method(bytecode,
    "private void scheduleLayer(net.minecraft.world.level.chunk.status.ChunkStatus, boolean);",
    "private int getRadiusForLayer");
  const runUntilWaitInstructions = bytecodeInstructions(runUntilWait);
  const runUntilWaitBackedges = runUntilWaitInstructions.flatMap((entry, index) => {
    const match = entry.instruction.match(/^goto(?:_w)?\s+(\d+)\s*$/);
    return match && Number(match[1]) < entry.offset
      ? [{entry, index, target: Number(match[1])}] : [];
  });
  assert.equal(runUntilWaitBackedges.length, 1,
    "ChunkGenerationTask.runUntilWait must retain one loop backedge");
  const yieldFieldIndex = runUntilWaitInstructions.findIndex(entry =>
    entry.instruction.includes("Field browserLayerYield"));
  assert.ok(yieldFieldIndex > 0,
    "runUntilWait yield gate field load is missing from the patched bytecode");
  const yieldGateStart = runUntilWaitInstructions[yieldFieldIndex - 1];
  assert.match(yieldGateStart.instruction, /^aload_0|^aload\s+0$/,
    "runUntilWait yield gate does not begin with its receiver load");
  assert.equal(runUntilWaitBackedges[0].target, yieldGateStart.offset,
    "runUntilWait backedge bypasses the holder-yield gate");
  const activeFieldIndex = runUntilWaitInstructions.findIndex(entry =>
    entry.instruction.includes("Field browserLayerActive"));
  const firstScheduleNextIndex = runUntilWaitInstructions.findIndex(entry =>
    entry.instruction.includes("Method scheduleNextLayer:()V"));
  const firstLayerWaitIndex = runUntilWaitInstructions.findIndex(entry =>
    entry.instruction.includes("Method waitForScheduledLayer:"));
  assert.ok(yieldFieldIndex < activeFieldIndex
      && activeFieldIndex < firstScheduleNextIndex
      && firstScheduleNextIndex < firstLayerWaitIndex,
  "active cursor must resume scheduleNextLayer before waitForScheduledLayer is reachable");
  assert.equal(occurrences(runUntilWait, "Method scheduleNextLayer:()V"), 2,
    "runUntilWait must retain active-resume and vanilla fresh-layer scheduling paths");
  assert.equal(occurrences(runUntilWait, "BrowserWorldgenScheduler.beginServerWorkTurn"), 0,
    "runUntilWait must not reset the shared tick budget per task invocation");
  assert.equal(occurrences(runUntilWait, "BrowserWorldgenScheduler.beginTaskWork"), 1,
    "runUntilWait must enter one active-work task scope");
  assert.match(runUntilWait,
    /String ChunkGenerationTask\.runUntilWait[\s\S]*BrowserWorldgenScheduler\.beginTaskWork:\(Ljava\/lang\/String;\)I/,
    "runUntilWait must label and store the integer task-scope token");
  assert.match(runUntilWait, /BrowserWorldgenScheduler\.endTaskWork:\(I\)V/,
    "runUntilWait must close task scopes with the integer token");
  assert.ok(occurrences(runUntilWait, "BrowserWorldgenScheduler.endTaskWork") >= 3,
    "runUntilWait must close its scope on returns and exceptions");
  assert.match(runUntilWait, /Exception table:[\s\S]*Throwable/,
    "runUntilWait task scope has no catch-all exception cleanup");
  assert.match(scheduleLayer, /Exception table:[\s\S]*Throwable/,
    "scheduleLayer must clean cursor state when holder work throws");
  const scheduleLayerExceptionCleanup = scheduleLayer.slice(scheduleLayer.lastIndexOf("astore"));
  assert.match(scheduleLayerExceptionCleanup, /Field browserLayerActive/,
    "scheduleLayer exception path must clear active cursor state");
  assert.match(scheduleLayerExceptionCleanup, /Field browserLayerYield/,
    "scheduleLayer exception path must clear pending continuation state");
  const scheduleLayerInstructions = bytecodeInstructions(scheduleLayer);
  const batchBackedges = scheduleLayerInstructions.flatMap((entry, index) => {
    const match = entry.instruction.match(/^if_icmplt\s+(\d+)\s*$/);
    return match && Number(match[1]) < entry.offset
      ? [{entry, index, target: Number(match[1])}] : [];
  });
  assert.equal(batchBackedges.length, 1,
    "scheduleLayer must retain one bounded holder-batch backedge");
  assert.match(scheduleLayerInstructions[batchBackedges[0].index - 1]?.instruction ?? "",
    /(?:bipush\s+16|ldc(?:_w)?\s+.*\/\/ int 16)/,
    "scheduleLayer batch backedge must enforce the 16-holder upper bound");
  assert.ok(scheduleLayer.indexOf("scheduleChunkInLayer") < scheduleLayer.indexOf("Platform.schedule"),
    "scheduleLayer must submit holders before publishing its batch continuation");
  const runServer = method(bytecode, "protected void runServer", "private void");
  const tickStart = runServer.indexOf("BrowserWorldgenScheduler.beginServerWorkTurn");
  const processTick = runServer.indexOf("processPacketsAndTick");
  const tickCheckpoint = runServer.indexOf("BrowserWorldgenScheduler.checkpoint");
  assert.equal(occurrences(runServer, "BrowserWorldgenScheduler.beginServerWorkTurn"), 1,
    "runServer must reset the scheduler clock exactly once per tick");
  assert.equal(occurrences(runServer, "BrowserWorldgenScheduler.checkpoint"), 1,
    "runServer must checkpoint exactly once per tick");
  assert.ok(tickStart >= 0 && processTick > tickStart && tickCheckpoint > processTick,
    "server tick must reset before processPacketsAndTick and checkpoint after it");
  const pollTask = method(bytecode, "protected boolean pollTask();", "private boolean pollTaskInternal");
  assert.equal(occurrences(pollTask, "BrowserWorldgenScheduler.beginTaskWork"), 1,
    "MinecraftServer.pollTask must enter one active-work task scope");
  assert.match(pollTask,
    /String MinecraftServer\.pollTask[\s\S]*BrowserWorldgenScheduler\.beginTaskWork:\(Ljava\/lang\/String;\)I/,
    "MinecraftServer.pollTask must label and store the integer task-scope token");
  assert.match(pollTask, /BrowserWorldgenScheduler\.endTaskWork:\(I\)V/,
    "MinecraftServer.pollTask must close task scopes with the integer token");
  const pollBegin = pollTask.indexOf(
    "BrowserWorldgenScheduler.beginTaskWork:(Ljava/lang/String;)I",
  );
  const pollPump = pollTask.indexOf("BrowserIntegratedServerMain.pumpUrgentPacketsIfPending");
  assert.ok(pollBegin >= 0 && pollPump > pollBegin,
    "MinecraftServer.pollTask must pump urgent packets after token initialization");
  assert.ok(occurrences(pollTask, "BrowserWorldgenScheduler.endTaskWork") >= 2,
    "MinecraftServer.pollTask must close its scope on returns and exceptions");
  const distance = method(bytecode, "public boolean runAllUpdates", "public void addPlayer");
  assert.equal(occurrences(distance, "int 2147483647"), 2,
    "DistanceManager no longer preserves vanilla full-propagation semantics");
  assert.equal(occurrences(distance, "distanceManagerUpdateBudget"), 0,
    "DistanceManager still returns between partial ticket-propagation batches");
  assert.equal(occurrences(distance, "pulseDistanceManager"), 3,
    "DistanceManager futures/ticket loops are not each cooperatively bounded");
  const distanceInstructions = bytecodeInstructions(distance);
  const backwardGotos = distanceInstructions.flatMap((entry, index) => {
    // ASM may widen a branch after the browser patch set grows the method;
    // javap prints that form as `goto_w` and keeps alignment spaces.
    const match = entry.instruction.match(/^goto(?:_w)?\s+(\d+)\s*$/);
    return match && Number(match[1]) < entry.offset ? [{entry, index}] : [];
  });
  assert.equal(backwardGotos.length, 3,
    "DistanceManager loop topology changed; update the one-pulse-per-backedge contract");
  for (const {entry, index} of backwardGotos) {
    const previous = distanceInstructions[index - 1]?.instruction ?? "";
    const beforePrevious = distanceInstructions[index - 2]?.instruction ?? "";
    assert.match(previous, /BrowserWorldgenScheduler\.pulseDistanceManager/,
      `DistanceManager backedge ${entry.offset} is missing its cooperative pulse`);
    assert.doesNotMatch(beforePrevious, /BrowserWorldgenScheduler\.pulseDistanceManager/,
      `DistanceManager backedge ${entry.offset} received a duplicate cooperative pulse`);
  }
  assert.equal(occurrences(distance, "InterfaceMethod java/util/Set.clear"), 1,
    "DistanceManager can clear newly queued future work after its snapshot");
  assert.equal(occurrences(distance, "InterfaceMethod it/unimi/dsi/fastutil/longs/LongSet.clear"), 1,
    "DistanceManager can clear newly queued ticket work after its snapshot");
  assert.ok(distance.indexOf("ArrayList.\"<init>\"") <
      distance.indexOf("InterfaceMethod java/util/Set.clear") &&
      distance.indexOf("InterfaceMethod java/util/Set.clear") <
        distance.indexOf("InterfaceMethod java/util/List.iterator"),
  "DistanceManager futures retain a live set until after the cooperative iterator starts");
  assert.ok(distance.indexOf("LongSet.toLongArray") <
      distance.indexOf("LongSet.clear"),
  "DistanceManager tickets are cleared before their snapshot is retained");
  assert.ok(distance.indexOf("LongSet.clear") < distance.indexOf("laload"),
    "DistanceManager can hold a live ticket iterator across a cooperative pulse");

  const loading = method(bytecode, "public int runDistanceUpdates", "static {}");
  assert.equal(occurrences(loading, "distanceManagerUpdateBudget"), 1,
    "LoadingChunkTracker does not bound its internal propagation batch");
  assert.equal(occurrences(loading, "recordDistanceManagerUpdates"), 1,
    "LoadingChunkTracker propagation telemetry is missing");
  assert.equal(occurrences(loading, "pulseDistanceManager"), 1,
    "LoadingChunkTracker cannot yield between internal propagation batches");
  assert.ok(loading.indexOf("Math.min") < loading.indexOf("runUpdates") &&
      loading.indexOf("runUpdates") < loading.indexOf("recordDistanceManagerUpdates") &&
      loading.indexOf("recordDistanceManagerUpdates") <
        loading.indexOf("pulseDistanceManager"),
  "LoadingChunkTracker lost batch-run-record-yield ordering");

  const broadcast = method(bytecode, "private void broadcastChangedChunks", "private void tickChunks");
  assert.ok(broadcast.includes("Set.toArray") && broadcast.includes("Set.clear"),
    "changed-chunk broadcast is not snapshot-backed");
  assert.equal(occurrences(broadcast, "Set.iterator"), 0,
    "changed-chunk broadcast can retain a live iterator across a pulse");
  assert.ok(broadcast.includes(
    "ChunkHolder.broadcastChanges:(Lnet/minecraft/world/level/chunk/LevelChunk;)V"),
  "changed-chunk broadcast uses an invalid LevelChunk method descriptor");
  assert.ok(broadcast.indexOf("Set.toArray") < broadcast.indexOf("Set.clear") &&
      broadcast.indexOf("Set.clear") < broadcast.indexOf("beginChunkBroadcast") &&
      broadcast.indexOf("beginChunkBroadcast") < broadcast.indexOf("pulseChunkBroadcast") &&
      broadcast.indexOf("pulseChunkBroadcast") < broadcast.indexOf("finishChunkBroadcast"),
  "changed-chunk broadcast no longer has snapshot-to-completion ordering");

  const upload = method(bytecode, "public boolean uploadStagedAllocations",
    "private static <T, U extends T> void runCallbackUnchecked");
  const cleanup = upload.slice(upload.indexOf("finishUploadBuffer"));
  assert.ok(cleanup.includes("beginUberNodeCleanup") && cleanup.includes("shouldCleanUberNode") &&
      cleanup.includes("finishUberNodeCleanup"),
  "UberGpuBuffer cleanup is not cursor-budgeted");
  assert.ok(cleanup.includes("List.remove:(I)Ljava/lang/Object;") &&
      !cleanup.includes("List.iterator"),
  "UberGpuBuffer cleanup still retains an unbounded iterator");
  assert.ok(cleanup.indexOf("beginUberNodeCleanup") < cleanup.indexOf("shouldCleanUberNode") &&
      cleanup.indexOf("shouldCleanUberNode") < cleanup.indexOf("List.remove:(I)") &&
      cleanup.indexOf("List.remove:(I)") < cleanup.indexOf("finishUberNodeCleanup"),
  "UberGpuBuffer cleanup cursor lifecycle order changed");

  const gameRenderer = method(bytecode,
    "public void extract(net.minecraft.client.DeltaTracker, boolean);",
    "private void extractCamera(net.minecraft.client.DeltaTracker, float, float);");
  const cameraExtract = gameRenderer.indexOf("Method extractCamera:");
  const targetingRefresh = gameRenderer.indexOf("BrowserTargeting.refreshFramePick");
  const levelExtract = gameRenderer.indexOf("LevelExtractor.extract");
  assert.ok(cameraExtract >= 0 && targetingRefresh > cameraExtract &&
      levelExtract > targetingRefresh,
  "frame targeting is not refreshed between camera and level extraction");
  const targetingInstructions = bytecodeInstructions(gameRenderer);
  const refreshInstructionIndex = targetingInstructions.findIndex(entry =>
    entry.instruction.includes("BrowserTargeting.refreshFramePick"));
  assert.ok(refreshInstructionIndex >= 0,
    "frame targeting refresh instruction is missing");
  assert.match(targetingInstructions[refreshInstructionIndex - 1]?.instruction ?? "",
    /^fload\s+6$/,
    "frame targeting does not use Camera.getCameraEntityPartialTicks");
  assert.doesNotMatch(targetingInstructions[refreshInstructionIndex - 1]?.instruction ?? "",
    /^fload\s+5$/,
    "frame targeting still uses the world partial tick and can drift");

  console.log("Minecraft 26.2 P1 patcher smoke passed", JSON.stringify({
    scheduledLayerPulses: occurrences(generationWait, "BrowserWorldgenScheduler.pulse"),
    distancePulses: occurrences(distance, "pulseDistanceManager"),
    graphicsPresetDistances: "6/4",
    oneTwentyOneFastDistances: "8/6",
    holderBatchLimit: HOLDERS_PER_TURN,
    layerBarrier: true,
    broadcastSnapshot: true,
    uberCleanupCursor: true,
    targetingPartialTickLocal: 6,
  }));
} finally {
  await rm(root, {recursive: true, force: true});
}
