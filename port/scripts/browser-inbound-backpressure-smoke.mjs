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
assert.match(source, /MAX_MILLIS_PER_PUMP = 2\.0/);

const bridgeScript = jsBodyBefore("private static native void initBridge();");
const schedulerScript = jsBodyBefore("private static native void initInboundScheduler();");
const sessionId = "5123456789abcdef0123456789abcdef";
const socketId = 91;
const frameBytes = 16 * 1024 * 1024;
const sliceBytes = 64 * 1024;
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
vm.runInNewContext(`(function() {${schedulerScript}\n})();`, context);

const bridge = context.__gaiusNettyBridge;
const stats = context.__gaiusNetworkStats;
const {port1, port2} = new MessageChannel();
port2.on("message", (message) => {
  if (message && message.type === "flow") flowControls.push(message);
});
context.__gaiusLocalServerPorts.set(sessionId, port1);
bridge.open(socketId, `client-${sessionId}.gaius-local`, 25565);

let frame = new Uint8Array(frameBytes);
for (let index = 0; index < frame.length; index++) {
  frame[index] = (index * 31 + 7) & 0xff;
}
port2.postMessage(frame.buffer, [frame.buffer]);
frame = null;

await waitFor(() => stats.maxInboundSliceQueue === 256, "256-slice high watermark");
await waitFor(() => flowControls.some((message) => message.paused === true),
  "transport pause control");
assert.equal(stats.peakInboundQueuedBytes, frameBytes,
  "16 MiB first frame was not accounted once");
assert.ok(stats.inboundQueuedBytes <= frameBytes,
  "transport queue grew beyond the received first frame");

let receivedBytes = 0;
let receivedSlices = 0;
let decodedPacketDepth = 0;
let decodedPacketPaused = false;
while (receivedBytes < frameBytes) {
  const chunk = bridge.pollInbound(socketId);
  if (!chunk) {
    await delay(0);
    continue;
  }
  assert.ok(chunk.byteLength > 0 && chunk.byteLength <= sliceBytes,
    `inbound slice exceeded 64 KiB: ${chunk.byteLength}`);
  for (let index = 0; index < chunk.byteLength; index++) {
    const absolute = receivedBytes + index;
    assert.equal(chunk[index] & 0xff, (absolute * 31 + 7) & 0xff,
      `TCP byte order changed at offset ${absolute}`);
  }
  receivedBytes += chunk.byteLength;
  receivedSlices++;
  bridge.recordDecodedSlice(socketId);
  decodedPacketDepth++;
  if (decodedPacketDepth >= 256) decodedPacketPaused = true;
  bridge.recordDecodedPacketQueue(decodedPacketDepth, decodedPacketPaused, false);
}

assert.equal(receivedBytes, frameBytes, "16 MiB first frame lost bytes");
assert.equal(receivedSlices, frameBytes / sliceBytes,
  "16 MiB first frame did not produce exactly 64 KiB slices");
assert.equal(stats.maxDecodedSliceBacklog, 256,
  "decoded-slice backlog did not reach its bounded high watermark");
assert.equal(stats.decodedSliceBacklog, 256,
  "decoded-slice accounting diverged before packet drain");
assert.equal(stats.maxDecodedPacketQueue, 256,
  "exact decoded-packet queue did not reach its high watermark");

for (let index = 0; index < 192; index++) {
  decodedPacketDepth--;
  if (decodedPacketDepth <= 64) decodedPacketPaused = false;
  bridge.recordDecodedPacketQueue(decodedPacketDepth, decodedPacketPaused, true);
}
await waitFor(() => flowControls.some((message) => message.paused === false),
  "transport resume control at low watermark");
assert.equal(stats.decodedSliceBacklog, 64,
  "decoded-slice backlog did not resume at the configured low watermark");
for (let index = 0; index < 64; index++) {
  decodedPacketDepth--;
  bridge.recordDecodedPacketQueue(decodedPacketDepth, false, true);
}
assert.equal(stats.decodedSliceBacklog, 0, "decoded-slice backlog did not drain to zero");
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
  bridge.recordDecodedSlice(socketId);
  bridge.recordDecodedPacketQueue(1, false, false);
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
assert.ok(stats.maxDecodedPacketQueue <= 256,
  "exact decoded-packet queue exceeded its hard high watermark");
assert.ok(stats.decodedSliceBacklogPauses >= 1 &&
  stats.decodedSliceBacklogResumes >= 1,
  "decoded-slice backpressure did not complete a pause/resume cycle");
assert.ok(stats.decodedPacketDrainSignals >= 256,
  "actual PacketProcessor drain signals did not retire decoded-slice debt");
assert.equal(stats.decodedPacketQueuePauses, 1,
  "exact decoded-packet queue did not pause once at 256");
assert.equal(stats.decodedPacketQueueResumes, 1,
  "exact decoded-packet queue did not resume once at 64");
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
  inboundSlices: stats.inboundSlices,
  maxInboundSliceQueue: stats.maxInboundSliceQueue,
  maxDecodedSliceBacklog: stats.maxDecodedSliceBacklog,
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
