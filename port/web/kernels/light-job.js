// Gaius light job codec: builds framed light_column jobs for the wasm light kernel and reads
// its results. The layout mirrors port/native/crates/gaius-kernel-abi (job/result headers) and
// port/native/crates/gaius-light-wasm/src/job.rs (payload); all integers are little-endian.
//
// This file is a plain script so it can be inlined next to kernel-pool.js. It installs
// globalThis.GaiusLightJob:
//
//   const bytes = GaiusLightJob.pickModule({simd: simdBytes, baseline: baselineBytes});
//   const pool = await GaiusKernelPool.create({moduleBytes: bytes, workerSource, name: "light"});
//   const job = GaiusLightJob.lightColumn({
//     ops: GaiusLightJob.OPS.INITIAL, sky: true, block: true, emitOutgoing: true,
//     chunkX, chunkZ, minSection: -4, sectionCount: 24, skyBottomSection: -5,
//     table: tableBytes, tableEpoch: epoch,
//     column: {flags, sections, sky, block},          // see below
//     neighbours: {north, south, west, east},         // same shape, omitted = not loaded
//     checks: [x, y, z, ...]}, jobId);
//   const result = GaiusLightJob.readLightColumn(await pool.submit("light_column", job), jobId);
//
// In the server Worker, GaiusLightJob.installHost(runner, {enabled}) exposes a kernel pool or the
// kernel runtime to the Java light engine (BrowserLightKernel), which encodes and decodes the
// jobs itself.
//
// A slot ({column} or a neighbour) is {flags, sections, sky, block}:
//   flags     Uint8Array(sectionCount + 2) of SECTION_FLAGS storing bits per light section
//             (index 0 = the padding section below the world); the data bits are added here
//   sections  sectionCount entries: Uint8Array of PalettedContainer.write bytes (encoding
//             NETWORK), {single: stateId}, {flat: Uint16Array(4096)} or {encoding, bytes}
//   sky/block optional arrays of sectionCount + 2 entries: a 2048-byte DataLayer or null
//
// result: {lightSections, sectionFlags, sky[], block[] (Uint8Array views or null),
//          outgoing: {count, kind, side, along, level, y, entry, count16} (struct of arrays),
//          stats: {increasePops, decreasePops}}
(function (global) {
  "use strict";

  const ABI_VERSION = 1;
  const JOB_MAGIC = 0x424a4b47;     // "GKJB" read as little-endian u32
  const RESULT_MAGIC = 0x53524b47;  // "GKRS"
  const HEADER_LEN = 16;
  const KIND_LIGHT_COLUMN = 0x0301;
  const JOB_VERSION = 1;
  const PAYLOAD_HEADER_LEN = 48;
  const RESULT_HEADER_LEN = 32;
  const LAYER_BYTES = 2048;
  const OUTGOING_LEN = 12;
  const MAX_SECTIONS = 254;

  const OPS = Object.freeze({INITIAL: 1, CHECKS: 2});
  const FLAGS = Object.freeze({
    SKY: 1, BLOCK: 2, SKY_LIGHT_ON: 4, BLOCK_LIGHT_ON: 8, PULL_RING: 16, EMIT_OUTGOING: 32, OMIT_UNCHANGED: 64,
  });
  const SECTION_FLAGS = Object.freeze({SKY_STORING: 1, BLOCK_STORING: 2, SKY_DATA: 4, BLOCK_DATA: 8});
  const ENCODING = Object.freeze({SINGLE: 0, NETWORK: 1, FLAT_U16: 2});
  const OUTGOING = Object.freeze({SKY_INCREASE: 0, BLOCK_INCREASE: 1, SKY_DECREASE: 2, BLOCK_DECREASE: 3});
  // Ring side order of the job, and the Direction ordinal from the column into each side.
  const SIDES = Object.freeze(["north", "south", "west", "east"]);
  const SIDE_DIRECTION = Object.freeze([2, 3, 4, 5]);

  const pad8 = (offset) => Math.ceil(offset / 8) * 8;

  // The smallest module that uses a SIMD opcode (i8x16.popcnt of a splat).
  const SIMD_PROBE = new Uint8Array([
    0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 96, 0, 1, 123, 3, 2, 1, 0, 10, 10, 1, 8, 0, 65, 0, 253, 15, 253, 98, 11,
  ]);
  let simdSupport = null;

  function supportsSimd() {
    if (simdSupport === null) {
      try {
        simdSupport = !!(global.WebAssembly && global.WebAssembly.validate(SIMD_PROBE));
      } catch (_) {
        simdSupport = false;
      }
    }
    return simdSupport;
  }

  // Chooses the SIMD build when the engine validates SIMD, else the baseline build.
  function pickModule(builds) {
    if (builds.simd && supportsSimd()) return builds.simd;
    if (builds.baseline) return builds.baseline;
    if (builds.simd) throw new Error("the light kernel SIMD build needs WebAssembly SIMD and no baseline build was given");
    throw new Error("no light kernel build given");
  }

  function sectionBlob(section) {
    if (section instanceof Uint8Array) return [ENCODING.NETWORK, section];
    if (section && typeof section.single === "number") {
      const bytes = new Uint8Array(4);
      new DataView(bytes.buffer).setUint32(0, section.single >>> 0, true);
      return [ENCODING.SINGLE, bytes];
    }
    if (section && section.flat) {
      const flat = section.flat;
      if (flat.length !== 4096) throw new RangeError("flat sections hold 4096 state ids");
      const bytes = new Uint8Array(8192);
      const view = new DataView(bytes.buffer);
      for (let i = 0; i < 4096; i++) view.setUint16(2 * i, flat[i], true);
      return [ENCODING.FLAT_U16, bytes];
    }
    if (section && typeof section.encoding === "number" && section.bytes) {
      return [section.encoding, section.bytes instanceof Uint8Array ? section.bytes : new Uint8Array(section.bytes)];
    }
    throw new TypeError("light job section must be network bytes, {single}, {flat} or {encoding, bytes}");
  }

  function asBytes(data) {
    if (data instanceof Uint8Array) return data;
    if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
    return new Uint8Array(data);
  }

  // Normalizes a slot: flags with data bits, blobs, and the layers in payload order.
  function prepareSlot(slot, sectionCount, what) {
    const lightSections = sectionCount + 2;
    if (!slot || !slot.flags || slot.flags.length !== lightSections) {
      throw new RangeError(`${what} needs flags for ${lightSections} light sections`);
    }
    if (!slot.sections || slot.sections.length !== sectionCount) {
      throw new RangeError(`${what} needs ${sectionCount} sections`);
    }
    const flags = new Uint8Array(lightSections);
    const layers = [];
    const sky = slot.sky || [];
    const block = slot.block || [];
    for (let i = 0; i < lightSections; i++) {
      flags[i] = slot.flags[i] & (SECTION_FLAGS.SKY_STORING | SECTION_FLAGS.BLOCK_STORING);
      if (sky[i]) flags[i] |= SECTION_FLAGS.SKY_DATA;
      if (block[i]) flags[i] |= SECTION_FLAGS.BLOCK_DATA;
    }
    for (const [list, bit] of [[sky, SECTION_FLAGS.SKY_DATA], [block, SECTION_FLAGS.BLOCK_DATA]]) {
      for (let i = 0; i < lightSections; i++) {
        if (flags[i] & bit) {
          const layer = asBytes(list[i]);
          if (layer.byteLength !== LAYER_BYTES) throw new RangeError(`${what} light layers are 2048 bytes`);
          layers.push(layer);
        }
      }
    }
    const blobs = slot.sections.map(sectionBlob);
    return {flags, blobs, layers};
  }

  function epochBigInt(epoch) {
    if (epoch === undefined || epoch === null) return 0n;
    return BigInt.asUintN(64, BigInt(epoch));
  }

  function checkList(checks) {
    if (!checks) return new Int32Array(0);
    if (checks instanceof Int32Array) return checks;
    const flat = [];
    for (const entry of checks) {
      if (typeof entry === "number") flat.push(entry);
      else flat.push(entry.x, entry.y, entry.z);
    }
    return Int32Array.from(flat);
  }

  // Encodes the framed job (header + payload) into one ArrayBuffer ready to transfer.
  function lightColumn(job, jobId) {
    const sectionCount = job.sectionCount | 0;
    if (sectionCount < 1 || sectionCount > MAX_SECTIONS) throw new RangeError("sectionCount must be 1..=254");
    const lightSections = sectionCount + 2;
    const slots = [prepareSlot(job.column, sectionCount, "column")];
    let neighbours = 0;
    const around = job.neighbours || {};
    SIDES.forEach((side, i) => {
      if (around[side]) {
        neighbours |= 1 << i;
        slots.push(prepareSlot(around[side], sectionCount, side + " neighbour"));
      }
    });
    const table = job.table ? asBytes(job.table) : new Uint8Array(0);
    const checks = checkList(job.checks);
    if (checks.length % 3 !== 0) throw new RangeError("checks must hold whole [x, y, z] triples");

    let ops = typeof job.ops === "number" ? job.ops : 0;
    if (checks.length > 0) ops |= OPS.CHECKS;
    let flags = 0;
    if (job.sky) flags |= FLAGS.SKY;
    if (job.block !== false) flags |= FLAGS.BLOCK;
    if (job.skyLightOn) flags |= FLAGS.SKY_LIGHT_ON;
    if (job.blockLightOn) flags |= FLAGS.BLOCK_LIGHT_ON;
    if (job.pullRing) flags |= FLAGS.PULL_RING;
    if (job.emitOutgoing !== false) flags |= FLAGS.EMIT_OUTGOING;
    if (job.omitUnchanged) flags |= FLAGS.OMIT_UNCHANGED;

    let length = pad8(PAYLOAD_HEADER_LEN + table.byteLength);
    length = pad8(length + lightSections * slots.length);
    for (const slot of slots) {
      for (const [, bytes] of slot.blobs) length = pad8(length + 8 + bytes.byteLength);
    }
    for (const slot of slots) length += slot.layers.length * LAYER_BYTES;
    length += (checks.length / 3) * 8;

    const buffer = new ArrayBuffer(HEADER_LEN + length);
    const head = new DataView(buffer);
    head.setUint32(0, JOB_MAGIC, true);
    head.setUint16(4, ABI_VERSION, true);
    head.setUint16(6, KIND_LIGHT_COLUMN, true);
    head.setUint32(8, jobId >>> 0, true);
    head.setUint32(12, length, true);

    const view = new DataView(buffer, HEADER_LEN);
    const bytes = new Uint8Array(buffer, HEADER_LEN);
    view.setUint8(0, JOB_VERSION);
    view.setUint8(1, ops);
    view.setUint16(2, flags, true);
    view.setInt32(4, job.chunkX | 0, true);
    view.setInt32(8, job.chunkZ | 0, true);
    view.setInt32(12, job.minSection | 0, true);
    view.setUint32(16, sectionCount, true);
    view.setInt32(20, job.skyBottomSection === undefined ? (job.minSection | 0) - 1 : job.skyBottomSection | 0, true);
    view.setUint8(24, neighbours);
    view.setUint32(28, table.byteLength, true);
    view.setBigUint64(32, epochBigInt(job.tableEpoch), true);
    view.setUint32(40, checks.length / 3, true);

    let at = PAYLOAD_HEADER_LEN;
    bytes.set(table, at);
    at = pad8(at + table.byteLength);
    for (const slot of slots) {
      bytes.set(slot.flags, at);
      at += lightSections;
    }
    at = pad8(at);
    for (const slot of slots) {
      for (const [encoding, blob] of slot.blobs) {
        view.setUint8(at, encoding);
        view.setUint32(at + 4, blob.byteLength, true);
        bytes.set(blob, at + 8);
        at = pad8(at + 8 + blob.byteLength);
      }
    }
    for (const slot of slots) {
      for (const layer of slot.layers) {
        bytes.set(layer, at);
        at += LAYER_BYTES;
      }
    }
    for (let i = 0; i < checks.length; i += 3) {
      const x = checks[i];
      const z = checks[i + 2];
      if (x < 0 || x > 15 || z < 0 || z > 15) throw new RangeError("check x and z are column-local (0..15)");
      view.setUint8(at, x);
      view.setUint8(at + 1, z);
      view.setInt32(at + 4, checks[i + 1], true);
      at += 8;
    }
    return buffer;
  }

  // Validates a result header and returns the payload bytes.
  function readResult(buffer, jobId) {
    if (buffer.byteLength < HEADER_LEN) throw new Error("kernel result is shorter than its header");
    const view = new DataView(buffer);
    if (view.getUint32(0, true) !== RESULT_MAGIC) throw new Error("kernel result has a bad magic");
    if (view.getUint16(4, true) !== ABI_VERSION) throw new Error("kernel result abi " + view.getUint16(4, true));
    const status = view.getUint16(6, true);
    if (status !== 0) throw new Error("kernel result status " + status);
    if (jobId !== undefined && view.getUint32(8, true) !== jobId >>> 0) {
      throw new Error("kernel result for job " + view.getUint32(8, true) + ", expected " + (jobId >>> 0));
    }
    const length = view.getUint32(12, true);
    if (HEADER_LEN + length > buffer.byteLength) throw new Error("kernel result is truncated");
    return new Uint8Array(buffer, HEADER_LEN, length);
  }

  // Decodes a light_column result. Layers and records are views into `buffer` (no copies).
  function readLightColumn(buffer, jobId) {
    const payload = readResult(buffer, jobId);
    const base = payload.byteOffset;
    const view = new DataView(buffer, base, payload.byteLength);
    if (payload.byteLength < RESULT_HEADER_LEN || view.getUint8(0) !== 1) throw new Error("bad light_column result");
    const lightSections = view.getUint16(2, true);
    const skyCount = view.getUint32(4, true);
    const blockCount = view.getUint32(8, true);
    const recordCount = view.getUint32(12, true);
    const sectionFlags = new Uint8Array(buffer, base + RESULT_HEADER_LEN, lightSections);
    let at = pad8(RESULT_HEADER_LEN + lightSections);
    const need = at + (skyCount + blockCount) * LAYER_BYTES + recordCount * OUTGOING_LEN;
    if (need > payload.byteLength) throw new Error("light_column result is truncated");
    const sky = new Array(lightSections).fill(null);
    const block = new Array(lightSections).fill(null);
    for (const [list, bit] of [[sky, 1], [block, 2]]) {
      for (let i = 0; i < lightSections; i++) {
        if (sectionFlags[i] & bit) {
          list[i] = new Uint8Array(buffer, base + at, LAYER_BYTES);
          at += LAYER_BYTES;
        }
      }
    }
    const outgoing = {
      count: recordCount,
      kind: new Uint8Array(recordCount),
      side: new Uint8Array(recordCount),
      along: new Uint8Array(recordCount),
      level: new Uint8Array(recordCount),
      y: new Int32Array(recordCount),
      entry: new Uint16Array(recordCount),
      emptySections: new Uint16Array(recordCount),
    };
    for (let i = 0; i < recordCount; i++, at += OUTGOING_LEN) {
      outgoing.kind[i] = view.getUint8(at);
      outgoing.side[i] = view.getUint8(at + 1);
      outgoing.along[i] = view.getUint8(at + 2);
      outgoing.level[i] = view.getUint8(at + 3);
      outgoing.y[i] = view.getInt32(at + 4, true);
      outgoing.entry[i] = view.getUint16(at + 8, true);
      outgoing.emptySections[i] = view.getUint16(at + 10, true);
    }
    return {
      lightSections,
      sectionFlags,
      skyChanged: (i) => (sectionFlags[i] & 4) !== 0,
      blockChanged: (i) => (sectionFlags[i] & 8) !== 0,
      sky,
      block,
      outgoing,
      stats: {increasePops: view.getUint32(16, true), decreasePops: view.getUint32(20, true)},
    };
  }

  // The page switch: ?lightkernel=0 or the gaius.lightKernel=off setting turn the kernel off,
  // and so does globalThis.GAIUS_LIGHT_KERNEL === false. A Worker has neither the page URL nor
  // localStorage, so the page passes its decision to installHost there.
  function lightKernelEnabled(scope) {
    if (scope.GAIUS_LIGHT_KERNEL === false) return false;
    try {
      const search = scope.location && scope.location.search;
      if (search && new URLSearchParams(search).get("lightkernel") === "0") return false;
    } catch (_) {
      // No usable location: keep the default.
    }
    try {
      if (scope.localStorage && scope.localStorage.getItem("gaius.lightKernel") === "off") return false;
    } catch (_) {
      // Storage blocked: keep the default.
    }
    return true;
  }

  // Installs globalThis.GaiusLightKernelHost, the object dev.gaius.browser.kernel.light.BrowserLightKernel
  // submits its light_column jobs to (from the server Worker). The Java side builds the framed
  // job itself and reads the raw result; this only forwards to `runner`, a GaiusKernelPool or a
  // GaiusKernelRuntime (or its remote client): anything with submit(kind, payload, opts)
  // returning a promise of the result buffer. Jobs that fail come back through fail(message)
  // and the chunk is lit by the vanilla engine instead; while `enabled` is false (the page
  // switch, or runner.available("light") turning false) Java takes the vanilla path up front.
  function installHost(runner, options) {
    const opts = options || {};
    const switchedOn = opts.enabled !== undefined ? !!opts.enabled : lightKernelEnabled(global);
    const host = {
      submitted: 0,
      failed: 0,
      get enabled() {
        if (!switchedOn) return false;
        return typeof runner.available === "function" ? runner.available("light") !== false : true;
      },
      submit(buffer, priority, done, fail) {
        host.submitted++;
        // Chunk coordinates from the payload header, for the scheduler's distance ordering.
        const view = new DataView(buffer);
        const cx = buffer.byteLength >= HEADER_LEN + 12 ? view.getInt32(HEADER_LEN + 4, true) : 0;
        const cz = buffer.byteLength >= HEADER_LEN + 12 ? view.getInt32(HEADER_LEN + 8, true) : 0;
        // The job id doubles as the version: a newer job for the same chunk supersedes an older
        // one, whose promise then rejects and whose chunk Java lights the vanilla way.
        const version = buffer.byteLength >= HEADER_LEN ? view.getUint32(8, true) : 0;
        const submitOptions = {kernel: "light", priority: priority | 0, key: "light:" + cx + "," + cz, version, cx, cz};
        Promise.resolve()
          .then(() => runner.submit("light_column", buffer, submitOptions))
          .then(done, (error) => {
            host.failed++;
            fail(error && error.message ? error.message : String(error));
          });
      },
    };
    global.GaiusLightKernelHost = host;
    return host;
  }

  global.GaiusLightJob = Object.freeze({
    ABI_VERSION, KIND_LIGHT_COLUMN, OPS, FLAGS, SECTION_FLAGS, ENCODING, OUTGOING, SIDES, SIDE_DIRECTION,
    LAYER_BYTES, supportsSimd, pickModule, lightColumn, readResult, readLightColumn, lightKernelEnabled,
    installHost,
  });
})(typeof globalThis !== "undefined" ? globalThis : self);
