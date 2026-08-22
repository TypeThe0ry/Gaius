package dev.gaius.browser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.server.WorldStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.teavm.jso.JSBody;
import org.teavm.platform.Platform;

/** Launches the official Minecraft server in a Web Worker for browser singleplayer. */
public final class BrowserSingleplayerClient {
    private static final int LOCAL_SERVER_PORT = 25565;
    private static final int READY_POLL_MILLIS = 25;
    private static final int READY_POLL_LIMIT = 2_400;
    /** Returned by the JS readiness probe when its poll belongs to an older launch. */
    private static final int STALE_WORKER_STATE = -2;

    private BrowserSingleplayerClient() {
    }

    /** Returns false when Worker/MessageChannel support is unavailable so vanilla can fall back. */
    public static boolean open(
            Minecraft minecraft,
            LevelStorageSource.LevelStorageAccess storage,
            WorldStem worldStem,
            boolean newWorld) {
        if (!isSupported()) {
            return false;
        }
        if (!storageConfigurationValid()) {
            return false;
        }

        String worldId = storage.getLevelId();
        worldStem.gaius$saveDataTag(storage);
        int renderDistance = minecraft.options.renderDistance().get();
        int simulationDistance = minecraft.options.simulationDistance().get();
        String sessionId = launchWorker(
                worldId,
                newWorld,
                renderDistance,
                simulationDistance);
        String launchGeneration = sessionId == null || sessionId.isEmpty()
                ? ""
                : localWorkerGeneration(sessionId);
        if (sessionId == null || sessionId.isEmpty() ||
                launchGeneration == null || launchGeneration.isEmpty()) {
            return false;
        }

        worldStem.close();
        storage.safeClose();
        beginClientHandoff(sessionId, launchGeneration);
        try {
            minecraft.disconnectWithProgressScreen();
        } catch (RuntimeException | Error throwable) {
            cancelClientHandoff(sessionId, launchGeneration);
            requestWorkerStop(sessionId, launchGeneration);
            throw throwable;
        }

        connectWhenWorkerReady(minecraft, sessionId, launchGeneration, 0);
        return true;
    }

    private static void connectWhenWorkerReady(
            Minecraft minecraft,
            String sessionId,
            String launchGeneration,
            int pollCount) {
        int state = localWorkerState(sessionId, launchGeneration);
        if (state == STALE_WORKER_STATE) {
            // A delayed poll from a previous launch must not change the screen
            // or touch the replacement Worker that reused this session key.
            return;
        }
        if (state > 0) {
            String host = "client-" + sessionId + ".gaius-local";
            ServerData serverData = new ServerData(
                    "Singleplayer",
                    host + ":" + LOCAL_SERVER_PORT,
                    ServerData.Type.LAN);
            ConnectScreen.startConnecting(
                    new TitleScreen(),
                    minecraft,
                    new ServerAddress(host, LOCAL_SERVER_PORT),
                    serverData,
                    false,
                    null);
            return;
        }
        if (state < 0 || pollCount >= READY_POLL_LIMIT) {
            String detail = state < 0
                    ? "Integrated server stopped before client attach"
                    : "Integrated server startup timed out before client attach";
            reportAttachFailure(sessionId, launchGeneration, detail);
            // This is an attach failure, not a normal disconnect.  Clear this
            // handoff and stop only the Worker that belongs to this poller;
            // the regular no-argument stop path deliberately protects a
            // pending handoff from a delayed disconnect.
            cancelClientHandoff(sessionId, launchGeneration);
            requestWorkerStop(sessionId, launchGeneration);
            minecraft.gaius$setScreen(new TitleScreen());
            return;
        }
        Platform.schedule(
                () -> Platform.startThread(
                        () -> connectWhenWorkerReady(
                                minecraft, sessionId, launchGeneration, pollCount + 1)),
                READY_POLL_MILLIS);
    }

    public static void stop() {
        requestWorkerStop();
    }

    /** Treats an active Worker-hosted world like vanilla singleplayer in menu navigation. */
    public static boolean isLocalSession(Minecraft minecraft) {
        return minecraft != null && (minecraft.isLocalServer() || hasActiveWorker());
    }

    /** Applies changed video settings to an active Worker-hosted singleplayer server. */
    public static void syncDistances(Minecraft minecraft) {
        if (minecraft == null || minecraft.options == null) {
            return;
        }
        postWorkerDistances(
                minecraft.options.renderDistance().get(),
                minecraft.options.simulationDistance().get());
    }

    @JSBody(script = """
            return typeof Worker === 'function' && typeof MessageChannel === 'function';
            """)
    private static native boolean isSupported();

    @JSBody(script = """
            try {
              const profile=String(globalThis.__gaiusProfileId || '').trim();
              const world=Number(globalThis.__gaiusWorldVersion);
              const schema=Number(globalThis.__gaiusStorageSchema);
              const database=String(globalThis.__gaiusStorageDatabaseName || '').trim();
              const prefix=String(globalThis.__gaiusStoragePrefix || '');
              const opfs=String(globalThis.__gaiusStorageOpfsDirectory || '').trim();
              return (profile==='1.21.11' && world===4671 && schema===2
                && database==='gaius-fs-v2-1.21.11'
                && prefix==='gaius.fs.v2:1.21.11:'
                && opfs==='regions-v2-1.21.11') ||
                (profile==='26.2' && world===4903 && schema===2
                && database==='gaius-fs-v2-26.2'
                && prefix==='gaius.fs.v2:26.2:'
                && opfs==='regions-v2-26.2');
            } catch (e) {
              return false;
            }
            """)
    private static native boolean storageConfigurationValid();

    @JSBody(params = {"sessionId"}, script = """
            const workers = globalThis.__gaiusSingleplayerWorkers;
            const worker = workers && typeof workers.get === 'function'
              ? workers.get(String(sessionId))
              : null;
            const generation = worker && worker.__gaiusLaunchGeneration
              ? String(worker.__gaiusLaunchGeneration)
              : '';
            return /^[1-9][0-9]*$/.test(generation) ? generation : '';
            """)
    private static native String localWorkerGeneration(String sessionId);

    @JSBody(params = {"sessionId", "launchGeneration"}, script = """
            const workers = globalThis.__gaiusSingleplayerWorkers;
            const key = String(sessionId || '');
            const expectedGeneration = String(launchGeneration || '');
            const worker = workers && typeof workers.get === 'function'
              ? workers.get(key)
              : null;
            if (!key || !/^[1-9][0-9]*$/.test(expectedGeneration) ||
                !worker || worker.__gaiusTerminal) return -1;
            if (String(worker.__gaiusLaunchGeneration || '') !== expectedGeneration) {
              return -2;
            }
            if (worker.__gaiusClientAttached) return 1;
            const ports = globalThis.__gaiusLocalServerPorts ||
              (globalThis.__gaiusLocalServerPorts = new Map());
            const ownedPort = worker.__gaiusClientPort || null;
            if (ownedPort && ports.get(key) !== ownedPort) {
              ports.set(key, ownedPort);
            }
            const mappedPort = ports.get(key);
            return worker.__gaiusServerReady && mappedPort &&
              String(mappedPort.__gaiusLaunchGeneration || '') === expectedGeneration
              ? 1
              : 0;
            """)
    private static native int localWorkerState(String sessionId, String launchGeneration);

