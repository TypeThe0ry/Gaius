/*
 * Explicit high-load multiplayer runner.
 *
 * The ordinary smoke and --acceptance contracts stay bounded at 2/4 clients.
 * This entry point is the only supported way to opt into the fixed 8/16 client,
 * radius-8 / 257-unique-chunk extreme tiers.
 */

import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

const fullPathScript = fileURLToPath(
    new URL("./browser-full-path-smoke.mjs", import.meta.url));
const canonicalProfilePath = fileURLToPath(
    new URL("../../port/versions/26.2.json", import.meta.url));
const canonicalEvidenceRoot = fileURLToPath(
    new URL("../../port/target/26.2/browser-relay-full-path-evidence/", import.meta.url));
const HARD_DEADLINES_MILLIS = Object.freeze({
    8: 20 * 60_000,
    16: 40 * 60_000,
});
const INBOUND_FLOW_EVIDENCE_FIELDS = Object.freeze([
    "flowPausedChannels",
    "decodeFlowPausedChannels",
    "activeHighWatermarks",
    "decodedSliceBacklog",
    "decoderCumulationBytes",
    "decodedPacketQueue",
    "flowPauses",
    "flowResumes",
    "decodedSliceBacklogPauses",
    "decodedSliceBacklogResumes",
    "inlineDecodedPackets",
    "maxDecoderCumulationBytes",
    "maxDecodedPacketQueue",
    "highWatermarkDurationMillis",
    "longestHighWatermarkMillis",
    "activeHighWatermarkLongestMillis",
]);
const INBOUND_FLOW_EVENT_FIELDS = Object.freeze([
    "sequence",
    "channelId",
    "reason",
    "startedAtMillis",
    "endedAtMillis",
    "durationMillis",
    "startDepth",
    "endDepth",
    "startQueuedBytes",
    "endQueuedBytes",
]);
const INBOUND_FLOW_DURATION_FIELDS = new Set([
    "highWatermarkDurationMillis",
    "longestHighWatermarkMillis",
    "activeHighWatermarkLongestMillis",
]);
const MAX_STDOUT_BYTES = 64 * 1024 * 1024;
const MAX_DIAGNOSTIC_TAIL_BYTES = 64 * 1024;
const argumentsList = process.argv.slice(2);
const printConfigOnly = removeFlag("--print-config");
const runAll = removeFlag("--all");
const selectedTier = readTierArgument();
if (argumentsList.length !== 0) {
    throw new Error(`Unsupported stress runner arguments: ${argumentsList.join(" ")}`);
}
if (runAll && selectedTier !== undefined) {
    throw new Error("--all and --tier are mutually exclusive");
}
const tiers = runAll ? [8, 16] : [selectedTier ?? 8];

const configurations = [];
const runs = [];
const results = [];
for (const tier of tiers) {
    const environment = stressEnvironment(tier);
    const configurationRun = await runChild(
        ["--print-config", "--stress"], environment, 30_000, false);
    const configuration = requireSuccessfulResult(
        configurationRun, `stress tier ${tier} configuration`);
    assert.equal(configuration.stressMode, true);
    assert.equal(configuration.acceptanceMode, false);
    assert.equal(configuration.stressTier, tier);
    assert.equal(configuration.profile.id, "26.2");
    assert.equal(configuration.profile.protocolVersion, 776);
    assert.equal(configuration.profile.worldVersion, 4903);
    assert.equal(configuration.clients, tier);
    assert.equal(configuration.performanceContract.mode, `stress-tier-${tier}`);
    assert.equal(configuration.performanceContract.minimumChunkMetric,
        "unique-chunk-position");
    assert.equal(configuration.performanceContract.chunkWindow.maximumUniqueChunkCapacity, 257);
    assert.equal(
        configuration.performanceContract.chunkWindow
            .observedDistanceContractRequiredBeforeCounting,
        true,
    );
    assert.deepEqual(configuration.performanceContract.chunkWindow.initialDistanceContract, {
        source: "clientbound-login",
        packetId: 49,
        fields: ["chunkRadius", "simulationDistance"],
    });
    assert.deepEqual(configuration.performanceContract.chunkWindow.observedDistancePackets, {
        cacheCenter: 94,
        cacheRadius: 95,
        simulationDistance: 111,
    });
    assert.equal(configuration.performanceContract.chunkBatch.acknowledgementEncoding,
        "float32-be");
    assert.deepEqual(configuration.performanceContract.stressLatencyDistribution.pollGap, {
        p99Millis: 16.7,
        p999Millis: 50,
        maxMillis: 100,
    });
    assert.deepEqual(configuration.performanceContract.stressLatencyDistribution.playTickGap, {
        p99Millis: 60,
        p999Millis: 75,
        maxMillis: 100,
    });
    assert.deepEqual(
        configuration.performanceContract.stressLatencyDistribution.preMinimumChunkGap,
        { p99Millis: 100, maxMillis: 250 },
    );
    const schedulerContract = configuration.performanceContract.pollScheduler;
    assert.ok(schedulerContract && typeof schedulerContract === "object",
        `stress tier ${tier} omitted poll scheduler contract`);
    assert.equal(schedulerContract.schemaVersion,
        "gaius.browser-client-poll-scheduler.v1");
    assert.equal(schedulerContract.mode,
        "round-robin-bounded-batch-per-macrotask");
    assert.equal(schedulerContract.maxBatchClients, 4);
    assert.equal(schedulerContract.callbackWorkBudgetMillis, 8);
    assert.equal(schedulerContract.callbackBudgetCoversPlayTicks, true);
    assert.equal(schedulerContract.maxPlayTicksPerSchedulerCallback, 4);
    assert.equal(schedulerContract.maxVisibleDispatchSkew, 1);
    assert.equal(schedulerContract.strictEventLoopMaxMillis, 100);
    assert.equal(schedulerContract.rawLatencyEvidence, true);
    assert.equal(schedulerContract.dueState,
        "side-map-authoritative-client-property-mirror-only");
    assert.equal(schedulerContract.immediateInboundPriority,
        "client-method-buffer-then-bridge");
    assert.deepEqual(schedulerContract.callbackTail, {
        schemaVersion: "gaius.browser-client-poll-callback-tail.v1",
        slowThresholdMillis: 16.7,
        sampleLimit: 64,
        retention: "longest-duration-desc-sequence-asc",
        strictRawDurationGateMillis: 16.7,
    });
    configurations.push(configuration);
    if (!printConfigOnly) {
        const run = await runChild(
            ["--stress"], environment, HARD_DEADLINES_MILLIS[tier], true);
        let resultValidationError = null;
        if (run.result !== null) {
            try {
                validateStressResult(run.result, tier, configuration);
            }
            catch (error) {
                resultValidationError = String(error?.stack || error);
            }
        }
        const { result, ...processEvidence } = run;
        runs.push({
            tier,
            evidenceRoot: canonicalEvidenceRoot,
            ...processEvidence,
            resultOk: result?.ok ?? null,
            resultValidationError,
        });
        if (run.result !== null) results.push(run.result);
    }
}

