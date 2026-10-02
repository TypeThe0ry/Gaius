#!/usr/bin/env node
// Unified kernel runtime (port/web/kernels/kernel-runtime.js) over the real kernel worker.
//
//   node port/scripts/kernel-runtime-smoke.mjs
//
// kernel-policy.js and kernel-runtime.js run in a node:vm "page"; every fake Worker runs the
// real kernel-worker.js in its own vm context, messages cross through structuredClone with
// their transfer lists, and a fake WebAssembly implements the kernel ABI in JavaScript. The
// checks cover priority dispatch across kernels, movement-aware ordering, lazy multi-kernel
// loading, SIMD/baseline selection, budget backpressure, kernel disabling (the vanilla
// fallback signal) and the MessagePort client the integrated server Worker uses.

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const kernelsDir = new URL("../web/kernels/", import.meta.url);
const policySource = await readFile(new URL("kernel-policy.js", kernelsDir), "utf8");
const runtimeSource = await readFile(new URL("kernel-runtime.js", kernelsDir), "utf8");
const workerSource = await readFile(new URL("kernel-worker.js", kernelsDir), "utf8");

const encodeModule = (desc) => new TextEncoder().encode(JSON.stringify(Object.assign({fake: 1}, desc))).buffer;
const decodeModule = (bytes) => JSON.parse(new TextDecoder().decode(bytes));

function moduleExports(desc) {
  return [
    {name: "memory", kind: "memory"},
    {name: "alloc", kind: "function"},
    {name: "reset", kind: "function"},
    ...desc.kinds.map((kind) => ({name: `run_${kind}`, kind: "function"})),
  ];
}

function makeInstance(desc, log) {
  log.instances.push(desc.name);
  let buffer = new ArrayBuffer(1 << 16);
  let top = 64;
  const memory = {get buffer() { return buffer; }};
  const alloc = (length) => {
    const ptr = (top + 7) & ~7;
    top = ptr + length;
    while (top > buffer.byteLength) buffer = buffer.transfer(buffer.byteLength * 2);
    return ptr;
  };
  const output = (status, bytes) => {
    const data = alloc(bytes.length);
    new Uint8Array(buffer, data, bytes.length).set(bytes);
    const header = alloc(12);
    const view = new DataView(buffer);
    view.setUint32(header, status, true);
    view.setUint32(header + 4, data, true);
    view.setUint32(header + 8, bytes.length, true);
    return header;
  };
  const exports = {memory, alloc, reset: () => { top = 64; }};
  for (const kind of desc.kinds) {
    exports[`run_${kind}`] = (ptr, length) => {
      const bytes = new Uint8Array(buffer, ptr, length).slice();
      const tag = bytes.length >= 4 ? new DataView(bytes.buffer).getUint32(0, true) : -1;
      log.order.push(`${desc.name}:${kind}:${tag}`);
      if (kind === "trap") throw new Error("unreachable executed");
      if (kind === "spin") {
        const until = Date.now() + 30;
        while (Date.now() < until) { /* a long kernel */ }
      }
      return output(0, bytes);
    };
  }
  return {exports};
}

