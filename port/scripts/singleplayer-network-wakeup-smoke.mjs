#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import {fileURLToPath} from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const source = fs.readFileSync(path.join(
  root,
  "port/src/main/java/dev/gaius/browser/BrowserIntegratedServerMain.java",
), "utf8");

const signalStart = source.indexOf("public static void signalIntegratedServerNetworkInput()");
const scheduleStart = source.indexOf("private static boolean scheduleNetworkInputTask(", signalStart);
const runStart = source.indexOf("private static void runScheduledNetworkInput()", scheduleStart);
const nextMethod = source.indexOf("public static void pumpIntegratedServerNetworkInput()", runStart);
assert.ok(signalStart >= 0 && scheduleStart > signalStart && runStart > scheduleStart &&
  nextMethod > runStart);

const signal = source.slice(signalStart, scheduleStart);
const schedule = source.slice(scheduleStart, runStart);
const run = source.slice(runStart, nextMethod);
assert.match(source, /MAX_NETWORK_INPUT_FOLLOWUPS = 4;/);
assert.match(source, /MAX_NETWORK_INPUT_DEFERRED_RETRIES = 4;/);
assert.match(source,
  /private static final AtomicBoolean NETWORK_INPUT_TASK_SCHEDULED = new AtomicBoolean\(\);/);
