package dev.gaius.browser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.server.WorldStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.teavm.jso.JSBody;

/** Launches the official Minecraft server in a Web Worker for browser singleplayer. */
public final class BrowserSingleplayerClient {
    private static final int LOCAL_SERVER_PORT = 25565;

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

        String worldId = storage.getLevelId();
        storage.saveDataTag(
                worldStem.registries().compositeAccess(),
                worldStem.worldData());
        int renderDistance = minecraft.options.renderDistance().get();
        int simulationDistance = minecraft.options.simulationDistance().get();
        String sessionId = launchWorker(
                worldId,
                newWorld,
                renderDistance,
                simulationDistance);
        if (sessionId == null || sessionId.isEmpty()) {
            return false;
        }

        worldStem.close();
        storage.safeClose();
        setClientHandoff(true);
        try {
            minecraft.disconnectWithProgressScreen();
        } finally {
            setClientHandoff(false);
        }

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
        return true;
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

    @JSBody(params = {"worldId", "newWorld", "renderDistance", "simulationDistance"}, script = """
            try {
              const bytes = new Uint8Array(16);
              crypto.getRandomValues(bytes);
              let sessionId = '';
              for (let i = 0; i < bytes.length; i++) {
                sessionId += bytes[i].toString(16).padStart(2, '0');
              }

              const channel = new MessageChannel();
              const ports = globalThis.__gaiusLocalServerPorts ||
                (globalThis.__gaiusLocalServerPorts = new Map());
              ports.set(sessionId, channel.port1);

              const workers = globalThis.__gaiusSingleplayerWorkers ||
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
              const worker = new Worker(workerUrl, {name: 'Gaius Integrated Server'});
              worker.__gaiusHandoffPending = true;
              worker.__gaiusClientAttached = false;
              worker.__gaiusStopRequested = false;
              workers.set(sessionId, worker);
              const failLocalSession = function(detail) {
                const bridge = globalThis.__gaiusNettyBridge;
                if (bridge && typeof bridge.failLocalSession === 'function') {
                  bridge.failLocalSession(sessionId, detail);
                }
              };
              const terminateFailedWorker = function(detail) {
                if (worker.__gaiusTerminal) return;
                worker.__gaiusTerminal = true;
                if (worker.__gaiusHandoffTimeout) {
                  clearTimeout(worker.__gaiusHandoffTimeout);
                  worker.__gaiusHandoffTimeout = 0;
                }
                failLocalSession(detail);
                const pendingPort = ports.get(sessionId);
                if (pendingPort) {
                  try { pendingPort.close(); } catch (ignored) {}
                  ports.delete(sessionId);
                }
                try { worker.terminate(); } catch (ignored) {}
                workers.delete(sessionId);
              };
              worker.onmessage = function(event) {
                const message = event.data;
                const events = globalThis.__gaiusMinecraftEvents ||
                  (globalThis.__gaiusMinecraftEvents = []);
                events.push({event: 'singleplayer:worker', detail: message, at: Date.now()});
                if (events.length > 500) events.splice(0, events.length - 500);
                if (message && message.type === 'stopped') {
                  worker.__gaiusStopped = true;
                  if (worker.__gaiusHandoffTimeout) {
                    clearTimeout(worker.__gaiusHandoffTimeout);
                    worker.__gaiusHandoffTimeout = 0;
                  }
                  const storageRefresh = refreshPersistentFiles();
                  worker.__gaiusStorageRefresh = storageRefresh;
                  storageRefresh.then(function() {
                    worker.__gaiusTerminal = true;
                    try { worker.terminate(); } catch (ignored) {}
                    workers.delete(sessionId);
                  }, function(error) {
                    events.push({
                      event: 'singleplayer:storage-refresh-error',
                      detail: String(error && (error.stack || error.message) || error),
                      at: Date.now()
                    });
                    worker.__gaiusTerminal = true;
                    try { worker.terminate(); } catch (ignored) {}
                    workers.delete(sessionId);
                  });
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
                worldId: String(worldId),
                newWorld: !!newWorld,
                bridgeUrl: globalThis.__gaiusBridgeUrl || null,
                bridgeToken: globalThis.__gaiusBridgeToken || null,
                renderDistance: Math.max(2, Math.min(32, Number(renderDistance) || 6)),
                simulationDistance: Math.max(2, Math.min(32, Number(simulationDistance) || 4)),
                serverScriptUrl: globalThis.__gaiusSingleplayerServerUrl || null,
                serverScriptGzipUrl: globalThis.__gaiusSingleplayerServerGzipUrl || null,
                port: channel.port2
              };
              worker.__gaiusDistances = init.renderDistance + ':' + init.simulationDistance;
              const start = function() {
                if (worker.__gaiusStopRequested) {
                  try { channel.port2.close(); } catch (ignored) {}
                  terminateFailedWorker('Singleplayer launch was cancelled');
                  return;
                }
                worker.postMessage(init, [channel.port2]);
              };
              const stopPreviousWorker = function(entry) {
                const previousSessionId = entry[0];
                const previousWorker = entry[1];
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
                  const terminatePrevious = function(detail) {
                    previousWorker.__gaiusTerminal = true;
                    const bridge = globalThis.__gaiusNettyBridge;
                    if (bridge && typeof bridge.failLocalSession === 'function') {
                      bridge.failLocalSession(previousSessionId, detail);
                    }
                    const pendingPort = ports.get(previousSessionId);
                    if (pendingPort) {
                      try { pendingPort.close(); } catch (ignored) {}
                      ports.delete(previousSessionId);
                    }
                    try { previousWorker.terminate(); } catch (ignored) {}
                    workers.delete(previousSessionId);
                  };
                  const finish = function() {
                    if (settled) return;
                    settled = true;
                    clearTimeout(timeout);
                    previousWorker.removeEventListener('message', onMessage);
                    waitForStorageRefresh().then(resolve, resolve);
                  };
                  const onMessage = function(event) {
                    if (event.data && event.data.type === 'stopped') {
                      Promise.resolve().then(finish);
                    }
                  };
                  const timeout = setTimeout(function() {
                    terminatePrevious(
                      'Previous integrated server did not stop within 30 seconds'
                    );
                    finish();
                  }, 30000);
                  previousWorker.addEventListener('message', onMessage);
                  try {
                    previousWorker.postMessage({type: 'stop'});
                  } catch (error) {
                    terminatePrevious(String(error && (error.message || error) || error));
                    finish();
                  }
                });
              };
              const refreshPersistentFiles = function() {
                if (typeof indexedDB === 'undefined') return Promise.resolve();
                return new Promise(function(resolve, reject) {
                  const request = indexedDB.open('gaius-fs-v1', 1);
                  request.onsuccess = function() { resolve(request.result); };
                  request.onerror = function() {
                    reject(request.error || new Error('IndexedDB refresh open failed'));
                  };
                }).then(function(database) {
                  return new Promise(function(resolve, reject) {
                    const transaction = database.transaction('files', 'readonly');
                    const request = transaction.objectStore('files').getAll();
                    request.onsuccess = function() { resolve(request.result || []); };
                    request.onerror = function() {
                      reject(request.error || new Error('IndexedDB refresh read failed'));
                    };
                  }).then(function(values) {
                    const files = globalThis.__gaiusPersistentFiles ||
                      (globalThis.__gaiusPersistentFiles = Object.create(null));
                    const paths = Object.keys(files);
                    for (let index = 0; index < paths.length; index++) {
                      delete files[paths[index]];
                    }
                    for (let index = 0; index < values.length; index++) {
                      const entry = values[index];
                      if (entry && typeof entry.path === 'string' &&
                          typeof entry.value === 'string') {
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
              const events = globalThis.__gaiusMinecraftEvents ||
                (globalThis.__gaiusMinecraftEvents = []);
              events.push({
                event: 'singleplayer:worker-launch-error',
                detail: String(error && (error.stack || error.message) || error),
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

    @JSBody(params = {"active"}, script = "globalThis.__gaiusSingleplayerHandoff = !!active;")
    private static native void setClientHandoff(boolean active);

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
            if (globalThis.__gaiusSingleplayerHandoff) return;
            const workers = globalThis.__gaiusSingleplayerWorkers;
            if (!workers || typeof workers.values !== 'function') return;
            workers.forEach(function(worker, sessionId) {
              if (worker.__gaiusHandoffPending && !worker.__gaiusClientAttached) {
                const events = globalThis.__gaiusMinecraftEvents ||
                  (globalThis.__gaiusMinecraftEvents = []);
                events.push({
                  event: 'singleplayer:handoff-disconnect-ignored',
                  detail: sessionId,
                  at: Date.now()
                });
                if (events.length > 500) events.splice(0, events.length - 500);
                return;
              }
              worker.__gaiusStopRequested = true;
              try { worker.postMessage({type: 'stop'}); } catch (ignored) {}
              setTimeout(function() {
                if (worker.__gaiusTerminal) return;
                worker.__gaiusTerminal = true;
                const bridge = globalThis.__gaiusNettyBridge;
                if (bridge && typeof bridge.failLocalSession === 'function') {
                  bridge.failLocalSession(
                    sessionId,
                    'Integrated server did not stop within 30 seconds'
                  );
                }
                const ports = globalThis.__gaiusLocalServerPorts;
                const pendingPort = ports && typeof ports.get === 'function'
                  ? ports.get(sessionId)
                  : null;
                if (pendingPort) {
                  try { pendingPort.close(); } catch (ignored) {}
                  ports.delete(sessionId);
                }
                try { worker.terminate(); } catch (ignored) {}
                workers.delete(sessionId);
              }, 30000);
            });
            """)
    private static native void requestWorkerStop();
}