function createEnv({cores = 4, deviceMemory = 8, simd = true} = {}) {
  const log = {order: [], instances: [], workers: [], posts: []};
  const workerWasm = {
    Module: {exports: moduleExports, imports: () => []},
    compile: async (bytes) => decodeModule(bytes),
    async instantiate(source) {
      return source && source.fake === 1 ? makeInstance(source, log) : {instance: makeInstance(decodeModule(source), log)};
    },
  };

  class FakeWorker {
    constructor(url, options) {
      this.name = options && options.name;
      this.terminated = false;
      this.onmessage = null;
      this.onerror = null;
      log.workers.push(this);
      this.ctx = vm.createContext({
        console, performance, TextDecoder,
        setTimeout: (fn, ms) => setTimeout(() => { if (!this.terminated) fn(); }, ms),
        WebAssembly: workerWasm,
        postMessage: (message, transfer) => this.fromWorker(message, transfer),
      });
      vm.runInContext("globalThis.self = globalThis;", this.ctx);
      vm.runInContext(workerSource, this.ctx, {filename: "kernel-worker.js"});
    }

    postMessage(message, transfer = []) {
      if (this.terminated) return;
      const data = structuredClone(message, {transfer});
      log.posts.push({dir: "toWorker", type: message.type, kernel: message.kernel});
      setTimeout(() => { if (!this.terminated) this.ctx.onmessage({data}); }, 1);
    }

    fromWorker(message, transfer = []) {
      if (this.terminated) return;
      const data = structuredClone(message, {transfer});
      setTimeout(() => { if (!this.terminated && this.onmessage) this.onmessage({data}); }, 1);
    }

    terminate() {
      this.terminated = true;
    }
  }

  const pageWasm = {
    Module: {exports: moduleExports, imports: () => []},
    compile: async (bytes) => {
      const desc = decodeModule(bytes);
      if (desc.failCompile) throw new Error("CompileError: simd opcode not supported");
      return desc;
    },
    validate: () => simd,
    Memory: class {},
  };
  const context = vm.createContext({
    console, performance, setTimeout, clearTimeout, setInterval, clearInterval, structuredClone,
    navigator: {hardwareConcurrency: cores, deviceMemory},
    Worker: FakeWorker, WebAssembly: pageWasm, MessageChannel, URLSearchParams,
    URL: {createObjectURL: () => "blob:kernel-runtime-smoke/1", revokeObjectURL() {}},
    Blob: class { constructor(parts) { this.parts = parts; } },
  });
  vm.runInContext(policySource, context, {filename: "kernel-policy.js"});
  vm.runInContext(runtimeSource, context, {filename: "kernel-runtime.js"});
  return {context, log, Runtime: context.GaiusKernelRuntime};
}

const tagged = (tag, size = 16) => {
  const buffer = new ArrayBuffer(size);
  new DataView(buffer).setUint32(0, tag, true);
  return buffer;
};
const tagOf = (buffer) => new DataView(buffer).getUint32(0, true);
const KERNELS = {
  mesh: {kinds: ["mesh_section", "spin"], variants: {simd: {bytes: encodeModule({name: "mesh", kinds: ["mesh_section", "spin"]})}}},
  worldgen: {kinds: ["gen_chunk"], variants: {
    simd: {bytes: encodeModule({name: "worldgen-simd", kinds: ["gen_chunk"], failCompile: true})},
    baseline: {bytes: encodeModule({name: "worldgen-baseline", kinds: ["gen_chunk"]})},
  }},
  light: {kinds: ["light_section", "trap"], variants: {baseline: {bytes: encodeModule({name: "light", kinds: ["light_section", "trap"]})}}},
};

function create(env, extra = {}) {
  return env.Runtime.create(Object.assign({
    kernels: KERNELS, workerUrl: "kernel-worker.js", search: "",
    switches: {enabled: true, off: new Set(), simd: "auto", size: 1, budgetBytes: 0},
    sizingIntervalMs: 60_000, instanceEstimateBytes: 1 << 20, mobile: false,
  }, extra));
}

const checks = [];
const check = (name, fn) => checks.push([name, fn]);

check("feature switches: URL parameters and stored settings", async () => {
  const {Runtime} = createEnv();
  const storage = {getItem: () => JSON.stringify({off: ["light"], simd: "off", workers: 3})};
  const switches = Runtime.readSwitches("?gaiusKernelsOff=mesh&gaiusKernelBudgetMB=64", storage);
  assert.deepEqual([...switches.off].sort(), ["light", "mesh"]);
  assert.equal(switches.simd, "off");
  assert.equal(switches.size, 3);
  assert.equal(switches.budgetBytes, 64 * 1024 * 1024);
  assert.equal(Runtime.readSwitches("?gaiusKernels=0", null).enabled, false);
  // The real SIMD probe module is valid wasm with SIMD (node supports it).
  assert.equal(WebAssembly.validate(new Uint8Array(Runtime.SIMD_PROBE)), true);
});

