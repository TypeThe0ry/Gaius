// Gaius WebAssembly GC thread host for TeaVM's JSPI mode (gaius.teavm.jspi): the run queue,
// the gaiusJspi imports of dev.gaius.browser.JspiHost and the routing of TeaVM EventQueue
// events into the same queue. Exposes globalThis.GaiusJspi; the boot passes
// installJspi/routeEventQueue to TeaVM.wasmGC.load through options.installImports and starts
// the program with startMain(args) instead of TeaVM's main export.
//
// Run queue: ready work (resumed threads, started threads, zero-delay TeaVM events) runs in
// one macrotask posted through a MessageChannel, for at most SLICE_MS, then yields to the
// event loop. Nested setTimeout(0) would be clamped to 4 ms; a MessageChannel message is not.
(function () {
  "use strict";
  var SLICE_MS = 4;
  // setTimeout takes a signed 32-bit delay; a longer one fires at once (TimeoutOverflowWarning).
  // A thread that waits longer re-parks for the time left when the timer fires.
  var MAX_TIMER_MS = 2147483647;

  function createScheduler() {
    var queue = [];
    var head = 0;
    var scheduled = false;
    var channel = new MessageChannel();
    var stats = { posts: 0, slices: 0, maxQueue: 0, turns: 0, hops: 0 };
    // True from the moment the drain loop resumes (or starts) an activation until that
    // activation suspends again (a suspending import returned a promise) or finishes.
    var turn = { running: false };
    // draining: drain() runs (between its first and last microtask). sliceEnd: when the Java
    // code that runs now has had its slice of the current macrotask (see the yield import).
    var slice = { draining: false, end: 0 };

    function post(fn) {
      queue.push(fn);
      stats.posts++;
      if (queue.length - head > stats.maxQueue) {
        stats.maxQueue = queue.length - head;
      }
      if (!scheduled) {
        scheduled = true;
        channel.port2.postMessage(0);
      }
    }

    // Each item either runs synchronously (TeaVM Fiber events) or resumes a JSPI activation
    // (returns true). JSPI resumes the wasm stack from a promise reaction, so the loop waits
    // microtask by microtask until that activation suspends again or finishes; the next item
    // then still runs in this macrotask, in FIFO order.
    async function drain() {
      stats.slices++;
      var end = performance.now() + SLICE_MS;
      slice.end = end;
      slice.draining = true;
      try {
        while (head < queue.length) {
          var fn = queue[head];
          queue[head] = undefined;
          head++;
          turn.running = true;
          var resumed = false;
          try {
            resumed = fn() === true;
          } catch (e) {
            // A failing event must not stall the queue.
            console.error(e);
          }
          if (resumed) {
            stats.turns++;
            for (var hop = 0; turn.running && hop < 64; hop++) {
              stats.hops++;
              await null;
            }
          }
          turn.running = false;
          if (performance.now() >= end) {
            break;
          }
        }
      } finally {
        slice.draining = false;
      }
      if (head === queue.length) {
        queue.length = 0;
        head = 0;
      } else if (head > 1024) {
        queue = queue.slice(head);
        head = 0;
      }
      scheduled = false;
      if (head < queue.length) {
        scheduled = true;
        channel.port2.postMessage(0);
      }
    }

    channel.port1.onmessage = function () {
      drain().catch(function (e) {
        console.error(e);
      });
    };

    return {
      post: post,
      turn: turn,
      pending: function () {
        return queue.length - head;
      },
      draining: function () {
        return slice.draining;
      },
      // A host callback (a timer, an unpark from an event handler, the start of main) is a
      // macrotask of its own: the Java code it resumes gets a fresh slice.
      beginSlice: function () {
        if (!slice.draining) {
          slice.end = performance.now() + SLICE_MS;
        }
      },
      sliceExpired: function () {
        return performance.now() >= slice.end;
      },
      stats: stats
    };
  }

  // gaiusJspi imports for TeaVM's JSPI mode (dev.gaius.browser.JspiHost). Handles are the
  // Java thread slots; 0 is the main thread.
  function installJspi(importObj, scheduler, getExports, onError) {
    if (typeof WebAssembly.Suspending !== "function" || typeof WebAssembly.promising !== "function") {
      throw new Error("JSPI (WebAssembly.Suspending/promising) is not available");
    }
    var states = [];
    var runActivation = null;
    // Synchronous JS -> Java calls (JSO methods and @JSFunctor callbacks, exported Java
    // classes, property accessors) run Java on top of a JS frame. A suspending import cannot
    // suspend there: V8 throws SuspendError, a JS exception that skips the Java finally blocks
    // on its way. JspiHost.callbackDepth() lets the class library refuse to block in such a
    // callback (IllegalStateException), as outside any activation.
    var callbackDepth = 0;
    countJavaCallbacks(importObj.teavmJso);

    function countJavaCallbacks(jso) {
      if (!jso) {
        return;
      }
      // Each TeaVM JSO definition import receives the wasm function that a JS caller ends up
      // calling; the argument indexes of those functions.
      var definitions = {
        defineMethod: [2],
        defineStaticMethod: [2],
        defineFunction: [0],
        defineProperty: [2, 3],
        defineStaticProperty: [2, 3],
        createClass: [2]
      };
      Object.keys(definitions).forEach(function (name) {
        var define = jso[name];
        if (typeof define !== "function") {
          return;
        }
        var indexes = definitions[name];
        jso[name] = function () {
          var args = Array.prototype.slice.call(arguments);
          for (var i = 0; i < indexes.length; i++) {
            args[indexes[i]] = counted(args[indexes[i]]);
          }
          return define.apply(this, args);
        };
      });
    }

    function counted(fn) {
      if (typeof fn !== "function") {
        return fn;
      }
      var wrapper = function () {
        callbackDepth++;
        try {
          return fn.apply(this, arguments);
        } finally {
          callbackDepth--;
        }
      };
      // The JSO glue derives the parameter list of the JS-visible function from fn.length.
      Object.defineProperty(wrapper, "length", { value: fn.length });
      return wrapper;
    }

    function state(handle) {
      var s = states[handle];
      if (s === undefined) {
        s = { resolve: null, permit: false, timer: 0 };
        states[handle] = s;
      }
      return s;
    }

    function wake(handle, value) {
      var s = states[handle];
      if (s === undefined) {
        return false;
      }
      if (s.resolve === null) {
        s.permit = true;
        return false;
      }
      var resolve = s.resolve;
      s.resolve = null;
      if (s.timer !== 0) {
        clearTimeout(s.timer);
        s.timer = 0;
      }
      resolve(value);
      return true;
    }

    function suspended() {
      scheduler.turn.running = false;
    }

    function run() {
      if (runActivation === null) {
        runActivation = WebAssembly.promising(getExports()["gaius.jspi.run"]);
      }
      return runActivation;
    }

    // An activation that ends with a wasm trap (a null dereference or an array bound in a build
    // without strict checks, a stack overflow, ...) or another JS exception skips the Java
    // finally blocks of TJspiThreadSupport.run: gaius.jspi.abandon releases its thread slot
    // and ends the thread instead (TJspiThreadSupport.abandon).
    function abandon(handle) {
      var exports = getExports();
      var abandonExport = exports ? exports["gaius.jspi.abandon"] : undefined;
      if (typeof abandonExport === "function") {
        try {
          abandonExport(handle);
        } catch (e) {
          console.error(e);
        }
      }
      states[handle] = undefined;
    }

    function activation(handle) {
      run()(handle).then(suspended, function (e) {
        suspended();
        abandon(handle);
        onError(e);
      });
      return true;
    }

    importObj.gaiusJspi = {
      park: new WebAssembly.Suspending(function (handle, timeoutMillis) {
        var s = state(handle);
        if (s.permit) {
          s.permit = false;
          return 1;
        }
        suspended();
        return new Promise(function (resolve) {
          s.resolve = resolve;
          if (timeoutMillis > 0) {
            s.timer = setTimeout(function () {
              s.timer = 0;
              if (s.resolve === resolve) {
                s.resolve = null;
                scheduler.beginSlice();
                resolve(0);
              }
            }, Math.min(timeoutMillis, MAX_TIMER_MS));
          }
        });
      }),
      unpark: function (handle) {
        var s = state(handle);
        if (s.resolve === null) {
          s.permit = true;
          return;
        }
        if (!scheduler.draining()) {
          // A host callback (a frame callback, an I/O completion): resume the thread from this
          // callback's microtask checkpoint instead of one MessageChannel hop later. Inside the
          // drain the queue keeps FIFO order and the slice budget.
          scheduler.beginSlice();
          wake(handle, 1);
          return;
        }
        scheduler.post(function () {
          return wake(handle, 1);
        });
      },
      yield: new WebAssembly.Suspending(function (handle) {
        // Nothing else is runnable and the slice is not over: keep running. Once the thread has
        // had its slice it suspends behind a queued resume even with an empty queue, so the
        // drain ends the slice and the browser (timers, input, messages) gets a turn: a thread
        // that spins on Thread.yield() waiting for one of them still sees it happen.
        if (scheduler.pending() === 0 && !scheduler.sliceExpired()) {
          return 0;
        }
        suspended();
        return new Promise(function (resolve) {
          scheduler.post(function () {
            resolve(0);
            return true;
          });
        });
      }),
      start: function (handle) {
        state(handle);
        scheduler.post(function () {
          return activation(handle);
        });
      },
      release: function (handle) {
        states[handle] = undefined;
      },
      callbackDepth: function () {
        return callbackDepth;
      }
    };

    return {
      // Runs the program's main method as the activation of the main thread (handle 0).
      // TeaVM's own "main" export is a JS wrapper and cannot be wrapped in promising.
      // The returned promise settles when the main method returns or throws.
      startMain: function (args) {
        globalThis.__gaiusJspiMainArgs = args || [];
        state(0);
        scheduler.beginSlice();
        return run()(0).then(undefined, function (e) {
          suspended();
          abandon(0);
          throw e;
        });
      },
      states: states
    };
  }

  // TeaVM EventQueue events (teavmAsync.offer, for example HostLoop.schedule): zero-delay
  // events go through the run queue, in FIFO order with thread resumptions, instead of a
  // clamped setTimeout; delayed ones keep TeaVM's setTimeout. Queued ids are negative.
  // kill (HostLoop.killSchedule) cancels both kinds.
  function routeEventQueue(importObj, scheduler) {
    var nextId = 0;
    var queued = new Set();
    var stock = importObj.teavmAsync;
    importObj.teavmAsync = {
      offer: function (instance, fn, time) {
        if (time - Date.now() > 0) {
          return stock.offer(instance, fn, time);
        }
        var id = -(++nextId);
        queued.add(id);
        scheduler.post(function () {
          if (queued.delete(id)) {
            fn(instance);
          }
          return false;
        });
        return id;
      },
      kill: function (id) {
        if (id < 0) {
          queued.delete(id);
        } else {
          stock.kill(id);
        }
        return 0;
      }
    };
  }

  globalThis.GaiusJspi = {
    createScheduler: createScheduler,
    installJspi: installJspi,
    routeEventQueue: routeEventQueue
  };
})();
