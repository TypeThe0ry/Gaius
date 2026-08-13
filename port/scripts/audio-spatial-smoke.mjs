#!/usr/bin/env node

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const sourcePath = new URL(
  "../overrides/libraries/lwjgl-openal/src/main/java/org/lwjgl/openal/BrowserOpenAL.java",
  import.meta.url
);
const javaSource = readFileSync(sourcePath, "utf8");

function jsBodyFor(methodSignature) {
  const methodOffset = javaSource.indexOf(methodSignature);
  assert.notEqual(methodOffset, -1, `missing method: ${methodSignature}`);
  const annotationOffset = javaSource.lastIndexOf("@JSBody", methodOffset);
  const scriptStart = javaSource.indexOf('"""', annotationOffset);
  const scriptEnd = javaSource.indexOf('"""', scriptStart + 3);
  assert.notEqual(annotationOffset, -1, `missing @JSBody: ${methodSignature}`);
  assert.notEqual(scriptStart, -1, `missing script start: ${methodSignature}`);
  assert.notEqual(scriptEnd, -1, `missing script end: ${methodSignature}`);
  const lines = javaSource.slice(scriptStart + 3, scriptEnd).split("\n");
  while (lines.length && lines[0].trim() === "") lines.shift();
  while (lines.length && lines.at(-1).trim() === "") lines.pop();
  const indentation = Math.min(
    ...lines.filter((line) => line.trim()).map((line) => line.match(/^\s*/)[0].length)
  );
  return lines.map((line) => line.slice(indentation)).join("\n");
}

class MockParam {
  constructor() {
    this.value = 0;
    this.history = [];
  }

  setValueAtTime(value, time) {
    this.value = value;
    this.history.push({ value, time });
  }
}

class MockNode {
  constructor() {
    this.connections = [];
    this.disconnected = 0;
  }

  connect(destination) {
    this.connections.push(destination);
  }

  disconnect() {
    this.connections = [];
    this.disconnected++;
  }
}

class MockGain extends MockNode {
  constructor() {
    super();
    this.gain = new MockParam();
  }
}

class MockPanner extends MockNode {
  constructor() {
    super();
    this.positionX = new MockParam();
    this.positionY = new MockParam();
    this.positionZ = new MockParam();
    this.orientationX = new MockParam();
    this.orientationY = new MockParam();
    this.orientationZ = new MockParam();
    this.velocityX = new MockParam();
    this.velocityY = new MockParam();
    this.velocityZ = new MockParam();
  }
}

class MockBufferSource extends MockNode {
  constructor() {
    super();
    this.playbackRate = new MockParam();
    this.loop = false;
    this.startedAt = null;
    this.stopped = false;
  }

  start(when, offset = 0) {
    this.startedAt = when;
    this.startedOffset = offset;
  }

  stop() {
    this.stopped = true;
  }

  finish() {
    if (typeof this.onended === "function") this.onended();
  }
}

class MockListener {
  constructor() {
    this.positionX = new MockParam();
    this.positionY = new MockParam();
    this.positionZ = new MockParam();
    this.forwardX = new MockParam();
    this.forwardY = new MockParam();
    this.forwardZ = new MockParam();
    this.upX = new MockParam();
    this.upY = new MockParam();
    this.upZ = new MockParam();
    this.velocityX = new MockParam();
    this.velocityY = new MockParam();
    this.velocityZ = new MockParam();
  }
}

class MockAudioContext {
  constructor() {
    this.currentTime = 10;
    this.state = "running";
    this.destination = new MockNode();
    this.listener = new MockListener();
  }

  createGain() {
    return new MockGain();
  }

  createPanner() {
    return new MockPanner();
  }

  createBufferSource() {
    return new MockBufferSource();
  }

  resume() {
    this.state = "running";
    return Promise.resolve();
  }
}

const window = {
  AudioContext: MockAudioContext,
  addEventListener() {}
};
const context = vm.createContext({
  window,
  console,
  Map,
  Math,
  Number,
  String,
  Promise
});

const init = jsBodyFor("public static native void init();");
vm.runInContext(`(function () {\n${init}\n})()`, context, {
  filename: "BrowserOpenAL.init.js"
});

const state = window.__gaiusOpenAL;
assert.ok(state, "OpenAL state was not initialized");
state.ensureContext();

function compileFunction(methodSignature, parameters) {
  const body = jsBodyFor(methodSignature);
  return vm.runInContext(
    `(function (${parameters.join(",")}) {\n${body}\n})`,
    context,
    { filename: `BrowserOpenAL.${methodSignature}.js` }
  );
}