assert.doesNotMatch(signal, /networkInputFollowupsRemaining = MAX_NETWORK_INPUT_FOLLOWUPS/);
assert.match(signal, /recordNetworkInputPending\(true\);/);
assert.match(signal, /scheduleNetworkInputTask\(false, true\);/);
assert.match(schedule, /currentServerThread == null \|\| serverThreadExited/);
assert.match(schedule, /!current\.isRunning\(\)/);
assert.match(schedule, /NETWORK_INPUT_TASK_SCHEDULED\.compareAndSet\(false, true\)/);
assert.ok(
  schedule.indexOf("NETWORK_INPUT_TASK_SCHEDULED.compareAndSet(false, true)") <
    schedule.indexOf("networkInputFollowupsRemaining = MAX_NETWORK_INPUT_FOLLOWUPS"),
  "coalesced signals must not refresh the active burst budget",
);
const enqueue = schedule.indexOf(
  "current.schedule(new TickTask(Integer.MIN_VALUE, NETWORK_INPUT_TASK))",
);
assert.ok(enqueue >= 0, "network task must use an overdue TickTask");
assert.ok(
  schedule.indexOf("LockSupport.unpark(currentServerThread)", enqueue) > enqueue,
  "a newly claimed task must be enqueued before its explicit wake",
);
assert.ok(
  schedule.indexOf("LockSupport.unpark(currentServerThread)") < enqueue,
  "a coalesced signal must refresh the queued task's wake permit",
);
assert.match(run, /pumped = drainUrgentPackets\(\);/);
assert.match(run, /network-pump-wrong-thread/);
assert.match(run, /finally\s*\{\s*NETWORK_INPUT_TASK_SCHEDULED\.set\(false\);/s);
assert.match(run, /networkInputFollowupsRemaining <= 0/);
assert.match(run, /networkInputFollowupsRemaining--;/);
assert.match(run, /scheduleNetworkInputTask\(true, false\);/);
assert.match(run, /deferNetworkInputRetry\(\);/);
assert.match(run, /TModernRuntimeSupport\.yieldToEventLoop\(delayMillis\);/);
assert.match(run, /scheduleNetworkInputTask\(false, false\);/);
assert.match(run, /network-pump-retry-exhausted/);
assert.doesNotMatch(run, /signalIntegratedServerNetworkInput\(\);/);
assert.match(source, /integratedServerTaskBudgetExhaustions/);
assert.match(source, /integratedServerTaskWrongThread/);
assert.match(source, /var field = '';/,
  "TeaVM's JSBody parser requires a var declaration for the event field");
assert.doesNotMatch(source, /let field = '';/);

let scheduled = false;
let active = true;
let burstActive = false;
let followupsRemaining = 0;
let deferredRetriesRemaining = 0;
let signals = 0;
let schedules = 0;
let unparks = 0;
let coalesced = 0;
let followups = 0;
let runs = 0;
let wrongThread = 0;
let lifecycleDrops = 0;
let budgetExhaustions = 0;
let deferredRetries = 0;
let retryExhaustions = 0;
const scheduleModel = (followup, externalSignal) => {
  if (!active) {
    lifecycleDrops++;
    return false;
  }
  if (scheduled) {
    unparks++;
    coalesced++;
    return false;
  }
  if (!followup) {
    if (!externalSignal) {
      burstActive = true;
      followupsRemaining = 4;
    } else if (!burstActive) {
      burstActive = true;
      followupsRemaining = 4;
      deferredRetriesRemaining = 4;
    }
  }
  scheduled = true;
  schedules++;
  unparks++;
  if (followup) followups++;
  return true;
};
const signalModel = () => {
  signals++;
  return scheduleModel(false, true);
};
const finishBurstModel = () => {
  burstActive = false;
  followupsRemaining = 0;
  deferredRetriesRemaining = 0;
};
const runModel = ({
  pendingAfterPump,
  pumpSucceeded = true,
  resumeDeferred = true,
}) => {
  scheduled = false;
  runs++;
  if (!pumpSucceeded) {
    wrongThread++;
    return;
  }
  if (!pendingAfterPump) {
    finishBurstModel();
    return;
  }
  if (!active) {
    finishBurstModel();
    lifecycleDrops++;
    return;
  }
  if (followupsRemaining <= 0) {
    budgetExhaustions++;
    if (deferredRetriesRemaining <= 0) {
      retryExhaustions++;
      finishBurstModel();
      return;
    }
    deferredRetriesRemaining--;
    deferredRetries++;
    if (resumeDeferred) scheduleModel(false, false);
    return;
  }
  followupsRemaining--;
  scheduleModel(true, false);
};

signalModel();
signalModel();
signalModel();
assert.equal(schedules, 1, "coalesced input must keep one queued server task");
assert.equal(unparks, 3, "coalesced input must still refresh every wake permit");
assert.equal(coalesced, 2);
runModel({pendingAfterPump: true});
assert.equal(schedules, 2, "pending input after a drain must schedule a follow-up");
assert.equal(followupsRemaining, 3);
signalModel();
assert.equal(followupsRemaining, 3,
  "a coalesced external signal must not refresh the current burst budget");
assert.equal(scheduled, true);
runModel({pendingAfterPump: false});
assert.equal(scheduled, false);

signalModel();
for (let index = 0; index < 4; index++) {
  runModel({pendingAfterPump: true});
}
runModel({pendingAfterPump: true, resumeDeferred: false});
assert.equal(burstActive, true);
assert.equal(followupsRemaining, 0);
assert.equal(deferredRetriesRemaining, 3);
signalModel();
assert.equal(followupsRemaining, 0,
  "external input during a deferred retry must not replenish follow-ups");
assert.equal(deferredRetriesRemaining, 3,
  "external input during a deferred retry must not replenish retry budget");
scheduleModel(false, false);
assert.equal(followupsRemaining, 0,
  "a coalesced deferred continuation must not replenish the claimed task");
runModel({pendingAfterPump: false});
assert.equal(burstActive, false);

const budgetExhaustionsBeforeStuckBacklog = budgetExhaustions;
const deferredRetriesBeforeStuckBacklog = deferredRetries;
const retryExhaustionsBeforeStuckBacklog = retryExhaustions;
signalModel();
for (let index = 0; index < 25; index++) {
  runModel({pendingAfterPump: true});
}
assert.equal(followupsRemaining, 0);
assert.equal(budgetExhaustions - budgetExhaustionsBeforeStuckBacklog, 5,
  "each bounded burst must yield before retrying");
assert.equal(deferredRetries - deferredRetriesBeforeStuckBacklog, 4,
  "bounded backlog must receive four delayed retries");
assert.equal(retryExhaustions - retryExhaustionsBeforeStuckBacklog, 1,
  "a permanently stuck backlog must terminate explicitly");
assert.equal(scheduled, false);

signalModel();
runModel({pendingAfterPump: true, pumpSucceeded: false});
assert.equal(wrongThread, 1);
assert.equal(scheduled, false, "wrong-thread execution must not schedule a retry");

active = false;
signalModel();
assert.equal(lifecycleDrops, 1, "stopped servers must reject late input tasks");
assert.equal(scheduled, false);
assert.equal(unparks, schedules + coalesced,
  "every successful schedule and coalesced signal must issue exactly one explicit wake");
assert.ok(schedules - followups <= signals + deferredRetries,
  "successful initial schedules cannot exceed external signals and retries");
assert.ok(followups <= schedules, "follow-up schedules must be a subset of all schedules");
assert.match(source, /integratedServerTaskTelemetryVersion !== 1/);
for (const field of [
  "integratedServerPumpFailures",
  "integratedServerTaskScheduleFailures",
  "integratedServerTaskWrongThread",
  "integratedServerTaskBudgetExhaustions",
]) {
  assert.match(source, new RegExp(`stats\\.${field} =`), `${field} must initialize to zero`);
}

console.log(JSON.stringify({
  ok: true,
  signals,
  schedules,
  unparks,
  coalesced,
  followups,
  runs,
  boundedFollowupDrain: true,
  wrongThreadStopsRetry: true,
  lifecycleGuard: true,
}));
