#!/usr/bin/env node
// Launcher boot script (port/web/boot/gaius-boot.js) in a node:vm page with a fake DOM.
//
//   node port/scripts/gaius-boot-smoke.mjs
//
// Checks the site asset map (hashed client, hotpath, singleplayer URLs and the client preload),
// ordered module loading, the sound-pack merge into the decoded vanilla pack, the kernel port
// handed to the integrated server Worker with its scripts and switches, the kernel switch-off
// fallback and the dev, portable and file modes; the client runtime selection (?runtime=,
// WebAssembly.validate probes for GC and try_table, JSPI, the fallback to the JavaScript client
// with its reason, the late fallback), the file:// payload scripts and ?serverRuntime=wasm.

import {RENDERPEARL_PROFILE} from "./verification-profiles.mjs";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../web/boot/gaius-boot.js", import.meta.url), "utf8");

function createPage({search = "", site = null, portable = false, protocol = "https:", inertScripts = {}, storage = {},
  globals = {}} = {}) {
  const appended = [];
  const posted = [];
  const element = (tag) => ({
    tagName: tag, attributes: {}, onload: null, onerror: null,
    setAttribute(name, value) { this.attributes[name] = value; },
  });
  const head = {appendChild(node) { appended.push(node); return node; }};
  class FakeWorker {
    postMessage(message, transfer) {
      posted.push({message, transfer});
    }
  }
  const window = {
    location: {protocol, search, href: `${protocol}//example.invalid/Gaius/${RENDERPEARL_PROFILE.id}/index.html${search}`},
    navigator: {hardwareConcurrency: 8},
    document: {
      readyState: "loading", head, documentElement: head, createElement: element, getElementById: () => null,
      // Only the portable page's inert server Worker scripts are looked up by selector.
      querySelector(selector) {
        const match = /^script\[type="text\/plain"\]\[data-gaius-worker-script="([^"]+)"\]$/.exec(selector);
        return match && inertScripts[match[1]] !== undefined ? {textContent: inertScripts[match[1]]} : null;
      },
    },
    localStorage: {getItem: (key) => (Object.prototype.hasOwnProperty.call(storage, key) ? storage[key] : null), setItem() {}},
    performance: {now: () => 1, mark() {}, getEntriesByType: () => []},
    setTimeout, clearTimeout, setInterval: () => 0, clearInterval() {},
    addEventListener() {},
    fetch: async () => { throw new Error("no network in this smoke"); },
    URL, URLSearchParams, TextDecoder, DataView, Uint8Array, JSON, Promise, Object, Array, Map,
    MessageChannel, Worker: FakeWorker, isSecureContext: true,
  };
  if (site) window.__gaiusSite = site;
  if (portable) window.__gaiusBootPortable = true;
  // Page globals set before the boot script (runtime configuration, WebAssembly, Blob, ...).
  Object.assign(window, globals);
  window.window = window;
  const context = vm.createContext(window);
  vm.runInContext(source, context, {filename: "gaius-boot.js"});
  return {window: context, appended, posted, FakeWorker};
}

const SITE = {
  id: "0011223344556677",
  profile: RENDERPEARL_PROFILE.id,
  assets: {
    "classes.js": "classes.aaaaaaaaaaaaaaaa.js",
    "classes.js.gz": "classes.js.bbbbbbbbbbbbbbbb.gz",
    "singleplayer-server.js": "singleplayer-server.cccccccccccccccc.js",
    "singleplayer-server.js.gz": "singleplayer-server.js.dddddddddddddddd.gz",
    "singleplayer-server-worker.js": "singleplayer-server-worker.eeeeeeeeeeeeeeee.js",
    "gaius-hotpath.wasm": "gaius-hotpath.ffffffffffffffff.wasm",
    "vanilla-assets.pack.gz": "vanilla-assets.pack.0000000000000000.gz",
    "kernels/kernel-policy.js": "kernels/kernel-policy.1111111111111111.js",
    "kernels/kernel-runtime.js": "kernels/kernel-runtime.2222222222222222.js",
    "kernels/mesh-job.js": "kernels/mesh-job.3333333333333333.js",
    "runtime/quality/gpu-caps.js": "runtime/quality/gpu-caps.4444444444444444.js",
  },
  modules: ["kernels/mesh-job.js"],
  kernels: {},
  serviceWorker: "gaius-sw.js",
};

function pack(entries) {
  const encoder = new TextEncoder();
  const index = {};
  const bodies = [];
  let offset = 0;
  for (const [name, text] of entries) {
    const body = encoder.encode(text);
    index[name] = [offset, body.length];
    offset += body.length;
    bodies.push(body);
  }
  const indexBytes = encoder.encode(JSON.stringify(index));
  const out = new Uint8Array(12 + indexBytes.length + offset);
  out.set(encoder.encode("GAIUSVP1"), 0);
  new DataView(out.buffer).setUint32(8, indexBytes.length, true);
  out.set(indexBytes, 12);
  let at = 12 + indexBytes.length;
  for (const body of bodies) {
    out.set(body, at);
    at += body.length;
  }
  return {bytes: out, index, dataOffset: 12 + indexBytes.length};
}

function read(root, name) {
  const [offset, length] = root.index[name];
  const start = root.dataOffset + offset;
  return new TextDecoder().decode(root.bytes.subarray(start, start + length));
}

const checks = [];
const check = (name, fn) => checks.push([name, fn]);

