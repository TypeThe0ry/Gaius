#!/usr/bin/env node
// Gaius Service Worker (port/web/sw/gaius-sw.js) in a node:vm Service Worker scope with fakes.
//
//   node port/scripts/service-worker-headers-smoke.mjs
//
// Checks the cross-origin isolation headers per request kind and COEP mode, the immutable
// cache for content-hashed files, the network-first shell fallback, the cases the worker must
// leave alone (cross-origin, non-GET, ranges, opaque responses) and generation pruning.

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const source = await readFile(new URL("../web/sw/gaius-sw.js", import.meta.url), "utf8");
const SCOPE = "https://typethe0ry.github.io/Gaius/26.3/";

function stripSearch(href) {
  const url = new URL(href);
  url.search = "";
  return url.href;
}

class FakeCache {
  constructor() {
    this.entries = new Map();
  }

  async match(request, options) {
    const href = typeof request === "string" ? request : request.url;
    const key = options && options.ignoreSearch ? stripSearch(href) : href;
    for (const [stored, response] of this.entries) {
      if ((options && options.ignoreSearch ? stripSearch(stored) : stored) === key) return response.clone();
    }
    return undefined;
  }

  async put(request, response) {
    const href = typeof request === "string" ? request : request.url;
    this.entries.set(href, response);
  }

  async keys() {
    return [...this.entries.keys()].map((url) => ({url}));
  }

  async delete(request) {
    return this.entries.delete(typeof request === "string" ? request : request.url);
  }
}

// sharedCaches: one origin's CacheStorage seen by the workers of several profiles.
function createScope({coep = "credentialless", network, scopeUrl = SCOPE, sharedCaches, navigator, openFails} = {}) {
  const caches = sharedCaches || new Map();
  const listeners = {};
  const fetches = [];
  const scope = {
    location: {href: `${scopeUrl}gaius-sw.js${coep === null ? "" : `?coep=${coep}`}`},
    registration: {scope: scopeUrl},
    navigator,
    caches: {
      async open(name) {
        if (openFails) throw new DOMException("Unexpected internal error.", "UnknownError");
        if (!caches.has(name)) caches.set(name, new FakeCache());
        return caches.get(name);
      },
      async keys() {
        return [...caches.keys()];
      },
      async delete(name) {
        return caches.delete(name);
      },
    },
    clients: {claim: async () => {}},
    skipWaiting: async () => {},
    addEventListener(type, listener) {
      listeners[type] = listener;
    },
    async fetch(request) {
      fetches.push(request.url);
      return network(request);
    },
  };
  const context = vm.createContext({self: scope, URL, Headers, Response, Set, Promise, JSON, Number, String, Array});
  vm.runInContext(source, context, {filename: "gaius-sw.js"});
  return {scope, caches, listeners, fetches};
}

function request(path, fields = {}) {
  return Object.assign({
    url: new URL(path, SCOPE).href, method: "GET", mode: "cors", destination: "", cache: "default",
    headers: new Headers(fields.headers || {}),
  }, fields, {headers: new Headers(fields.headers || {})});
}

async function dispatchFetch(env, req) {
  let responded = null;
  const pending = [];
  env.listeners.fetch({
    request: req,
    respondWith(promise) {
      responded = promise;
    },
    waitUntil(promise) {
      pending.push(promise);
    },
  });
  const response = responded ? await responded : null;
  await Promise.all(pending);
  return response;
}

const okNetwork = (body = "body", headers = {}) => async () => new Response(body, {
  status: 200, headers: Object.assign({"Content-Type": "text/plain", "Content-Length": String(body.length)}, headers),
});

const checks = [];
const check = (name, fn) => checks.push([name, fn]);

check("navigations get COOP, COEP (credentialless by default) and CORP", async () => {
  const env = createScope({coep: null, network: okNetwork("<!doctype html>")});
  const response = await dispatchFetch(env, request("", {mode: "navigate", destination: "document"}));
  assert.equal(response.headers.get("Cross-Origin-Opener-Policy"), "same-origin");
  assert.equal(response.headers.get("Cross-Origin-Embedder-Policy"), "credentialless");
  assert.equal(response.headers.get("Cross-Origin-Resource-Policy"), "same-origin");
  assert.equal(await response.text(), "<!doctype html>");
});

check("workers carry the embedder policy, plain subresources only CORP", async () => {
  const env = createScope({coep: "require-corp", network: okNetwork("self.onmessage = null;")});
  const worker = await dispatchFetch(env, request("singleplayer-server-worker.0123456789abcdef.js", {destination: "worker"}));
  assert.equal(worker.headers.get("Cross-Origin-Embedder-Policy"), "require-corp");
  assert.equal(worker.headers.get("Cross-Origin-Opener-Policy"), "same-origin");
  const script = await dispatchFetch(env, request("relay-nodes.json", {destination: ""}));
  assert.equal(script.headers.get("Cross-Origin-Embedder-Policy"), null);
  assert.equal(script.headers.get("Cross-Origin-Resource-Policy"), "same-origin");
  const preset = createScope({network: okNetwork("x", {"Cross-Origin-Resource-Policy": "cross-origin"})});
  const kept = await dispatchFetch(preset, request("data.json"));
  assert.equal(kept.headers.get("Cross-Origin-Resource-Policy"), "cross-origin", "an existing CORP is kept");
});

