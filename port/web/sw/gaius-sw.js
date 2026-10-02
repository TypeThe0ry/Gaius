// Gaius Service Worker: serves a multi-file Gaius site (port/scripts/build-pages-site.py) with
// the headers static hosting cannot set, and keeps its content-hashed assets for warm boots.
//
// 1. Cross-origin isolation. GitHub Pages cannot send COOP/COEP, so every same-origin document
//    and worker response this worker serves gets
//      Cross-Origin-Opener-Policy: same-origin
//      Cross-Origin-Embedder-Policy: credentialless (default) | require-corp
//    and every same-origin response a Cross-Origin-Resource-Policy: same-origin. Once a page
//    loads under this worker, crossOriginIsolated is true and the kernel runtime may use shared
//    WebAssembly memory. The mode comes from this script's own URL: gaius-sw.js?coep=
//    credentialless | require-corp | off. Cross-origin requests are never touched
//    (credentialless already lets no-cors subresources load without credentials).
// 2. Immutable cache. A file name with a content hash (name.<16-64 hex>.ext) never changes, so
//    it is served cache-first from "gaius-immutable-v1" (query strings ignored) and fetched and
//    stored once on a miss. Chrome keeps V8 code caches for scripts and wasm stored this way,
//    which is what makes a warm boot fast. The page sends {type: "gaius-sw-prune", generation,
//    keep: [urls]} after it booted; entries outside the last two generations are deleted.
// 3. Everything else same-origin is network-first; navigations and small files fall back to
//    "gaius-shell-v1" when the network fails, so a cached site still starts offline.
//
// file:// pages have no Service Worker; the portable single-file build never registers one.
// The policy functions are exposed as self.GaiusServiceWorker for the node smoke.
(function (scope) {
  "use strict";

  const SW_VERSION = 1;
  const IMMUTABLE_CACHE = "gaius-immutable-v1";
  const SHELL_CACHE = "gaius-shell-v1";
  const KNOWN_CACHES = [IMMUTABLE_CACHE, SHELL_CACHE];
  const META_PATH = "__gaius-sw/generations.json";
  const HASHED_NAME = /\.[0-9a-f]{16,64}\.[A-Za-z0-9]+$/;
  const COEP_MODES = ["credentialless", "require-corp", "off"];
  const SHELL_MAX_BYTES = 4 * 1024 * 1024;
  const NULL_BODY_STATUS = [101, 103, 204, 205, 304];
  const GENERATIONS_KEPT = 2;

  function configFromUrl(href) {
    let coep = "credentialless";
    try {
      const value = new URL(href).searchParams.get("coep");
      if (COEP_MODES.indexOf(value) >= 0) coep = value;
    } catch (_) {
      // keep the default
    }
    return {coep};
  }

  function stripSearch(href) {
    const url = new URL(href);
    url.search = "";
    url.hash = "";
    return url.href;
  }

  function isImmutable(url) {
    return HASHED_NAME.test(url.pathname);
  }

  // Documents and workers carry the embedder policy; everything else only needs CORP.
  function isPolicyContainer(request) {
    if (request.mode === "navigate") return true;
    const destination = request.destination || "";
    return destination === "document" || destination === "iframe" || destination === "worker"
      || destination === "sharedworker" || destination === "serviceworker";
  }

  function withIsolationHeaders(response, request, config) {
    if (!response || config.coep === "off") return response;
    if (response.type === "opaque" || response.type === "opaqueredirect" || response.type === "error"
        || response.status === 0) {
      return response;
    }
    const headers = new Headers(response.headers);
    if (isPolicyContainer(request)) {
      headers.set("Cross-Origin-Embedder-Policy", config.coep);
      headers.set("Cross-Origin-Opener-Policy", "same-origin");
    }
    if (!headers.has("Cross-Origin-Resource-Policy")) headers.set("Cross-Origin-Resource-Policy", "same-origin");
    const body = NULL_BODY_STATUS.indexOf(response.status) >= 0 ? null : response.body;
    return new Response(body, {status: response.status, statusText: response.statusText, headers});
  }

  // passthrough: not ours (the browser fetches it normally)
  // navigate:    network-first, shell fallback, isolation headers
  // immutable:   cache-first, store on miss
  // network:     network-first, small responses kept in the shell cache as an offline fallback
  // range:       network only (partial responses are never cached)
  function strategyFor(request, scopeOrigin) {
    if (request.method !== "GET") return "passthrough";
    let url;
    try {
      url = new URL(request.url);
    } catch (_) {
      return "passthrough";
    }
    if (url.origin !== scopeOrigin) return "passthrough";
    if (request.cache === "only-if-cached" && request.mode !== "same-origin") return "passthrough";
    if (request.headers && request.headers.get && request.headers.get("range")) return "range";
    if (request.mode === "navigate") return "navigate";
    if (isImmutable(url)) return "immutable";
    return "network";
  }

  function cacheable(response) {
    return !!response && response.status === 200 && (response.type === "basic" || response.type === "default");
  }

  function smallEnough(response) {
    const length = Number(response.headers.get("Content-Length"));
    return Number.isFinite(length) && length > 0 && length <= SHELL_MAX_BYTES;
  }

  function createWorker(deps) {
    const caches = deps.caches;
    const fetchFn = deps.fetch;
    const config = deps.config;
    const scopeOrigin = deps.scopeOrigin;
    const scopeUrl = deps.scopeUrl;
    const waitUntil = (event, promise) => {
      if (event && typeof event.waitUntil === "function") event.waitUntil(promise.catch(() => {}));
      else promise.catch(() => {});
    };

    async function immutable(event, request) {
      const cache = await caches.open(IMMUTABLE_CACHE);
      const hit = await cache.match(request, {ignoreSearch: true});
      if (hit) return {response: hit, source: "cache"};
      const response = await fetchFn(request);
      if (cacheable(response)) {
        waitUntil(event, cache.put(stripSearch(request.url), response.clone()));
      }
      return {response, source: "network"};
    }

    async function networkFirst(event, request, keepAlways) {
      try {
        const response = await fetchFn(request);
        if (cacheable(response) && (keepAlways || smallEnough(response))) {
          const copy = response.clone();
          waitUntil(event, caches.open(SHELL_CACHE).then((cache) => cache.put(stripSearch(request.url), copy)));
        }
        return {response, source: "network"};
      } catch (error) {
        const cache = await caches.open(SHELL_CACHE);
        const hit = await cache.match(request, {ignoreSearch: true});
        if (hit) return {response: hit, source: "shell"};
        throw error;
      }
    }

    // Returns {response, source, strategy} or null for requests the worker leaves alone.
    async function handle(event) {
      const request = event.request;
      const strategy = strategyFor(request, scopeOrigin);
      if (strategy === "passthrough") return null;
      let result;
      if (strategy === "immutable") result = await immutable(event, request);
      else if (strategy === "navigate") result = await networkFirst(event, request, true);
      else if (strategy === "range") result = {response: await fetchFn(request), source: "network"};
      else result = await networkFirst(event, request, false);
      return {
        response: withIsolationHeaders(result.response, request, config),
        source: result.source,
        strategy,
      };
    }

    async function readMeta() {
      const cache = await caches.open(SHELL_CACHE);
      const hit = await cache.match(new URL(META_PATH, scopeUrl).href);
      if (!hit) return {generations: []};
      try {
        const meta = await hit.json();
        return meta && Array.isArray(meta.generations) ? meta : {generations: []};
      } catch (_) {
        return {generations: []};
      }
    }

    async function writeMeta(meta) {
      const cache = await caches.open(SHELL_CACHE);
      await cache.put(new URL(META_PATH, scopeUrl).href, new Response(JSON.stringify(meta), {
        headers: {"Content-Type": "application/json"},
      }));
    }

    // Keeps the hashed assets of the last GENERATIONS_KEPT site generations (a tab still running
    // the previous release keeps working) and deletes every other immutable entry.
    async function prune(generation, keep) {
      const id = String(generation || "");
      const urls = (Array.isArray(keep) ? keep : []).map((href) => stripSearch(new URL(href, scopeUrl).href));
      const meta = await readMeta();
      meta.generations = meta.generations.filter((entry) => entry && entry.id !== id);
      meta.generations.push({id, keep: urls});
      while (meta.generations.length > GENERATIONS_KEPT) meta.generations.shift();
      const union = new Set();
      for (const entry of meta.generations) for (const href of entry.keep) union.add(href);
      const cache = await caches.open(IMMUTABLE_CACHE);
      let deleted = 0;
      for (const request of await cache.keys()) {
        const href = stripSearch(typeof request === "string" ? request : request.url);
        if (!union.has(href)) {
          await cache.delete(request);
          deleted++;
        }
      }
      await writeMeta(meta);
      return {deleted, kept: union.size, generations: meta.generations.length};
    }

    async function status() {
      const cache = await caches.open(IMMUTABLE_CACHE);
      const keys = await cache.keys();
      return {type: "gaius-sw-status", version: SW_VERSION, coep: config.coep, immutableEntries: keys.length};
    }

    async function cleanupOldCaches() {
      const names = await caches.keys();
      await Promise.all(names.filter((name) => name.indexOf("gaius-") === 0 && KNOWN_CACHES.indexOf(name) < 0)
        .map((name) => caches.delete(name)));
    }

    return {handle, prune, status, cleanupOldCaches, config};
  }

  const api = {
    SW_VERSION, IMMUTABLE_CACHE, SHELL_CACHE, HASHED_NAME,
    configFromUrl, isImmutable, strategyFor, withIsolationHeaders, createWorker, stripSearch,
  };
  scope.GaiusServiceWorker = api;

  // Install the handlers only inside a real (or simulated) Service Worker global scope.
  if (typeof scope.addEventListener !== "function" || !scope.caches || !scope.registration) return;

  const scopeUrl = scope.registration.scope;
  const worker = createWorker({
    caches: scope.caches,
    fetch: (request) => scope.fetch(request),
    config: configFromUrl(scope.location.href),
    scopeOrigin: new URL(scopeUrl).origin,
    scopeUrl,
  });
  api.worker = worker;

  scope.addEventListener("install", (event) => {
    event.waitUntil(scope.skipWaiting());
  });

  scope.addEventListener("activate", (event) => {
    event.waitUntil(worker.cleanupOldCaches().then(() => scope.clients.claim()));
  });

  scope.addEventListener("fetch", (event) => {
    if (strategyFor(event.request, new URL(scopeUrl).origin) === "passthrough") return;
    event.respondWith(worker.handle(event).then((result) => result ? result.response : scope.fetch(event.request)));
  });

  scope.addEventListener("message", (event) => {
    const message = event.data;
    if (!message || typeof message !== "object") return;
    const reply = (value) => {
      const port = event.ports && event.ports[0];
      if (port) port.postMessage(value);
      else if (event.source && typeof event.source.postMessage === "function") event.source.postMessage(value);
    };
    if (message.type === "gaius-sw-prune") {
      event.waitUntil(worker.prune(message.generation, message.keep)
        .then((result) => reply(Object.assign({type: "gaius-sw-pruned"}, result)), () => {}));
    } else if (message.type === "gaius-sw-status") {
      event.waitUntil(worker.status().then(reply, () => {}));
    }
  });
})(self);
