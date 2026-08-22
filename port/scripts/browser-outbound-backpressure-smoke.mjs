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

const bridgeScript = jsBodyBefore("private static native void initBridge();");
const outboundSchedulerScript = jsBodyBefore(
  "private static native void initOutboundScheduler();",
);
assert.match(source, /maximumOutboundFramesPerTurn = 32/);
assert.match(source, /maximumOutboundBytesPerTurn = 256 \* 1024/);
assert.match(source, /maximumOutboundMillisPerTurn = 2/);
assert.match(source, /webSocketBackpressureRetryMs = 4/);
assert.doesNotMatch(
  outboundSchedulerScript,
  /queueMicrotask/,
  "outbound continuation must not use a microtask",
);

function delay(millis) {
  return new Promise((resolve) => setTimeout(resolve, millis));
}

async function waitFor(predicate, label, timeoutMillis = 3000) {
  const deadline = Date.now() + timeoutMillis;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error(`Timed out waiting for ${label}`);
    await delay(1);
  }
}

function createContext(WebSocketImpl, localPorts = new Map()) {
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
      throw new Error("Outbound smoke must not query a relay registry");
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
    setTimeout,
    WebSocket: WebSocketImpl,
    __gaiusLocalServerPorts: localPorts,
  };
  context.globalThis = context;
  context.window = context;
  vm.runInNewContext(`(function() {${bridgeScript}\n})();`, context);
  vm.runInNewContext(`(function() {${outboundSchedulerScript}\n})();`, context);
  return context;
}

function assertBounded(stats, label) {
  assert.ok(stats.outboundTurns > 0, `${label} did not execute an outbound turn`);
  assert.ok(stats.maxOutboundTurnFrames <= stats.outboundFrameLimit,
    `${label} exceeded its frame budget`);
  assert.ok(stats.maxOutboundTurnBytes <= stats.outboundByteLimit,
    `${label} exceeded its byte budget`);
  assert.ok(Number.isFinite(stats.maxOutboundTurnMillis) && stats.maxOutboundTurnMillis < 100,
    `${label} produced an unbounded outbound callback`);
}

async function runLocalMessagePortSmoke() {
  class UnexpectedWebSocket {
    static OPEN = 1;
    constructor() {
      throw new Error("Local outbound smoke must not open a WebSocket");
    }
  }

  const sessionId = "6123456789abcdef0123456789abcdef";
  const socketId = 101;
  const {port1, port2} = new MessageChannel();
  const context = createContext(UnexpectedWebSocket, new Map([[sessionId, port1]]));
  const bridge = context.__gaiusNettyBridge;
  const stats = context.__gaiusNetworkStats;
  const received = [];
  const controls = [];
  port2.on("message", (message) => {
    if (message instanceof ArrayBuffer || ArrayBuffer.isView(message)) {
      const bytes = message instanceof ArrayBuffer
        ? new Uint8Array(message)
        : new Uint8Array(message.buffer, message.byteOffset || 0, message.byteLength || 0);
      received.push(Uint8Array.from(bytes));
    } else {
      controls.push(message);
    }
  });

  bridge.open(socketId, `client-${sessionId}.gaius-local`, 25565);
  const entry = bridge.channels.get(socketId);
  port2.postMessage({type: "flow", paused: true});
  await waitFor(() => entry.remotePaused, "local flow pause");
  await delay(10);

  const frameCount = 96;
  const frameBytes = 4096;
  const largeFrameBytes = 300 * 1024;
  const expected = new Uint8Array(frameCount * frameBytes + largeFrameBytes);
  const turnsBeforeSend = stats.outboundTurns;
  for (let frameIndex = 0; frameIndex < frameCount; frameIndex++) {
    const frame = new Uint8Array(frameBytes);
    for (let index = 0; index < frame.length; index++) {
      frame[index] = (frameIndex * 17 + index * 3) & 0xff;
    }
    expected.set(frame, frameIndex * frameBytes);
    assert.equal(bridge.send(socketId, frame), true);
  }
  const largeFrame = new Uint8Array(largeFrameBytes);
  for (let index = 0; index < largeFrame.length; index++) {
    largeFrame[index] = (index * 11 + 5) & 0xff;
  }
  expected.set(largeFrame, frameCount * frameBytes);
  assert.equal(bridge.send(socketId, largeFrame), true);
  assert.equal(stats.outboundTurns, turnsBeforeSend,
    "local send ran synchronously instead of scheduling a macrotask");
  await delay(20);
  assert.equal(received.length, 0, "remote flow pause did not stop local data sends");
  assert.equal(entry.queuedBytes, expected.byteLength, "paused local queue lost bytes");

  port2.postMessage({type: "flow", paused: false});
  await waitFor(() => entry.queuedBytes === 0, "local outbound queue drain");
  await waitFor(() => received.reduce((sum, part) => sum + part.byteLength, 0) === expected.byteLength,
    "local MessagePort delivery");

  const actual = new Uint8Array(expected.byteLength);
  let offset = 0;
  for (const part of received) {
    actual.set(part, offset);
    offset += part.byteLength;
  }
  assert.deepEqual(actual, expected, "local MessagePort byte ordering changed");
  assert.equal(entry.outbound.length, 0, "local outbound array retained processed frames");
  assert.equal(entry.outboundHead, 0, "local outbound head was not reset");
  assert.equal(stats.queuedBytes, 0, "global local outbound byte count did not drain");
  assert.ok(stats.outboundYields >= 2, "local burst did not yield across macrotasks");
  assert.ok(stats.localMessagePortSends > 0, "local send telemetry was not recorded");
  assertBounded(stats, "local MessagePort");
  assert.equal(stats.maxOutboundTurnBytes, stats.outboundByteLimit,
    "oversized local write was not segmented at the strict turn byte limit");

  bridge.close(socketId);
  await waitFor(() => controls.some((message) => message && message.type === "close"),
    "scheduled local close control");
  assert.equal(entry.outboundControls.length, 0,
    "local control array retained processed controls");
  port2.close();
  return {
    turns: stats.outboundTurns,
    yields: stats.outboundYields,
    maxFrames: stats.maxOutboundTurnFrames,
    maxBytes: stats.maxOutboundTurnBytes,
    maxMillis: stats.maxOutboundTurnMillis,
    physicalSends: stats.localMessagePortSends,
  };
}

