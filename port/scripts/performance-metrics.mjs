const MIB = 1024 * 1024;

function finiteNumber(value, fallback = 0) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function round(value, digits = 3) {
  if (!Number.isFinite(value)) {
    return null;
  }
  const scale = 10 ** digits;
  return Math.round(value * scale) / scale;
}

function valueAtPath(value, path) {
  let current = value;
  for (const part of path.split(".")) {
    if (current == null || typeof current !== "object") {
      return null;
    }
    current = current[part];
  }
  return Number.isFinite(Number(current)) ? Number(current) : null;
}

function hasOwnFinite(value, key) {
  return value != null
    && typeof value === "object"
    && Object.hasOwn(value, key)
    && Number.isFinite(Number(value[key]));
}

function summarizeScalarSection(snapshots, key) {
  const observedFields = new Set();
  const latest = {};
  const maxima = {};
  const minima = {};
  const anyTrue = {};
  let sampleCount = 0;
  for (const snapshot of snapshots) {
    const section = snapshot?.[key];
    if (section == null || typeof section !== "object" || Array.isArray(section)) {
      continue;
    }
    sampleCount++;
    for (const [field, rawValue] of Object.entries(section)) {
      if (typeof rawValue === "boolean") {
        observedFields.add(field);
        latest[field] = rawValue;
        anyTrue[field] = anyTrue[field] === true || rawValue;
      } else if (Number.isFinite(Number(rawValue))) {
        const value = Number(rawValue);
        observedFields.add(field);
        latest[field] = value;
        maxima[field] = Object.hasOwn(maxima, field) ? Math.max(maxima[field], value) : value;
        minima[field] = Object.hasOwn(minima, field) ? Math.min(minima[field], value) : value;
      } else if (typeof rawValue === "string" || rawValue == null) {
        observedFields.add(field);
        latest[field] = rawValue;
      }
    }
  }
  return {
    available: sampleCount > 0,
    sampleCount,
    observedFields: [...observedFields].sort(),
    latest,
    maxima,
    minima,
    anyTrue,
  };
}

/** Summarizes only scalar runtime-invariant evidence; no sampled arrays are retained. */
export function summarizeRuntimeInvariantTelemetry({
  snapshots = [],
  capacity = 4096,
  frameCount = 0,
  capturedAt = null,
} = {}) {
  const boundedCapacity = Math.max(1, Math.min(16384, Math.floor(finiteNumber(capacity, 4096))));
  const values = (Array.isArray(snapshots) ? snapshots : [])
    .filter((snapshot) => snapshot && typeof snapshot === "object")
    .slice(-boundedCapacity);
  return {
    sampleCapacity: boundedCapacity,
    sampleCount: values.length,
    droppedSampleCount: Math.max(0, (Array.isArray(snapshots) ? snapshots.length : 0) - values.length),
    frameCount: Math.max(0, Math.floor(finiteNumber(frameCount, 0))),
    capturedAt: Number.isFinite(Number(capturedAt)) ? Number(capturedAt) : null,
    glStats: summarizeScalarSection(values, "glStats"),
    targeting: summarizeScalarSection(values, "targeting"),
    worldgen: summarizeScalarSection(values, "worldgen"),
    workerQueue: summarizeScalarSection(values, "workerQueue"),
    renderPipeline: summarizeScalarSection(values, "renderPipeline"),
    network: summarizeScalarSection(values, "network"),
    framePacing: summarizeScalarSection(values, "framePacing"),
  };
}

function evidence(section, aliases, mode = "maxima") {
  const values = section?.[mode] || {};
  for (const name of aliases) {
    if (hasOwnFinite(values, name)) {
      return {available: true, value: Number(values[name]), source: `${mode}.${name}`};
    }
    if (mode === "latest" && typeof values[name] === "boolean") {
      return {available: true, value: values[name], source: `${mode}.${name}`};
    }
  }
  return {available: false, value: null, source: null};
}

function comparisonCheck(name, actual, expected, predicate, description) {
  if (!actual.available) {
    return {
      name,
      verdict: "inconclusive",
      expected,
      actual: null,
      source: null,
      reason: `required runtime telemetry is missing: ${description}`,
    };
  }
  const passed = predicate(actual.value, expected);
  return {
    name,
    verdict: passed ? "pass" : "fail",
    expected,
    actual: actual.value,
    source: actual.source,
    reason: passed ? null : `${description} was ${actual.value}; expected ${JSON.stringify(expected)}`,
  };
}

function componentResult(checks, externalSmokeRequired = []) {
  const verdicts = checks.map((check) => check.verdict);
  const verdict = verdicts.includes("fail")
    ? "fail"
    : (verdicts.includes("inconclusive") ? "inconclusive" : "pass");
  return {
    verdict,
    reasons: checks.filter((check) => check.reason).map((check) => check.reason),
    checks,
    externalSmokeRequired: [...new Set(externalSmokeRequired)],
    externalEvidenceVerdict: externalSmokeRequired.length > 0
      ? "external-smoke-required" : "not-required",
    runtimePassClaimedForExternalEvidence: false,
  };
}

/**
 * Proves that a measured run used the uncapped scheduler, rather than merely
 * carrying an Unlimited/VSync-off option in options.txt.
 */
export function evaluateUncappedFramePacing({
  samples = [],
  final = null,
  requirements = {},
} = {}) {
  const finiteTelemetryValue = (value) =>
    typeof value === "number" && Number.isFinite(value) ? value : null;
  const requiredSwapInterval = Number.isFinite(Number(requirements.requiredSwapInterval))
    ? Number(requirements.requiredSwapInterval) : 0;
  const minimumSamples = Math.max(1, Math.floor(Number(requirements.minimumSamples) || 1));
  const minimumUncappedYieldCount = Math.max(
    1,
    Math.floor(Number(requirements.minimumUncappedYieldCount) || 1),
  );
  const minimumFairYieldCount = Math.max(
    1,
    Math.floor(Number(requirements.minimumFairYieldCount) || 1),
  );
  const maximumVsyncYieldCount = Math.max(
    0,
    Math.floor(Number(requirements.maximumVsyncYieldCount) || 0),
  );
  const maximumPresentToRafCount = Math.max(
    0,
    Math.floor(Number(requirements.maximumPresentToRafCount) || 0),
  );
  const healthLimits = {
    messageChannelCreateFailureCount: Math.max(
      0,
      Math.floor(Number(requirements.maximumMessageChannelCreateFailureCount) || 0),
    ),
    messageChannelPostFailureCount: Math.max(
      0,
      Math.floor(Number(requirements.maximumMessageChannelPostFailureCount) || 0),
    ),
    messageChannelRebuildCount: Math.max(
      0,
      Math.floor(Number(requirements.maximumMessageChannelRebuildCount) || 0),
    ),
    cancelledMessageTaskCount: Math.max(
      0,
      Math.floor(Number(requirements.maximumCancelledMessageTaskCount) || 0),
    ),
    watchdogYieldCount: Math.max(
      0,
      Math.floor(Number(requirements.maximumWatchdogYieldCount) || 0),
    ),
  };
  const requiredFields = [
    "swapInterval",
    "uncappedYieldCount",
    "vsyncYieldCount",
    "presentToRafCount",
    "fairYieldCount",
    ...Object.keys(healthLimits),
  ];
  const entries = (Array.isArray(samples) ? samples : [])
    .filter((sample) => sample && typeof sample === "object")
    .map((sample) => Object.fromEntries(requiredFields.map((field) => [
      field,
      finiteTelemetryValue(sample[field]),
    ])));
  const finalEntry = final && typeof final === "object"
    ? Object.fromEntries(requiredFields.map((field) => [
      field,
      finiteTelemetryValue(final[field]),
    ]))
    : null;
  const missingFields = [...new Set([
    ...entries.flatMap((entry) => requiredFields.filter((field) => entry[field] == null)),
    ...(finalEntry ? requiredFields.filter((field) => finalEntry[field] == null) : requiredFields),
  ])];
  const allEntries = finalEntry ? [...entries, finalEntry] : entries;
  const extrema = (field, direction) => {
    const values = allEntries.map((entry) => entry[field]).filter((value) => value != null);
    if (values.length === 0) return null;
    return direction === "min" ? Math.min(...values) : Math.max(...values);
  };
  const observed = {
    swapIntervalMin: extrema("swapInterval", "min"),
    swapIntervalMax: extrema("swapInterval", "max"),
    uncappedYieldCountMax: extrema("uncappedYieldCount", "max"),
    vsyncYieldCountMax: extrema("vsyncYieldCount", "max"),
    presentToRafCountMax: extrema("presentToRafCount", "max"),
    fairYieldCountMax: extrema("fairYieldCount", "max"),
    messageChannelCreateFailureCountMax: extrema("messageChannelCreateFailureCount", "max"),
    messageChannelPostFailureCountMax: extrema("messageChannelPostFailureCount", "max"),
    messageChannelRebuildCountMax: extrema("messageChannelRebuildCount", "max"),
    cancelledMessageTaskCountMax: extrema("cancelledMessageTaskCount", "max"),
    watchdogYieldCountMax: extrema("watchdogYieldCount", "max"),
  };
  const healthChecks = Object.entries(healthLimits).map(([field, maximum]) => {
    const observedValue = observed[`${field}Max`];
    const hasNonzeroFailure = observedValue != null && observedValue > maximum;
    const missing = missingFields.includes(field);
    return {
      name: `no-${field}-during-measurement`,
      verdict: hasNonzeroFailure ? "fail" : (missing ? "inconclusive" : "pass"),
      expected: {[field]: maximum},
      actual: observedValue,
      reason: hasNonzeroFailure
        ? `${field} was ${observedValue}; expected at most ${maximum}`
        : (missing ? `${field} cannot be verified without complete runtime telemetry` : null),
    };
  });
  const checks = [
    {
      name: "measured-sample-count",
      verdict: entries.length >= minimumSamples ? "pass" : "inconclusive",
      expected: {minimumSamples},
      actual: entries.length,
      reason: entries.length >= minimumSamples
        ? null : `only ${entries.length} frame-pacing sample(s) were captured; ${minimumSamples} required`,
    },
    {
      name: "required-runtime-fields",
      verdict: missingFields.length > 0 ? "inconclusive" : "pass",
      expected: requiredFields,
      actual: requiredFields.filter((field) => !missingFields.includes(field)),
      reason: missingFields.length > 0
        ? `runtime frame-pacing telemetry is missing: ${missingFields.join(", ")}` : null,
    },
    {
      name: "final-runtime-evidence-is-consistent",
      verdict: missingFields.length > 0
        ? "inconclusive"
        : (finalEntry
          && finalEntry.swapInterval === requiredSwapInterval
          && finalEntry.uncappedYieldCount >= minimumUncappedYieldCount
          && finalEntry.vsyncYieldCount <= maximumVsyncYieldCount
          && finalEntry.presentToRafCount <= maximumPresentToRafCount
          && finalEntry.fairYieldCount >= minimumFairYieldCount
          && Object.entries(healthLimits).every(([field, maximum]) => finalEntry[field] <= maximum)
          ? "pass" : "fail"),
      expected: {
        swapInterval: requiredSwapInterval,
        minimumUncappedYieldCount,
        minimumFairYieldCount,
        maximumVsyncYieldCount,
        maximumPresentToRafCount,
        ...Object.fromEntries(Object.entries(healthLimits)
          .map(([field, maximum]) => [`maximum${field[0].toUpperCase()}${field.slice(1)}`, maximum])),
      },
      actual: finalEntry,
      reason: missingFields.length > 0
        ? "the final frame-pacing snapshot is incomplete"
        : (finalEntry
          && finalEntry.swapInterval === requiredSwapInterval
          && finalEntry.uncappedYieldCount >= minimumUncappedYieldCount
          && finalEntry.vsyncYieldCount <= maximumVsyncYieldCount
          && finalEntry.presentToRafCount <= maximumPresentToRafCount
          && finalEntry.fairYieldCount >= minimumFairYieldCount
          && Object.entries(healthLimits).every(([field, maximum]) => finalEntry[field] <= maximum)
          ? null : "the final frame-pacing snapshot does not satisfy uncapped evidence"),
    },
    {
      name: "swap-interval-zero-during-measurement",
      verdict: missingFields.length > 0
        ? "inconclusive"
        : (observed.swapIntervalMin === requiredSwapInterval
            && observed.swapIntervalMax === requiredSwapInterval ? "pass" : "fail"),
      expected: {swapInterval: requiredSwapInterval},
      actual: {min: observed.swapIntervalMin, max: observed.swapIntervalMax},
      reason: missingFields.length > 0
        ? "swapInterval cannot be verified without complete runtime telemetry"
        : (observed.swapIntervalMin === requiredSwapInterval
            && observed.swapIntervalMax === requiredSwapInterval
          ? null
          : `measured swapInterval range was ${observed.swapIntervalMin}..${observed.swapIntervalMax}`),
    },
    {
      name: "uncapped-scheduler-exercised",
      verdict: missingFields.length > 0
        ? "inconclusive"
        : (observed.uncappedYieldCountMax >= minimumUncappedYieldCount ? "pass" : "fail"),
      expected: {minimumUncappedYieldCount},
      actual: observed.uncappedYieldCountMax,
      reason: missingFields.length > 0
        ? "uncapped scheduler execution cannot be verified without complete runtime telemetry"
        : (observed.uncappedYieldCountMax >= minimumUncappedYieldCount
          ? null
          : `uncappedYieldCount was ${observed.uncappedYieldCountMax}; expected at least ${minimumUncappedYieldCount}`),
    },
    {
      name: "fair-browser-yield-exercised",
      verdict: missingFields.length > 0
        ? "inconclusive"
        : (observed.fairYieldCountMax >= minimumFairYieldCount ? "pass" : "fail"),
      expected: {minimumFairYieldCount},
      actual: observed.fairYieldCountMax,
      reason: missingFields.length > 0
        ? "fair browser yielding cannot be verified without complete runtime telemetry"
        : (observed.fairYieldCountMax >= minimumFairYieldCount
          ? null
          : `fairYieldCount was ${observed.fairYieldCountMax}; expected at least ${minimumFairYieldCount}`),
    },
    {
      name: "no-vsync-yields-during-measurement",
      verdict: missingFields.length > 0
        ? "inconclusive"
        : (observed.vsyncYieldCountMax <= maximumVsyncYieldCount ? "pass" : "fail"),
      expected: {maximumVsyncYieldCount},
      actual: observed.vsyncYieldCountMax,
      reason: missingFields.length > 0
        ? "VSync yield count cannot be verified without complete runtime telemetry"
        : (observed.vsyncYieldCountMax <= maximumVsyncYieldCount
          ? null
          : `vsyncYieldCount was ${observed.vsyncYieldCountMax}; expected at most ${maximumVsyncYieldCount}`),
    },
    ...healthChecks,
    {
      name: "no-raf-yields-during-measurement",
      verdict: missingFields.length > 0
        ? "inconclusive"
        : (observed.presentToRafCountMax <= maximumPresentToRafCount ? "pass" : "fail"),
      expected: {maximumPresentToRafCount},
      actual: observed.presentToRafCountMax,
      reason: missingFields.length > 0
        ? "rAF yield count cannot be verified without complete runtime telemetry"
        : (observed.presentToRafCountMax <= maximumPresentToRafCount
          ? null
          : `presentToRafCount was ${observed.presentToRafCountMax}; expected at most ${maximumPresentToRafCount}`),
    },
  ];
  const verdict = checks.some((check) => check.verdict === "fail")
    ? "fail"
    : (checks.some((check) => check.verdict === "inconclusive") ? "inconclusive" : "pass");
  return {
    verdict,
    reasons: checks.filter((check) => check.reason).map((check) => check.reason),
    checks,
    requiredFields,
    missingFields,
    measuredSampleCount: entries.length,
    requiredSampleCount: minimumSamples,
    observed,
    final: finalEntry,
    requirements: {
      requiredSwapInterval,
      minimumUncappedYieldCount,
      minimumFairYieldCount,
      maximumVsyncYieldCount,
      maximumPresentToRafCount,
      ...Object.fromEntries(Object.entries(healthLimits)
        .map(([field, maximum]) => [`maximum${field[0].toUpperCase()}${field.slice(1)}`, maximum])),
    },
  };
}

