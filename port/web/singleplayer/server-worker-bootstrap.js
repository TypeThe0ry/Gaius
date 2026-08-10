"use strict";

const root = globalThis;
if (typeof Error === "function" && (!Error.stackTraceLimit || Error.stackTraceLimit < 100)) {
  Error.stackTraceLimit = 100;
}
const dbName = "gaius-fs-v1";
const storeName = "files";
const defaultWorldgenSliceMillis = 20;
const defaultDistanceRampIntervalMillis = 750;
const defaultRegionCacheBudgetBytes = 32 * 1024 * 1024;
const minimumRegionCacheBudgetBytes = 64 * 1024;
const maximumRegionCacheBudgetBytes = 256 * 1024 * 1024;
const opfsRecordMagic = 0x47525331;
const opfsRecordVersion = 1;
const opfsRecordHeaderBytes = 24;
const opfsRecordLive = 1;
const opfsRecordDeleted = 2;
const fileValues = Object.create(null);
const regionIndex = new Map();
const regionCache = new Map();
const regionCacheBudgetBytes = clampRegionCacheBudget(
  root.__gaiusRegionCacheBudgetBytes,
);
const storageStats = root.__gaiusStorageStats = {
  backend: "initializing",
  cacheBudgetBytes: regionCacheBudgetBytes,
  cacheBytes: 0,
  cachePeakBytes: 0,
  cacheEntries: 0,
  regionEntries: 0,
  dirtyEntries: 0,
  pinnedEntries: 0,
  flushingEntries: 0,
  evictions: 0,
  cacheHits: 0,
  cacheMisses: 0,
  rejectedWrites: 0,
  writeErrors: 0,
  migratedRegions: 0,
  opfsFileBytes: 0,
};
const files = root.__gaiusPersistentFiles = createPersistentFileProxy();
let database;
const pendingChanges = new Map();
let regionCacheBytes = 0;
let regionCacheSequence = 0;
let flushInFlight;
let flushTimer;
let opfsAccessHandle;
let opfsAppendOffset = 0;
let opfsDirty = false;
let persistentStorageClosed = false;
let runtimeStarted = false;
let stopRequested = false;
let stopping = false;
let startAccepted = false;
let activeSessionId = "";
let startupPort;
let pendingPortResolve;
let pendingPortReject;
let pendingPortTimer;

function clampRegionCacheBudget(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    return defaultRegionCacheBudgetBytes;
  }
  return Math.max(
    minimumRegionCacheBudgetBytes,
    Math.min(maximumRegionCacheBudgetBytes, Math.floor(parsed)),
  );
}

function createPersistentFileProxy() {
  if (typeof Proxy !== "function") {
    return fileValues;
  }
  return new Proxy(fileValues, {
    get(target, property, receiver) {
      if (typeof property === "string" && regionIndex.has(property)) {
        return readCachedRegion(property);
      }
      return Reflect.get(target, property, receiver);
    },
    has(target, property) {
      return typeof property === "string" && regionIndex.has(property)
        ? true
        : Reflect.has(target, property);
    },
    ownKeys(target) {
      const keys = Reflect.ownKeys(target);
      for (const path of regionIndex.keys()) {
        if (!Object.prototype.hasOwnProperty.call(target, path)) {
          keys.push(path);
        }
      }
      return keys;
    },
    getOwnPropertyDescriptor(target, property) {
      if (typeof property === "string" && regionIndex.has(property) &&
          !Object.prototype.hasOwnProperty.call(target, property)) {
        return {configurable: true, enumerable: true};
      }
      return Reflect.getOwnPropertyDescriptor(target, property);
    },
  });
}

function monotonicMillis() {
  return typeof performance !== "undefined" && performance.now
    ? performance.now()
    : Date.now();
}

