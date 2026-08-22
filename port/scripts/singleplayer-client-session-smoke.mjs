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
  "worker.__gaiusLaunchGeneration = launchGeneration",
  "globalThis.__gaiusSingleplayerLaunchGeneration",
  "worker.__gaiusRuntimeReady = false",
  "worker.__gaiusServerReady = false",
  "workers.get(sessionId) === worker",
  "ports.get(sessionId) === worker.__gaiusClientPort",
  "message.type === 'runtime-ready'",
  "message.type === 'server-listener-ready'",
  "connectWhenWorkerReady(minecraft, sessionId, launchGeneration, 0)",
  "Platform.startThread(",
  "profileId: globalThis.__gaiusProfileId || null",
  "worldVersion: globalThis.__gaiusWorldVersion || null",
  "storageSchema: globalThis.__gaiusStorageSchema || null",
  "storageDatabaseName: globalThis.__gaiusStorageDatabaseName || null",
  "storagePrefix: globalThis.__gaiusStoragePrefix || null",
  "storageOpfsDirectory: globalThis.__gaiusStorageOpfsDirectory || null",
  "globalThis.__gaiusStorageDatabaseName || ''",
  "Number(globalThis.__gaiusStorageSchema)",
  "storageDatabaseName === 'gaius-fs-v2-1.21.11'",
  "storageDatabaseName === 'gaius-fs-v2-26.2'",
  "storageMatchesProfile",
  "gaius-fs-v2-1.21.11",
  "gaius-fs-v2-26.2",
  "gaius.fs.v2:1.21.11:",
  "gaius.fs.v2:26.2:",
  "regions-v2-1.21.11",
  "regions-v2-26.2",
  "storageSchema === 2",
  "Promise.reject(new Error(",
  "indexedDB.open(storageDatabaseName, storageSchema)",
  "localWorkerState(sessionId, launchGeneration)",
  "reportAttachFailure(sessionId, launchGeneration, detail)",
  "rollbackLaunch(detail)",
  "openKeyCursor(range)",
  "message.type === 'storage-flushing'",
  "worker.__gaiusStopTimeout",
  "measurementId: worker.__gaiusTelemetryMeasurementId",
  "message.measurementId",
  "worker.__gaiusTelemetryWorkerResetAt",
  "workerResetAt",
  "worker.__gaiusTelemetryMeasurementMismatches",
  "beginClientHandoff(sessionId, launchGeneration)",
  "globalThis.__gaiusSingleplayerHandoffGeneration",
  "singleplayer:handoff-disconnect-ignored",
]) {
  assert.ok(source.includes(contract), `missing session contract: ${contract}`);
}
const receivePongStart = source.indexOf("const receiveWorkerTelemetryPong = function(message)");
const receivePongEnd = source.indexOf("worker.__gaiusStopTelemetry = stopWorkerTelemetry", receivePongStart);
assert.ok(receivePongStart >= 0 && receivePongEnd > receivePongStart,
  "singleplayer telemetry pong handler could not be located");
const receivePong = source.slice(receivePongStart, receivePongEnd);
assert.ok(receivePong.indexOf("message.measurementId") <
  receivePong.indexOf("worker.__gaiusTelemetryPending.delete(sequence)"),
  "measurement-mismatched pong was accepted before validation");
assert.equal(
  source.includes("indexedDB.open('gaius-fs-v1'") ||
    source.includes('indexedDB.open("gaius-fs-v1"'),
  false,
  "singleplayer storage refresh still opens the legacy IndexedDB name",
);
const readyPollEnd = source.indexOf("READY_POLL_MILLIS);");
const readyPollStart = source.lastIndexOf("Platform.schedule(", readyPollEnd);
assert.ok(readyPollStart >= 0 && readyPollEnd > readyPollStart,
  "singleplayer readiness poll could not be located");
const readyPoll = source
  .slice(readyPollStart, readyPollEnd + "READY_POLL_MILLIS);".length)
  .replace(/\s+/g, "");
assert.equal(
  readyPoll,
  "Platform.schedule(()->Platform.startThread(()->connectWhenWorkerReady(minecraft,sessionId,launchGeneration,pollCount+1)),READY_POLL_MILLIS);",
  "singleplayer readiness poll must enter through TeaVM Platform.startThread",
);
assert.equal(source.includes(".getAll()"), false,
  "singleplayer shutdown still performs an IndexedDB full-store read");