check("site mode maps logical names to hashed files and preloads the client", async () => {
  const page = createPage({site: SITE});
  const boot = page.window.__gaiusBoot;
  assert.equal(boot.assetUrl("classes.js"), "classes.aaaaaaaaaaaaaaaa.js");
  assert.equal(boot.assetUrl("missing.js"), null);
  assert.equal(page.window.__gaiusClassesUrl, "classes.aaaaaaaaaaaaaaaa.js");
  assert.equal(page.window.__gaiusHotpathWasmUrl, "https://example.invalid/Gaius/26.3/gaius-hotpath.ffffffffffffffff.wasm");
  const preload = page.appended.find((node) => node.tagName === "link");
  assert.equal(preload.rel, "preload");
  assert.equal(preload.href, "classes.aaaaaaaaaaaaaaaa.js");
  // The launcher's generated block set dev URLs; applyAssetUrls replaces them afterwards.
  page.window.__gaiusSingleplayerServerGzipUrl = "singleplayer-server.js.gz?v=dev";
  assert.equal(boot.applyAssetUrls(), true);
  assert.equal(page.window.__gaiusSingleplayerServerUrl, "https://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/singleplayer-server.cccccccccccccccc.js");
  assert.equal(page.window.__gaiusSingleplayerServerGzipUrl, null, "a plain URL lets the Worker importScripts it");
  assert.equal(page.window.__gaiusSingleplayerWorkerUrl, "https://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/singleplayer-server-worker.eeeeeeeeeeeeeeee.js");
});

check("modules load in order with async=false; missing ones never block", async () => {
  const page = createPage({site: SITE});
  const scripts = page.appended.filter((node) => node.tagName === "script");
  assert.deepEqual(scripts.slice(0, 4).map((node) => node.src), [
    "kernels/kernel-mirror.js",
    "kernels/kernel-policy.1111111111111111.js",
    "kernels/kernel-runtime.2222222222222222.js",
    "kernels/mesh-job.3333333333333333.js",
  ]);
  assert.ok(scripts.every((node) => node.async === false));
  assert.equal(scripts[4].src, "runtime/quality/gpu-caps.4444444444444444.js");
  assert.equal(scripts[5].src, "runtime/quality/quality-profile.js", "unmapped quality files fall back to their name");
  for (const node of scripts) (node.src.includes("quality-profile") ? node.onerror : node.onload)();
  let tiers = 0;
  page.window.GaiusQuality = {caps: {ensureTier: () => { tiers++; }}};
  await page.window.__gaiusBoot.beforeMain();
  assert.equal(tiers, 1, "beforeMain picks the quality tier");
  assert.ok(page.window.__gaiusBoot.events.some((entry) => entry.event === "module-missing"));
});

check("the sound pack merges into the decoded core pack in one step", async () => {
  const page = createPage({site: SITE});
  const core = pack([["assets/minecraft/textures/block/stone.png", "stone"], ["assets/minecraft/sounds/random/click.ogg", "click"]]);
  const root = {bytes: core.bytes, index: Object.assign({}, core.index), dataOffset: core.dataOffset, resourceCount: 2};
  const sounds = pack([["assets/minecraft/sounds/mob/cow/say1.ogg", "moo"], ["assets/minecraft/sounds/music/menu/menu1.ogg", "music"]]);
  const added = page.window.__gaiusBoot.mergeSoundPack(root, sounds.bytes);
  assert.equal(added, 2);
  assert.equal(root.resourceCount, 4);
  assert.equal(read(root, "assets/minecraft/textures/block/stone.png"), "stone");
  assert.equal(read(root, "assets/minecraft/sounds/random/click.ogg"), "click");
  assert.equal(read(root, "assets/minecraft/sounds/mob/cow/say1.ogg"), "moo");
  assert.equal(read(root, "assets/minecraft/sounds/music/menu/menu1.ogg"), "music");
  assert.throws(() => page.window.__gaiusBoot.mergeSoundPack(root, new Uint8Array(16)), /GAIUSVP1/);
});

check("the integrated server Worker gets a kernel port after its start message", async () => {
  const page = createPage({site: SITE});
  const worker = new page.FakeWorker();
  const channel = new MessageChannel();
  worker.postMessage({type: "start", sessionId: "a".repeat(32), port: channel.port2}, [channel.port2]);
  assert.equal(page.posted.length, 2);
  assert.equal(page.posted[1].message.type, "gaius-kernel-port");
  assert.equal(page.posted[1].transfer[0], page.posted[1].message.port);
  worker.postMessage({type: "start", sessionId: "a".repeat(32), port: channel.port2});
  assert.equal(page.posted.length, 3, "one port per Worker");
  worker.postMessage({type: "distances"});
  assert.equal(page.posted.length, 4, "other messages pass through untouched");
  page.posted[1].message.port.close();
  channel.port1.close();
});

check("switched-off kernels give no runtime and no server port", async () => {
  const page = createPage({site: SITE, search: "?gaiusKernels=0"});
  assert.equal(page.window.__gaiusKernels.enabled(), false);
  assert.equal(await page.window.__gaiusKernels.runtime(), null);
  const worker = new page.FakeWorker();
  worker.postMessage({type: "start", port: {}});
  assert.equal(page.posted.length, 1);
  const missing = createPage({site: SITE});
  for (const node of missing.appended) if (node.onerror) node.onerror();
  assert.equal(await missing.window.__gaiusKernels.runtime(), null, "runtime scripts that fail to load: vanilla path");
  assert.ok(missing.window.__gaiusBoot.events.some((entry) => entry.event === "kernels-unavailable"));
});

