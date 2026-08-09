"use strict";

const root = globalThis;
if (typeof Error === "function" && (!Error.stackTraceLimit || Error.stackTraceLimit < 100)) {
  Error.stackTraceLimit = 100;
}
const dbName = "gaius-fs-v1";
const storeName = "files";
const defaultWorldgenSliceMillis = 20;
const defaultDistanceRampIntervalMillis = 750;
const files = root.__gaiusPersistentFiles = Object.create(null);
let database;
let pendingWrites = Promise.resolve();
const pendingChanges = new Map();
const pendingMigrations = new Map();
let flushTimer;
let runtimeStarted = false;
let stopRequested = false;
let stopping = false;

function monotonicMillis() {
  return typeof performance !== "undefined" && performance.now
    ? performance.now()
    : Date.now();
}

function clampWorldgenSlice(value, fallback) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= 4 && parsed <= 50
    ? parsed
    : fallback;
}

function clampDistanceRampInterval(value, fallback) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= 100 && parsed <= 2000
    ? Math.round(parsed)
    : fallback;
}

function markStartup(phase, startedAt) {
  const elapsedMillis = Math.max(0, monotonicMillis() - startedAt);
  postMessage({
    type: "startup-timing",
    phase,
    elapsedMillis,
    at: Date.now(),
  });
}

root.onmessage = async (event) => {
  const message = event.data;
  if (message && message.type === "stop") {
    stopRequested = true;
    return;
  }
  if (!message || message.type !== "start") {
    return;
  }
  root.onmessage = handleControlMessage;
  try {
    const startupStarted = monotonicMillis();
    markStartup("start-received", startupStarted);
    const port = message.port || (event.ports && event.ports[0]);
    if (!(port instanceof MessagePort)) {
      throw new Error("Singleplayer worker did not receive its MessagePort");
    }
    root.__gaiusServerSessionId = String(message.sessionId || "");
    root.__gaiusServerWorldId = String(message.worldId || "");
    root.__gaiusServerSeed = String(message.seed || "");
    root.__gaiusServerViewDistance = clampDistance(message.renderDistance, 6);
    root.__gaiusServerSimulationDistance = clampDistance(message.simulationDistance, 4);
    const requestedWorldgenSlice = message.worldgenSliceMillis ??
      root.__gaiusWorldgenSliceMillis;
    root.__gaiusWorldgenSliceMillis = clampWorldgenSlice(
      requestedWorldgenSlice,
      defaultWorldgenSliceMillis,
    );
    const requestedDistanceRampInterval = message.distanceRampIntervalMillis ??
      root.__gaiusDistanceRampIntervalMillis;
    root.__gaiusDistanceRampIntervalMillis = clampDistanceRampInterval(
      requestedDistanceRampInterval,
      defaultDistanceRampIntervalMillis,
    );
    root.__gaiusBridgeUrl = message.bridgeUrl || undefined;
    root.__gaiusBridgeToken = message.bridgeToken || undefined;
    root.__gaiusLocalServerPorts = new Map([
      [root.__gaiusServerSessionId, port],
    ]);

    const storageStarted = monotonicMillis();
    const storageReady = installPersistentFileSystem().then(() => {
      markStartup("storage-ready", storageStarted);
    });
    const assetReady = prepareServerScript(message, startupStarted);
    const [script] = await Promise.all([assetReady, storageReady]);
    postMessage({
      type: "storage-ready",
      detail: Object.keys(files).length + " files",
    });

    const importStarted = monotonicMillis();
    importScripts(script.url);
    markStartup("runtime-imported", importStarted);
    if (script.temporaryUrl) {
      URL.revokeObjectURL(script.temporaryUrl);
    }
    if (typeof main !== "function") {
      throw new Error("Singleplayer TeaVM output did not expose main(args)");
    }
    root.__gaiusIntegratedServerNetworkSignal =
      typeof signalIntegratedServerNetworkInput === "function"
        ? signalIntegratedServerNetworkInput
        : undefined;
    // The generated helper owns a separate TeaVM continuation and only schedules a TickTask;
    // packet decoding remains on the real server thread.
    if (typeof root.__gaiusStartIntegratedServerPump !== "function") {
      throw new Error("Singleplayer server input dispatcher is unavailable");
    }
    postMessage({type: "runtime-ready", detail: root.__gaiusServerWorldId});
    markStartup("runtime-ready", startupStarted);
    main([]);
    runtimeStarted = true;
    // TeaVM returns here on the first cooperative suspension while Java main continues.
    markStartup("main-dispatched", startupStarted);
    if (stopRequested) {
      void stopServer();
    }
  } catch (error) {
    postMessage({
      type: "bootstrap-crash",
      detail: String(error && (error.stack || error.message) || error),
    });
    throw error;
  }
};