const ok = printConfigOnly || runs.every((run) =>
    run.exitCode === 0 && !run.timedOut && run.parseError === null &&
    run.resultOk === true && run.resultValidationError === null);
console.log(JSON.stringify({
    schemaVersion: "browser-full-path-stress-result-v1",
    ok,
    mode: printConfigOnly ? "print-config" : "stress",
    tiers,
    hardDeadlinesMillis: Object.fromEntries(tiers.map((tier) => [
        tier, HARD_DEADLINES_MILLIS[tier],
    ])),
    evidenceRoot: canonicalEvidenceRoot,
    configurations,
    runs,
    results,
}));
if (!ok) process.exitCode = 1;

function validateStressResult(result, tier, configuration) {
    assert.equal(result?.ok, true, `stress tier ${tier} child result was not ok`);
    assert.equal(result?.profile?.id, "26.2");
    assert.equal(result?.profile?.protocolVersion, 776);
    assert.equal(result?.transport?.stressMode, true,
        `stress tier ${tier} child did not report stress mode`);
    assert.equal(result?.transport?.stressTier, tier,
        `stress tier ${tier} child reported the wrong tier`);
    assert.equal(result?.transport?.clients, configuration.stressTarget.clients);
    assert.equal(result?.transport?.reconnectWaves,
        configuration.stressTarget.reconnectWaves);
    assert.equal(result?.transport?.expectedConnections,
        configuration.stressTarget.clientLifecycles);
    assert.equal(result?.acceptance?.mode, `stress-tier-${tier}`);
    assertStressSchedulerEvidence(result, tier, configuration);
    const observed = result?.acceptance?.observed;
    assert.equal(observed?.clients, configuration.stressTarget.clients);
    assert.equal(observed?.minimumChunkPackets,
        configuration.stressTarget.minimumChunkPackets);
    assert.equal(observed?.uniqueChunkTarget, true);
    assert.equal(observed?.soakMillis, configuration.stressTarget.soakMillis);
    assert.ok(observed?.actualSoakMillis >= configuration.stressTarget.soakMillis,
        `stress tier ${tier} observed only ${observed?.actualSoakMillis}ms soak`);
    assert.equal(observed?.reconnectWaveCount,
        configuration.stressTarget.reconnectWaves);
    assert.equal(observed?.expectedConnections,
        configuration.stressTarget.clientLifecycles);
    assert.ok(Array.isArray(result?.clients));
    assert.equal(result.clients.length, configuration.stressTarget.clientLifecycles,
        `stress tier ${tier} omitted a client lifecycle`);
    for (const [index, client] of result.clients.entries()) {
        assert.ok(client?.chunkWindow?.uniqueChunkPositionsTowardTarget >=
            configuration.stressTarget.minimumChunkPackets,
        `stress tier ${tier} client lifecycle ${index + 1} observed only ` +
            `${client?.chunkWindow?.uniqueChunkPositionsTowardTarget} target chunks`);
        assert.equal(client?.chunkWindow?.observedChunkCacheRadius,
            configuration.stressTarget.serverViewDistance);
        assert.equal(client?.chunkWindow?.observedSimulationDistance,
            configuration.stressTarget.simulationDistance);
    }
    assert.ok(Array.isArray(result?.reconnectWaves));
    assert.equal(result.reconnectWaves.length, configuration.stressTarget.reconnectWaves,
        `stress tier ${tier} root reconnect evidence count drifted`);
    const inboundFlow = result?.inboundFlow ??
        result?.acceptance?.observed?.inboundFlow;
    assert.ok(inboundFlow && typeof inboundFlow === "object",
        `stress tier ${tier} omitted inbound-flow evidence`);
    assert.deepEqual(result?.acceptance?.observed?.inboundFlow, inboundFlow,
        `stress tier ${tier} observed inbound-flow evidence diverged`);
    assert.deepEqual(result?.acceptance?.actual?.inboundFlow, inboundFlow,
        `stress tier ${tier} actual inbound-flow evidence diverged`);
    const pauseLimitMillis =
        configuration.performanceContract.multiplayerPerformance.maxSoakPhaseStallMillis;
    const orderedStages = [];
    assertInboundFlowStage(inboundFlow.initial,
        inboundFlow.initial?.label ?? "initial connect through minimum chunks",
        pauseLimitMillis, {
            diagnosticLabel: `stress tier ${tier} initial`,
        });
    orderedStages.push(inboundFlow.initial);
    assert.ok(Array.isArray(inboundFlow.reconnectWaves),
        `stress tier ${tier} reconnect inbound-flow evidence is not an array`);
    assert.equal(inboundFlow.reconnectWaves.length,
        configuration.stressTarget.reconnectWaves,
        `stress tier ${tier} omitted a reconnect inbound-flow stage`);
    for (let wave = 1; wave <= configuration.stressTarget.reconnectWaves; wave++) {
        const evidence = inboundFlow.reconnectWaves[wave - 1];
        assert.equal(evidence?.wave, wave,
            `stress tier ${tier} inbound-flow wave order drifted`);
        assertInboundFlowStage(evidence?.preDrop,
            evidence?.preDrop?.label ??
                `reconnect wave ${wave} pre-drop browser inbound flow resume`,
            pauseLimitMillis, {
                diagnosticLabel: `stress tier ${tier} reconnect wave ${wave} pre-drop`,
            });
        orderedStages.push(evidence.preDrop);
        assertInboundFlowStage(evidence,
            evidence?.label ?? `reconnect wave ${wave} browser inbound flow resume`,
            pauseLimitMillis, {
                diagnosticLabel: `stress tier ${tier} reconnect wave ${wave}`,
            });
        assert.deepEqual(result.reconnectWaves[wave - 1]?.inboundFlow, evidence,
            `stress tier ${tier} reconnect wave ${wave} root evidence diverged`);
        orderedStages.push(evidence);
    }
    assertInboundFlowStage(inboundFlow.postSoak,
        inboundFlow.postSoak?.label ?? "post-soak browser inbound flow resume",
        pauseLimitMillis, {
            diagnosticLabel: `stress tier ${tier} post-soak`,
        });
    orderedStages.push(inboundFlow.postSoak);
    assertInboundFlowStage(inboundFlow.finalCleanup,
        inboundFlow.finalCleanup?.label ?? "final browser transport cleanup",
        pauseLimitMillis, {
            requireCleanup: true,
            diagnosticLabel: `stress tier ${tier} final cleanup`,
        });
    orderedStages.push(inboundFlow.finalCleanup);
    const stageTimes = orderedStages.map((stage) => stage.capturedAtElapsedMillis);
    for (let index = 1; index < stageTimes.length; index++) {
        assert.ok(stageTimes[index] >= stageTimes[index - 1],
            `stress tier ${tier} inbound-flow timestamps were not monotonic`);
    }
    const soak = result?.acceptance?.observed?.soakPerformance;
    assert.ok(soak && Number.isFinite(soak.maxContinuousFlowPauseMillis),
        `stress tier ${tier} omitted continuous inbound-pause evidence`);
    assert.equal(soak?.ok, true, `stress tier ${tier} soak was not healthy`);
    assert.deepEqual(soak?.violations, [],
        `stress tier ${tier} soak retained liveness violations`);
    assert.ok(soak.maxContinuousFlowPauseMillis <=
        configuration.performanceContract.multiplayerPerformance.maxSoakPhaseStallMillis,
    `stress tier ${tier} inbound flow paused for ` +
        `${soak.maxContinuousFlowPauseMillis}ms`);
    const rawEventLoopMax = Number.isFinite(soak.rawMaxBrowserEventLoopGapMillis)
        ? soak.rawMaxBrowserEventLoopGapMillis
        : NaN;
    assert.ok(Number.isFinite(rawEventLoopMax) &&
        rawEventLoopMax <= configuration.performanceContract.pollScheduler
            .strictEventLoopMaxMillis,
    `stress tier ${tier} raw browser event-loop max reached ${rawEventLoopMax}ms`);
    assert.equal(soak.limits.maxBrowserEventLoopGapMillis,
        configuration.performanceContract.pollScheduler.strictEventLoopMaxMillis,
        `stress tier ${tier} event-loop limit drifted`);
    assertStressClientRawLatency(result.clients, tier);
}

