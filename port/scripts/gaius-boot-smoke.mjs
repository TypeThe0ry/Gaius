#!/usr/bin/env node
// Launcher boot script (port/web/boot/gaius-boot.js) in a node:vm page with a fake DOM.
//
//   node port/scripts/gaius-boot-smoke.mjs
//
// Checks the site asset map (hashed client, hotpath, singleplayer URLs and the client preload),
// ordered module loading, the sound-pack merge into the decoded vanilla pack, the kernel port
// handed to the integrated server Worker, the kernel switch-off fallback and the dev and
// portable modes.

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../web/boot/gaius-boot.js", import.meta.url), "utf8");

function createPage({search = "", site = null, portable = false, protocol = "https:"} = {}) {
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
    location: {protocol, search, href: `${protocol}//example.invalid/Gaius/26.3/index.html${search}`},
    navigator: {hardwareConcurrency: 8},
    document: {readyState: "loading", head, documentElement: head, createElement: element, getElementById: () => null},
    performance: {now: () => 1, mark() {}, getEntriesByType: () => []},
    setTimeout, clearTimeout, setInterval: () => 0, clearInterval() {},
    addEventListener() {},
    fetch: async () => { throw new Error("no network in this smoke"); },
    URL, URLSearchParams, TextDecoder, DataView, Uint8Array, JSON, Promise, Object, Array, Map,
    MessageChannel, Worker: FakeWorker, isSecureContext: true,
  };
  if (site) window.__gaiusSite = site;
  if (portable) window.__gaiusBootPortable = true;
  window.window = window;
  const context = vm.createContext(window);
  vm.runInContext(source, context, {filename: "gaius-boot.js"});
  return {window: context, appended, posted, FakeWorker};
}

const SITE = {
  id: "0011223344556677",
  profile: "26.3",
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
  assert.equal(page.window.__gaiusSingleplayerServerUrl, "https://example.invalid/Gaius/26.3/singleplayer-server.cccccccccccccccc.js");
  assert.equal(page.window.__gaiusSingleplayerServerGzipUrl, null, "a plain URL lets the Worker importScripts it");
  assert.equal(page.window.__gaiusSingleplayerWorkerUrl, "https://example.invalid/Gaius/26.3/singleplayer-server-worker.eeeeeeeeeeeeeeee.js");
});

check("modules load in order with async=false; missing ones never block", async () => {
  const page = createPage({site: SITE});
  const scripts = page.appended.filter((node) => node.tagName === "script");
  assert.deepEqual(scripts.slice(0, 3).map((node) => node.src), [
    "kernels/kernel-policy.1111111111111111.js",
    "kernels/kernel-runtime.2222222222222222.js",
    "kernels/mesh-job.3333333333333333.js",
  ]);
  assert.ok(scripts.every((node) => node.async === false));
  assert.equal(scripts[3].src, "runtime/quality/gpu-caps.4444444444444444.js");
  assert.equal(scripts[4].src, "runtime/quality/quality-profile.js", "unmapped quality files fall back to their name");
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