check("dev and portable pages keep their own URLs", async () => {
  const dev = createPage();
  assert.equal(dev.window.__gaiusBoot.applyAssetUrls(), false);
  assert.equal(dev.window.__gaiusClassesUrl, undefined);
  const portable = createPage({portable: true, protocol: "file:"});
  assert.equal(portable.window.__gaiusBoot.applyAssetUrls(), false);
  assert.equal(portable.appended.length, 0, "a portable page inlines its modules");
});

const WORLDGEN_SITE = Object.assign({}, SITE, {
  assets: Object.assign({}, SITE.assets, {
    "kernels/kernel-mirror.js": "kernels/kernel-mirror.0000000000000000.js",
    "kernels/worldgen-job.js": "kernels/worldgen-job.5555555555555555.js",
    "kernels/worldgen-kernel.js": "kernels/worldgen-kernel.6666666666666666.js",
    "kernels/kernel-worker.js": "kernels/kernel-worker.7777777777777777.js",
  }),
  kernels: {
    worldgen: {kinds: ["terrain"], variants: {simd: "kernels/worldgen.simd.8888888888888888.wasm", baseline: "kernels/worldgen.baseline.9999999999999999.wasm"}},
  },
});

// Values from the page's realm, compared structurally.
const plain = (value) => JSON.parse(JSON.stringify(value));

function startWorker(page) {
  const worker = new page.FakeWorker();
  const channel = new MessageChannel();
  worker.postMessage({type: "start", sessionId: "b".repeat(32), port: channel.port2}, [channel.port2]);
  return channel;
}

function closePorts(page, channel) {
  for (const entry of page.posted) if (entry.message.type === "gaius-kernel-port") entry.message.port.close();
  channel.port1.close();
}

check("the kernel port carries the server Worker's scripts and the page switches", async () => {
  const page = createPage({site: WORLDGEN_SITE, search: "?worldgenKernel=off&lightkernel=0"});
  page.window.__gaiusKernelSemantics = {schemaVersion: 1, profile: SITE.profile,
    kernels: {worldgen: {enabled: false}}};
  const channel = startWorker(page);
  const message = page.posted[1].message;
  assert.equal(message.type, "gaius-kernel-port");
  assert.deepEqual(plain(message.kernelScripts.map((entry) => entry.url)), [
    `https://example.invalid/Gaius/${SITE.profile}/kernels/kernel-mirror.0000000000000000.js`,
    "https://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/kernels/worldgen-job.5555555555555555.js",
    "https://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/kernels/worldgen-kernel.6666666666666666.js",
  ]);
  assert.deepEqual(plain(message.worldgenKernel), {enabled: false});
  assert.deepEqual(plain(message.kernelSemantics), plain(page.window.__gaiusKernelSemantics));
  assert.deepEqual(plain(message.lightKernel), {enabled: false});
  closePorts(page, channel);

  // A site without the worldgen scripts hands over nothing it cannot serve; the stored light
  // setting turns the light kernel off as well.
  const bare = createPage({site: SITE, storage: {"gaius.lightKernel": "off"}});
  const bareChannel = startWorker(bare);
  assert.deepEqual(plain(bare.posted[1].message.kernelScripts), []);
  assert.deepEqual(plain(bare.posted[1].message.worldgenKernel), {});
  assert.deepEqual(plain(bare.posted[1].message.lightKernel), {enabled: false});
  closePorts(bare, bareChannel);

  const on = createPage({site: SITE, search: "?lightKernel=1"});
  const onChannel = startWorker(on);
  assert.deepEqual(plain(on.posted[1].message.lightKernel), {enabled: true});
  closePorts(on, onChannel);
});

check("dev, portable and file pages hand the server Worker what they can", async () => {
  const dev = createPage({protocol: "http:"});
  const devChannel = startWorker(dev);
  assert.deepEqual(plain(dev.posted[1].message.kernelScripts.map((entry) => entry.url)), [
    `http://example.invalid/Gaius/${SITE.profile}/kernels/kernel-mirror.js`,
    "http://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/kernels/worldgen-job.js",
    "http://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/kernels/worldgen-kernel.js",
  ]);
  closePorts(dev, devChannel);

  const portable = createPage({portable: true, protocol: "file:", inertScripts: {
    "kernels/worldgen-job.js": "\nself.GaiusWorldgenJob = {};\n",
    "kernels/worldgen-kernel.js": "\nself.GaiusWorldgenKernel = {};\n",
  }});
  const portableChannel = startWorker(portable);
  assert.deepEqual(plain(portable.posted[1].message.kernelScripts), [
    {name: "kernels/worldgen-job.js", source: "\nself.GaiusWorldgenJob = {};\n"},
    {name: "kernels/worldgen-kernel.js", source: "\nself.GaiusWorldgenKernel = {};\n"},
  ]);
  closePorts(portable, portableChannel);

  const file = createPage({protocol: "file:"});
  const fileChannel = startWorker(file);
  assert.deepEqual(plain(file.posted[1].message.kernelScripts), [], "a dist opened from disk has no kernels");
  closePorts(file, fileChannel);
  // The quality layer still loads by tag there; kernel modules do not.
  const sources = file.appended.filter((node) => node.tagName === "script").map((node) => node.src);
  assert.deepEqual(plain(sources), [
    "runtime/quality/gpu-caps.js", "runtime/quality/quality-profile.js", "runtime/quality/gl-pass.js",
    "runtime/quality/upscaler.js", "runtime/quality/post-chain.js", "runtime/quality/quality-runtime.js",
  ]);
});

