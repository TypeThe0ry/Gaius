import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {MessageChannel, Worker} from "node:worker_threads";

const bootstrapPath = new URL("../web/singleplayer/server-worker-bootstrap.js", import.meta.url);
const bootstrap = await readFile(bootstrapPath, "utf8");
const sessionId = "5123456789abcdef0123456789abcdef";

const harness = `
const {parentPort, MessagePort} = require("node:worker_threads");
const {performance} = require("node:perf_hooks");
globalThis.MessagePort = MessagePort;
globalThis.performance = performance;
globalThis.location = new URL("file:///Downloads/singleplayer-server-worker.js");
globalThis.postMessage = (message, transfer) => parentPort.postMessage(message, transfer || []);
globalThis.close = () => parentPort.postMessage({type: "harness-close"});
globalThis.importScripts = () => {};
globalThis.main = () => {};
globalThis.__gaiusStartIntegratedServerPump = () => {};
globalThis.__gaiusChunkPriorityStats = {
  queued: 7,
  longestTaskMillis: 12.5,
  active: true,
  phase: "terrain",
  invalidNumber: Infinity,
  nested: {mustNotCross: true},
  typed: new Uint8Array([1, 2, 3]),
};
globalThis.__gaiusNetworkStats = {
  queuedPackets: 3,
  maxPacketLatencyMillis: 8,
  connected: true,
  optional: null,
  nested: {mustNotCross: true},
};
globalThis.__gaiusWorldgenStats = {
  heartbeatCount: 19,
  longestHeartbeatDelayMillis: 14,
  sliceMillis: 16,
  healthy: true,
  nested: {mustNotCross: true},
};
globalThis.indexedDB = {
  open() {
    const request = {};
    queueMicrotask(() => {
      const database = {
        objectStoreNames: {contains: () => true},
        close() {},
        transaction() {
          const transaction = {};
          const store = {
            delete() {},
            put() {},
            openCursor() {
              const cursorRequest = {};
              queueMicrotask(() => {
                cursorRequest.result = null;
                cursorRequest.onsuccess?.();
              });
              return cursorRequest;
            },
          };
          transaction.objectStore = () => store;
          setTimeout(() => transaction.oncomplete?.(), 0);
          return transaction;
        },
      };
      request.result = database;
      request.onsuccess?.();
    });
    return request;
  },
};
${bootstrap}
parentPort.on("message", (message) => {
  globalThis.onmessage({data: message, ports: message && message.port ? [message.port] : []});
});
`;

const worker = new Worker(harness, {eval: true});
const events = [];
let waiter;
worker.on("message", (message) => {
  events.push(message);
  waiter?.();
});

async function waitFor(type, timeoutMillis = 1000) {
  const deadline = Date.now() + timeoutMillis;
  while (true) {
    const event = events.find((message) => message && message.type === type);
    if (event) return event;
    if (Date.now() >= deadline) throw new Error(`Timed out waiting for ${type}`);
    await new Promise((resolve) => {
      const timeout = setTimeout(resolve, 5);
      waiter = () => {
        clearTimeout(timeout);
        waiter = undefined;
        resolve();
      };
    });
  }
}

worker.postMessage({
  type: "start",
  sessionId,
  worldId: "portable-lifecycle-world",
  newWorld: true,
  renderDistance: 6,
  simulationDistance: 4,
  serverScriptUrl: "file:///Downloads/singleplayer-server.js",
});
await waitFor("port-waiting");

const activeChannel = new MessageChannel();
const activeMessages = [];
activeChannel.port2.on("message", (message) => activeMessages.push(message));
activeChannel.port2.start();
worker.postMessage({type: "attach-port", sessionId, port: activeChannel.port1},
  [activeChannel.port1]);
await waitFor("port-attached");
await waitFor("runtime-ready");

worker.postMessage({type: "telemetry-ping", sessionId, sequence: 17});
const telemetryPong = await waitFor("telemetry-pong");
assert.equal(telemetryPong.sequence, 17, "heartbeat sequence was not preserved");
assert.deepEqual({...telemetryPong.chunkPriority}, {
  queued: 7,
  longestTaskMillis: 12.5,
  active: true,
  phase: "terrain",
}, "Worker chunk priority snapshot crossed non-scalar values");
assert.deepEqual({...telemetryPong.network}, {
  queuedPackets: 3,
  maxPacketLatencyMillis: 8,
  connected: true,
  optional: null,
}, "Worker network snapshot crossed non-scalar values");
assert.deepEqual({...telemetryPong.worldgen}, {
  heartbeatCount: 19,
  longestHeartbeatDelayMillis: 14,
  sliceMillis: 16,
  healthy: true,
}, "Worker worldgen heartbeat snapshot regressed");
assert.equal(telemetryPong.storage.backend, "indexeddb-worker-lru");
assert.equal(telemetryPong.storage.cacheBytes, 0);
assert.equal(telemetryPong.storage.cacheEntries, 0);
assert.equal(telemetryPong.storage.evictions, 0);

const duplicateChannel = new MessageChannel();
const duplicateMessages = [];
duplicateChannel.port2.on("message", (message) => duplicateMessages.push(message));
duplicateChannel.port2.start();
worker.postMessage({
  type: "start",
  sessionId,
  worldId: "must-not-restart",
  port: duplicateChannel.port1,
}, [duplicateChannel.port1]);
await waitFor("start-duplicate-ignored");
await new Promise((resolve) => setTimeout(resolve, 10));
assert.deepEqual(duplicateMessages, [{type: "close"}],
  "duplicate start leaked its transferred MessagePort");

worker.postMessage({type: "stop"});
await waitFor("stopped");
await waitFor("harness-close");
await new Promise((resolve) => setTimeout(resolve, 10));
assert.ok(
  events.findIndex((message) => message?.type === "storage-flushing") <
    events.findIndex((message) => message?.type === "stopped"),
  "Worker acknowledged stop before its storage flush phase",
);
assert.deepEqual(activeMessages, [{type: "close"}],
  "Worker stop did not close the active local session port");

await worker.terminate();
activeChannel.port2.close();
duplicateChannel.port2.close();
console.log(JSON.stringify({
  ok: true,
  phases: events.map((message) => message && message.type).filter(Boolean),
  latePort: true,
  duplicateStart: true,
  cleanStop: true,
  portableWorkerUrl: true,
  scalarTelemetryPong: true,
  storageTelemetryPong: true,
  worldgenHeartbeatPreserved: true,
}));
