#!/usr/bin/env node

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const sourcePath = new URL(
  "../overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java",
  import.meta.url,
);
const source = readFileSync(sourcePath, "utf8");

function jsBody(method) {
  const escaped = method.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const declaration = new RegExp(
    `(?:private|public)\\s+static\\s+native\\s+[\\w<>\\[\\]]+\\s+${escaped}\\s*\\(`,
  ).exec(source);
  assert.ok(declaration, `missing native declaration for ${method}`);
  const annotationStart = source.lastIndexOf("@JSBody", declaration.index);
  const marker = 'script = """';
  const bodyStart = source.indexOf(marker, annotationStart);
  const contentStart = bodyStart + marker.length;
  const bodyEnd = source.indexOf('"""', contentStart);
  assert.ok(
    annotationStart >= 0 && bodyStart >= 0 && bodyEnd >= 0 && bodyEnd < declaration.index,
    `missing JS body for ${method}`,
  );
  return source.slice(contentStart, bodyEnd);
}

function compile(method, parameters = []) {
  return Function(...parameters, jsBody(method));
}

const GL = {
  ARRAY_BUFFER: 0x8892,
  ELEMENT_ARRAY_BUFFER: 0x8893,
  COPY_READ_BUFFER: 0x8f36,
  COPY_WRITE_BUFFER: 0x8f37,
  STATIC_DRAW: 0x88e4,
  UNSIGNED_BYTE: 0x1401,
  UNSIGNED_SHORT: 0x1403,
  UNSIGNED_INT: 0x1405,
};

let nextObjectId = 1;
const deletedObjects = new Set();
const physicalBindings = new Map();
const gpuBytes = new Map();
const frameCallbacks = [];
let failNextBufferUpload = false;

function copyBytes(value) {
  if (typeof value === "number") return new Uint8Array(value);
  if (!value) return new Uint8Array(0);
  return Uint8Array.from(
    new Uint8Array(value.buffer, value.byteOffset || 0, value.byteLength),
  );
}

const gl = {
  ...GL,
  createBuffer() {
    return { id: nextObjectId++ };
  },
  deleteBuffer(object) {
    if (!object) return;
    deletedObjects.add(object);
    gpuBytes.delete(object);
  },
  bindBuffer(target, object) {
    physicalBindings.set(target, object || null);
  },
  bufferData(target, data) {
    if (failNextBufferUpload) {
      failNextBufferUpload = false;
      throw new Error("injected derived buffer upload failure");
    }
    const object = physicalBindings.get(target);
    assert.ok(object, `bufferData target ${target} has no physical buffer`);
    gpuBytes.set(object, copyBytes(data));
  },
  getBufferSubData(target, offset, destination) {
    const object = physicalBindings.get(target);
    const bytes = object ? gpuBytes.get(object) : null;
    assert.ok(bytes, `getBufferSubData target ${target} has no storage`);
    destination.set(bytes.subarray(offset, offset + destination.byteLength));
  },
  getExtension() {
    return null;
  },
  drawElements() {},
  drawElementsInstanced() {},
  vertexAttribPointer() {},
  vertexAttribIPointer() {},
};

globalThis.requestAnimationFrame = (callback) => {
  frameCallbacks.push(callback);
  return frameCallbacks.length;
};
globalThis.window = {
  __gaiusWebGL: gl,
  __gaiusGLStats: {},
  __gaiusBufferShadowBudgetBytes: 4096,
  __gaiusMaxSingleBufferShadowBytes: 1024,
  __gaiusBaseVertexDerivedBufferBudgetBytes: 1024,
  __gaiusAlignedAttribDerivedBufferBudgetBytes: 1024,
};

compile("initializeJs")();
compile("initializeDrawCompatibilityJs")();
compile("initializeElementBufferStateJs")();
compile("initializeGpuHotPathJs")();
compile("initializeShadowDecisionCache")();

const state = window.__gaiusGL;
const stats = window.__gaiusGLStats;
state.hotPathTelemetryEnabled = true;
state.indexedBufferBindings = new Map();

