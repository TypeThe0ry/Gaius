#!/usr/bin/env node
// Smoke for the launcher's single-runtime lease (acquireGaiusRuntimeLease in
// port/web/launcher/index.template.html and the copy postprocess-index-html.py injects into an old
// page). The function runs in node:vm contexts that stand for documents of one browser profile:
// they share a Web Locks manager (or one localStorage when Web Locks are missing), so the cases
// below are a page replaced in the same tab and a second tab:
//   - a free lease is taken at once;
//   - a lease whose old document lets go late (the same-tab reload of a running game: its lock
//     lives until that document is destroyed) is taken after the wait, not reported as another tab;
//   - a lease a live tab keeps is reported as another tab once the wait runs out;
//   - pagehide/unload release even pending claims; bfcache restoration reloads before boot;
//   - the same three outcomes on the localStorage lease, where a live tab renews its lease.
// The handoff wait and the stale period are shortened for the test (the page uses 8 s and 10 s).
// Exit status 0 when every case passes.

import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {dirname, join} from "node:path";
import {fileURLToPath} from "node:url";
import vm from "node:vm";

const root = join(dirname(fileURLToPath(import.meta.url)), "../..");
const HANDOFF_MS = 400;
const STALE_MS = 600;

// The lease code of the launcher: from the comment above the handoff constant through the end of
// acquireGaiusRuntimeLease.
function leaseSource(text, label) {
  const start = text.indexOf("    // One Gaius runtime per browser profile:");
  const body = text.indexOf("    async function acquireGaiusRuntimeLease() {", start);
  const end = text.indexOf("\n    }\n", text.indexOf("Cross-tab runtime lease is unavailable", body));
  assert.ok(start >= 0 && body > start && end > body, `${label}: lease code not found`);
  const source = text.slice(start, end + "\n    }\n".length);
  assert.match(source, /const runtimeLeaseHandoffMs = 8000;/, `${label}: handoff wait changed`);
  assert.match(source, /const leaseStaleMs = 10000;/, `${label}: stale period changed`);
  return source.replace("const runtimeLeaseHandoffMs = 8000;", `const runtimeLeaseHandoffMs = ${HANDOFF_MS};`)
    .replace("const leaseStaleMs = 10000;", `const leaseStaleMs = ${STALE_MS};`)
    .replace("setInterval(renewLease, 3000)", "setInterval(renewLease, 100)");
}

