/*
 * External multi-client RelayNode transport smoke.
 *
 * This uses the BrowserWebSocketChannel JSBody directly, opens one isolated
 * bridge channel per client, and drives the Minecraft STATUS protocol through
 * the public RelayNode. It is intentionally a status/ping transport check for
 * a real remote target; it never reports LOGIN/PLAY success.
 */

import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";
import { WebSocket as NodeWebSocket } from "../../apps/bridge/node_modules/ws/wrapper.mjs";

const channelSourceUrl = new URL(
    "../overrides/libraries/netty-transport/src/main/java/" +
        "io/netty/channel/browser/BrowserWebSocketChannel.java",
    import.meta.url,
);
const relayUrl = process.env.GAIUS_EXTERNAL_RELAY_URL ?? "wss://ellan.site/tunnel";
const target = parseTarget(
    process.env.GAIUS_EXTERNAL_TARGET ?? "ellan.top:16888",
);
const profile = process.env.GAIUS_VERSION_PROFILE_PATH?.includes("1.21.11")
    ? { id: "1.21.11", protocol: 774 }
    : { id: "26.2", protocol: 776 };
const clientCount = parseBoundedInteger(
    process.env.GAIUS_EXTERNAL_CLIENTS ?? "4", "GAIUS_EXTERNAL_CLIENTS", 1, 16,
);
const soakMillis = parseBoundedInteger(
    process.env.GAIUS_EXTERNAL_SOAK_MS ?? "15000", "GAIUS_EXTERNAL_SOAK_MS", 1000, 300000,
);
const pingIntervalMillis = parseBoundedInteger(
    process.env.GAIUS_EXTERNAL_PING_INTERVAL_MS ?? "1000",
    "GAIUS_EXTERNAL_PING_INTERVAL_MS", 250, 10000,
);
const enablePing = process.env.GAIUS_EXTERNAL_ENABLE_PING === "1";
const p99RttLimitMillis = parseBoundedNumber(
    process.env.GAIUS_EXTERNAL_P99_RTT_LIMIT_MS ?? "250",
    "GAIUS_EXTERNAL_P99_RTT_LIMIT_MS", 1, 60000,
);
const maxRttLimitMillis = parseBoundedNumber(
    process.env.GAIUS_EXTERNAL_MAX_RTT_LIMIT_MS ?? "500",
    "GAIUS_EXTERNAL_MAX_RTT_LIMIT_MS", 1, 120000,
);
const eventLoopGapLimitMillis = parseBoundedNumber(
    process.env.GAIUS_EXTERNAL_EVENT_LOOP_GAP_LIMIT_MS ?? "500",
    "GAIUS_EXTERNAL_EVENT_LOOP_GAP_LIMIT_MS", 1, 120000,
);

const source = await readFile(channelSourceUrl, "utf8");
const bridgeScript = extractJsBody(source, "private static native void initBridge();");
const outboundScript = extractJsBody(
    source, "private static native void initOutboundScheduler();",
);

class InstrumentedWebSocket extends NodeWebSocket {
    static sockets = new Set();

    constructor(url) {
        super(url, { origin: "null" });
        InstrumentedWebSocket.sockets.add(this);
        this.once("close", () => InstrumentedWebSocket.sockets.delete(this));
    }
}

function installBridge() {
    globalThis.window = globalThis;
    Object.defineProperty(globalThis, "location", {
        configurable: true,
        value: {
            href: "https://portable.gaius.invalid/Gaius.html?directPlugin=0",
            hostname: "portable.gaius.invalid",
            protocol: "https:",
            search: "?directPlugin=0",
        },
    });
    globalThis.localStorage = { getItem: () => null };
    globalThis.WebSocket = InstrumentedWebSocket;
    globalThis.__gaiusDirectPlugin = false;
    globalThis.__gaiusDefaultRelayRegistries = false;
    globalThis.__gaiusRelayRegistryUrls = [];
    globalThis.__gaiusBridgeUrl = relayUrl;
    globalThis.__gaiusBridgeUrls = [{ name: "External RelayNode", url: relayUrl, priority: 100 }];
    new Function(bridgeScript)();
    new Function(outboundScript)();
    assert.ok(globalThis.__gaiusNettyBridge, "Browser bridge did not initialize");
    return {
        bridge: globalThis.__gaiusNettyBridge,
        stats: globalThis.__gaiusNetworkStats,
    };
}

