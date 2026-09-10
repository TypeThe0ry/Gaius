#!/usr/bin/env node

import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {mkdir, mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import {createHash} from "node:crypto";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {fileURLToPath} from "node:url";
import {
  createReleasePlan,
  validateManifestIdentity,
  validateContractShape,
  validateChildReport,
} from "./chrome-performance-release-suite.mjs";
import {
  evaluateUncappedFramePacing,
  mergeFramePacingTelemetrySources,
  mergeMonotonicSamples,
  normalizeFramePacingEvidenceSnapshot,
  normalizeFramePacingSettlementEvidence,
  summarizeAcceptanceEvidence,
} from "./performance-metrics.mjs";
import {
  acceptanceFixtureIdentity,
  acceptanceMeasurementEpochId,
  acceptanceFixtureProfile,
  legacyReleaseManifestFixtureIdentity,
  makeAcceptanceFramePacingSettlement,
  makeManifestIdentityFixture,
  makeAcceptanceFixtureReport,
  releaseManifestFixtureIdentity,
} from "./chrome-performance-release-fixtures.mjs";

const script = fileURLToPath(new URL(
  "./chrome-performance-release-suite.mjs",
  import.meta.url,
));
const benchmarkScript = fileURLToPath(new URL(
  "./chrome-chunk-benchmark.mjs",
  import.meta.url,
));
const source = await readFile(script, "utf8");
const benchmarkSource = await readFile(benchmarkScript, "utf8");
const fixtureRoot = await mkdtemp(join(tmpdir(), "gaius-release-suite-smoke-"));
const fixtureProfileBytes = new Map();
for (const profileId of ["26.2", "1.21.11"]) {
  const profilePath = fileURLToPath(new URL(`../versions/${profileId}.json`, import.meta.url));
  const profileBytes = await readFile(profilePath);
  fixtureProfileBytes.set(profileId, createHash("sha256").update(profileBytes).digest("hex"));
}
const suiteManifestIdentities = new Map([
  ["26.2", {
    ...releaseManifestFixtureIdentity,
    profileSha256: fixtureProfileBytes.get("26.2"),
  }],
  ["1.21.11", {
    ...legacyReleaseManifestFixtureIdentity,
    profileSha256: fixtureProfileBytes.get("1.21.11"),
  }],
]);
for (const identity of suiteManifestIdentities.values()) {
  const fixtureDist = join(fixtureRoot, identity.profileId);
  await mkdir(fixtureDist, {recursive: true});
  await writeFile(
    join(fixtureDist, "Gaius.manifest.json"),
    `${JSON.stringify(makeManifestIdentityFixture(identity), null, 2)}\n`,
  );
}
const suiteExecOptions = (identityOrExtra = suiteManifestIdentities.get("26.2"), extra = {}) => {
  const usesIdentity = identityOrExtra?.profileId != null;
  const identity = usesIdentity
    ? identityOrExtra
    : suiteManifestIdentities.get("26.2");
  const options = usesIdentity ? extra : identityOrExtra;
  return {
    encoding: "utf8",
    maxBuffer: 4 * 1024 * 1024,
    env: {
      ...process.env,
      GAIUS_VERSION_PROFILE_PATH: identity.profilePath,
      GAIUS_BUILD_ROOT: `port/target/${identity.profileId}`,
      GAIUS_DIST_DIRECTORY: join(fixtureRoot, identity.profileId),
      GAIUS_OVERLAY_DIRECTORY: `port/work/overlays/${identity.profileId}`,
    },
    ...options,
  };
};
for (const required of [
  "createReleasePlan",
  "validateManifestIdentity",
  "readBuildIdentity",
  "validateChildReport",
  "summarizeAcceptanceEvidence",
  "hardTargetProfiles",
  "requiredMemoryProfiles",
  "unsupportedStabilityProfiles",
  "configuration.profileName",
  "configuration.contractSchemaVersion",
  "analysis.releaseEvidence",
  "naturalWorkerEvidence",
  "harnessOverrideEvidence",
  "natural-observation",
  "harness-pin-diagnostic",
  "options preference must be exactly",
  "analysis.performanceEvidence",
  "worldgenTelemetryMode",
  "runtimeInvariantContract",
  "uncappedYieldCount",
  "visibleYieldCount",
  "hiddenYieldCount",
  "presentToRafCount",
  "messageChannelYieldCount",
  "fairYieldCount",
  "schedulerYieldCount",
  "timerYieldCount",
  "yieldRequestCount",
  "yieldCompletionCount",
  "pendingYieldCount",
  "maxPendingYieldCount",
  "duplicateYieldCallbackCount",
  "messageChannelCreateFailureCount",
  "messageChannelPostFailureCount",
  "messageChannelRebuildCount",
  "cancelledMessageTaskCount",
  "watchdogYieldCount",
  "minimumMessageChannelYieldCount",
  "minimumSamples",
  "maximumFairYieldCount",
  "maximumSchedulerYieldCount",
  "maximumTimerYieldCount",
  "maximumWatchdogYieldCount",
  "REQUIRED_UNCAPPED_TELEMETRY_FIELDS",
  "hasFiniteNumber",
  "isNonnegativeSafeInteger",
  "counterDecreaseCount",
  "sampleCompletenessViolationCount",
  "allowedResetSentinelSampleCount",
  "snapshotInvariantFailureCount",
  "framePacingSettlement",
  "mergeFramePacingTelemetrySources",
  "normalizeFramePacingEvidenceSnapshot",
  "normalizeFramePacingSettlementEvidence",
  "sourceOverlapMismatchCount",
  "measurementEpochId",
  "cleanupTelemetry",
  "cleanupCapturedAt",
  "requireEpochClosure: true",
  "epochMismatchCount",
  "controllerTimingViolationCount",
  "cleanupTelemetry.framePacingClosure",
  "cleanupClosureFailureCount",
  "settlementCounterDecreaseCount",
  "settlementFallbackOrHealthFailureCount",
  "recomputeChildUncappedEvidence",
  "report?.samples",
  "report?.telemetry",
  "evaluationLatencyMillis",
  "raw sample timestamps are missing or unsafe",
  "raw sample timestamps are not strictly increasing",
  "raw samples were dropped or merged",
  "derived frame-pacing summary does not match raw samples",
  "buildIdentity.coherent",
  "failureEvidence",
  "manifestSha256",
  "compatibilitySha256",
  "manifest.worldVersion",
  "manifest.worldgenTelemetryMode",
  "manifest.storage",
  "manifest.buildIdentity.profile.worldgenTelemetryMode",
  "manifest.buildIdentity.profile.storage",
  "exactIdentityValue",
  "canonicalJson",
  "child report verdict must be",
  '"smoke-pass"',
  'releaseEvidence: false',
  "uncappedMatrixProfiles",
  "acceptance evidence is unverified",
  "--matrix",
]) {
  assert.ok(source.includes(required), `release suite is missing ${required}`);
}
for (const required of [
  "supportedReleaseDistanceCapabilities",
  "headed-chrome-worker-distance-6-4",
  "releaseDistanceCapability",
  "active version profile is not a supported headed Chrome release capability",
  "--headless is disabled for strict headed Chrome release evidence",
  "natural-observation",
  "captureCleanupFramePacingClosure",
  "controllerRequestedAt",
  "controllerReceivedAt",
  "controllerElapsedMillis",
  "cutoffYieldRequestCount",
  "measurementEpochId",
]) {
  assert.ok(benchmarkSource.includes(required), `benchmark is missing ${required}`);
}
assert.match(source, /--attach-port is disabled/,
  "release suite must fail closed for attached Chrome");
assert.doesNotMatch(source, /forwarded.*attach-port/,
  "release suite must not forward attached input to child benchmarks");
assert.match(source, /resolveRepositoryPortPath/,
  "release suite paths must not depend on child cwd");
assert.match(source, /manifest\.buildIdentity\.profile\.worldVersion/,
  "release suite must verify nested manifest world version");
assert.match(source, /profile-scoped dist basename/,
  "release suite must reject a shared dist root for isolated profiles");
assert.match(source, /--pin-worker-distance is diagnostic-only/,
  "release suite must keep Worker distance pin diagnostic-only");

const manifestFixture = makeManifestIdentityFixture(releaseManifestFixtureIdentity);
assert.deepEqual(
  validateManifestIdentity(manifestFixture, releaseManifestFixtureIdentity),
  [],
  "a complete manifest identity fixture must pass the exact profile/telemetry/storage gate",
);
for (const [label, mutate, expectedFailure] of [
  ["top-level world version", (manifest) => { manifest.worldVersion += 1; }, /manifest\.worldVersion/],
  ["top-level telemetry mode", (manifest) => { manifest.worldgenTelemetryMode = "checkpoint-only"; }, /manifest\.worldgenTelemetryMode/],
  ["top-level storage", (manifest) => { manifest.storage.prefix += "forged"; }, /manifest\.storage/],
  ["nested telemetry mode", (manifest) => {
    manifest.buildIdentity.profile.worldgenTelemetryMode = "checkpoint-only";
  }, /manifest\.buildIdentity\.profile\.worldgenTelemetryMode/],
  ["nested storage", (manifest) => {
    manifest.buildIdentity.profile.storage.opfsDirectory += "-forged";
  }, /manifest\.buildIdentity\.profile\.storage/],
  ["world version type coercion", (manifest) => { manifest.worldVersion = "4903"; }, /manifest\.worldVersion/],
]) {
  const forgedManifest = makeManifestIdentityFixture(releaseManifestFixtureIdentity);
  mutate(forgedManifest);
  const failures = validateManifestIdentity(forgedManifest, releaseManifestFixtureIdentity);
  assert.notDeepEqual(failures, [], `${label} unexpectedly passed the identity gate`);
  assert.match(failures.join("\n"), expectedFailure, `${label} failure was not reported`);
}
assert.deepEqual(
  validateManifestIdentity(
    makeManifestIdentityFixture(legacyReleaseManifestFixtureIdentity),
    legacyReleaseManifestFixtureIdentity,
  ),
  [],
  "the legacy profile fixture must retain the same exact identity gate",
);

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
  schemaVersion: 14,
  measurement: {soakMs: 1_800_000},
  environment: {
    uncappedEvidence: {
      requiredSwapInterval: 0,
      minimumSamples: 2,
      minimumUncappedYieldCount: 1,
      minimumMessageChannelYieldCount: 1,
      maximumFairYieldCount: 0,
      maximumSchedulerYieldCount: 0,
      maximumTimerYieldCount: 0,
      maximumVsyncYieldCount: 0,
      maximumPresentToRafCount: 0,
      maximumMessageChannelCreateFailureCount: 0,
      maximumMessageChannelPostFailureCount: 0,
      maximumMessageChannelRebuildCount: 0,
      maximumCancelledMessageTaskCount: 0,
      maximumWatchdogYieldCount: 0,
      requireFramePacingSettlement: true,
      settlementSchemaVersion: 1,
      settlementPollIntervalMillis: 8,
      settlementPollIntervalMillisMin: 5,
      settlementPollIntervalMillisMax: 10,
      settlementTimeoutMillis: 200,
      settlementTimeoutMillisMin: 150,
      settlementTimeoutMillisMax: 250,
      requiredFields: [
        "swapInterval",
        "uncappedYieldCount",
        "vsyncYieldCount",
        "visibleYieldCount",
        "hiddenYieldCount",
        "presentToRafCount",
        "messageChannelYieldCount",
        "fairYieldCount",
        "schedulerYieldCount",
        "timerYieldCount",
        "yieldRequestCount",
        "yieldCompletionCount",
        "pendingYieldCount",
        "maxPendingYieldCount",
        "duplicateYieldCallbackCount",
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
assert.throws(
  () => validateContractShape({...fixtureContract, schemaVersion: 15}),
  /schemaVersion must be exactly 14/,
  "strict release contract must not drift from pacing evidence schema 14",
);
assert.throws(() => validateContractShape({
  ...fixtureContract,
  environment: {
    ...fixtureContract.environment,
    uncappedEvidence: {...fixtureContract.environment.uncappedEvidence, minimumSamples: 1},
  },
}), /unsafe release requirements/,
"strict release contract must require at least two complete pacing samples");
for (const [field, value] of [
  ["minimumUncappedYieldCount", 1.5],
  ["minimumMessageChannelYieldCount", 1.5],
  ["minimumUncappedYieldCount", "1"],
  ["minimumMessageChannelYieldCount", "1"],
]) {
  assert.throws(() => validateContractShape({
    ...fixtureContract,
    environment: {
      ...fixtureContract.environment,
      uncappedEvidence: {
        ...fixtureContract.environment.uncappedEvidence,
        [field]: value,
      },
    },
  }), /unsafe release requirements/,
  `strict release contract must reject malformed ${field}=${JSON.stringify(value)}`);
}
for (const [field, value] of [
  ["requireFramePacingSettlement", false],
  ["settlementSchemaVersion", 1.5],
  ["settlementPollIntervalMillis", 4],
  ["settlementPollIntervalMillisMin", 4],
  ["settlementPollIntervalMillisMax", 11],
  ["settlementTimeoutMillis", 100],
  ["settlementTimeoutMillisMin", 149],
  ["settlementTimeoutMillisMax", 251],
]) {
  assert.throws(() => validateContractShape({
    ...fixtureContract,
    environment: {
      ...fixtureContract.environment,
      uncappedEvidence: {
        ...fixtureContract.environment.uncappedEvidence,
        [field]: value,
      },
    },
  }), /settlement requirements are unsafe/,
  `strict release contract must reject malformed ${field}=${JSON.stringify(value)}`);
}
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
      uncappedEvidence: fixtureUncappedEvidence,
    }),
    ...overrides,
  };
}