    @JSBody(params = {"sessionId", "launchGeneration", "detail"}, script = """
            const key = String(sessionId || '');
            const expectedGeneration = String(launchGeneration || '');
            const workers = globalThis.__gaiusSingleplayerWorkers;
            const worker = workers && typeof workers.get === 'function'
              ? workers.get(key)
              : null;
            if (!key || !expectedGeneration || !worker ||
                String(worker.__gaiusLaunchGeneration || '') !== expectedGeneration) {
              return;
            }
            const events = globalThis.__gaiusMinecraftEvents ||
              (globalThis.__gaiusMinecraftEvents = []);
            events.push({
              event: 'singleplayer:client-attach-failed',
              detail: key + ': ' + String(detail),
              at: Date.now()
            });
            if (events.length > 500) events.splice(0, events.length - 500);
            """)
    private static native void reportAttachFailure(
            String sessionId,
            String launchGeneration,
            String detail);

    @JSBody(params = {"worldId", "newWorld", "renderDistance", "simulationDistance"}, script = """
            let sessionId = '';
            let launchGeneration = '';
            let channel = null;
            let ports = null;
            let workers = null;
            let worker = null;
            let port2Transferred = false;
            const closePort = function(port) {
              if (!port) return;
              try { port.close(); } catch (ignored) {}
            };
            const bindPortGeneration = function(port) {
              if (!port || !/^[1-9][0-9]*$/.test(launchGeneration)) return false;
              try { port.__gaiusLaunchGeneration = launchGeneration; } catch (ignored) {}
              return String(port.__gaiusLaunchGeneration || '') === launchGeneration;
            };
            const handoffMatches = function(expectedSession, expectedGeneration) {
              const handoff = globalThis.__gaiusSingleplayerHandoff;
              if (handoff && typeof handoff === 'object') {
                return String(handoff.sessionId || '') === String(expectedSession || '') &&
                  String(handoff.generation || '') === String(expectedGeneration || '');
              }
              return String(handoff || '') === String(expectedSession || '') &&
                String(globalThis.__gaiusSingleplayerHandoffGeneration || '') ===
                  String(expectedGeneration || '');
            };
            const ownsLaunch = function() {
              return workers && sessionId && worker && workers.get(sessionId) === worker &&
                String(worker.__gaiusLaunchGeneration || '') === launchGeneration;
            };
            const clearHandoffLease = function() {
              if (!ownsLaunch() || !sessionId || !launchGeneration ||
                  !handoffMatches(sessionId, launchGeneration)) {
                return;
              }
              globalThis.__gaiusSingleplayerHandoff = '';
              globalThis.__gaiusSingleplayerHandoffGeneration = '';
            };
            const rollbackLaunch = function(detail) {
              clearHandoffLease();
              if (worker) {
                worker.__gaiusTerminal = true;
                if (worker.__gaiusHandoffTimeout) {
                  clearTimeout(worker.__gaiusHandoffTimeout);
                  worker.__gaiusHandoffTimeout = 0;
                }
                if (worker.__gaiusStopTimeout) {
                  clearTimeout(worker.__gaiusStopTimeout);
                  worker.__gaiusStopTimeout = 0;
                }
                if (worker.__gaiusTelemetryTimer) {
                  clearInterval(worker.__gaiusTelemetryTimer);
                  worker.__gaiusTelemetryTimer = 0;
                }
                try { worker.terminate(); } catch (ignored) {}
              }
              if (ports && sessionId && channel && (!worker || ownsLaunch()) &&
                  ports.get(sessionId) === channel.port1) {
                ports.delete(sessionId);
              }
              if (workers && sessionId && worker && workers.get(sessionId) === worker &&
                  String(worker.__gaiusLaunchGeneration || '') === launchGeneration) {
                workers.delete(sessionId);
              }
              if (channel) {
                closePort(channel.port1);
                if (!port2Transferred) closePort(channel.port2);
              }
              const bridge = globalThis.__gaiusNettyBridge;
              if (workers && sessionId && worker && workers.get(sessionId) === worker &&
                  String(worker.__gaiusLaunchGeneration || '') === launchGeneration &&
                  bridge && typeof bridge.failLocalSession === 'function') {
                bridge.failLocalSession(sessionId, detail, launchGeneration);
              }
            };
            try {
              const profileId = String(globalThis.__gaiusProfileId || '').trim();
              const worldVersion = Number(globalThis.__gaiusWorldVersion);
              const storageSchema = Number(globalThis.__gaiusStorageSchema);
              const storageDatabaseName = String(
                globalThis.__gaiusStorageDatabaseName || ''
              ).trim();
              const storagePrefix = String(globalThis.__gaiusStoragePrefix || '');
              const storageOpfsDirectory = String(
                globalThis.__gaiusStorageOpfsDirectory || ''
              ).trim();
              const storageMatchesProfile =
                (profileId === '1.21.11' && worldVersion === 4671 &&
                  storageSchema === 2 &&
                  storageDatabaseName === 'gaius-fs-v2-1.21.11' &&
                  storagePrefix === 'gaius.fs.v2:1.21.11:' &&
                  storageOpfsDirectory === 'regions-v2-1.21.11') ||
                (profileId === '26.2' && worldVersion === 4903 &&
                  storageSchema === 2 &&
                  storageDatabaseName === 'gaius-fs-v2-26.2' &&
                  storagePrefix === 'gaius.fs.v2:26.2:' &&
                  storageOpfsDirectory === 'regions-v2-26.2');
              if (!storageMatchesProfile) {
                throw new Error('Singleplayer storage configuration does not match profile');
              }
              const bytes = new Uint8Array(16);
              crypto.getRandomValues(bytes);
              for (let i = 0; i < bytes.length; i++) {
                sessionId += bytes[i].toString(16).padStart(2, '0');
              }
              const previousGeneration = Number(
                globalThis.__gaiusSingleplayerLaunchGeneration
              );
              const nextGeneration = Number.isSafeInteger(previousGeneration) &&
                previousGeneration >= 0 && previousGeneration < Number.MAX_SAFE_INTEGER
                ? previousGeneration + 1
                : 1;
              launchGeneration = String(nextGeneration);
              globalThis.__gaiusSingleplayerLaunchGeneration = nextGeneration;
              if (!/^[1-9][0-9]*$/.test(launchGeneration)) {
                throw new Error('Singleplayer launch generation is invalid');
              }

              channel = new MessageChannel();
              if (!bindPortGeneration(channel.port1) || !bindPortGeneration(channel.port2)) {
                throw new Error('Singleplayer MessagePort generation binding failed');
              }
              ports = globalThis.__gaiusLocalServerPorts ||
                (globalThis.__gaiusLocalServerPorts = new Map());
              ports.set(sessionId, channel.port1);

              workers = globalThis.__gaiusSingleplayerWorkers ||
                (globalThis.__gaiusSingleplayerWorkers = new Map());
              const previousWorkers = Array.from(workers.entries());

              const buildToken = globalThis.__gaiusBootTimings &&
                globalThis.__gaiusBootTimings.buildToken
                ? String(globalThis.__gaiusBootTimings.buildToken)
                : 'dev';
              const workerUrl = globalThis.__gaiusSingleplayerWorkerUrl || new URL(
                'singleplayer-server-worker.js?v=' + encodeURIComponent(buildToken),
                location.href
              );
              worker = new Worker(workerUrl, {name: 'Gaius Integrated Server'});
              worker.__gaiusLaunchGeneration = launchGeneration;
              worker.__gaiusHandoffPending = true;
              worker.__gaiusClientAttached = false;
              worker.__gaiusClientPort = channel.port1;
              worker.__gaiusRuntimeReady = false;
              worker.__gaiusServerReady = false;
              worker.__gaiusStopRequested = false;
              workers.set(sessionId, worker);
              const ownsSession = function() {
                return workers.get(sessionId) === worker &&
                  String(worker.__gaiusLaunchGeneration || '') === launchGeneration;
              };
              const ownsPendingPort = function() {
                return ownsSession() && ports.get(sessionId) === worker.__gaiusClientPort;
              };
              const copyScalarTelemetry = function(value) {
                const snapshot = {};
                if (!value || typeof value !== 'object') return snapshot;
                let copied = 0;
                const keys = Object.keys(value);
                for (let keyIndex = 0; keyIndex < keys.length; keyIndex++) {
                  if (copied >= 64) break;
                  const key = keys[keyIndex];
                  const current = value[key];
                  if (typeof current === 'number') {
                    if (Number.isFinite(current)) {
                      snapshot[key] = current;
                      copied++;
                    }
                  } else if (typeof current === 'boolean' ||
                      typeof current === 'string' || current === null) {
                    snapshot[key] = current;
                    copied++;
                  }
                }
                return snapshot;
              };
              worker.__gaiusTelemetryPending = new Map();
              worker.__gaiusTelemetrySequence = 0;
              worker.__gaiusTelemetrySent = 0;
              worker.__gaiusTelemetryReceived = 0;
              worker.__gaiusTelemetryMissed = 0;
              worker.__gaiusTelemetryErrors = 0;
              worker.__gaiusTelemetryTotalRtt = 0;
              worker.__gaiusTelemetryMaxRtt = 0;
              worker.__gaiusTelemetryLastRtt = 0;
              worker.__gaiusTelemetryMaxPageToWorker = 0;
              worker.__gaiusTelemetryMaxWorkerToPage = 0;
              worker.__gaiusTelemetryRttHistogram = new Uint32Array(512);
              worker.__gaiusTelemetryRttSamples = 0;
              worker.__gaiusTelemetrySchemaVersion = 2;
              worker.__gaiusTelemetryMeasurementId =
                typeof globalThis.__gaiusBenchmarkMeasurementId === 'string'
                  ? globalThis.__gaiusBenchmarkMeasurementId
                  : '';
              worker.__gaiusTelemetryResetAt = Date.now();
              worker.__gaiusTelemetryWorkerResetAt = 0;
              worker.__gaiusTelemetryMeasurementMismatches = 0;
              worker.__gaiusTelemetryLongestHeartbeatGap = 0;
              worker.__gaiusTelemetryLongestHeartbeatDelay = 0;
              worker.__gaiusTelemetryInterval = 1000;
              worker.__gaiusTelemetryLastPingAt = 0;
              worker.__gaiusTelemetryLastPongAt = 0;
              worker.__gaiusTelemetryChunkPriority = {};
              worker.__gaiusTelemetryNetwork = {};
              worker.__gaiusTelemetryWorldgen = {};
              worker.__gaiusTelemetryStorage = {};
              const telemetryPercentile = function(histogram, count, fraction) {
                if (!histogram || count <= 0) return 0;
                const target = Math.max(1, Math.ceil(count * fraction));
                let seen = 0;
                for (let index = 0; index < histogram.length; index++) {
                  seen += histogram[index];
                  if (seen >= target) return index + 1;
                }
                return histogram.length;
              };
              const publishWorkerTelemetry = function() {
                if (!ownsSession()) return;
                const state = globalThis.__gaiusWorkerMessageTelemetry ||
                  (globalThis.__gaiusWorkerMessageTelemetry = {});
                state.schemaVersion = worker.__gaiusTelemetrySchemaVersion;
                state.sessionId = sessionId;
                state.measurementId = worker.__gaiusTelemetryMeasurementId;
                state.resetAt = worker.__gaiusTelemetryResetAt;
                state.workerResetAt = worker.__gaiusTelemetryWorkerResetAt;
                state.measurementMismatches = worker.__gaiusTelemetryMeasurementMismatches;
                state.sent = worker.__gaiusTelemetrySent;
                state.received = worker.__gaiusTelemetryReceived;
                state.rttSampleCount = worker.__gaiusTelemetryRttSamples;
                state.missed = worker.__gaiusTelemetryMissed;
                state.errors = worker.__gaiusTelemetryErrors;
                state.pending = worker.__gaiusTelemetryPending.size;
                state.lastRttMillis = worker.__gaiusTelemetryLastRtt;
                state.averageRttMillis = worker.__gaiusTelemetryReceived > 0
                  ? worker.__gaiusTelemetryTotalRtt / worker.__gaiusTelemetryReceived
                  : 0;
                state.maxRttMillis = worker.__gaiusTelemetryMaxRtt;
                state.p95RttMillis = telemetryPercentile(
                  worker.__gaiusTelemetryRttHistogram,
                  worker.__gaiusTelemetryRttSamples,
                  0.95
                );
                state.p99RttMillis = telemetryPercentile(
                  worker.__gaiusTelemetryRttHistogram,
                  worker.__gaiusTelemetryRttSamples,
                  0.99
                );
                state.maxPageToWorkerMillis = worker.__gaiusTelemetryMaxPageToWorker;
                state.maxWorkerToPageMillis = worker.__gaiusTelemetryMaxWorkerToPage;
                state.configuredIntervalMillis = worker.__gaiusTelemetryInterval;
                state.longestHeartbeatGapMillis = Math.max(
                  worker.__gaiusTelemetryLongestHeartbeatGap,
                  worker.__gaiusTelemetryLastPongAt > 0
                    ? Date.now() - worker.__gaiusTelemetryLastPongAt
                    : 0
                );
                state.longestHeartbeatDelayMillis = Math.max(
                  worker.__gaiusTelemetryLongestHeartbeatDelay,
                  worker.__gaiusTelemetryLastPongAt > 0
                    ? Math.max(0, Date.now() - worker.__gaiusTelemetryLastPongAt
                      - worker.__gaiusTelemetryInterval)
                    : 0
                );
                state.lastPingAt = worker.__gaiusTelemetryLastPingAt;
                state.lastPongAt = worker.__gaiusTelemetryLastPongAt;
                state.chunkPriority = copyScalarTelemetry(
                  worker.__gaiusTelemetryChunkPriority
                );
                state.network = copyScalarTelemetry(worker.__gaiusTelemetryNetwork);
                state.worldgen = copyScalarTelemetry(worker.__gaiusTelemetryWorldgen);
                state.storage = copyScalarTelemetry(worker.__gaiusTelemetryStorage);
                state.updatedAt = Date.now();
              };
              const resetWorkerTelemetry = function(measurementId) {
                worker.__gaiusTelemetryPending.clear();
                worker.__gaiusTelemetrySent = 0;
                worker.__gaiusTelemetryReceived = 0;
                worker.__gaiusTelemetryMissed = 0;
                worker.__gaiusTelemetryErrors = 0;
                worker.__gaiusTelemetryTotalRtt = 0;
                worker.__gaiusTelemetryMaxRtt = 0;
                worker.__gaiusTelemetryLastRtt = 0;
                worker.__gaiusTelemetryMaxPageToWorker = 0;
                worker.__gaiusTelemetryMaxWorkerToPage = 0;
                worker.__gaiusTelemetryRttHistogram.fill(0);
                worker.__gaiusTelemetryRttSamples = 0;
                worker.__gaiusTelemetryMeasurementId = String(measurementId || '');
                worker.__gaiusTelemetryResetAt = Date.now();
                worker.__gaiusTelemetryWorkerResetAt = 0;
                worker.__gaiusTelemetryMeasurementMismatches = 0;
                worker.__gaiusTelemetryLongestHeartbeatGap = 0;
                worker.__gaiusTelemetryLongestHeartbeatDelay = 0;
                worker.__gaiusTelemetryLastPingAt = 0;
                worker.__gaiusTelemetryLastPongAt = Date.now();
                worker.__gaiusTelemetryChunkPriority = {};
                worker.__gaiusTelemetryNetwork = {};
                worker.__gaiusTelemetryWorldgen = {};
                worker.__gaiusTelemetryStorage = {};
                publishWorkerTelemetry();
              };
              const stopWorkerTelemetry = function() {
                if (worker.__gaiusTelemetryTimer) {
                  clearInterval(worker.__gaiusTelemetryTimer);
                  worker.__gaiusTelemetryTimer = 0;
                }
                worker.__gaiusTelemetryPending.clear();
                publishWorkerTelemetry();
              };
              const sendWorkerTelemetryPing = function() {
                if (!ownsSession() || worker.__gaiusTerminal) {
                  stopWorkerTelemetry();
                  return;
                }
                const now = Date.now();
                worker.__gaiusTelemetryPending.forEach(function(sentAt, sequence) {
                  if (now - sentAt >= 5000) {
                    worker.__gaiusTelemetryPending.delete(sequence);
                    worker.__gaiusTelemetryMissed++;
                  }
                });
                const sequence = ++worker.__gaiusTelemetrySequence;
                worker.__gaiusTelemetryPending.set(sequence, now);
                worker.__gaiusTelemetrySent++;
                worker.__gaiusTelemetryLastPingAt = now;
                try {
                  worker.postMessage({
                    type: 'telemetry-ping',
                    sessionId: sessionId,
                    sequence: sequence,
                    sentAtEpoch: now,
                    measurementId: worker.__gaiusTelemetryMeasurementId
                  });
                } catch (error) {
                  worker.__gaiusTelemetryPending.delete(sequence);
                  worker.__gaiusTelemetryErrors++;
                }
                publishWorkerTelemetry();
              };
              const startWorkerTelemetry = function() {
                if (worker.__gaiusTelemetryTimer || worker.__gaiusTerminal) return;
                sendWorkerTelemetryPing();
                worker.__gaiusTelemetryTimer = setInterval(
                  sendWorkerTelemetryPing,
                  worker.__gaiusTelemetryInterval
                );
              };
              const setWorkerTelemetryInterval = function(intervalMillis) {
                if (worker.__gaiusTerminal) return;
                const interval = Math.max(50, Math.min(5000, Number(intervalMillis) || 1000));
                if (worker.__gaiusTelemetryTimer) {
                  clearInterval(worker.__gaiusTelemetryTimer);
                }
                worker.__gaiusTelemetryInterval = interval;
                worker.__gaiusTelemetryLastPongAt = Date.now();
                sendWorkerTelemetryPing();
                worker.__gaiusTelemetryTimer = setInterval(sendWorkerTelemetryPing, interval);
              };
              const receiveWorkerTelemetryPong = function(message) {
                if (!ownsSession() || String(message.sessionId || '') !== sessionId) return;
                if (typeof message.measurementId !== 'string' ||
                    message.measurementId !== worker.__gaiusTelemetryMeasurementId) {
                  worker.__gaiusTelemetryMeasurementMismatches++;
                  publishWorkerTelemetry();
                  return;
                }
                const sequence = Number(message.sequence) || 0;
                const sentAt = worker.__gaiusTelemetryPending.get(sequence);
                if (!sentAt) return;
                worker.__gaiusTelemetryPending.delete(sequence);
                const now = Date.now();
                const rtt = Math.max(0, now - sentAt);
                const pageToWorker = Math.max(
                  0,
                  Number(message.receivedAtEpoch || sentAt) - sentAt
                );
                const workerToPage = Math.max(
                  0,
                  now - Number(message.sentAtEpoch || now)
                );
                worker.__gaiusTelemetryReceived++;
                worker.__gaiusTelemetryTotalRtt += rtt;
                worker.__gaiusTelemetryLastRtt = rtt;
                worker.__gaiusTelemetryMaxRtt = Math.max(worker.__gaiusTelemetryMaxRtt, rtt);
                const rttBucket = Math.min(
                  worker.__gaiusTelemetryRttHistogram.length - 1,
                  Math.floor(rtt)
                );
                if (worker.__gaiusTelemetryRttHistogram[rttBucket] < 0xffffffff) {
                  worker.__gaiusTelemetryRttHistogram[rttBucket]++;
                }
                worker.__gaiusTelemetryRttSamples++;
                worker.__gaiusTelemetryMaxPageToWorker = Math.max(
                  worker.__gaiusTelemetryMaxPageToWorker,
                  pageToWorker
                );
                worker.__gaiusTelemetryMaxWorkerToPage = Math.max(
                  worker.__gaiusTelemetryMaxWorkerToPage,
                  workerToPage
                );
                if (worker.__gaiusTelemetryLastPongAt > 0) {
                  const heartbeatGap = now - worker.__gaiusTelemetryLastPongAt;
                  worker.__gaiusTelemetryLongestHeartbeatGap = Math.max(
                    worker.__gaiusTelemetryLongestHeartbeatGap,
                    heartbeatGap
                  );
                  worker.__gaiusTelemetryLongestHeartbeatDelay = Math.max(
                    worker.__gaiusTelemetryLongestHeartbeatDelay,
                    Math.max(0, heartbeatGap - worker.__gaiusTelemetryInterval)
                  );
                }
                worker.__gaiusTelemetryLastPongAt = now;
                worker.__gaiusTelemetryWorkerResetAt = Number(message.workerResetAt) || 0;
                worker.__gaiusTelemetryChunkPriority = copyScalarTelemetry(
                  message.chunkPriority
                );
                worker.__gaiusTelemetryNetwork = copyScalarTelemetry(message.network);
                worker.__gaiusTelemetryWorldgen = copyScalarTelemetry(message.worldgen);
                worker.__gaiusTelemetryStorage = copyScalarTelemetry(message.storage);
                publishWorkerTelemetry();
              };
              worker.__gaiusStopTelemetry = stopWorkerTelemetry;
              worker.__gaiusResetTelemetry = resetWorkerTelemetry;
              worker.__gaiusSetTelemetryInterval = setWorkerTelemetryInterval;
              const failLocalSession = function(detail) {
                const bridge = globalThis.__gaiusNettyBridge;
                if (bridge && typeof bridge.failLocalSession === 'function') {
                  bridge.failLocalSession(sessionId, detail, launchGeneration);
                }
              };
              const clearWorkerStopTimeout = function() {
                if (!worker.__gaiusStopTimeout) return;
                clearTimeout(worker.__gaiusStopTimeout);
                worker.__gaiusStopTimeout = 0;
              };
              const terminateFailedWorker = function(detail) {
                if (worker.__gaiusTerminal) return;
                clearHandoffLease();
                worker.__gaiusTerminal = true;
                stopWorkerTelemetry();
                clearWorkerStopTimeout();
                if (worker.__gaiusHandoffTimeout) {
                  clearTimeout(worker.__gaiusHandoffTimeout);
                  worker.__gaiusHandoffTimeout = 0;
                }
                if (ownsSession()) failLocalSession(detail);
                if (ownsPendingPort()) {
                  try { worker.__gaiusClientPort.close(); } catch (ignored) {}
                  ports.delete(sessionId);
                } else {
                  try { if (worker.__gaiusClientPort) worker.__gaiusClientPort.close(); }
                  catch (ignored) {}
                }
                try { worker.terminate(); } catch (ignored) {}
                if (ownsSession()) workers.delete(sessionId);
              };
              const armWorkerStopTimeout = function(delayMillis, detail) {
                clearWorkerStopTimeout();
                worker.__gaiusStopTimeout = setTimeout(function() {
                  terminateFailedWorker(detail);
                }, Math.max(1000, Number(delayMillis) || 35000));
              };
              worker.__gaiusArmStopTimeout = armWorkerStopTimeout;
              worker.onmessage = function(event) {
                const message = event.data;
                if (message && message.type === 'telemetry-pong') {
                  receiveWorkerTelemetryPong(message);
                  return;
                }
                const events = globalThis.__gaiusMinecraftEvents ||
                  (globalThis.__gaiusMinecraftEvents = []);
                events.push({event: 'singleplayer:worker', detail: message, at: Date.now()});
                if (events.length > 500) events.splice(0, events.length - 500);
                if (message && message.type === 'runtime-ready') {
                  worker.__gaiusRuntimeReady = true;
                  startWorkerTelemetry();
                } else if (message && message.type === 'server-listener-ready') {
                  worker.__gaiusServerReady = true;
                } else if (message && message.type === 'storage-flushing') {
                  worker.__gaiusStorageFlushing = true;
                  if (worker.__gaiusStopRequested) {
                    armWorkerStopTimeout(
                      15000,
                      'Integrated server storage flush did not finish within 15 seconds'
                    );
                  }
                } else if (message && message.type === 'stopped') {
                  clearHandoffLease();
                  worker.__gaiusStopped = true;
                  worker.__gaiusTerminal = true;
                  stopWorkerTelemetry();
                  clearWorkerStopTimeout();
                  if (worker.__gaiusHandoffTimeout) {
                    clearTimeout(worker.__gaiusHandoffTimeout);
                    worker.__gaiusHandoffTimeout = 0;
                  }
                  if (ownsSession()) {
                    const storageRefresh = refreshPersistentFiles();
                    worker.__gaiusStorageRefresh = storageRefresh;
                    storageRefresh.catch(function(error) {
                      events.push({
                        event: 'singleplayer:storage-refresh-error',
                        detail: String(error && (error.stack || error.message) || error),
                        at: Date.now()
                      });
                    });
                  }
                  if (ownsPendingPort()) {
                    try { worker.__gaiusClientPort.close(); } catch (ignored) {}
                    ports.delete(sessionId);
                  } else {
                    try { if (worker.__gaiusClientPort) worker.__gaiusClientPort.close(); }
                    catch (ignored) {}
                  }
                  try { worker.terminate(); } catch (ignored) {}
                  if (ownsSession()) workers.delete(sessionId);
                } else if (message &&
                    (message.type === 'crash' || message.type === 'bootstrap-crash')) {
                  terminateFailedWorker(String(message.detail || message.type));
                }
              };
              worker.__gaiusHandoffTimeout = setTimeout(function() {
                if (worker.__gaiusTerminal || worker.__gaiusClientAttached) return;
                terminateFailedWorker(
                  'Integrated server client did not attach within 60 seconds'
                );
              }, 60000);
              worker.onerror = function(event) {
                const detail = event && event.message ? event.message : 'worker error';
                const events = globalThis.__gaiusMinecraftEvents ||
                  (globalThis.__gaiusMinecraftEvents = []);
                events.push({event: 'singleplayer:worker-error', detail: detail, at: Date.now()});
                terminateFailedWorker(detail);
              };

              const init = {
                type: 'start',
                sessionId: sessionId,
                launchGeneration: launchGeneration,
                worldId: String(worldId),
                newWorld: !!newWorld,
                profileId: globalThis.__gaiusProfileId || null,
                worldVersion: globalThis.__gaiusWorldVersion || null,
                storageSchema: globalThis.__gaiusStorageSchema || null,
                storageDatabaseName: globalThis.__gaiusStorageDatabaseName || null,
                storagePrefix: globalThis.__gaiusStoragePrefix || null,
                storageOpfsDirectory: globalThis.__gaiusStorageOpfsDirectory || null,
                bridgeUrl: globalThis.__gaiusBridgeUrl || null,
                bridgeToken: globalThis.__gaiusBridgeToken || null,
                renderDistance: Math.max(2, Math.min(32, Number(renderDistance) || 6)),
                simulationDistance: Math.max(2, Math.min(32, Number(simulationDistance) || 4)),
                worldgenSliceMillis: 8,
                distanceRampIntervalMillis: 250,
                serverScriptUrl: globalThis.__gaiusSingleplayerServerUrl || null,
                serverScriptGzipUrl: globalThis.__gaiusSingleplayerServerGzipUrl || null,
                port: channel.port2
              };
              worker.__gaiusDistances = init.renderDistance + ':' + init.simulationDistance;
              const start = function() {
                if (worker.__gaiusStopRequested || !ownsSession()) {
                  try { channel.port2.close(); } catch (ignored) {}
                  terminateFailedWorker('Singleplayer launch was cancelled');
                  return;
                }
                if (!ownsPendingPort()) {
                  ports.set(sessionId, worker.__gaiusClientPort);
                }
                try {
                  worker.postMessage(init, [channel.port2]);
                  port2Transferred = true;
                } catch (error) {
                  closePort(channel.port2);
                  terminateFailedWorker(
                    String(error && (error.stack || error.message) || error)
                  );
                }
              };
              const stopPreviousWorker = function(entry) {
                const previousSessionId = entry[0];
                const previousWorker = entry[1];
                const previousGeneration = String(
                  previousWorker && previousWorker.__gaiusLaunchGeneration || ''
                );
                const ownsPreviousWorker = function() {
                  return workers.get(previousSessionId) === previousWorker &&
                    String(previousWorker && previousWorker.__gaiusLaunchGeneration || '') ===
                      previousGeneration;
                };
                if (!previousWorker || previousWorker.__gaiusTerminal) {
                  return Promise.resolve();
                }
                const waitForStorageRefresh = function() {
                  const refresh = previousWorker.__gaiusStorageRefresh;
                  return refresh && typeof refresh.then === 'function'
                    ? refresh.then(function() {}, function() {})
                    : Promise.resolve();
                };
                if (previousWorker.__gaiusStopped) {
                  return waitForStorageRefresh();
                }
                return new Promise(function(resolve) {
                  let settled = false;
                  let timeout = 0;
                  const terminatePrevious = function(detail) {
                    previousWorker.__gaiusTerminal = true;
                    if (previousWorker.__gaiusHandoffTimeout) {
                      clearTimeout(previousWorker.__gaiusHandoffTimeout);
                      previousWorker.__gaiusHandoffTimeout = 0;
                    }
                    if (previousWorker.__gaiusStopTimeout) {
                      clearTimeout(previousWorker.__gaiusStopTimeout);
                      previousWorker.__gaiusStopTimeout = 0;
                    }
                    if (typeof previousWorker.__gaiusStopTelemetry === 'function') {
                      previousWorker.__gaiusStopTelemetry();
                    }
                    const bridge = globalThis.__gaiusNettyBridge;
                  if (ownsPreviousWorker() &&
                      bridge && typeof bridge.failLocalSession === 'function') {
                      bridge.failLocalSession(previousSessionId, detail, previousGeneration);
                  }
                    const pendingPort = ports.get(previousSessionId);
                    if (ownsPreviousWorker() && pendingPort &&
                        pendingPort === previousWorker.__gaiusClientPort) {
                      try { pendingPort.close(); } catch (ignored) {}
                      ports.delete(previousSessionId);
                    } else {
                      closePort(previousWorker.__gaiusClientPort);
                    }
                    try { previousWorker.terminate(); } catch (ignored) {}
                    if (ownsPreviousWorker()) {
                      workers.delete(previousSessionId);
                    }
                  };
                  const armTimeout = function(delayMillis, detail) {
                    if (timeout) clearTimeout(timeout);
                    timeout = setTimeout(function() {
                      terminatePrevious(detail);
                      finish();
                    }, delayMillis);
                  };
                  const finish = function() {
                    if (settled) return;
                    settled = true;
                    if (timeout) clearTimeout(timeout);
                    previousWorker.removeEventListener('message', onMessage);
                    waitForStorageRefresh().then(resolve, resolve);
                  };
                  const onMessage = function(event) {
                    if (event.data && event.data.type === 'stopped') {
                      Promise.resolve().then(finish);
                    } else if (event.data && event.data.type === 'storage-flushing') {
                      armTimeout(
                        15000,
                        'Previous integrated server storage flush timed out'
                      );
                    }
                  };
                  armTimeout(
                    35000,
                    'Previous integrated server did not stop within 35 seconds'
                  );
                  previousWorker.addEventListener('message', onMessage);
                  try {
                    previousWorker.__gaiusStopRequested = true;
                    previousWorker.postMessage({type: 'stop'});
                  } catch (error) {
                    terminatePrevious(String(error && (error.message || error) || error));
                    finish();
                  }
                });
              };
              const refreshPersistentFiles = function() {
                const storageDatabaseName = String(
                  globalThis.__gaiusStorageDatabaseName || ''
                ).trim();
                const storageSchema = Number(globalThis.__gaiusStorageSchema);
                const profileId = String(globalThis.__gaiusProfileId || '').trim();
                const worldVersion = Number(globalThis.__gaiusWorldVersion);
                const storagePrefix = String(globalThis.__gaiusStoragePrefix || '');
                const storageOpfsDirectory = String(
                  globalThis.__gaiusStorageOpfsDirectory || ''
                ).trim();
                const storageMatchesProfile =
                  (profileId === '1.21.11' && worldVersion === 4671 &&
                    storageSchema === 2 &&
                    storageDatabaseName === 'gaius-fs-v2-1.21.11' &&
                    storagePrefix === 'gaius.fs.v2:1.21.11:' &&
                    storageOpfsDirectory === 'regions-v2-1.21.11') ||
                  (profileId === '26.2' && worldVersion === 4903 &&
                    storageSchema === 2 &&
                    storageDatabaseName === 'gaius-fs-v2-26.2' &&
                    storagePrefix === 'gaius.fs.v2:26.2:' &&
                    storageOpfsDirectory === 'regions-v2-26.2');
                if (!storageMatchesProfile) {
                  return Promise.reject(new Error(
                    'IndexedDB refresh storage configuration does not match profile'
                  ));
                }
                if (typeof indexedDB === 'undefined') return Promise.resolve();
                const worldPrefix = '/gaius/saves/';
                const isWorldMetadataPath = function(path) {
                  if (typeof path !== 'string' || !path.startsWith(worldPrefix)) {
                    return false;
                  }
                  const relative = path.substring(worldPrefix.length);
                  const separator = relative.indexOf('/');
                  if (separator <= 0) return false;
                  const file = relative.substring(separator + 1);
                  return file === 'level.dat' || file === 'level.dat_old' ||
                    file === 'icon.png' ||
                    file === 'data/minecraft/world_gen_settings.dat';
                };
                return new Promise(function(resolve, reject) {
                  const request = indexedDB.open(storageDatabaseName, storageSchema);
                  request.onsuccess = function() { resolve(request.result); };
                  request.onerror = function() {
                    reject(request.error || new Error('IndexedDB refresh open failed'));
                  };
                  request.onblocked = function() {
                    reject(new Error('IndexedDB metadata refresh was blocked'));
                  };
                }).then(function(database) {
                  const listMetadataPaths = new Promise(function(resolve, reject) {
                    const paths = [];
                    const transaction = database.transaction('files', 'readonly');
                    const store = transaction.objectStore('files');
                    const range = typeof IDBKeyRange !== 'undefined'
                      ? IDBKeyRange.bound(worldPrefix, worldPrefix + '\uffff')
                      : undefined;
                    const request = typeof store.openKeyCursor === 'function'
                      ? store.openKeyCursor(range)
                      : store.openCursor(range);
                    request.onsuccess = function() {
                      const cursor = request.result;
                      if (!cursor) {
                        resolve(paths);
                        return;
                      }
                      const path = cursor.key !== undefined
                        ? cursor.key
                        : cursor.value && cursor.value.path;
                      if (isWorldMetadataPath(path)) paths.push(path);
                      cursor.continue();
                    };
                    request.onerror = function() {
                      reject(request.error || new Error('IndexedDB metadata cursor failed'));
                    };
                  });
                  const readMetadata = function(paths) {
                    if (paths.length === 0) return Promise.resolve([]);
                    return new Promise(function(resolve, reject) {
                      const values = [];
                      const transaction = database.transaction('files', 'readonly');
                      const store = transaction.objectStore('files');
                      let failed = false;
                      for (let index = 0; index < paths.length; index++) {
                        const request = store.get(paths[index]);
                        request.onsuccess = function() {
                          if (request.result) values.push(request.result);
                        };
                        request.onerror = function() {
                          if (failed) return;
                          failed = true;
                          reject(request.error || new Error('IndexedDB metadata read failed'));
                        };
                      }
                      transaction.oncomplete = function() {
                        if (!failed) resolve(values);
                      };
                      transaction.onerror = function() {
                        if (!failed) reject(transaction.error ||
                          new Error('IndexedDB metadata transaction failed'));
                      };
                      transaction.onabort = transaction.onerror;
                    });
                  };
                  return listMetadataPaths.then(readMetadata).then(function(values) {
                    const files = globalThis.__gaiusPersistentFiles ||
                      (globalThis.__gaiusPersistentFiles = Object.create(null));
                    const paths = Object.keys(files);
                    for (let index = 0; index < paths.length; index++) {
                      if (isWorldMetadataPath(paths[index])) delete files[paths[index]];
                    }
                    for (let index = 0; index < values.length; index++) {
                      const entry = values[index];
                      if (entry && isWorldMetadataPath(entry.path)) {
                        files[entry.path] = entry.value;
                      }
                    }
                    database.close();
                  }, function(error) {
                    database.close();
                    throw error;
                  });
                });
              };
              Promise.all(previousWorkers.map(stopPreviousWorker)).then(function() {
                const flush = typeof globalThis.__gaiusFsFlush === 'function'
                  ? globalThis.__gaiusFsFlush()
                  : null;
                return flush && typeof flush.then === 'function' ? flush : null;
              }).then(start, start);
              return sessionId;
            } catch (error) {
              const detail = String(error && (error.stack || error.message) || error);
              rollbackLaunch(detail);
              const events = globalThis.__gaiusMinecraftEvents ||
                (globalThis.__gaiusMinecraftEvents = []);
              events.push({
                event: 'singleplayer:worker-launch-error',
                detail: detail,
                at: Date.now()
              });
              return null;
            }
            """)
    private static native String launchWorker(
            String worldId,
            boolean newWorld,
            int renderDistance,
            int simulationDistance);