check("?worldgenKernelHost=worker sends the port once the worldgen host configuration is known", async () => {
  const page = createPage({site: WORLDGEN_SITE, search: "?worldgenKernelHost=worker"});
  for (const node of page.appended) if (node.onload) node.onload();
  const channel = startWorker(page);
  assert.equal(page.posted.length, 1, "the port waits for the manifest");
  for (let i = 0; i < 50 && page.posted.length < 2; i++) await new Promise((resolve) => setTimeout(resolve, 5));
  const message = page.posted[1].message;
  assert.deepEqual(plain(message.kernelScripts.map((entry) => entry.name)), [
    "kernels/kernel-mirror.js",
    "kernels/kernel-policy.js", "kernels/kernel-runtime.js", "kernels/worldgen-job.js", "kernels/worldgen-kernel.js",
  ]);
  assert.equal(message.worldgenKernel.host, "worker");
  assert.equal(message.worldgenKernel.workerUrl, "https://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/kernels/kernel-worker.7777777777777777.js");
  assert.deepEqual(plain(message.worldgenKernel.kernels.worldgen.variants), {
    simd: {url: "https://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/kernels/worldgen.simd.8888888888888888.wasm"},
    baseline: {url: "https://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/kernels/worldgen.baseline.9999999999999999.wasm"},
  });
  assert.equal(page.posted[1].transfer[0], message.port);
  closePorts(page, channel);
});

check("a site page names its profile for the quality layer", async () => {
  const page = createPage({site: SITE});
  assert.equal(page.window.__gaiusProfileId, RENDERPEARL_PROFILE.id);
  const dev = createPage();
  assert.equal(dev.window.__gaiusProfileId, undefined);
});

// --- client runtime selection (design D14) ------------------------------------------------------

// node validates the GC and try_table probes but has no JSPI: the page's WebAssembly gets the
// real validate plus JSPI stand-ins, or validate stubs that refuse one probe.
const JSPI = {Suspending: function Suspending() {}, promising: function promising() {}};
const fullWasm = () => Object.assign({validate: (bytes) => WebAssembly.validate(bytes)}, JSPI);
const noJspiWasm = () => ({validate: (bytes) => WebAssembly.validate(bytes)});
// The GC probe is the one with the 0xFB (GC) prefix, the try_table probe the one with 0x1F.
const refusing = (opcode) => Object.assign({
  validate: (bytes) => (Array.prototype.includes.call(bytes, opcode) ? false : WebAssembly.validate(bytes)),
}, JSPI);
const WEB_APIS = {Blob, Response, DecompressionStream, ReadableStream, Uint8Array, ArrayBuffer};

const WASM_SITE = Object.assign({}, SITE, {
  assets: Object.assign({}, SITE.assets, {
    "classes.wasm": "classes.aaaaaaaaaaaaaaab.wasm",
    "classes.wasm.gz": "classes.wasm.aaaaaaaaaaaaaaac.gz",
    "gaius-wasm-boot.js": "gaius-wasm-boot.aaaaaaaaaaaaaaad.js",
    "gaius-wasm-gc-runtime.js": "gaius-wasm-gc-runtime.aaaaaaaaaaaaaaae.js",
    "gaius-jspi.js": "gaius-jspi.aaaaaaaaaaaaaaaf.js",
  }),
});
const WASM_SERVER_SITE = Object.assign({}, WASM_SITE, {
  assets: Object.assign({}, WASM_SITE.assets, {
    "singleplayer-server.wasm": "singleplayer-server.bbbbbbbbbbbbbbb1.wasm",
    "gaius-wasm-worker.js": "gaius-wasm-worker.bbbbbbbbbbbbbbb2.js",
  }),
});
const BASE = "https://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/";

check("the runtime defaults to js and still records the wasm features", async () => {
  const page = createPage({site: WASM_SITE, globals: {WebAssembly: fullWasm()}});
  const selection = page.window.__gaiusRuntimeSelection;
  assert.equal(selection, page.window.__gaiusBoot.runtime);
  assert.deepEqual(plain(selection.features), {webAssembly: true, gc: true, tryTable: true, jspi: true, decompressionStream: false});
  assert.equal(selection.requested, "js");
  assert.equal(selection.selected, "js");
  assert.equal(selection.reason, "default-js");
  assert.equal(selection.fallback, false);
  assert.equal(selection.form, "site");
  assert.equal(selection.wasmBuilt, true);
  assert.equal(page.window.__gaiusClassesUrl, "classes.aaaaaaaaaaaaaaaa.js");
  assert.equal(page.window.__gaiusWasmSource, undefined);
});