function metricEvidence(actual, required, direction) {
  const finiteActual = Number.isFinite(Number(actual)) ? Number(actual) : null;
  const finiteRequired = Number.isFinite(Number(required)) ? Number(required) : null;
  const passed = finiteRequired == null
    ? true
    : (finiteActual != null && (direction === "max"
        ? finiteActual <= finiteRequired : finiteActual >= finiteRequired));
  return {actual: finiteActual, required: finiteRequired, passed};
}

/** Builds a stable, report-friendly summary for both passing and failed runs. */
export function buildPerformanceEvidence({
  frames = {},
  framePacing = null,
  memory = {},
  freezes = {},
  travel = {},
  gates = {},
  buildIdentity = null,
} = {}) {
  const frame = {
    averageFps: metricEvidence(frames.averageFpsRaw ?? frames.averageFps, gates.averageFpsMin, "min"),
    onePercentLowFps: metricEvidence(
      frames.onePercentLowFpsRaw ?? frames.onePercentLowFps,
      gates.onePercentLowFpsMin,
      "min",
    ),
    p99FrameMs: metricEvidence(
      frames.p99FrameMsRaw ?? frames.p99FrameMs,
      gates.p99FrameMsMax,
      "max",
    ),
    longestFrameMs: metricEvidence(
      frames.longestFrameMsRaw ?? frames.longestFrameMs,
      gates.longestFrameMsMax,
      "max",
    ),
    coverageRatio: metricEvidence(
      frames.coverageRatioRaw ?? frames.coverageRatio,
      gates.coverageRatioMin,
      "min",
    ),
    sampleCount: Number.isFinite(Number(frames.sampleCount)) ? Number(frames.sampleCount) : null,
  };
  const heap = memory.v8Heap || {};
  const heapLeakSignal = heap.finalThreeWindowsPositive === true
    || (Number.isFinite(Number(heap.retainedGrowthPercent))
      && Number(heap.retainedGrowthPercent) > Number(heap.thresholds?.retainedGrowthPercentMax ?? 15))
    || (Number.isFinite(Number(heap.retainedGrowthBytes))
      && Number(heap.retainedGrowthBytes) > Number(heap.thresholds?.retainedGrowthBytesMax ?? 256 * MIB));
  const heapSlope = Number.isFinite(Number(heap.postGcSlopeMiBPerMinute))
    ? Number(heap.postGcSlopeMiBPerMinute) : null;
  const heapPlateau = !heapLeakSignal
    && heapSlope != null
    && Math.abs(heapSlope) <= Number(heap.thresholds?.plateauSlopeMiBPerMinuteMax ?? 1);
  const memoryEvidence = {
    verdict: memory.verdict || "not-evaluated",
    reasons: Array.isArray(memory.reasons) ? memory.reasons : [],
    heap: {
      verdict: heap.verdict || "not-evaluated",
      trend: heapLeakSignal ? "leak-signal" : (heapPlateau ? "plateau" : "non-plateau"),
      leakSignal: heapLeakSignal,
      plateau: heapPlateau,
      regularSlopeMiBPerMinute: heap.regularSlopeMiBPerMinute ?? null,
      postGcSlopeMiBPerMinute: heap.postGcSlopeMiBPerMinute ?? null,
      retainedGrowthPercent: heap.retainedGrowthPercent ?? null,
      retainedGrowthMiB: heap.retainedGrowthMiB ?? null,
      peakUsedMiB: heap.peakUsedMiB ?? null,
      finalThreeWindowsPositive: heap.finalThreeWindowsPositive ?? null,
      coverage: heap.coverage || null,
    },
    browserMemory: memory.browserMemory
      ? {verdict: memory.browserMemory.verdict, reasons: memory.browserMemory.reasons || [],
        metrics: memory.browserMemory.metrics || null}
      : null,
    processRss: memory.processRss
      ? {verdict: memory.processRss.verdict, reasons: memory.processRss.reasons || [],
        total: memory.processRss.total || null, byType: memory.processRss.byType || null}
      : null,
  };
  const stall = {
    longestFrameMs: metricEvidence(
      frames.longestFrameMsRaw ?? frames.longestFrameMs,
      gates.longestFrameMsMax,
      "max",
    ),
    freezeCount: metricEvidence(freezes.total, gates.freezeCountMax, "max"),
    maximumTraversalStallMs: metricEvidence(
      travel.maximumTraversalStallMillis,
      travel.maximumAllowedTraversalStallMillis,
      "max",
    ),
    reasons: Array.isArray(freezes.reasons) ? freezes.reasons : [],
  };
  const failureReasons = [
    ...Object.entries(frame)
      .filter(([, value]) => value && value.passed === false)
      .map(([name, value]) => `${name} actual=${value.actual} required=${value.required}`),
    ...(framePacing?.reasons || []),
    ...(memoryEvidence.reasons || []),
    ...(stall.reasons || []),
  ];
  return {
    buildIdentity,
    frame,
    framePacing,
    stall,
    memory: memoryEvidence,
    failureReasons: [...new Set(failureReasons)],
  };
}

/** Evaluates runtime invariants without treating absent instrumentation as a pass. */
export function evaluateRuntimeInvariants({contract = {}, telemetry = {}} = {}) {
  const targetingRules = contract.targeting || {};
  const worldgenRules = contract.worldgen || {};
  const webglRules = contract.webglMemory || {};
  const fenceRules = contract.gpuFences || {};
  const frameRules = contract.framePacing || {};
  const renderPipelineRules = contract.renderPipeline || {};
  const networkRules = contract.networkOutbound || {};
  const targeting = telemetry.targeting || {};
  const worldgen = telemetry.worldgen || {};
  const glStats = telemetry.glStats || {};
  const framePacing = telemetry.framePacing || {};
  const renderPipeline = telemetry.renderPipeline || {};
  const network = telemetry.network || {};

  const targetingChecks = [
    comparisonCheck(
      "raycasts-per-rendered-frame",
      evidence(targeting, [
        "maxRaycastsPerRenderedFrame",
        "raycastsPerRenderedFrameMax",
        "maxObservationsPerRenderedFrame",
        "observationsPerRenderedFrameMax",
      ]),
      {maximum: finiteNumber(targetingRules.raycastsPerRenderedFrameMax, 1)},
      (value, expected) => value <= expected.maximum,
      "maximum raycasts/observations in one rendered frame",
    ),
    comparisonCheck(
      "target-observation-lag",
      evidence(targeting, [
        "maxObservationLagFrames",
        "maximumObservationLagFrames",
      ]),
      {maximumFrames: finiteNumber(targetingRules.maximumObservationLagFrames, 1)},
      (value, expected) => value <= expected.maximumFrames,
      "maximum targeting observation lag",
    ),
  ];
  const worldgenChecks = [
    comparisonCheck(
      "worldgen-network-wait-pulses",
      evidence(worldgen, ["maxNetworkWaitPulses"]),
      {maximum: finiteNumber(worldgenRules.networkWaitPulsesMax, 2)},
      (value, expected) => value <= expected.maximum,
      "maximum worldgen network wait pulses",
    ),
    comparisonCheck(
      "worldgen-turn-pulses",
      evidence(worldgen, ["maxTurnPulses", "maxPulsesInTurn", "maxProgressPulsesPerTurn"]),
      {maximum: finiteNumber(worldgenRules.schedulerTurnPulsesMax, 64)},
      (value, expected) => value <= expected.maximum,
      "maximum worldgen scheduler pulses in one turn",
    ),
    comparisonCheck(
      "worldgen-reentrant-depth",
      evidence(worldgen, ["maxReentrantYieldDepth", "reentrantYieldDepthMax"]),
      {maximum: finiteNumber(worldgenRules.reentrantYieldDepthMax, 1)},
      (value, expected) => value <= expected.maximum,
      "maximum worldgen reentrant yield depth",
    ),
  ];
  if (worldgenRules.minimumAdaptiveSliceMillis != null) {
    worldgenChecks.push(comparisonCheck(
      "worldgen-adaptive-slice-floor",
      evidence(worldgen, ["minimumBudgetMillis", "minBudgetMillis"], "minima"),
      {minimum: Number(worldgenRules.minimumAdaptiveSliceMillis)},
      (value, expected) => value >= expected.minimum,
      "minimum adaptive worldgen slice budget",
    ));
  }
  worldgenChecks.push(
    comparisonCheck(
      "worldgen-slice-p99",
      evidence(worldgen, ["p99SliceElapsedMillis"]),
      {maximumMillis: finiteNumber(worldgenRules.p99SliceElapsedMillisMax, 14)},
      (value, expected) => value <= expected.maximumMillis,
      "worldgen slice p99 latency",
    ),
    comparisonCheck(
      "worldgen-slice-maximum",
      evidence(worldgen, ["maxSliceElapsedMillis"]),
      {maximumMillis: finiteNumber(worldgenRules.maxSliceElapsedMillisMax, 50)},
      (value, expected) => value <= expected.maximumMillis,
      "maximum worldgen slice latency",
    ),
    comparisonCheck(
      "worldgen-budget-overrun-maximum",
      evidence(worldgen, ["maxBudgetOverrunMillis"]),
      {maximumMillis: finiteNumber(worldgenRules.maxBudgetOverrunMillisMax, 8)},
      (value, expected) => value <= expected.maximumMillis,
      "maximum worldgen slice budget overrun",
    ),
    comparisonCheck(
      "worldgen-yield-delay-p99",
      evidence(worldgen, ["p99YieldDelayMillis"]),
      {maximumMillis: finiteNumber(worldgenRules.p99YieldDelayMillisMax, 16.7)},
      (value, expected) => value <= expected.maximumMillis,
      "worldgen event-loop yield p99 latency",
    ),
    comparisonCheck(
      "worldgen-yield-delay-maximum",
      evidence(worldgen, ["maxYieldDelayMillis"]),
      {maximumMillis: finiteNumber(worldgenRules.maxYieldDelayMillisMax, 50)},
      (value, expected) => value <= expected.maximumMillis,
      "maximum worldgen event-loop yield latency",
    ),
  );

  const webglChecks = [
    comparisonCheck(
      "webgl-buffer-shadow-budget",
      evidence(glStats, ["bufferShadowPeakBytes"]),
      {maximumBytes: finiteNumber(webglRules.bufferShadowBudgetBytes, 64 * MIB)},
      (value, expected) => value <= expected.maximumBytes,
      "peak WebGL buffer shadow bytes",
    ),
    comparisonCheck(
      "webgl-derived-index-budget",
      evidence(glStats, ["baseVertexIndexPeakBytes", "derivedBaseVertexPeakBytes"]),
      {maximumBytes: finiteNumber(webglRules.derivedBaseVertexBudgetBytes, 32 * MIB)},
      (value, expected) => value <= expected.maximumBytes,
      "peak derived base-vertex index bytes",
    ),
    comparisonCheck(
      "webgl-derived-attribute-budget",
      evidence(glStats, ["alignedAttribPeakBytes"]),
      {maximumBytes: finiteNumber(webglRules.derivedAlignedAttribBudgetBytes, 32 * MIB)},
      (value, expected) => value <= expected.maximumBytes,
      "peak derived aligned-attribute bytes",
    ),
  ];

  const renderPipelineChecks = [
    comparisonCheck(
      "terrain-upload-hard-frame-cap",
      evidence(renderPipeline, ["peakUploadDrainCount"]),
      {maximum: finiteNumber(renderPipelineRules.uploadAllocationsPerFrameMax, 8)},
      (value, expected) => value <= expected.maximum,
      "peak terrain upload allocations in one rendered frame",
    ),
    comparisonCheck(
      "terrain-upload-retry-cancellations",
      evidence(renderPipeline, ["uploadRetryCancellations"]),
      {maximum: finiteNumber(renderPipelineRules.uploadRetryCancellationsMax, 0)},
      (value, expected) => value <= expected.maximum,
      "terrain uploads cancelled after exhausting their bounded retry window",
    ),
    comparisonCheck(
      "terrain-upload-active-retry-bound",
      evidence(renderPipeline, ["activeUploadRetryTasks"]),
      {maximum: finiteNumber(renderPipelineRules.activeUploadRetryTasksMax, 8)},
      (value, expected) => value <= expected.maximum,
      "concurrently retained terrain upload retry tasks",
    ),
  ];

  const networkChecks = [
    comparisonCheck(
      "network-outbound-scheduler-exercised",
      evidence(network, ["outboundTurns"]),
      {minimum: finiteNumber(networkRules.turnsMin, 1)},
      (value, expected) => value >= expected.minimum,
      "outbound scheduler turns",
    ),
    comparisonCheck(
      "network-outbound-frame-turn-cap",
      evidence(network, ["maxOutboundTurnFrames"]),
      {maximum: finiteNumber(networkRules.framesPerTurnMax, 32)},
      (value, expected) => value <= expected.maximum,
      "maximum outbound frames in one event-loop turn",
    ),
    comparisonCheck(
      "network-outbound-byte-turn-cap",
      evidence(network, ["maxOutboundTurnBytes"]),
      {maximumBytes: finiteNumber(networkRules.bytesPerTurnMax, 256 * 1024)},
      (value, expected) => value <= expected.maximumBytes,
      "maximum outbound bytes in one event-loop turn",
    ),
    comparisonCheck(
      "network-outbound-time-turn-cap",
      evidence(network, ["maxOutboundTurnMillis"]),
      {maximumMillis: finiteNumber(networkRules.millisPerTurnMax, 8)},
      (value, expected) => value <= expected.maximumMillis,
      "maximum outbound scheduler turn duration",
    ),
  ];

  const timeoutEvidence = evidence(glStats, ["gpuFenceTimeouts"]);
  const contextLossCounter = evidence(glStats, ["gpuContextLosses"]);
  const contextLossEvidence = glStats?.anyTrue?.gpuContextLost === true
    ? {
        available: true,
        value: Math.max(1, finiteNumber(contextLossCounter.value, 0)),
        source: "anyTrue.gpuContextLost",
      }
    : contextLossCounter;
  const gpuChecks = [
    comparisonCheck(
      "gpu-early-resource-reuse",
      evidence(glStats, ["gpuEarlyResourceReuse", "gpuEarlyResourceReuses"]),
      {maximum: finiteNumber(fenceRules.earlyResourceReuseMax, 0)},
      (value, expected) => value <= expected.maximum,
      "GPU resource reuse before its fence signaled",
    ),
    comparisonCheck(
      "gpu-duplicate-fence-delete",
      evidence(glStats, ["gpuFenceDuplicateDeletes"]),
      {maximum: finiteNumber(fenceRules.duplicateDeletesMax, 0)},
      (value, expected) => value <= expected.maximum,
      "duplicate GPU fence deletes",
    ),
    comparisonCheck(
      "gpu-wait-failure",
      evidence(glStats, ["gpuWaitFailures"]),
      {maximum: finiteNumber(fenceRules.waitFailuresMax, 0)},
      (value, expected) => value <= expected.maximum,
      "GPU fence wait failures",
    ),
    comparisonCheck(
      "gpu-context-loss",
      contextLossEvidence,
      {maximum: finiteNumber(fenceRules.contextLossesMax, 0)},
      (value, expected) => value <= expected.maximum,
      "WebGL context losses observed by GPU retirement",
    ),
    comparisonCheck(
      "gpu-retire-backlog",
      evidence(glStats, ["gpuRetireBacklogMax"]),
      {maximum: finiteNumber(fenceRules.retireBacklogMax, 8)},
      (value, expected) => value <= expected.maximum,
      "maximum GPU retirement backlog",
    ),
    comparisonCheck(
      "gpu-fence-age",
      evidence(glStats, ["gpuFenceMaxAgeFrames"]),
      {maximumFrames: finiteNumber(fenceRules.fenceAgeFramesMax, 120)},
      (value, expected) => value <= expected.maximumFrames,
      "maximum GPU fence age",
    ),
    comparisonCheck(
      "gpu-retire-controlled-error",
      evidence(glStats, ["gpuRetireControlledErrors"]),
      {maximum: finiteNumber(fenceRules.controlledErrorsMax, 0)},
      (value, expected) => value <= expected.maximum,
      "controlled GPU retirement errors",
    ),
  ];
  const timeoutCheck = timeoutEvidence.available ? {
    name: "gpu-fence-timeout",
    verdict: fenceRules.timeoutIsFatal === true && timeoutEvidence.value > 0 ? "fail" : "pass",
    expected: {fatal: fenceRules.timeoutIsFatal === true},
    actual: timeoutEvidence.value,
    source: timeoutEvidence.source,
    fatal: fenceRules.timeoutIsFatal === true,
    reason: fenceRules.timeoutIsFatal === true && timeoutEvidence.value > 0
      ? "GPU fence timeout was configured as fatal"
      : null,
  } : {
    name: "gpu-fence-timeout",
    verdict: "inconclusive",
    expected: {fatal: fenceRules.timeoutIsFatal === true},
    actual: null,
    source: null,
    fatal: fenceRules.timeoutIsFatal === true,
    reason: "GPU fence lifecycle telemetry is unavailable",
  };
  gpuChecks.push(timeoutCheck);

  const requests = evidence(framePacing, ["yieldRequestCount"], "latest");
  const completions = evidence(framePacing, ["yieldCompletionCount"], "latest");
  const pendingTelemetry = evidence(framePacing, ["pendingYieldCount"], "latest");
  const maxPending = evidence(framePacing, ["maxPendingYieldCount"], "maxima");
  const duplicateCallbacks = evidence(framePacing, ["duplicateYieldCallbackCount"]);
  let continuationCheck;
  if (!requests.available || !completions.available || !pendingTelemetry.available
      || !maxPending.available || !duplicateCallbacks.available) {
    continuationCheck = {
      name: "frame-continuation-exact-once",
      verdict: "inconclusive",
      expected: {
        minimumPerRequest: 1,
        maximumPerRequest: 1,
        pendingAtSnapshotMax: finiteNumber(frameRules.pendingYieldCountMax, 1),
        historicalPendingMax: finiteNumber(frameRules.pendingYieldCountMax, 1),
        duplicateCallbacksMax: finiteNumber(frameRules.duplicateYieldCallbackCountMax, 0),
      },
      actual: null,
      source: null,
      reason: "frame continuation request/completion telemetry is missing",
    };
  } else {
    const computedPending = requests.value - completions.value;
    const pendingLimit = finiteNumber(frameRules.pendingYieldCountMax, 1);
    const duplicateLimit = finiteNumber(frameRules.duplicateYieldCallbackCountMax, 0);
    const passed = requests.value > 0 && completions.value <= requests.value
      && computedPending >= 0
      && pendingTelemetry.value === computedPending
      && pendingTelemetry.value <= pendingLimit
      && maxPending.value <= pendingLimit
      && duplicateCallbacks.value <= duplicateLimit;
    continuationCheck = {
      name: "frame-continuation-exact-once",
      verdict: passed ? "pass" : "fail",
      expected: {
        minimumPerRequest: finiteNumber(frameRules.continuationResumeCountPerRequestMin, 1),
        maximumPerRequest: finiteNumber(frameRules.continuationResumeCountPerRequestMax, 1),
        pendingAtSnapshotMax: pendingLimit,
        historicalPendingMax: pendingLimit,
        duplicateCallbacksMax: duplicateLimit,
      },
      actual: {
        requests: requests.value,
        completions: completions.value,
        computedPending,
        pending: pendingTelemetry.value,
        maxPending: maxPending.value,
        duplicateCallbacks: duplicateCallbacks.value,
      },
      source: [
        requests.source,
        completions.source,
        pendingTelemetry.source,
        maxPending.source,
        duplicateCallbacks.source,
      ].join(","),
      reason: passed ? null : "frame continuation requests did not complete exactly once",
    };
  }

  const components = {
    targeting: componentResult(
      targetingChecks,
      targetingRules.externalSmokeRequired || [],
    ),
    worldgen: componentResult(worldgenChecks),
    webglMemory: componentResult(
      webglChecks,
      webglRules.externalSmokeRequired || [],
    ),
    renderPipeline: componentResult(
      renderPipelineChecks,
      renderPipelineRules.externalSmokeRequired || [],
    ),
    networkOutbound: componentResult(
      networkChecks,
      networkRules.externalSmokeRequired || [],
    ),
    gpuFences: componentResult(gpuChecks),
    framePacing: componentResult(
      [continuationCheck],
      frameRules.externalSmokeRequired || [],
    ),
    sectionTaskQueue: componentResult(
      [],
      contract.sectionTaskQueue?.externalSmokeRequired || [],
    ),
  };
  components.sectionTaskQueue.verdict = "external-smoke-required";
  const runtimeComponents = [
    components.targeting,
    components.worldgen,
    components.webglMemory,
    components.renderPipeline,
    components.networkOutbound,
    components.gpuFences,
    components.framePacing,
  ];
  const verdict = runtimeComponents.some((component) => component.verdict === "fail")
    ? "fail"
    : (runtimeComponents.some((component) => component.verdict === "inconclusive")
        ? "inconclusive" : "pass");
  const externalSmokeRequired = [...new Set(Object.values(components)
    .flatMap((component) => component.externalSmokeRequired || []))];
  return {
    verdict,
    reasons: runtimeComponents.flatMap((component) => component.reasons),
    components,
    externalSmokeRequired,
    externalEvidenceVerdict: externalSmokeRequired.length > 0
      ? "external-smoke-required" : "not-required",
    telemetry,
  };
}