function snapshotScalarTelemetry(value) {
  const snapshot = Object.create(null);
  if (!value || typeof value !== "object") {
    return snapshot;
  }
  let copied = 0;
  for (const key of Object.keys(value)) {
    if (copied >= 64) {
      break;
    }
    const current = value[key];
    if (typeof current === "number") {
      if (Number.isFinite(current)) {
        snapshot[key] = current;
        copied++;
      }
    } else if (typeof current === "boolean" || typeof current === "string" || current === null) {
      snapshot[key] = current;
      copied++;
    }
  }
  return snapshot;
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
  if (startAccepted) {
    closeTransferredPort(message, event);
    postMessage({type: "start-duplicate-ignored", detail: activeSessionId});
    return;
  }
  startAccepted = true;
  activeSessionId = String(message.sessionId || "");
  root.onmessage = handleControlMessage;
  try {
    const startupStarted = monotonicMillis();
    markStartup("start-received", startupStarted);
    const port = await waitForStartPort(message, event);
    startupPort = port;
    root.__gaiusServerSessionId = activeSessionId;
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
    registerLocalPort(root.__gaiusServerSessionId, port);

    const storageStarted = monotonicMillis();
    const storageReady = installPersistentFileSystem().then(() => {
      markStartup("storage-ready", storageStarted);
    });
    const assetReady = prepareServerScript(message, startupStarted);
    const [script] = await Promise.all([assetReady, storageReady]);
    if (stopRequested) {
      throw new Error("Singleplayer launch was cancelled before runtime import");
    }
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
    if (stopRequested) {
      throw new Error("Singleplayer launch was cancelled before main dispatch");
    }
    main([]);
    runtimeStarted = true;
    // TeaVM returns here on the first cooperative suspension while Java main continues.
    releaseStartupRegionPins();
    markStartup("main-dispatched", startupStarted);
    postMessage({type: "runtime-ready", detail: root.__gaiusServerWorldId});
    markStartup("runtime-ready", startupStarted);
    if (stopRequested) {
      void stopServer();
    }
  } catch (error) {
    await closePersistentStorage(false);
    releaseLocalSession("Singleplayer Worker bootstrap failed");
    if (stopRequested) {
      postMessage({type: "stopped", detail: root.__gaiusServerWorldId || ""});
      setTimeout(() => close(), 0);
      return;
    }
    postMessage({
      type: "bootstrap-crash",
      detail: String(error && (error.stack || error.message) || error),
    });
    throw error;
  }
};

function isMessagePort(value) {
  return value && typeof value.postMessage === "function" &&
    typeof value.close === "function";
}

function transferredPort(message, event) {
  const direct = message && message.port;
  if (isMessagePort(direct)) return direct;
  const transferred = event && event.ports && event.ports[0];
  return isMessagePort(transferred) ? transferred : undefined;
}

function closeTransferredPort(message, event) {
  const port = transferredPort(message, event);
  if (!port) return;
  try { port.postMessage({type: "close"}); } catch (ignored) {}
  try { port.close(); } catch (ignored) {}
}

function waitForStartPort(message, event) {
  const port = transferredPort(message, event);
  if (port) return Promise.resolve(port);
  postMessage({type: "port-waiting", detail: activeSessionId});
  return new Promise((resolve, reject) => {
    pendingPortResolve = (latePort) => {
      clearTimeout(pendingPortTimer);
      pendingPortTimer = undefined;
      pendingPortResolve = undefined;
      pendingPortReject = undefined;
      resolve(latePort);
    };
    pendingPortReject = reject;
    pendingPortTimer = setTimeout(() => {
      pendingPortTimer = undefined;
      pendingPortResolve = undefined;
      pendingPortReject = undefined;
      reject(new Error(
        "Singleplayer worker MessagePort did not arrive within 5000 ms for " +
          activeSessionId
      ));
    }, 5000);
  });
}

