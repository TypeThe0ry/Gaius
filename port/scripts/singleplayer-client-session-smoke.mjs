#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const sourceUrl = new URL(
  "../src/main/java/dev/gaius/browser/BrowserSingleplayerClient.java",
  import.meta.url,
);
const source = await readFile(sourceUrl, "utf8");

for (const contract of [
  "worker.__gaiusClientPort = channel.port1",
  "worker.__gaiusRuntimeReady = false",
  "workers.get(sessionId) === worker",
  "ports.get(sessionId) === worker.__gaiusClientPort",
  "message.type === 'runtime-ready'",
  "connectWhenWorkerReady(minecraft, sessionId, 0)",
  "localWorkerState(sessionId)",
  "rollbackLaunch(detail)",
  "openKeyCursor(range)",
  "message.type === 'storage-flushing'",
  "worker.__gaiusStopTimeout",
  "beginClientHandoff(sessionId)",
  "singleplayer:handoff-disconnect-ignored",
]) {
  assert.ok(source.includes(contract), `missing session contract: ${contract}`);
}
assert.equal(source.includes(".getAll()"), false,
  "singleplayer shutdown still performs an IndexedDB full-store read");

const ports = new Map();
const workers = new Map();

function createSession(sessionId) {
  const port = {sessionId, closed: false};
  const worker = {
    __gaiusClientPort: port,
    __gaiusClientAttached: false,
    __gaiusRuntimeReady: false,
    __gaiusTerminal: false,
  };
  ports.set(sessionId, port);
  workers.set(sessionId, worker);
  return worker;
}

function cleanup(sessionId, worker) {
  worker.__gaiusTerminal = true;
  if (ports.get(sessionId) === worker.__gaiusClientPort) {
    worker.__gaiusClientPort.closed = true;
    ports.delete(sessionId);
  }
  if (workers.get(sessionId) === worker) workers.delete(sessionId);
}

function workerState(sessionId) {
  const worker = workers.get(sessionId);
  if (!worker || worker.__gaiusTerminal) return -1;
  if (worker.__gaiusClientAttached) return 1;
  if (ports.get(sessionId) !== worker.__gaiusClientPort) {
    ports.set(sessionId, worker.__gaiusClientPort);
  }
  return worker.__gaiusRuntimeReady && ports.get(sessionId) ? 1 : 0;
}

const collisionId = "00".repeat(16);
const oldWorker = createSession(collisionId);
const newWorker = createSession(collisionId);
cleanup(collisionId, oldWorker);
assert.equal(workers.get(collisionId), newWorker, "late old cleanup removed the new worker");
assert.equal(ports.get(collisionId), newWorker.__gaiusClientPort,
  "late old cleanup removed the new client port");

ports.delete(collisionId);
assert.equal(workerState(collisionId), 0, "pending worker should remain pending");
assert.equal(ports.get(collisionId), newWorker.__gaiusClientPort,
  "readiness poll did not restore its owned pending port");

newWorker.__gaiusRuntimeReady = true;
assert.equal(workerState(collisionId), 1, "runtime-ready worker was not attachable");

ports.delete(collisionId);
newWorker.__gaiusClientAttached = true;
assert.equal(workerState(collisionId), 1, "attached worker should remain active");
assert.equal(ports.has(collisionId), false,
  "attached session incorrectly re-registered a consumed MessagePort");

const launchMarker = "private static native String launchWorker(";
const launchEnd = source.indexOf(launchMarker);
const launchAnnotation = source.lastIndexOf("@JSBody(", launchEnd);
const launchStart = source.indexOf('"""', launchAnnotation) + 3;
const launchScriptEnd = source.lastIndexOf('""")', launchEnd);
assert.ok(launchEnd > 0 && launchAnnotation > 0 && launchScriptEnd > launchStart,
  "launchWorker JSBody could not be extracted");
const launchScript = source.slice(launchStart, launchScriptEnd);

