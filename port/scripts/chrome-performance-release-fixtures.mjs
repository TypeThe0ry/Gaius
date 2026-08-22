const fixtureHash = (character) => character.repeat(64);

export const acceptanceFixtureIdentity = {
  manifestSha256: fixtureHash("a"),
  compatibilitySha256: fixtureHash("b"),
  coherent: true,
  artifactCompatibilities: [fixtureHash("b"), fixtureHash("b"), fixtureHash("b"), fixtureHash("b")],
};

export const releaseManifestFixtureIdentity = {
  profileId: "26.2",
  profilePath: "versions/26.2.json",
  profileSha256: fixtureHash("c"),
  clientDistribution: "named",
  protocolVersion: 776,
  worldVersion: 4903,
  worldgenTelemetryMode: "task-pulsed",
  storage: {
    schema: 2,
    databaseName: "gaius-fs-v2-26.2",
    prefix: "gaius.fs.v2:26.2:",
    opfsDirectory: "regions-v2-26.2",
  },
};

export const legacyReleaseManifestFixtureIdentity = {
  profileId: "1.21.11",
  profilePath: "versions/1.21.11.json",
  profileSha256: fixtureHash("d"),
  clientDistribution: "obfuscated-with-mappings",
  protocolVersion: 774,
  worldVersion: 4671,
  worldgenTelemetryMode: "checkpoint-only",
  storage: {
    schema: 2,
    databaseName: "gaius-fs-v2-1.21.11",
    prefix: "gaius.fs.v2:1.21.11:",
    opfsDirectory: "regions-v2-1.21.11",
  },
};

export function makeManifestIdentityFixture(identity = releaseManifestFixtureIdentity) {
  const storage = {...identity.storage};
  const nestedProfile = {
    id: identity.profileId,
    path: identity.profilePath,
    sha256: identity.profileSha256,
    clientDistribution: identity.clientDistribution,
    protocolVersion: identity.protocolVersion,
    worldVersion: identity.worldVersion,
    worldgenTelemetryMode: identity.worldgenTelemetryMode,
    storage: {...storage},
  };
  const compatibilitySha256 = fixtureHash("b");
  return {
    artifact: "Gaius.html",
    profile: identity.profileId,
    profilePath: identity.profilePath,
    profileSha256: identity.profileSha256,
    worldVersion: identity.worldVersion,
    worldgenTelemetryMode: identity.worldgenTelemetryMode,
    storage,
    buildIdentity: {
      schemaVersion: 2,
      compatibilitySha256,
      profile: nestedProfile,
    },
    classesJs: {build: {compatibilitySha256}},
    singleplayerServerJs: {build: {compatibilitySha256}},
    singleplayerWorkerBootstrap: {build: {compatibilitySha256}},
    wasmHotpath: {build: {compatibilitySha256}},
  };
}

export const acceptanceFixtureProfile = {
  evidenceRole: "release-hard-target",
  releaseEvidence: true,
  scenario: "steady",
  route: "singleplayer",
  renderDistance: 6,
  simulationDistance: 4,
  gates: {
    averageFpsMin: 120,
    onePercentLowFpsMin: 60,
    coverageRatioMin: 0.98,
    longestFrameMsMax: 50,
    stallCountAtLeast2sMax: 0,
    freezeCountMax: 0,
    memory: false,
  },
};

