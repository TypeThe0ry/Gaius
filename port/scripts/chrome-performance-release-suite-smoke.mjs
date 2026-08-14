#!/usr/bin/env node

import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {readFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";
import {
  createReleasePlan,
  validateContractShape,
  validateChildReport,
} from "./chrome-performance-release-suite.mjs";
import {summarizeAcceptanceEvidence} from "./performance-metrics.mjs";
import {
  acceptanceFixtureIdentity,
  acceptanceFixtureProfile,
  makeAcceptanceFixtureReport,
} from "./chrome-performance-release-fixtures.mjs";

const script = fileURLToPath(new URL(
  "./chrome-performance-release-suite.mjs",
  import.meta.url,
));
const source = await readFile(script, "utf8");
for (const required of [
  "createReleasePlan",
  "validateChildReport",
  "summarizeAcceptanceEvidence",
  "hardTargetProfiles",
  "requiredMemoryProfiles",
  "unsupportedStabilityProfiles",
  "configuration.profileName",
  "configuration.contractSchemaVersion",
  "analysis.releaseEvidence",
  "analysis.performanceEvidence",
  "uncappedYieldCount",
  "presentToRafCount",
  "fairYieldCount",
  "messageChannelCreateFailureCount",
  "messageChannelPostFailureCount",
  "messageChannelRebuildCount",
  "cancelledMessageTaskCount",
  "watchdogYieldCount",
  "minimumFairYieldCount",
  "maximumWatchdogYieldCount",
  "REQUIRED_UNCAPPED_TELEMETRY_FIELDS",
  "hasFiniteNumber",
  "buildIdentity.coherent",
  "failureEvidence",
  "manifestSha256",
  "compatibilitySha256",
  "child report verdict must be",
  '"smoke-pass"',
  'releaseEvidence: false',
  "uncappedMatrixProfiles",
  "acceptance evidence is unverified",
  "--matrix",
]) {
  assert.ok(source.includes(required), `release suite is missing ${required}`);
}

const fixtureProfile = {
  ...acceptanceFixtureProfile,
};
const fixtureMemoryProfile = {
  ...fixtureProfile,
  evidenceRole: "release-stability",
  gates: {...fixtureProfile.gates, memory: true},
  soakMs: 1_800_000,
};
const fixtureUnsupportedProfile = {
  ...fixtureMemoryProfile,
  route: "multiplayer-relay",
  driverSupported: false,
};
const fixtureContract = {
  schemaVersion: 17,
  measurement: {soakMs: 1_800_000},
  environment: {
    uncappedEvidence: {
      requiredSwapInterval: 0,
      minimumSamples: 2,
      minimumUncappedYieldCount: 1,
      minimumFairYieldCount: 1,
      maximumVsyncYieldCount: 0,
      maximumPresentToRafCount: 0,
      maximumMessageChannelCreateFailureCount: 0,
      maximumMessageChannelPostFailureCount: 0,
      maximumMessageChannelRebuildCount: 0,
      maximumCancelledMessageTaskCount: 0,
      maximumWatchdogYieldCount: 0,
      requiredFields: [
        "swapInterval",
        "uncappedYieldCount",
        "vsyncYieldCount",
        "presentToRafCount",
        "fairYieldCount",
        "messageChannelCreateFailureCount",
        "messageChannelPostFailureCount",
        "messageChannelRebuildCount",
        "cancelledMessageTaskCount",
        "watchdogYieldCount",
      ],
    },
  },
  releaseEvidence: {
    hardTargetProfiles: ["hard-a", "hard-b"],
    stabilityProfiles: ["soak-mp"],
    requiredMemoryProfiles: ["memory-a"],
    uncappedMatrixProfiles: ["hard-a", "matrix-8", "matrix-12"],
    acceptance: {
      maximumTwoSecondStalls: 0,
      maximumFreezeCount: 0,
      maximumCrashSignals: 0,
      messagePortP99MaxMs: 250,
      messagePortMaxMs: 2_000,
      chunkBacklogPaths: [
        "chunk.pendingTasks",
        "chunk.compileBacklog",
        "chunk.uploadBacklog",
      ],
      memoryRequiredProfiles: ["memory-a"],
    },
  },
  profiles: {
    "hard-a": fixtureProfile,
    "hard-b": fixtureProfile,
    "matrix-8": {
      ...fixtureProfile,
      evidenceRole: "diagnostic-stress",
      releaseEvidence: false,
      renderDistance: 8,
    },
    "matrix-12": {
      ...fixtureProfile,
      evidenceRole: "diagnostic-stress",
      releaseEvidence: false,
      renderDistance: 12,
    },
    "memory-a": fixtureMemoryProfile,
    "soak-mp": fixtureUnsupportedProfile,
  },
};
assert.doesNotThrow(() => validateContractShape(fixtureContract));
const fixtureUncappedEvidence = fixtureContract.environment.uncappedEvidence;
const fixtureIdentity = acceptanceFixtureIdentity;

assert.throws(
  () => createReleasePlan(fixtureContract, ["hard-a"], {smoke: false}),
  /omitted mandatory profile.*hard-b.*memory-a/,
  "strict release must reject omitted hard targets and memory evidence",
);
assert.throws(
  () => createReleasePlan(
    fixtureContract,
    ["hard-a", "hard-b", "memory-a", "soak-mp"],
    {smoke: false},
  ),
  /Unsupported performance profile.*soak-mp/,
  "unsupported soak-mp must not be selectable as release evidence",
);
const defaultPlan = createReleasePlan(fixtureContract, null, {smoke: false});
assert.deepEqual(defaultPlan.selectedProfiles, ["hard-a", "hard-b", "memory-a"]);
assert.deepEqual(defaultPlan.unsupportedStabilityProfiles, ["soak-mp"]);

function childReport(overrides = {}) {
  return {
    ...makeAcceptanceFixtureReport({
      profileName: "hard-a",
      profile: fixtureContract.profiles["hard-a"],
      contractSchemaVersion: fixtureContract.schemaVersion,
      buildIdentity: fixtureIdentity,
    }),
    ...overrides,
  };
}

const completeAcceptance = summarizeAcceptanceEvidence({
  report: childReport(),
  profileName: "hard-a",
  profile: fixtureContract.profiles["hard-a"],
  contract: fixtureContract,
});
assert.equal(completeAcceptance.verdict, "pass");
assert.equal(completeAcceptance.measured.frame.averageFps, 144);
assert.equal(completeAcceptance.measured.frame.onePercentLowFps, 72);
assert.equal(completeAcceptance.measured.frame.longFramesAtLeast2s, 0);
assert.equal(completeAcceptance.measured.messagePort.p99RttMillis, 12);
assert.equal(completeAcceptance.measured.chunkBacklog.paths["chunk.compileBacklog"].maximum, 1);
assert.equal(completeAcceptance.measured.memory.available, true);

const missingMessagePortChild = childReport();
delete missingMessagePortChild.analysis.workerMessage;
const missingMessagePortEvidence = summarizeAcceptanceEvidence({
  report: missingMessagePortChild,
  profileName: "hard-a",
  profile: fixtureContract.profiles["hard-a"],
  contract: fixtureContract,
});
assert.equal(missingMessagePortEvidence.verdict, "inconclusive");
assert.match(missingMessagePortEvidence.missing.join("\n"), /messagePort/);

const missingMemoryChild = childReport();
delete missingMemoryChild.analysis.memory;
const missingMemoryEvidence = summarizeAcceptanceEvidence({
  report: missingMemoryChild,
  profileName: "hard-a",
  profile: fixtureContract.profiles["hard-a"],
  contract: fixtureContract,
});
assert.equal(missingMemoryEvidence.verdict, "inconclusive");
assert.match(missingMemoryEvidence.missing.join("\n"), /memory\.trend/);

const stalledChild = childReport();
stalledChild.analysis.frame.longFrames.atLeast2000Ms = 1;
const stalledEvidence = summarizeAcceptanceEvidence({
  report: stalledChild,
  profileName: "hard-a",
  profile: fixtureContract.profiles["hard-a"],
  contract: fixtureContract,
});
assert.equal(stalledEvidence.verdict, "fail");
assert.match(stalledEvidence.failures.join("\n"), /2-second stall/);

const growingMemoryChild = childReport();
growingMemoryChild.analysis.memory.v8Heap.leakSignal = true;
const growingMemoryEvidence = summarizeAcceptanceEvidence({
  report: growingMemoryChild,
  profileName: "hard-a",
  profile: fixtureContract.profiles["hard-a"],
  contract: fixtureContract,
});
assert.equal(growingMemoryEvidence.verdict, "fail");
assert.match(growingMemoryEvidence.failures.join("\n"), /memory trend/);

assert.equal(validateChildReport(childReport(), {
  profileName: "hard-a",
  profile: fixtureContract.profiles["hard-a"],
  contractSchemaVersion: fixtureContract.schemaVersion,
  expectedBuildIdentity: fixtureIdentity,
  uncappedEvidence: fixtureUncappedEvidence,
}).valid, true);
const inconclusiveChild = childReport({
  verdict: "inconclusive",
  analysis: {...childReport().analysis, verdict: "inconclusive"},
});
const inconclusiveValidation = validateChildReport(inconclusiveChild, {
  profileName: "hard-a",
  profile: fixtureContract.profiles["hard-a"],
  contractSchemaVersion: fixtureContract.schemaVersion,
  expectedBuildIdentity: fixtureIdentity,
  uncappedEvidence: fixtureUncappedEvidence,
});
assert.equal(inconclusiveValidation.valid, false);
assert.equal(inconclusiveValidation.inconclusive, true);
assert.match(inconclusiveValidation.failures.join("\n"), /unverified/);
assert.equal(validateChildReport(childReport({
  analysis: {
    ...childReport().analysis,
    performanceEvidence: undefined,
  },
}), {
  profileName: "hard-a",
  profile: fixtureContract.profiles["hard-a"],
  contractSchemaVersion: fixtureContract.schemaVersion,
  expectedBuildIdentity: fixtureIdentity,
  uncappedEvidence: fixtureUncappedEvidence,
}).valid, false, "strict release must reject a child report without uncapped evidence");
assert.equal(validateChildReport(childReport({
  analysis: {
    ...childReport().analysis,
    performanceEvidence: {
      framePacing: {verdict: "inconclusive", observed: {}},
    },
  },
}), {
  profileName: "hard-a",
  profile: fixtureContract.profiles["hard-a"],
  contractSchemaVersion: fixtureContract.schemaVersion,
  expectedBuildIdentity: fixtureIdentity,
  uncappedEvidence: fixtureUncappedEvidence,
}).valid, false, "strict release must reject options-only evidence");

const missingHealthChild = childReport();
delete missingHealthChild.analysis.performanceEvidence.framePacing.observed.watchdogYieldCountMax;
assert.equal(validateChildReport(missingHealthChild, {
  profileName: "hard-a",
  profile: fixtureContract.profiles["hard-a"],
  contractSchemaVersion: fixtureContract.schemaVersion,
  expectedBuildIdentity: fixtureIdentity,
  uncappedEvidence: fixtureUncappedEvidence,
}).valid, false, "strict release must reject missing scheduler health telemetry");

const failedHealthChild = childReport();
failedHealthChild.analysis.performanceEvidence.framePacing.observed.messageChannelPostFailureCountMax = 1;
const failedHealthValidation = validateChildReport(failedHealthChild, {
  profileName: "hard-a",
  profile: fixtureContract.profiles["hard-a"],
  contractSchemaVersion: fixtureContract.schemaVersion,
  expectedBuildIdentity: fixtureIdentity,
  uncappedEvidence: fixtureUncappedEvidence,
});
assert.equal(failedHealthValidation.valid, false, "strict release must reject nonzero scheduler health telemetry");
assert.match(failedHealthValidation.failures.join("\n"), /messageChannelPostFailureCount/);
for (const [label, overrides, expected] of [
  ["forged profile", {configuration: {
    ...childReport().configuration,
    profileName: "hard-b",
  }}, /profileName/],
  ["forged schema", {schemaVersion: 16}, /schemaVersion/],
  ["forged gating", {analysis: {
    ...childReport().analysis,
    gating: false,
    releaseEvidence: false,
  }}, /gating|releaseEvidence/],
  ["forged build", {buildIdentity: {
    ...fixtureIdentity,
    compatibilitySha256: "different-build",
  }}, /compatibilitySha256/],
]) {
  const validation = validateChildReport(childReport(overrides), {
    profileName: "hard-a",
    profile: fixtureContract.profiles["hard-a"],
    contractSchemaVersion: fixtureContract.schemaVersion,
    expectedBuildIdentity: fixtureIdentity,
    uncappedEvidence: fixtureUncappedEvidence,
  });
  assert.equal(validation.valid, false, `${label} fixture unexpectedly passed`);
  assert.match(validation.failures.join("\n"), expected);
}

const smokeChild = childReport({
  verdict: "non-gating",
  configuration: {
    ...childReport().configuration,
    gating: false,
    releaseEvidence: false,
    strictChecks: false,
  },
  analysis: {
    ...childReport().analysis,
    verdict: "non-gating",
    mode: "smoke-non-gating",
    gating: false,
    releaseEvidence: false,
  },
});
assert.equal(validateChildReport(smokeChild, {
  profileName: "hard-a",
  profile: fixtureContract.profiles["hard-a"],
  contractSchemaVersion: fixtureContract.schemaVersion,
  smoke: true,
  expectedBuildIdentity: fixtureIdentity,
}).valid, true);
assert.equal(validateChildReport(childReport(), {
  profileName: "hard-a",
  profile: fixtureContract.profiles["hard-a"],
  contractSchemaVersion: fixtureContract.schemaVersion,
  smoke: true,
  expectedBuildIdentity: fixtureIdentity,
}).valid, false, "strict child cannot become smoke evidence");

const configuration = JSON.parse(execFileSync(
  process.execPath,
  [script, "--print-config"],
  {encoding: "utf8", maxBuffer: 4 * 1024 * 1024},
));
assert.equal(configuration.buildIdentity.coherent, true);
assert.deepEqual(
  configuration.profiles.map((profile) => profile.name),
  ["steady-6-4", "traversal-6-4", "soak-sp-6-4"],
);
assert.deepEqual(configuration.requiredMemoryProfiles, ["soak-sp-6-4"]);
assert.equal(configuration.matrix, false);
assert.deepEqual(configuration.matrixProfiles, [
  "steady-6-4",
  "steady-8-4",
  "steady-12-4",
  "traversal-6-4",
  "traversal-8-4",
  "traversal-12-4",
]);
assert.deepEqual(configuration.releasePlan.mandatoryProfiles, [
  "steady-6-4",
  "traversal-6-4",
  "soak-sp-6-4",
]);
assert.deepEqual(configuration.unsupportedProfiles.map((profile) => profile.profile), ["soak-mp-6-4"]);
assert.equal(configuration.unsupportedProfiles[0].releaseEvidence, false);
assert.equal(configuration.releaseEvidence, false);

const matrixConfiguration = JSON.parse(execFileSync(
  process.execPath,
  [script, "--matrix", "--print-config"],
  {encoding: "utf8", maxBuffer: 4 * 1024 * 1024},
));
assert.equal(matrixConfiguration.matrix, true);
assert.deepEqual(
  matrixConfiguration.profiles.map((profile) => profile.name),
  [
    "steady-6-4",
    "traversal-6-4",
    "soak-sp-6-4",
    "steady-8-4",
    "steady-12-4",
    "traversal-8-4",
    "traversal-12-4",
  ],
);
assert.deepEqual(
  matrixConfiguration.profiles
    .filter((profile) => profile.name.endsWith("-8-4") || profile.name.endsWith("-12-4"))
    .map((profile) => profile.name),
  ["steady-8-4", "steady-12-4", "traversal-8-4", "traversal-12-4"],
);

assert.throws(
  () => execFileSync(
    process.execPath,
    [script, "--profiles", "steady-6-4", "--print-config"],
    {encoding: "utf8", maxBuffer: 4 * 1024 * 1024, stdio: ["ignore", "ignore", "ignore"]},
  ),
  (error) => error?.status === 1,
  "strict release must reject a profile list that omits hard targets or memory evidence",
);
assert.throws(
  () => execFileSync(
    process.execPath,
    [script, "--profiles", "steady-6-4,traversal-6-4,soak-sp-6-4,soak-mp-6-4", "--print-config"],
    {encoding: "utf8", maxBuffer: 4 * 1024 * 1024, stdio: ["ignore", "ignore", "ignore"]},
  ),
  (error) => error?.status === 1,
  "strict release must reject explicitly selected unsupported soak-mp evidence",
);
assert.throws(
  () => execFileSync(
    process.execPath,
    [script, "--url", "https://example.invalid/Gaius.html", "--print-config"],
    {encoding: "utf8", maxBuffer: 4 * 1024 * 1024, stdio: ["ignore", "ignore", "ignore"]},
  ),
  (error) => error?.status === 1,
  "strict release must not accept an unverified external build URL",
);
const smokeConfiguration = JSON.parse(execFileSync(
  process.execPath,
  [script, "--smoke", "--profiles", "steady-6-4", "--print-config"],
  {encoding: "utf8", maxBuffer: 4 * 1024 * 1024},
));
assert.equal(smokeConfiguration.mode, "smoke-suite");
assert.equal(smokeConfiguration.releaseEvidence, false);
assert.equal(smokeConfiguration.gating, false);
assert.equal(smokeConfiguration.profiles[0].releaseEvidence, false);

console.log("Chrome performance release-suite smoke passed");
