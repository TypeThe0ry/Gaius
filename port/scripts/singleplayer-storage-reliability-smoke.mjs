#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL(
  "../src/main/java/dev/gaius/browser/BrowserIntegratedServerMain.java",
  import.meta.url,
), "utf8");

assert.match(source, /STORAGE_FLUSH_ACK_TIMEOUT_MILLIS = 5000L/);
assert.match(source, /INDEXED_DB_FALLBACK_REHYDRATION_BUDGET_BYTES = 64 \* 1024 \* 1024/);
assert.match(source, /awaitIndexedDbFallbackHydration\(\);\s*BrowserFilePersistence\.mount\(\);/s);
assert.match(source, /serverThreadExited = true;[\s\S]*beginIntegratedServerStorageFlush\(\);/);
assert.match(source, /integratedServerStorageFlushPhase\(\)[\s\S]*STORAGE_FLUSH_ACK_TIMEOUT_MILLIS/);
assert.match(source, /completeIndexedDbFallbackHydration/);
assert.match(source, /__gaiusPersistentFiles = fallbackFiles/);
assert.match(source, /IndexedDB region rehydration exceeds bounded memory budget/);
assert.match(source, /storage-flush-ack/);

function extractJsBody(methodMarker) {
  const methodEnd = source.indexOf(methodMarker);
  assert.ok(methodEnd >= 0, "missing method marker: " + methodMarker);
  const annotationStart = source.lastIndexOf("@JSBody", methodEnd);
  const scriptStart = source.indexOf('"""', annotationStart) + 3;
  const scriptEnd = source.lastIndexOf('""")', methodEnd);
  assert.ok(annotationStart >= 0 && scriptStart > annotationStart && scriptEnd > scriptStart,
    "could not extract JSBody for " + methodMarker);
  return source.slice(scriptStart, scriptEnd);
}

function makeIndexedDb(records) {
  const database = {
    closed: false,
    close() { this.closed = true; },
    transaction() {
      const transaction = {};
      const store = {
        openCursor() {
          const request = {};
          const entries = Array.from(records.values());
          let index = 0;
          const advance = () => queueMicrotask(() => {
            if (index >= entries.length) {
              request.result = null;
            } else {
              const entry = entries[index++];
              request.result = {
                key: entry.path,
                value: entry,
                continue: advance,
              };
            }
            request.onsuccess?.();
          });
          advance();
          return request;
        },
      };
      transaction.objectStore = () => store;
      return transaction;
    },
  };
  return {
    open() {
      const request = {};
      queueMicrotask(() => {
        request.result = database;
        request.onsuccess?.();
      });
      return request;
    },
  };
}

function makeFiles(cached) {
  const target = Object.create(null);
  for (const [path, value] of cached) target[path] = value;
  return new Proxy(target, {
    ownKeys(value) {
      return [
        ...Reflect.ownKeys(value),
        "/gaius/saves/fallback-world/region/evicted.0.0.mca",
      ];
    },
    getOwnPropertyDescriptor(value, property) {
      return Reflect.getOwnPropertyDescriptor(value, property) ||
        (property === "/gaius/saves/fallback-world/region/evicted.0.0.mca"
          ? {configurable: true, enumerable: true}
          : undefined);
    },
  });
}

function createHydrationContext(records, cached) {
  const events = [];
  const files = makeFiles(cached);
  const context = {
    ArrayBuffer,
    Date,
    Error,
    Map,
    Math,
    Number,
    Object,
    Promise,
    Proxy,
    Reflect,
    String,
    Uint8Array,
    IDBKeyRange: {bound() { return {}; }},
    indexedDB: makeIndexedDb(records),
    __gaiusFsBackend: "indexeddb-worker-lru",
    __gaiusPersistentFiles: files,
    __gaiusServerWorldId: "fallback-world",
    __gaiusFsPutBytes(path, value) {
      return value instanceof Uint8Array && String(path).endsWith(".mca");
    },
    __gaiusFsDelete() {
      return true;
    },
    postMessage(message) { events.push(message); },
    queueMicrotask,
    setTimeout,
  };
  context.globalThis = context;
  context.completeIndexedDbFallbackHydration = (success, detail) => {
    events.push({type: "hydration-result", success, detail});
  };
  return {context, events};
}

const hydrationScript = extractJsBody(
  "private static native boolean beginIndexedDbFallbackHydration(",
);
assert.doesNotMatch(hydrationScript, /\blet\s+[A-Za-z_$]/,
  "TeaVM's JSBody parser does not accept let declarations");