function mergeSampleValues(left, right) {
  if (left == null) return right;
  if (right == null) return left;
  if (Array.isArray(left) && Array.isArray(right)) return left.concat(right);
  if (typeof left !== "object" || typeof right !== "object"
      || ArrayBuffer.isView(left) || ArrayBuffer.isView(right)) {
    return right;
  }
  const merged = {...left};
  for (const [key, value] of Object.entries(right)) {
    merged[key] = Object.hasOwn(merged, key)
      ? mergeSampleValues(merged[key], value)
      : value;
  }
  return merged;
}

export function mergeMonotonicSamples(...groups) {
  const ordered = groups
    .flatMap((group) => Array.isArray(group) ? group : [])
    .filter((sample) => Number.isFinite(Number(sample?.at)))
    .sort((left, right) => Number(left.at) - Number(right.at));
  const merged = [];
  for (const sample of ordered) {
    const at = Number(sample.at);
    const previous = merged.at(-1);
    if (previous && Number(previous.at) === at) {
      merged[merged.length - 1] = mergeSampleValues(previous, sample);
    } else {
      merged.push(sample);
    }
  }
  return merged;
}

export function nearestRankPercentile(values, fraction) {
  if (!Array.isArray(values) || values.length === 0) {
    return null;
  }
  const ordered = values
    .map((value) => Number(value))
    .filter((value) => Number.isFinite(value))
    .sort((left, right) => left - right);
  if (ordered.length === 0) {
    return null;
  }
  const bounded = Math.max(0, Math.min(1, Number(fraction)));
  const index = Math.max(0, Math.ceil(ordered.length * bounded) - 1);
  return ordered[Math.min(ordered.length - 1, index)];
}

export function summarizeFrameTimes(frameTimes, elapsedMs, freezeThresholdMs = 500) {
  const rawSamples = Array.isArray(frameTimes) ? frameTimes : [];
  const samples = rawSamples
    .map((value) => Number(value))
    .filter((value) => Number.isFinite(value) && value > 0);
  const ordered = samples.slice().sort((left, right) => left - right);
  const sampleCount = ordered.length;
  const slowestCount = sampleCount === 0 ? 0 : Math.max(1, Math.ceil(sampleCount * 0.01));
  const slowest = slowestCount === 0 ? [] : ordered.slice(sampleCount - slowestCount);
  const slowestMeanMs = slowest.length === 0
    ? null
    : slowest.reduce((sum, value) => sum + value, 0) / slowest.length;
  const frameTimeSumMs = ordered.reduce((sum, value) => sum + value, 0);
  const measuredElapsedMs = finiteNumber(elapsedMs, frameTimeSumMs);
  const coverageRatioRaw = measuredElapsedMs > 0 ? frameTimeSumMs / measuredElapsedMs : null;
  // Average complete frame intervals. The independent coverage gate accounts
  // for the partial interval at each measurement boundary and for missing time.
  const averageFpsRaw = frameTimeSumMs > 0 ? sampleCount * 1000 / frameTimeSumMs : null;
  const onePercentLowFpsRaw = slowestMeanMs > 0 ? 1000 / slowestMeanMs : null;
  const p99FrameMsRaw = nearestRankPercentile(ordered, 0.99);
  const longestFrameMsRaw = sampleCount > 0 ? ordered[sampleCount - 1] : null;
  const countAtLeast = (threshold) => ordered.length - lowerBound(ordered, threshold);

  return {
    sampleCount,
    invalidFrameIntervalCount: rawSamples.length - sampleCount,
    elapsedMs: round(measuredElapsedMs),
    frameTimeSumMs: round(frameTimeSumMs),
    coverageRatio: round(coverageRatioRaw, 6),
    coverageRatioRaw,
    averageFps: round(averageFpsRaw),
    averageFpsRaw,
    onePercentLowFps: round(onePercentLowFpsRaw),
    onePercentLowFpsRaw,
    onePercentSlowestFrameCount: slowestCount,
    onePercentSlowestMeanFrameMs: round(slowestMeanMs),
    p50FrameMs: round(nearestRankPercentile(ordered, 0.50)),
    p95FrameMs: round(nearestRankPercentile(ordered, 0.95)),
    p99FrameMs: round(p99FrameMsRaw),
    p99FrameMsRaw,
    longestFrameMs: round(longestFrameMsRaw),
    longestFrameMsRaw,
    longFrames: {
      atLeast50Ms: countAtLeast(50),
      atLeast100Ms: countAtLeast(100),
      atLeast150Ms: countAtLeast(150),
      atLeast200Ms: countAtLeast(200),
      atLeast250Ms: countAtLeast(250),
      atLeast500Ms: countAtLeast(freezeThresholdMs),
    },
  };
}

function lowerBound(ordered, target) {
  let low = 0;
  let high = ordered.length;
  while (low < high) {
    const middle = (low + high) >>> 1;
    if (ordered[middle] < target) {
      low = middle + 1;
    } else {
      high = middle;
    }
  }
  return low;
}

export function recoverFrameRingDelta(snapshot, previousFrameCount = 0) {
  const samples = Array.from(snapshot?.frameTimes || [], (value) => Number(value));
  const capacity = Math.max(0, Math.min(
    samples.length,
    Math.floor(finiteNumber(snapshot?.sampleCapacity, samples.length)),
  ));
  const totalFrameCount = Math.max(0, Math.floor(finiteNumber(snapshot?.frameCount, 0)));
  const previous = Math.max(0, Math.floor(finiteNumber(previousFrameCount, 0)));
  const delta = Math.max(0, totalFrameCount - previous);
  const sampleCount = Math.max(0, Math.min(
    capacity,
    Math.floor(finiteNumber(snapshot?.sampleCount, 0)),
  ));
  const available = Math.min(delta, sampleCount, capacity);
  const writeIndex = capacity === 0
    ? 0
    : ((Math.floor(finiteNumber(snapshot?.sampleWriteIndex, 0)) % capacity) + capacity) % capacity;
  const start = capacity === 0 ? 0 : (writeIndex - available + capacity) % capacity;
  const recovered = [];
  for (let index = 0; index < available; index++) {
    recovered.push(samples[(start + index) % capacity]);
  }
  const lostSamples = Math.max(0, delta - available);
  return {
    samples: recovered,
    totalFrameCount,
    deltaFrameCount: delta,
    lostSamples,
    wrapped: available > 0 && start + available > capacity,
  };
}

