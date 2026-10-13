#!/usr/bin/env node
// Executes the embedded sampler and texture binding glue, including target
// alias transitions, rather than a second implementation of the policy.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const source = readFileSync(new URL('../overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java', import.meta.url), 'utf8');
function body(method) {
  const end = source.indexOf(`native void ${method}(`);
  assert.ok(end >= 0, method);
  const begin = source.lastIndexOf('@JSBody(', end);
  return source.slice(begin, end).match(/script = """([\s\S]*?)"""\)/)[1];
}
const calls = [];
const gl = {TEXTURE_2D: 3553, TEXTURE_MIN_FILTER: 10241, TEXTURE_MAG_FILTER: 10240,
  TEXTURE_WRAP_S: 10242, TEXTURE_WRAP_T: 10243, NEAREST: 9728, CLAMP_TO_EDGE: 33071,
  bindSampler(unit, obj) {calls.push([unit, obj]);}, bindTexture() {}, texParameteri() {}, deleteSampler() {}};
const samplerA = {}, samplerB = {}, texture = {};
const state = {samplers: new Map([[1, samplerA], [2, samplerB]]), textures: new Map([[10, texture]]),
  samplerBindings: new Map(), effectiveSamplerBindings: new Map(), textureBufferUnits: new Set(),
  activeTextureUnit: 0, textureBindings: new Map(), textureParameters: new Map(), textureBufferDefaults: new Set()};
const window = {__gaiusGL: state, __gaiusWebGL: gl};
const helper = source.match(/state\.applySamplerBinding=function\(unit\) \{([\s\S]*?)\n            \};/);
assert.ok(helper, 'sampler policy helper');
state.applySamplerBinding = new Function('window', `return function(unit) {${helper[1]}}`)(window);
const bindSampler = new Function('window', 'unit', 'sampler', body('bindSampler')).bind(null, window);
const bindTexture = new Function('window', 'target', 'texture', body('bindTextureJs')).bind(null, window);
bindSampler(0, 1);
assert.deepEqual(calls.at(-1), [0, samplerA]);
bindTexture(0x8c2a, 10);
assert.deepEqual(calls.at(-1), [0, null], 'buffer texels ignore a previous linear sampler');
assert.equal(state.samplerBindings.get(0), 1, 'requested sampler is preserved');
let count = calls.length;
bindTexture(0x8c2a, 10);
bindSampler(0, 1);
assert.equal(calls.length, count, 'same effective binding is skipped');
bindSampler(0, 2);
assert.equal(calls.length, count, 'sampler updates while buffer is bound stay suppressed');
bindTexture(gl.TEXTURE_2D, 10);
assert.deepEqual(calls.at(-1), [0, samplerB], 'same texture object changing target restores sampler');
bindTexture(0x8c2a, 10);
bindSampler(0, 0);
bindTexture(gl.TEXTURE_2D, 10);
assert.deepEqual(calls.at(-1), [0, null], 'explicit sampler zero survives target transitions');
state.activeTextureUnit = 1;
bindSampler(1, 1);
bindTexture(gl.TEXTURE_2D, 10);
assert.deepEqual(calls.at(-1), [1, samplerA], 'units remain independent');
assert.equal(state.effectiveSamplerBindings.get(0), 0);
const deleteSampler = new Function('window', 'sampler', body('deleteSampler')).bind(null, window);
deleteSampler(1);
assert.deepEqual(calls.at(-1), [1, null], 'deletion clears the restored physical sampler');
state.activeTextureUnit = 0;
bindSampler(0, 2);
bindTexture(0x8c2a, 10);
deleteSampler(2);
bindTexture(gl.TEXTURE_2D, 10);
assert.deepEqual(calls.at(-1), [0, null], 'deletion while suppressed cannot restore a deleted sampler');
const perf = readFileSync(new URL('../overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGLPerf.java', import.meta.url), 'utf8');
assert.match(perf, /if \(target == 0x8C2A\) java\.util\.Arrays\.fill\(TEXTURES\[index\], Integer.MIN_VALUE\);/,
  'Java texture cache invalidates ordinary targets for sampler restoration');
console.log(`browser-opengl-buffer-sampler-smoke: ok (${calls.length} physical binds)`);
