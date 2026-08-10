#!/usr/bin/env node

import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {readFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";

const scriptsDirectory = fileURLToPath(new URL(".", import.meta.url));
const repositoryRoot = fileURLToPath(new URL("../..", import.meta.url));
const scheduler = await readFile(
  new URL("../src/main/java/dev/gaius/browser/BrowserRenderScheduler.java", import.meta.url),
  "utf8",
);
const patcher = await readFile(
  new URL("../tools/src/main/java/dev/gaius/tools/MinecraftClientPatcher.java", import.meta.url),
  "utf8",
);
const patcher262 = await readFile(
  new URL("../tools/src/main/java/dev/gaius/tools/Minecraft262BrowserPatcher.java", import.meta.url),
  "utf8",
);
const config = JSON.parse(await readFile(new URL("../config.json", import.meta.url), "utf8"));
const profile = JSON.parse(await readFile(
  new URL(`../${config.versionProfile}`, import.meta.url),
  "utf8",
));
const version = String(profile.id);
const overlayJar = `${repositoryRoot}/port/work/overlays/client-named-${version}-gaius.jar`;

for (const contract of [
  "MAX_UPLOAD_ALLOCATIONS_PER_FRAME = 8",
  "UPLOAD_WORK_BUDGET_NANOS = 2_000_000L",
  "MAX_UPLOAD_RETRY_YIELDS = 2_048",
  "MAX_UPLOAD_RETRY_NANOS = 5_000_000_000L",
  "scheduleDispatcher(",
  "rememberDispatcherContinuation(",
  "finishDispatcherRun(",
  "disposeDispatcher(",
  "shouldUploadNext(",
  "finishUploadBuffer(",
  "releaseUploadBuffer(",
  "requestEmergencyUpload(",
  "awaitUploadRetry(",
  "clearUploadRetry(",
  "TModernRuntimeSupport.yieldToEventLoop(1)",
  "emergencyUploadRequests",
  "emergencyUploadDrains",
  "emergencyUploadDeferrals",
  "uploadRetryYields",
  "uploadRetryNoProgressResumes",
  "uploadRetryCancellations",
  "lastTaskDrainCount",
  "currentUploadDrainCount",
  "uploadBudgetExhaustions",
  "dispatcherUploadDeferrals",
  "Window.cancelAnimationFrame",
  "longestTaskMillis",
]) {
  assert.ok(scheduler.includes(contract), `missing render scheduler contract: ${contract}`);
}
assert.ok(
  scheduler.includes("Math.max(pendingTasks(), compileBacklog)"),
  "section extraction backpressure ignores the dispatcher backlog",
);
assert.ok(
  scheduler.includes("QUEUE.addFirst(state.runner)"),
  "dispatcher work is not prioritized over generic deferred work",
);
assert.ok(
  scheduler.includes("if (uploadBacklog > 0)") &&
    scheduler.includes("deferDispatcherUntilNextFrame(state)"),
  "dispatcher can refill a non-rotatable staging buffer before pending uploads drain",
);

for (const contract of [
  "patchCurrentUberGpuBufferUploadBudget(node)",
  '"shouldUploadNext"',
  '"finishUploadBuffer"',
  '"releaseUploadBuffer"',
  '"scheduleDispatcher"',
  '"rememberDispatcherContinuation"',
  '"finishDispatcherRun"',
  '"disposeDispatcher"',
  "patchCurrentSectionTaskQueueBrowserPriorities",
]) {
  assert.ok(patcher.includes(contract), `missing render bytecode patch contract: ${contract}`);
}
for (const contract of [
  "patchSectionRenderEmergencyUpload",
  "patchSectionRenderTaskRetryYields",
  '"requestEmergencyUpload"',
  '"awaitUploadRetry"',
  '"clearUploadRetry"',
  '"uploadTerrainBuffersToGpu"',
]) {
  assert.ok(patcher262.includes(contract), `missing 26.2 progress patch contract: ${contract}`);
}

function javap(className) {
  return execFileSync(
    "javap",
    ["-classpath", overlayJar, "-p", "-c", className],
    {cwd: scriptsDirectory, encoding: "utf8", maxBuffer: 32 * 1024 * 1024},
  );
}

function method(bytecode, signature, nextSignature) {
  const start = bytecode.indexOf(signature);
  assert.notEqual(start, -1, `missing bytecode method: ${signature}`);
  const end = nextSignature ? bytecode.indexOf(nextSignature, start + signature.length) : -1;
  return bytecode.slice(start, end === -1 ? bytecode.length : end);
}

const uber = javap("com.mojang.blaze3d.vertex.UberGpuBuffer");
const upload = method(
  uber,
  "public boolean uploadStagedAllocations",
  "private static <T, U extends T> void runCallbackUnchecked",
);
for (const contract of [
  "BrowserRenderScheduler.shouldUploadNext",
  "java/util/Iterator.remove",
  "BrowserRenderScheduler.finishUploadBuffer",
]) {
  assert.ok(upload.includes(contract), `patched upload is missing: ${contract}`);
}
assert.ok(
  !upload.includes("Object2ObjectOpenHashMap.clear"),
  "partial upload still clears unprocessed staged entries",
);
assert.ok(
  !upload.includes("ObjectOpenHashSet.clear"),
  "partial upload still clears skip markers for unprocessed entries",
);
const close = method(uber, "public void close()", "static {};");
assert.ok(
  close.includes("BrowserRenderScheduler.releaseUploadBuffer"),
  "UberGpuBuffer.close does not release scheduler telemetry ownership",
);

const dispatcher = javap("net.minecraft.client.renderer.chunk.SectionRenderDispatcher");
const runTask = method(dispatcher, "private void runTask()", "public void setCameraPosition");
const schedule = method(dispatcher, "private void schedule", "public void clearCompileQueue");
const dispose = method(dispatcher, "public void dispose()", "public java.lang.String getStats");
assert.ok(schedule.includes("BrowserRenderScheduler.scheduleDispatcher"),
  "dispatcher schedule does not use the coalesced runner");
assert.ok(runTask.includes("BrowserRenderScheduler.rememberDispatcherContinuation"),
  "dispatcher continuation still enqueues an empty tail runner");
assert.equal(
  runTask.split("BrowserRenderScheduler.finishDispatcherRun").length - 1,
  3,
  "not every 26.2 runTask return reports the real dispatcher backlog",
);
assert.ok(dispose.includes("BrowserRenderScheduler.disposeDispatcher"),
  "dispatcher dispose does not release its queued runner");
const renderSection = javap(
  "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection",
);
const addSectionBuffers = method(
  renderSection,
  "private boolean addSectionBuffersToUberBuffer",
  "private void lambda$addSectionBuffersToUberBuffer$1",
);
assert.ok(
  addSectionBuffers.includes("BrowserRenderScheduler.requestEmergencyUpload") &&
    addSectionBuffers.indexOf("BrowserRenderScheduler.requestEmergencyUpload") <
      addSectionBuffers.indexOf("SectionRenderDispatcher.uploadTerrainBuffersToGpu"),
  "staging-capacity retry can still spin without forcing one progress upload",
);
for (const taskName of ["CompileTask", "ResortTransparencyTask"]) {
  const task = javap(
    `net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$${taskName}`,
  );
  const doTask = method(task, "public net.minecraft.client.renderer.chunk."
    + "SectionRenderDispatcher$RenderSection$SectionTask$SectionTaskResult doTask", "public void cancel");
  assert.ok(doTask.includes("BrowserRenderScheduler.awaitUploadRetry"),
    `${taskName} can retry staging in one uninterruptible JS turn`);
  assert.ok(doTask.includes("BrowserRenderScheduler.clearUploadRetry"),
    `${taskName} retains retry telemetry after completing`);
  assert.ok(doTask.includes("Method cancel:()V"),
    `${taskName} has no bounded-retry cancellation path`);
  assert.ok(!doTask.includes("RenderSystem.isOnRenderThread"),
    `${taskName} still bypasses the asynchronous upload retry`);
  assert.ok(!doTask.includes("Thread.onSpinWait"),
    `${taskName} still retains a desktop spin wait`);
}

const uploadBudgetCheck = scheduler.indexOf(
  "if (currentUploadDrainCount >= MAX_UPLOAD_ALLOCATIONS_PER_FRAME)",
  scheduler.indexOf("public static boolean shouldUploadNext"),
);
const emergencyDrainCheck = scheduler.indexOf(
  "if (emergencyUploadEntriesRemaining > 0)",
  scheduler.indexOf("public static boolean shouldUploadNext"),
);
assert.ok(uploadBudgetCheck >= 0 && uploadBudgetCheck < emergencyDrainCheck,
  "emergency uploads can bypass the per-frame allocation hard limit");

const queue = javap("net.minecraft.client.renderer.chunk.SectionTaskDynamicQueue");
const dirtyClassification = method(
  queue,
  "private static boolean browserIsDirtyCompile",
  "private double browserDistance",
);
assert.equal(
  dirtyClassification.split("instanceof").filter((part) => part.includes("CompileTask")).length,
  1,
  "dirty compile classification does not exclude transparency resort tasks",
);
assert.ok(dirtyClassification.includes("SectionTask.isRecompile"),
  "dirty compile classification ignores the recompile flag");
const gameRenderer = javap("net.minecraft.client.renderer.GameRenderer");
assert.ok(gameRenderer.includes("BrowserRenderScheduler.beginFrame"),
  "upload budget is not reset from the real render-frame boundary");

const pending = Array.from({length: 19}, (_, index) => index);
const callbacks = [];
const drains = [];
while (pending.length > 0) {
  let drained = 0;
  while (pending.length > 0 && drained < 8) {
    callbacks.push(pending.shift());
    drained++;
  }
  drains.push(drained);
}
assert.deepEqual(drains, [8, 8, 3], "max-entry frame budget model regressed");
assert.deepEqual(callbacks, Array.from({length: 19}, (_, index) => index),
  "resumable upload model lost or duplicated callbacks");

console.log("Render pipeline backpressure smoke passed");