export function diffHistogram(current, baseline) {
  const currentValues = Array.from(current || [], (value) => Math.max(0, Number(value) || 0));
  const baselineValues = Array.from(baseline || [], (value) => Math.max(0, Number(value) || 0));
  const length = Math.max(currentValues.length, baselineValues.length);
  const result = new Array(length);
  for (let index = 0; index < length; index++) {
    result[index] = Math.max(0, (currentValues[index] || 0) - (baselineValues[index] || 0));
  }
  return result;
}

export function sparseHistogram(histogram) {
  const result = [];
  Array.from(histogram || []).forEach((count, bucket) => {
    const numeric = Number(count) || 0;
    if (numeric > 0) {
      result.push([bucket, numeric]);
    }
  });
  return result;
}

export function summarizeQuarterMillisecondHistogram(histogram) {
  const values = Array.from(histogram || [], (value) => Math.max(0, Number(value) || 0));
  const count = values.reduce((sum, value) => sum + value, 0);
  if (count === 0) {
    return {count: 0, p50Ms: null, p95Ms: null, p99Ms: null};
  }
  const percentile = (fraction) => {
    const rank = Math.max(1, Math.ceil(count * fraction));
    let seen = 0;
    for (let bucket = 0; bucket < values.length; bucket++) {
      seen += values[bucket];
      if (seen >= rank) {
        return round((bucket + 1) / 4);
      }
    }
    return round(values.length / 4);
  };
  return {
    count,
    p50Ms: percentile(0.50),
    p95Ms: percentile(0.95),
    p99Ms: percentile(0.99),
  };
}

export function summarizeScalarSamples(values) {
  const samples = (Array.isArray(values) ? values : [])
    .map((value) => Number(value))
    .filter((value) => Number.isFinite(value));
  if (samples.length === 0) {
    return {
      count: 0,
      p50: null,
      p95: null,
      p99: null,
      max: null,
      mean: null,
      p50Raw: null,
      p95Raw: null,
      p99Raw: null,
      maxRaw: null,
      meanRaw: null,
    };
  }
  const p50Raw = nearestRankPercentile(samples, 0.50);
  const p95Raw = nearestRankPercentile(samples, 0.95);
  const p99Raw = nearestRankPercentile(samples, 0.99);
  const maxRaw = Math.max(...samples);
  const meanRaw = samples.reduce((sum, value) => sum + value, 0) / samples.length;
  return {
    count: samples.length,
    p50: round(p50Raw),
    p95: round(p95Raw),
    p99: round(p99Raw),
    max: round(maxRaw),
    mean: round(meanRaw),
    p50Raw,
    p95Raw,
    p99Raw,
    maxRaw,
    meanRaw,
  };
}

export function evaluateGameplayAuthority({evidence, contract = {}} = {}) {
  const reasons = [];
  const inconclusive = (extraReasons = []) => ({
    verdict: "inconclusive",
    reasons: [...reasons, ...extraReasons],
  });
  if (!evidence || typeof evidence !== "object" || Array.isArray(evidence)) {
    return inconclusive(["real browser gameplayAuthority telemetry is unavailable"]);
  }
  const requiredSource = String(contract.source || "browser-transport-and-client-state");
  const requiredRoute = String(contract.route || "singleplayer-worker");
  if (evidence.source !== requiredSource) {
    reasons.push("the evidence source is not the required live browser transport observer");
  }
  if (evidence.route !== requiredRoute) {
    reasons.push("the measured route is not the singleplayer Worker route");
  }
  if (evidence.installed !== true) {
    reasons.push("the gameplay authority probe was not installed in the page");
  }
  if (!Array.isArray(evidence.workerSessionIds) || evidence.workerSessionIds.length === 0
      || !Array.isArray(evidence.channelIds) || evidence.channelIds.length === 0) {
    reasons.push("a live singleplayer Worker and local transport channel were not identified");
  }
  if (!hasOwnFinite(evidence, "measurementStartedAt")
      || !hasOwnFinite(evidence, "measurementEndedAt")
      || Number(evidence.measurementEndedAt) < Number(evidence.measurementStartedAt)) {
    reasons.push("the gameplay measurement window has no valid monotonic timestamps");
  }
  const requiredProtocolVersion = finiteNumber(contract.protocolVersion, NaN);
  if (!Number.isFinite(requiredProtocolVersion)
      || !hasOwnFinite(evidence, "protocolVersion")
      || Number(evidence.protocolVersion) !== requiredProtocolVersion) {
    reasons.push("the measured packet protocol version is unavailable or mismatched");
  }
  const requiredPacketIds = contract.packetIds;
  if (!requiredPacketIds || typeof requiredPacketIds !== "object"
      || !evidence.packetIds || typeof evidence.packetIds !== "object"
      || Object.entries(requiredPacketIds).some(([name, id]) => (
        !hasOwnFinite(evidence.packetIds, name)
        || Number(evidence.packetIds[name]) !== Number(id)
      ))) {
    reasons.push("the measured packet id table is unavailable or mismatched");
  }
  const transportFields = [
    "outboundTransportCalls",
    "inboundTransportCalls",
    "outboundTransportBytes",
    "inboundTransportBytes",
    "parsedOutboundPackets",
    "parsedInboundPackets",
  ];
  if (evidence.transportObserved !== true
      || transportFields.some((field) => !hasOwnFinite(evidence, field)
        || Number(evidence[field]) <= 0)) {
    reasons.push("client-to-Worker and Worker-to-client transport activity was not observed");
  }
  if (!hasOwnFinite(evidence, "parserFailures")
      || !hasOwnFinite(evidence, "compressedFrames")
      || Number(evidence.parserFailures) < 0
      || Number(evidence.compressedFrames) < 0) {
    reasons.push("packet parser health counters are unavailable");
  } else if (Number(evidence.parserFailures) > 0) {
    reasons.push(`the packet parser reported ${Number(evidence.parserFailures)} framing failures`);
  }
  if (!Array.isArray(evidence.actions)) {
    reasons.push("per-action emission and confirmation timestamps are unavailable");
  }
  if (reasons.length > 0) return inconclusive();

  const minimumSamples = Math.max(1, Math.floor(finiteNumber(contract.minimumSamplesPerOperation, 1)));
  const maxP95Millis = finiteNumber(contract.maxP95Millis, NaN);
  const maxP99Millis = finiteNumber(contract.maxP99Millis, NaN);
  const requireActiveWorkloadEvidence = contract.requireActiveWorkloadEvidence === true;
  if (!Number.isFinite(maxP95Millis) || !Number.isFinite(maxP99Millis)) {
    return inconclusive(["gameplay authority latency thresholds are missing from the contract"]);
  }
  const operation = (name, latencyField, transportLatencyField) => {
    const values = evidence[latencyField];
    const transportValues = evidence[transportLatencyField];
    const emittedField = `${name}EmittedCount`;
    const confirmationField = `${name}ConfirmationCount`;
    const transportConfirmationField = `${name}TransportConfirmationCount`;
    const transitionField = `${name}StateTransitionCount`;
    const operationActions = evidence.actions.filter((action) => action?.type === name);
    const confirmedActions = operationActions.filter((action) => action?.confirmedAt != null);
    const derivedValues = confirmedActions.map((action) => (
      Number(action.confirmedAt) - Number(action.emittedAt)
    ));
    const derivedTransportValues = confirmedActions.map((action) => (
      Number(action.transportConfirmationAt) - Number(action.emittedAt)
    ));
    const count = Array.isArray(values) ? values.length : 0;
    const emitted = hasOwnFinite(evidence, emittedField) ? Number(evidence[emittedField]) : null;
    const confirmations = hasOwnFinite(evidence, confirmationField)
      ? Number(evidence[confirmationField]) : null;
    const transportConfirmations = hasOwnFinite(evidence, transportConfirmationField)
      ? Number(evidence[transportConfirmationField]) : null;
    const transitions = hasOwnFinite(evidence, transitionField) ? Number(evidence[transitionField]) : null;
    const latency = summarizeScalarSamples(values);
    const transportLatency = summarizeScalarSamples(transportValues);
    const invalidActions = confirmedActions.some((action) => (
      !hasOwnFinite(action, "emittedAt")
      || !hasOwnFinite(action, "transportConfirmationAt")
      || !hasOwnFinite(action, "authoritativeStateAt")
      || !hasOwnFinite(action, "stateTransitionAt")
      || !hasOwnFinite(action, "confirmedAt")
      || !hasOwnFinite(action, "emittedSequence")
      || !hasOwnFinite(action, "emittedPacketId")
      || typeof action.confirmationKind !== "string"
      || (requireActiveWorkloadEvidence
        && (action.workloadActiveAtArm !== true || action.workloadActiveAtEmission !== true))
      || Number(action.transportConfirmationAt) < Number(action.emittedAt)
      || Number(action.authoritativeStateAt) < Number(action.emittedAt)
      || Number(action.stateTransitionAt) < Number(action.transportConfirmationAt)
      || Number(action.stateTransitionAt) < Number(action.authoritativeStateAt)
      || Number(action.confirmedAt) !== Math.max(
        Number(action.transportConfirmationAt),
        Number(action.stateTransitionAt),
      )
    ));
    const valuesMatchActions = Array.isArray(values)
      && values.length === derivedValues.length
      && values.every((value, index) => Number(value) === derivedValues[index]);
    const transportValuesMatchActions = Array.isArray(transportValues)
      && transportValues.length === derivedTransportValues.length
      && transportValues.every(
        (value, index) => Number(value) === derivedTransportValues[index],
      );
    const invalidValues = !Array.isArray(values)
      || values.some((value) => !Number.isFinite(Number(value)) || Number(value) <= 0)
      || derivedValues.some((value) => !Number.isFinite(value) || value <= 0)
      || !Array.isArray(transportValues)
      || transportValues.some((value) => !Number.isFinite(Number(value)) || Number(value) <= 0)
      || derivedTransportValues.some((value) => !Number.isFinite(value) || value <= 0)
      || invalidActions
      || !valuesMatchActions
      || !transportValuesMatchActions;
    if (emitted === 0) {
      return {
        status: "inconclusive",
        reasons: [`no real ${name} packet was observed`],
        latency,
        transportLatency,
      };
    }
    if (invalidValues || count < minimumSamples) {
      return {
        status: "fail",
        reasons: [`${name} evidence has fewer than ${minimumSamples} positive raw latency samples`],
        latency,
        transportLatency,
      };
    }
    if (emitted !== count || confirmations !== count
        || transportConfirmations !== count || transitions !== count) {
      return {
        status: "fail",
        reasons: [`${name} emitted/transport/state confirmation counts do not match measured samples`],
        latency,
        transportLatency,
      };
    }
    const thresholdFailures = [];
    if (!(latency.p95Raw <= maxP95Millis)) {
      thresholdFailures.push(`${name} P95 ${latency.p95Raw} ms exceeds ${maxP95Millis} ms`);
    }
    if (!(latency.p99Raw <= maxP99Millis)) {
      thresholdFailures.push(`${name} P99 ${latency.p99Raw} ms exceeds ${maxP99Millis} ms`);
    }
    return {
      status: thresholdFailures.length > 0 ? "fail" : "pass",
      reasons: thresholdFailures,
      latency,
      transportLatency,
    };
  };

  const breakResult = operation("break", "breakAcknowledgementMillis", "breakTransportMillis");
  const placeResult = operation("place", "placeAcknowledgementMillis", "placeTransportMillis");
  const rollbacks = hasOwnFinite(evidence, "rollbacks") ? Number(evidence.rollbacks) : null;
  const rollbackLimit = finiteNumber(contract.maxRollbacks, 0);
  if (rollbacks == null) reasons.push("rollback count is missing; zero is not assumed");
  else if (rollbacks > rollbackLimit) reasons.push(`rollback count ${rollbacks} exceeds ${rollbackLimit}`);
  else if (rollbacks < 0) reasons.push("rollback count is negative");
  const operationReasons = [...breakResult.reasons, ...placeResult.reasons];
  const allReasons = [...reasons, ...operationReasons];
  let verdict = "pass";
  if (breakResult.status === "inconclusive" || placeResult.status === "inconclusive"
      || rollbacks == null) {
    verdict = "inconclusive";
  } else if (breakResult.status === "fail" || placeResult.status === "fail"
      || rollbacks > rollbackLimit || rollbacks < 0) {
    verdict = "fail";
  }
  return {
    verdict,
    reasons: allReasons,
    source: evidence.source,
    route: evidence.route,
    protocolVersion: evidence.protocolVersion ?? null,
    measurementStartedAt: evidence.measurementStartedAt,
    measurementEndedAt: evidence.measurementEndedAt,
    transport: {
      outboundCalls: Number(evidence.outboundTransportCalls),
      inboundCalls: Number(evidence.inboundTransportCalls),
      outboundBytes: Number(evidence.outboundTransportBytes),
      inboundBytes: Number(evidence.inboundTransportBytes),
      parsedOutboundPackets: Number(evidence.parsedOutboundPackets),
      parsedInboundPackets: Number(evidence.parsedInboundPackets),
      compressedFrames: Number(evidence.compressedFrames || 0),
      parserFailures: Number(evidence.parserFailures || 0),
    },
    breakLatencyMillis: breakResult.latency,
    placeLatencyMillis: placeResult.latency,
    breakTransportLatencyMillis: breakResult.transportLatency,
    placeTransportLatencyMillis: placeResult.transportLatency,
    rollbacks,
    minimumSamplesPerOperation: minimumSamples,
    requireActiveWorkloadEvidence,
    thresholds: {p95Millis: maxP95Millis, p99Millis: maxP99Millis, maxRollbacks: rollbackLimit},
    sampleCounts: {
      break: Array.isArray(evidence.breakAcknowledgementMillis)
        ? evidence.breakAcknowledgementMillis.length : 0,
      place: Array.isArray(evidence.placeAcknowledgementMillis)
        ? evidence.placeAcknowledgementMillis.length : 0,
    },
  };
}

export function combineMemorySnapshots(snapshots, fields) {
  const values = (Array.isArray(snapshots) ? snapshots : [])
    .filter((snapshot) => snapshot && typeof snapshot === "object");
  if (values.length === 0) return null;
  const combined = {};
  for (const field of fields || []) {
    const numbers = values
      .map((snapshot) => Number(snapshot[field]))
      .filter((value) => Number.isFinite(value));
    if (numbers.length > 0) combined[field] = numbers.reduce((sum, value) => sum + value, 0);
  }
  combined.sourceCount = values.length;
  return combined;
}

