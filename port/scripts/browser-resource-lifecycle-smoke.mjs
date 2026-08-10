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

function compile(method, parameters) {
  return Function(...parameters, jsBody(method));
}

const calls = {
  deleteBuffer: 0,
  deleteProgram: 0,
  deleteTexture: 0,
  unbindProgram: 0,
};
const gl = {
  deleteBuffer() {
    calls.deleteBuffer++;
  },
  deleteProgram() {
    calls.deleteProgram++;
  },
  deleteTexture() {
    calls.deleteTexture++;
  },
  useProgram(program) {
    assert.equal(program, null, "deleting the current program did not physically unbind it");
    calls.unbindProgram++;
  },
};

const state = {
  alignedAttribCache: new Map(),
  alignedAttribCacheKeys: new Map(),
  boundBuffers: new Map(),
  bufferBytes: new Map(),
  bufferShadowTouch: new Map(),
  bufferSizes: new Map(),
  bufferVersions: new Map(),
  buffers: new Map(),
  currentProgram: 0,
  framebufferColorTextureMisses: new Set(),
  framebufferColorTextures: new Map(),
  indexedBufferBindings: new Map(),
  misalignedBufferRefs: new Map(),
  programAttribs: new Map(),
  programs: new Map(),
  shadowRequiredBuffers: new Set(),
  shiftedIndexCache: new Map(),
  shiftedIndexCacheKeys: new Map(),
  textureBindings: new Map(),
  textureBufferDefaults: new Map(),
  textureBufferInfo: new Map(),
  textureInfo: new Map(),
  textureParameters: new Map(),
  textures: new Map(),
  vaoEmu: new Map(),
  bumpDrawProgramGeneration() {},
  bumpVaoAttribVersion() {},
  clearProgramUniforms() {},
  deleteBufferShadow(buffer) {
    this.bufferBytes.delete(buffer);
    this.bufferShadowTouch.delete(buffer);
  },
  dropBufferDerivedCaches(buffer) {
    this.alignedAttribCache.delete(buffer);
    this.alignedAttribCacheKeys.delete(buffer);
    this.shiftedIndexCache.delete(buffer);
    this.shiftedIndexCacheKeys.delete(buffer);
  },
  forgetPhysicalElementBuffer() {},
  invalidateGuiItemAtlasBlitCache() {},
  refreshFramebufferOffscreen512(framebuffer) {
    this.framebufferColorTextureMisses.delete(framebuffer);
  },
  setAttribBufferPresence() {},
  setAttribMisaligned() {},
};

globalThis.window = {
  __gaiusGL: state,
  __gaiusGLStats: { textureInfo: {} },
  __gaiusWebGL: gl,
};

const deleteBuffer = compile("deleteBufferJs", ["buffer"]);
const deleteTexture = compile("deleteTexture", ["texture"]);
const deleteProgram = compile("deleteProgram", ["program"]);

const lifecycleIterations = 50_000;
for (let index = 1; index <= lifecycleIterations; index++) {
  const object = { id: index };
  state.buffers.set(index, object);
  state.bufferSizes.set(index, 4096);
  state.bufferBytes.set(index, new Uint8Array(1));
  state.bufferShadowTouch.set(index, index);
  state.bufferVersions.set(index, 3);
  state.shadowRequiredBuffers.add(index);
  state.misalignedBufferRefs.set(index, 1);
  state.alignedAttribCache.set(index, object);
  state.alignedAttribCacheKeys.set(index, new Set([index]));
  state.shiftedIndexCache.set(index, object);
  state.shiftedIndexCacheKeys.set(index, new Set([index]));
  state.boundBuffers.set(index, index);
  state.indexedBufferBindings.set(index, { buffer: index });
  state.textureBufferInfo.set(index, { buffer: index });
  deleteBuffer(index);

  state.textures.set(index, object);
  state.textureInfo.set(index, {});
  state.textureBufferDefaults.set(index, {});
  state.textureParameters.set(index, new Map());
  state.textureBufferInfo.set(index, { buffer: 0 });
  state.textureBindings.set(index, index);
  state.framebufferColorTextures.set(index, index);
  window.__gaiusGLStats.textureInfo[String(index)] = {};
  deleteTexture(index);

  state.programs.set(index, object);
  state.programAttribs.set(index, new Map());
  state.currentProgram = index;
  deleteProgram(index);
}

for (const [name, registry] of [
  ["buffers", state.buffers],
  ["bufferSizes", state.bufferSizes],
  ["bufferBytes", state.bufferBytes],
  ["bufferShadowTouch", state.bufferShadowTouch],
  ["bufferVersions", state.bufferVersions],
  ["shadowRequiredBuffers", state.shadowRequiredBuffers],
  ["misalignedBufferRefs", state.misalignedBufferRefs],
  ["alignedAttribCache", state.alignedAttribCache],
  ["alignedAttribCacheKeys", state.alignedAttribCacheKeys],
  ["shiftedIndexCache", state.shiftedIndexCache],
  ["shiftedIndexCacheKeys", state.shiftedIndexCacheKeys],
  ["indexedBufferBindings", state.indexedBufferBindings],
  ["textureBufferInfo", state.textureBufferInfo],
  ["textures", state.textures],
  ["textureInfo", state.textureInfo],
  ["textureBufferDefaults", state.textureBufferDefaults],
  ["textureParameters", state.textureParameters],
  ["textureBindings", state.textureBindings],
  ["framebufferColorTextures", state.framebufferColorTextures],
  ["framebufferColorTextureMisses", state.framebufferColorTextureMisses],
  ["programs", state.programs],
  ["programAttribs", state.programAttribs],
]) {
  assert.equal(registry.size, 0, `${name} did not return to baseline`);
}
assert.equal(Object.keys(window.__gaiusGLStats.textureInfo).length, 0);
assert.equal(state.currentProgram, 0);
assert.equal(calls.deleteBuffer, lifecycleIterations);
assert.equal(calls.deleteTexture, lifecycleIterations);
assert.equal(calls.deleteProgram, lifecycleIterations);
assert.equal(calls.unbindProgram, lifecycleIterations);

const deleteBufferBody = jsBody("deleteBufferJs");
assert.doesNotMatch(
  deleteBufferBody,
  /bumpBufferVersion\s*\(/,
  "deleteBuffer reinserted a deleted buffer version",
);
assert.match(source, /releaseMappedBuffer\(buffer\);\s*deleteBufferJs\(buffer\);/s);
assert.match(source, /MAPPED_BUFFERS\.containsKey\(target\)/);
assert.match(source, /ensureBufferNotMapped\(logicalBuffer\);/);
assert.match(source, /ensureBufferNotMapped\(buffer\);/);
assert.match(source, /finally\s*\{\s*MemoryUtil\.memFree\(mapped\.buffer\);/s);
assert.match(source, /stats\.mappedBufferRegions=count\|0;/);

console.log("Browser resource lifecycle smoke passed");
console.log(JSON.stringify({
  lifecycleIterations,
  deleted: {
    buffers: calls.deleteBuffer,
    textures: calls.deleteTexture,
    programs: calls.deleteProgram,
  },
  physicalProgramUnbinds: calls.unbindProgram,
  registriesAtBaseline: true,
  mappedRegionGuardsVerified: true,
}, null, 2));
