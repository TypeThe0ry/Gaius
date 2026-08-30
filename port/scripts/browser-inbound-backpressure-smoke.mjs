#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {MessageChannel} from "node:worker_threads";
import vm from "node:vm";

const sourcePath = new URL(
  "../overrides/libraries/netty-transport/src/main/java/" +
    "io/netty/channel/browser/BrowserWebSocketChannel.java",
  import.meta.url,
);
const source = await readFile(sourcePath, "utf8");
const clientPatcher = await readFile(new URL(
  "../tools/src/main/java/dev/gaius/tools/MinecraftClientPatcher.java",
  import.meta.url,
), "utf8");
const clientNetwork = await readFile(new URL(
  "../src/main/java/dev/gaius/browser/BrowserClientNetwork.java",
  import.meta.url,
), "utf8");
const packetScheduler = await readFile(new URL(
  "../src/main/java/dev/gaius/browser/BrowserPacketScheduler.java",
  import.meta.url,
), "utf8");
function jsBodyBefore(marker) {
  const markerOffset = source.indexOf(marker);
  const annotationOffset = source.lastIndexOf('@JSBody(script = """', markerOffset);
  const scriptOffset = source.indexOf('"""', annotationOffset) + 3;
  const scriptEnd = source.lastIndexOf('""")', markerOffset);
  assert.ok(markerOffset > 0 && annotationOffset > 0 && scriptEnd > scriptOffset,
    `JSBody could not be extracted for ${marker}`);
  return source.slice(scriptOffset, scriptEnd).replaceAll("\\\\", "\\");
}
function jsBodyBeforeIn(javaSource, marker) {
  const markerOffset = javaSource.indexOf(marker);
  const annotationOffset = javaSource.lastIndexOf("@JSBody", markerOffset);
  const scriptOffset = javaSource.indexOf('"""', annotationOffset) + 3;
  const scriptEnd = javaSource.lastIndexOf('""")', markerOffset);
  assert.ok(markerOffset > 0 && annotationOffset > 0 && scriptEnd > scriptOffset,
    `JSBody could not be extracted for ${marker}`);
  return javaSource.slice(scriptOffset, scriptEnd).replaceAll("\\\\", "\\");
}
assert.match(source, /maximumInboundSliceBytes = 4 \* 1024/);
assert.match(source, /decodedSliceHighWatermark = 256/);
assert.match(source, /decodedSliceLowWatermark = 64/);
assert.match(source, /decoderCumulationPauseBytes = 12 \* 1024 \* 1024/);
assert.match(source, /maximumDecoderCumulationBytes = 16 \* 1024 \* 1024/);
assert.match(source, /MAX_CHUNKS_PER_PUMP = 64/);
assert.match(source, /MAX_BYTES_PER_PUMP = 256 \* 1024/);
assert.match(source, /MAX_MILLIS_PER_PUMP = 2\.0/);
assert.match(source, /inboundSliceScheduleWaitSamples/);
assert.match(source, /maxInboundSliceScheduleWaitMillis/);
assert.match(source, /highWatermarkEventSequence: 0/);
assert.match(source, /highWatermarkEvents: \[\]/);
assert.match(source, /highWatermarkEventsDropped: 0/);
assert.match(source, /queuedPacketHandleSamples: 0/);
assert.match(source, /maxQueuedPacketHandleMillis: 0/);
assert.match(source, /slowQueuedPacketEventSequence: 0/);
assert.match(source, /slowQueuedPacketEvents: \[\]/);
assert.match(source, /maximumSlowQueuedPacketEvents = 64/);
assert.match(source, /slowQueuedPacketThresholdMillis = 50/);
assert.match(source, /public static boolean pumpAllAndReportProgress\(\)/,
  "the independent dispatcher cannot observe bounded transport progress");
assert.match(source, /return progressed \|\| budgetExhausted;/,
  "a budget-shortened global scan must advertise continuation before the ready-input check");
// pumpAllAndReportProgress already folds budgetExhausted into its boolean
// result. Keep this truth-table assertion beside the source contract so a
// future refactor cannot accidentally turn the Java callback into
// `budgetExhausted && hasPumpableInput` (which would drop ordinary progress).
const pumpContinuationHint = (sliceProgressed, budgetExhausted) =>
  sliceProgressed || budgetExhausted;
assert.equal(pumpContinuationHint(false, true), true,
  "budget-only global pump turns must advertise continuation");
assert.equal(pumpContinuationHint(false, false), false,
  "idle global pump turns must not advertise continuation");
assert.match(source, /public static boolean hasPumpableInput\(\)/,
  "the independent dispatcher cannot stop cleanly on exact-queue pressure");
assert.match(source,
  /hasPumpableInboundScheduled = function\(id\)[\s\S]*!state\.exactPacketQueuePaused[\s\S]*entry\.inboundHead < entry\.inbound\.length/,
  "pumpable input must mean ready and exact-queue-unpaused, not pending-only input");
assert.match(source,
  /wasPaused && !state\.exactPacketQueuePaused[\s\S]*decodedPacketQueueResumes\+\+;[\s\S]*signalInbound\(\);/,
  "exact-queue resume does not wake already-buffered raw transport input");
assert.match(clientNetwork,
  /installInboundPump\(BrowserClientNetwork::pumpInbound\)/,
  "the browser bridge does not install exactly one raw-transport Java callback");
assert.match(clientNetwork,
  /private static native boolean installInboundPump\(BrowserPumpCallback callback\);/,
  "the browser bridge retained a second client PLAY callback parameter");
assert.match(clientNetwork, /new MessageChannel\(\)/,
  "the independent transport pump is not scheduled on a browser macrotask");
assert.match(clientNetwork, /finish\('watchdog'\)/,
  "the independent transport pump lacks a bounded MessageChannel watchdog");
assert.match(clientNetwork,
  /BrowserWebSocketChannel\.pumpAllAndReportProgress\(\)/,
  "the independent callback does not execute the existing bounded transport pump");
assert.match(clientNetwork,
  /progressed && BrowserWebSocketChannel\.hasPumpableInput\(\)/,
  "the independent callback can spin without progress or stop before ready input drains");
assert.match(packetScheduler,
  /CLIENT_PACKET_DRAIN_TARGET_QUEUE =\s*CLIENT_PACKET_DRAIN_THRESHOLD - 1/,
  "client pressure recovery no longer targets the exact queue depth 63");
assert.match(packetScheduler, /CLIENT_PACKET_DRAIN_HARD_MAX_PACKETS = 256/,
  "client pressure recovery lost its bounded clock-failure ceiling");