/** Rejects missing safety telemetry, allocation failures, and disabled runtime budgets. */
export function evaluateBrowserMemorySafety({
  snapshots = [],
  requiredFields = [],
  failureFields = [],
  limits = {},
  aggregateLimits = {},
} = {}) {
  const attempted = Array.isArray(snapshots) ? snapshots : [];
  const baseRequired = [...new Set([
    ...requiredFields,
    ...failureFields,
  ].map(String).filter(Boolean))];
  const missing = [];
  const failures = [];
  for (const [index, entry] of attempted.entries()) {
    const memory = entry?.memory && typeof entry.memory === "object"
      ? entry.memory
      : entry;
    const source = String(entry?.source || `snapshot-${index}`);
    const entryLimits = entry?.aggregate === true ? aggregateLimits : limits;
    const required = [...new Set([
      ...baseRequired,
      ...Object.keys(entryLimits || {}),
    ].map(String).filter(Boolean))];
    if (!memory || typeof memory !== "object") {
      missing.push({index, source, fields: required});
      continue;
    }
    const missingFields = required.filter(
      (field) => !Object.hasOwn(memory, field) || !Number.isFinite(Number(memory[field])),
    );
    if (missingFields.length > 0) missing.push({index, source, fields: missingFields});
    for (const field of failureFields) {
      const value = Number(memory[field]);
      if (Number.isFinite(value) && value !== 0) {
        failures.push({index, source, field, value, reason: "nonzero-failure-counter"});
      }
    }
    for (const [field, rawMaximum] of Object.entries(entryLimits || {})) {
      const value = Number(memory[field]);
      const maximum = Number(rawMaximum);
      if (Number.isFinite(value) && Number.isFinite(maximum)
          && (value <= 0 || value > maximum)) {
        failures.push({index, source, field, value, maximum, reason: "unsafe-limit"});
      }
    }
    for (const [valueField, limitField] of [
      ["liveBytes", "maxLiveBytes"],
      ["peakTemporaryBytes", "maxTemporaryBytes"],
    ]) {
      const value = Number(memory[valueField]);
      const limit = Number(memory[limitField]);
      if (Number.isFinite(value) && Number.isFinite(limit)
          && (value < 0 || value > limit)) {
        failures.push({
          index,
          source,
          field: valueField,
          value,
          maximum: limit,
          reason: "runtime-budget-exceeded",
        });
      }
    }
  }
  const reasons = [];
  let verdict = "pass";
  if (failures.length > 0) {
    verdict = "fail";
    reasons.push(`${failures.length} BrowserMemory safety violation(s) were recorded`);
  } else if (attempted.length === 0 || missing.length > 0) {
    verdict = "inconclusive";
    reasons.push(attempted.length === 0
      ? "BrowserMemory safety telemetry was not sampled"
      : `${missing.length} BrowserMemory sample(s) lacked required safety fields`);
  }
  return {
    verdict,
    reasons,
    attemptedSampleCount: attempted.length,
    requiredFields: [...new Set([
      ...baseRequired,
      ...Object.keys(limits || {}),
      ...Object.keys(aggregateLimits || {}),
    ])],
    failureFields: [...failureFields],
    limits: {...limits},
    aggregateLimits: {...aggregateLimits},
    missing,
    failures,
  };
}

function summarizeTimedSampleCoverage({
  attemptedSampleTimes = [],
  availableSampleTimes = [],
  requiredDurationMs = 0,
  sampleIntervalMs = 5000,
  minimumAvailableRatio = 0.9,
  minimumDurationCoverageRatio = 0.98,
  maximumSampleGapRatio = 1.5,
} = {}) {
  const available = availableSampleTimes
    .map((value) => Number(value))
    .filter(Number.isFinite)
    .sort((left, right) => left - right);
  const attemptedInput = Array.isArray(attemptedSampleTimes) ? attemptedSampleTimes : [];
  const hasAttemptedTimeline = attemptedInput.length > 0;
  const attempted = (hasAttemptedTimeline ? attemptedInput : available)
    .map((value) => Number(value))
    .filter(Number.isFinite)
    .sort((left, right) => left - right);
  const required = Math.max(0, Number(requiredDurationMs) || 0);
  const interval = Math.max(1, Number(sampleIntervalMs) || 5000);
  const minimumRatio = Math.max(0, Math.min(1, Number(minimumAvailableRatio) || 0));
  const durationRatio = Math.max(
    0,
    Math.min(1, Number(minimumDurationCoverageRatio) || 0),
  );
  const gapRatio = Math.max(1, Number(maximumSampleGapRatio) || 1.5);
  const expectedSampleCount = Math.floor(required / interval) + 1;
  const minimumExpectedSampleCount = hasAttemptedTimeline
    ? Math.ceil(expectedSampleCount * minimumRatio) : 0;
  const availabilityRatio = attempted.length > 0
    ? available.length / attempted.length : 0;
  const availableDurationMs = available.length >= 2
    ? available.at(-1) - available[0] : 0;
  const measuredDurationRatio = required > 0
    ? Math.min(1, availableDurationMs / required) : 0;
  const maximumSampleGapMs = available.length >= 2
    ? available.slice(1).reduce(
      (maximum, at, index) => Math.max(maximum, at - available[index]),
      0,
    )
    : null;
  const maximumAllowedSampleGapMs = interval * gapRatio;
  const boundaryToleranceMs = maximumAllowedSampleGapMs;
  const firstSampleAt = available[0] ?? null;
  const lastSampleAt = available.at(-1) ?? null;
  const complete = available.length >= Math.max(2, minimumExpectedSampleCount)
    && (!hasAttemptedTimeline || availabilityRatio >= minimumRatio)
    && measuredDurationRatio >= durationRatio
    && firstSampleAt != null
    && firstSampleAt <= boundaryToleranceMs
    && lastSampleAt != null
    && lastSampleAt >= required - boundaryToleranceMs
    && (maximumSampleGapMs == null || maximumSampleGapMs <= maximumAllowedSampleGapMs);
  return {
    complete,
    hasAttemptedTimeline,
    attemptedSampleCount: attempted.length,
    availableSampleCount: available.length,
    availabilityRatio,
    expectedSampleCount,
    minimumExpectedSampleCount,
    firstSampleAt,
    lastSampleAt,
    availableDurationMs,
    durationCoverageRatio: measuredDurationRatio,
    minimumDurationCoverageRatio: durationRatio,
    maximumSampleGapMs,
    maximumAllowedSampleGapMs,
    maximumSampleGapRatio: gapRatio,
  };
}

/** Proves that Worker heartbeat data belongs to the active session and current run. */
export function validateWorkerHeartbeatTelemetry(worker, lifecycle, context, heartbeatRules = {}) {
  const requiredNumericFields = [
    "schemaVersion",
    "resetAt",
    "updatedAt",
    "sent",
    "received",
    "rttSampleCount",
    "missed",
    "errors",
    "pending",
    "p99RttMillis",
    "maxRttMillis",
    "longestHeartbeatDelayMillis",
    "lastPongAt",
    "configuredIntervalMillis",
  ];
  const missingFields = requiredNumericFields.filter(
    (field) => !Object.hasOwn(worker || {}, field)
      || !Number.isFinite(Number(worker[field])),
  );
  const activeSessionIds = Array.from(lifecycle?.states || [])
    .filter((state) => state && state.terminal !== true)
    .map((state) => String(state.sessionId || ""))
    .filter(Boolean);
  const expectedMeasurementId = String(context?.expectedWorkerMeasurementId || "");
  const measurementStartedAt = Number(context?.measurementStartedAt);
  const measurementEndedAt = Number(context?.measurementEndedAt);
  const configuredInterval = Number(worker?.configuredIntervalMillis);
  const freshnessLimitMillis = Math.max(
    Number(heartbeatRules.rttMaxMs || 250),
    Number.isFinite(configuredInterval) ? configuredInterval * 3 : 0,
  );
  const reasons = [];
  if (missingFields.length > 0) {
    reasons.push(`Worker heartbeat telemetry is missing finite fields: ${missingFields.join(", ")}`);
  }
  if (Number(worker?.schemaVersion) !== 2) {
    reasons.push("Worker heartbeat telemetry schema is not version 2");
  }
  if (!expectedMeasurementId || String(worker?.measurementId || "") !== expectedMeasurementId) {
    reasons.push("Worker heartbeat telemetry does not belong to the current measurement window");
  }
  if (activeSessionIds.length !== 1
      || String(worker?.sessionId || "") !== activeSessionIds[0]) {
    reasons.push("Worker heartbeat telemetry does not belong to the sole active Worker session");
  }
  if (Number(worker?.received) <= 0
      || Number(worker?.sent) <= 0
      || Number(worker?.received) > Number(worker?.sent)
      || Number(worker?.rttSampleCount) !== Number(worker?.received)
      || ["sent", "received", "rttSampleCount", "missed", "errors", "pending"]
        .some((field) => Number(worker?.[field]) < 0)) {
    reasons.push("Worker heartbeat counters are absent, negative, or inconsistent");
  }
  const resetAt = Number(worker?.resetAt);
  if (!Number.isFinite(resetAt) || !Number.isFinite(measurementStartedAt)
      || resetAt < measurementStartedAt - 5000 || resetAt > measurementStartedAt + 1000) {
    reasons.push("Worker heartbeat reset timestamp is outside the current measurement start window");
  }
  const updatedAt = Number(worker?.updatedAt);
  if (!Number.isFinite(updatedAt) || !Number.isFinite(measurementStartedAt)
      || !Number.isFinite(measurementEndedAt)
      || updatedAt < measurementStartedAt || updatedAt > measurementEndedAt + 1000) {
    reasons.push("Worker heartbeat update timestamp is outside the current measurement window");
  }
  const lastPongAt = Number(worker?.lastPongAt);
  if (!Number.isFinite(lastPongAt) || !Number.isFinite(measurementStartedAt)
      || !Number.isFinite(measurementEndedAt)
      || lastPongAt < measurementStartedAt
      || lastPongAt > measurementEndedAt + 1000
      || measurementEndedAt - lastPongAt > freshnessLimitMillis) {
    reasons.push(`Worker heartbeat telemetry is stale by more than ${freshnessLimitMillis} ms`);
  }
  return {
    verdict: reasons.length === 0 ? "pass" : "fail",
    reasons,
    requiredNumericFields,
    missingFields,
    expectedMeasurementId,
    activeSessionIds,
    freshnessLimitMillis,
  };
}

export function parsePsRssOutput(output) {
  const rssByPid = {};
  for (const line of String(output || "").split(/\r?\n/)) {
    const match = line.trim().match(/^(\d+)\s+(\d+)$/);
    if (!match) continue;
    const pid = Number(match[1]);
    const rssKiB = Number(match[2]);
    if (!Number.isSafeInteger(pid) || pid <= 0
        || !Number.isFinite(rssKiB) || rssKiB < 0) {
      continue;
    }
    rssByPid[String(pid)] = rssKiB * 1024;
  }
  return rssByPid;
}

function normalizeChromeProcessType(value) {
  const type = String(value || "unknown").trim().toLowerCase();
  if (type.includes("renderer")) return "renderer";
  if (type === "gpu" || type.includes("gpu-process")) return "gpu";
  if (type.includes("utility") || type.includes(".mojom.")
      || type.includes("service") || type.includes("provider")) return "utility";
  if (type.includes("browser")) return "browser";
  return type || "unknown";
}

/** Joins SystemInfo.getProcessInfo output with byte-valued RSS readings keyed by PID. */
export function aggregateChromeProcessRss(processInfo, rssByPid) {
  const infos = Array.isArray(processInfo) ? processInfo : [];
  const rss = rssByPid && typeof rssByPid === "object" ? rssByPid : {};
  const processes = [];
  for (const info of infos) {
    const pid = Number(info?.id);
    if (!Number.isSafeInteger(pid) || pid <= 0) continue;
    const rssBytes = Number(rss[String(pid)]);
    const hasRss = Object.hasOwn(rss, String(pid))
      && Number.isFinite(rssBytes)
      && rssBytes >= 0;
    processes.push({
      pid,
      type: normalizeChromeProcessType(info?.type),
      rawType: String(info?.type || "unknown"),
      rssBytes: hasRss ? rssBytes : null,
      cpuTimeSeconds: Number.isFinite(Number(info?.cpuTime)) ? Number(info.cpuTime) : null,
    });
  }
  if (processes.length === 0) {
    return {
      available: false,
      error: "SystemInfo.getProcessInfo returned no valid Chrome process IDs",
      totalRssBytes: null,
      processCount: 0,
      missingPids: [],
      byType: {},
      processes: [],
    };
  }

  const missingPids = processes
    .filter((process) => process.rssBytes == null)
    .map((process) => process.pid);
  const grouped = new Map();
  for (const process of processes) {
    const group = grouped.get(process.type) || [];
    group.push(process);
    grouped.set(process.type, group);
  }
  const byType = {};
  for (const [type, typeProcesses] of grouped) {
    const missingProcessCount = typeProcesses.filter((process) => process.rssBytes == null).length;
    byType[type] = {
      available: missingProcessCount === 0,
      rssBytes: missingProcessCount === 0
        ? typeProcesses.reduce((sum, process) => sum + process.rssBytes, 0)
        : null,
      processCount: typeProcesses.length,
      missingProcessCount,
    };
  }
  const available = missingPids.length === 0;
  return {
    available,
    error: available ? null : `ps did not return RSS for Chrome PIDs: ${missingPids.join(", ")}`,
    totalRssBytes: available
      ? processes.reduce((sum, process) => sum + process.rssBytes, 0)
      : null,
    processCount: processes.length,
    missingPids,
    byType,
    processes,
  };
}