const activeMetadata = [{ location: 0, name: "Position" }];
activeMetadata.byLocation = new Map([[0, activeMetadata[0]]]);
state.programAttribs.set(99, activeMetadata);
state.currentProgram = 99;
const activeLocationIdentity = state.activeAttribLocations();
for (let iteration = 0; iteration < 100_000; iteration++) {
  assert.strictEqual(state.activeAttribLocations(), activeLocationIdentity,
    "active attribute lookup allocated a per-draw Set");
}

const bufferData = compile("bufferDataJs", ["target", "data", "usage"]);
const deleteBuffer = compile("deleteBufferJs", ["buffer"]);
const shiftedIndexBody = jsBody("initializeDrawCompatibilityJs");
const initializationBody = jsBody("initializeJs");
assert.equal(
  /cacheShiftedIndexBuffer=function[\s\S]*?readBufferShadow/.test(shiftedIndexBody),
  false,
  "base-vertex derivation still performs synchronous GPU readback in the draw path",
);
assert.equal(
  /evictOldestBufferShadow=function[\s\S]*?bufferBytes\.forEach/.test(initializationBody),
  false,
  "buffer-shadow eviction still scans every live shadow",
);
assert.ok(
  jsBody("deleteVertexArray").includes("state.releaseVaoShiftedIndexRefs(vao)"),
  "VAO deletion does not release shifted-index reverse references",
);

function flushFrame() {
  const callbacks = frameCallbacks.splice(0);
  for (const callback of callbacks) callback(performance.now());
}

function asUploadBytes(values) {
  return new Int8Array(values.buffer, values.byteOffset, values.byteLength);
}

function bindElementSource(logicalId, values) {
  const object = gl.createBuffer();
  state.buffers.set(logicalId, object);
  const vao = state.getVaoEmu();
  vao.elementArrayBuffer = logicalId;
  vao.elementArrayBufferObject = object;
  state.boundBuffers.set(gl.ELEMENT_ARRAY_BUFFER, logicalId);
  state.bindPhysicalElementBuffer(vao, object);
  bufferData(gl.ELEMENT_ARRAY_BUFFER, asUploadBytes(values), gl.STATIC_DRAW);
  return vao;
}

function uploadedValues(entry) {
  const bytes = gpuBytes.get(entry.buffer);
  assert.ok(bytes, "derived buffer has no GPU storage");
  if (entry.type === gl.UNSIGNED_BYTE) return [...bytes];
  if (entry.type === gl.UNSIGNED_SHORT) {
    return [...new Uint16Array(bytes.buffer, bytes.byteOffset, bytes.byteLength / 2)];
  }
  return [...new Uint32Array(bytes.buffer, bytes.byteOffset, bytes.byteLength / 4)];
}

function assertBudgets() {
  assert.ok(
    state.bufferShadowTotalBytes <= state.maxTotalBufferShadowBytes(),
    `CPU shadow budget exceeded: ${state.bufferShadowTotalBytes}`,
  );
  assert.ok(
    state.shiftedIndexCacheTotalBytes <= state.maxShiftedIndexCacheBytes(),
    `derived index budget exceeded: ${state.shiftedIndexCacheTotalBytes}`,
  );
  assert.ok(
    state.alignedAttribCacheTotalBytes <= state.maxAlignedAttribCacheBytes(),
    `derived attribute budget exceeded: ${state.alignedAttribCacheTotalBytes}`,
  );
}

function assertBaseVertexBaseline() {
  assert.equal(state.bufferShadowTotalBytes, 0, "CPU shadow bytes did not return to baseline");
  assert.equal(state.shiftedIndexCacheTotalBytes, 0, "derived bytes did not return to baseline");
  assert.equal(state.bufferBytes.size, 0, "CPU shadow registry did not return to baseline");
  assert.equal(state.bufferShadowTouch.size, 0, "CPU shadow LRU did not return to baseline");
  assert.equal(state.shiftedIndexCache.size, 0, "derived cache did not return to baseline");
  assert.equal(state.shiftedIndexCacheKeys.size, 0, "derived source index did not return to baseline");
  assert.equal(state.alignedAttribCacheTotalBytes, 0,
    "derived attribute bytes did not return to baseline");
  assert.equal(state.alignedAttribCache.size, 0,
    "derived attribute cache did not return to baseline");
  assert.equal(state.alignedAttribCacheKeys.size, 0,
    "derived attribute source index did not return to baseline");
}

