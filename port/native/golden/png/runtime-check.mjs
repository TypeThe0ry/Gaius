import { readFileSync } from "node:fs";
import { createContext, runInContext } from "node:vm";
import { performance } from "node:perf_hooks";
import assert from "node:assert/strict";

const [wasmPath, pngPath] = process.argv.slice(2);
const bytes = readFileSync(wasmPath);
const encoded = new Uint8Array(readFileSync(pngPath));
const bridge = readFileSync(new URL("../../../web/runtime/wasmgc/png-kernel.js", import.meta.url), "utf8");
const boot = readFileSync(new URL("../../../web/runtime/wasmgc/gaius-wasm-boot.js", import.meta.url), "utf8");
const loaded = await WebAssembly.instantiate(bytes, {});
const exports = loaded.instance.exports;
let releases = 0;
let deallocations = 0;
let corruptResult = false;
let releaseFailure = false;
let deallocationFailure = false;
const wrapped = { ...exports,
  release(pointer) {
    releases++;
    exports.release(pointer);
    if (releaseFailure) throw new Error("release failure");
  },
  dealloc(pointer, length) {
    deallocations++;
    exports.dealloc(pointer, length);
    if (deallocationFailure) throw new Error("deallocation failure");
  },
  run_png_decode(pointer, length) {
    const descriptor = exports.run_png_decode(pointer, length);
    if (corruptResult) {
      const view = new DataView(exports.memory.buffer);
      view.setUint32(view.getUint32(descriptor + 4, true), 0, true);
    }
    return descriptor;
  }
};
let fetches = 0;
const context = createContext({ Uint8Array, Int8Array, DataView, Number, performance, console,
  AbortController, setTimeout, clearTimeout,
  WebAssembly: { Memory: WebAssembly.Memory, instantiate: async () => ({ instance: { exports: wrapped } }) },
  fetch: async () => { fetches++; throw new Error("no network in check"); } });
runInContext(bridge, context);
const kernel = context.GaiusPngKernel;
assert.equal(await kernel.initialize(), false);
assert.equal(fetches, 0);
kernel.setEnabled(true);
assert.equal(await kernel.initialize({ bytes }), true);
const expected = kernel.decode(encoded, 4);
assert.ok(expected);
assert.equal(releases, 1);
assert.equal(deallocations, 1);
const damaged = encoded.slice();
damaged[29] ^= 1;
assert.equal(kernel.decode(damaged, 4), null, "bad CRC declines to Java");
assert.equal(kernel.ready, true, "an ordinary decline does not disable the kernel");
assert.equal(releases, 2);
assert.equal(deallocations, 2);
exports.memory.grow(1);
assert.deepEqual(kernel.decode(encoded, 4), expected, "memory growth must refresh views");
for (let warmup = 0; warmup < 10; warmup++) assert.ok(kernel.decode(encoded, 4));
const size = exports.memory.buffer.byteLength;
for (let repeat = 0; repeat < 1000; repeat++) assert.ok(kernel.decode(encoded, 4));
assert.equal(exports.memory.buffer.byteLength, size, "allocations are released");
corruptResult = true;
assert.equal(kernel.decode(encoded, 4), null);
assert.equal(kernel.ready, false, "a corrupt result disables the instance");
assert.equal(releases, deallocations);
assert.equal(kernel.metrics.failures, 1);
corruptResult = false;
for (const operation of ["release", "dealloc"]) {
  assert.equal(await kernel.initialize({ bytes }), true);
  const successes = kernel.metrics.successes;
  releaseFailure = operation === "release";
  deallocationFailure = operation === "dealloc";
  assert.equal(kernel.decode(encoded, 4), null, "cleanup failure cannot return successful pixels");
  assert.equal(kernel.ready, false);
  assert.equal(kernel.metrics.successes, successes);
  assert.equal(releases, deallocations, "input is freed even if result release throws");
  releaseFailure = false;
  deallocationFailure = false;
}
assert.equal(kernel.metrics.failures, 3);

for (const [change, expectedStatus] of [
  [(job) => job.setUint32(0, 0, true), 1],
  [(job) => job.setUint16(4, 2, true), 2],
  [(job) => job.setUint16(6, 0x0101, true), 3],
  [(job) => job.setUint32(12, encoded.length + 100, true), 4]
]) {
  const length = encoded.length + 20;
  const pointer = exports.alloc(length);
  const job = new DataView(exports.memory.buffer, pointer, length);
  job.setUint32(0, 0x424a4b47, true);
  job.setUint16(4, 1, true);
  job.setUint16(6, 0x0701, true);
  job.setUint32(8, 42, true);
  job.setUint32(12, encoded.length + 4, true);
  job.setUint32(16, 4, true);
  new Uint8Array(exports.memory.buffer, pointer + 20, encoded.length).set(encoded);
  change(job);
  const descriptor = exports.run_png_decode(pointer, length);
  assert.equal(new DataView(exports.memory.buffer).getUint32(descriptor, true), expectedStatus);
  exports.release(descriptor);
  exports.dealloc(pointer, length);
}

async function checkBoot(search, fail) {
  const scripts = [];
  let browser;
  const sandbox = {
    Uint8Array, Int8Array, DataView, Number, URLSearchParams, performance, console,
    AbortController, setTimeout, clearTimeout,
    WebAssembly: { Memory: WebAssembly.Memory, instantiate: WebAssembly.instantiate,
      Suspending: function () {}, promising: function () {} },
    fetch: async () => { throw new Error("unexpected fetch"); },
    location: { search },
    __gaiusPngWasmBytes: fail ? new Uint8Array(0) : bytes,
    __gaiusWasmSource: new Uint8Array(0),
    TeaVM: { wasmGC: { load: async () => ({ instance: { exports: {} } }) } },
    GaiusJspi: {},
    document: { createElement: () => ({}), head: { appendChild(script) {
      scripts.push(script.src);
      runInContext(bridge, browser);
      script.onload();
    } } }
  };
  sandbox.window = sandbox;
  browser = createContext(sandbox);
  runInContext(boot, browser);
  await browser.__gaiusClientReady;
  const enabled = search === "?gaiusPng=1";
  assert.deepEqual(scripts, enabled ? ["png-kernel.js"] : []);
  assert.equal(browser.__gaiusBootError, undefined);
  if (enabled) {
    await browser.__gaiusPngReady;
    assert.equal(browser.GaiusPngKernel.ready, !fail);
  }
}
await checkBoot("", false);
await checkBoot("?gaiusPng=0", false);
await checkBoot("?gaiusPng=1", false);
await checkBoot("?gaiusPng=1", true);
console.log("PNG runtime: default-off, fallback, framing, ownership, memory growth and boot checks passed");