/** Reduces strict world-start observations without allowing discontinuous frames to accumulate. */
export function summarizeWorldReadiness(observations = [], rules = {}) {
  const requiredConsecutiveFrames = Math.max(
    1,
    Math.floor(finiteNumber(rules.requiredConsecutiveTerrainFrames, 16)),
  );
  const minimumWindowMillis = Math.max(
    0,
    finiteNumber(rules.minimumTerrainWindowMs, 250),
  );
  const maximumFrameGapMillis = Math.max(
    1,
    finiteNumber(rules.maximumTerrainFrameGapMs, 100),
  );
  const minimumBlockHitFrames = Math.max(
    1,
    Math.floor(finiteNumber(rules.minimumBlockHitFrames, 3)),
  );
  const minimumVisualSamples = Math.max(
    1,
    Math.floor(finiteNumber(rules.minimumVisualSamples, 3)),
  );
  let streakStartedAt = null;
  let consecutiveFrames = 0;
  let blockHitFrames = 0;
  let validVisualSamples = 0;
  let lastVisibleFrameCount = null;
  let lastHitStateAt = null;
  let last = null;
  let passedAt = null;
  const reset = () => {
    streakStartedAt = null;
    consecutiveFrames = 0;
    blockHitFrames = 0;
    validVisualSamples = 0;
    lastHitStateAt = null;
  };
  for (const observation of Array.isArray(observations) ? observations : []) {
    const at = finiteNumber(observation?.at, NaN);
    const visibleFrameCount = finiteNumber(observation?.visibleFrameCount, NaN);
    const frameAgeMillis = finiteNumber(observation?.frameAgeMillis, NaN);
    const frameAdvanced = Number.isFinite(visibleFrameCount)
      && (lastVisibleFrameCount == null || visibleFrameCount > lastVisibleFrameCount);
    const continuous = observation?.baseReady === true
      && Number.isFinite(at)
      && Number.isFinite(frameAgeMillis)
      && frameAgeMillis <= maximumFrameGapMillis;
    if (!continuous) {
      reset();
    } else if (frameAdvanced) {
      if (streakStartedAt == null) {
        streakStartedAt = at;
        consecutiveFrames = 1;
      } else {
        consecutiveFrames++;
      }
      if (observation?.hitIsSolidBlock === true
          && observation?.stateAt !== lastHitStateAt) {
        blockHitFrames++;
        lastHitStateAt = observation?.stateAt;
      }
      if (observation?.visualPass === true) {
        validVisualSamples++;
      } else if (observation?.visualPass === false) {
        reset();
      }
    }
    if (Number.isFinite(visibleFrameCount)) lastVisibleFrameCount = visibleFrameCount;
    last = observation;
    const streakMillis = streakStartedAt == null || !Number.isFinite(at)
      ? 0
      : Math.max(0, at - streakStartedAt);
    if (consecutiveFrames >= requiredConsecutiveFrames
        && streakMillis >= minimumWindowMillis
        && blockHitFrames >= minimumBlockHitFrames
        && validVisualSamples >= minimumVisualSamples) {
      passedAt = at;
      break;
    }
  }
  const finalAt = finiteNumber(last?.at, NaN);
  const streakMillis = streakStartedAt == null || !Number.isFinite(finalAt)
    ? 0
    : Math.max(0, finalAt - streakStartedAt);
  return {
    verdict: passedAt == null ? "pending" : "pass",
    passedAt,
    consecutiveFrames,
    streakMillis,
    blockHitFrames,
    validVisualSamples,
    last,
    requirements: {
      requiredConsecutiveFrames,
      minimumWindowMillis,
      maximumFrameGapMillis,
      minimumBlockHitFrames,
      minimumVisualSamples,
    },
  };
}

/** Evaluates compositor screenshots without treating missing capture data as a black frame. */
export function summarizeVisualOutput(samples = [], rules = {}) {
  const attempted = Array.isArray(samples) ? samples : [];
  const requiredSamples = Math.max(1, Math.floor(finiteNumber(rules.requiredSamples, 6)));
  const minimumNonBlackRatio = Math.max(
    0,
    Math.min(1, finiteNumber(rules.minimumNonBlackRatio, 0.001)),
  );
  const minimumLuminanceStdDev = Math.max(
    0,
    finiteNumber(rules.minimumLuminanceStdDev, 0.5),
  );
  const minimumColorBuckets = Math.max(
    1,
    Math.floor(finiteNumber(rules.minimumColorBuckets, 4)),
  );
  const maximumDominantColorRatio = Math.max(
    0,
    Math.min(1, finiteNumber(rules.maximumDominantColorRatio, 0.999)),
  );
  const minimumCentralNonBlackRatio = Math.max(
    0,
    Math.min(1, finiteNumber(rules.minimumCentralNonBlackRatio, minimumNonBlackRatio)),
  );
  const minimumCentralLuminanceStdDev = Math.max(
    0,
    finiteNumber(rules.minimumCentralLuminanceStdDev, minimumLuminanceStdDev),
  );
  const minimumCentralColorBuckets = Math.max(
    1,
    Math.floor(finiteNumber(rules.minimumCentralColorBuckets, minimumColorBuckets)),
  );
  const maximumCentralDominantColorRatio = Math.max(
    0,
    Math.min(
      1,
      finiteNumber(rules.maximumCentralDominantColorRatio, maximumDominantColorRatio),
    ),
  );
  const expectedWidth = finiteNumber(rules.expectedWidth, NaN);
  const expectedHeight = finiteNumber(rules.expectedHeight, NaN);
  const requiredPhases = Array.isArray(rules.requiredPhases)
    ? [...new Set(rules.requiredPhases.map(String).filter(Boolean))]
    : [];
  const minimumSamplesPerPhase = Math.max(
    1,
    Math.floor(finiteNumber(rules.minimumSamplesPerPhase, 1)),
  );
  const requireSceneChange = rules.requireSceneChange === true;
  const minimumTraversalFingerprintRms = Math.max(
    0,
    finiteNumber(rules.minimumTraversalFingerprintRms, 2),
  );
  const minimumActiveTileCount = Math.max(
    1,
    Math.floor(finiteNumber(rules.minimumActiveTileCount, 6)),
  );
  const available = attempted.filter((sample) => sample?.available === true);
  const unavailable = attempted.filter((sample) => sample?.available !== true);
  const failures = [];
  const missingCanvasClip = [];
  for (const sample of available) {
    const nonBlackRatio = Number(sample.nonBlackRatio);
    const luminanceStdDev = Number(sample.luminanceStdDev);
    const colorBuckets = Number(sample.colorBuckets);
    const dominantColorRatio = Number(sample.dominantColorRatio);
    const centralNonBlackRatio = Number(sample.centralNonBlackRatio);
    const centralLuminanceStdDev = Number(sample.centralLuminanceStdDev);
    const centralColorBuckets = Number(sample.centralColorBuckets);
    const centralDominantColorRatio = Number(sample.centralDominantColorRatio);
    const activeTileCount = Number(sample.activeTileCount);
    const tileCount = Number(sample.tileCount);
    const canvasClip = sample.canvasClip;
    const clipPresent = canvasClip != null;
    const clipValid = clipPresent
      && Number(canvasClip.x) >= 0
      && Number(canvasClip.y) >= 0
      && Number(canvasClip.width) > 0
      && Number(canvasClip.height) > 0
      && Number(canvasClip.scale) > 0;
    const dimensionsValid = Number(sample.width) > 0
      && Number(sample.height) > 0
      && Number(sample.sampleWidth) > 0
      && Number(sample.sampleHeight) > 0
      && Number(sample.pixelCount) === Number(sample.sampleWidth) * Number(sample.sampleHeight)
      && Number(sample.centralPixelCount) > 0
      && (!Number.isFinite(expectedWidth) || Number(sample.width) === expectedWidth)
      && (!Number.isFinite(expectedHeight) || Number(sample.height) === expectedHeight);
    const reasons = [];
    if (!dimensionsValid) reasons.push("invalid screenshot dimensions");
    if (!clipPresent) {
      missingCanvasClip.push({
        phase: sample.phase || null,
        index: Number.isFinite(Number(sample.index)) ? Number(sample.index) : null,
      });
    } else if (!clipValid) {
      reasons.push("invalid Minecraft canvas screenshot clip");
    }
    if (!Number.isFinite(nonBlackRatio) || nonBlackRatio < minimumNonBlackRatio) {
      reasons.push(`non-black ratio ${round(nonBlackRatio, 6)} < ${minimumNonBlackRatio}`);
    }
    if (!Number.isFinite(luminanceStdDev) || luminanceStdDev < minimumLuminanceStdDev) {
      reasons.push(
        `luminance standard deviation ${round(luminanceStdDev, 6)} < ${minimumLuminanceStdDev}`,
      );
    }
    if (!Number.isFinite(colorBuckets) || colorBuckets < minimumColorBuckets) {
      reasons.push(`color buckets ${colorBuckets} < ${minimumColorBuckets}`);
    }
    if (!Number.isFinite(dominantColorRatio)
        || dominantColorRatio > maximumDominantColorRatio) {
      reasons.push(
        `dominant color ratio ${round(dominantColorRatio, 6)} > ${maximumDominantColorRatio}`,
      );
    }
    if (!Number.isFinite(centralNonBlackRatio)
        || centralNonBlackRatio < minimumCentralNonBlackRatio) {
      reasons.push(
        `central non-black ratio ${round(centralNonBlackRatio, 6)} < ${minimumCentralNonBlackRatio}`,
      );
    }
    if (!Number.isFinite(centralLuminanceStdDev)
        || centralLuminanceStdDev < minimumCentralLuminanceStdDev) {
      reasons.push(
        `central luminance standard deviation ${round(centralLuminanceStdDev, 6)} < ${minimumCentralLuminanceStdDev}`,
      );
    }
    if (!Number.isFinite(centralColorBuckets)
        || centralColorBuckets < minimumCentralColorBuckets) {
      reasons.push(
        `central color buckets ${centralColorBuckets} < ${minimumCentralColorBuckets}`,
      );
    }
    if (!Number.isFinite(centralDominantColorRatio)
        || centralDominantColorRatio > maximumCentralDominantColorRatio) {
      reasons.push(
        `central dominant color ratio ${round(centralDominantColorRatio, 6)} > ${maximumCentralDominantColorRatio}`,
      );
    }
    if (!Number.isFinite(activeTileCount) || !Number.isFinite(tileCount)
        || tileCount < minimumActiveTileCount || activeTileCount < minimumActiveTileCount) {
      reasons.push(
        `active terrain tiles ${activeTileCount}/${tileCount} < ${minimumActiveTileCount}`,
      );
    }
    if (reasons.length > 0) {
      failures.push({
        phase: sample.phase || null,
        index: Number.isFinite(Number(sample.index)) ? Number(sample.index) : null,
        path: sample.path || null,
        reasons,
      });
    }
  }

  const phaseCounts = Object.fromEntries(requiredPhases.map((phase) => [
    phase,
    available.filter((sample) => String(sample.phase || "") === phase).length,
  ]));
  const incompletePhases = requiredPhases.filter(
    (phase) => phaseCounts[phase] < minimumSamplesPerPhase,
  );
  const fingerprints = Object.fromEntries(requiredPhases.map((phase) => [
    phase,
    available
      .filter((sample) => String(sample.phase || "") === phase)
      .map((sample) => sample.terrainFingerprint)
      .filter((value) => Array.isArray(value) && value.length > 0
        && value.every((entry) => Number.isFinite(Number(entry)))),
  ]));
  const fingerprintDistances = [];
  if (requiredPhases.length >= 2) {
    const first = fingerprints[requiredPhases[0]] || [];
    const second = fingerprints[requiredPhases[1]] || [];
    for (const left of first) {
      for (const right of second) {
        if (left.length !== right.length) continue;
        const squareSum = left.reduce((sum, value, index) => {
          const difference = Number(value) - Number(right[index]);
          return sum + difference * difference;
        }, 0);
        fingerprintDistances.push(Math.sqrt(squareSum / left.length));
      }
    }
  }
  const maximumFingerprintRms = fingerprintDistances.length > 0
    ? Math.max(...fingerprintDistances)
    : null;
  const sceneChangeFailed = requireSceneChange
    && maximumFingerprintRms != null
    && maximumFingerprintRms < minimumTraversalFingerprintRms;
  const sceneChangeUnavailable = requireSceneChange && maximumFingerprintRms == null;

  let verdict = "pass";
  const reasons = [];
  if (failures.length > 0 || sceneChangeFailed) {
    verdict = "fail";
    if (failures.length > 0) {
      reasons.push(`${failures.length} compositor screenshot(s) lacked terrain evidence`);
    }
    if (sceneChangeFailed) {
      reasons.push(
        `traversal scene fingerprint RMS ${round(maximumFingerprintRms, 6)} < ${minimumTraversalFingerprintRms}`,
      );
    }
  } else if (available.length < requiredSamples || unavailable.length > 0
      || incompletePhases.length > 0 || sceneChangeUnavailable
      || missingCanvasClip.length > 0) {
    verdict = "inconclusive";
    reasons.push(
      `captured ${available.length}/${requiredSamples} required compositor screenshots`,
    );
    if (incompletePhases.length > 0) {
      reasons.push(`missing required phase coverage: ${incompletePhases.join(", ")}`);
    }
    if (sceneChangeUnavailable) {
      reasons.push("traversal scene fingerprints were unavailable");
    }
    if (missingCanvasClip.length > 0) {
      reasons.push(`${missingCanvasClip.length} screenshot(s) lacked a canvas-only clip`);
    }
  }
  return {
    verdict,
    reasons,
    attemptedSampleCount: attempted.length,
    availableSampleCount: available.length,
    unavailableSampleCount: unavailable.length,
    requiredSamples,
    thresholds: {
      minimumNonBlackRatio,
      minimumLuminanceStdDev,
      minimumColorBuckets,
      maximumDominantColorRatio,
      minimumCentralNonBlackRatio,
      minimumCentralLuminanceStdDev,
      minimumCentralColorBuckets,
      maximumCentralDominantColorRatio,
      expectedWidth: Number.isFinite(expectedWidth) ? expectedWidth : null,
      expectedHeight: Number.isFinite(expectedHeight) ? expectedHeight : null,
      minimumTraversalFingerprintRms,
      minimumActiveTileCount,
    },
    phases: {
      required: requiredPhases,
      minimumSamplesPerPhase,
      counts: phaseCounts,
      incomplete: incompletePhases,
    },
    sceneChange: {
      required: requireSceneChange,
      maximumFingerprintRms: maximumFingerprintRms == null
        ? null : round(maximumFingerprintRms, 6),
      minimumFingerprintRms: minimumTraversalFingerprintRms,
      pairCount: fingerprintDistances.length,
      failed: sceneChangeFailed,
      unavailable: sceneChangeUnavailable,
    },
    failures,
    missingCanvasClip,
    unavailable: unavailable.map((sample) => ({
      phase: sample?.phase || null,
      index: Number.isFinite(Number(sample?.index)) ? Number(sample.index) : null,
      error: String(sample?.error || "compositor screenshot unavailable"),
    })),
    samples: attempted,
  };
}

export function summarizeQueueTimeline(
  timeline,
  thresholds,
  requiredDurationMs = 10000,
  measurementEndMillis = null,
) {
  const samples = Array.isArray(timeline) ? timeline : [];
  const queues = {};
  for (const [path, rawThreshold] of Object.entries(thresholds || {})) {
    const threshold = Number(rawThreshold);
    let activeStartedAt = null;
    let longestHighWaterMs = 0;
    let maximum = null;
    let observed = false;
    let previousAt = null;
    let finalValue = null;
    for (const sample of samples) {
      const at = Number(sample?.atMillis);
      const value = valueAtPath(sample, path);
      if (!Number.isFinite(at) || value == null) {
        continue;
      }
      observed = true;
      maximum = maximum == null ? value : Math.max(maximum, value);
      finalValue = value;
      if (value >= threshold) {
        if (activeStartedAt == null) {
          activeStartedAt = at;
        }
        longestHighWaterMs = Math.max(longestHighWaterMs, at - activeStartedAt);
      } else {
        if (activeStartedAt != null) {
          longestHighWaterMs = Math.max(longestHighWaterMs, at - activeStartedAt);
        }
        activeStartedAt = null;
      }
      previousAt = at;
    }
    if (activeStartedAt != null && previousAt != null) {
      const observedEnd = Number(measurementEndMillis);
      const endAt = Number.isFinite(observedEnd) && observedEnd >= previousAt
        ? observedEnd : previousAt;
      longestHighWaterMs = Math.max(longestHighWaterMs, endAt - activeStartedAt);
    }
    queues[path] = {
      available: observed,
      threshold,
      maximum: maximum == null ? null : round(maximum),
      finalValue: finalValue == null ? null : round(finalValue),
      longestHighWaterMs: round(longestHighWaterMs),
      failed: observed && longestHighWaterMs >= requiredDurationMs,
    };
  }
  const failed = Object.values(queues).filter((queue) => queue.failed);
  const unavailable = Object.entries(queues)
    .filter(([, queue]) => !queue.available)
    .map(([path]) => path);
  return {
    verdict: failed.length > 0 ? "fail" : "pass",
    failedQueues: Object.entries(queues)
      .filter(([, queue]) => queue.failed)
      .map(([path]) => path),
    unavailableQueues: unavailable,
    queues,
  };
}