// Without the extension, element uploads retain a bounded CPU shadow so a first
// base-vertex fallback never introduces a synchronous GPU readback in the draw path.
let sourceId = 1;
let vao = bindElementSource(sourceId, new Uint8Array([1, 2, 3, 4]));
assert.equal(state.bufferBytes.has(sourceId), true, "fallback upload did not retain a shadow");
const lazyEntry = state.cacheShiftedIndexBuffer(vao, gl.UNSIGNED_BYTE, 0, 4, 1);
assert.ok(lazyEntry, "upload shadow did not produce a derived buffer");
assert.deepEqual(uploadedValues(lazyEntry), [2, 3, 4, 5]);
assert.strictEqual(
  state.cacheShiftedIndexBuffer(vao, gl.UNSIGNED_BYTE, 0, 4, 1),
  lazyEntry,
  "repeat draw missed the derived cache",
);
assert.equal(stats.bufferShadowReadbacks || 0, 0, "draw path performed a GPU readback");
assert.ok(stats.baseVertexIndexCacheHits >= 1);

// Deleting a VAO must remove only that VAO from each exact derived-entry ref set.
const secondaryVao = state.newVaoEmu();
state.trackShiftedIndexEntryVao(lazyEntry, secondaryVao);
assert.equal(lazyEntry.vaoRefs.has(secondaryVao), true);
secondaryVao.shiftedIndexLast = lazyEntry;
secondaryVao.shiftedIndexFastCache.set("tracked", lazyEntry);
state.releaseVaoShiftedIndexRefs(secondaryVao);
assert.equal(lazyEntry.vaoRefs.has(secondaryVao), false,
  "deleted VAO survived in a shifted-index reverse-reference set");
assert.equal(lazyEntry.vaoRefs.has(vao), true,
  "releasing one VAO detached a still-live VAO");
assert.equal(secondaryVao.shiftedIndexEntries.size, 0);
assert.equal(secondaryVao.shiftedIndexFastCache.size, 0);
assert.equal(secondaryVao.shiftedIndexLast, null);
deleteBuffer(sourceId);
assertBaseVertexBaseline();

// A usable extension avoids the CPU copy entirely.
state.baseVertexExtensionChecked = true;
state.baseVertexExtension = {drawElementsInstancedBaseVertexWEBGL() {}};
sourceId = 2;
vao = bindElementSource(sourceId, new Uint8Array([1, 2, 3, 4]));
assert.equal(state.bufferBytes.has(sourceId), false, "extension path retained an unused shadow");
deleteBuffer(sourceId);
assertBaseVertexBaseline();
state.baseVertexExtension = null;

// CPU shadows also use access order: touching A makes B the next eviction.
window.__gaiusBufferShadowBudgetBytes = 128;
for (const logicalId of [2_001, 2_002]) {
  state.buffers.set(logicalId, gl.createBuffer());
  state.bufferSizes.set(logicalId, 64);
  state.shadowBufferData(logicalId, new Uint8Array(64), 64);
}
state.touchBufferShadow(2_001, 64);
state.buffers.set(2_003, gl.createBuffer());
state.bufferSizes.set(2_003, 64);
state.shadowBufferData(2_003, new Uint8Array(64), 64);
assert.equal(state.bufferBytes.has(2_001), true, "recently touched CPU shadow was evicted");
assert.equal(state.bufferBytes.has(2_002), false, "least-recently-used CPU shadow survived");
assert.equal(state.bufferBytes.has(2_003), true);
assertBudgets();
for (const logicalId of [2_001, 2_002, 2_003]) deleteBuffer(logicalId);
assertBaseVertexBaseline();
window.__gaiusBufferShadowBudgetBytes = 4096;

