// Gaius WebAssembly GC client loader (v0.4.0 M0 spike S1).
//
// The launcher page loads this file in place of the JavaScript build's classes.js
// (window.__gaiusClassesUrl).  It starts loading the patched TeaVM runtime and classes.wasm at
// once, so the module compiles while the page prepares storage and assets, and defines the
// global main(args) that the launcher calls when it is ready.  The module bytes come from, in
// order: window.__gaiusWasmBytes (an ArrayBuffer or typed array), window.__gaiusWasmGzipBase64
// (a gzip+base64 payload for file:// pages, decoded with DecompressionStream), or
// window.__gaiusWasmUrl (default "classes.wasm", streamed).  Phase times go to
// window.__gaiusWasmTimings (performance.now() milliseconds).
//
// JSPI mode (gaius.teavm.jspi, design D3): gaius-jspi.js (window.__gaiusJspiUrl) provides the
// run queue and the gaiusJspi imports; every Java thread is a WebAssembly.promising activation
// and main(args) starts the main thread as one instead of calling TeaVM's main export.
// window.__gaiusWasmJspi = false loads a coroutine (non-JSPI) module.
(function () {
  "use strict";
  var g = window;
  var timings = g.__gaiusWasmTimings = { shimStart: performance.now() };

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
    if (g.__gaiusWasmBytes) {
      return Promise.resolve(g.__gaiusWasmBytes);
    }
    if (typeof g.__gaiusWasmGzipBase64 === "string") {
      timings.decodeStart = performance.now();
      return gunzip(base64ToBytes(g.__gaiusWasmGzipBase64)).then(function (buffer) {
        timings.decoded = performance.now();
        g.__gaiusWasmGzipBase64 = null;
        return buffer;
      });
    }
    return Promise.resolve(g.__gaiusWasmUrl || "classes.wasm");
  }

  var useJspi = g.__gaiusWasmJspi !== false;
  if (useJspi && !(typeof WebAssembly.Suspending === "function"
      && typeof WebAssembly.promising === "function")) {
    // The launcher falls back to the JavaScript build before it gets here (design D3).
    g.__gaiusBootError = new Error("This browser has no JSPI (WebAssembly.Suspending)");
  }
  var jspi = null;
  var runtimeUrl = g.__gaiusWasmRuntimeUrl || "gaius-wasm-gc-runtime.js";
  var scripts = [loadClassicScript(runtimeUrl)];
  if (useJspi) {
    scripts.push(loadClassicScript(g.__gaiusJspiUrl || "gaius-jspi.js"));
  }
  g.__gaiusWasmReady = Promise.all([Promise.all(scripts), moduleSource()])
    .then(function (loaded) {
      timings.runtimeLoaded = performance.now();
      var source = loaded[1];
      return g.TeaVM.wasmGC.load(source, {
        installImports: function (imports) {
          // Called after compilation and import linking, right before instantiation.
          timings.compiled = performance.now();
          g.__gaiusWasmMemory = imports.env ? imports.env.memory : null;
          if (useJspi) {
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
        }
      });
    })
    .then(function (teavm) {
      timings.instantiated = performance.now();
      g.__gaiusWasm = teavm;
      return teavm;
    });
  g.__gaiusWasmReady.catch(function (error) {
    g.__gaiusBootError = error;
    console.error("Gaius: the WebAssembly GC client did not load", error);
  });

  g.main = function (args) {
    g.__gaiusWasmReady.then(function (teavm) {
      timings.mainStart = performance.now();
      if (jspi !== null) {
        // The main activation runs until its first suspension, then from the run queue:
        // mainReturned marks that first suspension, as the main export's return does below.
        var mainSettled = jspi.startMain(args || []);
        timings.mainReturned = performance.now();
        mainSettled.then(function () {
          timings.mainFinished = performance.now();
        }, function (error) {
          timings.mainFinished = performance.now();
          g.__gaiusBootError = error;
          console.error("Gaius: the WebAssembly GC main thread failed", error);
        });
        return;
      }
      try {
        teavm.exports.main(args || []);
      } finally {
        timings.mainReturned = performance.now();
      }
    }).catch(function (error) {
      g.__gaiusBootError = error;
      console.error("Gaius: the WebAssembly GC client failed to start", error);
    });
  };
})();