assert.match(packetScheduler,
  /clientPacketDrainBatchTargetPackets = Math\.min\([\s\S]*CLIENT_PACKET_DRAIN_HARD_MAX_PACKETS,[\s\S]*clientPacketDrainRequestedPackets/,
  "cheap packet pressure is still capped below the available two-millisecond budget");
assert.match(clientNetwork,
  /stopReason: drainStopReason,[\s\S]*targetQueue: target,[\s\S]*requestedPackets: requested,[\s\S]*batchTargetPackets: batchTarget,[\s\S]*remainingDebt: debt/,
  "bounded frame-boundary evidence lost drain-debt attribution");
const rawTransportMethodSource = clientNetwork.slice(
  clientNetwork.indexOf("private static boolean pumpInbound()"),
  clientNetwork.indexOf("public static void beginClientPacketFrame()"),
);
assert.ok(rawTransportMethodSource.length > 0,
  "the bounded raw-transport callback could not be extracted");
assert.doesNotMatch(rawTransportMethodSource, /processQueuedPackets/,
  "the raw transport macrotask synchronously entered the PLAY FIFO drain");
const clientPacketBoundaryMethodSource = clientNetwork.slice(
  clientNetwork.indexOf("public static void processClientPacketsAtScheduledFrameBoundary("),
  clientNetwork.indexOf('@JSBody(params = "callback"'),
);
assert.match(clientPacketBoundaryMethodSource,
  /queueBefore < 64 \|\| !isClientPacketFrameBoundaryDrainEnabled\(\)/,
  "below-threshold PLAY work no longer preserves the one vanilla scheduled call");
assert.match(clientPacketBoundaryMethodSource,
  /tryBeginClientPacketDrain\(pausedBefore\)[\s\S]*packetProcessor\.processQueuedPackets\(\)[\s\S]*finally[\s\S]*finishClientPacketDrain\(\)/,
  "pressure PLAY work is not claimed/released at the scheduled runTick boundary");
assert.equal(
  (clientPacketBoundaryMethodSource.match(/packetProcessor\.processQueuedPackets\(\)/g) || [])
    .length,
  2,
  "the scheduled wrapper must contain only its vanilla and claimed branches",
);
assert.doesNotMatch(clientNetwork,
  /\bclientPacketDrainCallback\s*\(|\bscheduleClientPacketDrain\s*\(/,
  "a retired external client PLAY callback/scheduler remains reachable");

const clientNetworkInstallScript = jsBodyBeforeIn(
  clientNetwork,
  "private static native boolean installInboundPump(",
);
const inboundPumpHandlerSource = clientNetworkInstallScript.slice(
  clientNetworkInstallScript.indexOf("bridge.inboundPump = function()"),
  clientNetworkInstallScript.indexOf("return true;",
    clientNetworkInstallScript.indexOf("bridge.inboundPump = function()")),
);
assert.match(inboundPumpHandlerSource, /schedulePump\('requested'\)/,
  "WebSocket delivery does not coalesce into the independent scheduler");
assert.doesNotMatch(inboundPumpHandlerSource, /callback\(\)/,
  "a WebSocket callback synchronously entered Java packet decoding");
const clientPacketDrainHandlerSource = clientNetworkInstallScript.slice(
  clientNetworkInstallScript.indexOf("bridge.clientPacketDrain = function()"),
  clientNetworkInstallScript.indexOf("bridge.invalidateClientPacketDrain = function(reason)"),
);
assert.match(clientPacketDrainHandlerSource,
  /const demand = clientPacketQueueDepth\(\) >= 64[\s\S]*if \(bridge\.clientPacketDrainDemand\) return false[\s\S]*clientPacketDrainDemandSignals\+\+/,
  "decoded PLAY queue pressure is not retained as one false-to-true demand edge");
assert.doesNotMatch(clientPacketDrainHandlerSource,
  /callback\(|MessageChannel|setTimeout|watchdog|postMessage|schedule[A-Z]/,
  "decoded-packet accounting can schedule or enter a PLAY handler outside runTick");
assert.match(clientPatcher,
  /browserPackets\.add\(beginClientPacketFrame\);[\s\S]*browserPackets\.add\(installClientNetwork\);[\s\S]*browserPackets\.add\(pumpClientChannels\)/,
  "Minecraft.runTick entry is not frame-accounting -> install -> raw transport pump");
assert.match(clientPatcher,
  /scheduledPacketBoundaries != 1[\s\S]*method\.instructions\.set\(scheduledPacketBoundary, frameBoundaryWrapper\)[\s\S]*wrapperCalls != 1 \|\| directPacketProcessorCalls != 0/,
  "Minecraft.runTick no longer replaces its one vanilla PacketProcessor call in place");

const bridgeScript = jsBodyBefore("private static native void initBridge();");
const outboundSchedulerScript = jsBodyBefore(
  "private static native void initOutboundScheduler();",
);
const schedulerScript = jsBodyBefore("private static native void initInboundScheduler();");
// TeaVM's @JSBody parser accepts explicit object keys here but rejects ES6
// property shorthand even though Node's runtime parser accepts it. Keep this
// regression gate next to the bounded high-watermark event schema.
assert.match(schedulerScript, /sequence: sequence,/);
assert.match(schedulerScript, /startedAtMillis: startedAtMillis,/);
assert.match(schedulerScript, /endedAtMillis: endedAtMillis,/);
assert.doesNotMatch(schedulerScript,
  /^\s*(?:sequence|startedAtMillis|endedAtMillis),\s*$/m);
const flowControlSource = schedulerScript.slice(
  schedulerScript.indexOf("function applyFlowControl(entry)"),
  schedulerScript.indexOf("function compactPending(entry)"),
);
assert.ok(flowControlSource.length > 0, "inbound flow-control function was not extracted");
assert.doesNotMatch(flowControlSource, /decoderCumulationBytes/,
  "partial packet cumulation must not self-deadlock the paused TCP source");
const scheduleSlicesSource = schedulerScript.slice(
  schedulerScript.indexOf("function scheduleSlices(entry, immediate)"),
  schedulerScript.indexOf("function pumpSlices(entry)"),
);
const pumpSlicesSource = schedulerScript.slice(
  schedulerScript.indexOf("function pumpSlices(entry)"),
  schedulerScript.indexOf("function releaseDecoderCumulation(entry"),
);
assert.match(schedulerScript,
  /function shouldBlockInboundSliceAdmission\(entry\)[\s\S]*state\.exactPacketQueuePaused \|\|[\s\S]*workDepth\(entry\) > decodedSliceLowWatermark/,
  "paused admission predicate does not distinguish exact/depth pauses from byte-only pressure");
assert.match(scheduleSlicesSource,
  /shouldBlockInboundSliceAdmission\(entry\)/,
  "paused slice admission can refill the 256-slice high watermark before low-water drain");
assert.match(pumpSlicesSource,
  /applyFlowControl\(entry\);\s*if \(shouldBlockInboundSliceAdmission\(entry\)\) return;[\s\S]*while \(/,
  "an already-scheduled slice callback can refill a paused high-watermark episode");
assert.match(source, /public static void recordInlineDecodedPacket\(\)/);
assert.match(clientPatcher,
  /BrowserWebSocketChannel"[\s\S]{0,160}"recordInlineDecodedPacket"[\s\S]{0,80}"\(\)V"/,
  "PacketUtils browser-inline branch does not retire completed decoder packets");
assert.match(clientPatcher,
  /PacketUtils inline decoder accounting CFG changed: hooks=/,
  "PacketUtils inline decoded-packet hook is not verified fail-closed");

// Deterministic Java-pump model for the multiplayer startup burst. The chunk cap is the first
// bound, but the 256 KiB and 2 ms budgets remain independent early exits.
function modelInboundPumpTurns(sliceCount, sliceBytes, perSliceMillis) {
  let remaining = sliceCount;
  const turns = [];
  while (remaining > 0) {
    let chunks = 0;
    let bytes = 0;
    let elapsed = 0;
    while (chunks < 64 && bytes < 256 * 1024) {
      if (chunks > 0 && elapsed >= 2) break;
      if (remaining <= 0) break;
      chunks++;
      bytes += sliceBytes;
      elapsed += perSliceMillis;
      remaining--;
    }
    turns.push({chunks, bytes, elapsed});
  }
  return turns;
}
const startupBurstModel = modelInboundPumpTurns(256, 4096, 0.01);
assert.deepEqual(startupBurstModel.map((turn) => turn.chunks), [64, 64, 64, 64],
  "256 tiny startup slices did not use four bounded turns");
assert.ok(startupBurstModel.every((turn) =>
  turn.chunks <= 64 && turn.bytes <= 256 * 1024 && turn.elapsed < 2),
"startup burst model bypassed a Java inbound pump budget");

function modelExactPacketQueueRecovery(depth, packetMillis) {
  const requestedPackets = Math.max(1, depth - 63);
  const batchTargetPackets = Math.min(256, requestedPackets);
  let packetsProcessed = 0;
  let elapsedMillis = 0;
  while (packetsProcessed < batchTargetPackets && depth > 0) {
    if (packetsProcessed > 0 && elapsedMillis >= 2) break;
    packetsProcessed++;
    elapsedMillis += packetMillis;
    depth--;
  }
  return {
    queueAfter: depth,
    packetsProcessed,
    elapsedMillis,
    remainingDebt: Math.max(0, depth - 63),
  };
}
const cheapExactQueueRecovery = modelExactPacketQueueRecovery(256, 0.01);
assert.equal(cheapExactQueueRecovery.packetsProcessed, 193);
assert.equal(cheapExactQueueRecovery.queueAfter, 63);
assert.equal(cheapExactQueueRecovery.remainingDebt, 0);
assert.ok(cheapExactQueueRecovery.elapsedMillis < 2 &&
  cheapExactQueueRecovery.elapsedMillis < 500,
"cheap exact-queue recovery did not clear in one bounded frame");
const timedExactQueueRecovery = modelExactPacketQueueRecovery(256, 0.25);
assert.equal(timedExactQueueRecovery.packetsProcessed, 8);
assert.ok(timedExactQueueRecovery.elapsedMillis >= 2 &&
  timedExactQueueRecovery.elapsedMillis < 50,
"expensive exact-queue recovery bypassed the two-millisecond boundary gate");

function modelPausedAdmissionDrain(depth, lowWatermark, chunksPerTurn) {
  const depths = [];
  while (depth > lowWatermark) {
    depth = Math.max(lowWatermark, depth - chunksPerTurn);
    depths.push(depth);
  }
  return depths;
}
const pausedAdmissionDrain = modelPausedAdmissionDrain(256, 64, 64);
assert.deepEqual(pausedAdmissionDrain,
  [192, 128, 64],
  "paused 256-slice episode did not drain monotonically to the exact low watermark");
assert.ok(pausedAdmissionDrain.every((depth, index) =>
  index === 0 || depth < pausedAdmissionDrain[index - 1]),
"paused admission model refilled an active high-watermark episode");
function modelAdmissionBlocked({paused, exactPacketQueuePaused, depth}) {
  return paused && (exactPacketQueuePaused || depth > 64);
}
assert.equal(modelAdmissionBlocked({paused: true, exactPacketQueuePaused: false, depth: 65}), true,
  "slice-depth pause admitted before the low watermark");
assert.equal(modelAdmissionBlocked({paused: true, exactPacketQueuePaused: false, depth: 64}), false,
  "byte-only pause self-deadlocked pending bytes at the low watermark");
assert.equal(modelAdmissionBlocked({paused: true, exactPacketQueuePaused: true, depth: 0}), true,
  "exact decoded-packet pause admitted more decoder work");

function createIndependentPumpHarness({
  readySlices,
  exactPacketQueuePaused = false,
  MessageChannelImpl = MessageChannel,
}) {
  const bridge = {
    stats: {},
    exactPacketQueuePaused,
  };
  const harnessContext = {
    Date,
    JSON,
    Math,
    MessageChannel: MessageChannelImpl,
    Number,
    String,
    clearTimeout,
    performance,
    setTimeout,
  };
  harnessContext.__gaiusNettyBridge = bridge;
  harnessContext.__gaiusNetworkStats = bridge.stats;
  const install = vm.runInNewContext(
    `(function(callback) {${clientNetworkInstallScript}})`,
    harnessContext,
  );
  let remaining = readySlices;
  const turns = [];
  const callback = () => {
    const processed = Math.min(64, remaining);
    remaining -= processed;
    turns.push(processed);
    return processed > 0 && remaining > 0;
  };
  assert.equal(install(callback), true,
    "independent browser transport pump did not install");
  return {
    bridge,
    context: harnessContext,
    remaining: () => remaining,
    turns,
  };
}

async function waitForIndependentPump(predicate, label, timeoutMillis = 2000) {
  const deadline = Date.now() + timeoutMillis;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error(`Timed out waiting for ${label}`);
    await new Promise((resolve) => setTimeout(resolve, 1));
  }
}

const independentPump = createIndependentPumpHarness({readySlices: 257});
for (let index = 0; index < 1000; index++) independentPump.bridge.inboundPump();
await waitForIndependentPump(
  () => independentPump.remaining() === 0 &&
    !independentPump.bridge.inboundPumpScheduler.pending,
  "257-slice independent pump drain",
);
assert.deepEqual(independentPump.turns, [64, 64, 64, 64, 1],
  "independent dispatcher did not preserve one bounded 64-slice batch per macrotask");
assert.equal(independentPump.bridge.stats.inboundPumpScheduled, 5,
  "progress-aware dispatcher did not self-reschedule exactly once per remaining batch");
assert.equal(independentPump.bridge.stats.inboundPumpCallbacks, 5);
assert.equal(independentPump.bridge.stats.inboundPumpRescheduled, 4);
assert.equal(independentPump.bridge.stats.inboundPumpCoalesced, 999,
  "bursty WebSocket signals were not coalesced behind one pending task");
assert.equal(independentPump.bridge.stats.inboundPumpJavaFailures, 0);
independentPump.bridge.inboundPumpScheduler.channel?.port1?.close?.();
independentPump.bridge.inboundPumpScheduler.channel?.port2?.close?.();

const exactPausedPump = createIndependentPumpHarness({
  readySlices: 32,
  exactPacketQueuePaused: true,
});
exactPausedPump.bridge.recordDecodedPacketQueue = function(depth, paused) {
  const wasPaused = this.exactPacketQueuePaused;
  this.stats.decodedPacketQueue = Math.max(0, Number(depth) || 0);
  this.exactPacketQueuePaused = !!paused;
  if (wasPaused && !this.exactPacketQueuePaused) {
    this.stats.decodedPacketQueueResumes =
      (this.stats.decodedPacketQueueResumes || 0) + 1;
    this.inboundPump();
  }
};
exactPausedPump.bridge.inboundPump();
await new Promise((resolve) => setTimeout(resolve, 10));
assert.equal(exactPausedPump.turns.length, 0,
  "exact packet pressure created a busy-looping transport task");
assert.equal(exactPausedPump.bridge.stats.inboundPumpBlockedByExactQueue, 1);
exactPausedPump.bridge.recordDecodedPacketQueue(64, false);
await waitForIndependentPump(() => exactPausedPump.remaining() === 0,
  "exact-queue resume wake");
assert.deepEqual(exactPausedPump.turns, [32],
  "exact-queue resume did not restart bounded raw transport progress");
assert.equal(exactPausedPump.bridge.stats.decodedPacketQueueResumes, 1,
  "exact-queue falling edge did not record exactly one resume wake");
const exactResumeScheduled = exactPausedPump.bridge.stats.inboundPumpScheduled;
exactPausedPump.bridge.recordDecodedPacketQueue(63, false);
assert.equal(exactPausedPump.bridge.stats.inboundPumpScheduled, exactResumeScheduled,
  "a non-edge decoded-queue update scheduled a duplicate transport wake");
exactPausedPump.bridge.inboundPumpScheduler.channel?.port1?.close?.();
exactPausedPump.bridge.inboundPumpScheduler.channel?.port2?.close?.();

let droppedMessageChannel;
class DroppedMessageChannel {
  constructor() {
    this.port1 = {onmessage: null, close() {}};
    this.port2 = {postMessage() {}, close() {}};
    droppedMessageChannel = this;
  }
}
const watchdogPump = createIndependentPumpHarness({
  readySlices: 1,
  MessageChannelImpl: DroppedMessageChannel,
});
watchdogPump.bridge.inboundPump();
await waitForIndependentPump(() => watchdogPump.remaining() === 0,
  "MessageChannel watchdog pump", 1000);
assert.equal(watchdogPump.bridge.stats.inboundPumpWatchdogCallbacks, 1,
  "a lost MessageChannel task was not rescued by the bounded watchdog");
assert.equal(watchdogPump.turns.length, 1,
  "watchdog and MessageChannel paths executed the Java callback more than once");
droppedMessageChannel.port1.onmessage({data: 1});
assert.equal(watchdogPump.turns.length, 1,
  "a late MessageChannel callback re-executed a completed pump generation");
assert.equal(watchdogPump.bridge.stats.inboundPumpStaleCallbacks, 1);

const sessionId = "5123456789abcdef0123456789abcdef";
const socketId = 91;
const frameBytes = 8 * 1024 * 1024;
const wireFrameBytes = 16 * 1024;
const decodedSliceBytes = 4 * 1024;
const flowControls = [];
const callbackDurations = [];

function delay(millis) {
  return new Promise((resolve) => setTimeout(resolve, millis));
}

async function waitFor(predicate, label, timeoutMillis = 5000) {
  const deadline = Date.now() + timeoutMillis;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error(`Timed out waiting for ${label}`);
    await delay(1);
  }
}

const highWatermarkReasons = new Set([
  "inbound-slice-depth",
  "inbound-bytes",
  "exact-packet-queue",
]);
function assertHighWatermarkEvent(event, label) {
  assert.ok(event && Number.isSafeInteger(event.sequence) && event.sequence > 0,
    `${label} sequence is not a positive monotonic integer`);
  assert.ok(Number.isInteger(event.channelId), `${label} channelId is not an integer`);
  assert.ok(highWatermarkReasons.has(event.reason), `${label} has an invalid reason`);
  for (const field of [
    "startedAtMillis",
    "endedAtMillis",
    "durationMillis",
    "startDepth",
    "endDepth",
    "startQueuedBytes",
    "endQueuedBytes",
    "startDecodedPacketQueue",
    "endDecodedPacketQueue",
    "startDecodedPacketDrainSignals",
    "endDecodedPacketDrainSignals",
    "startPumpCalls",
    "endPumpCalls",
    "startInboundSlicePumps",
    "endInboundSlicePumps",
    "startInboundPumpJavaCompleted",
    "endInboundPumpJavaCompleted",
  ]) {
    assert.ok(Number.isFinite(event[field]) && event[field] >= 0,
      `${label}.${field} is not a bounded non-negative number`);
  }
  assert.ok(event.endedAtMillis >= event.startedAtMillis,
    `${label} ended before it started`);
  assert.ok(Math.abs(
    event.durationMillis - (event.endedAtMillis - event.startedAtMillis),
  ) < 0.001, `${label} duration does not match its monotonic endpoints`);
  assert.equal(typeof event.startExactPacketQueuePaused, "boolean");
  assert.equal(typeof event.endExactPacketQueuePaused, "boolean");
}

function requestAnimationFrame(callback) {
  return setTimeout(() => {
    const startedAt = performance.now();
    callback(startedAt);
    callbackDurations.push(performance.now() - startedAt);
  }, 0);
}

const context = {
  AbortController,
  Array,
  ArrayBuffer,
  Boolean,
  Date,
  Int8Array,
  JSON,
  Map,
  Math,
  Number,
  Object,
  Promise,
  RegExp,
  Set,
  String,
  TextDecoder,
  TextEncoder,
  URL,
  URLSearchParams,
  Uint8Array,
  clearTimeout,
  console,
  fetch: async () => {
    throw new Error("Local inbound smoke must not use a relay");
  },
  localStorage: {getItem: () => null},
  location: {
    href: "file:///Downloads/Gaius.html",
    hostname: "",
    protocol: "file:",
    search: "",
  },
  performance,
  queueMicrotask,
  cancelAnimationFrame: clearTimeout,
  requestAnimationFrame,
  setTimeout,
  WebSocket: class UnexpectedWebSocket {
    constructor() {
      throw new Error("Local inbound smoke must not open a WebSocket");
    }
  },
  __gaiusLocalServerPorts: new Map(),
};
context.globalThis = context;
context.window = context;
vm.runInNewContext(`(function() {${bridgeScript}\n})();`, context);
vm.runInNewContext(`(function() {${outboundSchedulerScript}\n})();`, context);
vm.runInNewContext(`(function() {${schedulerScript}\n})();`, context);

const bridge = context.__gaiusNettyBridge;
const stats = context.__gaiusNetworkStats;
const {port1, port2} = new MessageChannel();
const launchGeneration = "1";
port1.__gaiusLaunchGeneration = launchGeneration;
context.__gaiusSingleplayerWorkers = new Map([[sessionId, {
  __gaiusTerminal: false,
  __gaiusLaunchGeneration: launchGeneration,
  __gaiusClientPort: port1,
}]]);
port2.on("message", (message) => {
  if (message && message.type === "flow") flowControls.push(message);
});
context.__gaiusLocalServerPorts.set(sessionId, port1);
bridge.open(socketId, `client-${sessionId}.gaius-local`, 25565);

for (let offset = 0; offset < frameBytes; offset += wireFrameBytes) {
  const fragment = new Uint8Array(wireFrameBytes);
  for (let index = 0; index < fragment.length; index++) {
    fragment[index] = ((offset + index) * 31 + 7) & 0xff;
  }
  port2.postMessage(fragment.buffer, [fragment.buffer]);
}

await waitFor(() => stats.receivedFrames === frameBytes / wireFrameBytes,
  "all 8 MiB protocol-frame fragments");
await waitFor(() => stats.maxInboundSliceQueue === 256, "256-slice high watermark");
await waitFor(() => flowControls.some((message) => message.paused === true),
  "transport pause control");
assert.equal(stats.peakInboundQueuedBytes, frameBytes,
  "8 MiB protocol frame was not accounted once");
assert.ok(stats.inboundQueuedBytes <= frameBytes,
  "transport queue grew beyond the received first frame");

let receivedBytes = 0;
let receivedSlices = 0;
let pumpTurns = 0;
let maxTurnChunks = 0;
let maxTurnBytes = 0;
const frameDeadline = Date.now() + 10_000;
while (receivedBytes < frameBytes) {
  assert.ok(Date.now() < frameDeadline,
    `8 MiB protocol frame stalled after ${receivedSlices} slices`);
  const turnStartedAt = performance.now();
  let turnChunks = 0;
  let turnBytes = 0;
  while (turnChunks < 64 && turnBytes < 256 * 1024) {
    if (turnChunks > 0 && performance.now() - turnStartedAt >= 2) break;
    const chunk = bridge.pollInbound(socketId);
    if (!chunk) break;
    assert.equal(chunk.byteLength, decodedSliceBytes,
      `protocol fragment was not ${decodedSliceBytes} bytes`);
    const firstAbsolute = receivedBytes;
    const middleAbsolute = receivedBytes + (chunk.byteLength >>> 1);
    const lastAbsolute = receivedBytes + chunk.byteLength - 1;
    assert.equal(chunk[0] & 0xff, (firstAbsolute * 31 + 7) & 0xff,
      `TCP byte order changed at offset ${firstAbsolute}`);
    assert.equal(chunk[chunk.byteLength >>> 1] & 0xff,
      (middleAbsolute * 31 + 7) & 0xff,
      `TCP byte order changed at offset ${middleAbsolute}`);
    assert.equal(chunk[chunk.byteLength - 1] & 0xff,
      (lastAbsolute * 31 + 7) & 0xff,
      `TCP byte order changed at offset ${lastAbsolute}`);
    bridge.recordDecodedSlice(socketId, chunk.byteLength);
    receivedBytes += chunk.byteLength;
    receivedSlices++;
    turnChunks++;
    turnBytes += chunk.byteLength;
    if (receivedBytes === frameBytes) {
      // This is one protocol packet. No decoded-packet credit exists before its final slice.
      bridge.recordDecodedPacketQueue(1, false, false);
    }
    bridge.finishDecodedSlice(socketId);
  }
  if (turnChunks > 0) {
    const turnMillis = Math.max(0, performance.now() - turnStartedAt);
    bridge.recordPump(socketId, turnChunks, turnBytes, turnMillis);
    pumpTurns++;
    maxTurnChunks = Math.max(maxTurnChunks, turnChunks);
    maxTurnBytes = Math.max(maxTurnBytes, turnBytes);
  }
  await delay(0);
}

assert.equal(receivedBytes, frameBytes, "8 MiB protocol frame lost bytes");
assert.equal(receivedSlices, 2048,
  "8 MiB protocol frame did not produce 2048 4 KiB slices");
assert.ok(pumpTurns >= 32, "8 MiB frame bypassed bounded 64-slice event-loop turns");
assert.ok(maxTurnChunks <= 64, "an inbound event-loop turn exceeded 64 slices");
assert.ok(maxTurnBytes <= 256 * 1024, "an inbound event-loop turn exceeded 256 KiB");
assert.equal(stats.maxDecodedSliceBacklog, 1,
  "raw-slice ownership survived beyond a synchronous decoder handoff");
assert.equal(stats.decodedSliceBacklog, 0,
  "raw-slice ownership remained after the decoder handoff returned");
assert.equal(stats.maxDecoderCumulationBytes, frameBytes,
  "decoder cumulation did not account the complete 8 MiB protocol frame");
assert.ok(stats.decoderCumulationBytes <= decodedSliceBytes,
  "decoded packet retained more than the conservative active-slice tail");
bridge.recordDecodedPacketQueue(0, false, true);
await waitFor(() => flowControls.some((message) => message.paused === false),
  "fragmented packet transport resume");
assert.equal(stats.decodedSliceBacklog, 0,
  "one fragmented packet retained raw-slice ownership");

const mergedPayload = new Uint8Array(4096);
const mergedExpectedFrames = stats.receivedFrames + 1;
port2.postMessage(mergedPayload.buffer, [mergedPayload.buffer]);
await waitFor(() => stats.receivedFrames === mergedExpectedFrames, "merged packet frame");
let mergedChunk;
await waitFor(() => (mergedChunk = bridge.pollInbound(socketId)) !== null,
  "merged packet slice");
assert.equal(mergedChunk.byteLength, 4096);
bridge.recordDecodedSlice(socketId, mergedChunk.byteLength);
bridge.recordDecodedPacketQueue(256, true, false);
bridge.finishDecodedSlice(socketId);
for (let depth = 255; depth >= 0; depth--) {
  bridge.recordDecodedPacketQueue(depth, depth > 64, true);
}
await waitFor(() => flowControls.filter((message) => message.paused === false).length >= 2,
  "merged packet transport resume");
assert.equal(stats.decodedSliceBacklog, 0,
  "coalesced packets created synthetic decoded-slice debt");
assert.equal(stats.maxDecodedPacketQueue, 256,
  "exact decoded-packet queue did not reach its high watermark");
assert.equal(stats.decodedPacketQueue, 0, "exact decoded-packet queue did not drain to zero");

// Regression for the real 26.2 multiplayer failure: the transport reached its 256-slice
// watermark, Java drained the raw slices, but an unfinished length-prefixed packet retained more
// than one 4 KiB slice. That tail needs additional TCP bytes, so it must not block the flow
// resume that allows those bytes to arrive.  Inline configuration packets retire cumulation at
// their exact PacketUtils boundary instead of depending on the queued PLAY packet scheduler.
const inlinePauseCount = stats.flowPauses;
const inlineResumeCount = stats.flowResumes;
const inlineFragments = 256;
const inlineFragmentBytes = 4096;
for (let index = 0; index < inlineFragments; index++) {
  const payload = new Uint8Array(inlineFragmentBytes);
  payload[0] = index & 0xff;
  port2.postMessage(payload.buffer, [payload.buffer]);
}
await waitFor(() => stats.flowPauses > inlinePauseCount,
  "inline configuration transport pause");
const startupRuntimeTurnChunks = [];
const startupRuntimeTurnBytes = [];
let inlineIndex = 0;
while (inlineIndex < inlineFragments) {
  const turnStartedAt = performance.now();
  let turnChunks = 0;
  let turnBytes = 0;
  while (turnChunks < 64 && turnBytes < 256 * 1024) {
    if (turnChunks > 0 && performance.now() - turnStartedAt >= 2) break;
    const chunk = bridge.pollInbound(socketId);
    if (!chunk) break;
    assert.equal(chunk.byteLength, inlineFragmentBytes);
    bridge.recordDecodedSlice(socketId, chunk.byteLength);
    if (inlineIndex === 0) bridge.recordInlineDecodedPacket();
    bridge.finishDecodedSlice(socketId);
    inlineIndex++;
    turnChunks++;
    turnBytes += chunk.byteLength;
  }
  if (turnChunks > 0) {
    bridge.recordPump(
      socketId,
      turnChunks,
      turnBytes,
      Math.max(0, performance.now() - turnStartedAt),
    );
    startupRuntimeTurnChunks.push(turnChunks);
    startupRuntimeTurnBytes.push(turnBytes);
  } else {
    await waitFor(() => bridge.hasPendingInbound(socketId),
      "next inline configuration startup slice");
  }
  await delay(0);
}
assert.equal(startupRuntimeTurnChunks.reduce((total, chunks) => total + chunks, 0), 256,
  "runtime startup pump lost tiny slices");
assert.ok(startupRuntimeTurnChunks.every((chunks) => chunks > 0 && chunks <= 64),
  "runtime startup pump exceeded its 64-chunk bound");
assert.ok(startupRuntimeTurnBytes.every((bytes) => bytes <= 256 * 1024),
  "runtime startup pump exceeded its 256 KiB bound");
assert.equal(Math.max(...startupRuntimeTurnChunks), 64,
  "runtime startup pump never exercised the bounded 64-chunk cap");
await waitFor(() => stats.flowResumes > inlineResumeCount,
  "inline partial-packet transport resume");
assert.ok(stats.decoderCumulationBytes > 64 * 1024,
  "inline regression did not retain a multi-slice partial packet");
assert.equal(bridge.channels.get(socketId).flowPaused, false,
  "transport remained paused while a partial packet awaited TCP bytes");

const completion = new Uint8Array([0x7f]);
const completionExpectedFrames = stats.receivedFrames + 1;
port2.postMessage(completion.buffer, [completion.buffer]);
await waitFor(() => stats.receivedFrames === completionExpectedFrames,
  "inline partial-packet completion frame");
let completionChunk;
await waitFor(() => (completionChunk = bridge.pollInbound(socketId)) !== null,
  "inline partial-packet completion slice");
bridge.recordDecodedSlice(socketId, completionChunk.byteLength);
bridge.recordInlineDecodedPacket();
bridge.finishDecodedSlice(socketId);
assert.equal(stats.decoderCumulationBytes, completionChunk.byteLength,
  "inline packet completion did not retire older decoder cumulation");
assert.equal(stats.inlineDecodedPackets, 2,
  "inline decoded-packet accounting did not observe both packet boundaries");

const quickCycles = 180;
const longDeadline = process.env.GAIUS_LONG_SMOKE === "1"
  ? Date.now() + 30 * 60 * 1000
  : 0;
const memorySamples = [];
let nextMemorySampleAt = Date.now();
let cycles = 0;
do {
  const payload = new Uint8Array(4096);
  payload[0] = cycles & 0xff;
  const expectedFrames = stats.receivedFrames + 1;
  port2.postMessage(payload.buffer, [payload.buffer]);
  await waitFor(() => stats.receivedFrames === expectedFrames, "sustained inbound frame");
  let chunk;
  await waitFor(() => (chunk = bridge.pollInbound(socketId)) !== null,
    "sustained inbound slice");
  assert.equal(chunk.byteLength, 4096);
  assert.equal(chunk[0] & 0xff, cycles & 0xff);
  bridge.recordDecodedSlice(socketId, chunk.byteLength);
  bridge.recordDecodedPacketQueue(1, false, false);
  bridge.finishDecodedSlice(socketId);
  bridge.recordDecodedPacketQueue(0, false, true);
  assert.equal(stats.inboundQueuedBytes, 0,
    "sustained transport cycle left queued bytes behind");
  assert.equal(stats.decodedPacketQueue, 0,
    "sustained transport cycle left decoded packets behind");
  if (longDeadline && Date.now() >= nextMemorySampleAt) {
    if (global.gc) global.gc();
    const usage = process.memoryUsage();
    memorySamples.push({
      at: Date.now(),
      heapUsed: usage.heapUsed,
      arrayBuffers: usage.arrayBuffers,
    });
    nextMemorySampleAt = Date.now() + 60 * 1000;
  }
  cycles++;
  if (longDeadline) await delay(10);
} while (longDeadline ? Date.now() < longDeadline : cycles < quickCycles);

await delay(120);
const entry = bridge.channels.get(socketId);
for (let index = 0; index < 65; index++) {
  bridge.recordDecodedPacketQueue(
    0, false, true, 50 + index, `test.QueuedPacket${index}`);
}
assert.equal(stats.queuedPacketHandleSamples, 65,
  "queued packet handler sample counter lost exact completions");
assert.equal(stats.maxQueuedPacketHandleMillis, 114,
  "queued packet handler maximum was not retained");
assert.equal(stats.maxQueuedPacketHandleType, "test.QueuedPacket64",
  "queued packet handler maximum lost its exact packet type");
assert.equal(stats.slowQueuedPacketEventSequence, 65,
  "slow queued packet sequence is not monotonic");
assert.equal(stats.slowQueuedPacketEvents.length, 64,
  "slow queued packet evidence ring is not bounded to 64");
assert.equal(stats.slowQueuedPacketEventsDropped, 1,
  "slow queued packet evidence did not report its dropped prefix");
assert.equal(stats.slowQueuedPacketEvents[0].sequence, 2,
  "slow queued packet evidence did not retain the newest bounded suffix");
assert.equal(entry.inboundBytes, 0);
assert.equal(entry.pendingInboundBytes, 0);
assert.equal(entry.inbound.length, 0,
  "drained slice array retained a large backing buffer");
assert.equal(entry.pendingInbound.length, 0,
  "drained raw-frame array retained a large backing buffer");
assert.equal(stats.inboundQueuedBytes, 0, "transport byte queue did not drain to zero");
assert.ok(stats.maxInboundSliceQueue <= 256, "slice queue exceeded its hard high watermark");
assert.ok(stats.maxDecodedSliceBacklog <= 256,
  "decoded-slice backlog exceeded its hard high watermark");
assert.ok(stats.maxDecoderCumulationBytes <= stats.decoderCumulationLimitBytes,
  "decoder cumulation exceeded its 16 MiB hard limit");
assert.ok(stats.maxDecodedPacketQueue <= 256,
  "exact decoded-packet queue exceeded its hard high watermark");
assert.ok(stats.decodedSliceBacklogPauses >= 1 &&
  stats.decodedSliceBacklogResumes >= 1,
  "decoded-slice backpressure did not complete a pause/resume cycle");
assert.ok(stats.decodedPacketDrainSignals >= 257,
  "actual PacketProcessor drain signals did not retire decoded-slice debt");
assert.equal(stats.decodedPacketQueuePauses, 1,
  "exact decoded-packet queue did not pause once at 256");
assert.equal(stats.decodedPacketQueueResumes, 1,
  "exact decoded-packet queue did not resume once at 64");
assert.equal(entry.flowPaused, false, "transport remained flow-paused after the large packet");
assert.equal(entry.decodeFlowPaused, false,
  "decoder flow state remained paused after the large packet");
assert.equal(bridge.exactPacketQueuePaused, false,
  "decoded-packet queue remained paused after drain");
assert.equal(stats.activeHighWatermarks, 0, "high watermark remained active after drain");
assert.ok(stats.highWatermarkDurationMillis > 0,
  "high watermark duration telemetry was not recorded");
assert.ok(stats.longestHighWatermarkMillis > 0,
  "longest high watermark telemetry was not recorded");
assert.ok(stats.longestHighWatermarkMillis < 500,
  `high-watermark episode lasted ${stats.longestHighWatermarkMillis.toFixed(1)} ms`);
assert.ok(stats.longestInboundSlicePumpMillis < 500,
  `single slice pump blocked for ${stats.longestInboundSlicePumpMillis.toFixed(1)} ms`);
assert.ok(stats.inboundImmediateSchedules > 0,
  "initial inbound slices did not use an immediate schedule");
assert.ok(stats.inboundRafSchedules > 0,
  "bounded inbound continuation did not use a render-fair schedule");
assert.equal(stats.inboundTimerSchedules, 0,
  "requestAnimationFrame-capable smoke unexpectedly used timer continuation");
assert.equal(stats.inboundSliceScheduleWaitSamples,
  stats.inboundImmediateSchedules + stats.inboundRafSchedules +
    stats.inboundTimerSchedules,
  "inbound schedule-wait telemetry lost a callback");
assert.ok(Number.isFinite(stats.maxInboundSliceScheduleWaitMillis) &&
  stats.maxInboundSliceScheduleWaitMillis < 500,
  `inbound slice scheduling waited ${stats.maxInboundSliceScheduleWaitMillis.toFixed(1)} ms`);
assert.ok(stats.longestEventLoopGapMillis < 500,
  `event loop gap reached ${stats.longestEventLoopGapMillis.toFixed(1)} ms`);
assert.equal(stats.eventLoopGapsOver500, 0, "observed an event loop gap >=500 ms");
assert.ok(Math.max(0, ...callbackDurations) < 500,
  "requestAnimationFrame slice callback blocked for >=500 ms");
if (memorySamples.length >= 5) {
  const tail = memorySamples.slice(-5);
  const heapMonotonic = tail.every((sample, index) =>
    index === 0 || sample.heapUsed > tail[index - 1].heapUsed);
  const buffersMonotonic = tail.every((sample, index) =>
    index === 0 || sample.arrayBuffers > tail[index - 1].arrayBuffers);
  assert.ok(!heapMonotonic, "post-GC heap grew monotonically across the last five minutes");
  assert.ok(!buffersMonotonic,
    "post-GC ArrayBuffer memory grew monotonically across the last five minutes");
}

// Decoder cumulation is no longer a relay pause reason, so its independent 16 MiB guard must
// remain fail-closed.  Prime a second channel at the exact limit and deliver one more byte; the
// bridge must reject it before handing the slice to Java and must retire every owned buffer.
const overflowSessionId = "6123456789abcdef0123456789abcdef";
const overflowSocketId = 92;
const {port1: overflowPort1, port2: overflowPort2} = new MessageChannel();
overflowPort1.__gaiusLaunchGeneration = launchGeneration;
context.__gaiusSingleplayerWorkers.set(overflowSessionId, {
  __gaiusTerminal: false,
  __gaiusLaunchGeneration: launchGeneration,
  __gaiusClientPort: overflowPort1,
});
context.__gaiusLocalServerPorts.set(overflowSessionId, overflowPort1);
bridge.open(overflowSocketId, `client-${overflowSessionId}.gaius-local`, 25565);
const overflowEntry = bridge.channels.get(overflowSocketId);
const decoderHardLimitBytes = 16 * 1024 * 1024;
overflowEntry.decoderCumulationBytes = decoderHardLimitBytes;
stats.decoderCumulationBytes += decoderHardLimitBytes;
const overflowExpectedFrames = stats.receivedFrames + 1;
const overflowByte = new Uint8Array([0x01]);
overflowPort2.postMessage(overflowByte.buffer, [overflowByte.buffer]);
await waitFor(() => stats.receivedFrames === overflowExpectedFrames,
  "decoder hard-limit overflow frame");
let overflowPoll;
await waitFor(() => {
  overflowPoll = bridge.pollInbound(overflowSocketId);
  return overflowEntry.closed;
}, "decoder hard-limit failure");
assert.equal(overflowPoll, null,
  "decoder hard-limit overflow escaped into the Java pipeline");
assert.match(bridge.pollError(overflowSocketId),
  /Browser decoder cumulation exceeded 16 MiB/);
assert.equal(overflowEntry.disposed, true,
  "decoder hard-limit failure did not dispose the channel");
assert.equal(overflowEntry.decoderCumulationBytes, 0,
  "decoder hard-limit failure retained cumulation bytes");
bridge.close(overflowSocketId);
overflowPort2.close();

// A single large startup frame pauses on bytes before it is sliced.  Closing the channel while
// that episode is active must finalize the completed-event record with its start/end snapshots,
// rather than leaving an active watermark behind or losing the diagnostic.
const bytePauseSessionId = "7123456789abcdef0123456789abcdef";
const bytePauseSocketId = 93;
const {port1: bytePausePort1, port2: bytePausePort2} = new MessageChannel();
bytePausePort1.__gaiusLaunchGeneration = launchGeneration;
context.__gaiusSingleplayerWorkers.set(bytePauseSessionId, {
  __gaiusTerminal: false,
  __gaiusLaunchGeneration: launchGeneration,
  __gaiusClientPort: bytePausePort1,
});
context.__gaiusLocalServerPorts.set(bytePauseSessionId, bytePausePort1);
bridge.open(bytePauseSocketId, `client-${bytePauseSessionId}.gaius-local`, 25565);
const bytePauseExpectedFrames = stats.receivedFrames + 1;
const bytePausePayload = new Uint8Array(24 * 1024 * 1024);
bytePausePort2.postMessage(bytePausePayload.buffer, [bytePausePayload.buffer]);
await waitFor(() => stats.receivedFrames === bytePauseExpectedFrames,
  "24 MiB inbound-byte watermark frame");
const bytePauseEntry = bridge.channels.get(bytePauseSocketId);
assert.equal(bytePauseEntry.highWatermarkEpisode?.reason, "inbound-bytes",
  "large startup frame did not retain its active byte-watermark reason");
assert.equal(bytePauseEntry.highWatermarkEpisode?.channelId, bytePauseSocketId);
assert.equal(bytePauseEntry.highWatermarkEpisode?.startQueuedBytes, 24 * 1024 * 1024);
const bytePauseSequence = bytePauseEntry.highWatermarkEpisode.sequence;
bridge.close(bytePauseSocketId);
bytePausePort2.close();
assert.equal(bytePauseEntry.highWatermarkEpisode, null,
  "discard left a high-watermark episode active on the closed entry");
assert.equal(bytePauseEntry.flowPaused, false,
  "discard left the closed entry flow-paused");
const bytePauseEvent = stats.highWatermarkEvents.find(
  (event) => event.sequence === bytePauseSequence,
);
assertHighWatermarkEvent(bytePauseEvent, "discard-completed byte watermark");
assert.equal(bytePauseEvent.reason, "inbound-bytes");
assert.equal(bytePauseEvent.channelId, bytePauseSocketId);

bridge.close(socketId);
port2.close();
assert.equal(bridge.channels.size, 0, "closed channel remained registered");
assert.equal(bridge.gapProbeTimer, 0, "event-loop gap probe timer survived final close");
assert.equal(stats.activeHighWatermarks, 0,
  "closed channels left active high-watermark episodes");

const runtimeHighWatermarkEvents = Array.from(
  stats.highWatermarkEvents,
  (event) => ({...event}),
);
assert.ok(runtimeHighWatermarkEvents.length > 0 && runtimeHighWatermarkEvents.length <= 64,
  "runtime high-watermark completed-event ring is empty or unbounded");
runtimeHighWatermarkEvents.forEach((event, index) => {
  assertHighWatermarkEvent(event, `runtime high-watermark event ${index}`);
  if (index > 0) {
    assert.ok(event.sequence > runtimeHighWatermarkEvents[index - 1].sequence,
      "runtime high-watermark events are not sorted by their start sequence");
  }
});
const runtimeHighWatermarkReasons = Array.from(new Set(
  runtimeHighWatermarkEvents.map((event) => event.reason),
)).sort();
assert.deepEqual(runtimeHighWatermarkReasons, [
  "exact-packet-queue",
  "inbound-bytes",
  "inbound-slice-depth",
], "runtime flow control did not exercise every exact high-watermark reason");

// Deterministic ring overflow contract: sequence is assigned while the episode is active, the
// completed ring retains the newest 64 starts in order, and the 65th completion increments the
// dropped count exactly once.
stats.highWatermarkEventSequence = 0;
stats.highWatermarkEvents = [];
stats.highWatermarkEventsDropped = 0;
const ringEntry = {
  id: 94,
  highWatermarkStartedAt: 0,
  highWatermarkEpisode: null,
};
const ringReasons = [
  "inbound-slice-depth",
  "inbound-bytes",
  "exact-packet-queue",
];
for (let index = 0; index < 65; index++) {
  bridge.startHighWatermark(
    ringEntry,
    ringReasons[index % ringReasons.length],
    index,
    index * 4096,
  );
  assert.equal(ringEntry.highWatermarkEpisode.sequence, index + 1,
    "high-watermark sequence was not allocated at episode start");
  bridge.finishHighWatermark(ringEntry, index + 1, (index + 1) * 4096);
}
assert.equal(stats.highWatermarkEventSequence, 65);
assert.equal(stats.highWatermarkEvents.length, 64,
  "completed high-watermark event ring exceeded its 64-entry bound");
assert.equal(stats.highWatermarkEventsDropped, 1,
  "65 completed high-watermark events did not drop exactly one oldest event");
assert.deepEqual(
  Array.from(stats.highWatermarkEvents, (event) => event.sequence),
  Array.from({length: 64}, (_unused, index) => index + 2),
  "completed high-watermark ring did not retain sequences 2..65 in order",
);
stats.highWatermarkEvents.forEach((event, index) => {
  assertHighWatermarkEvent(event, `bounded high-watermark event ${index}`);
  assert.equal(event.channelId, ringEntry.id);
  assert.equal(event.endDepth, event.startDepth + 1);
  assert.equal(event.endQueuedBytes, event.startQueuedBytes + 4096);
});
assert.equal(ringEntry.highWatermarkEpisode, null);
assert.equal(stats.activeHighWatermarks, 0);
await delay(20);
console.log(JSON.stringify({
  ok: true,
  firstFrameBytes: receivedBytes,
  firstFrameSlices: receivedSlices,
  firstFramePumpTurns: pumpTurns,
  maxFirstFrameTurnChunks: maxTurnChunks,
  maxFirstFrameTurnBytes: maxTurnBytes,
  inboundSlices: stats.inboundSlices,
  maxInboundSliceQueue: stats.maxInboundSliceQueue,
  maxDecodedSliceBacklog: stats.maxDecodedSliceBacklog,
  maxDecoderCumulationBytes: stats.maxDecoderCumulationBytes,
  maxDecodedPacketQueue: stats.maxDecodedPacketQueue,
  queuedPacketHandleSamples: stats.queuedPacketHandleSamples,
  maxQueuedPacketHandleMillis: stats.maxQueuedPacketHandleMillis,
  maxQueuedPacketHandleType: stats.maxQueuedPacketHandleType,
  slowQueuedPacketEventsRetained: stats.slowQueuedPacketEvents.length,
  slowQueuedPacketEventsDropped: stats.slowQueuedPacketEventsDropped,
  decodedSliceBacklogPauses: stats.decodedSliceBacklogPauses,
  decodedSliceBacklogResumes: stats.decodedSliceBacklogResumes,
  decodedPacketDrainSignals: stats.decodedPacketDrainSignals,
  decodedPacketQueuePauses: stats.decodedPacketQueuePauses,
  decodedPacketQueueResumes: stats.decodedPacketQueueResumes,
  inlineDecodedPackets: stats.inlineDecodedPackets,
  highWatermarkDurationMillis: Number(stats.highWatermarkDurationMillis.toFixed(3)),
  longestHighWatermarkMillis: Number(stats.longestHighWatermarkMillis.toFixed(3)),
  longestInboundSlicePumpMillis: Number(stats.longestInboundSlicePumpMillis.toFixed(3)),
  inboundImmediateSchedules: stats.inboundImmediateSchedules,
  inboundRafSchedules: stats.inboundRafSchedules,
  inboundTimerSchedules: stats.inboundTimerSchedules,
  maxInboundSliceScheduleWaitMillis:
    Number(stats.maxInboundSliceScheduleWaitMillis.toFixed(3)),
  startupBurstModelTurns: startupBurstModel.map((turn) => turn.chunks),
  cheapExactQueueRecovery,
  timedExactQueueRecovery,
  startupRuntimeTurnChunks,
  runtimeHighWatermarkReasons,
  highWatermarkEventSequence: stats.highWatermarkEventSequence,
  highWatermarkEventsRetained: stats.highWatermarkEvents.length,
  highWatermarkEventsDropped: stats.highWatermarkEventsDropped,
  longestEventLoopGapMillis: Number(stats.longestEventLoopGapMillis.toFixed(3)),
  sustainedCycles: cycles,
  realThirtyMinuteRun: Boolean(longDeadline),
  memorySamples: memorySamples.length,
}));