function assertStressSchedulerEvidence(result, tier, configuration) {
    const scheduler = result?.pollScheduler;
    assert.ok(scheduler && typeof scheduler === "object",
        `stress tier ${tier} omitted poll scheduler evidence`);
    assert.equal(scheduler.schemaVersion,
        "gaius.browser-client-poll-scheduler.v1");
    assert.equal(scheduler.mode,
        "round-robin-bounded-batch-per-macrotask");
    assert.equal(scheduler.maxBatchClients,
        configuration.performanceContract.pollScheduler.maxBatchClients);
    assert.equal(scheduler.callbackWorkBudgetMillis,
        configuration.performanceContract.pollScheduler.callbackWorkBudgetMillis);
    assert.equal(scheduler.callbackBudgetCoversPlayTicks, true,
        `stress tier ${tier} callback budget did not cover PLAY ticks`);
    // Runtime evidence names this observed quantity after the service path
    // (`maxPlayTickServicesPerCallback`); keep the contract's descriptive
    // `maxPlayTicksPerSchedulerCallback` name separate from the measured field.
    assert.equal(scheduler.maxPlayTickServicesPerCallback,
        configuration.performanceContract.pollScheduler.maxPlayTicksPerSchedulerCallback);
    assert.equal(scheduler.maxPlayTickServicesPerCallbackLimit,
        configuration.performanceContract.pollScheduler.maxPlayTicksPerSchedulerCallback);
    assert.equal(scheduler.stopped, true,
        `stress tier ${tier} poll scheduler was not stopped`);
    assert.equal(scheduler.overlappingCallbacks, 0,
        `stress tier ${tier} poll scheduler callbacks overlapped`);
    assert.equal(scheduler.playTickServiceErrors, 0,
        `stress tier ${tier} PLAY tick service reported an error`);
    for (const [name, value] of [
        ["callbacks", scheduler.callbacks],
        ["dispatchedPolls", scheduler.dispatchedPolls],
        ["maxClientsPerCallback", scheduler.maxClientsPerCallback],
        ["maxPlayTickServicesPerCallback", scheduler.maxPlayTickServicesPerCallback],
        ["callbackBudgetExhaustions", scheduler.callbackBudgetExhaustions],
        ["callbackBudgetOverruns", scheduler.callbackBudgetOverruns],
        ["callbackBudgetTickSkips", scheduler.callbackBudgetTickSkips],
        ["callbackBudgetPollSkips", scheduler.callbackBudgetPollSkips],
        ["dueMapEntries", scheduler.dueMapEntries],
    ]) {
        assert.ok(Number.isSafeInteger(value) && value >= 0,
            `stress tier ${tier} scheduler ${name} was invalid: ${value}`);
    }
    assert.ok(scheduler.maxClientsPerCallback <= scheduler.maxBatchClients,
        `stress tier ${tier} exceeded poll callback client cap`);
    assert.ok(scheduler.maxPlayTickServicesPerCallback <=
        scheduler.maxPlayTickServicesPerCallbackLimit,
    `stress tier ${tier} exceeded PLAY tick callback cap`);
    assert.equal(scheduler.dueMapEntries, 0,
        `stress tier ${tier} scheduler retained due-map entries after stop`);
    const callbackTail = scheduler.callbackTail;
    assert.ok(callbackTail && typeof callbackTail === "object",
        `stress tier ${tier} omitted callback-tail telemetry`);
    assert.equal(callbackTail.schemaVersion,
        configuration.performanceContract.pollScheduler.callbackTail.schemaVersion,
        `stress tier ${tier} callback-tail schema drifted`);
    assert.equal(callbackTail.slowThresholdMillis, 16.7,
        `stress tier ${tier} callback-tail threshold drifted`);
    assert.equal(callbackTail.sampleLimit, 64,
        `stress tier ${tier} callback-tail sample limit drifted`);
    for (const [name, value] of [
        ["slowCallbackSamplesTotal", callbackTail.slowCallbackSamplesTotal],
        ["slowCallbackSamplesDropped", callbackTail.slowCallbackSamplesDropped],
        ["retainedSampleCount", callbackTail.retainedSampleCount],
    ]) {
        assert.ok(Number.isSafeInteger(value) && value >= 0,
            `stress tier ${tier} callback-tail ${name} was invalid: ${value}`);
    }
    assert.ok(Array.isArray(callbackTail.samples),
        `stress tier ${tier} callback-tail samples were not an array`);
    assert.ok(callbackTail.samples.length <= callbackTail.sampleLimit,
        `stress tier ${tier} callback-tail ring exceeded its hard limit`);
    assert.equal(callbackTail.retainedSampleCount, callbackTail.samples.length,
        `stress tier ${tier} callback-tail retained count diverged`);
    assert.equal(callbackTail.slowCallbackSamplesTotal,
        callbackTail.retainedSampleCount + callbackTail.slowCallbackSamplesDropped,
        `stress tier ${tier} callback-tail total/drop accounting diverged`);
    for (let index = 0; index < callbackTail.samples.length; index++) {
        const sample = callbackTail.samples[index];
        assert.ok(sample && typeof sample === "object",
            `stress tier ${tier} callback-tail sample ${index} was invalid`);
        assert.ok(Number.isFinite(sample.durationRawMillis) &&
            sample.durationRawMillis >= callbackTail.slowThresholdMillis,
        `stress tier ${tier} callback-tail sample ${index} was below threshold`);
        if (index > 0) {
            const previous = callbackTail.samples[index - 1];
            assert.ok(previous.durationRawMillis > sample.durationRawMillis ||
                previous.durationRawMillis === sample.durationRawMillis &&
                    previous.callbackSequence <= sample.callbackSequence,
            `stress tier ${tier} callback-tail samples were not stably ordered`);
        }
    }
    if (scheduler.maxCallbackDurationRawMillis >= 16.7) {
        assert.ok(callbackTail.samples.length > 0,
            `stress tier ${tier} strict callback overrun had no retained sample`);
        assert.equal(callbackTail.samples[0].durationRawMillis,
            Math.max(...callbackTail.samples.map((sample) => sample.durationRawMillis)),
            `stress tier ${tier} callback-tail max sample was not retained first`);
    }
    for (const [name, value] of [
        ["maxCallbackDurationRawMillis", scheduler.maxCallbackDurationRawMillis],
        ["maxClientPollDurationRawMillis", scheduler.maxClientPollDurationRawMillis],
        ["maxInterCallbackGapRawMillis", scheduler.maxInterCallbackGapRawMillis],
        ["maxScheduleDelayRawMillis", scheduler.maxScheduleDelayRawMillis],
    ]) {
        assert.ok(Number.isFinite(value) && value >= 0,
            `stress tier ${tier} scheduler ${name} was not raw finite latency: ${value}`);
    }
    assert.ok(scheduler.maxCallbackDurationRawMillis <= 16.7,
        `stress tier ${tier} scheduler callback reached ` +
        `${scheduler.maxCallbackDurationRawMillis}ms (render budget 16.7ms)`);
    assert.ok(scheduler.maxClientPollDurationRawMillis <= 16.7,
        `stress tier ${tier} client poll reached ` +
        `${scheduler.maxClientPollDurationRawMillis}ms (render budget 16.7ms)`);
    assert.ok(Number.isFinite(scheduler.maxVisibleDispatchSkew) &&
        scheduler.maxVisibleDispatchSkew <=
            configuration.performanceContract.pollScheduler.maxVisibleDispatchSkew,
    `stress tier ${tier} visible dispatch skew reached ${scheduler.maxVisibleDispatchSkew}`);
    assert.equal(scheduler.maxVisibleDispatchSkewLimit,
        configuration.performanceContract.pollScheduler.maxVisibleDispatchSkew);
    assert.ok(Array.isArray(scheduler.visibleDispatchCounts),
        `stress tier ${tier} omitted visible dispatch counts`);
    assert.equal(scheduler.visibleClientCount, scheduler.visibleDispatchCounts.length);
    const visibleIds = new Set();
    const visibleCounts = [];
    for (const entry of scheduler.visibleDispatchCounts) {
        assert.ok(entry && (typeof entry.id === "number" || typeof entry.id === "string"),
            `stress tier ${tier} visible dispatch entry omitted id`);
        assert.ok(!visibleIds.has(entry.id),
            `stress tier ${tier} visible dispatch ids were duplicated`);
        visibleIds.add(entry.id);
        assert.ok(Number.isSafeInteger(entry.count) && entry.count >= 0,
            `stress tier ${tier} visible dispatch count was invalid`);
        visibleCounts.push(entry.count);
    }
    if (visibleCounts.length > 0) {
        assert.ok(Math.max(...visibleCounts) - Math.min(...visibleCounts) <=
            scheduler.maxVisibleDispatchSkewLimit,
        `stress tier ${tier} visible dispatch count snapshot was unfair`);
    }
    const eventLoop = scheduler.eventLoopDelay;
    assert.ok(eventLoop && eventLoop.available === true,
        `stress tier ${tier} scheduler event-loop telemetry unavailable`);
    assert.ok(Number.isFinite(eventLoop.rawMaxMillis) && eventLoop.rawMaxMillis >= 0,
        `stress tier ${tier} scheduler raw event-loop max missing`);
    assert.ok(eventLoop.rawMaxMillis <=
        configuration.performanceContract.pollScheduler.strictEventLoopMaxMillis,
    `stress tier ${tier} scheduler event-loop max reached ${eventLoop.rawMaxMillis}ms`);
    assert.deepEqual(result?.acceptance?.observed?.pollScheduler, scheduler,
        `stress tier ${tier} observed scheduler evidence diverged`);
    assert.deepEqual(result?.acceptance?.actual?.pollScheduler, scheduler,
        `stress tier ${tier} actual scheduler evidence diverged`);
}

