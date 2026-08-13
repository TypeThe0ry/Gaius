import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const bootstrap = await readFile(
  new URL("../web/singleplayer/server-worker-bootstrap.js", import.meta.url),
  "utf8",
);

class MemoryFile {
  constructor() {
    this.bytes = new Uint8Array(0);
    this.size = 0;
    this.flushes = 0;
  }

  ensureCapacity(required) {
    if (required <= this.bytes.byteLength) return;
    let capacity = Math.max(1024, this.bytes.byteLength || 0);
    while (capacity < required) capacity *= 2;
    const replacement = new Uint8Array(capacity);
    replacement.set(this.bytes.subarray(0, this.size));
    this.bytes = replacement;
  }
}

class FakeSyncAccessHandle {
  constructor(file) {
    this.file = file;
    this.closed = false;
  }

  getSize() {
    return this.file.size;
  }

  read(output, options) {
    const at = Number(options?.at) || 0;
    const available = Math.max(0, Math.min(output.byteLength, this.file.size - at));
    for (let index = 0; index < available; index++) {
      output[index] = this.file.bytes[at + index];
    }
    return available;
  }

  write(input, options) {
    assert.equal(this.closed, false, "write used a closed OPFS handle");
    const at = Number(options?.at) || 0;
    this.file.ensureCapacity(at + input.byteLength);
    for (let index = 0; index < input.byteLength; index++) {
      this.file.bytes[at + index] = input[index];
    }
    this.file.size = Math.max(this.file.size, at + input.byteLength);
    return input.byteLength;
  }

  truncate(size) {
    this.file.size = Math.max(0, Number(size) || 0);
  }

  flush() {
    this.file.flushes++;
  }

  close() {
    this.closed = true;
  }
}

function createOpfs(files) {
  const directory = {
    async getDirectoryHandle() {
      return directory;
    },
    async getFileHandle(name) {
      if (!files.has(name)) files.set(name, new MemoryFile());
      const file = files.get(name);
      return {
        async createSyncAccessHandle() {
          return new FakeSyncAccessHandle(file);
        },
      };
    },
  };
  return directory;
}

function createIndexedDb(records) {
  const database = {
    objectStoreNames: {contains: () => true},
    close() {},
    transaction() {
      const transaction = {};
      const store = {
        openKeyCursor() {
          return cursorRequest(true);
        },
        openCursor() {
          return cursorRequest(false);
        },
        get(path) {
          const request = {};
          queueMicrotask(() => {
            request.result = records.get(path);
            request.onsuccess?.();
          });
          return request;
        },
        put(entry) {
          records.set(entry.path, entry);
        },
        delete(path) {
          records.delete(path);
        },
      };
      function cursorRequest(keyOnly) {
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
              value: keyOnly ? undefined : entry,
              continue: advance,
            };
          }
          request.onsuccess?.();
        });
        advance();
        return request;
      }
      transaction.objectStore = () => store;
      setTimeout(() => transaction.oncomplete?.(), 0);
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

function createRuntime({opfsFiles, idbRecords, budgetBytes = 64 * 1024}) {
  const events = [];
  const context = {
    Array,
    ArrayBuffer,
    Blob,
    DataView,
    Date,
    Error,
    Map,
    Math,
    Number,
    Object,
    Promise,
    Proxy,
    Reflect,
    Response,
    String,
    TextDecoder,
    TextEncoder,
    Uint8Array,
    URL,
    atob,
    clearTimeout,
    console,
    indexedDB: createIndexedDb(idbRecords),
    location: new URL("https://client.example/singleplayer-server-worker.js"),
    performance,
    postMessage(message) {
      events.push(message);
    },
    queueMicrotask,
    setTimeout,
    __gaiusRegionCacheBudgetBytes: budgetBytes,
    __gaiusStartIntegratedServerPump() {},
  };
  if (opfsFiles) {
    context.navigator = {
      storage: {
        async getDirectory() {
          return createOpfs(opfsFiles);
        },
      },
    };
  }
  context.globalThis = context;
  context.close = () => events.push({type: "harness-close"});
  context.importScripts = () => {};
  context.main = () => events.push({type: "harness-main"});
  vm.runInNewContext(bootstrap, context, {filename: "server-worker-bootstrap.js"});
  return {context, events};
}

function startRuntime(runtime, worldId = "region-cache-world") {
  const port = {
    close() {},
    postMessage() {},
  };
  return runtime.context.onmessage({
    data: {
      type: "start",
      sessionId: "7123456789abcdef0123456789abcdef",
      worldId,
      renderDistance: 6,
      simulationDistance: 4,
      serverScriptUrl: "https://client.example/singleplayer-server.js",
      port,
    },
    ports: [port],
  });
}