function selectMisalignedAttribSource(logicalId) {
  if (!state.buffers.has(logicalId)) {
    const payload = new Uint8Array([10, 11, 12, 13, 14]);
    state.buffers.set(logicalId, gl.createBuffer());
    state.bufferSizes.set(logicalId, payload.byteLength);
    state.bufferVersions.set(logicalId, 1);
    state.shadowBufferData(logicalId, payload, payload.byteLength);
  }
  const current = state.getVaoEmu();
  current.enabledAttribs.add(0);
  current.attribHasBuffer.add(0);
  current.misalignedAttribs.add(0);
  current.attribPointers.set(0, {
    index: 0,
    buffer: logicalId,
    size: 1,
    type: gl.UNSIGNED_BYTE,
    normalized: false,
    integer: false,
    stride: 2,
    offset: 1,
  });
  current.attribVersion = ((current.attribVersion || 0) + 1) | 0;
  current.alignedAttribVersion = -1;
  current.alignedAttribProgram = -1;
  current.alignedAttribGlobalVersion = -1;
  state.ensureAlignedAttribs();
  const keys = state.alignedAttribCacheKeys.get(logicalId);
  return keys && keys.size ? state.alignedAttribCache.get([...keys][0]) : null;
}

function clearMisalignedAttribState() {
  const current = state.getVaoEmu();
  current.enabledAttribs.delete(0);
  current.attribHasBuffer.delete(0);
  current.misalignedAttribs.delete(0);
  current.attribPointers.delete(0);
  current.missingEnabledAttribs.delete(0);
  current.alignedAttribVersion = -1;
}

// Aligned attribute copies use a byte-bounded LRU and clean up by source buffer.
window.__gaiusAlignedAttribDerivedBufferBudgetBytes = 16;
const alignedA = selectMisalignedAttribSource(3_001);
const alignedB = selectMisalignedAttribSource(3_002);
assert.ok(alignedA && alignedB);
const retainedAlignedVao = state.newVaoEmu();
retainedAlignedVao.alignedAttribVersion = 17;
retainedAlignedVao.alignedAttribProgram = 99;
retainedAlignedVao.alignedAttribGlobalVersion = state.programVersion || 0;
state.trackAlignedAttribEntryVao(alignedB, retainedAlignedVao);
assert.equal(alignedA.bytes, 8);
assert.equal(alignedB.bytes, 8);
assert.strictEqual(selectMisalignedAttribSource(3_001), alignedA,
  "repeat aligned-attrib draw missed the cache");
const alignedC = selectMisalignedAttribSource(3_003);
assert.ok(alignedC);
assert.equal(state.alignedAttribCache.has(alignedB.cacheKey), false,
  "least-recently-used aligned attribute entry survived");
assert.equal(alignedB.deleted, true,
  "evicted aligned attribute entry was not marked deleted");
assert.equal(retainedAlignedVao.alignedAttribVersion, -1,
  "aligned attribute eviction left a VAO eligible for a stale fast-skip");
assert.equal(retainedAlignedVao.alignedAttribEntries.size, 0,
  "aligned attribute eviction retained a VAO reverse reference");
assert.equal(state.alignedAttribCache.has(alignedA.cacheKey), true,
  "recently touched aligned attribute entry was evicted");
assert.ok(stats.alignedAttribEvictions > 0);
assertBudgets();
for (const logicalId of [3_001, 3_002, 3_003]) deleteBuffer(logicalId);
clearMisalignedAttribState();
assertBaseVertexBaseline();

// An entry larger than the hard budget is rejected before creating a GPU buffer.
window.__gaiusAlignedAttribDerivedBufferBudgetBytes = 4;
const createdBeforeOversize = nextObjectId;
assert.equal(selectMisalignedAttribSource(3_004), null);
assert.equal(nextObjectId, createdBeforeOversize + 1,
  "oversized aligned entry created a derived GPU buffer");
assert.ok(stats.alignedAttribBudgetFallbacks > 0);
deleteBuffer(3_004);
clearMisalignedAttribState();
assertBaseVertexBaseline();
window.__gaiusAlignedAttribDerivedBufferBudgetBytes = 1024;

