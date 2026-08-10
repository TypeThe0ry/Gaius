#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const sourcePath = new URL(
  "../overrides/libraries/lwjgl-glfw/src/main/java/org/lwjgl/glfw/BrowserGlfw.java",
  import.meta.url,
);
const source = await readFile(sourcePath, "utf8");

function jsBodyBefore(marker) {
  const markerOffset = source.indexOf(marker);
  const annotationOffset = source.lastIndexOf('@JSBody(params = {"hidden", "resume"}, script = """', markerOffset);
  const scriptOffset = source.indexOf('"""', annotationOffset) + 3;
  const scriptEnd = source.lastIndexOf('""")', markerOffset);
  assert.ok(markerOffset > 0 && annotationOffset > 0 && scriptEnd > scriptOffset,
    `JSBody could not be extracted for ${marker}`);
  return source.slice(scriptOffset, scriptEnd).replaceAll("\\\\", "\\");
}

assert.match(source, /@Async\s+private static native void yieldAfterPresent\(boolean hidden\)/,
  "swapBuffers yield is not a TeaVM async continuation boundary");
assert.match(source, /requestAnimationFrame/, "visible pacing does not use rAF");
assert.match(source, /new MessageChannel\(\)/, "visible pacing does not yield through MessageChannel");
assert.match(source, /setTimeout\(\(\) => finish\('timer'\), 50\)/,
  "hidden pacing is not kept low-frequency");
assert.match(source, /watchdog=setTimeout\(\(\) => finish\('timer'\), 100\)/,
  "a suspended visible rAF can starve its TeaVM continuation");
assert.doesNotMatch(
  source.slice(source.indexOf("public static void swapBuffers"), source.indexOf("public static void swapInterval")),
  /sleepForBrowserMillis|Thread\.sleep/,
  "swapBuffers still uses a clamp-prone fixed timer",
);
const waitEventsTimeout = source.slice(
  source.indexOf("public static void waitEventsTimeout"),
  source.indexOf("public static void postEmptyEvent"),
);
assert.match(waitEventsTimeout, /sleepForBrowserMillis\(Math\.max\(1L, Math\.min\(7L, millis - 1L\)\)\)/,
  "waitEventsTimeout semantics changed");

const frameYieldScript = jsBodyBefore(
  "private static native void scheduleFrameYield(boolean hidden, FrameYieldCallback resume);",
);

class VirtualBrowser {
  constructor({refreshRate = 120, timerClamp = 4, messageDelay = 0.01} = {}) {
    this.now = 0;
    this.refreshMillis = 1000 / refreshRate;
    this.timerClamp = timerClamp;
    this.messageDelay = messageDelay;
    this.nextOrder = 0;
    this.events = [];
    this.sideTasksCompleted = 0;
  }

  schedule(at, callback, kind) {
    const event = {at, callback, kind, order: this.nextOrder++, canceled: false};
    this.events.push(event);
    return event.order;
  }

  setTimeout(callback, delay = 0) {
    return this.schedule(
      this.now + Math.max(this.timerClamp, Number(delay) || 0),
      callback,
      "timer",
    );
  }

  clearTimeout(handle) {
    const event = this.events.find(candidate => candidate.order === handle);
    if (event) event.canceled = true;
  }

  requestAnimationFrame(callback) {
    const frame = (Math.floor(this.now / this.refreshMillis) + 1) * this.refreshMillis;
    this.schedule(frame, () => callback(frame), "raf");
    return this.nextOrder;
  }

  postMessage(callback) {
    this.schedule(this.now + this.messageDelay, callback, "message");
  }

  runNext() {
    this.events.sort((left, right) => left.at - right.at || left.order - right.order);
    let event = this.events.shift();
    while (event?.canceled) event = this.events.shift();
    assert.ok(event, "virtual browser event queue starved");
    this.now = Math.max(this.now, event.at);
    event.callback();
  }

  runUntil(predicate, limit = 100000) {
    for (let step = 0; step < limit && !predicate(); step++) this.runNext();
    assert.ok(predicate(), "virtual browser did not make bounded progress");
  }
}