function assertStressClientRawLatency(clients, tier) {
    for (const [index, client] of clients.entries()) {
        const performanceEvidence = client?.performance;
        assert.ok(performanceEvidence && typeof performanceEvidence === "object",
            `stress tier ${tier} client lifecycle ${index + 1} omitted performance evidence`);
        const checks = [
            ["pollGapHistogram", 100],
            ["playTickGapHistogram", 100],
            ["preMinimumChunkGapHistogram", 250],
        ];
        for (const [name, limit] of checks) {
            const histogram = performanceEvidence[name];
            assert.ok(histogram && Number.isSafeInteger(histogram.count) &&
                histogram.count > 0,
            `stress tier ${tier} client lifecycle ${index + 1} ${name} had no samples`);
            assert.ok(Number.isFinite(histogram.rawMaxMillis) &&
                histogram.rawMaxMillis >= 0 && histogram.rawMaxMillis <= limit,
            `stress tier ${tier} client lifecycle ${index + 1} ${name} raw max ` +
                `${histogram.rawMaxMillis}ms exceeded ${limit}ms`);
            const bounds = histogram.rawQuantileUpperBoundsMillis;
            assert.ok(bounds && Number.isFinite(bounds.p99Millis),
                `stress tier ${tier} client lifecycle ${index + 1} ${name} omitted raw quantile bounds`);
        }
    }
}

