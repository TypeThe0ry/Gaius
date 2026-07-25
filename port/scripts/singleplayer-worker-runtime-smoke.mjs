import fs from "node:fs";
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
  const events = [];
  let finished = false;
  const skipMining = process.env.GAIUS_SMOKE_SKIP_MINING === "1";
  const worker = new Worker(new URL(import.meta.url), {workerData: {runtime: true}});
  const {port1, port2} = new MessageChannel();
  const sessionId = "0123456789abcdef0123456789abcdef";
  const profileId = "00000000000040008000000000000002";
  const expectedStagedDistances = "1/1->7/3";
  const expectedDistanceRamp = ["2/1", "3/2", "4/3", "5/3", "6/3"];
  const expectedDistances = "7/3";
  const distanceRamp = [];
  const protocol = createProtocolClient(port2, sessionId, profileId, {skipMining});
  let protocolReady = false;
  let distanceSyncReady = false;
  let configuredDistanceReady = false;
  const maybeStop = () => {
    if (!protocolReady || !configuredDistanceReady) {
      return;
    }
    protocol.closeTransport();
    worker.postMessage({type: "stop"});
  };
  const finish = (code) => {
    if (finished) {
      return;
    }
    finished = true;
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
      blockActionTarget: protocol.blockActionTarget,
      blockActionProbeCount: protocol.blockActionProbeCount,
      blockActionLatencyMs: protocol.blockActionLatencyMs,
      targetAirUpdates: protocol.targetAirUpdates,
      loginToPlayMs: protocol.loginToPlayMs,
      loginToFirstChunkMs: protocol.loginToFirstChunkMs,
      playToFirstChunkMs: protocol.playToFirstChunkMs,
      skipMining,
    });
    maybeStop();
  }).catch((error) => {
    events.push({
      type: "protocol-error",
      detail: error.stack || String(error),
      ...protocol.snapshot(),
    });
    clearTimeout(timeout);
    setTimeout(() => finish(1), 2000);
  });
  worker.on("message", (message) => {
    events.push(message);
    if (message && message.type === "server-created") {
      setTimeout(() => {
        worker.postMessage({
          type: "distances",
          renderDistance: 7,
          simulationDistance: 3,
        });
      }, 1000);
    } else if (message && message.type === "server-distances-staged" &&
        message.detail === expectedStagedDistances && !distanceSyncReady) {
      distanceSyncReady = true;
      protocol.startLogin();
    } else if (message && message.type === "server-distances-ramping") {
      distanceRamp.push(message.detail);
    } else if (message && message.type === "server-distances" &&
        message.detail === expectedDistances && !configuredDistanceReady) {
      if (JSON.stringify(distanceRamp) !== JSON.stringify(expectedDistanceRamp)) {
        events.push({type: "distance-ramp-mismatch", expected: expectedDistanceRamp, actual: distanceRamp});
        clearTimeout(timeout);
        finish(1);
        return;
      }
      configuredDistanceReady = true;
      maybeStop();
    } else if (message && message.type === "stopped" && protocolReady &&
        distanceSyncReady && configuredDistanceReady) {
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
    sessionId,
    renderDistance: 8,
    simulationDistance: 5,
    port: port1,
  }, [port1]);
} else if (workerData && workerData.runtime) {
  installWorkerGlobals();
  vm.runInThisContext(fs.readFileSync(bootstrapPath, "utf8"), {filename: bootstrapPath});
  parentPort.on("message", (data) => globalThis.onmessage({data, ports: []}));
  parentPort.postMessage({type: "node-wrapper-ready"});
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
    knownPackRequests: 0,
    loginProfileId: undefined,
    playerLoadedSent: false,
    playerPosition: undefined,
    miningScheduled: false,
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
    playLoginAt: undefined,
    firstChunkAt: undefined,
    loginToPlayMs: undefined,
    loginToFirstChunkMs: undefined,
    playToFirstChunkMs: undefined,
    miningStartedAt: undefined,
    targetAirAt: undefined,
    blockActionAcks: 0,
    blockActionAckSequences: [],
    blockActionTarget: undefined,
    blockActionLatencyMs: undefined,
    targetAirUpdates: 0,
    chunkBatchAckSent: false,
    receivedPacketIds: {
      login: [],
      configuration: [],
      play: [],
    },
    playReady: ready.promise,
    snapshot,
    startLogin,
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
      state.phase = "configuration";
      send(encodePacket(3, new Uint8Array(0), state.compressionThreshold));
      send(encodePacket(0, encodeClientInformation(), state.compressionThreshold));
    } else if (state.phase === "configuration") {
      state.configurationPackets++;
      if (packetId.value === 2) {
        throw new Error("Official server disconnected during configuration");
      }
      if (packetId.value === 14) {
        state.knownPackRequests++;
        send(encodePacket(7, encodeVarInt(0), state.compressionThreshold));
      } else if (packetId.value === 3) {
        state.configurationFinished = true;
        state.phase = "play";
        send(encodePacket(3, new Uint8Array(0), state.compressionThreshold));
      } else if (packetId.value === 4) {
        send(encodePacket(4, payload, state.compressionThreshold));
      } else if (packetId.value === 4) {
        send(encodePacket(5, payload, state.compressionThreshold));
      }
    } else if (state.phase === "play") {
      state.playPackets++;
      if (packetId.value === 32) {
        throw new Error("Official server disconnected after entering PLAY");
      }
      if (packetId.value === 43) {
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
        state.firstChunkAt ??= Date.now();
        state.loginToFirstChunkMs ??= elapsed(state.loginStartedAt, state.firstChunkAt);
        state.playToFirstChunkMs ??= elapsed(state.playLoginAt, state.firstChunkAt);
        maybeScheduleMining();
      } else if (packetId.value === 70) {
        state.playerPosition = decodePlayerPosition(payload);
        maybeScheduleMining();
        send(
          encodePacket(
            0,
            encodeVarInt(state.playerPosition.teleportId),
            state.compressionThreshold
          )
        );
      } else if (packetId.value === 4) {
        const sequence = decodeVarInt(payload, 0);
        if (sequence === undefined) {
          throw new Error("Block-action acknowledgement omitted its sequence");
        }
        state.blockActionAckSequences.push(sequence.value);
        state.blockActionAcks = Math.max(state.blockActionAcks, sequence.value);
      } else if (packetId.value === 8 && state.blockActionTarget) {
        const update = decodeBlockUpdate(payload);
        if (sameBlockPos(update, state.blockActionTarget) && update.stateId === 0) {
          state.targetAirUpdates++;
          state.targetAirAt ??= Date.now();
          completeBlockAction();
        } else if (!state.blockActionCandidateConfirmed && update.stateId !== 0 &&
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
    if (options.skipMining) {
      state.miningScheduled = true;
      state.chunkBatchAckSent = true;
      send(encodePacket(10, encodeFloat(10), state.compressionThreshold));
      ready.resolve();
      return;
    }
    if (!state.playerPosition) {
      return;
    }
    state.miningScheduled = true;
    state.blockActionCandidates = createBlockCandidates(state.playerPosition);
    // Let world generation and network traffic go idle first. The old transport
    // lost this exact case because no later read event pulled the action packet.
    setTimeout(probeNextBlock, 3000);
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
    }, 8000);
  }

  function sendPlayerAction(action) {
    const sequence = ++state.blockActionSequence;
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
    if (state.chunkBatchAckSent || state.targetAirUpdates < 1) {
      return;
    }
    state.blockActionLatencyMs = Date.now() - state.miningStartedAt;
    clearTimeout(state.blockActionProbeTimer);
    clearTimeout(state.blockActionStopTimer);
    clearTimeout(state.blockActionRetryTimer);
    state.chunkBatchAckSent = true;
    send(encodePacket(10, encodeFloat(10), state.compressionThreshold));
    ready.resolve();
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
      knownPackRequests: state.knownPackRequests,
      loginProfileId: state.loginProfileId,
      playerPosition: state.playerPosition,
      blockActionAcks: state.blockActionAcks,
      blockActionAckSequences: state.blockActionAckSequences,
      blockActionTarget: state.blockActionTarget,
      blockActionProbeCount: state.blockActionProbeCount,
      blockActionLatencyMs: state.blockActionLatencyMs,
      targetAirUpdates: state.targetAirUpdates,
      loginToPlayMs: state.loginToPlayMs,
      loginToFirstChunkMs: state.loginToFirstChunkMs,
      playToFirstChunkMs: state.playToFirstChunkMs,
      chunkBatchAckSent: state.chunkBatchAckSent,
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
                  put() {},
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