check("one queue across kernels: visible mesh, then near generation, far, background", async () => {
  const env = createEnv();
  const runtime = create(env);
  runtime.setViewer({x: 8, z: 8, t: 0});
  await runtime.warm();
  // Occupy the single worker so the next submits queue together.
  const blocker = runtime.submit("spin", tagged(1), {kernel: "mesh"});
  await new Promise((resolve) => setTimeout(resolve, 5));
  const results = Promise.all([
    runtime.submit("gen_chunk", tagged(40), {background: true}),
    runtime.submit("gen_chunk", tagged(30), {cx: 30, cz: 30}),
    runtime.submit("light_section", tagged(20), {visible: true}),
    runtime.submit("gen_chunk", tagged(25), {cx: 1, cz: 0}),
    runtime.submit("mesh_section", tagged(10), {visible: true}),
  ]);
  await blocker;
  const values = await results;
  assert.deepEqual(values.map(tagOf), [40, 30, 20, 25, 10], "results resolve to their own jobs");
  const order = env.log.order.filter((entry) => !entry.endsWith(":1")).map((entry) => Number(entry.split(":")[2]));
  assert.deepEqual(order, [10, 20, 25, 30, 40]);
  const telemetry = runtime.telemetry();
  assert.equal(telemetry.kernels.worldgen.variant, "baseline", "the SIMD build failed to compile: baseline");
  assert.equal(telemetry.kernels.mesh.variant, "simd");
  assert.deepEqual([...telemetry.workers[0].loaded].sort(), ["light", "mesh", "worldgen"]);
  assert.equal(telemetry.classes[0].completed, 2, "the blocker and the visible mesh job");
  runtime.terminate();
});

check("movement prediction reorders queued generation toward where the player goes", async () => {
  const env = createEnv();
  const runtime = create(env);
  await runtime.warm();
  const blocker = runtime.submit("spin", tagged(1), {kernel: "mesh"});
  const blocker2 = runtime.submit("spin", tagged(2), {kernel: "mesh"});
  await new Promise((resolve) => setTimeout(resolve, 5));
  const jobs = [];
  for (const cx of [-6, -3, 3, 6]) jobs.push(runtime.submit("gen_chunk", tagged(100 + cx), {cx, cz: 0, near: true, priorityClass: 2}));
  // The player flies west (-X) at about 12 blocks per second.
  for (let i = 0; i <= 8; i++) runtime.setViewer({x: 8 - i * 3, z: 8, t: i * 250});
  runtime.rescore();
  await Promise.all([blocker, blocker2]);
  await Promise.all(jobs);
  const order = env.log.order.filter((entry) => entry.startsWith("worldgen")).map((entry) => Number(entry.split(":")[2]) - 100);
  assert.deepEqual(order.slice(0, 2), [-3, -6], `chunks ahead (west) first, got ${order}`);
  runtime.terminate();
});

check("memory budget backpressure refuses far work and keeps visible work queued", async () => {
  const env = createEnv();
  const runtime = create(env, {switches: {enabled: true, off: new Set(), simd: "auto", size: 1, budgetBytes: 8 * 1024 * 1024}});
  await runtime.warm();
  const accepted = [];
  let refused = null;
  for (let i = 0; i < 16 && !refused; i++) {
    try {
      accepted.push(runtime.submit("gen_chunk", tagged(i, 1 << 20), {cx: 50 + i, cz: 50, priorityClass: 3}).catch(() => null));
    } catch (error) {
      refused = error;
    }
  }
  const rejection = await runtime.submit("gen_chunk", tagged(99, 1 << 20), {cx: 90, cz: 90, priorityClass: 3}).then(() => null, (error) => error);
  assert.equal(rejection && rejection.code, "backpressure");
  const visible = runtime.submit("mesh_section", tagged(7, 1 << 20), {visible: true});
  assert.equal(tagOf(await visible), 7, "visible work is never refused");
  await Promise.all(accepted);
  assert.ok(runtime.telemetry().backpressure >= 1);
  runtime.terminate();
});