// Failed derived uploads delete their transient GPU object and do not poison the cache.
const deletedBeforeFailure = deletedObjects.size;
failNextBufferUpload = true;
assert.equal(selectMisalignedAttribSource(3_005), null,
  "failed aligned attribute upload produced a cache entry");
assert.equal(deletedObjects.size, deletedBeforeFailure + 1,
  "failed aligned attribute upload leaked its transient GPU buffer");
assert.ok(stats.alignedAttribUploadFailures > 0);
deleteBuffer(3_005);
clearMisalignedAttribState();
assertBaseVertexBaseline();

// Exercise tens of thousands of shadow uploads with enough live sources to force LRU eviction.
const uploadIterations = 20_000;
const liveWindow = 256;
const payload = new Uint8Array(64);
for (let index = 0; index < uploadIterations; index++) {
  const logicalId = 10_000 + index;
  state.buffers.set(logicalId, gl.createBuffer());
  state.bufferSizes.set(logicalId, payload.byteLength);
  state.shadowBufferData(logicalId, payload, payload.byteLength);
  assertBudgets();
  if (index >= liveWindow) deleteBuffer(logicalId - liveWindow);
}
for (let index = Math.max(0, uploadIterations - liveWindow); index < uploadIterations; index++) {
  deleteBuffer(10_000 + index);
}
assertBaseVertexBaseline();
assert.ok(stats.bufferShadowEvictions > 0, "shadow stress did not evict by bytes");

// Use a tiny derived budget to prove true LRU ordering, not insertion-count eviction.
window.__gaiusBaseVertexDerivedBufferBudgetBytes = 6;
sourceId = 40_000;
vao = bindElementSource(sourceId, new Uint8Array([1, 2]));
const entryA = state.cacheShiftedIndexBuffer(vao, gl.UNSIGNED_BYTE, 0, 2, 1);
const entryB = state.cacheShiftedIndexBuffer(vao, gl.UNSIGNED_BYTE, 0, 2, 2);
const entryC = state.cacheShiftedIndexBuffer(vao, gl.UNSIGNED_BYTE, 0, 2, 3);
assert.ok(entryA && entryB && entryC);
assert.strictEqual(state.cacheShiftedIndexBuffer(vao, gl.UNSIGNED_BYTE, 0, 2, 1), entryA);
const entryD = state.cacheShiftedIndexBuffer(vao, gl.UNSIGNED_BYTE, 0, 2, 4);
assert.ok(entryD);
assert.equal(entryB.deleted, true, "least-recently-used derived entry survived");
assert.equal(entryA.deleted, false, "recently touched derived entry was evicted");
assertBudgets();
deleteBuffer(sourceId);
assert.equal(vao.shiftedIndexFastCache.size, 0, "source delete retained fast-cache entries");
assert.equal(vao.shiftedIndexLast, null, "source delete retained the last-cache entry");
assertBaseVertexBaseline();

// Generate and delete thousands of variants; deletion must release every derived GPU byte.
window.__gaiusBaseVertexDerivedBufferBudgetBytes = 1024;
sourceId = 50_000;
vao = bindElementSource(sourceId, new Uint16Array([1, 2, 3, 4]));
const deriveIterations = 12_000;
for (let base = 1; base <= deriveIterations; base++) {
  assert.ok(state.cacheShiftedIndexBuffer(vao, gl.UNSIGNED_SHORT, 0, 4, base));
  assertBudgets();
  if (base % 100 === 0) flushFrame();
}
assert.ok(state.shiftedIndexCache.size < deriveIterations, "derived cache still uses an entry cap");
assert.ok(stats.baseVertexIndexEvictions > 0, "derived stress did not evict by bytes");
deleteBuffer(sourceId);
assert.equal(vao.shiftedIndexFastCache.size, 0, "source delete left derived fast references");
assertBaseVertexBaseline();