const template = readFileSync(join(root, "port/web/launcher/index.template.html"), "utf8");
const templateSource = leaseSource(template, "index.template.html");
// postprocess-index-html.py injects the same code (as Python string lines) into a page without it.
const postprocess = readFileSync(join(root, "port/scripts/postprocess-index-html.py"), "utf8");
const injected = [...postprocess.matchAll(/^ {12}("(?:[^"\\]|\\.)*")$/gm)].map(match => JSON.parse(match[1])).join("");
assert.equal(leaseSource(injected, "postprocess-index-html.py"), templateSource,
  "postprocess-index-html.py must inject the launcher's lease code unchanged");

// Web Locks of one profile: exclusive locks, ifAvailable, AbortSignal and release when the
// callback's promise settles.
class LockManager {
  constructor() { this.holder = null; this.queue = []; }
  request(name, options, callback) {
    return new Promise((resolve, reject) => {
      const grant = () => {
        const token = {};
        this.holder = token;
        Promise.resolve().then(() => callback({name, mode: "exclusive"})).then(value => {
          if (this.holder === token) this.holder = null;
          resolve(value);
          this.next();
        }, error => {
          if (this.holder === token) this.holder = null;
          reject(error);
          this.next();
        });
      };
      if (!this.holder && !this.queue.length) { grant(); return; }
      if (options.ifAvailable) { Promise.resolve().then(() => callback(null)).then(resolve, reject); return; }
      const entry = {grant};
      this.queue.push(entry);
      options.signal?.addEventListener("abort", () => {
        const index = this.queue.indexOf(entry);
        if (index < 0) return;
        this.queue.splice(index, 1);
        reject(new DOMException("The request was aborted", "AbortError"));
      });
    });
  }
  next() { if (!this.holder && this.queue.length) this.queue.shift().grant(); }
  // A document that holds the lock outside the code under test (the page being replaced).
  hold() {
    let release;
    this.request("gaius-client-runtime-v1", {mode: "exclusive"}, () => new Promise(done => { release = done; }));
    return () => release();
  }
}

class Storage {
  constructor() { this.map = new Map(); }
  getItem(key) { return this.map.has(key) ? this.map.get(key) : null; }
  setItem(key, value) { this.map.set(key, String(value)); }
  removeItem(key) { this.map.delete(key); }
}

// One document: runs the lease code with the profile's locks (or none) and storage.
function documentWith({locks, storage}) {
  const listeners = new Map();
  const context = {
    console: {warn() {}, info() {}, error() {}},
    setTimeout, clearTimeout, clearInterval, Promise, JSON, Date, Math, AbortController, DOMException,
    setInterval(callback, delay) {
      const id = setInterval(callback, delay);
      intervals.push(id);
      return id;
    },
    performance: {now: () => performance.now()},
    crypto: {randomUUID: () => crypto.randomUUID()},
    navigator: locks ? {locks} : {},
    localStorage: storage,
    urlParams: new URLSearchParams(""),
    bootTimings: {},
    location: {reloads: 0, reload() { this.reloads++; }},
    addEventListener(type, listener) {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push(listener);
    },
  };
  context.window = context;
  vm.createContext(context);
  vm.runInContext(`${templateSource}\nthis.acquire = acquireGaiusRuntimeLease;`, context);
  return {
    context,
    async acquire() {
      const started = performance.now();
      const result = await context.acquire();
      return {result, ms: performance.now() - started};
    },
    pagehide(persisted) {
      for (const listener of listeners.get("pagehide") || []) listener({type: "pagehide", persisted});
    },
    dispatch(type, properties = {}) {
      for (const listener of listeners.get(type) || []) listener({type, ...properties});
    },
  };
}

const sleep = ms => new Promise(done => setTimeout(done, ms));
const intervals = [];
const cases = [];
async function check(name, body) {
  try {
    await body();
    cases.push(name);
    console.log(`ok ${name}`);
  } finally {
    for (const id of intervals.splice(0)) clearInterval(id);
  }
}

await check("web-locks: a free lease is taken at once", async () => {
  const locks = new LockManager();
  const page = documentWith({locks, storage: new Storage()});
  const {result, ms} = await page.acquire();
  assert.equal(result, true);
  assert.ok(ms < HANDOFF_MS / 2, `took ${ms} ms`);
  assert.equal(page.context.__gaiusRuntimeLeaseHeld, true);
  assert.equal(page.context.bootTimings.runtimeLeaseWaitStart, undefined);
  page.pagehide(false);
});

await check("web-locks: a lease released late by the replaced document is handed over", async () => {
  const locks = new LockManager();
  const releaseOld = locks.hold();
  setTimeout(releaseOld, HANDOFF_MS / 2);
  const page = documentWith({locks, storage: new Storage()});
  const {result, ms} = await page.acquire();
  assert.equal(result, true, "a same-tab reload must not report another tab");
  assert.ok(ms >= HANDOFF_MS / 2 - 20, `took ${ms} ms`);
  assert.equal(typeof page.context.bootTimings.runtimeLeaseWaitStart, "number");
  page.pagehide(false);
});

await check("web-locks: a lease a live tab keeps is reported as another tab", async () => {
  const locks = new LockManager();
  const live = documentWith({locks, storage: new Storage()});
  assert.equal((await live.acquire()).result, true);
  const second = documentWith({locks, storage: new Storage()});
  const {result, ms} = await second.acquire();
  assert.equal(result, false, "the multi-tab guard must hold");
  assert.ok(ms >= HANDOFF_MS - 20, `gave up after ${ms} ms`);
  assert.equal(locks.queue.length, 0, "the aborted request must leave the queue");
  assert.notEqual(second.context.__gaiusRuntimeLeaseHeld, true);
  // The live tab still holds it: a third page is refused as well.
  assert.equal((await documentWith({locks, storage: new Storage()}).acquire()).result, false);
  live.pagehide(false);
});

await check("web-locks: bfcache releases the lease and restoration reloads", async () => {
  const locks = new LockManager();
  const page = documentWith({locks, storage: new Storage()});
  assert.equal((await page.acquire()).result, true);
  page.pagehide(true);
  await sleep(10);
  assert.equal(locks.holder, null, "a cached page must not keep a frozen lock");
  page.dispatch("pageshow", {persisted: true});
  assert.equal(page.context.location.reloads, 1);
  page.pagehide(false);
  await sleep(10);
  assert.equal(locks.holder, null, "pagehide must release the lease");
  assert.equal(page.context.__gaiusRuntimeLeaseHeld, false);
  const next = documentWith({locks, storage: new Storage()});
  const {result, ms} = await next.acquire();
  assert.equal(result, true);
  assert.ok(ms < HANDOFF_MS / 2, `took ${ms} ms`);
  next.pagehide(false);
});

await check("localStorage: a free lease is taken, a released one is handed over", async () => {
  const storage = new Storage();
  const first = documentWith({locks: null, storage});
  assert.equal((await first.acquire()).result, true);
  assert.ok(storage.getItem("gaius.runtimeLease.v1"));
  setTimeout(() => first.pagehide(false), HANDOFF_MS / 2);
  const second = documentWith({locks: null, storage});
  const {result, ms} = await second.acquire();
  assert.equal(result, true);
  assert.ok(ms >= HANDOFF_MS / 2 - 20, `took ${ms} ms`);
  second.pagehide(false);
  assert.equal(storage.getItem("gaius.runtimeLease.v1"), null);
});

await check("localStorage: a lease left behind turns stale and is taken over", async () => {
  const storage = new Storage();
  storage.setItem("gaius.runtimeLease.v1", JSON.stringify({id: "gone", at: Date.now()}));
  const page = documentWith({locks: null, storage});
  const {result, ms} = await page.acquire();
  assert.equal(result, true);
  assert.ok(ms >= STALE_MS - 50 && ms < STALE_MS + HANDOFF_MS, `took ${ms} ms`);
  page.pagehide(false);
});

await check("localStorage: a lease a live tab renews is reported as another tab", async () => {
  const storage = new Storage();
  const renew = () => storage.setItem("gaius.runtimeLease.v1", JSON.stringify({id: "live", at: Date.now()}));
  renew();
  intervals.push(setInterval(renew, 100));
  const page = documentWith({locks: null, storage});
  const {result, ms} = await page.acquire();
  assert.equal(result, false, "the multi-tab guard must hold");
  assert.ok(ms >= STALE_MS + HANDOFF_MS - 50, `gave up after ${ms} ms`);
  assert.equal(JSON.parse(storage.getItem("gaius.runtimeLease.v1")).id, "live");
});

await check("web-locks: pagehide before an asynchronous grant cannot resurrect a claim", async () => {
  const locks = new LockManager();
  const page = documentWith({locks, storage: new Storage()});
  const pending = page.acquire();
  page.pagehide(false);
  assert.equal((await pending).result, false);
  await sleep(10);
  assert.equal(locks.holder, null);
  assert.notEqual(page.context.__gaiusRuntimeLeaseHeld, true);
});

await check("web-locks: unload aborts a pending handoff without releasing the live tab", async () => {
  const locks = new LockManager();
  const live = documentWith({locks, storage: new Storage()});
  assert.equal((await live.acquire()).result, true);
  const second = documentWith({locks, storage: new Storage()});
  const pending = second.acquire();
  await sleep(20);
  second.dispatch("unload");
  assert.equal((await pending).result, false);
  assert.equal(locks.queue.length, 0);
  assert.equal(live.context.__gaiusRuntimeLeaseHeld, true);
  live.pagehide(false);
});

await check("localStorage: pagehide during claim confirmation cannot resurrect a lease", async () => {
  const storage = new Storage();
  const page = documentWith({locks: null, storage});
  const pending = page.acquire();
  page.pagehide(false);
  assert.equal((await pending).result, false);
  await sleep(120);
  assert.equal(storage.getItem("gaius.runtimeLease.v1"), null);
  assert.notEqual(page.context.__gaiusRuntimeLeaseHeld, true);
});

await check("localStorage: the actual heartbeat protects a live tab past the stale period", async () => {
  const storage = new Storage();
  const live = documentWith({locks: null, storage});
  assert.equal((await live.acquire()).result, true);
  const second = documentWith({locks: null, storage});
  assert.equal((await second.acquire()).result, false);
  assert.equal(live.context.__gaiusRuntimeLeaseHeld, true);
  live.pagehide(false);
});

await check("localStorage: concurrent claims admit only one runtime", async () => {
  const storage = new Storage();
  const pages = Array.from({length: 3}, () => documentWith({locks: null, storage}));
  const results = await Promise.all(pages.map(page => page.acquire()));
  assert.equal(results.filter(result => result.result).length, 1);
  for (const page of pages) page.pagehide(false);
  assert.equal(storage.getItem("gaius.runtimeLease.v1"), null);
});

for (const notification of ["storage", "heartbeat"]) {
  await check(`localStorage: ${notification} stops an obsolete owner from overwriting its successor`, async () => {
    const storage = new Storage();
    const page = documentWith({locks: null, storage});
    assert.equal((await page.acquire()).result, true);
    const successor = JSON.stringify({id: "new-owner", at: Date.now()});
    storage.setItem("gaius.runtimeLease.v1", successor);
    if (notification === "storage") page.dispatch("storage", {key: "gaius.runtimeLease.v1"});
    else await sleep(120);
    assert.equal(storage.getItem("gaius.runtimeLease.v1"), successor);
    assert.equal(page.context.__gaiusRuntimeLeaseHeld, false);
    assert.equal(page.context.location.reloads, 1);
    page.dispatch("unload");
    assert.equal(storage.getItem("gaius.runtimeLease.v1"), successor);
  });
}

await check("localStorage: failed lease cleanup still reloads an unprotected runtime", async () => {
  const storage = new Storage();
  const page = documentWith({locks: null, storage});
  assert.equal((await page.acquire()).result, true);
  storage.setItem = () => { throw new Error("storage revoked"); };
  storage.removeItem = () => { throw new Error("storage revoked"); };
  await sleep(120);
  assert.equal(page.context.__gaiusRuntimeLeaseHeld, false);
  assert.equal(page.context.location.reloads, 1);
});

let warmReplacements = 0;
for (const backend of ["web-locks", "localStorage"]) {
  await check(`${backend}: 70 warm replacements have no false denials`, async () => {
    const locks = backend === "web-locks" ? new LockManager() : null;
    const storage = new Storage();
    for (let index = 0; index < 70; index++) {
      const page = documentWith({locks, storage});
      assert.equal((await page.acquire()).result, true, `replacement ${index}`);
      if (index % 2) page.dispatch("unload");
      else page.pagehide(index % 7 === 0);
      await sleep(1);
      if (locks) assert.equal(locks.holder, null);
      else assert.equal(storage.getItem("gaius.runtimeLease.v1"), null);
      warmReplacements++;
    }
  });
}

console.log(`RUNTIME_LEASE_SMOKE_OK ${cases.length} cases; ${warmReplacements} warm replacements, 0 false denials`);
