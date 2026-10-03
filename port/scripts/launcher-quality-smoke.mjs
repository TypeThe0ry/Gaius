#!/usr/bin/env node
// Launcher canvas-ratio wiring of the graphics quality layer (port/web/launcher/index.template.html)
// in a node:vm page: the tier cap replaces the 1x default only when the layer is present and no
// URL override is set, requested minimums stay, a failing layer changes nothing, the canvas is
// magnified with nearest-neighbour sampling only while its backing store is below the device
// pixels (?gaiusPixelated=0 turns that off), and the frame-rate governor yields to the world
// render scale only while that scale runs in automatic mode on a live level hook.
//
//   node port/scripts/launcher-quality-smoke.mjs

import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const template = readFileSync(new URL("../web/launcher/index.template.html", import.meta.url), "utf8");

function slice(from, to) {
  const start = template.indexOf(from);
  const end = template.indexOf(to, start);
  assert.ok(start >= 0 && end > start, `launcher template has no block from ${JSON.stringify(from)}`);
  return template.slice(start, end);
}

// The parse-time ratio defaults with the quality hook and the sampling installer, and the governor gate.
const ratioBlock = slice("    const rawDevicePixelRatio = ", "    window.__gaiusMaxSingleBufferShadowBytes");
const governorGate = slice("    function gaiusRenderScaleGovernsFps()", "    function maybeDegradeResolutionForFps()");

function page({dpr = 2, search = "", quality = null, canvasWidth = 0, cssWidth = 1000} = {}) {
  const written = [];
  const canvas = {
    width: canvasWidth,
    clientWidth: cssWidth,
    style: {
      set imageRendering(value) {
        written.push(value);
      },
    },
  };
  const window = {
    devicePixelRatio: dpr, innerWidth: cssWidth, innerHeight: 600,
    urlParams: new URLSearchParams(search),
    document: {getElementById: () => canvas},
    addEventListener() {},
    MutationObserver: class {
      observe() {}
    },
    GaiusQuality: quality, Number, Math,
  };
  window.window = window;
  vm.createContext(window);
  vm.runInContext(`${ratioBlock}${governorGate}\nthis.governs = gaiusRenderScaleGovernsFps;`, window);
  return {window, canvas, written};
}

function quality(tierCap, {mode = "fixed", stats = {}} = {}) {
  return {
    runtime: {
      resolvePixelRatio: (raw) => Math.max(0.5, Math.min(raw, tierCap)),
      stats: () => Object.assign({frames: 0, disabled: false}, stats),
    },
    profile: {settings: () => ({renderScaleMode: mode})},
  };
}

// Without the layer the v0.3 defaults stay.
let current = page();
assert.equal(current.window.__gaiusMaxDpr, 1);
assert.equal(current.window.__gaiusApplyQualityPixelRatio(), false);

// A 1.5x tier cap on a 2x screen becomes the default; the governor may step down to 1.
current = page({quality: quality(1.5)});
assert.equal(current.window.__gaiusApplyQualityPixelRatio(), true);
assert.equal(current.window.__gaiusMaxDpr, 1.5);
assert.equal(current.window.__gaiusDefaultMaxDpr, 1.5);
assert.equal(current.window.__gaiusWorldMinDpr, 1);
assert.equal(current.window.__gaiusMenuMinDpr, 1);

// ?maxDpr keeps precedence; requested minimums stay.
current = page({search: "?maxDpr=2", quality: quality(1.5)});
assert.equal(current.window.__gaiusApplyQualityPixelRatio(), false);
assert.equal(current.window.__gaiusMaxDpr, 2);
current = page({dpr: 3, search: "?menuMinDpr=2", quality: quality(1)});
current.window.__gaiusApplyQualityPixelRatio();
assert.equal(current.window.__gaiusMenuMinDpr, 2);
assert.equal(current.window.__gaiusWorldMinDpr, 1);
assert.equal(current.window.__gaiusMaxDpr, 2);

// A layer that throws changes nothing.
current = page({quality: {runtime: {resolvePixelRatio() { throw new Error("broken"); }}}});
assert.equal(current.window.__gaiusApplyQualityPixelRatio(), false);
assert.equal(current.window.__gaiusMaxDpr, 1);

// Nearest-neighbour magnification only below the device pixels, each change written once.
current = page({canvasWidth: 1000});
assert.deepEqual(current.written, ["pixelated"]);
current.canvas.width = 2000;
current.window.__gaiusUpdateCanvasSampling();
current.window.__gaiusUpdateCanvasSampling();
assert.deepEqual(current.written, ["pixelated", ""]);
assert.deepEqual(page({canvasWidth: 1000, search: "?gaiusPixelated=0"}).written, []);

// The governor yields only to an automatic world render scale on a live, enabled level hook.
assert.equal(page({quality: quality(1.5, {mode: "auto"})}).window.governs(), false, "no level frames yet");
assert.equal(page({quality: quality(1.5, {mode: "auto", stats: {frames: 3}})}).window.governs(), true);
assert.equal(page({quality: quality(1.5, {stats: {frames: 3}})}).window.governs(), false);
assert.equal(page({quality: quality(1.5, {mode: "auto", stats: {frames: 3, disabled: true}})}).window.governs(), false);
assert.equal(page().window.governs(), false);

console.log("launcher quality wiring smoke passed");