check("a kernel that keeps trapping is disabled; its callers fall back", async () => {
  const env = createEnv();
  const runtime = create(env, {maxKernelFailures: 3});
  for (let i = 0; i < 3; i++) {
    const error = await runtime.submit("trap", tagged(i)).then(() => null, (failure) => failure);
    assert.equal(error.code, "kernel-trap");
  }
  assert.equal(runtime.available("light"), false);
  assert.equal(runtime.available("mesh"), true);
  const disabled = await runtime.submit("light_section", tagged(5)).then(() => null, (error) => error);
  assert.equal(disabled.code, "kernel-disabled");
  assert.equal(tagOf(await runtime.submit("mesh_section", tagged(6))), 6, "other kernels keep running");
  runtime.terminate();
});

check("switched-off kernels, no SIMD and dedupe/supersede by key", async () => {
  const env = createEnv({simd: false});
  const runtime = create(env, {switches: {enabled: true, off: new Set(["light"]), simd: "auto", size: 1, budgetBytes: 0}});
  assert.equal(runtime.available("light"), false);
  const error = await runtime.submit("light_section", tagged(1)).then(() => null, (failure) => failure);
  assert.equal(error.code, "kernel-disabled");
  assert.equal(runtime.features.simd, false);
  const meshKernel = Object.assign({}, KERNELS.mesh, {variants: {
    simd: KERNELS.mesh.variants.simd.bytes && {bytes: encodeModule({name: "mesh-simd", kinds: ["mesh_section", "spin"]})},
    baseline: {bytes: encodeModule({name: "mesh-baseline", kinds: ["mesh_section", "spin"]})},
  }});
  const noSimd = create(env, {kernels: {mesh: meshKernel}});
  await noSimd.warm();
  assert.equal(noSimd.telemetry().kernels.mesh.variant, "baseline");
  const first = noSimd.submit("mesh_section", tagged(1), {key: "section:0:0:0", version: 1});
  const same = noSimd.submit("mesh_section", tagged(1), {key: "section:0:0:0", version: 1});
  assert.equal(first, same, "same key and version share one job");
  const newer = noSimd.submit("mesh_section", tagged(2), {key: "section:0:0:0", version: 2});
  const superseded = await first.then(() => null, (failure) => failure);
  assert.equal(superseded.code, "superseded");
  assert.equal(tagOf(await newer), 2);
  runtime.terminate();
  noSimd.terminate();
});

check("the server Worker client: MessagePort submit, viewer and status", async () => {
  const env = createEnv();
  const runtime = create(env);
  const channel = new MessageChannel();
  runtime.attachPort(channel.port1);
  const client = env.Runtime.connect(channel.port2);
  const status = await client.ready;
  assert.equal(status.enabled, true);
  assert.equal(client.available("worldgen"), true);
  client.setViewer({x: 100, z: -40, vx: 0, vz: -20, t: 0});
  const result = await client.submit("gen_chunk", tagged(77), {kernel: "worldgen", cx: 6, cz: -4});
  assert.equal(tagOf(result), 77);
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(runtime.telemetry().motion.known, true, "the server's viewer reached the predictor");
  runtime.terminate();
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(client.available(), false, "a terminated runtime tells its clients");
  channel.port2.close();
});

let failed = 0;
for (const [name, fn] of checks) {
  try {
    await fn();
    console.log(`ok   ${name}`);
  } catch (error) {
    failed++;
    console.log(`FAIL ${name}`);
    console.log(error && error.stack || error);
  }
}
if (failed) {
  console.log(`${failed} of ${checks.length} kernel runtime checks failed`);
  process.exit(1);
}
console.log(`all ${checks.length} kernel runtime checks passed`);