function recomputeFixtureFramePacing(report) {
  const validSamples = mergeMonotonicSamples(report.samples.filter((sample) => !sample.error));
  const sampleSources = validSamples.map((sample, index) =>
    normalizeFramePacingEvidenceSnapshot(sample, {
      requiredFields: fixtureUncappedEvidence.requiredFields,
      label: `sample[${index}]`,
      requireDualSources: true,
    }));
  const finalSources = normalizeFramePacingEvidenceSnapshot(report.telemetry, {
    requiredFields: fixtureUncappedEvidence.requiredFields,
    label: "final",
    requireDualSources: true,
  });
  const settlementSources = normalizeFramePacingSettlementEvidence(
    report.telemetry.framePacingSettlement,
    {
      requiredFields: fixtureUncappedEvidence.requiredFields,
      label: "settlement",
      requireDualSources: true,
    },
  );
  const cleanupSources = normalizeFramePacingEvidenceSnapshot(report.cleanupTelemetry, {
    requiredFields: fixtureUncappedEvidence.requiredFields,
    label: "cleanup",
    requireDualSources: true,
  });
  report.analysis.performanceEvidence.framePacing = evaluateUncappedFramePacing({
    samples: sampleSources.map(({merged}) => merged),
    final: finalSources.merged,
    settlement: settlementSources.settlement,
    cleanup: cleanupSources.merged,
    measurementEpochId: report.configuration.measurementEpochId,
    requireEpochClosure: true,
    sourceOverlapMismatches: [
      ...sampleSources.flatMap(({overlapMismatches}) => overlapMismatches),
      ...sampleSources.flatMap(({sourceCompletenessFailures}) => sourceCompletenessFailures),
      ...finalSources.overlapMismatches,
      ...finalSources.sourceCompletenessFailures,
      ...settlementSources.overlapMismatches,
      ...settlementSources.sourceCompletenessFailures,
      ...cleanupSources.overlapMismatches,
      ...cleanupSources.sourceCompletenessFailures,
    ],
    timing: {
      lastSampleAt: validSamples.at(-1)?.at ?? null,
      finalCapturedAt: report.telemetry.capturedAt ?? null,
      settlementCapturedAt: report.telemetry.framePacingSettlement?.capturedAt ?? null,
      cleanupCapturedAt: report.cleanupTelemetry?.capturedAt ?? null,
    },
    requirements: fixtureUncappedEvidence,
  });
}