function assertInboundFlowStage(
    evidence,
    expectedLabel,
    configurationPauseLimitMillis,
    options = {},
) {
    const label = options.diagnosticLabel ?? expectedLabel;
    const requireCleanup = options.requireCleanup === true;
    const isWindowEvidence = evidence?.schemaVersion ===
        "gaius.browser-inbound-flow-window-evidence.v2";
    assert.ok(isWindowEvidence || evidence?.schemaVersion ===
        "gaius.browser-inbound-flow-evidence.v1", `${label} schema drifted`);
    const expectedSource = isWindowEvidence
        ? "BrowserWebSocketChannel.__gaiusNettyBridge.stats.highWatermarkEvents"
        : "BrowserWebSocketChannel.__gaiusNettyBridge.stats";
    assert.equal(evidence?.source, expectedSource,
        `${label} source drifted`);
    assert.equal(evidence?.label, expectedLabel, `${label} stage label drifted`);
    assert.equal(evidence?.available, true, `${label} telemetry was unavailable`);
    assert.equal(evidence?.ready, true, `${label} transport remained paused`);
    const withinPauseLimit = isWindowEvidence
        ? evidence?.windowWithinPauseLimit
        : evidence?.withinPauseLimit;
    const maximumPauseMillis = isWindowEvidence
        ? evidence?.windowMaximumPauseMillis
        : evidence?.maximumPauseMillis;
    const missing = isWindowEvidence
        ? evidence?.telemetryMissing
        : evidence?.missing;
    const fields = isWindowEvidence
        ? evidence?.eventFields
        : evidence?.fields;
    assert.equal(withinPauseLimit, true,
        `${label} exceeded the per-channel pause limit`);
    assert.ok(Number.isFinite(maximumPauseMillis) && maximumPauseMillis >= 0,
    `${label} maximum per-channel pause was invalid`);
    assert.deepEqual(missing, [], `${label} telemetry fields were missing`);
    assert.deepEqual(fields, [
        ...(isWindowEvidence ? INBOUND_FLOW_EVENT_FIELDS : INBOUND_FLOW_EVIDENCE_FIELDS),
    ],
        `${label} evidence field contract drifted`);
    assert.ok(Number.isFinite(Date.parse(evidence?.capturedAt)),
        `${label} capture timestamp was invalid`);
    assert.ok(Number.isFinite(evidence?.capturedAtElapsedMillis) &&
        evidence.capturedAtElapsedMillis >= 0,
    `${label} monotonic capture timestamp was invalid`);
    for (const name of INBOUND_FLOW_EVIDENCE_FIELDS) {
        const value = evidence?.observed?.[name];
        assert.ok(INBOUND_FLOW_DURATION_FIELDS.has(name)
            ? Number.isFinite(value) && value >= 0
            : Number.isSafeInteger(value) && value >= 0,
        `${label} field ${name} is not a valid non-negative metric`);
    }
    const derivedMaximumPauseMillis = Math.max(
        evidence.observed.longestHighWatermarkMillis,
        evidence.observed.activeHighWatermarkLongestMillis,
    );
    assert.equal(maximumPauseMillis, derivedMaximumPauseMillis,
        `${label} maximum pause derivation drifted`);
    assert.equal(evidence.pauseLimitMillis,
        configurationPauseLimitMillis,
        `${label} pause limit drifted`);
    assert.equal(withinPauseLimit,
        derivedMaximumPauseMillis <= configurationPauseLimitMillis,
        `${label} pause-limit verdict drifted`);
    assert.equal(evidence.observed.flowPausedChannels, 0, `${label} flow pause remained`);
    assert.equal(evidence.observed.decodeFlowPausedChannels, 0,
        `${label} decoder pause remained`);
    assert.equal(evidence.observed.activeHighWatermarks, 0,
        `${label} high watermark remained`);
    if (requireCleanup) {
        for (const name of [
            "decodedSliceBacklog",
            "decoderCumulationBytes",
            "decodedPacketQueue",
        ]) {
            assert.equal(evidence.observed[name], 0,
                `${label} retained ${name}`);
        }
    }
}

