/*
 * Browser transport -> RelayNode -> vanilla server acceptance smoke.
 *
 * This deliberately does not use the TeaVM output or a direct TCP socket. It
 * evaluates the same BrowserWebSocketChannel JSBody that the browser build
 * embeds, supplies a real ws WebSocket implementation, and drives the raw
 * Minecraft protocol through __gaiusNettyBridge. The only non-browser piece
 * is the Node event loop used to poll the JSBody bridge; every relay hop is a
 * real WebSocket binary frame.
 */

import assert from "node:assert/strict";
import {
    constants as cryptoConstants,
    createCipheriv,
    createDecipheriv,
    createHash,
    publicEncrypt,
    randomBytes,
} from "node:crypto";
import { createServer as createHttpServer } from "node:http";
import { createServer as createTcpServer } from "node:net";
import { once } from "node:events";
import { mkdtemp, mkdir, readFile, writeFile, lstat } from "node:fs/promises";
import { monitorEventLoopDelay } from "node:perf_hooks";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { deflateSync, inflateSync } from "node:zlib";
import { WebSocket as NodeWebSocket } from "./node_modules/ws/wrapper.mjs";
import {
    decodeClientboundLoginDistances,
    MINECRAFT_1_21_11,
    MINECRAFT_26_2,
} from "./dist/protocol.js";

const repository = fileURLToPath(new URL("../../", import.meta.url));
const bridgeDirectory = fileURLToPath(new URL(".", import.meta.url));
const channelSourceUrl = new URL(
    "../../port/overrides/libraries/netty-transport/src/main/java/" +
        "io/netty/channel/browser/BrowserWebSocketChannel.java",
    import.meta.url,
);
// Keep the full-path harness tied to the exact BrowserWebSocketChannel source
// that supplies its JSBody bridge.  The evidence is diagnostic and additive:
// it does not alter the bridge behavior, but it makes a runtime result
// reproducible when multiple dirty/generated trees are present.
const BROWSER_CHANNEL_SOURCE_EVIDENCE_SCHEMA_VERSION =
    "gaius.browser-websocket-channel-source.v1";
const BROWSER_CHANNEL_SOURCE_MARKERS = Object.freeze([
    ["pumpAllAndReportProgress", "public static boolean pumpAllAndReportProgress()", false],
    ["aggregatePumpBudget", "private static final double MAX_TOTAL_MILLIS_PER_PUMP = 4.0;", false],
    ["initBridge", "private static native void initBridge();", true],
    ["initBridgeTail", "private static native void initBridgeTail();", true],
    ["initOutboundScheduler", "private static native void initOutboundScheduler();", true],
    ["initInboundScheduler", "private static native void initInboundScheduler();", true],
    ["relayNodeRecordResolverRead", "const resolver = state.relayNodeRecordResolver;", false],
    ["relayNodeRecordResolverPublish", "state.relayNodeRecordResolver = relayNodeRecord;", false],
].map(([name, marker, jsBody]) => Object.freeze({name, marker, jsBody})));
let browserChannelSourceCache;
const origin = process.env.GAIUS_BROWSER_FULL_PATH_ORIGIN ?? "http://127.0.0.1:8781";
const relayToken = process.env.GAIUS_BROWSER_FULL_PATH_TOKEN ?? "browser-full-path-token";
const usernamePrefix = process.env.GAIUS_BROWSER_FULL_PATH_USERNAME_PREFIX ?? "GaiusBrowser";
const externalRelayUrl = process.env.GAIUS_EXTERNAL_RELAY_URL?.trim() || undefined;
const externalMode = externalRelayUrl !== undefined;
const externalTarget = externalMode
    ? parseTarget(process.env.GAIUS_EXTERNAL_TARGET ?? "ellan.top:16888")
    : undefined;
const acceptServerPrompts = externalMode ||
    process.env.GAIUS_BROWSER_FULL_PATH_ACCEPT_SERVER_PROMPTS === "1" ||
    process.env.GAIUS_BROWSER_FULL_PATH_ACCEPT_DIALOGS === "1";
const requestedDialogAction = process.env.GAIUS_SMOKE_DIALOG_ACTION_ID;
const dialogInputValues = await loadDialogInputValues();
const commandLineArguments = process.argv.slice(2);
const printConfigOnly = commandLineArguments.includes("--print-config");
const printJavaResolutionOnly = commandLineArguments.includes("--print-java-resolution");
if (printConfigOnly && printJavaResolutionOnly) {
    throw new Error("--print-config and --print-java-resolution are mutually exclusive");
}
const malformedAcceptanceArgument = commandLineArguments.find((argument) =>
    argument.startsWith("--acceptance") && argument !== "--acceptance");
if (malformedAcceptanceArgument !== undefined) {
    throw new Error(`Unsupported acceptance argument ${malformedAcceptanceArgument}; use --acceptance`);
}
const acceptanceEnvironment = process.env.GAIUS_BROWSER_FULL_PATH_ACCEPTANCE;
if (acceptanceEnvironment !== undefined && acceptanceEnvironment !== "0" &&
    acceptanceEnvironment !== "1") {
    throw new Error("GAIUS_BROWSER_FULL_PATH_ACCEPTANCE must be exactly 0 or 1");
}
const acceptanceMode = commandLineArguments.includes("--acceptance") ||
    acceptanceEnvironment === "1";
const malformedStressArgument = commandLineArguments.find((argument) =>
    argument.startsWith("--stress") && argument !== "--stress");
if (malformedStressArgument !== undefined) {
    throw new Error(`Unsupported stress argument ${malformedStressArgument}; use --stress`);
}
const stressEnvironment = process.env.GAIUS_BROWSER_FULL_PATH_STRESS;
if (stressEnvironment !== undefined && stressEnvironment !== "0" && stressEnvironment !== "1") {
    throw new Error("GAIUS_BROWSER_FULL_PATH_STRESS must be exactly 0 or 1");
}
const stressMode = commandLineArguments.includes("--stress") || stressEnvironment === "1";
if (acceptanceMode && stressMode) {
    throw new Error("--acceptance and --stress are mutually exclusive");
}
if (stressMode && externalMode) {
    throw new Error("stress tiers require the canonical local RelayNode and vanilla server");
}
const releaseEvidenceMode = acceptanceMode || stressMode;
// A strict acceptance run must prove the RelayNode-side lifecycle as well as
// browser/client cleanup, including when the node is external.  The
// compatible external smoke remains usable for nodes that predate the
// runtime-gauge contract, but it is never evidence for strict no-stall release.
const relayRuntimeTelemetryRequired = !externalMode || acceptanceMode || stressMode;
const STRICT_ACCEPTANCE_TARGET = Object.freeze({
    clients: 4,
    minimumChunkPackets: 9,
    soakMillis: 15_000,
    reconnectWaves: 1,
});
const STRESS_TIER_TARGETS = Object.freeze({
    8: Object.freeze({
        clients: 8,
        minimumChunkPackets: 257,
        soakMillis: 60_000,
        reconnectWaves: 2,
        clientLifecycles: 24,
        clientViewDistance: 8,
        serverViewDistance: 8,
        maximumUniqueChunkCapacity: 257,
        simulationDistance: 4,
        desiredChunksPerTick: 32,
    }),
    16: Object.freeze({
        clients: 16,
        minimumChunkPackets: 257,
        soakMillis: 120_000,
        reconnectWaves: 4,
        clientLifecycles: 80,
        clientViewDistance: 8,
        serverViewDistance: 8,
        maximumUniqueChunkCapacity: 257,
        simulationDistance: 4,
        desiredChunksPerTick: 64,
    }),
});
const CANONICAL_PROFILES = Object.freeze({
    "26.2": Object.freeze({
        protocolVersion: 776,
        worldVersion: 4903,
        javaVersion: 25,
        serverSha1: "823e2250d24b3ddac457a60c92a6a941943fcd6a",
    }),
    "1.21.11": Object.freeze({
        protocolVersion: 774,
        worldVersion: 4671,
        javaVersion: 21,
        serverSha1: "64bb6d763bed0a9f1d632ec347938594144943ed",
    }),
});
const STRICT_RUNTIME_JAVA_POLICY = Object.freeze({
    "1.21.11": "major-exactly-21",
    "26.2": "major-at-least-25",
});
const RELAY_RUNTIME_GAUGES = Object.freeze([
    "activeLocalTunnelSessions",
    "pendingSyntheticPlayTicks",
    "activeServerFrameDrainHandles",
    "activeServerFrameDrainTimers",
    "activeClientStallTimers",
]);
// These are connection-state gauges rather than zero-only worker gauges.  A
// strict run must observe exactly the client count while PLAY is live and zero
// after each transport/final-close boundary; otherwise a node can report a
// clean worker while retaining a logical lease or physical WebSocket.
const RELAY_RUNTIME_CONNECTION_GAUGES = Object.freeze([
    "activeTunnelLeases",
    "activeTransportWebSockets",
]);
const RELAY_DRAIN_MAX_DURATION_MILLIS = 16.7;
// Multiplayer acceptance is intentionally bounded by observed transport and
// scheduling behavior, not by the old 90-second protocol timeout. These are
// fixed in --acceptance mode so a caller cannot hide a stall by widening env.
const MULTIPLAYER_PERFORMANCE_TARGET = Object.freeze({
    maxConnectToMinimumChunksMillis: 15_000,
    maxConfigurationToPlayLoginMillis: 10_000,
    maxPlayLoginToFirstChunkMillis: 10_000,
    maxPreMinimumChunkPacketGapMillis: 500,
    maxPlayTickGapMillis: 250,
    maxPollGapMillis: 500,
    maxParserBufferedBytes: 4 * 1024 * 1024,
    maxBrowserQueuedFrames: 1024,
    maxBrowserInboundQueuedBytes: 24 * 1024 * 1024,
    maxSoakPhaseStallMillis: 500,
});
const LATENCY_HISTOGRAM_BUCKETS_MILLIS = Object.freeze([
    1, 2, 4, 8, 16.7, 25, 50, 60, 75, 100, 250, 500, 1000, Infinity,
]);
const STRESS_LATENCY_DISTRIBUTION_TARGET = Object.freeze({
    schemaVersion: "gaius.multiplayer-stress-latency-target.v1",
    histogramSchemaVersion: "gaius.latency-histogram.v1",
    // null is the JSON representation of the final +Infinity bucket.
    bucketUpperBoundsMillis: LATENCY_HISTOGRAM_BUCKETS_MILLIS.map((value) =>
        Number.isFinite(value) ? value : null),
    pollGap: Object.freeze({ p99Millis: 16.7, p999Millis: 50, maxMillis: 100 }),
    playTickGap: Object.freeze({ p99Millis: 60, p999Millis: 75, maxMillis: 100 }),
    preMinimumChunkGap: Object.freeze({ p99Millis: 100, maxMillis: 250 }),
});
// The browser bridge is polled from a shared event loop.  A client must not
// synchronously inflate and parse an unbounded burst while its peers wait for
// their next poll turn.  These budgets preserve frame ordering and leave any
// unread bytes in the bridge/client buffer for the following poll.
const MAX_INBOUND_FRAMES_PER_POLL = 8;
const MAX_PACKETS_PER_POLL = 64;
// The smoke models independent browser tabs in one Node process. Dispatching
// every live client from one timer callback made callback duration scale with
// fan-in and manufactured long poll buckets even when RelayNode had no queued
// frames or backpressure. A one-client callback, however, adds one timer turn
// per logical tab and itself creates an N× timer-jitter floor (the tier-8 run
// exposed ~126 ms gaps at eight clients). Bound each callback to four clients:
// per-client frame/packet budgets still cap synchronous work, while a client
// receives a poll turn at least every two macrotasks at tier 8. The latency
// gates remain unchanged; scheduler evidence is emitted below so a fairness
// change cannot hide a real client gap.
const CLIENT_POLL_INTERVAL_MILLIS = 1;
const MAX_CLIENTS_PER_POLL_CALLBACK = 4;
// Keep one scheduler callback comfortably below a 16.7 ms render frame even
// when several clients have work ready at once.  The per-client parser/poll
// budgets remain authoritative; this aggregate guard only decides how many
// round-robin clients enter this macrotask.  A later immediate turn resumes
// at the next cursor, so a slow client cannot monopolise its peers.
const MAX_POLL_CALLBACK_WORK_MILLIS = 8;
// Node's Windows timer backend can quantize a one-millisecond timeout to a
// much larger wall-clock interval.  Keep the timer only as a *rare* bounded
// fairness backstop; the normal poll turn uses setImmediate so a timer quantum
// cannot become the per-client latency floor.  Eight-turn yields put a timer
// gap in roughly every fourth client cycle at tier-8 and showed up directly in
// the p99=25 ms Helio failure.  256 turns leaves the check phase responsive to
// I/O while keeping timer-quantized gaps below one percent of samples.  The
// work value below is an evidence marker only: forcing a timer after every
// heavy callback would reintroduce that quantization under real parser load.
// These are harness controls, not release thresholds and are deliberately not
// environment-overridable.
const POLL_SCHEDULER_TIMER_YIELD_TURNS = 256;
const POLL_SCHEDULER_TIMER_YIELD_WORK_MILLIS = 2;
const POLL_SCHEDULER_WATCHDOG_MILLIS = 25;
// When every live client has no inbound frame ready, park the shared poll
// continuation on a short timer instead of spinning setImmediate callbacks.
// This is an idle backoff only; it does not alter any latency acceptance gate.
const POLL_SCHEDULER_IDLE_BACKOFF_MILLIS = 1;
// A due cursor is normally only one millisecond ahead of the completed poll.
// On Windows, parking that cursor on a one-millisecond timer exposes the host
// timer quantum as a visible 15--25 ms poll gap.  Probe with setImmediate
// turns while the next due cursor is close, but bound the probe by elapsed
// time as well as a small per-burst turn count.  A real dispatch starts a
// fresh probe for the next due cursor; a burst rollover does not reset the
// absolute probe deadline.  An overdue cursor gets one final immediate wake
// after that deadline so it can dispatch, then the timer backstop wins if it
// still cannot be admitted.  This is a harness scheduling optimization; the
// strict 16.7/50/100 ms gates remain unchanged.
const POLL_SCHEDULER_IDLE_IMMEDIATE_WINDOW_MILLIS = 2;
const POLL_SCHEDULER_IDLE_IMMEDIATE_PROBE_BUDGET_MILLIS = 2;
const POLL_SCHEDULER_IDLE_IMMEDIATE_SPIN_LIMIT = 16;
// Tick servicing shares the poll callback but has its own cursor and cap. A
// large stress tier therefore cannot turn one scheduler callback into an
// unbounded fan-out of outbound tick writes.
const MAX_PLAY_TICKS_PER_SCHEDULER_CALLBACK = MAX_CLIENTS_PER_POLL_CALLBACK;
// A scheduler callback must leave room for the browser event loop to service
// rendering/input work.  This is an evidence limit for the stress validator;
// the existing per-client poll/tick latency gates remain authoritative.
const MAX_VISIBLE_DISPATCH_SKEW = 1;
const STRESS_EVENT_LOOP_MAX_MILLIS = 100;
// Keep callback-tail attribution bounded and diagnostic-only.  The 16.7 ms
// render-frame threshold is still enforced by the stress validator; retaining
// the slow samples here must never turn a strict overrun into a pass.
const CALLBACK_TAIL_TELEMETRY_SCHEMA_VERSION =
    "gaius.browser-client-poll-callback-tail.v1";
const CALLBACK_TAIL_SLOW_THRESHOLD_MILLIS = 16.7;
const CALLBACK_TAIL_SAMPLE_LIMIT = 64;
// Scheduler gap telemetry is a bounded diagnostic ring for separating an
// event-loop/callback gap from work performed inside the callback.  It is
// deliberately independent of all acceptance gates: the 16.7 ms callback
// threshold and 500 ms liveness limits remain unchanged.
const SCHEDULER_GAP_TELEMETRY_SCHEMA_VERSION =
    "gaius.browser-client-scheduler-gap.v1";
const SCHEDULER_GAP_SLOW_THRESHOLD_MILLIS = 250;
const SCHEDULER_GAP_STRICT_CALLBACK_THRESHOLD_MILLIS = 16.7;
const SCHEDULER_GAP_SAMPLE_LIMIT = 64;
const SCHEDULER_GAP_RETENTION = "largest-gap-or-callback-desc-sequence-asc";
// Finalization telemetry starts at the existing callback work endpoint and
// ends after the continuation has been scheduled.  It is deliberately
// diagnostic-only: callbackDurationRawMillis remains the strict 16.7 ms gate
// and callbackWorkBudgetMillis remains the 8 ms admission budget.
const CALLBACK_FINALIZATION_TAIL_TELEMETRY_SCHEMA_VERSION =
    "gaius.browser-client-poll-callback-finalization-tail.v1";
const CALLBACK_FINALIZATION_TAIL_SLOW_THRESHOLD_MILLIS = 16.7;
const CALLBACK_FINALIZATION_TAIL_SAMPLE_LIMIT = 64;
const CALLBACK_FINALIZATION_TAIL_RETENTION = "longest-tail-desc-sequence-asc";
// PLAY tick timing evidence is a bounded, diagnostic-only ring.  It retains
// the last 64 successful tick sends per client so a p99/max gap can be
// correlated with the due time and scheduler turn that preceded it without
// changing cadence or any strict latency gate.
const PLAY_TICK_TIMING_TELEMETRY_SCHEMA_VERSION =
    "gaius.browser-client-play-tick-timing.v1";
const PLAY_TICK_TIMING_SAMPLE_LIMIT = 64;
const PLAY_TICK_TIMING_RETENTION = "last-64-chronological";
// A callback-tail overrun often collapses to one synchronous client poll.
// Retain a bounded scalar-only phase breakdown so the next run can distinguish
// bridge dequeue/transform, inflate/parse, packet dispatch, and bookkeeping.
// This is diagnostic evidence only: it neither changes the 8 ms admission
// budget nor the strict 16.7 ms raw callback/poll gates.
const POLL_PHASE_TELEMETRY_SCHEMA_VERSION =
    "gaius.browser-client-poll-phase.v1";
const POLL_PHASE_SLOW_THRESHOLD_MILLIS = 16.7;
const POLL_PHASE_SAMPLE_LIMIT = 64;
const POLL_PHASE_FRAME_SAMPLE_LIMIT = 8;
const POLL_PHASE_PACKET_SAMPLE_LIMIT = 64;
const POLL_PHASE_PACKET_ID_SAMPLE_LIMIT = 8;
// Phase envelopes intentionally include their nested work (for example,
// bridgeDrain contains bridgePoll/decrypt/concat and parse contains inflate /
// handler). Keep this explicit so consumers never sum the segments as if
// they were disjoint slices of the poll duration.
const POLL_PHASE_SEGMENT_ACCOUNTING = "inclusive-overlapping";
// Arrival-gap telemetry is diagnostic-only.  It is deliberately separate
// from every acceptance gate so a long decoded-packet gap can be attributed
// without turning a sampled/unsampled result into a pass.  The bridge does not
// expose a trusted wire timestamp; wireAt therefore remains null in every
// sample rather than being guessed from an onmessage timestamp.
const ARRIVAL_TIMELINE_SCHEMA_VERSION =
    "gaius.browser-client-arrival-timeline.v1";
const ARRIVAL_WIRE_AT_SOURCE = "unavailable";
const ARRIVAL_TIMELINE_SLOW_THRESHOLD_MILLIS = 250;
const ARRIVAL_TIMELINE_SAMPLE_LIMIT = 64;
const ARRIVAL_TIMELINE_RECONNECT_PHASE_LIMIT = 64;
const ARRIVAL_TIMELINE_FRAME_RING_LIMIT = 64;
// Keep the event-level arrival trace additive and diagnostic-only.  It is a
// bounded window used to explain a slow decoded-packet gap; it is not a
// replacement for any strict callback/poll/tick gate.
const ARRIVAL_TRACE_SCHEMA_VERSION =
    "gaius.browser-client-arrival-trace.v1";
const ARRIVAL_TRACE_EVENT_LIMIT = 64;
const ARRIVAL_TRACE_FORCED_EVENT_LIMIT = 64;
const ARRIVAL_TRACE_POLL_EVENT_STRIDE = 256;
const ARRIVAL_TRACE_FRAME_EVENT_STRIDE = 8;
const ARRIVAL_TRACE_PACKET_BEGIN_EVENT_STRIDE = 16;
const ARRIVAL_TRACE_PACKET_END_EVENT_STRIDE = 4;
const ARRIVAL_TRACE_PACKET_ALWAYS_IDS = new Set([113]);
// Event-level tracing is opt-in because every retained event is a small JS
// object allocation.  Keep the normal acceptance/stress path free of this
// diagnostic overhead; enable it only for a separate attribution run with
// `GAIUS_BROWSER_FULL_PATH_TRACE=1`.
const ARRIVAL_TRACE_ENABLED = process.env.GAIUS_BROWSER_FULL_PATH_TRACE === "1";
// A trace-enabled run intentionally allocates and sorts diagnostic events in
// the browser hot path.  Keep that evidence useful for attribution, but make
// its strict-performance eligibility explicit so callers cannot mistake a
// diagnostic pass for a clean release measurement.
const ARRIVAL_TRACE_STRICT_EVIDENCE_ELIGIBLE = !ARRIVAL_TRACE_ENABLED;
const ARRIVAL_TRACE_EVENT_KINDS = Object.freeze([
    "onmessage-enter",
    "bridge-enqueue",
    "bridge-dequeue",
    "poll-ready",
    "poll-begin",
    "poll-end",
    "decode-begin",
    "decode-end",
    "dispatch-begin",
    "dispatch-end",
    "arrival-gap-boundary",
    "phase",
    "client-created",
    "handshake-sent",
    "disconnect",
    "reconnect-scheduled",
    "synthetic-transport-drop",
    "connect-begin",
    "transport-open",
    "login-begin",
    "login-done",
    "configuration-begin",
    "configuration-done",
    "play-enter",
    "play-login",
    "first-onmessage",
    "first-decoded-packet",
    "first-chunk",
    "minimum-chunks",
    "close",
]);
const ARRIVAL_TRACE_EVENT_KIND_SET = new Set(ARRIVAL_TRACE_EVENT_KINDS);
// 26.2 emits ClientboundSetTime (play packet id 113) on the vanilla
// twenty-tick cadence.  A decoded gap near one second ending in that packet
// is a useful cadence hint, not proof of a user-visible stall.  Keep this
// classification deliberately narrow and diagnostic-only: raw gap counters,
// packet-gap measurements, and every strict gate remain unchanged.
const ARRIVAL_PERIODIC_SERVER_SYNC_SCHEMA_VERSION =
    "gaius.browser-client-arrival-periodic-server-sync.v1";
const ARRIVAL_PERIODIC_SERVER_SYNC_CLASSIFICATION = "periodic-server-sync";
const ARRIVAL_PERIODIC_SERVER_SYNC_PROFILE_ID = "26.2";
const ARRIVAL_PERIODIC_SERVER_SYNC_PROTOCOL_VERSION = 776;
const ARRIVAL_PERIODIC_SERVER_SYNC_PACKET_ID = 113;
const ARRIVAL_PERIODIC_SERVER_SYNC_NOMINAL_GAP_MILLIS = 1000;
const ARRIVAL_PERIODIC_SERVER_SYNC_TOLERANCE_MILLIS = 125;
const ARRIVAL_PERIODIC_SERVER_SYNC_EXCLUDED_FROM_USER_VISIBLE_STALL = true;
// These counters are exported by BrowserWebSocketChannel's JSBody stats. Keep
// their exact source names: a missing source field is evidence, not a made-up
// zero, and is handled explicitly by browserRuntimeCleanupGaugeEvidence().
const BROWSER_RUNTIME_CLEANUP_GAUGES = Object.freeze([
    "activeHighWatermarks",
    "decodedSliceBacklog",
    "decoderCumulationBytes",
    "decodedPacketQueue",
]);
// BrowserWebSocketChannel.pumpAllAndReportProgress() exposes these aggregate
// Java global-pump counters through the JSBody bridge.  Older generated
// classes may not have the fields yet; browserRuntimeSnapshot() deliberately
// reports null in that case instead of synthesizing a zero.
const BROWSER_GLOBAL_PUMP_TELEMETRY_FIELDS = Object.freeze([
    "pumpAllTurns",
    "pumpAllChannelsVisited",
    "pumpAllBudgetYields",
    "pumpAllMaxTurnMillis",
    "pumpAllMaxChannelsPerTurn",
    "pumpAllLastTurnMillis",
    "pumpAllLastChannelsVisited",
]);
// Mirrors BrowserWebSocketChannel.MAX_TOTAL_MILLIS_PER_PUMP. This is a
// descriptive contract marker only; strict acceptance does not invent a new
// Java-side threshold from the Node harness.
const BROWSER_GLOBAL_PUMP_MAX_TOTAL_MILLIS = 4;
const BROWSER_INBOUND_FLOW_EVIDENCE_FIELDS = Object.freeze([
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
const BROWSER_INBOUND_FLOW_DURATION_FIELDS = new Set([
    "highWatermarkDurationMillis",
    "longestHighWatermarkMillis",
    "activeHighWatermarkLongestMillis",
]);
const BROWSER_INBOUND_FLOW_EVENT_FIELDS = Object.freeze([
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
const BROWSER_INBOUND_FLOW_WINDOW_STAGES = Object.freeze([
    "initialConnectThroughMinimumChunks",
    "preDrop",
    "reconnectRecovery",
    "steadySoak",
    "finalCleanup",
]);
const BROWSER_INBOUND_FLOW_WINDOW_SCHEMA =
    "gaius.browser-inbound-flow-window-evidence.v2";
// BrowserWebSocketChannel keeps the diagnostic ring bounded to the latest 64
// slow queued-packet handlers.  Copy through the same hard limit so a malformed
// or older runtime cannot inflate strict-result JSON without bound.
const BROWSER_QUEUED_PACKET_SLOW_EVENT_LIMIT = 64;

function parseStrictAcceptanceNumber(name, expected) {
    const raw = process.env[name];
    if (raw === undefined) return expected;
    if (!/^(?:0|[1-9][0-9]*)$/u.test(raw) || raw !== String(expected)) {
        throw new Error(`${name} must be exactly ${expected} in strict acceptance mode`);
    }
    return expected;
}

function parseStressTier() {
    if (!stressMode) return undefined;
    const raw = process.env.GAIUS_BROWSER_FULL_PATH_STRESS_TIER ?? "8";
    if (!/^(?:8|16)$/u.test(raw)) {
        throw new Error("GAIUS_BROWSER_FULL_PATH_STRESS_TIER must be exactly 8 or 16");
    }
    return Number(raw);
}

function parseStressNumber(name, expected) {
    const raw = process.env[name];
    if (raw === undefined) return expected;
    if (!/^(?:0|[1-9][0-9]*)$/u.test(raw) || raw !== String(expected)) {
        throw new Error(`${name} must be exactly ${expected} for the selected stress tier`);
    }
    return expected;
}

function parseBoundedInteger(name, defaultValue, minimum, maximum) {
    const raw = process.env[name];
    if (raw === undefined) return defaultValue;
    if (!/^(?:0|[1-9][0-9]*)$/u.test(raw)) {
        throw new Error(`${name} must be an integer from ${minimum} to ${maximum}`);
    }
    const value = Number(raw);
    if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
        throw new Error(`${name} must be an integer from ${minimum} to ${maximum}`);
    }
    return value;
}

const stressTier = parseStressTier();
const stressTarget = stressTier === undefined ? undefined : STRESS_TIER_TARGETS[stressTier];

const requestedClients = Number.parseInt(
    acceptanceMode
        ? parseStrictAcceptanceNumber("GAIUS_BROWSER_FULL_PATH_CLIENTS",
            STRICT_ACCEPTANCE_TARGET.clients)
        : stressMode
            ? parseStressNumber("GAIUS_BROWSER_FULL_PATH_CLIENTS", stressTarget.clients)
        : process.env.GAIUS_BROWSER_FULL_PATH_CLIENTS ?? "2", 10);
const clientCount = Number.isInteger(requestedClients)
    ? acceptanceMode || stressMode
        ? requestedClients
        : Math.max(1, Math.min(4, requestedClients))
    : 2;
const soakMs = acceptanceMode
    ? parseStrictAcceptanceNumber("GAIUS_BROWSER_FULL_PATH_SOAK_MS",
        STRICT_ACCEPTANCE_TARGET.soakMillis)
    : stressMode
        ? parseStressNumber("GAIUS_BROWSER_FULL_PATH_SOAK_MS", stressTarget.soakMillis)
    : Math.max(0, Number.parseInt(
        process.env.GAIUS_BROWSER_FULL_PATH_SOAK_MS ?? "1000", 10) || 0);
const requestedMinimumChunkPackets = Number.parseInt(
    acceptanceMode
        ? parseStrictAcceptanceNumber("GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS",
            STRICT_ACCEPTANCE_TARGET.minimumChunkPackets)
        : stressMode
            ? parseStressNumber("GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS",
                stressTarget.minimumChunkPackets)
        : process.env.GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS ?? "9", 10);
const minimumChunkPackets = Number.isInteger(requestedMinimumChunkPackets)
    ? acceptanceMode || stressMode ? requestedMinimumChunkPackets :
        Math.max(1, Math.min(128, requestedMinimumChunkPackets))
    : 9;
const requestedReconnectWaves = Number.parseInt(
    acceptanceMode
        ? parseStrictAcceptanceNumber("GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES",
            STRICT_ACCEPTANCE_TARGET.reconnectWaves)
        : stressMode
            ? parseStressNumber("GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES",
                stressTarget.reconnectWaves)
        : process.env.GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES ?? "0", 10);
const reconnectWaves = Number.isInteger(requestedReconnectWaves)
    ? acceptanceMode || stressMode ? requestedReconnectWaves :
        Math.max(0, Math.min(8, requestedReconnectWaves))
    : 0;
const clientViewDistance = acceptanceMode
    ? parseStrictAcceptanceNumber("GAIUS_BROWSER_FULL_PATH_CLIENT_VIEW_DISTANCE", 6)
    : stressMode
        ? parseStressNumber("GAIUS_BROWSER_FULL_PATH_CLIENT_VIEW_DISTANCE",
            stressTarget.clientViewDistance)
        : parseBoundedInteger("GAIUS_BROWSER_FULL_PATH_CLIENT_VIEW_DISTANCE", 6, 2, 32);
const serverViewDistance = acceptanceMode
    ? parseStrictAcceptanceNumber("GAIUS_BROWSER_FULL_PATH_SERVER_VIEW_DISTANCE", 2)
    : stressMode
        ? parseStressNumber("GAIUS_BROWSER_FULL_PATH_SERVER_VIEW_DISTANCE",
            stressTarget.serverViewDistance)
        : parseBoundedInteger("GAIUS_BROWSER_FULL_PATH_SERVER_VIEW_DISTANCE", 2, 2, 32);
const effectiveChunkRadius = Math.min(clientViewDistance, serverViewDistance);
const maximumUniqueChunkCapacity = chunkTrackingCapacity(effectiveChunkRadius);
if (stressMode && maximumUniqueChunkCapacity !== stressTarget.maximumUniqueChunkCapacity) {
    throw new Error(`stress tier ${stressTier} chunk capacity drifted: ` +
        `${maximumUniqueChunkCapacity} != ${stressTarget.maximumUniqueChunkCapacity}`);
}
const desiredChunksPerTick = parseDesiredChunksPerTick(
    process.env.GAIUS_BROWSER_FULL_PATH_DESIRED_CHUNKS_PER_TICK ??
        String(stressTarget?.desiredChunksPerTick ?? 64));
if (stressMode && desiredChunksPerTick !== stressTarget.desiredChunksPerTick) {
    throw new Error(
        `GAIUS_BROWSER_FULL_PATH_DESIRED_CHUNKS_PER_TICK must be exactly ` +
        `${stressTarget.desiredChunksPerTick} for stress tier ${stressTier}`);
}
const requestedClientStartDelayMs = Number.parseInt(
    process.env.GAIUS_BROWSER_FULL_PATH_CLIENT_START_DELAY_MS ??
        (externalMode ? "1500" : "0"), 10);
const clientStartDelayMs = Number.isInteger(requestedClientStartDelayMs)
    ? Math.max(0, Math.min(30_000, requestedClientStartDelayMs))
    : externalMode ? 1500 : 0;
const smokeStartedAt = performance.now();

let activeProfile;
let lastInboundFlowAttempt = null;

async function runSmoke() {
    activeProfile = await loadActiveVersionProfile();
    assertCanonicalProfile(activeProfile);
    const browserChannelSource = await loadBrowserChannelSourceEvidence();
    if (minimumChunkPackets > maximumUniqueChunkCapacity) {
        throw new Error(
            `GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS=${minimumChunkPackets} exceeds ` +
            `the effective chunk radius ${effectiveChunkRadius} capacity ` +
            `${maximumUniqueChunkCapacity} (client view ${clientViewDistance}, ` +
            `server view ${serverViewDistance})`,
        );
    }
    const wireProfile = resolveWireProfile(activeProfile);
    const verifiedServerJar = externalMode
        ? undefined
        : await resolveVerifiedServerJar(activeProfile);
    const serverJar = verifiedServerJar?.path;
    const evidenceRoot = path.join(repository, "port", "target", activeProfile.id,
        "browser-relay-full-path-evidence");
    await mkdir(evidenceRoot, { recursive: true });
    const workDirectory = await mkdtemp(path.join(evidenceRoot, "run-"));
    const clientIdentities = Array.from({ length: clientCount }, (_, index) =>
        createClientIdentity(index));
    const sessionState = {
        publicKeyRequests: 0,
        joins: [],
        hasJoined: [],
        expectedProfiles: new Map(clientIdentities.map((identity) => [
            identity.accessToken,
            identity.profileId,
        ])),
        profileUsernames: new Map(clientIdentities.map((identity) => [
            identity.profileId,
            identity.username,
        ])),
    };
    const sessionServer = createSessionServer(sessionState);
    let sessionPort;
    let serverProcess;
    let relayProcess;
    let serverOutput = "";
    let relayOutput = "";
    let relayPort;
    let minecraftPort;
    let runtimeJava;
    let relayEndpoint;
    let readRelayRuntime;
    let waitRelayRuntime;
    let browserRuntime;
    let relayRuntimeBaseline;
    let relayRuntimeAtChunks;
    let relayRuntimeBeforeSoak;
    let relayRuntimeAfterSoak;
    let relayRuntimeAfterClose;
    let browserRuntimeAfterSoak;
    let soakHealth;
    let soakPerformance;
    let soakStartedAt;
    let soakCompletedAt;
    let failure;
    let serverSpawnError;
    let relaySpawnError;
    let allClients = [];
    let currentClients = [];
    let pollScheduler;
    const inboundFlowEvidence = {
        schemaVersion: BROWSER_INBOUND_FLOW_WINDOW_SCHEMA,
        initialConnectThroughMinimumChunks: null,
        preDrop: [],
        reconnectRecovery: [],
        steadySoak: null,
        // Legacy aliases are retained for result readers from the v1 contract.
        initial: null,
        reconnectWaves: [],
        postSoak: null,
        finalCleanup: null,
        compatibilityAliases: {
            initial: "initialConnectThroughMinimumChunks",
            reconnectWaves: ["preDrop", "reconnectRecovery"],
            postSoak: "steadySoak",
        },
    };
    let initialInboundFlowWindow;
    let finalCleanupInboundFlowWindow;

try {
    await listen(sessionServer, 0, "127.0.0.1");
    sessionPort = sessionServer.address().port;
    const sessionBaseUrl = `http://127.0.0.1:${sessionPort}`;
    if (externalMode) {
        relayEndpoint = {
            url: externalRelayUrl,
            target: externalTarget,
            external: true,
        };
        readRelayRuntime = () => fetchExternalRelayRuntime(
            relayEndpoint.url, relayEndpoint.target, relayToken);
        waitRelayRuntime = (predicate, label, timeoutMillis) =>
            waitForRelayRuntimeReader(readRelayRuntime, predicate, label, timeoutMillis);
    }
    else {
        await writeFile(path.join(workDirectory, "eula.txt"), "eula=true\n");

        // The port is allocated before writing server.properties so the vanilla
        // child never races a later listener. Keep the generated world/evidence
        // directory; this smoke is intentionally post-mortem friendly.
        minecraftPort = await reservePort();
        await writeFile(path.join(workDirectory, "server.properties"),
            serverProperties(minecraftPort));
        runtimeJava = await resolveJavaExecutable(activeProfile);
        const javaExecutable = runtimeJava.executable;
        serverProcess = spawn(javaExecutable, [
            `-Dminecraft.api.session.host=${sessionBaseUrl}`,
            `-Dminecraft.api.services.host=${sessionBaseUrl}`,
            `-Dminecraft.api.profiles.host=${sessionBaseUrl}`,
            "-Xms512m",
            "-Xmx1536m",
            "-jar",
            serverJar,
            "nogui",
        ], {
            cwd: workDirectory,
            stdio: ["pipe", "pipe", "pipe"],
            windowsHide: true,
        });
        serverProcess.once("error", (error) => {
            serverSpawnError = error;
            serverOutput += `\nJava process error: ${error.stack || error}\n`;
        });
        for (const stream of [serverProcess.stdout, serverProcess.stderr]) {
            stream.setEncoding("utf8");
            stream.on("data", (chunk) => { serverOutput += chunk; });
        }
        await waitFor(() => serverOutput.includes("Done (") ||
            serverProcess.exitCode !== null || serverSpawnError !== undefined,
        "vanilla server startup", 180000, () => serverOutput.slice(-4000));
        if (serverSpawnError !== undefined || serverProcess.exitCode !== null ||
            !serverOutput.includes("Done (")) {
            throw new Error("Vanilla server failed to start:\n" + serverOutput);
        }

        relayPort = await reservePort();
        relayProcess = spawn(process.execPath, ["dist/main.js"], {
            cwd: bridgeDirectory,
            env: {
                ...process.env,
                NODE_ENV: "test",
                GAIUS_BRIDGE_HOST: "127.0.0.1",
                GAIUS_BRIDGE_PORT: String(relayPort),
                GAIUS_ALLOWED_ORIGINS: origin,
                GAIUS_ALLOWED_HOSTS: "127.0.0.1",
                GAIUS_BRIDGE_TOKEN: relayToken,
                GAIUS_IDLE_TIMEOUT_MS: "60000",
                GAIUS_CONNECT_TIMEOUT_MS: "10000",
                GAIUS_PROXY_KEEPALIVES: "1",
            },
            stdio: ["ignore", "pipe", "pipe"],
        });
        relayProcess.once("error", (error) => {
            relaySpawnError = error;
            relayOutput += `\nRelay process error: ${error.stack || error}\n`;
        });
        for (const stream of [relayProcess.stdout, relayProcess.stderr]) {
            stream.setEncoding("utf8");
            stream.on("data", (chunk) => { relayOutput += chunk; });
        }
        await waitFor(() => relayOutput.includes("Gaius translator node listening") ||
            relayProcess.exitCode !== null || relaySpawnError !== undefined,
        "RelayNode startup", 15000, () => relayOutput.slice(-4000));
        if (relaySpawnError !== undefined || relayProcess.exitCode !== null ||
            !relayOutput.includes("Gaius translator node listening")) {
            throw new Error("RelayNode failed to start:\n" + relayOutput);
        }
        relayEndpoint = {
            url: `ws://127.0.0.1:${relayPort}/tunnel`,
            target: { host: "127.0.0.1", port: minecraftPort,
                text: `127.0.0.1:${minecraftPort}` },
            external: false,
        };
        readRelayRuntime = () => fetchRelayRuntime(relayPort, minecraftPort);
        waitRelayRuntime = (predicate, label, timeoutMillis) =>
            waitForRelayRuntimeReader(readRelayRuntime, predicate, label, timeoutMillis);
    }
    relayRuntimeBaseline = await readRelayRuntime();
    if (relayRuntimeTelemetryRequired) {
        assertRelayRuntimeConnectionGauges(
            relayRuntimeBaseline,
            0,
            "RelayNode baseline logical/physical connection gauges",
        );
    }

    browserRuntime = await createBrowserRuntime(relayEndpoint.url, relayToken);
    initialInboundFlowWindow = beginBrowserInboundFlowWindow(
        browserRuntime,
        "initialConnectThroughMinimumChunks",
        "initial connect through minimum chunks",
    );
    const createClients = (wave, previousClients = undefined,
        reconnectScheduledAt = undefined, reconnectDropAt = undefined) => Array.from({ length: clientCount }, (_, index) =>
        new BrowserMinecraftClient({
            id: 700 + wave * 100 + index,
            index,
            wave,
            profile: wireProfile,
            bridge: browserRuntime.bridge,
            stats: browserRuntime.stats,
            host: relayEndpoint.target.host,
            port: relayEndpoint.target.port,
            sessionUrl: sessionBaseUrl,
            identity: clientIdentities[index],
            previousDecodeEndAt: previousClients?.[index]?.arrivalLastDecodeEndAt,
            // Keep the boundary timestamp owned by this lifecycle immutable.
            // The current wave's drop is passed explicitly below; falling back
            // to the previous seed is only for callers that do not have a new
            // transport-drop marker.
            reconnectDropAt: Number.isFinite(reconnectDropAt)
                ? Number(reconnectDropAt)
                : previousClients?.[index]?.arrivalReconnectDropAt,
            reconnectScheduledAt,
        }));
    allClients = [];
    const reconnectEvidence = [];
    currentClients = createClients(0);
    allClients.push(...currentClients);
    pollScheduler = createFairClientPollScheduler(() => currentClients);
    try {
        await connectClientWave(currentClients);
        await waitFor(
            () => currentClients.every((client) => client.failure === undefined &&
                client.phase === "play" &&
                client.loginFinished &&
                (externalMode || (client.encryptionRequest && client.aesCfb8Enabled)) &&
                client.playLoginPackets > 0 &&
                client.minimumChunkTargetReached()),
            "browser Relay Minecraft PLAY/chunk", 90000,
            () => JSON.stringify(currentClients.map((client) => client.diagnostics())));
        for (const client of currentClients) {
            client.checkError();
            assertClientSecurity(client, `initial client ${client.id}`);
            // At the minimum-chunk readiness boundary a replacement client has
            // only a handful of 50 ms tick samples.  Scalar readiness limits
            // are still enforced here, while percentile gates wait for the
            // steady-soak window where the sample population is meaningful.
            assertClientPerformance(client, `initial client ${client.id}`, {
                requireLatencyDistributions: false,
            });
        }
        relayRuntimeAtChunks = await waitRelayRuntime(
            (snapshot) => snapshot.activeConnections === clientCount &&
                snapshot.target?.activeConnections === clientCount &&
                (!relayRuntimeTelemetryRequired ||
                    relayRuntimeConnectionGaugesEqual(snapshot, clientCount)) &&
                (!relayRuntimeTelemetryRequired || relayRuntimeGaugesAreZero(snapshot)),
            "initial RelayNode active tunnel quiescence",
            5000,
        );
        inboundFlowEvidence.initialConnectThroughMinimumChunks =
            await finishBrowserInboundFlowWindow(
            browserRuntime,
            initialInboundFlowWindow,
            "initial connect through minimum chunks",
            );
        inboundFlowEvidence.initial =
            inboundFlowEvidence.initialConnectThroughMinimumChunks;
        if (relayRuntimeTelemetryRequired) {
            assertRelayRuntimeConnectionGauges(
                relayRuntimeAtChunks,
                clientCount,
                "initial RelayNode logical/physical connection gauges",
            );
            assertRelayRuntimeGaugesZero(relayRuntimeAtChunks,
                "initial active tunnel gauge check");
            assertRelayDrainPerformance(
                [relayRuntimeAtChunks],
                "initial multiplayer chunk readiness",
            );
        }
        relayRuntimeBeforeSoak = relayRuntimeAtChunks;
        const seenBuffers = new Set(currentClients.map((client) => client.buffer));
        const seenCiphers = new Set(currentClients.map((client) => client.cipher));
        const seenDeciphers = new Set(currentClients.map((client) => client.decipher));
        const seenSecretFingerprints = new Set(currentClients.map((client) =>
            client.secretFingerprint));

        for (let wave = 1; wave <= reconnectWaves; wave++) {
            const previousClients = currentClients;
            const preDropInboundFlowWindow = beginBrowserInboundFlowWindow(
                browserRuntime,
                "preDrop",
                `reconnect wave ${wave} pre-drop browser inbound flow resume`,
                { wave },
            );
            const preDropInboundFlow = await finishBrowserInboundFlowWindow(
                browserRuntime,
                preDropInboundFlowWindow,
                `reconnect wave ${wave} pre-drop browser inbound flow resume`,
            );
            inboundFlowEvidence.preDrop.push(preDropInboundFlow);
            const reconnectRecoveryInboundFlowWindow = beginBrowserInboundFlowWindow(
                browserRuntime,
                "reconnectRecovery",
                `reconnect wave ${wave} recovery through minimum chunks`,
                { wave },
            );
            const relayBeforeDrop = await readRelayRuntime();
            const browserBeforeDrop = browserRuntimeSnapshot(browserRuntime);
            const sessionBeforeDrop = sessionRuntimeSnapshot(sessionState);
            const dropAt = performance.now();

            // Freeze the Java-side poll analogue, queue a deterministic marker, and
            // abnormally tear down every live WebSocket in one JS turn. This is a
            // real mid-PLAY transport loss: the bridge's onclose path must retain
            // the synthetic onmessage marker and the non-1000 close error until the
            // Java channel observes them and invokes its final close hook. The marker
            // exercises JSBody queue ordering; it is not claimed as a network tail.
            for (const client of previousClients) {
                client.pausePollingForTransportDrop(dropAt);
            }
            const transportDrop = forceAbnormalTransportDrop(
                browserRuntime, previousClients, wave);
            const dropDispatchSpreadMillis = timestampSpread(
                transportDrop.map((probe) => probe.terminatedAt));
            assert.ok(dropDispatchSpreadMillis <= 50,
                `reconnect wave ${wave} did not drop all clients together`);
            await waitFor(
                () => transportDrop.every((probe) => probe.entry.closed) &&
                    browserRuntime.wsStats.sockets.size === 0,
                `reconnect wave ${wave} abnormal WebSocket close`, 5000,
                () => JSON.stringify({
                    activeWebSockets: browserRuntime.wsStats.sockets.size,
                    entries: transportDrop.map((probe) => ({
                        id: probe.id,
                        closed: probe.entry.closed,
                        errors: probe.entry.errors,
                        hasPendingInbound:
                            browserRuntime.bridge.hasPendingInbound(probe.id),
                    })),
                }));
            const relayAfterDrop = await waitRelayRuntime(
                (snapshot) => relayRuntimeIsClean(snapshot),
                `reconnect wave ${wave} RelayNode final-close cleanup`,
                5000,
            );
            const transportDropEvidence = await captureAbnormalTransportDrop(
                browserRuntime, transportDrop);
            const browserAfterTransportDrop = browserRuntimeSnapshot(browserRuntime);
            assert.equal(browserAfterTransportDrop.activeChannels, clientCount,
                "abnormal transport close retired bridge entries before Java final close");
            assert.deepEqual({
                activeWebSockets: browserAfterTransportDrop.activeWebSockets,
                queuedBytes: browserAfterTransportDrop.queuedBytes,
                queuedFrames: browserAfterTransportDrop.queuedFrames,
                inboundQueuedBytes: browserAfterTransportDrop.inboundQueuedBytes,
                activeRelayTargetLeases:
                    browserAfterTransportDrop.activeRelayTargetLeases,
            }, {
                activeWebSockets: 0,
                queuedBytes: 0,
                queuedFrames: 0,
                inboundQueuedBytes: 0,
                activeRelayTargetLeases: 0,
            }, "abnormal transport close retained browser queue/lease state");
            if (!externalMode && relayAfterDrop.target !== undefined) {
                assert.equal(relayAfterDrop.target.totalConnections,
                    clientCount * wave,
                    `reconnect wave ${wave} changed target totals while dropping`);
            }
            const relayAfterDropGauges = relayRuntimeTelemetryRequired
                ? assertRelayRuntimeGaugesZero(
                    relayAfterDrop,
                    `reconnect wave ${wave} abnormal-drop cleanup`,
                )
                : relayRuntimeGaugeEvidence(relayAfterDrop);
            if (relayRuntimeTelemetryRequired) {
                assertRelayRuntimeConnectionGauges(
                    relayAfterDrop,
                    0,
                    `reconnect wave ${wave} abnormal-drop logical/physical cleanup`,
                );
            }

            const javaFinalCloseAt = performance.now();
            for (const client of previousClients) client.close("java-final-close");
            const javaFinalCloseDispatchSpreadMillis = timestampSpread(
                previousClients.map((client) => client.closedAt));
            assert.ok(javaFinalCloseDispatchSpreadMillis <= 50,
                `reconnect wave ${wave} did not finalize all Java channels together`);
            await waitForBrowserRuntimeCleanup(browserRuntime,
                `reconnect wave ${wave} browser Java final-close cleanup`);
            const browserAfterJavaFinalClose = browserRuntimeSnapshot(browserRuntime);
            assertBrowserRuntimeClean(browserAfterJavaFinalClose,
                `reconnect wave ${wave} Java final-close`);
            // Give the vanilla server one tick to retire the old player objects;
            // the reconnect timers below still start at the simultaneous drop.
            await delay(100);

            const replacementClients = createClients(
                wave, previousClients, performance.now(), dropAt);
            assert.equal(replacementClients.length, previousClients.length);
            for (let index = 0; index < replacementClients.length; index++) {
                const previous = previousClients[index];
                const replacement = replacementClients[index];
                assert.notEqual(replacement.id, previous.id,
                    "reconnect reused a BrowserWebSocketChannel id");
                assert.equal(replacement.username, previous.username,
                    "reconnect changed the Minecraft account username");
                assert.equal(replacement.profileId, previous.profileId,
                    "reconnect changed the Minecraft account profile");
                assert.equal(replacement.accessToken, previous.accessToken,
                    "reconnect changed the deterministic session identity");
                assert.ok(!seenBuffers.has(replacement.buffer),
                    "reconnect reused a protocol input buffer");
                assert.equal(replacement.cipher, undefined,
                    "reconnect inherited an AES cipher before login");
                assert.equal(replacement.decipher, undefined,
                    "reconnect inherited an AES decipher before login");
            }
            currentClients = replacementClients;
            allClients.push(...replacementClients);
            await connectClientWave(replacementClients);
            await waitFor(
                () => replacementClients.every((client) =>
                    client.failure === undefined &&
                    client.phase === "play" &&
                    client.loginFinished &&
                    (externalMode || (client.encryptionRequest && client.aesCfb8Enabled)) &&
                    client.playLoginPackets > 0 &&
                    client.minimumChunkTargetReached()),
                `browser Relay reconnect wave ${wave} PLAY/chunk`, 90000,
                () => JSON.stringify(replacementClients.map((client) =>
                    client.diagnostics())));
            for (const client of replacementClients) {
                client.checkError();
                assertClientSecurity(client, `reconnect wave ${wave} client ${client.id}`);
                if (!externalMode || client.cipher !== undefined) {
                    assert.ok(client.cipher !== undefined && !seenCiphers.has(client.cipher),
                        "reconnect reused an AES cipher object");
                    assert.ok(client.decipher !== undefined &&
                        !seenDeciphers.has(client.decipher),
                        "reconnect reused an AES decipher object");
                    assert.ok(client.secretFingerprint !== undefined &&
                        !seenSecretFingerprints.has(client.secretFingerprint),
                    "reconnect reused an AES shared secret");
                }
                seenBuffers.add(client.buffer);
                seenCiphers.add(client.cipher);
                seenDeciphers.add(client.decipher);
                seenSecretFingerprints.add(client.secretFingerprint);
            }
            const secretFingerprints = allClients.map((client) =>
                client.secretFingerprint).filter(Boolean);
            assert.equal(new Set(secretFingerprints).size, secretFingerprints.length,
                "reconnect reused an AES shared secret");
            const relayAtChunks = await waitRelayRuntime(
                (snapshot) => snapshot.activeConnections === clientCount &&
                    snapshot.target?.activeConnections === clientCount &&
                    snapshot.target?.totalConnections === clientCount * (wave + 1) &&
                    (!relayRuntimeTelemetryRequired ||
                        relayRuntimeConnectionGaugesEqual(snapshot, clientCount)) &&
                    (!relayRuntimeTelemetryRequired || relayRuntimeGaugesAreZero(snapshot)),
                `reconnect wave ${wave} RelayNode active tunnel quiescence`,
                5000,
            );
            const reconnectInboundFlow = await finishBrowserInboundFlowWindow(
                browserRuntime,
                reconnectRecoveryInboundFlowWindow,
                `reconnect wave ${wave} recovery through minimum chunks`,
            );
            inboundFlowEvidence.reconnectRecovery.push(reconnectInboundFlow);
            const reconnectInboundFlowStage = {
                wave,
                preDrop: preDropInboundFlow,
                ...reconnectInboundFlow,
            };
            inboundFlowEvidence.reconnectWaves.push(reconnectInboundFlowStage);
            relayRuntimeBeforeSoak = relayAtChunks;
            const browserAtChunks = browserRuntimeSnapshot(browserRuntime);
            const sessionAtChunks = sessionRuntimeSnapshot(sessionState);
            if (!externalMode) {
                assert.equal(sessionAtChunks.joins - sessionBeforeDrop.joins, clientCount,
                    `reconnect wave ${wave} did not create fresh session joins`);
                assert.equal(sessionAtChunks.hasJoined - sessionBeforeDrop.hasJoined, clientCount,
                    `reconnect wave ${wave} did not create fresh hasJoined checks`);
            }
            if (relayAtChunks.activeConnections !== undefined) {
                assert.equal(relayAtChunks.activeConnections, clientCount,
                    `reconnect wave ${wave} did not restore every Relay tunnel`);
            }
            if (relayAtChunks.target !== undefined) {
                assert.equal(relayAtChunks.target.activeConnections, clientCount,
                    `reconnect wave ${wave} did not restore every target route`);
                if (!externalMode) assert.equal(relayAtChunks.target.totalConnections,
                    clientCount * (wave + 1),
                    `reconnect wave ${wave} target connection count was not monotonic`);
            }
            const relayAtChunksGauges = relayRuntimeTelemetryRequired
                ? assertRelayRuntimeGaugesZero(
                    relayAtChunks,
                    `reconnect wave ${wave} active tunnel gauge check`,
                )
                : relayRuntimeGaugeEvidence(relayAtChunks);
            if (relayRuntimeTelemetryRequired) {
                assertRelayRuntimeConnectionGauges(
                    relayAtChunks,
                    clientCount,
                    `reconnect wave ${wave} active logical/physical connection gauges`,
                );
                assertRelayDrainPerformance(
                    [relayAfterDrop, relayAtChunks],
                    `reconnect wave ${wave} multiplayer drain`,
                );
            }
            const replacementHealth = assertSoakLiveness(
                replacementClients,
                browserAtChunks,
                relayAtChunks,
                `reconnect wave ${wave}`,
            );
            for (const client of replacementClients) {
                // Do not make a p99 decision from the 9-20 samples available
                // immediately after reconnect.  Keep scalar max-gap and
                // readiness checks active; the strict p99/p99.9/max histogram
                // gates run for every live replacement client after the
                // complete steady-soak window below.
                assertClientPerformance(client,
                    `reconnect wave ${wave} client ${client.id}`, {
                        requireLatencyDistributions: false,
                    });
            }
            reconnectEvidence.push({
                wave,
                simultaneousDrop: true,
                transportDrop: {
                    abnormalWebSocketClose: true,
                    method: "node-websocket-terminate",
                    harnessRequestedMinecraftDisconnectPacket: false,
                    dispatchSpreadMillis: dropDispatchSpreadMillis,
                    retainedEntriesBeforeJavaFinalClose: clientCount,
                    evidence: transportDropEvidence,
                    syntheticMarkerLabel: "synthetic-inbound-marker",
                    retireClosedEntry: {
                        defined: typeof browserRuntime.bridge.retireClosedEntry === "function",
                        invoked: false,
                        note: "optional hook is reported only; retention is tested, not repaired",
                    },
                },
                javaFinalClose: {
                    invoked: true,
                    atMillis: Number(javaFinalCloseAt.toFixed(3)),
                    dispatchSpreadMillis: javaFinalCloseDispatchSpreadMillis,
                    cleanupAllZero: true,
                },
                dropDispatchSpreadMillis,
                previousChannelIds: previousClients.map((client) => client.id),
                replacementChannelIds: replacementClients.map((client) => client.id),
                sameAccountIdentity: true,
                stateIsolation: {
                    newChannelIds: true,
                    newProtocolBuffers: true,
                    newCipherObjects: true,
                    uniqueSharedSecretFingerprints: true,
                },
                session: {
                    beforeDrop: sessionBeforeDrop,
                    atMinimumChunks: sessionAtChunks,
                    joinsDelta: sessionAtChunks.joins - sessionBeforeDrop.joins,
                    hasJoinedDelta:
                        sessionAtChunks.hasJoined - sessionBeforeDrop.hasJoined,
                },
                browser: {
                    beforeDrop: browserBeforeDrop,
                    afterTransportDrop: browserAfterTransportDrop,
                    afterJavaFinalClose: browserAfterJavaFinalClose,
                    atMinimumChunks: browserAtChunks,
                },
                inboundFlow: reconnectInboundFlowStage,
                relay: {
                    beforeDrop: relayBeforeDrop,
                    afterDrop: relayAfterDrop,
                    atMinimumChunks: relayAtChunks,
                    runtimeGauges: {
                        afterDrop: relayAfterDropGauges,
                        atMinimumChunks: relayAtChunksGauges,
                    },
                    runtimeConnectionGauges: {
                        afterDrop: relayRuntimeConnectionGaugeEvidence(relayAfterDrop),
                        atMinimumChunks: relayRuntimeConnectionGaugeEvidence(relayAtChunks),
                    },
                    reconnectDelta: relayRuntimeDelta(relayAfterDrop, relayAtChunks),
                },
                health: replacementHealth,
                clients: replacementClients.map((client) => ({
                    ...client.result(),
                    dropTiming: client.dropTimingResult(dropAt),
                })),
            });
        }
        const steadySoakInboundFlowWindow = beginBrowserInboundFlowWindow(
            browserRuntime,
            "steadySoak",
            "steady multiplayer soak",
        );
        soakStartedAt = performance.now();
        const soakObservation = startSoakPerformanceObservation(
            currentClients,
            browserRuntime,
        );
        if (soakMs > 0) await delayAtLeast(soakMs);
        soakPerformance = soakObservation.finish();
        inboundFlowEvidence.steadySoak = await finishBrowserInboundFlowWindow(
            browserRuntime,
            steadySoakInboundFlowWindow,
            "steady multiplayer soak",
        );
        inboundFlowEvidence.postSoak = inboundFlowEvidence.steadySoak;
        assertSoakPerformance(soakPerformance, "post-soak multiplayer performance");
        soakCompletedAt = performance.now();
        relayRuntimeAfterSoak = await waitRelayRuntime(
            (snapshot) => snapshot.activeConnections === clientCount &&
                snapshot.target?.activeConnections === clientCount &&
                (!relayRuntimeTelemetryRequired ||
                    relayRuntimeConnectionGaugesEqual(snapshot, clientCount)) &&
                (!relayRuntimeTelemetryRequired || relayRuntimeGaugesAreZero(snapshot)),
            "post-soak RelayNode active tunnel quiescence",
            5000,
        );
        browserRuntimeAfterSoak = browserRuntimeSnapshot(browserRuntime);
        soakHealth = assertSoakLiveness(
            currentClients,
            browserRuntimeAfterSoak,
            relayRuntimeAfterSoak,
            "post-soak",
        );
        for (const client of currentClients) {
            assertClientPerformance(client, `post-soak client ${client.id}`);
        }
        if (relayRuntimeTelemetryRequired) {
            assertRelayRuntimeConnectionGauges(
                relayRuntimeAfterSoak,
                clientCount,
                "post-soak logical/physical connection gauges",
            );
            assertRelayDrainPerformance(
                [relayRuntimeAfterSoak],
                "post-soak multiplayer drain",
            );
        }
    }
    finally {
        pollScheduler?.stop();
        if (browserRuntime !== undefined) {
            finalCleanupInboundFlowWindow = beginBrowserInboundFlowWindow(
                browserRuntime,
                "finalCleanup",
                "final browser transport cleanup",
            );
        }
        for (const client of currentClients) client.close("final-close");
        await waitForBrowserRuntimeCleanup(browserRuntime,
            "browser Relay transport cleanup").catch(() => {
            // Preserve the primary protocol failure. Successful runs assert every
            // cleanup counter below with a more specific lifecycle error.
        });
        if (browserRuntime !== undefined) {
            inboundFlowEvidence.finalCleanup = captureBrowserInboundFlowWindow(
                browserRuntime,
                finalCleanupInboundFlowWindow,
                "final browser transport cleanup",
            );
        }
        relayRuntimeAfterClose = await waitRelayRuntime(
            (snapshot) => relayRuntimeIsClean(snapshot),
            "RelayNode tunnel/timer cleanup",
            5000,
        ).catch(() => undefined);
    }

    const expectedConnections = clientCount * (reconnectWaves + 1);
    const phases = allClients.flatMap((client) => client.connectPhases);
    const relayConnections = phases.filter((event) => event.phase === "relay-connected");
    assert.equal(relayConnections.length, expectedConnections,
        "browser transport did not establish one real RelayNode WebSocket per client");
    assert.equal(browserRuntime.wsStats.connections, expectedConnections,
        "browser transport opened an unexpected number of WebSocket tunnels");
    assert.ok(browserRuntime.wsStats.urls.every((url) =>
        url === relayEndpoint.url),
    "browser transport connected to an unexpected RelayNode");
    assert.equal(browserRuntime.stats.relayTargetAttestationFailures, 0,
        "Relay target attestation rejected a valid browser tunnel");
    assert.ok(browserRuntime.wsStats.controlFrames >= expectedConnections,
        "browser runtime did not send WebSocket connect controls");
    assert.ok(browserRuntime.wsStats.binaryBytes > 0,
        "browser runtime did not send binary WebSocket frames");
    if (!externalMode) {
        assert.equal(sessionState.joins.length, expectedConnections,
            "vanilla online-mode login did not produce one authenticated session join per client");
        assert.equal(sessionState.hasJoined.length, expectedConnections,
            "vanilla online-mode login did not verify one hasJoined request per client");
        for (const [accessToken, profileId] of sessionState.expectedProfiles) {
            const identityJoins = sessionState.joins.filter((join) =>
                join.accessToken === accessToken && join.selectedProfile === profileId);
            assert.equal(identityJoins.length, reconnectWaves + 1,
                `session identity ${profileId} did not authenticate in every wave`);
        }
    }
    assert.equal(new Set(allClients.map((client) => client.id)).size,
        expectedConnections,
        "browser reconnect lifecycle reused a channel id");
    const observedSecretFingerprints = allClients.map((client) => client.secretFingerprint)
        .filter(Boolean);
    if (!externalMode || observedSecretFingerprints.length > 0) {
        assert.equal(new Set(observedSecretFingerprints).size,
            observedSecretFingerprints.length,
            "browser reconnect lifecycle reused encryption state");
    }
    assert.equal(browserRuntime.bridge.channels.size, 0,
        "browser transport leaked a channel after multiplayer cleanup");
    assert.equal(browserRuntime.wsStats.sockets.size, 0,
        "browser transport leaked a WebSocket after multiplayer cleanup");
    assert.equal(browserRuntime.stats.queuedBytes, 0,
        "browser transport retained outbound bytes after multiplayer cleanup");
    assert.equal(browserRuntime.stats.queuedFrames, 0,
        "browser transport retained outbound frames after multiplayer cleanup");
    assert.equal(browserRuntime.stats.inboundQueuedBytes, 0,
        "browser transport retained inbound bytes after multiplayer cleanup");
    assert.equal(browserRuntime.stats.activeRelayTargetLeases, 0,
        "browser transport retained a RelayNode target lease after multiplayer cleanup");
    const finalBrowserRuntimeSnapshot = browserRuntimeSnapshot(browserRuntime);
    assertBrowserRuntimeClean(finalBrowserRuntimeSnapshot,
        "final browser transport cleanup");
    inboundFlowEvidence.finalCleanup = assertBrowserInboundFlowWindow(
        inboundFlowEvidence.finalCleanup,
        "final browser transport cleanup",
        { requireCleanup: true },
    );
    assert.equal(relayRuntimeBaseline.activeConnections, 0,
        "RelayNode baseline unexpectedly had active browser tunnels");
    if (!externalMode) {
        assert.equal(relayRuntimeBaseline.target, undefined,
            "RelayNode baseline unexpectedly reported a target route before first use");
        assert.equal(relayRuntimeBaseline.targetEvidence.available, false,
            "RelayNode baseline target evidence was synthesized instead of observed");
    }
    if (relayRuntimeAtChunks.activeConnections !== undefined) {
        assert.equal(relayRuntimeAtChunks.activeConnections, clientCount,
            "RelayNode did not report every active multiplayer tunnel at chunk readiness");
    }
    if (relayRuntimeAtChunks.target !== undefined) {
        assert.equal(relayRuntimeAtChunks.target.activeConnections, clientCount,
            "RelayNode did not report every active target route at initial chunk readiness");
        if (!externalMode) assert.equal(relayRuntimeAtChunks.target.totalConnections, clientCount,
            "RelayNode initial target connection count did not match client count");
    }
    if (!externalMode) {
        assert.equal(relayRuntimeAfterSoak.target.totalConnections, expectedConnections,
            "RelayNode target route did not count every reconnect tunnel");
    }
    if (relayRuntimeTelemetryRequired) {
        assertRelayRuntimeGaugesZero(relayRuntimeAtChunks,
            "encrypted online-mode tunnels retained RelayNode runtime gauges");
        assertRelayRuntimeGaugesZero(relayRuntimeAfterSoak,
            "encrypted online-mode soak retained RelayNode runtime gauges");
    }
    assert.equal(relayRuntimeAfterClose?.activeConnections, 0,
        "RelayNode retained an active tunnel after browser cleanup");
    assert.equal(relayRuntimeAfterClose?.target?.activeConnections, 0,
        "RelayNode retained an active target route after browser cleanup");
    if (!externalMode) {
        assert.equal(relayRuntimeAfterClose?.target?.totalConnections, expectedConnections,
            "RelayNode final target connection count omitted a reconnect tunnel");
    }
    if (relayRuntimeTelemetryRequired) {
        assertRelayRuntimeConnectionGauges(
            relayRuntimeAfterClose,
            0,
            "RelayNode retained a logical/physical connection after browser cleanup",
        );
        assertRelayRuntimeGaugesZero(relayRuntimeAfterClose,
            "RelayNode retained a runtime gauge after browser cleanup");
        assertRelayDrainPerformance(
            [relayRuntimeAfterClose],
            "final multiplayer close drain",
        );
    }

    const actualSoakMillis = elapsedMillis(soakStartedAt, soakCompletedAt) ?? 0;
    if (acceptanceMode) {
        assert.equal(clientCount, STRICT_ACCEPTANCE_TARGET.clients,
            "strict acceptance client count drifted");
        assert.equal(minimumChunkPackets, STRICT_ACCEPTANCE_TARGET.minimumChunkPackets,
            "strict acceptance chunk target drifted");
        assert.equal(soakMs, STRICT_ACCEPTANCE_TARGET.soakMillis,
            "strict acceptance soak target drifted");
        assert.equal(reconnectWaves, STRICT_ACCEPTANCE_TARGET.reconnectWaves,
            "strict acceptance reconnect target drifted");
        assert.ok(actualSoakMillis >= STRICT_ACCEPTANCE_TARGET.soakMillis,
            `strict acceptance soak elapsed only ${actualSoakMillis}ms`);
    }
    if (stressMode) {
        assert.equal(clientCount, stressTarget.clients,
            "stress tier client count drifted");
        assert.equal(minimumChunkPackets, stressTarget.minimumChunkPackets,
            "stress tier chunk target drifted");
        assert.equal(soakMs, stressTarget.soakMillis,
            "stress tier soak target drifted");
        assert.equal(reconnectWaves, stressTarget.reconnectWaves,
            "stress tier reconnect target drifted");
        assert.equal(expectedConnections, stressTarget.clientLifecycles,
            "stress tier total client lifecycle count drifted");
        assert.ok(actualSoakMillis >= stressTarget.soakMillis,
            `stress tier soak elapsed only ${actualSoakMillis}ms`);
        for (const client of allClients) {
            assert.ok(client.stressQualifiedChunkPositions.size >= minimumChunkPackets,
                `${client.username} stress wave observed only ` +
                `${client.stressQualifiedChunkPositions.size} unique chunks after ` +
                `the distance contract`);
            assert.ok(Number.isInteger(client.observedChunkCacheCenter?.x) &&
                Number.isInteger(client.observedChunkCacheCenter?.z),
            `${client.username} did not observe the chunk-cache center contract`);
            assert.equal(client.observedChunkCacheRadius, stressTarget.serverViewDistance,
                `${client.username} did not observe the radius-8 cache contract`);
            assert.equal(client.observedSimulationDistance, stressTarget.simulationDistance,
                `${client.username} did not observe the simulation-distance contract`);
            assert.equal(chunkTrackingCapacity(client.observedChunkCacheRadius),
                stressTarget.maximumUniqueChunkCapacity,
                `${client.username} observed an impossible chunk-window capacity`);
            assert.ok(client.chunkBatchAcknowledgements > 0,
                `${client.username} stress wave did not acknowledge a chunk batch`);
            assert.equal(client.chunkBatchStarts, client.chunkBatchFinished,
                `${client.username} stress wave retained an unfinished chunk batch`);
            assert.equal(client.chunkBatchFinished, client.chunkBatchAcknowledgements,
                `${client.username} stress wave omitted a chunk-batch ACK`);
            assert.equal(client.chunkBatchOpen, false,
                `${client.username} stress wave closed with an open chunk batch`);
            assert.equal(client.chunkBatchProtocolErrors, 0,
                `${client.username} stress wave reported chunk-batch protocol errors`);
            assert.equal(client.chunkBatchCountMismatches, 0,
                `${client.username} stress wave reported chunk-batch count mismatches`);
        }
    }
    const pollSchedulerEvidence = pollScheduler?.evidence() ?? null;
    const result = {
        schemaVersion: "browser-full-path-result-v2",
        ok: true,
        acceptance: {
            // All assertions above have completed before this object is built.  Keep the
            // successful strict/compatible outcome explicit instead of forcing consumers to
            // infer it from the top-level result.ok field.
            ok: true,
            mode: acceptanceMode
                ? "strict-acceptance"
                : stressMode ? `stress-tier-${stressTier}` : "compatible-smoke",
            diagnostics: {
                arrivalTraceEnabled: ARRIVAL_TRACE_ENABLED,
                diagnosticOnly: ARRIVAL_TRACE_ENABLED,
                strictEvidenceEligible: ARRIVAL_TRACE_STRICT_EVIDENCE_ELIGIBLE,
            },
            required: acceptanceMode
                ? {
                    ...STRICT_ACCEPTANCE_TARGET,
                    profiles: Object.keys(CANONICAL_PROFILES),
                    relayRuntimeGaugesZero: [...RELAY_RUNTIME_GAUGES],
                    relayRuntimeConnectionGauges: [...RELAY_RUNTIME_CONNECTION_GAUGES],
                    relayDrainMaxDurationMillis: RELAY_DRAIN_MAX_DURATION_MILLIS,
                    relayDrainSendErrors: 0,
                    relayDrainCleanupRequired: true,
                    browserCleanupGaugesZero: [...BROWSER_RUNTIME_CLEANUP_GAUGES],
                    syntheticMarkerLabel: "synthetic-inbound-marker",
                    runtimeJavaPolicy: { ...STRICT_RUNTIME_JAVA_POLICY },
                    multiplayerPerformance: { ...MULTIPLAYER_PERFORMANCE_TARGET },
                }
                : stressMode
                    ? {
                        ...stressTarget,
                        uniqueChunkTarget: true,
                        maximumUniqueChunkCapacity,
                        observedDistanceContractRequired: true,
                        chunkBatchAcknowledgementRequired: true,
                        multiplayerPerformance: { ...MULTIPLAYER_PERFORMANCE_TARGET },
                        latencyDistribution: stressLatencyDistributionContract(),
                    }
                    : {
                    clients: clientCount,
                    minimumChunkPackets,
                    soakMillis: soakMs,
                    reconnectWaves,
                    relayRuntimeGaugesZero: [...RELAY_RUNTIME_GAUGES],
                    relayRuntimeConnectionGauges: [...RELAY_RUNTIME_CONNECTION_GAUGES],
                    relayDrainMaxDurationMillis: RELAY_DRAIN_MAX_DURATION_MILLIS,
                    relayDrainSendErrors: 0,
                    relayDrainCleanupRequired: true,
                    browserCleanupGaugesZero: [...BROWSER_RUNTIME_CLEANUP_GAUGES],
                    syntheticMarkerLabel: "synthetic-inbound-marker",
                    multiplayerPerformance: { ...MULTIPLAYER_PERFORMANCE_TARGET },
                },
            observed: {
                clients: clientCount,
                minimumChunkPackets,
                uniqueChunkTarget: stressMode,
                maximumUniqueChunkCapacity,
                clientViewDistance,
                serverViewDistance,
                effectiveChunkRadius,
                desiredChunksPerTick,
                soakMillis: soakMs,
                actualSoakMillis,
                reconnectWaveCount: reconnectEvidence.length,
                expectedConnections,
                profile: {
                    id: activeProfile.id,
                    path: repositoryRelativePath(activeProfile.path),
                    canonicalProfilePath: repositoryRelativePath(activeProfile.path),
                    protocolVersion: activeProfile.protocolVersion,
                    worldVersion: activeProfile.worldVersion,
                    javaVersion: activeProfile.javaVersion,
                    runtimeJavaMajor: runtimeJava?.major ?? null,
                    runtimeJavaExecutable: runtimeJava?.executable ?? null,
                    serverSha1: verifiedServerJar?.sha1 ?? null,
                    expectedServerJarSha1: activeProfile.official.serverSha1,
                    actualServerJarSha1: verifiedServerJar?.sha1 ?? null,
                },
                runtimeJavaMajor: runtimeJava?.major ?? null,
                runtimeJavaExecutable: runtimeJava?.executable ?? null,
                soak: soakHealth,
                soakPerformance,
                pollScheduler: pollSchedulerEvidence,
                inboundFlow: inboundFlowEvidence,
                reconnectWaves: reconnectEvidence.map((wave) => ({
                    wave: wave.wave,
                    health: wave.health,
                    syntheticMarkerLabel: wave.transportDrop.syntheticMarkerLabel,
                    relayRuntimeGauges: wave.relay.runtimeGauges,
                    relayRuntimeConnectionGauges: wave.relay.runtimeConnectionGauges,
                })),
                finalCleanup: {
                    browser: browserRuntimeSnapshot(browserRuntime),
                    browserCleanupGauges: browserRuntimeCleanupGaugeEvidence(
                        browserRuntimeSnapshot(browserRuntime)),
                    relay: relayRuntimeAfterClose,
                    relayRuntimeGauges: relayRuntimeGaugeEvidence(relayRuntimeAfterClose),
                    relayRuntimeConnectionGauges:
                        relayRuntimeConnectionGaugeEvidence(relayRuntimeAfterClose),
                },
            },
            actual: {
                soakMillis: actualSoakMillis,
                soak: soakHealth,
                soakPerformance,
                pollScheduler: pollSchedulerEvidence,
                inboundFlow: inboundFlowEvidence,
                reconnectWaves: reconnectEvidence.map((wave) => ({
                    wave: wave.wave,
                    health: wave.health,
                    syntheticMarkerLabel: wave.transportDrop.syntheticMarkerLabel,
                    relayRuntimeGauges: wave.relay.runtimeGauges,
                    relayRuntimeConnectionGauges: wave.relay.runtimeConnectionGauges,
                })),
                serverJarSha1: verifiedServerJar?.sha1 ?? null,
                runtimeJavaMajor: runtimeJava?.major ?? null,
                runtimeJavaExecutable: runtimeJava?.executable ?? null,
                profile: {
                    id: activeProfile.id,
                    path: repositoryRelativePath(activeProfile.path),
                    canonicalProfilePath: repositoryRelativePath(activeProfile.path),
                    protocolVersion: activeProfile.protocolVersion,
                    worldVersion: activeProfile.worldVersion,
                    javaVersion: activeProfile.javaVersion,
                    runtimeJavaMajor: runtimeJava?.major ?? null,
                    runtimeJavaExecutable: runtimeJava?.executable ?? null,
                    serverSha1: verifiedServerJar?.sha1 ?? null,
                    expectedServerJarSha1: activeProfile.official.serverSha1,
                    actualServerJarSha1: verifiedServerJar?.sha1 ?? null,
                },
            },
        },
        transport: {
            browserChannelSource: fileURLToPath(channelSourceUrl),
            browserChannelSourceSha256: browserChannelSource.evidence.sourceSha256,
            browserChannelSourceNormalizedSha256:
                browserChannelSource.evidence.normalizedSourceSha256,
            browserJsBodyMarkers: browserChannelSource.evidence.jsBodyMarkers,
            browserChannelSourceEvidence: browserChannelSource.evidence,
            browserJsBody: true,
            teaVmBuildRequired: false,
            realWebSocketFraming: true,
            relayUrl: relayEndpoint.url,
            clients: clientCount,
            reconnectWaves,
            expectedConnections,
            stressMode,
            stressTier: stressTier ?? null,
            webSocketConnections: browserRuntime.wsStats.connections,
            webSocketUrls: browserRuntime.wsStats.urls,
            controlFrames: browserRuntime.wsStats.controlFrames,
            binaryFrames: browserRuntime.wsStats.binaryFrames,
            binaryBytes: browserRuntime.wsStats.binaryBytes,
            inboundFrames: browserRuntime.stats.receivedFrames,
            inboundBytes: browserRuntime.stats.receivedBytes,
            relayTargetAttestationFailures: browserRuntime.stats.relayTargetAttestationFailures,
            activeChannelsAfterClose: browserRuntime.bridge.channels.size,
            activeWebSocketsAfterClose: browserRuntime.wsStats.sockets.size,
            queuedBytesAfterClose: browserRuntime.stats.queuedBytes,
            queuedFramesAfterClose: browserRuntime.stats.queuedFrames,
            inboundQueuedBytesAfterClose: browserRuntime.stats.inboundQueuedBytes,
            activeRelayTargetLeasesAfterClose:
                browserRuntime.stats.activeRelayTargetLeases,
            browserCleanupGaugesAfterClose: browserRuntimeCleanupGaugeEvidence(
                browserRuntimeSnapshot(browserRuntime)),
            browserGlobalPumpTelemetryAfterClose:
                browserGlobalPumpTelemetryEvidence(finalBrowserRuntimeSnapshot),
            arrivalTimeline: browserRuntime.arrivalTelemetry?.evidence?.() ?? null,
        },
        profile: {
            id: activeProfile.id,
            protocolVersion: activeProfile.protocolVersion,
            worldVersion: activeProfile.worldVersion,
            javaVersion: activeProfile.javaVersion,
            runtimeJavaMajor: runtimeJava?.major ?? null,
            runtimeJavaExecutable: runtimeJava?.executable ?? null,
            serverSha1: verifiedServerJar?.sha1 ?? null,
            path: repositoryRelativePath(activeProfile.path),
            canonicalProfilePath: repositoryRelativePath(activeProfile.path),
            serverJar,
            serverJarSha1: verifiedServerJar?.sha1 ?? null,
            expectedServerJarSha1: activeProfile.official.serverSha1,
        },
        vanilla: {
            onlineMode: externalMode ? null : true,
            unmodifiedServerJar: externalMode ? null : true,
            pluginsInstalled: externalMode ? null : false,
            workDirectory,
        },
        session: {
            joins: sessionState.joins.length,
            hasJoined: sessionState.hasJoined.length,
            publicKeyRequests: sessionState.publicKeyRequests,
        },
        arrivalTimeline: {
            schemaVersion: ARRIVAL_TIMELINE_SCHEMA_VERSION,
            independentExecution: false,
            strictGatesChanged: false,
            diagnosticOnly: true,
            strictEvidenceEligible: ARRIVAL_TRACE_STRICT_EVIDENCE_ELIGIBLE,
            limits: {
                perClientSlowSamples: ARRIVAL_TIMELINE_SAMPLE_LIMIT,
                perClientReconnectPhases: ARRIVAL_TIMELINE_RECONNECT_PHASE_LIMIT,
                frameMetadataRing: ARRIVAL_TIMELINE_FRAME_RING_LIMIT,
                traceEvents: ARRIVAL_TRACE_EVENT_LIMIT,
            },
            trace: arrivalTraceContract(),
            periodicServerSync: arrivalPeriodicServerSyncContract(),
            transport: browserRuntime.arrivalTelemetry?.evidence?.() ?? null,
            clients: allClients.map((client) => ({
                id: client.id,
                wave: client.wave,
                evidence: client.arrivalTimelineResult(),
            })),
        },
        clients: allClients.map((client) => client.result()),
        reconnectWaves: reconnectEvidence,
        inboundFlow: inboundFlowEvidence,
        relayPhases: phases,
        relayRuntime: {
            baseline: relayRuntimeBaseline,
            atMinimumChunks: relayRuntimeAtChunks,
            beforeSoak: relayRuntimeBeforeSoak,
            afterSoak: relayRuntimeAfterSoak,
            afterClose: relayRuntimeAfterClose,
            connectAndChunkDelta:
                relayRuntimeDelta(relayRuntimeBaseline, relayRuntimeAtChunks),
            soakDelta:
                relayRuntimeDelta(relayRuntimeBeforeSoak, relayRuntimeAfterSoak),
            totalDelta:
                relayRuntimeDelta(relayRuntimeBaseline, relayRuntimeAfterSoak),
        },
        browserGlobalPumpTelemetry: {
            afterSoak: browserGlobalPumpTelemetryEvidence(browserRuntimeAfterSoak),
            afterClose: browserGlobalPumpTelemetryEvidence(finalBrowserRuntimeSnapshot),
        },
        performanceContract: browserFullPathPerformanceContract(),
        pollScheduler: pollSchedulerEvidence,
        elapsedMillis: Number((performance.now() - smokeStartedAt).toFixed(1)),
    };
    await writeFile(path.join(workDirectory, "result.json"),
        JSON.stringify(result, null, 2) + "\n");
    console.log(JSON.stringify(result));
}
catch (error) {
    failure = error;
    let partialRelayRuntime;
    if (typeof readRelayRuntime === "function") {
        partialRelayRuntime = await readRelayRuntime().catch((runtimeError) => ({
            unavailable: true,
            error: String(runtimeError?.stack || runtimeError),
        }));
    }
    const partialBrowserRuntime = browserRuntime === undefined
        ? undefined
        : browserRuntimeSnapshot(browserRuntime);
    await writeFile(path.join(workDirectory, "failure.json"), JSON.stringify({
        ok: false,
        profile: activeProfile.id,
        error: String(error?.stack || error),
        workDirectory,
        partialEvidence: {
            clients: allClients.map((client) => clientLivenessEvidence(client)),
            activeClientIds: currentClients.map((client) => client.id),
            browserRuntime: partialBrowserRuntime,
            inboundFlow: inboundFlowEvidence,
            lastInboundFlowAttempt,
            relayRuntime: partialRelayRuntime,
            session: sessionRuntimeSnapshot(sessionState),
            browserChannelSourceEvidence: browserChannelSourceEvidenceForOutput(),
            performanceContract: browserFullPathPerformanceContract(),
            pollScheduler: pollScheduler?.evidence() ?? null,
            capturedAtElapsedMillis: Number(
                (performance.now() - smokeStartedAt).toFixed(3)),
        },
    }, null, 2) + "\n").catch(() => {});
    throw error;
}
    finally {
    if (browserRuntime !== undefined) {
        // The runtime has no process resources beyond WebSockets; close() is
        // best effort because failures can happen before all channels exist.
        await browserRuntime.close?.();
    }
    await stopChildProcess(relayProcess);
    await stopChildProcess(serverProcess, { gracefulInput: "stop\n" });
    await closeHttpServer(sessionServer);
    await writeFile(path.join(workDirectory, "server.log"), serverOutput).catch(() => {});
    await writeFile(path.join(workDirectory, "relay.log"), relayOutput).catch(() => {});
    if (failure !== undefined) {
        console.error(`Browser full-path evidence retained at ${workDirectory}`);
    }
}

}

async function connectClientWave(clients) {
    if (clientStartDelayMs === 0) {
        await Promise.all(clients.map((client) => client.connect()));
        return;
    }
    for (let index = 0; index < clients.length; index++) {
        if (index > 0) await delay(clientStartDelayMs);
        await clients[index].connect();
    }
}

/**
 * Dispatches a bounded batch of client polls per macrotask in round-robin
 * order.  The normal continuation is setImmediate; a zero-delay timer is used
 * only as a rare bounded fairness yield (every 256 immediate turns), while an
 * idle client set parks on the one-millisecond backoff timer. PLAY ticks are
 * serviced from this same continuation with a separate bounded cursor, so
 * each tab keeps its 50 ms cadence without a competing setInterval. Long
 * turns are recorded but do not force a timer: the immediate continuation
 * already returns control to Node between callbacks. A per-client due time and
 * bridge readiness check prevent empty tabs from manufacturing a busy-spin.
 * Windows commonly quantizes a
 * one-millisecond timeout, so recursively using setTimeout(1) makes the timer
 * quantum itself visible as a client poll gap.  The scheduler is deliberately
 * a test-harness component; shipped browser scheduling is measured separately
 * by BrowserWebSocketChannel telemetry.
 */
function createFairClientPollScheduler(getClients) {
    let stopped = false;
    let immediateHandle;
    let timerHandle;
    let watchdogHandle;
    let scheduleSequence = 0;
    let callbackSequence = 0;
    let cursor = 0;
    let callbackCount = 0;
    let dispatchedPolls = 0;
    let emptyCallbacks = 0;
    let maxVisibleClients = 0;
    let maxClientsPerCallback = 0;
    let maxVisibleDispatchSkew = 0;
    let lastVisibleDispatchCounts = [];
    let tickCursor = 0;
    let maxPlayTickServicesPerCallback = 0;
    let maxCallbackDurationMillis = 0;
    let maxCallbackDurationRawMillis = 0;
    let maxInterCallbackGapMillis = 0;
    let maxInterCallbackGapRawMillis = 0;
    let maxScheduleDelayMillis = 0;
    let maxScheduleDelayRawMillis = 0;
    let maxClientPollDurationMillis = 0;
    let maxClientPollDurationRawMillis = 0;
    let callbackBudgetExhaustions = 0;
    let callbackBudgetOverruns = 0;
    let callbackBudgetTickSkips = 0;
    let callbackBudgetPollSkips = 0;
    let slowCallbackSamplesTotal = 0;
    let slowCallbackSamplesDropped = 0;
    const slowCallbackSamples = [];
    let maxFinalizationTailMillis = 0;
    let maxFinalizationTailRawMillis = 0;
    let maxTotalAfterFinalizeMillis = 0;
    let maxTotalAfterFinalizeRawMillis = 0;
    let slowFinalizationTailSamplesTotal = 0;
    let slowFinalizationTailSamplesDropped = 0;
    const slowFinalizationTailSamples = [];
    let lastFinalizationTail;
    let fairnessSkips = 0;
    let lastCallbackAt;
    let lastCallbackSequence;
    let lastCallbackTrigger;
    let lastCallbackScheduledAt;
    let lastCallbackFinishedAt;
    let maxInterCallbackIdleGapRawMillis = 0;
    let schedulerGapSamplesTotal = 0;
    let schedulerGapSamplesDropped = 0;
    let schedulerGapClockAnomalies = 0;
    const schedulerGapSamples = [];
    let immediateSchedules = 0;
    let immediateCallbacks = 0;
    let timerSchedules = 0;
    let timerYieldCallbacks = 0;
    let timerFallbackCallbacks = 0;
    let idleSchedules = 0;
    let idleCallbacks = 0;
    let playTickServiceCalls = 0;
    let playTickDispatches = 0;
    let playTickServiceErrors = 0;
    let lastDueTicksAfterService = 0;
    let lastDueTicksBeforeIdle = 0;
    let maxDueTicks = 0;
    let dueTickImmediateContinuations = 0;
    let watchdogFires = 0;
    let overlappingCallbacks = 0;
    let turnsSinceTimerYield = 0;
    let heavyTurnCount = 0;
    let callbackRunning = false;
    let idleImmediateSpins = 0;
    let idleImmediateProbeStartedAt;
    let idleImmediateOverdueWakePending = false;
    const dispatchCounts = new Map();
    const pollDurationByClient = new Map();
    const retainSlowCallbackSample = (sample) => {
        slowCallbackSamples.push(sample);
        slowCallbackSamples.sort((left, right) =>
            right.durationRawMillis - left.durationRawMillis ||
            left.callbackSequence - right.callbackSequence);
        if (slowCallbackSamples.length > CALLBACK_TAIL_SAMPLE_LIMIT) {
            slowCallbackSamples.length = CALLBACK_TAIL_SAMPLE_LIMIT;
            slowCallbackSamplesDropped++;
        }
    };
    const retainSchedulerGapSample = (sample) => {
        schedulerGapSamples.push(sample);
        schedulerGapSamples.sort((left, right) => {
            const leftGap = Number(left.interCallbackIdleGapRawMillis) || 0;
            const rightGap = Number(right.interCallbackIdleGapRawMillis) || 0;
            if (leftGap !== rightGap) return rightGap - leftGap;
            const leftDuration = Number(left.callbackDurationRawMillis) || 0;
            const rightDuration = Number(right.callbackDurationRawMillis) || 0;
            return rightDuration - leftDuration ||
                left.callbackSequence - right.callbackSequence;
        });
        if (schedulerGapSamples.length > SCHEDULER_GAP_SAMPLE_LIMIT) {
            schedulerGapSamples.length = SCHEDULER_GAP_SAMPLE_LIMIT;
            schedulerGapSamplesDropped++;
        }
    };
    const retainSlowFinalizationTailSample = (sample) => {
        slowFinalizationTailSamples.push(sample);
        slowFinalizationTailSamples.sort((left, right) =>
            right.tailRawMillis - left.tailRawMillis ||
            left.callbackSequence - right.callbackSequence);
        if (slowFinalizationTailSamples.length >
            CALLBACK_FINALIZATION_TAIL_SAMPLE_LIMIT) {
            slowFinalizationTailSamples.length =
                CALLBACK_FINALIZATION_TAIL_SAMPLE_LIMIT;
            slowFinalizationTailSamplesDropped++;
        }
    };
    // Keep scheduling state per client rather than forcing every visible tab
    // through poll() on every immediate turn. The property is mirrored onto
    // normal BrowserMinecraftClient instances for diagnostics; the side map
    // keeps frozen/synthetic clients usable in the server-free smoke.
    const nextPollDueAtByClient = new Map();
    const validPollDueAt = (value) => Number.isFinite(value) || value === Infinity;
    const readNextPollDueAt = (client) => {
        // The side map is authoritative once a client has entered the
        // scheduler.  `client.nextPollDueAt` is only a compatibility mirror;
        // generated/frozen clients and reconnect replacements must not be able
        // to overwrite the scheduler's cursor by mutating that property.
        if (nextPollDueAtByClient.has(client)) {
            const stored = Number(nextPollDueAtByClient.get(client));
            return validPollDueAt(stored) ? stored : 0;
        }
        const explicit = Number(client?.nextPollDueAt);
        if (validPollDueAt(explicit)) {
            nextPollDueAtByClient.set(client, explicit);
            return explicit;
        }
        return 0;
    };
    const writeNextPollDueAt = (client, dueAt) => {
        const due = Number.isFinite(Number(dueAt))
            ? Number(dueAt) : performance.now();
        nextPollDueAtByClient.set(client, due);
        try {
            if (client !== null && client !== undefined &&
                (typeof client === "object" || typeof client === "function")) {
                client.nextPollDueAt = due;
            }
        }
        catch {
            // A frozen synthetic client is still schedulable via the side map.
        }
    };
    const hasPendingInbound = (client) => {
        if (client === null || client === undefined) return false;
        if (typeof client.hasPendingInbound === "function") {
            try { return !!client.hasPendingInbound(); }
            catch { return true; }
        }
        const bridge = client.bridge;
        if (bridge !== null && bridge !== undefined &&
            typeof bridge.hasPendingInbound === "function") {
            try { return !!bridge.hasPendingInbound(client.id); }
            catch { return true; }
        }
        if (client.buffer !== null && client.buffer !== undefined &&
            Number.isFinite(client.buffer.byteLength)) {
            return client.buffer.byteLength > 0;
        }
        // Unknown client implementations retain the old dispatch behaviour
        // rather than being silently starved by an unsupported readiness API.
        return true;
    };
    const hasImmediateInbound = (client) => {
        // BrowserMinecraftClient owns the authoritative immediate-readiness
        // contract (buffer first, then bridge).  Prefer it over bridge-only
        // compatibility probes so a stale bridge answer cannot park a client
        // that already has decoded bytes in its local cumulation buffer.
        if (typeof client?.hasImmediateInbound === "function") {
            try { return !!client.hasImmediateInbound(); }
            catch { return true; }
        }
        const bridge = client?.bridge;
        if (bridge !== null && bridge !== undefined &&
            typeof bridge.hasPendingInbound === "function") {
            try { return !!bridge.hasPendingInbound(client.id); }
            catch { return true; }
        }
        return hasPendingInbound(client);
    };
    // Client ids are numeric in the production harness.  Calling
    // String(...).localeCompare(..., {numeric:true}) from the callback hot
    // path forces ICU collation setup during the first scheduler turn (the
    // 1.21.11 run measured ~29 ms before any tick or poll was serviced).  Build
    // the collator once when the scheduler is created and keep the common
    // numeric-id path allocation/ICU free.  Non-numeric ids retain the old
    // numeric-aware ordering through the pre-warmed collator, with a simple
    // lexical fallback for runtimes without Intl.Collator.
    const visibleClientIdCollator = (() => {
        try {
            return typeof Intl === "object" && typeof Intl.Collator === "function"
                ? new Intl.Collator(undefined, { numeric: true }) : undefined;
        }
        catch {
            return undefined;
        }
    })();
    const compareVisibleClientIds = (left, right) => {
        const leftId = left?.id;
        const rightId = right?.id;
        if (typeof leftId === "number" && typeof rightId === "number" &&
            Number.isFinite(leftId) && Number.isFinite(rightId)) {
            return leftId - rightId;
        }
        if (leftId === rightId) return 0;
        const leftText = String(leftId);
        const rightText = String(rightId);
        if (visibleClientIdCollator !== undefined) {
            return visibleClientIdCollator.compare(leftText, rightText);
        }
        return leftText < rightText ? -1 : leftText > rightText ? 1 : 0;
    };
    const visibleDispatchEvidence = (clients) => {
        const entries = new Map();
        for (const client of clients) {
            if (client === null || client === undefined) continue;
            const id = client.id;
            if (id === undefined || id === null || entries.has(id)) continue;
            entries.set(id, {
                id,
                count: dispatchCounts.get(id) ?? 0,
            });
        }
        return [...entries.values()].sort(compareVisibleClientIds);
    };
    const recordVisibleDispatchEvidence = (clients) => {
        const entries = visibleDispatchEvidence(clients);
        const counts = entries.map(({ count }) => count);
        const skew = counts.length === 0
            ? 0 : Math.max(...counts) - Math.min(...counts);
        maxVisibleDispatchSkew = Math.max(maxVisibleDispatchSkew, skew);
        lastVisibleDispatchCounts = entries;
        return entries;
    };
    const isPollLive = (client) => client !== null && client !== undefined &&
        !client.closed && !client.pollingPaused && client.failure === undefined;
    const readPollFairnessFloor = (clients) => {
        let floor = Infinity;
        for (const client of clients) {
            if (!isPollLive(client)) continue;
            floor = Math.min(floor, dispatchCounts.get(client.id) ?? 0);
        }
        return Number.isFinite(floor) ? floor : 0;
    };
    const isPollFairlyEligible = (client, clients, floor =
        readPollFairnessFloor(clients)) => isPollLive(client) &&
        (dispatchCounts.get(client.id) ?? 0) <= floor;
    const hasFairPollReady = (clients, now = performance.now()) => {
        const floor = readPollFairnessFloor(clients);
        return clients.some((candidate) => isPollFairlyEligible(candidate, clients, floor) &&
            isPollReady(candidate, now));
    };
    const isPollReady = (client, now = performance.now()) => {
        if (client === null || client === undefined || client.closed ||
            client.pollingPaused || client.failure !== undefined) {
            return false;
        }
        // A bridge queue is eligible for the next fair due turn, but it does
        // not bypass that turn.  The old immediate-or-due shortcut let one
        // client with a continuous inbound stream remain ready on every
        // setImmediate callback while peers were still waiting for their
        // one-millisecond cadence.  Under tier-8 fan-in that produced large
        // visible dispatch skew and inflated peer poll p99.  Keep the
        // readiness probe in the contract, but gate both queued and partial
        // input on the scheduler-owned due cursor so a busy client cannot
        // monopolise the shared callback.
        const dueAt = readNextPollDueAt(client);
        if (now < dueAt) return false;
        // A complete queued frame is still useful evidence, but it cannot
        // bypass the scheduler-owned due boundary.  Once that boundary is
        // reached, poll both queued and quiescent clients so the due cadence
        // remains fair and the client can discover a newly arrived frame.
        if (hasImmediateInbound(client)) return true;
        return Number.isFinite(dueAt);
    };
    const isPlayTickDue = (client, now = performance.now()) => {
        if (client === null || client === undefined || client.closed ||
            client.pollingPaused || client.failure !== undefined ||
            client.phase !== "play" ||
            (client.playTickActive !== undefined && !client.playTickActive) ||
            typeof client.servicePlayTick !== "function") {
            return false;
        }
        // A transport drop clears nextPlayTickDueAt while leaving the client
        // active.  Admit exactly one service call after polling resumes so
        // servicePlayTick() can clear playTickSuspended and re-anchor the
        // cadence; otherwise a due-only admission guard would strand the
        // resumed client with an undefined deadline.
        if (client.playTickSuspended === true) return true;
        const dueAt = Number(client.nextPlayTickDueAt);
        const current = Number(now);
        return Number.isFinite(dueAt) && Number.isFinite(current) && current >= dueAt;
    };
    const countDuePlayTicks = (clients, now = performance.now()) => {
        let count = 0;
        for (const client of clients) {
            if (isPlayTickDue(client, now)) count++;
        }
        return count;
    };
    let eventLoopDelayMonitor;
    try {
        eventLoopDelayMonitor = monitorEventLoopDelay({ resolution: 10 });
        eventLoopDelayMonitor.enable();
    }
    catch {
        // Older/non-Node harnesses can still run the scheduler without the
        // optional perf_hooks histogram.  Evidence marks it unavailable.
        eventLoopDelayMonitor = undefined;
    }

    const roundedMillis = (value) => Number.isFinite(value) && value >= 0
        ? Number(value.toFixed(3)) : 0;
    const finiteNonNegativeMillis = (value) => {
        const number = Number(value);
        return Number.isFinite(number) && number >= 0 ? number : 0;
    };
    const eventLoopDelayEvidence = () => {
        const histogram = eventLoopDelayMonitor;
        if (histogram === undefined) {
            return {
                available: false,
                resolutionMillis: null,
                samples: 0,
                minMillis: null,
                p50Millis: null,
                p95Millis: null,
                p99Millis: null,
                maxMillis: null,
                rawMinMillis: null,
                rawP50Millis: null,
                rawP95Millis: null,
                rawP99Millis: null,
                rawMaxMillis: null,
            };
        }
        const samples = Number(histogram.count) || 0;
        if (samples === 0) {
            return {
                available: true,
                resolutionMillis: 10,
                samples: 0,
                minMillis: null,
                p50Millis: null,
                p95Millis: null,
                p99Millis: null,
                maxMillis: null,
                rawMinMillis: null,
                rawP50Millis: null,
                rawP95Millis: null,
                rawP99Millis: null,
                rawMaxMillis: null,
            };
        }
        const millis = (value) => {
            const number = Number(value);
            return Number.isFinite(number) && number > 0
                ? roundedMillis(number / 1e6) : 0;
        };
        const raw = (value) => {
            const number = Number(value);
            return Number.isFinite(number) && number >= 0 ? number / 1e6 : null;
        };
        return {
            available: true,
            resolutionMillis: 10,
            samples,
            minMillis: millis(histogram.min),
            p50Millis: millis(histogram.percentile(50)),
            p95Millis: millis(histogram.percentile(95)),
            p99Millis: millis(histogram.percentile(99)),
            maxMillis: millis(histogram.max),
            // Keep unrounded values for strict gates.  The rounded aliases are
            // retained for human-readable reports and backwards compatibility.
            rawMinMillis: raw(histogram.min),
            rawP50Millis: raw(histogram.percentile(50)),
            rawP95Millis: raw(histogram.percentile(95)),
            rawP99Millis: raw(histogram.percentile(99)),
            rawMaxMillis: raw(histogram.max),
        };
    };

    const clearPending = () => {
        if (immediateHandle !== undefined) {
            clearImmediate(immediateHandle);
            immediateHandle = undefined;
        }
        if (timerHandle !== undefined) {
            clearTimeout(timerHandle);
            timerHandle = undefined;
        }
        if (watchdogHandle !== undefined) {
            clearTimeout(watchdogHandle);
            watchdogHandle = undefined;
        }
    };

    let schedule;
    const run = (trigger, scheduledAt) => {
        if (stopped) return;
        if (callbackRunning) {
            overlappingCallbacks++;
            return;
        }
        callbackRunning = true;
        const callbackSequenceNumber = ++callbackSequence;
        const callbackStartedAt = performance.now();
        const previousCallbackSequence = Number.isSafeInteger(lastCallbackSequence)
            ? lastCallbackSequence : null;
        const previousCallbackTrigger = lastCallbackTrigger ?? null;
        const previousCallbackScheduledAtMillis =
            Number.isFinite(lastCallbackScheduledAt)
                ? lastCallbackScheduledAt : null;
        const previousCallbackStartedAtMillis = Number.isFinite(lastCallbackAt)
            ? lastCallbackAt : null;
        const previousCallbackFinishedAtMillis =
            Number.isFinite(lastCallbackFinishedAt)
                ? lastCallbackFinishedAt : null;
        // One deadline covers both PLAY ticks and inbound polls.  Previously
        // ticks ran before the poll deadline was created, so a slow tick burst
        // could consume an entire macrotask and manufacture peer poll gaps.
        const workDeadline = callbackStartedAt + MAX_POLL_CALLBACK_WORK_MILLIS;
        let callbackBudgetReached = false;
        let callbackBudgetReachedPhase;
        let callbackBudgetReachedAtMillis;
        let callbackPhase = "start";
        let timeBeforeTickLoop;
        let timeAfterTickLoop;
        let timeBeforePollLoop;
        let timeAfterPollLoop;
        let timeBeforeEvidenceOrFinalize;
        let clients = [];
        let dispatched = 0;
        let tickServicesThisCallback = 0;
        let tickAttemptsThisCallback = 0;
        let tickServicesCompletedThisCallback = 0;
        let tickDispatchesThisCallback = 0;
        let tickServiceErrorsThisCallback = 0;
        let maxPerTickDurationMillis = 0;
        let pollCandidatesInspectedThisCallback = 0;
        let fairnessFloorScansThisCallback = 0;
        let fairnessSkipsThisCallback = 0;
        let maxPerPollDurationMillis = 0;
        let nextContinuation = "immediate";
        // Keep the post-dispatch readiness result in callback scope: the
        // finalizer must retain an immediate wake when a fair candidate is
        // ready even though this turn dispatched zero polls.
        let readyAfterDispatch = false;
        let interCallbackGapMillis = null;
        let interCallbackIdleGapMillis = null;
        // Keep scheduler-delay values in the run scope: finalization records
        // the diagnostic sample from the sibling `finally` block, so a
        // declaration inside the `try` block would be out of scope and turn
        // the first real scheduler callback into a ReferenceError.
        let scheduleDelayRawMillis = 0;
        let scheduleDelayMillis = 0;
        let callbackClockAnomaly = false;
        const markPhase = (phase) => {
            callbackPhase = phase;
            return performance.now();
        };
        const markBudgetReached = (phase) => {
            callbackBudgetReached = true;
            if (callbackBudgetReachedPhase === undefined) {
                callbackBudgetReachedPhase = phase;
                callbackBudgetReachedAtMillis = Math.max(
                    0, performance.now() - callbackStartedAt);
            }
        };
        const elapsedSinceStart = (timestamp) => Number.isFinite(timestamp)
            ? Math.max(0, timestamp - callbackStartedAt) : null;
        try {
            if (lastCallbackFinishedAt !== undefined) {
                const rawInterCallbackIdleGapMillis =
                    callbackStartedAt - lastCallbackFinishedAt;
                callbackClockAnomaly ||= rawInterCallbackIdleGapMillis < 0;
                interCallbackIdleGapMillis = Math.max(
                    0, rawInterCallbackIdleGapMillis);
                maxInterCallbackIdleGapRawMillis = Math.max(
                    maxInterCallbackIdleGapRawMillis,
                    interCallbackIdleGapMillis);
            }
            if (lastCallbackAt !== undefined) {
                const rawInterCallbackGapMillis =
                    callbackStartedAt - lastCallbackAt;
                callbackClockAnomaly ||= rawInterCallbackGapMillis < 0;
                interCallbackGapMillis = Math.max(0, rawInterCallbackGapMillis);
                maxInterCallbackGapRawMillis = Math.max(
                    maxInterCallbackGapRawMillis, interCallbackGapMillis);
                maxInterCallbackGapMillis = Math.max(
                    maxInterCallbackGapMillis, interCallbackGapMillis);
            }
            lastCallbackAt = callbackStartedAt;
            scheduleDelayRawMillis = callbackStartedAt - scheduledAt;
            callbackClockAnomaly ||= scheduleDelayRawMillis < 0;
            scheduleDelayMillis = Math.max(0, scheduleDelayRawMillis);
            maxScheduleDelayRawMillis = Math.max(
                maxScheduleDelayRawMillis, scheduleDelayMillis);
            maxScheduleDelayMillis = Math.max(maxScheduleDelayMillis, scheduleDelayMillis);
            callbackCount++;
            markPhase("client-snapshot");
            try {
                const observed = getClients();
                if (Array.isArray(observed)) clients = observed;
            }
            catch {
                // The owner of the client array remains authoritative. A
                // transient replacement race is retried on the next turn.
            }
            maxVisibleClients = Math.max(maxVisibleClients, clients.length);
            recordVisibleDispatchEvidence(clients);
            // Reconnect waves replace the visible array. Drop scheduling state
            // for retired client objects so the due-map cannot retain channels
            // (or their bridge references) indefinitely.
            const visibleClients = new Set(clients);
            for (const knownClient of nextPollDueAtByClient.keys()) {
                if (!visibleClients.has(knownClient)) {
                    nextPollDueAtByClient.delete(knownClient);
                }
            }
            lastDueTicksAfterService = 0;
            lastDueTicksBeforeIdle = 0;
            // PLAY ticks share the same macrotask continuation as inbound
            // polling. This removes one independent timer per client while
            // retaining the client's 50 ms due cadence and pause/close gates.
            if (clients.length > 0) {
                timeBeforeTickLoop = markPhase("tick-loop");
                if (tickCursor >= clients.length) tickCursor = 0;
                // A separate cursor keeps tick servicing fair without coupling
                // it to whichever clients happened to consume the poll batch.
                for (let attempts = 0;
                    attempts < clients.length &&
                    tickServicesThisCallback <
                        Math.min(MAX_PLAY_TICKS_PER_SCHEDULER_CALLBACK, clients.length);
                    attempts++) {
                    tickAttemptsThisCallback++;
                    if (performance.now() >= workDeadline) {
                        markBudgetReached("tick-admission");
                        callbackBudgetTickSkips++;
                        break;
                    }
                    const candidate = clients[tickCursor];
                    tickCursor = (tickCursor + 1) % clients.length;
                    if (candidate === undefined || candidate === null || candidate.closed ||
                        candidate.pollingPaused ||
                        typeof candidate.servicePlayTick !== "function") continue;
                    const tickNow = performance.now();
                    if (!isPlayTickDue(candidate, tickNow)) continue;
                    tickServicesThisCallback++;
                    playTickServiceCalls++;
                    const tickStartedAt = tickNow;
                    try {
                        if (candidate.servicePlayTick(
                            tickNow, callbackSequenceNumber, trigger, callbackPhase)) {
                            playTickDispatches++;
                            tickDispatchesThisCallback++;
                        }
                    }
                    catch (error) {
                        playTickServiceErrors++;
                        tickServiceErrorsThisCallback++;
                        candidate.failure ??= error;
                    }
                    finally {
                        tickServicesCompletedThisCallback++;
                        maxPerTickDurationMillis = Math.max(
                            maxPerTickDurationMillis,
                            performance.now() - tickStartedAt);
                    }
                    if (performance.now() >= workDeadline && attempts + 1 < clients.length) {
                        markBudgetReached("tick-service");
                        callbackBudgetTickSkips++;
                        break;
                    }
                }
            }
            maxPlayTickServicesPerCallback = Math.max(
                maxPlayTickServicesPerCallback, tickServicesThisCallback);
            timeAfterTickLoop = markPhase("post-tick");
            // The per-callback tick cap deliberately services only a bounded
            // subset of PLAY clients.  Before deciding to park on the idle
            // timer, count the still-due clients; otherwise the remaining due
            // ticks wait behind the host's one-millisecond timer quantum and
            // inflate the strict PLAY tick gap histogram.  This is a
            // continuation choice only: the cap, cadence, and latency gates
            // remain unchanged.
            lastDueTicksAfterService = countDuePlayTicks(clients);
            maxDueTicks = Math.max(maxDueTicks, lastDueTicksAfterService);
            if (clients.length > 0) {
                timeBeforePollLoop = markPhase("poll-loop");
                if (cursor >= clients.length) cursor = 0;
                const batchLimit = Math.min(
                    MAX_CLIENTS_PER_POLL_CALLBACK, clients.length);
                const visited = new Set();
                // Prefer live, unpaused clients and advance the cursor for every
                // inspected entry. The per-client poll() budgets bound work in
                // this callback; no unbounded fan-out is permitted.
                for (let attempts = 0;
                    attempts < clients.length && dispatched < batchLimit;
                    attempts++) {
                    pollCandidatesInspectedThisCallback++;
                    if (performance.now() >= workDeadline) {
                        markBudgetReached("poll-admission");
                        callbackBudgetPollSkips++;
                        break;
                    }
                    const candidate = clients[cursor];
                    cursor = (cursor + 1) % clients.length;
                    if (candidate === undefined || candidate === null ||
                        visited.has(candidate)) continue;
                    visited.add(candidate);
                    const pollNow = performance.now();
                    if (!isPollReady(candidate, pollNow)) continue;
                    recordArrivalTrace(candidate.arrivalTraceRing, "poll-ready", {
                        at: pollNow,
                        phase: candidate.phase,
                        source: "client",
                        schedulerCallbackSequence: callbackSequenceNumber,
                        pollSequence: candidate.pollPhaseSequence + 1,
                    });
                    // A due client that has already received more turns than
                    // the least-served visible client must wait for the
                    // fairness floor.  This prevents a continuously busy
                    // client from consuming every callback while a peer is
                    // delayed by timer quantization or a transient I/O gap.
                    fairnessFloorScansThisCallback++;
                    const fairnessFloor = readPollFairnessFloor(clients);
                    if (!isPollFairlyEligible(candidate, clients, fairnessFloor)) {
                        fairnessSkips++;
                        fairnessSkipsThisCallback++;
                        continue;
                    }
                    const pollStartedAt = performance.now();
                    try {
                        // Supply scheduler provenance when the client accepts
                        // the optional arguments. JavaScript's extra-argument
                        // rule keeps older synthetic/production-compatible
                        // poll() implementations source-compatible; clients
                        // that ignore the arguments retain their old behavior.
                        candidate.poll(callbackSequenceNumber, trigger);
                    }
                    finally {
                        const pollDurationMillis = performance.now() - pollStartedAt;
                        maxPerPollDurationMillis = Math.max(
                            maxPerPollDurationMillis, pollDurationMillis);
                        maxClientPollDurationRawMillis = Math.max(
                            maxClientPollDurationRawMillis, pollDurationMillis);
                        maxClientPollDurationMillis = Math.max(
                            maxClientPollDurationMillis, pollDurationMillis);
                        const previous = pollDurationByClient.get(candidate.id) ?? 0;
                        pollDurationByClient.set(candidate.id,
                            Math.max(previous, pollDurationMillis));
                    }
                    dispatched++;
                    dispatchCounts.set(candidate.id,
                        (dispatchCounts.get(candidate.id) ?? 0) + 1);
                    // Every dispatched client gets the same short due
                    // cadence, including a client with more queued input.  The
                    // readiness probe above still observes queued input, but
                    // the due cursor is the fairness boundary that prevents a
                    // continuously busy client from monopolising setImmediate.
                    writeNextPollDueAt(candidate,
                        performance.now() + CLIENT_POLL_INTERVAL_MILLIS);
                    if (performance.now() >= workDeadline && attempts + 1 < clients.length) {
                        markBudgetReached("poll-dispatch");
                        callbackBudgetPollSkips++;
                        break;
                    }
                }
                // If no candidate is currently ready, park the continuation
                // on the idle backoff timer.  Otherwise retain immediate
                // round-robin scheduling for queued work or another batch.
                const readinessAt = performance.now();
                readyAfterDispatch = hasFairPollReady(clients, readinessAt);
                lastDueTicksBeforeIdle = countDuePlayTicks(clients, readinessAt);
                maxDueTicks = Math.max(maxDueTicks, lastDueTicksBeforeIdle);
                const overdueWakePending = idleImmediateOverdueWakePending;
                idleImmediateOverdueWakePending = false;
                // A successful poll crossed the previous due boundary.  Do not
                // charge the next due cursor against the old probe's elapsed
                // time/turn budget; otherwise a continuously active client set
                // is forced onto the quantized idle timer every few turns.
                if (dispatched > 0) {
                    idleImmediateSpins = 0;
                    idleImmediateProbeStartedAt = undefined;
                }
                if (lastDueTicksBeforeIdle > 0) {
                    idleImmediateSpins = 0;
                    idleImmediateProbeStartedAt = undefined;
                    nextContinuation = "immediate";
                    dueTickImmediateContinuations++;
                }
                else if (!readyAfterDispatch) {
                    // Do not hand a one-millisecond due cursor to the host
                    // timer queue: on Windows that queue is commonly
                    // quantized to ~15 ms and turns into a user-visible poll
                    // hitch.  A bounded immediate probe lets the due cursor
                    // mature on a real macrotask boundary.  If the client set
                    // is genuinely idle/far from due, fall back to the timer
                    // backoff and reset the elapsed-time probe.
                    let earliestPollDueAt = Infinity;
                    for (const candidate of clients) {
                        if (!isPollLive(candidate)) continue;
                        const dueAt = readNextPollDueAt(candidate);
                        if (Number.isFinite(dueAt)) {
                            earliestPollDueAt = Math.min(earliestPollDueAt, dueAt);
                        }
                    }
                    // Treat an already-overdue cursor as immediately eligible
                    // too.  A callback can finish after the one-millisecond
                    // due boundary (especially when the tick/poll budget was
                    // reached); requiring `dueAt >= readinessAt` in that case
                    // parks the next fair turn on the host's quantized idle
                    // timer and creates the exact 15--25 ms gap this scheduler
                    // is avoiding.  The bounded spin limit remains the hard
                    // backstop for a stale/overdue cursor, so this cannot turn
                    // an idle client set into an unbounded immediate loop.
                    const dueInImmediateWindow = Number.isFinite(earliestPollDueAt) &&
                        earliestPollDueAt - readinessAt <=
                            POLL_SCHEDULER_IDLE_IMMEDIATE_WINDOW_MILLIS;
                    if (overdueWakePending && dispatched === 0 &&
                        lastDueTicksBeforeIdle === 0 &&
                        lastDueTicksAfterService === 0) {
                        // The previous probe budget expired with an already
                        // overdue cursor.  Preserve exactly that one wake so
                        // the due client gets a chance to dispatch, but do not
                        // start a fresh probe when this wake still found no
                        // fair candidate.
                        idleImmediateSpins = 0;
                        idleImmediateProbeStartedAt = undefined;
                        nextContinuation = "idle";
                    }
                    else if (dueInImmediateWindow) {
                        if (!Number.isFinite(idleImmediateProbeStartedAt)) {
                            idleImmediateProbeStartedAt = readinessAt;
                            idleImmediateSpins = 0;
                        }
                        const probeElapsedMillis = Math.max(
                            0, readinessAt - idleImmediateProbeStartedAt);
                        if (probeElapsedMillis <
                            POLL_SCHEDULER_IDLE_IMMEDIATE_PROBE_BUDGET_MILLIS) {
                            // The turn cap is per burst, not the lifetime of
                            // this deadline probe.  Roll it over while the
                            // absolute elapsed-time budget still remains.
                            if (idleImmediateSpins >=
                                POLL_SCHEDULER_IDLE_IMMEDIATE_SPIN_LIMIT) {
                                idleImmediateSpins = 0;
                            }
                            idleImmediateSpins++;
                            nextContinuation = "immediate";
                        }
                        else if (earliestPollDueAt <= readinessAt) {
                            // Do not park an already-due cursor on a clamped
                            // timer after the probe budget expires.  Admit one
                            // immediate wake; if it still cannot dispatch, the
                            // next idle decision uses the normal timer.
                            idleImmediateSpins = 0;
                            idleImmediateProbeStartedAt = undefined;
                            idleImmediateOverdueWakePending = true;
                            nextContinuation = "immediate";
                        }
                        else {
                            idleImmediateSpins = 0;
                            idleImmediateProbeStartedAt = undefined;
                            nextContinuation = "idle";
                        }
                    }
                    else {
                        idleImmediateSpins = 0;
                        idleImmediateProbeStartedAt = undefined;
                        nextContinuation = "idle";
                    }
                }
                else {
                    idleImmediateSpins = 0;
                    idleImmediateProbeStartedAt = undefined;
                }
                timeAfterPollLoop = markPhase("post-poll");
            }
            if (dispatched === 0) {
                emptyCallbacks++;
                // Keep a due PLAY client on the immediate path even when this
                // callback had no inbound poll dispatches.
                if (lastDueTicksBeforeIdle > 0 || lastDueTicksAfterService > 0 ||
                    readyAfterDispatch) {
                    // A fair poll candidate can become ready while the shared
                    // callback is budgeted out by PLAY-tick work.  Keep that
                    // candidate on the immediate path even when this turn
                    // dispatched zero polls; otherwise the finalizer parks it
                    // on the host's quantized idle timer and recreates the
                    // 15--25 ms gap this scheduler is intended to remove.
                    nextContinuation = "immediate";
                }
                else if (!(nextContinuation === "immediate" &&
                    idleImmediateSpins > 0 &&
                    Number.isFinite(idleImmediateProbeStartedAt)) &&
                    !idleImmediateOverdueWakePending) {
                    nextContinuation = "idle";
                }
            }
            dispatchedPolls += dispatched;
            maxClientsPerCallback = Math.max(maxClientsPerCallback, dispatched);
            timeBeforeEvidenceOrFinalize = markPhase("evidence");
            recordVisibleDispatchEvidence(clients);
        }
        finally {
            callbackRunning = false;
            const callbackPhaseBeforeFinalize = callbackPhase;
            const callbackFinishedAt = performance.now();
            const callbackDurationMillis = callbackFinishedAt - callbackStartedAt;
            // Keep the existing callback work endpoint untouched for strict
            // gates.  Finalization telemetry begins after that measurement so
            // its own clock read cannot inflate callbackDurationRawMillis.
            const finalizeStartAt = performance.now();
            maxCallbackDurationMillis = Math.max(
                maxCallbackDurationMillis,
                callbackDurationMillis,
            );
            maxCallbackDurationRawMillis = Math.max(
                maxCallbackDurationRawMillis,
                callbackDurationMillis,
            );
            if (callbackBudgetReached) callbackBudgetExhaustions++;
            if (callbackDurationMillis > MAX_POLL_CALLBACK_WORK_MILLIS) {
                callbackBudgetOverruns++;
            }
            if (callbackDurationMillis >= POLL_SCHEDULER_TIMER_YIELD_WORK_MILLIS) {
                heavyTurnCount++;
            }
            if (trigger === "timer-yield" || trigger === "timer-fallback" ||
                trigger === "idle" || trigger === "watchdog") {
                turnsSinceTimerYield = 0;
            }
            else {
                turnsSinceTimerYield++;
            }
            const needsTimerYield = turnsSinceTimerYield >=
                POLL_SCHEDULER_TIMER_YIELD_TURNS;
            if (needsTimerYield) turnsSinceTimerYield = 0;
            // Keep the scheduler alive even if a future client implementation
            // throws outside its normal poll() fail-closed handler. The next
            // turn is selected only after this callback fully unwinds.
            // A timer-yield must never postpone a due PLAY tick batch. The
            // bounded tick cap can leave peers due after this callback; force
            // one immediate continuation first, then resume the normal
            // 256-turn fairness cadence once no due tick remains.
            const dueTicksPending = lastDueTicksAfterService > 0 ||
                lastDueTicksBeforeIdle > 0;
            // A fair poll candidate has the same immediate-wake priority as a
            // due PLAY tick.  In particular, a zero-dispatch callback that
            // spent its budget on tick work must not let the periodic
            // timer-yield override a ready inbound poll.
            const readyPollPending = readyAfterDispatch;
            const continuation = dueTicksPending || readyPollPending
                ? "immediate"
                : needsTimerYield ? "timer-yield" : nextContinuation;
            if (callbackDurationMillis >= CALLBACK_TAIL_SLOW_THRESHOLD_MILLIS) {
                slowCallbackSamplesTotal++;
                retainSlowCallbackSample({
                    schemaVersion: CALLBACK_TAIL_TELEMETRY_SCHEMA_VERSION,
                    callbackSequence: callbackSequenceNumber,
                    trigger,
                    startedAtMillis: callbackStartedAt,
                    scheduledAtMillis: scheduledAt,
                    scheduledDelayMillis: Math.max(0, callbackStartedAt - scheduledAt),
                    durationRawMillis: callbackDurationMillis,
                    durationMillis: roundedMillis(callbackDurationMillis),
                    slowThresholdMillis: CALLBACK_TAIL_SLOW_THRESHOLD_MILLIS,
                    callbackWorkBudgetMillis: MAX_POLL_CALLBACK_WORK_MILLIS,
                    strictFrameBudgetMillis: 16.7,
                    callbackWorkOverrunMillis: Math.max(
                        0, callbackDurationMillis - MAX_POLL_CALLBACK_WORK_MILLIS),
                    strictFrameBudgetExcessMillis: Math.max(
                        0, callbackDurationMillis - 16.7),
                    phase: callbackPhaseBeforeFinalize,
                    terminalPhase: callbackPhaseBeforeFinalize,
                    budgetReached: callbackBudgetReached,
                    budgetCheckReached: callbackBudgetReached,
                    budgetReachedPhase: callbackBudgetReachedPhase ?? null,
                    budgetReachedAtMillis: callbackBudgetReachedAtMillis ?? null,
                    tickAttempts: tickAttemptsThisCallback,
                    tickCandidatesInspected: tickAttemptsThisCallback,
                    tickServices: tickServicesThisCallback,
                    tickServicesAttempted: tickServicesThisCallback,
                    tickServicesCompleted: tickServicesCompletedThisCallback,
                    tickDispatches: tickDispatchesThisCallback,
                    tickServiceErrors: tickServiceErrorsThisCallback,
                    maxPerTickDurationRawMillis: maxPerTickDurationMillis,
                    maxPerTickDurationMillis: roundedMillis(maxPerTickDurationMillis),
                    pollCandidatesInspected: pollCandidatesInspectedThisCallback,
                    pollDispatches: dispatched,
                    fairnessFloorScans: fairnessFloorScansThisCallback,
                    fairnessSkips: fairnessSkipsThisCallback,
                    maxPerPollDurationRawMillis: maxPerPollDurationMillis,
                    maxPerPollDurationMillis: roundedMillis(maxPerPollDurationMillis),
                    visibleClientCount: clients.length,
                    dueTicksAfterService: lastDueTicksAfterService,
                    dueTicksBeforeIdle: lastDueTicksBeforeIdle,
                    phaseTimingsMillis: {
                        beforeTickLoop: elapsedSinceStart(timeBeforeTickLoop),
                        afterTickLoop: elapsedSinceStart(timeAfterTickLoop),
                        beforePollLoop: elapsedSinceStart(timeBeforePollLoop),
                        afterPollLoop: elapsedSinceStart(timeAfterPollLoop),
                        beforeFinalEvidence: elapsedSinceStart(timeBeforeEvidenceOrFinalize),
                        afterFinalEvidence: elapsedSinceStart(callbackFinishedAt),
                    },
                    nextContinuation: continuation,
                });
            }
            const schedulerGapTriggers = [];
            if (interCallbackIdleGapMillis !== null &&
                interCallbackIdleGapMillis >= SCHEDULER_GAP_SLOW_THRESHOLD_MILLIS) {
                schedulerGapTriggers.push("inter-callback-gap");
            }
            if (scheduleDelayMillis >= SCHEDULER_GAP_SLOW_THRESHOLD_MILLIS) {
                schedulerGapTriggers.push("scheduled-delay");
            }
            if (callbackDurationMillis >=
                SCHEDULER_GAP_STRICT_CALLBACK_THRESHOLD_MILLIS) {
                schedulerGapTriggers.push("callback-duration");
            }
            if (callbackBudgetReached) schedulerGapTriggers.push("budget-reached");
            if (schedulerGapTriggers.length > 0) {
                schedulerGapSamplesTotal++;
                if (callbackClockAnomaly) schedulerGapClockAnomalies++;
                retainSchedulerGapSample({
                    schemaVersion: SCHEDULER_GAP_TELEMETRY_SCHEMA_VERSION,
                    callbackSequence: callbackSequenceNumber,
                    previousCallbackSequence,
                    trigger,
                    previousCallbackTrigger,
                    scheduledAtMillis: scheduledAt,
                    previousCallbackScheduledAtMillis,
                    previousCallbackStartedAtMillis,
                    previousCallbackFinishedAtMillis,
                    callbackStartedAtMillis: callbackStartedAt,
                    callbackFinishedAtMillis: callbackFinishedAt,
                    scheduledDelayRawMillis: scheduleDelayRawMillis,
                    scheduledDelayMillis: roundedMillis(scheduleDelayMillis),
                    interCallbackGapRawMillis: interCallbackGapMillis,
                    interCallbackGapMillis: interCallbackGapMillis === null
                        ? null : roundedMillis(interCallbackGapMillis),
                    interCallbackIdleGapRawMillis:
                        interCallbackIdleGapMillis,
                    interCallbackIdleGapMillis: interCallbackIdleGapMillis === null
                        ? null : roundedMillis(interCallbackIdleGapMillis),
                    callbackDurationRawMillis: callbackDurationMillis,
                    callbackDurationMillis: roundedMillis(callbackDurationMillis),
                    callbackPhase: callbackPhaseBeforeFinalize,
                    nextContinuation: continuation,
                    visibleClientCount: clients.length,
                    dispatchedPolls: dispatched,
                    tickServices: tickServicesThisCallback,
                    dueTicksAfterService: lastDueTicksAfterService,
                    dueTicksBeforeIdle: lastDueTicksBeforeIdle,
                    budgetReached: callbackBudgetReached,
                    budgetReachedPhase: callbackBudgetReachedPhase ?? null,
                    budgetReachedAtMillis: callbackBudgetReachedAtMillis ?? null,
                    triggerReasons: schedulerGapTriggers,
                    slowGapThresholdMillis: SCHEDULER_GAP_SLOW_THRESHOLD_MILLIS,
                    strictCallbackThresholdMillis:
                        SCHEDULER_GAP_STRICT_CALLBACK_THRESHOLD_MILLIS,
                    clockAnomaly: callbackClockAnomaly,
                    diagnosticOnly: true,
                    strictGatesChanged: false,
                });
            }
            // The next callback's idle-gap measurement starts at the strict
            // callback endpoint, not after telemetry sorting or scheduling.
            lastCallbackFinishedAt = callbackFinishedAt;
            lastCallbackSequence = callbackSequenceNumber;
            lastCallbackTrigger = trigger;
            lastCallbackScheduledAt = scheduledAt;
            if (!stopped) {
                schedule(continuation);
            }
            // Include the existing continuation scheduling work in the
            // diagnostic finalization interval, but keep the bounded telemetry
            // ring itself out of the interval to avoid measuring its own sort
            // and allocation overhead recursively.
            const finalizeFinishAt = performance.now();
            const finalizationTailRawMillis = finiteNonNegativeMillis(
                finalizeFinishAt - finalizeStartAt);
            const totalAfterFinalizeRawMillis = finiteNonNegativeMillis(
                finalizeFinishAt - callbackStartedAt);
            maxFinalizationTailRawMillis = Math.max(
                maxFinalizationTailRawMillis, finalizationTailRawMillis);
            maxFinalizationTailMillis = Math.max(
                maxFinalizationTailMillis, finalizationTailRawMillis);
            maxTotalAfterFinalizeRawMillis = Math.max(
                maxTotalAfterFinalizeRawMillis, totalAfterFinalizeRawMillis);
            maxTotalAfterFinalizeMillis = Math.max(
                maxTotalAfterFinalizeMillis, totalAfterFinalizeRawMillis);
            lastFinalizationTail = {
                schemaVersion:
                    CALLBACK_FINALIZATION_TAIL_TELEMETRY_SCHEMA_VERSION,
                callbackSequence: callbackSequenceNumber,
                trigger,
                finalizeStartAtMillis: finiteNonNegativeMillis(finalizeStartAt),
                finalizeFinishAtMillis: finiteNonNegativeMillis(finalizeFinishAt),
                tailRawMillis: finalizationTailRawMillis,
                tailMillis: roundedMillis(finalizationTailRawMillis),
                totalAfterFinalizeRawMillis: totalAfterFinalizeRawMillis,
                totalAfterFinalizeMillis: roundedMillis(totalAfterFinalizeRawMillis),
                callbackDurationRawMillis: finiteNonNegativeMillis(
                    callbackDurationMillis),
                callbackDurationMillis: roundedMillis(callbackDurationMillis),
                strictFrameBudgetMillis: 16.7,
                diagnosticOnly: true,
                strictGatesChanged: false,
            };
            if (finalizationTailRawMillis >=
                CALLBACK_FINALIZATION_TAIL_SLOW_THRESHOLD_MILLIS) {
                slowFinalizationTailSamplesTotal++;
                retainSlowFinalizationTailSample({
                    ...lastFinalizationTail,
                    slowThresholdMillis:
                        CALLBACK_FINALIZATION_TAIL_SLOW_THRESHOLD_MILLIS,
                });
            }
        }
    };

    schedule = (kind = "immediate") => {
        if (stopped || immediateHandle !== undefined || timerHandle !== undefined) return;
        const sequence = ++scheduleSequence;
        const scheduledAt = performance.now();
        let fired = false;
        const fire = (trigger) => {
            if (fired || stopped || sequence !== scheduleSequence) return;
            fired = true;
            if (trigger === "immediate") {
                immediateHandle = undefined;
                immediateCallbacks++;
                if (watchdogHandle !== undefined) {
                    clearTimeout(watchdogHandle);
                    watchdogHandle = undefined;
                }
            }
            else if (trigger === "watchdog") {
                if (immediateHandle !== undefined) {
                    clearImmediate(immediateHandle);
                    immediateHandle = undefined;
                }
                watchdogHandle = undefined;
                watchdogFires++;
            }
            else {
                timerHandle = undefined;
                if (trigger === "timer-fallback") timerFallbackCallbacks++;
                else if (trigger === "idle") idleCallbacks++;
                else timerYieldCallbacks++;
            }
            run(trigger, scheduledAt);
        };
        const useTimer = kind === "timer-yield" || kind === "idle" ||
            typeof setImmediate !== "function";
        if (useTimer) {
            timerSchedules++;
            if (kind === "idle") idleSchedules++;
            const fireTimer = () => {
                const trigger = typeof setImmediate !== "function"
                    ? "timer-fallback"
                    : kind === "idle" ? "idle" : "timer-yield";
                fire(trigger);
            };
            if (kind === "idle") {
                timerHandle = setTimeout(fireTimer,
                    POLL_SCHEDULER_IDLE_BACKOFF_MILLIS);
            }
            else {
                // Keep the fairness-yield path explicitly zero-delay; the
                // static contract smoke treats this as a required backstop.
                timerHandle = setTimeout(() => {
                    fireTimer();
                }, 0);
            }
            return;
        }
        immediateSchedules++;
        immediateHandle = setImmediate(() => {
            fire("immediate");
        });
        // A watchdog prevents a blocked check phase from silently stopping the
        // poll loop. It races the immediate with a generation token; exactly one
        // callback is allowed to enter run().
        watchdogHandle = setTimeout(() => {
            if (immediateHandle === undefined) return;
            fire("watchdog");
        }, POLL_SCHEDULER_WATCHDOG_MILLIS);
    };

    schedule("timer-yield");
    return {
        stop() {
            if (stopped) return;
            stopped = true;
            scheduleSequence++;
            clearPending();
            nextPollDueAtByClient.clear();
            eventLoopDelayMonitor?.disable();
        },
        evidence() {
            const counts = [...dispatchCounts.entries()]
                .sort(([left], [right]) => Number(left) - Number(right))
                .slice(0, 128)
                .map(([id, count]) => ({ id, count }));
            const durations = [...pollDurationByClient.entries()]
                .sort(([left], [right]) => Number(left) - Number(right))
                .slice(0, 128)
                .map(([id, millis]) => ({ id,
                    maxPollDurationMillis: roundedMillis(millis) }));
            const numericCounts = counts.map(({ count }) => count);
            const minimumDispatches = numericCounts.length === 0
                ? 0 : Math.min(...numericCounts);
            const maximumDispatches = numericCounts.length === 0
                ? 0 : Math.max(...numericCounts);
            let visibleClients = [];
            try {
                const observed = getClients();
                if (Array.isArray(observed)) visibleClients = observed;
            }
            catch {
                // Keep the last authoritative visible set when a reconnect
                // replacement races evidence collection.
            }
            const visibleDispatchCounts = visibleClients.length > 0
                ? recordVisibleDispatchEvidence(visibleClients)
                : lastVisibleDispatchCounts;
            return {
                schemaVersion: "gaius.browser-client-poll-scheduler.v1",
                mode: "round-robin-bounded-batch-per-macrotask",
                schedulerMechanism:
                    "setImmediate-primary-idle-backoff-timer-yield-watchdog",
                maxBatchClients: MAX_CLIENTS_PER_POLL_CALLBACK,
                intervalMillis: CLIENT_POLL_INTERVAL_MILLIS,
                idleBackoffMillis: POLL_SCHEDULER_IDLE_BACKOFF_MILLIS,
                idleImmediateWindowMillis:
                    POLL_SCHEDULER_IDLE_IMMEDIATE_WINDOW_MILLIS,
                idleImmediateProbeBudgetMillis:
                    POLL_SCHEDULER_IDLE_IMMEDIATE_PROBE_BUDGET_MILLIS,
                idleImmediateProbeSpinLimit:
                    POLL_SCHEDULER_IDLE_IMMEDIATE_SPIN_LIMIT,
                timerYieldTurns: POLL_SCHEDULER_TIMER_YIELD_TURNS,
                timerYieldWorkMillis: POLL_SCHEDULER_TIMER_YIELD_WORK_MILLIS,
                timerYieldPolicy: "turn-count-only-heavy-turns-observed",
                watchdogMillis: POLL_SCHEDULER_WATCHDOG_MILLIS,
                stopped,
                callbacks: callbackCount,
                dispatchedPolls,
                emptyCallbacks,
                maxVisibleClients,
                maxClientsPerCallback,
                maxCallbackDurationMillis: roundedMillis(maxCallbackDurationMillis),
                maxCallbackDurationRawMillis,
                callbackWorkBudgetMillis: MAX_POLL_CALLBACK_WORK_MILLIS,
                callbackBudgetCoversPlayTicks: true,
                callbackBudgetExhaustions,
                callbackBudgetOverruns,
                callbackBudgetTickSkips,
                callbackBudgetPollSkips,
                callbackTail: {
                    schemaVersion: CALLBACK_TAIL_TELEMETRY_SCHEMA_VERSION,
                    slowThresholdMillis: CALLBACK_TAIL_SLOW_THRESHOLD_MILLIS,
                    sampleLimit: CALLBACK_TAIL_SAMPLE_LIMIT,
                    slowCallbackSamplesTotal,
                    retainedSampleCount: slowCallbackSamples.length,
                    slowCallbackSamplesDropped,
                    droppedSampleCount: slowCallbackSamplesDropped,
                    retention: "longest-duration-desc-sequence-asc",
                    samples: slowCallbackSamples.map((sample) => ({ ...sample })),
                },
                schedulerGap: {
                    schemaVersion: SCHEDULER_GAP_TELEMETRY_SCHEMA_VERSION,
                    slowGapThresholdMillis: SCHEDULER_GAP_SLOW_THRESHOLD_MILLIS,
                    strictCallbackThresholdMillis:
                        SCHEDULER_GAP_STRICT_CALLBACK_THRESHOLD_MILLIS,
                    sampleLimit: SCHEDULER_GAP_SAMPLE_LIMIT,
                    retention: SCHEDULER_GAP_RETENTION,
                    diagnosticOnly: true,
                    strictGatesChanged: false,
                    strictRawDurationGateMillis:
                        SCHEDULER_GAP_STRICT_CALLBACK_THRESHOLD_MILLIS,
                    samplesTotal: schedulerGapSamplesTotal,
                    retainedSampleCount: schedulerGapSamples.length,
                    samplesDropped: schedulerGapSamplesDropped,
                    droppedSampleCount: schedulerGapSamplesDropped,
                    clockAnomalies: schedulerGapClockAnomalies,
                    maxInterCallbackIdleGapRawMillis,
                    samples: schedulerGapSamples.map((sample) => ({
                        ...sample,
                        triggerReasons: [...sample.triggerReasons],
                    })),
                },
                callbackFinalizationTail: {
                    schemaVersion:
                        CALLBACK_FINALIZATION_TAIL_TELEMETRY_SCHEMA_VERSION,
                    slowThresholdMillis:
                        CALLBACK_FINALIZATION_TAIL_SLOW_THRESHOLD_MILLIS,
                    sampleLimit: CALLBACK_FINALIZATION_TAIL_SAMPLE_LIMIT,
                    retention: CALLBACK_FINALIZATION_TAIL_RETENTION,
                    diagnosticOnly: true,
                    strictGatesChanged: false,
                    strictRawDurationGateMillis: 16.7,
                    includesContinuationScheduling: true,
                    maxTailMillis: roundedMillis(maxFinalizationTailMillis),
                    maxTailRawMillis: maxFinalizationTailRawMillis,
                    maxTotalAfterFinalizeMillis:
                        roundedMillis(maxTotalAfterFinalizeMillis),
                    maxTotalAfterFinalizeRawMillis,
                    slowTailSamplesTotal: slowFinalizationTailSamplesTotal,
                    retainedSampleCount: slowFinalizationTailSamples.length,
                    slowTailSamplesDropped: slowFinalizationTailSamplesDropped,
                    droppedSampleCount: slowFinalizationTailSamplesDropped,
                    lastFinalization: lastFinalizationTail === undefined
                        ? null : { ...lastFinalizationTail },
                    samples: slowFinalizationTailSamples.map((sample) => ({
                        ...sample,
                    })),
                },
                fairnessSkips,
                maxInterCallbackGapMillis: roundedMillis(maxInterCallbackGapMillis),
                maxInterCallbackGapRawMillis,
                maxScheduleDelayMillis: roundedMillis(maxScheduleDelayMillis),
                maxScheduleDelayRawMillis,
                maxClientPollDurationMillis: roundedMillis(maxClientPollDurationMillis),
                maxClientPollDurationRawMillis,
                immediateSchedules,
                immediateCallbacks,
                timerSchedules,
                timerYieldCallbacks,
                timerFallbackCallbacks,
                idleSchedules,
                idleCallbacks,
                maxPlayTickServicesPerCallback,
                maxPlayTickServicesPerCallbackLimit:
                    MAX_PLAY_TICKS_PER_SCHEDULER_CALLBACK,
                playTickServiceCalls,
                playTickDispatches,
                playTickServiceErrors,
                dueTicksAfterService: lastDueTicksAfterService,
                dueTicksBeforeIdle: lastDueTicksBeforeIdle,
                maxDueTicks,
                dueTickImmediateContinuations,
                watchdogFires,
                overlappingCallbacks,
                heavyTurnCount,
                dispatchCounts: counts,
                pollDurationByClient: durations,
                dispatchSkew: maximumDispatches - minimumDispatches,
                maxVisibleDispatchSkew,
                maxVisibleDispatchSkewLimit: MAX_VISIBLE_DISPATCH_SKEW,
                visibleDispatchCounts,
                visibleClientCount: visibleDispatchCounts.length,
                dueMapEntries: nextPollDueAtByClient.size,
                eventLoopDelay: eventLoopDelayEvidence(),
            };
        },
    };
}

function createClientIdentity(index) {
    const ordinal = index + 1;
    const usernameSuffix = String(ordinal);
    const usernameBase = usernamePrefix.slice(0, Math.max(0, 16 - usernameSuffix.length));
    const username = `${usernameBase}${usernameSuffix}`;
    if (!/^[A-Za-z0-9_]{1,16}$/u.test(username)) {
        throw new Error(
            `GAIUS_BROWSER_FULL_PATH_USERNAME_PREFIX produced invalid username ${username}`);
    }
    const profileId = `00000000000040008000${(index + 2).toString(16).padStart(12, "0")}`;
    assert.match(profileId, /^[0-9a-f]{32}$/u, "smoke profile UUID must be 32 hex digits");
    return Object.freeze({
        username,
        profileId,
        accessToken: `${relayToken}-${ordinal}`,
    });
}

// The Java/TeaVM bridge intentionally exposes byte queues rather than a
// trusted packet-arrival clock.  The harness can still observe the Node
// WebSocket `message` callback and the later bridge dequeue without touching
// the production bridge.  Keep only primitive values in a fixed ring per
// socket; no per-frame objects or unbounded arrays are created on this path.
function createArrivalSocketState() {
    return {
        frameSequence: 0,
        consumedFrameSequence: 0,
        frameAtRing: new Float64Array(ARRIVAL_TIMELINE_FRAME_RING_LIMIT),
        frameBytesRing: new Uint32Array(ARRIVAL_TIMELINE_FRAME_RING_LIMIT),
        frameMetadataDropped: 0,
        traceRing: createArrivalTraceRing(),
        lastDequeuedAt: null,
        lastDequeuedPollStartedAt: null,
        lastDequeuedOnmessageAt: null,
        lastDequeuedFrameSequence: null,
        lastDequeuedBytes: null,
    };
}

function binaryMessageBytes(data) {
    if (Buffer.isBuffer(data)) return data.byteLength;
    if (data instanceof ArrayBuffer) return data.byteLength;
    if (ArrayBuffer.isView(data)) return data.byteLength;
    return 0;
}

function recordArrivalSocketMessage(state, data, isBinary, telemetry) {
    // The `ws` callback may hand text frames to Node as a Buffer while
    // explicitly setting `isBinary=false`.  Respect that flag; counting such
    // control/status text as a wire frame would shift the bounded sequence
    // correlation and manufacture metadata drops on the following packet.
    const binary = isBinary !== false && (isBinary === true ||
        Buffer.isBuffer(data) || data instanceof ArrayBuffer ||
        ArrayBuffer.isView(data));
    if (!binary) return;
    const at = performance.now();
    const sequence = ++state.frameSequence;
    const slot = (sequence - 1) % ARRIVAL_TIMELINE_FRAME_RING_LIMIT;
    state.frameAtRing[slot] = at;
    state.frameBytesRing[slot] = Math.min(
        0xffffffff,
        Math.max(0, binaryMessageBytes(data)),
    );
    recordArrivalTrace(state.traceRing, "onmessage-enter", {
        at,
        source: "socket",
        frameSequence: sequence,
        frameBytes: state.frameBytesRing[slot],
    });
    telemetry.binaryOnmessageFrames++;
    telemetry.binaryOnmessageBytes += state.frameBytesRing[slot];
}

function recordArrivalBridgeDequeue(socket, polledAt, chunk, telemetry) {
    const state = socket?.__gaiusArrivalState;
    if (state === undefined || chunk === null) return;
    if (telemetry.syntheticSockets?.has(socket)) {
        telemetry.syntheticDequeuedFrames++;
        telemetry.syntheticDequeuedBytes += Number.isFinite(chunk?.byteLength)
            ? Number(chunk.byteLength) : 0;
        return;
    }
    const sequence = ++state.consumedFrameSequence;
    // `polledAt` is the call-start timestamp.  Capture a second timestamp
    // after pollInbound returns so bridge dequeue latency is not silently
    // under-measured when the bridge itself does work before returning.
    state.lastDequeuedPollStartedAt = polledAt;
    state.lastDequeuedAt = performance.now();
    state.lastDequeuedOnmessageAt = null;
    state.lastDequeuedFrameSequence = null;
    state.lastDequeuedBytes = Number.isFinite(chunk?.byteLength)
        ? Number(chunk.byteLength) : null;
    const oldestRetained = state.frameSequence - ARRIVAL_TIMELINE_FRAME_RING_LIMIT + 1;
    if (sequence >= oldestRetained && sequence <= state.frameSequence && sequence > 0) {
        const slot = (sequence - 1) % ARRIVAL_TIMELINE_FRAME_RING_LIMIT;
        state.lastDequeuedOnmessageAt = state.frameAtRing[slot];
        state.lastDequeuedFrameSequence = sequence;
    }
    else {
        state.frameMetadataDropped++;
        telemetry.frameMetadataDropped++;
    }
    recordArrivalTrace(state.traceRing, "bridge-dequeue", {
        at: state.lastDequeuedAt,
        source: "socket",
        frameSequence: state.lastDequeuedFrameSequence,
        frameBytes: state.lastDequeuedBytes,
    });
    telemetry.bridgeDequeuedFrames++;
    telemetry.bridgeDequeuedBytes += state.lastDequeuedBytes ?? 0;
}

function arrivalSocketStateForClient(client) {
    try {
        const entry = client?.bridge?.channels?.get(client.id);
        return entry?.ws?.__gaiusArrivalState;
    }
    catch {
        return undefined;
    }
}

function arrivalDelta(start, end, clock = undefined) {
    if (!Number.isFinite(start) || !Number.isFinite(end)) return null;
    const raw = end - start;
    if (raw < 0 && clock !== undefined) clock.clampedNegativeDeltas = true;
    return Math.max(0, raw);
}

function isPeriodicServerSyncArrival({
    profileId,
    profileName,
    protocolVersion,
    packetId,
    decodedGapMillis,
    phaseAtDecode,
    onmessageToDequeueMillis,
    pollToBridgeDequeueMillis,
    pollToDecodeMillis,
    decodeMillis,
    decodeToDispatchMillis,
}) {
    const resolvedProfileId = profileId ?? profileName;
    const localSegments = [
        onmessageToDequeueMillis,
        pollToBridgeDequeueMillis,
        pollToDecodeMillis,
        decodeMillis,
        decodeToDispatchMillis,
    ];
    // A cadence hint is only safe in steady PLAY with no independently
    // observed local segment consuming the slow-gap window.  Otherwise keep
    // the narrower bridge/decode/dispatch attribution below; a packet id and
    // a coincidental ~1s interval must never hide a real browser stall.
    const localSlowSegment = localSegments.some((value) =>
        Number.isFinite(value) && value >= ARRIVAL_TIMELINE_SLOW_THRESHOLD_MILLIS);
    return resolvedProfileId === ARRIVAL_PERIODIC_SERVER_SYNC_PROFILE_ID &&
        protocolVersion === ARRIVAL_PERIODIC_SERVER_SYNC_PROTOCOL_VERSION &&
        packetId === ARRIVAL_PERIODIC_SERVER_SYNC_PACKET_ID &&
        phaseAtDecode === "play" &&
        !localSlowSegment &&
        Number.isFinite(decodedGapMillis) &&
        Math.abs(decodedGapMillis - ARRIVAL_PERIODIC_SERVER_SYNC_NOMINAL_GAP_MILLIS) <=
            ARRIVAL_PERIODIC_SERVER_SYNC_TOLERANCE_MILLIS;
}

function createArrivalTraceRing() {
    return {
        limit: ARRIVAL_TRACE_EVENT_LIMIT,
        enabled: ARRIVAL_TRACE_ENABLED,
        nextIndex: 0,
        sequence: 0,
        dropped: 0,
        forcedNextIndex: 0,
        forcedDropped: 0,
        forcedBoundaryDropped: 0,
        pollEventsSeen: 0,
        pollEventsSuppressed: 0,
        frameEventsSeen: 0,
        events: new Array(ARRIVAL_TRACE_EVENT_LIMIT),
        forcedEvents: new Array(ARRIVAL_TRACE_FORCED_EVENT_LIMIT),
    };
}

function traceInteger(value) {
    return Number.isSafeInteger(value) && value >= 0 ? value : null;
}

function traceNumber(value) {
    return Number.isFinite(value) && value >= 0 ? Number(value) : null;
}

function recordArrivalTrace(ring, kind, details = {}) {
    if (ring === undefined || ring === null) return null;
    if (ring.enabled !== true) return null;
    const requestedKind = String(kind);
    const traceKind = ARRIVAL_TRACE_EVENT_KIND_SET.has(requestedKind)
        ? requestedKind : "phase";
    const pollEvent = traceKind === "poll-ready" ||
        traceKind === "poll-begin" || traceKind === "poll-end";
    const pollSequence = traceInteger(details.pollSequence);
    if (pollEvent) {
        ring.pollEventsSeen++;
        const sampled = details.force === true ||
            (pollSequence !== null &&
                pollSequence % ARRIVAL_TRACE_POLL_EVENT_STRIDE === 0);
        if (!sampled) {
            ring.pollEventsSuppressed++;
            return null;
        }
    }
    const frameEvent = traceKind === "onmessage-enter" ||
        traceKind === "bridge-enqueue" || traceKind === "bridge-dequeue";
    const frameSequence = traceInteger(details.frameSequence);
    if (frameEvent) {
        ring.frameEventsSeen++;
        const sampleSequence = frameSequence ?? ring.frameEventsSeen;
        if (details.force !== true &&
            sampleSequence % ARRIVAL_TRACE_FRAME_EVENT_STRIDE !== 0) {
            return null;
        }
    }
    const packetSequence = traceInteger(details.packetSequence);
    if (traceKind === "decode-begin" || traceKind === "dispatch-begin") {
        if (packetSequence !== null && details.force !== true &&
            packetSequence % ARRIVAL_TRACE_PACKET_BEGIN_EVENT_STRIDE !== 0) {
            return null;
        }
    }
    else if (traceKind === "decode-end" || traceKind === "dispatch-end") {
        const packetId = traceInteger(details.packetId);
        if (packetSequence !== null && details.force !== true &&
            packetSequence % ARRIVAL_TRACE_PACKET_END_EVENT_STRIDE !== 0 &&
            !ARRIVAL_TRACE_PACKET_ALWAYS_IDS.has(packetId)) {
            return null;
        }
    }
    const sequence = ++ring.sequence;
    const index = ring.nextIndex;
    if (ring.events[index] !== undefined) ring.dropped++;
    const event = {
        schemaVersion: ARRIVAL_TRACE_SCHEMA_VERSION,
        sequence,
        kind: traceKind,
        at: traceNumber(details.at),
        source: details.source === "socket" ? "socket" : "client",
        frameSequence,
        frameBytes: traceInteger(details.frameBytes),
        packetSequence: traceInteger(details.packetSequence),
        packetId: traceInteger(details.packetId),
        phase: details.phase === undefined || details.phase === null
            ? null : String(details.phase),
        schedulerCallbackSequence: traceInteger(details.schedulerCallbackSequence),
        pollSequence,
        queueDepth: traceInteger(details.queueDepth),
        bufferedBytes: traceInteger(details.bufferedBytes),
        durationMillis: traceNumber(details.durationMillis),
        intentional: details.intentional === true,
        forceRetained: details.force === true,
        schedulerTrigger: typeof details.schedulerTrigger === "string"
            ? details.schedulerTrigger.slice(0, 80) : null,
        boundaryReason: typeof details.boundaryReason === "string"
            ? details.boundaryReason.slice(0, 80) : null,
        gapStartAt: traceNumber(details.gapStartAt),
        gapEndAt: traceNumber(details.gapEndAt),
        gapMillis: traceNumber(details.gapMillis),
        decodedGapMillis: traceNumber(details.decodedGapMillis),
        triggerSegments: Array.isArray(details.triggerSegments)
            ? details.triggerSegments.slice(0, 5).map((segment) =>
                String(segment).slice(0, 80)) : [],
    };
    ring.events[index] = event;
    ring.nextIndex = (index + 1) % ring.limit;
    if (details.force === true && Array.isArray(ring.forcedEvents)) {
        const forcedIndex = ring.forcedNextIndex;
        const evicted = ring.forcedEvents[forcedIndex];
        if (evicted !== undefined) {
            ring.forcedDropped++;
            if (evicted.kind === "arrival-gap-boundary") {
                ring.forcedBoundaryDropped++;
            }
        }
        ring.forcedEvents[forcedIndex] = event;
        ring.forcedNextIndex =
            (forcedIndex + 1) % ARRIVAL_TRACE_FORCED_EVENT_LIMIT;
    }
    return event;
}

function orderedArrivalTraceEvents(ring) {
    if (ring === undefined || ring === null) return [];
    const events = [];
    const seen = new Set();
    for (const event of [
        ...(Array.isArray(ring.events) ? ring.events : []),
        ...(Array.isArray(ring.forcedEvents) ? ring.forcedEvents : []),
    ]) {
        if (event === undefined || seen.has(event)) continue;
        seen.add(event);
        events.push(event);
    }
    return events.sort((left, right) => left.sequence - right.sequence);
}

function arrivalTraceWindow(rings, startAt, endAt) {
    const start = Number.isFinite(startAt) ? Number(startAt) : null;
    const end = Number.isFinite(endAt) ? Number(endAt) : null;
    const bounded = start !== null || end !== null;
    const candidates = [];
    let dropped = 0;
    let forcedEventsDropped = 0;
    let forcedBoundaryDropped = 0;
    let pollEventsSuppressed = 0;
    let timestampUnavailable = 0;
    let ringOverflowAffectsWindow = false;
    for (const ring of rings ?? []) {
        if (ring === undefined || ring === null) continue;
        dropped += ring.dropped;
        forcedEventsDropped += Number(ring.forcedDropped) || 0;
        forcedBoundaryDropped += Number(ring.forcedBoundaryDropped) || 0;
        pollEventsSuppressed += ring.pollEventsSuppressed;
        const retainedEvents = orderedArrivalTraceEvents(ring);
        const retainedTimes = retainedEvents.map((event) => event.at)
            .filter((at) => Number.isFinite(at));
        if ((ring.dropped > 0 || ring.forcedDropped > 0) &&
            (start === null || retainedTimes.length === 0 ||
            Math.min(...retainedTimes) > start)) {
            // `dropped` is cumulative.  It invalidates this particular window
            // only when the oldest retained timestamp is newer than the
            // window start (or when no timestamp can anchor the ring).
            ringOverflowAffectsWindow = true;
        }
        for (const event of retainedEvents) {
            if (bounded && event.at === null) {
                timestampUnavailable++;
                continue;
            }
            if (start !== null && event.at < start) continue;
            if (end !== null && event.at > end) continue;
            candidates.push(event);
        }
    }
    candidates.sort((left, right) =>
        (left.at ?? Number.POSITIVE_INFINITY) -
            (right.at ?? Number.POSITIVE_INFINITY) ||
        left.sequence - right.sequence || left.source.localeCompare(right.source));
    // A forced slow-boundary marker must survive ordinary sampled events.  It
    // is still returned in chronological order after priority selection so
    // consumers can read the window as a timeline.
    const forcedBoundaryCandidates = candidates.filter((event) =>
        event.kind === "arrival-gap-boundary" && event.forceRetained === true);
    const forcedCandidates = candidates.filter((event) =>
        event.forceRetained === true && event.kind !== "arrival-gap-boundary");
    const ordinaryCandidates = candidates.filter((event) =>
        event.forceRetained !== true);
    const prioritizedCandidates = [
        ...forcedBoundaryCandidates,
        ...forcedCandidates,
        ...ordinaryCandidates,
    ];
    const traceEvents = prioritizedCandidates.slice(0, ARRIVAL_TRACE_EVENT_LIMIT)
        .sort((left, right) =>
            (left.at ?? Number.POSITIVE_INFINITY) -
                (right.at ?? Number.POSITIVE_INFINITY) ||
            left.sequence - right.sequence || left.source.localeCompare(right.source));
    const retainedOverflow = Math.max(0, candidates.length - traceEvents.length);
    const times = traceEvents
        .map((event) => event.at)
        .filter((at) => Number.isFinite(at));
    let maxInterEventGapMillis = 0;
    for (let index = 1; index < times.length; index++) {
        maxInterEventGapMillis = Math.max(
            maxInterEventGapMillis, Math.max(0, times[index] - times[index - 1]));
    }
    const eventCounts = Object.fromEntries(
        ARRIVAL_TRACE_EVENT_KINDS.map((eventKind) => [eventKind, 0]));
    for (const event of traceEvents) eventCounts[event.kind]++;
    const forcedBoundaryEventCount = traceEvents.filter((event) =>
        event.kind === "arrival-gap-boundary" && event.forceRetained === true).length;
    const forcedEventCount = traceEvents.filter((event) =>
        event.forceRetained === true).length;
    const totalDropped = dropped + retainedOverflow;
    const enabled = (rings ?? []).some((ring) => ring?.enabled === true);
    return {
        schemaVersion: ARRIVAL_TRACE_SCHEMA_VERSION,
        enabled,
        diagnosticOnly: true,
        strictEvidenceEligible: ARRIVAL_TRACE_STRICT_EVIDENCE_ELIGIBLE,
        strictGatesChanged: false,
        limit: ARRIVAL_TRACE_EVENT_LIMIT,
        gapStartAt: start,
        gapEndAt: end,
        gapMillis: start !== null && end !== null ? Math.max(0, end - start) : null,
        firstEventAt: times.length > 0 ? times[0] : null,
        lastEventAt: times.length > 0 ? times[times.length - 1] : null,
        maxInterEventGapMillis,
        eventCounts,
        events: traceEvents,
        dropped: totalDropped,
        ringDropped: dropped,
        forcedEventCount,
        forcedEventsDropped,
        forcedBoundaryEventCount,
        forcedBoundaryDropped,
        forcedBoundaryRetained: forcedBoundaryEventCount > 0,
        forcedBoundaryCoverage: !enabled ? "disabled" :
            forcedBoundaryEventCount > 0
                ? forcedBoundaryDropped > 0 ? "retained-with-overflow" : "retained"
                : forcedBoundaryDropped > 0 ? "dropped" : "not-observed",
        ringOverflowAffectsWindow,
        pollEventsSuppressed,
        timestampUnavailableEvents: timestampUnavailable,
        coverage: !enabled ? "disabled" :
            bounded && timestampUnavailable > 0 ? "timestamp-incomplete" :
                (ringOverflowAffectsWindow || retainedOverflow > 0)
                    ? "ring-overflow" : "complete",
        wireAtSource: ARRIVAL_WIRE_AT_SOURCE,
        bridgeEnqueueTimestampAvailable: false,
    };
}

function arrivalTraceContract() {
    return {
        schemaVersion: ARRIVAL_TRACE_SCHEMA_VERSION,
        eventLimit: ARRIVAL_TRACE_EVENT_LIMIT,
        forcedEventLimit: ARRIVAL_TRACE_FORCED_EVENT_LIMIT,
        boundaryEventKind: "arrival-gap-boundary",
        forcedBoundaryPriority: true,
        pollEventStride: ARRIVAL_TRACE_POLL_EVENT_STRIDE,
        frameEventStride: ARRIVAL_TRACE_FRAME_EVENT_STRIDE,
        packetBeginEventStride: ARRIVAL_TRACE_PACKET_BEGIN_EVENT_STRIDE,
        packetEndEventStride: ARRIVAL_TRACE_PACKET_END_EVENT_STRIDE,
        enabled: ARRIVAL_TRACE_ENABLED,
        diagnosticOnly: true,
        strictEvidenceEligible: ARRIVAL_TRACE_STRICT_EVIDENCE_ELIGIBLE,
        strictGatesChanged: false,
        wireAtSource: ARRIVAL_WIRE_AT_SOURCE,
        bridgeEnqueueTimestampAvailable: false,
        retention: "bounded-gap-window-with-forced-boundary-priority",
    };
}

function arrivalTraceRingsForClient(client) {
    const rings = [];
    if (client?.arrivalTraceRing !== undefined) {
        rings.push(client.arrivalTraceRing);
    }
    const socketRing = arrivalSocketStateForClient(client)?.traceRing;
    if (socketRing !== undefined && socketRing !== client?.arrivalTraceRing) {
        rings.push(socketRing);
    }
    return rings;
}

function classifyBrowserArrivalGap({
    profileId,
    profileName,
    protocolVersion,
    packetId,
    decodedGapMillis,
    phaseAtDecode,
    intentional,
    reconnect,
    onmessageToDequeueMillis,
    pollToBridgeDequeueMillis,
    pollToDecodeMillis,
    decodeMillis,
    decodeToDispatchMillis,
}) {
    if (intentional) return "intentional-transport-drop-tail";
    if (reconnect) return "reconnect-gap";
    if (isPeriodicServerSyncArrival({
        profileId,
        profileName,
        protocolVersion,
        packetId,
        decodedGapMillis,
        phaseAtDecode,
        onmessageToDequeueMillis,
        pollToBridgeDequeueMillis,
        pollToDecodeMillis,
        decodeMillis,
        decodeToDispatchMillis,
    })) {
        return ARRIVAL_PERIODIC_SERVER_SYNC_CLASSIFICATION;
    }
    if (Number.isFinite(pollToBridgeDequeueMillis) &&
        pollToBridgeDequeueMillis >= ARRIVAL_TIMELINE_SLOW_THRESHOLD_MILLIS) {
        // Prefer the narrower bridge segment when both it and the aggregate
        // onmessage-to-dequeue interval are large; otherwise the aggregate
        // label would hide the actual synchronous bridge delay.
        return "bridge-dequeue-delay";
    }
    if (Number.isFinite(onmessageToDequeueMillis) &&
        onmessageToDequeueMillis >= ARRIVAL_TIMELINE_SLOW_THRESHOLD_MILLIS) {
        return "browser-queue-delay";
    }
    if (Number.isFinite(pollToDecodeMillis) &&
        pollToDecodeMillis >= ARRIVAL_TIMELINE_SLOW_THRESHOLD_MILLIS) {
        // pollToDecode includes buffered-prefix parsing and handler work.  In
        // the absence of an independent enqueue/wire timestamp it cannot be
        // promoted to a definitive scheduler/dispatch attribution.
        return "unknown-arrival-gap";
    }
    if (Number.isFinite(decodeMillis) &&
        decodeMillis >= ARRIVAL_TIMELINE_SLOW_THRESHOLD_MILLIS) {
        return "decode-delay";
    }
    if (Number.isFinite(decodeToDispatchMillis) &&
        decodeToDispatchMillis >= ARRIVAL_TIMELINE_SLOW_THRESHOLD_MILLIS) {
        return "dispatch-delay";
    }
    if (onmessageToDequeueMillis === null &&
        pollToBridgeDequeueMillis === null && pollToDecodeMillis === null &&
        decodeMillis === null && decodeToDispatchMillis === null) {
        // Missing local timestamps do not prove that the upstream server was
        // silent.  A packet already held in the protocol cumulation, for
        // example, intentionally has no new WebSocket/dequeue marker.  Keep
        // the attribution unknown until a trusted wire/transport source is
        // available.
        return "unattributed-arrival-gap";
    }
    return "unknown-arrival-gap";
}

class BrowserMinecraftClient {
    constructor(options) {
        Object.assign(this, options);
        this.username = options.identity.username;
        this.profileId = options.identity.profileId;
        this.accessToken = options.identity.accessToken;
        this.phase = "login";
        this.buffer = Buffer.alloc(0);
        this.cipher = undefined;
        this.decipher = undefined;
        this.compressionThreshold = undefined;
        this.encryptionRequest = false;
        this.rsaSecretEncrypted = false;
        this.rsaChallengeEncrypted = false;
        this.aesCfb8Enabled = false;
        this.secretFingerprint = undefined;
        this.sessionJoin = false;
        this.loginFinished = false;
        this.configurationFinished = false;
        this.configurationCycles = 0;
        this.reconfigurationRequests = 0;
        this.configurationPackets = 0;
        this.configurationPacketIds = [];
        this.showDialogPackets = 0;
        this.showDialogAccepts = 0;
        this.showDialogActions = [];
        this.showDialogSummaries = [];
        this.acceptedDialogActions = new Set();
        this.codeOfConductRequests = 0;
        this.codeOfConductAccepts = 0;
        this.playPackets = 0;
        this.playPacketIds = [];
        this.playDisconnectPayloadBase64 = undefined;
        this.playLoginPackets = 0;
        this.playLoginDistanceContracts = 0;
        this.playLoginChunkRadius = undefined;
        this.playLoginSimulationDistance = undefined;
        this.chunkPackets = 0;
        this.uniqueChunkPositions = new Set();
        // Extreme evidence begins only after the server has explicitly sent
        // its cache-radius and simulation-distance contract.  Keeping this
        // separate from all observed chunks prevents an early/default window
        // from being counted toward the radius-8 / 257-chunk target.
        this.stressQualifiedChunkPositions = new Set();
        this.chunkPacketsBeforeDistanceContract = 0;
        this.duplicateChunkPackets = 0;
        this.chunkBounds = undefined;
        this.observedChunkCacheCenter = undefined;
        this.observedChunkCacheRadius = undefined;
        this.observedSimulationDistance = undefined;
        this.chunkCacheCenterUpdates = 0;
        this.chunkCacheRadiusUpdates = 0;
        this.simulationDistanceUpdates = 0;
        this.chunkBatchStarts = 0;
        this.chunkBatchFinished = 0;
        this.chunkBatchAcknowledgements = 0;
        this.chunkBatchProtocolErrors = 0;
        this.chunkBatchCountMismatches = 0;
        this.chunkBatchOpen = false;
        this.currentChunkBatchPackets = 0;
        this.chunkBatches = [];
        this.playTickPackets = 0;
        this.playTickActive = false;
        this.playTickSuspended = false;
        this.nextPlayTickDueAt = undefined;
        this.playTickSkippedPeriods = 0;
        this.playTickTimer = undefined;
        this.playerLoadedSent = false;
        this.connectPhases = [];
        this.failure = undefined;
        this.closed = false;
        this.pollingPaused = false;
        // Shared poll scheduler state. The scheduler mirrors its own due map
        // into this field for diagnostics and for clients that can be observed
        // without a bridge-specific side table.
        this.nextPollDueAt = performance.now();
        this.startedAt = performance.now();
        this.connectStartedAt = undefined;
        this.relayConnectedAt = undefined;
        this.handshakeSentAt = undefined;
        this.encryptionRequestAt = undefined;
        this.sessionJoinAt = undefined;
        this.loginFinishedAt = undefined;
        this.configurationFinishedAt = undefined;
        this.playLoginAt = undefined;
        this.firstChunkAt = undefined;
        this.minimumChunksAt = undefined;
        this.closedAt = undefined;
        this.closeReason = undefined;
        this.inboundFrames = 0;
        this.inboundBytes = 0;
        this.maxInboundFramesPerPoll = 0;
        this.maxPacketsPerPoll = 0;
        this.inboundFrameBudgetYields = 0;
        this.packetBudgetYields = 0;
        this.outboundFrames = 0;
        this.outboundBytes = 0;
        this.decodedPackets = 0;
        this.maximumBufferedBytes = 0;
        this.lastPollAt = undefined;
        this.pollGapSamples = 0;
        this.maxPollGapMillis = 0;
        this.pollGapHistogram = createLatencyHistogram();
        this.lastInboundPacketAt = undefined;
        this.inboundPacketGapSamples = 0;
        this.maxInboundPacketGapMillis = 0;
        this.lastPlayPacketAt = undefined;
        this.playPacketGapSamples = 0;
        this.maxPlayPacketGapMillis = 0;
        this.preMinimumChunkPacketGapSamples = 0;
        this.maxPreMinimumChunkPacketGapMillis = 0;
        this.preMinimumChunkGapHistogram = createLatencyHistogram();
        this.lastChunkPacketAt = undefined;
        this.lastPlayTickAt = undefined;
        this.playTickGapSamples = 0;
        this.maxPlayTickGapMillis = 0;
        this.playTickGapHistogram = createLatencyHistogram();
        // Keep per-client PLAY tick timing as a bounded chronological ring.
        // This is attribution-only evidence for p99/max gaps; it does not
        // participate in scheduling, cadence, or strict acceptance checks.
        this.playTickTimingSequence = 0;
        this.playTickTimingRing = {
            nextIndex: 0,
            count: 0,
            total: 0,
            dropped: 0,
            samples: new Array(PLAY_TICK_TIMING_SAMPLE_LIMIT),
        };
        // Reuse one accumulator per client so the normal (sub-threshold) poll
        // path does not allocate a trace object on every scheduler turn.  A
        // materialized sample is copied into the bounded ring only when the
        // complete poll exceeds the diagnostic threshold.
        this.pollPhaseSequence = 0;
        this.pollPhasePollsTotal = 0;
        this.pollPhaseSamplesTotal = 0;
        this.pollPhaseSamplesDropped = 0;
        this.pollPhaseSamples = [];
        this.pollPhaseContext = {
            active: false,
            pollSequence: 0,
            schedulerCallbackSequence: null,
            schedulerTrigger: null,
            startedAt: 0,
            durationRawMillis: 0,
            clockAnomaly: false,
            outcome: "ok",
            segments: {
                preludeMillis: 0,
                checkErrorMillis: 0,
                recordPhasesMillis: 0,
                prefixParseMillis: 0,
                bridgeDrainMillis: 0,
                bridgePollMillis: 0,
                decryptMillis: 0,
                concatMillis: 0,
                parseMillis: 0,
                inflateMillis: 0,
                handlerMillis: 0,
                finalizeMillis: 0,
            },
            work: {
                frames: 0,
                frameBytes: 0,
                packetsParsed: 0,
                bridgePollCalls: 0,
                compressedPackets: 0,
                maxBridgePollMillis: 0,
                maxInflateMillis: 0,
                maxHandlerMillis: 0,
                maxPacketMillis: 0,
            },
            bufferBefore: 0,
            bufferAfter: 0,
            maximumBufferedBytes: 0,
            frameBudgetYield: false,
            packetBudgetYield: false,
            slowPacketIds: [],
        };
        // Bounded, diagnostic-only packet arrival evidence.  `wireAt` is
        // intentionally never synthesized: the browser/WebSocket bridge does
        // not expose a server wire timestamp.  Frame metadata is associated
        // with the latest bridge dequeue and may be null when the per-socket
        // primitive ring overflowed.
        this.arrivalSlowGapCountTotal = 0;
        this.arrivalSlowSampleCountTotal = 0;
        this.arrivalSlowSamplesDropped = 0;
        this.arrivalIntentionalTransportDropCount = 0;
        this.arrivalIntentionalDropGapsExcluded = 0;
        this.arrivalFrameMetadataDropped = 0;
        this.arrivalReconnectPhaseCountTotal = 0;
        this.arrivalReconnectPhasesDropped = 0;
        this.arrivalSlowSamples = [];
        this.arrivalReconnectPhases = [];
        this.arrivalClassificationCounts = new Map();
        this.arrivalTraceRing = createArrivalTraceRing();
        this.arrivalPeriodicServerSyncGapCountTotal = 0;
        this.arrivalPeriodicServerSyncGapsExcluded = 0;
        this.arrivalPhaseSequence = 0;
        this.arrivalSeenPhaseEvents = new WeakSet();
        // connectPhases is append-only across lifecycles in the shared stats
        // object. Keep an identity/length cursor so each poll only inspects
        // entries appended since the previous poll instead of rescanning the
        // entire history during long stress runs.
        this.phaseScanSource = null;
        this.phaseScanIndex = 0;
        // A replacement client may inherit only the previous lifecycle's last
        // decode timestamp as a diagnostic seed.  This lets the first decoded
        // packet after reconnect expose the full reconnect gap without sharing
        // protocol buffers, ciphers, or any live scheduler state.
        this.arrivalReconnectPriorDecodeEndAt = Number.isFinite(options.previousDecodeEndAt)
            ? Number(options.previousDecodeEndAt) : null;
        this.arrivalLastDecodeEndAt = this.arrivalReconnectPriorDecodeEndAt ?? undefined;
        this.arrivalReconnectDropAt = Number.isFinite(options.reconnectDropAt)
            ? Number(options.reconnectDropAt) : null;
        this.arrivalReconnectScheduledAt = Number.isFinite(options.reconnectScheduledAt)
            ? Number(options.reconnectScheduledAt) : null;
        this.arrivalIntentionalDropPending = false;
        this.arrivalReconnectRecoveryPending = this.wave > 0;
        this.arrivalCurrentFrameSequence = null;
        this.arrivalCurrentOnmessageAt = null;
        this.arrivalCurrentBridgeDequeueAt = null;
        this.arrivalCurrentFrameBytes = null;
        this.arrivalCurrentPollAt = null;
        this.arrivalCurrentFrameMetadataDropped = false;
        this.arrivalObservedOnmessage = false;
        this.arrivalObservedBridgeDequeue = false;
        this.arrivalObservedDecodedPacket = false;
        this.arrivalObservedDispatch = false;
        this.arrivalFirstChunkRecorded = false;
        this.arrivalMinimumChunksRecorded = false;
        this.arrivalFirstDecodedAfterReconnect = false;
        this.arrivalReconnectFirstDecodedAt = null;
        this.arrivalReconnectGapMillis = null;
        this.arrivalReconnectClockAnomaly = false;
        this.arrivalFirstOnmessageRecorded = false;
        if (this.arrivalReconnectDropAt !== null) {
            this.recordArrivalPhase("disconnect", this.arrivalReconnectDropAt, {
                intentional: true,
                source: "browser-full-path-harness",
            });
        }
        if (this.arrivalReconnectScheduledAt !== null) {
            this.recordArrivalPhase("reconnect-scheduled",
                this.arrivalReconnectScheduledAt, {
                    source: "browser-full-path-harness",
                });
        }
        this.recordArrivalPhase("client-created", this.startedAt);
    }

    recordArrivalPhase(phase, monotonicAt = performance.now(), details = {}) {
        const entry = {
            sequence: ++this.arrivalPhaseSequence,
            phase: String(phase),
            monotonicAt: Number.isFinite(monotonicAt) ? monotonicAt : null,
            wallAt: Number.isFinite(details.wallAt) ? details.wallAt : null,
            elapsedMillis: Number.isFinite(details.elapsedMillis)
                ? Math.max(0, details.elapsedMillis) : null,
            source: details.source ?? "browser-client-harness",
            intentional: details.intentional === true,
            ...(details.detail === undefined ? {} : {
                detail: String(details.detail).slice(0, 160),
            }),
        };
        recordArrivalTrace(this.arrivalTraceRing, phase, {
            at: entry.monotonicAt,
            phase: entry.phase,
            source: "client",
            intentional: entry.intentional,
        });
        this.arrivalReconnectPhaseCountTotal++;
        if (this.arrivalReconnectPhases.length >= ARRIVAL_TIMELINE_RECONNECT_PHASE_LIMIT) {
            this.arrivalReconnectPhases.shift();
            this.arrivalReconnectPhasesDropped++;
        }
        this.arrivalReconnectPhases.push(entry);
        return entry;
    }

    setArrivalFrameFromBridge(pollAt) {
        this.arrivalCurrentPollAt = Number.isFinite(pollAt) ? pollAt : null;
        this.arrivalCurrentFrameSequence = null;
        this.arrivalCurrentOnmessageAt = null;
        this.arrivalCurrentBridgeDequeueAt = null;
        this.arrivalCurrentFrameBytes = null;
        this.arrivalCurrentFrameMetadataDropped = false;
        const state = arrivalSocketStateForClient(this);
        if (state === undefined) return;
        if (Number.isFinite(state.lastDequeuedPollStartedAt)) {
            this.arrivalCurrentPollAt = state.lastDequeuedPollStartedAt;
        }
        this.arrivalCurrentFrameSequence = state.lastDequeuedFrameSequence;
        this.arrivalCurrentOnmessageAt = state.lastDequeuedOnmessageAt;
        this.arrivalCurrentBridgeDequeueAt = state.lastDequeuedAt;
        this.arrivalCurrentFrameBytes = state.lastDequeuedBytes;
        this.arrivalCurrentFrameMetadataDropped = state.lastDequeuedFrameSequence === null;
        this.arrivalFrameMetadataDropped += state.frameMetadataDropped;
        // The bridge state is cumulative; clear the per-client accounting
        // cursor after consuming it so the same overflow is not counted on
        // every packet in a later poll.
        state.frameMetadataDropped = 0;
        this.arrivalObservedOnmessage ||= Number.isFinite(this.arrivalCurrentOnmessageAt);
        this.arrivalObservedBridgeDequeue ||= Number.isFinite(
            this.arrivalCurrentBridgeDequeueAt);
        if (Number.isFinite(this.arrivalCurrentOnmessageAt) &&
            !this.arrivalFirstOnmessageRecorded) {
            this.arrivalFirstOnmessageRecorded = true;
            this.recordArrivalPhase("first-onmessage", this.arrivalCurrentOnmessageAt);
        }
    }

    clearArrivalFrame() {
        this.arrivalCurrentFrameSequence = null;
        this.arrivalCurrentOnmessageAt = null;
        this.arrivalCurrentBridgeDequeueAt = null;
        this.arrivalCurrentFrameBytes = null;
        this.arrivalCurrentPollAt = null;
        this.arrivalCurrentFrameMetadataDropped = false;
    }

    recordArrivalPacket({
        packetId,
        phaseAtDecode,
        decodeStartAt,
        decodeEndAt,
        dispatchAt,
        reconnectRecoveryAtDecode = false,
        schedulerCallbackSequence = null,
        schedulerTrigger = null,
        pollSequence = null,
    }) {
        this.arrivalObservedDecodedPacket = true;
        this.arrivalObservedDispatch ||= Number.isFinite(dispatchAt);
        const previousDecodeEndAt = this.arrivalLastDecodeEndAt;
        this.arrivalLastDecodeEndAt = decodeEndAt;
        const clock = {
            monotonic: true,
            clampedNegativeDeltas: false,
        };
        const decodedGapMillis = arrivalDelta(previousDecodeEndAt, decodeEndAt, clock);
        const onmessageToDequeueMillis = arrivalDelta(
            this.arrivalCurrentOnmessageAt,
            this.arrivalCurrentBridgeDequeueAt,
            clock,
        );
        const pollToBridgeDequeueMillis = arrivalDelta(
            this.arrivalCurrentPollAt,
            this.arrivalCurrentBridgeDequeueAt,
            clock,
        );
        const pollToDecodeMillis = arrivalDelta(
            this.arrivalCurrentPollAt,
            decodeStartAt,
            clock,
        );
        const decodeMillis = arrivalDelta(decodeStartAt, decodeEndAt, clock);
        const decodeToDispatchMillis = arrivalDelta(decodeEndAt, dispatchAt, clock);
        let triggerMask = 0;
        let slowTriggerMillis = Number.isFinite(decodedGapMillis)
            ? decodedGapMillis : 0;
        if (Number.isFinite(onmessageToDequeueMillis) &&
            onmessageToDequeueMillis >= ARRIVAL_TIMELINE_SLOW_THRESHOLD_MILLIS) {
            triggerMask |= 1;
            slowTriggerMillis = Math.max(slowTriggerMillis, onmessageToDequeueMillis);
        }
        if (Number.isFinite(pollToBridgeDequeueMillis) &&
            pollToBridgeDequeueMillis >= ARRIVAL_TIMELINE_SLOW_THRESHOLD_MILLIS) {
            triggerMask |= 2;
            slowTriggerMillis = Math.max(slowTriggerMillis, pollToBridgeDequeueMillis);
        }
        if (Number.isFinite(pollToDecodeMillis) &&
            pollToDecodeMillis >= ARRIVAL_TIMELINE_SLOW_THRESHOLD_MILLIS) {
            triggerMask |= 4;
            slowTriggerMillis = Math.max(slowTriggerMillis, pollToDecodeMillis);
        }
        if (Number.isFinite(decodeMillis) &&
            decodeMillis >= ARRIVAL_TIMELINE_SLOW_THRESHOLD_MILLIS) {
            triggerMask |= 8;
            slowTriggerMillis = Math.max(slowTriggerMillis, decodeMillis);
        }
        if (Number.isFinite(decodeToDispatchMillis) &&
            decodeToDispatchMillis >= ARRIVAL_TIMELINE_SLOW_THRESHOLD_MILLIS) {
            triggerMask |= 16;
            slowTriggerMillis = Math.max(slowTriggerMillis, decodeToDispatchMillis);
        }
        if (slowTriggerMillis < ARRIVAL_TIMELINE_SLOW_THRESHOLD_MILLIS) {
            return;
        }
        this.arrivalSlowGapCountTotal++;
        const triggerSegments = [];
        if (triggerMask & 1) triggerSegments.push("onmessage-to-bridge-dequeue");
        if (triggerMask & 2) triggerSegments.push("poll-to-bridge-dequeue");
        if (triggerMask & 4) triggerSegments.push("poll-to-decode");
        if (triggerMask & 8) triggerSegments.push("decode");
        if (triggerMask & 16) triggerSegments.push("decode-to-dispatch");
        const intentional = this.arrivalIntentionalDropPending;
        if (reconnectRecoveryAtDecode === true) {
            this.arrivalFirstDecodedAfterReconnect = true;
        }
        const classification = classifyBrowserArrivalGap({
            profileId: this.profile?.name,
            profileName: this.profile?.name,
            protocolVersion: this.profile?.protocolVersion,
            packetId,
            decodedGapMillis,
            slowTriggerMillis,
            triggerSegments,
            phaseAtDecode,
            intentional,
            // Snapshot this before handlePacket() can observe the first chunk
            // and close the recovery window.  A gap ending at that explicit
            // first-chunk boundary is reconnect evidence; later steady-wave
            // gaps are not classified by the wave number alone.
            reconnect: reconnectRecoveryAtDecode === true,
            onmessageToDequeueMillis,
            pollToBridgeDequeueMillis,
            pollToDecodeMillis,
            decodeMillis,
            decodeToDispatchMillis,
        });
        this.arrivalClassificationCounts.set(
            classification,
            (this.arrivalClassificationCounts.get(classification) ?? 0) + 1,
        );
        const excludedFromUserVisibleStall =
            classification === ARRIVAL_PERIODIC_SERVER_SYNC_CLASSIFICATION;
        if (excludedFromUserVisibleStall) {
            this.arrivalPeriodicServerSyncGapCountTotal++;
            this.arrivalPeriodicServerSyncGapsExcluded++;
        }
        if (intentional) {
            this.arrivalIntentionalDropGapsExcluded++;
            return;
        }
        // Keep the slow-gap boundary in the independent forced ring before
        // ordinary window selection; this is diagnostic-only and does not
        // affect any strict acceptance gate.
        recordArrivalTrace(this.arrivalTraceRing, "arrival-gap-boundary", {
            at: decodeEndAt,
            phase: phaseAtDecode,
            source: "client",
            packetSequence: this.decodedPackets,
            packetId,
            schedulerCallbackSequence,
            schedulerTrigger,
            pollSequence,
            gapStartAt: previousDecodeEndAt,
            gapEndAt: decodeEndAt,
            gapMillis: slowTriggerMillis,
            decodedGapMillis,
            boundaryReason: triggerSegments.length > 0
                ? "arrival-slow-segment" : "decoded-gap",
            triggerSegments,
            force: true,
        });
        const traceWindow = arrivalTraceWindow(
            arrivalTraceRingsForClient(this), previousDecodeEndAt, decodeEndAt);
        const sample = {
            schemaVersion: ARRIVAL_TIMELINE_SCHEMA_VERSION,
            wireAtSource: "unavailable",
            sampleSequence: this.arrivalSlowSampleCountTotal + 1,
            clientId: this.id,
            wave: this.wave,
            profileId: this.profile?.name ?? null,
            protocolVersion: Number.isSafeInteger(this.profile?.protocolVersion)
                ? this.profile.protocolVersion : null,
            packetSequence: this.decodedPackets,
            frameSequence: this.arrivalCurrentFrameSequence,
            packetId,
            phaseAtDecode,
            phaseAtReceive: this.phase,
            schedulerCallbackSequence: Number.isSafeInteger(
                schedulerCallbackSequence) ? schedulerCallbackSequence : null,
            schedulerTrigger: typeof schedulerTrigger === "string"
                ? schedulerTrigger : null,
            pollSequence: Number.isSafeInteger(pollSequence) ? pollSequence : null,
            decodedGapMillis,
            slowTriggerMillis,
            triggerSegments,
            thresholdMillis: ARRIVAL_TIMELINE_SLOW_THRESHOLD_MILLIS,
            intentionalTransportDrop: false,
            timestamps: {
                wireAt: null,
                onmessageAt: this.arrivalCurrentOnmessageAt,
                bridgeEnqueueAt: null,
                bridgeDequeueAt: this.arrivalCurrentBridgeDequeueAt,
                pollAt: this.arrivalCurrentPollAt,
                decodeStartAt,
                decodeEndAt,
                dispatchAt,
            },
            segments: {
                wireToOnmessageMillis: null,
                onmessageToBridgeDequeueMillis: onmessageToDequeueMillis,
                pollToBridgeDequeueMillis,
                pollToDecodeMillis,
                decodeMillis,
                decodeToDispatchMillis,
            },
            traceWindow,
            queue: {
                // The bridge stats object is runtime-global.  Use the channel
                // entry for the primary value so one busy client cannot be
                // mistaken for another client's backlog in a multi-client
                // sample; retain the global gauge only as explicitly scoped
                // context.
                inboundQueuedBytes: (() => {
                    const entry = this.bridge?.channels?.get(this.id);
                    const inbound = Number(entry?.inboundBytes);
                    const pending = Number(entry?.pendingInboundBytes);
                    if (!Number.isSafeInteger(inbound) || inbound < 0 ||
                        !Number.isSafeInteger(pending) || pending < 0) return null;
                    return inbound + pending;
                })(),
                inboundQueuedBytesScope: "per-client",
                globalInboundQueuedBytes:
                    Number.isSafeInteger(this.stats?.inboundQueuedBytes)
                        ? this.stats.inboundQueuedBytes : null,
                globalInboundQueuedBytesScope: "runtime-global",
                bufferedBytes: this.buffer.byteLength,
                frameBytes: this.arrivalCurrentFrameBytes,
                frameMetadataDropped: this.arrivalCurrentFrameMetadataDropped,
            },
            frameCorrelation: "latest-dequeued-websocket-message-best-effort",
            correlationQuality: this.arrivalCurrentFrameSequence === null
                ? "unknown" : "best-effort",
            classification,
            cadenceHint: excludedFromUserVisibleStall
                ? ARRIVAL_PERIODIC_SERVER_SYNC_CLASSIFICATION : null,
            excludedFromUserVisibleStall,
            strictGateImpact: "none",
            classificationConfidence: classification === "unattributed-arrival-gap"
                ? "unattributed" : classification === "reconnect-gap"
                    ? "reconnect-boundary" : excludedFromUserVisibleStall
                        ? "periodic-cadence-hint" : "best-effort-local-segment",
            clock: {
                ...clock,
            },
        };
        this.arrivalSlowSampleCountTotal++;
        this.arrivalSlowSamples.push(sample);
        this.arrivalSlowSamples.sort((left, right) =>
            (right.slowTriggerMillis ?? right.decodedGapMillis) -
                (left.slowTriggerMillis ?? left.decodedGapMillis) ||
            left.sampleSequence - right.sampleSequence);
        if (this.arrivalSlowSamples.length > ARRIVAL_TIMELINE_SAMPLE_LIMIT) {
            this.arrivalSlowSamples.length = ARRIVAL_TIMELINE_SAMPLE_LIMIT;
            this.arrivalSlowSamplesDropped++;
        }
    }

    arrivalTimelineResult() {
        return {
            schemaVersion: ARRIVAL_TIMELINE_SCHEMA_VERSION,
            independentExecution: false,
            strictGatesChanged: false,
            slowThresholdMillis: ARRIVAL_TIMELINE_SLOW_THRESHOLD_MILLIS,
            limits: {
                slowSamples: ARRIVAL_TIMELINE_SAMPLE_LIMIT,
                reconnectPhases: ARRIVAL_TIMELINE_RECONNECT_PHASE_LIMIT,
                frameMetadataRing: ARRIVAL_TIMELINE_FRAME_RING_LIMIT,
                traceEvents: ARRIVAL_TRACE_EVENT_LIMIT,
            },
            trace: arrivalTraceContract(),
            traceRing: {
                enabled: this.arrivalTraceRing.enabled === true,
                retainedEvents: orderedArrivalTraceEvents(this.arrivalTraceRing).length,
                dropped: this.arrivalTraceRing.dropped,
                forcedRetainedEvents: Array.isArray(this.arrivalTraceRing.forcedEvents)
                    ? this.arrivalTraceRing.forcedEvents.filter((event) =>
                        event !== undefined).length : 0,
                forcedDropped: this.arrivalTraceRing.forcedDropped,
                forcedBoundaryDropped: this.arrivalTraceRing.forcedBoundaryDropped,
            },
            source: {
                wireTimestampAvailable: false,
                wireAtSource: "unavailable",
                onmessageTimestampAvailable: this.arrivalObservedOnmessage,
                bridgeEnqueueTimestampAvailable: false,
                bridgeDequeueTimestampAvailable: this.arrivalObservedBridgeDequeue,
                decodeTimestampAvailable: this.arrivalObservedDecodedPacket,
                dispatchTimestampAvailable: this.arrivalObservedDispatch,
                wireAtPolicy: "null-when-unavailable",
                attributionPolicy:
                    "trusted-wire-required-for-upstream; missing-local-segments=>unattributed",
                frameCorrelation: "websocket-message-to-bridge-chunk-best-effort",
                correlationExact: false,
            },
            slowGapCountTotal: this.arrivalSlowGapCountTotal,
            slowSampleCountTotal: this.arrivalSlowSampleCountTotal,
            slowSamplesDropped: this.arrivalSlowSamplesDropped,
            intentionalTransportDropCount: this.arrivalIntentionalTransportDropCount,
            intentionalDropGapsExcluded: this.arrivalIntentionalDropGapsExcluded,
            frameMetadataDropped: this.arrivalFrameMetadataDropped,
            reconnectPhaseCountTotal: this.arrivalReconnectPhaseCountTotal,
            reconnectPhasesDropped: this.arrivalReconnectPhasesDropped,
            periodicServerSync: {
                ...arrivalPeriodicServerSyncContract(),
                gapCountTotal: this.arrivalPeriodicServerSyncGapCountTotal,
                gapsExcludedFromUserVisibleStall:
                    this.arrivalPeriodicServerSyncGapsExcluded,
            },
            // The bounded phase ring includes connect/login/play/close events
            // for the whole lifecycle, not only reconnect markers.  Keep the
            // historical field names for consumers while making its scope
            // explicit so counts cannot be mistaken for reconnect-only data.
            phaseRingScope: "all-lifecycle-phases",
            reconnectBoundary: {
                attempted: this.wave > 0,
                seededFromPreviousLifecycle: this.wave > 0 &&
                    Number.isFinite(this.arrivalReconnectPriorDecodeEndAt),
                priorDecodeEndAt: Number.isFinite(this.arrivalReconnectPriorDecodeEndAt)
                    ? this.arrivalReconnectPriorDecodeEndAt : null,
                disconnectAt: this.arrivalReconnectDropAt,
                reconnectScheduledAt: this.arrivalReconnectScheduledAt,
                firstDecodedAt: this.arrivalReconnectFirstDecodedAt,
                reconnectGapMillis: this.arrivalReconnectGapMillis,
                clockAnomaly: this.arrivalReconnectClockAnomaly,
                firstDecodedAfterReconnect: this.wave > 0 &&
                    this.arrivalFirstDecodedAfterReconnect === true,
            },
            classificationCounts: Object.fromEntries(this.arrivalClassificationCounts),
            slowSamples: this.arrivalSlowSamples.map((sample) => ({
                ...sample,
                timestamps: { ...sample.timestamps },
                segments: { ...sample.segments },
                traceWindow: sample.traceWindow === undefined ? null : {
                    ...sample.traceWindow,
                    eventCounts: { ...sample.traceWindow.eventCounts },
                    events: sample.traceWindow.events.map((event) => ({ ...event })),
                },
                queue: { ...sample.queue },
                clock: { ...sample.clock },
            })),
            reconnectPhases: this.arrivalReconnectPhases.map((phase) => ({ ...phase })),
        };
    }

    async connect() {
        this.connectStartedAt = performance.now();
        this.arrivalIntentionalDropPending = false;
        this.arrivalReconnectRecoveryPending = this.wave > 0;
        this.recordArrivalPhase("connect-begin", this.connectStartedAt);
        this.bridge.open(this.id, this.host, this.port);
        await waitFor(() => {
            this.checkError();
            this.recordPhases();
            return this.connectPhases.some((event) => event.phase === "relay-connected");
        }, `browser Relay client ${this.index + 1}`, 20000,
        () => JSON.stringify(this.diagnostics()));
        this.relayConnectedAt = performance.now();
        this.recordArrivalPhase("transport-open", this.relayConnectedAt);
        this.sendPacket(0, Buffer.concat([
            encodeVarInt(this.profile.protocolVersion),
            encodeString(this.host),
            Buffer.from([(this.port >>> 8) & 0xff, this.port & 0xff]),
            encodeVarInt(2),
        ]));
        this.recordArrivalPhase("login-begin", performance.now());
        this.sendPacket(0, Buffer.concat([
            encodeString(this.username),
            Buffer.from(this.profileId, "hex"),
        ]));
        this.handshakeSentAt = performance.now();
        this.recordArrivalPhase("handshake-sent", this.handshakeSentAt);
    }

    hasImmediateInbound() {
        // A complete outer frame in the local cumulation is an immediate
        // wake-up.  A split/partial frame is deliberately *not* immediate:
        // without another bridge chunk it would make the shared scheduler
        // recurse through setImmediate forever while parsePackets has no new
        // bytes to consume.  Partial bytes remain visible through
        // hasPendingInbound() and are retried on the normal due cadence.
        if (this.buffer.byteLength > 0) {
            try {
                const outerLength = decodeVarInt(this.buffer, 0);
                if (outerLength === undefined) {
                    // Split length VarInt; ask the bridge below whether the
                    // remaining bytes have already arrived in its queue.
                }
                else {
                    const frameEnd = outerLength.bytesRead + outerLength.value;
                    if (outerLength.value < 0 || !Number.isSafeInteger(frameEnd)) {
                        // Let poll()/parsePackets() raise the concrete framing
                        // error instead of parking malformed input forever.
                        return true;
                    }
                    if (frameEnd <= this.buffer.byteLength) return true;
                }
            }
            catch {
                // Malformed VarInts are ready for poll() to report; do not
                // hide a protocol error behind the due-time fallback.
                return true;
            }
        }
        try {
            return !!this.bridge.hasPendingInbound(this.id);
        }
        catch {
            // A bridge race is treated as ready; poll() captures the concrete
            // transport error instead of parking a client indefinitely.
            return true;
        }
    }

    hasPendingInbound() {
        if (this.buffer.byteLength > 0) return true;
        try {
            return !!this.bridge.hasPendingInbound(this.id);
        }
        catch {
            // A bridge race is treated as ready so a transient failure cannot
            // starve protocol progress; poll() will capture the real error.
            return true;
        }
    }

    resetPollPhaseContext(pollStartedAt, schedulerCallbackSequence = null,
        schedulerTrigger = null) {
        const context = this.pollPhaseContext;
        context.active = true;
        context.pollSequence = ++this.pollPhaseSequence;
        context.schedulerCallbackSequence = Number.isSafeInteger(
            schedulerCallbackSequence) ? schedulerCallbackSequence : null;
        context.schedulerTrigger = typeof schedulerTrigger === "string"
            ? schedulerTrigger : null;
        context.startedAt = pollStartedAt;
        context.durationRawMillis = 0;
        context.clockAnomaly = false;
        context.outcome = "ok";
        const segments = context.segments;
        segments.preludeMillis = 0;
        segments.checkErrorMillis = 0;
        segments.recordPhasesMillis = 0;
        segments.prefixParseMillis = 0;
        segments.bridgeDrainMillis = 0;
        segments.bridgePollMillis = 0;
        segments.decryptMillis = 0;
        segments.concatMillis = 0;
        segments.parseMillis = 0;
        segments.inflateMillis = 0;
        segments.handlerMillis = 0;
        segments.finalizeMillis = 0;
        const work = context.work;
        work.frames = 0;
        work.frameBytes = 0;
        work.packetsParsed = 0;
        work.bridgePollCalls = 0;
        work.compressedPackets = 0;
        work.maxBridgePollMillis = 0;
        work.maxInflateMillis = 0;
        work.maxHandlerMillis = 0;
        work.maxPacketMillis = 0;
        context.bufferBefore = this.buffer.byteLength;
        context.bufferAfter = context.bufferBefore;
        context.maximumBufferedBytes = this.maximumBufferedBytes;
        context.frameBudgetYield = false;
        context.packetBudgetYield = false;
        context.slowPacketIds.length = 0;
        return context;
    }

    addPollPhaseSegment(context, name, startedAt, endedAt) {
        const start = Number(startedAt);
        const end = Number(endedAt);
        const delta = end - start;
        if (!Number.isFinite(delta) || delta < 0) {
            context.clockAnomaly = true;
            return 0;
        }
        context.segments[name] += delta;
        return delta;
    }

    retainPollPhaseSample(context) {
        const duration = Number(context.durationRawMillis);
        if (!Number.isFinite(duration) || duration < POLL_PHASE_SLOW_THRESHOLD_MILLIS) {
            return false;
        }
        const segments = context.segments;
        let trigger = "poll-duration";
        let triggerMillis = 0;
        const candidates = [
            ["bridge-drain", segments.bridgeDrainMillis],
            ["decrypt-transform", segments.decryptMillis],
            ["buffer-concat", segments.concatMillis],
            ["parse-inflate", Math.max(segments.parseMillis, segments.inflateMillis)],
            ["packet-dispatch", segments.handlerMillis],
            ["prelude", Math.max(segments.preludeMillis,
                segments.checkErrorMillis, segments.recordPhasesMillis)],
            ["finalize", segments.finalizeMillis],
        ];
        for (const [name, value] of candidates) {
            if (Number.isFinite(value) && value > triggerMillis) {
                trigger = name;
                triggerMillis = value;
            }
        }
        // A poll can exceed the diagnostic threshold because several small
        // phases add up.  Do not mislabel that as a slow phase: retain the
        // aggregate poll-duration trigger unless one individual segment is
        // itself at least as slow as the diagnostic threshold.
        if (triggerMillis < POLL_PHASE_SLOW_THRESHOLD_MILLIS) {
            trigger = "poll-duration";
        }
        const boundedSegment = (value) => Number.isFinite(value) && value >= 0
            ? Math.min(duration, value) : 0;
        const boundedInteger = (value, maximum) => {
            const number = Number(value);
            return Number.isFinite(number)
                ? Math.min(maximum, Math.max(0, Math.trunc(number))) : 0;
        };
        const sample = {
            schemaVersion: POLL_PHASE_TELEMETRY_SCHEMA_VERSION,
            segmentAccounting: POLL_PHASE_SEGMENT_ACCOUNTING,
            pollSequence: context.pollSequence,
            schedulerCallbackSequence: context.schedulerCallbackSequence,
            schedulerTrigger: context.schedulerTrigger,
            clientId: this.id,
            wave: this.wave,
            startedAtMillis: context.startedAt,
            durationRawMillis: duration,
            durationMillis: Number(duration.toFixed(3)),
            slowThresholdMillis: POLL_PHASE_SLOW_THRESHOLD_MILLIS,
            strictThresholdMillis: 16.7,
            strictGatesChanged: false,
            independentExecution: false,
            diagnosticOnly: true,
            trigger,
            triggerMillis: boundedSegment(triggerMillis),
            outcome: context.outcome === "error" ? "error" : "ok",
            clockAnomaly: context.clockAnomaly,
            segments: {
                preludeMillis: boundedSegment(segments.preludeMillis),
                checkErrorMillis: boundedSegment(segments.checkErrorMillis),
                recordPhasesMillis: boundedSegment(segments.recordPhasesMillis),
                prefixParseMillis: boundedSegment(segments.prefixParseMillis),
                bridgeDrainMillis: boundedSegment(segments.bridgeDrainMillis),
                bridgePollMillis: boundedSegment(segments.bridgePollMillis),
                decryptMillis: boundedSegment(segments.decryptMillis),
                concatMillis: boundedSegment(segments.concatMillis),
                parseMillis: boundedSegment(segments.parseMillis),
                inflateMillis: boundedSegment(segments.inflateMillis),
                handlerMillis: boundedSegment(segments.handlerMillis),
                finalizeMillis: boundedSegment(segments.finalizeMillis),
            },
            work: {
                frames: boundedInteger(context.work.frames, POLL_PHASE_FRAME_SAMPLE_LIMIT),
                frameBytes: boundedInteger(context.work.frameBytes, Number.MAX_SAFE_INTEGER),
                packetsParsed: boundedInteger(context.work.packetsParsed,
                    POLL_PHASE_PACKET_SAMPLE_LIMIT),
                bridgePollCalls: boundedInteger(context.work.bridgePollCalls,
                    POLL_PHASE_FRAME_SAMPLE_LIMIT),
                compressedPackets: boundedInteger(context.work.compressedPackets,
                    POLL_PHASE_PACKET_SAMPLE_LIMIT),
                maxBridgePollMillis: boundedSegment(context.work.maxBridgePollMillis),
                maxInflateMillis: boundedSegment(context.work.maxInflateMillis),
                maxHandlerMillis: boundedSegment(context.work.maxHandlerMillis),
                maxPacketMillis: boundedSegment(context.work.maxPacketMillis),
            },
            buffer: {
                beforeBytes: boundedInteger(context.bufferBefore, Number.MAX_SAFE_INTEGER),
                afterBytes: boundedInteger(context.bufferAfter, Number.MAX_SAFE_INTEGER),
                maximumBytes: boundedInteger(context.maximumBufferedBytes,
                    Number.MAX_SAFE_INTEGER),
            },
            budget: {
                frameYield: context.frameBudgetYield,
                packetYield: context.packetBudgetYield,
            },
            slowPacketIds: context.slowPacketIds.slice(0,
                POLL_PHASE_PACKET_ID_SAMPLE_LIMIT),
        };
        this.pollPhaseSamplesTotal++;
        this.pollPhaseSamples.push(sample);
        this.pollPhaseSamples.sort((left, right) =>
            right.durationRawMillis - left.durationRawMillis ||
            left.pollSequence - right.pollSequence);
        if (this.pollPhaseSamples.length > POLL_PHASE_SAMPLE_LIMIT) {
            this.pollPhaseSamples.length = POLL_PHASE_SAMPLE_LIMIT;
            this.pollPhaseSamplesDropped++;
        }
        return true;
    }

    pollPhaseTelemetryResult() {
        return {
            schemaVersion: POLL_PHASE_TELEMETRY_SCHEMA_VERSION,
            segmentAccounting: POLL_PHASE_SEGMENT_ACCOUNTING,
            slowThresholdMillis: POLL_PHASE_SLOW_THRESHOLD_MILLIS,
            strictThresholdMillis: 16.7,
            sampleLimit: POLL_PHASE_SAMPLE_LIMIT,
            frameSampleLimit: POLL_PHASE_FRAME_SAMPLE_LIMIT,
            packetSampleLimit: POLL_PHASE_PACKET_SAMPLE_LIMIT,
            diagnosticOnly: true,
            strictGatesChanged: false,
            independentExecution: false,
            retention: "longest-duration-desc-sequence-asc",
            source: "BrowserMinecraftClient.poll",
            pollsTotal: this.pollPhasePollsTotal,
            slowSamplesTotal: this.pollPhaseSamplesTotal,
            pollPhaseSamplesTotal: this.pollPhaseSamplesTotal,
            retainedSampleCount: this.pollPhaseSamples.length,
            slowSamplesDropped: this.pollPhaseSamplesDropped,
            pollPhaseSamplesDropped: this.pollPhaseSamplesDropped,
            droppedSampleCount: this.pollPhaseSamplesDropped,
            samples: this.pollPhaseSamples.map((sample) => ({
                ...sample,
                segments: { ...sample.segments },
                work: { ...sample.work },
                buffer: { ...sample.buffer },
                budget: { ...sample.budget },
                slowPacketIds: [...sample.slowPacketIds],
            })),
        };
    }

    poll(schedulerCallbackSequence = null, schedulerTrigger = null) {
        if (this.closed || this.pollingPaused) return;
        const pollStartedAt = performance.now();
        const pollContext = this.resetPollPhaseContext(
            pollStartedAt, schedulerCallbackSequence, schedulerTrigger);
        this.pollPhasePollsTotal++;
        recordArrivalTrace(this.arrivalTraceRing, "poll-begin", {
            at: pollStartedAt,
            phase: this.phase,
            source: "client",
            schedulerCallbackSequence,
            pollSequence: pollContext.pollSequence,
            bufferedBytes: this.buffer.byteLength,
        });
        let checkErrorStartedAt;
        let recordPhasesStartedAt;
        let finalizeStartedAt;
        try {
            const polledAt = pollStartedAt;
            if (this.lastPollAt !== undefined) {
                this.pollGapSamples++;
                this.maxPollGapMillis = Math.max(
                    this.maxPollGapMillis,
                    polledAt - this.lastPollAt,
                );
                observeLatency(this.pollGapHistogram, polledAt - this.lastPollAt);
            }
            this.lastPollAt = polledAt;
            checkErrorStartedAt = performance.now();
            this.checkError();
            this.addPollPhaseSegment(pollContext, "checkErrorMillis",
                checkErrorStartedAt, performance.now());
            recordPhasesStartedAt = performance.now();
            this.recordPhases();
            this.addPollPhaseSegment(pollContext, "recordPhasesMillis",
                recordPhasesStartedAt, performance.now());
            let framesPolled = 0;
            let packetsRemaining = MAX_PACKETS_PER_POLL;
            // Bytes already held in the protocol cumulation have no new
            // bridge dequeue associated with them.  Clear the frame context
            // before parsing that prefix, then attach metadata for each newly
            // dequeued bridge chunk below.
            const prefixParseStartedAt = performance.now();
            this.clearArrivalFrame();
            const parsedBeforeInbound = this.parsePackets(packetsRemaining);
            this.addPollPhaseSegment(
                pollContext, "prefixParseMillis", prefixParseStartedAt,
                performance.now());
            packetsRemaining -= parsedBeforeInbound;
            let chunk;
            while (framesPolled < MAX_INBOUND_FRAMES_PER_POLL &&
                packetsRemaining > 0) {
                const bridgeDrainStartedAt = performance.now();
                const bridgePollStartedAt = bridgeDrainStartedAt;
                chunk = this.bridge.pollInbound(this.id);
                const bridgePollEndedAt = performance.now();
                const bridgePollMillis = this.addPollPhaseSegment(
                    pollContext, "bridgePollMillis", bridgePollStartedAt,
                    bridgePollEndedAt);
                pollContext.work.bridgePollCalls++;
                pollContext.work.maxBridgePollMillis = Math.max(
                    pollContext.work.maxBridgePollMillis, bridgePollMillis);
                if (chunk === null) break;
                this.setArrivalFrameFromBridge(polledAt);
                const bytes = Buffer.from(chunk);
                const decryptStartedAt = performance.now();
                const transformed = this.decipher === undefined
                    ? bytes : this.decipher.update(bytes);
                this.addPollPhaseSegment(
                    pollContext, "decryptMillis", decryptStartedAt,
                    performance.now());
                const concatStartedAt = performance.now();
                this.buffer = Buffer.concat([
                    this.buffer,
                    transformed,
                ]);
                this.addPollPhaseSegment(pollContext, "concatMillis", concatStartedAt,
                    performance.now());
                this.addPollPhaseSegment(pollContext, "bridgeDrainMillis",
                    bridgeDrainStartedAt, performance.now());
                this.inboundFrames++;
                this.inboundBytes += bytes.byteLength;
                pollContext.work.frames++;
                pollContext.work.frameBytes += bytes.byteLength;
                this.maximumBufferedBytes = Math.max(
                    this.maximumBufferedBytes,
                    this.buffer.byteLength,
                );
                pollContext.maximumBufferedBytes = Math.max(
                    pollContext.maximumBufferedBytes, this.buffer.byteLength);
                framesPolled++;
                packetsRemaining -= this.parsePackets(packetsRemaining);
            }
            const packetsParsed = MAX_PACKETS_PER_POLL - packetsRemaining;
            this.maxInboundFramesPerPoll = Math.max(this.maxInboundFramesPerPoll, framesPolled);
            this.maxPacketsPerPoll = Math.max(this.maxPacketsPerPoll, packetsParsed);
            if (framesPolled === MAX_INBOUND_FRAMES_PER_POLL &&
                this.bridge.hasPendingInbound(this.id)) {
                this.inboundFrameBudgetYields++;
                pollContext.frameBudgetYield = true;
            }
            if (packetsRemaining === 0 && this.buffer.byteLength > 0) {
                this.packetBudgetYields++;
                pollContext.packetBudgetYield = true;
            }
            finalizeStartedAt = performance.now();
        }
        catch (error) {
            this.failure ??= error;
            pollContext.outcome = "error";
        }
        finally {
            if (finalizeStartedAt === undefined) finalizeStartedAt = performance.now();
            const finalizeEndedAt = performance.now();
            this.addPollPhaseSegment(pollContext, "finalizeMillis",
                finalizeStartedAt, finalizeEndedAt);
            this.clearArrivalFrame();
            pollContext.bufferAfter = this.buffer.byteLength;
            const pollEndedAt = performance.now();
            pollContext.durationRawMillis = Math.max(0,
                pollEndedAt - pollStartedAt);
            if (this.failure !== undefined) pollContext.outcome = "error";
            recordArrivalTrace(this.arrivalTraceRing, "poll-end", {
                at: pollEndedAt,
                phase: this.phase,
                source: "client",
                schedulerCallbackSequence,
                pollSequence: pollContext.pollSequence,
                bufferedBytes: this.buffer.byteLength,
                durationMillis: pollContext.durationRawMillis,
                force: pollContext.durationRawMillis >=
                    ARRIVAL_TIMELINE_SLOW_THRESHOLD_MILLIS,
            });
            // Prelude is the bounded check/phase bookkeeping before parsing.
            // Keep it as an explicit scalar instead of double-counting the
            // wall-clock interval (which also includes scheduler admission).
            pollContext.segments.preludeMillis =
                pollContext.segments.checkErrorMillis +
                pollContext.segments.recordPhasesMillis;
            this.retainPollPhaseSample(pollContext);
            pollContext.active = false;
        }
    }

    parsePackets(maximumPackets = Number.POSITIVE_INFINITY) {
        let parsedPackets = 0;
        const pollContext = this.pollPhaseContext?.active
            ? this.pollPhaseContext : null;
        const parseStartedAt = pollContext === null ? 0 : performance.now();
        try {
        while (parsedPackets < maximumPackets) {
            const decodeStartAt = performance.now();
            const outerLength = decodeVarInt(this.buffer, 0);
            if (outerLength === undefined) return parsedPackets;
            const frameStart = outerLength.bytesRead;
            const frameEnd = frameStart + outerLength.value;
            if (frameEnd > this.buffer.byteLength) return parsedPackets;
            // Only retain a decode-begin marker once a complete frame is
            // available.  Empty-poll probes otherwise flood the 64-event ring
            // and hide the useful events surrounding a slow packet gap.
            recordArrivalTrace(this.arrivalTraceRing, "decode-begin", {
                at: decodeStartAt,
                phase: this.phase,
                source: "client",
                packetSequence: this.decodedPackets + 1,
                pollSequence: pollContext?.pollSequence,
                bufferedBytes: this.buffer.byteLength,
            });
            let frame = this.buffer.subarray(frameStart, frameEnd);
            this.buffer = this.buffer.subarray(frameEnd);
            if (this.compressionThreshold !== undefined) {
                const dataLength = decodeVarInt(frame, 0);
                if (dataLength === undefined) throw new Error("compressed frame omitted data length");
                if (dataLength.value !== 0) {
                    if (pollContext !== null) pollContext.work.compressedPackets++;
                    const inflateStartedAt = pollContext === null ? 0 : performance.now();
                    frame = inflateSync(frame.subarray(dataLength.bytesRead));
                    if (pollContext !== null) {
                        const inflateMillis = this.addPollPhaseSegment(
                            pollContext, "inflateMillis", inflateStartedAt,
                            performance.now());
                        pollContext.work.maxInflateMillis = Math.max(
                            pollContext.work.maxInflateMillis, inflateMillis);
                    }
                    if (frame.byteLength !== dataLength.value) {
                        throw new Error("compressed frame length mismatch");
                    }
                }
                else {
                    frame = frame.subarray(dataLength.bytesRead);
                }
            }
            const packetId = decodeVarInt(frame, 0);
            if (packetId === undefined) throw new Error("packet omitted id");
            this.decodedPackets++;
            parsedPackets++;
            if (pollContext !== null) pollContext.work.packetsParsed++;
            const packetAt = performance.now();
            recordArrivalTrace(this.arrivalTraceRing, "decode-end", {
                at: packetAt,
                phase: this.phase,
                source: "client",
                packetSequence: this.decodedPackets,
                packetId: packetId.value,
                pollSequence: pollContext?.pollSequence,
                bufferedBytes: this.buffer.byteLength,
            });
            if (this.lastInboundPacketAt !== undefined) {
                this.inboundPacketGapSamples++;
                this.maxInboundPacketGapMillis = Math.max(
                    this.maxInboundPacketGapMillis,
                    packetAt - this.lastInboundPacketAt,
                );
            }
            this.lastInboundPacketAt = packetAt;
            if (this.phase === "play") {
                if (this.lastPlayPacketAt !== undefined) {
                    const playGap = packetAt - this.lastPlayPacketAt;
                    this.playPacketGapSamples++;
                    this.maxPlayPacketGapMillis = Math.max(
                        this.maxPlayPacketGapMillis,
                        playGap,
                    );
                }
                this.lastPlayPacketAt = packetAt;
            }
            const phaseAtDecode = this.phase;
            const reconnectRecoveryAtDecode = this.arrivalReconnectRecoveryPending;
            if (reconnectRecoveryAtDecode) {
                if (!this.arrivalFirstDecodedAfterReconnect) {
                    this.arrivalFirstDecodedAfterReconnect = true;
                    this.arrivalReconnectFirstDecodedAt = packetAt;
                    const reconnectClock = {
                        monotonic: true,
                        clampedNegativeDeltas: false,
                    };
                    this.arrivalReconnectGapMillis = arrivalDelta(
                        this.arrivalReconnectPriorDecodeEndAt,
                        packetAt,
                        reconnectClock,
                    );
                    this.arrivalReconnectClockAnomaly ||=
                        reconnectClock.clampedNegativeDeltas;
                    this.recordArrivalPhase("first-decoded-packet", packetAt);
                    // The reconnect boundary ends at the first decoded
                    // packet.  Keeping this pending until the first chunk
                    // would label every later configuration/play gap as a
                    // reconnect gap and hide its real local segment.
                    this.arrivalReconnectRecoveryPending = false;
                }
            }
            let dispatchError;
            recordArrivalTrace(this.arrivalTraceRing, "dispatch-begin", {
                at: packetAt,
                phase: phaseAtDecode,
                source: "client",
                packetSequence: this.decodedPackets,
                packetId: packetId.value,
                pollSequence: pollContext?.pollSequence,
                bufferedBytes: this.buffer.byteLength,
            });
            try {
                this.handlePacket(packetId.value, frame.subarray(packetId.bytesRead));
            }
            catch (error) {
                dispatchError = error;
            }
            const dispatchAt = performance.now();
            recordArrivalTrace(this.arrivalTraceRing, "dispatch-end", {
                at: dispatchAt,
                phase: phaseAtDecode,
                source: "client",
                packetSequence: this.decodedPackets,
                packetId: packetId.value,
                pollSequence: pollContext?.pollSequence,
                bufferedBytes: this.buffer.byteLength,
                durationMillis: Math.max(0, dispatchAt - packetAt),
                force: dispatchAt - packetAt >= ARRIVAL_TIMELINE_SLOW_THRESHOLD_MILLIS,
            });
            if (pollContext !== null) {
                const handlerMillis = this.addPollPhaseSegment(
                    pollContext, "handlerMillis", packetAt, dispatchAt);
                const packetMillis = Math.max(0, dispatchAt - decodeStartAt);
                if (dispatchAt < decodeStartAt) pollContext.clockAnomaly = true;
                pollContext.work.maxHandlerMillis = Math.max(
                    pollContext.work.maxHandlerMillis, handlerMillis);
                pollContext.work.maxPacketMillis = Math.max(
                    pollContext.work.maxPacketMillis, packetMillis);
                if (packetMillis >= POLL_PHASE_SLOW_THRESHOLD_MILLIS &&
                    pollContext.slowPacketIds.length < POLL_PHASE_PACKET_ID_SAMPLE_LIMIT) {
                    pollContext.slowPacketIds.push(packetId.value);
                }
            }
            this.recordArrivalPacket({
                packetId: packetId.value,
                phaseAtDecode,
                decodeStartAt,
                decodeEndAt: packetAt,
                dispatchAt,
                reconnectRecoveryAtDecode,
                schedulerCallbackSequence: pollContext?.schedulerCallbackSequence,
                schedulerTrigger: pollContext?.schedulerTrigger,
                pollSequence: pollContext?.pollSequence,
            });
            if (dispatchError !== undefined) throw dispatchError;
            if (this.failure !== undefined) throw this.failure;
        }
        return parsedPackets;
        }
        finally {
            if (pollContext !== null) {
                this.addPollPhaseSegment(pollContext, "parseMillis",
                    parseStartedAt, performance.now());
            }
        }
    }

    handlePacket(packetId, payload) {
        if (this.phase === "login") {
            if (packetId === this.profile.login.clientboundDisconnect) {
                throw new Error(`${this.username}: server rejected login ${decodeReason(payload)}`);
            }
            if (packetId === this.profile.login.clientboundEncryptionRequest) {
                this.encryptionRequest = true;
                this.encryptionRequestAt ??= performance.now();
                void this.answerEncryptionRequest(payload).catch((error) => {
                    this.failure ??= error;
                });
                return;
            }
            if (packetId === this.profile.login.clientboundCompression) {
                const threshold = decodeVarInt(payload, 0);
                if (threshold === undefined || threshold.value < 0) {
                    throw new Error(`${this.username}: invalid compression threshold`);
                }
                this.compressionThreshold = threshold.value;
                return;
            }
            if (packetId === this.profile.login.clientboundLoginFinished) {
                this.loginFinished = true;
                this.loginFinishedAt ??= performance.now();
                this.phase = "configuration";
                this.recordArrivalPhase("login-done", this.loginFinishedAt);
                this.recordArrivalPhase("configuration-begin", this.loginFinishedAt);
                this.sendPacket(this.profile.login.serverboundLoginAcknowledged, Buffer.alloc(0));
                this.sendPacket(this.profile.login.serverboundHello, encodeClientInformation());
            }
            return;
        }
        if (this.phase === "configuration") {
            this.configurationPackets++;
            this.configurationPacketIds.push(packetId);
            if (this.configurationPacketIds.length > 64) this.configurationPacketIds.shift();
            if (packetId === this.profile.configuration.clientboundDisconnect) {
                throw new Error(`${this.username}: server disconnected during configuration ` +
                    decodeReason(payload));
            }
            if (packetId === this.profile.configuration.clientboundKnownPacks) {
                this.sendPacket(this.profile.configuration.serverboundSelectKnownPacks, encodeVarInt(0));
            }
            else if (packetId === this.profile.configuration.clientboundResourcePackPush) {
                if (payload.byteLength < 16) throw new Error("resource-pack push omitted UUID");
                const packId = payload.subarray(0, 16);
                const url = decodeString(payload, 16);
                const hash = decodeString(payload, url.nextOffset);
                for (const action of [3, 4, 0]) {
                    this.sendPacket(this.profile.configuration.serverboundResourcePack,
                        Buffer.concat([packId, encodeVarInt(action)]));
                }
                void hash;
            }
            else if (packetId === this.profile.configuration.clientboundShowDialog) {
                this.showDialogPackets++;
                const dialog = decodeNetworkNbt(payload);
                const prompt = inspectServerDialog(dialog);
                this.showDialogSummaries.push(prompt.summary);
                this.showDialogActions.push(...prompt.actionIds);
                if (!acceptServerPrompts) {
                    throw new Error(`${this.username}: Minecraft server requires an interactive ` +
                        `dialog (${prompt.summary})`);
                }
                const actionId = selectDialogAction(prompt.actionIds);
                if (this.acceptedDialogActions.has(actionId)) {
                    throw new Error(`${this.username}: server repeated dialog action ${actionId}`);
                }
                const inputValues = resolveDialogInputValues(prompt.inputs);
                this.sendPacket(this.profile.configuration.serverboundCustomClickAction,
                    encodeCustomClickAction(actionId, inputValues));
                this.acceptedDialogActions.add(actionId);
                this.showDialogAccepts++;
            }
            else if (packetId === this.profile.configuration.clientboundCodeOfConduct) {
                const codeOfConduct = decodeString(payload, 0);
                if (codeOfConduct.nextOffset !== payload.byteLength ||
                    codeOfConduct.value.length === 0) {
                    throw new Error(`${this.username}: malformed Code of Conduct packet`);
                }
                this.codeOfConductRequests++;
                if (this.codeOfConductRequests !== 1) {
                    throw new Error(`${this.username}: duplicate Code of Conduct packet`);
                }
                if (!acceptServerPrompts) {
                    throw new Error(`${this.username}: Minecraft server requires Code of Conduct ` +
                        "acceptance");
                }
                this.sendPacket(this.profile.configuration.serverboundAcceptCodeOfConduct,
                    Buffer.alloc(0));
                this.codeOfConductAccepts++;
            }
            else if (packetId === this.profile.configuration.clientboundKeepAlive) {
                this.sendPacket(this.profile.configuration.serverboundKeepAlive, payload);
            }
            else if (packetId === this.profile.configuration.clientboundPing) {
                this.sendPacket(this.profile.configuration.serverboundPong, payload);
            }
            else if (packetId === this.profile.configuration.clientboundFinish) {
                this.configurationFinished = true;
                this.configurationFinishedAt ??= performance.now();
                this.configurationCycles++;
                this.phase = "play";
                this.recordArrivalPhase("configuration-done", this.configurationFinishedAt);
                this.recordArrivalPhase("play-enter", this.configurationFinishedAt);
                this.sendPacket(this.profile.configuration.serverboundFinish, Buffer.alloc(0));
            }
            return;
        }
        if (this.phase !== "play") return;
        this.playPackets++;
        this.playPacketIds.push(packetId);
        if (this.playPacketIds.length > 128) this.playPacketIds.shift();
        if (packetId === this.profile.play.clientboundDisconnect) {
            this.playDisconnectPayloadBase64 = Buffer.from(payload).toString("base64");
            throw new Error(`${this.username}: server disconnected in PLAY ${decodeReason(payload)}`);
        }
        if (packetId === this.profile.play.clientboundKeepAlive) {
            this.sendPacket(this.profile.play.serverboundKeepAlive, payload);
        }
        else if (packetId === this.profile.play.clientboundPing) {
            this.sendPacket(this.profile.play.serverboundPong, payload);
        }
        else if (packetId === this.profile.play.clientboundLogin) {
            this.playLoginPackets++;
            this.playLoginAt ??= performance.now();
            this.recordArrivalPhase("play-login", this.playLoginAt);
            const initialDistances = decodeClientboundLoginDistances(payload);
            this.playLoginDistanceContracts++;
            this.playLoginChunkRadius = initialDistances.chunkRadius;
            this.playLoginSimulationDistance = initialDistances.simulationDistance;
            this.observedChunkCacheRadius = initialDistances.chunkRadius;
            this.observedSimulationDistance = initialDistances.simulationDistance;
            this.chunkCacheRadiusUpdates++;
            this.simulationDistanceUpdates++;
            if (stressMode &&
                (initialDistances.chunkRadius !== stressTarget.serverViewDistance ||
                    initialDistances.simulationDistance !== stressTarget.simulationDistance)) {
                throw new Error(
                    `${this.username}: PLAY login distance contract ` +
                    `${initialDistances.chunkRadius}/${initialDistances.simulationDistance}, ` +
                    `expected ${stressTarget.serverViewDistance}/` +
                    `${stressTarget.simulationDistance}`,
                );
            }
            if (!this.playerLoadedSent) {
                this.playerLoadedSent = true;
                this.sendPacket(this.profile.play.serverboundPlayerLoaded, Buffer.alloc(0));
            }
            this.startPlayTickLoop();
        }
        else if (packetId === this.profile.play.clientboundSetChunkCacheCenter) {
            const x = decodeVarInt(payload, 0);
            const z = x === undefined ? undefined : decodeVarInt(payload, x.bytesRead);
            if (x === undefined || z === undefined ||
                x.bytesRead + z.bytesRead !== payload.byteLength) {
                throw new Error(`${this.username}: malformed chunk-cache center`);
            }
            this.observedChunkCacheCenter = {
                x: signedVarInt(x.value),
                z: signedVarInt(z.value),
            };
            this.chunkCacheCenterUpdates++;
        }
        else if (packetId === this.profile.play.clientboundSetChunkCacheRadius) {
            const radius = decodeVarInt(payload, 0);
            if (radius === undefined || radius.bytesRead !== payload.byteLength ||
                radius.value < 2 || radius.value > 32) {
                throw new Error(`${this.username}: malformed chunk-cache radius`);
            }
            this.observedChunkCacheRadius = radius.value;
            this.chunkCacheRadiusUpdates++;
            if (stressMode && radius.value !== stressTarget.serverViewDistance) {
                throw new Error(`${this.username}: observed chunk-cache radius ` +
                    `${radius.value}, expected ${stressTarget.serverViewDistance}`);
            }
        }
        else if (packetId === this.profile.play.clientboundSetSimulationDistance) {
            const distance = decodeVarInt(payload, 0);
            if (distance === undefined || distance.bytesRead !== payload.byteLength ||
                distance.value < 2 || distance.value > 32) {
                throw new Error(`${this.username}: malformed simulation distance`);
            }
            this.observedSimulationDistance = distance.value;
            this.simulationDistanceUpdates++;
            if (stressMode && distance.value !== stressTarget.simulationDistance) {
                throw new Error(`${this.username}: observed simulation distance ` +
                    `${distance.value}, expected ${stressTarget.simulationDistance}`);
            }
        }
        else if (packetId === this.profile.play.clientboundChunkBatchStart) {
            if (payload.byteLength !== 0) {
                this.chunkBatchProtocolErrors++;
                throw new Error(`${this.username}: chunk-batch start had a payload`);
            }
            if (this.chunkBatchOpen) {
                this.chunkBatchProtocolErrors++;
                throw new Error(`${this.username}: chunk-batch start repeated before finish`);
            }
            this.chunkBatchStarts++;
            this.chunkBatchOpen = true;
            this.currentChunkBatchPackets = 0;
        }
        else if (packetId === this.profile.play.clientboundChunkBatchFinished) {
            const advertised = decodeVarInt(payload, 0);
            if (advertised === undefined || advertised.value < 0 ||
                advertised.bytesRead !== payload.byteLength) {
                this.chunkBatchProtocolErrors++;
                throw new Error(`${this.username}: malformed chunk-batch finish`);
            }
            if (!this.chunkBatchOpen) {
                this.chunkBatchProtocolErrors++;
                throw new Error(`${this.username}: chunk-batch finish arrived without start`);
            }
            this.chunkBatchFinished++;
            this.chunkBatchOpen = false;
            const countMatches = advertised.value === this.currentChunkBatchPackets;
            if (!countMatches) this.chunkBatchCountMismatches++;
            const acknowledgement = Buffer.allocUnsafe(4);
            acknowledgement.writeFloatBE(desiredChunksPerTick, 0);
            this.sendPacket(this.profile.play.serverboundChunkBatchReceived, acknowledgement);
            this.chunkBatchAcknowledgements++;
            if (this.chunkBatches.length < 64) {
                this.chunkBatches.push({
                    index: this.chunkBatchFinished,
                    advertisedChunkCount: advertised.value,
                    observedChunkPackets: this.currentChunkBatchPackets,
                    countMatches,
                    desiredChunksPerTick,
                });
            }
            this.currentChunkBatchPackets = 0;
        }
        else if (packetId === this.profile.play.clientboundChunk) {
            this.recordChunkPacket(payload);
        }
        else if (packetId === this.profile.play.clientboundStartConfiguration) {
            if (payload.byteLength !== 0) throw new Error("PLAY start-configuration had payload");
            this.reconfigurationRequests++;
            this.stopPlayTickLoop();
            this.phase = "configuration";
            this.sendPacket(this.profile.play.serverboundConfigurationAcknowledged, Buffer.alloc(0));
        }
    }

    recordChunkPacket(payload) {
        if (payload.byteLength < 8) {
            throw new Error(`${this.username}: chunk packet omitted coordinates`);
        }
        const x = payload.readInt32BE(0);
        const z = payload.readInt32BE(4);
        const key = `${x},${z}`;
        const packetAt = performance.now();
        const targetAlreadyReached = this.minimumChunkTargetReached();
        if (this.lastChunkPacketAt !== undefined && !targetAlreadyReached) {
            const chunkGap = packetAt - this.lastChunkPacketAt;
            this.preMinimumChunkPacketGapSamples++;
            this.maxPreMinimumChunkPacketGapMillis = Math.max(
                this.maxPreMinimumChunkPacketGapMillis,
                chunkGap,
            );
            observeLatency(this.preMinimumChunkGapHistogram, chunkGap);
        }
        this.lastChunkPacketAt = packetAt;
        this.chunkPackets++;
        if (this.chunkBatchOpen) this.currentChunkBatchPackets++;
        if (stressMode) {
            const distanceContractReady =
                this.observedChunkCacheRadius === stressTarget.serverViewDistance &&
                this.observedSimulationDistance === stressTarget.simulationDistance;
            if (distanceContractReady) this.stressQualifiedChunkPositions.add(key);
            else this.chunkPacketsBeforeDistanceContract++;
        }
        if (this.uniqueChunkPositions.has(key)) {
            this.duplicateChunkPackets++;
        }
        else {
            this.uniqueChunkPositions.add(key);
            if (this.chunkBounds === undefined) {
                this.chunkBounds = { minX: x, maxX: x, minZ: z, maxZ: z };
            }
            else {
                this.chunkBounds.minX = Math.min(this.chunkBounds.minX, x);
                this.chunkBounds.maxX = Math.max(this.chunkBounds.maxX, x);
                this.chunkBounds.minZ = Math.min(this.chunkBounds.minZ, z);
                this.chunkBounds.maxZ = Math.max(this.chunkBounds.maxZ, z);
            }
        }
        const firstChunk = this.firstChunkAt === undefined;
        this.firstChunkAt ??= packetAt;
        if (firstChunk && !this.arrivalFirstChunkRecorded) {
            this.arrivalFirstChunkRecorded = true;
            this.recordArrivalPhase("first-chunk", packetAt);
            this.arrivalReconnectRecoveryPending = false;
        }
        if (this.minimumChunkTargetReached()) {
            const minimumChunks = this.minimumChunksAt === undefined;
            this.minimumChunksAt ??= packetAt;
            if (minimumChunks && !this.arrivalMinimumChunksRecorded) {
                this.arrivalMinimumChunksRecorded = true;
                this.recordArrivalPhase("minimum-chunks", packetAt);
            }
        }
    }

    minimumChunkTargetReached() {
        return stressMode
            ? this.observedChunkCacheRadius === serverViewDistance &&
                this.observedSimulationDistance === stressTarget.simulationDistance &&
                this.stressQualifiedChunkPositions.size >= minimumChunkPackets
            : this.chunkPackets >= minimumChunkPackets;
    }

    chunkWindowResult() {
        const bounds = this.chunkBounds === undefined ? null : { ...this.chunkBounds };
        const spanX = bounds === null ? 0 : bounds.maxX - bounds.minX + 1;
        const spanZ = bounds === null ? 0 : bounds.maxZ - bounds.minZ + 1;
        return {
            configuredClientViewDistance: clientViewDistance,
            configuredServerViewDistance: serverViewDistance,
            effectiveRadius: effectiveChunkRadius,
            maximumUniqueChunkCapacity,
            observedChunkCacheCenter: this.observedChunkCacheCenter === undefined
                ? null : { ...this.observedChunkCacheCenter },
            observedChunkCacheRadius: this.observedChunkCacheRadius ?? null,
            observedSimulationDistance: this.observedSimulationDistance ?? null,
            observedMaximumUniqueChunkCapacity:
                this.observedChunkCacheRadius === undefined
                    ? null : chunkTrackingCapacity(this.observedChunkCacheRadius),
            chunkCacheCenterUpdates: this.chunkCacheCenterUpdates,
            chunkCacheRadiusUpdates: this.chunkCacheRadiusUpdates,
            simulationDistanceUpdates: this.simulationDistanceUpdates,
            uniqueChunkPositions: this.uniqueChunkPositions.size,
            uniqueChunkPositionsTowardTarget: stressMode
                ? this.stressQualifiedChunkPositions.size
                : this.uniqueChunkPositions.size,
            chunkPacketsBeforeDistanceContract: this.chunkPacketsBeforeDistanceContract,
            duplicateChunkPackets: this.duplicateChunkPackets,
            bounds,
            spanX,
            spanZ,
            observedRadiusLowerBound: Math.ceil(Math.max(0, Math.max(spanX, spanZ) - 1) / 2),
        };
    }

    chunkBatchResult() {
        return {
            clientboundStartPacketId: this.profile.play.clientboundChunkBatchStart,
            clientboundFinishedPacketId: this.profile.play.clientboundChunkBatchFinished,
            serverboundAcknowledgementPacketId:
                this.profile.play.serverboundChunkBatchReceived,
            desiredChunksPerTick,
            starts: this.chunkBatchStarts,
            finished: this.chunkBatchFinished,
            acknowledgements: this.chunkBatchAcknowledgements,
            protocolErrors: this.chunkBatchProtocolErrors,
            countMismatches: this.chunkBatchCountMismatches,
            openAtSnapshot: this.chunkBatchOpen,
            retainedBatches: this.chunkBatches.map((batch) => ({ ...batch })),
        };
    }

    async answerEncryptionRequest(payload) {
        const serverId = decodeString(payload, 0);
        const publicKey = decodeByteArray(payload, serverId.nextOffset);
        const challenge = decodeByteArray(payload, publicKey.nextOffset);
        if (challenge.nextOffset >= payload.byteLength || payload[challenge.nextOffset] === 0) {
            throw new Error(`${this.username}: server disabled session authentication`);
        }
        const secret = randomBytes(16);
        this.secretFingerprint = createHash("sha256").update(secret).digest("hex");
        const serverHash = minecraftServerHash(serverId.value, secret, publicKey.value);
        const joinResponse = await fetch(new URL(
            "session/minecraft/join", `${this.sessionUrl}/`), {
            method: "POST",
            headers: { "content-type": "application/json" },
            body: JSON.stringify({
                accessToken: this.accessToken,
                selectedProfile: this.profileId,
                serverId: serverHash,
            }),
        });
        if (!joinResponse.ok) throw new Error(`${this.username}: session join HTTP ${joinResponse.status}`);
        this.sessionJoin = true;
        this.sessionJoinAt ??= performance.now();
        const encryptedSecret = publicEncrypt({
            key: publicKey.value,
            format: "der",
            type: "spki",
            padding: cryptoConstants.RSA_PKCS1_PADDING,
        }, secret);
        const encryptedChallenge = publicEncrypt({
            key: publicKey.value,
            format: "der",
            type: "spki",
            padding: cryptoConstants.RSA_PKCS1_PADDING,
        }, challenge.value);
        this.rsaSecretEncrypted = encryptedSecret.byteLength > 0;
        this.rsaChallengeEncrypted = encryptedChallenge.byteLength > 0;
        this.sendRaw(encodePacket(this.profile.login.serverboundKey, Buffer.concat([
            encodeByteArray(encryptedSecret), encodeByteArray(encryptedChallenge),
        ])));
        this.cipher = createCipheriv("aes-128-cfb8", secret, secret);
        this.decipher = createDecipheriv("aes-128-cfb8", secret, secret);
        this.aesCfb8Enabled = true;
    }

    sendPacket(id, payload) {
        this.sendRaw(encodePacket(id, payload, this.compressionThreshold));
    }

    sendRaw(packet) {
        const wire = this.cipher === undefined ? packet : this.cipher.update(packet);
        if (!this.bridge.send(this.id, new Uint8Array(wire))) {
            throw new Error(`${this.username}: browser transport rejected outbound frame`);
        }
        this.outboundFrames++;
        this.outboundBytes += wire.byteLength;
    }

    recordPlayTickTiming({
        dueAtMillis,
        tickAtMillis,
        previousGapMillis,
        schedulerCallbackSequence = null,
        trigger = "direct",
        skipPeriods = 0,
        skippedPeriodsTotal = this.playTickSkippedPeriods,
        schedulerPhase = null,
    }) {
        const finiteMillis = (value, allowNull = false) => {
            if (allowNull && (value === null || value === undefined)) return null;
            const number = Number(value);
            return Number.isFinite(number) && number >= 0 ? number : null;
        };
        const nonNegativeInteger = (value) => Number.isSafeInteger(value) && value >= 0
            ? value : 0;
        const ring = this.playTickTimingRing;
        const sample = {
            schemaVersion: PLAY_TICK_TIMING_TELEMETRY_SCHEMA_VERSION,
            sequence: ++this.playTickTimingSequence,
            clientId: this.id,
            wave: this.wave,
            dueAtMillis: finiteMillis(dueAtMillis),
            tickAtMillis: finiteMillis(tickAtMillis),
            previousGapMillis: finiteMillis(previousGapMillis, true),
            schedulerCallbackSequence: Number.isSafeInteger(
                schedulerCallbackSequence) ? schedulerCallbackSequence : null,
            trigger: typeof trigger === "string" ? trigger : "direct",
            skipPeriods: nonNegativeInteger(skipPeriods),
            skippedPeriodsTotal: nonNegativeInteger(skippedPeriodsTotal),
            phase: typeof this.phase === "string" ? this.phase : null,
            schedulerPhase: typeof schedulerPhase === "string"
                ? schedulerPhase : null,
            strictThresholdMillis: 16.7,
            strictGatesChanged: false,
            diagnosticOnly: true,
            independentExecution: false,
        };
        ring.total++;
        const index = ring.nextIndex;
        if (ring.samples[index] !== undefined) ring.dropped++;
        ring.samples[index] = sample;
        ring.nextIndex = (index + 1) % PLAY_TICK_TIMING_SAMPLE_LIMIT;
        ring.count = Math.min(PLAY_TICK_TIMING_SAMPLE_LIMIT, ring.count + 1);
    }

    playTickTimingResult() {
        const ring = this.playTickTimingRing;
        const firstIndex = ring.count < PLAY_TICK_TIMING_SAMPLE_LIMIT
            ? 0 : ring.nextIndex;
        const samples = [];
        for (let offset = 0; offset < ring.count; offset++) {
            const sample = ring.samples[
                (firstIndex + offset) % PLAY_TICK_TIMING_SAMPLE_LIMIT];
            if (sample !== undefined) samples.push({ ...sample });
        }
        return {
            schemaVersion: PLAY_TICK_TIMING_TELEMETRY_SCHEMA_VERSION,
            sampleLimit: PLAY_TICK_TIMING_SAMPLE_LIMIT,
            retention: PLAY_TICK_TIMING_RETENTION,
            strictThresholdMillis: 16.7,
            strictGatesChanged: false,
            diagnosticOnly: true,
            independentExecution: false,
            samplesTotal: ring.total,
            playTickTimingSamplesTotal: ring.total,
            retainedSampleCount: samples.length,
            samplesDropped: ring.dropped,
            playTickTimingSamplesDropped: ring.dropped,
            droppedSampleCount: ring.dropped,
            samples,
        };
    }

    servicePlayTick(now = performance.now(), schedulerCallbackSequence = null,
        schedulerTrigger = "direct", schedulerPhase = null) {
        if (!this.playTickActive || this.closed || this.failure !== undefined ||
            this.phase !== "play") return false;
        if (this.pollingPaused) {
            this.playTickSuspended = true;
            this.nextPlayTickDueAt = undefined;
            return false;
        }
        if (this.playTickSuspended) {
            // A transport-drop pause is intentional. Do not charge the paused
            // interval to the strict PLAY tick histogram when the channel is
            // resumed by a caller.
            this.playTickSuspended = false;
            this.lastPlayTickAt = undefined;
            this.nextPlayTickDueAt = now;
        }
        const dueAt = Number(this.nextPlayTickDueAt);
        if (!Number.isFinite(dueAt) || now < dueAt) return false;
        try {
            const tickAt = performance.now();
            const previousGapMillis = this.lastPlayTickAt === undefined
                ? null : tickAt - this.lastPlayTickAt;
            if (this.lastPlayTickAt !== undefined) {
                this.playTickGapSamples++;
                this.maxPlayTickGapMillis = Math.max(
                    this.maxPlayTickGapMillis,
                    previousGapMillis,
                );
                observeLatency(this.playTickGapHistogram, previousGapMillis);
            }
            this.lastPlayTickAt = tickAt;
            this.sendPacket(this.profile.play.serverboundClientTickEnd, Buffer.alloc(0));
            this.playTickPackets++;
            // Preserve the 50 ms cadence without catch-up bursts after a long
            // event-loop turn. Count skipped periods for diagnostics and
            // re-anchor the next due time to the observed clock.
            const periodMillis = 50;
            const nextDueAt = dueAt + periodMillis;
            let skipPeriods = 0;
            if (nextDueAt < tickAt) {
                skipPeriods = Math.max(1,
                    Math.ceil((tickAt - nextDueAt) / periodMillis));
                this.playTickSkippedPeriods += skipPeriods;
                this.nextPlayTickDueAt = tickAt + periodMillis;
            }
            else {
                this.nextPlayTickDueAt = nextDueAt;
            }
            this.recordPlayTickTiming({
                dueAtMillis: dueAt,
                tickAtMillis: tickAt,
                previousGapMillis,
                schedulerCallbackSequence,
                trigger: schedulerTrigger,
                skipPeriods,
                skippedPeriodsTotal: this.playTickSkippedPeriods,
                schedulerPhase,
            });
            return true;
        }
        catch (error) {
            this.failure ??= error;
            return false;
        }
    }

    startPlayTickLoop() {
        if (this.playTickActive) return;
        this.playTickActive = true;
        this.playTickSuspended = false;
        this.nextPlayTickDueAt = performance.now();
        // Preserve the vanilla immediate first tick; subsequent ticks are
        // serviced by the shared fair poll scheduler.
        this.servicePlayTick(performance.now(), null, "play-start", "play-start");
    }

    stopPlayTickLoop() {
        this.playTickActive = false;
        this.playTickSuspended = false;
        this.nextPlayTickDueAt = undefined;
        // A stopped/reconnected client starts a fresh cadence.  Retaining the
        // previous timestamp would charge the transport-drop interval to the
        // next PLAY tick gap and create a synthetic strict-latency violation.
        this.lastPlayTickAt = undefined;
        // Kept as an explicit undefined compatibility marker for diagnostics;
        // no per-client timer is allocated by the shared scheduler.
        this.playTickTimer = undefined;
    }

    checkError() {
        const error = this.bridge.pollError(this.id);
        if (error) this.failure ??= new Error(`${this.username}: ${error}`);
        if (this.failure !== undefined) throw this.failure;
    }

    recordPhases() {
        const source = Array.isArray(this.stats.connectPhases)
            ? this.stats.connectPhases : [];
        const sourceReplaced = this.phaseScanSource !== source;
        const sourceTruncated = source.length < this.phaseScanIndex;
        if (sourceReplaced || sourceTruncated) {
            // A replaced/truncated source invalidates the cursor. Rebuild the
            // compatibility array from the current source while retaining the
            // WeakSet so previously observed objects are not emitted twice.
            this.phaseScanSource = source;
            this.phaseScanIndex = 0;
            this.connectPhases = [];
        }
        for (let index = this.phaseScanIndex; index < source.length; index++) {
            const event = source[index];
            if (event === null || typeof event !== "object" || event.id !== this.id)
                continue;
            if (!this.arrivalSeenPhaseEvents.has(event)) {
                this.arrivalSeenPhaseEvents.add(event);
                const bridgeElapsed = Number(event.elapsedMillis);
                const bridgeMonotonicAt = Number.isFinite(this.connectStartedAt) &&
                    Number.isFinite(bridgeElapsed)
                    ? this.connectStartedAt + Math.max(0, bridgeElapsed) : null;
                this.recordArrivalPhase(event.phase, bridgeMonotonicAt, {
                    wallAt: Number.isFinite(event.at) ? event.at : null,
                    elapsedMillis: bridgeElapsed,
                    source: "BrowserWebSocketChannel.connect-phase",
                });
            }
            this.connectPhases.push(event);
        }
        this.phaseScanSource = source;
        this.phaseScanIndex = source.length;
    }

    close(reason = "final-close") {
        if (this.closed) return;
        this.closed = true;
        this.stopPlayTickLoop();
        this.closedAt = performance.now();
        this.closeReason = reason;
        this.recordArrivalPhase("close", this.closedAt, { detail: reason });
        try { this.bridge.close(this.id); } catch {}
    }

    pausePollingForTransportDrop(dropAt = performance.now()) {
        assert.equal(this.closed, false, "cannot pause a closed reconnect client");
        this.pollingPaused = true;
        this.playTickSuspended = true;
        this.nextPlayTickDueAt = undefined;
        this.lastPlayTickAt = undefined;
        this.arrivalIntentionalDropPending = true;
        this.arrivalIntentionalTransportDropCount++;
        // `arrivalReconnectDropAt` is the immutable boundary seed for this
        // lifecycle.  Do not overwrite it when a later reconnect wave drops
        // this client; the next lifecycle receives the current `dropAt`
        // explicitly from createClients().
        this.recordArrivalPhase("disconnect", dropAt, {
            intentional: true,
            source: "browser-full-path-harness",
        });
        this.recordArrivalPhase("synthetic-transport-drop", dropAt, {
            intentional: true,
            source: "browser-full-path-harness",
        });
    }

    diagnostics() {
        return {
            username: this.username,
            id: this.id,
            wave: this.wave,
            phase: this.phase,
            encryptionRequest: this.encryptionRequest,
            rsaSecretEncrypted: this.rsaSecretEncrypted,
            rsaChallengeEncrypted: this.rsaChallengeEncrypted,
            aesCfb8Enabled: this.aesCfb8Enabled,
            sessionJoin: this.sessionJoin,
            compressionThreshold: this.compressionThreshold ?? null,
            loginFinished: this.loginFinished,
            configurationFinished: this.configurationFinished,
            configurationCycles: this.configurationCycles,
            configurationPacketIds: [...this.configurationPacketIds],
            showDialogPackets: this.showDialogPackets,
            showDialogAccepts: this.showDialogAccepts,
            showDialogActions: [...this.showDialogActions],
            showDialogSummaries: [...this.showDialogSummaries],
            codeOfConductRequests: this.codeOfConductRequests,
            codeOfConductAccepts: this.codeOfConductAccepts,
            reconfigurationRequests: this.reconfigurationRequests,
            playPackets: this.playPackets,
            playPacketIds: [...this.playPacketIds],
            playDisconnectPayloadBase64: this.playDisconnectPayloadBase64 ?? null,
            playLoginPackets: this.playLoginPackets,
            playLoginDistanceContracts: this.playLoginDistanceContracts,
            playLoginChunkRadius: this.playLoginChunkRadius ?? null,
            playLoginSimulationDistance: this.playLoginSimulationDistance ?? null,
            chunkPackets: this.chunkPackets,
            uniqueChunkPositions: this.uniqueChunkPositions.size,
            chunkWindow: this.chunkWindowResult(),
            chunkBatch: this.chunkBatchResult(),
            playTickPackets: this.playTickPackets,
            playTickActive: this.playTickActive,
            playTickSuspended: this.playTickSuspended,
            nextPlayTickDueAt: Number.isFinite(this.nextPlayTickDueAt)
                ? Number(this.nextPlayTickDueAt.toFixed(3)) : null,
            playTickSkippedPeriods: this.playTickSkippedPeriods,
            bufferedBytes: this.buffer.byteLength,
            minimumChunkPackets,
            inboundFrames: this.inboundFrames,
            inboundBytes: this.inboundBytes,
            outboundFrames: this.outboundFrames,
            outboundBytes: this.outboundBytes,
            decodedPackets: this.decodedPackets,
            maximumBufferedBytes: this.maximumBufferedBytes,
            arrivalTimeline: this.arrivalTimelineResult(),
            performance: this.performanceResult(),
            closeReason: this.closeReason ?? null,
            pollingPaused: this.pollingPaused,
            failure: this.failure === undefined ? null : String(this.failure),
        };
    }

    result() {
        return {
            ...this.diagnostics(),
            onlineEncryption: this.onlineEncryptionResult(),
            rsa: {
                requested: this.encryptionRequest,
                secretEncrypted: this.rsaSecretEncrypted,
                challengeEncrypted: this.rsaChallengeEncrypted,
                padding: "RSA_PKCS1_PADDING",
            },
            aes: {
                cipher: "aes-128-cfb8",
                enabled: this.aesCfb8Enabled,
                iv: "shared-secret",
                secretFingerprint: this.secretFingerprint ?? null,
            },
            onlineMode: this.encryptionRequest,
            configurationCycles: this.configurationCycles,
            reconfigurationRequests: this.reconfigurationRequests,
            playLoginPackets: this.playLoginPackets,
            chunkPackets: this.chunkPackets,
            timing: this.timingResult(),
            traffic: {
                inboundFrames: this.inboundFrames,
                inboundBytes: this.inboundBytes,
                outboundFrames: this.outboundFrames,
                outboundBytes: this.outboundBytes,
                decodedPackets: this.decodedPackets,
                maximumBufferedBytes: this.maximumBufferedBytes,
                packetsPerSecondToMinimumChunks: ratePerSecond(
                    this.decodedPackets,
                    elapsedMillis(this.connectStartedAt, this.minimumChunksAt),
                ),
                inboundBytesPerSecondToMinimumChunks: ratePerSecond(
                    this.inboundBytes,
                    elapsedMillis(this.connectStartedAt, this.minimumChunksAt),
                ),
            },
            performance: this.performanceResult(),
        };
    }

    onlineEncryptionResult() {
        return {
            required: true,
            encryptionRequest: this.encryptionRequest,
            sessionJoin: this.sessionJoin,
            rsaSecretEncrypted: this.rsaSecretEncrypted,
            rsaChallengeEncrypted: this.rsaChallengeEncrypted,
            aesCfb8Enabled: this.aesCfb8Enabled,
            secretFingerprint: this.secretFingerprint ?? null,
            failClosed: this.failure === undefined && this.encryptionRequest &&
                this.sessionJoin && this.rsaSecretEncrypted &&
                this.rsaChallengeEncrypted && this.aesCfb8Enabled &&
                typeof this.secretFingerprint === "string" &&
                /^[0-9a-f]{64}$/u.test(this.secretFingerprint),
        };
    }

    timingResult() {
        return {
            relayConnectedMillis:
                elapsedMillis(this.connectStartedAt, this.relayConnectedAt),
            relayToHandshakeMillis:
                elapsedMillis(this.relayConnectedAt, this.handshakeSentAt),
            handshakeToEncryptionRequestMillis:
                elapsedMillis(this.handshakeSentAt, this.encryptionRequestAt),
            encryptionRequestToSessionJoinMillis:
                elapsedMillis(this.encryptionRequestAt, this.sessionJoinAt),
            handshakeToLoginFinishedMillis:
                elapsedMillis(this.handshakeSentAt, this.loginFinishedAt),
            loginToConfigurationFinishedMillis:
                elapsedMillis(this.loginFinishedAt, this.configurationFinishedAt),
            configurationToPlayLoginMillis:
                elapsedMillis(this.configurationFinishedAt, this.playLoginAt),
            playLoginToFirstChunkMillis:
                elapsedMillis(this.playLoginAt, this.firstChunkAt),
            firstChunkToMinimumChunksMillis:
                elapsedMillis(this.firstChunkAt, this.minimumChunksAt),
            connectToFirstChunkMillis:
                elapsedMillis(this.connectStartedAt, this.firstChunkAt),
            connectToMinimumChunksMillis:
                elapsedMillis(this.connectStartedAt, this.minimumChunksAt),
            connectedLifetimeMillis:
                elapsedMillis(this.connectStartedAt, this.closedAt),
        };
    }

    performanceResult() {
        return {
            pollGapSamples: this.pollGapSamples,
            maxPollGapMillis: Number(this.maxPollGapMillis.toFixed(3)),
            maxPollGapRawMillis: this.maxPollGapMillis,
            pollGapHistogram: latencyHistogramResult(this.pollGapHistogram),
            inboundPacketGapSamples: this.inboundPacketGapSamples,
            maxInboundPacketGapMillis: Number(this.maxInboundPacketGapMillis.toFixed(3)),
            maxInboundPacketGapRawMillis: this.maxInboundPacketGapMillis,
            playPacketGapSamples: this.playPacketGapSamples,
            maxPlayPacketGapMillis: Number(this.maxPlayPacketGapMillis.toFixed(3)),
            maxPlayPacketGapRawMillis: this.maxPlayPacketGapMillis,
            preMinimumChunkPacketGapSamples: this.preMinimumChunkPacketGapSamples,
            maxPreMinimumChunkPacketGapMillis:
                Number(this.maxPreMinimumChunkPacketGapMillis.toFixed(3)),
            maxPreMinimumChunkPacketGapRawMillis: this.maxPreMinimumChunkPacketGapMillis,
            preMinimumChunkGapHistogram:
                latencyHistogramResult(this.preMinimumChunkGapHistogram),
            playTickGapSamples: this.playTickGapSamples,
            maxPlayTickGapMillis: Number(this.maxPlayTickGapMillis.toFixed(3)),
            maxPlayTickGapRawMillis: this.maxPlayTickGapMillis,
            playTickGapHistogram: latencyHistogramResult(this.playTickGapHistogram),
            playTickTiming: this.playTickTimingResult(),
            maximumBufferedBytes: this.maximumBufferedBytes,
            inboundDrainBudget: {
                maxFramesPerPoll: MAX_INBOUND_FRAMES_PER_POLL,
                maxPacketsPerPoll: MAX_PACKETS_PER_POLL,
                observedMaxFramesPerPoll: this.maxInboundFramesPerPoll,
                observedMaxPacketsPerPoll: this.maxPacketsPerPoll,
                frameBudgetYields: this.inboundFrameBudgetYields,
                packetBudgetYields: this.packetBudgetYields,
            },
            pollPhaseTelemetry: this.pollPhaseTelemetryResult(),
        };
    }

    dropTimingResult(dropAt) {
        return {
            dropToRelayConnectedMillis: elapsedMillis(dropAt, this.relayConnectedAt),
            dropToHandshakeSentMillis: elapsedMillis(dropAt, this.handshakeSentAt),
            dropToEncryptionRequestMillis:
                elapsedMillis(dropAt, this.encryptionRequestAt),
            dropToSessionJoinMillis: elapsedMillis(dropAt, this.sessionJoinAt),
            dropToLoginFinishedMillis: elapsedMillis(dropAt, this.loginFinishedAt),
            dropToConfigurationFinishedMillis:
                elapsedMillis(dropAt, this.configurationFinishedAt),
            dropToPlayLoginMillis: elapsedMillis(dropAt, this.playLoginAt),
            dropToFirstChunkMillis: elapsedMillis(dropAt, this.firstChunkAt),
            dropToMinimumChunksMillis: elapsedMillis(dropAt, this.minimumChunksAt),
        };
    }
}

function assertOnlineEncryption(client, label) {
    const observed = client.onlineEncryptionResult();
    assert.equal(observed.failClosed, true,
        `${label} did not complete online-mode RSA/AES encryption fail-closed: ` +
        JSON.stringify(observed));
    assert.equal(client.failure, undefined,
        `${label} reported a client failure after encrypted login`);
}

function assertClientSecurity(client, label) {
    if (!externalMode && !client.encryptionRequest) {
        throw new Error(`${label}: local full-path server did not request online encryption`);
    }
    if (client.encryptionRequest) assertOnlineEncryption(client, label);
}

function elapsedMillis(start, end) {
    if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return null;
    return Number((end - start).toFixed(3));
}

async function delayAtLeast(durationMillis) {
    const requested = Math.max(0, Number(durationMillis) || 0);
    const startedAt = performance.now();
    while (true) {
        const remaining = requested - (performance.now() - startedAt);
        if (remaining <= 0) return;
        // Node timers may wake a fraction of a millisecond early. Re-check the
        // monotonic deadline instead of letting a strict 15 s soak fail on
        // timer quantization (for example, 14999.936 ms).
        await delay(Math.max(1, Math.ceil(remaining)));
    }
}

function ratePerSecond(value, millis) {
    if (!Number.isFinite(value) || !Number.isFinite(millis) || millis <= 0) return null;
    return Number((value * 1000 / millis).toFixed(3));
}

function timestampSpread(values) {
    const finite = values.filter(Number.isFinite);
    if (finite.length === 0) return null;
    return Number((Math.max(...finite) - Math.min(...finite)).toFixed(3));
}

function sessionRuntimeSnapshot(state) {
    return {
        joins: state.joins.length,
        hasJoined: state.hasJoined.length,
        publicKeyRequests: state.publicKeyRequests,
    };
}

function browserRuntimeSnapshot(runtime) {
    const activeEntries = [...runtime.bridge.channels.values()];
    const sampledAt = performance.now();
    const activeHighWatermarkLongestMillis = activeEntries.reduce((longest, entry) =>
        entry.highWatermarkStartedAt > 0
            ? Math.max(longest, sampledAt - entry.highWatermarkStartedAt)
            : longest, 0);
    const snapshot = {
        activeChannels: runtime.bridge.channels.size,
        flowPausedChannels: activeEntries.filter((entry) => entry.flowPaused).length,
        decodeFlowPausedChannels:
            activeEntries.filter((entry) => entry.decodeFlowPaused).length,
        activeWebSockets: runtime.wsStats.sockets.size,
        webSocketConnections: runtime.wsStats.connections,
        queuedBytes: runtime.stats.queuedBytes,
        queuedFrames: runtime.stats.queuedFrames,
        peakQueuedFrames: runtime.stats.peakQueuedFrames,
        inboundQueuedBytes: runtime.stats.inboundQueuedBytes,
        peakInboundQueuedBytes: runtime.stats.peakInboundQueuedBytes,
        maxDecodedSliceBacklog: runtime.stats.maxDecodedSliceBacklog,
        longestEventLoopGapMillis: runtime.stats.longestEventLoopGapMillis,
        // These values are emitted by the Java global pump when the generated
        // BrowserWebSocketChannel includes that telemetry. Keep null for an
        // older classlib/runtime so evidence distinguishes absent from zero.
        pumpAllTurns: Object.prototype.hasOwnProperty.call(runtime.stats,
            "pumpAllTurns") ? runtime.stats.pumpAllTurns : null,
        pumpAllChannelsVisited: Object.prototype.hasOwnProperty.call(runtime.stats,
            "pumpAllChannelsVisited") ? runtime.stats.pumpAllChannelsVisited : null,
        pumpAllBudgetYields: Object.prototype.hasOwnProperty.call(runtime.stats,
            "pumpAllBudgetYields") ? runtime.stats.pumpAllBudgetYields : null,
        pumpAllMaxTurnMillis: Object.prototype.hasOwnProperty.call(runtime.stats,
            "pumpAllMaxTurnMillis") ? runtime.stats.pumpAllMaxTurnMillis : null,
        pumpAllMaxChannelsPerTurn: Object.prototype.hasOwnProperty.call(runtime.stats,
            "pumpAllMaxChannelsPerTurn") ? runtime.stats.pumpAllMaxChannelsPerTurn : null,
        pumpAllLastTurnMillis: Object.prototype.hasOwnProperty.call(runtime.stats,
            "pumpAllLastTurnMillis") ? runtime.stats.pumpAllLastTurnMillis : null,
        pumpAllLastChannelsVisited: Object.prototype.hasOwnProperty.call(runtime.stats,
            "pumpAllLastChannelsVisited") ? runtime.stats.pumpAllLastChannelsVisited : null,
        outboundTurns: runtime.stats.outboundTurns,
        outboundTurnFrames: runtime.stats.outboundTurnFrames,
        outboundTurnBytes: runtime.stats.outboundTurnBytes,
        outboundYields: runtime.stats.outboundYields,
        maxOutboundTurnMillis: runtime.stats.maxOutboundTurnMillis,
        webSocketBackpressureWaits: runtime.stats.webSocketBackpressureWaits,
        outboundBackpressureDeferrals: runtime.stats.outboundBackpressureDeferrals,
        outboundImmediateFlushes: runtime.stats.outboundImmediateFlushes ?? null,
        outboundTimerFlushes: runtime.stats.outboundTimerFlushes ?? null,
        outboundContinuationTimers: runtime.stats.outboundContinuationTimers ?? null,
        outboundMessageChannelFlushes:
            runtime.stats.outboundMessageChannelFlushes ?? null,
        outboundMessageChannelCallbacks:
            runtime.stats.outboundMessageChannelCallbacks ?? null,
        outboundContinuationMacrotasks:
            runtime.stats.outboundContinuationMacrotasks ?? null,
        outboundFlushWaitSamples: runtime.stats.outboundFlushWaitSamples ?? null,
        maxOutboundFlushWaitMillis: runtime.stats.maxOutboundFlushWaitMillis ?? null,
        outboundEmptyTurns: runtime.stats.outboundEmptyTurns ?? null,
        inboundImmediateSchedules: runtime.stats.inboundImmediateSchedules ?? null,
        inboundRafSchedules: runtime.stats.inboundRafSchedules ?? null,
        inboundTimerSchedules: runtime.stats.inboundTimerSchedules ?? null,
        inboundSliceScheduleWaitSamples:
            runtime.stats.inboundSliceScheduleWaitSamples ?? null,
        maxInboundSliceScheduleWaitMillis:
            runtime.stats.maxInboundSliceScheduleWaitMillis ?? null,
        longestInboundSlicePumpMillis: runtime.stats.longestInboundSlicePumpMillis,
        longestPumpMillis: runtime.stats.longestPumpMillis,
        peakPumpMillis: runtime.stats.peakPumpMillis,
        maxDecoderCumulationBytes: runtime.stats.maxDecoderCumulationBytes,
        maxDecodedPacketQueue: runtime.stats.maxDecodedPacketQueue,
        decodedSliceBacklogPauses: runtime.stats.decodedSliceBacklogPauses,
        decodedSliceBacklogResumes: runtime.stats.decodedSliceBacklogResumes,
        inlineDecodedPackets: runtime.stats.inlineDecodedPackets,
        queuedPacketHandleSamples: runtime.stats.queuedPacketHandleSamples ?? null,
        maxQueuedPacketHandleMillis: runtime.stats.maxQueuedPacketHandleMillis ?? null,
        maxQueuedPacketHandleType:
            typeof runtime.stats.maxQueuedPacketHandleType === "string"
                ? runtime.stats.maxQueuedPacketHandleType
                : null,
        slowQueuedPacketEventSequence:
            runtime.stats.slowQueuedPacketEventSequence ?? null,
        slowQueuedPacketEventsDropped:
            runtime.stats.slowQueuedPacketEventsDropped ?? null,
        slowQueuedPacketEvents: Array.isArray(runtime.stats.slowQueuedPacketEvents)
            ? runtime.stats.slowQueuedPacketEvents
                .slice(-BROWSER_QUEUED_PACKET_SLOW_EVENT_LIMIT)
                .map((event) => ({ ...event }))
            : null,
        highWatermarkDurationMillis: runtime.stats.highWatermarkDurationMillis,
        longestHighWatermarkMillis: runtime.stats.longestHighWatermarkMillis,
        activeHighWatermarkLongestMillis,
        highWatermarkEventSequence: runtime.stats.highWatermarkEventSequence ?? null,
        highWatermarkEventsDropped: runtime.stats.highWatermarkEventsDropped ?? null,
        highWatermarkEvents: Array.isArray(runtime.stats.highWatermarkEvents)
            ? runtime.stats.highWatermarkEvents.map((event) => ({ ...event }))
            : null,
        flowPauses: runtime.stats.flowPauses,
        flowResumes: runtime.stats.flowResumes,
        activeRelayTargetLeases: runtime.stats.activeRelayTargetLeases,
        relayTargetAttestationFailures: runtime.stats.relayTargetAttestationFailures,
        arrivalTimeline: runtime.arrivalTelemetry?.evidence?.() ?? null,
    };
    for (const name of BROWSER_RUNTIME_CLEANUP_GAUGES) {
        if (Object.prototype.hasOwnProperty.call(runtime.stats, name)) {
            snapshot[name] = runtime.stats[name];
        }
    }
    return snapshot;
}

function browserGlobalPumpTelemetryEvidence(snapshot) {
    const observed = Object.fromEntries(BROWSER_GLOBAL_PUMP_TELEMETRY_FIELDS.map((name) => [
        name,
        snapshot !== undefined && Object.prototype.hasOwnProperty.call(snapshot, name)
            ? snapshot[name]
            : null,
    ]));
    const missing = BROWSER_GLOBAL_PUMP_TELEMETRY_FIELDS.filter((name) =>
        observed[name] === null || observed[name] === undefined);
    return {
        source: "BrowserWebSocketChannel.pumpAllAndReportProgress",
        fields: [...BROWSER_GLOBAL_PUMP_TELEMETRY_FIELDS],
        observed,
        missing,
        available: missing.length === 0,
        maxTotalMillis: BROWSER_GLOBAL_PUMP_MAX_TOTAL_MILLIS,
        note: missing.length === 0
            ? "Java global-pump telemetry observed directly"
            : "Generated class omitted one or more Java global-pump fields; null preserved",
    };
}

function browserInboundFlowEvidence(snapshot, label) {
    const observed = Object.fromEntries(BROWSER_INBOUND_FLOW_EVIDENCE_FIELDS.map((name) => [
        name,
        snapshot !== undefined && Object.prototype.hasOwnProperty.call(snapshot, name)
            ? snapshot[name]
            : null,
    ]));
    const missing = BROWSER_INBOUND_FLOW_EVIDENCE_FIELDS.filter((name) => {
        const value = observed[name];
        return BROWSER_INBOUND_FLOW_DURATION_FIELDS.has(name)
            ? !Number.isFinite(value) || value < 0
            : !Number.isSafeInteger(value) || value < 0;
    });
    const lifetimeMaximumPauseMillis = missing.length === 0
        ? Math.max(
            observed.longestHighWatermarkMillis,
            observed.activeHighWatermarkLongestMillis,
        )
        : null;
    const lifetimeWithinPauseLimit = lifetimeMaximumPauseMillis !== null &&
        lifetimeMaximumPauseMillis <=
            MULTIPLAYER_PERFORMANCE_TARGET.maxSoakPhaseStallMillis;
    const ready = missing.length === 0 &&
        observed.flowPausedChannels === 0 &&
        observed.decodeFlowPausedChannels === 0 &&
        observed.activeHighWatermarks === 0;
    return {
        schemaVersion: "gaius.browser-inbound-flow-evidence.v1",
        label,
        capturedAt: new Date().toISOString(),
        capturedAtElapsedMillis: Number(
            (performance.now() - smokeStartedAt).toFixed(3)),
        source: "BrowserWebSocketChannel.__gaiusNettyBridge.stats",
        fields: [...BROWSER_INBOUND_FLOW_EVIDENCE_FIELDS],
        observed,
        missing,
        available: missing.length === 0,
        lifetimeMaximumPauseMillis,
        lifetimeWithinPauseLimit,
        // v1 readers used these names. They are explicitly lifetime aliases;
        // strict v2 evidence gates windowMaximumPauseMillis instead.
        maximumPauseMillis: lifetimeMaximumPauseMillis,
        pauseLimitMillis: MULTIPLAYER_PERFORMANCE_TARGET.maxSoakPhaseStallMillis,
        withinPauseLimit: lifetimeWithinPauseLimit,
        compatibilityAliases: {
            maximumPauseMillis: "lifetimeMaximumPauseMillis",
            withinPauseLimit: "lifetimeWithinPauseLimit",
        },
        ready,
    };
}

function assertBrowserInboundFlowReady(snapshot, label) {
    const evidence = browserInboundFlowEvidence(snapshot, label);
    assert.deepEqual(evidence.missing, [],
        `${label}: browser inbound-flow telemetry omitted required fields: ` +
        JSON.stringify(evidence));
    assert.equal(evidence.ready, true,
        `${label}: browser inbound flow remained paused: ${JSON.stringify(evidence)}`);
    return evidence;
}

function inboundFlowWindowMarker(snapshot) {
    const sequence = snapshot?.highWatermarkEventSequence;
    const eventsDropped = snapshot?.highWatermarkEventsDropped;
    const events = snapshot?.highWatermarkEvents;
    const telemetryMissing = [];
    if (!Number.isSafeInteger(sequence) || sequence < 0) {
        telemetryMissing.push("highWatermarkEventSequence");
    }
    if (!Number.isSafeInteger(eventsDropped) || eventsDropped < 0) {
        telemetryMissing.push("highWatermarkEventsDropped");
    }
    if (!Array.isArray(events)) telemetryMissing.push("highWatermarkEvents");
    const retainedSequences = Array.isArray(events)
        ? events.map((event) => event?.sequence).filter((value) =>
            Number.isSafeInteger(value) && value >= 0).sort((left, right) => left - right)
        : [];
    const readiness = browserInboundFlowEvidence(snapshot, "inbound-flow window marker");
    return {
        capturedAt: new Date().toISOString(),
        capturedAtElapsedMillis: Number(
            (performance.now() - smokeStartedAt).toFixed(3)),
        sequence: Number.isSafeInteger(sequence) ? sequence : null,
        eventsDropped: Number.isSafeInteger(eventsDropped) ? eventsDropped : null,
        retainedFirstSequence: retainedSequences[0] ?? null,
        retainedLastSequence: retainedSequences.at(-1) ?? null,
        retainedEventCount: Array.isArray(events) ? events.length : null,
        telemetryMissing,
        current: {
            flowPausedChannels: readiness.observed.flowPausedChannels,
            decodeFlowPausedChannels: readiness.observed.decodeFlowPausedChannels,
            activeHighWatermarks: readiness.observed.activeHighWatermarks,
        },
        ready: readiness.ready,
        lifetimeMaximumPauseMillis: readiness.lifetimeMaximumPauseMillis,
        observed: readiness.observed,
    };
}

function beginBrowserInboundFlowWindow(runtime, stage, label, metadata = {}) {
    const attempt = {
        schemaVersion: BROWSER_INBOUND_FLOW_WINDOW_SCHEMA,
        stage,
        label,
        status: "started",
        source: "BrowserWebSocketChannel.__gaiusNettyBridge.stats.highWatermarkEvents",
        eventFields: [...BROWSER_INBOUND_FLOW_EVENT_FIELDS],
        pauseLimitMillis: MULTIPLAYER_PERFORMANCE_TARGET.maxSoakPhaseStallMillis,
        ...metadata,
        start: inboundFlowWindowMarker(browserRuntimeSnapshot(runtime)),
        end: null,
    };
    // Persist the stage before any exact-evidence assertion can throw. failure.json
    // must identify which strict window was being collected.
    lastInboundFlowAttempt = attempt;
    return attempt;
}

function normalizedInboundFlowEvent(event) {
    return Object.fromEntries(BROWSER_INBOUND_FLOW_EVENT_FIELDS.map((name) =>
        [name, event?.[name] ?? null]));
}

function invalidInboundFlowEventFields(event) {
    const invalid = [];
    for (const name of BROWSER_INBOUND_FLOW_EVENT_FIELDS) {
        const value = event?.[name];
        if (name === "reason") {
            if (typeof value !== "string" || value.length === 0) invalid.push(name);
        }
        else if (name === "startedAtMillis" || name === "endedAtMillis" ||
            name === "durationMillis") {
            if (!Number.isFinite(value) || value < 0) invalid.push(name);
        }
        else if (!Number.isSafeInteger(value) || value < 0) invalid.push(name);
    }
    if (Number.isFinite(event?.startedAtMillis) && Number.isFinite(event?.endedAtMillis) &&
        event.endedAtMillis < event.startedAtMillis) {
        invalid.push("endedAtMillis-before-startedAtMillis");
    }
    return invalid;
}

function captureBrowserInboundFlowWindow(runtime, attempt, label = attempt?.label) {
    const snapshot = browserRuntimeSnapshot(runtime);
    const end = inboundFlowWindowMarker(snapshot);
    const startSequence = attempt?.start?.sequence;
    const endSequence = end.sequence;
    const sequenceMonotonic = Number.isSafeInteger(startSequence) &&
        Number.isSafeInteger(endSequence) && endSequence >= startSequence;
    const expectedEventCount = sequenceMonotonic ? endSequence - startSequence : null;
    const rawRetainedEvents = Array.isArray(snapshot.highWatermarkEvents)
        ? snapshot.highWatermarkEvents : [];
    const windowEvents = sequenceMonotonic
        ? rawRetainedEvents
            .filter((event) => Number.isSafeInteger(event?.sequence) &&
                event.sequence > startSequence && event.sequence <= endSequence)
            .map(normalizedInboundFlowEvent)
            .sort((left, right) => left.sequence - right.sequence)
        : [];
    const invalidEvents = windowEvents.map((event) => ({
        sequence: event.sequence,
        fields: invalidInboundFlowEventFields(event),
    })).filter((event) => event.fields.length > 0);
    let sequenceGap = expectedEventCount === null ||
        windowEvents.length !== expectedEventCount;
    const duplicateSequences = [];
    for (let index = 0; index < windowEvents.length; index++) {
        const expectedSequence = startSequence + index + 1;
        if (windowEvents[index].sequence !== expectedSequence) sequenceGap = true;
        if (index > 0 && windowEvents[index].sequence === windowEvents[index - 1].sequence) {
            duplicateSequences.push(windowEvents[index].sequence);
        }
    }
    const startDropped = attempt?.start?.eventsDropped;
    const endDropped = end.eventsDropped;
    const droppedMonotonic = Number.isSafeInteger(startDropped) &&
        Number.isSafeInteger(endDropped) && endDropped >= startDropped;
    const eventsDroppedDelta = droppedMonotonic ? endDropped - startDropped : null;
    const ringDropAffectedWindow = sequenceGap ||
        (expectedEventCount > 0 && end.retainedFirstSequence !== null &&
            end.retainedFirstSequence > startSequence + 1);
    const windowMaximumPauseMillis = invalidEvents.length === 0
        ? windowEvents.reduce((maximum, event) =>
            Math.max(maximum, event.durationMillis), 0)
        : null;
    const lifetimeMaximumPauseMillis = end.lifetimeMaximumPauseMillis;
    const telemetryMissing = [
        ...(attempt?.start?.telemetryMissing ?? ["start-marker"]),
        ...end.telemetryMissing.map((name) => `end.${name}`),
    ];
    const complete = telemetryMissing.length === 0 && sequenceMonotonic &&
        droppedMonotonic && !sequenceGap && duplicateSequences.length === 0 &&
        invalidEvents.length === 0 && !ringDropAffectedWindow;
    const evidence = {
        ...attempt,
        label,
        status: "captured",
        capturedAt: end.capturedAt,
        capturedAtElapsedMillis: end.capturedAtElapsedMillis,
        end,
        events: windowEvents,
        telemetryMissing,
        sequence: {
            start: startSequence ?? null,
            end: endSequence ?? null,
            expectedEventCount,
            observedEventCount: windowEvents.length,
            sequenceGap,
            duplicateSequences,
        },
        ring: {
            droppedAtStart: startDropped ?? null,
            droppedAtEnd: endDropped ?? null,
            eventsDroppedDelta,
            ringDropAffectedWindow,
        },
        invalidEvents,
        complete,
        ready: end.ready,
        windowMaximumPauseMillis,
        lifetimeMaximumPauseMillis,
        windowWithinPauseLimit: windowMaximumPauseMillis !== null &&
            windowMaximumPauseMillis <=
                MULTIPLAYER_PERFORMANCE_TARGET.maxSoakPhaseStallMillis,
        lifetimeWithinPauseLimit: lifetimeMaximumPauseMillis !== null &&
            lifetimeMaximumPauseMillis <=
                MULTIPLAYER_PERFORMANCE_TARGET.maxSoakPhaseStallMillis,
        // Compatibility aliases are window-scoped in v2; lifetime values are
        // never substituted into a steady/reconnect window verdict.
        maximumPauseMillis: windowMaximumPauseMillis,
        withinPauseLimit: windowMaximumPauseMillis !== null &&
            windowMaximumPauseMillis <=
                MULTIPLAYER_PERFORMANCE_TARGET.maxSoakPhaseStallMillis,
        compatibilityAliases: {
            maximumPauseMillis: "windowMaximumPauseMillis",
            withinPauseLimit: "windowWithinPauseLimit",
        },
        observed: end.observed,
        missing: telemetryMissing,
        available: telemetryMissing.length === 0,
    };
    lastInboundFlowAttempt = evidence;
    return evidence;
}

function finishBrowserInboundFlowWindow(runtime, attempt, label, options = {}) {
    const evidence = captureBrowserInboundFlowWindow(runtime, attempt, label);
    return assertBrowserInboundFlowWindow(evidence, label, options);
}

function assertBrowserInboundFlowWindow(evidence, label, options = {}) {
    // Save the fully captured attempt before the first assertion. A failed strict
    // gate therefore retains the stage, sequence range, ring state, and events.
    lastInboundFlowAttempt = evidence;
    assert.equal(evidence?.schemaVersion, BROWSER_INBOUND_FLOW_WINDOW_SCHEMA,
        `${label}: inbound-flow window schema drifted`);
    assert.ok(BROWSER_INBOUND_FLOW_WINDOW_STAGES.includes(evidence?.stage),
        `${label}: unsupported inbound-flow stage ${evidence?.stage}`);
    assert.deepEqual(evidence?.telemetryMissing, [],
        `${label}: exact high-watermark telemetry was unavailable: ` +
            JSON.stringify(evidence));
    assert.equal(evidence?.complete, true,
        `${label}: high-watermark ring did not retain a complete sequence window: ` +
            JSON.stringify(evidence));
    assert.equal(evidence?.ready, true,
        `${label}: browser inbound flow remained paused: ${JSON.stringify(evidence)}`);
    assert.equal(evidence?.windowWithinPauseLimit, true,
        `${label}: exact window pause reached ${evidence?.windowMaximumPauseMillis}ms ` +
            `(limit ${evidence?.pauseLimitMillis}ms)`);
    assert.equal(evidence?.maximumPauseMillis, evidence?.windowMaximumPauseMillis,
        `${label}: compatibility maximum was not window scoped`);
    assert.equal(evidence?.withinPauseLimit, evidence?.windowWithinPauseLimit,
        `${label}: compatibility verdict was not window scoped`);
    if (options.requireCleanup === true) {
        for (const name of BROWSER_RUNTIME_CLEANUP_GAUGES) {
            assert.equal(evidence?.observed?.[name], 0,
                `${label}: final cleanup retained ${name}`);
        }
    }
    evidence.status = "passed";
    lastInboundFlowAttempt = evidence;
    return evidence;
}

function browserRuntimeCleanupGaugeEvidence(snapshot) {
    const observed = Object.fromEntries(BROWSER_RUNTIME_CLEANUP_GAUGES.map((name) => [
        name,
        snapshot !== undefined && Object.prototype.hasOwnProperty.call(snapshot, name)
            ? snapshot[name]
            : null,
    ]));
    const missing = BROWSER_RUNTIME_CLEANUP_GAUGES.filter((name) =>
        !Number.isSafeInteger(observed[name]));
    return {
        source: "BrowserWebSocketChannel.__gaiusNettyBridge.stats",
        fields: [...BROWSER_RUNTIME_CLEANUP_GAUGES],
        observed,
        missing,
        available: missing.length === 0,
        allZero: missing.length === 0 &&
            BROWSER_RUNTIME_CLEANUP_GAUGES.every((name) => observed[name] === 0),
        note: missing.length === 0
            ? "Browser JSBody cleanup counters observed directly"
            : "Browser JSBody cleanup counters were absent; no zero was synthesized",
    };
}

function assertBrowserRuntimeCleanupGaugesZero(snapshot, label) {
    const evidence = browserRuntimeCleanupGaugeEvidence(snapshot);
    assert.deepEqual(evidence.missing, [],
        `${label}: browser cleanup telemetry omitted required fields: ` +
        JSON.stringify(evidence));
    assert.deepEqual(evidence.observed,
        Object.fromEntries(BROWSER_RUNTIME_CLEANUP_GAUGES.map((name) => [name, 0])),
        `${label}: browser cleanup gauges did not drain to zero`);
    return evidence;
}

function assertBrowserOutboundContinuationScheduler(snapshot, label) {
    const observed = {
        macrotasks: snapshot.outboundContinuationMacrotasks,
        messageChannelSchedules: snapshot.outboundMessageChannelFlushes,
        messageChannelCallbacks: snapshot.outboundMessageChannelCallbacks,
        timerFallbacks: snapshot.outboundContinuationTimers,
    };
    for (const [name, value] of Object.entries(observed)) {
        assert.ok(Number.isSafeInteger(value) && value >= 0,
            `${label}: outbound continuation telemetry ${name} is ${value}`);
    }
    assert.equal(observed.timerFallbacks, 0,
        `${label}: multiplayer budget continuation regressed to a clamped timer`);
    assert.equal(observed.messageChannelSchedules, observed.macrotasks,
        `${label}: a continuation macrotask bypassed MessageChannel`);
    assert.ok(observed.messageChannelCallbacks <= observed.messageChannelSchedules,
        `${label}: MessageChannel callback accounting exceeded scheduled callbacks`);
    return {
        source: "BrowserWebSocketChannel.__gaiusNettyBridge.stats",
        scheduler: "MessageChannel-one-callback-per-task",
        ...observed,
        timerClampAvoided: observed.timerFallbacks === 0,
    };
}

function relayRuntimeGaugeEvidence(snapshot) {
    const runtime = snapshot?.runtime;
    const observed = Object.fromEntries(RELAY_RUNTIME_GAUGES.map((name) => [
        name,
        runtime !== undefined && Object.prototype.hasOwnProperty.call(runtime, name)
            ? runtime[name]
            : null,
    ]));
    const missing = RELAY_RUNTIME_GAUGES.filter((name) =>
        !Number.isSafeInteger(observed[name]));
    return {
        source: "/relay-node/v1.runtime",
        fields: [...RELAY_RUNTIME_GAUGES],
        observed,
        missing,
        available: missing.length === 0,
        allZero: missing.length === 0 &&
            RELAY_RUNTIME_GAUGES.every((name) => observed[name] === 0),
    };
}

function relayRuntimeConnectionGaugeEvidence(snapshot) {
    const runtime = snapshot?.runtime;
    const observed = Object.fromEntries(RELAY_RUNTIME_CONNECTION_GAUGES.map((name) => [
        name,
        runtime !== undefined && Object.prototype.hasOwnProperty.call(runtime, name)
            ? runtime[name]
            : null,
    ]));
    const missing = RELAY_RUNTIME_CONNECTION_GAUGES.filter((name) =>
        !Number.isSafeInteger(observed[name]) || observed[name] < 0);
    return {
        source: "/relay-node/v1.runtime",
        fields: [...RELAY_RUNTIME_CONNECTION_GAUGES],
        observed,
        missing,
        available: missing.length === 0,
    };
}

function relayRuntimeConnectionGaugesEqual(snapshot, expected) {
    const evidence = relayRuntimeConnectionGaugeEvidence(snapshot);
    return evidence.available && RELAY_RUNTIME_CONNECTION_GAUGES.every((name) =>
        evidence.observed[name] === expected);
}

function assertRelayRuntimeConnectionGauges(snapshot, expected, label) {
    const evidence = relayRuntimeConnectionGaugeEvidence(snapshot);
    assert.deepEqual(evidence.missing, [],
        `${label}: RelayNode connection telemetry omitted required gauges: ` +
        JSON.stringify(evidence));
    assert.deepEqual(evidence.observed,
        Object.fromEntries(RELAY_RUNTIME_CONNECTION_GAUGES.map((name) => [name, expected])),
        `${label}: RelayNode logical/physical connection gauges drifted`);
    return evidence;
}

function relayRuntimeGaugesAreZero(snapshot) {
    const evidence = relayRuntimeGaugeEvidence(snapshot);
    return evidence.available && evidence.allZero;
}

function assertRelayRuntimeGaugesZero(snapshot, label) {
    const evidence = relayRuntimeGaugeEvidence(snapshot);
    assert.deepEqual(evidence.missing, [],
        `${label}: RelayNode runtime telemetry omitted required gauges: ` +
        JSON.stringify(evidence));
    assert.deepEqual(evidence.observed,
        Object.fromEntries(RELAY_RUNTIME_GAUGES.map((name) => [name, 0])),
        `${label}: RelayNode runtime gauges did not drain to zero`);
    return evidence;
}

function assertRelayDrainPerformance(snapshots, label) {
    const entries = snapshots.filter((snapshot) => snapshot?.runtime !== undefined);
    assert.ok(entries.length > 0, `${label}: RelayNode runtime telemetry missing`);
    for (const snapshot of entries) {
        const runtime = snapshot.runtime;
        assert.ok(Number.isFinite(runtime.serverFrameMaxDrainDurationMillis),
            `${label}: RelayNode drain duration telemetry missing`);
        assert.ok(runtime.serverFrameMaxDrainDurationMillis <=
            RELAY_DRAIN_MAX_DURATION_MILLIS,
            `${label}: RelayNode server-frame drain reached ` +
            `${runtime.serverFrameMaxDrainDurationMillis}ms`);
        assert.equal(runtime.serverFrameSendErrors, 0,
            `${label}: RelayNode server-frame send error detected`);
    }
    const final = entries.at(-1).runtime;
    assert.equal(final.serverFrameBufferedBytes, 0,
        `${label}: RelayNode retained server-frame bytes`);
    assert.equal(final.activeServerFrameDrainHandles, 0,
        `${label}: RelayNode retained an active server-frame drain`);
}

function relayRuntimeIsClean(snapshot) {
    return snapshot !== undefined && snapshot.activeConnections === 0 &&
        (snapshot.target === undefined || snapshot.target.activeConnections === 0) &&
        (!relayRuntimeTelemetryRequired ||
            (relayRuntimeGaugesAreZero(snapshot) &&
                relayRuntimeConnectionGaugesEqual(snapshot, 0)));
}

function clientLivenessEvidence(client) {
    return {
        id: client.id,
        username: client.username,
        wave: client.wave,
        phase: client.phase,
        loginFinished: client.loginFinished,
        playLoginPackets: client.playLoginPackets,
        chunkPackets: client.chunkPackets,
        uniqueChunkPositions: client.uniqueChunkPositions.size,
        chunkWindow: client.chunkWindowResult(),
        chunkBatch: client.chunkBatchResult(),
        timing: client.timingResult(),
        performance: client.performanceResult(),
        arrivalTimeline: client.arrivalTimelineResult(),
        failure: client.failure === undefined ? null : String(client.failure),
        onlineEncryption: client.onlineEncryptionResult(),
    };
}

function assertClientPerformance(client, label, options = {}) {
    if (!releaseEvidenceMode) return client.performanceResult();
    const requireLatencyDistributions = options.requireLatencyDistributions !== false;
    const timing = client.timingResult();
    const performanceEvidence = client.performanceResult();
    for (const [name, value, limit] of [
        ["connectToMinimumChunksMillis", timing.connectToMinimumChunksMillis,
            MULTIPLAYER_PERFORMANCE_TARGET.maxConnectToMinimumChunksMillis],
        ["configurationToPlayLoginMillis", timing.configurationToPlayLoginMillis,
            MULTIPLAYER_PERFORMANCE_TARGET.maxConfigurationToPlayLoginMillis],
        ["playLoginToFirstChunkMillis", timing.playLoginToFirstChunkMillis,
            MULTIPLAYER_PERFORMANCE_TARGET.maxPlayLoginToFirstChunkMillis],
        ["maxPreMinimumChunkPacketGapMillis",
            performanceEvidence.maxPreMinimumChunkPacketGapMillis,
            MULTIPLAYER_PERFORMANCE_TARGET.maxPreMinimumChunkPacketGapMillis],
        ["maxPlayTickGapMillis", performanceEvidence.maxPlayTickGapMillis,
            MULTIPLAYER_PERFORMANCE_TARGET.maxPlayTickGapMillis],
        ["maxPollGapMillis", performanceEvidence.maxPollGapMillis,
            MULTIPLAYER_PERFORMANCE_TARGET.maxPollGapMillis],
    ]) {
        assert.ok(Number.isFinite(value) && value <= limit,
            `${label}: ${name} reached ${value}ms (limit ${limit}ms)`);
    }
    // Use the unrounded monotonic measurements for the actual gate.  The
    // three-decimal aliases above are for report readability only and must not
    // turn 100.0004 ms into a false 100 ms pass.
    for (const [name, value, limit] of [
        ["maxPollGapRawMillis", performanceEvidence.maxPollGapRawMillis,
            MULTIPLAYER_PERFORMANCE_TARGET.maxPollGapMillis],
        ["maxPlayTickGapRawMillis", performanceEvidence.maxPlayTickGapRawMillis,
            MULTIPLAYER_PERFORMANCE_TARGET.maxPlayTickGapMillis],
        ["maxPreMinimumChunkPacketGapRawMillis",
            performanceEvidence.maxPreMinimumChunkPacketGapRawMillis,
            MULTIPLAYER_PERFORMANCE_TARGET.maxPreMinimumChunkPacketGapMillis],
    ]) {
        assert.ok(Number.isFinite(value) && value <= limit,
            `${label}: ${name} reached ${value}ms (limit ${limit}ms)`);
    }
    assert.ok(performanceEvidence.maximumBufferedBytes <=
        MULTIPLAYER_PERFORMANCE_TARGET.maxParserBufferedBytes,
    `${label}: parser buffered ${performanceEvidence.maximumBufferedBytes} bytes`);
    if (stressMode) {
        const latencyTarget = STRESS_LATENCY_DISTRIBUTION_TARGET;
        if (requireLatencyDistributions) {
            assertHistogramLimit(performanceEvidence.pollGapHistogram, "p95Millis", 16.7,
                `${label} poll p95`);
            assertHistogramLimit(performanceEvidence.pollGapHistogram, "p99Millis",
                latencyTarget.pollGap.p99Millis,
                `${label} poll p99`);
            assertHistogramLimit(performanceEvidence.pollGapHistogram, "p999Millis",
                latencyTarget.pollGap.p999Millis,
                `${label} poll p99.9`);
            assertHistogramLimit(performanceEvidence.pollGapHistogram, "maxMillis",
                latencyTarget.pollGap.maxMillis,
                `${label} poll max`);
            assertHistogramLimit(performanceEvidence.playTickGapHistogram, "p99Millis",
                latencyTarget.playTickGap.p99Millis,
                `${label} tick p99`);
            assertHistogramLimit(performanceEvidence.playTickGapHistogram, "p999Millis",
                latencyTarget.playTickGap.p999Millis,
                `${label} tick p99.9`);
            assertHistogramLimit(performanceEvidence.playTickGapHistogram, "maxMillis",
                latencyTarget.playTickGap.maxMillis,
                `${label} tick max`);
            assertHistogramLimit(performanceEvidence.preMinimumChunkGapHistogram, "p99Millis",
                latencyTarget.preMinimumChunkGap.p99Millis,
                `${label} pre-chunk p99`);
            assertHistogramLimit(performanceEvidence.preMinimumChunkGapHistogram, "maxMillis",
                latencyTarget.preMinimumChunkGap.maxMillis,
                `${label} pre-chunk max`);
            for (const [name, histogram, limit] of [
                ["poll", performanceEvidence.pollGapHistogram,
                    latencyTarget.pollGap.maxMillis],
                ["tick", performanceEvidence.playTickGapHistogram,
                    latencyTarget.playTickGap.maxMillis],
                ["pre-chunk", performanceEvidence.preMinimumChunkGapHistogram,
                    latencyTarget.preMinimumChunkGap.maxMillis],
            ]) {
                assert.ok(Number.isFinite(histogram?.rawMaxMillis) &&
                    histogram.rawMaxMillis <= limit,
                `${label} ${name} raw max reached ${histogram?.rawMaxMillis}ms ` +
                    `(limit ${limit}ms)`);
            }
            for (const [name, histogram, p99Limit] of [
                ["poll", performanceEvidence.pollGapHistogram,
                    latencyTarget.pollGap.p99Millis],
                ["tick", performanceEvidence.playTickGapHistogram,
                    latencyTarget.playTickGap.p99Millis],
                ["pre-chunk", performanceEvidence.preMinimumChunkGapHistogram,
                    latencyTarget.preMinimumChunkGap.p99Millis],
            ]) {
                const rawBounds = histogram?.rawQuantileUpperBoundsMillis;
                assert.ok(Number.isFinite(rawBounds?.p99Millis) &&
                    rawBounds.p99Millis <= p99Limit,
                `${label} ${name} raw p99 bound reached ${rawBounds?.p99Millis}ms ` +
                    `(limit ${p99Limit}ms)`);
            }
        } else {
            // A reconnect boundary has too few samples for a percentile to be
            // statistically meaningful, but a single long gap is still a real
            // visible hitch. Keep the hard max buckets active while deferring
            // only p95/p99/p99.9 decisions until the steady-soak population.
            assertHistogramLimit(performanceEvidence.pollGapHistogram, "maxMillis",
                latencyTarget.pollGap.maxMillis,
                `${label} poll max`);
            assertHistogramLimit(performanceEvidence.playTickGapHistogram, "maxMillis",
                latencyTarget.playTickGap.maxMillis,
                `${label} tick max`);
            assertHistogramLimit(performanceEvidence.preMinimumChunkGapHistogram, "maxMillis",
                latencyTarget.preMinimumChunkGap.maxMillis,
                `${label} pre-chunk max`);
            for (const [name, histogram, limit] of [
                ["poll", performanceEvidence.pollGapHistogram,
                    latencyTarget.pollGap.maxMillis],
                ["tick", performanceEvidence.playTickGapHistogram,
                    latencyTarget.playTickGap.maxMillis],
                ["pre-chunk", performanceEvidence.preMinimumChunkGapHistogram,
                    latencyTarget.preMinimumChunkGap.maxMillis],
            ]) {
                assert.ok(Number.isFinite(histogram?.rawMaxMillis) &&
                    histogram.rawMaxMillis <= limit,
                `${label} ${name} raw max reached ${histogram?.rawMaxMillis}ms ` +
                    `(limit ${limit}ms)`);
            }
            // Percentile gates are intentionally deferred at startup and
            // reconnect boundaries: those windows contain too few samples to
            // make a meaningful p99 decision.  Keep the raw max gate above as
            // the hard no-hitch bound; the full raw p99/p99.9 checks remain in
            // the requireLatencyDistributions=true (steady-soak) branch.
        }
    }
    return {
        timing,
        performance: performanceEvidence,
        latencyDistributionGate: {
            required: requireLatencyDistributions,
            deferredUntilSteadySoak: !requireLatencyDistributions,
        },
        limits: { ...MULTIPLAYER_PERFORMANCE_TARGET },
    };
}

function startSoakPerformanceObservation(clients, browserRuntime) {
    const startedAt = performance.now();
    const notPlaySince = new Map();
    const reported = new Set();
    const violations = [];
    let samples = 0;
    let maxPhaseStallMillis = 0;
    let maxBrowserQueuedFrames = 0;
    let maxBrowserInboundQueuedBytes = 0;
    let maxBrowserEventLoopGapMillis = 0;
    let maxFlowPausedChannels = 0;
    let maxDecodeFlowPausedChannels = 0;
    let maxActiveHighWatermarks = 0;
    let pausedSamples = 0;
    let maxContinuousFlowPauseMillis = 0;
    const sample = () => {
        const now = performance.now();
        samples++;
        for (const client of clients) {
            if (client.failure !== undefined) {
                const key = `${client.id}:failure`;
                if (!reported.has(key)) {
                    reported.add(key);
                    violations.push({
                        type: "client-failure",
                        clientId: client.id,
                        error: String(client.failure),
                    });
                }
            }
            const healthy = client.failure === undefined &&
                client.phase === "play" &&
                client.minimumChunkTargetReached();
            if (healthy) {
                notPlaySince.delete(client.id);
                continue;
            }
            const began = notPlaySince.get(client.id) ?? now;
            notPlaySince.set(client.id, began);
            const duration = now - began;
            maxPhaseStallMillis = Math.max(maxPhaseStallMillis, duration);
            if (duration > MULTIPLAYER_PERFORMANCE_TARGET.maxSoakPhaseStallMillis) {
                const key = `${client.id}:phase-stall`;
                if (!reported.has(key)) {
                    reported.add(key);
                    violations.push({
                        type: "play-liveness-stall",
                        clientId: client.id,
                        phase: client.phase,
                        chunkPackets: client.chunkPackets,
                        durationMillis: Number(duration.toFixed(3)),
                    });
                }
            }
        }
        const browser = browserRuntimeSnapshot(browserRuntime);
        maxBrowserQueuedFrames = Math.max(
            maxBrowserQueuedFrames,
            Number(browser.peakQueuedFrames ?? browser.queuedFrames ?? 0),
        );
        maxBrowserInboundQueuedBytes = Math.max(
            maxBrowserInboundQueuedBytes,
            Number(browser.peakInboundQueuedBytes ?? browser.inboundQueuedBytes ?? 0),
        );
        maxBrowserEventLoopGapMillis = Math.max(
            maxBrowserEventLoopGapMillis,
            Number(browser.longestEventLoopGapMillis ?? 0),
        );
        const inboundFlow = browserInboundFlowEvidence(browser, "soak sample");
        if (!inboundFlow.available) {
            const key = "browser-inbound-flow-telemetry";
            if (!reported.has(key)) {
                reported.add(key);
                violations.push({
                    type: key,
                    missing: inboundFlow.missing,
                });
            }
        }
        else {
            maxFlowPausedChannels = Math.max(
                maxFlowPausedChannels,
                inboundFlow.observed.flowPausedChannels,
            );
            maxDecodeFlowPausedChannels = Math.max(
                maxDecodeFlowPausedChannels,
                inboundFlow.observed.decodeFlowPausedChannels,
            );
            maxActiveHighWatermarks = Math.max(
                maxActiveHighWatermarks,
                inboundFlow.observed.activeHighWatermarks,
            );
            const paused = inboundFlow.observed.flowPausedChannels > 0 ||
                inboundFlow.observed.decodeFlowPausedChannels > 0 ||
                inboundFlow.observed.activeHighWatermarks > 0;
            if (paused) pausedSamples++;
            maxContinuousFlowPauseMillis = Math.max(
                maxContinuousFlowPauseMillis,
                inboundFlow.maximumPauseMillis,
            );
            if (!inboundFlow.withinPauseLimit) {
                const key = "browser-inbound-flow-stall";
                if (!reported.has(key)) {
                    reported.add(key);
                    violations.push({
                        type: key,
                        durationMillis: Number(inboundFlow.maximumPauseMillis.toFixed(3)),
                        source: "per-channel-high-watermark",
                        flowPausedChannels: inboundFlow.observed.flowPausedChannels,
                        decodeFlowPausedChannels:
                            inboundFlow.observed.decodeFlowPausedChannels,
                        activeHighWatermarks:
                            inboundFlow.observed.activeHighWatermarks,
                    });
                }
            }
        }
        if (browser.activeChannels !== clientCount ||
            browser.activeWebSockets !== clientCount) {
            const key = "browser-liveness";
            if (!reported.has(key)) {
                reported.add(key);
                violations.push({
                    type: key,
                    activeChannels: browser.activeChannels,
                    activeWebSockets: browser.activeWebSockets,
                });
            }
        }
    };
    const timer = setInterval(sample, 100);
    timer.unref();
    return {
        finish() {
            clearInterval(timer);
            sample();
            return {
                samples,
                elapsedMillis: Number((performance.now() - startedAt).toFixed(3)),
                maxPhaseStallMillis: Number(maxPhaseStallMillis.toFixed(3)),
                maxBrowserQueuedFrames,
                maxBrowserInboundQueuedBytes,
                maxBrowserEventLoopGapMillis:
                    Number(maxBrowserEventLoopGapMillis.toFixed(3)),
                rawMaxBrowserEventLoopGapMillis: maxBrowserEventLoopGapMillis,
                maxFlowPausedChannels,
                maxDecodeFlowPausedChannels,
                maxActiveHighWatermarks,
                pausedSamples,
                maxContinuousFlowPauseMillis:
                    Number(maxContinuousFlowPauseMillis.toFixed(3)),
                violations,
                ok: violations.length === 0,
                limits: {
                    maxSoakPhaseStallMillis:
                        MULTIPLAYER_PERFORMANCE_TARGET.maxSoakPhaseStallMillis,
                    maxBrowserQueuedFrames:
                        MULTIPLAYER_PERFORMANCE_TARGET.maxBrowserQueuedFrames,
                    maxBrowserInboundQueuedBytes:
                        MULTIPLAYER_PERFORMANCE_TARGET.maxBrowserInboundQueuedBytes,
                    maxPollGapMillis: MULTIPLAYER_PERFORMANCE_TARGET.maxPollGapMillis,
                    maxBrowserEventLoopGapMillis: stressMode
                        ? STRESS_EVENT_LOOP_MAX_MILLIS
                        : MULTIPLAYER_PERFORMANCE_TARGET.maxPollGapMillis,
                    maxContinuousFlowPauseMillis:
                        MULTIPLAYER_PERFORMANCE_TARGET.maxSoakPhaseStallMillis,
                },
            };
        },
    };
}

function assertSoakPerformance(evidence, label) {
    if (!releaseEvidenceMode) return evidence;
    assert.ok(evidence?.ok === true,
        `${label}: liveness violations ${JSON.stringify(evidence?.violations ?? [])}`);
    assert.ok(evidence.samples >= 10,
        `${label}: insufficient liveness samples (${evidence.samples})`);
    assert.ok(evidence.maxBrowserQueuedFrames <=
        MULTIPLAYER_PERFORMANCE_TARGET.maxBrowserQueuedFrames,
    `${label}: browser queued frames reached ${evidence.maxBrowserQueuedFrames}`);
    assert.ok(evidence.maxBrowserInboundQueuedBytes <=
        MULTIPLAYER_PERFORMANCE_TARGET.maxBrowserInboundQueuedBytes,
    `${label}: browser inbound queue reached ${evidence.maxBrowserInboundQueuedBytes} bytes`);
    const eventLoopGapLimitMillis = stressMode
        ? STRESS_EVENT_LOOP_MAX_MILLIS
        : MULTIPLAYER_PERFORMANCE_TARGET.maxPollGapMillis;
    const rawEventLoopGapMillis = Number.isFinite(evidence.rawMaxBrowserEventLoopGapMillis)
        ? evidence.rawMaxBrowserEventLoopGapMillis
        : evidence.maxBrowserEventLoopGapMillis;
    assert.ok(rawEventLoopGapMillis <= eventLoopGapLimitMillis,
        `${label}: browser event-loop gap reached ${rawEventLoopGapMillis}ms ` +
        `(limit ${eventLoopGapLimitMillis}ms)`);
    assert.ok(evidence.maxContinuousFlowPauseMillis <=
        MULTIPLAYER_PERFORMANCE_TARGET.maxSoakPhaseStallMillis,
    `${label}: browser inbound flow stayed paused for ` +
        `${evidence.maxContinuousFlowPauseMillis}ms`);
    return evidence;
}

function assertSoakLiveness(clients, browser, relay, label) {
    const observedClients = clients.map((client) => {
        const evidence = clientLivenessEvidence(client);
        assert.equal(evidence.failure, null,
            `${label}: ${evidence.username} reported failure: ${evidence.failure}`);
        assert.equal(evidence.phase, "play",
            `${label}: ${evidence.username} is not in PLAY`);
        assert.equal(evidence.loginFinished, true,
            `${label}: ${evidence.username} did not finish LOGIN`);
        assert.ok(evidence.playLoginPackets > 0,
            `${label}: ${evidence.username} did not receive PLAY login`);
        assert.ok(client.minimumChunkTargetReached(),
            `${label}: ${evidence.username} received ${evidence.chunkPackets} chunk packets / ` +
            `${evidence.uniqueChunkPositions} unique chunks, expected ${minimumChunkPackets}`);
        assertClientSecurity(client, `${label} ${evidence.username}`);
        return evidence;
    });
    assert.equal(browser.activeChannels, clientCount,
        `${label}: browser active channel count drifted`);
    assert.equal(browser.activeWebSockets, clientCount,
        `${label}: browser active WebSocket count drifted`);
    if (!externalMode) {
        assert.equal(relay.target !== undefined, true,
            `${label}: RelayNode omitted the target route telemetry`);
        assert.equal(relay.activeConnections, clientCount,
            `${label}: RelayNode active connection count drifted`);
        assert.equal(relay.target.activeConnections, clientCount,
            `${label}: RelayNode active target connection count drifted`);
    }
    else {
        assert.ok(relay === undefined || relay.activeConnections === undefined ||
            relay.activeConnections === clientCount,
        `${label}: external RelayNode active connection count drifted`);
        assert.ok(relay?.target === undefined ||
            relay.target.activeConnections === clientCount,
        `${label}: external RelayNode target connection count drifted`);
    }
    const relayGauges = relayRuntimeGaugeEvidence(relay);
    const inboundFlow = assertBrowserInboundFlowReady(
        browser,
        `${label}: browser inbound flow`,
    );
    const outboundContinuationScheduler =
        assertBrowserOutboundContinuationScheduler(browser, label);
    if (relayRuntimeTelemetryRequired) {
        assertRelayRuntimeConnectionGauges(
            relay,
            clientCount,
            `${label}: RelayNode logical/physical connection gauges`,
        );
        assertRelayRuntimeGaugesZero(relay, `${label}: RelayNode gauges`);
    }
    return {
        required: {
            clientCount,
            phase: "play",
            loginFinished: true,
            minimumChunkPackets,
            failure: null,
            onlineEncryptionFailClosed: true,
            browserActiveChannels: clientCount,
            browserActiveWebSockets: clientCount,
            browserInboundFlowReady: true,
            relayActiveConnections: clientCount,
            relayTargetActiveConnections: clientCount,
            relayRuntimeGaugesZero: [...RELAY_RUNTIME_GAUGES],
            outboundContinuationTimerFallbacks: 0,
        },
        observed: {
            clients: observedClients,
            browser: {
                activeChannels: browser.activeChannels,
                activeWebSockets: browser.activeWebSockets,
            },
            inboundFlow,
            relay: {
                activeConnections: relay.activeConnections,
                targetActiveConnections: relay.target.activeConnections,
            },
            relayRuntimeGauges: relayGauges,
            relayRuntimeConnectionGauges: relayRuntimeConnectionGaugeEvidence(relay),
            outboundContinuationScheduler,
        },
        ok: true,
    };
}

function forceAbnormalTransportDrop(runtime, clients, wave) {
    const probes = clients.map((client) => {
        const entry = runtime.bridge.channels.get(client.id);
        assert.ok(entry && !entry.closed && entry.ws,
            `reconnect wave ${wave} client ${client.id} has no live bridge entry`);
        const tail = Buffer.from(`gaius-reconnect-tail:${wave}:${client.id}`, "utf8");
        assert.equal(typeof entry.ws.onmessage, "function",
            "live bridge entry omitted its WebSocket message handler");
        entry.ws.onmessage({
            data: tail.buffer.slice(tail.byteOffset, tail.byteOffset + tail.byteLength),
        });
        return {
            id: client.id,
            entry,
            tail,
            terminatedAt: undefined,
        };
    });
    for (const probe of probes) {
        probe.terminatedAt = performance.now();
        probe.entry.ws.terminate();
    }
    return probes;
}

async function captureAbnormalTransportDrop(runtime, probes) {
    const evidence = [];
    for (const probe of probes) {
        await waitFor(
            () => probe.entry.errors.length > 0 &&
                runtime.bridge.hasPendingInbound(probe.id),
            `abnormal close evidence for channel ${probe.id}`, 5000,
            () => JSON.stringify({
                closed: probe.entry.closed,
                errors: probe.entry.errors,
                pendingInbound: runtime.bridge.hasPendingInbound(probe.id),
            }));
        const chunks = [];
        const deadline = Date.now() + 5000;
        runtime.arrivalTelemetry?.syntheticSockets?.add(probe.entry.ws);
        try {
            while (runtime.bridge.hasPendingInbound(probe.id)) {
                const chunk = runtime.bridge.pollInbound(probe.id);
                if (chunk === null) {
                    if (Date.now() >= deadline) {
                        throw new Error(`timed out draining channel ${probe.id} close tail`);
                    }
                    await delay(0);
                    continue;
                }
                chunks.push(Buffer.from(chunk));
            }
        }
        finally {
            runtime.arrivalTelemetry?.syntheticSockets?.delete(probe.entry.ws);
        }
        const drained = Buffer.concat(chunks);
        const tailOffset = drained.indexOf(probe.tail);
        assert.ok(tailOffset >= 0,
            `abnormal close discarded channel ${probe.id} synthetic inbound marker`);
        assert.equal(tailOffset + probe.tail.byteLength, drained.byteLength,
            `channel ${probe.id} synthetic inbound marker was not the final queued bytes`);
        const error = runtime.bridge.pollError(probe.id);
        assert.match(String(error), /^WebSocket transport closed: (?!1000\b)\d+/,
            `abnormal close omitted channel ${probe.id} transport error`);
        assert.equal(runtime.bridge.channels.get(probe.id), probe.entry,
            `abnormal close retired channel ${probe.id} before Java final close`);
        evidence.push({
            channelId: probe.id,
            closeError: error,
            nonNormalClose: true,
            retainedEntry: true,
            label: "synthetic-inbound-marker",
            syntheticInboundMarker: {
                preserved: true,
                networkFrame: false,
                source: "websocket-onmessage-before-abnormal-close",
                markerSha256: createHash("sha256").update(probe.tail).digest("hex"),
                drainedChunks: chunks.length,
                drainedBytes: drained.byteLength,
                markerOffset: tailOffset,
                finalQueuedBytes: true,
            },
        });
    }
    return evidence;
}

function assertBrowserRuntimeClean(snapshot, label) {
    assert.deepEqual({
        activeChannels: snapshot.activeChannels,
        flowPausedChannels: snapshot.flowPausedChannels,
        decodeFlowPausedChannels: snapshot.decodeFlowPausedChannels,
        activeWebSockets: snapshot.activeWebSockets,
        queuedBytes: snapshot.queuedBytes,
        queuedFrames: snapshot.queuedFrames,
        inboundQueuedBytes: snapshot.inboundQueuedBytes,
        activeRelayTargetLeases: snapshot.activeRelayTargetLeases,
    }, {
        activeChannels: 0,
        flowPausedChannels: 0,
        decodeFlowPausedChannels: 0,
        activeWebSockets: 0,
        queuedBytes: 0,
        queuedFrames: 0,
        inboundQueuedBytes: 0,
        activeRelayTargetLeases: 0,
    }, `${label} retained browser transport state`);
    assertBrowserRuntimeCleanupGaugesZero(snapshot, label);
}

async function waitForBrowserRuntimeCleanup(runtime, label) {
    await waitFor(() => {
        const snapshot = browserRuntimeSnapshot(runtime);
        return snapshot.activeChannels === 0 &&
            snapshot.activeWebSockets === 0 &&
            snapshot.queuedBytes === 0 &&
            snapshot.queuedFrames === 0 &&
            snapshot.inboundQueuedBytes === 0 &&
            snapshot.activeRelayTargetLeases === 0 &&
            browserRuntimeCleanupGaugeEvidence(snapshot).allZero;
    }, label, 5000, () => JSON.stringify(browserRuntimeSnapshot(runtime)));
}

async function waitForBrowserInboundFlowReady(runtime, label) {
    await waitFor(() => {
        const snapshot = browserRuntimeSnapshot(runtime);
        const evidence = browserInboundFlowEvidence(snapshot, label);
        assert.ok(evidence.withinPauseLimit !== false,
            `${label}: per-channel inbound flow paused for ` +
                `${evidence.maximumPauseMillis}ms (limit ${evidence.pauseLimitMillis}ms)`);
        return evidence.ready;
    }, label, 5000, () => JSON.stringify(browserRuntimeSnapshot(runtime)));
    return assertBrowserInboundFlowReady(browserRuntimeSnapshot(runtime), label);
}

function callbackFinalizationTailContract() {
    return {
        schemaVersion: CALLBACK_FINALIZATION_TAIL_TELEMETRY_SCHEMA_VERSION,
        slowThresholdMillis: CALLBACK_FINALIZATION_TAIL_SLOW_THRESHOLD_MILLIS,
        sampleLimit: CALLBACK_FINALIZATION_TAIL_SAMPLE_LIMIT,
        retention: CALLBACK_FINALIZATION_TAIL_RETENTION,
        diagnosticOnly: true,
        strictGatesChanged: false,
        strictRawDurationGateMillis: 16.7,
        measuredFrom: "callback-work-end-to-finalize-finish",
        totalAfterFinalizeFrom: "callback-start-to-finalize-finish",
        includesContinuationScheduling: true,
    };
}

function arrivalPeriodicServerSyncContract() {
    return {
        schemaVersion: ARRIVAL_PERIODIC_SERVER_SYNC_SCHEMA_VERSION,
        classification: ARRIVAL_PERIODIC_SERVER_SYNC_CLASSIFICATION,
        profileId: ARRIVAL_PERIODIC_SERVER_SYNC_PROFILE_ID,
        protocolVersion: ARRIVAL_PERIODIC_SERVER_SYNC_PROTOCOL_VERSION,
        packetId: ARRIVAL_PERIODIC_SERVER_SYNC_PACKET_ID,
        nominalGapMillis: ARRIVAL_PERIODIC_SERVER_SYNC_NOMINAL_GAP_MILLIS,
        toleranceMillis: ARRIVAL_PERIODIC_SERVER_SYNC_TOLERANCE_MILLIS,
        excludedFromUserVisibleStall:
            ARRIVAL_PERIODIC_SERVER_SYNC_EXCLUDED_FROM_USER_VISIBLE_STALL,
        strictGateImpact: "none",
        diagnosticOnly: true,
        strictGatesChanged: false,
    };
}

function browserFullPathPerformanceContract() {
    return {
        mode: externalMode
            ? "external-full-path"
            : acceptanceMode
                ? "strict-acceptance"
                : stressMode ? `stress-tier-${stressTier}` : "compatible-smoke",
        externalRelay: externalMode ? {
            relayUrl: externalRelayUrl,
            target: externalTarget?.text,
            runtimeTelemetryRequired: relayRuntimeTelemetryRequired,
            note: relayRuntimeTelemetryRequired
                ? "Strict acceptance requires complete RelayNode runtime gauges and zero-state cleanup"
                : "Compatible external smoke records runtime gauges when available; use --acceptance for release evidence",
        } : null,
        configurationPrompts: {
            autoAccept: acceptServerPrompts,
            requestedDialogAction: requestedDialogAction ?? null,
            inputOverridesProvided: Object.keys(dialogInputValues).length > 0,
            supportedPackets: ["clientboundShowDialog", "clientboundCodeOfConduct"],
        },
        clientStartDelayMillis: clientStartDelayMs,
        strictAcceptanceTarget: acceptanceMode ? { ...STRICT_ACCEPTANCE_TARGET } : null,
        stressTarget: stressMode ? { tier: stressTier, ...stressTarget } : null,
        canonicalProfiles: CANONICAL_PROFILES,
        browserChannelSourceEvidence: browserChannelSourceEvidenceForOutput(),
        relayRuntimeGauges: [...RELAY_RUNTIME_GAUGES],
        relayRuntimeConnectionGauges: [...RELAY_RUNTIME_CONNECTION_GAUGES],
        relayDrainMaxDurationMillis: RELAY_DRAIN_MAX_DURATION_MILLIS,
        relayDrainSendErrors: 0,
        relayDrainCleanupRequired: true,
        arrivalTimeline: {
            schemaVersion: ARRIVAL_TIMELINE_SCHEMA_VERSION,
            wireAtSource: ARRIVAL_WIRE_AT_SOURCE,
            periodicServerSync: arrivalPeriodicServerSyncContract(),
            slowThresholdMillis: ARRIVAL_TIMELINE_SLOW_THRESHOLD_MILLIS,
            perClientSlowSampleLimit: ARRIVAL_TIMELINE_SAMPLE_LIMIT,
            perClientReconnectPhaseLimit: ARRIVAL_TIMELINE_RECONNECT_PHASE_LIMIT,
            frameMetadataRingLimit: ARRIVAL_TIMELINE_FRAME_RING_LIMIT,
            trace: arrivalTraceContract(),
            wireAtPolicy: "null-when-unavailable",
            attributionPolicy:
                "trusted-wire-required-for-upstream; missing-local-segments=>unattributed",
            strictGatesChanged: false,
        },
        pollPhaseTelemetry: {
            schemaVersion: POLL_PHASE_TELEMETRY_SCHEMA_VERSION,
            segmentAccounting: POLL_PHASE_SEGMENT_ACCOUNTING,
            slowThresholdMillis: POLL_PHASE_SLOW_THRESHOLD_MILLIS,
            sampleLimit: POLL_PHASE_SAMPLE_LIMIT,
            frameSampleLimit: POLL_PHASE_FRAME_SAMPLE_LIMIT,
            packetSampleLimit: POLL_PHASE_PACKET_SAMPLE_LIMIT,
            diagnosticOnly: true,
            strictGatesChanged: false,
            independentExecution: false,
            retention: "longest-duration-desc-sequence-asc",
        },
        callbackFinalizationTail: callbackFinalizationTailContract(),
        multiplayerPerformance: { ...MULTIPLAYER_PERFORMANCE_TARGET },
        pollScheduler: {
            schemaVersion: "gaius.browser-client-poll-scheduler.v1",
            mode: "round-robin-bounded-batch-per-macrotask",
            maxBatchClients: MAX_CLIENTS_PER_POLL_CALLBACK,
            callbackWorkBudgetMillis: MAX_POLL_CALLBACK_WORK_MILLIS,
            callbackBudgetCoversPlayTicks: true,
            idleImmediateWindowMillis:
                POLL_SCHEDULER_IDLE_IMMEDIATE_WINDOW_MILLIS,
            idleImmediateProbeBudgetMillis:
                POLL_SCHEDULER_IDLE_IMMEDIATE_PROBE_BUDGET_MILLIS,
            idleImmediateProbeSpinLimit:
                POLL_SCHEDULER_IDLE_IMMEDIATE_SPIN_LIMIT,
            maxPlayTicksPerSchedulerCallback:
                MAX_PLAY_TICKS_PER_SCHEDULER_CALLBACK,
            maxVisibleDispatchSkew: MAX_VISIBLE_DISPATCH_SKEW,
            strictEventLoopMaxMillis: stressMode
                ? STRESS_EVENT_LOOP_MAX_MILLIS
                : MULTIPLAYER_PERFORMANCE_TARGET.maxPollGapMillis,
            rawLatencyEvidence: true,
            callbackTail: {
                schemaVersion: CALLBACK_TAIL_TELEMETRY_SCHEMA_VERSION,
                slowThresholdMillis: CALLBACK_TAIL_SLOW_THRESHOLD_MILLIS,
                sampleLimit: CALLBACK_TAIL_SAMPLE_LIMIT,
                retention: "longest-duration-desc-sequence-asc",
                strictRawDurationGateMillis: 16.7,
            },
            schedulerGap: {
                schemaVersion: SCHEDULER_GAP_TELEMETRY_SCHEMA_VERSION,
                slowGapThresholdMillis: SCHEDULER_GAP_SLOW_THRESHOLD_MILLIS,
                strictCallbackThresholdMillis:
                    SCHEDULER_GAP_STRICT_CALLBACK_THRESHOLD_MILLIS,
                sampleLimit: SCHEDULER_GAP_SAMPLE_LIMIT,
                retention: SCHEDULER_GAP_RETENTION,
                diagnosticOnly: true,
                strictGatesChanged: false,
                strictRawDurationGateMillis:
                    SCHEDULER_GAP_STRICT_CALLBACK_THRESHOLD_MILLIS,
                triggerKinds: [
                    "inter-callback-gap",
                    "scheduled-delay",
                    "callback-duration",
                    "budget-reached",
                ],
                clockAnomalyField: "clockAnomaly",
                correlationFields: [
                    "previousCallbackSequence",
                    "previousCallbackStartedAtMillis",
                    "previousCallbackFinishedAtMillis",
                    "interCallbackGapRawMillis",
                ],
            },
            callbackFinalizationTail: callbackFinalizationTailContract(),
            dueState: "side-map-authoritative-client-property-mirror-only",
            immediateInboundPriority: "client-method-buffer-then-bridge",
        },
        stressLatencyDistribution: stressMode
            ? stressLatencyDistributionContract()
            : null,
        browserRuntimeCleanupGauges: [...BROWSER_RUNTIME_CLEANUP_GAUGES],
        browserGlobalPumpTelemetry: {
            source: "BrowserWebSocketChannel.pumpAllAndReportProgress",
            fields: [...BROWSER_GLOBAL_PUMP_TELEMETRY_FIELDS],
            maxTotalMillis: BROWSER_GLOBAL_PUMP_MAX_TOTAL_MILLIS,
            missingValue: null,
            note: "Generated Java telemetry is observed when present; absent fields remain null",
        },
        browserInboundFlowEvidence: {
            schemaVersion: "gaius.browser-inbound-flow-evidence.v1",
            windowSchemaVersion: BROWSER_INBOUND_FLOW_WINDOW_SCHEMA,
            fields: [...BROWSER_INBOUND_FLOW_EVIDENCE_FIELDS],
            windowStages: [...BROWSER_INBOUND_FLOW_WINDOW_STAGES],
            maximumContinuousPauseMillis:
                MULTIPLAYER_PERFORMANCE_TARGET.maxSoakPhaseStallMillis,
            requiredStages: [
                ...BROWSER_INBOUND_FLOW_WINDOW_STAGES,
            ],
            compatibilityAliases: {
                initial: "initialConnectThroughMinimumChunks",
                reconnectWaves: ["preDrop", "reconnectRecovery"],
                postSoak: "steadySoak",
                finalCleanup: "finalCleanup",
            },
        },
        syntheticMarkerLabel: "synthetic-inbound-marker",
        runtimeJavaPolicy: releaseEvidenceMode
            ? { ...STRICT_RUNTIME_JAVA_POLICY }
            : null,
        minimumChunkPackets,
        minimumChunkMetric: stressMode ? "unique-chunk-position" : "chunk-packet",
        chunkWindow: {
            clientViewDistance,
            serverViewDistance,
            effectiveRadius: effectiveChunkRadius,
            maximumUniqueChunkCapacity,
            initialDistanceContract: {
                source: "clientbound-login",
                packetId: activeProfile?.id === "1.21.11"
                    ? MINECRAFT_1_21_11.play.clientboundLogin
                    : MINECRAFT_26_2.play.clientboundLogin,
                fields: ["chunkRadius", "simulationDistance"],
            },
            observedDistancePackets: {
                cacheCenter: activeProfile?.id === "1.21.11"
                    ? MINECRAFT_1_21_11.play.clientboundSetChunkCacheCenter
                    : MINECRAFT_26_2.play.clientboundSetChunkCacheCenter,
                cacheRadius: activeProfile?.id === "1.21.11"
                    ? MINECRAFT_1_21_11.play.clientboundSetChunkCacheRadius
                    : MINECRAFT_26_2.play.clientboundSetChunkCacheRadius,
                simulationDistance: activeProfile?.id === "1.21.11"
                    ? MINECRAFT_1_21_11.play.clientboundSetSimulationDistance
                    : MINECRAFT_26_2.play.clientboundSetSimulationDistance,
            },
            observedDistanceContractRequiredBeforeCounting: stressMode,
        },
        chunkBatch: {
            clientboundFinishedPacketId: activeProfile?.id === "1.21.11"
                ? MINECRAFT_1_21_11.play.clientboundChunkBatchFinished
                : MINECRAFT_26_2.play.clientboundChunkBatchFinished,
            clientboundStartPacketId: activeProfile?.id === "1.21.11"
                ? MINECRAFT_1_21_11.play.clientboundChunkBatchStart
                : MINECRAFT_26_2.play.clientboundChunkBatchStart,
            serverboundAcknowledgementPacketId: activeProfile?.id === "1.21.11"
                ? MINECRAFT_1_21_11.play.serverboundChunkBatchReceived
                : MINECRAFT_26_2.play.serverboundChunkBatchReceived,
            desiredChunksPerTick,
            acknowledgementEncoding: "float32-be",
        },
        soakMillis: soakMs,
        reconnectWaves,
        lifecycleCleanupRequired: true,
        reconnect: {
            simultaneousDrop: true,
            abnormalTransportDrop: true,
            transportCloseErrorRetained: true,
            syntheticInboundMarkerRetained: true,
            javaFinalCloseAfterTransportDrop: true,
            freshChannelIds: true,
            sameAccountIdentity: true,
            freshProtocolBuffers: true,
            freshEncryptionState: true,
            requiredSessionChecksPerClientPerWave: {
                joins: 1,
                hasJoined: 1,
            },
            requiredMilestonesPerWave: [
                "abnormal-transport-drop",
                "close-error-retained",
                "synthetic-inbound-marker-retained",
                "java-final-close-all-zero",
                "relay-connected",
                "login-finished",
                "configuration-finished",
                "play-login",
                "first-chunk",
                `chunk-${minimumChunkPackets}`,
            ],
        },
        requiredMilestones: [
            "relay-connected",
            "login-finished",
            "configuration-finished",
            "play-login",
            "first-chunk",
            `chunk-${minimumChunkPackets}`,
        ],
    };
}

function stressLatencyDistributionContract() {
    return {
        schemaVersion: STRESS_LATENCY_DISTRIBUTION_TARGET.schemaVersion,
        histogramSchemaVersion:
            STRESS_LATENCY_DISTRIBUTION_TARGET.histogramSchemaVersion,
        bucketUpperBoundsMillis: [
            ...STRESS_LATENCY_DISTRIBUTION_TARGET.bucketUpperBoundsMillis,
        ],
        pollGap: { ...STRESS_LATENCY_DISTRIBUTION_TARGET.pollGap },
        playTickGap: { ...STRESS_LATENCY_DISTRIBUTION_TARGET.playTickGap },
        preMinimumChunkGap: {
            ...STRESS_LATENCY_DISTRIBUTION_TARGET.preMinimumChunkGap,
        },
        storage: "fixed-bucket-counts-only",
        rawSamplesRetained: false,
    };
}

async function fetchRelayRuntime(port, targetPort) {
    const runtimeUrl = new URL(`http://127.0.0.1:${port}/relay-node/v1`);
    if (Number.isInteger(targetPort)) {
        runtimeUrl.searchParams.set("host", "127.0.0.1");
        runtimeUrl.searchParams.set("port", String(targetPort));
    }
    const response = await fetch(runtimeUrl, {
        headers: {
            origin,
            authorization: `Bearer ${relayToken}`,
        },
    });
    if (!response.ok) {
        throw new Error(`RelayNode runtime manifest returned ${response.status}`);
    }
    const manifest = await response.json();
    if (!manifest?.capabilities?.includes("runtime-telemetry") ||
        !Number.isSafeInteger(manifest.runtime?.rssBytes) ||
        !Number.isSafeInteger(manifest.runtime?.cpuUserMicros) ||
        !Number.isSafeInteger(manifest.runtime?.cpuSystemMicros)) {
        throw new Error("RelayNode runtime manifest omitted bounded performance telemetry");
    }
    if (!Number.isSafeInteger(manifest.activeConnections) ||
        !Number.isSafeInteger(manifest.availableConnections)) {
        throw new Error("RelayNode runtime manifest omitted active connection gauges");
    }
    const targetManifest = Number.isInteger(targetPort) ? manifest.target : undefined;
    const target = targetManifest !== undefined &&
        Number.isSafeInteger(targetManifest.activeConnections) &&
        Number.isSafeInteger(targetManifest.totalConnections) &&
        typeof targetManifest.recentlyReachable === "boolean" &&
        (targetManifest.lastSuccessAgeMs === null ||
            Number.isSafeInteger(targetManifest.lastSuccessAgeMs))
        ? targetManifest
        : undefined;
    const runtimeGaugeEvidence = relayRuntimeGaugeEvidence(manifest);
    const runtimeConnectionGaugeEvidence = relayRuntimeConnectionGaugeEvidence(manifest);
    if (releaseEvidenceMode &&
            (!runtimeGaugeEvidence.available || !runtimeConnectionGaugeEvidence.available)) {
        throw new Error("strict acceptance requires every RelayNode runtime gauge: " +
            JSON.stringify({ runtimeGaugeEvidence, runtimeConnectionGaugeEvidence }));
    }
    return {
        activeConnections: manifest.activeConnections,
        availableConnections: manifest.availableConnections,
        target: target === undefined ? undefined : {
            activeConnections: target.activeConnections,
            totalConnections: target.totalConnections,
            recentlyReachable: target.recentlyReachable,
            lastSuccessAgeMs: target.lastSuccessAgeMs,
        },
        targetEvidence: {
            source: "/relay-node/v1?host=127.0.0.1&port=target",
            available: target !== undefined,
            observed: targetManifest === undefined ? null : targetManifest,
            note: target === undefined
                ? "RelayNode target route telemetry was absent or partial; no zero was synthesized"
                : "RelayNode target route fields observed directly",
        },
        runtime: manifest.runtime,
        runtimeGaugeEvidence,
        runtimeConnectionGaugeEvidence,
    };
}

async function fetchExternalRelayRuntime(relayUrl, target, token) {
    const runtimeUrl = new URL(relayUrl);
    runtimeUrl.protocol = runtimeUrl.protocol === "wss:" ? "https:" : "http:";
    runtimeUrl.pathname = "/relay-node/v1";
    runtimeUrl.search = "";
    runtimeUrl.searchParams.set("host", target.host);
    runtimeUrl.searchParams.set("port", String(target.port));
    const headers = { origin };
    if (token !== undefined && token.length > 0) {
        headers.authorization = `Bearer ${token}`;
    }
    const response = await fetch(runtimeUrl, {
        headers,
        signal: AbortSignal.timeout(15000),
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
        throw new Error(`External RelayNode runtime manifest returned ${response.status}`);
    }
    const targetManifest = body?.target;
    const targetAvailable = targetManifest !== undefined &&
        Number.isSafeInteger(targetManifest.activeConnections) &&
        Number.isSafeInteger(targetManifest.totalConnections) &&
        typeof targetManifest.recentlyReachable === "boolean" &&
        (targetManifest.lastSuccessAgeMs === null ||
            Number.isSafeInteger(targetManifest.lastSuccessAgeMs));
    const activeConnections = Number.isSafeInteger(body?.activeConnections)
        ? body.activeConnections : undefined;
    const runtime = body?.runtime !== undefined && typeof body.runtime === "object"
        ? body.runtime : undefined;
    const runtimeGaugeEvidence = relayRuntimeGaugeEvidence({ runtime });
    const runtimeConnectionGaugeEvidence = relayRuntimeConnectionGaugeEvidence({ runtime });
    if (releaseEvidenceMode) {
        if (!body?.capabilities?.includes("runtime-telemetry") ||
            !Number.isSafeInteger(runtime?.rssBytes) ||
            !Number.isSafeInteger(runtime?.cpuUserMicros) ||
            !Number.isSafeInteger(runtime?.cpuSystemMicros)) {
            throw new Error("strict acceptance requires bounded external RelayNode performance telemetry");
        }
        if (!Number.isSafeInteger(activeConnections) || !targetAvailable) {
            throw new Error("strict acceptance requires complete external RelayNode connection telemetry");
        }
        if (!runtimeGaugeEvidence.available || !runtimeConnectionGaugeEvidence.available) {
            throw new Error("strict acceptance requires every external RelayNode runtime gauge: " +
                JSON.stringify({ runtimeGaugeEvidence, runtimeConnectionGaugeEvidence }));
        }
    }
    return {
        external: true,
        activeConnections,
        availableConnections: Number.isSafeInteger(body?.availableConnections)
            ? body.availableConnections : undefined,
        target: targetAvailable ? {
            activeConnections: targetManifest.activeConnections,
            totalConnections: targetManifest.totalConnections,
            recentlyReachable: targetManifest.recentlyReachable,
            lastSuccessAgeMs: targetManifest.lastSuccessAgeMs,
        } : undefined,
        targetEvidence: {
            source: runtimeUrl.href,
            available: targetAvailable,
            observed: targetManifest ?? null,
            note: targetAvailable
                ? "External RelayNode target route fields observed directly"
                : "External RelayNode omitted complete target route fields",
        },
        runtime,
        runtimeGaugeEvidence,
        runtimeConnectionGaugeEvidence,
        raw: body,
    };
}

async function waitForRelayRuntimeReader(reader, predicate, label, timeoutMillis) {
    const deadline = Date.now() + timeoutMillis;
    let snapshot;
    while (true) {
        snapshot = await reader();
        if (predicate(snapshot)) return snapshot;
        if (Date.now() >= deadline) {
            throw new Error(`Timed out waiting for ${label}: ${JSON.stringify(snapshot)}`);
        }
        await delay(100);
    }
}

async function waitForRelayRuntime(port, predicate, label, timeoutMillis, targetPort) {
    const deadline = Date.now() + timeoutMillis;
    let snapshot;
    while (true) {
        snapshot = await fetchRelayRuntime(port, targetPort);
        if (predicate(snapshot)) return snapshot;
        if (Date.now() >= deadline) {
            throw new Error(`Timed out waiting for ${label}: ${JSON.stringify(snapshot)}`);
        }
        await delay(20);
    }
}

function relayRuntimeDelta(before, after) {
    if (!before?.runtime || !after?.runtime) return null;
    const elapsed = Math.max(0,
        after.runtime.uptimeMillis - before.runtime.uptimeMillis);
    const userMicros = Math.max(0,
        after.runtime.cpuUserMicros - before.runtime.cpuUserMicros);
    const systemMicros = Math.max(0,
        after.runtime.cpuSystemMicros - before.runtime.cpuSystemMicros);
    const cpuMicros = userMicros + systemMicros;
    return {
        elapsedMillis: elapsed,
        cpuUserMicros: userMicros,
        cpuSystemMicros: systemMicros,
        cpuTotalMicros: cpuMicros,
        cpuPercentOfOneCore: elapsed > 0
            ? Number((cpuMicros / (elapsed * 10)).toFixed(3))
            : null,
        rssDeltaBytes: after.runtime.rssBytes - before.runtime.rssBytes,
        heapUsedDeltaBytes:
            after.runtime.heapUsedBytes - before.runtime.heapUsedBytes,
        externalDeltaBytes:
            after.runtime.externalBytes - before.runtime.externalBytes,
    };
}

async function printConfiguration() {
    activeProfile = await loadActiveVersionProfile();
    assertCanonicalProfile(activeProfile);
    const browserChannelSource = await loadBrowserChannelSourceEvidence();
    const wireProfile = resolveWireProfile(activeProfile);
    const identities = Array.from({ length: clientCount }, (_, index) =>
        createClientIdentity(index));
    console.log(JSON.stringify({
        profile: {
            id: activeProfile.id,
            protocolVersion: activeProfile.protocolVersion,
            worldVersion: activeProfile.worldVersion,
            javaVersion: activeProfile.javaVersion,
            serverSha1: activeProfile.official.serverSha1,
            expectedServerJarSha1: activeProfile.official.serverSha1,
            path: activeProfile.path,
            canonicalProfilePath: repositoryRelativePath(activeProfile.path),
        },
        wireProfile: {
            name: wireProfile.name,
            protocolVersion: wireProfile.protocolVersion,
        },
        clients: clientCount,
        acceptanceMode,
        stressMode,
        stressTier: stressTier ?? null,
        strictAcceptanceTarget: acceptanceMode ? STRICT_ACCEPTANCE_TARGET : null,
        stressTarget: stressMode ? stressTarget : null,
        identityContract: {
            explicitProfileUsernameMap: true,
            uniqueProfiles: new Set(identities.map(({ profileId }) => profileId)).size,
            uniqueUsernames: new Set(identities.map(({ username }) => username)).size,
            identities,
        },
        browserChannelSourceEvidence: browserChannelSource.evidence,
        callbackFinalizationTail: callbackFinalizationTailContract(),
        arrivalTimeline: {
            schemaVersion: ARRIVAL_TIMELINE_SCHEMA_VERSION,
            periodicServerSync: arrivalPeriodicServerSyncContract(),
            slowThresholdMillis: ARRIVAL_TIMELINE_SLOW_THRESHOLD_MILLIS,
            perClientSlowSampleLimit: ARRIVAL_TIMELINE_SAMPLE_LIMIT,
            perClientReconnectPhaseLimit: ARRIVAL_TIMELINE_RECONNECT_PHASE_LIMIT,
            frameMetadataRingLimit: ARRIVAL_TIMELINE_FRAME_RING_LIMIT,
            trace: arrivalTraceContract(),
            wireAtPolicy: "null-when-unavailable",
            attributionPolicy:
                "trusted-wire-required-for-upstream; missing-local-segments=>unattributed",
            strictGatesChanged: false,
        },
        performanceContract: browserFullPathPerformanceContract(),
    }));
}

async function printJavaResolution() {
    activeProfile = await loadActiveVersionProfile();
    assertCanonicalProfile(activeProfile);
    const runtimeJava = await resolveJavaExecutable(activeProfile);
    console.log(JSON.stringify({
        profile: {
            id: activeProfile.id,
            javaVersion: activeProfile.javaVersion,
            path: repositoryRelativePath(activeProfile.path),
        },
        runtimeJavaMajor: runtimeJava.major,
        runtimeJavaExecutable: runtimeJava.executable,
        runtimeJavaSource: runtimeJava.source,
        runtimeJavaPolicy: releaseEvidenceMode
            ? STRICT_RUNTIME_JAVA_POLICY[activeProfile.id] ?? null
            : `major-at-least-${activeProfile.javaVersion}`,
    }));
}

async function createBrowserRuntime(relayUrl, token) {
    const browserChannelSource = await loadBrowserChannelSourceEvidence();
    const source = browserChannelSource.source;
    const init = extractJsBody(source, "private static native void initBridge();");
    const initTail = extractJsBody(source,
        "private static native void initBridgeTail();");
    const outbound = extractJsBody(source,
        "private static native void initOutboundScheduler();");
    const inbound = extractJsBody(source,
        "private static native void initInboundScheduler();");
    assert.match(init, /state\.relayNodeRecordResolver/,
        "initBridge lost the shared relayNodeRecord resolver read marker");
    assert.match(initTail, /state\.relayNodeRecordResolver\s*=\s*relayNodeRecord/,
        "initBridgeTail lost the shared relayNodeRecord resolver publish marker");
    const wsStats = {
        connections: 0,
        controlFrames: 0,
        binaryFrames: 0,
        binaryBytes: 0,
        urls: [],
        sockets: new Set(),
    };
    const arrivalTelemetry = {
        schemaVersion: ARRIVAL_TIMELINE_SCHEMA_VERSION,
        independentExecution: false,
        slowThresholdMillis: ARRIVAL_TIMELINE_SLOW_THRESHOLD_MILLIS,
        traceSchemaVersion: ARRIVAL_TRACE_SCHEMA_VERSION,
        traceEventLimit: ARRIVAL_TRACE_EVENT_LIMIT,
        traceEnabled: ARRIVAL_TRACE_ENABLED,
        binaryOnmessageFrames: 0,
        binaryOnmessageBytes: 0,
        bridgeDequeuedFrames: 0,
        bridgeDequeuedBytes: 0,
        frameMetadataDropped: 0,
        syntheticDequeuedFrames: 0,
        syntheticDequeuedBytes: 0,
        syntheticSockets: new Set(),
        evidence() {
            return {
                schemaVersion: this.schemaVersion,
                independentExecution: this.independentExecution,
                strictGatesChanged: false,
                slowThresholdMillis: this.slowThresholdMillis,
                traceSchemaVersion: this.traceSchemaVersion,
                traceEventLimit: this.traceEventLimit,
                traceEnabled: ARRIVAL_TRACE_ENABLED,
                limits: {
                    frameMetadataRing: ARRIVAL_TIMELINE_FRAME_RING_LIMIT,
                    perClientSlowSamples: ARRIVAL_TIMELINE_SAMPLE_LIMIT,
                    perClientReconnectPhases: ARRIVAL_TIMELINE_RECONNECT_PHASE_LIMIT,
                    traceEvents: ARRIVAL_TRACE_EVENT_LIMIT,
                },
                trace: arrivalTraceContract(),
                source: {
                    wireTimestampAvailable: false,
                    wireAtSource: "unavailable",
                    onmessageTimestampAvailable: this.binaryOnmessageFrames > 0,
                    bridgeEnqueueTimestampAvailable: false,
                    bridgeDequeueTimestampAvailable: this.bridgeDequeuedFrames > 0,
                    decodeTimestampAvailable: false,
                    dispatchTimestampAvailable: false,
                    wireAtPolicy: "null-when-unavailable",
                    attributionPolicy:
                        "trusted-wire-required-for-upstream; missing-local-segments=>unattributed",
                    frameCorrelation: "websocket-message-to-bridge-chunk-best-effort",
                    correlationExact: false,
                },
                periodicServerSync: arrivalPeriodicServerSyncContract(),
                binaryOnmessageFrames: this.binaryOnmessageFrames,
                binaryOnmessageBytes: this.binaryOnmessageBytes,
                bridgeDequeuedFrames: this.bridgeDequeuedFrames,
                bridgeDequeuedBytes: this.bridgeDequeuedBytes,
                frameMetadataDropped: this.frameMetadataDropped,
                syntheticDequeuedFrames: this.syntheticDequeuedFrames,
                syntheticDequeuedBytes: this.syntheticDequeuedBytes,
                syntheticMarkerDequeuesExcluded: true,
            };
        },
    };
    class BrowserWebSocket extends NodeWebSocket {
        constructor(url) {
            super(url, { origin });
            const arrivalState = createArrivalSocketState();
            Object.defineProperty(this, "__gaiusArrivalState", {
                configurable: false,
                enumerable: false,
                value: arrivalState,
                writable: false,
            });
            // Register before BrowserWebSocketChannel assigns ws.onmessage so
            // this listener observes the raw binary delivery first.  It only
            // updates primitive ring slots; protocol handling remains wholly
            // owned by the bridge's original onmessage callback.
            this.on("message", (data, isBinary) => {
                recordArrivalSocketMessage(arrivalState, data, isBinary,
                    arrivalTelemetry);
            });
            wsStats.connections++;
            wsStats.urls.push(String(url));
            wsStats.sockets.add(this);
            this.once("close", () => wsStats.sockets.delete(this));
        }

        send(data, ...rest) {
            if (typeof data === "string") {
                try {
                    const message = JSON.parse(data);
                    if (message?.type === "connect") wsStats.controlFrames++;
                }
                catch {}
            }
            else {
                wsStats.binaryFrames++;
                wsStats.binaryBytes += data?.byteLength ?? 0;
            }
            return super.send(data, ...rest);
        }
    }
    globalThis.window = globalThis;
    Object.defineProperty(globalThis, "location", {
        configurable: true,
        value: {
            href: "http://127.0.0.1:8781/Gaius.html",
            hostname: "127.0.0.1",
            protocol: "http:",
            search: "",
        },
    });
    globalThis.localStorage = { getItem: () => null };
    globalThis.WebSocket = BrowserWebSocket;
    globalThis.__gaiusDefaultRelayRegistries = false;
    globalThis.__gaiusRelayRegistryUrls = [];
    globalThis.__gaiusDirectPlugin = false;
    globalThis.__gaiusBridgeUrl = relayUrl;
    globalThis.__gaiusBridgeToken = token;
    globalThis.__gaiusBridgeUrls = [{
        name: externalMode ? "external full-path RelayNode" : "local full-path RelayNode",
        url: relayUrl,
        token,
        priority: 100,
    }];
    new Function(init)();
    new Function(initTail)();
    new Function(outbound)();
    new Function(inbound)();
    const bridge = globalThis.__gaiusNettyBridge;
    const stats = globalThis.__gaiusNetworkStats;
    assert.ok(bridge && stats, "BrowserWebSocketChannel JSBody did not initialize");
    assert.equal(typeof bridge.relayNodeRecordResolver, "function",
        "BrowserWebSocketChannel JSBody did not publish relayNodeRecord resolver state");
    assert.equal(typeof bridge.pollInbound, "function",
        "BrowserWebSocketChannel JSBody did not expose pollInbound");
    const originalPollInbound = bridge.pollInbound;
    bridge.pollInbound = (id) => {
        const polledAt = performance.now();
        const chunk = originalPollInbound.call(bridge, id);
        if (chunk !== null) {
            const entry = bridge.channels.get(Number(id));
            recordArrivalBridgeDequeue(entry?.ws, polledAt, chunk, arrivalTelemetry);
        }
        return chunk;
    };
    return {
        bridge,
        stats,
        wsStats,
        arrivalTelemetry,
        browserChannelSourceEvidence: browserChannelSource.evidence,
        async close() {
            const sockets = [...wsStats.sockets];
            const closed = Promise.all(sockets.map((socket) =>
                socket.readyState === NodeWebSocket.CLOSED
                    ? Promise.resolve()
                    : once(socket, "close").catch(() => {})));
            for (const id of [...bridge.channels.keys()]) {
                try { bridge.close(id); } catch {}
            }
            await Promise.race([
                closed,
                delay(1000),
            ]);
            for (const socket of wsStats.sockets) {
                try { socket.terminate(); } catch {}
            }
        },
    };
}

async function loadBrowserChannelSourceEvidence() {
    if (browserChannelSourceCache !== undefined) return browserChannelSourceCache;
    const source = await readFile(channelSourceUrl, "utf8");
    const normalizedSource = source.replaceAll("\r\n", "\n").replaceAll("\r", "\n");
    const markers = BROWSER_CHANNEL_SOURCE_MARKERS.map(({name, marker, jsBody}) => {
        const offset = source.indexOf(marker);
        assert.ok(offset >= 0,
            `BrowserWebSocketChannel source marker missing: ${name}`);
        const entry = { name, marker, present: true };
        if (jsBody) {
            const script = extractJsBody(source, marker);
            entry.jsBodyBytes = Buffer.byteLength(script, "utf8");
            entry.jsBodySha256 = createHash("sha256").update(script).digest("hex");
            entry.jsBodyNormalizedSha256 = createHash("sha256")
                .update(script.replaceAll("\r\n", "\n").replaceAll("\r", "\n"))
                .digest("hex");
        }
        return entry;
    });
    const jsBodyMarkers = markers
        .filter((marker) => marker.jsBodySha256 !== undefined)
        .map((marker) => ({
            name: marker.name,
            marker: marker.marker,
            present: marker.present,
            jsBodyBytes: marker.jsBodyBytes,
            jsBodySha256: marker.jsBodySha256,
            jsBodyNormalizedSha256: marker.jsBodyNormalizedSha256,
        }));
    const evidence = Object.freeze({
        schemaVersion: BROWSER_CHANNEL_SOURCE_EVIDENCE_SCHEMA_VERSION,
        independentExecution: true,
        path: fileURLToPath(channelSourceUrl),
        relativePath: repositoryRelativePath(fileURLToPath(channelSourceUrl)),
        hashAlgorithm: "sha256",
        sourceBytes: Buffer.byteLength(source, "utf8"),
        sourceSha256: createHash("sha256").update(source).digest("hex"),
        normalizedSourceBytes: Buffer.byteLength(normalizedSource, "utf8"),
        normalizedSourceSha256: createHash("sha256")
            .update(normalizedSource)
            .digest("hex"),
        requiredMarkerCount: markers.length,
        sourceMarkers: markers.map((marker) => ({
            name: marker.name,
            marker: marker.marker,
            present: marker.present,
            ...(marker.jsBodySha256 === undefined ? {} : {
                jsBodySha256: marker.jsBodySha256,
                jsBodyNormalizedSha256: marker.jsBodyNormalizedSha256,
            }),
        })),
        jsBodyMarkers,
    });
    browserChannelSourceCache = Object.freeze({source, evidence});
    return browserChannelSourceCache;
}

function browserChannelSourceEvidenceForOutput() {
    return browserChannelSourceCache?.evidence ?? null;
}

function extractJsBody(source, marker) {
    const markerOffset = source.indexOf(marker);
    const annotationOffset = source.lastIndexOf("@JSBody(script = \"\"\"", markerOffset);
    const scriptOffset = source.indexOf("\"\"\"", annotationOffset) + 3;
    const firstScriptEnd = source.indexOf('\"\"\")', scriptOffset);
    // Method signatures occur after their text block, while resolver markers
    // live inside the JSBody text itself. Select the enclosing terminator in
    // both cases without changing the extracted script bytes.
    const scriptEnd = markerOffset > scriptOffset && markerOffset < firstScriptEnd
        ? firstScriptEnd
        : source.lastIndexOf('\"\"\")', markerOffset);
    assert.ok(markerOffset > 0 && annotationOffset > 0 && scriptEnd > scriptOffset,
        `could not extract BrowserWebSocketChannel JSBody for ${marker}`);
    return source.slice(scriptOffset, scriptEnd);
}

function createSessionServer(state) {
    return createHttpServer(async (request, response) => {
        try {
            const url = new URL(request.url ?? "/", "http://127.0.0.1");
            if (request.method === "GET" && url.pathname === "/publickeys") {
                state.publicKeyRequests++;
                sendJson(response, 200, { profilePropertyKeys: [], playerCertificateKeys: [] });
                return;
            }
            if (request.method === "POST" && url.pathname === "/session/minecraft/join") {
                const body = JSON.parse((await readBody(request)).toString("utf8"));
                const expectedProfile = state.expectedProfiles.get(body.accessToken);
                if (expectedProfile === undefined || body.selectedProfile !== expectedProfile ||
                    typeof body.serverId !== "string" || body.serverId.length === 0) {
                    sendJson(response, 403, { error: "invalid smoke join" });
                    return;
                }
                state.joins.push(body);
                response.writeHead(204);
                response.end();
                return;
            }
            if (request.method === "GET" && url.pathname === "/session/minecraft/hasJoined") {
                const query = Object.fromEntries(url.searchParams);
                state.hasJoined.push(query);
                const join = [...state.joins].reverse().find((candidate) =>
                    candidate.serverId === query.serverId &&
                    query.username === profileUsernameForId(state, candidate.selectedProfile));
                if (join === undefined) {
                    response.writeHead(204);
                    response.end();
                    return;
                }
                sendJson(response, 200, {
                    id: join.selectedProfile,
                    name: query.username,
                    properties: [],
                    profileActions: [],
                });
                return;
            }
            if (request.method === "GET" &&
                url.pathname.startsWith("/session/minecraft/profile/")) {
                const id = url.pathname.slice("/session/minecraft/profile/".length);
                sendJson(response, 200, {
                    id,
                    name: profileUsernameForId(state, id),
                    properties: [],
                    profileActions: [],
                });
                return;
            }
            sendJson(response, 404, { error: "not found" });
        }
        catch (error) {
            sendJson(response, 500, { error: String(error) });
        }
    });
}

function profileUsernameForId(state, id) {
    const username = state.profileUsernames.get(String(id));
    if (username === undefined) {
        throw new Error(`Session server received unknown profile id ${id}`);
    }
    return username;
}

function sendJson(response, status, value) {
    const body = Buffer.from(JSON.stringify(value));
    response.writeHead(status, {
        "content-type": "application/json",
        "content-length": String(body.byteLength),
    });
    response.end(body);
}

async function readBody(request) {
    const chunks = [];
    let bytes = 0;
    for await (const chunk of request) {
        bytes += chunk.byteLength;
        if (bytes > 1024 * 1024) throw new Error("request body too large");
        chunks.push(chunk);
    }
    return Buffer.concat(chunks, bytes);
}

function serverProperties(port) {
    return [
        "accepts-transfers=false",
        "allow-flight=true",
        "allow-nether=false",
        "difficulty=peaceful",
        "enable-command-block=false",
        "enable-query=false",
        "enable-rcon=false",
        "enforce-secure-profile=false",
        "gamemode=creative",
        "generate-structures=false",
        "level-name=world-browser-relay",
        "level-seed=1",
        "level-type=minecraft:flat",
        "log-ips=false",
        `max-players=${stressMode ? clientCount : 4}`,
        "motd=Gaius browser RelayNode full-path smoke",
        "network-compression-threshold=256",
        "online-mode=true",
        "pause-when-empty-seconds=0",
        "player-idle-timeout=0",
        "prevent-proxy-connections=false",
        "pvp=false",
        "rate-limit=0",
        "server-ip=127.0.0.1",
        `server-port=${port}`,
        `simulation-distance=${stressTarget?.simulationDistance ?? 2}`,
        "spawn-animals=false",
        "spawn-monsters=false",
        "spawn-npcs=false",
        "spawn-protection=0",
        "sync-chunk-writes=false",
        "use-native-transport=false",
        `view-distance=${serverViewDistance}`,
        "white-list=false",
        "",
    ].join("\n");
}

function nativePath(value) {
    const text = String(value ?? "").trim().replaceAll("\\", "/");
    if (process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/u.test(text)) {
        return `${text[1].toUpperCase()}:${text.slice(2)}`;
    }
    return text;
}

function resolveRepositoryPath(value) {
    const normalized = nativePath(value);
    if (!normalized) throw new Error("Configured path must not be empty");
    return path.isAbsolute(normalized)
        ? path.resolve(normalized)
        : path.resolve(repository, normalized);
}

function repositoryRelativePath(value) {
    const relative = path.relative(repository, path.resolve(value))
        .replaceAll(path.sep, "/");
    assert.ok(relative !== "" && relative !== "." &&
        !relative.startsWith("../") && relative !== ".." &&
        !path.isAbsolute(relative),
    `path is outside the repository: ${value}`);
    return relative;
}

function pathInside(parent, child) {
    const relative = path.relative(path.resolve(parent), path.resolve(child));
    return relative === "" || (relative !== ".." &&
        !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative));
}

async function loadActiveVersionProfile() {
    const config = JSON.parse(await readFile(path.join(repository, "port", "config.json"), "utf8"));
    let selected = nativePath(
        process.env.GAIUS_VERSION_PROFILE_PATH ?? config.versionProfile ?? "");
    if (!selected) throw new Error("GAIUS_VERSION_PROFILE_PATH or port/config.json.versionProfile is required");
    if (/^\d+(?:\.\d+)+$/u.test(selected)) selected = `versions/${selected}.json`;
    const relativeSelected = selected.replace(/^\.\//u, "");
    const profilePath = relativeSelected.startsWith("port/")
        ? resolveRepositoryPath(relativeSelected)
        : path.isAbsolute(relativeSelected)
            ? path.resolve(relativeSelected)
            : resolveRepositoryPath(`port/${relativeSelected}`);
    const versionsDirectory = path.join(repository, "port", "versions");
    if (!pathInside(versionsDirectory, profilePath) || !profilePath.endsWith(".json")) {
        throw new Error(`Active version profile must be a JSON file inside port/versions: ${profilePath}`);
    }
    const profile = JSON.parse(await readFile(profilePath, "utf8"));
    assert.ok(typeof profile.id === "string" && profile.id.length > 0 &&
        Number.isInteger(profile.protocolVersion) && Number.isInteger(profile.worldVersion) &&
        Number.isInteger(profile.javaVersion) &&
        typeof profile.official?.serverSha1 === "string" &&
        /^[0-9a-f]{40}$/iu.test(profile.official.serverSha1),
        `invalid version profile ${profilePath}`);
    if (releaseEvidenceMode && path.basename(profilePath) !== `${profile.id}.json`) {
        throw new Error(
            `strict acceptance profile basename must be exactly ${profile.id}.json: ` +
            `${path.basename(profilePath)}`,
        );
    }
    return {
        ...profile,
        path: profilePath,
        official: { ...profile.official, serverSha1: profile.official.serverSha1.toLowerCase() },
    };
}

function assertCanonicalProfile(profile) {
    const canonical = CANONICAL_PROFILES[profile.id];
    if (canonical === undefined) {
        if (releaseEvidenceMode) {
            throw new Error(`strict acceptance does not support profile ${profile.id}`);
        }
        return;
    }
    if (!releaseEvidenceMode) return;
    assert.equal(path.basename(profile.path), `${profile.id}.json`,
        `non-canonical strict acceptance profile basename ${profile.path}`);
    assert.equal(repositoryRelativePath(profile.path), `port/versions/${profile.id}.json`,
        `non-canonical strict acceptance profile path ${profile.path}`);
    assert.deepEqual({
        protocolVersion: profile.protocolVersion,
        worldVersion: profile.worldVersion,
        javaVersion: profile.javaVersion,
        serverSha1: profile.official.serverSha1,
    }, canonical, `non-canonical strict acceptance profile ${profile.id}`);
}

function resolveWireProfile(profile) {
    const resolved = [MINECRAFT_1_21_11, MINECRAFT_26_2]
        .find((candidate) => candidate.protocolVersion === profile.protocolVersion &&
            candidate.name === profile.id);
    assert.ok(resolved, `unsupported browser full-path profile ${profile.id}/${profile.protocolVersion}`);
    return resolved;
}

async function resolveVerifiedServerJar(profile) {
    const configured = process.env.GAIUS_BROWSER_FULL_PATH_SERVER_JAR?.trim() ||
        process.env.GAIUS_SMOKE_SERVER_JAR?.trim();
    const candidate = configured !== undefined
        ? resolveRepositoryPath(configured)
        : path.join(repository, "port", "target", profile.id,
            "multiplayer-smoke-server", "server.jar");
    const info = await lstat(candidate).catch((error) => {
        if (error?.code === "ENOENT") return undefined;
        throw error;
    });
    if (info === undefined || !info.isFile() || info.isSymbolicLink()) {
        throw new Error(`Verified vanilla server.jar is required at ${candidate}; ` +
            "set GAIUS_BROWSER_FULL_PATH_SERVER_JAR to an existing profile-scoped jar");
    }
    const sha1 = createHash("sha1").update(await readFile(candidate)).digest("hex");
    assert.equal(sha1, profile.official.serverSha1,
        `${profile.id} server.jar SHA-1 mismatch: ${sha1}`);
    return { path: candidate, sha1 };
}

async function reservePort() {
    const server = createTcpServer();
    await listen(server, 0, "127.0.0.1");
    const port = server.address().port;
    await new Promise((resolve) => server.close(resolve));
    return port;
}

async function listen(server, port, host) {
    await new Promise((resolve, reject) => {
        server.once("error", reject);
        server.listen(port, host, resolve);
    });
}

function runtimeJavaMajorMeetsPolicy(profile, major) {
    if (!Number.isSafeInteger(major)) return false;
    if (releaseEvidenceMode && profile.id === "1.21.11") return major === 21;
    return major >= profile.javaVersion;
}

function javaCandidateVariants(value) {
    if (typeof value !== "string" || value.trim() === "") return [];
    const normalized = nativePath(value.trim());
    const variants = [normalized];
    const baseName = path.basename(normalized).toLowerCase();
    if (baseName !== "java" && baseName !== "java.exe") {
        variants.push(path.join(normalized, "bin", "java"));
        variants.push(path.join(normalized, "bin", "java.exe"));
    }
    else if (path.extname(normalized) === "") {
        variants.push(`${normalized}.exe`);
    }
    return variants;
}

async function resolveJavaExecutable(profile) {
    const candidates = [];
    const addCandidate = (value, source) => {
        for (const candidate of javaCandidateVariants(value)) {
            candidates.push({ candidate, source });
        }
    };
    // The profile-specific variable is first and may name either a java
    // executable or a JDK home. This prevents a globally selected JDK from
    // silently satisfying the wrong Minecraft profile.
    const profileJavaVariable = `GAIUS_JAVA_${profile.javaVersion}`;
    addCandidate(process.env[profileJavaVariable], profileJavaVariable);
    addCandidate(process.env.GAIUS_JAVA, "GAIUS_JAVA");
    for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME]) {
        addCandidate(home, home === process.env.GAIUS_JAVA_HOME
            ? "GAIUS_JAVA_HOME" : "JAVA_HOME");
    }
    candidates.push(
        { candidate: "C:\\Program Files\\Java\\jdk-24\\bin\\java.exe", source: "known-jdk" },
        { candidate: "C:\\Program Files\\Java\\jdk-26.0.1\\bin\\java.exe", source: "known-jdk" },
        { candidate: "java", source: "PATH" },
    );
    const diagnostics = [];
    for (const { candidate, source } of candidates) {
        const result = await probeJava(candidate);
        if (result.error === undefined &&
            runtimeJavaMajorMeetsPolicy(profile, result.major)) {
            return {
                executable: result.executable ?? candidate,
                major: result.major,
                source,
            };
        }
        const version = result.error ?? `Java ${result.major}`;
        diagnostics.push(`${candidate} [${source}]: ${version}`);
    }
    const policy = releaseEvidenceMode && profile.id === "1.21.11"
        ? "Java 21 exactly"
        : `Java >= ${profile.javaVersion}`;
    throw new Error(`No ${policy} for ${profile.id}: ${diagnostics.join("; ")}`);
}

async function probeJava(candidate) {
    return await new Promise((resolve) => {
        let output = "";
        let settled = false;
        const finish = (value) => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            resolve(value);
        };
        const windowsScript = process.platform === "win32" &&
            /\.(?:cmd|bat)$/iu.test(candidate);
        const command = windowsScript
            ? (process.env.ComSpec ?? process.env.COMSPEC ?? "cmd.exe")
            : candidate;
        const argumentsList = windowsScript
            ? ["/d", "/c", candidate, "-version"]
            : ["-version"];
        const child = spawn(command, argumentsList, {
            stdio: ["ignore", "pipe", "pipe"],
            windowsHide: true,
            shell: false,
        });
        const timer = setTimeout(() => {
            try { child.kill(); } catch {}
            finish({ executable: candidate, error: "timed out" });
        }, 5000);
        child.stdout.setEncoding("utf8");
        child.stderr.setEncoding("utf8");
        child.stdout.on("data", (chunk) => { output += chunk; });
        child.stderr.on("data", (chunk) => { output += chunk; });
        child.once("error", (error) => {
            finish({ executable: candidate, error: error.message });
        });
        child.once("close", (code) => {
            if (code !== 0) {
                finish({ executable: candidate,
                    error: `exited ${code}: ${output.trim()}` });
                return;
            }
            const match = output.match(/version\s+["']?(\d+)/iu);
            finish(match ? { executable: candidate, major: Number(match[1]) } : {
                executable: candidate,
                error: `could not parse version: ${output.trim()}`,
            });
        });
    });
}

function minecraftServerHash(serverId, secret, publicKey) {
    const digest = createHash("sha1")
        .update(Buffer.from(serverId, "utf8"))
        .update(secret)
        .update(publicKey)
        .digest();
    let value = BigInt(`0x${digest.toString("hex")}`);
    if ((digest[0] & 0x80) !== 0) value -= 1n << BigInt(digest.byteLength * 8);
    return value.toString(16);
}

function encodePacket(id, payload = Buffer.alloc(0), compressionThreshold) {
    const packet = Buffer.concat([encodeVarInt(id), payload]);
    const body = compressionThreshold === undefined
        ? packet
        : packet.byteLength >= compressionThreshold
            ? Buffer.concat([encodeVarInt(packet.byteLength), deflateSync(packet)])
            : Buffer.concat([Buffer.from([0]), packet]);
    return Buffer.concat([encodeVarInt(body.byteLength), body]);
}

function parseTarget(value) {
    const text = String(value).trim();
    const separator = text.lastIndexOf(":");
    const host = separator > 0 ? text.slice(0, separator) : text;
    const port = separator > 0 ? Number(text.slice(separator + 1)) : 25565;
    assert.ok(host.length > 0 && Number.isInteger(port) && port >= 1 && port <= 65535,
        "GAIUS_EXTERNAL_TARGET must be host:port");
    return { host, port, text: `${host}:${port}` };
}

function decodeNetworkNbt(bytes) {
    const input = Buffer.from(bytes);
    let offset = 0;
    const maximumDepth = 32;
    const maximumCollectionLength = 65_536;

    function requireBytes(length, label) {
        if (length < 0 || offset + length > input.byteLength) {
            throw new Error(`Network NBT ${label} exceeded its packet`);
        }
    }

    function readUnsignedByte(label) {
        requireBytes(1, label);
        return input[offset++];
    }

    function readLength(label) {
        requireBytes(4, label);
        const length = input.readInt32BE(offset);
        offset += 4;
        if (length < 0 || length > maximumCollectionLength) {
            throw new Error(`Network NBT ${label} had invalid length ${length}`);
        }
        return length;
    }

    function readString(label) {
        requireBytes(2, `${label} length`);
        const length = input.readUInt16BE(offset);
        offset += 2;
        requireBytes(length, label);
        const value = input.toString("utf8", offset, offset + length);
        offset += length;
        return value;
    }

    function readPayload(type, depth) {
        if (depth > maximumDepth) throw new Error("Network NBT exceeded its maximum nesting depth");
        switch (type) {
            case 0:
                return null;
            case 1:
                requireBytes(1, "byte");
                return input.readInt8(offset++);
            case 2: {
                requireBytes(2, "short");
                const value = input.readInt16BE(offset);
                offset += 2;
                return value;
            }
            case 3: {
                requireBytes(4, "int");
                const value = input.readInt32BE(offset);
                offset += 4;
                return value;
            }
            case 4: {
                requireBytes(8, "long");
                const value = input.readBigInt64BE(offset).toString();
                offset += 8;
                return value;
            }
            case 5: {
                requireBytes(4, "float");
                const value = input.readFloatBE(offset);
                offset += 4;
                return value;
            }
            case 6: {
                requireBytes(8, "double");
                const value = input.readDoubleBE(offset);
                offset += 8;
                return value;
            }
            case 7: {
                const length = readLength("byte array");
                requireBytes(length, "byte array");
                const value = input.subarray(offset, offset + length);
                offset += length;
                return value;
            }
            case 8:
                return readString("string");
            case 9: {
                const childType = readUnsignedByte("list type");
                const length = readLength("list");
                if (childType === 0 && length !== 0) {
                    throw new Error("Network NBT used END as a non-empty list type");
                }
                return Array.from({ length }, () => readPayload(childType, depth + 1));
            }
            case 10: {
                const value = {};
                while (true) {
                    const childType = readUnsignedByte("compound type");
                    if (childType === 0) return value;
                    const name = readString("compound key");
                    if (Object.hasOwn(value, name)) {
                        throw new Error(`Network NBT repeated compound key ${name}`);
                    }
                    value[name] = readPayload(childType, depth + 1);
                }
            }
            case 11: {
                const length = readLength("int array");
                requireBytes(length * 4, "int array");
                return Array.from({ length }, () => {
                    const value = input.readInt32BE(offset);
                    offset += 4;
                    return value;
                });
            }
            case 12: {
                const length = readLength("long array");
                requireBytes(length * 8, "long array");
                return Array.from({ length }, () => {
                    const value = input.readBigInt64BE(offset).toString();
                    offset += 8;
                    return value;
                });
            }
            default:
                throw new Error(`Network NBT used unknown tag type ${type}`);
        }
    }

    const rootType = readUnsignedByte("root type");
    const value = readPayload(rootType, 0);
    if (offset !== input.byteLength) {
        throw new Error(`Network NBT left ${input.byteLength - offset} unread bytes`);
    }
    return value;
}

function inspectServerDialog(dialog) {
    const actionRoots = findNamedArrays(dialog, "actions");
    const inputRoots = findNamedArrays(dialog, "inputs");
    const actionIds = uniqueStrings(actionRoots.flatMap((root) =>
        collectNbtObjects(root)
            .filter((value) => value.type === "minecraft:dynamic/custom")
            .map((value) => value.id)
            .filter((value) => typeof value === "string")));
    const inputs = inputRoots.flatMap((root) => collectNbtObjects(root))
        .filter((value) => typeof value.key === "string" &&
            typeof value.type === "string");
    const inputDefinitions = [];
    const inputKeys = new Set();
    for (const input of inputs) {
        if (inputKeys.has(input.key)) continue;
        inputKeys.add(input.key);
        inputDefinitions.push({
            key: input.key,
            type: input.type,
            maxLength: Number.isInteger(input.max_length) ? input.max_length : undefined,
        });
    }
    const title = summarizeNbtValue(findFirstNamedValue(dialog, "title"));
    return {
        actionIds,
        inputs: inputDefinitions,
        summary: `title=${title}; actions=${actionIds.join(",") || "none"}; ` +
            `inputs=${inputDefinitions.map((input) => `${input.key}:${input.type}`).join(",") || "none"}`,
    };
}

function findNamedArrays(value, name, output = []) {
    if (Array.isArray(value)) {
        for (const child of value) findNamedArrays(child, name, output);
    }
    else if (value !== null && typeof value === "object" && !Buffer.isBuffer(value)) {
        for (const [key, child] of Object.entries(value)) {
            if (key === name && Array.isArray(child)) output.push(child);
            findNamedArrays(child, name, output);
        }
    }
    return output;
}

function collectNbtObjects(value, output = []) {
    if (Array.isArray(value)) {
        for (const child of value) collectNbtObjects(child, output);
    }
    else if (value !== null && typeof value === "object" && !Buffer.isBuffer(value)) {
        output.push(value);
        for (const child of Object.values(value)) collectNbtObjects(child, output);
    }
    return output;
}

function findFirstNamedValue(value, name) {
    if (Array.isArray(value)) {
        for (const child of value) {
            const found = findFirstNamedValue(child, name);
            if (found !== undefined) return found;
        }
    }
    else if (value !== null && typeof value === "object" && !Buffer.isBuffer(value)) {
        if (Object.hasOwn(value, name)) return value[name];
        for (const child of Object.values(value)) {
            const found = findFirstNamedValue(child, name);
            if (found !== undefined) return found;
        }
    }
    return undefined;
}

function summarizeNbtValue(value) {
    if (value === undefined) return "<untitled>";
    const encoded = typeof value === "string" ? value : JSON.stringify(value);
    return encoded.length <= 160 ? encoded : encoded.slice(0, 157) + "...";
}

function uniqueStrings(values) {
    return [...new Set(values)];
}

function selectDialogAction(actionIds) {
    if (requestedDialogAction !== undefined) {
        if (!actionIds.includes(requestedDialogAction)) {
            throw new Error(`Requested dialog action ${requestedDialogAction} was not offered by the server`);
        }
        return requestedDialogAction;
    }
    const loginAction = "authmeui:action/login";
    if (actionIds.includes(loginAction)) return loginAction;
    if (actionIds.length !== 1) {
        throw new Error(
            `Smoke requires exactly one dynamic dialog action, received ${actionIds.length}; ` +
            "set GAIUS_SMOKE_DIALOG_ACTION_ID when the intended action is known");
    }
    return actionIds[0];
}

function parseDialogInputValues(encoded) {
    if (encoded === undefined) return {};
    let parsed;
    try {
        parsed = JSON.parse(encoded);
    }
    catch (error) {
        throw new Error(`GAIUS_SMOKE_DIALOG_INPUTS_JSON is invalid JSON: ${error.message}`);
    }
    if (parsed === null || Array.isArray(parsed) || typeof parsed !== "object") {
        throw new Error("GAIUS_SMOKE_DIALOG_INPUTS_JSON must be a JSON object");
    }
    for (const [key, value] of Object.entries(parsed)) {
        if (typeof value !== "string" && typeof value !== "boolean") {
            throw new Error(`Dialog input ${key} must be a string or boolean`);
        }
    }
    return parsed;
}

async function loadDialogInputValues() {
    const inputFile = process.env.GAIUS_SMOKE_DIALOG_INPUTS_FILE?.trim();
    const inputJson = process.env.GAIUS_SMOKE_DIALOG_INPUTS_JSON;
    if (inputFile !== undefined && inputFile !== "" && inputJson !== undefined) {
        throw new Error(
            "Set only one of GAIUS_SMOKE_DIALOG_INPUTS_FILE or " +
            "GAIUS_SMOKE_DIALOG_INPUTS_JSON",
        );
    }
    if (inputFile === undefined || inputFile === "") {
        return parseDialogInputValues(inputJson);
    }
    const info = await lstat(inputFile).catch((error) => {
        throw new Error(`GAIUS_SMOKE_DIALOG_INPUTS_FILE cannot be read: ${error.message}`);
    });
    if (!info.isFile() || info.isSymbolicLink()) {
        throw new Error("GAIUS_SMOKE_DIALOG_INPUTS_FILE must be a regular file");
    }
    if (process.platform !== "win32" && (info.mode & 0o077) !== 0) {
        throw new Error("GAIUS_SMOKE_DIALOG_INPUTS_FILE must be owner-readable only");
    }
    return parseDialogInputValues(await readFile(inputFile, "utf8"));
}

function resolveDialogInputValues(inputs) {
    const values = {};
    for (const input of inputs) {
        if (input.type === "minecraft:boolean") {
            const supplied = dialogInputValues[input.key];
            if (supplied !== undefined && typeof supplied !== "boolean") {
                throw new Error(`Dialog boolean input ${input.key} requires a JSON boolean`);
            }
            values[input.key] = supplied ?? true;
        }
        else if (input.type === "minecraft:text") {
            const supplied = dialogInputValues[input.key];
            if (typeof supplied !== "string") {
                throw new Error(
                    `Dialog text input ${input.key} requires a value in ` +
                    "GAIUS_SMOKE_DIALOG_INPUTS_JSON");
            }
            if (/[^ -~]/.test(supplied)) {
                throw new Error(`Dialog text input ${input.key} must use printable ASCII in smoke`);
            }
            if (input.maxLength !== undefined && supplied.length > input.maxLength) {
                throw new Error(
                    `Dialog text input ${input.key} exceeds max_length ${input.maxLength}`);
            }
            values[input.key] = supplied;
        }
        else {
            throw new Error(`Smoke cannot safely fill ${input.key}:${input.type}`);
        }
    }
    return values;
}

function encodeCustomClickAction(actionId, values) {
    if (!/^[a-z0-9_.-]+:[a-z0-9/._-]+$/.test(actionId)) {
        throw new Error(`Server dialog supplied invalid action identifier ${actionId}`);
    }
    const nbt = encodeDialogCompound(values);
    return Buffer.concat([encodeString(actionId), encodeVarInt(nbt.byteLength), nbt]);
}

function encodeDialogCompound(values) {
    const parts = [Buffer.from([10])];
    for (const [name, value] of Object.entries(values)) {
        const key = Buffer.from(name, "utf8");
        if (key.byteLength === 0 || key.byteLength > 65_535 || /[^ -~]/.test(name)) {
            throw new Error(`Server dialog supplied unsafe input key ${name}`);
        }
        const keyLength = Buffer.allocUnsafe(2);
        keyLength.writeUInt16BE(key.byteLength);
        if (typeof value === "boolean") {
            parts.push(Buffer.from([1]), keyLength, key, Buffer.from([value ? 1 : 0]));
        }
        else if (typeof value === "string") {
            const text = Buffer.from(value, "utf8");
            if (text.byteLength > 65_535) {
                throw new Error(`Dialog text input ${name} exceeds the NBT string limit`);
            }
            const textLength = Buffer.allocUnsafe(2);
            textLength.writeUInt16BE(text.byteLength);
            parts.push(Buffer.from([8]), keyLength, key, textLength, text);
        }
        else {
            throw new Error(`Dialog input ${name} has an unsupported value type`);
        }
    }
    parts.push(Buffer.from([0]));
    return Buffer.concat(parts);
}

function encodeString(value) {
    const bytes = Buffer.from(value, "utf8");
    return Buffer.concat([encodeVarInt(bytes.byteLength), bytes]);
}

function encodeByteArray(value) {
    const bytes = Buffer.from(value);
    return Buffer.concat([encodeVarInt(bytes.byteLength), bytes]);
}

function encodeClientInformation() {
    return Buffer.concat([
        encodeString("en_us"),
        Buffer.from([clientViewDistance]),
        encodeVarInt(0),
        Buffer.from([1, 0x7f]),
        encodeVarInt(1),
        Buffer.from([0, 1]),
        encodeVarInt(0),
    ]);
}

function parseDesiredChunksPerTick(raw) {
    if (!/^(?:[0-9]+(?:\.[0-9]+)?|\.[0-9]+)$/u.test(String(raw))) {
        throw new Error(
            "GAIUS_BROWSER_FULL_PATH_DESIRED_CHUNKS_PER_TICK must be a finite number " +
            "from 0.01 to 64");
    }
    const value = Number(raw);
    if (!Number.isFinite(value) || value < 0.01 || value > 64) {
        throw new Error(
            "GAIUS_BROWSER_FULL_PATH_DESIRED_CHUNKS_PER_TICK must be a finite number " +
            "from 0.01 to 64");
    }
    return value;
}

function chunkTrackingCapacity(viewDistance) {
    let capacity = 0;
    for (let x = -(viewDistance + 1); x <= viewDistance + 1; x++) {
        for (let z = -(viewDistance + 1); z <= viewDistance + 1; z++) {
            const normalizedX = Math.max(0, Math.abs(x) - 1);
            const normalizedZ = Math.max(0, Math.abs(z) - 1);
            if (normalizedX * normalizedX + normalizedZ * normalizedZ <
                viewDistance * viewDistance) {
                capacity++;
            }
        }
    }
    return capacity;
}

function createLatencyHistogram() {
    return {
        counts: new Array(LATENCY_HISTOGRAM_BUCKETS_MILLIS.length).fill(0),
        count: 0,
        maxMillis: 0,
    };
}

function observeLatency(histogram, value) {
    if (!Number.isFinite(value) || value < 0) return;
    histogram.count++;
    histogram.maxMillis = Math.max(histogram.maxMillis, value);
    const bucket = LATENCY_HISTOGRAM_BUCKETS_MILLIS.findIndex((limit) => value <= limit);
    histogram.counts[bucket === -1 ? histogram.counts.length - 1 : bucket]++;
}

function latencyHistogramResult(histogram) {
    const quantile = (fraction) => {
        if (histogram.count === 0) return null;
        const target = Math.ceil(histogram.count * fraction);
        let cumulative = 0;
        for (let index = 0; index < histogram.counts.length; index++) {
            cumulative += histogram.counts[index];
            if (cumulative >= target) {
                const limit = LATENCY_HISTOGRAM_BUCKETS_MILLIS[index];
                return Number.isFinite(limit) ? limit : null;
            }
        }
        return null;
    };
    const p95Millis = quantile(0.95);
    const p99Millis = quantile(0.99);
    const p999Millis = quantile(0.999);
    const rawQuantileUpperBoundsMillis = {
        p95Millis,
        p99Millis,
        p999Millis,
    };
    return {
        schemaVersion: "gaius.latency-histogram.v1",
        count: histogram.count,
        buckets: LATENCY_HISTOGRAM_BUCKETS_MILLIS.map((upperBound, index) => ({
            upperBoundMillis: Number.isFinite(upperBound) ? upperBound : null,
            count: histogram.counts[index],
        })),
        p95Millis,
        p99Millis,
        p999Millis,
        // Human-readable alias retained alongside the conventional p999 key.
        p99_9Millis: p999Millis,
        maxMillis: Number(histogram.maxMillis.toFixed(3)),
        // The bucket aliases above intentionally remain stable for existing
        // readers.  Strict stress validation also consumes the raw maximum so
        // a value such as 100.0004 ms cannot be rounded down to a false pass.
        rawMaxMillis: Number.isFinite(histogram.maxMillis)
            ? histogram.maxMillis : null,
        rawQuantileUpperBoundsMillis,
    };
}

function assertHistogramLimit(histogram, field, limit, label) {
    const value = histogram?.[field];
    assert.ok(Number.isFinite(value) && value <= limit,
        `${label} reached ${value}ms (limit ${limit}ms)`);
}

function signedVarInt(value) {
    return value | 0;
}

function encodeVarInt(value) {
    const bytes = [];
    let remaining = value >>> 0;
    do {
        let next = remaining & 0x7f;
        remaining >>>= 7;
        if (remaining !== 0) next |= 0x80;
        bytes.push(next);
    } while (remaining !== 0);
    return Buffer.from(bytes);
}

function decodeVarInt(bytes, offset) {
    let value = 0;
    for (let index = 0; index < 5; index++) {
        const position = offset + index;
        if (position >= bytes.byteLength) return undefined;
        const next = bytes[position];
        value |= (next & 0x7f) << (index * 7);
        if ((next & 0x80) === 0) return { value, bytesRead: index + 1 };
    }
    throw new Error("Minecraft VarInt exceeded five bytes");
}

function decodeByteArray(bytes, offset) {
    const length = decodeVarInt(bytes, offset);
    if (length === undefined || length.value < 0) throw new Error("invalid byte-array length");
    const start = offset + length.bytesRead;
    const end = start + length.value;
    if (end > bytes.byteLength) throw new Error("byte array exceeded packet");
    return { value: bytes.subarray(start, end), nextOffset: end };
}

function decodeString(bytes, offset) {
    const encoded = decodeByteArray(bytes, offset);
    return { value: encoded.value.toString("utf8"), nextOffset: encoded.nextOffset };
}

function decodeReason(payload) {
    try {
        return decodeString(payload, 0).value;
    }
    catch {
        return "<malformed disconnect>";
    }
}

async function waitFor(predicate, label, timeoutMs, diagnostics) {
    const deadline = Date.now() + timeoutMs;
    while (!predicate()) {
        if (Date.now() >= deadline) {
            const suffix = typeof diagnostics === "function" ? `\n${diagnostics()}` : "";
            throw new Error(`Timed out waiting for ${label}${suffix}`);
        }
        await delay(20);
    }
}

async function stopChildProcess(child, options = {}) {
    if (child === undefined || child.exitCode !== null || child.signalCode !== null) return;
    const exited = once(child, "exit").catch(() => {});
    if (options.gracefulInput !== undefined && child.stdin !== undefined &&
        !child.stdin.destroyed) {
        // The Java process can win the startup/exit race between the check and
        // write. Swallow that expected EPIPE instead of masking the smoke error.
        child.stdin.once("error", () => {});
        try { child.stdin.write(options.gracefulInput, () => {}); } catch {}
    }
    await Promise.race([exited, delay(options.gracefulTimeoutMs ?? 15000)]);
    if (child.exitCode !== null || child.signalCode !== null) return;
    try { child.kill("SIGTERM"); } catch {}
    await Promise.race([exited, delay(5000)]);
}

async function closeHttpServer(server) {
    if (!server?.listening) return;
    await new Promise((resolve) => {
        let settled = false;
        let forceCloseTimer;
        const finish = () => {
            if (settled) return;
            settled = true;
            clearTimeout(forceCloseTimer);
            resolve();
        };
        forceCloseTimer = setTimeout(() => {
            server.closeAllConnections?.();
        }, 5000);
        server.close(finish);
        server.closeIdleConnections?.();
    });
}

if (printJavaResolutionOnly) await printJavaResolution();
else if (printConfigOnly) await printConfiguration();
else await runSmoke();