async function waitForEvent(events, type, timeoutMillis = 1000) {
  const deadline = Date.now() + timeoutMillis;
  while (!events.some((event) => event?.type === type)) {
    if (Date.now() >= deadline) throw new Error(`Timed out waiting for ${type}`);
    await new Promise((resolve) => setTimeout(resolve, 2));
  }
}

const opfsFiles = new Map();
const idbRecords = new Map();
const first = createRuntime({opfsFiles, idbRecords});
await startRuntime(first);
assert.equal(first.context.__gaiusFsBackend, "opfs-sync-worker");
assert.ok(
  first.events.findIndex((event) => event?.type === "harness-main") <
    first.events.findIndex((event) => event?.type === "runtime-ready"),
  "runtime-ready was emitted before main dispatch",
);

const fixtureCount = 512;
const fixtureBytes = 16 * 1024;
const externalFixtureCount = 256;
const externalFixtureBytes = 96 * 1024;
for (let index = 0; index < fixtureCount; index++) {
  const bytes = new Uint8Array(fixtureBytes);
  bytes[0] = index & 0xff;
  bytes[bytes.length - 1] = (index >>> 8) & 0xff;
  const path = `/gaius/saves/region-cache-world/region/r.${index}.0.mca`;
  assert.equal(first.context.__gaiusFsPutBytes(path, bytes), true);
}
for (let index = 0; index < externalFixtureCount; index++) {
  const bytes = new Uint8Array(externalFixtureBytes);
  bytes[0] = (index + 31) & 0xff;
  bytes[bytes.length - 1] = (index >>> 8) & 0xff;
  const path = `/gaius/saves/region-cache-world/region/c.${index}.0.mcc`;
  assert.equal(first.context.__gaiusFsPutBytes(path, bytes), true);
}
await first.context.__gaiusFsFlush();
assert.equal(
  Object.keys(first.context.__gaiusPersistentFiles)
    .filter((path) => path.endsWith(".mca") || path.endsWith(".mcc")).length,
  fixtureCount + externalFixtureCount,
  "OPFS chunk-storage index lost fixture entries",
);

for (let index = 0; index < fixtureCount; index++) {
  const path = `/gaius/saves/region-cache-world/region/r.${index}.0.mca`;
  const bytes = first.context.__gaiusPersistentFiles[path];
  assert.equal(bytes.byteLength, fixtureBytes);
  assert.equal(bytes[0], index & 0xff);
  assert.equal(bytes[bytes.length - 1], (index >>> 8) & 0xff);
}
for (let index = 0; index < externalFixtureCount; index++) {
  const path = `/gaius/saves/region-cache-world/region/c.${index}.0.mcc`;
  const bytes = first.context.__gaiusPersistentFiles[path];
  assert.equal(bytes.byteLength, externalFixtureBytes);
  assert.equal(bytes[0], (index + 31) & 0xff);
  assert.equal(bytes[bytes.length - 1], (index >>> 8) & 0xff);
}
const firstStats = first.context.__gaiusFsStorageSnapshot();
assert.ok(firstStats.cacheBytes <= firstStats.cacheBudgetBytes,
  "region cache exceeded its byte budget");
assert.ok(firstStats.cacheEntries <= 4, "region cache retained too many fixture entries");
assert.ok(firstStats.evictions >= fixtureCount - firstStats.cacheEntries,
  "large fixture did not exercise LRU eviction");
assert.equal(firstStats.regionEntries, fixtureCount + externalFixtureCount);

const temporaryPath =
  "/gaius/saves/region-cache-world/region/tmpabcdefghij.tmp";
const movedExternalPath =
  "/gaius/saves/region-cache-world/region/c.9000.0.mcc";
const movedBytes = new Uint8Array(128 * 1024);
movedBytes[0] = 0x4d;
movedBytes[movedBytes.length - 1] = 0x43;
assert.equal(first.context.__gaiusFsPutBytes(temporaryPath, movedBytes), true,
  "RegionFile temporary write was rejected");
await first.context.__gaiusFsFlush();
const temporaryBytes = first.context.__gaiusPersistentFiles[temporaryPath];
assert.equal(temporaryBytes.byteLength, movedBytes.byteLength,
  "closed RegionFile temporary write was not persisted as binary");
assert.equal(first.context.__gaiusFsPutBytes(movedExternalPath, temporaryBytes), true,
  "temporary payload could not be persisted under its final .mcc path");
assert.equal(first.context.__gaiusFsDelete(temporaryPath), true,
  "temporary path could not be removed after the durable .mcc write");
await first.context.__gaiusFsFlush();
assert.equal(temporaryPath in first.context.__gaiusPersistentFiles, false,
  "temporary path survived a completed .mcc move");

first.context.onmessage({data: {type: "stop"}, ports: []});
await waitForEvent(first.events, "stopped");
const container = Array.from(opfsFiles.values())[0];
assert.ok(container.flushes >= fixtureCount + externalFixtureCount + 1,
  "OPFS writes reported success before their sync handle was flushed");
