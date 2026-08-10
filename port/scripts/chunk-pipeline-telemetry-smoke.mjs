#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

async function source(relative) {
  return readFile(new URL(relative, import.meta.url), "utf8");
}

function jsBody(sourceText, declaration) {
  const declarationAt = sourceText.indexOf(declaration);
  assert.notEqual(declarationAt, -1, `missing JSBody declaration: ${declaration}`);
  const marker = 'script = """';
  const bodyAt = sourceText.lastIndexOf(marker, declarationAt);
  const start = bodyAt + marker.length;
  const end = sourceText.indexOf('""")', start);
  assert.ok(bodyAt >= 0 && end > start && end < declarationAt,
    `missing JSBody script: ${declaration}`);
  return sourceText.slice(start, end);
}

const [
  scheduler,
  targeting,
  singleplayer,
  workerBootstrap,
  patcher,
  benchmark,
  performanceContract,
] = await Promise.all([
  source("../src/main/java/dev/gaius/browser/BrowserRenderScheduler.java"),
  source("../src/main/java/dev/gaius/browser/BrowserTargeting.java"),
  source("../src/main/java/dev/gaius/browser/BrowserSingleplayerClient.java"),
  source("../web/singleplayer/server-worker-bootstrap.js"),
  source("../tools/src/main/java/dev/gaius/tools/MinecraftClientPatcher.java"),
  source("./chrome-chunk-benchmark.mjs"),
  source("./performance-contract.json"),
]);

for (const contract of [
  "QUEUE_HIGH_WATER = 8",
  "MAX_TASKS_PER_FRAME = 8",
  "FRAME_WORK_BUDGET_NANOS = 2_000_000L",
  "MAX_COMPILE_RUNS_DURING_UPLOAD_PER_FRAME = 1",
  "UPLOAD_FRAME_DRAIN_COUNTS",
  "uploadFairShareDeferrals",
  "emergencyUploadRequests",
  "emergencyUploadDrains",
  "emergencyUploadDeferrals",
  "uploadRetryYields",
  "uploadRetryNoProgressResumes",
  "uploadRetryCancellations",
  "activeUploadRetryTasks",
  "uploadProgressEpoch",
  "compileRunsDuringUploadThisFrame",
  "shouldContinueDrain(completed, System.nanoTime() - startedAt)",
  "completed < MAX_TASKS_PER_FRAME",
  "completed == 0 || elapsedNanos < FRAME_WORK_BUDGET_NANOS",
  "QUEUE.addLast(command)",
  "backpressureEvents",
  "currentHighWaterMillis",
  "longestHighWaterMillis",
  "compileBacklog",
  "uploadBacklog",
  "longestTaskMillis",
  "longestUploadPassMillis",
  "state.droppedTasks=0",
]) {
  assert.ok(scheduler.includes(contract), "missing scheduler telemetry contract: " + contract);
}
assert.ok(!scheduler.includes("QUEUE.removeLast("), "render tasks must not be dropped");

for (const contract of [
  "minecraft.player.raycastHitResult(partialTick, camera.entity())",
  "__gaiusBenchmarkEnabled",
  "__gaiusTargetingTelemetry",
  "maxObservationsPerRenderedFrame",
  "maxObservationLagFrames",
]) {
  assert.ok(targeting.includes(contract), "missing targeting contract: " + contract);
}
const recordTargetingFrame = Function(
  "updated", "partialTick", "type", "blockX", "blockY", "blockZ",
  "cameraX", "cameraY", "cameraZ",
  jsBody(targeting, "private static native void recordTargetingFrame("),
);
globalThis.__gaiusBenchmarkEnabled = true;
globalThis.__gaiusFrameTelemetry = {visibleFrameCount: 0};
globalThis.__gaiusTargetingTelemetry = {updates: 0, skips: 0, lastAt: 0};
recordTargetingFrame(true, 0.25, 1, 2, 3, 4, 5, 6, 7);
globalThis.__gaiusFrameTelemetry.visibleFrameCount = 1;
recordTargetingFrame(true, 0.5, 1, 3, 3, 4, 5.5, 6, 7);
assert.equal(globalThis.__gaiusTargetingTelemetry.maxObservationsPerRenderedFrame, 1);
assert.equal(globalThis.__gaiusTargetingTelemetry.maxObservationLagFrames, 1);
assert.equal(globalThis.__gaiusTargetingTelemetry.ring.count, 2);
recordTargetingFrame(true, 0.75, 1, 4, 3, 4, 6, 6, 7);
assert.equal(globalThis.__gaiusTargetingTelemetry.maxObservationsPerRenderedFrame, 2,
  "same-frame duplicate targeting observation was not detected");
delete globalThis.__gaiusTargetingTelemetry;
delete globalThis.__gaiusFrameTelemetry;
delete globalThis.__gaiusBenchmarkEnabled;

for (const contract of [
  "__gaiusWorkerMessageTelemetry",
  "telemetry-ping",
  "telemetry-pong",
  "longestHeartbeatGapMillis",
  "configuredIntervalMillis",
  "longestHeartbeatDelayMillis",
  "copyScalarTelemetry",
  "state.chunkPriority",
  "state.network",
  "__gaiusResetTelemetry",
]) {
  assert.ok(singleplayer.includes(contract), "missing Worker telemetry contract: " + contract);
}
assert.ok(workerBootstrap.includes("message.type === \"telemetry-ping\""),
  "Worker bootstrap does not answer heartbeat pings");
