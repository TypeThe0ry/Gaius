#!/usr/bin/env node

import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {readFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";

const scriptsDirectory = fileURLToPath(new URL(".", import.meta.url));
const benchmarkPath = fileURLToPath(new URL("./chrome-chunk-benchmark.mjs", import.meta.url));
const benchmarkSource = await readFile(benchmarkPath, "utf8");
const isolatedBenchmarkEnvironment = {
  ...process.env,
  GAIUS_VERSION_PROFILE_PATH: "versions/26.2.json",
  GAIUS_BUILD_ROOT: "port/target/26.2",
  // Allow the caller to point the smoke at an independently hashed release
  // dist (for example a cluster artifact) while retaining the profile/build
  // identity contract below.  The default remains the repository profile
  // directory and still fails closed when its manifest is stale.
  GAIUS_DIST_DIRECTORY: process.env.GAIUS_DIST_DIRECTORY || "port/web/dist/26.2",
  GAIUS_OVERLAY_DIRECTORY: "port/work/overlays/26.2",
};
const configuration = JSON.parse(execFileSync(
  process.execPath,
  [benchmarkPath, "--profile", "steady-6-4", "--print-config"],
  {
    encoding: "utf8",
    cwd: scriptsDirectory,
    env: isolatedBenchmarkEnvironment,
    maxBuffer: 4 * 1024 * 1024,
  },
));

assert.equal(configuration.profileName, "steady-6-4");
assert.equal(configuration.expectedRenderDistance, 6);
assert.equal(configuration.expectedSimulationDistance, 4);
assert.equal(configuration.workerDistanceMode, "natural-observation");
assert.equal(configuration.workerDistancePin, false);
assert.equal(configuration.workerDistanceContract.mode, "natural-observation");
assert.equal(configuration.workerDistanceContract.releaseEligible, true);
assert.equal(configuration.workerDistanceContract.releaseTargetProfile, "26.2");
assert.equal(configuration.workerDistanceContract.activeProfileId, "26.2");
assert.equal(configuration.workerDistanceContract.expectedStartDistance, "6:4");
assert.equal(
  configuration.workerDistanceContract.effectiveDistanceModel,
  "min(client-options-preference,worker-server-distance)",
);
assert.equal(configuration.activeVersionProfile.storageSchema, 2);
assert.match(configuration.activeVersionProfile.storageDatabaseName, /^gaius-fs-v2-/);
assert.match(configuration.activeVersionProfile.storagePrefix, /^gaius\.fs\.v2:/);
assert.match(configuration.activeVersionProfile.storageOpfsDirectory, /^regions-v2-/);
assert.equal(configuration.isolatedEnvironment, true);
assert.equal(configuration.attachMode, "disabled-input-required");
assert.equal(configuration.distRoot.endsWith("\\26.2") || configuration.distRoot.endsWith("/26.2"), true);
assert.equal(configuration.buildIdentity.profile, "26.2");
assert.equal(configuration.buildIdentity.profilePath, "versions/26.2.json");
assert.equal(configuration.buildIdentity.nestedProfile.id, "26.2");
assert.equal(configuration.buildIdentity.nestedProfile.path, "versions/26.2.json");
assert.equal(configuration.buildIdentity.nestedProfile.protocolVersion, 776);
assert.equal(configuration.buildIdentity.nestedProfile.worldVersion, 4903);
const pinConfiguration = JSON.parse(execFileSync(
  process.execPath,
  [benchmarkPath, "--profile", "steady-6-4", "--pin-worker-distance", "--print-config"],
  {
    encoding: "utf8",
    cwd: scriptsDirectory,
    env: isolatedBenchmarkEnvironment,
    maxBuffer: 4 * 1024 * 1024,
  },
));
assert.equal(pinConfiguration.workerDistanceMode, "harness-pin-diagnostic");
assert.equal(pinConfiguration.workerDistancePin, true);
assert.equal(pinConfiguration.workerDistanceContract.mode, "harness-pin-diagnostic");
assert.equal(pinConfiguration.workerDistanceContract.releaseEligible, false);
assert.equal(pinConfiguration.workerDistanceContract.releaseTargetProfile, "26.2");
assert.equal(pinConfiguration.gating, false);
assert.equal(pinConfiguration.releaseEvidence, false);
assert.equal(pinConfiguration.mode, "diagnostic-pin-non-gating");
assert.doesNotMatch(benchmarkSource, /gaius\.fs\.v1:/,
  "benchmark options must not read or write the retired v1 namespace");
assert.match(benchmarkSource, /activeVersionProfile\.storage/,
  "benchmark must derive storage from the active version profile");
assert.match(benchmarkSource, /attachPortRequested/,
  "benchmark must fail closed whenever --attach-port is present");
assert.match(benchmarkSource, /--attach-port is disabled/,
  "benchmark must not permit attached synthetic input");
assert.match(benchmarkSource, /benchmarkKeyCodes/,
  "benchmark cleanup must enumerate all possible key releases");
for (const key of ["KeyW", "KeyA", "KeyS", "KeyD", "Space", "ControlLeft", "ShiftLeft", "Escape"]) {
  assert.match(benchmarkSource, new RegExp(key),
    `benchmark cleanup must include ${key} keyUp coverage`);
}
assert.match(benchmarkSource, /resolveRepositoryPortPath/,
  "benchmark paths must not depend on child cwd");
assert.match(benchmarkSource, /manifest\.buildIdentity\.profile\.worldVersion/,
  "benchmark must verify nested manifest world version");
for (const rejectedArgs of [
  ["--attach-port", "9222", "--print-config"],
  ["--allow-attached-input", "--print-config"],
]) {
  assert.throws(
    () => execFileSync(
      process.execPath,
      [benchmarkPath, "--profile", "steady-6-4", ...rejectedArgs],
      {encoding: "utf8", cwd: scriptsDirectory, env: isolatedBenchmarkEnvironment,
        stdio: ["ignore", "ignore", "ignore"]},
    ),
    (error) => error?.status === 1,
    `benchmark must reject ${rejectedArgs[0]}`,
  );
}

const gateStart = benchmarkSource.indexOf("async function passProfileGate(session)");
const gateEnd = benchmarkSource.indexOf("\nasync function enterWorld(session)", gateStart);
assert.ok(gateStart >= 0 && gateEnd > gateStart, "profile gate function must be present");
const gateSource = benchmarkSource.slice(gateStart, gateEnd);
const storageReady = gateSource.indexOf("if(globalThis.__gaiusFsReady)await globalThis.__gaiusFsReady");
const filePut = gateSource.indexOf("globalThis.__gaiusFsPut");
const flush = gateSource.indexOf("await globalThis.__gaiusFsFlush()");
const storagePrefix = gateSource.indexOf("benchmarkStoragePrefix");
const localStorageSet = gateSource.indexOf(
  "localStorage.setItem(benchmarkStoragePrefix+path,encoded)",
);
const workerProxy = gateSource.indexOf("const WorkerProxy=new Proxy(NativeWorker");
const workerMessageIntercept = gateSource.indexOf("message.type==='start'||message.type==='distances'");
const naturalSnapshot = gateSource.indexOf("const original=snapshotWorkerMessage(message)");
const naturalEvidenceLog = gateSource.indexOf("appendBounded(naturalWorkerLog,naturalWorkerLogState");
const harnessPin = gateSource.indexOf("const pinned={...message,...expectedDistances,...expectedStorage}");
const workerDistanceTelemetry = gateSource.indexOf(
  "if(pinWorkerDistance)globalThis.__gaiusBenchmarkWorkerDistanceOverride=true",
);
const workerRegistryCheck = gateSource.indexOf("workerRegistrySize");
const workerOverrideLog = gateSource.indexOf("appendBounded(workerOverrideLog,harnessOverrideLogState");
const workerSequence = gateSource.indexOf("__gaiusBenchmarkWorkerSequence");
const boundedLog = gateSource.indexOf("if(log.length>=64)");
const workerStartEvidence = benchmarkSource.indexOf("workerStartContractMatches");
const workerEvidenceCollector = benchmarkSource.indexOf("async function collectWorkerDistanceEvidence(session)");
const naturalEvidenceCollector = benchmarkSource.indexOf("naturalWorkerEvidence:{mode:'natural-observation'");
const harnessEvidenceCollector = benchmarkSource.indexOf("harnessOverrideEvidence:{enabled:");
const submitClick = gateSource.indexOf("submit.click()");
assert.ok(storageReady >= 0, "profile gate must await page storage readiness");
assert.ok(filePut > storageReady, "options must be written after storage readiness");
assert.ok(flush > filePut, "options must be flushed before the persistence fallback is written");
assert.ok(localStorageSet > flush, "the enumerable options fallback must be written after flush");
assert.ok(storagePrefix > storageReady, "profile gate must use the active storage prefix");
assert.ok(workerProxy >= 0, "profile gate must install the benchmark Worker proxy");
assert.ok(workerMessageIntercept > workerProxy, "Worker launch messages must be observed");
assert.ok(naturalSnapshot > workerMessageIntercept, "natural evidence must snapshot every raw Worker message");
assert.ok(naturalEvidenceLog > naturalSnapshot, "natural Worker evidence must be recorded");
assert.ok(harnessPin > naturalEvidenceLog, "distance pin must be an explicit opt-in branch");
assert.doesNotMatch(gateSource, /message=Object\.assign\(\{\},message/,
  "default Worker observation must not Object.assign-rewrite the launch message");
assert.match(gateSource, /return transfer===undefined\?originalPostMessage\(message\)/,
  "natural Worker observation must forward the original message");
assert.ok(workerDistanceTelemetry > harnessPin, "pin marker must remain after the explicit harness branch");
assert.ok(workerRegistryCheck >= 0, "profile gate must fail closed on pre-existing Workers");
assert.ok(workerOverrideLog > harnessPin, "profile gate must record harness overrides separately");
assert.ok(workerSequence > workerProxy, "profile gate must assign a bounded Worker sequence");
assert.ok(boundedLog >= 0, "Worker evidence records must be bounded");
assert.ok(submitClick > naturalEvidenceLog, "the Worker proxy must be installed before profile submit");
assert.ok(workerStartEvidence >= 0, "analysis must require an exact natural start-message distance/storage witness");
assert.ok(workerEvidenceCollector >= 0, "benchmark must collect Worker launch evidence after launch");
assert.ok(naturalEvidenceCollector >= 0, "benchmark report must expose naturalWorkerEvidence");
assert.ok(harnessEvidenceCollector >= 0, "benchmark report must expose harnessOverrideEvidence");
assert.match(
  gateSource,
  /seeded:persisted\|\|fsPut\|\|localStoragePut/,
  "profile gate must require a verifiable 6/4 seed path",
);

console.log("Chrome steady-6-4 profile distance smoke passed (render=6 simulation=4)");