    @JSBody(params = {"renderDistance", "simulationDistance"}, script = """
            const workers = globalThis.__gaiusSingleplayerWorkers;
            if (!workers || typeof workers.values !== 'function') return;
            const view = Math.max(2, Math.min(32, Number(renderDistance) || 6));
            const simulation = Math.max(2, Math.min(32, Number(simulationDistance) || 4));
            const key = view + ':' + simulation;
            workers.forEach(function(worker) {
              if (!worker || worker.__gaiusTerminal || worker.__gaiusDistances === key) return;
              worker.__gaiusDistances = key;
              try {
                worker.postMessage({
                  type: 'distances',
                  renderDistance: view,
                  simulationDistance: simulation
                });
              } catch (ignored) {}
            });
            """)
    private static native void postWorkerDistances(int renderDistance, int simulationDistance);

    @JSBody(params = {"sessionId", "launchGeneration"}, script = """
            const key = String(sessionId || '');
            const generation = String(launchGeneration || '');
            if (!key || !/^[1-9][0-9]*$/.test(generation)) return;
            globalThis.__gaiusSingleplayerHandoff = key;
            globalThis.__gaiusSingleplayerHandoffGeneration = generation;
            """)
    private static native void beginClientHandoff(String sessionId, String launchGeneration);

    @JSBody(params = {"sessionId", "launchGeneration"}, script = """
            const key = String(sessionId || '');
            const generation = String(launchGeneration || '');
            const workers = globalThis.__gaiusSingleplayerWorkers;
            const worker = workers && typeof workers.get === 'function'
              ? workers.get(key)
              : null;
            if (!key || !generation || !worker || worker.__gaiusTerminal ||
                String(worker.__gaiusLaunchGeneration || '') !== generation) {
              return;
            }
            const handoff = globalThis.__gaiusSingleplayerHandoff;
            const matches = handoff && typeof handoff === 'object'
              ? String(handoff.sessionId || '') === key &&
                String(handoff.generation || '') === generation
              : String(handoff || '') === key &&
                String(globalThis.__gaiusSingleplayerHandoffGeneration || '') === generation;
            if (matches) {
              globalThis.__gaiusSingleplayerHandoff = '';
              globalThis.__gaiusSingleplayerHandoffGeneration = '';
            }
            """)
    private static native void cancelClientHandoff(String sessionId, String launchGeneration);