async function prepareServerScript(message, startupStarted) {
  if (message.serverScriptGzipUrl) {
    if (typeof DecompressionStream !== "function") {
      throw new Error("This browser cannot decompress the portable singleplayer server");
    }
    const response = await fetch(String(message.serverScriptGzipUrl));
    if (!response.ok || !response.body) {
      throw new Error("Portable singleplayer server asset could not be loaded");
    }
    markStartup("runtime-downloaded", startupStarted);
    const decompressed = response.body.pipeThrough(new DecompressionStream("gzip"));
    const scriptBlob = await new Response(decompressed).blob();
    const temporaryUrl = URL.createObjectURL(new Blob(
      [scriptBlob],
      {type: "text/javascript"},
    ));
    markStartup("runtime-decompressed", startupStarted);
    return {url: temporaryUrl, temporaryUrl};
  }
  if (message.serverScriptUrl) {
    return {url: String(message.serverScriptUrl), temporaryUrl: null};
  }
  const resolved = new URL("singleplayer-server.js", location.href);
  resolved.search = location.search;
  return {url: resolved.href, temporaryUrl: null};
}

function handleControlMessage(event) {
  const message = event.data;
  if (!message) {
    return;
  }
  if (message.type === "distances") {
    root.__gaiusServerViewDistance = clampDistance(message.renderDistance, 6);
    root.__gaiusServerSimulationDistance = clampDistance(message.simulationDistance, 4);
    if (typeof setIntegratedServerDistances === "function") {
      setIntegratedServerDistances(
        root.__gaiusServerViewDistance,
        root.__gaiusServerSimulationDistance,
      );
    }
    return;
  }
  if (message.type !== "stop") return;
  stopRequested = true;
  if (runtimeStarted) {
    void stopServer();
  }
}

function clampDistance(value, fallback) {
  const number = Number(value);
  return Math.max(2, Math.min(32, Number.isFinite(number) ? Math.floor(number) : fallback));
}

async function stopServer() {
  if (stopping) {
    return;
  }
  stopping = true;
  postMessage({type: "stopping", detail: root.__gaiusServerWorldId});
  if (typeof stopIntegratedServer === "function") {
    stopIntegratedServer();
  }
  const deadline = Date.now() + 30000;
  while (typeof isIntegratedServerStopped === "function" &&
      !isIntegratedServerStopped() && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  queuePendingMigrations();
  await flushPendingChanges();
  if (database) {
    database.close();
  }
  root.__gaiusIntegratedServerNetworkSignal = undefined;
  if (root.__gaiusChunkPriorityStats) {
    postMessage({
      type: "chunk-priority-stats",
      ...root.__gaiusChunkPriorityStats,
    });
  }
  postMessage({type: "stopped", detail: root.__gaiusServerWorldId});
  setTimeout(() => close(), 0);
}

async function installPersistentFileSystem() {
  database = await openDatabase();
  await readWorldFiles(root.__gaiusServerWorldId);
  root.__gaiusFsBackend = "indexeddb-worker";
  root.__gaiusFsPut = (path, value) => {
    path = normalize(path);
    value = String(value || "");
    files[path] = value;
    pendingChanges.set(path, value);
    scheduleFlush();
    return true;
  };
  root.__gaiusFsPutBytes = (path, value) => {
    path = normalize(path);
    const bytes = toUint8Array(value);
    if (!bytes) return false;
    const copy = bytes.slice();
    files[path] = copy;
    pendingMigrations.delete(path);
    pendingChanges.set(path, copy);
    scheduleFlush(250);
    return true;
  };
  root.__gaiusFsDelete = (path) => {
    path = normalize(path);
    delete files[path];
    pendingMigrations.delete(path);
    pendingChanges.set(path, null);
    scheduleFlush();
    return true;
  };
  root.__gaiusFsFlush = flushPendingChanges;
}

function openDatabase() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(dbName, 1);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(storeName)) {
        db.createObjectStore(storeName, {keyPath: "path"});
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error("IndexedDB open failed"));
    request.onblocked = () => reject(new Error("IndexedDB open blocked"));
  });
}