function registerLocalPort(sessionId, port) {
  const bridge = root.__gaiusNettyBridge;
  if (bridge && typeof bridge.registerLocalPort === "function") {
    bridge.registerLocalPort(sessionId, port);
    return;
  }
  const ports = root.__gaiusLocalServerPorts ||
    (root.__gaiusLocalServerPorts = new Map());
  const previous = typeof ports.get === "function" ? ports.get(sessionId) : ports[sessionId];
  if (previous && previous !== port) {
    try { previous.postMessage({type: "close"}); } catch (ignored) {}
    try { previous.close(); } catch (ignored) {}
  }
  if (typeof ports.set === "function") ports.set(sessionId, port);
  else ports[sessionId] = port;
}

function releaseLocalSession(reason) {
  if (pendingPortTimer !== undefined) {
    clearTimeout(pendingPortTimer);
    pendingPortTimer = undefined;
  }
  pendingPortResolve = undefined;
  pendingPortReject = undefined;
  const sessionId = String(root.__gaiusServerSessionId || activeSessionId || "");
  const bridge = root.__gaiusNettyBridge;
  if (bridge && typeof bridge.failLocalSession === "function" && sessionId) {
    bridge.failLocalSession(sessionId, reason);
  }
  const ports = root.__gaiusLocalServerPorts;
  const pendingPort = ports && typeof ports.get === "function"
    ? ports.get(sessionId)
    : ports && ports[sessionId];
  if (pendingPort) {
    try { pendingPort.postMessage({type: "close"}); } catch (ignored) {}
    try { pendingPort.close(); } catch (ignored) {}
    if (typeof ports.delete === "function") ports.delete(sessionId);
    else delete ports[sessionId];
  }
  if (startupPort && startupPort !== pendingPort) {
    try { startupPort.close(); } catch (ignored) {}
  }
  startupPort = undefined;
}

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
  if (message.type === "telemetry-ping") {
    const receivedAtEpoch = Date.now();
    refreshStorageStats();
    postMessage({
      type: "telemetry-pong",
      sessionId: String(message.sessionId || activeSessionId || ""),
      sequence: Number(message.sequence) || 0,
      receivedAtEpoch,
      sentAtEpoch: Date.now(),
      chunkPriority: snapshotScalarTelemetry(root.__gaiusChunkPriorityStats),
      network: snapshotScalarTelemetry(root.__gaiusNetworkStats),
      worldgen: snapshotScalarTelemetry(root.__gaiusWorldgenStats),
      storage: snapshotScalarTelemetry(storageStats),
    });
    return;
  }
  if (message.type === "start" || message.type === "attach-port") {
    const sessionId = String(message.sessionId || activeSessionId || "");
    const port = transferredPort(message, event);
    if (pendingPortResolve && port && sessionId === activeSessionId) {
      const resolve = pendingPortResolve;
      postMessage({type: "port-attached", detail: activeSessionId});
      resolve(port);
    } else {
      closeTransferredPort(message, event);
      postMessage({type: "start-duplicate-ignored", detail: activeSessionId});
    }
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
  if (!runtimeStarted && pendingPortReject) {
    const reject = pendingPortReject;
    pendingPortReject = undefined;
    reject(new Error("Singleplayer launch was cancelled before MessagePort attachment"));
    return;
  }
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
  const deadline = Date.now() + 20000;
  while (typeof isIntegratedServerStopped === "function" &&
      !isIntegratedServerStopped() && Date.now() < deadline) {
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  if (typeof isIntegratedServerStopped === "function" && !isIntegratedServerStopped()) {
    postMessage({
      type: "server-stop-timeout",
      detail: "Integrated server did not stop within 20000 ms",
    });
  }
  postMessage({type: "storage-flushing", detail: root.__gaiusServerWorldId});
  try {
    await withTimeout(flushPendingChanges(), 10000, "Persistent storage flush timed out");
  } catch (error) {
    reportStorageError(error);
  } finally {
    await closePersistentStorage(false);
  }
  root.__gaiusIntegratedServerNetworkSignal = undefined;
  releaseLocalSession("Singleplayer Worker stopped");
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
  const opfsReady = await openOpfsRegionStore(root.__gaiusServerWorldId);
  await readWorldFiles(root.__gaiusServerWorldId);
  root.__gaiusFsBackend = opfsReady
    ? "opfs-sync-worker"
    : "indexeddb-worker-lru";
  storageStats.backend = root.__gaiusFsBackend;
  root.__gaiusFsPut = (path, value) => {
    path = normalize(path);
    value = String(value || "");
    fileValues[path] = value;
    queueIndexedDbChange(path, value, false);
    scheduleFlush();
    return true;
  };
  root.__gaiusFsPutBytes = (path, value) => {
    path = normalize(path);
    const bytes = toUint8Array(value);
    if (!bytes) return false;
    if (isRegionPath(path) && opfsAccessHandle) {
      try {
        appendOpfsRegion(path, bytes, false);
        removeRegionCache(path);
        scheduleFlush(250);
        return true;
      } catch (error) {
        storageStats.writeErrors++;
        reportStorageError(error);
        return false;
      }
    }
    const copy = bytes.slice();
    if (isRegionPath(path)) {
      const version = nextChangeVersion();
      if (!putRegionCache(path, copy, {
        dirty: true,
        pinned: false,
        flushing: false,
        version,
      })) {
        storageStats.rejectedWrites++;
        refreshStorageStats();
        postMessage({
          type: "storage-write-error",
          detail: "IndexedDB region cache budget exhausted for " + path,
        });
        return false;
      }
      regionIndex.set(path, {backend: "indexeddb", length: copy.byteLength});
      pendingChanges.set(path, {value: copy, version, region: true});
    } else {
      fileValues[path] = copy;
      queueIndexedDbChange(path, copy, false);
    }
    scheduleFlush(250);
    return true;
  };
  root.__gaiusFsDelete = (path) => {
    path = normalize(path);
    if (isRegionPath(path)) {
      if (opfsAccessHandle && regionIndex.has(path)) {
        try {
          appendOpfsRegion(path, null, true);
        } catch (error) {
          storageStats.writeErrors++;
          reportStorageError(error);
          return false;
        }
      }
      regionIndex.delete(path);
      removeRegionCache(path);
    } else {
      delete fileValues[path];
    }
    queueIndexedDbChange(path, null, isRegionPath(path));
    scheduleFlush();
    return true;
  };
  root.__gaiusFsFlush = flushPendingChanges;
  root.__gaiusFsStorageSnapshot = () => ({...refreshStorageStats()});
  refreshStorageStats();
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
    request.onsuccess = () => {
      const opened = request.result;
      opened.onversionchange = () => opened.close();
      resolve(opened);
    };
    request.onerror = () => reject(request.error || new Error("IndexedDB open failed"));
    request.onblocked = () => reject(new Error("IndexedDB open blocked"));
  });
}

