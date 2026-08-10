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

  start(when) {
    this.startedAt = when;
  }

  stop() {
    this.stopped = true;
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
const cleanup = compileFunction(
  "public static native void cleanup();",
  []
);

listener3f(0x1004, 12, 3, -7);
listenerOrientation(0, 0, -4, 0, 2, 0);
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

const streaming = state.freshSource();
state.sources.set(3, streaming);
streaming.queue.push(7);
state.context.currentTime = 30;
state.scheduleBuffer(streaming, 7, 0, false);
const streamedNode = streaming.scheduled[0].node;
state.context.currentTime = 32;
assert.equal(unqueueBuffer(3), 7, "processed streaming buffer was not unqueued");
assert.equal(streamedNode.stopped, true, "unqueued BufferSourceNode was not stopped");
assert.equal(streamedNode.disconnected, 1, "unqueued BufferSourceNode was not disconnected once");
assert.equal(streaming.scheduled.length, 0, "unqueued entry retained its scheduled node");
assert.ok(streaming.panner && streaming.gainNode, "unqueue destroyed the reusable spatial graph");

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
assert.equal(state.sources.size, 3, "transient OpenAL sources leaked into the registry");

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
    sources: state.sources.size,
    buffers: state.buffers.size
  }
}, null, 2));
