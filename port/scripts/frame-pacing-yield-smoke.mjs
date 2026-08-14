#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const sourcePath = new URL(
  "../overrides/libraries/lwjgl-glfw/src/main/java/org/lwjgl/glfw/BrowserGlfw.java",
  import.meta.url,
);
const source = await readFile(sourcePath, "utf8");
const UNCAPPED_FAIR_YIELD_CADENCE = 4;

function jsBodyBefore(marker) {
  const markerOffset = source.indexOf(marker);
  const annotationOffset = source.lastIndexOf(
    '@JSBody(params = {"hidden", "interval", "resume"}, script = """',
    markerOffset,
  );
  const scriptOffset = source.indexOf('"""', annotationOffset) + 3;
  const scriptEnd = source.lastIndexOf('""")', markerOffset);
  assert.ok(markerOffset > 0 && annotationOffset > 0 && scriptEnd > scriptOffset,
    `JSBody could not be extracted for ${marker}`);
  return source.slice(scriptOffset, scriptEnd).replaceAll("\\\\", "\\");
}

assert.match(source, /@Async\s+private static native void yieldAfterPresent\(boolean hidden, int interval\)/,
  "swapBuffers yield is not a TeaVM async continuation boundary");
assert.match(source, /private static int swapInterval;/,
  "GLFW swap interval is not retained for the asynchronous present boundary");
assert.match(source, /requestAnimationFrame/, "visible pacing does not use rAF");
assert.match(source, /new MessageChannel\(\)/, "visible pacing does not yield through MessageChannel");
assert.match(source, /setTimeout\(\(\) => finish\('timer'\), 50\)/,
  "hidden pacing is not kept low-frequency");
assert.match(source, /watchdog=setTimeout\(\(\) => finish\('watchdog'\), 100\)/,
  "a suspended visible rAF can starve its TeaVM continuation");
assert.match(source, /else if \(synchronizedToDisplay && typeof requestAnimationFrame==='function'\)/,
  "swapInterval(0) still waits for display refresh");
assert.match(source, /telemetry\.uncappedYieldCount=/,
  "uncapped scheduling is not observable in release telemetry");
assert.match(source, /telemetry\.vsyncYieldCount=/,
  "VSync scheduling is not observable in release telemetry");
assert.match(source, /telemetry\.swapInterval=Number\(interval\)\|\|0/,
  "the effective GLFW swap interval is not observable in release telemetry");
assert.match(source, /scheduler=\{tasks:new Map\(\),channel:null,nextTaskId:1\}/,
  "MessageChannel continuations are not stored as cancellable tokens");
assert.match(source, /scheduler\.tasks\.delete\(taskId\)/,
  "watchdog recovery cannot remove a stalled MessageChannel continuation");
assert.match(source, /setTimeout\(\(\) => finish\('timer'\), 0\)/,
  "uncapped pacing has no real timer task for browser fairness");
assert.match(source, /\(sequence & 3\)===0/,
  "uncapped pacing fairness cadence changed from once per 4 frames");
assert.doesNotMatch(source, /scheduler=\{queue:\[\],channel:null\}/,
  "frame pacing still retains an unbounded callback queue");
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
  "private static native void scheduleFrameYield(boolean hidden, int interval, FrameYieldCallback resume);",
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
    this.rafRequests = 0;
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
    this.rafRequests++;
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
      let closed = false;
      this.port1 = {
        onmessage: null,
        close: () => {
          closed = true;
        },
      };
      this.port2 = {
        postMessage: data => browser.postMessage(() => {
          if (!closed) this.port1.onmessage?.({data});
        }),
        close: () => {
          closed = true;
        },
      };
    }
  };
}

function createDeadMessageChannelClass(stats) {
  return class DeadMessageChannel {
    constructor() {
      stats.created++;
      let closed = false;
      this.port1 = {
        onmessage: null,
        close: () => {
          if (!closed) stats.closed++;
          closed = true;
        },
      };
      this.port2 = {
        postMessage: () => {
          stats.posts++;
        },
        close: () => {
          closed = true;
        },
      };
    }
  };
}

