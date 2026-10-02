// Gaius boot: the one script the launcher (port/web/launcher/index.template.html) includes
// before its own boot code. It wires the v0.4 runtime pieces into every distribution form
// without the launcher having to know which one it runs in:
//
//   site      multi-file GitHub Pages layout (port/scripts/build-pages-site.py); the page
//             carries window.__gaiusSite = {id, profile, assets: {logical name: hashed url},
//             kernels, modules, serviceWorker, gzip}
//   portable  the offline single file (port/scripts/build-portable-html.py); modules are
//             inlined before this script and window.__gaiusPortableKernels carries the wasm
//   dev       a plain dist directory; files are loaded by their logical names
//
// What it does:
//   - asset URLs: __gaiusBoot.assetUrl(name) maps a logical file name (classes.js,
//     kernels/kernel-worker.js, ...) to its content-hashed URL; applyAssetUrls() points the
//     launcher's client, wasm hotpath and singleplayer globals at them.
//   - Service Worker (site over https only): registers gaius-sw.js (cross-origin isolation
//     headers + immutable asset cache) and prunes old generations once the title screen is up.
//     ?gaiusSw=0 unregisters it; ?gaiusCoi=0|credentialless|require-corp picks the COEP mode.
//   - runtime modules: kernel-policy.js, kernel-runtime.js and the kernel job codecs, then the
//     quality layer (runtime/quality/*, owned by the graphics workstream) in its load order.
//     beforeMain() waits for them (bounded) and runs GaiusQuality.caps.ensureTier().
//   - kernel runtime: window.__gaiusKernels creates GaiusKernelRuntime lazily, feeds it the
//     player position from window.__gaiusMinecraftState for motion prediction, and gives the
//     integrated server Worker a MessagePort into it ({type: "gaius-kernel-port"} right after
//     the singleplayer "start" message).
//   - lazy singleplayer: the server Worker payload is only loaded when a world starts; while
//     the title screen idles it is prefetched (?gaiusPrefetch=0 turns that off).
//   - sounds on demand (site): the vanilla pack is split into a core pack and a sounds pack;
//     the sounds pack streams in parallel and is merged into window.__gaiusVanillaAssets.
//   - boot timeline: performance marks "gaius:<phase>" for every launcher boot timing.
//
// Plain script, no modules: it is inlined into the portable page. It must never contain a
// closing script tag sequence.
(function (global) {
  "use strict";
  if (!global || global.__gaiusBoot) return;

  const BOOT_VERSION = 1;
  const doc = global.document;
  const loc = global.location || {protocol: "", search: "", href: ""};
  const nav = global.navigator || {};
  const perf = global.performance;
  const params = (() => {
    try {
      return new URLSearchParams(loc.search || "");
    } catch (_) {
      return {get: () => null};
    }
  })();
  const nowMs = () => (perf && perf.now ? perf.now() : Date.now());
  const site = global.__gaiusSite && typeof global.__gaiusSite === "object" ? global.__gaiusSite : null;
  // The portable page sets __gaiusBootPortable before this script (its own bootstrap, which sets
  // __gaiusPortableBuild, runs later in the body).
  const isPortable = () => global.__gaiusPortableBuild === true || global.__gaiusBootPortable === true;
  const isFile = loc.protocol === "file:";
  const events = [];

  function record(event, detail) {
    events.push({event, detail: detail === undefined ? "" : String(detail), at: Date.now()});
    if (events.length > 200) events.splice(0, events.length - 200);
  }

  function storageGet(key) {
    try {
      return global.localStorage ? global.localStorage.getItem(key) : null;
    } catch (_) {
      return null;
    }
  }

  function storageSet(key, value) {
    try {
      if (global.localStorage) global.localStorage.setItem(key, value);
    } catch (_) {
      // storage may be blocked; the setting simply does not persist
    }
  }

  function mark(name) {
    try {
      if (perf && typeof perf.mark === "function") perf.mark("gaius:" + name);
    } catch (_) {
      // marks are diagnostics only
    }
  }

  mark("boot-script");

  // --- asset map ------------------------------------------------------------------------------

  const KERNEL_RUNTIME_SCRIPTS = ["kernels/kernel-policy.js", "kernels/kernel-runtime.js"];
  const QUALITY_SCRIPTS = [
    "runtime/quality/gpu-caps.js",
    "runtime/quality/quality-profile.js",
    "runtime/quality/gl-pass.js",
    "runtime/quality/upscaler.js",
    "runtime/quality/post-chain.js",
    "runtime/quality/quality-runtime.js",
  ];
  const SITE_GZIP_KEY = "gaius.site.gzip.v1";

  function assetUrl(name) {
    if (site && site.assets && Object.prototype.hasOwnProperty.call(site.assets, name)) return site.assets[name];
    return null;
  }

  function hasAsset(name) {
    return assetUrl(name) !== null;
  }

  // Whether the host compresses large text responses on the wire. Measured on a visit without
  // the Service Worker (its responses report decoded sizes); remembered per browser.
  function siteUsesGzipFallback() {
    if (!site) return false;
    const forced = params.get("gaiusSiteGzip");
    if (forced === "1") return true;
    if (forced === "0") return false;
    return storageGet(SITE_GZIP_KEY) === "1";
  }

  const useGzip = siteUsesGzipFallback();
  let assetsReadyPromise = Promise.resolve();

  async function gunzipToBlobUrl(url, type) {
    const response = await global.fetch(url, {cache: "force-cache"});
    if (!response.ok || !response.body) throw new Error("HTTP " + response.status + " while loading " + url);
    const stream = response.body.pipeThrough(new global.DecompressionStream("gzip"));
    const blob = await new global.Response(stream).blob();
    return global.URL.createObjectURL(new global.Blob([blob], {type}));
  }

  // Early (parse-time) URLs: the wasm hotpath loader and the client script read these.
  if (site) {
    if (hasAsset("gaius-hotpath.wasm") && !global.__gaiusHotpathWasmUrl) {
      global.__gaiusHotpathWasmUrl = new URL(assetUrl("gaius-hotpath.wasm"), loc.href).href;
    }
    if (useGzip && hasAsset("classes.js.gz") && typeof global.DecompressionStream === "function") {
      assetsReadyPromise = gunzipToBlobUrl(assetUrl("classes.js.gz"), "text/javascript").then((url) => {
        global.__gaiusClassesUrl = url;
        record("classes-gzip-fallback", url);
      }, (error) => {
        record("classes-gzip-fallback-failed", error && error.message);
        if (hasAsset("classes.js")) global.__gaiusClassesUrl = assetUrl("classes.js");
      });
    } else if (hasAsset("classes.js")) {
      global.__gaiusClassesUrl = assetUrl("classes.js");
      // Start the client download now, in parallel with storage initialisation; the launcher's
      // own script tag for the same URL then reuses this response.
      if (doc && doc.head && doc.createElement) {
        const link = doc.createElement("link");
        link.rel = "preload";
        link.as = "script";
        link.href = global.__gaiusClassesUrl;
        doc.head.appendChild(link);
      }
    }
  }

  // Called by the launcher once its own singleplayer URL block ran (that block is regenerated by
  // postprocess-index-html.py, so the site URLs are applied after it instead of inside it).
  function applyAssetUrls() {
    if (!site || isPortable()) return false;
    if (hasAsset("singleplayer-server-worker.js")) {
      global.__gaiusSingleplayerWorkerUrl = new URL(assetUrl("singleplayer-server-worker.js"), loc.href).href;
    }
    if (useGzip && hasAsset("singleplayer-server.js.gz")) {
      global.__gaiusSingleplayerServerGzipUrl = new URL(assetUrl("singleplayer-server.js.gz"), loc.href).href;
      global.__gaiusSingleplayerServerUrl = null;
    } else if (hasAsset("singleplayer-server.js")) {
      // A plain URL lets the Worker importScripts() it directly: streaming parse and a code
      // cache instead of a gunzipped Blob.
      global.__gaiusSingleplayerServerUrl = new URL(assetUrl("singleplayer-server.js"), loc.href).href;
      global.__gaiusSingleplayerServerGzipUrl = null;
    }
    return true;
  }

  // --- script modules -------------------------------------------------------------------------

  const inline = global.__gaiusBootInline && typeof global.__gaiusBootInline === "object" ? global.__gaiusBootInline : {};
  const scriptLoads = new Map();

  function loadScript(name) {
    if (scriptLoads.has(name)) return scriptLoads.get(name);
    let promise;
    if (inline[name] === true) {
      promise = Promise.resolve(true);
    } else if (!doc || !doc.createElement) {
      promise = Promise.resolve(false);
    } else if (isPortable() || (isFile && !site)) {
      // A portable page inlines what it has; a dist opened from disk cannot load siblings
      // reliably, so missing modules are simply absent.
      promise = Promise.resolve(false);
    } else {
      const url = assetUrl(name) || name;
      promise = new Promise((resolve) => {
        const element = doc.createElement("script");
        element.src = url;
        // Dynamically inserted scripts with async=false run in insertion order.
        element.async = false;
        element.setAttribute("data-gaius-boot-module", name);
        element.onload = () => resolve(true);
        element.onerror = () => {
          record("module-missing", name);
          resolve(false);
        };
        (doc.head || doc.documentElement).appendChild(element);
      });
    }
    scriptLoads.set(name, promise);
    return promise;
  }

  function kernelJobScripts() {
    if (site && Array.isArray(site.modules)) return site.modules.slice();
    if (Array.isArray(global.__gaiusKernelJobScripts)) return global.__gaiusKernelJobScripts.slice();
    return [];
  }

  // Started at parse time: these download while the launcher fetches classes.js.
  const kernelScriptsReady = Promise.all(KERNEL_RUNTIME_SCRIPTS.concat(kernelJobScripts()).map(loadScript));
  const qualityReady = Promise.all(QUALITY_SCRIPTS.map(loadScript));

  function withTimeout(promise, ms) {
    return Promise.race([promise, new Promise((resolve) => global.setTimeout(resolve, ms))]);
  }

  // --- sounds on demand -----------------------------------------------------------------------

  const VANILLA_MAGIC = "GAIUSVP1";

  function parsePack(bytes) {
    for (let i = 0; i < VANILLA_MAGIC.length; i++) {
      if (bytes[i] !== VANILLA_MAGIC.charCodeAt(i)) throw new Error("sound pack has no GAIUSVP1 header");
    }
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const indexLength = view.getUint32(8, true);
    const dataOffset = 12 + indexLength;
    if (dataOffset > bytes.length) throw new Error("sound pack index is truncated");
    const index = JSON.parse(new global.TextDecoder().decode(bytes.subarray(12, dataOffset)));
    return {index, dataOffset};
  }

  // Appends the sound pack's payload to the decoded core pack and its ranges to the index. The
  // swap of bytes and index happens in one synchronous step, so Java never sees a half merge.
  function mergeSoundPack(root, soundBytes) {
    const pack = parsePack(soundBytes);
    const payload = soundBytes.subarray(pack.dataOffset);
    const merged = new Uint8Array(root.bytes.length + payload.length);
    merged.set(root.bytes, 0);
    merged.set(payload, root.bytes.length);
    const shift = root.bytes.length - root.dataOffset;
    const additions = [];
    for (const name in pack.index) {
      if (!Object.prototype.hasOwnProperty.call(pack.index, name)) continue;
      const range = pack.index[name];
      if (!Array.isArray(range) || range.length !== 2 || range[0] < 0 || range[1] < 0
          || range[0] + range[1] > payload.length) {
        throw new Error("sound pack range is invalid: " + name);
      }
      additions.push([name, [range[0] + shift, range[1]]]);
    }
    root.bytes = merged;
    for (let i = 0; i < additions.length; i++) root.index[additions[i][0]] = additions[i][1];
    root.resourceCount = (root.resourceCount || 0) + additions.length;
    return additions.length;
  }

  // The launcher defines __gaiusVanillaAssetsReady after this script ran; wait for it to exist,
  // then for the core pack to be decoded.
  function vanillaAssetsDecoded() {
    return new Promise((resolve) => {
      const poll = () => {
        const ready = global.__gaiusVanillaAssetsReady;
        if (ready && typeof ready.then === "function") ready.then(resolve, () => resolve(null));
        else global.setTimeout(poll, 50);
      };
      poll();
    });
  }

  let soundsState = {mode: "none", merged: false, error: null, resources: 0};
  let soundsGate = Promise.resolve();

  if (site && hasAsset("vanilla-sounds.pack") && !isPortable()) {
    const requested = params.get("gaiusSounds");
    const mode = requested === "eager" || requested === "deferred" ? requested
      : (typeof global.__gaiusSoundReloadHook === "function" ? "deferred" : "eager");
    soundsState = {mode, merged: false, error: null, resources: 0};
    const soundBytes = global.fetch(assetUrl("vanilla-sounds.pack"), {cache: "force-cache"}).then((response) => {
      if (!response.ok) throw new Error("HTTP " + response.status + " while loading the sound pack");
      return response.arrayBuffer();
    });
    const merge = Promise.all([soundBytes, vanillaAssetsDecoded()])
      .then((parts) => {
        const root = global.__gaiusVanillaAssets || parts[1];
        if (!root || !root.bytes || !root.index) throw new Error("the core asset pack is not decoded");
        soundsState.resources = mergeSoundPack(root, new Uint8Array(parts[0]));
        soundsState.merged = true;
        mark("sounds-merged");
        if (typeof global.__gaiusSoundReloadHook === "function" && soundsState.mode === "deferred") {
          try {
            global.__gaiusSoundReloadHook();
          } catch (error) {
            record("sound-reload-hook-failed", error && error.message);
          }
        }
      })
      .catch((error) => {
        soundsState.error = String(error && error.message || error);
        record("sound-pack-failed", soundsState.error);
      });
    soundsGate = mode === "eager" ? merge : Promise.resolve();
  }

  // --- kernel runtime -------------------------------------------------------------------------

  let runtimePromise = null;
  let runtimeInstance = null;
  let viewerTimer = null;

  function kernelSwitchOff() {
    const value = params.get("gaiusKernels");
    if (value === "0" || value === "off" || value === "false") return true;
    try {
      const stored = JSON.parse(storageGet("gaius.kernels.settings.v1") || "null");
      return !!(stored && stored.enabled === false) && value !== "1";
    } catch (_) {
      return false;
    }
  }

  async function kernelManifest() {
    if (isPortable() && global.__gaiusPortableKernels) {
      return {kernels: global.__gaiusPortableKernels, worker: portableWorkerSource()};
    }
    if (site) {
      if (!site.kernels || !hasAsset("kernels/kernel-worker.js")) return null;
      const kernels = {};
      for (const name of Object.keys(site.kernels)) {
        const entry = site.kernels[name];
        const variants = {};
        for (const variant of Object.keys(entry.variants || {})) {
          variants[variant] = {url: new URL(entry.variants[variant], loc.href).href};
        }
        kernels[name] = {kinds: entry.kinds || [], memory: entry.memory, variants};
      }
      return {kernels, worker: {workerUrl: new URL(assetUrl("kernels/kernel-worker.js"), loc.href).href}};
    }
    if (isFile) return null;
    try {
      const response = await global.fetch("kernels/kernels.json", {cache: "no-cache"});
      if (!response.ok) return null;
      const manifest = await response.json();
      // A dist directory lists the kernel job codecs next to the modules.
      if (Array.isArray(manifest.scripts)) await Promise.all(manifest.scripts.map(loadScript));
      const kernels = {};
      for (const name of Object.keys(manifest.kernels || {})) {
        const entry = manifest.kernels[name];
        const variants = {};
        for (const variant of Object.keys(entry.variants || {})) {
          variants[variant] = {url: new URL("kernels/" + entry.variants[variant].file, loc.href).href};
        }
        kernels[name] = {kinds: entry.kinds || [], memory: entry.memory, variants};
      }
      return {kernels, worker: {workerUrl: new URL("kernels/kernel-worker.js", loc.href).href}};
    } catch (_) {
      return null;
    }
  }

  function portableWorkerSource() {
    const element = doc && doc.getElementById ? doc.getElementById("gaius-kernel-worker-source") : null;
    const source = element ? element.textContent : "";
    return source ? {workerSource: source} : {};
  }

  function runtime() {
    if (runtimePromise) return runtimePromise;
    runtimePromise = (async () => {
      if (kernelSwitchOff()) {
        record("kernels-disabled", "switch");
        return null;
      }
      await withTimeout(kernelScriptsReady, 15000);
      if (!global.GaiusKernelRuntime || !global.GaiusKernelPolicy) {
        record("kernels-unavailable", "runtime scripts missing");
        return null;
      }
      const manifest = await kernelManifest();
      if (!manifest || !manifest.kernels || Object.keys(manifest.kernels).length === 0) {
        record("kernels-unavailable", "no kernel manifest");
        return null;
      }
      if (!manifest.worker.workerUrl && !manifest.worker.workerSource) {
        record("kernels-unavailable", "no kernel worker script");
        return null;
      }
      const created = global.GaiusKernelRuntime.create(Object.assign({kernels: manifest.kernels}, manifest.worker));
      runtimeInstance = created;
      startViewerFeed();
      mark("kernel-runtime");
      return created.available() ? created : null;
    })().catch((error) => {
      record("kernel-runtime-failed", error && error.message);
      return null;
    });
    return runtimePromise;
  }

  // Motion prediction input: the client publishes the player in __gaiusMinecraftState.
  function startViewerFeed() {
    if (viewerTimer !== null) return;
    let lastAt = 0;
    viewerTimer = global.setInterval(() => {
      const runtime = runtimeInstance;
      if (!runtime || runtime.terminated) return;
      const state = global.__gaiusMinecraftState;
      const player = state && state.player;
      if (!player || !state.level || state.at === lastAt) return;
      lastAt = state.at;
      const distance = state.clientDistance && state.clientDistance.effectiveRenderDistance;
      runtime.setViewer({
        x: player.x, y: player.y, z: player.z, yaw: player.yaw, t: state.at,
        viewDistance: typeof distance === "number" ? distance : undefined,
      });
    }, 200);
  }

  // The integrated server Worker gets a port into this runtime right after its start message.
  function installServerWorkerHook() {
    const WorkerClass = global.Worker;
    if (typeof WorkerClass !== "function" || !WorkerClass.prototype || WorkerClass.prototype.__gaiusKernelHook) return;
    if (typeof global.MessageChannel !== "function") return;
    const nativePostMessage = WorkerClass.prototype.postMessage;
    WorkerClass.prototype.postMessage = function (message, transfer) {
      const result = nativePostMessage.apply(this, arguments);
      try {
        if (message && message.type === "start" && message.port && !this.__gaiusKernelPortSent && !kernelSwitchOff()) {
          this.__gaiusKernelPortSent = true;
          const channel = new global.MessageChannel();
          nativePostMessage.call(this, {type: "gaius-kernel-port", port: channel.port2}, [channel.port2]);
          runtime().then((created) => {
            if (created) created.attachPort(channel.port1);
            else {
              channel.port1.postMessage({type: "status", status: {enabled: false, kernels: {}}});
              channel.port1.close();
            }
          });
        }
      } catch (error) {
        record("kernel-port-failed", error && error.message);
      }
      return result;
    };
    WorkerClass.prototype.__gaiusKernelHook = true;
  }
  installServerWorkerHook();

  const kernels = {
    version: BOOT_VERSION,
    runtime,
    enabled() {
      return !kernelSwitchOff();
    },
    available(kernel) {
      return !!(runtimeInstance && runtimeInstance.available(kernel));
    },
    submit(kind, payload, opts) {
      return runtime().then((created) => {
        if (!created) throw new Error("kernel runtime is not available");
        return created.submit(kind, payload, opts);
      });
    },
    setViewer(viewer) {
      return runtimeInstance ? runtimeInstance.setViewer(viewer) : false;
    },
    reportPressure(level) {
      if (runtimeInstance) runtimeInstance.reportPressure(level);
    },
    telemetry() {
      return runtimeInstance ? runtimeInstance.telemetry() : null;
    },
  };
  global.__gaiusKernels = kernels;

  // --- Service Worker -------------------------------------------------------------------------

  const swState = {registered: false, controlled: false, coep: null, error: null, pruned: null};

  function coepMode() {
    const requested = params.get("gaiusCoi");
    if (requested === "0" || requested === "off") return "off";
    if (requested === "credentialless" || requested === "require-corp") return requested;
    const stored = storageGet("gaius.sw.coep");
    return stored === "require-corp" || stored === "off" ? stored : "credentialless";
  }

  function serviceWorkerAllowed() {
    return !!site && !isPortable() && !isFile && !!nav.serviceWorker && global.isSecureContext === true;
  }

  function registerServiceWorker() {
    if (!serviceWorkerAllowed()) return;
    const container = nav.serviceWorker;
    swState.controlled = !!container.controller;
    if (params.get("gaiusSw") === "0") {
      container.getRegistrations().then((registrations) => {
        for (const registration of registrations) registration.unregister();
      }).catch(() => {});
      record("service-worker", "disabled by ?gaiusSw=0");
      return;
    }
    const mode = coepMode();
    swState.coep = mode;
    const script = (site.serviceWorker || "gaius-sw.js") + "?coep=" + encodeURIComponent(mode);
    container.register(script, {scope: "./", updateViaCache: "none"}).then(() => {
      swState.registered = true;
    }, (error) => {
      swState.error = String(error && error.message || error);
      record("service-worker-failed", swState.error);
    });
  }

  function pruneServiceWorkerCache() {
    if (!serviceWorkerAllowed() || !nav.serviceWorker.controller || !site.assets) return;
    const keep = [];
    for (const name of Object.keys(site.assets)) keep.push(new URL(site.assets[name], loc.href).href);
    nav.serviceWorker.controller.postMessage({type: "gaius-sw-prune", generation: site.id || "", keep});
    swState.pruned = Date.now();
  }

  // On a visit the Service Worker did not serve, learn whether the host compressed the big
  // client script; if not, later boots fetch the .gz copies and inflate them locally.
  function learnHostCompression() {
    if (!site || !perf || typeof perf.getEntriesByType !== "function" || swState.controlled) return;
    const name = assetUrl("classes.js");
    if (!name || useGzip) return;
    const absolute = new URL(name, loc.href).href;
    const entry = perf.getEntriesByType("resource").find((item) => item.name === absolute || item.name.indexOf(absolute + "?") === 0);
    if (!entry || !(entry.transferSize > 0) || !(entry.decodedBodySize > 0) || !(entry.encodedBodySize > 0)) return;
    if (entry.encodedBodySize >= entry.decodedBodySize * 0.9 && hasAsset("classes.js.gz")) {
      storageSet(SITE_GZIP_KEY, "1");
      record("host-compression", "absent; next boot uses gzip copies");
    }
  }

  if (doc && doc.readyState === "complete") registerServiceWorker();
  else if (global.addEventListener) global.addEventListener("load", registerServiceWorker, {once: true});

  // --- title-screen idle work -----------------------------------------------------------------

  let idleWorkDone = false;
  let titleSince = 0;

  function connectionIsConstrained() {
    const connection = nav.connection;
    if (!connection) return false;
    return connection.saveData === true || /(^|-)2g$/.test(String(connection.effectiveType || ""));
  }

  function prefetch(url) {
    if (!url) return Promise.resolve(false);
    const options = {cache: "force-cache"};
    try {
      options.priority = "low";
    } catch (_) {
      // older engines ignore unknown init members anyway
    }
    return global.fetch(url, options).then((response) => response.ok ? response.arrayBuffer().then(() => true) : false)
      .catch(() => false);
  }

  function runIdleWork() {
    if (idleWorkDone) return;
    idleWorkDone = true;
    publishTimeline();
    learnHostCompression();
    pruneServiceWorkerCache();
    if (isPortable() || params.get("gaiusPrefetch") === "0" || connectionIsConstrained()) return;
    // The singleplayer payload is only needed when a world opens; warm the caches now.
    const targets = [global.__gaiusSingleplayerWorkerUrl];
    if (global.__gaiusSingleplayerServerUrl) targets.push(global.__gaiusSingleplayerServerUrl);
    else if (global.__gaiusSingleplayerServerGzipUrl) targets.push(global.__gaiusSingleplayerServerGzipUrl);
    if (site && site.kernels) {
      const simd = global.GaiusKernelRuntime ? global.GaiusKernelRuntime.detectFeatures().simd : false;
      for (const name of Object.keys(site.kernels)) {
        const variants = site.kernels[name].variants || {};
        targets.push(simd && variants.simd ? variants.simd : (variants.baseline || variants.simd));
      }
    }
    let chain = Promise.resolve();
    for (const target of targets) {
      if (!target || String(target).indexOf("blob:") === 0) continue;
      chain = chain.then(() => prefetch(target));
    }
    chain.then(() => record("prefetch-done", targets.length));
  }

  function watchTitleScreen() {
    const timer = global.setInterval(() => {
      const state = global.__gaiusMinecraftState;
      const screen = state && state.screen ? String(state.screen) : "";
      const onTitle = (screen === "TitleScreen" || screen.slice(-12) === ".TitleScreen")
        && state.running === true && !state.overlay;
      if (state && state.level && !idleWorkDone) {
        // A world opened before the title idled: do the bookkeeping, skip the prefetch.
        idleWorkDone = true;
        publishTimeline();
        learnHostCompression();
        pruneServiceWorkerCache();
      }
      if (!onTitle) {
        titleSince = 0;
        if (idleWorkDone) global.clearInterval(timer);
        return;
      }
      if (!titleSince) {
        titleSince = Date.now();
        mark("title-screen");
        return;
      }
      if (Date.now() - titleSince < 2500 || idleWorkDone) return;
      global.clearInterval(timer);
      if (typeof global.requestIdleCallback === "function") global.requestIdleCallback(runIdleWork, {timeout: 5000});
      else global.setTimeout(runIdleWork, 0);
    }, 1000);
  }
  watchTitleScreen();

  // --- boot timeline --------------------------------------------------------------------------

  const timeline = [];

  function publishTimeline() {
    const timings = global.__gaiusBootTimings;
    if (!timings || !perf || typeof perf.mark !== "function") return timeline;
    for (const key of Object.keys(timings)) {
      const value = timings[key];
      if (typeof value !== "number" || !isFinite(value)) continue;
      if (timeline.some((entry) => entry.name === key)) continue;
      timeline.push({name: key, at: value});
      try {
        perf.mark("gaius:" + key, {startTime: value});
      } catch (_) {
        // engines without mark options still get the table below
      }
    }
    timeline.sort((a, b) => a.at - b.at);
    return timeline;
  }

  // --- launcher hooks -------------------------------------------------------------------------

  // Awaited by the launcher right before main(args): the quality layer must be loaded (bounded,
  // a missing file never blocks the boot) and its tier chosen; eager sounds must be merged.
  async function beforeMain() {
    mark("before-main");
    await withTimeout(qualityReady, 5000);
    const quality = global.GaiusQuality;
    if (quality && quality.caps && typeof quality.caps.ensureTier === "function") {
      try {
        quality.caps.ensureTier();
      } catch (error) {
        record("quality-tier-failed", error && error.message);
      }
    }
    await soundsGate;
    mark("main");
  }

  function assetsReady() {
    return assetsReadyPromise;
  }

  global.__gaiusBoot = {
    version: BOOT_VERSION,
    site,
    startedAt: nowMs(),
    assetUrl,
    applyAssetUrls,
    assetsReady,
    beforeMain,
    mark,
    publishTimeline,
    timeline,
    events,
    kernels,
    serviceWorker: swState,
    sounds: () => soundsState,
    modules: {kernels: kernelScriptsReady, quality: qualityReady},
    mergeSoundPack,
  };
})(typeof window !== "undefined" ? window : globalThis);
