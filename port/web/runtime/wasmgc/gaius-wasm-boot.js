// Gaius WebAssembly GC client loader (v0.4.0 design D3 and D13).
//
// The launcher loads this file in place of the JavaScript build's classes.js: gaius-boot.js picks
// the client runtime (?runtime=wasm|js|auto, feature detection, fallback to js) and, for wasm,
// points window.__gaiusClassesUrl here. It loads the patched TeaVM runtime and gaius-jspi.js
// (unless the page already carries them), compiles and instantiates classes.wasm while the page
// prepares storage and assets, and defines the global main(args) that the launcher calls.
//
// Module source, first match wins:
//   window.__gaiusWasmSource   a value or a promise of one: a Response (compiled while it streams),
//                              bytes (an ArrayBuffer or a typed array: TeaVM load(bytes)) or a URL
//                              string. gaius-boot.js sets it for every distribution form: a fetch
//                              started at parse time (site, dist over http), the gzip+base64 file
//                              payload inflated with DecompressionStream (dist opened from disk),
//                              the module embedded in the portable page. The last two never fetch.
//   window.__gaiusWasmBytes, window.__gaiusWasmGzipBase64   (the M0 S1 page forms)
//   window.__gaiusWasmUrl || "classes.wasm"                streamed from that URL
//
// Telemetry: phase times (performance.now() milliseconds) go to window.__gaiusWasmTimings and,
// as wasm<Phase> keys, into the launcher's window.__gaiusBootTimings, which both runtimes share.
//
// Failures: a module that does not load or instantiate is window.__gaiusWasmError. When
// gaius-boot.js handles it (window.__gaiusBoot.handlesWasmFailure: beforeMain() starts the
// JavaScript client instead, or reports the error through the launcher) it is not also the
// launcher's window.__gaiusBootError.
//
// JSPI mode (gaius.teavm.jspi, design D3): gaius-jspi.js (window.__gaiusJspiUrl) provides the
// run queue and the gaiusJspi imports; every Java thread is a WebAssembly.promising activation
// and main(args) starts the main thread as one instead of calling TeaVM's main export. Every
// wasmgc build is a JSPI build: the coroutine (Fiber) mode was removed from the build.
(function () {
  "use strict";
  var g = window;
  var timings = g.__gaiusWasmTimings = { shimStart: performance.now() };

  function noteMemory() {
    if (!g.__gaiusWasmMemory) return;
    var root = g.__gaiusMemoryTelemetry || (g.__gaiusMemoryTelemetry = {});
    var bytes = g.__gaiusWasmMemory.buffer.byteLength;
    var memory = root.linearMemory;
    if (!memory) memory = root.linearMemory = {
      initialBytes: bytes, currentBytes: bytes, peakBytes: bytes, growthCount: 0, growths: []
    };
    if (bytes !== memory.currentBytes) {
      memory.growthCount++;
      if (memory.growths.length < 32) memory.growths.push({atMs: performance.now(),
        fromBytes: memory.currentBytes, toBytes: bytes});
      memory.currentBytes = bytes;
      memory.peakBytes = Math.max(memory.peakBytes, bytes);
    }
  }

  function phase(name) {
    var at = performance.now();
    timings[name] = at;
    var boot = g.__gaiusBootTimings;
    if (boot && typeof boot === "object") {
      boot["wasm" + name.charAt(0).toUpperCase() + name.slice(1)] = at;
    }
    return at;
  }

  function loadClassicScript(src) {
    return new Promise(function (resolve, reject) {
      var script = document.createElement("script");
      script.src = src;
      script.onload = function () { resolve(); };
      script.onerror = function () { reject(new Error("Failed to load " + src)); };
      document.head.appendChild(script);
    });
  }

  function base64ToBytes(text) {
    if (typeof Uint8Array.fromBase64 === "function") {
      return Uint8Array.fromBase64(text);
    }
    var binary = atob(text);
    var bytes = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
  }

  function gunzip(bytes) {
    var stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("gzip"));
    return new Response(stream).arrayBuffer();
  }

  function moduleSource() {
    if (g.__gaiusWasmSource !== undefined && g.__gaiusWasmSource !== null) {
      return Promise.resolve(g.__gaiusWasmSource).then(function (source) {
        // The loader holds the only reference from here on (the bytes can be large).
        g.__gaiusWasmSource = null;
        phase("sourceReady");
        return source;
      });
    }
    if (g.__gaiusWasmBytes) {
      return Promise.resolve(g.__gaiusWasmBytes);
    }
    if (typeof g.__gaiusWasmGzipBase64 === "string") {
      timings.decodeStart = performance.now();
      return gunzip(base64ToBytes(g.__gaiusWasmGzipBase64)).then(function (buffer) {
        phase("decoded");
        g.__gaiusWasmGzipBase64 = null;
        return buffer;
      });
    }
    return Promise.resolve(g.__gaiusWasmUrl || "classes.wasm");
  }

  var jspiMissing = !(typeof WebAssembly.Suspending === "function"
      && typeof WebAssembly.promising === "function");
  var jspi = null;
  var ready = null;
  var scripts = [];
  // A portable page carries the runtime scripts; a page that already ran them skips the load.
  if (!(g.TeaVM && g.TeaVM.wasmGC)) {
    scripts.push(loadClassicScript(g.__gaiusWasmRuntimeUrl || "gaius-wasm-gc-runtime.js"));
  }
  if (!g.GaiusJspi) {
    scripts.push(loadClassicScript(g.__gaiusJspiUrl || "gaius-jspi.js"));
  }
  var pngEnabled = g.__gaiusPngKernelEnabled === true
      || new URLSearchParams(g.location ? g.location.search : "").get("gaiusPng") === "1";
  if (pngEnabled) {
    g.__gaiusPngReady = (g.GaiusPngKernel ? Promise.resolve()
      : loadClassicScript(g.__gaiusPngScriptUrl || "png-kernel.js"))
      .then(function () {
        g.GaiusPngKernel.setEnabled(true);
        return g.GaiusPngKernel.initialize({
          bytes: g.__gaiusPngWasmBytes,
          module: g.__gaiusPngWasmModule,
          url: g.__gaiusPngWasmUrl || "gaius-png.wasm"
        });
      }).then(function (loaded) {
        if (!loaded) g.__gaiusPngError = g.GaiusPngKernel.lastError;
        return loaded;
      }).catch(function (error) {
        g.__gaiusPngError = String(error);
        return false;
      });
  }
  g.__gaiusWasmReady = (jspiMissing
    // gaius-boot.js falls back to the JavaScript build before it gets here (design D14).
    ? Promise.reject(new Error("This browser has no JSPI (WebAssembly.Suspending/promising)"))
    : Promise.all([Promise.all(scripts), moduleSource()]))
    .then(function (loaded) {
      phase("runtimeLoaded");
      var source = loaded[1];
      return g.TeaVM.wasmGC.load(source, {
        // memory.grow detaches the cached linear-memory views of dev.gaius.browser.JsArrays
        // (window.__gaiusLinear); refresh them at once instead of at their next use.
        memory: {
          onResize: function () {
            if (g.__gaiusLinear) {
              g.__gaiusLinear.refresh();
            }
            noteMemory();
          }
        },
        installImports: function (imports) {
          // Called after compilation and import linking, right before instantiation.
          phase("compiled");
          g.__gaiusWasmMemory = imports.env ? imports.env.memory : null;
          noteMemory();
          var scheduler = g.GaiusJspi.createScheduler();
          g.__gaiusJspiScheduler = scheduler;
          jspi = g.GaiusJspi.installJspi(imports, scheduler,
            function () { return g.__gaiusWasm.instance.exports; },
            function (e) {
              g.__gaiusThreadError = e;
              console.error("Gaius: a Java thread failed", e);
            });
          g.GaiusJspi.routeEventQueue(imports, scheduler);
        }
      });
    })
    .then(function (teavm) {
      phase("instantiated");
      g.__gaiusWasm = teavm;
      ready = teavm;
      return teavm;
    });
  // gaius-boot.js beforeMain() awaits this before the launcher calls main(args), so main starts
  // synchronously and mainStart/mainReturned mean the same in both runtimes.
  g.__gaiusClientReady = g.__gaiusWasmReady;
  g.__gaiusWasmReady.catch(function (error) {
    g.__gaiusWasmError = error;
    if (!(g.__gaiusBoot && g.__gaiusBoot.handlesWasmFailure === true)) {
      g.__gaiusBootError = error;
    }
    console.error("Gaius: the WebAssembly GC client did not load", error);
  });

  function runMain(args) {
    phase("mainStart");
    // The main activation runs until its first suspension, then from the run queue:
    // mainReturned marks that first suspension, as the main export's return does in the
    // JavaScript build.
    var mainSettled = jspi.startMain(args || []);
    phase("mainReturned");
    mainSettled.then(function () {
      phase("mainFinished");
    }, function (error) {
      phase("mainFinished");
      g.__gaiusBootError = error;
      console.error("Gaius: the WebAssembly GC main thread failed", error);
    });
  }

  g.main = function (args) {
    if (ready !== null) {
      runMain(args);
      return;
    }
    g.__gaiusWasmReady.then(function () {
      runMain(args);
    }).catch(function (error) {
      g.__gaiusBootError = error;
      console.error("Gaius: the WebAssembly GC client failed to start", error);
    });
  };
})();
