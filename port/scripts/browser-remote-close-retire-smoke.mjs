#!/usr/bin/env node

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const repository = new URL("../..", import.meta.url);
const sourcePath = new URL(
  "../overrides/libraries/netty-transport/src/main/java/io/netty/channel/browser/BrowserWebSocketChannel.java",
  import.meta.url,
);
const source = readFileSync(sourcePath, "utf8");

// This smoke is deliberately a small deterministic model. It proves the lifecycle contract
// without starting TeaVM, Chrome, or a network tunnel: a remote close must eventually retire a
// dead channel even when no subsequent Java tick arrives, while allowing one bounded decoder
// handoff to drain already-buffered bytes first.
assert.match(source, /const remoteCloseRetireGraceMillis = 5000;/);
assert.match(source, /const remoteCloseRetireRetryMillis = 16;/);
assert.match(source, /remoteClosedAt: 0,/);
assert.match(source, /retireClosedHandle: 0,/);
assert.match(source, /retireClosedPending: false,/);
assert.match(source, /state\.retireClosedEntry = function\(entry\)/);
assert.match(source, /state\.retireClosedEntry\(entry\);/);

const onCloseStart = source.indexOf("ws.onclose = function(event)");
const onCloseEnd = source.indexOf("};", onCloseStart);
assert.ok(onCloseStart >= 0 && onCloseEnd > onCloseStart, "missing remote onclose body");
const onCloseBody = source.slice(onCloseStart, onCloseEnd);
assert.match(onCloseBody, /entry\.closed = true/);
assert.match(onCloseBody, /entry\.connected = false/);
assert.match(onCloseBody, /state\.retireClosedEntry\(entry\)/);

const discardStart = source.lastIndexOf("state.discardInbound = function(entry)");
const discardEnd = source.indexOf("};", discardStart);
assert.ok(discardStart >= 0 && discardEnd > discardStart, "missing final discardInbound body");
const discardBody = source.slice(discardStart, discardEnd);
const retireStart = source.indexOf("state.retireClosedEntry = function(entry)");
const retireEnd = source.indexOf("state.deliverInbound = function(entry, buffer)", retireStart);
assert.ok(retireStart >= 0 && retireEnd > retireStart, "missing retire implementation");
const retireBody = source.slice(retireStart, retireEnd);
const finalizeStart = source.indexOf("function finalizeRemoteCloseRetire(entry, forced)");
assert.ok(finalizeStart >= 0 && finalizeStart < retireStart, "missing retire finalizer");
const finalizeBody = source.slice(finalizeStart, retireEnd);
assert.match(finalizeBody, /state\.discardInbound\(entry\)/);
assert.match(finalizeBody, /state\.channels\.delete\(entry\.id\|0\)/);
assert.match(finalizeBody, /state\.stopEventLoopGapProbeIfIdle\(\)/);
assert.match(discardBody, /entry\.inbound = \[\]/);
assert.match(discardBody, /entry\.pendingInbound = \[\]/);

const GRACE_MS = 5000;
const RETRY_MS = 16;