function createThrowingMessageChannelClass(stats) {
  return class ThrowingMessageChannel {
    constructor() {
      stats.created++;
      let closed = false;
      this.port1 = {
        onmessage: null,
        close: () => {
          if (!closed) stats.closed++;
          closed = true;
        },
      };
      this.port2 = {
        postMessage: () => {
          stats.posts++;
          throw new Error("synthetic MessageChannel post failure");
        },
        close: () => {
          closed = true;
        },
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
  const scheduleYield = vm.runInContext(`(hidden, interval, resume) => {${frameYieldScript}}`, context);
  const presentTimes = [];
  let completed = 0;
  const present = () => {
    presentTimes.push(browser.now);
    scheduleYield(false, 1, () => {
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
  assert.equal(telemetry.swapInterval, 1);
  assert.equal(telemetry.vsyncYieldCount, frameCount);
  assert.equal(telemetry.uncappedYieldCount || 0, 0);
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

async function simulateUncappedYield(frameCount = 1440) {
  const browser = new VirtualBrowser({refreshRate: 60, timerClamp: 4, messageDelay: 0.01});
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
  const scheduleYield = vm.runInContext(`(hidden, interval, resume) => {${frameYieldScript}}`, context);
  const presentTimes = [];
  const workPattern = [4.0, 4.4, 4.7, 4.2, 5.0, 4.5, 4.1, 4.8];
  let completed = 0;
  const present = () => {
    presentTimes.push(browser.now);
    scheduleYield(false, 0, () => {
      completed++;
      browser.now += workPattern[completed % workPattern.length];
      if (completed < frameCount) present();
    });
  };

  for (let at = 1; at < frameCount * 5; at += 1) {
    browser.schedule(at, () => browser.sideTasksCompleted++, "side-task");
  }
  present();
  browser.runUntil(() => completed === frameCount);

  const frameTimes = presentTimes.slice(1).map((at, index) => at - presentTimes[index]);
  const averageFps = 1000 / (frameTimes.reduce((total, value) => total + value, 0) / frameTimes.length);
  const onePercentLow = onePercentLowFps(frameTimes);
  const fairYieldCount = Math.floor(frameCount / UNCAPPED_FAIR_YIELD_CADENCE);
  assert.equal(browser.rafRequests, 0, "uncapped pacing unexpectedly waited for rAF");
  assert.equal(telemetry.swapInterval, 0);
  assert.equal(telemetry.uncappedYieldCount, frameCount);
  assert.equal(telemetry.vsyncYieldCount || 0, 0);
  assert.equal(telemetry.presentToRafCount || 0, 0);
  assert.equal(telemetry.messageChannelYieldCount, frameCount - fairYieldCount);
  assert.equal(telemetry.timerYieldCount, fairYieldCount);
  assert.equal(telemetry.fairYieldCount, fairYieldCount);
  assert.equal(telemetry.schedulerYieldCount || 0, 0);
  assert.equal(telemetry.yieldRequestCount, frameCount);
  assert.equal(telemetry.yieldCompletionCount, frameCount);
  assert.equal(telemetry.pendingYieldCount, 0);
  assert.equal(telemetry.maxPendingYieldCount, 1);
  assert.equal(telemetry.duplicateYieldCallbackCount, 0);
  assert.equal(telemetry.cancelledMessageTaskCount || 0, 0);
  assert.equal(window.__gaiusFrameYieldScheduler.tasks.size, 0);
  assert.ok(browser.sideTasksCompleted > frameCount,
    "uncapped render loop starved timer/input/MessagePort-like tasks");
  assert.ok(averageFps >= 120,
    `uncapped scheduler failed the 120 FPS average contract: ${averageFps.toFixed(1)} FPS`);
  assert.ok(onePercentLow >= 60,
    `uncapped scheduler failed the 60 FPS 1% low contract: ${onePercentLow.toFixed(1)} FPS`);
  return {averageFps, onePercentLow};
}

async function simulateDeadMessageChannel(frameCount = 2048) {
  const browser = new VirtualBrowser({refreshRate: 60, timerClamp: 4});
  const telemetry = {enabled: true};
  const window = {__gaiusFrameTelemetry: telemetry};
  const channelStats = {created: 0, closed: 0, posts: 0};
  const context = vm.createContext({
    Date: {now: () => browser.now},
    clearTimeout: handle => browser.clearTimeout(handle),
    Map,
    Math,
    MessageChannel: createDeadMessageChannelClass(channelStats),
    Number,
    performance: {now: () => browser.now},
    requestAnimationFrame: callback => browser.requestAnimationFrame(callback),
    setTimeout: (callback, delay) => browser.setTimeout(callback, delay),
    window,
  });
  const scheduleYield = vm.runInContext(`(hidden, interval, resume) => {${frameYieldScript}}`, context);
  let completed = 0;
  const present = () => scheduleYield(false, 0, () => {
    completed++;
    if (completed < frameCount) present();
  });
  present();
  browser.runUntil(() => completed === frameCount);

  const fairYieldCount = Math.floor(frameCount / UNCAPPED_FAIR_YIELD_CADENCE);
  const messageAttempts = frameCount - fairYieldCount;
  const scheduler = window.__gaiusFrameYieldScheduler;
  assert.equal(telemetry.yieldRequestCount, frameCount);
  assert.equal(telemetry.yieldCompletionCount, frameCount);
  assert.equal(telemetry.pendingYieldCount, 0);
  assert.equal(telemetry.cancelledMessageTaskCount, messageAttempts);
  assert.equal(telemetry.messageChannelRebuildCount, messageAttempts);
  assert.equal(telemetry.watchdogYieldCount, messageAttempts);
  assert.equal(telemetry.fairYieldCount, fairYieldCount);
  assert.equal(scheduler.tasks.size, 0,
    "dead MessageChannel retained completed TeaVM continuations");
  assert.equal(scheduler.channel, null,
    "dead MessageChannel was not retired after watchdog recovery");
  assert.equal(channelStats.created, messageAttempts);
  assert.equal(channelStats.closed, messageAttempts);
  assert.equal(channelStats.posts, messageAttempts);
}

async function simulateThrowingMessageChannel(frameCount = 128) {
  const browser = new VirtualBrowser({refreshRate: 60, timerClamp: 4});
  const telemetry = {enabled: true};
  const window = {__gaiusFrameTelemetry: telemetry};
  const channelStats = {created: 0, closed: 0, posts: 0};
  const context = vm.createContext({
    Date: {now: () => browser.now},
    clearTimeout: handle => browser.clearTimeout(handle),
    Map,
    Math,
    MessageChannel: createThrowingMessageChannelClass(channelStats),
    Number,
    performance: {now: () => browser.now},
    requestAnimationFrame: callback => browser.requestAnimationFrame(callback),
    setTimeout: (callback, delay) => browser.setTimeout(callback, delay),
    window,
  });
  const scheduleYield = vm.runInContext(`(hidden, interval, resume) => {${frameYieldScript}}`, context);
  let completed = 0;
  const present = () => scheduleYield(false, 0, () => {
    completed++;
    if (completed < frameCount) present();
  });
  present();
  browser.runUntil(() => completed === frameCount);

  const fairYieldCount = Math.floor(frameCount / UNCAPPED_FAIR_YIELD_CADENCE);
  const failedPosts = frameCount - fairYieldCount;
  const scheduler = window.__gaiusFrameYieldScheduler;
  assert.equal(telemetry.yieldCompletionCount, frameCount);
  assert.equal(telemetry.pendingYieldCount, 0);
  assert.equal(telemetry.messageChannelPostFailureCount, failedPosts);
  assert.equal(telemetry.cancelledMessageTaskCount, failedPosts);
  assert.equal(telemetry.messageChannelRebuildCount, failedPosts);
  assert.equal(telemetry.watchdogYieldCount || 0, 0);
  assert.equal(telemetry.timerYieldCount, frameCount);
  assert.equal(telemetry.duplicateYieldCallbackCount, 0);
  assert.equal(scheduler.tasks.size, 0);
  assert.equal(scheduler.channel, null);
  assert.equal(channelStats.created, failedPosts);
  assert.equal(channelStats.closed, failedPosts);
  assert.equal(channelStats.posts, failedPosts);
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
  const scheduleYield = vm.runInContext(`(hidden, interval, resume) => {${frameYieldScript}}`, context);
  let completed = false;
  scheduleYield(true, 0, () => {
    completed = true;
  });
  browser.runUntil(() => completed);
  assert.equal(browser.now, 50);
  assert.equal(telemetry.hiddenYieldCount, 1);
  assert.equal(telemetry.swapInterval, 0);
  assert.equal(telemetry.uncappedYieldCount, 1);
  assert.equal(telemetry.timerYieldCount, 1);
  assert.equal(telemetry.watchdogYieldCount || 0, 0);
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
  const scheduleYield = vm.runInContext(`(hidden, interval, resume) => {${frameYieldScript}}`, context);
  let completed = false;
  scheduleYield(false, 1, () => {
    completed = true;
  });
  browser.runUntil(() => completed);
  assert.equal(browser.now, 100);
  assert.equal(telemetry.yieldCompletionCount, 1);
  assert.equal(telemetry.timerYieldCount, 1);
  assert.equal(telemetry.watchdogYieldCount, 1);
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
  const scheduleYield = vm.runInContext(`(hidden, interval, resume) => {${frameYieldScript}}`, context);
  let completed = false;
  scheduleYield(false, 0, () => {
    completed = true;
  });
  browser.runUntil(() => completed);
  assert.equal(browser.now, 100);
  assert.equal(telemetry.presentToRafCount || 0, 0);
  assert.equal(telemetry.uncappedYieldCount, 1);
  assert.equal(telemetry.timerYieldCount, 1);
  assert.equal(telemetry.watchdogYieldCount, 1);
  assert.equal(telemetry.messageChannelYieldCount || 0, 0);
  assert.equal(telemetry.pendingYieldCount, 0);
  assert.equal(telemetry.maxPendingYieldCount, 1);
  browser.runNext();
  assert.equal(telemetry.cancelledMessageTaskCount, 1);
  assert.equal(telemetry.messageChannelRebuildCount, 1);
  assert.equal(window.__gaiusFrameYieldScheduler.tasks.size, 0);
  assert.equal(telemetry.duplicateYieldCallbackCount, 0,
    "retired MessageChannel delivered a late callback");
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
  const scheduleYield = vm.runInContext(`(hidden, interval, resume) => {${frameYieldScript}}`, context);
  let completed = 0;
  scheduleYield(false, 0, () => completed++);
  scheduleYield(false, 0, () => completed++);
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
const uncapped = await simulateUncappedYield();
await simulateDeadMessageChannel();
await simulateThrowingMessageChannel();
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
  `uncapped avg=${uncapped.averageFps.toFixed(1)}fps,`,
  `uncapped 1% low=${uncapped.onePercentLow.toFixed(1)}fps,`,
  `120Hz 1% low=${cooperativeOnePercentLow.toFixed(1)}fps,`,
  `144Hz 1% low=${highRefreshOnePercentLow.toFixed(1)}fps,`,
  `fixed-1ms/clamped 1% low=${fixedTimerOnePercentLow.toFixed(1)}fps`,
);
