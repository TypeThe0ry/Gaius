#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";
import {
  buildPerformanceEvidence,
  combineMemorySnapshots,
  evaluateBrowserMemorySafety,
  evaluatePerformanceGates,
  evaluateRuntimeInvariants,
  evaluateUncappedFramePacing,
  mergeMonotonicSamples,
  recoverFrameRingDelta,
  summarizeFrameTimes,
  summarizeMemoryTrend,
  summarizeNativeMemoryTrend,
  summarizeQueueTimeline,
  summarizeRuntimeInvariantTelemetry,
  validateWorkerHeartbeatTelemetry,
} from "./performance-metrics.mjs";

const fixturePath = fileURLToPath(new URL("./performance-fixtures.json", import.meta.url));
const contractPath = fileURLToPath(new URL("./performance-contract.json", import.meta.url));
const benchmarkPath = fileURLToPath(new URL("./chrome-chunk-benchmark.mjs", import.meta.url));
const fixtures = JSON.parse(await readFile(fixturePath, "utf8"));
const contract = JSON.parse(await readFile(contractPath, "utf8"));
const benchmarkSource = await readFile(benchmarkPath, "utf8");
const completed = [];

assert.equal(contract.schemaVersion, 11, "performance contract schema must include scheduler health evidence rules");
assert.deepEqual(
  contract.environment.uncappedEvidence.requiredFields,
  [
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
  "uncapped release evidence fields must be explicit",
);

for (const testCase of fixtures.uncappedPacingCases) {
  const result = evaluateUncappedFramePacing({
    samples: testCase.samples,
    final: testCase.final,
    requirements: contract.environment.uncappedEvidence,
  });
  assert.equal(result.verdict, testCase.expectedVerdict, `${testCase.name}: verdict`);
  completed.push(testCase.name);
}

{
  const evidence = buildPerformanceEvidence({
    frames: {
      averageFpsRaw: 118,
      onePercentLowFpsRaw: 55,
      p99FrameMsRaw: 20,
      longestFrameMsRaw: 81,
      coverageRatioRaw: 0.99,
      sampleCount: 1000,
    },
    framePacing: {
      verdict: "pass",
      observed: {
        swapIntervalMin: 0,
        swapIntervalMax: 0,
        uncappedYieldCountMax: 10,
        vsyncYieldCountMax: 0,
        presentToRafCountMax: 0,
        fairYieldCountMax: 2,
        messageChannelCreateFailureCountMax: 0,
        messageChannelPostFailureCountMax: 0,
        messageChannelRebuildCountMax: 0,
        cancelledMessageTaskCountMax: 0,
        watchdogYieldCountMax: 0,
      },
      reasons: [],
    },
    memory: {
      verdict: "fail",
      reasons: ["retained heap grew"],
      v8Heap: {
        verdict: "fail",
        postGcSlopeMiBPerMinute: 4,
        retainedGrowthPercent: 22,
        retainedGrowthBytes: 300 * 1024 * 1024,
        finalThreeWindowsPositive: true,
        peakUsedMiB: 900,
        coverage: {complete: true},
        thresholds: {retainedGrowthPercentMax: 15, retainedGrowthBytesMax: 256 * 1024 * 1024},
      },
    },
    freezes: {total: 1, reasons: ["81 ms stall"]},
    travel: {maximumTraversalStallMillis: 12000, maximumAllowedTraversalStallMillis: 10000},
    gates: {
      averageFpsMin: 120,
      onePercentLowFpsMin: 60,
      p99FrameMsMax: 16.7,
      longestFrameMsMax: 50,
      coverageRatioMin: 0.98,
      freezeCountMax: 0,
    },
    buildIdentity: {compatibilitySha256: "c".repeat(64)},
  });
  assert.equal(evidence.frame.averageFps.passed, false);
  assert.equal(evidence.frame.onePercentLowFps.passed, false);
  assert.equal(evidence.stall.longestFrameMs.actual, 81);
  assert.equal(evidence.stall.maximumTraversalStallMs.passed, false);
  assert.equal(evidence.memory.heap.trend, "leak-signal");
  assert.equal(evidence.memory.heap.leakSignal, true);
  assert.deepEqual(evidence.buildIdentity, {compatibilitySha256: "c".repeat(64)});
  assert.ok(evidence.failureReasons.some((reason) => reason.includes("averageFps")));
  completed.push("failure-evidence-includes-fps-stall-heap-and-build-identity");
}

for (const testCase of fixtures.frameCases) {
  const frameTimes = testCase.segments.flatMap(({frameMs, count}) =>
    Array.from({length: count}, () => frameMs));
  const summary = summarizeFrameTimes(frameTimes, testCase.elapsedMs);
  for (const [key, expected] of Object.entries(testCase.expected)) {
    const actual = key === "atLeast500Ms" ? summary.longFrames.atLeast500Ms : summary[key];
    assert.equal(actual, expected, `${testCase.name}: ${key}`);
  }
  if (testCase.assertDistinctFromP99Reciprocal) {
    assert.notEqual(
      summary.onePercentLowFps,
      summary.p99FrameMs > 0 ? Math.round((1000 / summary.p99FrameMs) * 1000) / 1000 : null,
      `${testCase.name}: 1% low must not be the reciprocal of P99`,
    );
  }
  completed.push(testCase.name);
}

{
  const summary = summarizeFrameTimes(
    Array.from({length: 1200}, () => 1000 / 120),
    10_008,
  );
  assert.equal(summary.averageFps, 120,
    "120 Hz complete intervals must not fail because of partial boundary time");
  assert.ok(summary.coverageRatioRaw < 1,
    "partial boundary time must remain visible to the coverage gate");
  completed.push("average-fps-uses-complete-frame-intervals");
}

{
  const summary = summarizeFrameTimes([0, 0, 1000 / 120, 1000 / 120], 1000 / 60);
  assert.equal(summary.sampleCount, 2, "zero frame intervals must not inflate FPS");
  assert.equal(summary.invalidFrameIntervalCount, 2);
  assert.equal(summary.averageFps, 120);
  completed.push("zero-frame-intervals-are-invalid");
}

for (const testCase of fixtures.ringCases) {
  const result = recoverFrameRingDelta(testCase.snapshot, testCase.previousFrameCount);
  assert.deepEqual(result.samples, testCase.expectedSamples, `${testCase.name}: samples`);
  assert.equal(result.lostSamples, testCase.expectedLostSamples, `${testCase.name}: lostSamples`);
  assert.equal(result.wrapped, testCase.expectedWrapped, `${testCase.name}: wrapped`);
  completed.push(testCase.name);
}

{
  const capacity = 65536;
  const frameCount = capacity + 5;
  const frameTimes = new Array(capacity);
  for (let frame = 5; frame < frameCount; frame++) {
    frameTimes[frame % capacity] = frame;
  }
  const result = recoverFrameRingDelta({
    frameTimes,
    sampleCapacity: capacity,
    sampleWriteIndex: frameCount % capacity,
    sampleCount: capacity,
    frameCount,
  }, 0);
  assert.equal(result.samples.length, capacity, "fixed-65536-ring-wrap: length");
  assert.equal(result.samples[0], 5, "fixed-65536-ring-wrap: oldest sample");
  assert.equal(result.samples.at(-1), frameCount - 1, "fixed-65536-ring-wrap: newest sample");
  assert.equal(result.lostSamples, 5, "fixed-65536-ring-wrap: loss");
  assert.equal(result.wrapped, true, "fixed-65536-ring-wrap: wrapped");
  completed.push("fixed-65536-ring-wrap");
}

for (const testCase of fixtures.queueCases) {
  const result = summarizeQueueTimeline(
    testCase.timeline,
    testCase.thresholds,
    testCase.requiredDurationMs,
  );
  assert.equal(result.verdict, testCase.expectedVerdict, `${testCase.name}: verdict`);
  completed.push(testCase.name);
}

{
  const result = summarizeQueueTimeline([
    {atMillis: 0, chunk: {pendingTasks: 9}},
    {atMillis: 7000, chunk: {pendingTasks: 9}},
    {atMillis: 12000, chunk: {pendingTasks: 0}},
  ], {"chunk.pendingTasks": 8}, 10000);
  assert.equal(
    result.queues["chunk.pendingTasks"].longestHighWaterMs,
    12000,
    "queue recovery sample must close the full high-water interval",
  );
  assert.equal(result.verdict, "fail", "12-second high-water must not be reported as 7 seconds");
  completed.push("queue-high-water-recovery-closes-final-interval");
}

{
  const result = summarizeQueueTimeline([
    {atMillis: 0, chunk: {pendingTasks: 0}},
    {atMillis: 5000, chunk: {pendingTasks: 9}},
  ], {"chunk.pendingTasks": 8}, 10000, 16000);
  assert.equal(result.verdict, "fail",
    "an active high-water interval must close at the real measurement deadline");
  assert.equal(result.queues["chunk.pendingTasks"].longestHighWaterMs, 11000);
  completed.push("queue-tail-closes-at-measurement-deadline");
}

{
  const result = mergeMonotonicSamples(
    [{at: 3000, page: {frames: 3}}, {at: 1000, page: {frames: 1}}],
    [{at: 2000, worker: {pending: 2}}, {at: 3000, worker: {pending: 1}}],
  );
  assert.deepEqual(result.map((sample) => sample.at), [1000, 2000, 3000]);
  assert.deepEqual(result.at(-1), {
    at: 3000,
    page: {frames: 3},
    worker: {pending: 1},
  });
  completed.push("concurrent-samples-sort-and-merge-equal-timestamps");
}

{
  const result = combineMemorySnapshots([
    {liveBytes: 10, liveRegions: 2, allocations: 4},
    {liveBytes: 15, liveRegions: 3, allocations: 6},
  ], ["liveBytes", "liveRegions", "allocations"]);
  assert.deepEqual(result, {
    liveBytes: 25,
    liveRegions: 5,
    allocations: 10,
    sourceCount: 2,
  });
  completed.push("page-and-worker-browser-memory-are-merged");
}

for (const testCase of fixtures.memoryCases) {
  const postGcSamples = testCase.postGcMiB.map((value, index) => ({
    atMillis: index * 300000,
    totalUsedBytes: value * 1024 * 1024,
    supported: true,
  }));
  const result = summarizeMemoryTrend({
    postGcSamples,
    durationMs: testCase.durationMs,
    loadedChunkDelta: testCase.loadedChunkDelta,
    requiredDurationMs: testCase.durationMs,
    minimumDurationCoverageRatio: 0,
    maximumSampleGapRatio: 1_000_000,
  });
  assert.equal(result.verdict, testCase.expectedVerdict, `${testCase.name}: verdict`);
  completed.push(testCase.name);
}

{
  const result = summarizeMemoryTrend({
    regularSamples: [
      {atMillis: 0, totalUsedBytes: 512 * 1024 * 1024},
      {atMillis: 900000, totalUsedBytes: 9 * 1024 * 1024 * 1024},
      {atMillis: 1800000, totalUsedBytes: 600 * 1024 * 1024},
    ],
    postGcSamples: [100, 101, 100, 101].map((value, index) => ({
      atMillis: index * 600000,
      totalUsedBytes: value * 1024 * 1024,
      supported: true,
    })),
    durationMs: 1800000,
    requiredDurationMs: 1800000,
    peakUsedBytesMax: 8 * 1024 * 1024 * 1024,
    minimumDurationCoverageRatio: 0,
    maximumSampleGapRatio: 1_000_000,
  });
  assert.equal(result.verdict, "fail",
    "a transient regular-heap peak above the hard limit must not be hidden by GC");
  assert.equal(result.regularPeakUsedMiB, 9 * 1024);
  completed.push("regular-heap-absolute-peak-is-gated");
}

{
  const postGcMiB = [100, 260, 420, 800, 700, 790, 780];
  const result = summarizeMemoryTrend({
    postGcSamples: postGcMiB.map((value, index) => ({
      atMillis: index * 300000,
      totalUsedBytes: value * 1024 * 1024,
      supported: true,
    })),
    durationMs: 1800000,
    requiredDurationMs: 1800000,
    loadedChunkDelta: 1000,
    minimumDurationCoverageRatio: 0,
    maximumSampleGapRatio: 1_000_000,
  });
  assert.equal(
    result.verdict,
    "fail",
    "newly loaded chunks must not exempt extreme retained heap growth",
  );
  assert.ok(result.retainedGrowthMiB > 256);
  completed.push("loaded-chunks-do-not-exempt-extreme-heap-growth");
}

for (const testCase of fixtures.nativeMemoryCases) {
  const makeSamples = (values, interval, spanDuration = null) => values.map((value, index) => ({
    atMillis: spanDuration != null && values.length > 1
      ? index * spanDuration / (values.length - 1)
      : index * interval,
    liveBytes: value[0],
    liveRegions: value[1],
    associatedBuffers: value[2],
  }));
  const result = summarizeNativeMemoryTrend({
    regularSamples: makeSamples(testCase.regular, 5000, testCase.durationMs),
    postGcSamples: makeSamples(testCase.postGc, 300000, testCase.durationMs),
    cleanupSamples: makeSamples(testCase.cleanup, 5000),
    baseline: {
      liveBytes: testCase.baseline[0],
      liveRegions: testCase.baseline[1],
      associatedBuffers: testCase.baseline[2],
    },
    durationMs: testCase.durationMs,
    requiredDurationMs: testCase.durationMs,
    minimumDurationCoverageRatio: 0,
    maximumSampleGapRatio: 1_000_000,
  });
  assert.equal(result.verdict, testCase.expectedVerdict, `${testCase.name}: verdict`);
  completed.push(testCase.name);
}

{
  const requiredDurationMs = 1_800_000;
  const attemptedSampleTimes = Array.from(
    {length: requiredDurationMs / 5000 + 1},
    (_, index) => index * 5000,
  );
  const clusteredHeap = [100, 108, 104, 107].map((value, index) => ({
    atMillis: index * 5000,
    totalUsedBytes: value * 1024 * 1024,
    supported: true,
  }));
  const heapCoverage = summarizeMemoryTrend({
    regularSamples: clusteredHeap,
    postGcSamples: clusteredHeap,
    durationMs: requiredDurationMs,
    requiredDurationMs,
    attemptedSampleTimes,
    sampleIntervalMs: 5000,
    minimumAvailableRatio: contract.heapMemory.minimumAvailableRatio,
    minimumDurationCoverageRatio: contract.heapMemory.minimumDurationCoverageRatio,
    maximumSampleGapRatio: contract.heapMemory.maximumSampleGapRatio,
  });
  assert.equal(heapCoverage.verdict, "inconclusive",
    "clustered heap samples must not pass a thirty-minute soak");
  assert.equal(heapCoverage.coverage.complete, false);
  completed.push("sparse-heap-coverage-is-inconclusive");

  const clusteredNative = [
    [10, 100, 50],
    [11, 105, 52],
    [10.5, 102, 51],
    [10.8, 104, 52],
  ].map(([liveBytesMiB, liveRegions, associatedBuffers], index) => ({
    atMillis: index * 5000,
    liveBytes: liveBytesMiB * 1024 * 1024,
    liveRegions,
    associatedBuffers,
  }));
  const nativeCoverage = summarizeNativeMemoryTrend({
    regularSamples: clusteredNative,
    postGcSamples: clusteredNative,
    cleanupSamples: [clusteredNative[0]],
    baseline: {liveBytes: 10 * 1024 * 1024, liveRegions: 100, associatedBuffers: 50},
    durationMs: requiredDurationMs,
    requiredDurationMs,
    attemptedSampleTimes,
    sampleIntervalMs: 5000,
    minimumAvailableRatio: contract.browserMemory.minimumAvailableRatio,
    minimumDurationCoverageRatio: contract.browserMemory.minimumDurationCoverageRatio,
    maximumSampleGapRatio: contract.browserMemory.maximumSampleGapRatio,
  });
  assert.equal(nativeCoverage.verdict, "inconclusive",
    "clustered BrowserMemory samples must not pass a thirty-minute soak");
  assert.equal(nativeCoverage.coverage.complete, false);
  completed.push("sparse-browser-memory-coverage-is-inconclusive");
}

{
  const span = [0, 600_000, 1_200_000, 1_800_000];
  const makeNative = (values) => values.map((value, index) => ({
    atMillis: span[index],
    liveBytes: value[0],
    liveRegions: value[1],
    associatedBuffers: value[2],
  }));
  const result = summarizeNativeMemoryTrend({
    regularSamples: makeNative([
      [12_000_000, 120, 60], [13_000_000, 125, 62],
      [12_500_000, 123, 61], [12_800_000, 124, 62],
    ]),
    postGcSamples: makeNative([
      [12_000_000, 120, 60], [13_000_000, 125, 62],
      [12_500_000, 123, 61], [12_800_000, 124, 62],
    ]),
    cleanupSamples: makeNative([[10_000_000, 100, 50], [10_000_000, 100, 50],
      [10_000_000, 100, 50], [10_000_000, 100, 50]]),
    baseline: {liveBytes: 10_000_000, liveRegions: 100, associatedBuffers: 50},
    durationMs: 1_800_000,
    requiredDurationMs: 1_800_000,
    attemptedSampleTimes: span,
    sampleIntervalMs: 600_000,
    minimumAvailableRatio: 1,
    minimumDurationCoverageRatio: 1,
    maximumSampleGapRatio: 1.5,
    cleanupSourceComplete: false,
  });
  assert.equal(result.verdict, "inconclusive",
    "cleanup without complete page and Worker evidence must not pass");
  assert.match(result.reasons.join(" "), /complete page and Worker evidence/);
  completed.push("browser-memory-cleanup-source-is-required");
}

{
  const browserMemoryRules = contract.browserMemory;
  const safeSnapshot = {
    liveBytes: 64 * 1024 * 1024,
    liveRegions: 128,
    associatedBuffers: 256,
    maxLiveBytes: browserMemoryRules.limits.maxLiveBytes,
    maxTemporaryBytes: browserMemoryRules.limits.maxTemporaryBytes,
    peakTemporaryBytes: 128 * 1024,
    allocationFailures: 0,
    temporaryAllocationFailures: 0,
  };
  const evaluate = (memory) => evaluateBrowserMemorySafety({
    snapshots: [{source: "page", memory}],
    requiredFields: [
      ...browserMemoryRules.liveFields,
      ...browserMemoryRules.safetyRequiredFields,
    ],
    failureFields: browserMemoryRules.failureFields,
    limits: browserMemoryRules.limits,
  });
  assert.equal(evaluate(safeSnapshot).verdict, "pass");
  completed.push("browser-memory-safety-valid-snapshot");

  assert.equal(evaluate({...safeSnapshot, allocationFailures: 1}).verdict, "fail");
  completed.push("browser-memory-safety-allocation-failure");

  const missingLimit = {...safeSnapshot};
  delete missingLimit.maxTemporaryBytes;
  assert.equal(evaluate(missingLimit).verdict, "inconclusive");
  completed.push("browser-memory-safety-missing-limit");

  assert.equal(evaluate({
    ...safeSnapshot,
    maxLiveBytes: browserMemoryRules.limits.maxLiveBytes + 1,
  }).verdict, "fail");
  completed.push("browser-memory-safety-unsafe-configured-limit");

  assert.equal(evaluate({
    ...safeSnapshot,
    peakTemporaryBytes: safeSnapshot.maxTemporaryBytes + 1,
  }).verdict, "fail");
  completed.push("browser-memory-safety-temporary-peak-over-budget");

  const pageBudget = {
    ...safeSnapshot,
    liveBytes: 1.8 * 1024 * 1024 * 1024,
    peakTemporaryBytes: 8 * 1024 * 1024,
  };
  const workerBudget = {...pageBudget};
  const aggregateMemory = {
    ...combineMemorySnapshots(
      [pageBudget, workerBudget],
      browserMemoryRules.liveFields,
    ),
    maxLiveBytes: browserMemoryRules.aggregateLimits.maxLiveBytes,
    maxTemporaryBytes: browserMemoryRules.aggregateLimits.maxTemporaryBytes,
    peakTemporaryBytes: pageBudget.peakTemporaryBytes + workerBudget.peakTemporaryBytes,
    allocationFailures: 0,
    temporaryAllocationFailures: 0,
  };
  const aggregateResult = evaluateBrowserMemorySafety({
    snapshots: [
      {source: "page", memory: pageBudget},
      {source: "worker", memory: workerBudget},
      {source: "page+worker", aggregate: true, memory: aggregateMemory},
    ],
    requiredFields: [
      ...browserMemoryRules.liveFields,
      ...browserMemoryRules.safetyRequiredFields,
    ],
    failureFields: browserMemoryRules.failureFields,
    limits: browserMemoryRules.limits,
    aggregateLimits: browserMemoryRules.aggregateLimits,
  });
  assert.equal(aggregateResult.verdict, "fail",
    "page and Worker memory must be checked against an aggregate budget");
  assert.ok(aggregateResult.failures.some(
    (failure) => failure.source === "page+worker" && failure.field === "liveBytes",
  ));
  completed.push("browser-memory-aggregate-budget-is-gated");
}

{
  const context = {
    expectedWorkerMeasurementId: "measurement-current",
    measurementStartedAt: 10_000,
    measurementEndedAt: 20_000,
  };
  const lifecycle = {states: [{sessionId: "session-current", terminal: false}]};
  const valid = {
    schemaVersion: 2,
    sessionId: "session-current",
    measurementId: "measurement-current",
    resetAt: 9_999,
    updatedAt: 19_990,
    sent: 200,
    received: 200,
    rttSampleCount: 200,
    missed: 0,
    errors: 0,
    pending: 0,
    p99RttMillis: 12,
    maxRttMillis: 20,
    longestHeartbeatDelayMillis: 4,
    lastPongAt: 19_980,
    configuredIntervalMillis: 50,
  };
  const evaluate = (worker) => validateWorkerHeartbeatTelemetry(
    worker,
    lifecycle,
    context,
    contract.heartbeat,
  );
  assert.equal(evaluate(valid).verdict, "pass");
  completed.push("worker-heartbeat-current-window-valid");

  const missingP99 = {...valid};
  delete missingP99.p99RttMillis;
  assert.equal(evaluate(missingP99).verdict, "fail");
  completed.push("worker-heartbeat-missing-field-fails");

  assert.equal(evaluate({...valid, measurementId: "measurement-old"}).verdict, "fail");
  completed.push("worker-heartbeat-old-measurement-fails");

  assert.equal(evaluate({...valid, updatedAt: 15_000, lastPongAt: 15_000}).verdict, "fail");
  completed.push("worker-heartbeat-stale-update-fails");

  assert.equal(evaluate({...valid, lastPongAt: 25_000}).verdict, "fail");
  completed.push("worker-heartbeat-future-pong-fails");

  assert.equal(evaluate({...valid, sent: 0, received: 0, rttSampleCount: 0}).verdict, "fail");
  completed.push("worker-heartbeat-zero-counters-fail");

  assert.equal(evaluate({...valid, sent: 10, received: 11, rttSampleCount: 11}).verdict, "fail");
  completed.push("worker-heartbeat-counter-mismatch-fails");

  const manyWorkers = {
    states: Array.from({length: 17}, (_, index) => ({
      sessionId: index === 0 ? "session-current" : `session-${index}`,
      terminal: false,
    })),
  };
  assert.equal(validateWorkerHeartbeatTelemetry(
    valid,
    manyWorkers,
    context,
    contract.heartbeat,
  ).verdict, "fail");
  completed.push("worker-lifecycle-over-sixteen-is-not-truncated");
}

function mergeFixture(base, overrides) {
  const result = JSON.parse(JSON.stringify(base));
  for (const [section, values] of Object.entries(overrides || {})) {
    result[section] = {...(result[section] || {}), ...values};
  }
  return result;
}

function omitFixturePath(value, path) {
  const parts = String(path).split(".");
  const key = parts.pop();
  let current = value;
  for (const part of parts) current = current?.[part];
  if (current && key) delete current[key];
}

const passingRuntimeSnapshot = {
  glStats: {
    bufferShadowPeakBytes: 67108864,
    baseVertexIndexPeakBytes: 33554432,
    alignedAttribPeakBytes: 33554432,
    gpuRetireCapacity: 8,
    gpuEarlyResourceReuse: 0,
    gpuFenceDuplicateDeletes: 0,
    gpuWaitFailures: 0,
    gpuContextLosses: 0,
    gpuContextLost: false,
    gpuFenceTimeouts: 0,
    gpuRetireBacklogMax: 8,
    gpuFenceMaxAgeFrames: 120,
    gpuRetireControlledErrors: 0,
  },
  targeting: {
    updates: 1000,
    maxObservationsPerRenderedFrame: 1,
    maxObservationLagFrames: 1,
  },
  worldgen: {
    maxNetworkWaitPulses: 2,
    checkpointOnlyMaxNetworkWaitPulses: 2,
    maxTurnPulses: 64,
    maxReentrantYieldDepth: 1,
    checkpointOnlyMaxReentrantYieldDepth: 1,
    maxQueueDepth: 1,
    checkpointYields: 12,
    checkpointOnlyYields: 12,
    checkpointOnlyMaxQueueDepth: 1,
    minimumBudgetMillis: 2,
    p99SliceElapsedMillis: 14,
    maxSliceElapsedMillis: 50,
    maxBudgetOverrunMillis: 8,
    p99YieldDelayMillis: 16.7,
    maxYieldDelayMillis: 50,
    checkpointOnlyP99YieldDelayMillis: 16.7,
    checkpointOnlyMaxYieldDelayMillis: 50,
    progressSlices: 8,
    totalProgressPulses: 32,
  },
  workerQueue: {
    pendingTasks: 4,
    pollP99Millis: 0.2,
  },
  renderPipeline: {
    peakUploadDrainCount: 8,
    uploadRetryCancellations: 0,
    activeUploadRetryTasks: 8,
  },
  network: {
    outboundTurns: 100,
    maxOutboundTurnFrames: 32,
    maxOutboundTurnBytes: 262144,
    maxOutboundTurnMillis: 8,
  },
  framePacing: {
    yieldRequestCount: 1000,
    yieldCompletionCount: 999,
    pendingYieldCount: 1,
    maxPendingYieldCount: 1,
    duplicateYieldCallbackCount: 0,
  },
};
let passingRuntimeInvariants;
let missingRuntimeInvariants;
for (const testCase of fixtures.runtimeInvariantCases) {
  const snapshot = mergeFixture(passingRuntimeSnapshot, testCase.overrides);
  for (const path of testCase.omit || []) omitFixturePath(snapshot, path);
  const telemetry = summarizeRuntimeInvariantTelemetry({
    snapshots: [{...snapshot, atMillis: 1000}],
    capacity: contract.measurement.runtimeInvariantSampleCapacity,
    frameCount: 1000,
    capturedAt: 1000,
  });
  const result = evaluateRuntimeInvariants({
    contract: contract.runtimeInvariants,
    telemetry,
    worldgenTelemetryMode: testCase.worldgenTelemetryMode || null,
  });
  assert.equal(result.verdict, testCase.expectedVerdict, `${testCase.name}: verdict`);
  if (testCase.expectedComponent) {
    assert.equal(
      result.components[testCase.expectedComponent].verdict,
      testCase.expectedVerdict,
      `${testCase.name}: component verdict`,
    );
  }
  if (testCase.expectedGpuTimeoutVerdict) {
    const timeout = result.components.gpuFences.checks.find(
      (check) => check.name === "gpu-fence-timeout",
    );
    assert.equal(timeout?.verdict, testCase.expectedGpuTimeoutVerdict, `${testCase.name}: timeout`);
    assert.equal(timeout?.fatal, false, `${testCase.name}: timeout must remain non-fatal`);
  }
  if (result.verdict === "pass") passingRuntimeInvariants = result;
  if (testCase.name === "runtime-invariants-missing-required-evidence-is-inconclusive") {
    missingRuntimeInvariants = result;
  }
  completed.push(testCase.name);
}
assert.ok(passingRuntimeInvariants, "a passing runtime invariant fixture is required");
assert.ok(missingRuntimeInvariants, "a missing-evidence runtime invariant fixture is required");
assert.ok(
  passingRuntimeInvariants.externalSmokeRequired.includes("section-task-queue-smoke.mjs"),
  "section queue static evidence must name its external smoke",
);
  assert.ok(
    passingRuntimeInvariants.externalSmokeRequired.includes("frame-pacing-yield-smoke.mjs"),
    "frame pacing static evidence must name its external smoke",
  );
  assert.ok(
    passingRuntimeInvariants.externalSmokeRequired.includes(
      "browser-outbound-backpressure-smoke.mjs",
    ),
    "outbound scheduling evidence must name its external smoke",
  );
assert.equal(
  passingRuntimeInvariants.components.sectionTaskQueue.verdict,
  "external-smoke-required",
  "section queue must not claim a runtime pass",
);
assert.ok(
  passingRuntimeInvariants.telemetry.workerQueue.observedFields.includes("pendingTasks"),
  "Worker queue scalar telemetry must be summarized",
);
completed.push("runtime-invariant-external-smoke-evidence");

{
  const telemetry = summarizeRuntimeInvariantTelemetry({
    snapshots: [
      {glStats: {bufferShadowPeakBytes: 1}},
      {glStats: {bufferShadowPeakBytes: 2}},
      {glStats: {bufferShadowPeakBytes: 3}},
    ],
    capacity: 2,
    frameCount: 3,
  });
  assert.equal(telemetry.sampleCapacity, 2);
  assert.equal(telemetry.sampleCount, 2);
  assert.equal(telemetry.droppedSampleCount, 1);
  assert.equal(telemetry.glStats.maxima.bufferShadowPeakBytes, 3);
  assert.equal(Object.hasOwn(telemetry, "snapshots"), false, "summary must not retain samples");
  completed.push("runtime-invariant-sampling-is-fixed-capacity");
}

{
  const evaluation = evaluatePerformanceGates({
    profile: contract.profiles["steady-6-4"],
    environment: {valid: true, issues: []},
    frames: {
      averageFps: 120,
      onePercentLowFps: 60,
      coverageRatio: 1,
      p99FrameMs: 16.7,
      longestFrameMs: 50,
      sampleCount: 36000,
      rawFrameCount: 36000,
    },
    freezes: {total: 0, reasons: []},
    queues: {verdict: "inconclusive", reasons: ["not sampled"]},
    memory: {verdict: "not-evaluated", reasons: []},
    gameplayAuthority: {verdict: "inconclusive", reasons: []},
    stability: {verdict: "pass", reasons: []},
    runtimeInvariants: passingRuntimeInvariants,
  });
  assert.equal(evaluation.overall, "pass", "steady profile exact FPS thresholds pass");
  assert.equal(evaluation.independent.queues.verdict, "not-required");
  assert.equal(evaluation.independent.memory.verdict, "not-required");
  completed.push("steady-profile-contract-boundary");
}

{
  const evaluation = evaluatePerformanceGates({
    profile: contract.profiles["steady-6-4"],
    environment: {valid: true, issues: []},
    frames: {
      averageFps: 120,
      onePercentLowFps: 60,
      coverageRatio: 1,
      p99FrameMs: 16.7,
      longestFrameMs: 50,
      sampleCount: 36000,
      rawFrameCount: 36000,
    },
    freezes: {total: 0, reasons: []},
    queues: {verdict: "not-evaluated", reasons: []},
    memory: {verdict: "not-evaluated", reasons: []},
    gameplayAuthority: {verdict: "not-evaluated", reasons: []},
    stability: {verdict: "pass", reasons: []},
    runtimeInvariants: missingRuntimeInvariants,
  });
  assert.equal(
    evaluation.overall,
    "inconclusive",
    "strict 6/4 release profile cannot pass without required runtime evidence",
  );
  assert.equal(evaluation.independent.runtimeInvariants.verdict, "inconclusive");
  completed.push("release-profile-missing-runtime-evidence-is-inconclusive");
}

{
  const evaluation = evaluatePerformanceGates({
    profile: contract.profiles["traversal-6-4"],
    environment: {valid: true, issues: []},
    frames: {
      averageFps: 119.999,
      onePercentLowFps: 60,
      coverageRatio: 1,
      p99FrameMs: 16.7,
      longestFrameMs: 50,
      sampleCount: 36000,
      rawFrameCount: 36000,
    },
    freezes: {total: 0, reasons: []},
    queues: {verdict: "pass", reasons: []},
    memory: {verdict: "not-evaluated", reasons: []},
    gameplayAuthority: {verdict: "pass", reasons: []},
    stability: {verdict: "pass", reasons: []},
    runtimeInvariants: passingRuntimeInvariants,
  });
  assert.equal(evaluation.overall, "fail", "traversal profile rejects sub-120 average FPS");
  completed.push("traversal-profile-fps-failure");
}

{
  const evaluation = evaluatePerformanceGates({
    profile: contract.profiles["steady-6-4"],
    environment: {valid: true, issues: []},
    frames: {
      averageFps: 120,
      averageFpsRaw: 119.9996,
      onePercentLowFps: 60,
      onePercentLowFpsRaw: 59.9996,
      coverageRatio: 1,
      coverageRatioRaw: 1,
      p99FrameMs: 16.7,
      p99FrameMsRaw: 16.7,
      longestFrameMs: 50,
      longestFrameMsRaw: 50,
      sampleCount: 36000,
      rawFrameCount: 36000,
    },
    freezes: {total: 0, reasons: []},
    queues: {verdict: "not-evaluated", reasons: []},
    memory: {verdict: "not-evaluated", reasons: []},
    gameplayAuthority: {verdict: "not-evaluated", reasons: []},
    stability: {verdict: "pass", reasons: []},
    runtimeInvariants: passingRuntimeInvariants,
  });
  assert.equal(evaluation.overall, "fail", "rounded FPS values cannot cross a hard threshold");
  completed.push("raw-fps-boundary-does-not-round-to-pass");
}

{
  const evaluation = evaluatePerformanceGates({
    profile: contract.profiles["soak-sp-6-4"],
    environment: {valid: true, issues: []},
    frames: {sampleCount: 0, rawFrameCount: 0},
    freezes: {total: 0, reasons: []},
    queues: {verdict: "pass", reasons: []},
    memory: {verdict: "inconclusive", reasons: ["GC unavailable"]},
    gameplayAuthority: {verdict: "pass", reasons: []},
    stability: {verdict: "pass", reasons: []},
    runtimeInvariants: passingRuntimeInvariants,
  });
  assert.equal(evaluation.overall, "fail", "soak cannot pass without Minecraft frames");
  assert.equal(evaluation.independent.framePerformance.verdict, "fail");
  completed.push("soak-profile-zero-frame-fails");
}

{
  const evaluation = evaluatePerformanceGates({
    profile: contract.profiles["soak-sp-6-4"],
    environment: {valid: true, issues: []},
    frames: {
      averageFps: 120,
      onePercentLowFps: 60,
      coverageRatio: 1,
      p99FrameMs: 16.7,
      longestFrameMs: 50,
      sampleCount: 36000,
      rawFrameCount: 36000,
    },
    freezes: {total: 0, reasons: []},
    queues: {verdict: "pass", reasons: []},
    memory: {verdict: "not-evaluated", reasons: []},
    gameplayAuthority: {verdict: "pass", reasons: []},
    stability: {verdict: "pass", reasons: []},
    runtimeInvariants: passingRuntimeInvariants,
  });
  assert.equal(evaluation.overall, "inconclusive",
    "a required not-evaluated gate must never aggregate to pass");
  assert.equal(evaluation.independent.memory.verdict, "inconclusive");
  completed.push("required-not-evaluated-gate-is-inconclusive");
}

{
  const evaluation = evaluatePerformanceGates({
    profile: {
      gates: {decodedPacketQueue: true},
    },
    environment: {valid: true, issues: []},
    frames: {sampleCount: 1, rawFrameCount: 1},
    freezes: {total: 0, reasons: []},
    queues: {verdict: "pass", reasons: []},
    decodedPacketQueue: {verdict: "inconclusive", reasons: ["missing"]},
    memory: {verdict: "not-evaluated", reasons: []},
    gameplayAuthority: {verdict: "not-evaluated", reasons: []},
    stability: {verdict: "pass", reasons: []},
  });
  assert.equal(evaluation.overall, "inconclusive", "decoded queue evidence is an explicit gate");
  completed.push("decoded-packet-queue-explicit-gate");
}

{
  const evaluation = evaluatePerformanceGates({
    profile: contract.profiles["steady-6-4"],
    environment: {valid: true, issues: []},
    frames: {
      averageFps: 120,
      onePercentLowFps: 60,
      coverageRatio: 1,
      p99FrameMs: 16.7,
      longestFrameMs: 50,
      sampleCount: 36000,
      rawFrameCount: 36000,
    },
    freezes: {reasons: []},
    queues: {verdict: "not-evaluated", reasons: []},
    memory: {verdict: "not-evaluated", reasons: []},
    gameplayAuthority: {verdict: "not-evaluated", reasons: []},
    stability: {verdict: "pass", reasons: []},
    runtimeInvariants: passingRuntimeInvariants,
  });
  assert.equal(evaluation.overall, "inconclusive",
    "missing freeze telemetry must not be treated as zero freezes");
  assert.equal(evaluation.independent.freezes.verdict, "inconclusive");
  completed.push("missing-freeze-telemetry-is-inconclusive");
}

{
  assert.ok(
    benchmarkSource.includes("entries:new Array(longTaskCapacity)"),
    "long-task telemetry must use a bounded ring",
  );
  assert.ok(
    !benchmarkSource.includes("globalThis.__gaiusBenchmarkLongTasks=[]"),
    "long-task telemetry must not grow an unbounded array",
  );
  assert.ok(
    !benchmarkSource.includes("workerEntries.slice(0,16)"),
    "Worker lifecycle telemetry must not omit active Workers after the first sixteen",
  );
  assert.ok(
    benchmarkSource.includes("workerEntries.map"),
    "Worker lifecycle telemetry must enumerate every Worker entry",
  );
  assert.ok(
    benchmarkSource.includes("mergeMonotonicSamples(validSamples, validStabilitySamples)"),
    "concurrent timelines must be merged monotonically",
  );
  assert.ok(
    benchmarkSource.includes("sampleFor(session, frameMeasurementMillis, samples)"),
    "the single frame-ring consumer must cover the full FPS/soak duration",
  );
  assert.ok(
    !benchmarkSource.includes("sampleFor(session, heapMillis, stabilitySamples"),
    "a second soak sampler must not race the FPS frame-ring consumer",
  );
  for (const requiredEvidence of [
    "await globalThis.__gaiusFsFlush()",
    "visualViewport",
    "canvas CSS size or backing-store resolution",
    "__gaiusMinecraftEvents",
    "workerLifecycle",
    "runtime-loaded-options-and-uncapped-frame-pacing-telemetry",
    "evaluateUncappedFramePacing",
    "buildPerformanceEvidence",
    "uncappedYieldCount",
    "presentToRafCount",
    "fairYieldCount",
    "messageChannelCreateFailureCount",
    "messageChannelPostFailureCount",
    "messageChannelRebuildCount",
    "cancelledMessageTaskCount",
    "watchdogYieldCount",
    "swapInterval:null",
    "typeof frame.swapInterval==='number'",
    "browserMemoryContract.postGcFinalWindows",
    "measurementContract.frameSampleCapacity",
    "measurementContract.heartbeatIntervalMs",
    "measurementContract.runtimeInvariantSampleCapacity",
    "environmentRules.foregroundRequired",
    "__gaiusGLStats",
    "runtimeInvariants:{glStats,targeting:targetingSnapshot",
    "network:scalarSnapshot(network)",
    "collectContinuousVisualOutput",
    "cdpCommandTimeoutMillis",
    "maximumStateStallMillis",
    "isIntegratedServer",
    "aggregateLimits",
    "cleanupSourceComplete",
    "attemptedSampleTimes",
    "buildIdentity: benchmarkBuildIdentity",
    "combinedEvents()",
  ]) {
    assert.ok(
      benchmarkSource.includes(requiredEvidence),
      `benchmark source is missing required evidence hook: ${requiredEvidence}`,
    );
  }
  completed.push("benchmark-telemetry-is-bounded-and-monotonic");
}

{
  const soak = contract.profiles["soak-sp-6-4"];
  assert.equal(soak.fpsWindowMs, 300000, "soak keeps a five-minute minimum FPS window");
  assert.equal(soak.soakMs, 1800000, "stability soak must remain thirty minutes");
  assert.ok(benchmarkSource.includes("Math.max(performanceMillis, heapMillis)"),
    "soak FPS evidence must cover the full thirty-minute stability run");
  assert.equal(soak.gates.averageFpsMin, 120);
  assert.equal(soak.gates.onePercentLowFpsMin, 60);
  assert.deepEqual(contract.releaseEvidence.requiredMemoryProfiles, ["soak-sp-6-4"]);
  assert.equal(soak.gates.memory, true);
  assert.equal(soak.soakMs, contract.measurement.soakMs);
  assert.equal(contract.measurement.soakMs, 30 * 60_000);
  for (const name of ["steady-6-4", "traversal-6-4"]) {
    assert.equal(contract.profiles[name].gates.memory, false,
      `${name} may remain a five-minute FPS-only profile only with explicit soak evidence`);
  }
  assert.equal(contract.profiles["steady-6-4"].releaseEvidence, true);
  assert.equal(contract.profiles["traversal-6-4"].releaseEvidence, true);
  assert.equal(contract.profiles["steady-8-4"].releaseEvidence, false);
  assert.equal(contract.profiles["traversal-8-4"].releaseEvidence, false);
  assert.equal(contract.profiles["traversal-12-4"].releaseEvidence, false);
  assert.equal(contract.profiles["soak-mp-6-4"].driverSupported, false);
  assert.equal(contract.startup.newWorldInteractiveMsMax, 15000,
    "new-world interactive startup remains a hard 15 second target");
  for (const name of ["steady-6-4", "traversal-6-4", "soak-sp-6-4", "soak-mp-6-4"]) {
    assert.equal(contract.profiles[name].gates.runtimeInvariants, true, `${name} runtime gate`);
  }
  for (const name of ["steady-8-4", "traversal-8-4", "traversal-12-4"]) {
    assert.notEqual(contract.profiles[name].gates.runtimeInvariants, true, `${name} diagnostic gate`);
  }
  assert.deepEqual(
    contract.runtimeInvariants.sectionTaskQueue.externalSmokeRequired,
    ["section-task-queue-smoke.mjs"],
  );
  assert.deepEqual(
    contract.runtimeInvariants.framePacing.externalSmokeRequired,
    ["frame-pacing-yield-smoke.mjs"],
  );
  assert.equal(Object.hasOwn(contract.queueHighWater, "worker.pending"), false);
  completed.push("release-and-diagnostic-profile-semantics");
}

console.log(JSON.stringify({
  passed: true,
  fixture: fixturePath,
  tests: completed.length,
  completed,
}, null, 2));