function linearSlope(samples) {
  if (samples.length < 2) {
    return null;
  }
  const meanX = samples.reduce((sum, sample) => sum + sample.x, 0) / samples.length;
  const meanY = samples.reduce((sum, sample) => sum + sample.y, 0) / samples.length;
  let numerator = 0;
  let denominator = 0;
  for (const sample of samples) {
    const dx = sample.x - meanX;
    numerator += dx * (sample.y - meanY);
    denominator += dx * dx;
  }
  return denominator > 0 ? numerator / denominator : null;
}

function median(numbers) {
  if (numbers.length === 0) return null;
  const sorted = [...numbers].sort((left, right) => left - right);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0
    ? (sorted[middle - 1] + sorted[middle]) / 2
    : sorted[middle];
}

function processRssSeries(points, thresholds, trendWindowCount) {
  const ordered = points
    .filter((point) => Number.isFinite(point?.x) && Number.isFinite(point?.y))
    .sort((left, right) => left.x - right.x);
  const windowCount = Math.max(2, Math.floor(finiteNumber(trendWindowCount, 4)));
  const windowMedians = [];
  if (ordered.length >= windowCount) {
    for (let index = 0; index < windowCount; index++) {
      const start = Math.floor(index * ordered.length / windowCount);
      const end = Math.floor((index + 1) * ordered.length / windowCount);
      windowMedians.push(median(ordered.slice(start, end).map((point) => point.y)));
    }
  }
  const firstWindowMedian = windowMedians[0] ?? null;
  const lastWindowMedian = windowMedians.at(-1) ?? null;
  const growthBytes = firstWindowMedian != null && lastWindowMedian != null
    ? lastWindowMedian - firstWindowMedian
    : null;
  const growthPercent = firstWindowMedian > 0 && growthBytes != null
    ? growthBytes * 100 / firstWindowMedian
    : null;
  const sustainedGrowth = windowMedians.length === windowCount
    && windowMedians.slice(1).every((value, index) => value > windowMedians[index]);
  const growthPercentMax = finiteNumber(thresholds?.growthPercentMax, NaN);
  const growthBytesMax = finiteNumber(thresholds?.growthBytesMax, NaN);
  const peakBytesMax = finiteNumber(thresholds?.peakBytesMax, NaN);
  const thresholdConfigured = Number.isFinite(growthPercentMax)
    && Number.isFinite(growthBytesMax)
    && Number.isFinite(peakBytesMax);
  const peakRssBytes = ordered.length > 0 ? Math.max(...ordered.map((point) => point.y)) : null;
  const growthFailed = thresholdConfigured
    && sustainedGrowth
    && growthPercent != null
    && growthBytes != null
    && (growthPercent > growthPercentMax || growthBytes > growthBytesMax);
  const peakFailed = thresholdConfigured
    && peakRssBytes != null
    && peakRssBytes > peakBytesMax;
  const failed = growthFailed || peakFailed;
  const seriesSlope = linearSlope(ordered);
  return {
    available: ordered.length > 0,
    sampleCount: ordered.length,
    firstRssBytes: ordered[0]?.y ?? null,
    lastRssBytes: ordered.at(-1)?.y ?? null,
    peakRssBytes,
    firstWindowMedianBytes: firstWindowMedian,
    lastWindowMedianBytes: lastWindowMedian,
    growthBytes,
    growthMiB: growthBytes == null ? null : round(growthBytes / MIB),
    growthPercent: growthPercent == null ? null : round(growthPercent),
    slopeMiBPerMinute: seriesSlope == null ? null : round(seriesSlope * 60000 / MIB, 6),
    trendWindowCount: windowCount,
    windowMediansBytes: windowMedians,
    sustainedGrowth,
    thresholdConfigured,
    thresholds: thresholdConfigured ? {growthPercentMax, growthBytesMax, peakBytesMax} : null,
    growthFailed,
    peakFailed,
    failed,
  };
}

/** Evaluates OS RSS only for a complete soak; shorter captures remain record-only. */
export function summarizeChromeProcessRssTrend({
  samples = [],
  cleanupSamples = [],
  durationMs = 0,
  requiredDurationMs = 1800000,
  sampleIntervalMs = 15000,
  minimumAvailableRatio = 0.9,
  minimumDurationCoverageRatio = 0.98,
  maximumSampleGapRatio = 1.5,
  trendWindowCount = 4,
  processTypes = ["browser", "renderer", "gpu", "utility"],
  requiredProcessTypes = ["browser", "renderer", "gpu"],
  totalThresholds = {},
  typeThresholds = {},
} = {}) {
  const attempted = Array.isArray(samples) ? samples : [];
  const available = attempted.filter((sample) => sample?.available === true
    && Number.isFinite(Number(sample.totalRssBytes)));
  const availabilityRatio = attempted.length > 0 ? available.length / attempted.length : 0;
  const intervalMillis = Math.max(1000, finiteNumber(sampleIntervalMs, 15000));
  const expectedSampleCount = Math.floor(requiredDurationMs / intervalMillis) + 1;
  const minimumExpectedSampleCount = Math.ceil(
    expectedSampleCount * Math.max(0, Math.min(1, finiteNumber(minimumAvailableRatio, 0.9))),
  );
  const availableTimes = available.map((sample) => Number(sample.atMillis))
    .filter(Number.isFinite).sort((left, right) => left - right);
  const availableDurationMs = availableTimes.length >= 2
    ? availableTimes.at(-1) - availableTimes[0]
    : 0;
  const maximumSampleGapMs = availableTimes.length >= 2
    ? availableTimes.slice(1).reduce(
      (maximum, at, index) => Math.max(maximum, at - availableTimes[index]),
      0,
    )
    : null;
  const maximumAllowedSampleGapMs = intervalMillis * Math.max(
    1,
    finiteNumber(maximumSampleGapRatio, 1.5),
  );
  const durationCoverageRatio = requiredDurationMs > 0
    ? Math.min(1, availableDurationMs / requiredDurationMs)
    : 0;
  const errors = [...new Set(attempted
    .filter((sample) => sample?.available !== true)
    .map((sample) => String(sample?.error || "process RSS sample unavailable")))];
  const total = processRssSeries(available.map((sample) => ({
    x: Number(sample.atMillis),
    y: Number(sample.totalRssBytes),
  })), totalThresholds, trendWindowCount);

  const observedTypes = available.flatMap((sample) => Object.keys(sample.byType || {}));
  const types = [...new Set([
    ...(Array.isArray(processTypes) ? processTypes : []),
    ...Object.keys(typeThresholds || {}),
    ...observedTypes,
  ].map(normalizeChromeProcessType))];
  const byType = {};
  for (const type of types) {
    const points = available.flatMap((sample) => {
      const group = sample.byType?.[type];
      return group?.available === true && Number.isFinite(Number(group.rssBytes))
        ? [{x: Number(sample.atMillis), y: Number(group.rssBytes)}]
        : [];
    });
    byType[type] = {
      ...processRssSeries(points, typeThresholds?.[type], trendWindowCount),
      availabilityRatio: attempted.length > 0 ? points.length / attempted.length : 0,
    };
  }

  const requiredTypes = (Array.isArray(requiredProcessTypes) ? requiredProcessTypes : [])
    .map(normalizeChromeProcessType);
  const minimumRatio = Math.max(0, Math.min(1, finiteNumber(minimumAvailableRatio, 0.9)));
  const minimumDurationRatio = Math.max(
    0,
    Math.min(1, finiteNumber(minimumDurationCoverageRatio, 0.98)),
  );
  const unavailableRequiredTypes = requiredTypes.filter(
    (type) => !byType[type] || byType[type].availabilityRatio < minimumRatio,
  );
  const failures = [
    ...(total.failed ? ["total"] : []),
    ...Object.entries(byType)
      .filter(([, metric]) => metric.failed)
      .map(([type]) => type),
  ];
  const cleanupAvailable = (Array.isArray(cleanupSamples) ? cleanupSamples : [])
    .filter((sample) => sample?.available === true
      && Number.isFinite(Number(sample.totalRssBytes)));
  const cleanupLast = cleanupAvailable.at(-1) || null;

  let verdict = "not-evaluated";
  const reasons = [];
  if (durationMs >= requiredDurationMs) {
    if (failures.length > 0) {
      verdict = "fail";
      reasons.push(
        `Chrome RSS exceeded a sustained-growth or absolute-peak threshold: ${failures.join(", ")}`,
      );
    } else if (available.length < Math.max(minimumExpectedSampleCount, trendWindowCount)
        || availabilityRatio < minimumRatio) {
      verdict = "inconclusive";
      reasons.push(
        `Chrome process RSS captured ${available.length}/${expectedSampleCount} expected samples with ${round(availabilityRatio * 100)}% attempt availability`,
      );
    } else if (durationCoverageRatio < minimumDurationRatio) {
      verdict = "inconclusive";
      reasons.push(
        `Chrome process RSS covered ${round(durationCoverageRatio * 100)}% of the required soak duration`,
      );
    } else if (maximumSampleGapMs == null
        || maximumSampleGapMs > maximumAllowedSampleGapMs) {
      verdict = "inconclusive";
      reasons.push(
        `Chrome process RSS maximum sample gap was ${round(maximumSampleGapMs)} ms; `
          + `maximum is ${round(maximumAllowedSampleGapMs)} ms`,
      );
    } else if (unavailableRequiredTypes.length > 0) {
      verdict = "inconclusive";
      reasons.push(
        `required Chrome process types lacked RSS coverage: ${unavailableRequiredTypes.join(", ")}`,
      );
    } else {
      verdict = "pass";
    }
  }

  return {
    verdict,
    reasons,
    durationMs,
    requiredDurationMs,
    availability: {
      attemptedSampleCount: attempted.length,
      availableSampleCount: available.length,
      unavailableSampleCount: attempted.length - available.length,
      ratio: round(availabilityRatio, 6),
      minimumRequiredRatio: minimumRatio,
      sampleIntervalMs: intervalMillis,
      expectedSampleCount,
      minimumExpectedSampleCount,
      availableDurationMs,
      durationCoverageRatio: round(durationCoverageRatio, 6),
      minimumDurationCoverageRatio: minimumDurationRatio,
      maximumSampleGapMs,
      maximumAllowedSampleGapMs,
      maximumSampleGapRatio: Math.max(1, finiteNumber(maximumSampleGapRatio, 1.5)),
      errors,
    },
    total,
    byType,
    requiredProcessTypes: requiredTypes,
    unavailableRequiredTypes,
    failedMetrics: failures,
    cleanup: {
      attemptedSampleCount: Array.isArray(cleanupSamples) ? cleanupSamples.length : 0,
      availableSampleCount: cleanupAvailable.length,
      lastTotalRssBytes: cleanupLast?.totalRssBytes ?? null,
      lastByType: cleanupLast?.byType ?? null,
    },
  };
}