async function openOpfsRegionStore(worldId) {
  if (typeof Proxy !== "function" || typeof navigator === "undefined" ||
      !navigator.storage || typeof navigator.storage.getDirectory !== "function") {
    return false;
  }
  try {
    const storageRoot = await navigator.storage.getDirectory();
    const gaiusDirectory = await storageRoot.getDirectoryHandle("gaius", {create: true});
    const regionDirectory = await gaiusDirectory.getDirectoryHandle("regions", {create: true});
    const fileHandle = await regionDirectory.getFileHandle(
      opfsContainerName(worldId),
      {create: true},
    );
    if (typeof fileHandle.createSyncAccessHandle !== "function") {
      return false;
    }
    opfsAccessHandle = await fileHandle.createSyncAccessHandle();
    scanOpfsRegionStore();
    return true;
  } catch (error) {
    if (opfsAccessHandle) {
      try { opfsAccessHandle.close(); } catch (ignored) {}
    }
    opfsAccessHandle = undefined;
    postMessage({
      type: "storage-opfs-fallback",
      detail: String(error && (error.stack || error.message) || error),
    });
    return false;
  }
}

function opfsContainerName(worldId) {
  const value = String(worldId || "world");
  let hash = 0x811c9dc5;
  const bytes = new TextEncoder().encode(value);
  for (let index = 0; index < bytes.length; index++) {
    hash ^= bytes[index];
    hash = Math.imul(hash, 0x01000193);
  }
  const suffix = value.replace(/[^A-Za-z0-9._-]/g, "_").slice(0, 48) || "world";
  return suffix + "-" + (hash >>> 0).toString(16).padStart(8, "0") + ".regions";
}