check("?runtime=wasm starts the WebAssembly GC client and its module download", async () => {
  const requests = [];
  const served = new Response(new Uint8Array([0, 97, 115, 109]), {headers: {"Content-Type": "application/wasm"}});
  const page = createPage({site: WASM_SITE, search: "?runtime=wasm", globals: Object.assign({
    WebAssembly: fullWasm(),
    fetch: async (url) => { requests.push(String(url)); return served; },
  }, WEB_APIS)});
  const selection = page.window.__gaiusRuntimeSelection;
  assert.equal(selection.selected, "wasm");
  assert.equal(selection.reason, "url-wasm");
  assert.equal(page.window.__gaiusClassesUrl, BASE + "gaius-wasm-boot.aaaaaaaaaaaaaaad.js");
  assert.equal(page.window.__gaiusWasmRuntimeUrl, BASE + "gaius-wasm-gc-runtime.aaaaaaaaaaaaaaae.js");
  assert.equal(page.window.__gaiusJspiUrl, BASE + "gaius-jspi.aaaaaaaaaaaaaaaf.js");
  assert.ok(!page.appended.some((node) => node.tagName === "link"), "no classes.js preload");
  const source = await page.window.__gaiusWasmSource;
  assert.equal(requests[0], BASE + "classes.aaaaaaaaaaaaaaab.wasm");
  assert.equal(source, served, "the fetched Response itself streams into the compiler (code cache)");
  assert.equal(new Uint8Array(await source.arrayBuffer())[1], 97);
  // A host that serves .wasm with another type gets the body rewrapped as application/wasm.
  const untyped = createPage({site: WASM_SITE, search: "?runtime=wasm", globals: Object.assign({
    WebAssembly: fullWasm(), fetch: async () => new Response(new Uint8Array([0, 97, 115, 109])),
  }, WEB_APIS)});
  const rewrapped = await untyped.window.__gaiusWasmSource;
  assert.equal(rewrapped.headers.get("Content-Type"), "application/wasm");
  assert.equal(new Uint8Array(await rewrapped.arrayBuffer())[2], 115);
  // beforeMain waits for the instantiated module and publishes the shared boot timings.
  for (const node of page.appended) if (node.onload) node.onload();
  page.window.__gaiusBootTimings = {pageStart: 0, classesLoaded: 5};
  page.window.__gaiusWasmTimings = {instantiated: 42};
  page.window.__gaiusClientReady = Promise.resolve({});
  await page.window.__gaiusBoot.beforeMain();
  assert.equal(page.window.__gaiusBootTimings.runtime, "wasm");
  assert.equal(page.window.__gaiusBootTimings.clientReady, 42);
  assert.equal(page.window.__gaiusBootTimings.runtimeFallback, undefined);
  assert.equal(typeof page.window.__gaiusBootTimings.runtimeSelected, "number");
});

check("a browser without JSPI gets the JavaScript client and the reason", async () => {
  const warnings = [];
  const page = createPage({site: WASM_SITE, search: "?runtime=wasm", globals: {
    WebAssembly: noJspiWasm(), console: {warn: (text) => warnings.push(text)},
  }});
  const selection = page.window.__gaiusRuntimeSelection;
  assert.equal(selection.selected, "js");
  assert.equal(selection.fallback, true);
  assert.equal(selection.reason, "missing:jspi");
  assert.equal(page.window.__gaiusClassesUrl, "classes.aaaaaaaaaaaaaaaa.js");
  assert.ok(page.window.__gaiusBoot.events.some((entry) => entry.event === "runtime-fallback" && entry.detail === "missing:jspi"));
  assert.match(warnings[0], /missing:jspi/);
  for (const node of page.appended) if (node.onload) node.onload();
  page.window.__gaiusBootTimings = {classesLoaded: 7};
  await page.window.__gaiusBoot.beforeMain();
  assert.equal(page.window.__gaiusBootTimings.runtime, "js");
  assert.equal(page.window.__gaiusBootTimings.runtimeFallback, "missing:jspi");
  assert.equal(page.window.__gaiusBootTimings.clientReady, 7, "the JavaScript client is ready once classes.js ran");
});

check("missing WebAssembly GC, try_table or WebAssembly name their feature", async () => {
  const quiet = {console: {warn() {}}};
  const noGc = createPage({site: WASM_SITE, search: "?runtime=wasm", globals: Object.assign({WebAssembly: refusing(0xfb)}, quiet)});
  assert.equal(noGc.window.__gaiusRuntimeSelection.reason, "missing:gc");
  const noTryTable = createPage({site: WASM_SITE, search: "?runtime=auto", globals: Object.assign({WebAssembly: refusing(0x1f)}, quiet)});
  assert.equal(noTryTable.window.__gaiusRuntimeSelection.reason, "missing:try-table");
  assert.equal(noTryTable.window.__gaiusRuntimeSelection.requested, "auto");
  const none = createPage({site: WASM_SITE, search: "?runtime=wasm", globals: Object.assign({WebAssembly: undefined}, quiet)});
  assert.equal(none.window.__gaiusRuntimeSelection.reason, "missing:webassembly");
  const unbuilt = createPage({site: SITE, search: "?runtime=wasm", globals: Object.assign({WebAssembly: noJspiWasm()}, quiet)});
  assert.equal(unbuilt.window.__gaiusRuntimeSelection.reason, "missing:wasm-build,jspi");
  for (const page of [noGc, noTryTable, none, unbuilt]) assert.equal(page.window.__gaiusRuntimeSelection.selected, "js");
});