function setFixtureCleanupClosure(report, pacing, {
  capturedAt = 5,
  cutoffYieldRequestCount = pacing.yieldRequestCount,
} = {}) {
  const scalar = Object.fromEntries([
    "measurementId",
    "measurementEpochId",
    ...fixtureUncappedEvidence.requiredFields,
  ].map((field) => [field, pacing[field]]));
  const cleanup = {
    ...scalar,
    capturedAt,
    controllerRequestedAt: capturedAt,
    controllerReceivedAt: capturedAt + 1,
    evaluationLatencyMillis: 1,
    deadlineExceeded: false,
    cutoffYieldRequestCount,
    frame: {...scalar},
    runtimeInvariants: {framePacing: {...scalar}},
  };
  report.cleanupTelemetry = structuredClone(cleanup);
  report.cleanupTelemetry.framePacingClosure = structuredClone(cleanup);
  report.telemetry.cleanupFramePacing = structuredClone(cleanup);
}

function mutateDualPacingSnapshot(snapshot, changes) {
  Object.assign(snapshot, changes);
  if (snapshot.frame && typeof snapshot.frame === "object") Object.assign(snapshot.frame, changes);
  if (snapshot.runtimeInvariants?.framePacing) {
    Object.assign(snapshot.runtimeInvariants.framePacing, changes);
  }
}

function mutateAllCleanupPacingRepresentations(report, changes) {
  mutateDualPacingSnapshot(report.cleanupTelemetry, changes);
  mutateDualPacingSnapshot(report.cleanupTelemetry.framePacingClosure, changes);
  mutateDualPacingSnapshot(report.telemetry.cleanupFramePacing, changes);
}