check("coep=off serves everything without isolation headers", async () => {
  const env = createScope({coep: "off", network: okNetwork("<!doctype html>")});
  const response = await dispatchFetch(env, request("", {mode: "navigate", destination: "document"}));
  assert.equal(response.headers.get("Cross-Origin-Embedder-Policy"), null);
  assert.equal(response.headers.get("Cross-Origin-Opener-Policy"), null);
  assert.equal(await response.text(), "<!doctype html>");
});

check("content-hashed files are cached once and then served without the network", async () => {
  let served = 0;
  const env = createScope({network: async () => {
    served++;
    return new Response("classes", {status: 200, headers: {"Content-Type": "text/javascript"}});
  }});
  const name = "classes.0123456789abcdef.js";
  const first = await dispatchFetch(env, request(name, {destination: "script"}));
  assert.equal(await first.text(), "classes");
  const second = await dispatchFetch(env, request(`${name}?v=dev`, {destination: "script"}));
  assert.equal(await second.text(), "classes");
  assert.equal(served, 1, "the second request (another query string) is a cache hit");
  assert.equal(second.headers.get("Cross-Origin-Resource-Policy"), "same-origin");
  const immutable = env.caches.get("gaius-immutable-v1");
  assert.deepEqual([...immutable.entries.keys()], [new URL(name, SCOPE).href]);
  const failed = createScope({network: async () => new Response("missing", {status: 404})});
  await dispatchFetch(failed, request("kernels/mesh.simd.0123456789abcdef.wasm"));
  assert.equal((await failed.scope.caches.open("gaius-immutable-v1")).entries.size, 0, "errors are never cached");
});

check("network-first files fall back to the shell cache offline", async () => {
  let online = true;
  const env = createScope({network: async () => {
    if (!online) throw new TypeError("Failed to fetch");
    return new Response("<!doctype html>v1", {status: 200, headers: {"Content-Type": "text/html", "Content-Length": "17"}});
  }});
  await dispatchFetch(env, request("", {mode: "navigate", destination: "document"}));
  online = false;
  const offline = await dispatchFetch(env, request("?gaiusKernels=0", {mode: "navigate", destination: "document"}));
  assert.equal(await offline.text(), "<!doctype html>v1");
  assert.equal(offline.headers.get("Cross-Origin-Opener-Policy"), "same-origin");
  const missing = await dispatchFetch(env, request("gaius-site.json")).then(() => null, (error) => error);
  assert.ok(missing instanceof Error, "nothing cached and no network: the request fails");
});

check("cross-origin, non-GET, range and opaque responses are left alone", async () => {
  const env = createScope({network: async (req) => req.url.includes("opaque")
    ? {type: "opaque", status: 0, headers: new Headers()}
    : new Response("partial", {status: 206, headers: {"Content-Range": "bytes 0-6/100"}})});
  assert.equal(await dispatchFetch(env, request("https://relay.example.invalid/registry.json")), null);
  assert.equal(await dispatchFetch(env, request("save", {method: "POST"})), null);
  const ranged = await dispatchFetch(env, request("vanilla-sounds.0123456789abcdef.pack", {headers: {range: "bytes=0-6"}}));
  assert.equal(ranged.status, 206);
  assert.equal(env.caches.has("gaius-immutable-v1"), false, "partial responses never reach the cache");
  const opaque = await dispatchFetch(env, request("opaque.txt"));
  assert.equal(opaque.type, "opaque");
  const api = env.scope.GaiusServiceWorker;
  assert.equal(api.strategyFor(request("x.js", {cache: "only-if-cached", mode: "no-cors"}), new URL(SCOPE).origin), "passthrough");
  assert.equal(api.isImmutable(new URL("kernels/noise.baseline.0123456789abcdef.wasm", SCOPE)), true);
  assert.equal(api.isImmutable(new URL("gaius-sw.js", SCOPE)), false);
  assert.equal(api.configFromUrl("https://x.invalid/gaius-sw.js?coep=bogus").coep, "credentialless");
});

check("prune keeps the last two site generations", async () => {
  const env = createScope({network: async () => new Response("x", {status: 200})});
  const worker = env.scope.GaiusServiceWorker.worker;
  const immutable = async () => [...(await env.scope.caches.open("gaius-immutable-v1")).entries.keys()].sort();
  // Generation 1 boots and prunes; generation 2 (a new release) does the same.
  await dispatchFetch(env, request("a.0000000000000001.js"));
  await dispatchFetch(env, request("stale.0000000000000009.js"));
  await worker.prune("gen-1", [`${SCOPE}a.0000000000000001.js`]);
  assert.deepEqual(await immutable(), [`${SCOPE}a.0000000000000001.js`], "files of no generation go");
  await dispatchFetch(env, request("b.0000000000000002.js?v=1"));
  await worker.prune("gen-2", [`${SCOPE}b.0000000000000002.js?v=1`]);
  assert.deepEqual(await immutable(), [`${SCOPE}a.0000000000000001.js`, `${SCOPE}b.0000000000000002.js`],
    "a tab still running the previous generation keeps its files");
  await dispatchFetch(env, request("c.0000000000000003.js"));
  const third = await worker.prune("gen-3", [`${SCOPE}c.0000000000000003.js`]);
  assert.equal(third.generations, 2);
  assert.deepEqual(await immutable(), [`${SCOPE}b.0000000000000002.js`, `${SCOPE}c.0000000000000003.js`]);
  const status = await worker.status();
  assert.equal(status.coep, "credentialless");
  assert.equal(status.immutableEntries, 2);
});

