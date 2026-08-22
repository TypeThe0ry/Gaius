import fs from "node:fs";
import {createHash} from "node:crypto";
import {Session as InspectorSession} from "node:inspector";
import {performance} from "node:perf_hooks";
import vm from "node:vm";
import {inflateSync} from "node:zlib";
import {basename, isAbsolute} from "node:path";
import {fileURLToPath, pathToFileURL} from "node:url";
import {
  MessageChannel,
  MessagePort,
  Worker,
  isMainThread,
  parentPort,
  workerData,
} from "node:worker_threads";

const rootDirectory = fileURLToPath(new URL("../../", import.meta.url));
const nativePath = (value) => {
  if (!value) return value;
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
};
const portConfig = JSON.parse(fs.readFileSync(rootDirectory + "port/config.json", "utf8"));
const versionProfileRelative = nativePath(
  process.env.GAIUS_VERSION_PROFILE_PATH || String(portConfig.versionProfile || ""),
);
const profileId = basename(versionProfileRelative.replaceAll("\\", "/"))
  .replace(/\.json$/, "") || "26.2";
const versionProfilePath = isAbsolute(versionProfileRelative)
  ? versionProfileRelative
  : rootDirectory + "port/" + versionProfileRelative;
const isolated = Boolean(process.env.GAIUS_BUILD_ROOT || process.env.GAIUS_VERSION_PROFILE_PATH);
const distDirectory = nativePath(process.env.GAIUS_DIST_DIRECTORY ||
  (isolated ? rootDirectory + "port/web/dist/" + profileId : rootDirectory + "port/web/dist"));
const buildDirectory = nativePath(process.env.GAIUS_BUILD_ROOT ||
  (isolated ? rootDirectory + "port/target/" + profileId : rootDirectory + "port/target"));
const bootstrapPath = nativePath(process.env.GAIUS_SMOKE_BOOTSTRAP_PATH ||
  distDirectory + "/singleplayer-server-worker.js");