const source3f = compileFunction(
  "private static native void source3fJs(int source, int parameter, float x, float y, float z);",
  ["source", "parameter", "x", "y", "z"]
);
const sourcef = compileFunction(
  "private static native void sourcefJs(int source, int parameter, float value);",
  ["source", "parameter", "value"]
);
const sourcei = compileFunction(
  "private static native void sourceiJs(int source, int parameter, int value);",
  ["source", "parameter", "value"]
);
const listener3f = compileFunction(
  "private static native void listener3fJs(int parameter, float x, float y, float z);",
  ["parameter", "x", "y", "z"]
);
const listenerOrientation = compileFunction(
  "private static native void listenerOrientationJs(",
  ["forwardX", "forwardY", "forwardZ", "upX", "upY", "upZ"]
);
const distanceModel = compileFunction(
  "private static native void distanceModelJs(int model);",
  ["model"]
);
const deleteSource = compileFunction(
  "private static native void deleteSourceJs(int source);",
  ["source"]
);
const unqueueBuffer = compileFunction(
  "private static native int sourceUnqueueBufferJs(int source);",
  ["source"]
);
const queueBuffer = compileFunction(
  "private static native void sourceQueueBufferJs(int source, int buffer);",
  ["source", "buffer"]
);
const playSource = compileFunction(
  "private static native void sourcePlayJs(int source);",
  ["source"]
);
const pauseSource = compileFunction(
  "private static native void sourcePauseJs(int source);",
  ["source"]
);
const stopSource = compileFunction(
  "private static native void sourceStopJs(int source);",
  ["source"]
);
const cleanup = compileFunction(
  "public static native void cleanup();",
  []
);

listener3f(0x1004, 12, 3, -7);
listenerOrientation(0, 0, -4, 0, 2, 0);
const listenerPositionIdentity = state.listener.position;
const listenerForwardIdentity = state.listener.forward;
const listenerNormalizedForwardIdentity = state.listener.normalizedForward;
assert.deepEqual(
  [
    state.context.listener.positionX.value,
    state.context.listener.positionY.value,
    state.context.listener.positionZ.value
  ],
  [12, 3, -7],
  "listener position was not forwarded"
);
assert.deepEqual(
  [
    state.context.listener.forwardX.value,
    state.context.listener.forwardY.value,
    state.context.listener.forwardZ.value,
    state.context.listener.upX.value,
    state.context.listener.upY.value,
    state.context.listener.upZ.value
  ],
  [0, 0, -1, 0, 1, 0],
  "listener orientation was not normalized"
);

const source = state.freshSource();
state.sources.set(1, source);
state.buffers.set(7, { audio: { duration: 1 } });
source.queue.push(7);
state.scheduleBuffer(source, 7, 0, false);
assert.ok(source.panner, "world source did not get a PannerNode");
const scheduled = source.scheduled.at(-1).node;
const sourcePositionIdentity = source.position;
const sourceDirectionIdentity = source.direction;
const sourceNormalizedDirectionIdentity = source.normalizedDirection;

source3f(1, 0x1004, 8, 4, -2);
source3f(1, 0x1005, 4, 0, 0);
sourcef(1, 0x1020, 2);
sourcef(1, 0x1021, 0.75);
sourcef(1, 0x1023, 32);
assert.deepEqual(
  [source.panner.positionX.value, source.panner.positionY.value, source.panner.positionZ.value],
  [8, 4, -2],
  "source position was not forwarded"
);
assert.deepEqual(
  [source.panner.orientationX.value, source.panner.orientationY.value, source.panner.orientationZ.value],
  [1, 0, 0],
  "source direction was not normalized"
);
assert.equal(source.panner.refDistance, 2);
assert.equal(source.panner.rolloffFactor, 0.75);
assert.equal(source.panner.maxDistance, 32);

for (let index = 0; index < 1_000; index++) {
  source3f(1, 0x1004, index, index + 1, index + 2);
  source3f(1, 0x1005, index + 1, 1, -1);
  listener3f(0x1004, -index, index, 3);
  listenerOrientation(0, 0, -1, 0, 1, 0);
}
assert.strictEqual(source.position, sourcePositionIdentity,
  "source position updates allocated replacement vectors");
assert.strictEqual(source.direction, sourceDirectionIdentity,
  "source direction updates allocated replacement vectors");
