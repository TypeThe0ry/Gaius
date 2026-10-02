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

function createScope({coep = "credentialless", network} = {}) {
  const caches = new Map();
  const listeners = {};
  const fetches = [];
  const scope = {
    location: {href: `${SCOPE}gaius-sw.js${coep === null ? "" : `?coep=${coep}`}`},
    registration: {scope: SCOPE},
    caches: {
      async open(name) {
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