    @JSBody(script = """
            const workers = globalThis.__gaiusSingleplayerWorkers;
            if (!workers || typeof workers.values !== 'function') return false;
            let active = false;
            workers.forEach(function(worker) {
              if (worker && !worker.__gaiusTerminal) active = true;
            });
            return active;
            """)
    private static native boolean hasActiveWorker();

    @JSBody(script = """
            const workers = globalThis.__gaiusSingleplayerWorkers;
            if (!workers || typeof workers.values !== 'function') return;
            const handoff = globalThis.__gaiusSingleplayerHandoff;
            const handoffSession = handoff && typeof handoff === 'object'
              ? String(handoff.sessionId || '')
              : String(handoff || '');
            const handoffGeneration = handoff && typeof handoff === 'object'
              ? String(handoff.generation || '')
              : String(globalThis.__gaiusSingleplayerHandoffGeneration || '');
            workers.forEach(function(worker, sessionId) {
              const workerGeneration = String(worker && worker.__gaiusLaunchGeneration || '');
              const ownsWorker = function() {
                return workers.get(sessionId) === worker &&
                  String(worker && worker.__gaiusLaunchGeneration || '') === workerGeneration;
              };
              if (handoffSession === String(sessionId) &&
                  handoffGeneration && handoffGeneration === workerGeneration &&
                  ownsWorker() &&
                  worker.__gaiusHandoffPending && !worker.__gaiusClientAttached) {
                const events = globalThis.__gaiusMinecraftEvents ||
                  (globalThis.__gaiusMinecraftEvents = []);
                events.push({
                  event: 'singleplayer:handoff-disconnect-ignored',
                  detail: String(sessionId),
                  at: Date.now()
                });
                if (events.length > 500) events.splice(0, events.length - 500);
                return;
              }
              if (!ownsWorker()) return;
              worker.__gaiusStopRequested = true;
              try {
                worker.postMessage({type: 'stop'});
              } catch (error) {
                const bridge = globalThis.__gaiusNettyBridge;
                if (ownsWorker() && bridge &&
                    typeof bridge.failLocalSession === 'function') {
                  bridge.failLocalSession(sessionId, String(error &&
                    (error.message || error) || error), workerGeneration);
                }
              }
              if (typeof worker.__gaiusArmStopTimeout === 'function') {
                worker.__gaiusArmStopTimeout(
                  35000,
                  'Integrated server did not stop within 35 seconds'
                );
                return;
              }
              if (worker.__gaiusStopTimeout) clearTimeout(worker.__gaiusStopTimeout);
              worker.__gaiusStopTimeout = setTimeout(function() {
                if (worker.__gaiusTerminal) return;
                worker.__gaiusTerminal = true;
                if (typeof worker.__gaiusStopTelemetry === 'function') {
                  worker.__gaiusStopTelemetry();
                }
                const bridge = globalThis.__gaiusNettyBridge;
                if (ownsWorker() &&
                    bridge && typeof bridge.failLocalSession === 'function') {
                  bridge.failLocalSession(
                    sessionId,
                    'Integrated server did not stop within 35 seconds',
                    workerGeneration
                  );
                }
                const ports = globalThis.__gaiusLocalServerPorts;
                const pendingPort = ports && typeof ports.get === 'function'
                  ? ports.get(sessionId)
                  : null;
                if (ownsWorker() && pendingPort &&
                    pendingPort === worker.__gaiusClientPort) {
                  try { pendingPort.close(); } catch (ignored) {}
                  ports.delete(sessionId);
                } else {
                  try { if (worker.__gaiusClientPort) worker.__gaiusClientPort.close(); }
                  catch (ignored) {}
                }
                try { worker.terminate(); } catch (ignored) {}
                if (ownsWorker()) workers.delete(sessionId);
              }, 35000);
            });
            """)
    private static native void requestWorkerStop();