check("the page default applies unless ?runtime= names a runtime", async () => {
  const runtimes = {wasm: {client: "classes.wasm", server: "singleplayer-server.wasm"}};
  const fetchNothing = async () => new Response(new Uint8Array(0));
  const page = createPage({protocol: "http:", search: "?runtime=fastest", globals: {
    WebAssembly: fullWasm(), __gaiusRuntimes: runtimes, __gaiusRuntimeDefault: "wasm", fetch: fetchNothing,
  }});
  const selection = page.window.__gaiusRuntimeSelection;
  assert.equal(selection.form, "dev");
  assert.equal(selection.source, "page");
  assert.equal(selection.selected, "wasm");
  assert.ok(page.window.__gaiusBoot.events.some((entry) => entry.event === "runtime-invalid" && entry.detail === "fastest"));
  assert.equal(page.window.__gaiusClassesUrl, "http://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/gaius-wasm-boot.js");
  const js = createPage({protocol: "http:", search: "?runtime=js", globals: {
    WebAssembly: fullWasm(), __gaiusRuntimes: runtimes, __gaiusRuntimeDefault: "wasm",
  }});
  assert.equal(js.window.__gaiusRuntimeSelection.selected, "js");
  assert.equal(js.window.__gaiusRuntimeSelection.reason, "url-js");
  assert.equal(js.window.__gaiusClassesUrl, undefined, "the launcher keeps its own classes.js URL");
});

check("a module that does not instantiate starts the JavaScript client instead", async () => {
  const page = createPage({site: WASM_SITE, search: "?runtime=wasm", globals: {
    WebAssembly: fullWasm(), console: {warn() {}}, fetch: async () => new Response(new Uint8Array(0)),
  }});
  for (const node of page.appended) if (node.onload) node.onload();
  const loaded = page.appended.length;
  page.window.__gaiusBootTimings = {};
  page.window.__gaiusClientReady = Promise.reject(new Error("CompileError: boom"));
  page.window.__gaiusClientReady.catch(() => {});
  const ready = page.window.__gaiusBoot.beforeMain();
  for (let i = 0; i < 50 && page.appended.length === loaded; i++) await new Promise((resolve) => setTimeout(resolve, 2));
  const script = page.appended[loaded];
  assert.equal(script.src, BASE + "classes.aaaaaaaaaaaaaaaa.js");
  script.onload();
  await ready;
  const selection = page.window.__gaiusRuntimeSelection;
  assert.equal(selection.selected, "js");
  assert.equal(selection.late, true);
  assert.equal(selection.reason, "wasm-load-failed:CompileError: boom");
  assert.equal(page.window.__gaiusBootTimings.runtime, "js");
  assert.equal(page.window.__gaiusBootTimings.runtimeFallback, "wasm-load-failed:CompileError: boom");
  assert.equal(page.window.__gaiusBoot.handlesWasmFailure, true);
});

check("a dist opened from disk decodes its payload scripts without fetch", async () => {
  const module = new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0]);
  const shaderc = new Uint8Array([0, 97, 115, 109, 2]);
  const gzip = async (bytes) => new Uint8Array(await new Response(new Blob([bytes]).stream()
    .pipeThrough(new CompressionStream("gzip"))).arrayBuffer());
  const base64 = (bytes) => Buffer.from(bytes).toString("base64");
  const payloadData = {
    "classes.wasm": base64(await gzip(module)),
    "vanilla-assets.pack.gz": base64(await gzip(new TextEncoder().encode("GAIUSVP1"))),
    "gaius-shaderc.wasm": base64(await gzip(shaderc)),
    "gaius-spvc.wasm": base64(await gzip(shaderc)),
    "gaius-shaderc.js": base64(new TextEncoder().encode("self.GaiusShaderc = {};")),
    "gaius-spvc.js": base64(new TextEncoder().encode("self.GaiusSpvc = {};")),
    "gaius-hotpath.wasm": base64(await gzip(shaderc)),
  };
  const payloads = {};
  for (const name of Object.keys(payloadData)) {
    payloads[name] = {script: name + ".payload.js", bytes: 1,
      gzip: name.endsWith(".wasm") || name === "classes.wasm"};
  }
  const page = createPage({protocol: "file:", search: "?runtime=wasm", globals: Object.assign({
    WebAssembly: fullWasm(), atob, __gaiusFilePayloads: payloads,
    __gaiusRuntimes: {wasm: {client: "classes.wasm"}},
    fetch: async () => { throw new Error("fetch under file://"); },
  }, WEB_APIS)});
  const selection = page.window.__gaiusRuntimeSelection;
  assert.equal(selection.form, "file");
  assert.equal(selection.selected, "wasm");
  assert.equal(page.window.__gaiusClassesUrl, "file://example.invalid/Gaius/" + RENDERPEARL_PROFILE.id + "/gaius-wasm-boot.js");
  // Every payload script runs like the real one: it stores its base64 text, then loads.
  const scripts = page.appended.filter((node) => node.attributes["data-gaius-file-payload"]);
  assert.deepEqual(scripts.map((node) => node.src).sort(), Object.keys(payloads).map((name) => name + ".payload.js").sort());
  page.window.__gaiusFilePayloadData = {};
  for (const node of scripts) {
    page.window.__gaiusFilePayloadData[node.attributes["data-gaius-file-payload"]] = payloadData[node.attributes["data-gaius-file-payload"]];
    node.onload();
  }
  const source = await page.window.__gaiusWasmSource;
  assert.equal(source.headers.get("Content-Type"), "application/wasm");
  assert.deepEqual([...new Uint8Array(await source.arrayBuffer())], [...module]);
  const vanilla = await page.window.__gaiusVanillaAssetsCompressedPromise;
  assert.equal(vanilla[0], 0x1f, "the vanilla pack stays compressed for the launcher's decoder");
  await page.window.__gaiusPortableAssetsReady;
  const urls = page.window.__gaiusShaderToolchainUrls;
  assert.match(urls.shadercJs, /^blob:/);
  assert.match(urls.shadercWasm, /^blob:/);
  assert.match(page.window.__gaiusHotpathWasmUrl, /^blob:/);
  assert.deepEqual(Object.keys(page.window.__gaiusFilePayloadData), [], "decoded texts are released");
  // Without the module payload a disk page has no wasm build to start.
  const bare = createPage({protocol: "file:", search: "?runtime=wasm", globals: Object.assign({
    WebAssembly: fullWasm(), __gaiusRuntimes: {wasm: {client: "classes.wasm"}}, console: {warn() {}},
  }, WEB_APIS)});
  assert.equal(bare.window.__gaiusRuntimeSelection.reason, "missing:wasm-build");
});

