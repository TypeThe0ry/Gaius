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
function jsBodyBefore(marker) {
  const markerOffset = source.indexOf(marker);
  const annotationOffset = source.lastIndexOf('@JSBody(script = """', markerOffset);
  const scriptOffset = source.indexOf('"""', annotationOffset) + 3;
  const scriptEnd = source.lastIndexOf('""")', markerOffset);
  assert.ok(markerOffset > 0 && annotationOffset > 0 && scriptEnd > scriptOffset,
    `JSBody could not be extracted for ${marker}`);
  return source.slice(scriptOffset, scriptEnd).replaceAll("\\\\", "\\");
}
assert.match(source, /maximumInboundSliceBytes = 64 \* 1024/);
assert.match(source, /decodedSliceHighWatermark = 256/);
assert.match(source, /decodedSliceLowWatermark = 64/);
assert.match(source, /decoderCumulationPauseBytes = 12 \* 1024 \* 1024/);
assert.match(source, /maximumDecoderCumulationBytes = 16 \* 1024 \* 1024/);
assert.match(source, /MAX_MILLIS_PER_PUMP = 2\.0/);

const bridgeScript = jsBodyBefore("private static native void initBridge();");
const outboundSchedulerScript = jsBodyBefore(
  "private static native void initOutboundScheduler();",
);
const schedulerScript = jsBodyBefore("private static native void initInboundScheduler();");
const sessionId = "5123456789abcdef0123456789abcdef";
const socketId = 91;
const frameBytes = 8 * 1024 * 1024;
const wireSliceBytes = 16 * 1024;
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
port2.on("message", (message) => {
  if (message && message.type === "flow") flowControls.push(message);
});
context.__gaiusLocalServerPorts.set(sessionId, port1);
bridge.open(socketId, `client-${sessionId}.gaius-local`, 25565);

for (let offset = 0; offset < frameBytes; offset += wireSliceBytes) {
  const fragment = new Uint8Array(wireSliceBytes);
  for (let index = 0; index < fragment.length; index++) {
    fragment[index] = ((offset + index) * 31 + 7) & 0xff;
  }
  port2.postMessage(fragment.buffer, [fragment.buffer]);
}

await waitFor(() => stats.receivedFrames === frameBytes / wireSliceBytes,
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
  while (turnChunks < 16 && turnBytes < 1024 * 1024) {
    if (turnChunks > 0 && performance.now() - turnStartedAt >= 2) break;
    const chunk = bridge.pollInbound(socketId);
    if (!chunk) break;
    assert.equal(chunk.byteLength, wireSliceBytes,
      `protocol fragment was not ${wireSliceBytes} bytes`);
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
assert.equal(receivedSlices, 512,
  "8 MiB protocol frame did not produce 512 16 KiB fragments");
assert.ok(pumpTurns >= 32, "8 MiB frame bypassed bounded 16-slice event-loop turns");
assert.ok(maxTurnChunks <= 16, "an inbound event-loop turn exceeded 16 slices");
assert.ok(maxTurnBytes <= 1024 * 1024, "an inbound event-loop turn exceeded 1 MiB");
assert.equal(stats.maxDecodedSliceBacklog, 1,
  "raw-slice ownership survived beyond a synchronous decoder handoff");
assert.equal(stats.decodedSliceBacklog, 0,
  "raw-slice ownership remained after the decoder handoff returned");
assert.equal(stats.maxDecoderCumulationBytes, frameBytes,
  "decoder cumulation did not account the complete 8 MiB protocol frame");
assert.ok(stats.decoderCumulationBytes <= wireSliceBytes,
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
assert.ok(stats.longestInboundSlicePumpMillis < 500,
  `single slice pump blocked for ${stats.longestInboundSlicePumpMillis.toFixed(1)} ms`);
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

bridge.close(socketId);
port2.close();
assert.equal(bridge.channels.size, 0, "closed channel remained registered");
assert.equal(bridge.gapProbeTimer, 0, "event-loop gap probe timer survived final close");
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
  decodedSliceBacklogPauses: stats.decodedSliceBacklogPauses,
  decodedSliceBacklogResumes: stats.decodedSliceBacklogResumes,
  decodedPacketDrainSignals: stats.decodedPacketDrainSignals,
  decodedPacketQueuePauses: stats.decodedPacketQueuePauses,
  decodedPacketQueueResumes: stats.decodedPacketQueueResumes,
  highWatermarkDurationMillis: Number(stats.highWatermarkDurationMillis.toFixed(3)),
  longestHighWatermarkMillis: Number(stats.longestHighWatermarkMillis.toFixed(3)),
  longestInboundSlicePumpMillis: Number(stats.longestInboundSlicePumpMillis.toFixed(3)),
  longestEventLoopGapMillis: Number(stats.longestEventLoopGapMillis.toFixed(3)),
  sustainedCycles: cycles,
  realThirtyMinuteRun: Boolean(longDeadline),
  memorySamples: memorySamples.length,
}));