const attachFailureStart = source.indexOf("if (state < 0 || pollCount >= READY_POLL_LIMIT)");
const attachFailureEnd = source.indexOf("return;", attachFailureStart);
assert.ok(attachFailureStart >= 0 && attachFailureEnd > attachFailureStart,
  "singleplayer attach-failure branch could not be located");
const attachFailure = source.slice(attachFailureStart, attachFailureEnd);
assert.ok(attachFailure.indexOf("reportAttachFailure(sessionId, launchGeneration, detail)") >= 0,
  "attach failure did not retain its error event");
assert.ok(attachFailure.indexOf("cancelClientHandoff(sessionId, launchGeneration)") <
  attachFailure.indexOf("requestWorkerStop(sessionId, launchGeneration)"),
  "attach failure did not clear handoff before targeted Worker stop");
assert.ok(attachFailure.indexOf("requestWorkerStop(sessionId, launchGeneration)") <
  attachFailure.indexOf("minecraft.gaius$setScreen(new TitleScreen())"),
  "attach failure did not stop its Worker before returning to the title screen");

const ports = new Map();
const workers = new Map();

function createSession(sessionId, launchGeneration = "1") {
  const port = {sessionId, closed: false};
  const worker = {
    __gaiusLaunchGeneration: launchGeneration,
    __gaiusClientPort: port,
    __gaiusClientAttached: false,
    __gaiusRuntimeReady: false,
    __gaiusServerReady: false,
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
  return worker.__gaiusServerReady && ports.get(sessionId) ? 1 : 0;
}

const collisionId = "00".repeat(16);
const oldWorker = createSession(collisionId, "1");
const newWorker = createSession(collisionId, "2");
cleanup(collisionId, oldWorker);
assert.equal(workers.get(collisionId), newWorker, "late old cleanup removed the new worker");
assert.equal(ports.get(collisionId), newWorker.__gaiusClientPort,
  "late old cleanup removed the new client port");

ports.delete(collisionId);
assert.equal(workerState(collisionId), 0, "pending worker should remain pending");
assert.equal(ports.get(collisionId), newWorker.__gaiusClientPort,
  "readiness poll did not restore its owned pending port");

newWorker.__gaiusRuntimeReady = true;
assert.equal(workerState(collisionId), 0,
  "runtime-ready worker connected before its local listener was registered");
newWorker.__gaiusServerReady = true;
assert.equal(workerState(collisionId), 1, "listener-ready worker was not attachable");

ports.delete(collisionId);
newWorker.__gaiusClientAttached = true;
assert.equal(workerState(collisionId), 1, "attached worker should remain active");
assert.equal(ports.has(collisionId), false,
  "attached session incorrectly re-registered a consumed MessagePort");

function extractBody(marker) {
  const end = source.indexOf(marker);
  const annotation = source.lastIndexOf("@JSBody(", end);
  const start = source.indexOf('"""', annotation) + 3;
  const bodyEnd = source.lastIndexOf('""")', end);
  assert.ok(end > 0 && annotation > 0 && bodyEnd > start,
    `could not extract ${marker}`);
  return source.slice(start, bodyEnd).replace(/^\r?\n/, "");
}

// A poll/cleanup from generation 1 must not report, clear, or stop generation
// 2 after a same-key replacement has won the map slot.
{
  const stateScript = extractBody(
    "private static native int localWorkerState(String sessionId, String launchGeneration);",
  );
  const reportScript = extractBody(
    "private static native void reportAttachFailure(\n            String sessionId,\n            String launchGeneration,\n            String detail);",
  );
  const cancelScript = extractBody(
    "private static native void cancelClientHandoff(String sessionId, String launchGeneration);",
  );
  const staleMessages = [];
  const oldWorker = {
    __gaiusLaunchGeneration: "1",
    __gaiusClientPort: {id: "old"},
    __gaiusClientAttached: false,
    __gaiusServerReady: true,
    __gaiusTerminal: false,
    postMessage: (message) => staleMessages.push(["old", message]),
  };
  const newWorker = {
    __gaiusLaunchGeneration: "2",
    __gaiusClientPort: {id: "new"},
    __gaiusClientAttached: false,
    __gaiusServerReady: true,
    __gaiusTerminal: false,
    postMessage: (message) => staleMessages.push(["new", message]),
  };
  const raceContext = {
    Date,
    Map,
    __gaiusMinecraftEvents: [],
    __gaiusSingleplayerHandoff: "same-key",
    __gaiusSingleplayerHandoffGeneration: "2",
    __gaiusSingleplayerWorkers: new Map([["same-key", newWorker]]),
    __gaiusLocalServerPorts: new Map([["same-key", newWorker.__gaiusClientPort]]),
  };
  raceContext.globalThis = raceContext;
  vm.runInNewContext(
    `globalThis.workerState = function(sessionId, launchGeneration) {${stateScript}\n};` +
      `globalThis.reportFailure = function(sessionId, launchGeneration, detail) {${reportScript}\n};` +
      `globalThis.cancelHandoff = function(sessionId, launchGeneration) {${cancelScript}\n};`,
    raceContext,
  );
  assert.equal(raceContext.workerState("same-key", "1"), -2,
    "old readiness poll did not reject a replacement Worker generation");
  raceContext.reportFailure("same-key", "1", "old timeout");
  assert.equal(raceContext.__gaiusMinecraftEvents.length, 0,
    "old readiness poll reported failure for a replacement Worker");
  raceContext.cancelHandoff("same-key", "1");
  assert.equal(raceContext.__gaiusSingleplayerHandoff, "same-key",
    "old readiness poll cleared the replacement handoff session");
  assert.equal(raceContext.__gaiusSingleplayerHandoffGeneration, "2",
    "old readiness poll cleared the replacement handoff generation");

  const stopMarker = "private static native void requestWorkerStop(String sessionId, String launchGeneration);";
  const stopScript = extractBody(stopMarker);
  vm.runInNewContext(
    `globalThis.stopWorker = function(sessionId, launchGeneration) {${stopScript}\n};`,
    raceContext,
  );
  raceContext.stopWorker("same-key", "1");
  assert.deepEqual(staleMessages, [],
    "old targeted stop touched a replacement Worker under the same key");
  assert.equal(raceContext.__gaiusSingleplayerWorkers.get("same-key"), newWorker,
    "old targeted stop removed the replacement Worker");
}

const launchMarker = "private static native String launchWorker(";
const launchEnd = source.indexOf(launchMarker);
const launchAnnotation = source.lastIndexOf("@JSBody(", launchEnd);
const launchStart = source.indexOf('"""', launchAnnotation) + 3;
const launchScriptEnd = source.lastIndexOf('""")', launchEnd);
assert.ok(launchEnd > 0 && launchAnnotation > 0 && launchScriptEnd > launchStart,
  "launchWorker JSBody could not be extracted");
const launchScript = source.slice(launchStart, launchScriptEnd);

function createLaunchRuntime(failureMode, options = {}) {
  const channels = [];
  const createdWorkers = [];
  const capturedTimers = options.captureTimers ? [] : null;
  const runtimeSetTimeout = capturedTimers
    ? (callback, delay) => {
        const timer = {callback, delay, cleared: false};
        capturedTimers.push(timer);
        return timer;
      }
    : setTimeout;
  const runtimeClearTimeout = capturedTimers
    ? (timer) => {
        if (timer) timer.cleared = true;
      }
    : clearTimeout;
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
      this.messageListeners = [];
      createdWorkers.push(this);
    }
    postMessage() {
      if (failureMode === "postMessage") throw new Error("postMessage failed");
    }
    terminate() {
      this.terminated = true;
    }
    addEventListener(type, listener) {
      if (type === "message" && typeof listener === "function") {
        this.messageListeners.push(listener);
      }
    }
    removeEventListener(type, listener) {
      if (type !== "message") return;
      this.messageListeners = this.messageListeners.filter((entry) => entry !== listener);
    }
    dispatchMessage(data) {
      for (const listener of this.messageListeners.slice()) listener({data});
    }
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
    clearTimeout: runtimeClearTimeout,
    console,
    crypto: {getRandomValues: (bytes) => bytes.fill(7)},
    location: new URL("https://client.example/Gaius.html"),
    navigator: {hardwareConcurrency: 8},
    setInterval,
    setTimeout: runtimeSetTimeout,
    __gaiusProfileId: "26.2",
    __gaiusWorldVersion: 4903,
    __gaiusStorageSchema: 2,
    __gaiusStorageDatabaseName: "gaius-fs-v2-26.2",
    __gaiusStoragePrefix: "gaius.fs.v2:26.2:",
    __gaiusStorageOpfsDirectory: "regions-v2-26.2",
  };
  context.globalThis = context;
  vm.runInNewContext(
    `globalThis.launchWorker = function(worldId, newWorld, renderDistance, ` +
      `simulationDistance) {${launchScript}\n};`,
    context,
  );
  return {context, channels, createdWorkers, capturedTimers};
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
  const runtime = createLaunchRuntime(null);
  const sessionId = runtime.context.launchWorker("world", true, 6, 4);
  assert.notEqual(sessionId, null);
  await new Promise((resolve) => setTimeout(resolve, 5));
  const worker = runtime.createdWorkers[0];
  assert.equal(worker.__gaiusServerReady, false,
    "new Worker started with its server listener marked ready");
  worker.onmessage({data: {type: "server-listener-ready", detail: sessionId}});
  assert.equal(worker.__gaiusServerReady, true,
    "listener-ready event did not release the client connection gate");
  clearTimeout(worker.__gaiusHandoffTimeout);
  worker.terminate();
}