function scanOpfsRegionStore() {
  const size = Number(opfsAccessHandle.getSize()) || 0;
  const header = new Uint8Array(opfsRecordHeaderBytes);
  const decoder = new TextDecoder();
  const worldPrefix = activeWorldPrefix();
  let offset = 0;
  while (offset + opfsRecordHeaderBytes <= size) {
    if (readSync(opfsAccessHandle, header, offset) !== header.byteLength) break;
    const view = new DataView(header.buffer, header.byteOffset, header.byteLength);
    const magic = view.getUint32(0, true);
    const version = view.getUint32(4, true);
    const state = view.getUint32(8, true);
    const pathLength = view.getUint32(12, true);
    const dataLength = view.getUint32(16, true);
    const recordLength = view.getUint32(20, true);
    if (magic !== opfsRecordMagic || version !== opfsRecordVersion ||
        (state !== opfsRecordLive && state !== opfsRecordDeleted) ||
        pathLength === 0 || pathLength > 4096 ||
        recordLength !== opfsRecordHeaderBytes + pathLength + dataLength ||
        offset + recordLength > size) {
      break;
    }
    const pathBytes = new Uint8Array(pathLength);
    if (readSync(opfsAccessHandle, pathBytes, offset + opfsRecordHeaderBytes) !== pathLength) {
      break;
    }
    const path = normalize(decoder.decode(pathBytes));
    if (path.startsWith(worldPrefix) && isRegionPath(path)) {
      if (state === opfsRecordDeleted) {
        regionIndex.delete(path);
      } else {
        regionIndex.set(path, {
          backend: "opfs",
          offset: offset + opfsRecordHeaderBytes + pathLength,
          length: dataLength,
        });
      }
    }
    offset += recordLength;
  }
  if (offset < size) {
    try {
      opfsAccessHandle.truncate(offset);
      storageStats.recoveredTailBytes = size - offset;
    } catch (error) {
      storageStats.writeErrors++;
      reportStorageError(error);
      offset = size;
    }
  }
  opfsAppendOffset = offset;
  storageStats.opfsFileBytes = offset;
  refreshStorageStats();
}

function appendOpfsRegion(path, value, deleted) {
  if (!opfsAccessHandle) {
    throw new Error("OPFS region handle is unavailable");
  }
  const pathBytes = new TextEncoder().encode(path);
  const bytes = deleted ? new Uint8Array(0) : toUint8Array(value);
  if (!bytes) {
    throw new Error("Region payload is not a byte array: " + path);
  }
  const recordLength = opfsRecordHeaderBytes + pathBytes.byteLength + bytes.byteLength;
  const offset = opfsAppendOffset;
  writeSync(opfsAccessHandle, new Uint8Array(opfsRecordHeaderBytes), offset);
  writeSync(opfsAccessHandle, pathBytes, offset + opfsRecordHeaderBytes);
  if (bytes.byteLength > 0) {
    writeSync(
      opfsAccessHandle,
      bytes,
      offset + opfsRecordHeaderBytes + pathBytes.byteLength,
    );
  }
  const header = new Uint8Array(opfsRecordHeaderBytes);
  const view = new DataView(header.buffer);
  view.setUint32(0, opfsRecordMagic, true);
  view.setUint32(4, opfsRecordVersion, true);
  view.setUint32(8, deleted ? opfsRecordDeleted : opfsRecordLive, true);
  view.setUint32(12, pathBytes.byteLength, true);
  view.setUint32(16, bytes.byteLength, true);
  view.setUint32(20, recordLength, true);
  writeSync(opfsAccessHandle, header, offset);
  opfsAppendOffset += recordLength;
  opfsDirty = true;
  storageStats.opfsFileBytes = opfsAppendOffset;
  if (deleted) {
    regionIndex.delete(path);
  } else {
    regionIndex.set(path, {
      backend: "opfs",
      offset: offset + opfsRecordHeaderBytes + pathBytes.byteLength,
      length: bytes.byteLength,
    });
  }
  refreshStorageStats();
}

