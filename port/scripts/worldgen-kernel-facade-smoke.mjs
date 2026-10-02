#!/usr/bin/env node
// Worldgen kernel facade (port/web/kernels/worldgen-kernel.js) against a stand-in for the shared
// kernel runtime client (server-worker-bootstrap.js __gaiusKernelClient).
//
//   node port/scripts/worldgen-kernel-facade-smoke.mjs [wasm-dir]
//
// wasm-dir holds gaius_worldgen_wasm.simd.wasm (or .baseline.wasm) and 26.3.gwir / 26.2.gwir
// (default port/native/target/wasm-worldgen; see worldgen-kernel-pool-smoke.mjs for the build).
// The stand-in runs jobs in-process on one wasm instance with the kernel-worker.js calling
// convention. Checks: the facade is unusable without a runner and usable with the client, jobs
// carry the "worldgen" kernel and the chunk position, terrain and biome results reach the Java
// callbacks as flat arrays of the right sizes, jobs carry the server-side distance hint, a kept
// noise chunk plus its surface diff reproduce the full surface result, a transient refusal reports
// "transient:" and keeps the generator, a kernel failure disables the generator, and drain()
// settles a job the runner never answers.
import assert from "node:assert/strict";
import {existsSync, readFileSync} from "node:fs";
import {dirname, join} from "node:path";
import {fileURLToPath} from "node:url";
import vm from "node:vm";

const here = dirname(fileURLToPath(import.meta.url));
const kernelsDir = join(here, "..", "web", "kernels");
const [wasmDir = join(here, "..", "native", "target", "wasm-worldgen")] = process.argv.slice(2);
const wasmFile = ["simd", "baseline"].map((v) => join(wasmDir, `gaius_worldgen_wasm.${v}.wasm`)).find(existsSync);
assert.ok(wasmFile, `no worldgen wasm module in ${wasmDir}`);

const context = vm.createContext({console, URLSearchParams, WebAssembly, BigInt, Map, Set, Promise,
  setInterval, clearInterval, setTimeout, clearTimeout});
context.globalThis = context;
context.location = {search: ""};
vm.runInContext(readFileSync(join(kernelsDir, "worldgen-job.js"), "utf8"), context, {filename: "worldgen-job.js"});
vm.runInContext(readFileSync(join(kernelsDir, "worldgen-kernel.js"), "utf8"), context, {filename: "worldgen-kernel.js"});
const facade = context.GaiusWorldgenKernel;

// In-process runner with the kernel-worker.js calling convention.
const instance = new WebAssembly.Instance(new WebAssembly.Module(readFileSync(wasmFile)), {});
const decoder = new TextDecoder();
function run(kind, payload) {
  const exports = instance.exports;
  const input = new Uint8Array(payload);
  const ptr = exports.alloc(input.byteLength) >>> 0;
  new Uint8Array(exports.memory.buffer, ptr, input.byteLength).set(input);
  const header = exports["run_" + kind](ptr, input.byteLength) >>> 0;
  const view = new DataView(exports.memory.buffer);
  const status = view.getUint32(header, true);
  const bytes = new Uint8Array(exports.memory.buffer, view.getUint32(header + 4, true), view.getUint32(header + 8, true)).slice();
  exports.release(header);
  exports.dealloc(ptr, input.byteLength);
  if (status !== 0) {
    const error = new Error(decoder.decode(bytes));
    error.code = "kernel-error";
    throw error;
  }
  return bytes.buffer;
}

const submitted = [];
let mode = "ok";
context.__gaiusKernelClient = {
  available: (kernel) => kernel === "worldgen",
  submit(kind, payload, opts) {
    submitted.push({kind, opts});
    if (mode === "backpressure") {
      const error = new Error("kernel memory budget is full");
      error.code = "backpressure";
      return Promise.reject(error);
    }
    if (mode === "hang") return new Promise(() => {});
    if (mode === "trap") {
      const error = new Error("unreachable executed");
      error.code = "kernel-trap";
      return Promise.reject(error);
    }
    try {
      return Promise.resolve(run(kind, payload));
    } catch (error) {
      return Promise.reject(error);
    }
  },
};

function call(submit) {
  return new Promise((resolve) => submit((value) => resolve({ok: true, value}), (message) => resolve({ok: false, message})));
}