if (!isAbsolute(versionProfileRelative)
    && !/^versions\/[A-Za-z0-9._-]+\.json$/.test(versionProfileRelative.replaceAll("\\", "/"))) {
  throw new Error("the active version profile path is invalid");
}
const activeVersionProfile = JSON.parse(fs.readFileSync(
  versionProfilePath,
  "utf8",
));
const storageProfileId = String(activeVersionProfile.id || "");
const worldVersion = Number(activeVersionProfile.worldVersion);
const profileStorage = activeVersionProfile.storage || {};
const storageConfig = Object.freeze({
  profileId: storageProfileId,
  worldVersion,
  storageSchema: Number(profileStorage.schema),
  storageDatabaseName: String(profileStorage.databaseName || ""),
  storagePrefix: String(profileStorage.prefix || ""),
  storageOpfsDirectory: String(profileStorage.opfsDirectory || ""),
});
if (!storageConfig.profileId || !Number.isSafeInteger(storageConfig.worldVersion) ||
    storageConfig.worldVersion <= 0 || storageConfig.storageSchema !== 2 ||
    storageConfig.storageDatabaseName !== `gaius-fs-v2-${storageConfig.profileId}` ||
    storageConfig.storagePrefix !== `gaius.fs.v2:${storageConfig.profileId}:` ||
    storageConfig.storageOpfsDirectory !== `regions-v2-${storageConfig.profileId}`) {
  throw new Error(
    `The active version profile has an invalid schema-2 storage namespace: ${
      JSON.stringify(activeVersionProfile.storage)}`,
  );
}
const activeProtocolVersion = Number(activeVersionProfile.protocolVersion);
if (!Number.isSafeInteger(activeProtocolVersion) || activeProtocolVersion < 0) {
  throw new Error("The active version profile has an invalid protocolVersion");
}
const playProtocols = {
  774: {
    itemEntityTypeId: 71,
    clientbound: {
      addEntity: 1,
      blockChangedAck: 4,
      blockUpdate: 8,
      disconnect: 32,
      keepAlive: 43,
      levelChunkWithLight: 44,
      login: 48,
      ping: 59,
      playerPosition: 70,
      setChunkCacheCenter: 92,
    },
    serverbound: {
      acceptTeleportation: 0,
      chatCommand: 6,
      chunkBatchReceived: 10,
      keepAlive: 27,
      movePlayerPos: 29,
      playerAction: 40,
      playerLoaded: 43,
      pong: 44,
    },
  },
  776: {
    itemEntityTypeId: 71,
    clientbound: {
      addEntity: 1,
      blockChangedAck: 4,
      blockUpdate: 8,
      disconnect: 32,
      keepAlive: 44,
      levelChunkWithLight: 45,
      login: 49,
      ping: 61,
      playerPosition: 72,
      setChunkCacheCenter: 94,
    },
    serverbound: {
      acceptTeleportation: 0,
      chatCommand: 7,
      chunkBatchReceived: 11,
      keepAlive: 28,
      movePlayerPos: 30,
      playerAction: 41,
      playerLoaded: 44,
      pong: 45,
    },
  },
};
const activePlayProtocol = playProtocols[activeProtocolVersion];
if (!activePlayProtocol) {
  throw new Error(`No PLAY packet table exists for protocol ${activeProtocolVersion}`);
}
const {clientbound: clientboundPlay, serverbound: serverboundPlay} = activePlayProtocol;
const itemEntityTypeId = activePlayProtocol.itemEntityTypeId;
const requiredNetworkTaskTelemetryFields = Object.freeze([
  "errors",
  "inboundQueuedBytes",
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

// The Worker build intentionally bypasses BrowserCooperativeExecutor.  The
// integrated-server network pump counters therefore remain zero in that mode;
// only a runtime that actually uses the cooperative pump can be required to
// demonstrate pump activity.  Keep this list limited to successful activity
// counters so a failure cannot make an otherwise direct-executor run look
// active.
const cooperativePumpActivityFields = Object.freeze([
  "integratedServerPumpRequests",
  "integratedServerPumpStarts",
  "integratedServerTaskSignals",
  "integratedServerTaskSchedules",
]);

function hasCooperativePumpActivity(stats) {
  if (stats === null || typeof stats !== "object") {
    return false;
  }
  return cooperativePumpActivityFields.some((field) => Number(stats[field]) > 0);
}

function resolveCooperativePumpMode(stats, expected = false) {
  const active = hasCooperativePumpActivity(stats);
  return {
    expected: Boolean(expected),
    active,
    requireActivity: Boolean(expected) || active,
  };
}

function taskRunSkewAllowance(options = {}) {
  // Telemetry counters are deltas from the first pong in a measurement window.
  // A task already pending at that baseline contributes a run without a
  // matching schedule in the window.  Only the one-bit pending gauge can
  // justify that one-run allowance; unknown/malformed values get no allowance.
  return options.initialIntegratedServerTaskPending === 1 ? 1 : 0;
}

function validateNetworkTaskTelemetry(stats, options = {}) {
  const missingFields = [];
  const nonFiniteFields = [];
  const nonIntegerFields = [];
  const negativeFields = [];
  const relationshipErrors = [];
  const healthErrors = [];
  const fieldValues = Object.create(null);
  const runSkewAllowance = taskRunSkewAllowance(options);
  const objectStats = stats !== null && typeof stats === "object" ? stats : null;
  for (const field of requiredNetworkTaskTelemetryFields) {
    if (!objectStats || !Object.prototype.hasOwnProperty.call(objectStats, field)) {
      missingFields.push(field);
      continue;
    }
    const value = objectStats[field];
    fieldValues[field] = value;
    if (typeof value !== "number" || !Number.isFinite(value)) {
      nonFiniteFields.push(field);
      continue;
    }
    if (!Number.isInteger(value)) {
      nonIntegerFields.push(field);
    }
    if (value < 0) {
      negativeFields.push(field);
    }
  }
  const fieldComplete = missingFields.length === 0 && nonFiniteFields.length === 0;
  if (fieldComplete && nonIntegerFields.length === 0 && negativeFields.length === 0) {
    if (fieldValues.integratedServerTaskPending !== 0 &&
        fieldValues.integratedServerTaskPending !== 1) {
      relationshipErrors.push("integratedServerTaskPending must be 0 or 1");
    }
    if (fieldValues.integratedServerInputPending !== 0 &&
        fieldValues.integratedServerInputPending !== 1) {
      relationshipErrors.push("integratedServerInputPending must be 0 or 1");
    }
    if (fieldValues.integratedServerPumpStarts >
        fieldValues.integratedServerPumpRequests +
          fieldValues.integratedServerPumpRetrySchedules) {
      relationshipErrors.push("integrated pump starts exceed requests plus retries");
    }
    if (fieldValues.integratedServerPumpFailures >
        fieldValues.integratedServerPumpStarts) {
      relationshipErrors.push("integrated pump failures exceed starts");
    }
    if (fieldValues.integratedServerPumpRetrySchedules >
        fieldValues.integratedServerPumpFailures) {
      relationshipErrors.push("integrated pump retries exceed failures");
    }
    if (fieldValues.integratedServerPumpRetryExhaustions >
        fieldValues.integratedServerPumpFailures) {
      relationshipErrors.push("integrated pump retry exhaustion exceeds failures");
    }
    if (fieldValues.integratedServerPumpRetrySchedules +
        fieldValues.integratedServerPumpRetryExhaustions !==
          fieldValues.integratedServerPumpFailures) {
      relationshipErrors.push("integrated pump failure accounting is incomplete");
    }
    if (fieldValues.integratedServerTaskUnparks !==
        fieldValues.integratedServerTaskSchedules +
          fieldValues.integratedServerTaskCoalesced) {
      relationshipErrors.push("task unparks do not match schedules plus coalesced signals");
    }
    if (fieldValues.integratedServerTaskCoalesced >
        fieldValues.integratedServerTaskSignals +
          fieldValues.integratedServerTaskFollowups +
          fieldValues.integratedServerTaskDeferredRetries) {
      relationshipErrors.push("task coalesced count exceeds all schedule attempts");
    }
    if (fieldValues.integratedServerTaskSchedules -
        fieldValues.integratedServerTaskFollowups >
          fieldValues.integratedServerTaskSignals +
            fieldValues.integratedServerTaskDeferredRetries) {
      relationshipErrors.push("burst task schedules exceed signals plus deferred retries");
    }
    if (fieldValues.integratedServerTaskRuns >
        fieldValues.integratedServerTaskSchedules + runSkewAllowance) {
      relationshipErrors.push("task runs exceed task schedules");
    }
    if (fieldValues.integratedServerTaskFollowups >
        fieldValues.integratedServerTaskSchedules) {
      relationshipErrors.push("task followups exceed task schedules");
    }
    if (fieldValues.integratedServerTaskDeferredRetries +
        fieldValues.integratedServerTaskRetryExhaustions !==
          fieldValues.integratedServerTaskBudgetExhaustions) {
      relationshipErrors.push("task budget exhaustion accounting is incomplete");
    }
    if (options.requireActivity && fieldValues.integratedServerTaskSchedules <= 0) {
      relationshipErrors.push("task schedules are zero");
    }
    if (options.requireActivity && fieldValues.integratedServerTaskRuns <= 0) {
      relationshipErrors.push("task runs are zero");
    }
    if (options.requireActivity && fieldValues.integratedServerPumpRequests <= 0) {
      relationshipErrors.push("integrated pump requests are zero");
    }
    if (options.requireActivity && fieldValues.integratedServerPumpStarts <= 0) {
      relationshipErrors.push("integrated pump starts are zero");
    }
    if (options.requireDrained && fieldValues.inboundQueuedBytes !== 0) {
      relationshipErrors.push("inbound queue is not drained");
    }
    if (options.requireDrained && fieldValues.integratedServerTaskPending !== 0) {
      relationshipErrors.push("integrated server task is still pending");
    }
    if (options.requireDrained && fieldValues.integratedServerInputPending !== 0) {
      relationshipErrors.push("integrated server input is still pending");
    }
    if (options.requireDrained && fieldValues.integratedServerTaskRuns <
        fieldValues.integratedServerTaskSchedules) {
      relationshipErrors.push("scheduled integrated server tasks did not all run");
    }
    if (options.requireHealthy && fieldValues.errors !== 0) {
      healthErrors.push("network errors are non-zero");
    }
    if (options.requireHealthy && fieldValues.integratedServerPumpFailures !== 0) {
      healthErrors.push("integrated server pump failures are non-zero");
    }
    if (options.requireHealthy && fieldValues.integratedServerPumpRetryExhaustions !== 0) {
      healthErrors.push("integrated server pump retry exhaustion is non-zero");
    }
    if (options.requireHealthy && fieldValues.integratedServerTaskScheduleFailures !== 0) {
      healthErrors.push("integrated server task schedule failures are non-zero");
    }
    if (options.requireHealthy && fieldValues.integratedServerTaskLifecycleDrops !== 0) {
      healthErrors.push("integrated server task lifecycle drops are non-zero");
    }
    if (options.requireHealthy && fieldValues.integratedServerTaskWrongThread !== 0) {
      healthErrors.push("integrated server task wrong-thread runs are non-zero");
    }
    if (options.requireHealthy && fieldValues.integratedServerTaskRetryExhaustions !== 0) {
      healthErrors.push("integrated server task deferred retries exhausted");
    }
  }
  return {
    valid: fieldComplete && nonIntegerFields.length === 0 && negativeFields.length === 0 &&
      relationshipErrors.length === 0 && healthErrors.length === 0,
    fieldComplete,
    missingFields,
    nonFiniteFields,
    nonIntegerFields,
    negativeFields,
    taskRunSkewAllowance: runSkewAllowance,
    relationshipErrors,
    healthErrors,
  };
}

function copyObjectSnapshot(value) {
  return value !== null && typeof value === "object" ? {...value} : null;
}

// Telemetry pongs are the only samples that cross the Worker boundary after
// the measurement window is reset.  Keep each object detached from the
// structured-clone payload, and keep the update itself pure so a stale or
// cross-session pong cannot repopulate the previous window's auxiliary stats.
function snapshotTelemetryPong(message) {
  return {
    chunkPriorityStats: copyObjectSnapshot(message.chunkPriority),
    networkStats: copyObjectSnapshot(message.network),
    worldgenStats: copyObjectSnapshot(message.worldgen),
    storageStats: copyObjectSnapshot(message.storage),
  };
}

function updateLatestTelemetrySnapshots(latest, message, expectedSessionId) {
  if (message === null || typeof message !== "object" ||
      message.sessionId !== expectedSessionId) {
    return {...latest};
  }
  return {
    ...latest,
    ...snapshotTelemetryPong(message),
  };
}

function recentTelemetryAuxiliarySnapshot(samples, field, fallback) {
  for (let index = samples.length - 1; index >= 0; index--) {
    const sample = samples[index];
    if (sample && sample.available &&
        Object.prototype.hasOwnProperty.call(sample, field)) {
      return copyObjectSnapshot(sample[field]);
    }
  }
  return copyObjectSnapshot(fallback);
}

function runTelemetrySnapshotSelfSmoke() {
  const oldWindow = {
    chunkPriorityStats: {window: "old"},
    networkStats: {window: "old"},
    worldgenStats: {window: "old"},
    storageStats: {window: "old"},
  };
  const stalePong = {
    sessionId: "stale-session",
    chunkPriority: {window: "stale"},
    network: {window: "stale"},
    worldgen: {window: "stale"},
    storage: {window: "stale"},
  };
  const afterStale = updateLatestTelemetrySnapshots(
    oldWindow,
    stalePong,
    "active-session",
  );
  if (JSON.stringify(afterStale) !== JSON.stringify(oldWindow)) {
    throw new Error("stale telemetry pong polluted the latest snapshot");
  }
  const resetPong = {
    sessionId: "active-session",
    chunkPriority: {window: "reset"},
    network: {window: "reset"},
    worldgen: {window: "reset"},
    storage: {window: "reset"},
  };
  const afterReset = updateLatestTelemetrySnapshots(
    afterStale,
    resetPong,
    "active-session",
  );
  for (const field of [
    "chunkPriorityStats",
    "networkStats",
    "worldgenStats",
    "storageStats",
  ]) {
    if (afterReset[field]?.window !== "reset") {
      throw new Error(`reset telemetry snapshot did not replace ${field}`);
    }
  }
  // Ensure the returned objects are detached before the Worker payload can be
  // reused or mutated by a later measurement window.
  resetPong.storage.window = "mutated-after-snapshot";
  if (afterReset.storageStats.window !== "reset") {
    throw new Error("telemetry snapshot retained a mutable pong reference");
  }
  return {
    ok: true,
    staleSessionIgnored: true,
    resetAuxiliarySnapshotsReplaced: true,
    snapshotObjectsDetached: true,
  };
}

function runNetworkValidationSelfSmoke() {
  const zeroStats = Object.fromEntries(
    requiredNetworkTaskTelemetryFields.map((field) => [field, 0]),
  );
  zeroStats.integratedServerTaskPending = 0;
  zeroStats.integratedServerInputPending = 0;
  const directMode = resolveCooperativePumpMode(zeroStats, false);
  if (directMode.requireActivity || directMode.active) {
    throw new Error("direct-executor self-smoke incorrectly requires pump activity");
  }
  const directValidation = validateNetworkTaskTelemetry(zeroStats, {
    requireActivity: directMode.requireActivity,
    requireDrained: true,
    requireHealthy: true,
  });
  if (!directValidation.valid) {
    throw new Error(
      `direct-executor self-smoke rejected zero activity: ${JSON.stringify(directValidation)}`,
    );
  }

  const activeStats = {...zeroStats,
    integratedServerPumpRequests: 1,
    integratedServerPumpStarts: 1,
    integratedServerTaskSignals: 1,
    integratedServerTaskUnparks: 1,
    integratedServerTaskSchedules: 1,
    integratedServerTaskRuns: 1,
  };
  const activeMode = resolveCooperativePumpMode(activeStats, false);
  if (!activeMode.active || !activeMode.requireActivity) {
    throw new Error("active cooperative-pump self-smoke did not require activity");
  }
  const activeValidation = validateNetworkTaskTelemetry(activeStats, {
    requireActivity: activeMode.requireActivity,
    requireDrained: true,
    requireHealthy: true,
  });
  if (!activeValidation.valid) {
    throw new Error(
      `active cooperative-pump self-smoke rejected valid activity: ${JSON.stringify(activeValidation)}`,
    );
  }

  const expectedMode = resolveCooperativePumpMode(zeroStats, true);
  if (!expectedMode.requireActivity || expectedMode.active) {
    throw new Error("expected cooperative-pump self-smoke did not require activity");
  }
  const expectedValidation = validateNetworkTaskTelemetry(zeroStats, {
    requireActivity: expectedMode.requireActivity,
    requireDrained: true,
    requireHealthy: true,
  });
  if (expectedValidation.valid ||
      !expectedValidation.relationshipErrors.includes("task schedules are zero") ||
      !expectedValidation.relationshipErrors.includes("integrated pump requests are zero")) {
    throw new Error(
      `expected cooperative-pump self-smoke failed to reject missing activity: ${JSON.stringify(expectedValidation)}`,
    );
  }

  const inFlightStats = {...zeroStats, integratedServerTaskRuns: 1};
  const inFlightValidation = validateNetworkTaskTelemetry(inFlightStats, {
    requireActivity: false,
    requireDrained: true,
    initialIntegratedServerTaskPending: 1,
    requireHealthy: true,
  });
  if (!inFlightValidation.valid || inFlightValidation.taskRunSkewAllowance !== 1) {
    throw new Error(
      `in-flight baseline self-smoke rejected the permitted run: ${JSON.stringify(inFlightValidation)}`,
    );
  }
  const overrunValidation = validateNetworkTaskTelemetry(
    {...inFlightStats, integratedServerTaskRuns: 2},
    {
      requireActivity: false,
      requireDrained: true,
      initialIntegratedServerTaskPending: 1,
      requireHealthy: true,
    },
  );
  if (overrunValidation.valid ||
      !overrunValidation.relationshipErrors.includes("task runs exceed task schedules")) {
    throw new Error("in-flight baseline self-smoke failed to reject a second run");
  }

  const failedDirectStats = {...zeroStats, errors: 1};
  const failedDirectValidation = validateNetworkTaskTelemetry(failedDirectStats, {
    requireActivity: false,
    requireDrained: true,
    requireHealthy: true,
  });
  if (failedDirectValidation.valid ||
      !failedDirectValidation.healthErrors.includes("network errors are non-zero")) {
    throw new Error("direct-executor self-smoke failed to reject a network error");
  }
  const queuedDirectStats = {...zeroStats, inboundQueuedBytes: 1};
  const queuedDirectValidation = validateNetworkTaskTelemetry(queuedDirectStats, {
    requireActivity: false,
    requireDrained: true,
    requireHealthy: true,
  });
  if (queuedDirectValidation.valid ||
      !queuedDirectValidation.relationshipErrors.includes("inbound queue is not drained")) {
    throw new Error("direct-executor self-smoke failed to reject queued input");
  }
  return {
    ok: true,
    directExecutorZeroActivity: true,
    activeCooperativePump: true,
    expectedCooperativePumpMissingActivity: true,
    inFlightRunAllowance: true,
    overrunStillRejected: true,
    directExecutorFailureStillRejected: true,
    directExecutorQueueStillRejected: true,
  };
}

if (isMainThread && process.env.GAIUS_SMOKE_SELF_TEST === "1") {
  process.stdout.write(JSON.stringify({
    ...runNetworkValidationSelfSmoke(),
    telemetrySnapshots: runTelemetrySnapshotSelfSmoke(),
  }) + "\n");
  process.exit(0);
}

function networkDrainSignature(stats) {
  const validation = validateNetworkTaskTelemetry(stats);
  if (!validation.fieldComplete || validation.nonIntegerFields.length > 0 ||
      validation.negativeFields.length > 0) {
    return undefined;
  }
  return requiredNetworkTaskTelemetryFields.map((field) => stats[field]).join("/");
}

if (isMainThread) {
  const smokeStartedAt = Date.now();
  const events = [];
  let finished = false;
  const skipMining = process.env.GAIUS_SMOKE_SKIP_MINING === "1";
  const stopAtFirstChunk = process.env.GAIUS_SMOKE_STOP_AT_FIRST_CHUNK === "1";
  const roamSteps = Number(process.env.GAIUS_SMOKE_ROAM_STEPS || "0");
  const roamStepBlocks = Number(process.env.GAIUS_SMOKE_ROAM_STEP_BLOCKS || "8");
  const roamTimeoutMs = Number(process.env.GAIUS_SMOKE_ROAM_TIMEOUT_MS || "30000");
  const roamSpectator = process.env.GAIUS_SMOKE_ROAM_SPECTATOR === "1";
  const requireBlockDrop = process.env.GAIUS_SMOKE_REQUIRE_BLOCK_DROP === "1";
  const jsonOnly = process.env.GAIUS_SMOKE_JSON_ONLY === "1";
  const traceEvents = process.env.GAIUS_SMOKE_TRACE_EVENTS === "1";
  const blockDropTimeoutMs = Number(
    process.env.GAIUS_SMOKE_BLOCK_DROP_TIMEOUT_MS || "5000",
  );
  const blockReboundWindowMs = Number(
    process.env.GAIUS_SMOKE_BLOCK_REBOUND_WINDOW_MS || "1000",
  );
  const blockActionHoldMs = Number(
    process.env.GAIUS_SMOKE_BLOCK_ACTION_HOLD_MS ||
      (requireBlockDrop ? "750" : "8000"),
  );
  const chunkBatchAckDelayMs = Number(
    process.env.GAIUS_SMOKE_CHUNK_BATCH_ACK_DELAY_MS || "250",
  );
  const chunkBatchDesiredRate = Number(
    process.env.GAIUS_SMOKE_CHUNK_BATCH_DESIRED_RATE || "10",
  );
  const cooperativePumpExpected = process.env.GAIUS_SMOKE_EXPECT_COOPERATIVE_PUMP === "1";
  const maximumGameplayStallMs = Number(
    process.env.GAIUS_SMOKE_MAX_GAMEPLAY_STALL_MS || "500",
  );
  const telemetryBarrierTimeoutMs = Number(
    process.env.GAIUS_SMOKE_TELEMETRY_BARRIER_TIMEOUT_MS || "1000",
  );
  const telemetryBarrierSampleCount = 2;
  const telemetryBarrierMaxAttempts = Number(
    process.env.GAIUS_SMOKE_TELEMETRY_BARRIER_MAX_ATTEMPTS || "20",
  );
  const telemetryBarrierSampleDelayMs = Number(
    process.env.GAIUS_SMOKE_TELEMETRY_BARRIER_SAMPLE_DELAY_MS || "25",
  );
  const distanceRampIntervalMillis = process.env.GAIUS_SMOKE_DISTANCE_RAMP_MS === undefined
    ? undefined
    : Number(process.env.GAIUS_SMOKE_DISTANCE_RAMP_MS);
  const targetRenderDistance = Number(process.env.GAIUS_SMOKE_RENDER_DISTANCE || "7");
  const targetSimulationDistance = Number(process.env.GAIUS_SMOKE_SIMULATION_DISTANCE || "3");
  const cpuProfilePhase = process.env.GAIUS_SMOKE_CPU_PROFILE_PHASE || "";
  const cpuProfileDurationMs = Number(
    process.env.GAIUS_SMOKE_CPU_PROFILE_DURATION_MS || "15000",
  );
  const cpuProfilePath = nativePath(process.env.GAIUS_SMOKE_CPU_PROFILE_PATH) ||
    (buildDirectory + "/singleplayer-worker-" +
      cpuProfilePhase.replace(/[^a-z0-9._-]+/gi, "-") + ".cpuprofile");
  const coveragePhase = process.env.GAIUS_SMOKE_COVERAGE_PHASE || "";
  const coverageDurationMs = Number(
    process.env.GAIUS_SMOKE_COVERAGE_DURATION_MS || "10000",
  );
  const coveragePath = nativePath(process.env.GAIUS_SMOKE_COVERAGE_PATH) ||
    (buildDirectory + "/singleplayer-worker-" +
      coveragePhase.replace(/[^a-z0-9._-]+/gi, "-") + "-coverage.json");
  if (!Number.isFinite(maximumGameplayStallMs) || maximumGameplayStallMs <= 0) {
    throw new Error("GAIUS_SMOKE_MAX_GAMEPLAY_STALL_MS must be a positive number");
  }
  if (!Number.isFinite(telemetryBarrierTimeoutMs) || telemetryBarrierTimeoutMs <= 0) {
    throw new Error("GAIUS_SMOKE_TELEMETRY_BARRIER_TIMEOUT_MS must be a positive number");
  }
  if (!Number.isInteger(telemetryBarrierMaxAttempts) ||
      telemetryBarrierMaxAttempts < telemetryBarrierSampleCount) {
    throw new Error("GAIUS_SMOKE_TELEMETRY_BARRIER_MAX_ATTEMPTS must be at least 2");
  }
  if (!Number.isFinite(telemetryBarrierSampleDelayMs) ||
      telemetryBarrierSampleDelayMs < 0) {
    throw new Error("GAIUS_SMOKE_TELEMETRY_BARRIER_SAMPLE_DELAY_MS must be non-negative");
  }
  if (!Number.isFinite(blockDropTimeoutMs) || blockDropTimeoutMs <= 0) {
    throw new Error("GAIUS_SMOKE_BLOCK_DROP_TIMEOUT_MS must be a positive number");
  }
  if (!Number.isFinite(blockReboundWindowMs) || blockReboundWindowMs <= 0) {
    throw new Error("GAIUS_SMOKE_BLOCK_REBOUND_WINDOW_MS must be a positive number");
  }
  if (!Number.isFinite(roamTimeoutMs) || roamTimeoutMs <= 0) {
    throw new Error("GAIUS_SMOKE_ROAM_TIMEOUT_MS must be a positive number");
  }
  if (roamSpectator && !skipMining) {
    throw new Error("GAIUS_SMOKE_ROAM_SPECTATOR requires GAIUS_SMOKE_SKIP_MINING=1");
  }
  if (!Number.isFinite(blockActionHoldMs) || blockActionHoldMs <= 0) {
    throw new Error("GAIUS_SMOKE_BLOCK_ACTION_HOLD_MS must be a positive number");
  }
  if (!Number.isFinite(chunkBatchAckDelayMs) || chunkBatchAckDelayMs < 0) {
    throw new Error("GAIUS_SMOKE_CHUNK_BATCH_ACK_DELAY_MS must be zero or positive");
  }
  if (!Number.isFinite(chunkBatchDesiredRate) || chunkBatchDesiredRate <= 0) {
    throw new Error("GAIUS_SMOKE_CHUNK_BATCH_DESIRED_RATE must be a positive number");
  }
  if (distanceRampIntervalMillis !== undefined &&
      (!Number.isFinite(distanceRampIntervalMillis) ||
        distanceRampIntervalMillis < 100 || distanceRampIntervalMillis > 2000)) {
    throw new Error("GAIUS_SMOKE_DISTANCE_RAMP_MS must be between 100 and 2000");
  }
  if (!Number.isInteger(targetRenderDistance) || targetRenderDistance < 2 ||
      targetRenderDistance > 32 || !Number.isInteger(targetSimulationDistance) ||
      targetSimulationDistance < 2 || targetSimulationDistance > 32) {
    throw new Error("Smoke render and simulation distances must be integers between 2 and 32");
  }
  if (cpuProfilePhase &&
      (!Number.isFinite(cpuProfileDurationMs) || cpuProfileDurationMs <= 0)) {
    throw new Error("GAIUS_SMOKE_CPU_PROFILE_DURATION_MS must be a positive number");
  }
  if (coveragePhase &&
      (!Number.isFinite(coverageDurationMs) || coverageDurationMs <= 0)) {
    throw new Error("GAIUS_SMOKE_COVERAGE_DURATION_MS must be a positive number");
  }
  const configuredDistanceRampIntervalMillis = distanceRampIntervalMillis === undefined
    ? 750
    : Math.round(distanceRampIntervalMillis);
  const worker = new Worker(new URL(import.meta.url), {workerData: {runtime: true}});
  const {port1, port2} = new MessageChannel();
  const sessionId = "0123456789abcdef0123456789abcdef";
  const clientProfileId = "00000000000040008000000000000002";
  const expectedStagedDistances = `1/1->${targetRenderDistance}/${targetSimulationDistance}`;
  const expectedTransitions = [];
  let expectedViewDistance = Math.min(targetRenderDistance, 2);
  let expectedSimulationDistance = 1;
  expectedTransitions.push(`${expectedViewDistance}/${expectedSimulationDistance}`);
  while (expectedViewDistance < targetRenderDistance ||
      expectedSimulationDistance < targetSimulationDistance) {
    expectedViewDistance = Math.min(targetRenderDistance, expectedViewDistance + 1);
    expectedSimulationDistance = Math.min(
      targetSimulationDistance,
      expectedSimulationDistance + 1,
    );
    expectedTransitions.push(`${expectedViewDistance}/${expectedSimulationDistance}`);
  }
  const expectedDistances = expectedTransitions.at(-1);
  const expectedDistanceRamp = expectedTransitions.slice(0, -1);
  const distanceRamp = [];
  const distanceTransitionTimeline = [];
  const protocol = createProtocolClient(port2, sessionId, clientProfileId, {
    skipMining,
    roamSteps,
    roamStepBlocks,
    roamTimeoutMs,
    roamSpectator,
    requireBlockDrop,
    blockDropTimeoutMs,
    blockReboundWindowMs,
    blockActionHoldMs,
    chunkBatchAckDelayMs,
    chunkBatchDesiredRate,
    onRoamPhase(detail) {
      const at = Date.now();
      const nextPhase = "roam-" + detail;
      const stopCpuProfile = cpuProfileActive &&
        (workerPhase === cpuProfilePhase ||
          (cpuProfilePhase === "roam" && nextPhase === "roam-complete"));
      if (stopCpuProfile) {
        worker.postMessage({type: "node-cpu-profile-stop"});
        cpuProfileActive = false;
      }
      workerPhase = nextPhase;
      const startCpuProfile = !cpuProfileActive &&
        (workerPhase === cpuProfilePhase ||
          (cpuProfilePhase === "roam" && /^roam-1\//.test(workerPhase)));
      if (startCpuProfile) {
        worker.postMessage({
          type: "node-cpu-profile-start",
          phase: cpuProfilePhase,
          path: cpuProfilePath,
        });
        cpuProfileActive = true;
      }
      events.push({type: "roam-phase", detail, at, afterSmokeMs: at - smokeStartedAt});
    },
  });
  let protocolReady = false;
  let distanceSyncReady = false;
  let configuredDistanceReady = false;
  let regionStorageWrites = 0;
  let nonEmptyRegionStorageWrites = 0;
  let eventLoopProbeId = 0;
  const eventLoopProbeStartedAt = new Map();
  const eventLoopProbeLatenciesMs = [];
  const eventLoopProbeSamples = [];
  let longestEventLoopProbe = {latencyMs: 0, startedAt: 0, completedAt: 0};
  let longestGameplayEventLoopProbe = {
    latencyMs: 0,
    startedAt: 0,
    completedAt: 0,
    phase: "",
  };
  let latestChunkPriorityStats = null;
  let latestNetworkStats = null;
  let latestWorldgenStats = null;
  let latestStorageStats = null;
  let lastWorldgenTraceAt = 0;
  let workerExited = false;
  let stopFlowStarted = false;
  let stoppedReceived = false;
  let stoppedFinalizationStarted = false;
  let telemetrySequence = 0;
  const pendingTelemetryPongs = new Map();
  let preStopTelemetryBarrier = null;
  let postStopTelemetryBarrier = null;
  let serverCreatedAt = 0;
  let protocolReadyAt = 0;
  let workerPhase = "startup";
  let cpuProfileActive = false;
  let cpuProfileWritten = !cpuProfilePhase;
  let cpuProfileStopTimer = 0;
  const cpuProfileCompletion = deferred();
  if (!cpuProfilePhase) {
    cpuProfileCompletion.resolve();
  }
  let coverageActive = false;
  let coverageWritten = !coveragePhase;
  let coverageStopTimer = 0;
  const startTimedCpuProfile = (phase) => {
    if (cpuProfileActive || cpuProfileWritten || cpuProfilePhase !== phase) {
      return;
    }
    worker.postMessage({
      type: "node-cpu-profile-start",
      phase: cpuProfilePhase,
      path: cpuProfilePath,
    });
    cpuProfileActive = true;
    cpuProfileStopTimer = setTimeout(() => {
      cpuProfileStopTimer = 0;
      if (!cpuProfileActive) return;
      worker.postMessage({type: "node-cpu-profile-stop"});
      cpuProfileActive = false;
    }, cpuProfileDurationMs);
  };
  const startTimedCoverage = (phase) => {
    if (coverageActive || coverageWritten || coveragePhase !== phase) {
      return;
    }
    worker.postMessage({
      type: "node-coverage-start",
      phase: coveragePhase,
      path: coveragePath,
    });
    coverageActive = true;
    coverageStopTimer = setTimeout(() => {
      coverageStopTimer = 0;
      if (!coverageActive) return;
      worker.postMessage({type: "node-coverage-stop"});
      coverageActive = false;
    }, coverageDurationMs);
  };
  const eventLoopProbeInterval = setInterval(() => {
    const probeId = ++eventLoopProbeId;
    eventLoopProbeStartedAt.set(probeId, {startedAt: Date.now(), phase: workerPhase});
    worker.postMessage({type: "node-event-loop-probe", probeId});
  }, 100);
  const requestTelemetryPong = (stage) => {
    if (finished || workerExited) {
      return Promise.resolve({
        available: false,
        source: "telemetry-pong",
        stage,
        reason: workerExited ? "worker-exited" : "smoke-finished",
      });
    }
    const sequence = ++telemetrySequence;
    const wait = deferred();
    const pending = {
      stage,
      resolve: wait.resolve,
      timer: 0,
    };
    pendingTelemetryPongs.set(sequence, pending);
    const settle = (result) => {
      if (pendingTelemetryPongs.get(sequence) !== pending) {
        return;
      }
      pendingTelemetryPongs.delete(sequence);
      if (pending.timer) clearTimeout(pending.timer);
      wait.resolve(result);
    };
    pending.timer = setTimeout(() => {
      settle({
        available: false,
        source: "telemetry-pong",
        stage,
        sequence,
        reason: "timeout",
      });
    }, telemetryBarrierTimeoutMs);
    try {
      worker.postMessage({type: "telemetry-ping", sessionId, sequence});
    } catch (error) {
      settle({
        available: false,
        source: "telemetry-pong",
        stage,
        sequence,
        reason: String(error && (error.stack || error.message) || error),
      });
    }
    return wait.promise;
  };
  const requestNodeTelemetryPong = (stage) => {
    if (finished || workerExited) {
      return Promise.resolve({
        available: false,
        source: "node-event-loop-pong",
        stage,
        reason: workerExited ? "worker-exited" : "smoke-finished",
      });
    }
    const probeId = ++eventLoopProbeId;
    const wait = deferred();
    const probe = {
      startedAt: Date.now(),
      phase: "telemetry-barrier-" + stage,
      barrier: wait,
      barrierTimer: 0,
    };
    eventLoopProbeStartedAt.set(probeId, probe);
    const settle = (result) => {
      if (eventLoopProbeStartedAt.get(probeId) !== probe) {
        return;
      }
      eventLoopProbeStartedAt.delete(probeId);
      if (probe.barrierTimer) clearTimeout(probe.barrierTimer);
      wait.resolve(result);
    };
    probe.barrierTimer = setTimeout(() => {
      settle({
        available: false,
        source: "node-event-loop-pong",
        stage,
        probeId,
        reason: "timeout",
      });
    }, telemetryBarrierTimeoutMs);
    try {
      worker.postMessage({type: "node-event-loop-probe", probeId});
    } catch (error) {
      settle({
        available: false,
        source: "node-event-loop-pong",
        stage,
        probeId,
        reason: String(error && (error.stack || error.message) || error),
      });
    }
    return wait.promise;
  };
  const makeNetworkTelemetryBarrier = (
    stage,
    source,
    samples,
    directResponseCount,
    fallbackUsed,
    baselineNetworkStats = null,
  ) => {
    const baselineTaskPending = baselineNetworkStats !== null &&
      baselineNetworkStats.integratedServerTaskPending === 1;
    const boundarySample = samples.find((sample) =>
      sample.available && sample.networkStats !== null);
    const boundarySampleTaskPending = boundarySample !== undefined &&
      boundarySample.networkStats.integratedServerTaskPending === 1;
    const initialIntegratedServerTaskPending = baselineTaskPending ||
      boundarySampleTaskPending ? 1 : 0;
    const availableSamples = samples.filter((sample) => sample.available);
    const stableSamples = samples.slice(-telemetryBarrierSampleCount);
    const completeStableSamples = stableSamples.filter((sample) =>
      sample.available && validateNetworkTaskTelemetry(sample.networkStats).fieldComplete);
    const signatures = completeStableSamples
      .map((sample) => networkDrainSignature(sample.networkStats));
    const stable = completeStableSamples.length === telemetryBarrierSampleCount &&
      signatures.length === telemetryBarrierSampleCount &&
      signatures.every((signature) => signature !== undefined && signature === signatures[0]);
    const selectedSample = [...availableSamples].reverse().find((sample) =>
      sample.networkStats !== null);
    const selectedValidation = validateNetworkTaskTelemetry(
      selectedSample === undefined ? null : selectedSample.networkStats,
      {
        requireDrained: true,
        initialIntegratedServerTaskPending,
      },
    );
    const drained = selectedValidation.fieldComplete &&
      selectedValidation.nonIntegerFields.length === 0 &&
      selectedValidation.negativeFields.length === 0 &&
      selectedValidation.relationshipErrors.length === 0;
    return {
      stage,
      source,
      directResponseCount,
      fallbackUsed,
      initialIntegratedServerTaskPending,
      available: availableSamples.length > 0,
      stable,
      drained,
      attempts: samples.length,
      samples: samples.map((sample) => {
        const validation = validateNetworkTaskTelemetry(sample.networkStats);
        return {
          source: sample.source,
          stage: sample.stage,
          sequence: sample.sequence,
          probeId: sample.probeId,
          available: sample.available,
          reason: sample.reason,
          receivedAt: sample.receivedAt,
          fieldComplete: validation.fieldComplete,
          missingFields: validation.missingFields,
          nonFiniteFields: validation.nonFiniteFields,
          drainSignature: networkDrainSignature(sample.networkStats),
        };
      }),
      networkStats: selectedSample === undefined
        ? null
        : copyObjectSnapshot(selectedSample.networkStats),
      // Auxiliary telemetry is diagnostic only.  Keep stability and drain
      // decisions network-only, but return the newest detached snapshots so
      // reset-window callers cannot fall back to an older cached window.
      chunkPriorityStats: recentTelemetryAuxiliarySnapshot(
        samples,
        "chunkPriorityStats",
        latestChunkPriorityStats,
      ),
      worldgenStats: recentTelemetryAuxiliarySnapshot(
        samples,
        "worldgenStats",
        latestWorldgenStats,
      ),
      storageStats: recentTelemetryAuxiliarySnapshot(
        samples,
        "storageStats",
        latestStorageStats,
      ),
    };
  };
  const waitForTelemetrySample = () => new Promise((resolve) => {
    setTimeout(resolve, telemetryBarrierSampleDelayMs);
  });
  const requestNetworkTelemetryBarrier = async (stage) => {
    // Capture the raw cumulative gauge before the first telemetry pong resets
    // the worker's measurement baseline.  The pong counters are then deltas,
    // so this preserves whether one task was already in flight at the window
    // boundary without weakening ordinary counter relationships.  The first
    // pong's raw pending gauge is also retained because a task can be queued
    // between the last event-loop probe and the baseline reset.
    const baselineNetworkStats = copyObjectSnapshot(latestNetworkStats);
    const directSamples = [];
    for (let index = 0; index < telemetryBarrierMaxAttempts; index++) {
      const sample = await requestTelemetryPong(stage + "-direct-" + (index + 1));
      if (!sample.available) {
        break;
      }
      sample.receivedAt = Date.now();
      directSamples.push(sample);
      if (!validateNetworkTaskTelemetry(sample.networkStats).fieldComplete) {
        break;
      }
      const barrier = makeNetworkTelemetryBarrier(
        stage,
        "telemetry-pong",
        directSamples,
        directSamples.length,
        false,
        baselineNetworkStats,
      );
      if (barrier.stable && barrier.drained) {
        return barrier;
      }
      if (index + 1 < telemetryBarrierMaxAttempts) {
        await waitForTelemetrySample();
      }
    }
    const directComplete = directSamples.length > 0 &&
      directSamples.every((sample) =>
        validateNetworkTaskTelemetry(sample.networkStats).fieldComplete);
    if (directComplete) {
      return makeNetworkTelemetryBarrier(
        stage,
        "telemetry-pong",
        directSamples,
        directSamples.length,
        false,
        baselineNetworkStats,
      );
    }
    const nodeSamples = [];
    for (let index = 0; index < telemetryBarrierMaxAttempts; index++) {
      const sample = await requestNodeTelemetryPong(stage + "-node-" + (index + 1));
      if (!sample.available) {
        break;
      }
      sample.receivedAt = Date.now();
      nodeSamples.push(sample);
      const barrier = makeNetworkTelemetryBarrier(
        stage,
        "node-event-loop-pong",
        nodeSamples,
        directSamples.length,
        true,
        baselineNetworkStats,
      );
      if (barrier.stable && barrier.drained) {
        return barrier;
      }
      if (index + 1 < telemetryBarrierMaxAttempts) {
        await waitForTelemetrySample();
      }
    }
    if (nodeSamples.length > 0) {
      return makeNetworkTelemetryBarrier(
        stage,
        "node-event-loop-pong",
        nodeSamples,
        directSamples.length,
        true,
        baselineNetworkStats,
      );
    }
    return makeNetworkTelemetryBarrier(
      stage,
      "telemetry-pong",
      directSamples,
      directSamples.length,
      false,
      baselineNetworkStats,
    );
  };
  const completionReady = () => protocolReady &&
    (stopAtFirstChunk || (distanceSyncReady && configuredDistanceReady));
  const finishCpuProfileBeforeShutdown = async () => {
    if (!cpuProfilePhase || cpuProfileWritten) {
      return;
    }
    if (cpuProfileActive) {
      if (cpuProfileStopTimer) {
        clearTimeout(cpuProfileStopTimer);
        cpuProfileStopTimer = 0;
      }
      worker.postMessage({type: "node-cpu-profile-stop"});
      cpuProfileActive = false;
    }
    let timeoutId;
    try {
      await Promise.race([
        cpuProfileCompletion.promise,
        new Promise((resolve, reject) => {
          timeoutId = setTimeout(
            () => reject(new Error("Timed out writing the Worker CPU profile")),
            5000,
          );
        }),
      ]);
    } finally {
      if (timeoutId) clearTimeout(timeoutId);
    }
  };
  const beginStop = async () => {
    clearInterval(eventLoopProbeInterval);
    workerPhase = "telemetry-pre-stop";
    preStopTelemetryBarrier = await requestNetworkTelemetryBarrier("pre-stop");
    if (preStopTelemetryBarrier.networkStats !== null) {
      latestNetworkStats = copyObjectSnapshot(preStopTelemetryBarrier.networkStats);
    }
    latestChunkPriorityStats = copyObjectSnapshot(
      preStopTelemetryBarrier.chunkPriorityStats,
    );
    latestWorldgenStats = copyObjectSnapshot(preStopTelemetryBarrier.worldgenStats);
    latestStorageStats = copyObjectSnapshot(preStopTelemetryBarrier.storageStats);
    await finishCpuProfileBeforeShutdown();
    if (finished) {
      return;
    }
    workerPhase = "stopping";
    protocol.closeTransport();
    worker.postMessage({type: "stop"});
  };
  const maybeStop = () => {
    if (!completionReady() || stopFlowStarted) {
      return;
    }
    stopFlowStarted = true;
    if (traceEvents) {
      process.stderr.write(`[smoke-event] stop-requested phase=${workerPhase}\n`);
    }
    void beginStop().catch((error) => {
      events.push({
        type: "final-telemetry-barrier-error",
        detail: error.stack || String(error),
      });
      clearTimeout(timeout);
      finish(1);
    });
  };
  const finish = (code) => {
    if (finished) {
      return;
    }
    finished = true;
    clearInterval(eventLoopProbeInterval);
    if (cpuProfileStopTimer) clearTimeout(cpuProfileStopTimer);
    if (coverageStopTimer) clearTimeout(coverageStopTimer);
    process.stdout.write(JSON.stringify({events}, null, 2) + "\n");
    void worker.terminate().finally(() => process.exit(code));
  };
  const timeoutMs = Number(process.env.GAIUS_SMOKE_TIMEOUT_MS || "240000");
  const timeout = setTimeout(() => {
    events.push({type: "protocol-timeout", ...protocol.snapshot()});
    finish(2);
  }, timeoutMs);
  const finalizeStopped = async (stoppedMessage) => {
    if (stoppedFinalizationStarted) {
      return;
    }
    stoppedFinalizationStarted = true;
    postStopTelemetryBarrier = await requestNetworkTelemetryBarrier("post-stopped");
    let finalNetworkStats = latestNetworkStats;
    let finalNetworkSource = "last-node-event-loop-pong";
    if (preStopTelemetryBarrier !== null &&
        preStopTelemetryBarrier.networkStats !== null) {
      finalNetworkStats = copyObjectSnapshot(preStopTelemetryBarrier.networkStats);
      finalNetworkSource = "pre-stop-barrier";
    } else if (postStopTelemetryBarrier.networkStats !== null) {
      finalNetworkStats = copyObjectSnapshot(postStopTelemetryBarrier.networkStats);
      finalNetworkSource = "post-stopped-barrier-pre-stop-unavailable";
    }
    const finalAuxiliaryBarrier = preStopTelemetryBarrier !== null
      ? preStopTelemetryBarrier
      : postStopTelemetryBarrier;
    if (finalAuxiliaryBarrier !== null) {
      latestChunkPriorityStats = copyObjectSnapshot(
        finalAuxiliaryBarrier.chunkPriorityStats,
      );
      latestWorldgenStats = copyObjectSnapshot(finalAuxiliaryBarrier.worldgenStats);
      latestStorageStats = copyObjectSnapshot(finalAuxiliaryBarrier.storageStats);
    }
    latestNetworkStats = finalNetworkStats === null
      ? null
      : copyObjectSnapshot(finalNetworkStats);
    const cooperativePumpMode = resolveCooperativePumpMode(
      finalNetworkStats,
      cooperativePumpExpected,
    );
    const selectedBarrier = preStopTelemetryBarrier !== null &&
        preStopTelemetryBarrier.networkStats !== null
      ? preStopTelemetryBarrier
      : postStopTelemetryBarrier;
    const networkValidation = validateNetworkTaskTelemetry(finalNetworkStats, {
      requireActivity: cooperativePumpMode.requireActivity,
      requireDrained: true,
      initialIntegratedServerTaskPending: selectedBarrier === null
        ? 0
        : selectedBarrier.initialIntegratedServerTaskPending,
      requireHealthy: true,
    });
    networkValidation.cooperativePumpMode = cooperativePumpMode;
    if (!selectedBarrier || !selectedBarrier.stable) {
      networkValidation.healthErrors.push("final network telemetry barrier was not stable");
      networkValidation.valid = false;
    }
    if (preStopTelemetryBarrier === null || !preStopTelemetryBarrier.stable) {
      networkValidation.healthErrors.push("pre-stop network telemetry barrier was not stable");
      networkValidation.valid = false;
    }
    events.push({
      type: "protocol-final",
      stoppedDetail: stoppedMessage.detail,
      distanceRampIntervalMillis: configuredDistanceRampIntervalMillis,
      distanceTransitionTimeline: distanceTransitionTimeline.slice(),
      ...protocol.snapshot(),
      chunkPriorityStats: latestChunkPriorityStats,
      networkStats: finalNetworkStats,
      networkSource: finalNetworkSource,
      networkValidation,
      telemetryBarriers: {
        preStop: preStopTelemetryBarrier,
        postStopped: postStopTelemetryBarrier,
      },
      worldgenStats: latestWorldgenStats,
      storageStats: latestStorageStats,
    });
    const sortedProbeLatencies = eventLoopProbeLatenciesMs.slice().sort((left, right) => left - right);
    const phaseLatencies = summarizeProbePhases(eventLoopProbeSamples);
    const gameplayLatency = summarizeGameplayProbeLatencies(eventLoopProbeSamples);
    const afterProtocolReadyGameplayLatency = summarizeGameplayProbeLatencies(
      eventLoopProbeSamples.filter((sample) => sample.startedAt >= protocolReadyAt),
    );
    // Worker direct-executor mode does not promise cooperative pump activity.
    // Its meaningful stall window starts after the client is protocol-ready;
    // startup/worldgen work before that point is intentionally staged.  When
    // the cooperative pump is expected or observed, retain the stricter full
    // gameplay window so regressions cannot hide behind protocol readiness.
    const stallValidation = cooperativePumpMode.requireActivity
      ? gameplayLatency
      : afterProtocolReadyGameplayLatency;
    events.push({
      type: "worker-event-loop-latency",
      samples: sortedProbeLatencies.length,
      p95Ms: percentile(sortedProbeLatencies, 0.95),
      p99Ms: percentile(sortedProbeLatencies, 0.99),
      maxMs: sortedProbeLatencies.at(-1) || 0,
      maxStartedAfterSmokeMs: longestEventLoopProbe.startedAt - smokeStartedAt,
      maxCompletedAfterSmokeMs: longestEventLoopProbe.completedAt - smokeStartedAt,
      longestGameplay: {
        ...longestGameplayEventLoopProbe,
        startedAfterSmokeMs:
          longestGameplayEventLoopProbe.startedAt - smokeStartedAt,
        completedAfterSmokeMs:
          longestGameplayEventLoopProbe.completedAt - smokeStartedAt,
      },
      afterServerCreated: summarizeProbeLatencies(eventLoopProbeSamples, serverCreatedAt),
      afterProtocolReady: summarizeProbeLatencies(eventLoopProbeSamples, protocolReadyAt),
      gameplay: gameplayLatency,
      stallValidation,
      stallValidationScope: cooperativePumpMode.requireActivity
        ? "all-gameplay"
        : "after-protocol-ready",
      byPhase: phaseLatencies,
      pending: eventLoopProbeStartedAt.size,
    });
    if (stallValidation.maxMs > maximumGameplayStallMs) {
      events.push({
        type: "worldgen-event-loop-stall",
        maximumGameplayStallMs,
        gameplayLatency: stallValidation,
        allGameplayLatency: gameplayLatency,
        afterProtocolReadyGameplayLatency,
        stallValidationScope: cooperativePumpMode.requireActivity
          ? "all-gameplay"
          : "after-protocol-ready",
      });
      clearTimeout(timeout);
      finish(1);
      return;
    }
    if (!networkValidation.valid) {
      events.push({
        type: "network-state-mismatch",
        networkStats: finalNetworkStats,
        networkSource: finalNetworkSource,
        networkValidation,
      });
      clearTimeout(timeout);
      finish(1);
      return;
    }
    if (!stopAtFirstChunk && !skipMining &&
        (regionStorageWrites === 0 || nonEmptyRegionStorageWrites !== regionStorageWrites)) {
      events.push({
        type: "region-storage-mismatch",
        regionStorageWrites,
        nonEmptyRegionStorageWrites,
      });
      clearTimeout(timeout);
      finish(1);
      return;
    }
    if (!cpuProfileWritten) {
      events.push({
        type: "cpu-profile-missing",
        phase: cpuProfilePhase,
        path: cpuProfilePath,
      });
      clearTimeout(timeout);
      finish(1);
      return;
    }
    clearTimeout(timeout);
    finish(0);
  };
  protocol.playReady.then(() => {
    protocolReady = true;
    protocolReadyAt = Date.now();
    const timing = protocol.snapshot();
    events.push({
      type: "protocol-ready",
      compressionThreshold: protocol.compressionThreshold,
      configurationPackets: protocol.configurationPackets,
      playPackets: protocol.playPackets,
      playLoginPackets: protocol.playLoginPackets,
      chunkPackets: protocol.chunkPackets,
      knownPackRequests: protocol.knownPackRequests,
      loginProfileId: protocol.loginProfileId,
      blockActionAcks: protocol.blockActionAcks,
      blockActionAckSequences: protocol.blockActionAckSequences,
      blockActionAckLatenciesMs: protocol.blockActionAckLatenciesMs,
      blockActionMaxAckLatencyMs: protocol.blockActionMaxAckLatencyMs,
      blockActionTarget: protocol.blockActionTarget,
      blockUpdates: protocol.blockUpdates,
      blockActionProbeCount: protocol.blockActionProbeCount,
      blockActionLatencyMs: protocol.blockActionLatencyMs,
      targetAirUpdates: protocol.targetAirUpdates,
      targetBlockStateId: protocol.targetBlockStateId,
      addEntityPackets: protocol.addEntityPackets,
      blockDropEntity: protocol.blockDropEntity,
      blockDropLatencyMs: protocol.blockDropLatencyMs,
      roamSteps: protocol.roamSteps,
      roamTimeline: protocol.roamTimeline,
      roamCorrections: protocol.roamCorrections,
      uniqueChunkPositions: timing.uniqueChunkPositions,
      loginToPlayMs: protocol.loginToPlayMs,
      loginToFirstChunkMs: protocol.loginToFirstChunkMs,
      playToFirstChunkMs: protocol.playToFirstChunkMs,
      loginToCompressionMs: timing.loginToCompressionMs,
      compressionToLoginFinishedMs: timing.compressionToLoginFinishedMs,
      loginFinishedToConfigurationFinishedMs:
        timing.loginFinishedToConfigurationFinishedMs,
      configurationFinishedToPlayMs: timing.configurationFinishedToPlayMs,
      configurationTimeline: timing.configurationTimeline,
      playTimeline: timing.playTimeline,
      skipMining,
      requireBlockDrop,
      jsonOnly,
      stopAtFirstChunk,
    });
    maybeStop();
  }).catch((error) => {
    events.push({
      type: "protocol-error",
      detail: error.stack || String(error),
      chunkPriorityStats: latestChunkPriorityStats,
      networkStats: latestNetworkStats,
      worldgenStats: latestWorldgenStats,
      storageStats: latestStorageStats,
      distanceRampIntervalMillis: configuredDistanceRampIntervalMillis,
      ...protocol.snapshot(),
    });
    const profileWasActive = cpuProfileActive;
    if (profileWasActive) {
      worker.postMessage({type: "node-cpu-profile-stop"});
      cpuProfileActive = false;
    }
    clearTimeout(timeout);
    setTimeout(() => finish(1), profileWasActive ? 5000 : 2000);
  });
  worker.on("message", (message) => {
    if (traceEvents && message && message.type &&
        message.type !== "node-event-loop-pong" &&
        message.type !== "node-idb-put" &&
        message.type !== "server-startup-progress" &&
        message.type !== "startup-timing" &&
        message.type !== "telemetry-pong") {
      const detail = message.detail === undefined
        ? ""
        : ` detail=${String(message.detail).slice(0, 500)}`;
      process.stderr.write(`[smoke-event] ${message.type}${detail}\n`);
    }
    if (message && message.type === "telemetry-pong") {
      const sequence = Number(message.sequence);
      const pending = Number.isSafeInteger(sequence)
        ? pendingTelemetryPongs.get(sequence)
        : undefined;
      if (pending === undefined) {
        events.push({
          type: "telemetry-pong-unmatched",
          sequence: Number.isSafeInteger(sequence) ? sequence : null,
        });
        return;
      }
      pendingTelemetryPongs.delete(sequence);
      if (pending.timer) clearTimeout(pending.timer);
      const sessionMatches = message.sessionId === sessionId;
      const telemetrySnapshots = snapshotTelemetryPong(message);
      if (sessionMatches) {
        const latest = updateLatestTelemetrySnapshots(
          {
            chunkPriorityStats: latestChunkPriorityStats,
            networkStats: latestNetworkStats,
            worldgenStats: latestWorldgenStats,
            storageStats: latestStorageStats,
          },
          message,
          sessionId,
        );
        latestChunkPriorityStats = latest.chunkPriorityStats;
        latestNetworkStats = latest.networkStats;
        latestWorldgenStats = latest.worldgenStats;
        latestStorageStats = latest.storageStats;
      }
      pending.resolve({
        available: sessionMatches,
        source: "telemetry-pong",
        stage: pending.stage,
        sequence,
        receivedAt: Date.now(),
        reason: sessionMatches ? undefined : "session-mismatch",
        ...telemetrySnapshots,
      });
      return;
    }
    if (message && message.type === "node-event-loop-pong") {
      latestChunkPriorityStats = message.chunkPriorityStats !== null &&
        message.chunkPriorityStats !== undefined
        ? copyObjectSnapshot(message.chunkPriorityStats)
        : latestChunkPriorityStats;
      latestNetworkStats = message.networkStats !== null && message.networkStats !== undefined
        ? copyObjectSnapshot(message.networkStats)
        : latestNetworkStats;
      latestWorldgenStats = message.worldgenStats !== null &&
        message.worldgenStats !== undefined
        ? copyObjectSnapshot(message.worldgenStats)
        : latestWorldgenStats;
      if (traceEvents && Date.now() - lastWorldgenTraceAt >= 5000) {
        lastWorldgenTraceAt = Date.now();
        process.stderr.write(
          `[smoke-worldgen] ${JSON.stringify({
            phase: workerPhase,
            chunkPriority: latestChunkPriorityStats,
            network: latestNetworkStats,
            worldgen: latestWorldgenStats,
          })}\n`,
        );
      }
      const probe = eventLoopProbeStartedAt.get(message.probeId);
      if (probe !== undefined) {
        eventLoopProbeStartedAt.delete(message.probeId);
        const completedAt = Date.now();
        const latencyMs = completedAt - probe.startedAt;
        eventLoopProbeLatenciesMs.push(latencyMs);
        eventLoopProbeSamples.push({
          latencyMs,
          startedAt: probe.startedAt,
          completedAt,
          phase: probe.phase,
        });
        if (latencyMs > longestEventLoopProbe.latencyMs) {
          longestEventLoopProbe = {latencyMs, startedAt: probe.startedAt, completedAt};
        }
        if (isGameplayProbePhase(probe.phase) &&
            latencyMs > longestGameplayEventLoopProbe.latencyMs) {
          longestGameplayEventLoopProbe = {
            latencyMs,
            startedAt: probe.startedAt,
            completedAt,
            phase: probe.phase,
          };
        }
        if (probe.barrier !== undefined) {
          if (probe.barrierTimer) clearTimeout(probe.barrierTimer);
          probe.barrier.resolve({
            available: true,
            source: "node-event-loop-pong",
            stage: probe.phase.slice("telemetry-barrier-".length),
            probeId: message.probeId,
            receivedAt: completedAt,
            networkStats: message.networkStats !== null &&
              message.networkStats !== undefined
              ? copyObjectSnapshot(message.networkStats)
              : null,
            chunkPriorityStats: message.chunkPriorityStats !== null &&
              message.chunkPriorityStats !== undefined
              ? copyObjectSnapshot(message.chunkPriorityStats)
              : null,
            worldgenStats: message.worldgenStats !== null &&
              message.worldgenStats !== undefined
              ? copyObjectSnapshot(message.worldgenStats)
              : null,
          });
        }
      }
      return;
    }
    events.push(message);
    if (message && message.type === "node-console-error") {
      clearTimeout(timeout);
      finish(1);
    } else if (message && (message.type === "network-pump-error" ||
        message.type === "network-pump-schedule-error" ||
        message.type === "network-pump-retry-exhausted" ||
        message.type === "chunk-batch-ack-without-send")) {
      clearTimeout(timeout);
      finish(1);
    } else if (message && message.type === "node-cpu-profile-written") {
      cpuProfileWritten = true;
      cpuProfileCompletion.resolve();
      if (cpuProfileStopTimer) {
        clearTimeout(cpuProfileStopTimer);
        cpuProfileStopTimer = 0;
      }
    } else if (message && message.type === "node-coverage-written") {
      coverageWritten = true;
      if (coverageStopTimer) {
        clearTimeout(coverageStopTimer);
        coverageStopTimer = 0;
      }
    } else if (message && message.type === "node-idb-put" && message.path.endsWith(".mca")) {
      regionStorageWrites++;
      if (message.bytes > 0) nonEmptyRegionStorageWrites++;
    } else if (message && message.type === "server-created") {
      serverCreatedAt = Date.now();
      workerPhase = "server-created";
      startTimedCpuProfile(workerPhase);
      startTimedCoverage(workerPhase);
      protocol.startLogin();
    } else if (message && message.type === "server-listener-ready") {
      workerPhase = "server-listener-ready";
      setTimeout(() => {
        worker.postMessage({
          type: "distances",
          renderDistance: targetRenderDistance,
          simulationDistance: targetSimulationDistance,
        });
      }, 1000);
    } else if (message && message.type === "server-distances-staged" &&
        message.detail === expectedStagedDistances && !distanceSyncReady) {
      workerPhase = "distance-staged";
      distanceSyncReady = true;
      startTimedCpuProfile(workerPhase);
      startTimedCoverage(workerPhase);
    } else if (message && message.type === "server-distances-ramping") {
      workerPhase = "distance-" + message.detail;
      distanceRamp.push(message.detail);
      distanceTransitionTimeline.push({
        detail: message.detail,
        receivedAt: Date.now(),
        receivedAtMs: performance.now(),
        ackCountAtTransition: protocol.chunkBatchAckCount,
        chunkPacketCountAtTransition: protocol.chunkPackets,
      });
    } else if (message && message.type === "server-distances" &&
        message.detail === expectedDistances && !configuredDistanceReady) {
      workerPhase = "distance-" + message.detail;
      distanceTransitionTimeline.push({
        detail: message.detail,
        receivedAt: Date.now(),
        receivedAtMs: performance.now(),
        ackCountAtTransition: protocol.chunkBatchAckCount,
        chunkPacketCountAtTransition: protocol.chunkPackets,
      });
      if (JSON.stringify(distanceRamp) !== JSON.stringify(expectedDistanceRamp)) {
        events.push({type: "distance-ramp-mismatch", expected: expectedDistanceRamp, actual: distanceRamp});
        clearTimeout(timeout);
        finish(1);
        return;
      }
      const actualTransitions = distanceTransitionTimeline.map((entry) => entry.detail);
      const configuredInterval = configuredDistanceRampIntervalMillis;
      const ackCausal = distanceTransitionTimeline.every((entry, index) =>
        entry.ackCountAtTransition > (index === 0
          ? 0
          : distanceTransitionTimeline[index - 1].ackCountAtTransition) &&
        protocol.chunkBatchAckTimeline[entry.ackCountAtTransition - 1]?.sentAtMs <=
          entry.receivedAtMs);
      const intervalValid = distanceTransitionTimeline.every((entry, index) =>
        index === 0 || entry.receivedAtMs - distanceTransitionTimeline[index - 1].receivedAtMs >=
          configuredInterval - 50);
      const ringBackpressureValid = distanceTransitionTimeline.every((entry) => {
        const nextViewDistance = Number(String(entry.detail).split("/", 1)[0]);
        if (nextViewDistance <= 2) return entry.chunkPacketCountAtTransition >= 1;
        const previousDiameter = (nextViewDistance - 1) * 2 - 1;
        return entry.chunkPacketCountAtTransition >= previousDiameter * previousDiameter;
      });
      if (JSON.stringify(actualTransitions) !== JSON.stringify(expectedTransitions) ||
          !ackCausal || !intervalValid || !ringBackpressureValid) {
        events.push({
          type: "distance-ramp-causality-mismatch",
          expectedTransitions,
          actualTransitions,
          configuredInterval,
          ackCausal,
          intervalValid,
          ringBackpressureValid,
          distanceTransitionTimeline,
          chunkBatchAckTimeline: protocol.chunkBatchAckTimeline,
        });
        clearTimeout(timeout);
        finish(1);
        return;
      }
      configuredDistanceReady = true;
      protocol.startRoam();
      maybeStop();
    } else if (message && message.type === "stopped" && completionReady() &&
        !stoppedReceived) {
      stoppedReceived = true;
      clearInterval(eventLoopProbeInterval);
      void finalizeStopped(message).catch((error) => {
        events.push({
          type: "final-telemetry-barrier-error",
          detail: error.stack || String(error),
        });
        clearTimeout(timeout);
        finish(1);
      });
    } else if (message && (message.type === "crash" || message.type === "bootstrap-crash")) {
      clearTimeout(timeout);
      finish(1);
    }
  });
  worker.on("error", (error) => {
    events.push({type: "worker-error", detail: error.stack || String(error)});
    clearTimeout(timeout);
    finish(1);
  });
  worker.on("exit", (code) => {
    workerExited = true;
    if (!finished && !stoppedReceived) {
      events.push({type: "worker-exited-early", code, ...protocol.snapshot()});
      clearTimeout(timeout);
      finish(code === 0 ? 1 : code);
    }
  });
  worker.postMessage({
    type: "start",
    worldId: "gaius-node-runtime-smoke",
    seed: process.env.GAIUS_SMOKE_SEED || "gaius-runtime-smoke-v1",
    sessionId,
    launchGeneration: "1",
    profileId: storageConfig.profileId,
    worldVersion: storageConfig.worldVersion,
    storageSchema: storageConfig.storageSchema,
    storageDatabaseName: storageConfig.storageDatabaseName,
    storagePrefix: storageConfig.storagePrefix,
    storageOpfsDirectory: storageConfig.storageOpfsDirectory,
    renderDistance: 8,
    simulationDistance: 5,
    distanceRampIntervalMillis: distanceRampIntervalMillis === undefined
      ? undefined
      : configuredDistanceRampIntervalMillis,
    port: port1,
  }, [port1]);
} else if (workerData && workerData.runtime) {
  if (process.env.GAIUS_SMOKE_JSON_ONLY === "1") {
    const writeDiagnostic = (...args) => {
      process.stderr.write(args.map((value) => String(value)).join(" ") + "\n");
    };
    console.log = writeDiagnostic;
    console.info = writeDiagnostic;
    console.warn = writeDiagnostic;
  }
  installWorkerGlobals();
  const originalConsoleError = console.error.bind(console);
  console.error = (...args) => {
    parentPort.postMessage({
      type: "node-console-error",
      detail: args.map((value) => String(value)).join(" "),
      stack: new Error("console-error-stack").stack,
    });
    originalConsoleError(...args);
  };
  vm.runInThisContext(fs.readFileSync(bootstrapPath, "utf8"), {filename: bootstrapPath});
  let cpuProfileSession;
  let cpuProfileStarted;
  let cpuProfileMetadata;
  let coverageSession;
  let coverageStarted;
  let coverageMetadata;
  parentPort.on("message", (data) => {
    if (data && data.type === "node-event-loop-probe") {
      parentPort.postMessage({
        type: "node-event-loop-pong",
        probeId: data.probeId,
        chunkPriorityStats: globalThis.__gaiusChunkPriorityStats
          ? {...globalThis.__gaiusChunkPriorityStats}
          : null,
        networkStats: globalThis.__gaiusNetworkStats
          ? {...globalThis.__gaiusNetworkStats}
          : null,
        worldgenStats: globalThis.__gaiusWorldgenStats
          ? {...globalThis.__gaiusWorldgenStats}
          : null,
      });
      return;
    }
    if (data && data.type === "node-cpu-profile-start") {
      if (cpuProfileSession !== undefined) {
        parentPort.postMessage({
          type: "node-console-error",
          detail: "Worker CPU profile was started more than once",
        });
        return;
      }
      cpuProfileSession = new InspectorSession();
      cpuProfileSession.connect();
      cpuProfileMetadata = {phase: data.phase, path: data.path};
      cpuProfileStarted = inspectorPost(cpuProfileSession, "Profiler.enable")
        .then(() => inspectorPost(cpuProfileSession, "Profiler.start"));
      return;
    }
    if (data && data.type === "node-cpu-profile-stop") {
      void stopWorkerCpuProfile(
        cpuProfileSession,
        cpuProfileStarted,
        cpuProfileMetadata,
      );
      return;
    }
    if (data && data.type === "node-coverage-start") {
      if (coverageSession !== undefined) {
        parentPort.postMessage({
          type: "node-console-error",
          detail: "Worker precise coverage was started more than once",
        });
        return;
      }
      coverageSession = new InspectorSession();
      coverageSession.connect();
      coverageMetadata = {phase: data.phase, path: data.path};
      coverageStarted = inspectorPost(coverageSession, "Profiler.enable")
        .then(() => inspectorPost(coverageSession, "Profiler.startPreciseCoverage", {
          callCount: true,
          detailed: true,
          allowTriggeredUpdates: false,
        }));
      return;
    }
    if (data && data.type === "node-coverage-stop") {
      void stopWorkerCoverage(
        coverageSession,
        coverageStarted,
        coverageMetadata,
      );
      return;
    }
    globalThis.onmessage({data, ports: []});
  });
  parentPort.postMessage({type: "node-wrapper-ready"});
}

function inspectorPost(session, method, params) {
  return new Promise((resolve, reject) => {
    const callback = (error, result) => {
      if (error) reject(error);
      else resolve(result);
    };
    if (params === undefined) session.post(method, callback);
    else session.post(method, params, callback);
  });
}

async function stopWorkerCpuProfile(session, started, metadata) {
  try {
    if (!session || !started || !metadata?.path) {
      throw new Error("Worker CPU profile stop arrived before start");
    }
    await started;
    const result = await inspectorPost(session, "Profiler.stop");
    fs.writeFileSync(metadata.path, JSON.stringify(result.profile));
    parentPort.postMessage({
      type: "node-cpu-profile-written",
      phase: metadata.phase,
      path: metadata.path,
      nodes: result.profile.nodes.length,
      samples: result.profile.samples?.length || 0,
      startTime: result.profile.startTime,
      endTime: result.profile.endTime,
    });
  } catch (error) {
    parentPort.postMessage({
      type: "node-console-error",
      detail: "Worker CPU profile failed: " + (error.stack || String(error)),
    });
  } finally {
    session?.disconnect();
  }
}

async function stopWorkerCoverage(session, started, metadata) {
  try {
    if (!session || !started || !metadata?.path) {
      throw new Error("Worker precise coverage stop arrived before start");
    }
    await started;
    const result = await inspectorPost(session, "Profiler.takePreciseCoverage");
    await inspectorPost(session, "Profiler.stopPreciseCoverage");
    fs.writeFileSync(metadata.path, JSON.stringify(result));
    parentPort.postMessage({
      type: "node-coverage-written",
      phase: metadata.phase,
      path: metadata.path,
      scripts: result.result.length,
    });
  } catch (error) {
    parentPort.postMessage({
      type: "node-console-error",
      detail: "Worker precise coverage failed: " + (error.stack || String(error)),
    });
  } finally {
    session?.disconnect();
  }
}

function createProtocolClient(port, sessionId, expectedProfileId, options = {}) {
  const ready = deferred();
  const host = "client-" + sessionId + ".gaius-local";
  let buffered = new Uint8Array(0);
  let packetWork = Promise.resolve();
  let remotePaused = false;
  let loginStarted = false;
  const pendingSends = [];
  const state = {
    compressionThreshold: undefined,
    phase: "login",
    loginFinished: false,
    configurationPackets: 0,
    configurationFinished: false,
    playPackets: 0,
    playLoginPackets: 0,
    chunkPackets: 0,
    chunkTimeline: [],
    knownPackRequests: 0,
    loginProfileId: undefined,
    playerLoadedSent: false,
    playerPosition: undefined,
    miningScheduled: false,
    miningCompleted: Boolean(options.skipMining),
    roamRequested: false,
    roamScheduled: false,
    roamCompleted: options.roamSteps <= 0,
    roamStep: 0,
    roamOriginX: undefined,
    roamTargetX: undefined,
    roamTargetChunkKey: undefined,
    roamLiftStep: 0,
    roamSteps: options.roamSteps || 0,
    roamStepBlocks: options.roamStepBlocks || 8,
    roamStepStartedAt: undefined,
    roamBaselineChunkPackets: 0,
    roamStepWaiting: false,
    roamTimeline: [],
    roamCorrections: [],
    roamSettleTimer: undefined,
    roamStepTimer: undefined,
    roamHeartbeatTimer: undefined,
    blockActionCandidates: [],
    blockActionProbedTargets: [],
    blockActionCandidateIndex: 0,
    blockActionCandidateConfirmed: false,
    blockActionSequence: 0,
    blockActionProbeCount: 0,
    blockActionProbeTimer: undefined,
    blockActionStopTimer: undefined,
    blockActionAckTimer: undefined,
    blockActionRetryTimer: undefined,
    loginStartedAt: undefined,
    compressionAt: undefined,
    loginFinishedAt: undefined,
    configurationStartedAt: undefined,
    configurationFinishedAt: undefined,
    playLoginAt: undefined,
    configurationTimeline: [],
    playTimeline: [],
    firstChunkAt: undefined,
    loginToPlayMs: undefined,
    loginToFirstChunkMs: undefined,
    playToFirstChunkMs: undefined,
    miningStartedAt: undefined,
    targetAirAt: undefined,
    targetStableAt: undefined,
    targetBlockStateId: undefined,
    blockActionStopSequence: undefined,
    blockActionAcks: 0,
    blockActionAckSequences: [],
    blockActionSentAt: new Map(),
    blockActionAckLatenciesMs: [],
    blockActionMaxAckLatencyMs: 0,
    blockActionTarget: undefined,
    blockUpdates: [],
    blockActionLatencyMs: undefined,
    targetAirUpdates: 0,
    addEntityPackets: 0,
    addedEntities: [],
    blockDropEntity: undefined,
    blockDropAt: undefined,
    blockDropLatencyMs: undefined,
    blockDropTimer: undefined,
    blockReboundTimer: undefined,
    persistenceMarkerScheduled: false,
    persistenceMarkerCompleted: false,
    chunkBatchAckSent: false,
    chunkBatchAckCount: 0,
    chunkBatchAckTimeline: [],
    lastAckedChunkPackets: 0,
    chunkBatchAckTimer: undefined,
    uniqueChunkPositions: new Set(),
    chunkDigests: new Map(),
    chunkCenters: [],
    receivedPacketIds: {
      login: [],
      configuration: [],
      play: [],
    },
    playReady: ready.promise,
    snapshot,
    startLogin,
    startRoam,
    closeTransport,
  };

  port.onmessage = (event) => {
    const message = event.data;
    if (isControlMessage(message)) {
      if (message.type === "flow") {
        remotePaused = Boolean(message.paused);
        if (!remotePaused) {
          flushSends();
        }
      } else if (message.type === "close" && state.chunkPackets === 0) {
        ready.reject(new Error("Local server transport closed before PLAY chunk data arrived"));
      }
      return;
    }
    if (!(message instanceof ArrayBuffer) && !ArrayBuffer.isView(message)) {
      ready.reject(new Error("Local server transport returned an unsupported message"));
      return;
    }
    const bytes = message instanceof ArrayBuffer
      ? new Uint8Array(message)
      : new Uint8Array(message.buffer, message.byteOffset, message.byteLength);
    buffered = concatenate(buffered, bytes);
    drainFrames();
  };
  port.onmessageerror = () => {
    ready.reject(new Error("Local server MessagePort could not decode a message"));
  };
  port.start();

  function startLogin() {
    if (loginStarted) {
      return;
    }
    loginStarted = true;
    state.loginStartedAt = Date.now();
    const handshake = concatenateMany([
      encodeVarInt(activeProtocolVersion),
      encodeString(host),
      new Uint8Array([0x63, 0xdd]),
      encodeVarInt(2),
    ]);
    const hello = concatenate(
      encodeString("GaiusSmoke"),
      hexBytes(expectedProfileId)
    );
    send(encodePacket(0, handshake));
    send(encodePacket(0, hello));
  }

  function closeTransport() {
    clearTimeout(state.roamHeartbeatTimer);
    clearTimeout(state.roamSettleTimer);
    clearTimeout(state.roamStepTimer);
    clearTimeout(state.blockActionProbeTimer);
    clearTimeout(state.blockActionStopTimer);
    clearTimeout(state.blockActionAckTimer);
    clearTimeout(state.blockActionRetryTimer);
    clearTimeout(state.blockDropTimer);
    clearTimeout(state.blockReboundTimer);
    try {
      port.postMessage({type: "close"});
    } finally {
      port.close();
    }
  }

  function drainFrames() {
    try {
      while (true) {
        const outerLength = decodeVarInt(buffered, 0);
        if (outerLength === undefined) {
          return;
        }
        const frameStart = outerLength.bytesRead;
        const frameEnd = frameStart + outerLength.value;
        if (frameEnd > buffered.byteLength) {
          return;
        }
        const frame = buffered.slice(frameStart, frameEnd);
        buffered = buffered.slice(frameEnd);
        packetWork = packetWork.then(() => processFrame(frame));
        packetWork.catch((error) => ready.reject(error));
      }
    } catch (error) {
      ready.reject(error);
    }
  }

  function processFrame(frame) {
    let packet = frame;
    if (state.compressionThreshold !== undefined) {
      const dataLength = decodeVarInt(frame, 0);
      if (dataLength === undefined) {
        throw new Error("Compressed Minecraft frame omitted its data length");
      }
      const payload = frame.subarray(dataLength.bytesRead);
      packet = dataLength.value === 0 ? payload : new Uint8Array(inflateSync(payload));
      if (dataLength.value !== 0 && packet.byteLength !== dataLength.value) {
        throw new Error("Minecraft compressed frame length did not match");
      }
    }
    const packetId = decodeVarInt(packet, 0);
    if (packetId === undefined) {
      throw new Error("Minecraft frame omitted its packet id");
    }
    const phasePacketIds = state.receivedPacketIds[state.phase];
    if (phasePacketIds && phasePacketIds.length < 256) {
      phasePacketIds.push(packetId.value);
    }
    const payload = packet.subarray(packetId.bytesRead);
    if (state.phase === "login" && packetId.value === 0) {
      throw new Error("Official server rejected the smoke login");
    }
    if (state.phase === "login" && packetId.value === 3) {
      const threshold = decodeVarInt(packet, packetId.bytesRead);
      if (threshold === undefined || threshold.value < 0) {
        throw new Error("Official server sent an invalid compression threshold");
      }
      state.compressionThreshold = threshold.value;
      state.compressionAt ??= Date.now();
    } else if (state.phase === "login" && packetId.value === 2) {
      if (payload.byteLength < 16) {
        throw new Error("Official server login profile omitted its UUID");
      }
      state.loginProfileId = Buffer.from(payload.subarray(0, 16)).toString("hex");
      if (state.loginProfileId !== expectedProfileId) {
        throw new Error(
          `Integrated server changed the client profile UUID: ${state.loginProfileId}`
        );
      }
      state.loginFinished = true;
      state.loginFinishedAt ??= Date.now();
      state.phase = "configuration";
      send(encodePacket(3, new Uint8Array(0), state.compressionThreshold));
      send(encodePacket(0, encodeClientInformation(), state.compressionThreshold));
    } else if (state.phase === "configuration") {
      state.configurationStartedAt ??= Date.now();
      if (state.configurationTimeline.length < 64) {
        state.configurationTimeline.push({
          packetId: packetId.value,
          elapsedMs: elapsed(state.loginStartedAt, Date.now()),
        });
      }
      state.configurationPackets++;
      if (packetId.value === 2) {
        throw new Error("Official server disconnected during configuration");
      }
      if (packetId.value === 14) {
        state.knownPackRequests++;
        send(encodePacket(7, encodeVarInt(0), state.compressionThreshold));
      } else if (packetId.value === 3) {
        state.configurationFinished = true;
        state.configurationFinishedAt ??= Date.now();
        state.phase = "play";
        send(encodePacket(3, new Uint8Array(0), state.compressionThreshold));
      } else if (packetId.value === 4) {
        send(encodePacket(4, payload, state.compressionThreshold));
      } else if (packetId.value === 5) {
        send(encodePacket(5, payload, state.compressionThreshold));
      }
    } else if (state.phase === "play") {
      if (state.playTimeline.length < 64) {
        state.playTimeline.push({
          packetId: packetId.value,
          elapsedMs: elapsed(state.loginStartedAt, Date.now()),
        });
      }
      state.playPackets++;
      if (packetId.value === clientboundPlay.disconnect) {
        throw new Error("Official server disconnected after entering PLAY");
      }
      if (packetId.value === clientboundPlay.addEntity) {
        const entity = {...decodeAddEntity(payload), receivedAt: Date.now()};
        state.addEntityPackets++;
        if (state.addedEntities.length < 512) {
          state.addedEntities.push(entity);
        }
        maybeRecordBlockDrop(entity);
      } else if (packetId.value === clientboundPlay.keepAlive) {
        send(encodePacket(serverboundPlay.keepAlive, payload, state.compressionThreshold));
      } else if (packetId.value === clientboundPlay.ping) {
        send(encodePacket(serverboundPlay.pong, payload, state.compressionThreshold));
      } else if (packetId.value === clientboundPlay.login) {
        state.playLoginPackets++;
        state.playLoginAt ??= Date.now();
        state.loginToPlayMs ??= elapsed(state.loginStartedAt, state.playLoginAt);
        if (!state.playerLoadedSent) {
          state.playerLoadedSent = true;
          send(encodePacket(
            serverboundPlay.playerLoaded,
            new Uint8Array(0),
            state.compressionThreshold,
          ));
        }
      } else if (packetId.value === clientboundPlay.levelChunkWithLight) {
        state.chunkPackets++;
        const chunkPosition = decodeChunkPosition(payload);
        if (state.chunkTimeline.length < 256) {
          state.chunkTimeline.push({...chunkPosition, receivedAt: Date.now()});
        }
        const chunkKey = `${chunkPosition.x},${chunkPosition.z}`;
        state.uniqueChunkPositions.add(chunkKey);
        state.chunkDigests.set(
          chunkKey,
          createHash("sha256").update(payload).digest("hex"),
        );
        state.firstChunkAt ??= Date.now();
        state.loginToFirstChunkMs ??= elapsed(state.loginStartedAt, state.firstChunkAt);
        state.playToFirstChunkMs ??= elapsed(state.playLoginAt, state.firstChunkAt);
        maybeScheduleChunkBatchAck();
        maybeCompleteRoamStep(chunkPosition);
        maybeScheduleRoam();
        maybeScheduleMining();
      } else if (packetId.value === clientboundPlay.playerPosition) {
        const previousPosition = state.playerPosition;
        state.playerPosition = decodePlayerPosition(payload);
        if (state.roamScheduled && previousPosition) {
          state.roamCorrections.push({
            step: state.roamStep,
            from: previousPosition,
            to: state.playerPosition,
          });
        }
        maybeScheduleRoam();
        maybeScheduleMining();
        send(
          encodePacket(
            serverboundPlay.acceptTeleportation,
            encodeVarInt(state.playerPosition.teleportId),
            state.compressionThreshold
          )
        );
        if (state.roamScheduled && state.roamStep > 0) {
          // A real client resumes movement heartbeats after accepting a server correction.
          scheduleRoamHeartbeat();
        }
      } else if (packetId.value === clientboundPlay.setChunkCacheCenter) {
        const center = decodeChunkCacheCenter(payload);
        if (center && state.chunkCenters.length < 32) {
          state.chunkCenters.push({...center, receivedAt: Date.now()});
        }
      } else if (packetId.value === clientboundPlay.blockChangedAck) {
        const sequence = decodeVarInt(payload, 0);
        if (sequence === undefined) {
          throw new Error("Block-action acknowledgement omitted its sequence");
        }
        state.blockActionAckSequences.push(sequence.value);
        state.blockActionAcks = Math.max(state.blockActionAcks, sequence.value);
        const sentAt = state.blockActionSentAt.get(sequence.value);
        if (sentAt !== undefined) {
          const latency = Date.now() - sentAt;
          state.blockActionSentAt.delete(sequence.value);
          state.blockActionAckLatenciesMs.push(latency);
          state.blockActionMaxAckLatencyMs = Math.max(state.blockActionMaxAckLatencyMs, latency);
        }
        if (sequence.value === state.blockActionStopSequence) {
          clearTimeout(state.blockActionAckTimer);
          completeBlockAction();
        }
      } else if (packetId.value === clientboundPlay.blockUpdate) {
        const update = decodeBlockUpdate(payload);
        if (state.blockUpdates.length < 128) {
          state.blockUpdates.push({...update, receivedAt: Date.now()});
        }
        const matchesCurrentTarget = state.blockActionTarget &&
          sameBlockPos(update, state.blockActionTarget);
        if (matchesCurrentTarget) {
          if (update.stateId === 0) {
            state.targetAirUpdates++;
            if (state.targetAirAt === undefined) {
              state.targetAirAt = Date.now();
              state.blockReboundTimer = setTimeout(() => {
                state.targetStableAt = Date.now();
                completeBlockAction();
              }, options.blockReboundWindowMs);
            }
            if (state.blockDropAt !== undefined && state.blockDropLatencyMs === undefined) {
              state.blockDropLatencyMs = Math.max(0, state.blockDropAt - state.targetAirAt);
            }
            maybeRecordPriorBlockDrop();
            completeBlockAction();
          } else if (state.targetAirAt !== undefined) {
            ready.reject(new Error(
              `Broken block ${update.x},${update.y},${update.z} rebounded to state ` +
              `${update.stateId} within ${options.blockReboundWindowMs} ms`
            ));
          }
        }
        // Under world-generation load the authoritative update can arrive just after the
        // 750 ms probe timer advances. It still proves that a previously probed target is
        // solid and reachable, so do not discard it only because another probe is current.
        if (update.stateId !== 0 && !state.blockActionCandidateConfirmed &&
            state.blockActionProbedTargets.some((target) => sameBlockPos(update, target))) {
          startConfirmedBlockAction(update);
        }
      }
    }
  }

  function maybeScheduleMining() {
    if (state.miningScheduled || state.chunkPackets === 0) {
      return;
    }
    if (state.roamSteps > 0 && !state.roamCompleted) {
      return;
    }
    if (options.skipMining) {
      state.miningScheduled = true;
      state.miningCompleted = true;
      maybeResolveReady();
      return;
    }
    if (!state.playerPosition) {
      return;
    }
    state.miningScheduled = true;
    state.blockActionCandidates = createBlockCandidates(state.playerPosition);
    // Let world generation and network traffic go idle first. The old transport
    // lost this exact case because no later read event pulled the action packet.
    if (options.requireBlockDrop) {
      setTimeout(prepareDeterministicDropProbe, 500);
    } else {
      setTimeout(probeNextBlock, 3000);
    }
  }

  function prepareDeterministicDropProbe() {
    const target = {
      x: Math.floor(state.playerPosition.x) + 2,
      y: Math.floor(state.playerPosition.y) + 1,
      z: Math.floor(state.playerPosition.z),
    };
    state.blockActionCandidates = [target];
    state.blockActionTarget = target;
    state.blockActionProbedTargets.push(target);
    state.blockActionProbeCount++;
    send(encodePacket(
      serverboundPlay.chatCommand,
      encodeString("item replace entity @s weapon.mainhand with minecraft:diamond_pickaxe"),
      state.compressionThreshold
    ));
    send(encodePacket(
      serverboundPlay.chatCommand,
      encodeString(`setblock ${target.x} ${target.y} ${target.z} minecraft:nether_bricks`),
      state.compressionThreshold
    ));
    // Chat commands are deliberately queued onto the server's main executor. During heavy
    // generation, wait for the resulting block update instead of allowing a later player-action
    // packet to overtake the command and turn this into a false mining failure.
    state.blockActionProbeTimer = setTimeout(() => {
      if (!state.blockActionCandidateConfirmed) {
        ready.reject(new Error("Prepared probe block was not observed within 10 seconds"));
      }
    }, 10000);
  }

  function startRoam() {
    state.roamRequested = true;
    maybeScheduleRoam();
  }

  function scheduleRoamHeartbeat() {
    clearTimeout(state.roamHeartbeatTimer);
    const sendHeartbeat = () => {
      if (!state.roamStepWaiting || state.roamCompleted || !state.playerPosition) {
        state.roamHeartbeatTimer = undefined;
        return;
      }
      send(
        encodePacket(
          serverboundPlay.movePlayerPos,
          encodeMovePlayerPosition(state.playerPosition),
          state.compressionThreshold
        )
      );
      state.roamHeartbeatTimer = setTimeout(sendHeartbeat, 250);
    };
    state.roamHeartbeatTimer = setTimeout(sendHeartbeat, 25);
  }

  function maybeScheduleRoam() {
    if (!state.roamRequested || state.roamScheduled || state.roamCompleted ||
        state.chunkPackets === 0 || !state.playerPosition) {
      return;
    }
    clearTimeout(state.roamSettleTimer);
    state.roamSettleTimer = setTimeout(() => {
      state.roamScheduled = true;
      liftPlayerForRoam();
    }, 750);
  }

  function liftPlayerForRoam() {
    if (state.roamLiftStep >= 3) {
      state.roamOriginX ??= state.playerPosition.x;
      setTimeout(sendNextRoamStep, 250);
      return;
    }
    state.roamLiftStep++;
    state.playerPosition = {
      ...state.playerPosition,
      y: state.playerPosition.y + 8,
    };
    send(
      encodePacket(
        serverboundPlay.movePlayerPos,
        encodeMovePlayerPosition(state.playerPosition),
        state.compressionThreshold
      )
    );
    setTimeout(liftPlayerForRoam, 100);
  }

  function sendNextRoamStep() {
    if (state.roamStep >= state.roamSteps) {
      state.roamCompleted = true;
      clearTimeout(state.roamHeartbeatTimer);
      state.roamHeartbeatTimer = undefined;
      options.onRoamPhase?.("complete");
      maybeScheduleMining();
      maybeResolveReady();
      return;
    }
    state.roamStep++;
    let nextX = state.playerPosition.x + state.roamStepBlocks;
    let targetKey = chunkKeyForBlock(nextX, state.playerPosition.z);
    for (let skipped = 0;
        skipped < 64 && state.uniqueChunkPositions.has(targetKey);
        skipped++) {
      nextX += state.roamStepBlocks;
      targetKey = chunkKeyForBlock(nextX, state.playerPosition.z);
    }
    if (state.uniqueChunkPositions.has(targetKey)) {
      ready.reject(new Error("Could not find an unloaded roam target within 64 steps"));
      return;
    }
    state.roamTargetX = nextX;
    state.roamTargetChunkKey = targetKey;
    state.roamBaselineChunkPackets = state.uniqueChunkPositions.size;
    state.roamStepStartedAt = Date.now();
    state.roamStepWaiting = true;
    options.onRoamPhase?.(`${state.roamStep}/${state.roamSteps}`);
    // A direct client movement jump is rejected by the vanilla server once the nearest
    // unloaded chunk is more than a few blocks away. Use the command path granted only
    // to this isolated smoke player so the test exercises chunk generation instead of
    // tripping the server's movement validation.
    if (state.roamStep === 1 && options.roamSpectator) {
      send(encodePacket(
        serverboundPlay.chatCommand,
        encodeString("gamemode spectator @s"),
        state.compressionThreshold
      ));
    }
    send(encodePacket(
      serverboundPlay.chatCommand,
      encodeString(
        `tp @s ${nextX} ${state.playerPosition.y} ${state.playerPosition.z}`
      ),
      state.compressionThreshold
    ));
    state.roamStepTimer = setTimeout(() => {
      clearTimeout(state.roamHeartbeatTimer);
      state.roamHeartbeatTimer = undefined;
      ready.reject(new Error(
        `Roam step ${state.roamStep} produced no new chunk packet within ` +
          `${options.roamTimeoutMs} ms`
      ));
    }, options.roamTimeoutMs);
  }

  function maybeCompleteRoamStep(chunkPosition) {
    if (!state.roamScheduled || state.roamCompleted || !state.roamStepWaiting ||
        `${chunkPosition.x},${chunkPosition.z}` !== state.roamTargetChunkKey) {
      return;
    }
    state.roamStepWaiting = false;
    clearTimeout(state.roamStepTimer);
    clearTimeout(state.roamHeartbeatTimer);
    state.roamHeartbeatTimer = undefined;
    state.roamTimeline.push({
      step: state.roamStep,
      blocks: Math.round(Math.abs(state.roamTargetX - state.roamOriginX)),
      targetChunk: state.roamTargetChunkKey,
      newChunkPositions: state.uniqueChunkPositions.size - state.roamBaselineChunkPackets,
      firstChunkMs: Date.now() - state.roamStepStartedAt,
    });
    setTimeout(sendNextRoamStep, 250);
  }

  function probeNextBlock() {
    if (state.blockActionCandidateIndex >= state.blockActionCandidates.length) {
      ready.reject(new Error("No reachable solid block produced break progress"));
      return;
    }
    state.blockActionTarget =
      state.blockActionCandidates[state.blockActionCandidateIndex++];
    state.blockActionProbedTargets.push(state.blockActionTarget);
    state.blockActionProbeCount++;
    sendPlayerAction(0);
    state.blockActionProbeTimer = setTimeout(() => {
      if (state.blockActionCandidateConfirmed) {
        return;
      }
      probeNextBlock();
    }, 750);
  }

  function startConfirmedBlockAction(target) {
    state.blockActionCandidateConfirmed = true;
    state.blockActionTarget = {x: target.x, y: target.y, z: target.z};
    state.targetBlockStateId = target.stateId;
    clearTimeout(state.blockActionProbeTimer);
    state.miningStartedAt = Date.now();
    sendPlayerAction(0);
    state.blockActionStopTimer = setTimeout(() => {
      state.blockActionStopSequence = sendPlayerAction(2);
      clearTimeout(state.blockActionAckTimer);
      state.blockActionAckTimer = setTimeout(() => {
        ready.reject(new Error(
          `Block-action STOP sequence ${state.blockActionStopSequence} was not acknowledged`
        ));
      }, 5000);
      state.blockActionRetryTimer = setTimeout(() => {
        if (state.targetAirUpdates > 0) {
          return;
        }
        state.blockActionCandidateConfirmed = false;
        sendPlayerAction(1);
        setTimeout(probeNextBlock, 100);
      }, 4000);
    }, options.blockActionHoldMs);
  }

  function sendPlayerAction(action) {
    const sequence = ++state.blockActionSequence;
    state.blockActionSentAt.set(sequence, Date.now());
    send(encodePacket(
      serverboundPlay.playerAction,
      concatenateMany([
        encodeVarInt(action),
        encodeBlockPos(state.blockActionTarget),
        new Uint8Array([1]),
        encodeVarInt(sequence),
      ]),
      state.compressionThreshold
    ));
    return sequence;
  }

  function completeBlockAction() {
    if (state.targetAirUpdates < 1 || state.targetStableAt === undefined) {
      return;
    }
    if (state.blockActionStopSequence === undefined ||
        !state.blockActionAckSequences.includes(state.blockActionStopSequence)) {
      return;
    }
    if (options.requireBlockDrop && state.blockDropEntity === undefined) {
      if (state.blockDropTimer === undefined) {
        state.blockDropTimer = setTimeout(() => {
          ready.reject(new Error(
            `Broken block state ${state.targetBlockStateId} produced no nearby entity ` +
            `within ${options.blockDropTimeoutMs} ms`
          ));
        }, options.blockDropTimeoutMs);
      }
      return;
    }
    if (options.requireBlockDrop && !state.persistenceMarkerScheduled) {
      state.persistenceMarkerScheduled = true;
      send(encodePacket(
        serverboundPlay.chatCommand,
        encodeString(
          `setblock ${state.blockActionTarget.x} ${state.blockActionTarget.y} ` +
          `${state.blockActionTarget.z + 1} minecraft:gold_block`
        ),
        state.compressionThreshold
      ));
      setTimeout(() => {
        state.persistenceMarkerCompleted = true;
        completeBlockAction();
      }, 500);
      return;
    }
    if (options.requireBlockDrop && !state.persistenceMarkerCompleted) {
      return;
    }
    state.blockActionLatencyMs = Date.now() - state.miningStartedAt;
    state.miningCompleted = true;
    clearTimeout(state.blockActionProbeTimer);
    clearTimeout(state.blockActionStopTimer);
    clearTimeout(state.blockActionAckTimer);
    clearTimeout(state.blockActionRetryTimer);
    clearTimeout(state.blockDropTimer);
    clearTimeout(state.blockReboundTimer);
    maybeResolveReady();
  }

  function maybeResolveReady() {
    if (!state.roamCompleted || !state.miningCompleted) {
      return;
    }
    if (state.roamTimeline.length !== state.roamSteps) {
      ready.reject(new Error(
        `Roam completed with ${state.roamTimeline.length}/${state.roamSteps} measured steps`
      ));
      return;
    }
    if (options.requireBlockDrop &&
        (state.blockActionProbeCount < 1 || state.targetAirUpdates < 1 ||
          state.targetStableAt === undefined ||
          !state.blockActionAckSequences.includes(state.blockActionStopSequence) ||
          state.blockDropEntity === undefined ||
          state.blockDropEntity.entityTypeId !== itemEntityTypeId ||
          state.blockDropLatencyMs === undefined)) {
      ready.reject(new Error("Block-drop smoke completed without a confirmed dropped entity"));
      return;
    }
    ready.resolve();
  }

  function maybeRecordPriorBlockDrop() {
    if (!state.blockActionTarget || state.targetAirAt === undefined) {
      return;
    }
    for (let index = state.addedEntities.length - 1; index >= 0; index--) {
      const entity = state.addedEntities[index];
      if (entity.receivedAt < state.miningStartedAt) {
        break;
      }
      if (entity.entityTypeId === itemEntityTypeId &&
          isNearBlock(entity, state.blockActionTarget)) {
        recordBlockDrop(entity);
        return;
      }
    }
  }

  function maybeRecordBlockDrop(entity) {
    if (state.blockDropEntity !== undefined || !state.blockActionTarget ||
        state.miningStartedAt === undefined || entity.receivedAt < state.miningStartedAt ||
        entity.entityTypeId !== itemEntityTypeId ||
        !isNearBlock(entity, state.blockActionTarget)) {
      return;
    }
    recordBlockDrop(entity);
    completeBlockAction();
  }

  function recordBlockDrop(entity) {
    if (state.blockDropEntity !== undefined) {
      return;
    }
    state.blockDropEntity = entity;
    state.blockDropAt = entity.receivedAt;
    if (state.targetAirAt !== undefined) {
      state.blockDropLatencyMs = Math.max(0, entity.receivedAt - state.targetAirAt);
    }
  }

  function maybeScheduleChunkBatchAck() {
    if (state.chunkBatchAckTimer !== undefined ||
        state.chunkPackets <= state.lastAckedChunkPackets) {
      return;
    }
    state.chunkBatchAckTimer = setTimeout(() => {
      state.chunkBatchAckTimer = undefined;
      if (state.chunkPackets <= state.lastAckedChunkPackets) {
        return;
      }
      const ackedChunkPackets = state.chunkPackets;
      state.lastAckedChunkPackets = ackedChunkPackets;
      send(encodePacket(
        serverboundPlay.chunkBatchReceived,
        encodeFloat(options.chunkBatchDesiredRate),
        state.compressionThreshold
      ), () => {
        state.chunkBatchAckSent = true;
        state.chunkBatchAckCount++;
        state.chunkBatchAckTimeline.push({
          ackIndex: state.chunkBatchAckCount,
          chunkPacketCount: ackedChunkPackets,
          sentAt: Date.now(),
          sentAtMs: performance.now(),
        });
      });
    }, options.chunkBatchAckDelayMs);
  }

  function send(bytes, onSent) {
    pendingSends.push({bytes, onSent});
    flushSends();
  }

  function snapshot() {
    return {
      phase: state.phase,
      compressionThreshold: state.compressionThreshold,
      configurationPackets: state.configurationPackets,
      configurationFinished: state.configurationFinished,
      playPackets: state.playPackets,
      playLoginPackets: state.playLoginPackets,
      chunkPackets: state.chunkPackets,
      chunkTimeline: state.chunkTimeline.slice(),
      knownPackRequests: state.knownPackRequests,
      loginProfileId: state.loginProfileId,
      playerPosition: state.playerPosition,
      blockActionAcks: state.blockActionAcks,
      blockActionAckSequences: state.blockActionAckSequences.slice(),
      blockActionAckLatenciesMs: state.blockActionAckLatenciesMs.slice(),
      blockActionMaxAckLatencyMs: state.blockActionMaxAckLatencyMs,
      blockActionTarget: state.blockActionTarget,
      blockUpdates: state.blockUpdates.slice(),
      blockActionProbeCount: state.blockActionProbeCount,
      blockActionLatencyMs: state.blockActionLatencyMs,
      blockActionHoldMs: options.blockActionHoldMs,
      blockActionStopSequence: state.blockActionStopSequence,
      blockReboundWindowMs: options.blockReboundWindowMs,
      blockStable: state.targetStableAt !== undefined,
      miningCompleted: state.miningCompleted,
      targetAirUpdates: state.targetAirUpdates,
      targetBlockStateId: state.targetBlockStateId,
      addEntityPackets: state.addEntityPackets,
      blockDropEntity: state.blockDropEntity,
      blockDropLatencyMs: state.blockDropLatencyMs,
      persistenceMarkerCompleted: state.persistenceMarkerCompleted,
      roamSteps: state.roamSteps,
      roamCompleted: state.roamCompleted,
      roamTimeline: state.roamTimeline.slice(),
      roamCorrections: state.roamCorrections.slice(),
      uniqueChunkPositions: state.uniqueChunkPositions.size,
      chunkDigests: Object.fromEntries(
        [...state.chunkDigests].sort(([left], [right]) => left.localeCompare(right)),
      ),
      chunkCenters: state.chunkCenters.slice(),
      loginToPlayMs: state.loginToPlayMs,
      loginToFirstChunkMs: state.loginToFirstChunkMs,
      playToFirstChunkMs: state.playToFirstChunkMs,
      loginToCompressionMs: elapsed(state.loginStartedAt, state.compressionAt),
      compressionToLoginFinishedMs: elapsed(state.compressionAt, state.loginFinishedAt),
      loginFinishedToConfigurationFinishedMs:
        elapsed(state.loginFinishedAt, state.configurationFinishedAt),
      configurationFinishedToPlayMs:
        elapsed(state.configurationFinishedAt, state.playLoginAt),
      configurationTimeline: state.configurationTimeline.slice(),
      playTimeline: state.playTimeline.slice(),
      chunkBatchAckSent: state.chunkBatchAckSent,
      chunkBatchAckCount: state.chunkBatchAckCount,
      chunkBatchAckTimeline: state.chunkBatchAckTimeline.slice(),
      chunkBatchAckDelayMs: options.chunkBatchAckDelayMs,
      chunkBatchDesiredRate: options.chunkBatchDesiredRate,
      receivedPacketIds: state.receivedPacketIds,
    };
  }

  function flushSends() {
    while (!remotePaused && pendingSends.length > 0) {
      const {bytes, onSent} = pendingSends.shift();
      const buffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
      port.postMessage(buffer, [buffer]);
      onSent?.();
    }
  }

  return state;
}

function elapsed(start, end) {
  return start === undefined || end === undefined ? undefined : end - start;
}

function encodePacket(id, payload, compressionThreshold) {
  const packet = concatenate(encodeVarInt(id), payload);
  let body = packet;
  if (compressionThreshold !== undefined) {
    if (packet.byteLength >= compressionThreshold) {
      throw new Error("Smoke client only sends small uncompressed post-login packets");
    }
    body = concatenate(encodeVarInt(0), packet);
  }
  return concatenate(encodeVarInt(body.byteLength), body);
}

function encodeString(value) {
  const bytes = new TextEncoder().encode(value);
  return concatenate(encodeVarInt(bytes.byteLength), bytes);
}

function encodeClientInformation() {
  return concatenateMany([
    encodeString("en_us"),
    new Uint8Array([6]),
    encodeVarInt(0),
    new Uint8Array([1, 0x7f]),
    encodeVarInt(1),
    new Uint8Array([0, 1]),
    encodeVarInt(0),
  ]);
}

function encodeFloat(value) {
  const result = new Uint8Array(4);
  new DataView(result.buffer).setFloat32(0, value, false);
  return result;
}

function encodeMovePlayerPosition(position) {
  const result = new Uint8Array(25);
  const view = new DataView(result.buffer);
  view.setFloat64(0, position.x, false);
  view.setFloat64(8, position.y, false);
  view.setFloat64(16, position.z, false);
  result[24] = 0;
  return result;
}

function encodeBlockPos(position) {
  const packed = (BigInt(position.x) & 0x3ffffffn) << 38n |
    (BigInt(position.z) & 0x3ffffffn) << 12n |
    (BigInt(position.y) & 0xfffn);
  const result = new Uint8Array(8);
  new DataView(result.buffer).setBigUint64(0, packed, false);
  return result;
}

function decodePlayerPosition(payload) {
  const teleportId = decodeVarInt(payload, 0);
  if (teleportId === undefined || payload.byteLength < teleportId.bytesRead + 56) {
    throw new Error("Player-position packet was truncated");
  }
  const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
  const offset = teleportId.bytesRead;
  return {
    teleportId: teleportId.value,
    x: view.getFloat64(offset, false),
    y: view.getFloat64(offset + 8, false),
    z: view.getFloat64(offset + 16, false),
  };
}

function decodeChunkPosition(payload) {
  if (payload.byteLength < 8) {
    throw new Error("Chunk packet omitted its chunk coordinates");
  }
  const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
  return {x: view.getInt32(0, false), z: view.getInt32(4, false)};
}

function decodeChunkCacheCenter(payload) {
  const x = decodeVarInt(payload, 0);
  if (x === undefined) {
    return undefined;
  }
  const z = decodeVarInt(payload, x.bytesRead);
  return z === undefined ? undefined : {x: x.value, z: z.value};
}

function decodeAddEntity(payload) {
  const entityId = decodeVarInt(payload, 0);
  if (entityId === undefined) {
    throw new Error("Add-entity packet omitted its entity id");
  }
  let offset = entityId.bytesRead + 16;
  if (payload.byteLength < offset) {
    throw new Error("Add-entity packet omitted its UUID");
  }
  const entityType = decodeVarInt(payload, offset);
  if (entityType === undefined) {
    throw new Error("Add-entity packet omitted its entity type");
  }
  offset += entityType.bytesRead;
  if (payload.byteLength < offset + 24) {
    throw new Error("Add-entity packet omitted its position");
  }
  const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
  return {
    entityId: entityId.value,
    entityTypeId: entityType.value,
    x: view.getFloat64(offset, false),
    y: view.getFloat64(offset + 8, false),
    z: view.getFloat64(offset + 16, false),
  };
}

function decodeBlockUpdate(payload) {
  if (payload.byteLength < 9) {
    throw new Error("Block-update packet was truncated");
  }
  const packed = new DataView(
    payload.buffer,
    payload.byteOffset,
    payload.byteLength
  ).getBigUint64(0, false);
  const state = decodeVarInt(payload, 8);
  if (state === undefined) {
    throw new Error("Block-update packet omitted its state id");
  }
  return {
    x: signedBits(packed >> 38n, 26n),
    y: signedBits(packed, 12n),
    z: signedBits(packed >> 12n, 26n),
    stateId: state.value,
  };
}

function createBlockCandidates(position) {
  const baseX = Math.floor(position.x);
  const baseY = Math.floor(position.y);
  const baseZ = Math.floor(position.z);
  // Never mine the stationary smoke player's supporting column. Falling into
  // the probe hole can kill the client and make chunk streaming look stalled.
  const offsets = [[1, 0], [-1, 0], [0, 1], [0, -1]];
  const candidates = [];
  for (let depth = 1; depth <= 4; depth++) {
    for (const [offsetX, offsetZ] of offsets) {
      candidates.push({
        x: baseX + offsetX,
        y: baseY - depth,
        z: baseZ + offsetZ,
      });
    }
  }
  return candidates;
}

function signedBits(value, bits) {
  const mask = (1n << bits) - 1n;
  const sign = 1n << (bits - 1n);
  const normalized = value & mask;
  return Number(normalized & sign ? normalized - (1n << bits) : normalized);
}

function sameBlockPos(first, second) {
  return first.x === second.x && first.y === second.y && first.z === second.z;
}

function chunkKeyForBlock(x, z) {
  return `${Math.floor(x) >> 4},${Math.floor(z) >> 4}`;
}

function isNearBlock(entity, block) {
  return Math.abs(entity.x - (block.x + 0.5)) <= 2.0 &&
    Math.abs(entity.y - (block.y + 0.5)) <= 2.0 &&
    Math.abs(entity.z - (block.z + 0.5)) <= 2.0;
}

function encodeVarInt(value) {
  const bytes = [];
  let remaining = value >>> 0;
  do {
    let next = remaining & 0x7f;
    remaining >>>= 7;
    if (remaining !== 0) {
      next |= 0x80;
    }
    bytes.push(next);
  } while (remaining !== 0);
  return new Uint8Array(bytes);
}

function decodeVarInt(bytes, offset) {
  let value = 0;
  for (let index = 0; index < 5; index++) {
    const position = offset + index;
    if (position >= bytes.byteLength) {
      return undefined;
    }
    const next = bytes[position];
    value |= (next & 0x7f) << (index * 7);
    if ((next & 0x80) === 0) {
      return {value, bytesRead: index + 1};
    }
  }
  throw new Error("Minecraft VarInt exceeded five bytes");
}

function concatenate(first, second) {
  const result = new Uint8Array(first.byteLength + second.byteLength);
  result.set(first, 0);
  result.set(second, first.byteLength);
  return result;
}

function concatenateMany(parts) {
  let result = new Uint8Array(0);
  for (const part of parts) {
    result = concatenate(result, part);
  }
  return result;
}

function hexBytes(value) {
  const result = new Uint8Array(value.length / 2);
  for (let index = 0; index < result.length; index++) {
    result[index] = Number.parseInt(value.slice(index * 2, index * 2 + 2), 16);
  }
  return result;
}

function isControlMessage(message) {
  return message && typeof message === "object" &&
    !(message instanceof ArrayBuffer) && !ArrayBuffer.isView(message);
}

function percentile(sortedValues, fraction) {
  if (sortedValues.length === 0) {
    return 0;
  }
  return sortedValues[Math.min(
    sortedValues.length - 1,
    Math.max(0, Math.ceil(sortedValues.length * fraction) - 1),
  )];
}

function summarizeProbeLatencies(samples, minimumStartedAt) {
  if (!minimumStartedAt) {
    return {samples: 0, p95Ms: 0, p99Ms: 0, maxMs: 0};
  }
  const latencies = samples
    .filter((sample) => sample.startedAt >= minimumStartedAt)
    .map((sample) => sample.latencyMs)
    .sort((left, right) => left - right);
  return {
    samples: latencies.length,
    p95Ms: percentile(latencies, 0.95),
    p99Ms: percentile(latencies, 0.99),
    maxMs: latencies.at(-1) || 0,
  };
}

function summarizeProbePhases(samples) {
  const phases = {};
  for (const sample of samples) {
    (phases[sample.phase] ||= []).push(sample.latencyMs);
  }
  for (const [phase, latencies] of Object.entries(phases)) {
    latencies.sort((left, right) => left - right);
    phases[phase] = {
      samples: latencies.length,
      p95Ms: percentile(latencies, 0.95),
      p99Ms: percentile(latencies, 0.99),
      maxMs: latencies.at(-1) || 0,
    };
  }
  return phases;
}

function summarizeGameplayProbeLatencies(samples) {
  const latencies = samples
    .filter((sample) => isGameplayProbePhase(sample.phase))
    .map((sample) => sample.latencyMs)
    .sort((left, right) => left - right);
  return {
    samples: latencies.length,
    p95Ms: percentile(latencies, 0.95),
    p99Ms: percentile(latencies, 0.99),
    maxMs: latencies.at(-1) || 0,
  };
}

function isGameplayProbePhase(phase) {
  return phase === "server-created" ||
    phase === "server-listener-ready" ||
    phase === "distance-staged" ||
    phase.startsWith("distance-") ||
    phase.startsWith("roam-");
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return {promise, resolve, reject};
}

function installWorkerGlobals() {
  Error.stackTraceLimit = 100;
  class NodeWorkerGlobalScope {}
  Object.setPrototypeOf(globalThis, NodeWorkerGlobalScope.prototype);
  globalThis.WorkerGlobalScope = NodeWorkerGlobalScope;
  globalThis.MessagePort = MessagePort;
  globalThis.self = globalThis;
  const worldgenSliceMillis = Number(process.env.GAIUS_SMOKE_WORLDGEN_SLICE_MS || "");
  if (Number.isFinite(worldgenSliceMillis) && worldgenSliceMillis > 0) {
    globalThis.__gaiusWorldgenSliceMillis = worldgenSliceMillis;
  }
  const distanceManagerUpdateBudget = Number(
    process.env.GAIUS_SMOKE_DISTANCE_MANAGER_UPDATE_BUDGET || "",
  );
  if (Number.isFinite(distanceManagerUpdateBudget) && distanceManagerUpdateBudget > 0) {
    globalThis.__gaiusDistanceManagerUpdateBudget = distanceManagerUpdateBudget;
  }
  globalThis.location = pathToFileURL(bootstrapPath);
  globalThis.location.search = "";
  globalThis.postMessage = (message, transfer) => parentPort.postMessage(message, transfer);
  globalThis.close = () => process.exit(0);
  globalThis.importScripts = (...urls) => {
    for (const url of urls) {
      const path = fileURLToPath(String(url));
      let source = fs.readFileSync(path, "utf8");
      if (process.env.GAIUS_SMOKE_MIN_SERVER_VIEW_DISTANCE === "2") {
        const minimumDistanceReturn =
          "return !(typeof WorkerGlobalScope !== 'undefined' && " +
          "globalThis instanceof WorkerGlobalScope ? 1 : 0) ? 2 : 1;";
        if (!source.includes(minimumDistanceReturn)) {
          throw new Error(
            "The diagnostic Worker has no replaceable minimum view-distance method",
          );
        }
        source = source.replace(
          minimumDistanceReturn,
          "return 2;",
        );
      }
      if (process.env.GAIUS_SMOKE_CAPTURE_JAVA_ERRORS === "1") {
        const loggerErrorStart =
          "osh_AbstractLogger_error = ($this, $format, $arg1, $arg2) => {";
        const instrumentedLoggerErrorStart = `${loggerErrorStart}\n` +
          "    if ($arg2 && $arg2.$jsException && $arg2.$jsException.stack) {\n" +
          "        globalThis.postMessage({\n" +
          "            type: 'node-java-error-stack',\n" +
          "            detail: String($arg2.$jsException.stack)\n" +
          "        });\n" +
          "    }";
        if (!source.includes(loggerErrorStart)) {
          throw new Error(
            "The diagnostic Worker has no instrumentable logger error method",
          );
        }
        source = source.replace(loggerErrorStart, instrumentedLoggerErrorStart);
      }
      if (process.env.GAIUS_SMOKE_UNBOUNDED_DISTANCE_MANAGER === "1") {
        const boundedDistanceManagerBudget =
          "return Math.max(8, Math.min(512, Math.floor(configured)));";
        if (!source.includes(boundedDistanceManagerBudget)) {
          throw new Error(
            "The diagnostic Worker has no replaceable distance-manager budget",
          );
        }
        source = source.replace(
          boundedDistanceManagerBudget,
          "return Math.max(8, Math.floor(configured));",
        );
      }
      if (process.env.GAIUS_SMOKE_DEFER_DISTANCE_MANAGER_FUTURES === "1") {
        const distanceUpdateTelemetry =
          "$rt_java.dgb_BrowserWorldgenScheduler_recordDistanceManagerUpdatesJs$js_body$_17(" +
          "$rt_java.dgb_BrowserWorldgenScheduler_lastDistanceManagerUpdateBudget, " +
          "jl_Math_max(0, $updates));";
        const deferAfterTelemetry = `${distanceUpdateTelemetry}\n` +
          "        if (!nmwll_LeveledPriorityQueue_isEmpty($chunk.$priorityQueue)) {\n" +
          "            return 1;\n" +
          "        }";
        if (!source.includes(distanceUpdateTelemetry)) {
          throw new Error(
            "The diagnostic Worker has no instrumentable distance-manager telemetry",
          );
        }
        source = source.replace(distanceUpdateTelemetry, deferAfterTelemetry);
      }
      vm.runInThisContext(source, {filename: path});
    }
  };
  globalThis.XMLHttpRequest = class NodeSmokeXmlHttpRequest {
    readyState = 0;
    status = 0;
    statusText = "";
    response = new ArrayBuffer(0);
    responseType = "";
    onreadystatechange = null;

    open() {
      this.readyState = 1;
    }

    setRequestHeader() {}

    getAllResponseHeaders() {
      return "";
    }

    send() {
      queueMicrotask(() => {
        parentPort.postMessage({type: "node-xhr-request"});
        this.status = 404;
        this.statusText = "Not Found";
        this.response = new ArrayBuffer(0);
        this.readyState = 4;
        this.onreadystatechange?.();
      });
    }
  };
  globalThis.indexedDB = createMemoryIndexedDb();
}

function createMemoryIndexedDb() {
  return {
    open() {
      const request = {};
      queueMicrotask(() => {
        const database = {
          objectStoreNames: {contains: () => false},
          createObjectStore() {},
          close() {},
          transaction() {
            const transaction = {
              objectStore() {
                return {
                  openCursor() {
                    const cursorRequest = {};
                    queueMicrotask(() => {
                      cursorRequest.result = null;
                      cursorRequest.onsuccess?.();
                    });
                    return cursorRequest;
                  },
                  put(record) {
                    const value = record && record.value;
                    const bytes = value && value.encoding === "gzip"
                      ? value.bytes
                      : value;
                    parentPort.postMessage({
                      type: "node-idb-put",
                      path: String(record && record.path || ""),
                      encoding: value && value.encoding || typeof value,
                      bytes: bytes && bytes.byteLength || 0,
                    });
                  },
                  delete() {},
                };
              },
            };
            queueMicrotask(() => transaction.oncomplete?.());
            return transaction;
          },
        };
        request.result = database;
        request.onupgradeneeded?.();
        request.onsuccess?.();
      });
      return request;
    },
  };
}