function readSync(handle, output, at) {
  let offset = 0;
  while (offset < output.byteLength) {
    const read = Number(handle.read(output.subarray(offset), {at: at + offset})) || 0;
    if (read <= 0) break;
    offset += read;
  }
  return offset;
}

function writeSync(handle, input, at) {
  let offset = 0;
  while (offset < input.byteLength) {
    const written = Number(handle.write(input.subarray(offset), {at: at + offset})) || 0;
    if (written <= 0) {
      throw new Error("OPFS SyncAccessHandle wrote zero bytes");
    }
    offset += written;
  }
}

async function readWorldFiles(worldId) {
  const worldPrefix = "/gaius/saves/" + String(worldId || "") + "/";
  const paths = await listStoredPaths(worldPrefix);
  const migratedPaths = [];
  for (const path of paths) {
    const entry = await readStoredRecord(path);
    if (!entry) continue;
    const value = await decodeStoredValue(path, entry.value);
    if (value === undefined) continue;
    if (isRegionPath(path)) {
      const bytes = toUint8Array(value);
      if (!bytes) {
        throw new Error("Stored region is not a byte array: " + path);
      }
      if (opfsAccessHandle) {
        appendOpfsRegion(path, bytes, false);
        migratedPaths.push(path);
        storageStats.migratedRegions++;
      } else {
        const copy = bytes.slice();
        if (!putRegionCache(path, copy, {
          dirty: false,
          pinned: true,
          flushing: false,
          version: 0,
        })) {
          throw new Error(
            "Saved regions exceed the IndexedDB compatibility cache budget; " +
              "OPFS SyncAccessHandle is required",
          );
        }
        regionIndex.set(path, {backend: "indexeddb", length: copy.byteLength});
      }
    } else {
      fileValues[path] = value;
    }
  }
  if (migratedPaths.length > 0) {
    flushOpfsSync();
    await deleteStoredPaths(migratedPaths);
  }
  refreshStorageStats();
}

function listStoredPaths(prefix) {
  return new Promise((resolve, reject) => {
    const paths = [];
    const transaction = database.transaction(storeName, "readonly");
    const store = transaction.objectStore(storeName);
    const range = typeof IDBKeyRange !== "undefined"
      ? IDBKeyRange.bound(prefix, prefix + "\uffff")
      : undefined;
    const request = typeof store.openKeyCursor === "function"
      ? store.openKeyCursor(range)
      : store.openCursor(range);
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) {
        resolve(paths);
        return;
      }
      const rawPath = cursor.key !== undefined
        ? cursor.key
        : cursor.value && cursor.value.path;
      if (typeof rawPath === "string") {
        const path = normalize(rawPath);
        if (path.startsWith(prefix)) paths.push(path);
      }
      cursor.continue();
    };
    request.onerror = () => reject(request.error || new Error("IndexedDB key cursor failed"));
  });
}

function readStoredRecord(path) {
  return new Promise((resolve, reject) => {
    const transaction = database.transaction(storeName, "readonly");
    const request = transaction.objectStore(storeName).get(path);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error("IndexedDB read failed"));
  });
}

function deleteStoredPaths(paths) {
  if (paths.length === 0) return Promise.resolve();
  const transaction = database.transaction(storeName, "readwrite");
  const store = transaction.objectStore(storeName);
  for (const path of paths) store.delete(path);
  return transactionDone(transaction);
}

