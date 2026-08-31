#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

const sourcePath = new URL(
  "../overrides/libraries/netty-transport/src/main/java/" +
    "io/netty/channel/browser/BrowserWebSocketChannel.java",
  import.meta.url,
);
const source = await readFile(sourcePath, "utf8");

const pumpStart = source.indexOf("public static boolean pumpAllAndReportProgress()");
const pumpEnd = source.indexOf(
  "/** Returns whether a browser transport has data waiting for the Java pipeline. */",
  pumpStart,
);
assert.ok(pumpStart >= 0 && pumpEnd > pumpStart,
  "global pump method could not be extracted");
const pump = source.slice(pumpStart, pumpEnd);

// The per-channel limits are an existing contract. The global scheduler must add a bound,
// never replace or silently enlarge these limits.
assert.match(source, /MAX_CHUNKS_PER_PUMP = 64/);
assert.match(source, /MAX_BYTES_PER_PUMP = 256 \* 1024/);
assert.match(source, /MAX_MILLIS_PER_PUMP = 2\.0/);
assert.match(source, /MAX_TOTAL_MILLIS_PER_PUMP = 4\.0/);
assert.match(source, /private static int nextPumpChannelIndex;/);
assert.match(source, /pumpAllTurns: 0/);
assert.match(source, /pumpAllChannelsVisited: 0/);
assert.match(source, /pumpAllBudgetYields: 0/);
assert.match(source, /recordPumpAllTelemetry\(channelsVisited, elapsed, budgetExhausted\)/);

assert.match(pump, /final double startedAt = monotonicMillis\(\);/);
assert.match(pump, /final int channelCount = channels\.length;/);
assert.match(pump, /int index = nextPumpChannelIndex;/);
assert.match(pump, /while \(channelsVisited < channelCount\)/);
assert.match(pump,
  /openChannelsVisited > 0[\s\S]*monotonicMillis\(\) - startedAt >= MAX_TOTAL_MILLIS_PER_PUMP/,
  "global pump lacks its aggregate time gate after the first inspected slot");
