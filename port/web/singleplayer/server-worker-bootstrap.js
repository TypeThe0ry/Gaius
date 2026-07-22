"use strict";

const root = globalThis;
if (typeof Error === "function" && (!Error.stackTraceLimit || Error.stackTraceLimit < 100)) {
  Error.stackTraceLimit = 100;
}
const dbName = "gaius-fs-v1";
const storeName = "files";
const files = root.__gaiusPersistentFiles = Object.create(null);
let database;
let pendingWrites = Promise.resolve();
const pendingChanges = new Map();
let flushTimer;
let runtimeStarted = false;
let stopRequested = false;
let stopping = false;

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
    const port = message.port || (event.ports && event.ports[0]);
    if (!(port instanceof MessagePort)) {
      throw new Error("Singleplayer worker did not receive its MessagePort");
    }
    root.__gaiusServerSessionId = String(message.sessionId || "");
    root.__gaiusServerWorldId = String(message.worldId || "");
    root.__gaiusServerViewDistance = clampDistance(message.renderDistance, 6);
    root.__gaiusServerSimulationDistance = clampDistance(message.simulationDistance, 4);
    root.__gaiusBridgeUrl = message.bridgeUrl || undefined;
    root.__gaiusBridgeToken = message.bridgeToken || undefined;
    root.__gaiusLocalServerPorts = new Map([
      [root.__gaiusServerSessionId, port],
    ]);

    await installPersistentFileSystem();
    postMessage({
      type: "storage-ready",
      detail: Object.keys(files).length + " files",
    });

    let scriptUrl;
    let temporaryScriptUrl;
    if (message.serverScriptGzipUrl) {
      if (typeof DecompressionStream !== "function") {
        throw new Error("This browser cannot decompress the portable singleplayer server");
      }
      const response = await fetch(String(message.serverScriptGzipUrl));
      if (!response.ok) {
        throw new Error("Portable singleplayer server asset could not be loaded");
      }
      const decompressed = response.body.pipeThrough(new DecompressionStream("gzip"));
      const scriptBlob = await new Response(decompressed).blob();
      temporaryScriptUrl = URL.createObjectURL(new Blob(
        [scriptBlob],
        {type: "text/javascript"},
      ));
      scriptUrl = temporaryScriptUrl;
    } else if (message.serverScriptUrl) {
      scriptUrl = String(message.serverScriptUrl);
    } else {
      const resolved = new URL("singleplayer-server.js", location.href);
      resolved.search = location.search;
      scriptUrl = resolved.href;
    }
    importScripts(scriptUrl);
    if (temporaryScriptUrl) {
      URL.revokeObjectURL(temporaryScriptUrl);
    }
    if (typeof main !== "function") {
      throw new Error("Singleplayer TeaVM output did not expose main(args)");
    }
    postMessage({type: "runtime-ready", detail: root.__gaiusServerWorldId});
    main([]);
    runtimeStarted = true;
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
  await flushPendingChanges();
  if (database) {
    database.close();
  }
  postMessage({type: "stopped", detail: root.__gaiusServerWorldId});
  setTimeout(() => close(), 0);
}

async function installPersistentFileSystem() {
  database = await openDatabase();
  await readAllFiles();
  root.__gaiusFsBackend = "indexeddb-worker";
  root.__gaiusFsPut = (path, value) => {
    path = normalize(path);
    value = String(value || "");
    files[path] = value;
    pendingChanges.set(path, value);
    scheduleFlush();
    return true;
  };
  root.__gaiusFsDelete = (path) => {
    path = normalize(path);
    delete files[path];
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

function readAllFiles() {
  return new Promise((resolve, reject) => {
    const transaction = database.transaction(storeName, "readonly");
    const request = transaction.objectStore(storeName).openCursor();
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) {
        resolve();
        return;
      }
      const value = cursor.value;
      if (value && typeof value.path === "string" && typeof value.value === "string") {
        files[normalize(value.path)] = value.value;
      }
      cursor.continue();
    };
    request.onerror = () => reject(request.error || new Error("IndexedDB cursor failed"));
  });
}

function scheduleFlush() {
  if (flushTimer !== undefined) {
    return;
  }
  flushTimer = setTimeout(() => {
    flushTimer = undefined;
    void flushPendingChanges();
  }, 25);
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

function writeBatch(changes) {
  const transaction = database.transaction(storeName, "readwrite");
  const store = transaction.objectStore(storeName);
  for (const [path, value] of changes) {
    if (value === null) {
      store.delete(path);
    } else {
      store.put({path, value});
    }
  }
  return transactionDone(transaction);
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
