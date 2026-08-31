"use strict";

const root = globalThis;
if (typeof Error === "function" && (!Error.stackTraceLimit || Error.stackTraceLimit < 100)) {
  Error.stackTraceLimit = 100;
}
const storageProfiles = Object.freeze({
  "1.21.11": Object.freeze({
    worldVersion: 4671,
    storageSchema: 2,
    storageDatabaseName: "gaius-fs-v2-1.21.11",
    storagePrefix: "gaius.fs.v2:1.21.11:",
    storageOpfsDirectory: "regions-v2-1.21.11",
  }),
  "26.2": Object.freeze({
    worldVersion: 4903,
    storageSchema: 2,
    storageDatabaseName: "gaius-fs-v2-26.2",
    storagePrefix: "gaius.fs.v2:26.2:",
    storageOpfsDirectory: "regions-v2-26.2",
  }),
});
const storeName = "files";
const defaultWorldgenSliceMillis = 8;
const defaultDistanceRampIntervalMillis = 750;
const defaultRegionCacheBudgetBytes = 32 * 1024 * 1024;
const minimumRegionCacheBudgetBytes = 64 * 1024;
const maximumRegionCacheBudgetBytes = 256 * 1024 * 1024;
const opfsRecordMagic = 0x47525331;
const opfsRecordVersion = 1;
const opfsRecordHeaderBytes = 24;
const opfsRecordLive = 1;
const opfsRecordDeleted = 2;
const opfsPatchRecordMagic = 0x47525332;
const opfsPatchRecordVersion = 2;
const opfsPatchRecordHeaderBytes = 48;
const opfsPatchRecordState = 3;
const opfsPatchCommitMagic = 0x434f4d54;
const maximumOpfsPatchRanges = 64;
const maximumOpfsPatchChainRecords = 64;
const maximumOpfsPatchChainBytes = 8 * 1024 * 1024;
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
  opfsFullWrites: 0,
  opfsFullWriteBytes: 0,
  opfsPatchWrites: 0,
  opfsPatchPayloadBytes: 0,
  opfsPatchRanges: 0,
  opfsPatchCheckpoints: 0,
  opfsReconstructedRegions: 0,
  opfsFlushes: 0,
  opfsFlushMillis: 0,
  opfsMaxFlushMillis: 0,
  opfsScanRecords: 0,
  opfsScanV1Records: 0,
  opfsScanV2Records: 0,
  flushTimeouts: 0,
  flushAbortRequests: 0,
  flushAbortTimeouts: 0,
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
let opfsCrcTable;
let persistentStorageClosed = false;
let storageGeneration = 0;
let storageStartupInFlight;
let storageClosePromise;
let storageHandlesClosed = false;
let activeFlushTransaction;
let flushAbortRequested = false;
let stopPromise;
let startupCancelResolve;
let startupCancelPromise;
let storageFlushFailureReported = false;
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

const networkTelemetryPriorityKeys = Object.freeze([
  "errors",
  "queuedBytes",
  "inboundQueuedBytes",
  "peakInboundQueuedBytes",
  "inboundSlices",
  "inboundSlicePumps",
  "maxInboundSliceQueue",
  "longestInboundSlicePumpMillis",
  "decodedSliceBacklog",
  "maxDecodedSliceBacklog",
  "decoderCumulationBytes",
  "maxDecoderCumulationBytes",
  "decodedPacketQueue",
  "maxDecodedPacketQueue",
  "sentFrames",
  "sentBytes",
  "receivedFrames",
  "receivedBytes",
  "pumpCalls",
  "pumpChunks",
  "pumpBytes",
  "peakPumpChunks",
  "peakPumpBytes",
  "peakPumpMillis",
  "longestPumpMillis",
  "eventLoopGapSamples",
  "eventLoopGapsOver500",
  "longestEventLoopGapMillis",
  "activeHighWatermarks",
  "highWatermarkDurationMillis",
  "longestHighWatermarkMillis",
  "activeHighWatermarkMillis",
  "localFlushes",
  "localFlushFrames",
  "localFlushBytes",
  "localReceivedFrames",
  "localReceivedBytes",
  "outboundTurns",
  "outboundTurnFrames",
  "outboundTurnBytes",
  "maxOutboundTurnFrames",
  "maxOutboundTurnBytes",
  "maxOutboundTurnMillis",
  "outboundYields",
  "outboundBackpressureDeferrals",
  "integratedServerPumpFailures",
  "integratedServerPumpRequests",
  "integratedServerPumpStarts",
  "integratedServerPumpRetrySchedules",
  "integratedServerPumpRetryExhaustions",
  "integratedServerTaskSignals",
  "integratedServerTaskUnparks",
  "integratedServerTaskCoalesced",
  "integratedServerTaskSchedules",
  "integratedServerTaskScheduleFailures",
  "integratedServerTaskRuns",
  "integratedServerTaskFollowups",
  "integratedServerTaskLifecycleDrops",
  "integratedServerTaskWrongThread",
  "integratedServerTaskBudgetExhaustions",
  "integratedServerTaskDeferredRetries",
  "integratedServerTaskRetryExhaustions",
  "integratedServerTaskPending",
  "integratedServerInputPending",
]);

// A measurement window must not mutate the live network/storage state: the
// scheduler reads queue depths and pending flags from these objects while the
// benchmark is running.  Only known monotonic counters are baselined below;
// gauges, extrema, and configuration limits are always returned as raw live
// values so their meaning is not changed by subtraction.
const networkTelemetryCounterKeys = Object.freeze([
  "opened", "localOpened", "directAttempts", "directConnected",
  "directPluginCachedMisses", "relayAttempts", "relayFailovers",
  "relayPreflights", "relayPreflightSuccesses", "relayPreflightFailures",
  "relayPreflightCacheHits", "relayRegistryRequests", "relayRegistrySuccesses",
  "relayRegistryFailures", "relayRegistryCacheHits", "relayRegistryNodesLoaded",
  "relayRegistryRegistriesLoaded", "relayTargetActiveSelections",
  "relayTargetRecentSelections", "relayTargetLocalActiveSelections",
  "relayTargetLocalRecentSelections", "relayTargetLeaseAcquires",
  "relayTargetLeaseReleases", "relayNodeSuccesses", "relayNodeFailures",
  "relayTargetAttestationFailures", "closed", "sentFrames", "sentBytes",
  "receivedFrames", "receivedBytes", "inboundSlices", "inboundSlicePumps",
  "decodedSliceBacklogPauses", "decodedSliceBacklogResumes",
  "decodedPacketQueuePauses", "decodedPacketQueueResumes",
  "decodedPacketDrainSignals", "flowPauses", "flowResumes", "localFlushes",
  "localFlushFrames", "localFlushBytes", "localReceivedFrames",
  "localReceivedBytes", "localClaimWaits", "localClaimRetries",
  "localClaimTimeouts", "localDuplicateOpens", "localSupersededClaims",
  "outboundTurns", "outboundTurnFrames", "outboundTurnBytes", "outboundYields",
  "outboundBackpressureDeferrals", "controlQueueOverflows",
  "webSocketBackpressureWaits", "localMessagePortSends", "webSocketSends",
  "pumpCalls", "pumpChunks", "pumpBytes", "deferredPumps",
  "eventLoopGapSamples", "eventLoopGapsOver500", "errors",
  "integratedServerPumpFailures", "integratedServerPumpRequests",
  "integratedServerPumpStarts", "integratedServerPumpRetrySchedules",
  "integratedServerPumpRetryExhaustions", "integratedServerTaskSignals",
  "integratedServerTaskUnparks", "integratedServerTaskCoalesced",
  "integratedServerTaskSchedules", "integratedServerTaskScheduleFailures",
  "integratedServerTaskRuns", "integratedServerTaskFollowups",
  "integratedServerTaskLifecycleDrops", "integratedServerTaskWrongThread",
  "integratedServerTaskBudgetExhaustions", "integratedServerTaskDeferredRetries",
  "integratedServerTaskRetryExhaustions",
]);
// Global pump counters are populated by BrowserWebSocketChannel.pumpAll* and
// live after the 64-key scalar network snapshot. Keep this list deliberately
// fixed and shallow so the aggregate cannot be dropped when the network stats
// object grows with additional transport diagnostics.
const globalPumpTelemetryKeys = Object.freeze([
  "pumpAllTurns",
  "pumpAllChannelsVisited",
  "pumpAllBudgetYields",
  "pumpAllMaxTurnMillis",
  "pumpAllMaxChannelsPerTurn",
  "pumpAllLastTurnMillis",
  "pumpAllLastChannelsVisited",
]);
const storageTelemetryCounterKeys = Object.freeze([
  "evictions", "cacheHits", "cacheMisses", "rejectedWrites", "writeErrors",
  "migratedRegions", "opfsFullWrites", "opfsFullWriteBytes", "opfsPatchWrites",
  "opfsPatchPayloadBytes", "opfsPatchRanges", "opfsPatchCheckpoints",
  "opfsReconstructedRegions", "opfsFlushes", "opfsFlushMillis",
  "opfsScanRecords", "opfsScanV1Records", "opfsScanV2Records",
  "flushTimeouts", "flushAbortRequests", "flushAbortTimeouts",
]);
let telemetryMeasurementInitialized = false;
let telemetryMeasurementId = "";
let telemetryWorkerResetAt = 0;
let telemetryNetworkBaseline;
let telemetryStorageBaseline;