function createMessageChannelClass(browser) {
  return class VirtualMessageChannel {
    constructor() {
      this.port1 = {onmessage: null};
      this.port2 = {
        postMessage: () => browser.postMessage(() => this.port1.onmessage?.({data: 0})),
      };
    }
  };
}

function percentile(values, fraction) {
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * fraction))];
}

function onePercentLowFps(frameTimes) {
  const sampleCount = Math.max(1, Math.ceil(frameTimes.length * 0.01));
  const slowest = [...frameTimes].sort((left, right) => right - left).slice(0, sampleCount);
  return 1000 / (slowest.reduce((total, value) => total + value, 0) / slowest.length);
}

function displayedFrameTimes(presentTimes, refreshRate, duration) {
  const refreshMillis = 1000 / refreshRate;
  const displayed = [];
  let presentIndex = 0;
  let generation = 0;
  let displayedGeneration = 0;
  for (let at = refreshMillis; at <= duration; at += refreshMillis) {
    while (presentIndex < presentTimes.length && presentTimes[presentIndex] <= at + 1e-6) {
      generation++;
      presentIndex++;
    }
    if (generation !== displayedGeneration) {
      displayed.push(at);
      displayedGeneration = generation;
    }
  }
  return displayed.slice(1).map((at, index) => at - displayed[index]);
}

async function simulateVisibleYield(refreshRate, frameCount = 720) {
  const browser = new VirtualBrowser({refreshRate, timerClamp: 4});
  const telemetry = {enabled: true};
  const window = {__gaiusFrameTelemetry: telemetry};
  const context = vm.createContext({
    Date: {now: () => browser.now},
    clearTimeout: handle => browser.clearTimeout(handle),
    Math,
    MessageChannel: createMessageChannelClass(browser),
    Number,
    performance: {now: () => browser.now},
    requestAnimationFrame: callback => browser.requestAnimationFrame(callback),
    setTimeout: (callback, delay) => browser.setTimeout(callback, delay),
    window,
  });
  const scheduleYield = vm.runInContext(`(hidden, resume) => {${frameYieldScript}}`, context);
  const presentTimes = [];
  let completed = 0;
  const present = () => {
    presentTimes.push(browser.now);
    scheduleYield(false, () => {
      completed++;
      browser.now += 4.4;
      if (completed < frameCount) present();
    });
  };

  for (let at = 1; at < frameCount * browser.refreshMillis; at += 1) {
    browser.schedule(at, () => browser.sideTasksCompleted++, "side-task");
  }
  present();
  browser.runUntil(() => completed === frameCount);

  assert.equal(telemetry.yieldRequestCount, frameCount);
  assert.equal(telemetry.yieldCompletionCount, frameCount);
  assert.equal(telemetry.pendingYieldCount, 0);
  assert.equal(telemetry.maxPendingYieldCount, 1);
  assert.equal(telemetry.duplicateYieldCallbackCount, 0);
  assert.equal(telemetry.visibleYieldCount, frameCount);
  assert.equal(telemetry.hiddenYieldCount || 0, 0);
  assert.equal(telemetry.presentToRafCount, frameCount);
  assert.equal(telemetry.messageChannelYieldCount, frameCount);
  assert.ok(telemetry.longestPresentToRafMillis <= browser.refreshMillis + 1e-6,
    `rAF delay exceeded one ${refreshRate} Hz interval: ${telemetry.longestPresentToRafMillis}`);
  assert.ok(telemetry.longestYieldResumeDelayMillis < browser.refreshMillis + 0.1,
    `visible resume was starved: ${telemetry.longestYieldResumeDelayMillis}`);
  assert.ok(browser.sideTasksCompleted > frameCount * 5,
    "input/audio/MessagePort-like tasks were starved by the render loop");
  return presentTimes;
}

function simulateFixedTimer(frameCount = 720) {
  const workPattern = [4.1, 4.4, 4.7, 4.3, 4.6, 4.2, 4.8, 4.5];
  const timerJitter = [0, 0.2, 0.8, 0.1, 1.1, 0.4, 0.6, 0.3];
  const presents = [0];
  let now = 0;
  for (let frame = 1; frame < frameCount; frame++) {
    now += 4 + timerJitter[frame % timerJitter.length];
    now += workPattern[frame % workPattern.length];
    presents.push(now);
  }
  return presents;
}