{
  const runtime = createLaunchRuntime(null, {captureTimers: true});
  const oldSessionId = runtime.context.launchWorker("world", true, 6, 4);
  await new Promise((resolve) => setTimeout(resolve, 5));
  const oldWorker = runtime.createdWorkers[0];
  const oldGeneration = oldWorker.__gaiusLaunchGeneration;
  runtime.context.__gaiusSingleplayerHandoff = oldSessionId;
  runtime.context.__gaiusSingleplayerHandoffGeneration = oldGeneration;

  const newSessionId = runtime.context.launchWorker("world", true, 6, 4);
  await new Promise((resolve) => setTimeout(resolve, 5));
  const newWorker = runtime.createdWorkers[1];
  const newGeneration = newWorker.__gaiusLaunchGeneration;
  assert.equal(newSessionId, oldSessionId,
    "generation race fixture did not reuse its session key");
  assert.notEqual(newGeneration, oldGeneration,
    "same-key replacement did not receive a fresh launch generation");
  runtime.context.__gaiusSingleplayerHandoff = newSessionId;
  runtime.context.__gaiusSingleplayerHandoffGeneration = newGeneration;

  const lateTimeout = oldWorker.__gaiusHandoffTimeout;
  assert.ok(lateTimeout && typeof lateTimeout.callback === "function",
    "old Worker handoff timeout was not captured");
  lateTimeout.callback();
  assert.equal(runtime.context.__gaiusSingleplayerWorkers.get(oldSessionId), newWorker,
    "late old handoff timeout removed the replacement Worker");
  assert.equal(runtime.context.__gaiusLocalServerPorts.get(oldSessionId),
    newWorker.__gaiusClientPort,
    "late old handoff timeout removed the replacement client port");
  assert.equal(runtime.context.__gaiusSingleplayerHandoff, newSessionId,
    "late old handoff timeout cleared the replacement handoff session");
  assert.equal(runtime.context.__gaiusSingleplayerHandoffGeneration, newGeneration,
    "late old handoff timeout cleared the replacement handoff generation");
  assert.equal(oldWorker.__gaiusClientPort.closed, true,
    "late old handoff timeout leaked the old client port");
  assert.equal(newWorker.terminated, false,
    "late old handoff timeout terminated the replacement Worker");

  // A stopped/error callback from the old closure is another cleanup path;
  // it must retain the same-key replacement and its lease as well.
  oldWorker.onmessage({data: {type: "stopped"}});
  assert.equal(runtime.context.__gaiusSingleplayerWorkers.get(oldSessionId), newWorker,
    "old stopped cleanup removed the replacement Worker");
  assert.equal(runtime.context.__gaiusSingleplayerHandoffGeneration, newGeneration,
    "old stopped cleanup cleared the replacement handoff generation");
  assert.equal(oldWorker.__gaiusStorageRefresh, undefined,
    "old stopped cleanup refreshed persistent files for the replacement Worker");
  assert.equal(newWorker.terminated, false,
    "old stopped cleanup terminated the replacement Worker");
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
    __gaiusLaunchGeneration: "1",
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
    __gaiusSingleplayerHandoffGeneration: "1",
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
  context.__gaiusSingleplayerHandoffGeneration = "";
  context.requestWorkerStop();
  assert.equal(messages.length, 1,
    "explicit disconnect did not send exactly one pending Worker stop");
  assert.equal(messages[0].type, "stop",
    "explicit disconnect did not stop a pending Worker");
  assert.equal(pendingWorker.__gaiusStopRequested, true);
  assert.equal(armed[0].delay, 35000,
    "pending Worker stop did not install the bounded stop deadline");
}