assert.ok(workerBootstrap.includes("type: \"telemetry-pong\""),
  "Worker bootstrap does not emit heartbeat pongs");
for (const contract of [
  "snapshotScalarTelemetry",
  "root.__gaiusChunkPriorityStats",
  "root.__gaiusNetworkStats",
  "copied >= 64",
  "Number.isFinite(current)",
]) {
  assert.ok(workerBootstrap.includes(contract),
    "Worker heartbeat is missing scalar snapshot contract: " + contract);
}
const heartbeatBranch = workerBootstrap.slice(
  workerBootstrap.indexOf('if (message.type === "telemetry-ping")'),
  workerBootstrap.indexOf('if (message.type === "start" || message.type === "attach-port")'),
);
assert.ok(!heartbeatBranch.includes("...root.__gaiusChunkPriorityStats"),
  "Worker heartbeat must not spread an unbounded chunk telemetry object");

const shouldContinueDrain = (completed, elapsedNanos) =>
  completed < 8 && (completed === 0 || elapsedNanos < 2_000_000);
let cheapTasks = 0;
let elapsedNanos = 0;
while (shouldContinueDrain(cheapTasks, elapsedNanos)) {
  cheapTasks++;
  elapsedNanos += 100_000;
}
assert.equal(cheapTasks, 8, "multiple cheap section tasks did not drain in one frame");
let expensiveTasks = 0;
elapsedNanos = 0;
while (shouldContinueDrain(expensiveTasks, elapsedNanos)) {
  expensiveTasks++;
  elapsedNanos += 3_000_000;
}
assert.equal(expensiveTasks, 1, "an expensive section task did not yield after one task");

const extractorPatch = patcher.slice(
  patcher.indexOf("patchCurrentLevelExtractorBrowserSectionCompileThrottle"),
  patcher.indexOf("private static AbstractInsnNode findPreviousNew"),
);
for (const contract of ["retryNextFrame", "canScheduleSection", '"(I)Z"']) {
  assert.ok(extractorPatch.includes(contract), "missing dirty retry contract: " + contract);
}
assert.ok(extractorPatch.includes("extract.instructions.insert(setNotDirty, retryNextFrame)"),
  "section retry branch no longer skips the dirty clear");

for (const contract of [
  "patchCurrentSectionRenderDispatcherTelemetry(currentUpload)",
  "patchUberGpuBufferBrowserTelemetry",
  "\"getCompileQueueSize\"",
  "\"recordUploadBacklogResult\"",
  "\"stagedAllocations\"",
]) {
  assert.ok(patcher.includes(contract), "missing bytecode telemetry contract: " + contract);
}

for (const contract of [
  "warmupMillis",
  "performanceMillis",
  "heapMillis",
  "profile frame-performance thresholds",
  "no contracted freeze signal",
  "queue high-water contract",
  "Worker and MessagePort remain below 500 ms",
  "post-GC memory trend",
  "gameplay block authority",
  "longestHeartbeatDelayMillis",
]) {
  assert.ok(benchmark.includes(contract), "missing benchmark acceptance contract: " + contract);
}
const parsedPerformanceContract = JSON.parse(performanceContract);
const newChunksProfile = parsedPerformanceContract.profiles["traversal-6-4"];
assert.equal(newChunksProfile.warmupMs, 30_000, "new-chunk warmup must remain 30 seconds");
assert.equal(newChunksProfile.durationMs, 300_000,
  "new-chunk performance sample must remain five minutes");
assert.equal(newChunksProfile.gates.averageFpsMin, 120,
  "new-chunk average FPS gate regressed");
assert.equal(newChunksProfile.gates.onePercentLowFpsMin, 60,
  "new-chunk 1% low gate regressed");
assert.equal(newChunksProfile.gates.freezeCountMax, 0,
  "new-chunk >=500 ms freeze gate regressed");
assert.equal(parsedPerformanceContract.startup.requiredConsecutiveTerrainFrames, 16,
  "world readiness no longer requires a sustained terrain frame sequence");
assert.equal(parsedPerformanceContract.startup.minimumBlockHitFrames, 3,
  "world readiness no longer proves live crosshair targeting");
assert.equal(parsedPerformanceContract.startup.minimumVisualSamples, 3,
  "world readiness no longer proves canvas terrain output");
assert.ok(parsedPerformanceContract.startup.externalSmokeRequired.includes(
  "world-readiness-smoke.mjs",
), "strict world-readiness state machine is not part of release evidence");
assert.equal(
  parsedPerformanceContract.runtimeInvariants.renderPipeline.uploadAllocationsPerFrameMax,
  8,
  "terrain upload hard frame cap regressed",
);
assert.equal(
  parsedPerformanceContract.runtimeInvariants.renderPipeline.uploadRetryCancellationsMax,
  0,
  "bounded terrain upload retry cancellations must fail release evidence",
);

const histogram = new Uint32Array(4001);
histogram[Math.floor(1000 / 120 * 4)] = 990;
histogram[Math.floor(1000 / 60 * 4)] = 10;
const total = histogram.reduce((sum, count) => sum + count, 0);
assert.equal(total, 1000, "frame histogram fixture is malformed");
let cumulative = 0;
let p99Bucket = 0;
for (let index = 0; index < histogram.length; index++) {
  cumulative += histogram[index];
  if (cumulative >= Math.ceil(total * 0.99)) {
    p99Bucket = index;
    break;
  }
}
assert.ok(1000 / (p99Bucket / 4) >= 60,
  "1% low fixture does not match the benchmark percentile convention");

console.log("Chunk pipeline telemetry smoke passed");