assert.strictEqual(source.normalizedDirection, sourceNormalizedDirectionIdentity,
  "source panner normalization allocated replacement vectors");
assert.strictEqual(state.listener.position, listenerPositionIdentity,
  "listener position updates allocated replacement vectors");
assert.strictEqual(state.listener.forward, listenerForwardIdentity,
  "listener orientation updates allocated replacement vectors");
assert.strictEqual(state.listener.normalizedForward, listenerNormalizedForwardIdentity,
  "listener normalization allocated replacement vectors");

sourcei(1, 0xD000, 0xD004);
assert.equal(source.panner.distanceModel, "linear", "source linear model was not mapped");
sourcei(1, 0xD000, 0);
assert.equal(source.panner.rolloffFactor, 0, "AL_NONE did not disable attenuation");

sourcei(1, 0x0202, 1);
assert.equal(source.relative, true);
assert.equal(scheduled.connections[0], source.gainNode, "relative source bypassed PannerNode incorrectly");
sourcei(1, 0x0202, 0);
assert.equal(scheduled.connections[0], source.panner, "positional source did not reconnect to PannerNode");

const inherited = state.freshSource();
state.sources.set(2, inherited);
state.scheduleBuffer(inherited, 7, 0, false);
distanceModel(0xD005);
assert.equal(inherited.panner.distanceModel, "exponential", "global model was not mapped");
sourcei(2, 0xD000, 0xD003);
distanceModel(0xD001);
assert.equal(inherited.panner.distanceModel, "linear", "source model did not override global model");

const oneShot = state.freshSource();
state.sources.set(4, oneShot);
oneShot.buffer = 7;
oneShot.state = 0x1012;
state.scheduleBuffer(oneShot, 7, 0, false);
const oneShotEntry = oneShot.scheduled[0];
const oneShotNode = oneShotEntry.node;
oneShotNode.finish();
assert.equal(oneShot.scheduled.length, 0,
  "naturally ended one-shot audio retained a scheduled entry");
assert.equal(oneShotEntry.node, null, "naturally ended one-shot retained its node");
assert.equal(oneShotNode.stopped, false, "natural completion redundantly stopped the node");
assert.equal(oneShotNode.disconnected, 1,
  "naturally ended one-shot did not disconnect exactly once");
assert.equal(oneShot.state, 0x1014, "naturally ended one-shot did not stop its AL source");

const streaming = state.freshSource();
state.sources.set(3, streaming);
streaming.queue.push(7);
state.context.currentTime = 30;
state.scheduleBuffer(streaming, 7, 0, false);
const streamedNode = streaming.scheduled[0].node;
const streamedEntry = streaming.scheduled[0];
streamedNode.finish();
assert.equal(streaming.scheduled.length, 1,
  "naturally ended queued buffer disappeared before AL unqueue");
assert.equal(streamedEntry.ended, true, "queued buffer was not marked processed on end");
assert.equal(streamedEntry.node, null, "processed queued buffer retained its Web Audio node");
assert.equal(streamedNode.stopped, false, "natural queued completion redundantly stopped the node");
assert.equal(streamedNode.disconnected, 1,
  "processed queued buffer did not disconnect exactly once");
state.context.currentTime = 32;
assert.equal(unqueueBuffer(3), 7, "processed streaming buffer was not unqueued");
assert.equal(streamedNode.disconnected, 1, "unqueued BufferSourceNode was not disconnected once");
assert.equal(streaming.scheduled.length, 0, "unqueued entry retained its scheduled node");
assert.ok(streaming.panner && streaming.gainNode, "unqueue destroyed the reusable spatial graph");

state.buffers.set(8, { audio: { duration: 0.5 } });
const appendStream = state.freshSource();
state.sources.set(5, appendStream);
appendStream.queue.push(7);
state.scheduleBuffer(appendStream, 7, 0, false);
appendStream.state = 0x1012;
state.setSourceActive(appendStream, true);
const firstStreamEnd = appendStream.scheduled[0].endTime;
queueBuffer(5, 8);
assert.equal(appendStream.scheduled.length, 2,
  "buffer queued during playback was not scheduled in Web Audio");
assert.equal(appendStream.scheduled[1].node.startedAt, firstStreamEnd,
  "streaming append did not preserve gapless queue timing");
deleteSource(5);

const activeOne = state.freshSource();
const activeTwo = state.freshSource();
activeOne.buffer = 7;
activeTwo.buffer = 7;
state.sources.set(6, activeOne);
state.sources.set(7, activeTwo);
playSource(6);
playSource(7);
assert.equal(state.stats.activeSources, 2,
  "active source telemetry did not count concurrent sources");