assert.doesNotMatch(hydrationScript, /for\s*\(\s*const\s+\w+\s+of\s+/,
  "TeaVM's JSBody parser does not accept for-of loops");
assert.doesNotMatch(hydrationScript,
  /(?:^|[,\{])\s*(?:get|has|ownKeys|getOwnPropertyDescriptor)\s*\([^:]*\)\s*\{/m,
  "TeaVM's JSBody parser does not accept object method shorthand");
assert.doesNotMatch(hydrationScript, /\{\s*path\s*,\s*value\s*\}/,
  "TeaVM's JSBody parser does not accept property shorthand");
assert.doesNotMatch(hydrationScript, /,\s*detail\s*\}/,
  "TeaVM's JSBody parser does not accept property shorthand");

const cachedPath = "/gaius/saves/fallback-world/region/cached.0.0.mca";
const evictedPath = "/gaius/saves/fallback-world/region/evicted.0.0.mca";
const records = new Map([
  [cachedPath, {path: cachedPath, value: new Uint8Array([1, 2, 3])}],
  [evictedPath, {path: evictedPath, value: new Uint8Array([4, 5, 6, 7])}],
]);
const runtime = createHydrationContext(records, new Map([
  [cachedPath, new Uint8Array([1, 2, 3])],
]));
vm.runInNewContext(
  "globalThis.beginHydration = function(maxBytes, maxEntries) {" +
    hydrationScript + "\n};",
  runtime.context,
  {filename: "BrowserIntegratedServerMain.java:hydration"},
);
assert.equal(runtime.context.beginHydration(1024 * 1024, 16), true);
await new Promise((resolve) => setTimeout(resolve, 10));
assert.equal(runtime.events.find((event) => event.type === "hydration-result")?.success, true);
assert.deepEqual([...runtime.context.__gaiusPersistentFiles[evictedPath]], [4, 5, 6, 7]);
delete runtime.context.__gaiusPersistentFiles[cachedPath];
assert.deepEqual([...runtime.context.__gaiusPersistentFiles[cachedPath]], [1, 2, 3]);
const changedPath = "/gaius/saves/fallback-world/region/changed.0.0.mca";
const changed = new Uint8Array([8, 9]);
assert.equal(runtime.context.__gaiusFsPutBytes(changedPath, changed), true);
assert.deepEqual([...runtime.context.__gaiusPersistentFiles[changedPath]], [8, 9]);
assert.equal(runtime.context.__gaiusFsDelete(changedPath), true);
assert.equal(runtime.context.__gaiusPersistentFiles[changedPath], undefined);

const overBudget = new Map([
  [evictedPath, {path: evictedPath, value: new Uint8Array(32)}],
]);
const rejected = createHydrationContext(overBudget, new Map());
vm.runInNewContext(
  "globalThis.beginHydration = function(maxBytes, maxEntries) {" +
    hydrationScript + "\n};",
  rejected.context,
  {filename: "BrowserIntegratedServerMain.java:hydration-budget"},
);
assert.equal(rejected.context.beginHydration(8, 16), true);
await new Promise((resolve) => setTimeout(resolve, 10));
assert.equal(rejected.events.find((event) => event.type === "hydration-result")?.success, false);
assert.equal(rejected.context.__gaiusPersistentFiles[evictedPath], undefined);

const flushScript = extractJsBody(
  "private static native void beginIntegratedServerStorageFlush();",
);
let resolveFlush;
const flushEvents = [];
const flushContext = {
  Date,
  Promise,
  String,
  Number,
  globalThis: null,
  __gaiusServerWorldId: "flush-world",
  __gaiusFsFlush: () => new Promise((resolve) => { resolveFlush = resolve; }),
  postMessage(message) { flushEvents.push(message); },
};
flushContext.globalThis = flushContext;
vm.runInNewContext(
  "globalThis.beginFlush = function() {" + flushScript + "\n};",
  flushContext,
  {filename: "BrowserIntegratedServerMain.java:flush"},
);
flushContext.beginFlush();
assert.equal(flushContext.__gaiusIntegratedServerStorageFlush.phase, "pending");
resolveFlush();
await new Promise((resolve) => setTimeout(resolve, 0));
assert.equal(flushContext.__gaiusIntegratedServerStorageFlush.phase, "ack");
assert.equal(flushEvents.at(-1)?.type, "storage-flush-ack");

const expireScript = extractJsBody(
  "private static native void expireIntegratedServerStorageFlush();",
);
const timeoutEvents = [];
const timeoutContext = {
  globalThis: null,
  __gaiusIntegratedServerStorageFlush: {phase: "pending", startedAt: Date.now()},
  postMessage(message) { timeoutEvents.push(message); },
};
timeoutContext.globalThis = timeoutContext;
vm.runInNewContext(
  "globalThis.expireFlush = function() {" + expireScript + "\n};",
  timeoutContext,
  {filename: "BrowserIntegratedServerMain.java:flush-timeout"},
);
timeoutContext.expireFlush();
assert.equal(timeoutContext.__gaiusIntegratedServerStorageFlush.phase, "timeout");
assert.equal(timeoutEvents.at(-1)?.type, "storage-flush-timeout");

console.log(JSON.stringify({
  ok: true,
  boundedFallbackRehydration: true,
  evictedRegionReadable: true,
  overBudgetRejected: true,
  boundedFlushAck: true,
  boundedFlushTimeout: true,
}));