container.ensureCapacity(container.size + 13);
container.bytes.fill(0xa5, container.size, container.size + 13);
container.size += 13;

const second = createRuntime({opfsFiles, idbRecords});
await startRuntime(second);
const restoredStats = second.context.__gaiusFsStorageSnapshot();
assert.equal(restoredStats.regionEntries, fixtureCount + externalFixtureCount + 1,
  "restart did not rebuild the complete OPFS region index");
assert.equal(restoredStats.cacheEntries, 0,
  "restart eagerly hydrated persisted OPFS regions");
assert.equal(restoredStats.cacheBytes, 0,
  "restart retained region payload bytes before first access");
assert.equal(restoredStats.cacheMisses, 0,
  "enumerating persisted region paths read their payloads");
assert.equal(restoredStats.recoveredTailBytes, 13,
  "restart did not discard an incomplete append-log tail");
for (let index = 0; index < externalFixtureCount; index++) {
  const path = `/gaius/saves/region-cache-world/region/c.${index}.0.mcc`;
  assert.equal(path in second.context.__gaiusPersistentFiles, true);
  assert.equal(Object.prototype.hasOwnProperty.call(
    second.context.__gaiusPersistentFiles, path), true);
}
const afterExistenceScan = second.context.__gaiusFsStorageSnapshot();
assert.equal(afterExistenceScan.cacheMisses, 0,
  "existence-only .mcc scans read OPFS payload bytes");
assert.equal(afterExistenceScan.cacheBytes, 0,
  "existence-only .mcc scans retained payload bytes");
for (const index of [0, 511, 1023]) {
  if (index >= fixtureCount) continue;
  const path = `/gaius/saves/region-cache-world/region/r.${index}.0.mca`;
  const bytes = second.context.__gaiusPersistentFiles[path];
  assert.equal(bytes[0], index & 0xff);
  assert.equal(bytes[bytes.length - 1], (index >>> 8) & 0xff);
}
for (const index of [0, 127, 255]) {
  const path = `/gaius/saves/region-cache-world/region/c.${index}.0.mcc`;
  const bytes = second.context.__gaiusPersistentFiles[path];
  assert.equal(bytes.byteLength, externalFixtureBytes);
  assert.equal(bytes[0], (index + 31) & 0xff);
}
const restoredMovedBytes = second.context.__gaiusPersistentFiles[movedExternalPath];
assert.equal(restoredMovedBytes.byteLength, movedBytes.byteLength,
  "restart lost the committed external chunk");
assert.equal(restoredMovedBytes[0], 0x4d);
assert.equal(restoredMovedBytes[restoredMovedBytes.length - 1], 0x43);
assert.equal(temporaryPath in second.context.__gaiusPersistentFiles, false,
  "restart resurrected the moved temporary file");
const afterOversizedReads = second.context.__gaiusFsStorageSnapshot();
assert.ok(afterOversizedReads.cacheBytes <= afterOversizedReads.cacheBudgetBytes,
  "oversized .mcc reads exceeded the materialization cache budget");
second.context.onmessage({data: {type: "stop"}, ports: []});
await waitForEvent(second.events, "stopped");

const fallbackRecords = new Map();
for (let index = 0; index < 4; index++) {
  const extension = index % 2 === 0 ? "mca" : "mcc";
  const prefix = extension === "mca" ? "r" : "c";
  const path = `/gaius/saves/fallback-world/region/${prefix}.${index}.0.${extension}`;
  fallbackRecords.set(path, {path, value: new Uint8Array(96 * 1024)});
}
const fallback = createRuntime({opfsFiles: null, idbRecords: fallbackRecords});
await assert.rejects(startRuntime(fallback, "fallback-world"), /cache budget/);
assert.equal(fallbackRecords.size, 4,
  "over-budget IndexedDB fallback silently deleted durable regions");
assert.equal(fallback.events.some((event) => event?.type === "runtime-ready"), false,
  "over-budget fallback started with an incomplete world");

console.log(JSON.stringify({
  ok: true,
  fixtureRegions: fixtureCount,
  fixtureExternalChunks: externalFixtureCount,
  fixtureBytes: fixtureCount * fixtureBytes +
    externalFixtureCount * externalFixtureBytes,
  cacheBudgetBytes: firstStats.cacheBudgetBytes,
  cachePeakBytes: firstStats.cachePeakBytes,
  evictions: firstStats.evictions,
  restartRecoveredEntries: restoredStats.regionEntries,
  restartHydratedEntries: restoredStats.cacheEntries,
  existenceScanHydratedBytes: afterExistenceScan.cacheBytes,
  movedExternalBytes: restoredMovedBytes.byteLength,
  recoveredTailBytes: restoredStats.recoveredTailBytes,
  fallbackPreservedEntries: fallbackRecords.size,
}));
