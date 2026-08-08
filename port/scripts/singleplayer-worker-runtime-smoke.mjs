import fs from "node:fs";
import {createHash} from "node:crypto";
import {Session as InspectorSession} from "node:inspector";
import vm from "node:vm";
import {inflateSync} from "node:zlib";
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
const bootstrapPath = rootDirectory + "port/web/dist/singleplayer-server-worker.js";

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
  const blockDropTimeoutMs = Number(
    process.env.GAIUS_SMOKE_BLOCK_DROP_TIMEOUT_MS || "5000",
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
  const maximumGameplayStallMs = Number(
    process.env.GAIUS_SMOKE_MAX_GAMEPLAY_STALL_MS || "500",
  );
  const cpuProfilePhase = process.env.GAIUS_SMOKE_CPU_PROFILE_PHASE || "";
  const cpuProfilePath = process.env.GAIUS_SMOKE_CPU_PROFILE_PATH ||
    (rootDirectory + "port/target/singleplayer-worker-" +
      cpuProfilePhase.replace(/[^a-z0-9._-]+/gi, "-") + ".cpuprofile");
  if (!Number.isFinite(maximumGameplayStallMs) || maximumGameplayStallMs <= 0) {
    throw new Error("GAIUS_SMOKE_MAX_GAMEPLAY_STALL_MS must be a positive number");
  }
  if (!Number.isFinite(blockDropTimeoutMs) || blockDropTimeoutMs <= 0) {
    throw new Error("GAIUS_SMOKE_BLOCK_DROP_TIMEOUT_MS must be a positive number");
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
  const worker = new Worker(new URL(import.meta.url), {workerData: {runtime: true}});
  const {port1, port2} = new MessageChannel();
  const sessionId = "0123456789abcdef0123456789abcdef";
  const profileId = "00000000000040008000000000000002";
  const expectedStagedDistances = "1/1->7/3";
  const expectedDistanceRamp = ["2/1", "3/2", "4/3", "5/3", "6/3"];
  const expectedDistances = "7/3";
  const distanceRamp = [];
  const protocol = createProtocolClient(port2, sessionId, profileId, {
    skipMining,
    roamSteps,
    roamStepBlocks,
    roamTimeoutMs,
    roamSpectator,
    requireBlockDrop,
    blockDropTimeoutMs,
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
  let compressedRegionStorageWrites = 0;
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
  let serverCreatedAt = 0;
  let protocolReadyAt = 0;
  let workerPhase = "startup";
  let cpuProfileActive = false;
  let cpuProfileWritten = !cpuProfilePhase;
  const eventLoopProbeInterval = setInterval(() => {
    const probeId = ++eventLoopProbeId;
    eventLoopProbeStartedAt.set(probeId, {startedAt: Date.now(), phase: workerPhase});
    worker.postMessage({type: "node-event-loop-probe", probeId});
  }, 100);
  const maybeStop = () => {
    if (!protocolReady || (!stopAtFirstChunk && !configuredDistanceReady)) {
      return;
    }
    workerPhase = "stopping";
    protocol.closeTransport();
    worker.postMessage({type: "stop"});
  };
  const finish = (code) => {
    if (finished) {
      return;
    }
    finished = true;
    clearInterval(eventLoopProbeInterval);
    process.stdout.write(JSON.stringify({events}, null, 2) + "\n");
    void worker.terminate().finally(() => process.exit(code));
  };
  const timeoutMs = Number(process.env.GAIUS_SMOKE_TIMEOUT_MS || "240000");
  const timeout = setTimeout(() => {
    events.push({type: "protocol-timeout", ...protocol.snapshot()});
    finish(2);
  }, timeoutMs);
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
      ...protocol.snapshot(),
    });
    clearTimeout(timeout);
    setTimeout(() => finish(1), 2000);
  });
  worker.on("message", (message) => {
    if (message && message.type === "node-event-loop-pong") {
      latestChunkPriorityStats = message.chunkPriorityStats || latestChunkPriorityStats;
      latestNetworkStats = message.networkStats || latestNetworkStats;
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
      }
      return;
    }
    events.push(message);
    if (message && message.type === "node-console-error") {
      clearTimeout(timeout);
      finish(1);
    } else if (message && message.type === "node-cpu-profile-written") {
      cpuProfileWritten = true;
    } else if (message && message.type === "node-idb-put" && message.path.endsWith(".mca")) {
      regionStorageWrites++;
      if (message.encoding === "gzip") compressedRegionStorageWrites++;
    } else if (message && message.type === "server-created") {
      serverCreatedAt = Date.now();
      workerPhase = "server-created";
      setTimeout(() => {
        worker.postMessage({
          type: "distances",
          renderDistance: 7,
          simulationDistance: 3,
        });
      }, 1000);
    } else if (message && message.type === "server-distances-staged" &&
        message.detail === expectedStagedDistances && !distanceSyncReady) {
      workerPhase = "distance-staged";
      distanceSyncReady = true;
      protocol.startLogin();
    } else if (message && message.type === "server-distances-ramping") {
      workerPhase = "distance-" + message.detail;
      distanceRamp.push(message.detail);
    } else if (message && message.type === "server-distances" &&
        message.detail === expectedDistances && !configuredDistanceReady) {
      workerPhase = "distance-" + message.detail;
      if (JSON.stringify(distanceRamp) !== JSON.stringify(expectedDistanceRamp)) {
        events.push({type: "distance-ramp-mismatch", expected: expectedDistanceRamp, actual: distanceRamp});
        clearTimeout(timeout);
        finish(1);
        return;
      }
      configuredDistanceReady = true;
      protocol.startRoam();
      maybeStop();
    } else if (message && message.type === "stopped" && protocolReady &&
        distanceSyncReady && (stopAtFirstChunk || configuredDistanceReady)) {
      events.push({
        type: "protocol-final",
        ...protocol.snapshot(),
        chunkPriorityStats: latestChunkPriorityStats,
        networkStats: latestNetworkStats,
      });
      const sortedProbeLatencies = eventLoopProbeLatenciesMs.slice().sort((left, right) => left - right);
      const phaseLatencies = summarizeProbePhases(eventLoopProbeSamples);
      const gameplayLatency = summarizeGameplayProbeLatencies(eventLoopProbeSamples);
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
        byPhase: phaseLatencies,
        pending: eventLoopProbeStartedAt.size,
      });
      if (gameplayLatency.maxMs > maximumGameplayStallMs) {
        events.push({
          type: "worldgen-event-loop-stall",
          maximumGameplayStallMs,
          gameplayLatency,
        });
        clearTimeout(timeout);
        finish(1);
        return;
      }
      if (!stopAtFirstChunk && !skipMining &&
          (regionStorageWrites === 0 || compressedRegionStorageWrites !== regionStorageWrites)) {
        events.push({
          type: "region-storage-mismatch",
          regionStorageWrites,
          compressedRegionStorageWrites,
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
    if (!finished) {
      events.push({type: "worker-exited-early", code, ...protocol.snapshot()});
      clearTimeout(timeout);
      finish(code === 0 ? 1 : code);
    }
  });
  worker.postMessage({
    type: "start",
    worldId: "gaius-node-runtime-smoke",
    seed: "gaius-runtime-smoke-v1",
    sessionId,
    renderDistance: 8,
    simulationDistance: 5,
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
    });
    originalConsoleError(...args);
  };
  vm.runInThisContext(fs.readFileSync(bootstrapPath, "utf8"), {filename: bootstrapPath});
  let cpuProfileSession;
  let cpuProfileStarted;
  let cpuProfileMetadata;
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
    globalThis.onmessage({data, ports: []});
  });
  parentPort.postMessage({type: "node-wrapper-ready"});
}