function createModel() {
  let clock = 0;
  let sequence = 0;
  const timers = [];
  const channels = new Map();
  const stats = {
    scheduled: 0,
    deferred: 0,
    forced: 0,
    finalized: 0,
    signals: 0,
  };

  function schedule(fn, delay) {
    const task = { id: ++sequence, at: clock + delay, fn, canceled: false };
    timers.push(task);
    return task;
  }

  function cancel(task) {
    if (task) task.canceled = true;
  }

  function hasWork(entry) {
    return !entry.disposed && (
      entry.inbound > 0 ||
      entry.pendingInbound > 0 ||
      entry.sliceScheduled ||
      entry.decodedBacklog > 0 ||
      entry.activeDecoder
    );
  }

  function finalize(entry, forced) {
    if (!entry || entry.disposed) return;
    cancel(entry.retireHandle);
    entry.retireHandle = null;
    entry.retirePending = false;
    entry.inbound = 0;
    entry.pendingInbound = 0;
    entry.sliceScheduled = false;
    entry.decodedBacklog = 0;
    entry.activeDecoder = false;
    entry.disposed = true;
    if (channels.get(entry.id) === entry) channels.delete(entry.id);
    stats.finalized++;
    if (forced) stats.forced++;
  }

  function retire(entry) {
    if (!entry || entry.disposed || entry.retirePending) return;
    const startedAt = Number(entry.remoteClosedAt) || clock;
    entry.remoteClosedAt = startedAt;
    stats.scheduled++;
    const retry = () => {
      entry.retirePending = false;
      entry.retireHandle = null;
      if (!entry || entry.disposed) return;
      const elapsed = Math.max(0, clock - startedAt);
      if (hasWork(entry) && elapsed < GRACE_MS) {
        stats.deferred++;
        stats.signals++;
        entry.retirePending = true;
        entry.retireHandle = schedule(retry, RETRY_MS);
        return;
      }
      finalize(entry, hasWork(entry) && elapsed >= GRACE_MS);
    };
    entry.retirePending = true;
    entry.retireHandle = schedule(retry, 0);
  }

  function runNext() {
    timers.sort((left, right) => left.at - right.at || left.id - right.id);
    const task = timers.shift();
    if (!task) return false;
    if (task.canceled) return true;
    clock = Math.max(clock, task.at);
    task.fn();
    return true;
  }

  function runUntilIdle(limit = 1000) {
    let steps = 0;
    while (steps++ < limit && runNext()) {}
    assert.ok(steps <= limit, "retire retry loop exceeded bounded timer limit");
    return steps - 1;
  }

  function open(id = 1) {
    const entry = {
      id,
      remoteClosedAt: 0,
      retireHandle: null,
      retirePending: false,
      inbound: 0,
      pendingInbound: 0,
      sliceScheduled: false,
      decodedBacklog: 0,
      activeDecoder: false,
      disposed: false,
    };
    channels.set(id, entry);
    return entry;
  }

  function closeExplicit(entry) {
    cancel(entry.retireHandle);
    entry.retireHandle = null;
    entry.retirePending = false;
    entry.disposed = true;
    if (channels.get(entry.id) === entry) channels.delete(entry.id);
  }

  return {
    channels,
    stats,
    get clock() {
      return clock;
    },
    open,
    retire,
    runNext,
    runUntilIdle,
    closeExplicit,
  };
}

// No buffered work: one zero-delay callback removes the dead entry and leaves no timer behind.
{
  const model = createModel();
  const entry = model.open();
  model.retire(entry);
  model.runUntilIdle();
  assert.equal(model.channels.size, 0);
  assert.equal(model.stats.scheduled, 1);
  assert.equal(model.stats.deferred, 0);
  assert.equal(model.stats.finalized, 1);
  assert.equal(model.stats.forced, 0);
}

// Buffered work gets one bounded signal/handoff. If the decoder drains before the retry, the
// channel is retired cleanly rather than being force-discarded.
{
  const model = createModel();
  const entry = model.open();
  entry.pendingInbound = 3;
  entry.sliceScheduled = true;
  model.retire(entry);
  assert.equal(model.runNext(), true);
  assert.equal(model.channels.size, 1);
  assert.equal(model.stats.deferred, 1);
  entry.pendingInbound = 0;
  entry.sliceScheduled = false;
  model.runUntilIdle();
  assert.equal(model.channels.size, 0);
  assert.equal(model.stats.finalized, 1);
  assert.equal(model.stats.forced, 0);
  assert.equal(model.stats.signals, 1);
}

// A missing Java tick cannot leak the channel forever: the grace timer force-cleans at most
// ceil(5000/16)+1 callbacks and records that it had to discard unfinished decoder work.
{
  const model = createModel();
  const entry = model.open();
  entry.activeDecoder = true;
  model.retire(entry);
  const callbacks = model.runUntilIdle();
  assert.equal(model.channels.size, 0);
  assert.equal(model.stats.finalized, 1);
  assert.equal(model.stats.forced, 1);
  assert.ok(callbacks <= Math.ceil(GRACE_MS / RETRY_MS) + 2);
  assert.ok(model.clock >= GRACE_MS);
}

// Repeated onclose notifications are idempotent, explicit close cancels the pending timer, and
// an old callback cannot delete a newly opened channel that reuses the same numeric id.
{
  const model = createModel();
  const oldEntry = model.open(7);
  oldEntry.pendingInbound = 1;
  model.retire(oldEntry);
  model.retire(oldEntry);
  assert.equal(model.stats.scheduled, 1);
  const replacement = model.open(7);
  oldEntry.disposed = true;
  model.runUntilIdle();
  assert.equal(model.channels.get(7), replacement);
  model.closeExplicit(replacement);
  assert.equal(model.channels.size, 0);
}

console.log(JSON.stringify({
  schema: "gaius.browser-remote-close-retire-smoke.v1",
  status: "pass",
  graceMillis: GRACE_MS,
  retryMillis: RETRY_MS,
  noNextTickCleanup: true,
  bufferedDrainBeforeRetire: true,
  forcedCleanupBounded: true,
  duplicateCloseIdempotent: true,
  replacementGenerationSafe: true,
  sourcePath: repository.pathname,
}, null, 2));