function scheduleFlush(delay = 25) {
  if (flushTimer !== undefined) {
    return;
  }
  flushTimer = setTimeout(() => {
    flushTimer = undefined;
    void flushPendingChanges().catch(() => {});
  }, delay);
}

function flushPendingChanges() {
  if (flushTimer !== undefined) {
    clearTimeout(flushTimer);
    flushTimer = undefined;
  }
  if (flushInFlight) {
    return flushInFlight;
  }
  flushInFlight = drainPendingChanges().finally(() => {
    flushInFlight = undefined;
  });
  return flushInFlight;
}

async function drainPendingChanges() {
  try {
    while (opfsDirty || pendingChanges.size > 0) {
      flushOpfsSync();
      if (pendingChanges.size === 0) continue;
      const changes = new Map(pendingChanges);
      pendingChanges.clear();
      try {
        await writeBatch(changes);
      } catch (error) {
        for (const [path, change] of changes) {
          if (!pendingChanges.has(path)) pendingChanges.set(path, change);
          const cacheEntry = regionCache.get(path);
          if (cacheEntry && cacheEntry.version === change.version) {
            cacheEntry.dirty = true;
            cacheEntry.flushing = false;
          }
        }
        throw error;
      }
    }
  } catch (error) {
    storageStats.writeErrors++;
    reportStorageError(error);
    throw error;
  } finally {
    refreshStorageStats();
  }
}

async function writeBatch(changes) {
  if (!database) throw new Error("IndexedDB is closed");
  for (const [path, change] of changes) {
    if (!change.region) continue;
    const cacheEntry = regionCache.get(path);
    if (cacheEntry && cacheEntry.version === change.version) {
      cacheEntry.flushing = true;
    }
  }
  const transaction = database.transaction(storeName, "readwrite");
  const store = transaction.objectStore(storeName);
  for (const [path, change] of changes) {
    if (change.value === null) {
      store.delete(path);
    } else {
      store.put({path, value: change.value});
    }
  }
  await transactionDone(transaction);
  for (const [path, change] of changes) {
    if (!change.region) continue;
    const cacheEntry = regionCache.get(path);
    if (cacheEntry && cacheEntry.version === change.version) {
      cacheEntry.dirty = false;
      cacheEntry.flushing = false;
    }
  }
  trimRegionCache();
}

let changeVersion = 0;

function nextChangeVersion() {
  changeVersion = (changeVersion + 1) >>> 0;
  return changeVersion || ++changeVersion;
}

function queueIndexedDbChange(path, value, region) {
  pendingChanges.set(path, {
    value,
    version: nextChangeVersion(),
    region: !!region,
  });
}

function flushOpfsSync() {
  if (!opfsAccessHandle || !opfsDirty) return;
  opfsAccessHandle.flush();
  opfsDirty = false;
}

function readCachedRegion(path) {
  const cached = regionCache.get(path);
  if (cached) {
    cached.lastAccess = ++regionCacheSequence;
    storageStats.cacheHits++;
    return cached.value;
  }
  storageStats.cacheMisses++;
  const indexed = regionIndex.get(path);
  if (!indexed || indexed.backend !== "opfs" || !opfsAccessHandle) {
    refreshStorageStats();
    return undefined;
  }
  const bytes = new Uint8Array(indexed.length);
  if (readSync(opfsAccessHandle, bytes, indexed.offset) !== bytes.byteLength) {
    throw new Error("Could not read complete OPFS region: " + path);
  }
  putRegionCache(path, bytes, {
    dirty: false,
    pinned: false,
    flushing: false,
    version: 0,
  });
  refreshStorageStats();
  return bytes;
}