async function runWebSocketSmoke() {
  const sockets = [];
  class FakeWebSocket {
    static CONNECTING = 0;
    static OPEN = 1;
    static CLOSED = 3;

    constructor(url) {
      this.url = url;
      this.readyState = FakeWebSocket.CONNECTING;
      this.bufferedAmount = 4 * 1024 * 1024;
      this.data = [];
      sockets.push(this);
      setTimeout(() => {
        this.readyState = FakeWebSocket.OPEN;
        if (this.onopen) this.onopen();
      }, 0);
    }

    send(payload) {
      if (typeof payload === "string") {
        const message = JSON.parse(payload);
        assert.equal(message.type, "connect", "WebSocket control ordering changed");
        setTimeout(() => {
          if (this.onmessage) {
            this.onmessage({data: JSON.stringify({
              type: "connected",
              host: message.host,
              port: message.port,
            })});
          }
        }, 0);
        return;
      }
      this.data.push(Uint8Array.from(payload));
    }

    close() {
      this.readyState = FakeWebSocket.CLOSED;
    }
  }

  const socketId = 202;
  const context = createContext(FakeWebSocket);
  const bridge = context.__gaiusNettyBridge;
  const stats = context.__gaiusNetworkStats;
  bridge.open(socketId, "outbound-smoke.invalid", 25565);
  const entry = bridge.channels.get(socketId);
  const socket = sockets[0];
  await waitFor(() => stats.webSocketBackpressureWaits >= 1,
    "WebSocket handshake backpressure wait");
  assert.ok(stats.webSocketBackpressureWaits >= 1,
    "WebSocket handshake did not self-reschedule behind bufferedAmount");
  socket.bufferedAmount = 0;
  await waitFor(() => entry.connected, "fake WebSocket connection");

  socket.bufferedAmount = 4 * 1024 * 1024;
  const waitsBeforeData = stats.webSocketBackpressureWaits;
  const frameCount = 80;
  const frameBytes = 4096;
  for (let frameIndex = 0; frameIndex < frameCount; frameIndex++) {
    const frame = new Uint8Array(frameBytes);
    frame.fill(frameIndex & 0xff);
    assert.equal(bridge.send(socketId, frame), true);
  }
  await waitFor(() => stats.webSocketBackpressureWaits > waitsBeforeData,
    "WebSocket data backpressure wait");
  assert.ok(stats.webSocketBackpressureWaits > waitsBeforeData,
    "WebSocket data backpressure did not self-reschedule");
  socket.bufferedAmount = 0;
  await waitFor(() => entry.queuedBytes === 0, "WebSocket outbound queue drain");
  assert.equal(socket.data.length, frameCount, "WebSocket queue lost or merged data frames");
  for (let frameIndex = 0; frameIndex < socket.data.length; frameIndex++) {
    const frame = socket.data[frameIndex];
    assert.equal(frame.byteLength, frameBytes);
    assert.equal(frame[0], frameIndex & 0xff, `WebSocket ordering changed at frame ${frameIndex}`);
    assert.equal(frame[frame.length - 1], frameIndex & 0xff,
      `WebSocket frame ${frameIndex} was truncated`);
  }
  assert.equal(entry.outbound.length, 0, "WebSocket outbound array retained processed frames");
  assert.equal(entry.outboundHead, 0, "WebSocket outbound head was not reset");
  assert.equal(stats.queuedBytes, 0, "global WebSocket outbound byte count did not drain");
  assert.ok(stats.outboundYields >= 2, "WebSocket burst did not yield across macrotasks");
  assert.ok(stats.webSocketSends >= frameCount + 1,
    "WebSocket send telemetry omitted control or data frames");
  assertBounded(stats, "WebSocket");
  bridge.close(socketId);
  return {
    turns: stats.outboundTurns,
    yields: stats.outboundYields,
    backpressureWaits: stats.webSocketBackpressureWaits,
    maxFrames: stats.maxOutboundTurnFrames,
    maxBytes: stats.maxOutboundTurnBytes,
    maxMillis: stats.maxOutboundTurnMillis,
    physicalSends: stats.webSocketSends,
  };
}

const local = await runLocalMessagePortSmoke();
const webSocket = await runWebSocketSmoke();
await delay(20);
console.log(JSON.stringify({ok: true, local, webSocket}));