function inspectorPost(session, method) {
  return new Promise((resolve, reject) => {
    session.post(method, (error, result) => {
      if (error) reject(error);
      else resolve(result);
    });
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
    targetBlockStateId: undefined,
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
    persistenceMarkerScheduled: false,
    persistenceMarkerCompleted: false,
    chunkBatchAckSent: false,
    chunkBatchAckCount: 0,
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
      encodeVarInt(774),
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
      if (packetId.value === 32) {
        throw new Error("Official server disconnected after entering PLAY");
      }
      if (packetId.value === 1) {
        const entity = {...decodeAddEntity(payload), receivedAt: Date.now()};
        state.addEntityPackets++;
        if (state.addedEntities.length < 512) {
          state.addedEntities.push(entity);
        }
        maybeRecordBlockDrop(entity);
      } else if (packetId.value === 43) {
        send(encodePacket(27, payload, state.compressionThreshold));
      } else if (packetId.value === 59) {
        send(encodePacket(44, payload, state.compressionThreshold));
      } else if (packetId.value === 48) {
        state.playLoginPackets++;
        state.playLoginAt ??= Date.now();
        state.loginToPlayMs ??= elapsed(state.loginStartedAt, state.playLoginAt);
        if (!state.playerLoadedSent) {
          state.playerLoadedSent = true;
          send(encodePacket(43, new Uint8Array(0), state.compressionThreshold));
        }
      } else if (packetId.value === 44) {
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
      } else if (packetId.value === 70) {
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
            0,
            encodeVarInt(state.playerPosition.teleportId),
            state.compressionThreshold
          )
        );
        if (state.roamScheduled && state.roamStep > 0) {
          // A real client resumes movement heartbeats after accepting a server correction.
          scheduleRoamHeartbeat();
        }
      } else if (packetId.value === 92) {
        const center = decodeChunkCacheCenter(payload);
        if (center && state.chunkCenters.length < 32) {
          state.chunkCenters.push({...center, receivedAt: Date.now()});
        }
      } else if (packetId.value === 4) {
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
      } else if (packetId.value === 8) {
        const update = decodeBlockUpdate(payload);
        if (state.blockUpdates.length < 128) {
          state.blockUpdates.push({...update, receivedAt: Date.now()});
        }
        if (state.blockActionTarget &&
            sameBlockPos(update, state.blockActionTarget) && update.stateId === 0) {
          state.targetAirUpdates++;
          state.targetAirAt ??= Date.now();
          if (state.blockDropAt !== undefined && state.blockDropLatencyMs === undefined) {
            state.blockDropLatencyMs = Math.max(0, state.blockDropAt - state.targetAirAt);
          }
          maybeRecordPriorBlockDrop();
          completeBlockAction();
        } else if (state.blockActionTarget &&
            !state.blockActionCandidateConfirmed && update.stateId !== 0 &&
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
      6,
      encodeString("item replace entity @s weapon.mainhand with minecraft:diamond_pickaxe"),
      state.compressionThreshold
    ));
    send(encodePacket(
      6,
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
          29,
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
        29,
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
        6,
        encodeString("gamemode spectator @s"),
        state.compressionThreshold
      ));
    }
    send(encodePacket(
      6,
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
      sendPlayerAction(2);
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
      40,
      concatenateMany([
        encodeVarInt(action),
        encodeBlockPos(state.blockActionTarget),
        new Uint8Array([1]),
        encodeVarInt(sequence),
      ]),
      state.compressionThreshold
    ));
  }

  function completeBlockAction() {
    if (state.targetAirUpdates < 1) {
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
        6,
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
    clearTimeout(state.blockActionRetryTimer);
    clearTimeout(state.blockDropTimer);
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
          state.blockDropEntity === undefined || state.blockDropLatencyMs === undefined)) {
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
      if (isNearBlock(entity, state.blockActionTarget)) {
        recordBlockDrop(entity);
        return;
      }
    }
  }

  function maybeRecordBlockDrop(entity) {
    if (state.blockDropEntity !== undefined || !state.blockActionTarget ||
        state.miningStartedAt === undefined || entity.receivedAt < state.miningStartedAt ||
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
      state.lastAckedChunkPackets = state.chunkPackets;
      state.chunkBatchAckSent = true;
      state.chunkBatchAckCount++;
      send(encodePacket(
        10,
        encodeFloat(options.chunkBatchDesiredRate),
        state.compressionThreshold
      ));
    }, options.chunkBatchAckDelayMs);
  }

  function send(bytes) {
    pendingSends.push(bytes);
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
      chunkBatchAckDelayMs: options.chunkBatchAckDelayMs,
      chunkBatchDesiredRate: options.chunkBatchDesiredRate,
      receivedPacketIds: state.receivedPacketIds,
    };
  }

  function flushSends() {
    while (!remotePaused && pendingSends.length > 0) {
      const bytes = pendingSends.shift();
      const buffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
      port.postMessage(buffer, [buffer]);
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
  const offsets = [[0, 0], [1, 0], [-1, 0], [0, 1], [0, -1]];
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
  globalThis.location = pathToFileURL(bootstrapPath);
  globalThis.location.search = "";
  globalThis.postMessage = (message, transfer) => parentPort.postMessage(message, transfer);
  globalThis.close = () => process.exit(0);
  globalThis.importScripts = (...urls) => {
    for (const url of urls) {
      const path = fileURLToPath(String(url));
      vm.runInThisContext(fs.readFileSync(path, "utf8"), {filename: path});
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
