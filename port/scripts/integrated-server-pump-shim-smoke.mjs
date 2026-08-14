#!/usr/bin/env node

import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import path from "node:path";
import vm from "node:vm";
import {fileURLToPath} from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const postprocess = path.join(root, "port/scripts/postprocess-teavm-js.py");
const python = path.join(root, "port/scripts/run-python.sh");
const generator = [
  "import importlib.util",
  `spec = importlib.util.spec_from_file_location('gaius_postprocess', ${JSON.stringify(postprocess)})`,
  "module = importlib.util.module_from_spec(spec)",
  "spec.loader.exec_module(module)",
  "print(module.integrated_server_pump_shim('exports', 'startThread'), end='')",
].join("; ");
const generated = spawnSync(python, ["-c", generator], {
  cwd: root,
  encoding: "utf8",
  maxBuffer: 1024 * 1024,
});
assert.equal(generated.status, 0, generated.stderr || "pump shim generation failed");
const shim = generated.stdout;
for (const contract of [
  "gaius-integrated-server-input-coroutine",
  "$gaiusIntegratedServerPumpMaxRetries = 4",
  "$gaiusIntegratedServerPumpDispatchScheduled",
  "$gaiusIntegratedServerPumpRetryTimer",
  "integratedServerPumpRetrySchedules",
  "integratedServerPumpRetryExhaustions",
]) {
  assert.ok(shim.includes(contract), `generated pump shim is missing ${contract}`);
}

function runtime(startThread) {
  const microtasks = [];
  const timers = [];
  const callbacks = [];
  let pumpCalls = 0;
  const context = {
    Error,
    Math,
    Number,
    Promise,
    String,
    exports: {
      pumpIntegratedServerNetworkInput() {
        pumpCalls++;
      },
    },
    __gaiusNetworkStats: {},
    queueMicrotask(callback) {
      microtasks.push(callback);
    },
    setTimeout(callback, delay) {
      timers.push({callback, delay});
      return timers.length;
    },
  };
  context.globalThis = context;
  context.startThread = (entry, callback) => startThread({
    entry,
    callback,
    callbacks,
  });
  vm.runInNewContext(shim, context, {filename: "integrated-server-pump-shim.js"});
  return {
    callbacks,
    context,
    microtasks,
    timers,
    get pumpCalls() {
      return pumpCalls;
    },
    runMicrotask() {
      assert.ok(microtasks.length > 0, "no pump microtask was queued");
      microtasks.shift()();
    },
    runTimer() {
      assert.ok(timers.length > 0, "no pump retry timer was queued");
      return timers.shift();
    },
  };
}

const coalesced = runtime(({entry, callback, callbacks}) => {
  entry();
  callbacks.push(callback);
});
coalesced.context.exports.__gaiusStartIntegratedServerPump();
coalesced.context.exports.__gaiusStartIntegratedServerPump();
assert.equal(coalesced.pumpCalls, 1);
assert.equal(coalesced.context.__gaiusNetworkStats.integratedServerPumpCoalesced, 1);
coalesced.callbacks.shift()(null);
coalesced.context.exports.__gaiusStartIntegratedServerPump();
assert.equal(coalesced.pumpCalls, 1,
  "input arriving before the queued dispatch must not start a competing coroutine");
coalesced.runMicrotask();
coalesced.callbacks.shift()(null);
assert.equal(coalesced.pumpCalls, 2, "coalesced input was not delivered after success");
assert.equal(coalesced.context.__gaiusNetworkStats.integratedServerPumpStarts, 2);
assert.equal(coalesced.context.__gaiusNetworkStats.integratedServerPumpCoalesced, 2);

let thrownStarts = 0;
const recovered = runtime(({entry, callback, callbacks}) => {
  thrownStarts++;
  if (thrownStarts <= 2) throw new Error("Another thread is running");
  entry();
  callbacks.push(callback);
});
recovered.context.exports.__gaiusStartIntegratedServerPump();
const firstRetry = recovered.runTimer();
assert.equal(firstRetry.delay, 1);
firstRetry.callback();
const secondRetry = recovered.runTimer();
assert.equal(secondRetry.delay, 2);
secondRetry.callback();
recovered.callbacks.shift()(null);
assert.equal(recovered.pumpCalls, 1, "a transient starter failure lost the input signal");
assert.equal(recovered.context.__gaiusNetworkStats.integratedServerPumpFailures, 2);
assert.equal(recovered.context.__gaiusNetworkStats.integratedServerPumpRetrySchedules, 2);
assert.equal(recovered.context.__gaiusNetworkStats.integratedServerPumpRetryExhaustions, 0);

const exhausted = runtime(() => {
  throw new Error("Another thread is running");
});
exhausted.context.exports.__gaiusStartIntegratedServerPump();
const retryDelays = [];
while (exhausted.timers.length > 0) {
  const timer = exhausted.runTimer();
  retryDelays.push(timer.delay);
  timer.callback();
}
assert.deepEqual(retryDelays, [1, 2, 4, 8]);
assert.equal(exhausted.context.__gaiusNetworkStats.integratedServerPumpStarts, 5);
assert.equal(exhausted.context.__gaiusNetworkStats.integratedServerPumpFailures, 5);
assert.equal(exhausted.context.__gaiusNetworkStats.integratedServerPumpRetrySchedules, 4);
assert.equal(exhausted.context.__gaiusNetworkStats.integratedServerPumpRetryExhaustions, 1);
assert.equal(exhausted.timers.length, 0, "terminal starter failures must not spin forever");

console.log(JSON.stringify({
  ok: true,
  coalescedWakePreserved: true,
  transientFailureRecovered: true,
  retryDelays,
  terminalFailureBounded: true,
}));