check("prune and status only touch this worker's scope (profiles share the origin's caches)", async () => {
  const origin = new URL(SCOPE).origin;
  const scope262 = `${origin}/Gaius/26.2/`;
  const scope263 = `${origin}/Gaius/26.3/`;
  const shared = new Map();
  const network = async () => new Response("x", {status: 200});
  const env262 = createScope({network, scopeUrl: scope262, sharedCaches: shared});
  const env263 = createScope({network, scopeUrl: scope263, sharedCaches: shared});
  await dispatchFetch(env262, request(`${scope262}classes.0000000000000262.js`));
  await dispatchFetch(env262, request(`${scope262}kernels/mesh.simd.0000000000000262.wasm`));
  await dispatchFetch(env263, request(`${scope263}classes.0000000000000263.js`));
  await dispatchFetch(env263, request(`${scope263}stale.0000000000000009.js`));
  const result = await env263.scope.GaiusServiceWorker.worker.prune("gen-263", [`${scope263}classes.0000000000000263.js`]);
  assert.equal(result.deleted, 1, "only the stale 26.3 entry goes");
  assert.deepEqual([...shared.get("gaius-immutable-v1").entries.keys()].sort(), [
    `${scope262}classes.0000000000000262.js`,
    `${scope262}kernels/mesh.simd.0000000000000262.wasm`,
    `${scope263}classes.0000000000000263.js`,
  ], "the 26.2 profile keeps its entries after 26.3 prunes");
  assert.equal((await env262.scope.GaiusServiceWorker.worker.status()).immutableEntries, 2);
  assert.equal((await env263.scope.GaiusServiceWorker.worker.status()).immutableEntries, 1);
});

check("a CacheStorage failure falls back to the network with isolation headers", async () => {
  let served = 0;
  const env = createScope({openFails: true, network: async () => {
    served++;
    return new Response("classes", {status: 200, headers: {"Content-Type": "text/javascript"}});
  }});
  const script = await dispatchFetch(env, request("classes.0123456789abcdef.js", {destination: "script"}));
  assert.equal(await script.text(), "classes");
  assert.equal(script.headers.get("Cross-Origin-Resource-Policy"), "same-origin");
  const page = await dispatchFetch(env, request("", {mode: "navigate", destination: "document"}));
  assert.equal(page.headers.get("Cross-Origin-Opener-Policy"), "same-origin");
  // One fetch each: the hashed file falls back before any network request, and the navigation's
  // failed shell write runs in waitUntil after its response.
  assert.equal(served, 2);
});

check("large entries are not cached while the origin is short of quota", async () => {
  const big = String(8 * 1024 * 1024);
  const network = async () => new Response("payload", {status: 200, headers: {"Content-Length": big}});
  const tight = createScope({network, navigator: {storage: {estimate: async () => ({quota: 1.5e9, usage: 1e9})}}});
  await dispatchFetch(tight, request("singleplayer-server.0123456789abcdef.js"));
  assert.equal((await tight.scope.caches.open("gaius-immutable-v1")).entries.size, 0);
  const roomy = createScope({network, navigator: {storage: {estimate: async () => ({quota: 50e9, usage: 1e9})}}});
  await dispatchFetch(roomy, request("singleplayer-server.0123456789abcdef.js"));
  assert.equal((await roomy.scope.caches.open("gaius-immutable-v1")).entries.size, 1);
  const unknown = createScope({network, navigator: {storage: {estimate: async () => { throw new Error("blocked"); }}}});
  await dispatchFetch(unknown, request("singleplayer-server.0123456789abcdef.js"));
  assert.equal((await unknown.scope.caches.open("gaius-immutable-v1")).entries.size, 1, "no estimate: cache as before");
});

check("activate removes caches of older worker versions only", async () => {
  const env = createScope({network: okNetwork()});
  await env.scope.caches.open("gaius-immutable-v0");
  await env.scope.caches.open("gaius-shell-v1");
  await env.scope.caches.open("someone-else");
  const pending = [];
  env.listeners.activate({waitUntil: (promise) => pending.push(promise)});
  await Promise.all(pending);
  assert.deepEqual([...env.caches.keys()].sort(), ["gaius-shell-v1", "someone-else"]);
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
  console.log(`${failed} of ${checks.length} service worker checks failed`);
  process.exit(1);
}
console.log(`all ${checks.length} service worker checks passed`);