function removeFlag(flag) {
    const index = argumentsList.indexOf(flag);
    if (index === -1) return false;
    argumentsList.splice(index, 1);
    if (argumentsList.includes(flag)) {
        throw new Error(`${flag} may be supplied only once`);
    }
    return true;
}

function readTierArgument() {
    const equalsArguments = argumentsList.filter((value) => value.startsWith("--tier="));
    if (equalsArguments.length > 1) throw new Error("--tier may be supplied only once");
    let raw;
    if (equalsArguments.length === 1) {
        raw = equalsArguments[0].slice("--tier=".length);
        argumentsList.splice(argumentsList.indexOf(equalsArguments[0]), 1);
    }
    const index = argumentsList.indexOf("--tier");
    if (index !== -1) {
        if (raw !== undefined || index + 1 >= argumentsList.length) {
            throw new Error("Use exactly one --tier=8, --tier=16, --tier 8, or --tier 16");
        }
        raw = argumentsList[index + 1];
        argumentsList.splice(index, 2);
    }
    if (raw === undefined) return undefined;
    if (raw !== "8" && raw !== "16") {
        throw new Error("Stress tier must be exactly 8 or 16");
    }
    return Number(raw);
}

function stressEnvironment(tier) {
    const environment = { ...process.env };
    const blockedExactNames = new Set([
        "GAIUS_SMOKE_SERVER_JAR",
        "GAIUS_SMOKE_DIALOG_ACTION_ID",
        "GAIUS_SMOKE_DIALOG_INPUTS_FILE",
        "GAIUS_SMOKE_DIALOG_INPUTS_JSON",
        "GAIUS_PROFILE_ID",
        "GAIUS_VERSION_PROFILE_PATH",
        "GAIUS_BUILD_ROOT",
        "GAIUS_DIST_DIRECTORY",
        "GAIUS_TARGET_DIRECTORY",
        "GAIUS_OVERLAY_DIRECTORY",
    ]);
    for (const name of Object.keys(environment)) {
        const normalized = name.toUpperCase();
        if (normalized.startsWith("GAIUS_BROWSER_FULL_PATH_") ||
            normalized.startsWith("GAIUS_EXTERNAL_") ||
            blockedExactNames.has(normalized)) {
            delete environment[name];
        }
    }
    environment.GAIUS_VERSION_PROFILE_PATH = canonicalProfilePath;
    environment.GAIUS_BROWSER_FULL_PATH_STRESS = "1";
    environment.GAIUS_BROWSER_FULL_PATH_STRESS_TIER = String(tier);
    return environment;
}