assert.match(pump, /int openChannelsVisited = 0;/);
assert.match(pump, /openChannelsVisited\+\+;/);
assert.match(pump, /budgetExhausted = true;/);
assert.match(pump, /if \(channel == null \|\| !channel\.open\) \{\s*continue;/);
assert.match(pump, /progressed \|= channel\.pump\(\);/);
assert.match(pump, /nextPumpChannelIndex = currentLength == 0 \? 0 : index % currentLength;/);
assert.match(pump, /return progressed \|\| budgetExhausted;/,
  "a budget-shortened scan must return a continuation hint for ready channels later in the ring");
assert.match(source, /public static void pumpAll\(\) \{\s*pumpAllAndReportProgress\(\);\s*\}/,
  "legacy pumpAll entry point no longer uses the bounded global pump");
assert.match(source, /public static boolean hasPumpableInput\(\)/,
  "global pump lost the ready-input predicate used by the follow-up scheduler");
assert.doesNotMatch(pump, /Platform\.schedule|new Thread|CompletableFuture|setTimeout|MessageChannel/,
  "global pump introduced asynchronous/thread work into the bounded Java turn");
assert.doesNotMatch(pump, /for \(BrowserWebSocketChannel channel : channels\)/,
  "global pump reverted to an unbounded full-array foreach");

const MAX_TOTAL_MILLIS = 4;

/**
 * Deterministic model of the Java loop. A channel's one pump call is intentionally treated as
 * non-preemptible; the aggregate gate is checked only between channels, just like the product.
 */
function modelPumpTurns(channelWork, perChannelMillis = 2, capacity = channelWork.length) {
  const remaining = channelWork.slice();
  let cursor = 0;
  const turns = [];
  while (remaining.some((work) => work > 0)) {
    let visited = 0;
    let elapsed = 0;
    const processed = [];
    let budgetYield = false;
    while (visited < capacity) {
      if (visited > 0 && elapsed >= MAX_TOTAL_MILLIS) {
        budgetYield = true;
        break;
      }
      const index = cursor;
      cursor = (cursor + 1) % capacity;
      visited++;
      if (remaining[index] > 0) {
        remaining[index]--;
        processed.push(index);
        elapsed += perChannelMillis;
      }
    }
    turns.push({
      visited,
      elapsed,
      processed,
      budgetYield,
      continuationHint: processed.length > 0 || budgetYield,
    });
    assert.ok(visited > 0, "model produced a zero-slot turn");
    if (turns.length > 10000) throw new Error("model did not drain");
  }
  return turns;
}

// Sixteen busy channels at the old 2 ms per-channel worst case must never run as one 32 ms turn.
// With a 4 ms aggregate budget, two channels are the maximum in a turn and all channels rotate.
const sixteenBusy = modelPumpTurns(new Array(16).fill(1), 2);
assert.equal(sixteenBusy.length, 8, "16-channel model did not drain in eight fair turns");
assert.ok(sixteenBusy.every((turn) => turn.elapsed <= MAX_TOTAL_MILLIS),
  "model exceeded the global aggregate budget");
assert.ok(sixteenBusy.every((turn) => turn.processed.length <= 2),
  "two-millisecond channels exceeded the 4 ms global turn bound");
assert.deepEqual(sixteenBusy.flatMap((turn) => turn.processed),
  [...Array(16).keys()],
  "round-robin cursor did not service every busy channel exactly once");
assert.ok(sixteenBusy.some((turn) => turn.budgetYield),
  "global budget exhaustion was not represented in the model");
assert.ok(sixteenBusy.every((turn) => turn.continuationHint),
  "every budget-shortened busy turn must advertise a continuation hint");

// A sparse ring can spend the first turn visiting idle channels before the ready channel. Model
// the tiny native lookup cost of those idle slots: the first turn may hit the aggregate budget
// with zero slices consumed, but the continuation hint must still make the ready channel retry.
function modelBudgetScan(channelWork, idleMillis, perChannelMillis = 2) {
  const remaining = channelWork.slice();
  let cursor = 0;
  const turns = [];
  while (remaining.some((work) => work > 0)) {
    let visited = 0;
    let elapsed = 0;
    const processed = [];
    let budgetYield = false;
    while (visited < remaining.length) {
      if (visited > 0 && elapsed >= MAX_TOTAL_MILLIS) {
        budgetYield = true;
        break;
      }
      const index = cursor;
      cursor = (cursor + 1) % remaining.length;
      visited++;
      if (remaining[index] > 0) {
        remaining[index]--;
        processed.push(index);
        elapsed += perChannelMillis;
      } else {
        elapsed += idleMillis;
      }
    }
    turns.push({
      visited,
      elapsed,
      processed,
      budgetYield,
      continuationHint: processed.length > 0 || budgetYield,
    });
    if (turns.length > 1000) throw new Error("budget model did not drain");
  }
  return turns;
}
const delayedReady = modelBudgetScan([0, 0, 0, 0, 0, 0, 0, 1], 1);
assert.equal(delayedReady[0].processed.length, 0,
  "delayed-ready model should exercise a budget yield before any slice is consumed");
assert.equal(delayedReady[0].budgetYield, true,
  "idle-slot scan did not hit the aggregate budget");
assert.equal(delayedReady[0].continuationHint, true,
  "budget hit without progress lost the continuation hint");
assert.ok(delayedReady.slice(1).some((turn) => turn.processed.includes(7)),
  "ready channel after idle slots was never serviced");

// Sustained work must continue rotating: no channel may wait for another channel's entire
// backlog. The largest service gap is bounded by one full 16-channel rotation.
const sustained = modelPumpTurns(new Array(16).fill(4), 2);
const serviceTurns = new Map();
sustained.forEach((turn, turnIndex) => {
  for (const channel of turn.processed) {
    const prior = serviceTurns.get(channel);
    if (prior !== undefined) {
      assert.ok(turnIndex - prior <= 8,
        `channel ${channel} waited more than one fair rotation`);
    }
    serviceTurns.set(channel, turnIndex);
  }
});
assert.equal(serviceTurns.size, 16, "sustained model starved one or more channels");

// Sparse holes and a backing-array growth must not make an active channel permanently invisible.
const sparse = modelPumpTurns(
  [0, 2, 0, 1, 0, 0, 3, 0],
  2,
);
const sparseProcessed = sparse.flatMap((turn) => turn.processed);
for (const index of [1, 3, 6]) {
  assert.ok(sparseProcessed.includes(index), `sparse channel ${index} was starved`);
}
assert.ok(sparse.every((turn) => turn.visited > 0 && turn.visited <= 8));

// A single non-preemptible handoff may itself exceed the aggregate budget. It is still processed
// once (forward progress), then the cursor moves on instead of spinning on the same slot.
const overBudgetSingle = modelPumpTurns([1, 1], 7, 2);
assert.equal(overBudgetSingle[0].processed.length, 1);
assert.equal(overBudgetSingle[0].processed[0], 0);
assert.equal(overBudgetSingle[1].processed[0], 1);

console.log(JSON.stringify({
  smoke: "browser-global-pump-fairness",
  maxTotalMillis: MAX_TOTAL_MILLIS,
  sixteenBusyTurns: sixteenBusy.length,
  sixteenBusyMaxProcessed: Math.max(...sixteenBusy.map((turn) => turn.processed.length)),
  sparseTurns: sparse.length,
  result: "pass",
}));