export function makeAcceptanceFixtureReport({
  profileName = "hard-a",
  profile = acceptanceFixtureProfile,
  contractSchemaVersion = 17,
  buildIdentity = acceptanceFixtureIdentity,
  analysisOverrides = {},
} = {}) {
  const releaseEvidence = profile.releaseEvidence === true;
  const mode = releaseEvidence ? "release-gating" : "diagnostic-stress";
  const verdict = "pass";
  const expectedDistance = `${Number(profile.renderDistance)}:${Number(profile.simulationDistance)}`;
  const naturalWorkerEvidence = {
    mode: "natural-observation",
    complete: true,
    truncatedCount: 0,
    messages: [{
      type: "start",
      launchGeneration: "1",
      renderDistance: Number(profile.renderDistance),
      simulationDistance: Number(profile.simulationDistance),
      profileId: "26.2",
      worldVersion: 4903,
      storageSchema: 2,
      storageDatabaseName: "gaius-fs-v2-26.2",
      storagePrefix: "gaius.fs.v2:26.2:",
      storageOpfsDirectory: "regions-v2-26.2",
    }],
  };
  const harnessOverrideEvidence = {
    enabled: false,
    mode: "disabled",
    messages: [],
    truncatedCount: 0,
    releaseEligible: false,
  };
  return {
    schemaVersion: contractSchemaVersion,
    passed: true,
    verdict,
    profileName,
    buildIdentity,
    configuration: {
      profileName,
      contractSchemaVersion,
      profile,
      worldgenTelemetryMode: "task-pulsed",
      gating: releaseEvidence,
      releaseEvidence,
      strictChecks: true,
      buildIdentity,
      workerDistanceContract: {
        mode: "natural-observation",
        releaseEligible: true,
        optionsPreference: expectedDistance,
        expectedWorkerServerDistance: expectedDistance,
        matchingWorkerStartMessages: 1,
        expectedStorage: {
          profileId: "26.2",
          worldVersion: 4903,
          storageSchema: 2,
          storageDatabaseName: "gaius-fs-v2-26.2",
          storagePrefix: "gaius.fs.v2:26.2:",
          storageOpfsDirectory: "regions-v2-26.2",
        },
        naturalWorkerEvidence,
        harnessOverrideEvidence,
      },
    },
    analysis: {
      verdict,
      passed: true,
      mode,
      gating: releaseEvidence,
      releaseEvidence,
      evidenceRole: profile.evidenceRole || null,
      worldgenTelemetryMode: "task-pulsed",
      environment: {
        profileName,
        frameLimiter: {uncapped: true},
        distanceContract: {
          mode: "natural-observation",
          releaseEligible: true,
          optionsPreference: expectedDistance,
          expectedWorkerServerDistance: expectedDistance,
          matchingWorkerStartMessages: 1,
          expectedStorage: {
            profileId: "26.2",
            worldVersion: 4903,
            storageSchema: 2,
            storageDatabaseName: "gaius-fs-v2-26.2",
            storagePrefix: "gaius.fs.v2:26.2:",
            storageOpfsDirectory: "regions-v2-26.2",
          },
          naturalWorkerEvidence,
          harnessOverrideEvidence,
        },
      },
      naturalWorkerEvidence,
      harnessOverrideEvidence,
      frame: {
        sampleCount: 36_000,
        rawFrameCount: 36_000,
        rawSampleCount: 360,
        averageFpsRaw: 144,
        onePercentLowFpsRaw: 72,
        coverageRatioRaw: 1,
        longestFrameMsRaw: 18,
        longFrames: {
          atLeast2000Ms: 0,
        },
      },
      freezes: {
        total: 0,
      },
      stability: {
        verdict: "pass",
        fatalEvents: [],
        contextLosses: [],
        maximumStateStallMillis: 0,
      },
      queues: {
        verdict: "pass",
        queues: {
          "chunk.pendingTasks": {
            available: true,
            maximum: 2,
            finalValue: 0,
            longestHighWaterMs: 20,
            failed: false,
          },
          "chunk.compileBacklog": {
            available: true,
            maximum: 1,
            finalValue: 0,
            longestHighWaterMs: 20,
            failed: false,
          },
          "chunk.uploadBacklog": {
            available: true,
            maximum: 1,
            finalValue: 0,
            longestHighWaterMs: 20,
            failed: false,
          },
        },
      },
      workerMessage: {
        rttSampleCount: 360,
        p99RttMillis: 12,
        maxRttMillis: 24,
        pending: 0,
      },
      memory: {
        verdict: "pass",
        v8Heap: {
          verdict: "pass",
          regularSampleCount: 8,
          postGcSampleCount: 4,
          leakSignal: false,
          finalThreeWindowsPositive: false,
          postGcSlopeMiBPerMinute: 0.1,
          retainedGrowthPercent: 1,
        },
        browserMemory: {
          verdict: "pass",
          regularSampleCount: 8,
          postGcSampleCount: 4,
          metrics: {
            liveBytes: {finalThreeWindowsPositive: false},
          },
        },
        processRss: {
          verdict: "pass",
          availability: {availableSampleCount: 8},
          failedMetrics: [],
          total: {failed: false},
          byType: {},
        },
      },
      performanceEvidence: {
        framePacing: {
          verdict: "pass",
          observed: {
            swapIntervalMin: 0,
            swapIntervalMax: 0,
            uncappedYieldCountMax: 8,
            vsyncYieldCountMax: 0,
            presentToRafCountMax: 0,
            fairYieldCountMax: 2,
            messageChannelCreateFailureCountMax: 0,
            messageChannelPostFailureCountMax: 0,
            messageChannelRebuildCountMax: 0,
            cancelledMessageTaskCountMax: 0,
            watchdogYieldCountMax: 0,
          },
        },
      },
      ...analysisOverrides,
    },
  };
}