function snapshotScalarTelemetry(value, priorityKeys = []) {
  const snapshot = Object.create(null);
  if (!value || typeof value !== "object") {
    return snapshot;
  }
  let copied = 0;
  const copy = (key) => {
    if (copied >= 64 || Object.prototype.hasOwnProperty.call(snapshot, key)) {
      return;
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
  };
  for (const key of priorityKeys) {
    copy(key);
  }
  for (const key of Object.keys(value)) {
    copy(key);
    if (copied >= 64) {
      break;
    }
  }
  return snapshot;
}

function snapshotGlobalPumpTelemetry(value) {
  const snapshot = Object.create(null);
  for (const key of globalPumpTelemetryKeys) {
    const current = value && typeof value === "object" ? value[key] : undefined;
    snapshot[key] = typeof current === "number" && Number.isFinite(current)
      ? current
      : null;
  }
  return snapshot;
}

function globalPumpTelemetrySource() {
  const bridge = root.__gaiusNettyBridge;
  const bridgeStats = bridge && bridge.stats;
  return bridgeStats && typeof bridgeStats === "object"
    ? bridgeStats
    : root.__gaiusNetworkStats;
}

function snapshotMeasurementTelemetry(value, baseline, priorityKeys, counterKeys) {
  const snapshot = snapshotScalarTelemetry(value, priorityKeys);
  const previous = baseline || Object.create(null);
  const counters = new Set(counterKeys || []);
  for (const key of Object.keys(snapshot)) {
    const current = snapshot[key];
    if (typeof current !== "number" || !counters.has(key)) {
      continue;
    }
    const before = Number(previous[key]);
    snapshot[key] = Number.isFinite(before)
      ? Math.max(0, current - before)
      : current;
  }
  return snapshot;
}

function createChunkPriorityTelemetryStats() {
  return {
    playerUpdates: 0,
    pops: 0,
    reorderedPops: 0,
    scannedCandidates: 0,
    maxCandidates: 0,
  };
}

function observeTelemetryMeasurement(value) {
  const measurementId = typeof value === "string"
    ? value
    : String(value == null ? "" : value);
  if (telemetryMeasurementInitialized && telemetryMeasurementId === measurementId) {
    return false;
  }
  telemetryMeasurementInitialized = true;
  telemetryMeasurementId = measurementId;
  root.__gaiusTelemetryMeasurementId = measurementId;
  telemetryWorkerResetAt = Math.max(Date.now(), telemetryWorkerResetAt + 1);
  root.__gaiusTelemetryWorkerResetAt = telemetryWorkerResetAt;

  // These two objects are telemetry-only.  Replacing them (rather than
  // clearing the network bridge) keeps the scheduler's live references and
  // queue/backpressure decisions intact while dropping startup samples.
  root.__gaiusWorldgenStats = {};
  root.__gaiusChunkPriorityStats = createChunkPriorityTelemetryStats();

  // Network and storage objects contain scheduler-visible gauges.  Leave the
  // objects untouched and subtract this baseline only when making pong
  // snapshots so a new measurement window cannot contaminate cumulative
  // counters without changing runtime behavior.
  telemetryNetworkBaseline = snapshotScalarTelemetry(
    root.__gaiusNetworkStats,
    networkTelemetryPriorityKeys,
  );
  telemetryStorageBaseline = snapshotScalarTelemetry(storageStats);
  root.__gaiusTelemetryNetworkBaseline = telemetryNetworkBaseline;
  root.__gaiusTelemetryStorageBaseline = telemetryStorageBaseline;
  return true;
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
    requestStop();
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
  const launchGeneration = String(message.launchGeneration || "");
  if (!/^[1-9][0-9]*$/.test(launchGeneration)) {
    closeTransferredPort(message, event);
    postMessage({type: "start-invalid-generation", detail: activeSessionId});
    return;
  }
  startAccepted = true;
  activeSessionId = String(message.sessionId || "");
  root.onmessage = handleControlMessage;
  startupCancelPromise = new Promise((resolve) => {
    startupCancelResolve = resolve;
  });
  try {
    clearStorageConfiguration();
    configureStorage(message);
    if (stopRequested) {
      throw startupCancellationError();
    }
    const startupStarted = monotonicMillis();
    markStartup("start-received", startupStarted);
    const port = await waitForStartPort(message, event);
    startupPort = port;
    root.__gaiusServerClientPort = port;
    root.__gaiusServerSessionId = activeSessionId;
    root.__gaiusServerLaunchGeneration = launchGeneration;
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
    if (!registerLocalPort(
      root.__gaiusServerSessionId,
      port,
      root.__gaiusServerLaunchGeneration,
    )) {
      throw new Error("Singleplayer local port registration requires a launch generation");
    }

    const storageStarted = monotonicMillis();
    const startupStorageGeneration = ++storageGeneration;
    const storageReady = installPersistentFileSystem(startupStorageGeneration).then((installed) => {
      if (installed && storageLifecycleIsActive(startupStorageGeneration)) {
        markStartup("storage-ready", storageStarted);
      }
      return installed;
    });
    storageStartupInFlight = storageReady;
    storageReady.then(
      () => {
        if (storageStartupInFlight === storageReady) storageStartupInFlight = undefined;
      },
      () => {
        if (storageStartupInFlight === storageReady) storageStartupInFlight = undefined;
      },
    );
    const assetReady = prepareServerScript(message, startupStarted);
    // A stop must not remain hostage to a portable asset fetch that never
    // resolves. Promise.all is still observed so a late rejection cannot
    // become an unhandled rejection, but cancellation wins the bootstrap
    // race immediately.
    const startupReady = Promise.all([assetReady, storageReady]);
    startupReady.catch(() => {});
    assetReady.then((script) => {
      if (stopRequested && script && script.temporaryUrl) {
        try { URL.revokeObjectURL(script.temporaryUrl); } catch (ignored) {}
      }
    }, () => {});
    const [script, storageInstalled] = await Promise.race([
      startupReady,
      startupCancelPromise.then(() => { throw startupCancellationError(); }),
    ]);
    if (!storageInstalled || !storageLifecycleIsActive(startupStorageGeneration)) {
      throw new Error("Singleplayer launch storage was closed before runtime import");
    }
    if (stopRequested) {
      throw startupCancellationError();
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
      throw startupCancellationError();
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
    if (stopRequested) {
      // requestStop() starts the same finalizer used after runtime startup.
      // Never claim a clean stop from this catch path: the finalizer is the
      // only code allowed to post stopped/close after storage has closed
      // safely.
      if (!runtimeStarted && !startupPort) {
        // A stop may have arrived before a start carrying a direct transferred
        // port was allowed through. It was never registered in that case, so
        // release it explicitly instead of leaking the transfer.
        closeTransferredPort(message, event);
      }
      const stopped = await (stopPromise || stopServer());
      if (!stopped) return;
      return;
    }
    const closed = await closePersistentStorage(false);
    if (!closed) {
      reportStorageFlushFailure(new Error(
        "Persistent storage was not safely closed after bootstrap failure",
      ));
    }
    releaseLocalSession("Singleplayer Worker bootstrap failed");
    postMessage({
      type: "bootstrap-crash",
      detail: String(error && (error.stack || error.message) || error),
    });
    // The bootstrap entrypoint is invoked from a MessagePort callback in
    // browsers (and from an unawaited event shim in the lifecycle harness).
    // Reporting the crash is sufficient; rethrowing here creates an
    // unhandled-rejection event after teardown has already completed.
    return;
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

function startupCancellationError() {
  const error = new Error("Singleplayer launch was cancelled");
  error.code = "GAIUS_STARTUP_CANCELLED";
  return error;
}

function invalidateStorageLifecycle() {
  if (!persistentStorageClosed) {
    persistentStorageClosed = true;
    storageGeneration++;
  }
  // Generation guards are the authoritative write barrier. Clearing the
  // exported callbacks as well makes late TeaVM continuations fail closed
  // without touching pendingChanges that still need a shutdown flush.
  root.__gaiusFsPut = undefined;
  root.__gaiusFsPutBytes = undefined;
  root.__gaiusFsCanPatchBytes = undefined;
  root.__gaiusFsPatchBytes = undefined;
  root.__gaiusFsDelete = undefined;
  root.__gaiusFsFlush = undefined;
  root.__gaiusFsStorageSnapshot = undefined;
}

function rejectPendingStartPort(reason) {
  if (!pendingPortReject) return false;
  const reject = pendingPortReject;
  if (pendingPortTimer !== undefined) {
    clearTimeout(pendingPortTimer);
    pendingPortTimer = undefined;
  }
  pendingPortResolve = undefined;
  pendingPortReject = undefined;
  reject(reason || startupCancellationError());
  return true;
}

function requestStop() {
  stopRequested = true;
  if (!startAccepted) return;
  // Runtime shutdown must keep the generation/callbacks live while
  // stopIntegratedServer performs its final save. Startup cancellation is the
  // only path that may raise the storage barrier before teardown.
  if (!runtimeStarted) {
    invalidateStorageLifecycle();
    if (startupCancelResolve) {
      const resolve = startupCancelResolve;
      startupCancelResolve = undefined;
      resolve();
    }
    rejectPendingStartPort();
  }
  if (!stopPromise) {
    stopPromise = stopServer();
  }
}

function waitForStartPort(message, event) {
  const port = transferredPort(message, event);
  if (stopRequested) {
    if (port) closeTransferredPort(message, event);
    return Promise.reject(startupCancellationError());
  }
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

function registerLocalPort(sessionId, port, launchGeneration) {
  const generation = String(launchGeneration || "");
  if (!/^[1-9][0-9]*$/.test(generation)) return false;
  let extensible = false;
  try { extensible = Object.isExtensible(port); } catch {}
  if (!extensible) return false;
  const existingGeneration = String(port && port.__gaiusLaunchGeneration || "");
  if (existingGeneration && existingGeneration !== generation) return false;
  if (existingGeneration !== generation) {
    try { port.__gaiusLaunchGeneration = generation; } catch {}
  }
  if (String(port && port.__gaiusLaunchGeneration || "") !== generation) return false;
  const bridge = root.__gaiusNettyBridge;
  if (bridge && typeof bridge.registerLocalPort === "function") {
    return bridge.registerLocalPort(sessionId, port, generation) !== false;
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
  return true;
}

function releaseLocalSession(reason) {
  if (pendingPortTimer !== undefined) {
    clearTimeout(pendingPortTimer);
    pendingPortTimer = undefined;
  }
  pendingPortResolve = undefined;
  pendingPortReject = undefined;
  const sessionId = String(root.__gaiusServerSessionId || activeSessionId || "");
  const launchGeneration = String(root.__gaiusServerLaunchGeneration || "");
  const bridge = root.__gaiusNettyBridge;
  if (bridge && typeof bridge.failLocalSession === "function" && sessionId) {
    bridge.failLocalSession(sessionId, reason, launchGeneration);
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
  root.__gaiusServerClientPort = undefined;
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
    const measurementId = typeof message.measurementId === "string"
      ? message.measurementId
      : String(message.measurementId == null ? "" : message.measurementId);
    const receivedAtEpoch = Date.now();
    refreshStorageStats();
    observeTelemetryMeasurement(measurementId);
    postMessage({
      type: "telemetry-pong",
      sessionId: String(message.sessionId || activeSessionId || ""),
      sequence: Number(message.sequence) || 0,
      measurementId: telemetryMeasurementId,
      workerResetAt: telemetryWorkerResetAt,
      receivedAtEpoch,
      sentAtEpoch: Date.now(),
      chunkPriority: snapshotScalarTelemetry(root.__gaiusChunkPriorityStats),
      network: snapshotMeasurementTelemetry(
        root.__gaiusNetworkStats,
        telemetryNetworkBaseline,
        networkTelemetryPriorityKeys,
        networkTelemetryCounterKeys,
      ),
      // Keep the bounded global-pump aggregate outside the capped scalar
      // network object. These values are intentionally raw (not measurement
      // deltas): turns/yields are cumulative process diagnostics while the
      // max/last fields are gauges. Missing fields stay explicit nulls.
      globalPump: snapshotGlobalPumpTelemetry(globalPumpTelemetrySource()),
      worldgen: snapshotScalarTelemetry(root.__gaiusWorldgenStats),
      storage: snapshotMeasurementTelemetry(
        storageStats,
        telemetryStorageBaseline,
        [],
        storageTelemetryCounterKeys,
      ),
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
    // A queued client preference update must not revive distance work after
    // shutdown has started. PlayerList setters rebroadcast and walk every
    // ServerLevel, so even a late no-op message is expensive in this Worker.
    if (stopRequested || stopping) return;
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
  requestStop();
}

function clampDistance(value, fallback) {
  const number = Number(value);
  return Math.max(2, Math.min(32, Number.isFinite(number) ? Math.floor(number) : fallback));
}

function configureStorage(message) {
  const profileId = requiredStorageIdentifier(message && message.profileId, "profileId");
  const worldVersion = requiredStorageInteger(message && message.worldVersion, "worldVersion");
  const storageSchema = requiredStorageInteger(
    message && message.storageSchema,
    "storageSchema",
  );
  const storageDatabaseName = requiredStorageIdentifier(
    message && message.storageDatabaseName,
    "storageDatabaseName",
  );
  const storagePrefix = requiredStoragePrefix(message && message.storagePrefix);
  const storageOpfsDirectory = requiredStorageIdentifier(
    message && message.storageOpfsDirectory,
    "storageOpfsDirectory",
  );
  const expected = storageProfiles[profileId];
  if (!expected || worldVersion !== expected.worldVersion ||
      storageSchema !== expected.storageSchema ||
      storageDatabaseName !== expected.storageDatabaseName ||
      storagePrefix !== expected.storagePrefix ||
      storageOpfsDirectory !== expected.storageOpfsDirectory) {
    throw new Error(
      "Singleplayer storage configuration does not match profile " + profileId,
    );
  }
  root.__gaiusProfileId = profileId;
  root.__gaiusWorldVersion = worldVersion;
  root.__gaiusStorageSchema = storageSchema;
  root.__gaiusStorageDatabaseName = storageDatabaseName;
  root.__gaiusStoragePrefix = storagePrefix;
  root.__gaiusStorageOpfsDirectory = storageOpfsDirectory;
}

function clearStorageConfiguration() {
  root.__gaiusProfileId = undefined;
  root.__gaiusWorldVersion = undefined;
  root.__gaiusStorageSchema = undefined;
  root.__gaiusStorageDatabaseName = undefined;
  root.__gaiusStoragePrefix = undefined;
  root.__gaiusStorageOpfsDirectory = undefined;
}

function requiredStorageIdentifier(value, field) {
  const text = String(value == null ? "" : value).trim();
  if (!text || text.length > 128 || text === "." || text === ".." ||
      text.includes("/") || text.includes("\\") || text.includes("\u0000") ||
      text === "gaius-fs-v1" || text === "regions") {
    throw new Error("Singleplayer storage " + field + " is not configured safely");
  }
  return text;
}

function requiredStoragePrefix(value) {
  const text = String(value == null ? "" : value);
  if (!text || text.length > 256 || text.includes("\u0000") ||
      text === "gaius.fs.v1:") {
    throw new Error("Singleplayer storage prefix is not configured safely");
  }
  return text;
}

function requiredStorageInteger(value, field) {
  const number = Number(value);
  if (!Number.isSafeInteger(number) || number <= 0) {
    throw new Error("Singleplayer storage " + field + " is not configured safely");
  }
  return number;
}

function storageLifecycleIsActive(generation) {
  return !persistentStorageClosed && Number(generation) === storageGeneration;
}

function assertStorageLifecycleIsActive(generation) {
  if (!storageLifecycleIsActive(generation)) {
    throw new Error("Persistent storage lifecycle was closed");
  }
}

function configuredTimeout(rootValue, fallback, maximum) {
  const value = Number(rootValue);
  if (!Number.isFinite(value) || value <= 0) return fallback;
  return Math.min(maximum, Math.max(1, Math.floor(value)));
}

function storageFlushWatchdogMillis() {
  return configuredTimeout(root.__gaiusStorageFlushWatchdogMillis, 10000, 60000);
}

function storageFlushRetryWatchdogMillis() {
  const configured = Number(root.__gaiusStorageFlushRetryWatchdogMillis);
  if (Number.isFinite(configured) && configured > 0) {
    return Math.min(60000, Math.max(1, Math.floor(configured)));
  }
  // The retry is deliberately given a little more room than the first
  // watchdog: an abort/rollback can leave the backend briefly busy even
  // after its promise has settled.
  return Math.min(60000, Math.max(storageFlushWatchdogMillis(), 1000));
}

function storageFlushAbortSettleMillis() {
  return configuredTimeout(root.__gaiusStorageFlushAbortSettleMillis, 1000, 10000);
}

function storageStartupSettleMillis() {
  return configuredTimeout(root.__gaiusStorageStartupSettleMillis, 5000, 30000);
}

function runtimeStopWatchdogMillis() {
  return configuredTimeout(root.__gaiusRuntimeStopWatchdogMillis, 20000, 60000);
}

function runtimeStopFailure(detail) {
  const error = new Error(detail);
  error.code = "GAIUS_RUNTIME_STOP_FAILED";
  return error;
}

function reportRuntimeStopFailure(error) {
  const detail = String(error && (error.stack || error.message) || error);
  postMessage({type: "runtime-stop-failed", detail, error: detail});
}

function stopServer() {
  if (stopPromise) return stopPromise;
  stopPromise = stopServerImpl().catch((error) => {
    if (error && error.code === "GAIUS_RUNTIME_STOP_FAILED") {
      reportRuntimeStopFailure(error);
    } else {
      reportStorageFlushFailure(error);
    }
    return false;
  });
  return stopPromise;
}

async function stopServerImpl() {
  if (stopping) return false;
  stopping = true;
  postMessage({type: "stopping", detail: root.__gaiusServerWorldId});
  if (runtimeStarted) {
    // Once main() has been dispatched, both hooks are required to prove that
    // the Java server stopped. Closing storage without either proof can race
    // a final save, so fail closed before touching flush/close.
    if (typeof stopIntegratedServer !== "function") {
      throw runtimeStopFailure(
        "Integrated server stop hook is unavailable; storage shutdown refused",
      );
    }
    if (typeof isIntegratedServerStopped !== "function") {
      throw runtimeStopFailure(
        "Integrated server stopped-state hook is unavailable; storage shutdown refused",
      );
    }
    try {
      stopIntegratedServer();
    } catch (error) {
      throw runtimeStopFailure(
        "Integrated server stop hook failed: " +
          String(error && (error.stack || error.message) || error),
      );
    }
    const stopWatchdog = runtimeStopWatchdogMillis();
    const deadline = Date.now() + stopWatchdog;
    while (Date.now() < deadline) {
      let stopped;
      try {
        stopped = isIntegratedServerStopped();
      } catch (error) {
        throw runtimeStopFailure(
          "Integrated server stopped-state hook failed: " +
            String(error && (error.stack || error.message) || error),
        );
      }
      if (stopped) break;
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
    let stopped;
    try {
      stopped = isIntegratedServerStopped();
    } catch (error) {
      throw runtimeStopFailure(
        "Integrated server stopped-state hook failed: " +
          String(error && (error.stack || error.message) || error),
      );
    }
    if (!stopped) {
      const detail = "Integrated server did not stop within " +
        stopWatchdog + " ms";
      postMessage({type: "server-stop-timeout", detail});
      throw runtimeStopFailure(detail + "; storage shutdown refused");
    }
  }
  postMessage({type: "storage-flushing", detail: root.__gaiusServerWorldId});

  const flushed = await flushForShutdown();
  const closed = flushed && await closePersistentStorage(false);
  // This is deliberately checked again after closePersistentStorage.  No
  // caller may turn a deferred/failed close into a clean worker shutdown.
  if (!flushed || !closed || !storageHandlesClosed || !storageStateIsClean()) {
    reportStorageFlushFailure(new Error(
      "Persistent storage flush/close failed; worker remains open",
    ));
    return false;
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
  return true;
}

function storageStateIsClean() {
  refreshStorageStats();
  if (flushInFlight || activeFlushTransaction || pendingChanges.size > 0 || opfsDirty) {
    return false;
  }
  for (const entry of regionCache.values()) {
    if (entry.dirty || entry.flushing) return false;
  }
  return true;
}

async function flushForShutdown() {
  let lastError;
  // A watchdog abort is expected to requeue the batch.  Always give that
  // recovered batch one complete retry, but never retry while the aborted
  // transaction/promise is still unsettled.
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      await flushWithWatchdog(
        attempt === 0
          ? storageFlushWatchdogMillis()
          : storageFlushRetryWatchdogMillis(),
        "Persistent storage flush timed out",
      );
      if (storageStateIsClean()) return true;
    } catch (error) {
      lastError = error;
      // Keep the watchdog event even when the recovered batch succeeds on
      // retry; operators need to see why the first shutdown flush was
      // aborted.
      reportStorageError(error);
      if (flushInFlight || activeFlushTransaction) {
        reportStorageFlushFailure(error);
        return false;
      }
      if (attempt === 1) break;
      continue;
    }
    if (storageStateIsClean()) return true;
  }
  reportStorageFlushFailure(lastError || new Error(
    "Persistent storage still has pending changes after shutdown flush retry",
  ));
  return false;
}

async function installPersistentFileSystem(generation = storageGeneration) {
  let openedDatabase;
  try {
    assertStorageLifecycleIsActive(generation);
    openedDatabase = await openDatabase();
    if (!storageLifecycleIsActive(generation)) {
      try { openedDatabase.close(); } catch (ignored) {}
      openedDatabase = undefined;
      return false;
    }
    database = openedDatabase;
    openedDatabase = undefined;
    const opfsReady = await openOpfsRegionStore(root.__gaiusServerWorldId, generation);
    assertStorageLifecycleIsActive(generation);
    await readWorldFiles(root.__gaiusServerWorldId, generation);
    assertStorageLifecycleIsActive(generation);
    root.__gaiusFsBackend = opfsReady
      ? "opfs-sync-worker"
      : "indexeddb-worker-lru";
    storageStats.backend = root.__gaiusFsBackend;
  } catch (error) {
    if (openedDatabase) {
      try { openedDatabase.close(); } catch (ignored) {}
      openedDatabase = undefined;
    }
    if (!storageLifecycleIsActive(generation)) {
      if (database && generation !== storageGeneration) {
        try { database.close(); } catch (ignored) {}
        database = undefined;
      }
      if (opfsAccessHandle && generation !== storageGeneration) {
        try { opfsAccessHandle.close(); } catch (ignored) {}
        opfsAccessHandle = undefined;
      }
      return false;
    }
    throw error;
  }

  if (!storageLifecycleIsActive(generation)) return false;
  root.__gaiusFsPut = (path, value) => {
    if (!storageLifecycleIsActive(generation)) return false;
    path = normalize(path);
    value = String(value || "");
    fileValues[path] = value;
    queueIndexedDbChange(path, value, false);
    scheduleFlush();
    return true;
  };
  root.__gaiusFsPutBytes = (path, value) => {
    if (!storageLifecycleIsActive(generation)) return false;
    path = normalize(path);
    const bytes = toUint8Array(value);
    if (!bytes) return false;
    if (isRegionPath(path) && opfsAccessHandle) {
      try {
        appendOpfsRegion(path, bytes, false);
        // Java may reclaim the TeaVM copy as soon as this call returns true.
        flushOpfsSync();
        removeRegionCache(path);
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
  root.__gaiusFsCanPatchBytes = (path) => {
    if (!storageLifecycleIsActive(generation)) return false;
    path = normalize(path);
    const indexed = regionIndex.get(path);
    return !!opfsAccessHandle && isRegionPath(path) &&
      (!indexed || indexed.backend === "opfs");
  };
  root.__gaiusFsPatchBytes = (path, logicalSize, offsets, lengths, value) => {
    if (!storageLifecycleIsActive(generation)) return false;
    path = normalize(path);
    const indexed = regionIndex.get(path);
    if (!opfsAccessHandle || !isRegionPath(path) ||
        (indexed && indexed.backend !== "opfs")) return false;
    try {
      appendOpfsPatch(path, logicalSize, offsets, lengths, value);
      // One committed record may contain many ranges; flush exactly once after
      // the complete transaction (and any bounded checkpoint) has been appended.
      flushOpfsSync();
      removeRegionCache(path);
      return true;
    } catch (error) {
      storageStats.writeErrors++;
      reportStorageError(error);
      return false;
    }
  };
  root.__gaiusFsDelete = (path) => {
    if (!storageLifecycleIsActive(generation)) return false;
    path = normalize(path);
    if (isRegionPath(path)) {
      if (opfsAccessHandle && regionIndex.has(path)) {
        try {
          appendOpfsRegion(path, null, true);
          flushOpfsSync();
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
  root.__gaiusFsFlush = () => {
    if (!storageLifecycleIsActive(generation)) {
      return Promise.reject(new Error("Persistent storage is closed"));
    }
    return flushPendingChanges();
  };
  root.__gaiusFsStorageSnapshot = () => ({...refreshStorageStats()});
  refreshStorageStats();
  return true;
}

function openDatabase() {
  return new Promise((resolve, reject) => {
    const databaseName = requiredStorageIdentifier(
      root.__gaiusStorageDatabaseName,
      "storageDatabaseName",
    );
    const schema = requiredStorageInteger(root.__gaiusStorageSchema, "storageSchema");
    const request = indexedDB.open(databaseName, schema);
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

async function openOpfsRegionStore(worldId, generation = storageGeneration) {
  assertStorageLifecycleIsActive(generation);
  if (typeof Proxy !== "function" || typeof navigator === "undefined" ||
      !navigator.storage || typeof navigator.storage.getDirectory !== "function") {
    return false;
  }
  try {
    const storageRoot = await navigator.storage.getDirectory();
    assertStorageLifecycleIsActive(generation);
    const gaiusDirectory = await storageRoot.getDirectoryHandle("gaius", {create: true});
    assertStorageLifecycleIsActive(generation);
    const regionDirectory = await gaiusDirectory.getDirectoryHandle(
      requiredStorageIdentifier(
        root.__gaiusStorageOpfsDirectory,
        "storageOpfsDirectory",
      ),
      {create: true},
    );
    assertStorageLifecycleIsActive(generation);
    const fileHandle = await regionDirectory.getFileHandle(
      opfsContainerName(worldId),
      {create: true},
    );
    assertStorageLifecycleIsActive(generation);
    if (typeof fileHandle.createSyncAccessHandle !== "function") {
      return false;
    }
    const accessHandle = await fileHandle.createSyncAccessHandle();
    if (!storageLifecycleIsActive(generation)) {
      try { accessHandle.close(); } catch (ignored) {}
      return false;
    }
    opfsAccessHandle = accessHandle;
    scanOpfsRegionStore();
    return true;
  } catch (error) {
    if (opfsAccessHandle) {
      try { opfsAccessHandle.close(); } catch (ignored) {}
    }
    opfsAccessHandle = undefined;
    if (storageLifecycleIsActive(generation)) {
      postMessage({
        type: "storage-opfs-fallback",
        detail: String(error && (error.stack || error.message) || error),
      });
    }
    if (!storageLifecycleIsActive(generation)) return false;
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
  const prefix = new Uint8Array(8);
  const decoder = new TextDecoder();
  const worldPrefix = activeWorldPrefix();
  let offset = 0;
  while (offset + prefix.byteLength <= size) {
    if (readSync(opfsAccessHandle, prefix, offset) !== prefix.byteLength) break;
    const view = new DataView(prefix.buffer, prefix.byteOffset, prefix.byteLength);
    const magic = view.getUint32(0, true);
    const version = view.getUint32(4, true);
    let recordLength = 0;
    if (magic === opfsRecordMagic && version === opfsRecordVersion) {
      recordLength = scanOpfsV1Record(offset, size, decoder, worldPrefix);
    } else if (magic === opfsPatchRecordMagic && version === opfsPatchRecordVersion) {
      recordLength = scanOpfsV2Record(offset, size, decoder, worldPrefix);
    }
    if (recordLength <= 0) break;
    storageStats.opfsScanRecords++;
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

function scanOpfsV1Record(offset, size, decoder, worldPrefix) {
  if (offset + opfsRecordHeaderBytes > size) return 0;
  const header = new Uint8Array(opfsRecordHeaderBytes);
  if (readSync(opfsAccessHandle, header, offset) !== header.byteLength) return 0;
  const view = new DataView(header.buffer, header.byteOffset, header.byteLength);
  const state = view.getUint32(8, true);
  const pathLength = view.getUint32(12, true);
  const dataLength = view.getUint32(16, true);
  const recordLength = view.getUint32(20, true);
  if ((state !== opfsRecordLive && state !== opfsRecordDeleted) ||
      pathLength === 0 || pathLength > 4096 ||
      recordLength !== opfsRecordHeaderBytes + pathLength + dataLength ||
      offset + recordLength > size) {
    return 0;
  }
  const pathBytes = new Uint8Array(pathLength);
  if (readSync(opfsAccessHandle, pathBytes, offset + opfsRecordHeaderBytes) !== pathLength) {
    return 0;
  }
  const path = normalize(decoder.decode(pathBytes));
  if (path.startsWith(worldPrefix) && isRegionPath(path)) {
    if (state === opfsRecordDeleted) {
      regionIndex.delete(path);
    } else {
      const generation = nextOpfsRegionGeneration(regionIndex.get(path));
      regionIndex.set(path, fullOpfsRegionEntry(
        offset + opfsRecordHeaderBytes + pathLength,
        dataLength,
        generation,
      ));
    }
  }
  storageStats.opfsScanV1Records++;
  return recordLength;
}

function scanOpfsV2Record(offset, size, decoder, worldPrefix) {
  if (offset + opfsPatchRecordHeaderBytes > size) return 0;
  const header = new Uint8Array(opfsPatchRecordHeaderBytes);
  if (readSync(opfsAccessHandle, header, offset) !== header.byteLength) return 0;
  const view = new DataView(header.buffer, header.byteOffset, header.byteLength);
  const state = view.getUint32(8, true);
  const pathLength = view.getUint32(12, true);
  const logicalSize = view.getUint32(16, true);
  const rangeCount = view.getUint32(20, true);
  const metadataLength = view.getUint32(24, true);
  const payloadLength = view.getUint32(28, true);
  const recordLength = view.getUint32(32, true);
  const generation = view.getUint32(36, true);
  const checksum = view.getUint32(40, true);
  const commitMagic = view.getUint32(44, true);
  if (state !== opfsPatchRecordState || commitMagic !== opfsPatchCommitMagic ||
      pathLength === 0 || pathLength > 4096 ||
      rangeCount > maximumOpfsPatchRanges || metadataLength !== rangeCount * 8 ||
      generation === 0 ||
      recordLength !== opfsPatchRecordHeaderBytes + pathLength + metadataLength + payloadLength ||
      offset + recordLength > size) {
    return 0;
  }

  const pathBytes = new Uint8Array(pathLength);
  const metadata = new Uint8Array(metadataLength);
  const payload = new Uint8Array(payloadLength);
  let cursor = offset + opfsPatchRecordHeaderBytes;
  if (readSync(opfsAccessHandle, pathBytes, cursor) !== pathLength) return 0;
  cursor += pathLength;
  if (readSync(opfsAccessHandle, metadata, cursor) !== metadataLength) return 0;
  cursor += metadataLength;
  if (readSync(opfsAccessHandle, payload, cursor) !== payloadLength) return 0;
  if (opfsPatchChecksum(pathBytes, metadata, payload, logicalSize, generation) !== checksum) {
    return 0;
  }

  const ranges = decodeOpfsPatchRanges(metadata, logicalSize, payloadLength);
  if (!ranges) return 0;
  const path = normalize(decoder.decode(pathBytes));
  if (path.startsWith(worldPrefix) && isRegionPath(path)) {
    const previous = regionIndex.get(path);
    if (generation !== nextOpfsRegionGeneration(previous)) return 0;
    regionIndex.set(path, patchedOpfsRegionEntry(
      previous,
      logicalSize,
      generation,
      cursor,
      payloadLength,
      ranges,
    ));
  }
  storageStats.opfsScanV2Records++;
  return recordLength;
}

function fullOpfsRegionEntry(dataOffset, dataLength, generation) {
  return {
    backend: "opfs",
    offset: dataOffset,
    length: dataLength,
    baseOffset: dataOffset,
    baseLength: dataLength,
    logicalSize: dataLength,
    generation,
    patches: [],
    patchBytes: 0,
  };
}

function patchedOpfsRegionEntry(
  previous, logicalSize, generation, dataOffset, payloadLength, ranges,
) {
  const hasBase = previous && previous.backend === "opfs";
  const patches = hasBase && Array.isArray(previous.patches)
    ? previous.patches.slice()
    : [];
  patches.push({dataOffset, payloadLength, logicalSize, ranges});
  return {
    backend: "opfs",
    offset: hasBase ? previous.baseOffset : undefined,
    length: logicalSize,
    baseOffset: hasBase ? previous.baseOffset : undefined,
    baseLength: hasBase ? previous.baseLength : 0,
    logicalSize,
    generation,
    patches,
    patchBytes: (hasBase ? Number(previous.patchBytes) || 0 : 0) + payloadLength,
  };
}

function nextOpfsRegionGeneration(previous) {
  const next = (((previous && Number(previous.generation)) || 0) + 1) >>> 0;
  return next || 1;
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
  const generation = nextOpfsRegionGeneration(regionIndex.get(path));
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
    regionIndex.set(path, fullOpfsRegionEntry(
      offset + opfsRecordHeaderBytes + pathBytes.byteLength,
      bytes.byteLength,
      generation,
    ));
    storageStats.opfsFullWrites++;
    storageStats.opfsFullWriteBytes += bytes.byteLength;
  }
  refreshStorageStats();
}

function appendOpfsPatch(path, logicalSizeValue, offsets, lengths, value) {
  if (!opfsAccessHandle) {
    throw new Error("OPFS region handle is unavailable");
  }
  const logicalSize = Number(logicalSizeValue);
  const payload = toUint8Array(value);
  const rangeCount = Number(offsets && offsets.length);
  if (!Number.isInteger(logicalSize) || logicalSize < 0 || logicalSize > 0xffffffff ||
      !payload || !Number.isInteger(rangeCount) || rangeCount < 0 ||
      rangeCount > maximumOpfsPatchRanges || !lengths || lengths.length !== rangeCount) {
    throw new Error("Invalid OPFS region patch for " + path);
  }

  const metadata = new Uint8Array(rangeCount * 8);
  const metadataView = new DataView(metadata.buffer);
  let payloadLength = 0;
  let previousEnd = 0;
  for (let index = 0; index < rangeCount; index++) {
    const rangeOffset = Number(offsets[index]);
    const rangeLength = Number(lengths[index]);
    const rangeEnd = rangeOffset + rangeLength;
    if (!Number.isInteger(rangeOffset) || !Number.isInteger(rangeLength) ||
        rangeOffset < previousEnd || rangeLength <= 0 || rangeEnd > logicalSize) {
      throw new Error("Invalid OPFS patch range " + index + " for " + path);
    }
    metadataView.setUint32(index * 8, rangeOffset, true);
    metadataView.setUint32(index * 8 + 4, rangeLength, true);
    payloadLength += rangeLength;
    previousEnd = rangeEnd;
  }
  if (payloadLength !== payload.byteLength) {
    throw new Error("OPFS patch payload length mismatch for " + path);
  }

  let previous = regionIndex.get(path);
  if (shouldCheckpointOpfsRegion(previous, payloadLength)) {
    checkpointOpfsRegion(path, previous);
    previous = regionIndex.get(path);
  }
  const generation = nextOpfsRegionGeneration(previous);
  const pathBytes = new TextEncoder().encode(path);
  if (pathBytes.byteLength === 0 || pathBytes.byteLength > 4096) {
    throw new Error("Invalid OPFS patch path length for " + path);
  }
  const recordLength = opfsPatchRecordHeaderBytes + pathBytes.byteLength +
    metadata.byteLength + payload.byteLength;
  if (recordLength > 0xffffffff) {
    throw new Error("OPFS patch record is too large for " + path);
  }

  const offset = opfsAppendOffset;
  let cursor = offset + opfsPatchRecordHeaderBytes;
  writeSync(opfsAccessHandle, new Uint8Array(opfsPatchRecordHeaderBytes), offset);
  writeSync(opfsAccessHandle, pathBytes, cursor);
  cursor += pathBytes.byteLength;
  if (metadata.byteLength > 0) {
    writeSync(opfsAccessHandle, metadata, cursor);
  }
  cursor += metadata.byteLength;
  if (payload.byteLength > 0) {
    writeSync(opfsAccessHandle, payload, cursor);
  }

  const header = new Uint8Array(opfsPatchRecordHeaderBytes);
  const view = new DataView(header.buffer);
  view.setUint32(0, opfsPatchRecordMagic, true);
  view.setUint32(4, opfsPatchRecordVersion, true);
  view.setUint32(8, opfsPatchRecordState, true);
  view.setUint32(12, pathBytes.byteLength, true);
  view.setUint32(16, logicalSize, true);
  view.setUint32(20, rangeCount, true);
  view.setUint32(24, metadata.byteLength, true);
  view.setUint32(28, payload.byteLength, true);
  view.setUint32(32, recordLength, true);
  view.setUint32(36, generation, true);
  view.setUint32(
    40,
    opfsPatchChecksum(pathBytes, metadata, payload, logicalSize, generation),
    true,
  );
  view.setUint32(44, opfsPatchCommitMagic, true);
  writeSync(opfsAccessHandle, header, offset);

  opfsAppendOffset += recordLength;
  opfsDirty = true;
  storageStats.opfsFileBytes = opfsAppendOffset;
  const ranges = decodeOpfsPatchRanges(metadata, logicalSize, payload.byteLength);
  if (!ranges) {
    throw new Error("Could not decode committed OPFS patch ranges for " + path);
  }
  regionIndex.set(path, patchedOpfsRegionEntry(
    previous,
    logicalSize,
    generation,
    cursor,
    payload.byteLength,
    ranges,
  ));
  storageStats.opfsPatchWrites++;
  storageStats.opfsPatchPayloadBytes += payload.byteLength;
  storageStats.opfsPatchRanges += rangeCount;
  refreshStorageStats();
}

function decodeOpfsPatchRanges(metadata, logicalSize, payloadLength) {
  if (metadata.byteLength % 8 !== 0) return null;
  const ranges = [];
  const view = new DataView(metadata.buffer, metadata.byteOffset, metadata.byteLength);
  let previousEnd = 0;
  let payloadOffset = 0;
  for (let index = 0; index < metadata.byteLength / 8; index++) {
    const offset = view.getUint32(index * 8, true);
    const length = view.getUint32(index * 8 + 4, true);
    const end = offset + length;
    if (length === 0 || offset < previousEnd || end > logicalSize ||
        payloadOffset + length > payloadLength) {
      return null;
    }
    ranges.push({offset, length, payloadOffset});
    previousEnd = end;
    payloadOffset += length;
  }
  return payloadOffset === payloadLength ? ranges : null;
}

function shouldCheckpointOpfsRegion(indexed, incomingPayloadBytes) {
  if (!indexed || indexed.backend !== "opfs") return false;
  const patches = Array.isArray(indexed.patches) ? indexed.patches.length : 0;
  const patchBytes = Number(indexed.patchBytes) || 0;
  return patches >= maximumOpfsPatchChainRecords ||
    patchBytes + incomingPayloadBytes > maximumOpfsPatchChainBytes;
}

function checkpointOpfsRegion(path, indexed) {
  const bytes = materializeOpfsRegion(indexed);
  storageStats.opfsPatchCheckpoints++;
  appendOpfsRegion(path, bytes, false);
}

function materializeOpfsRegion(indexed) {
  const baseLength = Math.max(0, Number(indexed.baseLength) || 0);
  let bytes = new Uint8Array(baseLength);
  if (baseLength > 0) {
    if (readSync(opfsAccessHandle, bytes, indexed.baseOffset) !== baseLength) {
      throw new Error("Could not read complete OPFS region base");
    }
  }
  const patches = Array.isArray(indexed.patches) ? indexed.patches : [];
  for (const patch of patches) {
    if (bytes.byteLength !== patch.logicalSize) {
      const resized = new Uint8Array(patch.logicalSize);
      resized.set(bytes.subarray(0, Math.min(bytes.byteLength, resized.byteLength)));
      bytes = resized;
    }
    const payload = new Uint8Array(patch.payloadLength);
    if (readSync(opfsAccessHandle, payload, patch.dataOffset) !== payload.byteLength) {
      throw new Error("Could not read complete OPFS region patch");
    }
    for (const range of patch.ranges) {
      bytes.set(
        payload.subarray(range.payloadOffset, range.payloadOffset + range.length),
        range.offset,
      );
    }
  }
  const logicalSize = Math.max(0, Number(indexed.logicalSize ?? indexed.length) || 0);
  if (bytes.byteLength !== logicalSize) {
    const resized = new Uint8Array(logicalSize);
    resized.set(bytes.subarray(0, Math.min(bytes.byteLength, resized.byteLength)));
    bytes = resized;
  }
  storageStats.opfsReconstructedRegions++;
  return bytes;
}

function opfsPatchChecksum(pathBytes, metadata, payload, logicalSize, generation) {
  const scalar = new Uint8Array(8);
  const scalarView = new DataView(scalar.buffer);
  scalarView.setUint32(0, logicalSize, true);
  scalarView.setUint32(4, generation, true);
  let crc = 0xffffffff;
  crc = updateOpfsCrc32(crc, pathBytes);
  crc = updateOpfsCrc32(crc, scalar);
  crc = updateOpfsCrc32(crc, metadata);
  crc = updateOpfsCrc32(crc, payload);
  return (crc ^ 0xffffffff) >>> 0;
}

function updateOpfsCrc32(crc, bytes) {
  if (!opfsCrcTable) {
    opfsCrcTable = new Uint32Array(256);
    for (let index = 0; index < opfsCrcTable.length; index++) {
      let value = index;
      for (let bit = 0; bit < 8; bit++) {
        value = (value >>> 1) ^ ((value & 1) ? 0xedb88320 : 0);
      }
      opfsCrcTable[index] = value >>> 0;
    }
  }
  for (let index = 0; index < bytes.byteLength; index++) {
    crc = (crc >>> 8) ^ opfsCrcTable[(crc ^ bytes[index]) & 0xff];
  }
  return crc >>> 0;
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

async function readWorldFiles(worldId, generation = storageGeneration) {
  assertStorageLifecycleIsActive(generation);
  const worldPrefix = "/gaius/saves/" + String(worldId || "") + "/";
  const paths = await listStoredPaths(worldPrefix);
  assertStorageLifecycleIsActive(generation);
  const migratedPaths = [];
  for (const path of paths) {
    const entry = await readStoredRecord(path);
    assertStorageLifecycleIsActive(generation);
    if (!entry) continue;
    const value = await decodeStoredValue(path, entry.value);
    assertStorageLifecycleIsActive(generation);
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
    assertStorageLifecycleIsActive(generation);
    flushOpfsSync();
    await deleteStoredPaths(migratedPaths);
    assertStorageLifecycleIsActive(generation);
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
  flushAbortRequested = false;
  const pending = drainPendingChanges();
  const tracked = pending.finally(() => {
    if (flushInFlight === tracked) flushInFlight = undefined;
    activeFlushTransaction = undefined;
    flushAbortRequested = false;
  });
  flushInFlight = tracked;
  return flushInFlight;
}

async function drainPendingChanges() {
  try {
    while (opfsDirty || pendingChanges.size > 0) {
      throwIfFlushAborted();
      flushOpfsSync();
      if (pendingChanges.size === 0) continue;
      const changes = new Map(pendingChanges);
      pendingChanges.clear();
      try {
        await writeBatch(changes);
        throwIfFlushAborted();
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
  activeFlushTransaction = transaction;
  try {
    const store = transaction.objectStore(storeName);
    for (const [path, change] of changes) {
      if (change.value === null) {
        store.delete(path);
      } else {
        store.put({path, value: change.value});
      }
    }
    await transactionDone(transaction);
  } finally {
    if (activeFlushTransaction === transaction) activeFlushTransaction = undefined;
  }
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

function throwIfFlushAborted() {
  if (flushAbortRequested) {
    throw new Error("Persistent storage flush was aborted during shutdown");
  }
}

function requestFlushAbort() {
  if (!flushInFlight) return false;
  if (flushAbortRequested) return true;
  flushAbortRequested = true;
  storageStats.flushAbortRequests++;
  const transaction = activeFlushTransaction;
  if (!transaction || typeof transaction.abort !== "function") return false;
  try {
    transaction.abort();
  } catch (error) {
    reportStorageError(error);
  }
  return true;
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
  const startedAt = monotonicMillis();
  try {
    opfsAccessHandle.flush();
    opfsDirty = false;
  } finally {
    const elapsed = Math.max(0, monotonicMillis() - startedAt);
    storageStats.opfsFlushes++;
    storageStats.opfsFlushMillis += elapsed;
    storageStats.opfsMaxFlushMillis = Math.max(storageStats.opfsMaxFlushMillis, elapsed);
  }
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
  const bytes = materializeOpfsRegion(indexed);
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

function reportStorageFlushFailure(error) {
  const detail = String(error && (error.stack || error.message) || error);
  if (!storageFlushFailureReported) {
    storageFlushFailureReported = true;
    postMessage({type: "storage-flush-failed", detail});
  }
  // Preserve the existing storage-write-error evidence for clients that only
  // consume that channel, while the explicit flush-failed event makes it
  // impossible to mistake this path for a clean stop.
  reportStorageError(error);
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

function waitForPromiseSettlement(promise, timeoutMillis) {
  if (!promise) return Promise.resolve(true);
  // Always observe the original promise, even when the watchdog wins.  The
  // operation may still reject after the caller has moved on and must not
  // become an unhandled rejection.
  const observed = Promise.resolve(promise).then(
    () => true,
    () => true,
  );
  return withTimeout(observed, timeoutMillis, "Promise settlement timed out")
    .then(() => true, () => false);
}

async function flushWithWatchdog(timeoutMillis, detail) {
  const flush = flushPendingChanges();
  try {
    await withTimeout(flush, timeoutMillis, detail);
    return true;
  } catch (error) {
    if (String(error && error.message || error) !== String(detail)) {
      throw error;
    }
    storageStats.flushTimeouts++;
    requestFlushAbort();
    const settled = await waitForPromiseSettlement(
      flush,
      storageFlushAbortSettleMillis(),
    );
    if (!settled) {
      storageStats.flushAbortTimeouts++;
      reportStorageError(new Error(
        "Persistent storage flush abort did not settle within " +
          storageFlushAbortSettleMillis() + " ms",
      ));
    }
    throw error;
  }
}

function deferStorageCloseUntil(promise) {
  if (!promise) return;
  const retry = () => {
    if (!storageStartupInFlight && !flushInFlight && !activeFlushTransaction) {
      closeStorageHandles();
    }
  };
  // Observe the original promise even when the caller's startup watchdog
  // expired. This continuation is only a safety retry; it never posts a
  // clean stop on behalf of a timed-out shutdown.
  void Promise.resolve(promise).then(retry, retry);
}

function closeStorageHandles() {
  if (storageHandlesClosed) return true;
  // Closing either backend while a write is still running can invalidate the
  // transaction/SyncAccessHandle underneath the writer.  Leave ownership with
  // the settling promise and close only from a safe continuation.
  if (!storageStateIsClean()) return false;
  if (flushTimer !== undefined) {
    clearTimeout(flushTimer);
    flushTimer = undefined;
  }
  let failed = false;
  if (opfsAccessHandle) {
    try {
      opfsAccessHandle.close();
      opfsAccessHandle = undefined;
    } catch (error) {
      failed = true;
      reportStorageError(error);
    }
  }
  if (database) {
    try {
      database.close();
      database = undefined;
    } catch (error) {
      failed = true;
      reportStorageError(error);
    }
  }
  if (failed || opfsAccessHandle || database) return false;
  root.__gaiusFsPut = undefined;
  root.__gaiusFsPutBytes = undefined;
  root.__gaiusFsCanPatchBytes = undefined;
  root.__gaiusFsPatchBytes = undefined;
  root.__gaiusFsDelete = undefined;
  root.__gaiusFsFlush = undefined;
  root.__gaiusFsStorageSnapshot = undefined;
  storageHandlesClosed = true;
  return true;
}

async function closePersistentStorage(flush) {
  if (storageClosePromise) return storageClosePromise;
  invalidateStorageLifecycle();
  const startup = storageStartupInFlight;
  const operation = (async () => {
    if (startup) {
      const settled = await waitForPromiseSettlement(
        startup,
        storageStartupSettleMillis(),
      );
      if (!settled) {
        reportStorageError(new Error(
          "Persistent storage startup did not settle within " +
            storageStartupSettleMillis() + " ms",
        ));
        deferStorageCloseUntil(startup);
        return false;
      }
    }
    if (flush) {
      if (!await flushForShutdown()) return false;
    }
    if (!storageStateIsClean()) {
      reportStorageError(new Error(
        "Persistent storage close refused while changes remain pending",
      ));
      return false;
    }
    return closeStorageHandles();
  })();
  const wrapped = operation.then(
    (result) => {
      // A failed close is retryable once the original startup/flush promise
      // settles. Do not cache false forever and accidentally turn a later
      // safe close into a false result.
      if (storageClosePromise === wrapped && !result) {
        storageClosePromise = undefined;
      }
      return result;
    },
    (error) => {
      if (storageClosePromise === wrapped) storageClosePromise = undefined;
      reportStorageFlushFailure(error);
      return false;
    },
  );
  storageClosePromise = wrapped;
  return wrapped;
}

function isRegionPath(path) {
  path = String(path || "");
  return path.endsWith(".mca") || path.endsWith(".mcc");
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
  // Collapse "." and redundant segments so write and read keys agree even when
  // Java-side Path.resolve introduces "./" (e.g. world_gen_settings.dat).
  const absolute = path.startsWith("/");
  const parts = path.split("/");
  const kept = [];
  for (let index = 0; index < parts.length; index++) {
    const part = parts[index];
    if (part === "" || part === ".") continue;
    if (part === "..") {
      if (kept.length > 0) kept.pop();
      continue;
    }
    kept.push(part);
  }
  return (absolute ? "/" : "") + kept.join("/");
}
