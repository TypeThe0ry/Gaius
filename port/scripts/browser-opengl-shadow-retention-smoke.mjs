#!/usr/bin/env node
// BrowserOpenGL shadow retention (initializeShadowRetentionJs, WebAssembly GC): fewer GPU
// readbacks for the CPU buffer shadows, on a model WebGL2 buffer state with the real
// BrowserOpenGL bodies (initializeJs, bindBuffer, bufferDataSizeJs, bufferSubDataJs,
// copyBufferSubData). A new staging buffer (other data, copied into an element buffer)
// reads back once instead of twice; a required buffer allocated without data starts with a
// zero shadow (its partial writes need no readback); over budget, unrequired shadows are
// evicted first; ?gaiusShadowKeep=0 restores the old counts; every copy stays byte-exact.
//
//   node port/scripts/browser-opengl-shadow-retention-smoke.mjs [BrowserOpenGL.java]

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import {fileURLToPath} from "node:url";

const dir = path.dirname(fileURLToPath(import.meta.url));
const source = fs.readFileSync(path.resolve(process.argv[2] || path.join(dir,
  "../overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java")), "utf8");
function jsBody(method) {
  const d = new RegExp(`(?:private|public)\\s+static\\s+native\\s+[\\w<>\\[\\]]+\\s+${method}\\s*\\(`).exec(source);
  assert.ok(d, `missing ${method}`);
  const a = source.lastIndexOf("@JSBody", d.index);
  const m = 'script = """';
  const s = source.indexOf(m, a) + m.length;
  const e = source.indexOf('"""', s);
  return source.slice(s, e);
}
const C = {ARRAY_BUFFER: 0x8892, ELEMENT_ARRAY_BUFFER: 0x8893, COPY_READ_BUFFER: 0x8f36, COPY_WRITE_BUFFER: 0x8f37};

function setup(search, wasm = false, lowMemory = false) {
  const bytes = new Map(), bindings = new Map(), kinds = new Map();
  const counts = {readbacks: 0, readbackBytes: 0};
  const gl = new Proxy({...C, getError: () => 0, isContextLost: () => false}, {get(t, k) {
    if (k in t) return t[k];
    if (k === "bindBuffer") return (target, object) => {
      if (object && !kinds.has(object)) kinds.set(object, target === C.ELEMENT_ARRAY_BUFFER ? 1 : 2);
      bindings.set(target, object || null);
    };
    if (k === "bufferData") return (target, data) => {
      const o = bindings.get(target); bytes.set(o, typeof data === "number" ? new Uint8Array(data) : new Uint8Array(data));
    };
    if (k === "bufferSubData") return (target, offset, data) => { bytes.get(bindings.get(target)).set(new Uint8Array(data.buffer, data.byteOffset, data.byteLength), offset); };
    if (k === "getBufferSubData") return (target, offset, out) => {
      counts.readbacks++; counts.readbackBytes += out.byteLength;
      out.set(bytes.get(bindings.get(target)).subarray(offset, offset + out.byteLength));
    };
    if (k === "copyBufferSubData") return (r, w, ro, wo, n) => {
      const a = bindings.get(r), b = bindings.get(w);
      assert.equal(kinds.get(a), kinds.get(b), "native copies stay within one buffer kind");
      bytes.get(b).set(bytes.get(a).slice(ro, ro + n), wo);
    };
    if (k === "createBuffer") return () => ({});
    return () => {};
  }});
  globalThis.window = {__gaiusWebGL: gl, __gaiusGLStats: {}, __gaiusMaxSingleBufferShadowBytes: 16 * 1024 * 1024,
    __gaiusMaxTotalBufferShadowBytes: 64 * 1024 * 1024};
  globalThis.performance = {now: () => 1};
  globalThis.location = {search};
  if (wasm) window.__gaiusWasmMemory = {};
  window.__gaiusMemoryTier = {lowMemory};
  new Function(jsBody("initializeJs"))();
  const state = window.__gaiusGL;
  state.hasUsableBaseVertexExtension = () => true;
  const vao = {elementArrayBuffer: 0, elementArrayBufferObject: null};
  state.getVaoEmu = () => vao;
  state.bindPhysicalElementBuffer = (v, o) => gl.bindBuffer(C.ELEMENT_ARRAY_BUFFER, o || null);
  state.replaceVaoBufferRef = () => {}; state.ensureLogicalElementBuffer = () => {}; state.noteBufferUpload = () => {};
  new Function(jsBody("initializeShadowRetentionJs"))();
  const run = (name, params, args) => new Function(...params, jsBody(name))(...args);
  const ctx = {
    gl, state, counts, bytes,
    create(id) { state.buffers.set(id, {id}); },
    bind(target, id) { run("bindBuffer", ["target", "buffer"], [target, id]); },
    allocate(target, id, size) { ctx.bind(target, id); run("bufferDataSizeJs", ["target", "size", "usage"], [target, size, 0x88E8]); },
    write(target, offset, data) { run("bufferSubDataJs", ["target", "offset", "data"], [target, offset, data]); },
    copy(from, to, offset, targetOffset, size) {
      ctx.bind(C.COPY_READ_BUFFER, from); ctx.bind(C.COPY_WRITE_BUFFER, to);
      run("copyBufferSubData", ["sourceTarget", "targetTarget", "sourceOffset", "targetOffset", "size"],
        [C.COPY_READ_BUFFER, C.COPY_WRITE_BUFFER, offset, targetOffset, size]);
    },
    physical(id) { return bytes.get(state.buffers.get(id)); },
  };
  return ctx;
}

