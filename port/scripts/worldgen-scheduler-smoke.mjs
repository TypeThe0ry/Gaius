#!/usr/bin/env node

import assert from "node:assert/strict";
import {execFileSync, spawnSync} from "node:child_process";
import {mkdir, mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import {tmpdir} from "node:os";
import path from "node:path";

const nativePath = (value) => {
  if (!value) return value;
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
};
const source = relative => readFile(new URL(relative, import.meta.url), "utf8");
const [worldgen, packets, server, client, patcher262, clientPatcher] = await Promise.all([
  source("../src/main/java/dev/gaius/browser/BrowserWorldgenScheduler.java"),
  source("../src/main/java/dev/gaius/browser/BrowserPacketScheduler.java"),
  source("../src/main/java/dev/gaius/browser/BrowserIntegratedServerMain.java"),
  source("../src/main/java/dev/gaius/browser/BrowserSingleplayerClient.java"),
  source("../tools/src/main/java/dev/gaius/tools/Minecraft262BrowserPatcher.java"),
  source("../tools/src/main/java/dev/gaius/tools/MinecraftClientPatcher.java"),
]);

function numericConstant(name) {
  const match = worldgen.match(new RegExp(
    `private static final (?:double|int) ${name} = ([0-9.]+);`,
  ));
  assert.ok(match, `missing numeric scheduler constant: ${name}`);
  return Number(match[1]);
}

function jsBody(methodDeclaration) {
  const methodAt = worldgen.indexOf(methodDeclaration);
  assert.notEqual(methodAt, -1, `missing method declaration: ${methodDeclaration}`);
  const annotationAt = worldgen.lastIndexOf("@JSBody", methodAt);
  const marker = 'script = """';
  const scriptAt = worldgen.indexOf(marker, annotationAt);
  assert.ok(annotationAt >= 0 && scriptAt >= 0 && scriptAt < methodAt,
    `missing JSBody script: ${methodDeclaration}`);
  const start = scriptAt + marker.length;
  const end = worldgen.indexOf('""")', start);
  assert.ok(end > start && end < methodAt, `unterminated JSBody: ${methodDeclaration}`);
  return worldgen.slice(start, end);
}

const constants = {
  defaultBudget: numericConstant("DEFAULT_SLICE_MILLIS"),
  minBudget: numericConstant("MIN_ADAPTIVE_SLICE_MILLIS"),
  recovery: numericConstant("BUDGET_RECOVERY_MILLIS"),
  moderateDelay: numericConstant("MODERATE_YIELD_DELAY_MILLIS"),
  busyDelay: numericConstant("BUSY_YIELD_DELAY_MILLIS"),
  clockCheck: numericConstant("CLOCK_CHECK_INTERVAL"),
  networkCheck: numericConstant("NETWORK_CHECK_INTERVAL"),
  minProgress: numericConstant("MIN_PROGRESS_PULSES_BEFORE_NETWORK_PREEMPTION"),
  maxNetworkWait: numericConstant("MAX_NETWORK_WAIT_PULSES"),
  maxPulses: numericConstant("MAX_PULSES_PER_TURN"),
  distanceManagerDefault: numericConstant("DEFAULT_DISTANCE_MANAGER_UPDATE_BUDGET"),
  distanceManagerMin: numericConstant("MIN_DISTANCE_MANAGER_UPDATE_BUDGET"),
  distanceManagerMax: numericConstant("MAX_DISTANCE_MANAGER_UPDATE_BUDGET"),
};

assert.ok(worldgen.includes("TModernRuntimeSupport.yieldToEventLoop(0)"),
  "worldgen yield still requests a clamp-prone positive timer");
assert.ok(worldgen.includes("public static void beginServerWorkTurn()")
    && worldgen.includes("if (yieldActive) {\n            return;\n        }"),
  "worldgen server-tick clock reset is missing its reentrancy guard");
assert.ok(worldgen.includes("public static int beginTaskWork()")
    && worldgen.includes("public static int beginTaskWork(String taskLabel)")
    && worldgen.includes("recordSchedulerTaskLabel(taskLabel)")
    && worldgen.includes("public static void endTaskWork(int token)")
    && worldgen.includes("private static int taskWorkDepth")
    && worldgen.includes("activeWorkElapsedMillis")
    && worldgen.includes("reentrantTaskWorkDepth")
    && worldgen.includes("TASK_SCOPE_NONE")
    && worldgen.includes("TASK_SCOPE_NORMAL")
    && worldgen.includes("TASK_SCOPE_REENTRANT")
    && worldgen.includes("if (token == TASK_SCOPE_REENTRANT)")
    && worldgen.includes("if (deferredTaskScopeEnds == 0)"),
  "worldgen task active-work token scope is missing");
assert.ok(worldgen.includes("deferredTaskScopeEnds = 0;"),
  "worldgen task-scope finally does not clear stale deferred closes");
assert.ok(worldgen.includes("sliceStartedAtMillis = now;")
    && worldgen.includes("deadlineMillis = now + currentBudgetMillis"),
  "worldgen server-tick clock reset does not preserve the current pulse budget");
const workTurnStart = worldgen.indexOf("public static void beginServerWorkTurn()");
const workTurnEnd = worldgen.indexOf("public static void pulse()", workTurnStart);
assert.ok(workTurnStart >= 0 && workTurnEnd > workTurnStart
    && !worldgen.slice(workTurnStart, workTurnEnd).includes("yieldToEventLoop"),
  "server work-turn boundary must not suspend and re-enter a CPS continuation");
assert.ok(!worldgen.slice(workTurnStart, workTurnEnd)
    .includes("activeWorkElapsedMillis = 0.0"),
  "server work-turn boundary discarded task work left by the wait loop");
assert.ok(clientPatcher.includes("browserWorldgenBeginServerWorkTurn()")
    && clientPatcher.includes("method.instructions.insert(instruction, browserWorldgenCheckpoint())")
    && clientPatcher.includes("method.instructions.insertBefore(instruction, browserWorldgenBeginServerWorkTurn())"),
  "server tick does not reset the clock before work and checkpoint after work");
assert.ok(clientPatcher.includes("browserWorldgenBeginTaskWork()")
    && clientPatcher.includes('"(Ljava/lang/String;)I"')
    && clientPatcher.includes("new LdcInsnNode(target)")
    && clientPatcher.includes("browserWorldgenEndTaskWork()")
    && clientPatcher.includes("instrumentBrowserTaskScope(")
    && clientPatcher.includes("\"MinecraftServer.pollTask\"")
    && clientPatcher.includes("pumpUrgentPacketsIfPending"),
  "MinecraftServer.pollTask has no active-work task scope");
assert.ok(patcher262.includes("browserWorldgenBeginTaskWork()")
    && patcher262.includes('"(Ljava/lang/String;)I"')
    && patcher262.includes("new LdcInsnNode(target)")
    && patcher262.includes("browserWorldgenEndTaskWork()")
    && patcher262.includes("instrumentBrowserTaskScope(runUntilWait")
    && patcher262.includes("TryCatchBlockNode"),
  "ChunkGenerationTask.runUntilWait has no return/exception task scope");
assert.ok(clientPatcher.includes("method.tryCatchBlocks.add(new TryCatchBlockNode(")
    && clientPatcher.includes("java/lang/Throwable"),
  "MinecraftServer.pollTask exception cleanup is not protected by a finally handler");
assert.equal(worldgen.split("BrowserIntegratedServerMain.pumpUrgentPackets()").length - 1, 2,
  "worldgen does not drain one bounded packet batch before and after a yield");
assert.ok(worldgen.includes("boolean yieldActive") && worldgen.includes("deferredYield"),
  "worldgen yield has no reentrancy gate");
assert.ok(worldgen.includes("pulsesInTurn >= MAX_PULSES_PER_TURN"),
  "worldgen turn has no hard pulse cap");
assert.ok(!worldgen.includes("pulseSparse") && !worldgen.includes("SPARSE_PULSE_INTERVAL"),
  "worldgen scheduler retained the deep-loop suspendable sampling path");
assert.ok(worldgen.includes("maxPulsesInTurn = Math.max(maxPulsesInTurn, pulsesInTurn)"),
  "worldgen does not track the actual maximum pulses in one scheduler turn");
assert.ok(worldgen.includes("progressPulsesInSlice >= "
    + "MIN_PROGRESS_PULSES_BEFORE_NETWORK_PREEMPTION"),
  "persistent network input can prevent all worldgen progress");
assert.ok(worldgen.includes("adaptiveBudgetMillis("),
  "worldgen slices do not use an adaptive budget");
assert.ok(worldgen.includes("BrowserWebSocketChannel.hasPendingInput()"),
  "worldgen does not observe transport input");
assert.ok(worldgen.includes("BrowserPacketScheduler.hasPendingPackets()"),
  "worldgen does not observe already-decoded packets");
assert.ok(worldgen.includes("decodedPacketQueue") && worldgen.includes("inboundQueuedBytes"),
  "adaptive pressure does not include transport and decoded queue depth");
assert.ok(!worldgen.includes("new Thread") && !worldgen.includes("Executor")
    && !worldgen.includes("CompletableFuture"),
  "worldgen scheduler introduces parallel server-state execution");
assert.ok(server.includes("private static PlayerList appliedDistancePlayerList")
    && server.includes("private static int appliedViewDistance = Integer.MIN_VALUE")
    && server.includes("private static int appliedSimulationDistance = Integer.MIN_VALUE")
    && server.includes("if (playerList != appliedDistancePlayerList)")
    && server.includes("if (appliedViewDistance != view || playerList.getViewDistance() != view)")
    && server.includes("if (appliedSimulationDistance != simulation")
    && server.includes("playerList.getSimulationDistance() != simulation)")
    && server.includes("current != null && !serverThreadExited")
    && server.includes("distanceApplyTelemetryEnabled()")
    && server.includes("integratedServerDistanceMaxViewApplyMillis")
    && server.includes("integratedServerDistanceMaxSimulationApplyMillis")
    && server.includes("Diagnostic telemetry is fail-open"),
  "integrated server repeats unchanged PlayerList distance traversals");
const distanceApplyStart = server.indexOf("private static void applyActiveDistances()");
const distanceApplyEnd = server.indexOf("public static void recordChunkBatchSent", distanceApplyStart);
const distanceApplySource = server.slice(distanceApplyStart, distanceApplyEnd);
assert.ok(distanceApplyStart >= 0 && distanceApplyEnd > distanceApplyStart
    && distanceApplySource.indexOf("playerList.setViewDistance(view)")
      < distanceApplySource.indexOf("appliedViewDistance = view")
    && distanceApplySource.indexOf("playerList.setSimulationDistance(simulation)")
      < distanceApplySource.indexOf("appliedSimulationDistance = simulation"),
  "distance setter cache was committed before the vanilla setter succeeded");

// Behavioural policy model for the source contract above. It covers the four
// regressions that matter without constructing Minecraft's concrete PlayerList.
const distanceCache = {
  owner: null,
  view: Number.MIN_SAFE_INTEGER,
  simulation: Number.MIN_SAFE_INTEGER,
};
const distanceCalls = {view: 0, simulation: 0};
function applyDistanceModel(owner, view, simulation, failSimulation = false, stopped = false) {
  if (stopped) return;
  if (owner !== distanceCache.owner) {
    distanceCache.owner = owner;
    distanceCache.view = Number.MIN_SAFE_INTEGER;
    distanceCache.simulation = Number.MIN_SAFE_INTEGER;
  }
  if (distanceCache.view !== view || owner.view !== view) {
    distanceCalls.view++;
    owner.view = view;
    distanceCache.view = view;
  }
  if (distanceCache.simulation !== simulation || owner.simulation !== simulation) {
    distanceCalls.simulation++;
    if (failSimulation) throw new Error("simulated setter failure");
    owner.simulation = simulation;
    distanceCache.simulation = simulation;
  }
}
const firstPlayerList = {view: 10, simulation: 10};
applyDistanceModel(firstPlayerList, 1, 1);
applyDistanceModel(firstPlayerList, 1, 1);
assert.deepEqual(distanceCalls, {view: 1, simulation: 1},
  "same PlayerList and distance pair was not idempotent");
firstPlayerList.view = 7;
applyDistanceModel(firstPlayerList, 1, 1);
assert.equal(distanceCalls.view, 2, "external view-distance mutation was not repaired");
assert.throws(() => applyDistanceModel(firstPlayerList, 1, 2, true),
  /simulated setter failure/);
applyDistanceModel(firstPlayerList, 1, 2);
assert.equal(distanceCalls.simulation, 3,
  "failed simulation setter was cached instead of retried");
const replacementPlayerList = {view: 1, simulation: 2};
applyDistanceModel(replacementPlayerList, 1, 2);
assert.deepEqual(distanceCalls, {view: 3, simulation: 4},
  "replacement PlayerList did not invalidate both cached distances");
applyDistanceModel(replacementPlayerList, 6, 4, false, true);
assert.deepEqual(distanceCalls, {view: 3, simulation: 4},
  "stopped server accepted a late distance update");
assert.ok(!worldgen.includes("setTimeout(") && !worldgen.includes("setInterval("),
  "worldgen scheduler owns a timer that can leak after shutdown");
assert.ok(worldgen.includes("recordSchedulerMarker(")
    && worldgen.includes("globalThis.__gaiusSlowProbeTelemetryEnabled !== true")
    && worldgen.includes("globalThis.__gaiusWorldgenSchedulerMarker")
    && worldgen.includes("Diagnostic telemetry is fail-open")
    && worldgen.includes('"task-end-underflow"'),
  "worldgen task scheduler is missing its opt-in slow-probe marker");
assert.ok(worldgen.includes("if (reentrantTaskWorkDepth <= 0)")
    && worldgen.includes("if (taskWorkDepth <= 0)"),
  "worldgen task-scope underflow paths are not fail-closed");
for (const field of [
  "sliceElapsedMillis",
  "p95SliceElapsedMillis",
  "p99SliceElapsedMillis",
  "p95YieldDelayMillis",
  "p99YieldDelayMillis",
  "maxSliceElapsedMillis",
  "budgetOverruns",
  "yieldDelayMillis",
  "queueDepth",
  "progressSlices",
  "noProgressSlices",
  "networkPreemptions",
]) {
  assert.ok(worldgen.includes(`stats.${field}`), `missing worldgen telemetry: ${field}`);
}
for (const field of [
  "checkpointOnlyYields",
  "checkpointOnlyP99YieldDelayMillis",
  "checkpointOnlyMaxYieldDelayMillis",
  "checkpointOnlyMaxQueueDepth",
  "checkpointOnlyMaxNetworkWaitPulses",
  "checkpointOnlyMaxReentrantYieldDepth",
]) {
  assert.ok(worldgen.includes(`stats.${field}`),
    `missing checkpoint-only worldgen telemetry: ${field}`);
}
assert.ok(worldgen.includes("boolean checkpointOnly = reason == YIELD_CHECKPOINT")
    && worldgen.includes("&& progressPulsesInSlice == 0;"),
  "checkpoint-only classification did not occur after packet/event-loop work");
assert.ok(worldgen.includes("__checkpointOnlyYieldDelayHistogram")
    && worldgen.includes("enumerable: false"),
  "checkpoint-only yield-delay histogram is not hidden from scalar telemetry");
assert.equal(constants.maxNetworkWait, 2,
  "network wait contract no longer permits one bounded unit of progress");
assert.ok(constants.minProgress > 0 && constants.maxPulses >= constants.minProgress,
  "worldgen progress and hard-cap constants are inconsistent");
assert.equal(constants.clockCheck, 1,
  "explicit worldgen boundaries no longer check their deadline immediately");
assert.equal(constants.networkCheck, 1,
  "explicit worldgen boundaries no longer check packet pressure immediately");
assert.equal(constants.distanceManagerDefault, 64,
  "DistanceManager default work budget changed without an explicit benchmark update");
assert.equal(constants.distanceManagerMin, 8,
  "DistanceManager minimum work budget no longer guarantees forward progress");
assert.equal(constants.distanceManagerMax, 512,
  "DistanceManager maximum work budget no longer bounds one server turn");
for (const contract of [
  "distanceManagerUpdateBudget()",
  "recordDistanceManagerUpdates(int processed)",
  "pulseDistanceManager()",
  "beginChunkBroadcast(int entries)",
  "pulseChunkBroadcast()",
  "finishChunkBroadcast(int entries)",
  "distanceManagerBudgetExhaustions",
  "chunkBroadcastBatches",
  "chunkBroadcastItems",
]) {
  assert.ok(worldgen.includes(contract), `missing cooperative worldgen contract: ${contract}`);
}
for (const contract of [
  "patchDistanceManagerCooperation",
  "snapshotChunkFutureUpdates",
  "patchLoadingChunkTrackerCooperation",
  "snapshotTicketReleases",
  "patchServerChunkBroadcastCooperation",
  '"distanceManagerUpdateBudget"',
  '"pulseDistanceManager"',
  '"beginChunkBroadcast"',
  '"pulseChunkBroadcast"',
  '"finishChunkBroadcast"',
  "toLongArray",
  "chunkHoldersToBroadcast",
]) {
  assert.ok(patcher262.includes(contract), `missing 26.2 cooperative patch contract: ${contract}`);
}
assert.ok(packets.includes("MAX_PACKETS_PER_BATCH = 16")
    && packets.includes("BATCH_BUDGET_NANOS = 2_000_000L"),
  "urgent packet handling is not count- and time-bounded");
assert.ok(server.includes("Thread.currentThread() != serverThread")
    && server.includes("urgentPacketPumpActive"),
  "urgent packet handling can leave the server thread or reenter");
assert.equal(constants.defaultBudget, 8,
  "worldgen default slice no longer protects 60 FPS network delivery");
assert.ok(client.includes("worldgenSliceMillis: 8"),
  "singleplayer no longer supplies the audited 8 ms configured ceiling");
assert.ok(client.includes("state.worldgen = copyScalarTelemetry"),
  "page telemetry does not expose Worker worldgen metrics");

const queueDepth = new Function(jsBody("private static native int networkQueueDepth()"));
globalThis.__gaiusNettyBridge = undefined;
globalThis.__gaiusNetworkStats = {
  decodedPacketQueue: 7,
  decodedSliceBacklog: 3,
  inboundQueuedBytes: 65_536,
};
assert.equal(queueDepth(), 7, "queue pressure does not report the deepest bounded stage");
delete globalThis.__gaiusNetworkStats;

const recordSchedulerMarkerSource = jsBody(
  "private static native void recordSchedulerMarker(",
);
assert.doesNotMatch(
  recordSchedulerMarkerSource,
  /^\s*(?:taskActiveWorkMillis|taskScopeWallMillis),\s*$/mu,
  "TeaVM JSBody parser does not accept shorthand task-context properties",
);
assert.match(recordSchedulerMarkerSource,
  /taskActiveWorkMillis:\s*taskActiveWorkMillis/,
  "task active-work context must use TeaVM-compatible explicit property syntax");
assert.match(recordSchedulerMarkerSource,
  /taskScopeWallMillis:\s*taskScopeWallMillis/,
  "task wall-time context must use TeaVM-compatible explicit property syntax");
const recordSchedulerMarker = new Function(
  "event",
  "token",
  "taskDepth",
  "reentrantDepth",
  "yielding",
  "activeWorkMillis",
  recordSchedulerMarkerSource,
);
const recordSchedulerTaskLabel = new Function(
  "taskLabel",
  jsBody("private static native void recordSchedulerTaskLabel("),
);
delete globalThis.__gaiusWorldgenSchedulerMarker;
delete globalThis.__gaiusSlowProbeTelemetryEnabled;
recordSchedulerTaskLabel("MinecraftServer.pollTask");
assert.equal(globalThis.__gaiusWorldgenSchedulerMarker, undefined,
  "worldgen task label mutated release state while disabled");
recordSchedulerMarker("task-start-normal", 1, 1, 0, false, 4);
assert.equal(globalThis.__gaiusWorldgenSchedulerMarker, undefined,
  "worldgen slow-probe marker mutated release state while disabled");
globalThis.__gaiusSlowProbeTelemetryEnabled = true;
recordSchedulerMarker("server-work-turn-start", 0, 0, 0, false, 0);
recordSchedulerTaskLabel("MinecraftServer.pollTask");
recordSchedulerMarker("task-start-normal", 1, 1, 0, false, 4);
recordSchedulerTaskLabel("ChunkGenerationTask.runUntilWait");
recordSchedulerMarker("task-start-nested", 0, 1, 0, false, 4);
assert.equal(globalThis.__gaiusWorldgenSchedulerMarker.currentTaskLabel,
  "MinecraftServer.pollTask", "normal task label was not retained");
assert.equal(globalThis.__gaiusWorldgenSchedulerMarker.currentNestedTaskLabel,
  "ChunkGenerationTask.runUntilWait", "nested task label was not retained");
assert.equal(globalThis.__gaiusWorldgenSchedulerMarker.currentTaskScopeId, 1,
  "normal task scope did not receive a bounded sequence id");
recordSchedulerMarker("task-end-nested", 0, 1, 0, false, 4);
recordSchedulerMarker("yield-start", 0, 1, 0, true, 12);
recordSchedulerMarker("yield-end", 0, 1, 0, false, 0);
recordSchedulerMarker("task-end-normal", 1, 0, 0, false, 6);
recordSchedulerMarker("server-work-turn-end", 3, 0, 0, false, 0);
assert.equal(globalThis.__gaiusWorldgenSchedulerMarker.taskScopesStarted, 1,
  "worldgen slow-probe marker did not count task entry");
assert.equal(globalThis.__gaiusWorldgenSchedulerMarker.taskScopesEnded, 1,
  "worldgen slow-probe marker did not count task exit");
assert.equal(globalThis.__gaiusWorldgenSchedulerMarker.lastTaskActiveWorkMillis, 14,
  "worldgen slow-probe marker did not isolate active task work across a yield");
assert.equal(globalThis.__gaiusWorldgenSchedulerMarker.activeTaskScope, false,
  "worldgen slow-probe marker retained a closed task scope");
assert.equal(globalThis.__gaiusWorldgenSchedulerMarker.yieldActive, false,
  "worldgen slow-probe marker retained a completed yield");
assert.equal(globalThis.__gaiusWorldgenSchedulerMarker.serverWorkTurnSequence, 1,
  "worldgen slow-probe marker did not count the server work turn");
assert.equal(globalThis.__gaiusWorldgenSchedulerMarker.serverWorkTurnActive, false,
  "worldgen slow-probe marker retained a completed server work turn");
assert.ok(globalThis.__gaiusWorldgenSchedulerMarker.lastTaskScopeWallMillis >= 0,
  "worldgen slow-probe marker omitted task wall time");
const maximumTaskContext = JSON.parse(
  globalThis.__gaiusWorldgenSchedulerMarker.maxTaskContext,
);
assert.equal(maximumTaskContext.taskScopeId, 1,
  "maximum task context lost its task scope id");
assert.equal(maximumTaskContext.taskLabel, "MinecraftServer.pollTask",
  "maximum task context lost its task label");
assert.ok(!Object.keys(globalThis.__gaiusWorldgenSchedulerMarker)
  .some(key => key.startsWith("__task")),
  "worldgen slow-probe marker leaked internal task accounting fields");
delete globalThis.__gaiusWorldgenSchedulerMarker;
recordSchedulerTaskLabel("ChunkGenerationTask.runUntilWait");
recordSchedulerMarker("task-start-reentrant", 2, 0, 1, true, 0);
assert.equal(globalThis.__gaiusWorldgenSchedulerMarker.activeTaskScope, true,
  "worldgen slow-probe marker omitted a reentrant-only task scope");
assert.equal(globalThis.__gaiusWorldgenSchedulerMarker.normalTaskScopeActive, false,
  "worldgen slow-probe marker confused reentrant work with the outer task clock");
recordSchedulerMarker("task-end-reentrant", 2, 0, 0, true, 0);
assert.equal(globalThis.__gaiusWorldgenSchedulerMarker.activeTaskScope, false,
  "worldgen slow-probe marker retained a closed reentrant-only task scope");
assert.equal(globalThis.__gaiusWorldgenSchedulerMarker.reentrantTaskScopesEnded, 1,
  "worldgen slow-probe marker did not count a matched reentrant close");
delete globalThis.__gaiusWorldgenSchedulerMarker;
recordSchedulerMarker("task-end-underflow", 2, 0, 0, true, 0);
assert.equal(globalThis.__gaiusWorldgenSchedulerMarker.taskScopeUnderflows, 1,
  "worldgen slow-probe marker did not count a reentrant/deferred underflow");
assert.equal(globalThis.__gaiusWorldgenSchedulerMarker.reentrantTaskScopesEnded || 0, 0,
  "worldgen slow-probe marker counted an unmatched reentrant scope as closed");
delete globalThis.__gaiusWorldgenSchedulerMarker;
Object.defineProperty(globalThis, "__gaiusWorldgenSchedulerMarker", {
  configurable: true,
  get() {
    throw new Error("poisoned diagnostic marker");
  },
});
assert.doesNotThrow(() => recordSchedulerMarker("task-start-normal", 1, 1, 0, false, 0),
  "worldgen diagnostic marker exception escaped into scheduler work");
assert.doesNotThrow(() => recordSchedulerTaskLabel("poisoned"),
  "worldgen diagnostic task label exception escaped into scheduler work");
delete globalThis.__gaiusWorldgenSchedulerMarker;
delete globalThis.__gaiusSlowProbeTelemetryEnabled;

const distanceBudget = new Function(
  "fallback",
  jsBody("private static native int configuredDistanceManagerUpdateBudget(int fallback)"),
);
globalThis.__gaiusDistanceManagerUpdateBudget = 1;
assert.equal(distanceBudget(constants.distanceManagerDefault), constants.distanceManagerMin,
  "DistanceManager lower budget clamp regressed");
globalThis.__gaiusDistanceManagerUpdateBudget = 10_000;
assert.equal(distanceBudget(constants.distanceManagerDefault), constants.distanceManagerMax,
  "DistanceManager upper budget clamp regressed");
globalThis.__gaiusDistanceManagerUpdateBudget = 37.9;
assert.equal(distanceBudget(constants.distanceManagerDefault), 37,
  "DistanceManager budget no longer uses a stable integer cap");
delete globalThis.__gaiusDistanceManagerUpdateBudget;

const recordDistanceUpdates = new Function(
  "budget",
  "processed",
  jsBody("private static native void recordDistanceManagerUpdatesJs(int budget, int processed)"),
);
const recordBroadcastStart = new Function(
  "entries",
  jsBody("private static native void recordChunkBroadcastStart(int entries)"),
);
const recordBroadcastItem = new Function(
  jsBody("private static native void recordChunkBroadcastItem()"),
);
const recordBroadcastFinish = new Function(
  "entries",
  jsBody("private static native void recordChunkBroadcastFinish(int entries)"),
);
globalThis.__gaiusWorldgenStats = undefined;
recordDistanceUpdates(64, 64);
recordDistanceUpdates(64, 13);
recordBroadcastStart(3);
recordBroadcastItem();
recordBroadcastItem();
recordBroadcastItem();
recordBroadcastFinish(3);
assert.deepEqual(globalThis.__gaiusWorldgenStats, {
  distanceManagerBatches: 2,
  distanceManagerUpdateBudget: 64,
  lastDistanceManagerUpdates: 13,
  totalDistanceManagerUpdates: 77,
  maxDistanceManagerUpdates: 64,
  distanceManagerBudgetExhaustions: 1,
  chunkBroadcastBatches: 1,
  lastChunkBroadcastEntries: 3,
  totalChunkBroadcastEntries: 3,
  maxChunkBroadcastEntries: 3,
  chunkBroadcastItems: 3,
  lastChunkBroadcastCompleted: 3,
  completedChunkBroadcastBatches: 1,
}, "worldgen cooperative telemetry lost a bounded batch");
delete globalThis.__gaiusWorldgenStats;

function drainBoundedQueue(queue, budget) {
  const current = queue.splice(0, Math.min(queue.length, budget));
  return current;
}

const distanceQueue = Array.from({length: 197}, (_, index) => index);
const distanceBatches = [];
const distanceProcessed = [];
while (distanceQueue.length > 0) {
  const batch = drainBoundedQueue(distanceQueue, constants.distanceManagerDefault);
  distanceBatches.push(batch.length);
  distanceProcessed.push(...batch);
}
assert.deepEqual(distanceBatches, [64, 64, 64, 5],
  "DistanceManager queue no longer resumes at the configured boundary");
assert.deepEqual(distanceProcessed, Array.from({length: 197}, (_, index) => index),
  "DistanceManager budget model dropped or reordered ticket work");

function drainSnapshot(live, onEntry) {
  const snapshot = [...live];
  live.clear();
  for (const entry of snapshot) onEntry(entry, live);
  return snapshot;
}

const dirtyTickets = new Set(["ticket-a", "ticket-b", "ticket-c"]);
const processedTickets = drainSnapshot(dirtyTickets, (entry, live) => {
  if (entry === "ticket-b") {
    live.add("ticket-b");
    live.add("ticket-new");
  }
});
assert.deepEqual(processedTickets, ["ticket-a", "ticket-b", "ticket-c"],
  "ticket snapshot changed the current server-turn ordering");
assert.deepEqual([...dirtyTickets], ["ticket-b", "ticket-new"],
  "ticket work queued during a cooperative yield was dropped");

const dirtyBroadcasts = new Set(["chunk-0", "chunk-1", "chunk-2"]);
const processedBroadcasts = drainSnapshot(dirtyBroadcasts, (entry, live) => {
  if (entry === "chunk-1") live.add("chunk-1");
});
assert.deepEqual(processedBroadcasts, ["chunk-0", "chunk-1", "chunk-2"],
  "broadcast snapshot changed its current ordering");
assert.deepEqual([...dirtyBroadcasts], ["chunk-1"],
  "broadcast work marked dirty during a pulse was cleared at batch end");

const reportSliceSource = jsBody("private static native void reportSlice(");
assert.doesNotMatch(
  reportSliceSource,
  /^\s*taskLabel,\s*$/mu,
  "TeaVM JSBody parser does not accept shorthand slice-context properties",
);
assert.match(reportSliceSource, /taskLabel:\s*taskLabel/,
  "slice label context must use TeaVM-compatible explicit property syntax");
const reportSlice = new Function(
  "reason",
  "networkPreemption",
  "progressPulses",
  "networkWaitPulses",
  "sliceElapsedMillis",
  "completedBudgetMillis",
  "nextBudgetMillis",
  "overrunMillis",
  "yieldDelayMillis",
  "queueDepthBefore",
  "queueDepthAfter",
  "reentrantRequests",
  "networkWaitPulseLimit",
  "maximumPulsesInTurn",
  "maximumReentrantYieldDepth",
  reportSliceSource,
);
const reportCheckpointOnlyYield = new Function(
  "networkWaitPulses",
  "yieldDelayMillis",
  "queueDepthBefore",
  "queueDepthAfter",
  "maximumReentrantYieldDepth",
  jsBody("private static native void recordCheckpointOnlyYield("),
);
globalThis.__gaiusWorldgenStats = undefined;
reportCheckpointOnlyYield(1, 2.25, 5, 1, 0);
reportCheckpointOnlyYield(2, 17.5, 1, 7, 1);
const checkpointOnlyTelemetry = globalThis.__gaiusWorldgenStats;
assert.equal(checkpointOnlyTelemetry.checkpointOnlyYields, 2,
  "checkpoint-only telemetry dropped a pure checkpoint");
assert.equal(checkpointOnlyTelemetry.checkpointOnlyMaxQueueDepth, 7,
  "checkpoint-only telemetry lost pre/post network queue pressure");
assert.equal(checkpointOnlyTelemetry.checkpointOnlyMaxNetworkWaitPulses, 2,
  "checkpoint-only telemetry lost network wait pulses");
assert.equal(checkpointOnlyTelemetry.checkpointOnlyMaxReentrantYieldDepth, 1,
  "checkpoint-only telemetry lost reentrant yield depth");
assert.equal(checkpointOnlyTelemetry.checkpointOnlyMaxYieldDelayMillis, 17.5,
  "checkpoint-only telemetry lost maximum yield delay");
assert.equal(checkpointOnlyTelemetry.checkpointOnlyP99YieldDelayMillis, 18,
  "checkpoint-only telemetry did not use its dedicated p99 histogram");
assert.equal(checkpointOnlyTelemetry.slices, undefined,
  "pure checkpoint telemetry was counted as an ordinary slice");
assert.equal(checkpointOnlyTelemetry.noProgressSlices, undefined,
  "pure checkpoint telemetry was counted as no-progress work");
assert.equal(Object.keys(checkpointOnlyTelemetry)
    .includes("__checkpointOnlyYieldDelayHistogram"), false,
  "checkpoint-only histogram leaked into scalar telemetry snapshots");
delete globalThis.__gaiusWorldgenStats;
globalThis.__gaiusWorldgenSliceMillis = 16;
globalThis.__gaiusWorldgenStats = undefined;
globalThis.__gaiusSlowProbeTelemetryEnabled = true;
delete globalThis.__gaiusWorldgenSchedulerMarker;
recordSchedulerTaskLabel("MinecraftServer.pollTask");
recordSchedulerMarker("task-start-normal", 1, 1, 0, false, 0);
for (let elapsed = 1; elapsed <= 100; elapsed++) {
  reportSlice(0, false, 4, 0, elapsed, 16, 8, Math.max(0, elapsed - 16), 1,
    0, 0, 0, constants.maxNetworkWait, 4, 1);
}
recordSchedulerTaskLabel("ChunkGenerationTask.runUntilWait");
recordSchedulerMarker("task-start-nested", 0, 1, 0, false, 100);
reportSlice(1, true, 2, 2, 500, 4, 2, 496, 500, 12, 0, 1,
  constants.maxNetworkWait, 2, 1);
const telemetry = globalThis.__gaiusWorldgenStats;
assert.equal(telemetry.slices, 101, "telemetry dropped completed slices");
assert.equal(telemetry.maxSliceElapsedMillis, 500, "telemetry lost the long slice");
assert.ok(telemetry.p95SliceElapsedMillis >= 95
    && telemetry.p95SliceElapsedMillis <= 98,
  `unexpected p95 slice telemetry: ${telemetry.p95SliceElapsedMillis}`);
assert.ok(telemetry.p99SliceElapsedMillis >= telemetry.p95SliceElapsedMillis,
  "p99 slice telemetry is below p95");
assert.ok(telemetry.budgetOverruns > 0 && telemetry.maxBudgetOverrunMillis === 496,
  "budget overrun telemetry is incomplete");
assert.equal(telemetry.maxYieldDelayMillis, 500, "yield delay telemetry lost the long load");
assert.ok(telemetry.p99YieldDelayMillis >= telemetry.p95YieldDelayMillis,
  "p99 yield delay telemetry is below p95");
assert.equal(telemetry.maxQueueDepth, 12, "queue depth telemetry lost burst pressure");
assert.equal(telemetry.networkPreemptions, 1, "network preemption telemetry is wrong");
assert.equal(telemetry.networkWaitPulseLimit, constants.maxNetworkWait,
  "network wait telemetry diverged from the Java contract");
assert.equal(telemetry.maxTurnPulses, 4, "worldgen turn maximum was not recorded");
assert.equal(telemetry.minimumBudgetMillis, 2, "adaptive budget floor was not recorded");
assert.equal(telemetry.maxReentrantYieldDepth, 1,
  "worldgen reentrant continuation depth was not recorded");
const maximumSliceContextText =
  globalThis.__gaiusWorldgenSchedulerMarker.maxSliceContext;
const maximumSliceContext = JSON.parse(maximumSliceContextText);
assert.equal(maximumSliceContext.reason, "network",
  "maximum slice context lost its yield reason");
assert.equal(maximumSliceContext.taskScopeId, 1,
  "maximum slice context lost its task scope id");
assert.equal(maximumSliceContext.taskLabel, "ChunkGenerationTask.runUntilWait",
  "maximum slice context lost the nested task label");
assert.equal(maximumSliceContext.sliceElapsedMillis, 500,
  "maximum slice context lost the long slice duration");
reportSlice(0, false, 1, 0, 10, 16, 8, 0, 1, 0, 0, 0,
  constants.maxNetworkWait, 1, 0);
assert.equal(globalThis.__gaiusWorldgenSchedulerMarker.maxSliceContext,
  maximumSliceContextText, "a shorter later slice overwrote maximum context");
assert.equal(Object.keys(telemetry).includes("__sliceHistogram"), false,
  "bounded percentile histogram leaks into scalar telemetry snapshots");
assert.equal(Object.keys(telemetry).includes("__yieldDelayHistogram"), false,
  "bounded yield-delay histogram leaks into scalar telemetry snapshots");
delete globalThis.__gaiusWorldgenStats;
delete globalThis.__gaiusWorldgenSliceMillis;
delete globalThis.__gaiusWorldgenSchedulerMarker;
delete globalThis.__gaiusSlowProbeTelemetryEnabled;

class DeterministicScheduler {
  constructor(configuredBudget = 16) {
    this.configuredBudget = configuredBudget;
    this.time = 0;
    this.sliceStartedAt = 0;
    this.activeWorkElapsed = 0;
    this.activeWorkStartedAt = -1;
    this.taskWorkDepth = 0;
    this.reentrantTaskWorkDepth = 0;
    this.deferredTaskScopeEnds = 0;
    this.deadline = 0;
    this.currentBudget = 0;
    this.previousYieldDelay = 0;
    this.previousOverrun = 0;
    this.pulsesUntilClockCheck = 1;
    this.pulsesUntilNetworkCheck = 1;
    this.pulsesInTurn = 0;
    this.progressPulses = 0;
    this.networkWaitPulses = 0;
    this.networkPreemptionPending = false;
    this.yieldActive = false;
    this.deferredYield = false;
    this.reentrantRequests = 0;
    this.reentrantPulseYields = 0;
    this.reentrantYieldDepth = 0;
    this.maxReentrantYieldDepth = 0;
    this.maxReentrantYieldDepthInYield = 0;
    this.maxYieldDepth = 0;
    this.yieldDepth = 0;
    this.totalPulses = 0;
    this.worldCompleted = 0;
    this.networkQueue = [];
    this.scheduled = [];
    this.processed = [];
    this.slices = [];
    this.noProgressSlices = 0;
    this.checkpointOnlyYields = 0;
    this.checkpointOnlyP99YieldDelayMillis = 0;
    this.checkpointOnlyMaxYieldDelayMillis = 0;
    this.checkpointOnlyMaxQueueDepth = 0;
    this.checkpointOnlyMaxNetworkWaitPulses = 0;
    this.checkpointOnlyMaxReentrantYieldDepth = 0;
    this.checkpointOnlyYieldDelays = [];
    this.nextYieldDelay = 0.25;
    this.injectReentry = true;
    this.injectReentrantRequest = false;
  }

  schedule(at, type, count = 1) {
    for (let index = 0; index < count; index++) {
      this.scheduled.push({at, type, sequence: this.scheduled.length});
    }
    this.scheduled.sort((left, right) => left.at - right.at || left.sequence - right.sequence);
  }

  activateArrivals(until = this.time) {
    while (this.scheduled.length > 0 && this.scheduled[0].at <= until) {
      const packet = this.scheduled.shift();
      packet.availablePulse = this.totalPulses;
      this.networkQueue.push(packet);
    }
  }

  adaptiveBudget(queueDepthValue, yieldDelay, overrun, madeProgress) {
    let target = this.configuredBudget;
    if (queueDepthValue > 0) target = Math.min(target, this.configuredBudget * 0.35);
    if (yieldDelay >= constants.busyDelay || overrun >= 2) {
      target = Math.min(target, this.configuredBudget * 0.25);
    } else if (yieldDelay >= constants.moderateDelay || overrun > 0) {
      target = Math.min(target, this.configuredBudget * 0.5);
    }
    if (!madeProgress) target = Math.min(target, this.configuredBudget * 0.5);
    target = Math.max(Math.min(this.configuredBudget, constants.minBudget), target);
    if (this.currentBudget <= 0 || target < this.currentBudget) return target;
    return Math.min(target, this.currentBudget + constants.recovery);
  }

  beginSlice() {
    this.currentBudget = this.adaptiveBudget(
      this.networkQueue.length,
      this.previousYieldDelay,
      this.previousOverrun,
      true,
    );
    this.sliceStartedAt = this.time;
    this.activeWorkElapsed = 0;
    this.activeWorkStartedAt = this.taskWorkDepth > 0 ? this.time : -1;
    this.deadline = this.time + this.currentBudget;
  }

  beginServerWorkTurn() {
    // Mirrors BrowserWorldgenScheduler.beginServerWorkTurn(): a new server work turn
    // owns a fresh clock, while the current adaptive budget and pulse counters
    // remain in force until this server work turn really yields.
    if (this.yieldActive) return;
    if (this.taskWorkDepth > 0) return;
    if (this.currentBudget <= 0) {
      this.beginSlice();
    } else {
      this.sliceStartedAt = this.time;
      // A task may have run from waitUntilNextTick after the prior checkpoint;
      // the next server-turn boundary must retain that active elapsed work.
      this.activeWorkStartedAt = -1;
      this.deadline = this.time + this.currentBudget;
      this.pulsesUntilClockCheck = constants.clockCheck;
    }
  }

  beginTaskWork() {
    if (this.yieldActive) {
      this.reentrantTaskWorkDepth++;
      return 2;
    }
    if (this.taskWorkDepth > 0) return 0;
    this.taskWorkDepth = 1;
    if (this.deadline === 0) {
      this.beginSlice();
    } else {
      this.activeWorkStartedAt = this.time;
    }
    return 1;
  }

  endTaskWork(token) {
    if (token === 0) return;
    if (token === 2) {
      if (this.reentrantTaskWorkDepth > 0) this.reentrantTaskWorkDepth--;
      return;
    }
    if (token !== 1) return;
    if (this.yieldActive) {
      if (this.taskWorkDepth > 0 && this.deferredTaskScopeEnds === 0) {
        this.deferredTaskScopeEnds++;
      }
      return;
    }
    if (this.taskWorkDepth <= 0) return;
    this.taskWorkDepth = 0;
    this.activeWorkElapsed += this.activeSegmentElapsed();
    this.activeWorkStartedAt = -1;
  }

  activeSegmentElapsed() {
    return this.activeWorkStartedAt < 0
      ? 0
      : Math.max(0, this.time - this.activeWorkStartedAt);
  }

  activeSliceElapsed() {
    return this.activeWorkElapsed
      + (this.taskWorkDepth > 0 ? this.activeSegmentElapsed() : 0);
  }

  pump(limit = 16) {
    let processed = 0;
    while (processed < limit && this.networkQueue.length > 0) {
      const packet = this.networkQueue.shift();
      this.time += 0.05;
      this.processed.push({
        ...packet,
        processedAt: this.time,
        latency: this.time - packet.at,
        waitPulses: this.totalPulses - packet.availablePulse,
      });
      processed++;
    }
    return processed;
  }

  recordCheckpointOnlyYield(networkWaitPulses, yieldDelay, queueBefore, queueAfter) {
    this.checkpointOnlyYields++;
    this.checkpointOnlyYieldDelays.push(yieldDelay);
    this.checkpointOnlyYieldDelays.sort((left, right) => left - right);
    const index = Math.max(
      0,
      Math.ceil(this.checkpointOnlyYieldDelays.length * 0.99) - 1,
    );
    this.checkpointOnlyP99YieldDelayMillis =
      Math.floor(this.checkpointOnlyYieldDelays[index]) + 1;
    this.checkpointOnlyMaxYieldDelayMillis = Math.max(
      this.checkpointOnlyMaxYieldDelayMillis,
      yieldDelay,
    );
    this.checkpointOnlyMaxQueueDepth = Math.max(
      this.checkpointOnlyMaxQueueDepth,
      queueBefore,
      queueAfter,
    );
    this.checkpointOnlyMaxNetworkWaitPulses = Math.max(
      this.checkpointOnlyMaxNetworkWaitPulses,
      networkWaitPulses,
    );
    this.checkpointOnlyMaxReentrantYieldDepth = Math.max(
      this.checkpointOnlyMaxReentrantYieldDepth,
      this.maxReentrantYieldDepthInYield,
    );
  }

  requestYield(reason) {
    if (this.yieldActive) {
      this.yieldReentrantContinuation();
      return;
    }
    const sliceElapsed = this.activeSliceElapsed();
    this.activeWorkElapsed = sliceElapsed;
    this.activeWorkStartedAt = -1;
    this.yieldActive = true;
    this.maxReentrantYieldDepthInYield = 0;
    this.yieldDepth++;
    this.maxYieldDepth = Math.max(this.maxYieldDepth, this.yieldDepth);
    try {
      if (this.sliceStartedAt === 0) this.sliceStartedAt = this.time;
      if (this.currentBudget <= 0) {
        this.currentBudget = this.adaptiveBudget(this.networkQueue.length, 0, 0, true);
      }
      const completedBudget = this.currentBudget;
      const elapsed = sliceElapsed;
      const overrun = Math.max(0, elapsed - completedBudget);
      const queueBefore = this.networkQueue.length;
      if (queueBefore > 0) this.pump();

      const yieldStartedAt = this.time;
      const delay = this.nextYieldDelay;
      this.nextYieldDelay = 0.25;
      this.time += delay;
      this.activateArrivals(this.time);
      if (this.injectReentry) {
        this.injectReentry = false;
        this.workPulse(0.1);
      }
      if (this.injectReentrantRequest) {
        this.injectReentrantRequest = false;
        this.requestYield("checkpoint");
      }
      if (this.networkQueue.length > 0) this.pump();
      const queueAfter = this.networkQueue.length;
      const yieldDelay = this.time - yieldStartedAt;
      const madeProgress = this.progressPulses > 0;
      const checkpointOnly = reason === "checkpoint" && !madeProgress;
      if (checkpointOnly) {
        this.recordCheckpointOnlyYield(
          this.networkWaitPulses,
          yieldDelay,
          queueBefore,
          queueAfter,
        );
      } else {
        if (!madeProgress) this.noProgressSlices++;
        this.previousYieldDelay = yieldDelay;
        this.previousOverrun = overrun;
        this.currentBudget = this.adaptiveBudget(
          Math.max(queueBefore, queueAfter),
          yieldDelay,
          overrun,
          madeProgress,
        );
        this.slices.push({
          reason,
          elapsed,
          completedBudget,
          nextBudget: this.currentBudget,
          overrun,
          yieldDelay,
          queueBefore,
          queueAfter,
          progress: this.progressPulses,
          networkWaitPulses: this.networkWaitPulses,
        });
      }

      this.sliceStartedAt = this.time;
      this.activeWorkElapsed = 0;
      this.activeWorkStartedAt = !checkpointOnly && this.taskWorkDepth > 0
        ? this.time
        : -1;
      this.deadline = this.time + this.currentBudget;
      this.pulsesUntilClockCheck = constants.clockCheck;
      this.pulsesUntilNetworkCheck = constants.networkCheck;
      this.pulsesInTurn = 0;
      this.progressPulses = 0;
      this.networkWaitPulses = 0;
      this.networkPreemptionPending = false;
    } finally {
      this.yieldDepth--;
      this.yieldActive = false;
      while (this.deferredTaskScopeEnds > 0 && this.taskWorkDepth > 0) {
        this.deferredTaskScopeEnds--;
        this.taskWorkDepth = 0;
        this.activeWorkStartedAt = -1;
      }
      this.deferredTaskScopeEnds = 0;
      if (this.deferredYield) {
        this.deferredYield = false;
        this.activeWorkElapsed = Math.max(this.activeWorkElapsed, this.currentBudget);
        this.deadline = this.sliceStartedAt;
        this.pulsesUntilClockCheck = 1;
      }
    }
  }

  yieldReentrantContinuation() {
    this.deferredYield = true;
    this.reentrantRequests++;
    this.reentrantYieldDepth++;
    this.maxReentrantYieldDepth = Math.max(
      this.maxReentrantYieldDepth,
      this.reentrantYieldDepth,
    );
    this.maxReentrantYieldDepthInYield = Math.max(
      this.maxReentrantYieldDepthInYield,
      this.reentrantYieldDepth,
    );
    this.reentrantYieldDepth--;
  }

  workPulse(duration) {
    this.activateArrivals();
    this.time += duration;
    this.activateArrivals();
    this.worldCompleted++;
    this.totalPulses++;
    this.progressPulses++;
    this.pulsesInTurn++;

    if (this.yieldActive) {
      this.yieldReentrantContinuation();
      this.reentrantPulseYields++;
      this.time += 0.25;
      this.activateArrivals(this.time);
      return;
    }

    if (this.networkPreemptionPending) {
      this.networkWaitPulses++;
      if (this.progressPulses >= constants.minProgress) {
        this.requestYield("network");
        return;
      }
    } else if (--this.pulsesUntilNetworkCheck <= 0) {
      this.pulsesUntilNetworkCheck = constants.networkCheck;
      if (this.networkQueue.length > 0) {
        this.networkPreemptionPending = true;
        this.networkWaitPulses = 1;
        if (this.progressPulses >= constants.minProgress) {
          this.requestYield("network");
          return;
        }
      }
    }

    if (this.pulsesInTurn >= constants.maxPulses) {
      this.requestYield("hard-cap");
      return;
    }
    if (--this.pulsesUntilClockCheck > 0) return;
    this.pulsesUntilClockCheck = constants.clockCheck;
    if (this.deadline === 0) this.beginSlice();
    else if (this.activeSliceElapsed() >= this.currentBudget) this.requestYield("deadline");
  }
}

const simulation = new DeterministicScheduler(16);
simulation.schedule(24, "block-confirm", 12);
simulation.schedule(82, "movement", 16);
for (let at = 50; at <= 950; at += 50) simulation.schedule(at, "heartbeat");
simulation.schedule(145, "block-confirm", 8);
simulation.schedule(310, "movement", 8);

const targetWorldPulses = 3_200;
let longLoadInjected = false;
for (let guard = 0; guard < 20_000 && simulation.worldCompleted < targetWorldPulses; guard++) {
  if (!longLoadInjected && simulation.time >= 120) {
    simulation.nextYieldDelay = 500;
    longLoadInjected = true;
  }
  const simulationToken = simulation.beginTaskWork();
  simulation.workPulse(0.35);
  simulation.endTaskWork(simulationToken);
}
while ((simulation.networkQueue.length > 0 || simulation.scheduled.length > 0)
    && simulation.time < 2_000) {
  if (simulation.scheduled.length > 0 && simulation.networkQueue.length === 0) {
    simulation.time = Math.max(simulation.time, simulation.scheduled[0].at);
    simulation.activateArrivals();
  }
  simulation.requestYield("checkpoint");
}

assert.equal(simulation.worldCompleted, targetWorldPulses,
  "sustained network pressure starved worldgen progress");
assert.equal(longLoadInjected, true, "500 ms long-load scenario was not exercised");
assert.ok(simulation.slices.some(slice => slice.yieldDelay >= 500),
  "500 ms event-loop delay was not observed");
assert.ok(simulation.slices.some(slice => slice.nextBudget <= 4),
  "busy event-loop delay did not shorten the adaptive budget");
assert.ok(simulation.slices.at(-1).nextBudget > constants.minBudget,
  "adaptive budget never recovered after pressure cleared");
const networkSlices = simulation.slices.filter(slice => slice.reason === "network");
assert.ok(networkSlices.length > 0, "network bursts never preempted worldgen");
assert.ok(networkSlices.every(slice => slice.progress >= constants.minProgress),
  "network preemption violated the minimum worldgen progress guarantee");
assert.ok(networkSlices.every(slice => slice.networkWaitPulses <= constants.maxNetworkWait),
  "network preemption exceeded its scheduling-round limit");
assert.equal(simulation.maxYieldDepth, 1, "scheduler yield reentered server state");
assert.equal(simulation.reentrantRequests, 1, "reentrant yield request was not deferred once");
assert.equal(simulation.reentrantPulseYields, 1,
  "active worldgen pulse did not suspend its continuation");

const stalledPackets = simulation.processed.filter(packet => packet.at >= 120 && packet.at <= 620);
const ordinaryPackets = simulation.processed.filter(packet => packet.at < 120 || packet.at > 620);
assert.ok(stalledPackets.length > 0, "long-load interval contained no network traffic");
assert.ok(Math.max(...stalledPackets.map(packet => packet.latency)) <= 505,
  "network incurred avoidable delay beyond the injected 500 ms load");
assert.ok(Math.max(...ordinaryPackets.map(packet => packet.latency)) <= 20,
  "ordinary network/block/heartbeat latency exceeded 20 ms");
assert.equal(simulation.processed.filter(packet => packet.type === "heartbeat").length, 19,
  "heartbeat traffic was lost");
assert.equal(simulation.networkQueue.length, 0, "network queue leaked after worldgen completed");
assert.equal(simulation.scheduled.length, 0, "scheduled deterministic events leaked");
assert.equal(simulation.yieldActive, false, "yield remained active after simulation shutdown");
assert.equal(simulation.deferredYield, false, "deferred yield remained queued after shutdown");

// A 1.21-style server turn can reach checkpoint with no task pulse at all.
// Checkpoint-only yields must still pump/yield, but may not consume an
// adaptive slice or manufacture no-progress slice telemetry.
const pureCheckpoint = new DeterministicScheduler(8);
pureCheckpoint.injectReentry = false;
pureCheckpoint.time = 1;
pureCheckpoint.beginServerWorkTurn();
assert.equal(pureCheckpoint.currentBudget, 8,
  "server-turn clock did not initialize the 8 ms budget");
pureCheckpoint.requestYield("checkpoint");
assert.equal(pureCheckpoint.slices.length, 0,
  "pure checkpoint was reported as an ordinary slice");
assert.equal(pureCheckpoint.checkpointOnlyYields, 1,
  "pure checkpoint was not isolated in checkpoint-only telemetry");
assert.equal(pureCheckpoint.currentBudget, 8,
  "pure checkpoint changed the adaptive budget");
const checkpointYieldDelay = pureCheckpoint.previousYieldDelay;
const checkpointOverrun = pureCheckpoint.previousOverrun;
pureCheckpoint.time += 50;
pureCheckpoint.beginServerWorkTurn();
pureCheckpoint.requestYield("checkpoint");
assert.equal(pureCheckpoint.slices.length, 0,
  "repeated idle checkpoints accumulated ordinary slices");
assert.equal(pureCheckpoint.checkpointOnlyYields, 2,
  "repeated idle checkpoints were not counted independently");
assert.equal(pureCheckpoint.noProgressSlices, 0,
  "repeated no-progress checkpoints leaked into ordinary slice telemetry");
assert.equal(pureCheckpoint.currentBudget, 8,
  "50 ms idle gap changed the active budget");
assert.equal(pureCheckpoint.previousYieldDelay, checkpointYieldDelay,
  "checkpoint-only yield delay changed adaptive history");
assert.equal(pureCheckpoint.previousOverrun, checkpointOverrun,
  "checkpoint-only overrun changed adaptive history");

// A server tick may perform active work which reaches the checkpoint without
// crossing a pulse boundary.  It is still a no-progress checkpoint, not an
// ordinary slice: the active elapsed value must not change the classification
// or adaptive budget.
const activeCheckpoint = new DeterministicScheduler(8);
activeCheckpoint.injectReentry = false;
activeCheckpoint.time = 1;
activeCheckpoint.beginServerWorkTurn();
const activeCheckpointToken = activeCheckpoint.beginTaskWork();
activeCheckpoint.time += 3;
activeCheckpoint.endTaskWork(activeCheckpointToken);
assert.equal(activeCheckpoint.activeWorkElapsed, 3,
  "active checkpoint model did not retain elapsed task work");
activeCheckpoint.requestYield("checkpoint");
assert.equal(activeCheckpoint.slices.length, 0,
  "active no-progress checkpoint became an ordinary slice");
assert.equal(activeCheckpoint.noProgressSlices, 0,
  "active no-progress checkpoint leaked into ordinary telemetry");
assert.equal(activeCheckpoint.checkpointOnlyYields, 1,
  "active no-progress checkpoint was not classified as checkpoint-only");
assert.equal(activeCheckpoint.currentBudget, 8,
  "active no-progress checkpoint changed the 8 ms adaptive budget");

// A packet present before the yield and one arriving during the event-loop
// continuation both remain pumpable even when no worldgen pulse occurs.
const checkpointNetwork = new DeterministicScheduler(8);
checkpointNetwork.injectReentry = false;
checkpointNetwork.networkQueue.push({
  at: 0,
  type: "pre-pump",
  sequence: 0,
  availablePulse: 0,
});
checkpointNetwork.schedule(0.1, "post-pump");
checkpointNetwork.requestYield("checkpoint");
assert.equal(checkpointNetwork.processed.length, 2,
  "checkpoint-only yield skipped pre/post urgent packet pumps");
assert.equal(checkpointNetwork.checkpointOnlyMaxQueueDepth, 1,
  "checkpoint-only telemetry missed packet queue pressure");

// A callback pulse produced while yieldActive is true converts the checkpoint
// into an ordinary progress-bearing slice after the pumps complete.
const callbackCheckpoint = new DeterministicScheduler(8);
callbackCheckpoint.injectReentry = true;
callbackCheckpoint.requestYield("checkpoint");
assert.equal(callbackCheckpoint.slices.length, 1,
  "yield callback pulse did not produce an ordinary slice");
assert.equal(callbackCheckpoint.slices[0].reason, "checkpoint",
  "yield callback pulse changed the checkpoint reason");
assert.equal(callbackCheckpoint.slices[0].progress, 1,
  "yield callback pulse was not retained in the ordinary slice");
assert.equal(callbackCheckpoint.checkpointOnlyYields, 0,
  "callback-pulsed checkpoint was misclassified as checkpoint-only");
assert.equal(callbackCheckpoint.maxReentrantYieldDepth, 1,
  "callback pulse did not exercise reentrant continuation depth");

// A reentrant yield request without progress stays checkpoint-only, while
// preserving the deferred/reentrant guard and its dedicated depth telemetry.
const reentrantCheckpoint = new DeterministicScheduler(8);
reentrantCheckpoint.injectReentry = false;
reentrantCheckpoint.injectReentrantRequest = true;
reentrantCheckpoint.requestYield("checkpoint");
assert.equal(reentrantCheckpoint.slices.length, 0,
  "reentrant no-progress checkpoint became an ordinary slice");
assert.equal(reentrantCheckpoint.checkpointOnlyYields, 1,
  "reentrant no-progress checkpoint was not counted");
assert.equal(reentrantCheckpoint.checkpointOnlyMaxReentrantYieldDepth, 1,
  "checkpoint-only telemetry lost reentrant depth");
assert.equal(reentrantCheckpoint.deferredYield, false,
  "reentrant checkpoint left a deferred yield queued");

const frozenClock = new DeterministicScheduler(16);
frozenClock.injectReentry = false;
for (let pulse = 0; pulse < constants.maxPulses; pulse++) {
  const frozenToken = frozenClock.beginTaskWork();
  frozenClock.workPulse(0);
  frozenClock.endTaskWork(frozenToken);
}
assert.equal(frozenClock.slices.filter(slice => slice.reason === "hard-cap").length, 1,
  "frozen clock bypassed the per-turn hard pulse cap");
assert.equal(frozenClock.slices[0].progress, constants.maxPulses,
  "hard-cap turn did not preserve bounded progress");

// A sparse task pulse can be separated by a server tick (roughly 50 ms).  The
// old scheduler retained the previous deadline and manufactured one deadline
// yield per tick.  A server-turn boundary must discard only that idle gap.
const sparseTask = new DeterministicScheduler(8);
for (let turn = 0; turn < 32; turn++) {
  sparseTask.time = turn * 50;
  sparseTask.beginServerWorkTurn();
  const sparseToken = sparseTask.beginTaskWork();
  sparseTask.workPulse(0.05);
  sparseTask.endTaskWork(sparseToken);
}
assert.equal(sparseTask.slices.length, 0,
  "sparse task pulses still count inter-tick idle time as worldgen");
assert.equal(sparseTask.worldCompleted, 32,
  "server-tick clock reset dropped a sparse task pulse");

// Exercise the exact patched runServer order: begin the work turn, do a small
// amount of task work, checkpoint after processPacketsAndTick, then spend the
// remaining ~50 ms waiting for the next tick.  The checkpoint slice must retain
// only active work; the wait belongs before the next beginServerWorkTurn.
const tickBoundary = new DeterministicScheduler(8);
tickBoundary.injectReentry = false;
tickBoundary.time = 1;
for (let turn = 0; turn < 32; turn++) {
  tickBoundary.beginServerWorkTurn();
  const tickToken = tickBoundary.beginTaskWork();
  tickBoundary.workPulse(0.05);
  tickBoundary.endTaskWork(tickToken);
  tickBoundary.requestYield("checkpoint");
  tickBoundary.time += 50;
}
assert.equal(tickBoundary.slices.length, 32,
  "each post-tick checkpoint must complete exactly one scheduler slice");
assert.ok(tickBoundary.slices.every(slice => slice.reason === "checkpoint"),
  "tick-boundary simulation yielded for a task/deadline reason");
assert.ok(tickBoundary.slices.every(slice => slice.elapsed >= 0.049
    && slice.elapsed <= 0.051),
  "post-tick checkpoint slices included the inter-tick 50 ms idle gap");
assert.ok(Math.abs(Math.max(...tickBoundary.slices.map(slice => slice.elapsed)) - 0.05) <= 1e-9,
  "checkpoint slice elapsed time changed after the server-work boundary");
assert.ok(tickBoundary.slices.every(slice => slice.overrun === 0),
  "inter-tick idle time was incorrectly recorded as budget overrun");

// The same contract must hold for a longer browser wait: checkpoint closes the
// active task segment before waitUntilNextTick/pollTask can spend 50/100 ms
// waiting for the next server tick.
const checkpointIdle = new DeterministicScheduler(8);
checkpointIdle.injectReentry = false;
checkpointIdle.time = 1;
for (const idle of [50, 100]) {
  checkpointIdle.beginServerWorkTurn();
  const checkpointIdleToken = checkpointIdle.beginTaskWork();
  checkpointIdle.workPulse(4);
  checkpointIdle.endTaskWork(checkpointIdleToken);
  checkpointIdle.requestYield("checkpoint");
  checkpointIdle.time += idle;
}
assert.equal(checkpointIdle.slices.length, 2,
  "checkpoint idle model did not close both active task segments");
assert.ok(checkpointIdle.slices.every(slice => slice.elapsed === 4),
  "checkpoint-after-task elapsed included 50/100 ms waiting");
assert.ok(checkpointIdle.slices.every(slice => slice.overrun === 0),
  "checkpoint idle model reported a false budget overrun");

// Work submitted by the wait loop after a checkpoint is still part of the
// shared adaptive slice.  A later beginServerWorkTurn must not erase it before
// the next task gets its 4 ms, otherwise two 4 ms tasks evade the 8 ms budget.
const checkpointFollowup = new DeterministicScheduler(8);
checkpointFollowup.injectReentry = false;
checkpointFollowup.time = 1;
checkpointFollowup.beginServerWorkTurn();
const checkpointFollowupToken1 = checkpointFollowup.beginTaskWork();
checkpointFollowup.workPulse(4);
checkpointFollowup.endTaskWork(checkpointFollowupToken1);
checkpointFollowup.requestYield("checkpoint");
checkpointFollowup.time += 50;
const checkpointFollowupToken2 = checkpointFollowup.beginTaskWork();
checkpointFollowup.workPulse(4);
checkpointFollowup.endTaskWork(checkpointFollowupToken2);
checkpointFollowup.time += 100;
checkpointFollowup.beginServerWorkTurn();
const checkpointFollowupToken3 = checkpointFollowup.beginTaskWork();
checkpointFollowup.workPulse(4);
checkpointFollowup.endTaskWork(checkpointFollowupToken3);
assert.equal(checkpointFollowup.slices.length, 2,
  "wait-loop task work was lost at the next server-turn boundary");
assert.equal(checkpointFollowup.slices[1].elapsed, 8,
  "checkpoint follow-up tasks did not share their active 4+4 ms budget");
assert.equal(checkpointFollowup.slices[1].reason, "deadline",
  "checkpoint follow-up task did not trigger the shared deadline");

// Multiple task invocations in one server tick share one pulse budget.  Do
// not reset at each runUntilWait entry or active work can evade the deadline.
const sameTickTasks = new DeterministicScheduler(8);
sameTickTasks.time = 1;
sameTickTasks.beginServerWorkTurn();
const sameTickToken1 = sameTickTasks.beginTaskWork();
sameTickTasks.workPulse(4);
sameTickTasks.endTaskWork(sameTickToken1);
const sameTickToken2 = sameTickTasks.beginTaskWork();
sameTickTasks.workPulse(4);
sameTickTasks.endTaskWork(sameTickToken2);
assert.equal(sameTickTasks.slices.length, 1,
  "same-tick task work did not share the scheduler budget");
assert.equal(sameTickTasks.slices[0].reason, "deadline",
  "same-tick cumulative work yielded for the wrong reason");

// Resetting at the tick entry must not hide a real long synchronous region
// in that same tick: the first pulse still observes the elapsed body.
const longTask = new DeterministicScheduler(8);
longTask.time = 1;
longTask.beginServerWorkTurn();
const longTaskToken = longTask.beginTaskWork();
longTask.time += 20;
longTask.workPulse(0);
longTask.endTaskWork(longTaskToken);
assert.equal(longTask.slices.length, 1,
  "a long same-turn synchronous region was not deadline-bounded");
assert.equal(longTask.slices[0].reason, "deadline",
  "long same-turn work yielded for the wrong reason");
assert.ok(longTask.slices[0].elapsed >= 20,
  "same-turn elapsed work was reset before it could be measured");

// Nested task scopes must not split or reset the shared active segment.
const nestedTasks = new DeterministicScheduler(8);
nestedTasks.time = 1;
nestedTasks.beginServerWorkTurn();
const nestedOuterToken = nestedTasks.beginTaskWork();
nestedTasks.workPulse(3);
const nestedInnerToken = nestedTasks.beginTaskWork();
nestedTasks.workPulse(3);
nestedTasks.endTaskWork(nestedInnerToken);
assert.equal(nestedTasks.taskWorkDepth, 1,
  "nested task scope was not retained by the outer task");
nestedTasks.endTaskWork(nestedOuterToken);
const nestedFollowupToken = nestedTasks.beginTaskWork();
nestedTasks.workPulse(2);
nestedTasks.endTaskWork(nestedFollowupToken);
assert.equal(nestedTasks.slices.length, 1,
  "nested task scopes reset instead of sharing one adaptive budget");
assert.equal(nestedTasks.slices[0].reason, "deadline",
  "nested task cumulative work did not trigger the shared deadline");

// A reentrant task callback during packet pumping must not move the outer
// continuation's active segment.  Its begin/end pair is tracked separately.
const reentrantTask = new DeterministicScheduler(8);
reentrantTask.time = 1;
reentrantTask.beginServerWorkTurn();
const reentrantOuterToken = reentrantTask.beginTaskWork();
reentrantTask.workPulse(4);
reentrantTask.yieldActive = true;
const reentrantInnerToken = reentrantTask.beginTaskWork();
assert.equal(reentrantOuterToken, 1,
  "outer task scope did not receive the NORMAL token");
assert.equal(reentrantInnerToken, 2,
  "reentrant task scope did not receive the REENTRANT token");
assert.equal(reentrantTask.taskWorkDepth, 1,
  "reentrant task callback changed the outer task depth");
reentrantTask.yieldActive = false;
assert.equal(reentrantTask.reentrantTaskWorkDepth, 1,
  "reentrant task scope was lost when the outer yield resumed");
reentrantTask.endTaskWork(reentrantInnerToken);
reentrantTask.endTaskWork(reentrantOuterToken);
assert.equal(reentrantTask.taskWorkDepth, 0,
  "outer task scope did not close after a reentrant callback");
assert.equal(reentrantTask.activeWorkElapsed, 4,
  "reentrant task callback moved the outer active clock");
assert.equal(reentrantTask.reentrantTaskWorkDepth, 0,
  "reentrant task scope did not close after its continuation returned");

// Continuations may finish in a different order from the packet callback
// which entered them.  Category tokens keep the REENTRANT closes isolated,
// while the NORMAL close is applied exactly once by the outer finally.
const reentrantOrder = new DeterministicScheduler(8);
reentrantOrder.time = 1;
reentrantOrder.beginServerWorkTurn();
const reentrantOrderOuter = reentrantOrder.beginTaskWork();
reentrantOrder.workPulse(2);
reentrantOrder.yieldActive = true;
const reentrantOrderFirst = reentrantOrder.beginTaskWork();
const reentrantOrderSecond = reentrantOrder.beginTaskWork();
assert.equal(reentrantOrderFirst, 2,
  "first interleaved continuation did not receive REENTRANT");
assert.equal(reentrantOrderSecond, 2,
  "second interleaved continuation did not receive REENTRANT");
reentrantOrder.endTaskWork(reentrantOrderOuter);
reentrantOrder.endTaskWork(reentrantOrderFirst);
reentrantOrder.endTaskWork(reentrantOrderSecond);
assert.equal(reentrantOrder.taskWorkDepth, 1,
  "interleaved reentrant closes touched the outer NORMAL scope");
assert.equal(reentrantOrder.reentrantTaskWorkDepth, 0,
  "interleaved reentrant closes were not consumed independently");
assert.equal(reentrantOrder.deferredTaskScopeEnds, 1,
  "the live NORMAL close was not deferred exactly once");
reentrantOrder.yieldActive = false;
reentrantOrder.requestYield("checkpoint");
assert.equal(reentrantOrder.taskWorkDepth, 0,
  "outer NORMAL scope was not closed by requestYield finally");
assert.equal(reentrantOrder.deferredTaskScopeEnds, 0,
  "reentrant-order test left a deferred close behind");

// Unmatched closes while yieldActive must be no-ops.  In particular they must
// not manufacture a deferred NORMAL close which a later task can inherit.
const staleScope = new DeterministicScheduler(8);
staleScope.yieldActive = true;
staleScope.endTaskWork(1);
staleScope.endTaskWork(2);
staleScope.endTaskWork(0);
assert.equal(staleScope.deferredTaskScopeEnds, 0,
  "unmatched yield-active closes left stale deferred state");
staleScope.yieldActive = false;
staleScope.deferredTaskScopeEnds = 3;
staleScope.requestYield("checkpoint");
assert.equal(staleScope.deferredTaskScopeEnds, 0,
  "requestYield finally did not clear stale deferred state");
const staleScopeToken = staleScope.beginTaskWork();
assert.equal(staleScopeToken, 1,
  "stale deferred state poisoned the next NORMAL task scope");
staleScope.endTaskWork(staleScopeToken);

// performance.now() may legitimately start at zero; the paused marker must not
// mistake that timestamp for an inactive segment.
const zeroClock = new DeterministicScheduler(8);
zeroClock.injectReentry = false;
zeroClock.beginServerWorkTurn();
const zeroClockToken1 = zeroClock.beginTaskWork();
zeroClock.workPulse(4);
zeroClock.endTaskWork(zeroClockToken1);
const zeroClockToken2 = zeroClock.beginTaskWork();
zeroClock.workPulse(4);
zeroClock.endTaskWork(zeroClockToken2);
assert.equal(zeroClock.slices.length, 1,
  "zero-origin performance clock dropped the first active task segment");

function selectJavac() {
  const homes = [
    process.env.GAIUS_JAVA_HOME && nativePath(process.env.GAIUS_JAVA_HOME),
    process.env.JAVA_HOME && nativePath(process.env.JAVA_HOME),
    process.platform === "win32" && "C:/Program Files/Java/jdk-26.0.1",
    process.platform === "win32" && "C:/Program Files/Java/jdk-24",
    "/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home",
    "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home",
  ].filter(Boolean);
  for (const home of homes) {
    const javac = path.join(home, "bin/javac");
    try {
      const probe = spawnSync(javac, ["-version"], {encoding: "utf8"});
      const version = `${probe.stdout || ""}${probe.stderr || ""}`;
      if (probe.status === 0 && Number(version.match(/javac (\d+)/)?.[1]) >= 21) {
        return javac;
      }
    } catch {
      // Try the next configured JDK.
    }
  }
  throw new Error("worldgen scheduler smoke requires javac 21 or newer");
}

async function minimalJavaCompile() {
  const root = await mkdtemp(path.join(tmpdir(), "gaius-worldgen-scheduler-"));
  const files = new Map([
    ["dev/gaius/browser/BrowserWorldgenScheduler.java", worldgen],
    ["dev/gaius/browser/BrowserPacketScheduler.java", `
package dev.gaius.browser;
final class BrowserPacketScheduler {
    static boolean hasPendingPackets() { return false; }
}
`],
    ["dev/gaius/browser/BrowserIntegratedServerMain.java", `
package dev.gaius.browser;
final class BrowserIntegratedServerMain {
    static void pumpUrgentPackets() {}
}
`],
    // BrowserWorldgenScheduler's optional Mob-AI diagnostic fallback keeps the
    // production type check explicit.  The smoke compiler intentionally uses a
    // tiny fixture classpath, so provide the referenced type without pulling in
    // the full Minecraft client JAR.
    ["net/minecraft/world/entity/Mob.java", `
package net.minecraft.world.entity;
public class Mob {}
`],
    ["io/netty/channel/browser/BrowserWebSocketChannel.java", `
package io.netty.channel.browser;
public final class BrowserWebSocketChannel {
    public static boolean hasPendingInput() { return false; }
}
`],
    ["org/teavm/classlib/java/lang/TModernRuntimeSupport.java", `
package org.teavm.classlib.java.lang;
public final class TModernRuntimeSupport {
    public static void yieldToEventLoop(long delay) {}
}
`],
    ["org/teavm/jso/JSBody.java", `
package org.teavm.jso;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface JSBody {
    String[] params() default {};
    String script();
}
`],
  ]);
  try {
    await Promise.all([...files].map(async ([relative, contents]) => {
      const target = path.join(root, "src", relative);
      await mkdir(path.dirname(target), {recursive: true});
      await writeFile(target, contents);
    }));
    const classes = path.join(root, "classes");
    await mkdir(classes);
    execFileSync(selectJavac(), [
      "--release", "21",
      "-proc:none",
      "-d", classes,
      ...[...files.keys()].map(relative => path.join(root, "src", relative)),
    ], {encoding: "utf8", timeout: 30_000});
  } finally {
    await rm(root, {recursive: true, force: true});
  }
}

await minimalJavaCompile();

const maxLatency = Math.max(...simulation.processed.map(packet => packet.latency));
console.log("Worldgen scheduler smoke passed:", JSON.stringify({
  worldgenPulses: simulation.worldCompleted,
  networkEvents: simulation.processed.length,
  slices: simulation.slices.length,
  networkPreemptions: networkSlices.length,
  maxNetworkLatencyMillis: Number(maxLatency.toFixed(2)),
  maxYieldDepth: simulation.maxYieldDepth,
  finalBudgetMillis: simulation.slices.at(-1).nextBudget,
}));