async function simulateHiddenYield() {
  const browser = new VirtualBrowser({refreshRate: 120, timerClamp: 4});
  const telemetry = {enabled: true};
  const window = {__gaiusFrameTelemetry: telemetry};
  const context = vm.createContext({
    Date: {now: () => browser.now},
    clearTimeout: handle => browser.clearTimeout(handle),
    Math,
    MessageChannel: createMessageChannelClass(browser),
    Number,
    performance: {now: () => browser.now},
    requestAnimationFrame: callback => browser.requestAnimationFrame(callback),
    setTimeout: (callback, delay) => browser.setTimeout(callback, delay),
    window,
  });
  const scheduleYield = vm.runInContext(`(hidden, resume) => {${frameYieldScript}}`, context);
  let completed = false;
  scheduleYield(true, () => {
    completed = true;
  });
  browser.runUntil(() => completed);
  assert.equal(browser.now, 50);
  assert.equal(telemetry.hiddenYieldCount, 1);
  assert.equal(telemetry.timerYieldCount, 1);
  assert.equal(telemetry.pendingYieldCount, 0);
  assert.equal(telemetry.maxPendingYieldCount, 1);
  assert.equal(telemetry.duplicateYieldCallbackCount, 0);
  assert.equal(telemetry.presentToRafCount || 0, 0);
}

async function simulateStalledRafYield() {
  const browser = new VirtualBrowser({refreshRate: 120, timerClamp: 4});
  const telemetry = {enabled: true};
  const window = {__gaiusFrameTelemetry: telemetry};
  const context = vm.createContext({
    Date: {now: () => browser.now},
    clearTimeout: handle => browser.clearTimeout(handle),
    Math,
    MessageChannel: createMessageChannelClass(browser),
    Number,
    performance: {now: () => browser.now},
    requestAnimationFrame: () => 1,
    setTimeout: (callback, delay) => browser.setTimeout(callback, delay),
    window,
  });
  const scheduleYield = vm.runInContext(`(hidden, resume) => {${frameYieldScript}}`, context);
  let completed = false;
  scheduleYield(false, () => {
    completed = true;
  });
  browser.runUntil(() => completed);
  assert.equal(browser.now, 100);
  assert.equal(telemetry.yieldCompletionCount, 1);
  assert.equal(telemetry.timerYieldCount, 1);
  assert.equal(telemetry.pendingYieldCount, 0);
  assert.equal(telemetry.maxPendingYieldCount, 1);
  assert.equal(telemetry.duplicateYieldCallbackCount, 0);
  assert.equal(telemetry.presentToRafCount || 0, 0);
}

async function simulateStalledMessageYield() {
  const browser = new VirtualBrowser({refreshRate: 120, timerClamp: 4, messageDelay: 200});
  const telemetry = {enabled: true};
  const window = {__gaiusFrameTelemetry: telemetry};
  const context = vm.createContext({
    Date: {now: () => browser.now},
    clearTimeout: handle => browser.clearTimeout(handle),
    Math,
    MessageChannel: createMessageChannelClass(browser),
    Number,
    performance: {now: () => browser.now},
    requestAnimationFrame: callback => browser.requestAnimationFrame(callback),
    setTimeout: (callback, delay) => browser.setTimeout(callback, delay),
    window,
  });
  const scheduleYield = vm.runInContext(`(hidden, resume) => {${frameYieldScript}}`, context);
  let completed = false;
  scheduleYield(false, () => {
    completed = true;
  });
  browser.runUntil(() => completed);
  assert.equal(browser.now, 100);
  assert.equal(telemetry.presentToRafCount, 1);
  assert.equal(telemetry.timerYieldCount, 1);
  assert.equal(telemetry.messageChannelYieldCount || 0, 0);
  assert.equal(telemetry.pendingYieldCount, 0);
  assert.equal(telemetry.maxPendingYieldCount, 1);
  browser.runNext();
  assert.equal(telemetry.duplicateYieldCallbackCount, 1,
    "late MessageChannel callback was not detected after watchdog recovery");
}