check("a portable page with an embedded module selects wasm and decodes it itself", async () => {
  const page = createPage({portable: true, protocol: "file:", search: "?runtime=wasm", globals: Object.assign({
    WebAssembly: fullWasm(), __gaiusPortableRuntimes: {wasm: {client: "classes.wasm"}},
  }, WEB_APIS)});
  assert.equal(page.window.__gaiusRuntimeSelection.form, "portable");
  assert.equal(page.window.__gaiusRuntimeSelection.selected, "wasm");
  assert.equal(page.window.__gaiusClassesUrl, undefined, "the portable bootstrap sets the loader URL");
  assert.equal(page.window.__gaiusWasmSource, undefined);
  const jsOnly = createPage({portable: true, protocol: "file:", search: "?runtime=wasm", globals: Object.assign({
    WebAssembly: fullWasm(), console: {warn() {}},
  }, WEB_APIS)});
  assert.equal(jsOnly.window.__gaiusRuntimeSelection.reason, "missing:wasm-build");
});

check("a dual portable page keeps the Wasm client on its embedded payload", async () => {
  const wasmBytes = new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0]);
  const siblingRequests = [];
  const page = createPage({portable: true, protocol: "file:", search: "?runtime=wasm", site: SITE,
    globals: Object.assign({
      WebAssembly: fullWasm(),
      __gaiusPortableRuntimes: {wasm: {client: "classes.wasm", server: "singleplayer-server.wasm"}},
      __gaiusPortablePayloadBytes: {
        "classes.wasm": Promise.resolve(wasmBytes),
        "singleplayer-server.wasm": Promise.resolve(wasmBytes),
      },
      fetch: async (url) => {
        siblingRequests.push(String(url));
        throw new Error("dual portable attempted a sibling fetch: " + url);
      },
    }, WEB_APIS)});
  assert.equal(page.window.__gaiusRuntimeSelection.form, "portable");
  assert.equal(page.window.__gaiusRuntimeSelection.selected, "wasm");
  assert.equal(page.window.__gaiusClassesUrl, undefined);
  assert.deepEqual([...await page.window.__gaiusPortablePayloadBytes["classes.wasm"]], [...wasmBytes]);
  assert.deepEqual(siblingRequests, [], "portable Wasm selection must not call inflateUrl/fetch");
});

check("a dual portable descriptor is recognized before the body bootstrap runs", async () => {
  const page = createPage({protocol: "file:", search: "?runtime=wasm", globals: Object.assign({
    WebAssembly: fullWasm(),
    __gaiusPortableRuntimes: {wasm: {client: "classes.wasm", server: "singleplayer-server.wasm"}},
    __gaiusFilePayloads: {"classes.wasm": {script: "classes.wasm.payload.js", bytes: 8, gzip: true}},
  }, WEB_APIS)});
  assert.equal(page.window.__gaiusRuntimeSelection.form, "portable");
  assert.equal(page.window.__gaiusRuntimeSelection.selected, "wasm");
  assert.equal(page.window.__gaiusRuntimeSelection.wasmBuilt, true);
});

check("?serverRuntime=wasm hands the server module to the singleplayer Worker", async () => {
  const page = createPage({site: WASM_SERVER_SITE, search: "?serverRuntime=wasm", globals: {WebAssembly: fullWasm()}});
  assert.equal(page.window.__gaiusBoot.serverRuntime, "wasm");
  const channel = startWorker(page);
  const start = page.posted[0].message;
  assert.equal(start.serverRuntime, "wasm");
  assert.equal(start.serverWasmUrl, BASE + "singleplayer-server.bbbbbbbbbbbbbbb1.wasm");
  assert.deepEqual(plain(start.wasmRuntimeScripts), [
    {name: "gaius-wasm-gc-runtime.js", url: BASE + "gaius-wasm-gc-runtime.aaaaaaaaaaaaaaae.js"},
    {name: "gaius-jspi.js", url: BASE + "gaius-jspi.aaaaaaaaaaaaaaaf.js"},
    {name: "gaius-wasm-worker.js", url: BASE + "gaius-wasm-worker.bbbbbbbbbbbbbbb2.js"},
  ]);
  assert.equal(start.port, channel.port2, "the transferred port stays in the message");
  closePorts(page, channel);
  const noServer = createPage({site: WASM_SITE, search: "?serverRuntime=wasm", globals: {WebAssembly: fullWasm()}});
  const noServerChannel = startWorker(noServer);
  assert.equal(noServer.posted[0].message.serverRuntime, undefined);
  assert.ok(noServer.window.__gaiusBoot.events.some((entry) => entry.event === "server-runtime-fallback"
    && entry.detail === "missing:wasm-server-build"));
  closePorts(noServer, noServerChannel);
});