function putRegionCache(path, value, state) {
  const bytes = toUint8Array(value);
  if (!bytes) return false;
  const previous = regionCache.get(path);
  const previousBytes = previous ? previous.byteLength : 0;
  evictRegionCache(Math.max(0, bytes.byteLength - previousBytes), path);
  if (regionCacheBytes - previousBytes + bytes.byteLength > regionCacheBudgetBytes) {
    return false;
  }
  if (previous) regionCacheBytes -= previous.byteLength;
  regionCache.set(path, {
    value: bytes,
    byteLength: bytes.byteLength,
    dirty: !!state.dirty,
    pinned: !!state.pinned,
    flushing: !!state.flushing,
    version: Number(state.version) || 0,
    lastAccess: ++regionCacheSequence,
  });
  regionCacheBytes += bytes.byteLength;
  storageStats.cachePeakBytes = Math.max(storageStats.cachePeakBytes, regionCacheBytes);
  refreshStorageStats();
  return true;
}

function removeRegionCache(path) {
  const entry = regionCache.get(path);
  if (!entry) return;
  regionCacheBytes -= entry.byteLength;
  regionCache.delete(path);
  refreshStorageStats();
}

function evictRegionCache(requiredBytes, excludedPath) {
  while (regionCacheBytes + requiredBytes > regionCacheBudgetBytes) {
    let candidatePath;
    let candidate;
    for (const [path, entry] of regionCache) {
      if (path === excludedPath || entry.dirty || entry.pinned || entry.flushing) continue;
      if (!candidate || entry.lastAccess < candidate.lastAccess) {
        candidatePath = path;
        candidate = entry;
      }
    }
    if (!candidate) break;
    regionCache.delete(candidatePath);
    regionCacheBytes -= candidate.byteLength;
    storageStats.evictions++;
  }
}

function trimRegionCache() {
  evictRegionCache(0);
  refreshStorageStats();
}

function releaseStartupRegionPins() {
  for (const entry of regionCache.values()) entry.pinned = false;
  trimRegionCache();
}

function refreshStorageStats() {
  let dirtyEntries = 0;
  let pinnedEntries = 0;
  let flushingEntries = 0;
  for (const entry of regionCache.values()) {
    if (entry.dirty) dirtyEntries++;
    if (entry.pinned) pinnedEntries++;
    if (entry.flushing) flushingEntries++;
  }
  storageStats.cacheBytes = regionCacheBytes;
  storageStats.cacheEntries = regionCache.size;
  storageStats.regionEntries = regionIndex.size;
  storageStats.dirtyEntries = dirtyEntries;
  storageStats.pinnedEntries = pinnedEntries;
  storageStats.flushingEntries = flushingEntries;
  storageStats.pendingEntries = pendingChanges.size;
  return storageStats;
}

function activeWorldPrefix() {
  return "/gaius/saves/" + String(root.__gaiusServerWorldId || "") + "/";
}

function reportStorageError(error) {
  postMessage({
    type: "storage-write-error",
    detail: String(error && (error.stack || error.message) || error),
  });
}

function withTimeout(promise, timeoutMillis, detail) {
  let timer;
  return Promise.race([
    Promise.resolve(promise),
    new Promise((resolve, reject) => {
      timer = setTimeout(() => reject(new Error(detail)), timeoutMillis);
    }),
  ]).finally(() => clearTimeout(timer));
}

async function closePersistentStorage(flush) {
  if (persistentStorageClosed) return;
  persistentStorageClosed = true;
  if (flush) {
    try {
      await withTimeout(flushPendingChanges(), 5000, "Persistent storage close timed out");
    } catch (error) {
      reportStorageError(error);
    }
  }
  if (flushTimer !== undefined) {
    clearTimeout(flushTimer);
    flushTimer = undefined;
  }
  if (opfsAccessHandle) {
    try { flushOpfsSync(); } catch (error) { reportStorageError(error); }
    try { opfsAccessHandle.close(); } catch (error) { reportStorageError(error); }
    opfsAccessHandle = undefined;
  }
  if (database) {
    database.close();
    database = undefined;
  }
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