async function simulateOverlappingYields() {
  const browser = new VirtualBrowser({refreshRate: 120, timerClamp: 4});
  const telemetry = {enabled: true};
  const window = {__gaiusFrameTelemetry: telemetry};
  const context = vm.createContext({
    Date: {now: () => browser.now},
    clearTimeout: handle => browser.clearTimeout(handle),
    Math,
    MessageChannel: createMessageChannelClass(browser),
    Number,
    performance: {now: () => browser.now},
    requestAnimationFrame: callback => browser.requestAnimationFrame(callback),
    setTimeout: (callback, delay) => browser.setTimeout(callback, delay),
    window,
  });
  const scheduleYield = vm.runInContext(`(hidden, resume) => {${frameYieldScript}}`, context);
  let completed = 0;
  scheduleYield(false, () => completed++);
  scheduleYield(false, () => completed++);
  assert.equal(telemetry.pendingYieldCount, 2);
  assert.equal(telemetry.maxPendingYieldCount, 2,
    "overlapping continuations did not raise the historical pending high-water mark");
  browser.runUntil(() => completed === 2);
  assert.equal(telemetry.pendingYieldCount, 0);
  assert.equal(telemetry.yieldCompletionCount, 2);
  assert.equal(telemetry.duplicateYieldCallbackCount, 0);
}

const visiblePresents = await simulateVisibleYield(120);
const highRefreshPresents = await simulateVisibleYield(144);
await simulateHiddenYield();
await simulateStalledRafYield();
await simulateStalledMessageYield();
await simulateOverlappingYields();
const fixedTimerPresents = simulateFixedTimer();
const duration = Math.max(visiblePresents.at(-1), fixedTimerPresents.at(-1)) + 20;
const cooperativeFrames = displayedFrameTimes(visiblePresents, 120, duration);
const fixedTimerFrames = displayedFrameTimes(fixedTimerPresents, 120, duration);
const highRefreshFrames = displayedFrameTimes(
  highRefreshPresents,
  144,
  highRefreshPresents.at(-1) + 20,
);
const cooperativeP99 = percentile(cooperativeFrames, 0.99);
const fixedTimerP99 = percentile(fixedTimerFrames, 0.99);
const highRefreshP99 = percentile(highRefreshFrames, 0.99);
const cooperativeOnePercentLow = onePercentLowFps(cooperativeFrames);
const fixedTimerOnePercentLow = onePercentLowFps(fixedTimerFrames);
const highRefreshOnePercentLow = onePercentLowFps(highRefreshFrames);

assert.ok(cooperativeP99 < 9,
  `cooperative 120 Hz pacing lost its 1% low: p99=${cooperativeP99.toFixed(3)} ms`);
assert.ok(fixedTimerP99 > 15,
  `timer-clamp control did not reproduce the 1% low regression: p99=${fixedTimerP99.toFixed(3)} ms`);
assert.ok(highRefreshP99 < 7.2,
  `cooperative 144 Hz pacing was capped or unstable: p99=${highRefreshP99.toFixed(3)} ms`);
assert.ok(cooperativeOnePercentLow > 115,
  `cooperative 120 Hz 1% low regressed: ${cooperativeOnePercentLow.toFixed(1)} FPS`);
assert.ok(fixedTimerOnePercentLow < 70,
  `fixed timer control did not regress: ${fixedTimerOnePercentLow.toFixed(1)} FPS`);
assert.ok(highRefreshOnePercentLow > 138,
  `cooperative 144 Hz 1% low regressed: ${highRefreshOnePercentLow.toFixed(1)} FPS`);

console.log(
  "Frame pacing yield smoke passed:",
  `120Hz 1% low=${cooperativeOnePercentLow.toFixed(1)}fps,`,
  `144Hz 1% low=${highRefreshOnePercentLow.toFixed(1)}fps,`,
  `fixed-1ms/clamped 1% low=${fixedTimerOnePercentLow.toFixed(1)}fps`,
);