// A gzip-only site (build-pages-site.py, gaius-site-inflate-v1): the modules ship as .gz only and
// site.inflate names the URL the Service Worker serves them under.
const GZIP_ONLY_SITE = (() => {
  const assets = Object.assign({}, WASM_SERVER_SITE.assets, {
    "classes.wasm.gz": "classes.wasm.aaaaaaaaaaaaaaac.gz",
    "singleplayer-server.wasm.gz": "singleplayer-server.wasm.bbbbbbbbbbbbbbb3.gz",
  });
  delete assets["classes.wasm"];
  delete assets["singleplayer-server.wasm"];
  return Object.assign({}, WASM_SERVER_SITE, {assets, inflate: {
    "classes.wasm": "classes.aaaaaaaaaaaaaaac.wasm",
    "singleplayer-server.wasm": "singleplayer-server.bbbbbbbbbbbbbbb3.wasm",
  }});
})();
const MODULE_BYTES = new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0]);
const gzipResponse = async () => new Response(await new Response(new Blob([MODULE_BYTES]).stream()
  .pipeThrough(new CompressionStream("gzip"))).arrayBuffer(), {headers: {"Content-Type": "application/gzip"}});
const controlled = {hardwareConcurrency: 8, serviceWorker: {controller: {}}};

check("a gzip-only module comes from the Service Worker, or is inflated on the page without one", async () => {
  const requests = [];
  const page = createPage({site: GZIP_ONLY_SITE, search: "?runtime=wasm", globals: Object.assign({
    WebAssembly: fullWasm(), fetch: async (url) => { requests.push(String(url)); return gzipResponse(); },
  }, WEB_APIS)});
  assert.equal(page.window.__gaiusRuntimeSelection.selected, "wasm", "a .gz module counts as built");
  const inflatedOnPage = await page.window.__gaiusWasmSource;
  assert.deepEqual(requests, [BASE + "classes.wasm.aaaaaaaaaaaaaaac.gz"]);
  assert.equal(inflatedOnPage.headers.get("Content-Type"), "application/wasm");
  assert.deepEqual(new Uint8Array(await inflatedOnPage.arrayBuffer()), MODULE_BYTES);
  assert.ok(page.window.__gaiusBoot.events.some((entry) => entry.event === "wasm-inflate" && entry.detail === "page"));

  const served = new Response(MODULE_BYTES, {headers: {"Content-Type": "application/wasm"}});
  const swRequests = [];
  const underWorker = createPage({site: GZIP_ONLY_SITE, search: "?runtime=wasm", globals: Object.assign({
    navigator: controlled, WebAssembly: fullWasm(),
    fetch: async (url) => { swRequests.push(String(url)); return served; },
  }, WEB_APIS)});
  assert.equal(await underWorker.window.__gaiusWasmSource, served, "the worker's stored response streams into the compiler");
  assert.deepEqual(swRequests, [BASE + "classes.aaaaaaaaaaaaaaac.wasm"]);

  const fallbackRequests = [];
  const workerMissed = createPage({site: GZIP_ONLY_SITE, search: "?runtime=wasm", globals: Object.assign({
    navigator: controlled, WebAssembly: fullWasm(),
    fetch: async (url) => {
      fallbackRequests.push(String(url));
      return String(url).endsWith(".wasm") ? new Response("missing", {status: 404}) : gzipResponse();
    },
  }, WEB_APIS)});
  assert.deepEqual(new Uint8Array(await (await workerMissed.window.__gaiusWasmSource).arrayBuffer()), MODULE_BYTES);
  assert.deepEqual(fallbackRequests, [BASE + "classes.aaaaaaaaaaaaaaac.wasm", BASE + "classes.wasm.aaaaaaaaaaaaaaac.gz"]);
  assert.ok(workerMissed.window.__gaiusBoot.events.some((entry) => entry.event === "wasm-inflate-fallback"));
});

check("a gzip-only server module goes to the Worker by its inflated URL or as its .gz", async () => {
  const underWorker = createPage({site: GZIP_ONLY_SITE, search: "?serverRuntime=wasm",
    globals: {navigator: controlled, WebAssembly: fullWasm()}});
  const channel = startWorker(underWorker);
  assert.equal(underWorker.posted[0].message.serverRuntime, "wasm");
  assert.equal(underWorker.posted[0].message.serverWasmUrl, BASE + "singleplayer-server.bbbbbbbbbbbbbbb3.wasm");
  closePorts(underWorker, channel);
  const alone = createPage({site: GZIP_ONLY_SITE, search: "?serverRuntime=wasm", globals: {WebAssembly: fullWasm()}});
  const aloneChannel = startWorker(alone);
  assert.equal(alone.posted[0].message.serverWasmUrl, BASE + "singleplayer-server.wasm.bbbbbbbbbbbbbbb3.gz");
  closePorts(alone, aloneChannel);
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
  console.log(`${failed} of ${checks.length} boot checks failed`);
  process.exit(1);
}
console.log(`all ${checks.length} boot checks passed`);