let checks = 0;
for (const profile of ["26.3", "26.2"]) {
  const irPath = join(wasmDir, `${profile}.gwir`);
  if (!existsSync(irPath)) continue;
  const key = profile === "26.3" ? 1 : 2;
  assert.equal(facade.usable(), true, "usable with the runtime client");
  assert.equal(facade.registerGenerator(key, new Uint8Array(readFileSync(irPath))), true);
  assert.equal(facade.generatorUsable(key), true);

  mode = "ok";
  submitted.length = 0;
  const terrain = await call((ok, err) => facade.submitTerrain(key, 3, -2, 1, new Int32Array(9), ok, err));
  assert.ok(terrain.ok, terrain.message);
  const chunk = terrain.value;
  assert.equal(chunk.chunkX, 3);
  assert.equal(chunk.chunkZ, -2);
  assert.equal(chunk.paletteOffsets.length, chunk.sectionCount + 1);
  assert.equal(chunk.indices.length, chunk.sectionCount * 4096);
  assert.equal(chunk.uniform.length, chunk.sectionCount);
  assert.equal(chunk.worldSurface.length, 256);
  assert.equal(chunk.oceanFloor.length, 256);
  assert.ok(chunk.palettes.length >= chunk.sectionCount);
  assert.equal(submitted[0].kind, "terrain");
  assert.equal(submitted[0].opts.kernel, "worldgen");
  assert.equal(submitted[0].opts.cx, 3);
  assert.equal(submitted[0].opts.cz, -2);

  const biomes = await call((ok, err) => facade.submitBiomes(key, 3, -2, ok, err));
  assert.ok(biomes.ok, biomes.message);
  assert.equal(biomes.value.length, chunk.sectionCount * 64);

  // Server-side priority data (BrowserChunkTaskPriority): the job carries a distance hint.
  context.__gaiusChunkPriorityStats = {playerChunk: "0,0", direction: "1,0"};
  submitted.length = 0;
  const hinted = await call((ok, err) => facade.submitBiomes(key, 4, 0, ok, err));
  assert.ok(hinted.ok, hinted.message);
  assert.ok(submitted[0].opts.distance < 4, "a chunk ahead of the player ranks closer than its distance");
  delete context.__gaiusChunkPriorityStats;

  // Kept noise chunk + surface diff == the terrain job with surface rules.
  const keep = facade.FLAG_KEEP_NOISE;
  const noise = await call((ok, err) => facade.submitTerrain(key, 0, 0, keep, new Int32Array(9), ok, err));
  assert.ok(noise.ok, noise.message);
  assert.ok(noise.value.token > 0, "the noise chunk is kept");
  const diff = await call((ok, err) => facade.submitSurfaceStored(key, noise.value.token, 0, 0, new Int32Array(9), ok, err));
  assert.ok(diff.ok, diff.message);
  assert.equal(diff.value.positions.length, diff.value.count);
  assert.ok(diff.value.count > 0, "the surface rules change blocks of this chunk");
  const full = await call((ok, err) => facade.submitTerrain(key, 0, 0, 1, new Int32Array(9), ok, err));
  assert.ok(full.ok, full.message);
  const stateAt = (c, s, i) => c.palettes[c.paletteOffsets[s] + (c.uniform[s] ? 0 : c.indices[s * 4096 + i])];
  const patched = new Map();
  for (let n = 0; n < diff.value.count; n++) patched.set(diff.value.positions[n], diff.value.states[n]);
  let mismatches = 0;
  for (let s = 0; s < full.value.sectionCount; s++) {
    for (let i = 0; i < 4096; i++) {
      const id = patched.has((s << 12) | i) ? patched.get((s << 12) | i) : stateAt(noise.value, s, i);
      if (id !== stateAt(full.value, s, i)) mismatches++;
    }
  }
  assert.equal(mismatches, 0, "noise + surface diff reproduces the surface result");
  const again = await call((ok, err) => facade.submitSurfaceStored(key, noise.value.token, 0, 0, new Int32Array(9), ok, err));
  assert.ok(!again.ok && again.message.startsWith("transient:"), "a kept chunk is consumed once");

  mode = "backpressure";
  const refused = await call((ok, err) => facade.submitTerrain(key, 4, -2, 1, new Int32Array(9), ok, err));
  assert.equal(refused.ok, false);
  assert.ok(refused.message.startsWith("transient:"), refused.message);
  assert.equal(facade.generatorUsable(key), true, "a transient refusal keeps the generator");

  mode = "trap";
  const trapped = await call((ok, err) => facade.submitTerrain(key, 5, -2, 1, new Int32Array(9), ok, err));
  assert.equal(trapped.ok, false);
  assert.ok(!trapped.message.startsWith("transient:"), trapped.message);
  assert.equal(facade.generatorUsable(key), false, "a kernel failure disables the generator");
  console.log(`ok   ${profile}: facade over the runtime client (terrain ${chunk.sectionCount} sections, biomes,`
    + ` surface diff ${diff.value.count} blocks, transient and fatal errors)`);
  checks++;
}
assert.ok(checks > 0, `no example IR in ${wasmDir}`);

// drain(): a job its runner never answers settles as transient once the capped deadline passes.
{
  const irPath = [join(wasmDir, "26.3.gwir"), join(wasmDir, "26.2.gwir")].find(existsSync);
  assert.equal(facade.registerGenerator(9, new Uint8Array(readFileSync(irPath))), true);
  mode = "hang";
  const hung = call((ok, err) => facade.submitBiomes(9, 0, 0, ok, err));
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(facade.pending(), 1);
  const drained = facade.drain(50);
  assert.equal(facade.usable(), false, "no new jobs while draining");
  const outcome = await hung;
  assert.ok(!outcome.ok && outcome.message.startsWith("transient:"), outcome.message);
  assert.equal(await drained, true);
  assert.equal(facade.pending(), 0);
  facade.resume();
  console.log("ok   drain settles a hung job as transient");
  checks++;
}

delete context.__gaiusKernelClient;
assert.equal(facade.usable(), false, "no runner: Java keeps the vanilla path");
console.log(`all ${checks} worldgen kernel facade checks passed`);