    @JSBody(params = {"sessionId", "launchGeneration"}, script = """
            const key = String(sessionId || '');
            const expectedGeneration = String(launchGeneration || '');
            if (!key || !expectedGeneration) return;
            const workers = globalThis.__gaiusSingleplayerWorkers;
            if (!workers || typeof workers.get !== 'function') return;
            const worker = workers.get(key);
            if (!worker || worker.__gaiusTerminal ||
                String(worker.__gaiusLaunchGeneration || '') !== expectedGeneration) return;
            const ownsWorker = function() {
              return workers.get(key) === worker &&
                String(worker.__gaiusLaunchGeneration || '') === expectedGeneration;
            };
            const handoff = globalThis.__gaiusSingleplayerHandoff;
            const handoffMatches = handoff && typeof handoff === 'object'
              ? String(handoff.sessionId || '') === key &&
                String(handoff.generation || '') === expectedGeneration
              : String(handoff || '') === key &&
                String(globalThis.__gaiusSingleplayerHandoffGeneration || '') ===
                  expectedGeneration;
            if (handoffMatches && ownsWorker()) {
              globalThis.__gaiusSingleplayerHandoff = '';
              globalThis.__gaiusSingleplayerHandoffGeneration = '';
            }
            worker.__gaiusHandoffPending = false;
            worker.__gaiusStopRequested = true;
            try {
              worker.postMessage({type: 'stop'});
            } catch (error) {
              const bridge = globalThis.__gaiusNettyBridge;
              if (ownsWorker() && bridge &&
                  typeof bridge.failLocalSession === 'function') {
                bridge.failLocalSession(key, String(error &&
                  (error.message || error) || error), expectedGeneration);
              }
            }
            if (typeof worker.__gaiusArmStopTimeout === 'function') {
              worker.__gaiusArmStopTimeout(
                35000,
                'Integrated server did not stop within 35 seconds'
              );
              return;
            }
            if (worker.__gaiusStopTimeout) clearTimeout(worker.__gaiusStopTimeout);
            worker.__gaiusStopTimeout = setTimeout(function() {
              if (worker.__gaiusTerminal) return;
              worker.__gaiusTerminal = true;
              if (typeof worker.__gaiusStopTelemetry === 'function') {
                worker.__gaiusStopTelemetry();
              }
              const bridge = globalThis.__gaiusNettyBridge;
              if (ownsWorker() && bridge &&
                  typeof bridge.failLocalSession === 'function') {
                bridge.failLocalSession(
                  key,
                  'Integrated server did not stop within 35 seconds',
                  expectedGeneration
                );
              }
              const ports = globalThis.__gaiusLocalServerPorts;
              const pendingPort = ports && typeof ports.get === 'function'
                ? ports.get(key)
                : null;
              if (ownsWorker() && pendingPort && pendingPort === worker.__gaiusClientPort) {
                try { pendingPort.close(); } catch (ignored) {}
                ports.delete(key);
              } else {
                try { if (worker.__gaiusClientPort) worker.__gaiusClientPort.close(); }
                catch (ignored) {}
              }
              try { worker.terminate(); } catch (ignored) {}
              if (ownsWorker()) workers.delete(key);
            }, 35000);
            """)
    private static native void requestWorkerStop(String sessionId, String launchGeneration);
}
