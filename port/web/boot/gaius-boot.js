// Gaius boot: the one script the launcher (port/web/launcher/index.template.html) includes
// before its own boot code. It wires the v0.4 runtime pieces into every distribution form
// without the launcher having to know which one it runs in:
//
//   site      multi-file GitHub Pages layout (port/scripts/build-pages-site.py); the page
//             carries window.__gaiusSite = {id, profile, assets: {logical name: hashed url},
//             kernels, modules, serviceWorker, inflate}; gaius-site-inflate-v1: the
//             WebAssembly GC modules may ship only as <name>.wasm.<hash>.gz (assets
//             "<name>.wasm.gz"), and inflate names the <name>.<hash>.wasm URL the Service
//             Worker serves them under (port/web/sw/gaius-sw.js)
//   portable  the offline single file (port/scripts/build-portable-html.py); modules are
//             inlined before this script and window.__gaiusPortableKernels carries the wasm
//   dev       a plain dist directory over http(s); files are loaded by their logical names
//   file      a plain dist directory opened from disk (file://): nothing can be fetched there,
//             so binary files come from gzip+base64 script payloads next to them
//             (window.__gaiusFilePayloads, written with the WebAssembly GC page)
//
// What it does:
//   - client runtime (design D14): picks the WebAssembly GC client or the JavaScript client.
//     ?runtime=wasm|js|auto, else the page default window.__gaiusRuntimeDefault, else js (until
//     M2). wasm needs a build of it in this distribution, WebAssembly GC, try_table exception
//     handling and JSPI, checked with small WebAssembly.validate modules; anything missing
//     selects js, and the reason goes to __gaiusBoot.runtime / window.__gaiusRuntimeSelection,
//     the "runtime-fallback" event and window.__gaiusBootTimings.runtimeFallback. For wasm it
//     points the launcher's client script at gaius-wasm-boot.js and starts the module source
//     (window.__gaiusWasmSource) at parse time; beforeMain() waits for the instantiated module,
//     so the shared boot timings (classesStart, classesLoaded, clientReady, mainStart,
//     mainReturned, runtime) mean the same for both clients. A module that fails to load or
//     instantiate although the features were there starts the JavaScript client instead
//     (reason "wasm-load-failed:...").
//   - file payloads (file form): the vanilla pack, the shader toolchain modules, the hotpath and
//     the WebAssembly GC client are decoded from their script payloads into the globals the
//     launcher already reads for the portable page (__gaiusVanillaAssetsCompressedPromise,
//     __gaiusShaderToolchainUrls, __gaiusHotpathWasmUrl, __gaiusPortableAssetsReady).
//   - integrated server runtime (WP10): ?serverRuntime=wasm hands the server Worker
//     singleplayer-server.wasm and its runtime scripts in the start message (site, dist over
//     http, and a dist opened from disk, whose Worker gets these files from the page:
//     filePayload, BrowserSingleplayerClient).
//   - asset URLs: __gaiusBoot.assetUrl(name) maps a logical file name (classes.js,
//     kernels/kernel-worker.js, ...) to its content-hashed URL; applyAssetUrls() points the
//     launcher's client, wasm hotpath and singleplayer globals at them.
//   - Service Worker (site over https only): registers gaius-sw.js (cross-origin isolation
//     headers + immutable asset cache) and prunes old generations once the title screen is up.
//     ?gaiusSw=0 unregisters it; ?gaiusCoi=0|credentialless|require-corp picks the COEP mode.
//   - runtime modules: kernel-policy.js, kernel-runtime.js and the kernel job codecs, then the
//     quality layer (runtime/quality/*, owned by the graphics workstream) in its load order.
//     beforeMain() waits for them (bounded) and runs GaiusQuality.caps.ensureTier(). The
//     quality layer reads window.__gaiusProfileId and window.__gaiusRenderSeam, which the
//     launcher sets in <head>.
//   - kernel runtime: window.__gaiusKernels creates GaiusKernelRuntime lazily, feeds it the
//     player position from window.__gaiusMinecraftState for motion prediction, and gives the
//     integrated server Worker a MessagePort into it ({type: "gaius-kernel-port"} right after
//     the singleplayer "start" message) together with the worldgen kernel scripts and the
//     page's worldgen and light switches (?worldgenKernel=0, ?worldgenKernelHost=,
//     ?lightKernel=0 or gaius.lightKernel=off), which that Worker cannot read itself.
//   - lazy singleplayer: the server Worker payload is only loaded when a world starts; while
//     the title screen idles it is prefetched (?gaiusPrefetch=0 turns that off).
//   - sounds on demand (site): the vanilla pack is split into a core pack and a sounds pack;
//     the sounds pack streams in parallel and is merged into window.__gaiusVanillaAssets.
//   - graphics backend (M1 G2): ?gpu=webgpu|webgl2 (default webgl2); for webgpu the WebGPU glue
//     and the shader translator load at parse time and beforeMain() waits (at most 5 s) for the
//     device and the translator, else the game renders with WebGL 2. See "graphics backend".
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
  // Keep all low-memory decisions on one bridge. The quality layer may publish its automatic
  // tier after this script starts, so read the global dynamically and retain the URL override.
  global.__gaiusIsLowMemoryTier = function () {
    return String(params.get("gaiusMemoryTier") || "").toLowerCase() === "low"
      || !!(global.__gaiusMemoryTier && global.__gaiusMemoryTier.lowMemory);
  };
  const nowMs = () => (perf && perf.now ? perf.now() : Date.now());
  const site = global.__gaiusSite && typeof global.__gaiusSite === "object" ? global.__gaiusSite : null;
  // The quality layer caps the canvas ratio by profile. The launcher sets the id in <head>; a
  // site page from an older launcher still names it in its descriptor.
  if (site && typeof site.profile === "string" && site.profile && !global.__gaiusProfileId) {
    global.__gaiusProfileId = site.profile;
  }
  // The portable page sets __gaiusBootPortable before this script (its own bootstrap, which sets
  // __gaiusPortableBuild, runs later in the body).
  // The portable body bootstrap runs after this head script. Its runtime descriptor and
  // payload table are already present, so use them to identify file:// portable pages before
  // __gaiusPortableBuild is published; otherwise dual pages are mistaken for plain file dists.
  const isPortable = () => global.__gaiusPortableBuild === true || global.__gaiusBootPortable === true
    || (isFile && global.__gaiusPortableRuntimes && global.__gaiusFilePayloads);
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

  const KERNEL_RUNTIME_SCRIPTS = ["kernels/kernel-mirror.js", "kernels/kernel-policy.js", "kernels/kernel-runtime.js"];
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

  // A gzip-only WebAssembly GC module of the site (build-pages-site.py): the URL the Service
  // Worker inflates it under, or null. Under the worker the page asks for that URL and gets
  // the stored module as application/wasm (compileStreaming keeps V8's code cache with the
  // stored entry); without one the page inflates assets["<name>.gz"] itself.
  function inflateUrl(name) {
    const inflate = site && site.inflate && typeof site.inflate === "object" ? site.inflate : null;
    return inflate && typeof inflate[name] === "string" && hasAsset(name + ".gz")
      ? new URL(inflate[name], loc.href).href : null;
  }

  function hasModule(name) {
    return hasAsset(name) || inflateUrl(name) !== null;
  }

  function serviceWorkerControlled() {
    return !!(nav.serviceWorker && nav.serviceWorker.controller);
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

  // --- file payloads -------------------------------------------------------------------------

  // A dist opened from disk cannot fetch its siblings, but it can run them as scripts: each
  // binary file the page needs has a <file>.js next to it that stores the file's bytes
  // (gzip-compressed unless the file already is) as base64 in
  // globalThis.__gaiusFilePayloadData[<file>]. window.__gaiusFilePayloads (written into the page
  // with the WebAssembly GC dist) lists them: {<file>: {script, bytes, gzip}}.
  const FILE_PAYLOAD_STORE = "__gaiusFilePayloadData";
  const filePayloadLoads = new Map();

  function filePayloads() {
    const payloads = global.__gaiusFilePayloads;
    // Wasm-only portable pages retain the staged site's __gaiusSite metadata,
    // but their sibling assets are embedded in the document.  Treat the
    // portable form as a file-payload page even when site metadata is present.
    return isFile && (isPortable() || !site) && payloads && typeof payloads === "object" &&
      payloads;
  }

  function hasFilePayload(name) {
    const payloads = filePayloads();
    return !!(payloads && payloads[name] && typeof payloads[name].script === "string");
  }

  function decodeBase64(text) {
    if (typeof Uint8Array.fromBase64 === "function") return Uint8Array.fromBase64(text);
    const binary = global.atob(text);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
  }

  function gunzipStream(bytes) {
    return new global.Blob([bytes]).stream().pipeThrough(new global.DecompressionStream("gzip"));
  }

  // The payload bytes as stored (for a gzip payload: still compressed).
  function filePayloadBytes(name) {
    if (filePayloadLoads.has(name)) return filePayloadLoads.get(name);
    const entry = hasFilePayload(name) ? filePayloads()[name] : null;
    const portableBytes = global.__gaiusPortablePayloadBytes && global.__gaiusPortablePayloadBytes[name];
    if (portableBytes) {
      const promise = Promise.resolve(portableBytes).then((value) => value instanceof Uint8Array ? value : new Uint8Array(value));
      filePayloadLoads.set(name, promise);
      return promise;
    }
    const inline = global[FILE_PAYLOAD_STORE] && global[FILE_PAYLOAD_STORE][name];
    const promise = inline ? Promise.resolve(decodeBase64(inline)) : (!entry || !doc || !doc.createElement
      ? Promise.reject(new Error("This page has no file payload for " + name))
      : new Promise((resolve, reject) => {
        const element = doc.createElement("script");
        element.src = entry.script;
        element.async = true;
        element.setAttribute("data-gaius-file-payload", name);
        element.onload = () => {
          const store = global[FILE_PAYLOAD_STORE] || {};
          const text = store[name];
          // The text can be tens of megabytes: keep no reference once it is decoded.
          delete store[name];
          if (element.parentNode) element.parentNode.removeChild(element);
          if (typeof text !== "string") {
            reject(new Error(entry.script + " did not provide " + name));
            return;
          }
          try {
            resolve(decodeBase64(text));
          } catch (error) {
            reject(error);
          }
        };
        element.onerror = () => reject(new Error("Could not load " + entry.script));
        (doc.head || doc.documentElement).appendChild(element);
      }));
    promise.catch((error) => record("file-payload-failed", name + ": " + (error && error.message)));
    filePayloadLoads.set(name, promise);
    return promise;
  }

  // The file itself (inflated when its payload is gzip), as a Blob URL of the given type.
  function filePayloadUrl(name, type) {
    return filePayloadBytes(name).then((bytes) => {
      const entry = (filePayloads() && filePayloads()[name]) || {gzip: false};
      const body = entry.gzip ? new global.Response(gunzipStream(bytes)).blob() : new global.Blob([bytes]);
      return Promise.resolve(body).then((blob) => {
        const url = global.URL.createObjectURL(new global.Blob([blob], {type}));
        (global.__gaiusPortablePayloadUrls || (global.__gaiusPortablePayloadUrls = {}))[name] = url;
        return url;
      });
    });
  }

  // The file itself as bytes the caller owns (it may transfer them): the integrated server Worker
  // of a page opened from disk cannot read its siblings, so BrowserSingleplayerClient passes it
  // the bootstrap, the server and its runtime scripts read this way.
  function filePayload(name) {
    return filePayloadBytes(name).then((bytes) => {
      if (!filePayloads()[name].gzip) return bytes.slice();
      return new global.Response(gunzipStream(bytes)).arrayBuffer().then((buffer) => new Uint8Array(buffer));
    });
  }

  // Started at parse time, before the launcher reads these globals: its vanilla pack loader takes
  // the compressed pack as from the portable page, the hotpath and the shader toolchain loader
  // wait for __gaiusPortableAssetsReady and then read their (Blob) URLs.
  function wireFilePayloads() {
    if (!filePayloads() || typeof global.DecompressionStream !== "function") return;
    const waits = [];
    if (hasFilePayload("vanilla-assets.pack.gz") && !global.__gaiusVanillaAssetsCompressedPromise) {
      global.__gaiusVanillaAssetsCompressedPromise = filePayloadBytes("vanilla-assets.pack.gz");
    }
    if (hasFilePayload("gaius-shaderc.js") && hasFilePayload("gaius-spvc.js")
        && hasFilePayload("gaius-shaderc.wasm") && hasFilePayload("gaius-spvc.wasm")
        && !global.__gaiusShaderToolchainUrls) {
      const urls = {};
      const modules = [
        filePayloadUrl("gaius-shaderc.js", "text/javascript").then((url) => { urls.shadercJs = url; }),
        filePayloadUrl("gaius-spvc.js", "text/javascript").then((url) => { urls.spvcJs = url; }),
        filePayloadUrl("gaius-shaderc.wasm", "application/wasm").then((url) => { urls.shadercWasm = url; }),
        filePayloadUrl("gaius-spvc.wasm", "application/wasm").then((url) => { urls.spvcWasm = url; }),
      ];
      // Published once complete: the toolchain loader reads it after __gaiusPortableAssetsReady.
      waits.push(Promise.all(modules).then(() => { global.__gaiusShaderToolchainUrls = urls; }));
    }
    if (hasFilePayload("gaius-hotpath.wasm") && !global.__gaiusHotpathWasmUrl) {
      waits.push(filePayloadUrl("gaius-hotpath.wasm", "application/wasm").then((url) => { global.__gaiusHotpathWasmUrl = url; }));
    }
    if (waits.length && !global.__gaiusPortableAssetsReady) {
      global.__gaiusPortableAssetsReady = Promise.all(waits).then(() => {
        mark("file-payloads");
      });
    }
  }

  // --- client runtime ---------------------------------------------------------------------------

  const RUNTIMES = ["js", "wasm", "auto"];
  const WASM_CLIENT_FILES = ["classes.wasm", "gaius-wasm-boot.js", "gaius-wasm-gc-runtime.js", "gaius-jspi.js"];
  const WASM_SERVER_FILES = ["singleplayer-server.wasm", "gaius-wasm-gc-runtime.js", "gaius-jspi.js", "gaius-wasm-worker.js"];
  // WebAssembly.validate probes for what the TeaVM WebAssembly GC output needs: a struct type
  // created with struct.new (GC) and a try_table with catch_all (exception handling with exnref).
  const WASM_GC_PROBE = [0, 97, 115, 109, 1, 0, 0, 0, 1, 10, 2, 95, 1, 127, 0, 96, 0, 1, 99, 0,
    3, 2, 1, 1, 10, 9, 1, 7, 0, 65, 0, 251, 0, 0, 11];
  const WASM_TRY_TABLE_PROBE = [0, 97, 115, 109, 1, 0, 0, 0, 1, 4, 1, 96, 0, 0, 3, 2, 1, 0,
    10, 10, 1, 8, 0, 31, 64, 1, 2, 0, 11, 11];

  function validates(wasm, probe) {
    try {
      return wasm.validate(new Uint8Array(probe)) === true;
    } catch (_) {
      return false;
    }
  }

  function detectWasmFeatures() {
    const wasm = global.WebAssembly;
    const features = {
      webAssembly: !!wasm && typeof wasm.validate === "function",
      gc: false,
      tryTable: false,
      jspi: false,
      decompressionStream: typeof global.DecompressionStream === "function",
    };
    if (!features.webAssembly) return features;
    features.gc = validates(wasm, WASM_GC_PROBE);
    features.tryTable = validates(wasm, WASM_TRY_TABLE_PROBE);
    features.jspi = typeof wasm.Suspending === "function" && typeof wasm.promising === "function";
    return features;
  }

  function distributionForm() {
    if (isPortable()) return "portable";
    if (site) return "site";
    return isFile ? "file" : "dev";
  }

  // The window.__gaiusRuntimes descriptor of a dist: {wasm: {client, server?}}.
  function distRuntimes() {
    const runtimes = global.__gaiusRuntimes;
    return runtimes && typeof runtimes === "object" && runtimes.wasm && typeof runtimes.wasm === "object"
      ? runtimes.wasm : null;
  }

  function wasmClientBuilt(form) {
    if (form === "site") return WASM_CLIENT_FILES.every(hasModule);
    if (form === "portable") return !!(global.__gaiusPortableRuntimes && global.__gaiusPortableRuntimes.wasm);
    if (!distRuntimes()) return false;
    // From disk the module only arrives through its script payload.
    return form !== "file" || hasFilePayload("classes.wasm");
  }

  function missingWasmFeatures(features, inflatesOnPage) {
    if (!features.webAssembly) return ["webassembly"];
    const missing = [];
    if (!features.gc) missing.push("gc");
    if (!features.tryTable) missing.push("try-table");
    if (!features.jspi) missing.push("jspi");
    if (inflatesOnPage && !features.decompressionStream) missing.push("decompression-stream");
    return missing;
  }

  function selectRuntime() {
    const fromUrl = String(params.get("runtime") || "").toLowerCase();
    const fromPage = typeof global.__gaiusRuntimeDefault === "string" ? global.__gaiusRuntimeDefault.toLowerCase() : "";
    let requested = site && (site.runtimePolicy === "auto" || site.runtimePolicy === "wasm-only") ? "auto" : "js";
    let source = site && site.runtimePolicy === "auto" ? "site" : "default";
    if (RUNTIMES.indexOf(fromUrl) >= 0) {
      requested = fromUrl;
      source = "url";
    } else if (source !== "site" && RUNTIMES.indexOf(fromPage) >= 0) {
      requested = fromPage;
      source = "page";
    }
    if (fromUrl && source !== "url") record("runtime-invalid", fromUrl);
    const form = distributionForm();
    const features = detectWasmFeatures();
    const built = wasmClientBuilt(form);
    const selection = {
      requested, source, selected: "js", fallback: false, reason: source + "-js", form, wasmBuilt: built,
      features, at: nowMs(),
    };
    if (site && site.runtimePolicy === "wasm-only") {
      selection.requested = "wasm";
      selection.source = "wasm-only";
      if (fromUrl === "js") {
        selection.selected = "unsupported";
        selection.reason = "wasm-only-js-request";
        return selection;
      }
    }
    if (requested === "js") return selection;
    const missing = built ? [] : ["wasm-build"];
    // The file and portable forms inflate the module on the page, and so does a site page of a
    // gzip-only module that no Service Worker controls.
    missing.push(...missingWasmFeatures(features, form === "file" || form === "portable"
      || (form === "site" && inflateUrl("classes.wasm") !== null && !serviceWorkerControlled())));
    if (missing.length === 0) {
      selection.selected = "wasm";
      selection.reason = source + "-" + requested;
    } else {
      if (site && site.runtimePolicy === "wasm-only") {
        selection.selected = "unsupported";
        selection.reason = "wasm-only-missing:" + missing.join(",");
        return selection;
      }
      selection.fallback = true;
      selection.reason = "missing:" + missing.join(",");
    }
    return selection;
  }

  const clientRuntime = selectRuntime();
  global.__gaiusRuntimeSelection = clientRuntime;
  record("runtime", clientRuntime.selected + " (" + clientRuntime.reason + ")");
  if (clientRuntime.fallback) {
    record("runtime-fallback", clientRuntime.reason);
    if (global.console && typeof global.console.warn === "function") {
      global.console.warn("Gaius: the WebAssembly GC client is unavailable (" + clientRuntime.reason
        + "); starting the JavaScript client");
    }
  }
  if (clientRuntime.selected === "unsupported") {
    const message = "This acceptance build requires WebAssembly GC and JSPI; this browser is unsupported.";
    record("runtime-unsupported", clientRuntime.reason);
    if (global.console && typeof global.console.error === "function") global.console.error("Gaius: " + message);
    if (doc && doc.body) doc.body.setAttribute("data-gaius-unsupported", "wasm-only");
    throw new Error(message);
  }
  mark("runtime-" + clientRuntime.selected);

  function siblingUrl(name) {
    return new URL(assetUrl(name) || name, loc.href).href;
  }

  // A Response of the module type: the loader compiles it while it streams.
  function wasmResponse(body) {
    return new global.Response(body, {headers: {"Content-Type": "application/wasm"}});
  }

  function fetchWasm(url, gzip) {
    return global.fetch(url, site ? {cache: "force-cache"} : {}).then((response) => {
      if (!response.ok || !response.body) {
        throw new Error("HTTP " + response.status + " while loading the WebAssembly GC client");
      }
      if (gzip) return wasmResponse(response.body.pipeThrough(new global.DecompressionStream("gzip")));
      // The fetched Response itself keeps V8's code cache for streamed modules; a host that does
      // not serve .wasm as application/wasm gets the body rewrapped (compileStreaming needs it).
      const type = response.headers && response.headers.get ? String(response.headers.get("Content-Type") || "") : "";
      return type.indexOf("application/wasm") === 0 ? response : wasmResponse(response.body);
    });
  }

  // The portable page decodes its embedded module itself (build-portable-html.py); every other
  // form loads the loader scripts by URL and starts the module now, while storage initialises.
  function wireWasmClient() {
    if (clientRuntime.form === "portable") return;
    global.__gaiusClassesUrl = siblingUrl("gaius-wasm-boot.js");
    global.__gaiusWasmRuntimeUrl = siblingUrl("gaius-wasm-gc-runtime.js");
    global.__gaiusJspiUrl = siblingUrl("gaius-jspi.js");
    let source;
    if (clientRuntime.form === "file") {
      // Inflated while it compiles; no fetch from disk.
      source = filePayloadBytes("classes.wasm").then((bytes) =>
        filePayloads()["classes.wasm"].gzip ? wasmResponse(gunzipStream(bytes)) : bytes);
    } else if (inflateUrl("classes.wasm")) {
      const inflateOnPage = () => fetchWasm(siblingUrl("classes.wasm.gz"), true);
      if (serviceWorkerControlled()) {
        record("wasm-inflate", "service-worker");
        source = fetchWasm(inflateUrl("classes.wasm"), false).catch((error) => {
          record("wasm-inflate-fallback", error && error.message);
          return inflateOnPage();
        });
      } else {
        record("wasm-inflate", "page");
        source = inflateOnPage();
      }
    } else if (useGzip && hasAsset("classes.wasm.gz") && typeof global.DecompressionStream === "function") {
      record("wasm-gzip-fallback", assetUrl("classes.wasm.gz"));
      source = fetchWasm(siblingUrl("classes.wasm.gz"), true);
    } else {
      source = fetchWasm(siblingUrl("classes.wasm"), false);
    }
    source.catch(() => {});
    global.__gaiusWasmSource = source;
  }

  if (clientRuntime.form === "file" || clientRuntime.form === "portable") wireFilePayloads();

  // Early (parse-time) URLs: the wasm hotpath loader and the client script read these.
  if (site) {
    if (hasAsset("gaius-hotpath.wasm") && !global.__gaiusHotpathWasmUrl) {
      global.__gaiusHotpathWasmUrl = new URL(assetUrl("gaius-hotpath.wasm"), loc.href).href;
    }
    if (clientRuntime.selected === "wasm") {
      // The WebAssembly GC client replaces classes.js (wireWasmClient below).
    } else if (useGzip && hasAsset("classes.js.gz") && typeof global.DecompressionStream === "function") {
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
  if (clientRuntime.selected === "wasm") wireWasmClient();
  if (clientRuntime.form === "portable" && clientRuntime.selected === "js"
      && typeof global.__gaiusPortableLoadJsClient === "function") {
    assetsReadyPromise = global.__gaiusPortableLoadJsClient().then((url) => {
      global.__gaiusClassesUrl = url;
    });
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
    } else if (isPortable() || (isFile && !site && name.indexOf("runtime/quality/") !== 0
        && name.indexOf("runtime/webgpu/") !== 0)) {
      // A portable page inlines what it has. A dist opened from disk has no kernels (no Worker
      // from a file: URL, no kernels.json fetch), so their modules are not loaded there; the
      // quality layer and the WebGPU glue are plain script and load by tag like classes.js does.
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

  // --- graphics backend (DESIGN D10, M1 G2) -----------------------------------------------------
  //
  // ?gpu=webgpu|webgl2, else the page default window.__gaiusGpuDefault, else webgl2 (until the M4
  // A/B decides the default). For webgpu the page loads the WebGPU glue and the shader translator
  // (runtime/webgpu/: gpu-glue.js, shaderxlat-job.js, gaius-shaderxlat.wasm) and beforeMain()
  // waits, at most WEBGPU_INIT_TIMEOUT_MS, for the adapter, the device, the canvas format and the
  // translator. Java then picks the backend: the profile's render seam (Minecraft262BrowserPatcher.
  // patchPreferredGraphicsApi) tries WebGPU first and vanilla falls back to WebGL 2 when the
  // device is not ready. window.__gaiusGpuSelection / __gaiusBoot.gpu say what the page offered,
  // window.__gaiusGpuBackend ("webgpu" or "webgl2") what the game renders with.
  //   ?gpuInject=after-configure  fallback test: WebGPU fails after configuring the canvas, so
  //                               WebGL 2 must attach to a fresh canvas (PLAN-DELTA 3.4)
  //   ?gpuInject=taint-canvas     the same for profiles without a WebGPU backend: the canvas holds
  //                               a "webgpu" context before the GL window is made
  //   ?gpuInject=lose-device      device-loss test: the glue destroys the device the game renders
  //                               with 3 s after the first frame, so the reload prompt must appear
  //   ?gpuPipelines=eager|lazy    pipeline creation of the glue (A/B runs; default lazy)
  //   ?gpuAllowFallback=1         accept a fallback (software) adapter, e.g. SwiftShader in CI
  //
  // A profile whose WebGPU backend compiles GLSL itself (the blaze3d front-end of 26.2 and
  // 1.21.11: shaderc in the browser, then the translator's link) also needs the shader toolchain
  // (gaius-shader-toolchain.js and its four modules next to index.html). Such a page names the
  // loader in window.__gaiusWebGpuShaderToolchain = {loader: "gaius-shader-toolchain.js?v=..."}
  // (make-wasm-page.py); it loads for webgpu only, at parse time, and beforeMain() waits for it
  // within the same budget. A page that loads the toolchain for every backend (renderpearl,
  // postprocess-index-html.py) already has window.__gaiusShaderToolchainReady.

  const GPUS = ["webgpu", "webgl2"];
  const GPU_INJECTIONS = ["after-configure", "taint-canvas", "lose-device"];
  const WEBGPU_SCRIPTS = ["runtime/webgpu/gpu-glue.js", "runtime/webgpu/shaderxlat-job.js",
    "runtime/webgpu/quality-post.js"];
  const WEBGPU_TRANSLATOR = "runtime/webgpu/gaius-shaderxlat.wasm";
  const WEBGPU_INIT_TIMEOUT_MS = 5000;

  function selectGpu() {
    const fromUrl = String(params.get("gpu") || "").toLowerCase();
    const fromPage = typeof global.__gaiusGpuDefault === "string" ? global.__gaiusGpuDefault.toLowerCase() : "";
    const automatic = site && site.runtimePolicy === "auto";
    const available = clientRuntime.selected === "wasm" && global.navigator && global.navigator.gpu
      && typeof global.navigator.gpu.requestAdapter === "function"
      && WEBGPU_SCRIPTS.every(hasModule) && hasModule(WEBGPU_TRANSLATOR);
    let requested = automatic && available ? "webgpu" : "webgl2";
    let source = automatic ? "site" : "default";
    if (GPUS.indexOf(fromUrl) >= 0) {
      requested = fromUrl;
      source = "url";
    } else if (!automatic && GPUS.indexOf(fromPage) >= 0) {
      requested = fromPage;
      source = "page";
    }
    if (fromUrl && source !== "url") record("gpu-invalid", fromUrl);
    const inject = String(params.get("gpuInject") || "").toLowerCase();
    const pipelines = String(params.get("gpuPipelines") || "").toLowerCase();
    return {
      requested, source, selected: requested === "webgpu" ? "pending" : "webgl2", fallback: false,
      reason: source + "-" + requested, inject: GPU_INJECTIONS.indexOf(inject) >= 0 ? inject : "",
      pipelines: pipelines === "eager" || pipelines === "lazy" ? pipelines : "",
      allowFallbackAdapter: params.get("gpuAllowFallback") === "1",
      initMs: null, at: nowMs(),
      backend: () => (typeof global.__gaiusGpuBackend === "string" ? global.__gaiusGpuBackend : null),
    };
  }

  const gpuSelection = selectGpu();
  global.__gaiusGpuSelection = gpuSelection;
  record("gpu", gpuSelection.requested + " (" + gpuSelection.source + ")"
    + (gpuSelection.inject ? " inject=" + gpuSelection.inject : ""));
  const webGpuWanted = gpuSelection.requested === "webgpu" || gpuSelection.inject !== "";
  // Started at parse time, like the quality layer: these download while classes load.
  const webGpuReady = webGpuWanted ? Promise.all(WEBGPU_SCRIPTS.map(loadScript)) : Promise.resolve([]);

  // The blaze3d front-end's shader toolchain (see above): resolves to "loaded" once
  // window.__gaiusShaderToolchainReady resolved, "page" when the page loads it itself, "none" when
  // this page needs none; rejects when it cannot be loaded.
  function webGpuShaderToolchainConfig() {
    const config = global.__gaiusWebGpuShaderToolchain;
    return config && typeof config === "object" && typeof config.loader === "string" && config.loader ? config : null;
  }

  function loadWebGpuShaderToolchain() {
    if (global.__gaiusShaderToolchainReady) return Promise.resolve("page");
    const config = webGpuShaderToolchainConfig();
    if (!config) return Promise.resolve("none");
    if (isPortable() || !doc || !doc.createElement) {
      return Promise.reject(new Error("this page form carries no shader toolchain"));
    }
    let url = config.loader;
    if (site) {
      const loader = assetUrl("gaius-shader-toolchain.js");
      const modules = {shadercJs: "gaius-shaderc.js", shadercWasm: "gaius-shaderc.wasm",
        spvcJs: "gaius-spvc.js", spvcWasm: "gaius-spvc.wasm"};
      const urls = {};
      for (const key of Object.keys(modules)) urls[key] = assetUrl(modules[key]);
      if (!loader || Object.keys(urls).some((key) => !urls[key])) {
        return Promise.reject(new Error("the site carries no shader toolchain"));
      }
      // Content-hashed names: the loader cannot find its modules as siblings.
      if (!global.__gaiusShaderToolchainUrls) {
        urls.version = String(config.version || loader);
        global.__gaiusShaderToolchainUrls = urls;
      }
      url = loader;
    }
    return new Promise((resolve, reject) => {
      const element = doc.createElement("script");
      element.src = url;
      element.async = false;
      // The loader names its IndexedDB result cache after the profile.
      const profile = typeof global.__gaiusProfileId === "string" ? global.__gaiusProfileId : "";
      if (/^[A-Za-z0-9._+-]+$/.test(profile)) element.setAttribute("data-profile", profile);
      element.setAttribute("data-gaius-boot-module", "gaius-shader-toolchain.js");
      element.onload = () => {
        if (global.__gaiusShaderToolchainReady) resolve(true);
        else reject(new Error(url + " did not start the shader toolchain"));
      };
      element.onerror = () => reject(new Error("could not load " + url));
      (doc.head || doc.documentElement).appendChild(element);
    }).then(() => global.__gaiusShaderToolchainReady).then(() => "loaded");
  }

  // Started at parse time for webgpu, next to the glue (a 4 MB download and two compiles).
  const webGpuShaderToolchain = gpuSelection.requested === "webgpu" && gpuSelection.inject !== "after-configure"
    ? loadWebGpuShaderToolchain() : null;
  if (webGpuShaderToolchain) webGpuShaderToolchain.catch(() => {});

  function webGpuInitOptions() {
    return {
      inject: gpuSelection.inject === "after-configure" || gpuSelection.inject === "lose-device"
        ? gpuSelection.inject : undefined,
      pipelineMode: gpuSelection.pipelines || undefined,
      allowFallback: gpuSelection.allowFallbackAdapter,
    };
  }

  // The adapter and the device are requested as soon as the glue is there, while the client
  // loads (init takes no canvas context; the canvas is found when the surface is configured).
  // The after-configure test needs the canvas at init, so it starts in beforeMain().
  if (gpuSelection.requested === "webgpu" && gpuSelection.inject !== "after-configure") {
    webGpuReady.then(() => {
      const gpu = global.GaiusWebGpu;
      if (gpu && typeof gpu.init === "function") {
        gpu.requested = true;
        requestWebGpu(gpu);
      }
    });
  }

  function requestWebGpu(gpu) {
    const failed = (error) => {
      const reason = "adapter/device: " + String(error && error.message || error);
      if (typeof gpu.abandon === "function") gpu.abandon(reason);
      return false;
    };
    try {
      return Promise.resolve(gpu.init(
        doc && doc.getElementById ? doc.getElementById("mc-canvas") : null, webGpuInitOptions())).catch(failed);
    } catch (error) {
      return Promise.resolve(failed(error));
    }
  }

  function webGpuFallback(reason) {
    gpuSelection.selected = "webgl2";
    gpuSelection.fallback = true;
    gpuSelection.reason = String(reason || "unavailable").slice(0, 300);
    record("gpu-fallback", gpuSelection.reason);
    if (global.console && typeof global.console.warn === "function") {
      global.console.warn("Gaius: WebGPU is unavailable (" + gpuSelection.reason + "); rendering with WebGL 2");
    }
  }

  // The translator module's bytes or Response for this distribution form, or null.
  function translatorSource() {
    if (isPortable()) {
      const embedded = global.__gaiusPortableWebGpu;
      return embedded && embedded.translator && typeof embedded.translator.load === "function"
        ? Promise.resolve(embedded.translator.load()) : null;
    }
    if (isFile && !site) {
      if (!hasFilePayload(WEBGPU_TRANSLATOR)) return null;
      return filePayloadBytes(WEBGPU_TRANSLATOR).then((bytes) =>
        filePayloads()[WEBGPU_TRANSLATOR].gzip ? wasmResponse(gunzipStream(bytes)) : bytes);
    }
    if (site && !hasAsset(WEBGPU_TRANSLATOR) && !hasAsset(WEBGPU_TRANSLATOR + ".gz")) return null;
    if (site && useGzip && hasAsset(WEBGPU_TRANSLATOR + ".gz") && typeof global.DecompressionStream === "function") {
      return fetchWasm(siblingUrl(WEBGPU_TRANSLATOR + ".gz"), true);
    }
    return fetchWasm(siblingUrl(WEBGPU_TRANSLATOR), false);
  }

  function compileTranslator() {
    const source = translatorSource();
    if (!source || !global.WebAssembly) return Promise.resolve(null);
    return source.then((body) => {
      const wasm = global.WebAssembly;
      if (body instanceof global.Response) {
        return typeof wasm.compileStreaming === "function"
          ? wasm.compileStreaming(body) : body.arrayBuffer().then((bytes) => wasm.compile(bytes));
      }
      return wasm.compile(body);
    }).catch((error) => {
      record("gpu-translator-failed", error && error.message);
      return null;
    });
  }

  // Where the prefetch Worker gets the job codec: its URL, or the inline text of a portable page.
  function translatorJobSource() {
    const name = WEBGPU_SCRIPTS[1];
    if (isPortable()) {
      const element = doc && doc.querySelector ? doc.querySelector('script[data-gaius-boot-module="' + name + '"]') : null;
      return element && element.textContent ? {jobSource: element.textContent} : null;
    }
    if (isFile && !site) return null;
    return {jobUrl: siblingUrl(name)};
  }

  // Bounded by WEBGPU_INIT_TIMEOUT_MS; never throws. Leaves GaiusWebGpu ready (state 2) or failed.
  async function prepareGpu() {
    if (!webGpuWanted) return;
    const started = nowMs();
    mark("webgpu-start");
    const deadline = started + WEBGPU_INIT_TIMEOUT_MS;
    const left = () => Math.max(0, deadline - nowMs());
    const translator = gpuSelection.requested === "webgpu" ? compileTranslator() : Promise.resolve(null);
    await withTimeout(webGpuReady, left());
    const gpu = global.GaiusWebGpu;
    if (!gpu || typeof gpu.init !== "function") {
      if (gpuSelection.requested === "webgpu") webGpuFallback("glue-missing");
      return;
    }
    if (gpuSelection.inject === "taint-canvas") {
      record("gpu-inject", "taint-canvas " + (gpu.taintCanvas() ? "done" : "failed"));
    }
    if (gpuSelection.requested !== "webgpu") return;
    gpu.requested = true;
    const timedOut = {};
    const ready = await Promise.race([
      // The same promise when the early request above already started it.
      requestWebGpu(gpu),
      new Promise((resolve) => global.setTimeout(() => resolve(timedOut), left())),
    ]);
    let failure = "";
    if (ready === timedOut) {
      failure = "init timed out after " + WEBGPU_INIT_TIMEOUT_MS + " ms";
    } else if (ready !== true) {
      failure = gpu.failure || "init failed";
    } else {
      const module = await Promise.race([translator, new Promise((resolve) => global.setTimeout(() => resolve(timedOut), left()))]);
      if (module === timedOut) {
        failure = "translator timed out after " + WEBGPU_INIT_TIMEOUT_MS + " ms";
      } else if (!module) {
        failure = "translator unavailable (" + WEBGPU_TRANSLATOR + ")";
      } else if (!gpu.installTranslator(module, translatorJobSource())) {
        failure = "translator: " + (gpu.translateError || "install failed");
      }
    }
    if (!failure) {
      const toolchain = (webGpuShaderToolchain || loadWebGpuShaderToolchain()).catch(
        (error) => ({failure: "shader toolchain: " + (error && error.message ? error.message : error)}));
      const state = await Promise.race([toolchain, new Promise((resolve) => global.setTimeout(() => resolve(timedOut), left()))]);
      if (state === timedOut) {
        failure = "shader toolchain timed out after " + WEBGPU_INIT_TIMEOUT_MS + " ms";
      } else if (state && state.failure) {
        failure = state.failure;
      } else if (state === "loaded") {
        record("webgpu-shader-toolchain", "loaded");
      }
    }
    gpuSelection.initMs = Math.round(nowMs() - started);
    if (failure) {
      // The seam reads the glue's state: a refused device sends vanilla on to WebGL 2.
      if (typeof gpu.abandon === "function") gpu.abandon(failure);
      webGpuFallback(failure);
    } else {
      gpuSelection.selected = "webgpu";
      record("webgpu-ready", gpuSelection.initMs + " ms");
    }
    mark("webgpu-" + gpuSelection.selected);
  }

  function publishGpuTimings() {
    const timings = global.__gaiusBootTimings;
    if (!timings || typeof timings !== "object") return;
    timings.gpu = gpuSelection.selected;
    if (gpuSelection.initMs !== null) timings.gpuInitMs = gpuSelection.initMs;
    if (gpuSelection.fallback) timings.gpuFallback = gpuSelection.reason;
    else delete timings.gpuFallback;
  }

  function loadClassicScript(url) {
    return new Promise((resolve, reject) => {
      const element = doc.createElement("script");
      element.src = url;
      element.onload = () => resolve();
      element.onerror = () => reject(new Error("Could not load " + url));
      (doc.body || doc.head || doc.documentElement).appendChild(element);
    });
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

  // Low-memory sessions keep sounds in a separate indexed pack. AssetArchive checks this
  // sidecar after the core pack, so Java retains synchronous reads without a second merged copy.
  function publishSoundPack(soundBytes) {
    const pack = parsePack(soundBytes);
    let resourceCount = 0;
    for (const name in pack.index) {
      if (Object.prototype.hasOwnProperty.call(pack.index, name)) resourceCount++;
    }
    global.__gaiusVanillaSounds = {
      bytes: soundBytes,
      index: pack.index,
      dataOffset: pack.dataOffset,
      resourceCount
    };
    return resourceCount;
  }

  function splitSoundPackRequested() {
    if (!clientRuntime || clientRuntime.selected !== "wasm") return false;
    return String(params.get("gaiusSounds") || "").toLowerCase() === "split"
      || global.__gaiusIsLowMemoryTier();
  }

  // The launcher defines __gaiusVanillaAssetsReady after this script ran; wait for it to exist,
  // then for the core pack to be decoded.
  function vanillaAssetsDecoded() {
    return new Promise((resolve) => {
      const poll = () => {
        const ready = global.__gaiusVanillaAssetsReady;
        if (ready && typeof ready.then === "function") {
          ready.then((value) => {
            // The launcher has parsed the pack at this point. Drop the compressed payload
            // promise so its temporary response bytes can be collected while the indexed
            // decoded pack remains available to Java.
            try { global.__gaiusVanillaAssetsCompressedPromise = null; } catch (_) {}
            resolve(value);
          }, () => resolve(null));
        }
        else global.setTimeout(poll, 50);
      };
      poll();
    });
  }

  let soundsState = {mode: "none", merged: false, layout: "none", error: null, resources: 0};
  let soundsGate = Promise.resolve();
  let soundsReady = Promise.resolve();

  if (site && hasAsset("vanilla-sounds.pack") && !isPortable()) {
    const requested = params.get("gaiusSounds");
    const mode = requested === "eager" || requested === "deferred" ? requested
      : (typeof global.__gaiusSoundReloadHook === "function" ? "deferred" : "eager");
    soundsState = {mode, merged: false, layout: "pending", error: null, resources: 0};
    const soundBytes = global.fetch(assetUrl("vanilla-sounds.pack"), {cache: "force-cache"}).then((response) => {
      if (!response.ok) throw new Error("HTTP " + response.status + " while loading the sound pack");
      return response.arrayBuffer();
    });
    const merge = Promise.all([soundBytes, vanillaAssetsDecoded()])
      .then((parts) => {
        mark("asset-pack-decode");
        const root = global.__gaiusVanillaAssets || parts[1];
        if (!root || !root.bytes || !root.index) throw new Error("the core asset pack is not decoded");
        const soundBytes = new Uint8Array(parts[0]);
        if (splitSoundPackRequested()) {
          soundsState.resources = publishSoundPack(soundBytes);
          soundsState.layout = "split";
        } else {
          soundsState.resources = mergeSoundPack(root, soundBytes);
          soundsState.layout = "merged";
        }
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
    soundsReady = merge;
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

  // --- integrated server Worker ---------------------------------------------------------------

  // The server Worker sees neither the page URL nor its storage, so the page passes the kernel
  // switches and the scripts that Worker imports before its kernel client exists along with the
  // port (server-worker-bootstrap.js loadKernelHostScripts).
  const SERVER_KERNEL_SCRIPTS = ["kernels/worldgen-job.js", "kernels/worldgen-kernel.js"];
  const OFF_VALUE = /^(0|off|false|no)$/i;

  // "worker": the server Worker runs its own worldgen kernel workers; "shared": worldgen only
  // goes through this page's runtime; empty: the facade decides (auto).
  function worldgenHostMode() {
    const value = String(params.get("worldgenKernelHost") || "").toLowerCase();
    return value === "worker" || value === "shared" ? value : "";
  }

  // [{name, url}] for a site or dist over http(s), [{name, source}] from the inert copies a
  // portable page carries (build-portable-html.py), nothing for a dist opened from disk.
  function serverWorkerKernelScripts() {
    const names = (worldgenHostMode() === "worker" ? KERNEL_RUNTIME_SCRIPTS : ["kernels/kernel-mirror.js"]).concat(SERVER_KERNEL_SCRIPTS);
    if (isPortable()) {
      const out = [];
      for (const name of names) {
        const element = doc && doc.querySelector
          ? doc.querySelector('script[type="text/plain"][data-gaius-worker-script="' + name + '"]')
          : null;
        if (element && element.textContent) out.push({name, source: element.textContent});
      }
      return out;
    }
    if (isFile && !site) return [];
    const out = [];
    for (const name of names) {
      // A site that does not list a script never had it: the Worker keeps the vanilla path.
      if (site && !hasAsset(name)) continue;
      out.push({name, url: new URL(assetUrl(name) || name, loc.href).href});
    }
    return out;
  }

  function serverWorldgenConfig() {
    const config = {};
    const value = params.get("worldgenKernel");
    if (value !== null && OFF_VALUE.test(value)) config.enabled = false;
    const host = worldgenHostMode();
    if (host) config.host = host;
    return config;
  }

  // ?lightKernel=0 (also lightkernel, off/false/no) or the stored gaius.lightKernel=off setting.
  function serverLightConfig() {
    const value = params.get("lightKernel") !== null ? params.get("lightKernel") : params.get("lightkernel");
    const off = (value !== null && OFF_VALUE.test(value)) || storageGet("gaius.lightKernel") === "off";
    return {enabled: !off};
  }

  // ?worldgenKernelHost=worker: the server Worker starts its own kernel runtime, so it needs the
  // worldgen modules and the kernel worker script itself. Portable modules travel as bytes
  // (copied, the page runtime keeps its own), URLs stay URLs.
  async function serverWorldgenHost() {
    try {
      const manifest = await kernelManifest();
      const entry = manifest && manifest.kernels ? manifest.kernels.worldgen : null;
      if (!entry || !manifest.worker) return null;
      const variants = {};
      for (const variant of Object.keys(entry.variants || {})) {
        const source = entry.variants[variant];
        if (source && source.url) variants[variant] = {url: source.url};
        else if (source && typeof source.load === "function") variants[variant] = {bytes: await source.load()};
      }
      if (Object.keys(variants).length === 0) return null;
      const host = {kernels: {worldgen: {kinds: entry.kinds || [], memory: entry.memory, variants}}};
      if (manifest.worker.workerUrl) host.workerUrl = manifest.worker.workerUrl;
      else if (manifest.worker.workerSource) host.workerSource = manifest.worker.workerSource;
      else return null;
      return host;
    } catch (error) {
      record("worldgen-host-config-failed", error && error.message);
      return null;
    }
  }

  // The inert copy of a WebAssembly GC runtime script a portable page carries
  // (build-portable-html.py data-gaius-wasm-script), or null.
  function portableWasmScript(name) {
    const element = doc && doc.querySelector
      ? doc.querySelector('script[type="text/plain"][data-gaius-wasm-script="' + name + '"]')
      : null;
    return element && element.textContent ? element.textContent : null;
  }

  function portableWasmServerBuilt() {
    const runtimes = global.__gaiusPortableRuntimes;
    return !!(runtimes && runtimes.wasm && typeof runtimes.wasm.server === "string")
      && WASM_SERVER_FILES.slice(1).every((name) => portableWasmScript(name) !== null);
  }

  // The server module of a site: a gzip-only one under the Service Worker by its inflated URL,
  // otherwise its .gz, which gaius-wasm-worker.js inflates in the Worker.
  function siteServerWasmUrl() {
    const inflate = inflateUrl("singleplayer-server.wasm");
    if (inflate === null) return siblingUrl("singleplayer-server.wasm");
    return serviceWorkerControlled() ? inflate : siblingUrl("singleplayer-server.wasm.gz");
  }

  // ?serverRuntime=wasm (WP10): the start message names the integrated server module and the
  // runtime scripts the Worker imports (server-worker-bootstrap.js, gaius-wasm-worker.js). Site,
  // dist over http, and a dist opened from disk with file payloads for the module and the
  // scripts: that Worker finds them by name among the files the page hands it
  // (BrowserSingleplayerClient, filePayload). A portable page that embeds the module gets the
  // scripts as sources here; its own bootstrap adds the module bytes (serverWasmGzipData).
  // An "auto" site runs the Wasm server with the Wasm client; a "wasm-only" site has no
  // JavaScript server to fall back to, so it always does.
  function siteRunsWasmServer() {
    return site.runtimePolicy === "auto" || site.runtimePolicy === "wasm-only";
  }

  function serverRuntimeConfig() {
    const requested = String(params.get("serverRuntime")
      || (clientRuntime.form === "portable" && clientRuntime.selected === "wasm" ? "wasm" : "")
      || (site && siteRunsWasmServer() && clientRuntime.selected === "wasm" ? "wasm" : "")).toLowerCase();
    if (requested !== "wasm") return null;
    const form = clientRuntime.form;
    const dist = distRuntimes();
    const missing = [];
    if (form === "site" ? !WASM_SERVER_FILES.every(hasModule)
      : form === "portable" ? !portableWasmServerBuilt()
      : form === "file" ? !dist || typeof dist.server !== "string"
        || ![dist.server].concat(WASM_SERVER_FILES.slice(1)).every(hasFilePayload)
      : form !== "dev" || !dist || typeof dist.server !== "string") missing.push("wasm-server-build");
    missing.push(...missingWasmFeatures(clientRuntime.features, form === "file" || form === "portable"
      || (form === "site" && siteRunsWasmServer() && inflateUrl("singleplayer-server.wasm") !== null
        && !serviceWorkerControlled())));
    if (missing.length) {
      record("server-runtime-fallback", "missing:" + missing.join(","));
      return null;
    }
    const scripts = WASM_SERVER_FILES.slice(1);
    if (form === "portable") {
      return {
        serverRuntime: "wasm",
        wasmRuntimeScripts: scripts.map((name) => ({name, source: portableWasmScript(name)})),
      };
    }
    return {
      serverRuntime: "wasm",
      serverWasmUrl: form === "site" ? siteServerWasmUrl() : siblingUrl(dist.server),
      wasmRuntimeScripts: scripts.map((name) => ({name, url: siblingUrl(name)})),
    };
  }
  let serverWasm = serverRuntimeConfig();
  if (serverWasm) record("server-runtime", "wasm");

  // The integrated server Worker gets a port into this runtime right after its start message.
  let serverKernelPort = null;

  function installServerWorkerHook() {
    const WorkerClass = global.Worker;
    if (typeof WorkerClass !== "function" || !WorkerClass.prototype || WorkerClass.prototype.__gaiusKernelHook) return;
    if (typeof global.MessageChannel !== "function") return;
    const nativePostMessage = WorkerClass.prototype.postMessage;
    WorkerClass.prototype.postMessage = function (message, transfer) {
      let result;
      if (serverWasm && message && message.type === "start" && !message.serverRuntime) {
        const args = Array.prototype.slice.call(arguments);
        args[0] = Object.assign({}, message, serverWasm);
        result = nativePostMessage.apply(this, args);
      } else {
        result = nativePostMessage.apply(this, arguments);
      }
      try {
        if (message && message.type === "start" && message.port && !this.__gaiusKernelPortSent && !kernelSwitchOff()) {
          this.__gaiusKernelPortSent = true;
          const channel = new global.MessageChannel();
          const portMessage = {
            type: "gaius-kernel-port",
            port: channel.port2,
            kernelScripts: serverWorkerKernelScripts(),
            worldgenKernel: serverWorldgenConfig(),
            lightKernel: serverLightConfig(),
            kernelSemantics: global.__gaiusKernelSemantics || null,
          };
          if (worldgenHostMode() === "worker") {
            // The host configuration needs the resolved manifest; bounded, and without it the
            // Worker's worldgen simply stays on vanilla Java.
            const worker = this;
            withTimeout(serverWorldgenHost(), 15000).then((host) => {
              if (host) Object.assign(portMessage.worldgenKernel, host);
              try {
                nativePostMessage.call(worker, portMessage, [channel.port2]);
              } catch (error) {
                record("kernel-port-failed", error && error.message);
              }
            });
          } else {
            nativePostMessage.call(this, portMessage, [channel.port2]);
          }
          runtime().then((created) => {
            if (created) {
              // One integrated server at a time: a new start replaces the previous Worker, whose
              // queued jobs would only hold kernel workers (detachPort cancels them).
              if (serverKernelPort && typeof created.detachPort === "function") created.detachPort(serverKernelPort);
              serverKernelPort = channel.port1;
              created.attachPort(channel.port1);
            } else {
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
    // Nothing to warm from disk: a file:// page cannot fetch its siblings (design D13).
    if (isPortable() || isFile || params.get("gaiusPrefetch") === "0" || connectionIsConstrained()) return;
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

  // Cheap startup observability. These marks are intentionally one-shot and do not
  // alter scheduling; they let Pages startup traces expose the critical path.
  (() => {
    const started = {title: false, present: false, server: false, kernel: false, shader: false};
    const timer = global.setInterval(() => {
      const state = global.__gaiusMinecraftState;
      if (!started.title && state && String(state.screen || "").endsWith("TitleScreen")) {
        started.title = true; mark("title-screen-frame");
      }
      const gpu = global.GaiusWebGpu;
      if (!started.present && gpu && gpu.stats && gpu.stats.presents > 0) {
        started.present = true; mark("first-title-frame-presented");
      }
      if (!started.shader && gpu && gpu.stats && (gpu.stats.pipelinesRecorded || gpu.stats.pipelinesOnDemand)) {
        started.shader = true; mark("shader-pipeline-ready");
      }
      if (!started.server && global.__gaiusSingleplayerServerState
          && global.__gaiusSingleplayerServerState.ready) {
        started.server = true; mark("server-worker-ready");
      }
      if (!started.kernel && global.__gaiusKernelRuntime && global.__gaiusKernelRuntime.ready) {
        started.kernel = true; mark("kernel-worker-ready");
      }
      if (started.title && started.present && started.shader && started.server && started.kernel) {
        global.clearInterval(timer);
      }
    }, 50);
  })();

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

  // The JavaScript client of this distribution as a script URL, for the late fallback.
  async function jsClientUrl() {
    if (clientRuntime.form === "portable") {
      // The portable page decodes its embedded classes.js only on demand in wasm mode.
      const load = global.__gaiusPortableLoadJsClient;
      return typeof load === "function" ? load() : null;
    }
    if (site) {
      if (useGzip && hasAsset("classes.js.gz") && typeof global.DecompressionStream === "function") {
        return gunzipToBlobUrl(assetUrl("classes.js.gz"), "text/javascript");
      }
      return hasAsset("classes.js") ? siblingUrl("classes.js") : null;
    }
    return new URL("classes.js", loc.href).href;
  }

  // The module did not load or instantiate although the probes passed (a feature the probes do
  // not cover, a truncated download, out of memory): start the JavaScript client instead.
  async function startJsClientInstead(error) {
    const message = String(error && error.message || error);
    // The legacy JS client only reads the merged core archive. If Wasm failed after the sound
    // sidecar was selected, restore that view before loading classes.js so sounds are preserved.
    await soundsReady;
    if (global.__gaiusVanillaSounds && global.__gaiusVanillaAssets) {
      try {
        mergeSoundPack(global.__gaiusVanillaAssets, global.__gaiusVanillaSounds.bytes);
        global.__gaiusVanillaSounds = null;
        soundsState.layout = "merged-fallback";
        record("sounds-merged-for-js-fallback", "legacy archive");
      } catch (soundError) {
        record("sound-fallback-merge-failed", soundError && soundError.message);
        throw soundError;
      }
    }
    let url = null;
    try {
      url = await jsClientUrl();
    } catch (lookupError) {
      record("runtime-fallback-failed", lookupError && lookupError.message);
    }
    if (!url || !doc || !doc.createElement) throw error;
    clientRuntime.selected = "js";
    clientRuntime.fallback = true;
    clientRuntime.late = true;
    clientRuntime.reason = "wasm-load-failed:" + message.slice(0, 200);
    if (site && site.runtimePolicy === "auto") {
      serverWasm = null;
      if (global.GaiusWebGpu && typeof global.GaiusWebGpu.abandon === "function") {
        global.GaiusWebGpu.abandon("client-runtime-fallback");
      }
      gpuSelection.requested = "webgl2";
      webGpuFallback("client-runtime-fallback");
      publishGpuTimings();
    }
    record("runtime-fallback", clientRuntime.reason);
    if (global.console && typeof global.console.warn === "function") {
      global.console.warn("Gaius: the WebAssembly GC client did not start (" + message
        + "); starting the JavaScript client");
    }
    mark("runtime-js");
    global.__gaiusClassesUrl = url;
    await loadClassicScript(url);
  }

  // Wasm: the instantiated module (gaius-wasm-boot.js __gaiusClientReady), so main(args) starts
  // at once. Returns the time the client became ready (performance.now()).
  async function clientReady() {
    const timings = global.__gaiusBootTimings;
    if (clientRuntime.selected !== "wasm") return timings && timings.classesLoaded;
    try {
      await global.__gaiusClientReady;
      const wasm = global.__gaiusWasmTimings;
      return wasm && typeof wasm.instantiated === "number" ? wasm.instantiated : nowMs();
    } catch (error) {
      await startJsClientInstead(error);
      return nowMs();
    }
  }

  // Shared boot telemetry of both clients in the launcher's __gaiusBootTimings.
  function publishRuntimeTimings(readyAt) {
    const timings = global.__gaiusBootTimings;
    if (!timings || typeof timings !== "object") return;
    timings.runtime = clientRuntime.selected;
    timings.runtimeSelected = clientRuntime.at;
    if (clientRuntime.fallback) timings.runtimeFallback = clientRuntime.reason;
    else delete timings.runtimeFallback;
    if (typeof readyAt === "number" && isFinite(readyAt)) timings.clientReady = readyAt;
  }

  // Awaited by the launcher right before main(args): the quality layer must be loaded (bounded,
  // a missing file never blocks the boot) and its tier chosen; eager sounds must be merged; the
  // client must be ready to start synchronously.
  async function beforeMain() {
    mark("before-main");
    publishRuntimeTimings();
    await withTimeout(qualityReady, 5000);
    const quality = global.GaiusQuality;
    if (quality && quality.caps && typeof quality.caps.ensureTier === "function") {
      try {
        quality.caps.ensureTier();
      } catch (error) {
        record("quality-tier-failed", error && error.message);
      }
    }
    await prepareGpu();
    publishGpuTimings();
    await soundsGate;
    publishRuntimeTimings(await clientReady());
    mark("client-ready");
    mark("main");
  }

  function assetsReady() {
    return assetsReadyPromise;
  }

  global.__gaiusBoot = {
    version: BOOT_VERSION,
    site,
    startedAt: nowMs(),
    // The client runtime selection (window.__gaiusRuntimeSelection): requested, source,
    // selected, fallback, reason, form, wasmBuilt, features.
    runtime: clientRuntime,
    // The graphics backend selection (window.__gaiusGpuSelection): requested, source, selected
    // (webgpu, webgl2, or pending until beforeMain), fallback, reason, inject, initMs, backend().
    gpu: gpuSelection,
    get serverRuntime() { return serverWasm ? "wasm" : "js"; },
    handlesWasmFailure: true,
    assetUrl,
    filePayload,
    hasFilePayload,
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
    modules: {kernels: kernelScriptsReady, quality: qualityReady, webgpu: webGpuReady},
    mergeSoundPack,
  };
})(typeof window !== "undefined" ? window : globalThis);