function createLaunchRuntime(failureMode) {
  const channels = [];
  const createdWorkers = [];
  class TestPort {
    constructor() {
      this.closed = false;
    }
    close() {
      this.closed = true;
    }
  }
  class TestMessageChannel {
    constructor() {
      this.port1 = new TestPort();
      this.port2 = new TestPort();
      channels.push(this);
    }
  }
  class TestWorker {
    constructor() {
      if (failureMode === "constructor") throw new Error("constructor failed");
      this.terminated = false;
      createdWorkers.push(this);
    }
    postMessage() {
      if (failureMode === "postMessage") throw new Error("postMessage failed");
    }
    terminate() {
      this.terminated = true;
    }
    addEventListener() {}
    removeEventListener() {}
  }
  const context = {
    Array,
    Date,
    Map,
    Math,
    MessageChannel: TestMessageChannel,
    Number,
    Object,
    Promise,
    String,
    Uint8Array,
    URL,
    Worker: TestWorker,
    clearInterval,
    clearTimeout,
    console,
    crypto: {getRandomValues: (bytes) => bytes.fill(7)},
    location: new URL("https://client.example/Gaius.html"),
    navigator: {hardwareConcurrency: 8},
    setInterval,
    setTimeout,
  };
  context.globalThis = context;
  vm.runInNewContext(
    `globalThis.launchWorker = function(worldId, newWorld, renderDistance, ` +
      `simulationDistance) {${launchScript}\n};`,
    context,
  );
  return {context, channels, createdWorkers};
}

{
  const runtime = createLaunchRuntime("constructor");
  assert.equal(runtime.context.launchWorker("world", true, 6, 4), null);
  assert.equal(runtime.channels.length, 1);
  assert.equal(runtime.channels[0].port1.closed, true,
    "Worker constructor failure leaked MessagePort port1");
  assert.equal(runtime.channels[0].port2.closed, true,
    "Worker constructor failure leaked MessagePort port2");
  assert.equal(runtime.context.__gaiusLocalServerPorts.size, 0,
    "Worker constructor failure leaked the local-port map entry");
  assert.equal(runtime.context.__gaiusSingleplayerWorkers.size, 0,
    "Worker constructor failure leaked the worker map entry");
}

{
  const runtime = createLaunchRuntime("postMessage");
  assert.notEqual(runtime.context.launchWorker("world", true, 6, 4), null);
  await new Promise((resolve) => setTimeout(resolve, 5));
  assert.equal(runtime.createdWorkers[0].terminated, true,
    "start postMessage failure did not terminate the Worker");
  assert.equal(runtime.channels[0].port1.closed, true,
    "start postMessage failure leaked MessagePort port1");
  assert.equal(runtime.channels[0].port2.closed, true,
    "start postMessage failure leaked MessagePort port2");
  assert.equal(runtime.context.__gaiusLocalServerPorts.size, 0,
    "start postMessage failure leaked the local-port map entry");
  assert.equal(runtime.context.__gaiusSingleplayerWorkers.size, 0,
    "start postMessage failure leaked the Worker map entry");
}

{
  const stopMarker = "private static native void requestWorkerStop();";
  const stopEnd = source.indexOf(stopMarker);
  const stopAnnotation = source.lastIndexOf("@JSBody(", stopEnd);
  const stopStart = source.indexOf('"""', stopAnnotation) + 3;
  const stopScriptEnd = source.lastIndexOf('""")', stopEnd);
  const messages = [];
  const armed = [];
  const pendingWorker = {
    __gaiusHandoffPending: true,
    __gaiusClientAttached: false,
    postMessage: (message) => messages.push(message),
    __gaiusArmStopTimeout: (delay, detail) => armed.push({delay, detail}),
  };
  const context = {
    Date,
    Map,
    clearTimeout,
    setTimeout,
    __gaiusMinecraftEvents: [],
    __gaiusSingleplayerHandoff: "pending",
    __gaiusSingleplayerWorkers: new Map([["pending", pendingWorker]]),
  };
  context.globalThis = context;
  vm.runInNewContext(
    `globalThis.requestWorkerStop = function() {` +
      `${source.slice(stopStart, stopScriptEnd)}\n};`,
    context,
  );
  context.requestWorkerStop();
  assert.equal(messages.length, 0,
    "the delayed disconnect from world handoff stopped the new Worker");
  assert.equal(context.__gaiusMinecraftEvents.at(-1)?.event,
    "singleplayer:handoff-disconnect-ignored");

  context.__gaiusSingleplayerHandoff = "";
  context.requestWorkerStop();
  assert.equal(messages.length, 1,
    "explicit disconnect did not send exactly one pending Worker stop");
  assert.equal(messages[0].type, "stop",
    "explicit disconnect did not stop a pending Worker");
  assert.equal(pendingWorker.__gaiusStopRequested, true);
  assert.equal(armed[0].delay, 35000,
    "pending Worker stop did not install the bounded stop deadline");
}

console.log("Singleplayer client session smoke passed");