function requireSuccessfulResult(run, label) {
    assert.equal(run.timedOut, false, `${label} exceeded ${run.deadlineMillis}ms`);
    assert.equal(run.exitCode, 0,
        `${label} exited ${run.exitCode ?? run.signal}: ${run.stderrTail}`);
    assert.equal(run.parseError, null, `${label} returned invalid JSON: ${run.parseError}`);
    assert.notEqual(run.result, null, `${label} did not return a JSON result`);
    return run.result;
}

async function runChild(childArguments, environment, deadlineMillis, relayDiagnostics) {
    return await new Promise((resolve) => {
        const startedAt = new Date();
        const startedMono = performance.now();
        const child = spawn(process.execPath, [fullPathScript, ...childArguments], {
            env: environment,
            stdio: ["ignore", "pipe", "pipe"],
            detached: process.platform !== "win32",
        });
        let stdout = Buffer.alloc(0);
        let stdoutTail = Buffer.alloc(0);
        let stdoutBytes = 0;
        let stderrBytes = 0;
        let stderrTail = Buffer.alloc(0);
        let timedOut = false;
        let spawnError = null;
        let cleanupAttempted = false;
        let cleanupMethod = null;
        let cleanupError = null;
        let cleanupExitCode = null;
        let cleanupSignal = null;
        let cleanupCompletedAt = null;
        let cleanupCompletion = Promise.resolve();
        let killTimer;
        const deadlineTimer = setTimeout(() => {
            timedOut = true;
            cleanupAttempted = true;
            if (process.platform === "win32") {
                cleanupMethod = "taskkill-tree";
                const cleanup = spawn("taskkill.exe", [
                    "/PID", String(child.pid), "/T", "/F",
                ], { stdio: "ignore", windowsHide: true });
                cleanupCompletion = new Promise((cleanupResolved) => {
                    cleanup.once("error", (error) => {
                        cleanupError = String(error);
                        cleanupCompletedAt = new Date().toISOString();
                        try { child.kill("SIGKILL"); }
                        catch (fallbackError) {
                            cleanupError += `; parent fallback: ${String(fallbackError)}`;
                        }
                        cleanupResolved();
                    });
                    cleanup.once("close", (exitCode, signal) => {
                        cleanupExitCode = exitCode;
                        cleanupSignal = signal;
                        cleanupCompletedAt = new Date().toISOString();
                        if (exitCode !== 0) {
                            cleanupError ??= `taskkill exited ${exitCode ?? signal}`;
                            try { child.kill("SIGKILL"); }
                            catch (fallbackError) {
                                cleanupError += `; parent fallback: ${String(fallbackError)}`;
                            }
                        }
                        cleanupResolved();
                    });
                });
            }
            else {
                cleanupMethod = "posix-process-group";
                try { process.kill(-child.pid, "SIGTERM"); }
                catch (error) { cleanupError = String(error); }
            }
            killTimer = setTimeout(() => {
                if (process.platform === "win32") return;
                try { process.kill(-child.pid, "SIGKILL"); }
                catch (error) { cleanupError ??= String(error); }
            }, 5_000);
            killTimer.unref?.();
        }, deadlineMillis);
        deadlineTimer.unref?.();
        child.stdout.on("data", (chunk) => {
            const bytes = Buffer.from(chunk);
            stdoutBytes += bytes.byteLength;
            stdoutTail = boundedTail(stdoutTail, bytes, MAX_DIAGNOSTIC_TAIL_BYTES);
            if (stdout.byteLength < MAX_STDOUT_BYTES) {
                stdout = Buffer.concat([
                    stdout,
                    bytes.subarray(0, MAX_STDOUT_BYTES - stdout.byteLength),
                ]);
            }
        });
        child.stderr.on("data", (chunk) => {
            const bytes = Buffer.from(chunk);
            stderrBytes += bytes.byteLength;
            stderrTail = boundedTail(stderrTail, bytes, MAX_DIAGNOSTIC_TAIL_BYTES);
            if (relayDiagnostics) process.stderr.write(bytes);
        });
        child.once("error", (error) => { spawnError = String(error); });
        child.once("close", async (exitCode, signal) => {
            clearTimeout(deadlineTimer);
            clearTimeout(killTimer);
            if (cleanupAttempted) {
                await Promise.race([
                    cleanupCompletion,
                    new Promise((cleanupWaitResolved) =>
                        setTimeout(cleanupWaitResolved, 2_000)),
                ]);
            }
            const completedAt = new Date();
            const text = stdout.toString("utf8");
            const lines = text.trim().split(/\r?\n/u).filter(Boolean);
            let result = null;
            let parseError = null;
            if (stdoutBytes > MAX_STDOUT_BYTES) {
                parseError = `stdout exceeded ${MAX_STDOUT_BYTES} bytes`;
            }
            else if (lines.length !== 1) {
                parseError = `expected one JSON line, received ${lines.length}`;
            }
            else {
                try { result = JSON.parse(lines[0]); }
                catch (error) { parseError = String(error); }
            }
            resolve({
                command: [process.execPath, fullPathScript, ...childArguments],
                startedAt: startedAt.toISOString(),
                completedAt: completedAt.toISOString(),
                elapsedMillis: Number((performance.now() - startedMono).toFixed(1)),
                deadlineMillis,
                exitCode,
                signal,
                timedOut,
                spawnError,
                cleanupAttempted,
                cleanupMethod,
                cleanupError,
                cleanupExitCode,
                cleanupSignal,
                cleanupCompletedAt,
                stdoutBytes,
                stderrBytes,
                stdoutTail: stdoutTail.toString("utf8"),
                stderrTail: stderrTail.toString("utf8"),
                parseError,
                result,
            });
        });
    });
}

function boundedTail(previous, next, maximumBytes) {
    const combined = Buffer.concat([previous, next]);
    return combined.byteLength <= maximumBytes
        ? combined
        : combined.subarray(combined.byteLength - maximumBytes);
}