activeOne.scheduled[0].node.finish();
assert.equal(state.stats.activeSources, 1,
  "natural source completion did not decrement active source telemetry");
deleteSource(7);
deleteSource(6);
assert.equal(state.stats.activeSources, 0,
  "source deletion did not return active source telemetry to baseline");

state.buffers.set(9, { audio: { duration: 10 } });
const resumable = state.freshSource();
resumable.buffer = 9;
state.sources.set(8, resumable);
state.context.currentTime = 40;
playSource(8);
state.context.currentTime = 42.5;
pauseSource(8);
assert.equal(resumable.state, 0x1013, "playing source did not enter AL_PAUSED");
assert.ok(Math.abs(resumable.resumeOffset - 2.5) < 0.001,
  "paused source did not retain its playback offset");
state.context.currentTime = 50;
playSource(8);
assert.ok(Math.abs(resumable.scheduled[0].node.startedOffset - 2.5) < 0.001,
  "resumed source restarted from the beginning");
stopSource(8);
assert.equal(resumable.resumeOffset, 0, "stopped source retained a resume offset");
deleteSource(8);

const resumableStream = state.freshSource();
resumableStream.queue.push(9, 9);
state.sources.set(9, resumableStream);
state.context.currentTime = 60;
playSource(9);
state.context.currentTime = 72.5;
pauseSource(9);
assert.equal(resumableStream.resumeQueueIndex, 1,
  "paused stream did not retain its active queue index");
assert.ok(Math.abs(resumableStream.resumeOffset - 2.5) < 0.001,
  "paused stream did not retain its active-buffer offset");
state.context.currentTime = 80;
playSource(9);
assert.equal(resumableStream.scheduled.length, 2,
  "resumed stream lost its processed queue entry");
assert.equal(resumableStream.scheduled[0].ended, true,
  "resumed stream did not preserve its processed prefix");
assert.ok(Math.abs(resumableStream.scheduled[1].node.startedOffset - 2.5) < 0.001,
  "resumed stream restarted its active buffer from the beginning");
assert.equal(unqueueBuffer(9), 9,
  "resumed stream could not unqueue its processed prefix");
assert.equal(resumableStream.scheduled.length, 1,
  "unqueue removed the resumed active stream entry");
stopSource(9);
deleteSource(9);

const lifecycleIterations = 20_000;
for (let index = 0; index < lifecycleIterations; index++) {
  const id = 100 + index;
  const transient = state.freshSource();
  state.sources.set(id, transient);
  state.scheduleBuffer(transient, 7, 0, false);
  deleteSource(id);
  assert.equal(transient.scheduled.length, 0);
  assert.equal(transient.panner, null);
  assert.equal(transient.gainNode, null);
}
assert.equal(state.sources.size, 4, "transient OpenAL sources leaked into the registry");

cleanup();
assert.equal(state.sources.size, 0, "OpenAL source registry did not return to baseline");
assert.equal(state.buffers.size, 0, "OpenAL buffer registry did not return to baseline");
assert.equal(state.masterGainNode, null, "OpenAL master gain retained the audio graph");
assert.equal(
  state.stats.webAudioNodesCreated,
  state.stats.webAudioNodesDisposed,
  "created Web Audio nodes were not all disposed"
);
assert.equal(
  state.stats.webAudioConnections,
  state.stats.webAudioDisconnects,
  "Web Audio connections were not all disconnected"
);

console.log(JSON.stringify({
  passed: true,
  listener: {
    position: [state.context.listener.positionX.value, state.context.listener.positionY.value,
      state.context.listener.positionZ.value],
    forward: [state.context.listener.forwardX.value, state.context.listener.forwardY.value,
      state.context.listener.forwardZ.value]
  },
  positional: {
    distanceModel: "inverse",
    referenceDistance: 2,
    rolloffFactor: 0,
    maxDistance: 32,
    relativeRouteReconnected: true
  },
  lifecycle: {
    iterations: lifecycleIterations,
    nodesCreated: state.stats.webAudioNodesCreated,
    nodesDisposed: state.stats.webAudioNodesDisposed,
    connections: state.stats.webAudioConnections,
    disconnects: state.stats.webAudioDisconnects,
    naturalEnds: state.stats.webAudioNaturalEnds,
    sources: state.sources.size,
    buffers: state.buffers.size
  }
}, null, 2));