function setFixtureSettlementControllerSamples(report, timings, {
  controllerStartedAt = 3,
} = {}) {
  const settlement = report.telemetry.framePacingSettlement;
  const samples = timings.map(({capturedAt, controllerRequestedAt, controllerReceivedAt}) => {
    const sample = structuredClone(settlement.final);
    Object.assign(sample, {
      capturedAt,
      controllerRequestedAt,
      controllerReceivedAt,
      evaluationLatencyMillis: controllerReceivedAt - controllerRequestedAt,
      deadlineExceeded: false,
    });
    return sample;
  });
  const final = samples.at(-1);
  settlement.samples = samples;
  settlement.final = structuredClone(final);
  settlement.capturedAt = final.capturedAt;
  settlement.controllerStartedAt = controllerStartedAt;
  settlement.controllerCompletedAt = final.controllerReceivedAt;
  settlement.controllerElapsedMillis = settlement.controllerCompletedAt - controllerStartedAt;
  setFixtureCleanupClosure(report, final, {
    capturedAt: Math.max(final.capturedAt, final.controllerReceivedAt) + 1,
    cutoffYieldRequestCount: final.yieldRequestCount,
  });
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
const summaryOnlyPacingChild = childReport();
delete summaryOnlyPacingChild.samples;
delete summaryOnlyPacingChild.telemetry;
const summaryOnlyPacingValidation = validateChildReport(summaryOnlyPacingChild, {
  profileName: "hard-a",
  profile: fixtureContract.profiles["hard-a"],
  contractSchemaVersion: fixtureContract.schemaVersion,
  expectedBuildIdentity: fixtureIdentity,
  uncappedEvidence: fixtureUncappedEvidence,
});
assert.equal(summaryOnlyPacingValidation.valid, false,
  "strict release must reject a summary-only frame-pacing pass claim");
assert.match(summaryOnlyPacingValidation.failures.join("\n"), /raw report\.samples|raw report\.telemetry/);

const rawBadDerivedGoodChild = childReport();
Object.assign(rawBadDerivedGoodChild.samples[1].frame, {
  visibleYieldCount: 7,
  hiddenYieldCount: 1,
  messageChannelYieldCount: 7,
  timerYieldCount: 1,
});
const rawBadDerivedGoodValidation = validateChildReport(rawBadDerivedGoodChild, {
  profileName: "hard-a",
  profile: fixtureContract.profiles["hard-a"],
  contractSchemaVersion: fixtureContract.schemaVersion,
  expectedBuildIdentity: fixtureIdentity,
  uncappedEvidence: fixtureUncappedEvidence,
});
assert.equal(rawBadDerivedGoodValidation.valid, false,
  "strict release must reject bad raw pacing hidden behind a forged passing summary");
assert.match(rawBadDerivedGoodValidation.failures.join("\n"),
  /raw frame-pacing evidence recomputed as fail|derived frame-pacing summary/);

for (const [label, mutateRaw, expected] of [
  ["errored raw sample", (report) => { report.samples[0].error = "forged sample error"; },
    /errored raw samples/],
  ["stalled raw sample", (report) => { report.samples[0].evaluationLatencyMillis = 500; },
    /raw sample latency/],
  ["non-object raw sample", (report) => { report.samples[0] = null; },
    /non-object raw samples/],
  ["unsafe raw timestamp", (report) => {
    report.samples[0].at = null;
    report.samples[0].frame.hiddenYieldCount = 1;
    report.samples[0].frame.timerYieldCount = 1;
  }, /raw sample timestamps/],
  ["duplicate raw timestamp", (report) => { report.samples[1].at = report.samples[0].at; },
    /dropped or merged/],
]) {
  const candidate = childReport();
  mutateRaw(candidate);
  const validation = validateChildReport(candidate, {
    profileName: "hard-a",
    profile: fixtureContract.profiles["hard-a"],
    contractSchemaVersion: fixtureContract.schemaVersion,
    expectedBuildIdentity: fixtureIdentity,
    uncappedEvidence: fixtureUncappedEvidence,
  });
  assert.equal(validation.valid, false, `strict release must reject ${label}`);
  assert.match(validation.failures.join("\n"), expected, `${label}: raw evidence failure`);
}

const validateStrictPacingFixture = (report) => validateChildReport(report, {
  profileName: "hard-a",
  profile: fixtureContract.profiles["hard-a"],
  contractSchemaVersion: fixtureContract.schemaVersion,
  expectedBuildIdentity: fixtureIdentity,
  uncappedEvidence: fixtureUncappedEvidence,
});

const pendingSettlementChild = childReport();
const pendingInitial = {
  ...pendingSettlementChild.telemetry.frame,
  uncappedYieldCount: 9,
  visibleYieldCount: 9,
  yieldRequestCount: 9,
  pendingYieldCount: 1,
};
const pendingCompleted = {
  ...pendingInitial,
  messageChannelYieldCount: 9,
  yieldCompletionCount: 9,
  pendingYieldCount: 0,
};
pendingSettlementChild.telemetry.frame = {...pendingInitial};
pendingSettlementChild.telemetry.runtimeInvariants.framePacing = {...pendingInitial};
pendingSettlementChild.telemetry.framePacingSettlement = makeAcceptanceFramePacingSettlement(
  pendingInitial,
  {initialCapturedAt: 3, capturedAt: 4, final: pendingCompleted},
);
setFixtureCleanupClosure(pendingSettlementChild, pendingCompleted);
recomputeFixtureFramePacing(pendingSettlementChild);
const pendingSettlementValidation = validateStrictPacingFixture(pendingSettlementChild);
assert.equal(pendingSettlementValidation.valid, true,
  `strict release must accept one proved in-flight completion: ${pendingSettlementValidation.failures.join("; ")}`);

for (const [label, mutateRaw, expected] of [
  ["sample source overlap mismatch", (report) => {
    report.samples[0].runtimeInvariants.framePacing.timerYieldCount = 1;
  }, /sources disagree|raw frame-pacing evidence recomputed as fail/],
  ["final source overlap mismatch", (report) => {
    report.telemetry.runtimeInvariants.framePacing.messageChannelYieldCount = 7;
  }, /sources disagree|raw frame-pacing evidence recomputed as fail/],
  ["final timestamp not after sample", (report) => {
    report.telemetry.capturedAt = report.samples.at(-1).at;
    report.telemetry.framePacingSettlement.initialCapturedAt = report.telemetry.capturedAt;
  }, /final telemetry\.capturedAt|raw frame-pacing evidence recomputed as fail/],
  ["settlement timestamp not after final", (report) => {
    report.telemetry.framePacingSettlement.capturedAt = report.telemetry.capturedAt;
    report.telemetry.framePacingSettlement.samples[0].capturedAt = report.telemetry.capturedAt;
  }, /settlement\.capturedAt|raw frame-pacing evidence recomputed as fail/],
  ["raw max pending peak forged to zero", (report) => {
    report.samples[0].frame.maxPendingYieldCount = 0;
    report.samples[0].runtimeInvariants.framePacing.maxPendingYieldCount = 0;
  }, /raw frame-pacing evidence recomputed as fail|derived frame-pacing summary/],
  ["settlement cumulative counter decrease", (report) => {
    const settlement = report.telemetry.framePacingSettlement;
    for (const field of [
      "uncappedYieldCount", "visibleYieldCount", "messageChannelYieldCount",
      "yieldRequestCount", "yieldCompletionCount",
    ]) {
      mutateDualPacingSnapshot(settlement.samples[0], {[field]: 7});
      mutateDualPacingSnapshot(settlement.final, {[field]: 7});
    }
  }, /raw frame-pacing evidence recomputed as fail|derived frame-pacing summary/],
  ["dead MessageChannel watchdog fallback", (report) => {
    const settlement = report.telemetry.framePacingSettlement;
    const watchdogFinal = {
      ...pendingInitial,
      timerYieldCount: 1,
      yieldCompletionCount: 9,
      pendingYieldCount: 0,
      watchdogYieldCount: 1,
    };
    Object.assign(settlement, {
      messageChannelCompletionDelta: 0,
      yieldCompletionDelta: 1,
      settled: false,
      timedOut: true,
      samples: [{capturedAt: 4, ...watchdogFinal}],
      final: watchdogFinal,
    });
  }, /raw frame-pacing evidence recomputed as fail|derived frame-pacing summary/],
]) {
  const candidate = label === "dead MessageChannel watchdog fallback"
    ? structuredClone(pendingSettlementChild) : childReport();
  mutateRaw(candidate);
  const validation = validateStrictPacingFixture(candidate);
  assert.equal(validation.valid, false, `strict release must reject ${label}`);
  assert.match(validation.failures.join("\n"), expected, `${label}: strict raw pacing failure`);
}

const initialZeroNewPendingChild = childReport();
const initialZeroSnapshot = {...initialZeroNewPendingChild.telemetry.frame};
const newPendingSnapshot = {
  ...initialZeroSnapshot,
  uncappedYieldCount: 9,
  visibleYieldCount: 9,
  yieldRequestCount: 9,
  messageChannelYieldCount: 8,
  yieldCompletionCount: 8,
  pendingYieldCount: 1,
  maxPendingYieldCount: 1,
};
initialZeroNewPendingChild.telemetry.framePacingSettlement =
  makeAcceptanceFramePacingSettlement(initialZeroSnapshot, {
    initialCapturedAt: 3,
    capturedAt: 4,
    final: newPendingSnapshot,
  });
const pendingClosedSnapshot = {
  ...newPendingSnapshot,
  messageChannelYieldCount: 9,
  yieldCompletionCount: 9,
  pendingYieldCount: 0,
};
setFixtureCleanupClosure(initialZeroNewPendingChild, pendingClosedSnapshot, {
  cutoffYieldRequestCount: 9,
});
recomputeFixtureFramePacing(initialZeroNewPendingChild);
const initialZeroNewPendingValidation = validateStrictPacingFixture(initialZeroNewPendingChild);
assert.equal(initialZeroNewPendingValidation.valid, true,
  `cleanup must close a continuation requested during settlement: ${initialZeroNewPendingValidation.failures.join("; ")}`);

const cleanupPendingOneChild = childReport();
const cleanupPendingOneSnapshot = {
  ...cleanupPendingOneChild.telemetry.framePacingSettlement.final,
  uncappedYieldCount: 9,
  visibleYieldCount: 9,
  yieldRequestCount: 9,
  messageChannelYieldCount: 8,
  yieldCompletionCount: 8,
  pendingYieldCount: 1,
  maxPendingYieldCount: 1,
};
setFixtureCleanupClosure(cleanupPendingOneChild, cleanupPendingOneSnapshot, {
  cutoffYieldRequestCount: 8,
});
recomputeFixtureFramePacing(cleanupPendingOneChild);
const cleanupPendingOneValidation = validateStrictPacingFixture(cleanupPendingOneChild);
assert.equal(cleanupPendingOneValidation.valid, true,
  `cleanup may retain one post-cutoff continuation: ${cleanupPendingOneValidation.failures.join("; ")}`);

const epochAndCleanupAdversarialFixtures = [
  ["raw sample epoch mismatch", (report) => {
    mutateDualPacingSnapshot(report.samples[0], {
      measurementId: "wrong-epoch",
      measurementEpochId: "wrong-epoch",
    });
  }],
  ["raw sample dual-source epoch disagreement", (report) => {
    report.samples[0].runtimeInvariants.framePacing.measurementEpochId = "runtime-wrong-epoch";
  }],
  ["final epoch mismatch", (report) => {
    report.telemetry.measurementId = "wrong-final-epoch";
    report.telemetry.measurementEpochId = "wrong-final-epoch";
    mutateDualPacingSnapshot(report.telemetry, {
      measurementId: "wrong-final-epoch",
      measurementEpochId: "wrong-final-epoch",
    });
  }],
  ["settlement metadata epoch mismatch", (report) => {
    report.telemetry.framePacingSettlement.measurementEpochId = "wrong-settlement-epoch";
  }],
  ["settlement sample epoch mismatch", (report) => {
    mutateDualPacingSnapshot(report.telemetry.framePacingSettlement.samples[0], {
      measurementId: "wrong-settlement-sample",
      measurementEpochId: "wrong-settlement-sample",
    });
  }],
  ["settlement final epoch mismatch", (report) => {
    mutateDualPacingSnapshot(report.telemetry.framePacingSettlement.final, {
      measurementId: "wrong-settlement-final",
      measurementEpochId: "wrong-settlement-final",
    });
  }],
  ["cleanup epoch mismatch", (report) => {
    mutateDualPacingSnapshot(report.cleanupTelemetry, {
      measurementId: "wrong-cleanup-epoch",
      measurementEpochId: "wrong-cleanup-epoch",
    });
  }],
  ["late final controller response", (report) => {
    Object.assign(report.telemetry, {
      controllerReceivedAt: 500,
      evaluationLatencyMillis: 498,
      deadlineExceeded: true,
    });
  }],
  ["late settlement scalar response", (report) => {
    const sample = report.telemetry.framePacingSettlement.samples[0];
    Object.assign(sample, {
      controllerReceivedAt: 500,
      evaluationLatencyMillis: 497,
      deadlineExceeded: true,
    });
    report.telemetry.framePacingSettlement.final = structuredClone(sample);
  }],
  ["late settlement controller completion", (report) => {
    Object.assign(report.telemetry.framePacingSettlement, {
      controllerCompletedAt: 500,
      controllerElapsedMillis: 497,
      deadlineExceeded: true,
    });
  }],
  ["settlement controller ranges overlap", (report) => {
    setFixtureSettlementControllerSamples(report, [
      {capturedAt: 4, controllerRequestedAt: 3, controllerReceivedAt: 4},
      {capturedAt: 5, controllerRequestedAt: 3, controllerReceivedAt: 5},
    ]);
  }],
  ["settlement controller request moves backwards", (report) => {
    setFixtureSettlementControllerSamples(report, [
      {capturedAt: 4, controllerRequestedAt: 4, controllerReceivedAt: 4},
      {capturedAt: 5, controllerRequestedAt: 3, controllerReceivedAt: 5},
    ]);
  }],
  ["settlement changes to swap interval one", (report) => {
    for (const sample of report.telemetry.framePacingSettlement.samples) {
      mutateDualPacingSnapshot(sample, {swapInterval: 1});
    }
    mutateDualPacingSnapshot(report.telemetry.framePacingSettlement.final, {swapInterval: 1});
  }],
  ["cleanup changes to swap interval one", (report) => {
    mutateAllCleanupPacingRepresentations(report, {swapInterval: 1});
  }],
  ["cleanup timestamp before settlement", (report) => {
    report.cleanupTelemetry.capturedAt = report.telemetry.framePacingSettlement.capturedAt;
  }],
  ["cleanup counter decrease", (report) => {
    mutateDualPacingSnapshot(report.cleanupTelemetry, {
      uncappedYieldCount: 7,
      visibleYieldCount: 7,
      messageChannelYieldCount: 7,
      yieldRequestCount: 7,
      yieldCompletionCount: 7,
    });
  }],
  ["chained pending continuation reaches watchdog", (report) => {
    mutateDualPacingSnapshot(report.cleanupTelemetry, {
      uncappedYieldCount: 9,
      visibleYieldCount: 9,
      yieldRequestCount: 9,
      yieldCompletionCount: 9,
      messageChannelYieldCount: 8,
      timerYieldCount: 1,
      pendingYieldCount: 0,
      maxPendingYieldCount: 1,
      watchdogYieldCount: 1,
    });
  }],
  ["initial zero then new pending lacks cleanup closure", (report) => {
    const initial = {...report.telemetry.frame};
    const pending = {
      ...initial,
      uncappedYieldCount: 9,
      visibleYieldCount: 9,
      yieldRequestCount: 9,
      pendingYieldCount: 1,
    };
    report.telemetry.framePacingSettlement = makeAcceptanceFramePacingSettlement(initial, {
      initialCapturedAt: 3,
      capturedAt: 4,
      final: pending,
    });
  }],
  ["cleanup pending peak forged to zero", (report) => {
    mutateDualPacingSnapshot(report.cleanupTelemetry, {
      uncappedYieldCount: 9,
      visibleYieldCount: 9,
      yieldRequestCount: 9,
      pendingYieldCount: 1,
      maxPendingYieldCount: 0,
    });
  }],
  ["cleanup timer path", (report) => {
    mutateDualPacingSnapshot(report.cleanupTelemetry, {
      uncappedYieldCount: 9,
      visibleYieldCount: 8,
      hiddenYieldCount: 1,
      yieldRequestCount: 9,
      yieldCompletionCount: 9,
      messageChannelYieldCount: 8,
      timerYieldCount: 1,
      maxPendingYieldCount: 1,
    });
  }],
];
assert.equal(epochAndCleanupAdversarialFixtures.length, 20,
  "schema-14 pacing evidence must retain exactly twenty raw adversarial fixtures here");
for (const [label, mutateRaw] of epochAndCleanupAdversarialFixtures) {
  const candidate = childReport();
  mutateRaw(candidate);
  const validation = validateStrictPacingFixture(candidate);
  assert.equal(validation.valid, false, `strict release must reject ${label}`);
  assert.match(validation.failures.join("\n"),
    /raw frame-pacing evidence|derived frame-pacing summary|epoch|cleanup|controller|sources disagree/,
    `${label}: strict epoch/cleanup failure`);
  if (label.startsWith("settlement controller")
      || label.endsWith("swap interval one")) {
    assert.match(validation.failures.join("\n"), /raw frame-pacing evidence recomputed as fail/,
      `${label}: strict parent must independently recompute the raw failure`);
  }
}

const forgedCleanupSummaryChild = childReport();
forgedCleanupSummaryChild.analysis.performanceEvidence.framePacing.cleanup.cutoffConsistent = false;
const forgedCleanupSummaryValidation = validateStrictPacingFixture(forgedCleanupSummaryChild);
assert.equal(forgedCleanupSummaryValidation.valid, false,
  "strict release must reject a forged cleanup summary that disagrees with raw cleanupTelemetry");
assert.match(forgedCleanupSummaryValidation.failures.join("\n"), /derived frame-pacing summary/);

const forgedNestedCleanupClosureChild = childReport();
mutateDualPacingSnapshot(
  forgedNestedCleanupClosureChild.cleanupTelemetry.framePacingClosure,
  {swapInterval: 1},
);
const forgedNestedCleanupClosureValidation = validateStrictPacingFixture(
  forgedNestedCleanupClosureChild,
);
assert.equal(forgedNestedCleanupClosureValidation.valid, false,
  "strict release must reject a forged nested cleanupTelemetry.framePacingClosure");
assert.match(forgedNestedCleanupClosureValidation.failures.join("\n"),
  /framePacingClosure|sources disagree/);

const forgedWorkerDistanceChild = childReport();
forgedWorkerDistanceChild.analysis.environment.distanceContract.optionsPreference = "8:6";
const forgedWorkerDistanceValidation = validateChildReport(forgedWorkerDistanceChild, {
  profileName: "hard-a",
  profile: fixtureContract.profiles["hard-a"],
  contractSchemaVersion: fixtureContract.schemaVersion,
  expectedBuildIdentity: fixtureIdentity,
  uncappedEvidence: fixtureUncappedEvidence,
});
assert.equal(forgedWorkerDistanceValidation.valid, false,
  "strict release must reject a non-6/4 natural options preference");
assert.match(forgedWorkerDistanceValidation.failures.join("\n"), /options preference/);
const pinnedWorkerDistanceChild = childReport();
pinnedWorkerDistanceChild.analysis.environment.distanceContract.mode = "harness-pin-diagnostic";
pinnedWorkerDistanceChild.analysis.harnessOverrideEvidence.enabled = true;
const pinnedWorkerDistanceValidation = validateChildReport(pinnedWorkerDistanceChild, {
  profileName: "hard-a",
  profile: fixtureContract.profiles["hard-a"],
  contractSchemaVersion: fixtureContract.schemaVersion,
  expectedBuildIdentity: fixtureIdentity,
  uncappedEvidence: fixtureUncappedEvidence,
});
assert.equal(pinnedWorkerDistanceValidation.valid, false,
  "strict release must reject harness-pinned Worker distance evidence");
assert.match(pinnedWorkerDistanceValidation.failures.join("\n"), /harnessOverrideEvidence|natural-observation/);
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

const leadingResetSentinelChild = childReport();
const leadingResetPacing = Object.fromEntries([
  "measurementId",
  "measurementEpochId",
  ...fixtureUncappedEvidence.requiredFields,
].map((field) => [field, field === "measurementId" || field === "measurementEpochId"
  ? acceptanceMeasurementEpochId : (field === "swapInterval" ? null : 0)]));
leadingResetSentinelChild.samples.unshift({
  at: 0,
  evaluationLatencyMillis: 1,
  measurementId: acceptanceMeasurementEpochId,
  measurementEpochId: acceptanceMeasurementEpochId,
  frame: {...leadingResetPacing},
  runtimeInvariants: {framePacing: {...leadingResetPacing}},
});
recomputeFixtureFramePacing(leadingResetSentinelChild);
const leadingResetSentinelValidation = validateChildReport(leadingResetSentinelChild, {
  profileName: "hard-a",
  profile: fixtureContract.profiles["hard-a"],
  contractSchemaVersion: fixtureContract.schemaVersion,
  expectedBuildIdentity: fixtureIdentity,
  uncappedEvidence: fixtureUncappedEvidence,
});
assert.equal(leadingResetSentinelValidation.valid, true,
  `strict release must allow a proved leading all-zero reset sentinel: ${leadingResetSentinelValidation.failures.join("; ")}`);

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

const oldCadenceChild = childReport();
oldCadenceChild.analysis.performanceEvidence.framePacing.observed.fairYieldCountMax = 1;
oldCadenceChild.analysis.performanceEvidence.framePacing.observed.schedulerYieldCountMax = 1;
const oldCadenceValidation = validateChildReport(oldCadenceChild, {
  profileName: "hard-a",
  profile: fixtureContract.profiles["hard-a"],
  contractSchemaVersion: fixtureContract.schemaVersion,
  expectedBuildIdentity: fixtureIdentity,
  uncappedEvidence: fixtureUncappedEvidence,
});
assert.equal(oldCadenceValidation.valid, false,
  "strict release must reject the retired fixed scheduler.yield cadence");
assert.match(oldCadenceValidation.failures.join("\n"), /fairYieldCount|schedulerYieldCount/);

for (const [label, mutate, expected] of [
  ["one complete sample", (frame) => {
    frame.measuredSampleCount = 1;
    frame.observed.completeSampleCount = 1;
  }, /complete frame-pacing samples/],
  ["100-to-1 completion mismatch", (frame) => {
    Object.assign(frame.final, {
      uncappedYieldCount: 100,
      visibleYieldCount: 100,
      yieldRequestCount: 100,
      yieldCompletionCount: 1,
      messageChannelYieldCount: 1,
      pendingYieldCount: 0,
    });
  }, /continuation accounting/],
  ["negative counter", (frame) => {
    frame.final.messageChannelYieldCount = -1;
  }, /counters are unsafe/],
  ["two pending continuations", (frame) => {
    Object.assign(frame.final, {
      uncappedYieldCount: 100,
      visibleYieldCount: 100,
      yieldRequestCount: 100,
      yieldCompletionCount: 98,
      messageChannelYieldCount: 98,
      pendingYieldCount: 2,
      maxPendingYieldCount: 2,
    });
  }, /continuation accounting/],
  ["counter reset", (frame) => {
    frame.observed.counterDecreaseCount = 1;
  }, /counterDecreaseCount/],
  ["mid-stream incomplete sample", (frame) => {
    frame.incompleteSampleCount = 1;
    frame.observed.incompleteSampleCount = 1;
    frame.observed.allowedResetSentinelSampleCount = 0;
    frame.observed.sampleCompletenessViolationCount = 1;
  }, /sampleCompletenessViolationCount/],
  ["missing historical pending peak", (frame) => {
    frame.final.maxPendingYieldCount = 0;
  }, /continuation accounting/],
  ["hidden timer path", (frame) => {
    Object.assign(frame.final, {
      hiddenYieldCount: 1,
      visibleYieldCount: 7,
      timerYieldCount: 1,
      messageChannelYieldCount: 7,
    });
  }, /continuation accounting/],
]) {
  const candidate = childReport();
  mutate(candidate.analysis.performanceEvidence.framePacing);
  const validation = validateChildReport(candidate, {
    profileName: "hard-a",
    profile: fixtureContract.profiles["hard-a"],
    contractSchemaVersion: fixtureContract.schemaVersion,
    expectedBuildIdentity: fixtureIdentity,
    uncappedEvidence: fixtureUncappedEvidence,
  });
  assert.equal(validation.valid, false, `strict release must reject ${label}`);
  assert.match(validation.failures.join("\n"), expected, `${label}: failure evidence`);
}
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

const benchmarkConfigurationFor = (identity) => JSON.parse(execFileSync(
  process.execPath,
  [benchmarkScript, "--profile", "traversal-6-4", "--print-config"],
  suiteExecOptions(identity),
));
for (const [profileId, expected] of [
  ["26.2", {
    protocolVersion: 776,
    worldVersion: 4903,
    worldgenTelemetryMode: "task-pulsed",
  }],
  ["1.21.11", {
    protocolVersion: 774,
    worldVersion: 4671,
    worldgenTelemetryMode: "checkpoint-only",
  }],
]) {
  const benchmarkConfiguration = benchmarkConfigurationFor(
    suiteManifestIdentities.get(profileId),
  );
  assert.equal(benchmarkConfiguration.activeVersionProfile.id, profileId);
  assert.equal(benchmarkConfiguration.activeVersionProfile.protocolVersion,
    expected.protocolVersion);
  assert.equal(benchmarkConfiguration.activeVersionProfile.worldVersion,
    expected.worldVersion);
  assert.equal(benchmarkConfiguration.activeVersionProfile.worldgenTelemetryMode,
    expected.worldgenTelemetryMode);
  assert.equal(benchmarkConfiguration.releaseDistanceCapability.supported, true);
  assert.equal(benchmarkConfiguration.releaseDistanceCapability.profileId, profileId);
  assert.equal(
    benchmarkConfiguration.releaseDistanceCapability.capability,
    "headed-chrome-worker-distance-6-4",
  );
  assert.equal(benchmarkConfiguration.workerDistanceMode, "natural-observation");
  assert.equal(benchmarkConfiguration.workerDistanceContract.mode, "natural-observation");
  assert.equal(benchmarkConfiguration.workerDistanceContract.releaseEligible, true);
  assert.equal(benchmarkConfiguration.workerDistanceContract.releaseTargetProfile, profileId);
  assert.equal(benchmarkConfiguration.workerDistanceContract.capability,
    "headed-chrome-worker-distance-6-4");
  assert.equal(benchmarkConfiguration.mode, "release-gating");
  assert.equal(benchmarkConfiguration.gating, true);
  assert.equal(benchmarkConfiguration.releaseEvidence, true);
}

const configuration = JSON.parse(execFileSync(
  process.execPath,
  [script, "--print-config"],
  suiteExecOptions(),
));
assert.equal(configuration.buildIdentity.coherent, true);
assert.equal(configuration.activeProfile.id, "26.2");
assert.equal(configuration.activeProfile.path, "versions/26.2.json");
assert.equal(configuration.activeProfile.worldgenTelemetryMode, "task-pulsed");
assert.equal(configuration.buildIdentity.profilePath, "versions/26.2.json");
assert.equal(configuration.buildIdentity.nestedProfile.worldVersion, 4903);
assert.equal(configuration.buildIdentity.worldVersion, 4903);
assert.equal(configuration.buildIdentity.worldgenTelemetryMode, "task-pulsed");
assert.deepEqual(configuration.buildIdentity.storage, releaseManifestFixtureIdentity.storage);
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
assert.ok(
  configuration.profiles.length > 0
    && configuration.profiles.every((profile) => profile.releaseEvidence === true),
  "strict 26.2 suite profiles must remain release-gating",
);

const legacyConfiguration = JSON.parse(execFileSync(
  process.execPath,
  [script, "--print-config"],
  suiteExecOptions(suiteManifestIdentities.get("1.21.11")),
));
assert.equal(legacyConfiguration.activeProfile.id, "1.21.11");
assert.equal(legacyConfiguration.activeProfile.path, "versions/1.21.11.json");
assert.equal(legacyConfiguration.activeProfile.worldVersion, 4671);
assert.equal(legacyConfiguration.activeProfile.worldgenTelemetryMode, "checkpoint-only");
assert.equal(legacyConfiguration.buildIdentity.profilePath, "versions/1.21.11.json");
assert.equal(legacyConfiguration.buildIdentity.nestedProfile.worldVersion, 4671);
assert.equal(legacyConfiguration.buildIdentity.worldgenTelemetryMode, "checkpoint-only");
assert.deepEqual(
  legacyConfiguration.buildIdentity.storage,
  legacyReleaseManifestFixtureIdentity.storage,
);
assert.ok(
  legacyConfiguration.profiles.length > 0
    && legacyConfiguration.profiles.every((profile) => profile.releaseEvidence === true),
  "strict 1.21.11 suite profiles must remain release-gating",
);

const matrixConfiguration = JSON.parse(execFileSync(
  process.execPath,
  [script, "--matrix", "--print-config"],
  suiteExecOptions(),
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
    suiteExecOptions({stdio: ["ignore", "ignore", "ignore"]}),
  ),
  (error) => error?.status === 1,
  "strict release must reject a profile list that omits hard targets or memory evidence",
);
assert.throws(
  () => execFileSync(
    process.execPath,
    [script, "--profiles", "steady-6-4,traversal-6-4,soak-sp-6-4,soak-mp-6-4", "--print-config"],
    suiteExecOptions({stdio: ["ignore", "ignore", "ignore"]}),
  ),
  (error) => error?.status === 1,
  "strict release must reject explicitly selected unsupported soak-mp evidence",
);
assert.throws(
  () => execFileSync(
    process.execPath,
    [script, "--url", "https://example.invalid/Gaius.html", "--print-config"],
    suiteExecOptions({stdio: ["ignore", "ignore", "ignore"]}),
  ),
  (error) => error?.status === 1,
  "strict release must not accept an unverified external build URL",
);
assert.throws(
  () => execFileSync(
    process.execPath,
    [script, "--attach-port", "9222", "--print-config"],
    suiteExecOptions({stdio: ["ignore", "ignore", "ignore"]}),
  ),
  (error) => error?.status === 1,
  "release suite must fail closed for attached Chrome input",
);
assert.throws(
  () => execFileSync(
    process.execPath,
    [script, "--allow-attached-input", "--print-config"],
    suiteExecOptions({stdio: ["ignore", "ignore", "ignore"]}),
  ),
  (error) => error?.status === 1,
  "removed attached-input escape hatch must remain rejected",
);
assert.throws(
  () => execFileSync(
    process.execPath,
    [script, "--pin-worker-distance", "--print-config"],
    suiteExecOptions({stdio: ["ignore", "ignore", "ignore"]}),
  ),
  (error) => error?.status === 1,
  "release suite must reject diagnostic Worker distance pinning",
);
const smokeConfiguration = JSON.parse(execFileSync(
  process.execPath,
  [script, "--smoke", "--profiles", "steady-6-4", "--print-config"],
  suiteExecOptions(),
));
assert.equal(smokeConfiguration.mode, "smoke-suite");
assert.equal(smokeConfiguration.releaseEvidence, false);
assert.equal(smokeConfiguration.gating, false);
assert.equal(smokeConfiguration.profiles[0].releaseEvidence, false);

await rm(fixtureRoot, {recursive: true, force: true});
console.log(
  "Chrome performance release-suite smoke passed",
  `(schema14 pacing adversarial fixtures=${epochAndCleanupAdversarialFixtures.length + 2})`,
);