// Rebase boundaries preserve unsigned values and fixed-index primitive restart.
function checkDerived(logicalId, values, type, base, expectedType, expectedValues) {
  const boundaryVao = bindElementSource(logicalId, values);
  const entry = state.cacheShiftedIndexBuffer(boundaryVao, type, 0, values.length, base);
  assert.ok(entry, `boundary derivation failed for type ${type}`);
  assert.equal(entry.type, expectedType);
  assert.deepEqual(uploadedValues(entry), expectedValues);
  deleteBuffer(logicalId);
  assertBaseVertexBaseline();
}

checkDerived(60_001, new Uint8Array([1, 254]), gl.UNSIGNED_BYTE, 1,
  gl.UNSIGNED_SHORT, [2, 255]);
checkDerived(60_002, new Uint16Array([1, 65534]), gl.UNSIGNED_SHORT, 1,
  gl.UNSIGNED_INT, [2, 65535]);
checkDerived(60_003, new Uint32Array([0x80000000]), gl.UNSIGNED_INT, 1,
  gl.UNSIGNED_INT, [0x80000001]);
checkDerived(60_004, new Uint8Array([1, 255]), gl.UNSIGNED_BYTE, 1,
  gl.UNSIGNED_BYTE, [2, 255]);
checkDerived(60_005, new Uint16Array([1, 65535]), gl.UNSIGNED_SHORT, 1,
  gl.UNSIGNED_SHORT, [2, 65535]);
checkDerived(60_006, new Uint32Array([1, 0xffffffff]), gl.UNSIGNED_INT, 1,
  gl.UNSIGNED_INT, [2, 0xffffffff]);

sourceId = 60_007;
vao = bindElementSource(sourceId, new Uint16Array([0]));
assert.equal(
  state.cacheShiftedIndexBuffer(vao, gl.UNSIGNED_SHORT, 0, 1, -1),
  null,
  "negative unsigned rebase did not fall back",
);
deleteBuffer(sourceId);
assertBaseVertexBaseline();

sourceId = 60_008;
vao = bindElementSource(sourceId, new Uint32Array([0xfffffffe]));
state.drawElementsWithBaseVertex(vao, 4, 1, gl.UNSIGNED_INT, 0, 1, 1);
assert.ok(stats.baseVertexIndexFallbacks >= 1, "restart collision did not use fallback draw");
deleteBuffer(sourceId);
assertBaseVertexBaseline();

flushFrame();
assertBudgets();
assert.ok(stats.bufferShadowPeakBytes <= window.__gaiusBufferShadowBudgetBytes);
assert.ok(stats.baseVertexIndexPeakBytes <= 1024);
assert.ok(stats.bufferShadowCopyBytes > 0);
assert.ok(stats.bufferShadowCopyMs >= 0);
assert.ok(stats.baseVertexIndexCopyBytes > 0);
assert.ok(stats.baseVertexIndexCopyMs >= 0);
assert.ok(stats.baseVertexIndexCacheMisses > 0);
assert.ok(stats.baseVertexIndexCreatedFrameHighWater > 0);

console.log("Base-vertex cache budget smoke passed");
console.log(JSON.stringify({
  uploadIterations,
  deriveIterations,
  shadow: {
    budgetBytes: stats.bufferShadowBudgetBytes,
    liveBytes: stats.bufferShadowLiveBytes,
    peakBytes: stats.bufferShadowPeakBytes,
    evictions: stats.bufferShadowEvictions,
    copyBytes: stats.bufferShadowCopyBytes,
    copyMs: stats.bufferShadowCopyMs,
  },
  derived: {
    budgetBytes: stats.baseVertexIndexBudgetBytes,
    liveBytes: stats.baseVertexIndexLiveBytes,
    peakBytes: stats.baseVertexIndexPeakBytes,
    evictions: stats.baseVertexIndexEvictions,
    hits: stats.baseVertexIndexCacheHits,
    misses: stats.baseVertexIndexCacheMisses,
    fallbacks: stats.baseVertexIndexFallbacks,
    createdFrameHighWater: stats.baseVertexIndexCreatedFrameHighWater,
  },
}, null, 2));
