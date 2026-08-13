#!/usr/bin/env node

import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {readFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";

const scriptsDirectory = fileURLToPath(new URL(".", import.meta.url));
const benchmarkPath = fileURLToPath(new URL("./chrome-chunk-benchmark.mjs", import.meta.url));
const benchmarkSource = await readFile(benchmarkPath, "utf8");
const configuration = JSON.parse(execFileSync(
  process.execPath,
  [benchmarkPath, "--profile", "steady-6-4", "--print-config"],
  {encoding: "utf8", cwd: scriptsDirectory, maxBuffer: 4 * 1024 * 1024},
));

assert.equal(configuration.profileName, "steady-6-4");
assert.equal(configuration.expectedRenderDistance, 6);
assert.equal(configuration.expectedSimulationDistance, 4);

const gateStart = benchmarkSource.indexOf("async function passProfileGate(session)");
const gateEnd = benchmarkSource.indexOf("\nasync function enterWorld(session)", gateStart);
assert.ok(gateStart >= 0 && gateEnd > gateStart, "profile gate function must be present");
const gateSource = benchmarkSource.slice(gateStart, gateEnd);
const storageReady = gateSource.indexOf("if(globalThis.__gaiusFsReady)await globalThis.__gaiusFsReady");
const filePut = gateSource.indexOf("globalThis.__gaiusFsPut");
const flush = gateSource.indexOf("await globalThis.__gaiusFsFlush()");
const localStorageSet = gateSource.indexOf("localStorage.setItem('gaius.fs.v1:'+path,encoded)");
const workerProxy = gateSource.indexOf("const WorkerProxy=new Proxy(NativeWorker");
const workerStartOverride = gateSource.indexOf("message.type==='start'||message.type==='distances'");
const workerDistanceTelemetry = gateSource.indexOf(
  "worker.__gaiusDistances=expectedDistances.renderDistance+':'+expectedDistances.simulationDistance",
);
const submitClick = gateSource.indexOf("submit.click()");
assert.ok(storageReady >= 0, "profile gate must await page storage readiness");
assert.ok(filePut > storageReady, "options must be written after storage readiness");
assert.ok(flush > filePut, "options must be flushed before the persistence fallback is written");
assert.ok(localStorageSet > flush, "the enumerable options fallback must be written after flush");
assert.ok(workerProxy >= 0, "profile gate must install the benchmark Worker proxy");
assert.ok(workerStartOverride > workerProxy, "Worker launch messages must be intercepted");
assert.ok(workerDistanceTelemetry > workerStartOverride, "Worker telemetry must record the forced distance");
assert.ok(submitClick > workerDistanceTelemetry, "the Worker proxy must be installed before profile submit");
assert.match(
  gateSource,
  /seeded:persisted\|\|fsPut\|\|localStoragePut/,
  "profile gate must require a verifiable 6/4 seed path",
);

console.log("Chrome steady-6-4 profile distance smoke passed (render=6 simulation=4)");