function extractJsBody(text, marker) {
    const markerOffset = text.indexOf(marker);
    const annotationOffset = text.lastIndexOf("@JSBody(script = \"\"\"", markerOffset);
    const scriptOffset = text.indexOf("\"\"\"", annotationOffset) + 3;
    const scriptEnd = text.lastIndexOf('\"\"\")', markerOffset);
    assert.ok(markerOffset > 0 && annotationOffset > 0 && scriptEnd > scriptOffset,
        `Browser JSBody extraction failed for ${marker}`);
    return text.slice(scriptOffset, scriptEnd);
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

function parseBoundedInteger(raw, name, minimum, maximum) {
    const value = Number(raw);
    assert.ok(Number.isInteger(value) && value >= minimum && value <= maximum,
        `${name} must be an integer in [${minimum}, ${maximum}]`);
    return value;
}

function parseBoundedNumber(raw, name, minimum, maximum) {
    const value = Number(raw);
    assert.ok(Number.isFinite(value) && value >= minimum && value <= maximum,
        `${name} must be a number in [${minimum}, ${maximum}]`);
    return value;
}

function encodeVarInt(value) {
    let number = value | 0;
    const bytes = [];
    do {
        let next = number & 0x7f;
        number >>>= 7;
        if (number !== 0) next |= 0x80;
        bytes.push(next);
    } while (number !== 0);
    return Buffer.from(bytes);
}

function encodeString(value) {
    const bytes = Buffer.from(value, "utf8");
    return Buffer.concat([encodeVarInt(bytes.byteLength), bytes]);
}

function encodePacket(packetId, payload) {
    const body = Buffer.concat([encodeVarInt(packetId), payload]);
    return Buffer.concat([encodeVarInt(body.byteLength), body]);
}

function encodeStatusHandshake() {
    return encodePacket(0, Buffer.concat([
        encodeVarInt(profile.protocol),
        encodeString(target.host),
        Buffer.from([(target.port >>> 8) & 0xff, target.port & 0xff]),
        encodeVarInt(1),
    ]));
}

function encodeStatusRequest() {
    return encodePacket(0, Buffer.alloc(0));
}

function encodePing(value) {
    const payload = Buffer.alloc(8);
    payload.writeBigInt64BE(BigInt(value));
    return encodePacket(1, payload);
}

function readVarInt(buffer, offset = 0) {
    let value = 0;
    let shift = 0;
    for (let index = offset; index < buffer.length && index < offset + 5; index++) {
        const byte = buffer[index];
        value |= (byte & 0x7f) << shift;
        if ((byte & 0x80) === 0) return { value: value >>> 0, nextOffset: index + 1 };
        shift += 7;
    }
    return undefined;
}

function decodeString(buffer, offset) {
    const length = readVarInt(buffer, offset);
    if (!length || buffer.length < length.nextOffset + length.value) return undefined;
    return {
        value: buffer.subarray(length.nextOffset, length.nextOffset + length.value)
            .toString("utf8"),
        nextOffset: length.nextOffset + length.value,
    };
}

function percentile(values, fraction) {
    if (values.length === 0) return 0;
    const sorted = [...values].sort((left, right) => left - right);
    const index = Math.min(sorted.length - 1,
        Math.max(0, Math.ceil(fraction * sorted.length) - 1));
    return Number(sorted[index].toFixed(3));
}

function latencySummary(values) {
    return {
        samples: values.length,
        p50Millis: percentile(values, 0.50),
        p95Millis: percentile(values, 0.95),
        p99Millis: percentile(values, 0.99),
        maxMillis: Number((values.length ? Math.max(...values) : 0).toFixed(3)),
    };
}

function relayManifestUrl(value) {
    const parsed = new URL(value);
    parsed.protocol = parsed.protocol === "wss:" ? "https:" : "http:";
    parsed.pathname = "/relay-node/v1";
    parsed.search = "";
    parsed.searchParams.set("host", target.host);
    parsed.searchParams.set("port", String(target.port));
    return parsed.href;
}

function dnsCacheRuntime(manifest) {
    const runtime = manifest?.runtime;
    const fields = [
        "publicDnsCacheEntries",
        "publicDnsCacheHits",
        "publicDnsCacheMisses",
        "publicDnsCacheInflightJoins",
    ];
    if (!fields.every((field) => Number.isSafeInteger(runtime?.[field]) &&
            runtime[field] >= 0)) {
        return undefined;
    }
    return Object.fromEntries(fields.map((field) => [field, runtime[field]]));
}

async function fetchManifest() {
    const response = await fetch(relayManifestUrl(relayUrl), {
        signal: AbortSignal.timeout(15000),
    });
    const body = await response.json();
    assert.ok(response.ok, `RelayNode manifest HTTP ${response.status}`);
    return body;
}

function phaseFor(stats, id, phase) {
    return stats.connectPhases.find((event) => event.id === id && event.phase === phase);
}

function createClient(id, bridge, stats) {
    const client = {
        id,
        connected: false,
        closedEarly: false,
        failure: undefined,
        buffer: Buffer.alloc(0),
        statusSentAt: 0,
        statusReceivedAt: 0,
        statusRtt: [],
        pingRtt: [],
        pendingPings: new Map(),
        nextPingAt: 0,
        statusResponses: 0,
        statusPayload: undefined,
        pingSent: 0,
        pingResponses: 0,
        closed: false,
        unexpectedPackets: 0,
        lastInboundAt: 0,
    };
    client.send = (bytes) => {
        const accepted = bridge.send(id, bytes);
        if (!accepted) throw new Error(`bridge.send failed for client ${id}`);
        return accepted;
    };
    client.sendInitial = () => {
        client.statusSentAt = performance.now();
        client.send(Buffer.concat([encodeStatusHandshake(), encodeStatusRequest()]));
    };
    client.sendPing = () => {
        if (client.pingSent > 0 || client.closed || !bridge.channels.has(id)) {
            return false;
        }
        const token = Math.round(performance.now() * 1000);
        client.pendingPings.set(token, performance.now());
        client.pingSent++;
        try {
            client.send(encodePing(token));
            return true;
        }
        catch (error) {
            client.closed = true;
            client.failure = String(error?.stack || error);
            client.pendingPings.delete(token);
            return false;
        }
    };
    client.consume = () => {
        let chunk;
        while ((chunk = bridge.pollInbound(id)) !== null) {
            const bytes = Buffer.from(chunk.buffer, chunk.byteOffset ?? 0, chunk.byteLength);
            client.buffer = Buffer.concat([client.buffer, bytes]);
            client.lastInboundAt = performance.now();
            consumeFrames(client);
        }
    };
    return client;
}

function consumeFrames(client) {
    while (true) {
        const length = readVarInt(client.buffer);
        if (!length || client.buffer.length < length.nextOffset + length.value) return;
        const frameEnd = length.nextOffset + length.value;
        const payload = client.buffer.subarray(length.nextOffset, frameEnd);
        client.buffer = client.buffer.subarray(frameEnd);
        const packet = readVarInt(payload);
        if (!packet) throw new Error(`client ${client.id} received malformed packet id`);
        const body = payload.subarray(packet.nextOffset);
        if (packet.value === 0) {
            const response = decodeString(body, 0);
            if (!response) throw new Error(`client ${client.id} received malformed status`);
            client.statusResponses++;
            if (client.statusResponses === 1) {
                client.statusReceivedAt = performance.now();
                client.statusRtt.push(client.statusReceivedAt - client.statusSentAt);
                client.statusPayload = JSON.parse(response.value);
                client.nextPingAt = performance.now();
            }
        }
        else if (packet.value === 1 && body.byteLength === 8) {
            const token = Number(body.readBigInt64BE());
            const sentAt = client.pendingPings.get(token);
            if (sentAt !== undefined) {
                client.pendingPings.delete(token);
                client.pingResponses++;
                client.pingRtt.push(performance.now() - sentAt);
            }
        }
        else {
            client.unexpectedPackets++;
        }
    }
}

function runtimeClean(bridge, stats) {
    return bridge.channels.size === 0 && InstrumentedWebSocket.sockets.size === 0 &&
        stats.queuedBytes === 0 && stats.queuedFrames === 0 &&
        stats.inboundQueuedBytes === 0 && stats.activeRelayTargetLeases === 0;
}

async function waitFor(predicate, label, timeoutMillis, diagnostics) {
    const deadline = Date.now() + timeoutMillis;
    while (Date.now() < deadline) {
        if (predicate()) return;
        await delay(10);
    }
    throw new Error(`${label} timed out\n${JSON.stringify(diagnostics?.() ?? {}, null, 2)}`);
}

async function waitForTargetConnections(baselineManifest, expectedAdditional) {
    const baseline = Number(baselineManifest.target?.activeConnections ?? 0);
    const deadline = Date.now() + 15000;
    let latest = baselineManifest;
    while (Date.now() < deadline) {
        latest = await fetchManifest();
        if (Number(latest.target?.activeConnections ?? 0) >= baseline + expectedAdditional) {
            return latest;
        }
        await delay(100);
    }
    throw new Error(`RelayNode target active connections did not reach ${baseline + expectedAdditional}\n` +
        JSON.stringify(latest, null, 2));
}

async function waitForTargetBaseline(baselineManifest, label) {
    const baseline = Number(baselineManifest.target?.activeConnections ?? 0);
    const deadline = Date.now() + 15000;
    let latest = baselineManifest;
    while (Date.now() < deadline) {
        latest = await fetchManifest();
        if (Number(latest.target?.activeConnections ?? 0) <= baseline) {
            return latest;
        }
        await delay(100);
    }
    throw new Error(`RelayNode target active connections did not return to baseline for ${label}: ` +
        `${latest.target?.activeConnections ?? "unknown"} > ${baseline}\n` +
        JSON.stringify(latest, null, 2));
}

let lastExternalResult;

async function main() {
    const { bridge, stats } = installBridge();
    const baselineManifest = await fetchManifest();
    const clients = Array.from({ length: clientCount }, (_, index) =>
        createClient(500 + index, bridge, stats));
    const startedAt = performance.now();
    const pollTimer = setInterval(() => {
        for (const client of clients) {
            try {
                const error = bridge.pollError(client.id);
                if (error) client.failure = error;
                if (!bridge.channels.has(client.id)) {
                    client.closed = true;
                    continue;
                }
                client.consume();
                if (!client.connected && phaseFor(stats, client.id, "relay-connected")) {
                    client.connected = true;
                    client.sendInitial();
                }
                if (enablePing && !client.closed && client.statusReceivedAt > 0 &&
                    client.pingSent === 0 && performance.now() >= client.nextPingAt) {
                    client.sendPing();
                    client.nextPingAt = performance.now() + pingIntervalMillis;
                }
            }
            catch (error) {
                client.failure = String(error?.stack || error);
            }
        }
    }, 2);
    try {
        for (const client of clients) bridge.open(client.id, target.host, target.port);
        await waitFor(
            () => clients.every((client) => client.connected && client.failure === undefined),
            "all external RelayNode tunnels", 30000,
            () => ({ phases: stats.connectPhases, failures: clients.map((client) => client.failure) }),
        );
        await waitFor(
            () => clients.every((client) => client.statusResponses >= 1),
            "all external Minecraft status responses", 30000,
            () => clients.map((client) => ({ id: client.id, statusResponses: client.statusResponses,
                failure: client.failure, bufferBytes: client.buffer.byteLength })),
        );
        // STATUS ping is a terminal probe for many Minecraft proxies: after
        // returning the pong, the server is allowed to close the STATUS TCP
        // stream. Capture target activity before issuing that probe so a
        // normal status close cannot erase the peak-concurrency evidence.
        const peakManifest = await waitForTargetConnections(
            baselineManifest, clients.length,
        );
        if (enablePing) {
            await waitFor(
                () => clients.every((client) => client.pingResponses >= 1),
                "initial status pongs", 30000,
                () => clients.map((client) => ({ id: client.id, pingSent: client.pingSent,
                    pingResponses: client.pingResponses })),
            );
        }
        const soakStartedAt = performance.now();
        await waitFor(
            () => performance.now() - soakStartedAt >= soakMillis,
            "external status/ping soak", soakMillis + 30000,
            () => clients.map((client) => ({ id: client.id, failure: client.failure,
                pingSent: client.pingSent, pingResponses: client.pingResponses })),
        );
        const afterSoakManifest = await fetchManifest();
        const dnsCacheBaseline = dnsCacheRuntime(baselineManifest);
        const dnsCachePeak = dnsCacheRuntime(peakManifest);
        const dnsCacheAfterSoak = dnsCacheRuntime(afterSoakManifest);
        clearInterval(pollTimer);
        for (const client of clients) bridge.close(client.id);
        await waitFor(
            () => runtimeClean(bridge, stats), "external browser/bridge cleanup", 15000,
            () => ({ channels: bridge.channels.size, sockets: InstrumentedWebSocket.sockets.size,
                queuedBytes: stats.queuedBytes, queuedFrames: stats.queuedFrames,
                inboundQueuedBytes: stats.inboundQueuedBytes,
                activeRelayTargetLeases: stats.activeRelayTargetLeases }),
        );
        // The browser-side lease can be gone before the RelayNode's TCP close
        // callback publishes its target-route decrement. Wait for the same
        // target baseline instead of sampling that short propagation window.
        const afterCloseManifest = await waitForTargetBaseline(
            baselineManifest, "external browser/bridge cleanup",
        );
        const dnsCacheAfterClose = dnsCacheRuntime(afterCloseManifest);
        const statusRtt = clients.flatMap((client) => client.statusRtt);
        const pingRtt = clients.flatMap((client) => client.pingRtt);
        const pingSent = clients.reduce((sum, client) => sum + client.pingSent, 0);
        const pingResponses = clients.reduce((sum, client) => sum + client.pingResponses, 0);
        const measuredRtt = enablePing ? pingRtt : statusRtt;
        const measuredRttSummary = latencySummary(measuredRtt);
        const targetActive = (manifest) => Number(manifest.target?.activeConnections ?? 0);
        const result = {
            schemaVersion: "browser-relay-external-multiplayer-v1",
            ok: true,
            evidenceKind: "external-status-ping-transport",
            profile,
            relayUrl,
            target: target.text,
            clients: clientCount,
            soakMillis: Number((performance.now() - soakStartedAt).toFixed(1)),
            connectionMillis: clients.map((client) => {
                const phase = phaseFor(stats, client.id, "relay-connected");
                return phase ? Number(phase.elapsedMillis.toFixed(3)) : null;
            }),
            statusRtt: latencySummary(statusRtt),
            pingRtt: latencySummary(pingRtt),
            packets: { statusResponses: clients.map((client) => client.statusResponses),
                pingSent, pingResponses, pingLoss: pingSent - pingResponses },
            minecraftStatus: clients.map((client) => ({
                protocol: client.statusPayload?.version?.protocol ?? null,
                version: client.statusPayload?.version?.name ?? null,
                onlinePlayers: client.statusPayload?.players?.online ?? null,
                maximumPlayers: client.statusPayload?.players?.max ?? null,
            })),
            relay: {
                baselineTargetActive: targetActive(baselineManifest),
                peakTargetActive: targetActive(peakManifest),
                afterSoakTargetActive: targetActive(afterSoakManifest),
                afterCloseTargetActive: targetActive(afterCloseManifest),
                targetAttestationFailures: stats.relayTargetAttestationFailures,
                dnsCache: {
                    baseline: dnsCacheBaseline,
                    peak: dnsCachePeak,
                    afterSoak: dnsCacheAfterSoak,
                    afterClose: dnsCacheAfterClose,
                },
            },
            browser: {
                longestEventLoopGapMillis: Number(stats.longestEventLoopGapMillis.toFixed(3)),
                eventLoopGapSamples: stats.eventLoopGapSamples,
                eventLoopGapsOver500: stats.eventLoopGapsOver500,
                peakInboundQueuedBytes: stats.peakInboundQueuedBytes,
                maxDecodedSliceBacklog: stats.maxDecodedSliceBacklog,
                peakQueuedFrames: stats.peakQueuedFrames,
                peakQueuedBytes: stats.peakQueuedBytes ?? null,
                relayPreparationCount: stats.relayParallelPreparations,
            },
            cleanup: {
                browserChannels: bridge.channels.size,
                webSockets: InstrumentedWebSocket.sockets.size,
                queuedBytes: stats.queuedBytes,
                queuedFrames: stats.queuedFrames,
                inboundQueuedBytes: stats.inboundQueuedBytes,
                activeRelayTargetLeases: stats.activeRelayTargetLeases,
            },
            gate: {
                statusResponses: clients.every((client) => client.statusResponses >= 1),
                pingLoss: pingSent - pingResponses,
                rttSource: enablePing ? "status-ping" : "status-response",
                p99RttMillis: measuredRttSummary.p99Millis,
                maxRttMillis: measuredRttSummary.maxMillis,
                eventLoopGapMillis: stats.longestEventLoopGapMillis,
                targetObserved: targetActive(peakManifest) >= targetActive(baselineManifest) + clientCount,
                dnsCacheTelemetry: dnsCacheBaseline !== undefined &&
                    dnsCachePeak !== undefined && dnsCacheAfterSoak !== undefined &&
                    dnsCacheAfterClose !== undefined,
                dnsLookupsShared: dnsCachePeak === undefined ? 0 :
                    (dnsCachePeak.publicDnsCacheHits -
                        (dnsCacheBaseline?.publicDnsCacheHits ?? 0)) +
                    (dnsCachePeak.publicDnsCacheInflightJoins -
                        (dnsCacheBaseline?.publicDnsCacheInflightJoins ?? 0)),
                thresholds: { p99RttLimitMillis, maxRttLimitMillis, eventLoopGapLimitMillis,
                    pingEnabled: enablePing,
                    minimumSharedDnsLookups: Math.max(0, clientCount - 1),
                },
            },
            elapsedMillis: Number((performance.now() - startedAt).toFixed(1)),
            phases: stats.connectPhases.filter((event) => clients.some((client) => client.id === event.id)),
        };
        lastExternalResult = result;
        try {
            assert.equal(result.packets.pingLoss, 0, "external status ping loss detected");
            assert.equal(stats.relayTargetAttestationFailures, 0,
                "external RelayNode target attestation failure detected");
            assert.ok(result.gate.targetObserved,
                "RelayNode manifest did not observe all external target connections");
            assert.ok(result.gate.dnsCacheTelemetry,
                "RelayNode did not expose DNS cache telemetry; deployed node is stale");
            assert.ok(result.gate.dnsLookupsShared >=
                result.gate.thresholds.minimumSharedDnsLookups,
                "RelayNode did not merge/cache same-target DNS lookups for every extra client");
            assert.ok(result.gate.p99RttMillis <= p99RttLimitMillis,
                `external ${result.gate.rttSource} p99 exceeded ${p99RttLimitMillis}ms`);
            assert.ok(result.gate.maxRttMillis <= maxRttLimitMillis,
                `external ${result.gate.rttSource} max exceeded ${maxRttLimitMillis}ms`);
            assert.ok(result.gate.eventLoopGapMillis < eventLoopGapLimitMillis,
                `browser event-loop gap reached ${result.gate.eventLoopGapMillis}ms`);
            assert.ok(result.relay.afterCloseTargetActive <= result.relay.baselineTargetActive,
                "RelayNode target connection did not return to baseline after close");
        }
        catch (error) {
            error.message += `\nExternal gate snapshot: ${JSON.stringify({
                statusRtt: result.statusRtt,
                pingRtt: result.pingRtt,
                connectionMillis: result.connectionMillis,
                relay: result.relay,
                browser: result.browser,
                gate: result.gate,
            })}`;
            throw error;
        }
        console.log(JSON.stringify(result));
    }
    finally {
        clearInterval(pollTimer);
        for (const client of clients) bridge.close(client.id);
    }
}

try {
    await main();
}
catch (error) {
    console.error(JSON.stringify({
        ok: false,
        evidenceKind: "external-status-ping-transport",
        relayUrl,
        target: target.text,
        profile,
        snapshot: lastExternalResult === undefined ? undefined : {
            statusRtt: lastExternalResult.statusRtt,
            pingRtt: lastExternalResult.pingRtt,
            connectionMillis: lastExternalResult.connectionMillis,
            relay: lastExternalResult.relay,
            browser: lastExternalResult.browser,
            gate: lastExternalResult.gate,
        },
        error: String(error?.stack || error),
    }));
    process.exitCode = 1;
}
