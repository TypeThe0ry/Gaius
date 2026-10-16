// Gaius mesh kernel facade: the page-side bridge between the Java section compiler
// (dev.gaius.browser.kernel.mesh.MeshKernelBridge, through small @JSBody calls) and the "mesher"
// kernel of the kernel runtime (kernel-runtime.js, created by gaius-boot.js as
// window.__gaiusKernels). The job and result codecs are GaiusMeshJob (mesh-job.js); this file
// only owns the per-resource-epoch table session, the in-flight jobs and a queue of finished
// results that Java polls. It is named *-job.js so stage-web-runtime.py and the portable page
// load it with the other kernel scripts; it resolves GaiusMeshJob lazily, so load order between
// the two does not matter.
//
// It installs globalThis.__gaiusMeshKernel:
//
//   ready()                      true when the runtime exists, the mesher kernel is available and
//                                no switch turned the kernel off (the Java side then compiles
//                                sections through the kernel; otherwise it keeps the vanilla path)
//   setTable(bytes, length, epoch)   new model table (Int8Array view of a Java byte[]): opens a
//                                GaiusMeshJob session for that epoch and warms one worker
//   hasTable(epoch)
//   submitJob(header, ids, light, quarts, palette, swamp, floats)
//                                copies one section snapshot (typed views of Java arrays) and
//                                submits it; header is an Int32Array, see HEADER below
//   cancelAll()                  cancels every section job still out (the level was reset or
//                                left); their records come back with code "cancelled"
//   poll()                       next finished record or null: {ticket, ok, code, message,
//                                status (0 meshed, 2 needs vanilla, -1 failed), quads[3],
//                                visLo, visHi, detail, result}
//   setWake(fn)                  fn() runs once when a record lands in an empty queue
//   status()                     counters for telemetry and debugging
//
// Switches: ?meshKernel=0|off|vanilla (or localStorage "gaius.meshKernel" = "off") keeps the
// vanilla Java compiler for this page; GaiusMeshJob's own ?gaiusMesher=0 and its automatic
// disable after repeated kernel failures are honoured too, as is ?gaiusKernels=0.
(function (global) {
  "use strict";

  const KERNEL = "mesher";
  const VOLUME = 8000;
  const QUART_VOLUME = 216;
  // Header ints written by MeshKernelBridge.
  const HEADER = Object.freeze({
    TICKET: 0, EPOCH: 1, WIDE: 2, PALETTE_COUNT: 3, FLAGS: 4, SX: 5, SY: 6, SZ: 7,
    SEED_HI: 8, SEED_LO: 9, REQUEST_SEQ: 10, NON_AIR: 11, DISTANCE: 12, BLEND: 13, LENGTH: 14,
  });
  const FLAG_AO = 1;
  const FLAG_CUTOUT_LEAVES = 2;
  const FLAG_NEARBY = 4;
  const NEARBY_PRIORITY_CHUNKS = 8;
  // Swamp column mask of a job with a biome blend radius: 20 x 20 columns (x, z in -2..17).
  const SWAMP_BLEND_BYTES = 50;
  const MAX_BIOME_BLEND = 2;

  let runtime = null;
  let runtimeState = "none"; // none | pending | ready | failed
  let switchReason;          // undefined until read once
  let disabledReason = null;
  let session = null;
  let sessionEpoch = -1;
  let wake = null;
  let wakeArmed = true;
  let done = [];
  let head = 0;
  let inFlight = 0;
  // Runtime key of every section job still out -> its ticket, for cancelAll.
  const outstanding = new Map();
  const stats = {
    submitted: 0, meshed: 0, needsVanilla: 0, failed: 0, superseded: 0, cancelledAll: 0, tables: 0,
    tableBytes: 0, tableEpoch: -1, preloadErrors: 0, lastError: null,
  };

  function message(error) {
    return error && error.message ? String(error.message) : String(error);
  }

  function readSwitch() {
    if (switchReason !== undefined) return switchReason;
    switchReason = null;
    try {
      const search = global.location && global.location.search ? global.location.search : "";
      const value = new URLSearchParams(search).get("meshKernel");
      if (value === "0" || value === "false" || value === "off" || value === "vanilla") switchReason = "url";
    } catch (_) { /* no location in this realm */ }
    try {
      if (!switchReason && global.localStorage && global.localStorage.getItem("gaius.meshKernel") === "off") {
        switchReason = "setting";
      }
    } catch (_) { /* storage blocked */ }
    return switchReason;
  }

  function ensureRuntime() {
    if (runtimeState !== "none") return;
    const kernels = global.__gaiusKernels;
    // The boot script may not have installed the runtime yet; try again on the next call.
    if (!kernels || typeof kernels.runtime !== "function") return;
    if (typeof kernels.enabled === "function" && !kernels.enabled()) {
      runtimeState = "failed";
      disabledReason = "kernels-off";
      return;
    }
    runtimeState = "pending";
    Promise.resolve().then(() => kernels.runtime()).then((created) => {
      runtime = created || null;
      runtimeState = created ? "ready" : "failed";
      if (!created) disabledReason = "runtime-unavailable";
      else preload();
    }, (error) => {
      runtimeState = "failed";
      disabledReason = "runtime-failed: " + message(error);
    });
  }

  function ready() {
    if (disabledReason || readSwitch()) return false;
    const codec = global.GaiusMeshJob;
    if (!codec || !codec.enabled()) return false;
    ensureRuntime();
    return runtimeState === "ready" && runtime.available(KERNEL);
  }

  function submitToRuntime(kind, payload, opts) {
    return runtime.submit(kind, payload, Object.assign({kernel: KERNEL}, opts || {}));
  }

  function preload() {
    if (!session || runtimeState !== "ready" || !runtime.available(KERNEL)) return;
    const current = session;
    current.preload(submitToRuntime, {key: "mesher-table", version: current.epoch, visible: true})
      .catch((error) => {
        stats.preloadErrors++;
        stats.lastError = "preload: " + message(error);
      });
  }

  function setTable(bytes, length, epoch) {
    const codec = global.GaiusMeshJob;
    if (!codec) return false;
    const count = length >>> 0;
    const copy = new Uint8Array(count);
    copy.set(new Uint8Array(bytes.buffer, bytes.byteOffset, count));
    const next = codec.session(copy, epoch >>> 0);
    // Jobs of another table would come back with its UVs and model choices: drop them, the Java
    // side then compiles those sections with the vanilla compiler.
    if (session !== null && sessionEpoch !== (epoch >>> 0)) cancelAll();
    session = next;
    sessionEpoch = epoch >>> 0;
    stats.tables++;
    stats.tableBytes = count;
    stats.tableEpoch = sessionEpoch;
    preload();
    return true;
  }

  function hasTable(epoch) {
    return session !== null && sessionEpoch === (epoch >>> 0);
  }

  function copyOf(view, Type, count) {
    const out = new Type(count);
    out.set(new Type(view.buffer, view.byteOffset, count));
    return out;
  }

  function finish(record, key) {
    inFlight--;
    if (key !== undefined && outstanding.get(key) === record.ticket) outstanding.delete(key);
    if (record.ok) {
      const result = record.result;
      record.status = result.statusCode;
      record.quads = [result.layers.solid.quadCount, result.layers.cutout.quadCount, result.layers.translucent.quadCount];
      record.visLo = result.visibilityLo | 0;
      record.visHi = result.visibilityHi | 0;
      record.detail = result.detail | 0;
      if (record.status === 0) stats.meshed++;
      else stats.needsVanilla++;
    } else {
      record.status = -1;
      record.quads = [0, 0, 0];
      record.visLo = 0;
      record.visHi = 0;
      record.detail = 0;
      if (record.code === "superseded" || record.code === "stale" || record.code === "cancelled") {
        stats.superseded++;
      } else {
        stats.failed++;
        stats.lastError = record.code + ": " + record.message;
      }
    }
    done.push(record);
    if (wake && wakeArmed) {
      wakeArmed = false;
      try {
        wake();
      } catch (_) {
        wakeArmed = true;
      }
    }
  }

  // header: Int32Array (HEADER); ids: Int16Array or Int32Array (20^3 global state ids, the
  // region of GaiusMeshJob.regionIndex); light, quarts, swamp: Int8Array; palette: Int32Array of
  // 5 ints per biome; floats: Float32Array cardinal[6] then camera[3]. Every array is a view of a
  // reused Java array, so this copies before anything asynchronous happens.
  function submitJob(header, ids, light, quarts, palette, swamp, floats) {
    if (!session || runtimeState !== "ready" || !runtime.available(KERNEL)) return false;
    const codec = global.GaiusMeshJob;
    if (!codec || !codec.enabled()) return false;
    if ((header[HEADER.EPOCH] >>> 0) !== sessionEpoch) return false;
    const ticket = header[HEADER.TICKET] | 0;
    const wide = header[HEADER.WIDE] !== 0;
    const flags = header[HEADER.FLAGS] | 0;
    const sx = header[HEADER.SX] | 0;
    const sy = header[HEADER.SY] | 0;
    const sz = header[HEADER.SZ] | 0;
    const requestSeq = header[HEADER.REQUEST_SEQ] >>> 0;
    const paletteCount = header[HEADER.PALETTE_COUNT] | 0;
    if (paletteCount < 1 || paletteCount > 256) return false;
    const blend = header[HEADER.BLEND] | 0;
    if (blend < 0 || blend > MAX_BIOME_BLEND) return false;
    const seed = BigInt.asIntN(64, (BigInt(header[HEADER.SEED_HI] | 0) << BigInt(32))
      | BigInt(header[HEADER.SEED_LO] >>> 0));
    const input = {
      requestSeq,
      sectionVersion: requestSeq,
      section: [sx, sy, sz],
      ao: (flags & FLAG_AO) !== 0,
      cutoutLeaves: (flags & FLAG_CUTOUT_LEAVES) !== 0,
      vanilla: true,
      compact: false,
      sortTranslucent: true,
      centroids: false,
      cardinal: [floats[0], floats[1], floats[2], floats[3], floats[4], floats[5]],
      camera: [floats[6], floats[7], floats[8]],
      biomeZoomSeed: seed,
      states: wide ? copyOf(ids, Uint32Array, VOLUME) : copyOf(ids, Uint16Array, VOLUME),
      light: copyOf(light, Uint8Array, VOLUME),
      biomeQuarts: copyOf(quarts, Uint8Array, QUART_VOLUME),
      biomePalette: copyOf(palette, Int32Array, paletteCount * 5),
      biomeBlend: blend,
      swampMask: copyOf(swamp, Uint8Array, SWAMP_BLEND_BYTES),
    };
    const key = "s" + sx + "," + sy + "," + sz;
    const epochAtSubmit = sessionEpoch;
    const opts = {
      key,
      // The runtime dedupes a key's queued job of the same version and rejects older versions as
      // stale. Java's request sequence restarts at 1 after a level reset, so the version is the
      // ticket, which only grows for the whole page.
      version: ticket,
      visible: true,
      cx: sx,
      cz: sz,
      distance: header[HEADER.DISTANCE] | 0,
      resultBytes: codec.estimateResultBytes(header[HEADER.NON_AIR] | 0, false),
    };
    // A recompile next to the camera is almost always the player's own edit: run it ahead of
    // the sections that merely stream in (kernel-policy counts priority in chunks of distance).
    if ((flags & FLAG_NEARBY) !== 0) opts.priority = -NEARBY_PRIORITY_CHUNKS;
    stats.submitted++;
    inFlight++;
    let pending;
    try {
      pending = session.mesh(submitToRuntime, input, opts);
    } catch (error) {
      inFlight--;
      stats.failed++;
      stats.lastError = "submit: " + message(error);
      return false;
    }
    outstanding.set(key, ticket);
    pending.then(
      (result) => {
        // Last line of defence against a result that answers another request (a different
        // table, section or request): it never installs, the section goes to vanilla.
        if (sessionEpoch !== epochAtSubmit || (result.tableEpoch >>> 0) !== epochAtSubmit
            || (result.requestSeq >>> 0) !== requestSeq || result.section[0] !== sx
            || result.section[1] !== sy || result.section[2] !== sz) {
          finish({ticket, ok: false, code: "stale", message: "result does not match the request", result: null}, key);
          return;
        }
        finish({ticket, ok: true, code: null, message: null, result}, key);
      },
      (error) => finish({
        ticket, ok: false, code: (error && error.code) || "error", message: message(error), result: null,
      }, key));
    return true;
  }

  // Cancels every section job still out. Their promises reject with "cancelled", so the Java side
  // still gets one record per ticket and settles its in-flight bookkeeping.
  function cancelAll() {
    if (outstanding.size === 0) return 0;
    const keys = Array.from(outstanding.keys());
    outstanding.clear();
    if (!runtime || typeof runtime.cancel !== "function") return 0;
    let cancelled = 0;
    keys.forEach((key) => {
      try {
        if (runtime.cancel(key, KERNEL) !== false) cancelled++;
      } catch (_) { /* the runtime is going away; the Java answer timeout covers the job */ }
    });
    stats.cancelledAll += cancelled;
    return cancelled;
  }

  function poll() {
    if (head >= done.length) {
      if (done.length > 0) {
        done = [];
        head = 0;
      }
      wakeArmed = true;
      return null;
    }
    const record = done[head];
    done[head++] = undefined;
    return record;
  }

  function setWake(fn) {
    wake = typeof fn === "function" ? fn : null;
    wakeArmed = true;
  }

  function disable(reason) {
    if (!disabledReason) disabledReason = String(reason || "disabled");
  }

  function status() {
    const codec = global.GaiusMeshJob;
    return Object.assign({
      ready: ready(),
      runtime: runtimeState,
      disabledReason: disabledReason || readSwitch(),
      codec: codec ? codec.status() : null,
      inFlight,
      queued: done.length - head,
    }, stats);
  }

  global.__gaiusMeshKernel = Object.freeze({
    version: 1, HEADER, ready, setTable, hasTable, submitJob, cancelAll, poll, setWake, disable, status,
  });
})(typeof globalThis !== "undefined" ? globalThis : self);