async function readWorldFiles(worldId) {
  const worldPrefix = "/gaius/saves/" + String(worldId || "") + "/";
  const stored = [];
  await new Promise((resolve, reject) => {
    const transaction = database.transaction(storeName, "readonly");
    const range = typeof IDBKeyRange !== "undefined"
      ? IDBKeyRange.bound(worldPrefix, worldPrefix + "\uffff")
      : undefined;
    const request = transaction.objectStore(storeName).openCursor(range);
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) {
        resolve();
        return;
      }
      const value = cursor.value;
      if (value && typeof value.path === "string") {
        const path = normalize(value.path);
        if (path.startsWith(worldPrefix)) {
          stored.push({path, value: value.value});
        }
      }
      cursor.continue();
    };
    request.onerror = () => reject(request.error || new Error("IndexedDB cursor failed"));
  });
  for (const entry of stored) {
    const value = await decodeStoredValue(entry.path, entry.value);
    if (value === undefined) continue;
    files[entry.path] = value;
    if (isRegionPath(entry.path) && typeof entry.value === "string") {
      // Migrate legacy Base64 regions on the next normal flush/clean shutdown.
      pendingMigrations.set(entry.path, value);
    }
  }
}

function scheduleFlush(delay = 25) {
  if (flushTimer !== undefined) {
    return;
  }
  flushTimer = setTimeout(() => {
    flushTimer = undefined;
    void flushPendingChanges();
  }, delay);
}

function flushPendingChanges() {
  if (flushTimer !== undefined) {
    clearTimeout(flushTimer);
    flushTimer = undefined;
  }
  if (pendingChanges.size === 0) {
    return pendingWrites;
  }
  const changes = new Map(pendingChanges);
  pendingChanges.clear();
  const write = () => writeBatch(changes);
  pendingWrites = pendingWrites.then(write, write).catch((error) => {
    postMessage({
      type: "storage-write-error",
      detail: String(error && (error.stack || error.message) || error),
    });
  });
  return pendingWrites;
}

function queuePendingMigrations() {
  for (const [path, value] of pendingMigrations) {
    if (!pendingChanges.has(path)) pendingChanges.set(path, value);
  }
  pendingMigrations.clear();
}

async function writeBatch(changes) {
  const prepared = [];
  for (const [path, value] of changes) {
    prepared.push([path, await encodeStoredValue(path, value)]);
  }
  const transaction = database.transaction(storeName, "readwrite");
  const store = transaction.objectStore(storeName);
  for (const [path, value] of prepared) {
    if (value === null) {
      store.delete(path);
    } else {
      store.put({path, value});
    }
  }
  await transactionDone(transaction);
}

function isRegionPath(path) {
  return String(path || "").endsWith(".mca");
}

function toUint8Array(value) {
  if (value instanceof Uint8Array) {
    return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
  }
  if (value instanceof ArrayBuffer) {
    return new Uint8Array(value);
  }
  if (ArrayBuffer.isView(value)) {
    return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
  }
  return null;
}

function decodeBase64(value) {
  if (typeof Uint8Array.fromBase64 === "function") {
    return Uint8Array.fromBase64(value);
  }
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

async function decodeStoredValue(path, value) {
  if (isRegionPath(path) && typeof value === "string") {
    return decodeBase64(value);
  }
  if (value && value.encoding === "gzip") {
    const compressed = toUint8Array(value.bytes);
    if (!compressed || typeof DecompressionStream !== "function") {
      throw new Error("Compressed region storage is unavailable");
    }
    const stream = new Blob([compressed]).stream()
      .pipeThrough(new DecompressionStream("gzip"));
    return new Uint8Array(await new Response(stream).arrayBuffer());
  }
  return value;
}

async function encodeStoredValue(path, value) {
  const bytes = toUint8Array(value);
  if (!isRegionPath(path) || !bytes || typeof CompressionStream !== "function") {
    return value;
  }
  const stream = new Blob([bytes]).stream().pipeThrough(new CompressionStream("gzip"));
  const compressed = new Uint8Array(await new Response(stream).arrayBuffer());
  if (compressed.byteLength >= bytes.byteLength) {
    return bytes.slice();
  }
  return {encoding: "gzip", bytes: compressed};
}

function transactionDone(transaction) {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error || new Error("IndexedDB transaction failed"));
    transaction.onabort = () => reject(transaction.error || new Error("IndexedDB transaction aborted"));
  });
}

function normalize(path) {
  path = String(path || "/").replace(/\\/g, "/");
  return path.startsWith("/") ? path : "/" + path;
}
