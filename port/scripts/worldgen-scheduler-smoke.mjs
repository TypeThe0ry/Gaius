#!/usr/bin/env node

import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {mkdir, mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import {tmpdir} from "node:os";
import path from "node:path";

const source = relative => readFile(new URL(relative, import.meta.url), "utf8");
const [worldgen, packets, server, client, patcher262] = await Promise.all([
  source("../src/main/java/dev/gaius/browser/BrowserWorldgenScheduler.java"),
  source("../src/main/java/dev/gaius/browser/BrowserPacketScheduler.java"),
  source("../src/main/java/dev/gaius/browser/BrowserIntegratedServerMain.java"),
  source("../src/main/java/dev/gaius/browser/BrowserSingleplayerClient.java"),
  source("../tools/src/main/java/dev/gaius/tools/Minecraft262BrowserPatcher.java"),
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
assert.equal(worldgen.split("BrowserIntegratedServerMain.pumpUrgentPackets()").length - 1, 2,
  "worldgen does not drain one bounded packet batch before and after a yield");
assert.ok(worldgen.includes("boolean yieldActive") && worldgen.includes("deferredYield"),
  "worldgen yield has no reentrancy gate");
assert.ok(worldgen.includes("pulsesInTurn >= MAX_PULSES_PER_TURN"),
  "worldgen turn has no hard pulse cap");
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
assert.ok(!worldgen.includes("setTimeout(") && !worldgen.includes("setInterval("),
  "worldgen scheduler owns a timer that can leak after shutdown");
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
assert.equal(constants.maxNetworkWait, constants.networkCheck,
  "network wait contract no longer matches the probe interval");
assert.ok(constants.minProgress > 0 && constants.maxPulses >= constants.minProgress,
  "worldgen progress and hard-cap constants are inconsistent");
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
  jsBody("private static native void reportSlice("),
);
globalThis.__gaiusWorldgenSliceMillis = 16;
globalThis.__gaiusWorldgenStats = undefined;
for (let elapsed = 1; elapsed <= 100; elapsed++) {
  reportSlice(0, false, 4, 0, elapsed, 16, 8, Math.max(0, elapsed - 16), 1,
    0, 0, 0, constants.maxNetworkWait, 4, 1);
}
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
assert.equal(Object.keys(telemetry).includes("__sliceHistogram"), false,
  "bounded percentile histogram leaks into scalar telemetry snapshots");
assert.equal(Object.keys(telemetry).includes("__yieldDelayHistogram"), false,
  "bounded yield-delay histogram leaks into scalar telemetry snapshots");
delete globalThis.__gaiusWorldgenStats;
delete globalThis.__gaiusWorldgenSliceMillis;

class DeterministicScheduler {
  constructor(configuredBudget = 16) {
    this.configuredBudget = configuredBudget;
    this.time = 0;
    this.sliceStartedAt = 0;
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
    this.maxYieldDepth = 0;
    this.yieldDepth = 0;
    this.totalPulses = 0;
    this.worldCompleted = 0;
    this.networkQueue = [];
    this.scheduled = [];
    this.processed = [];
    this.slices = [];
    this.nextYieldDelay = 0.25;
    this.injectReentry = true;
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
    this.deadline = this.time + this.currentBudget;
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

  requestYield(reason) {
    if (this.yieldActive) {
      this.deferredYield = true;
      this.reentrantRequests++;
      return;
    }
    this.yieldActive = true;
    this.yieldDepth++;
    this.maxYieldDepth = Math.max(this.maxYieldDepth, this.yieldDepth);
    try {
      if (this.sliceStartedAt === 0) this.sliceStartedAt = this.time;
      if (this.currentBudget <= 0) {
        this.currentBudget = this.adaptiveBudget(this.networkQueue.length, 0, 0, true);
      }
      const completedBudget = this.currentBudget;
      const elapsed = Math.max(0, this.time - this.sliceStartedAt);
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
      if (this.networkQueue.length > 0) this.pump();
      const queueAfter = this.networkQueue.length;
      const yieldDelay = this.time - yieldStartedAt;
      const madeProgress = this.progressPulses > 0;
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

      this.sliceStartedAt = this.time;
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
      if (this.deferredYield) {
        this.deferredYield = false;
        this.deadline = this.sliceStartedAt;
        this.pulsesUntilClockCheck = 1;
      }
    }
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
      this.deferredYield = true;
      this.reentrantRequests++;
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
    else if (this.time >= this.deadline) this.requestYield("deadline");
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
  simulation.workPulse(0.35);
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

const frozenClock = new DeterministicScheduler(16);
frozenClock.injectReentry = false;
for (let pulse = 0; pulse < constants.maxPulses; pulse++) frozenClock.workPulse(0);
assert.equal(frozenClock.slices.filter(slice => slice.reason === "hard-cap").length, 1,
  "frozen clock bypassed the per-turn hard pulse cap");
assert.equal(frozenClock.slices[0].progress, constants.maxPulses,
  "hard-cap turn did not preserve bounded progress");

function selectJavac() {
  const homes = [
    process.env.GAIUS_JAVA_HOME,
    process.env.JAVA_HOME,
    "/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home",
    "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home",
  ].filter(Boolean);
  for (const home of homes) {
    const javac = path.join(home, "bin/javac");
    try {
      const version = execFileSync(javac, ["-version"], {encoding: "utf8"});
      if (Number(version.match(/javac (\d+)/)?.[1]) >= 21) return javac;
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