{
  const stopMarker = "private static native void requestWorkerStop(String sessionId, String launchGeneration);";
  const stopEnd = source.indexOf(stopMarker);
  const stopAnnotation = source.lastIndexOf("@JSBody(", stopEnd);
  const stopStart = source.indexOf('"""', stopAnnotation) + 3;
  const stopScriptEnd = source.lastIndexOf('""")', stopEnd);
  assert.ok(stopEnd > 0 && stopAnnotation > 0 && stopScriptEnd > stopStart,
    "targeted Worker stop JSBody could not be extracted");
  const stopScript = source.slice(stopStart, stopScriptEnd);
  const failedMessages = [];
  const failedArmed = [];
  const otherMessages = [];
  const otherArmed = [];
  const failedPort = {closed: false, close() { this.closed = true; }};
  const otherPort = {closed: false, close() { this.closed = true; }};
  const failedWorker = {
    __gaiusLaunchGeneration: "1",
    __gaiusHandoffPending: true,
    __gaiusClientAttached: false,
    __gaiusClientPort: failedPort,
    __gaiusStopTimeout: 0,
    postMessage: (message) => failedMessages.push(message),
    __gaiusArmStopTimeout: (delay, detail) => failedArmed.push({delay, detail}),
  };
  const otherWorker = {
    __gaiusLaunchGeneration: "2",
    __gaiusHandoffPending: true,
    __gaiusClientAttached: false,
    __gaiusClientPort: otherPort,
    __gaiusStopTimeout: 0,
    postMessage: (message) => otherMessages.push(message),
    __gaiusArmStopTimeout: (delay, detail) => otherArmed.push({delay, detail}),
  };
  const targetedContext = {
    Date,
    Map,
    clearTimeout,
    setTimeout,
    __gaiusSingleplayerHandoff: "failed",
    __gaiusSingleplayerHandoffGeneration: "1",
    __gaiusSingleplayerWorkers: new Map([
      ["failed", failedWorker],
      ["other", otherWorker],
    ]),
    __gaiusLocalServerPorts: new Map([
      ["failed", failedPort],
      ["other", otherPort],
    ]),
  };
  targetedContext.globalThis = targetedContext;
  vm.runInNewContext(
    `globalThis.requestWorkerStop = function(sessionId, launchGeneration) {${stopScript}\n};`,
    targetedContext,
  );
  targetedContext.requestWorkerStop("failed", "1");
  assert.equal(targetedContext.__gaiusSingleplayerHandoff, "",
    "targeted attach failure did not clear its handoff lease");
  assert.equal(failedMessages.length, 1,
    "targeted attach failure did not post exactly one Worker stop");
  assert.equal(failedMessages[0].type, "stop",
    "targeted attach failure posted the wrong Worker message");
  assert.equal(failedWorker.__gaiusStopRequested, true,
    "targeted attach failure did not mark its Worker stop request");
  assert.equal(failedWorker.__gaiusHandoffPending, false,
    "targeted attach failure left its handoff pending");
  assert.equal(failedArmed.length, 1,
    "targeted attach failure did not arm its stop timeout");
  assert.equal(failedArmed[0].delay, 35000,
    "targeted attach failure armed the wrong stop timeout");
  assert.equal(otherMessages.length, 0,
    "targeted attach failure stopped another session");
  assert.equal(otherWorker.__gaiusStopRequested, undefined,
    "targeted attach failure marked another session for stop");
  assert.equal(otherArmed.length, 0,
    "targeted attach failure armed another session's stop timeout");

  // A state<0 poll can have already lost its Worker entry.  The targeted
  // helper still clears only that handoff and does not touch a new session.
  targetedContext.__gaiusSingleplayerHandoff = "gone";
  targetedContext.__gaiusSingleplayerHandoffGeneration = "1";
  targetedContext.requestWorkerStop("gone", "1");
  assert.equal(targetedContext.__gaiusSingleplayerHandoff, "gone",
    "stale targeted stop cleared a handoff without its Worker generation");
  assert.equal(otherMessages.length, 0,
    "state<0 attach failure stopped another session");
}

console.log("Singleplayer client session smoke passed");