export function summarizeMemoryTrend({
  regularSamples = [],
  postGcSamples = [],
  durationMs = 0,
  requiredDurationMs = 1800000,
  loadedChunkDelta = null,
  postGcFinalWindows = 4,
  retainedGrowthPercentMax = 15,
  retainedGrowthBytesMax = 256 * MIB,
  peakUsedBytesMax = 8 * 1024 * MIB,
  plateauSlopeMiBPerMinuteMax = 1,
  attemptedSampleTimes = [],
  sampleIntervalMs = 5000,
  minimumAvailableRatio = 0.9,
  minimumDurationCoverageRatio = 0.98,
  maximumSampleGapRatio = 1.5,
} = {}) {
  const regular = regularSamples
    .map((sample) => ({
      x: finiteNumber(sample.atMillis),
      y: finiteNumber(sample.totalUsedBytes, NaN),
    }))
    .filter((sample) => Number.isFinite(sample.x) && Number.isFinite(sample.y));
  const postGc = postGcSamples
    .filter((sample) => sample?.supported !== false)
    .map((sample) => ({
      x: finiteNumber(sample.atMillis),
      y: finiteNumber(sample.totalUsedBytes, NaN),
    }))
    .filter((sample) => Number.isFinite(sample.x) && Number.isFinite(sample.y))
    .sort((left, right) => left.x - right.x);
  const regularSlope = linearSlope(regular);
  const postGcSlope = linearSlope(postGc);
  const base = postGc[0]?.y ?? null;
  const end = postGc.at(-1)?.y ?? null;
  const retainedGrowthRatio = base > 0 && end != null ? (end - base) / base : null;
  const retainedGrowthBytes = base != null && end != null ? end - base : null;
  const regularPeakUsedBytes = regular.length > 0
    ? Math.max(...regular.map((sample) => sample.y)) : null;
  const postGcPeakUsedBytes = postGc.length > 0
    ? Math.max(...postGc.map((sample) => sample.y)) : null;
  const finalWindowCount = Math.max(2, Math.floor(finiteNumber(postGcFinalWindows, 4)));
  const finalIntervals = postGc.slice(-finalWindowCount);
  const finalThreePositive = finalIntervals.length === finalWindowCount
    && finalIntervals.slice(1).every((sample, index) => sample.y > finalIntervals[index].y);
  const coverage = summarizeTimedSampleCoverage({
    attemptedSampleTimes,
    availableSampleTimes: regular.length > 0
      ? regular.map((sample) => sample.x)
      : postGc.map((sample) => sample.x),
    requiredDurationMs,
    sampleIntervalMs,
    minimumAvailableRatio,
    minimumDurationCoverageRatio,
    maximumSampleGapRatio,
  });
  const postGcSlopeMiBPerMinute = postGcSlope == null
    ? null : postGcSlope * 60000 / MIB;
  const leakSignal = finalThreePositive
    || (retainedGrowthRatio != null && retainedGrowthRatio * 100 > retainedGrowthPercentMax)
    || (retainedGrowthBytes != null && retainedGrowthBytes > retainedGrowthBytesMax);
  const plateau = !leakSignal
    && postGcSlopeMiBPerMinute != null
    && Math.abs(postGcSlopeMiBPerMinute) <= plateauSlopeMiBPerMinuteMax;

  let verdict = "not-evaluated";
  const reasons = [];
  if (durationMs >= requiredDurationMs) {
    if (postGc.length < finalWindowCount) {
      verdict = "inconclusive";
      reasons.push(
        `Chrome did not provide the required ${finalWindowCount} post-GC samples`,
      );
    } else if (finalThreePositive) {
      verdict = "fail";
      reasons.push("Post-GC heap increased across each of the final three windows");
    } else if (regularPeakUsedBytes != null && regularPeakUsedBytes > peakUsedBytesMax) {
      verdict = "fail";
      reasons.push(`Regular heap peak exceeded ${peakUsedBytesMax} bytes`);
    } else if (retainedGrowthRatio != null
        && retainedGrowthRatio * 100 > retainedGrowthPercentMax) {
      verdict = "fail";
      reasons.push(`Retained post-GC heap grew by more than ${retainedGrowthPercentMax}%`);
    } else if (retainedGrowthBytes != null
        && retainedGrowthBytes > retainedGrowthBytesMax) {
      verdict = "fail";
      reasons.push(`Retained post-GC heap grew by more than ${retainedGrowthBytesMax} bytes`);
    } else if (!coverage.complete) {
      verdict = "inconclusive";
      reasons.push(
        `heap memory samples covered ${round(coverage.durationCoverageRatio * 100)}% of the required duration `
          + `with ${coverage.availableSampleCount}/${coverage.attemptedSampleCount || coverage.expectedSampleCount} usable samples`,
      );
    } else {
      verdict = "pass";
    }
  }

  return {
    verdict,
    reasons,
    regularSampleCount: regular.length,
    postGcSampleCount: postGc.length,
    regularSlopeMiBPerMinute: regularSlope == null ? null : round(regularSlope * 60000 / MIB, 6),
    postGcSlopeMiBPerMinute: postGcSlopeMiBPerMinute == null
      ? null : round(postGcSlopeMiBPerMinute, 6),
    firstPostGcMiB: base == null ? null : round(base / MIB),
    lastPostGcMiB: end == null ? null : round(end / MIB),
    retainedGrowthPercent: retainedGrowthRatio == null ? null : round(retainedGrowthRatio * 100),
    retainedGrowthBytes: retainedGrowthBytes == null ? null : round(retainedGrowthBytes),
    retainedGrowthMiB: retainedGrowthBytes == null ? null : round(retainedGrowthBytes / MIB),
    peakUsedMiB: regularPeakUsedBytes == null ? null : round(regularPeakUsedBytes / MIB),
    regularPeakUsedMiB: regularPeakUsedBytes == null
      ? null : round(regularPeakUsedBytes / MIB),
    postGcPeakUsedMiB: postGcPeakUsedBytes == null
      ? null : round(postGcPeakUsedBytes / MIB),
    coverage,
    thresholds: {
      retainedGrowthPercentMax,
      retainedGrowthBytesMax,
      peakUsedBytesMax,
      plateauSlopeMiBPerMinuteMax,
    },
    finalThreeWindowsPositive: finalThreePositive,
    leakSignal,
    plateau,
    retainedTrend: leakSignal ? "leak-signal" : (plateau ? "plateau" : "non-plateau"),
    postGcFinalWindows: finalWindowCount,
    loadedChunkDelta,
  };
}

export function summarizeNativeMemoryTrend({
  regularSamples = [],
  postGcSamples = [],
  cleanupSamples = [],
  baseline = null,
  durationMs = 0,
  requiredDurationMs = 1800000,
  postGcFinalWindows = 4,
  attemptedSampleTimes = [],
  sampleIntervalMs = 5000,
  minimumAvailableRatio = 0.9,
  minimumDurationCoverageRatio = 0.98,
  maximumSampleGapRatio = 1.5,
  cleanupSourceComplete = true,
} = {}) {
  const fields = ["liveBytes", "liveRegions", "associatedBuffers"];
  const clean = (samples) => samples
    .map((sample) => ({
      atMillis: finiteNumber(sample?.atMillis, NaN),
      ...Object.fromEntries(fields.map((field) => [field, finiteNumber(sample?.[field], NaN)])),
    }))
    .filter((sample) => Number.isFinite(sample.atMillis)
      && fields.every((field) => Number.isFinite(sample[field])));
  const regular = clean(regularSamples);
  const postGc = clean(postGcSamples);
  const cleanup = clean(cleanupSamples);
  const reference = fields.every((field) => Number.isFinite(Number(baseline?.[field])))
    ? Object.fromEntries(fields.map((field) => [field, Number(baseline[field])]))
    : regular[0] || null;
  const cleanupLast = cleanup.at(-1) || null;
  const finalWindowCount = Math.max(2, Math.floor(finiteNumber(postGcFinalWindows, 4)));
  const coverage = summarizeTimedSampleCoverage({
    attemptedSampleTimes,
    availableSampleTimes: regular.map((sample) => sample.atMillis),
    requiredDurationMs,
    sampleIntervalMs,
    minimumAvailableRatio,
    minimumDurationCoverageRatio,
    maximumSampleGapRatio,
  });
  const tolerances = {
    liveBytes: Math.max(MIB, Number(reference?.liveBytes || 0) * 0.05),
    liveRegions: Math.max(16, Number(reference?.liveRegions || 0) * 0.05),
    associatedBuffers: Math.max(16, Number(reference?.associatedBuffers || 0) * 0.05),
  };
  const metrics = {};
  for (const field of fields) {
    const finalWindows = postGc.slice(-finalWindowCount).map((sample) => sample[field]);
    const finalThreePositive = finalWindows.length === finalWindowCount
      && finalWindows.slice(1).every((value, index) => value > finalWindows[index]);
    const first = postGc[0]?.[field] ?? null;
    const last = postGc.at(-1)?.[field] ?? null;
    const peak = regular.length > 0 ? Math.max(...regular.map((sample) => sample[field])) : null;
    const cleanupLimit = reference == null ? null : reference[field] + tolerances[field];
    const recoveredAfterExit = cleanupLast != null && cleanupLimit != null
      ? cleanupLast[field] <= cleanupLimit
      : null;
    metrics[field] = {
      firstPostGc: first,
      lastPostGc: last,
      peak,
      finalThreeWindowsPositive: finalThreePositive,
      baseline: reference?.[field] ?? null,
      cleanupFinal: cleanupLast?.[field] ?? null,
      cleanupLimit: cleanupLimit == null ? null : round(cleanupLimit),
      recoveredAfterExit,
    };
  }

  let verdict = "not-evaluated";
  const reasons = [];
  if (durationMs >= requiredDurationMs) {
    if (postGc.length < finalWindowCount) {
      verdict = "inconclusive";
      reasons.push(
        `fewer than ${finalWindowCount} post-GC BrowserMemory samples were captured`,
      );
    } else if (fields.some((field) => metrics[field].finalThreeWindowsPositive)) {
      verdict = "fail";
      reasons.push("a BrowserMemory live metric increased across all final three post-GC windows");
    } else if (!coverage.complete) {
      verdict = "inconclusive";
      reasons.push(
        `BrowserMemory samples covered ${round(coverage.durationCoverageRatio * 100)}% of the required duration `
          + `with ${coverage.availableSampleCount}/${coverage.attemptedSampleCount || coverage.expectedSampleCount} usable samples`,
      );
    } else if (cleanupSourceComplete !== true) {
      verdict = "inconclusive";
      reasons.push("post-exit BrowserMemory cleanup did not include complete page and Worker evidence");
    } else if (cleanupLast == null || reference == null) {
      verdict = "inconclusive";
      reasons.push("pre-world baseline or post-exit BrowserMemory cleanup samples are unavailable");
    } else if (fields.some((field) => metrics[field].recoveredAfterExit !== true)) {
      verdict = "fail";
      reasons.push("BrowserMemory live bytes, regions, or associations did not recover after leaving the world");
    } else {
      verdict = "pass";
    }
  }
  return {
    verdict,
    reasons,
    regularSampleCount: regular.length,
    postGcSampleCount: postGc.length,
    cleanupSampleCount: cleanup.length,
    postGcFinalWindows: finalWindowCount,
    coverage,
    cleanupSourceComplete: cleanupSourceComplete === true,
    metrics,
  };
}

export function evaluatePerformanceGates({
  profile,
  environment,
  frames,
  freezes,
  queues,
  decodedPacketQueue,
  memory,
  gameplayAuthority,
  stability,
  runtimeInvariants,
}) {
  const gates = profile?.gates || {};
  const frameFailures = [];
  const averageFps = hasOwnFinite(frames, "averageFpsRaw")
    ? Number(frames.averageFpsRaw) : Number(frames?.averageFps);
  const onePercentLowFps = hasOwnFinite(frames, "onePercentLowFpsRaw")
    ? Number(frames.onePercentLowFpsRaw) : Number(frames?.onePercentLowFps);
  const p99FrameMs = hasOwnFinite(frames, "p99FrameMsRaw")
    ? Number(frames.p99FrameMsRaw) : Number(frames?.p99FrameMs);
  const longestFrameMs = hasOwnFinite(frames, "longestFrameMsRaw")
    ? Number(frames.longestFrameMsRaw) : Number(frames?.longestFrameMs);
  const coverageRatio = hasOwnFinite(frames, "coverageRatioRaw")
    ? Number(frames.coverageRatioRaw) : Number(frames?.coverageRatio);
  if (!(finiteNumber(frames?.sampleCount, 0) > 0)
      || !(finiteNumber(frames?.rawFrameCount, 0) > 0)) {
    frameFailures.push("Minecraft produced no measured BrowserGlfw frames");
  }
  if (finiteNumber(frames?.invalidFrameIntervalCount, 0) > 0) {
    frameFailures.push(
      `${Number(frames.invalidFrameIntervalCount)} invalid or zero frame intervals were captured`,
    );
  }
  if (gates.averageFpsMin != null && !(averageFps >= gates.averageFpsMin)) {
    frameFailures.push(`average FPS ${averageFps} is below ${gates.averageFpsMin}`);
  }
  if (gates.onePercentLowFpsMin != null
      && !(onePercentLowFps >= gates.onePercentLowFpsMin)) {
    frameFailures.push(`1% low FPS ${onePercentLowFps} is below ${gates.onePercentLowFpsMin}`);
  }
  if (gates.coverageRatioMin != null && !(coverageRatio >= gates.coverageRatioMin)) {
    frameFailures.push(`frame sample coverage ${coverageRatio} is below ${gates.coverageRatioMin}`);
  }
  if (gates.p99FrameMsMax != null && !(p99FrameMs <= gates.p99FrameMsMax)) {
    frameFailures.push(`P99 frame time ${p99FrameMs} ms exceeds ${gates.p99FrameMsMax} ms`);
  }
  if (gates.longestFrameMsMax != null
      && !(longestFrameMs <= gates.longestFrameMsMax)) {
    frameFailures.push(
      `longest frame ${longestFrameMs} ms exceeds ${gates.longestFrameMsMax} ms`,
    );
  }
  const freezeCountAvailable = hasOwnFinite(freezes, "total");
  const freezeCount = freezeCountAvailable ? Number(freezes.total) : null;
  if (freezeCountAvailable && gates.freezeCountMax != null && freezeCount > gates.freezeCountMax) {
    frameFailures.push(`freeze count ${freezeCount} exceeds ${gates.freezeCountMax}`);
  }

  const independent = {
    environment: {
      verdict: environment?.valid ? "pass" : "invalid",
      reasons: environment?.issues || [],
    },
    framePerformance: {
      verdict: frameFailures.length === 0 ? "pass" : "fail",
      reasons: frameFailures,
    },
    freezes: {
      verdict: !freezeCountAvailable
        ? "inconclusive"
        : (freezeCount <= finiteNumber(gates.freezeCountMax, 0) ? "pass" : "fail"),
      reasons: !freezeCountAvailable
        ? ["freeze telemetry total is missing or non-finite"]
        : (freezes?.reasons || []),
    },
    queues: gates.queueHighWater
      ? {verdict: queues?.verdict || "inconclusive", reasons: queues?.reasons || []}
      : {verdict: "not-required", reasons: []},
    decodedPacketQueue: gates.decodedPacketQueue
      ? {
          verdict: decodedPacketQueue?.verdict || "inconclusive",
          reasons: decodedPacketQueue?.reasons || [],
        }
      : {verdict: "not-required", reasons: []},
    memory: gates.memory
      ? {verdict: memory?.verdict || "inconclusive", reasons: memory?.reasons || []}
      : {verdict: "not-required", reasons: []},
    gameplayAuthority: gates.gameplayAuthority
      ? {
          verdict: gameplayAuthority?.verdict || "inconclusive",
          reasons: gameplayAuthority?.reasons || [],
        }
      : {verdict: "not-required", reasons: []},
    stability: {
      verdict: stability?.verdict || "inconclusive",
      reasons: stability?.reasons || [],
    },
    runtimeInvariants: gates.runtimeInvariants
      ? {
          verdict: runtimeInvariants?.verdict || "inconclusive",
          reasons: runtimeInvariants?.reasons || [
            "required runtime invariant telemetry was not evaluated",
          ],
        }
      : {verdict: "not-required", reasons: []},
  };
  for (const [name, gate] of Object.entries(independent)) {
    if (gate.verdict === "not-evaluated") {
      gate.verdict = "inconclusive";
      gate.reasons = [...(gate.reasons || []), `${name} evidence was not evaluated`];
    }
  }
  const verdicts = Object.values(independent).map((gate) => gate.verdict);
  let overall = "pass";
  if (verdicts.includes("invalid")) {
    overall = "invalid";
  } else if (verdicts.includes("fail")) {
    overall = "fail";
  } else if (verdicts.includes("inconclusive")) {
    overall = "inconclusive";
  }
  return {overall, independent};
}

export function parseOptionsText(text) {
  const values = {};
  for (const line of String(text || "").split(/\r?\n/)) {
    const separator = line.indexOf(":");
    if (separator <= 0) {
      continue;
    }
    const key = line.slice(0, separator).trim();
    let value = line.slice(separator + 1).trim();
    if (value.startsWith('"') && value.endsWith('"')) {
      try {
        value = JSON.parse(value);
      } catch {
        // Keep the original string when a user-edited value is not valid JSON.
      }
    } else if (/^-?\d+(?:\.\d+)?$/.test(value)) {
      value = Number(value);
    } else if (value === "true" || value === "false") {
      value = value === "true";
    }
    values[key] = value;
  }
  return values;
}