for (const [wasm, lowMemory, search, expected] of [
  [false, true, "", 64], [true, true, "", 16], [true, false, "", 32],
  [true, true, "?gaiusShadowBudgetMB=off", 64], [true, true, "?gaiusShadowBudgetMB=0", 0],
  [true, true, "?gaiusShadowBudgetMB=bad", 16], [true, false, "?gaiusShadowBudgetMB=999", 64]
]) {
  const context = setup(search, wasm, lowMemory);
  assert.equal(context.state.maxTotalBufferShadowBytes(), expected * 1048576);
}

// A staging cycle as StagedVertexBuffer runs it: a partial write of [0, used) into the staging
// buffer, then its index range copied into the element buffer.
function stagingFrames(search, frames, wasm = false, lowMemory = false) {
  const c = setup(search, wasm, lowMemory);
  c.create(1); c.create(2);
  c.allocate(C.ELEMENT_ARRAY_BUFFER, 2, 65536);
  c.allocate(C.COPY_WRITE_BUFFER, 1, 262144);
  const perFrame = [];
  for (let frame = 0; frame < frames; frame++) {
    const before = c.counts.readbacks;
    const data = new Uint8Array(4096).map((_, i) => (i * 3 + frame) & 255);
    c.bind(C.COPY_WRITE_BUFFER, 1);
    c.write(C.COPY_WRITE_BUFFER, 0, data);
    c.copy(1, 2, 2048, 128 * frame, 1024);
    assert.deepEqual(c.physical(2).slice(128 * frame, 128 * frame + 1024), data.slice(2048, 3072), `frame ${frame} indices exact`);
    perFrame.push(c.counts.readbacks - before);
  }
  return {c, perFrame};
}

const kept = stagingFrames("", 6);
assert.deepEqual(kept.perFrame, [0, 0, 0, 0, 0, 0], "staging retains CPU bytes before its first copy");
assert.equal(kept.c.state.shadowRetention.readbacks.calls, 0);
const old = stagingFrames("?gaiusShadowKeep=0", 6);
assert.deepEqual(old.perFrame, [1, 1, 0, 0, 0, 0], "the old path read back twice");
const minimal = stagingFrames("?gaiusShadowBudgetMB=0", 6, true, true);
assert.equal(minimal.c.state.maxTotalBufferShadowBytes(), 0);
assert.ok(minimal.c.counts.readbacks > 0, "a zero shadow budget falls back to byte-exact readback");

// A required buffer reallocated without data starts with a zero shadow.
{
  const c = setup("");
  c.create(5); c.create(6);
  c.allocate(C.ELEMENT_ARRAY_BUFFER, 6, 4096);
  c.allocate(C.COPY_WRITE_BUFFER, 5, 8192);
  c.bind(C.COPY_WRITE_BUFFER, 5); c.write(C.COPY_WRITE_BUFFER, 0, new Uint8Array(8192).fill(7));
  c.copy(5, 6, 0, 0, 64);
  const before = c.counts.readbacks;
  c.allocate(C.COPY_WRITE_BUFFER, 5, 16384);
  assert.ok(c.state.bufferBytes.get(5) && c.state.bufferBytes.get(5).byteLength === 16384, "zero shadow for the new storage");
  c.bind(C.COPY_WRITE_BUFFER, 5); c.write(C.COPY_WRITE_BUFFER, 100, new Uint8Array(50).fill(9));
  c.copy(5, 6, 90, 0, 80);
  assert.equal(c.counts.readbacks, before, "no readback after reallocation");
  const expected = new Uint8Array(80); expected.fill(9, 10, 60);
  assert.deepEqual(c.physical(6).slice(0, 80), expected, "zeros and the written bytes reach the element buffer");
}

// Over budget, a shadow nothing requires is evicted before a required one.
{
  const c = setup("");
  window.__gaiusMaxTotalBufferShadowBytes = 3 * 65536;
  c.create(10); c.create(11); c.create(12);
  c.state.markBufferShadowRequired(10, "test");
  for (const id of [10, 11]) {
    c.allocate(id === 11 ? C.ARRAY_BUFFER : C.COPY_WRITE_BUFFER, id, 65536);
    c.state.shadowBufferData(id, new Uint8Array(65536).fill(id), 65536);
  }
  c.state.touchBufferShadow(11, 65536);
  c.allocate(C.COPY_WRITE_BUFFER, 12, 65536);
  c.state.shadowBufferData(12, new Uint8Array(2 * 65536).fill(12), 2 * 65536);
  assert.ok(c.state.bufferBytes.has(10), "the required shadow stays");
  assert.ok(!c.state.bufferBytes.has(11), "the unrequired shadow goes first");
}
console.log(`browser-opengl-shadow-retention-smoke: ok (staging readbacks ${JSON.stringify(kept.perFrame)} vs old ${JSON.stringify(old.perFrame)})`);
