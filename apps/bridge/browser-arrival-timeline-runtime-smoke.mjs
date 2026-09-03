#!/usr/bin/env node

/*
 * Runtime-level Node fixture for BrowserWebSocketChannel's diagnostic arrival
 * timeline.  This extracts the four production @JSBody bridge bodies and runs
 * them against a mocked WebSocket, so the test exercises the real enqueue,
 * slice admission, poll dequeue, and bounded-ring code without starting Java,
 * TeaVM, Chrome, or a RelayNode.
 *
 * The timeline is deliberately diagnostic-only.  This fixture never changes a
 * strict acceptance threshold and reports enabled/disabled runs separately so
 * a source-static smoke cannot hide an enabled-path exception.
 */

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptPath = fileURLToPath(import.meta.url);
const repository = resolve(dirname(scriptPath), "../..");
const channelPath = resolve(
    repository,
    "port/overrides/libraries/netty-transport/src/main/java/" +
        "io/netty/channel/browser/BrowserWebSocketChannel.java",
);

function extractJsBody(source, methodMarker) {
    const markerOffset = source.indexOf(methodMarker);
    assert.ok(markerOffset >= 0, `missing method marker: ${methodMarker}`);
    const annotationOffset = source.lastIndexOf("@JSBody(script = \"\"\"", markerOffset);
    assert.ok(annotationOffset >= 0, `missing @JSBody annotation: ${methodMarker}`);
    const scriptOffset = source.indexOf("\"\"\"", annotationOffset) + 3;
    const scriptEnd = source.lastIndexOf("\"\"\")", markerOffset);
    assert.ok(scriptEnd > scriptOffset, `invalid @JSBody body: ${methodMarker}`);
    // Java text blocks retain the doubled backslash used by the source file;
    // the browser receives the single JavaScript backslash at runtime.
    return source.slice(scriptOffset, scriptEnd).replaceAll("\\\\", "\\");
}

function delay(millis) {
    return new Promise((resolveDelay) => setTimeout(resolveDelay, millis));
}

async function waitFor(predicate, description, timeoutMillis = 250) {
    const deadline = Date.now() + timeoutMillis;
    while (!predicate() && Date.now() < deadline) await delay(2);
    assert.ok(predicate(), description);
}

function installBrowserMocks(enabled) {
    globalThis.window = globalThis;
    globalThis.__gaiusBrowserArrivalTimeline = enabled;
    Object.defineProperty(globalThis, "location", {
        configurable: true,
        value: {
            href: "https://portable.test/Gaius.html",
            hostname: "portable.test",
            protocol: "https:",
            search: "",
        },
    });
    globalThis.localStorage = {
        getItem: () => null,
        setItem: () => {},
        removeItem: () => {},
    };
    globalThis.__gaiusDefaultRelayRegistries = false;
    globalThis.__gaiusRelayRegistryUrls = [];
    globalThis.__gaiusBridgeUrl = "wss://relay.test/tunnel";
    globalThis.__gaiusBridgeUrls = [
        { name: "test", url: "wss://relay.test/tunnel", priority: 1 },
    ];
    globalThis.fetch = async () => ({
        ok: true,
        status: 200,
        json: async () => ({
            kind: "gaius-relay-node",
            protocolVersion: 1,
            capabilities: ["target-attestation"],
            availableConnections: 8,
            targetConnectTimeoutMs: 1000,
            targetAffinityMs: 1000,
            target: { activeConnections: 0, recentlyReachable: true },
        }),
    });

    const sockets = [];
    class MockWebSocket {
        static CONNECTING = 0;
        static OPEN = 1;
        static CLOSING = 2;
        static CLOSED = 3;

        constructor(url) {
            this.url = String(url);
            this.readyState = MockWebSocket.CONNECTING;
            this.bufferedAmount = 0;
            this.binaryType = "arraybuffer";
            sockets.push(this);
            queueMicrotask(() => {
                this.readyState = MockWebSocket.OPEN;
                this.onopen?.();
            });
        }

        send(data) {
            let message;
            try {
                message = JSON.parse(String(data));
            } catch {
                return;
            }
            if (message?.type === "connect") {
                queueMicrotask(() => this.onmessage?.({
                    data: JSON.stringify({
                        type: "connected",
                        host: message.host,
                        port: message.port,
                    }),
                }));
            }
        }

        close() {
            this.readyState = MockWebSocket.CLOSED;
            queueMicrotask(() => this.onclose?.({ code: 1000, reason: "fixture" }));
        }
    }
    globalThis.WebSocket = MockWebSocket;
    return sockets;
}

function executeBridgeBodies(source, enabled) {
    installBrowserMocks(enabled);
    const markers = [
        "private static native void initBridge();",
        "private static native void initBridgeTail();",
        "private static native void initOutboundScheduler();",
        "private static native void initInboundScheduler();",
    ];
    for (const marker of markers) {
        new Function(extractJsBody(source, marker))();
    }
    const bridge = globalThis.__gaiusNettyBridge;
    const stats = globalThis.__gaiusNetworkStats;
    assert.ok(bridge && stats, "bridge bodies did not publish shared state");
    return { bridge, stats };
}

function pollOne(bridge, id) {
    return bridge.pollInboundScheduled(id, () => {});
}

async function drain(bridge, entry, expectedBytes, maximumPolls = 512) {
    const chunks = [];
    let total = 0;
    for (let attempt = 0; attempt < maximumPolls && total < expectedBytes; attempt++) {
        const chunk = pollOne(bridge, entry.id);
        if (chunk) {
            chunks.push(chunk.byteLength);
            total += chunk.byteLength;
        } else {
            await delay(2);
        }
    }
    assert.equal(total, expectedBytes,
        `drain stalled at ${total}/${expectedBytes} bytes`);
    return chunks;
}

async function runCase(source, enabled) {
    const { bridge, stats } = executeBridgeBodies(source, enabled);
    bridge.open(1, "ellan.top", 25565);
    await delay(35);

    const entry = bridge.channels.get(1);
    assert.ok(entry?.ws, "mock WebSocket was not attached");
    assert.equal(stats.arrivalTimeline.enabled, enabled);
    assert.equal(bridge.arrivalTimelineEnabled, enabled);

    const frame = new Uint8Array(9000);
    for (let index = 0; index < frame.length; index++) frame[index] = index & 0xff;
    entry.ws.onmessage({ data: frame.buffer });
    await waitFor(
        () => entry.inbound.length === 3,
        "9KB frame was not admitted as three slices",
    );

    if (enabled) {
        assert.equal(typeof bridge.recordArrivalMessage, "function");
        assert.equal(stats.arrivalTimeline.events.length, 2,
            "enabled onmessage must record enter + enqueue before poll");
        assert.equal(entry.inbound.length, 3,
            "9KB frame must be admitted as three bounded slices");
        assert.equal(entry.arrivalInboundMeta.length, entry.inbound.length,
            "arrival metadata must align with inbound slices");
    } else {
        assert.equal(bridge.recordArrivalMessage(entry, "fixture", frame), null,
            "disabled arrival marker must return no token");
        assert.equal(stats.arrivalTimeline.events.length, 0,
            "disabled path must not retain arrival events");
        assert.equal(entry.arrivalInboundMeta.length, 0,
            "disabled path must not allocate arrival metadata");
    }

    const firstChunks = await drain(bridge, entry, frame.byteLength);
    assert.deepEqual(firstChunks, [4096, 4096, 808],
        "inbound slice cap or cursor changed");
    assert.equal(entry.arrivalInboundMeta.length, 0,
        "poll must clear metadata with the shared inbound cursor");

    if (enabled) {
        // Cross both diagnostic ring caps without retaining payloads.  Each
        // eight-byte frame produces one onmessage and one enqueue event; the
        // subsequent poll adds one dequeue event per frame.
        const boundedFrameCount = 180;
        const tiny = new Uint8Array(8).buffer;
        for (let index = 0; index < boundedFrameCount; index++) {
            entry.ws.onmessage({ data: tiny.slice(0) });
        }
        await waitFor(
            () => entry.inbound.length === boundedFrameCount,
            "bounded-frame fixture did not admit all tiny frames",
        );
        assert.equal(entry.inbound.length, boundedFrameCount,
            "bounded-frame fixture did not admit all tiny frames");
        assert.equal(entry.arrivalInboundMeta.length, boundedFrameCount,
            "tiny-frame metadata lost alignment before poll");
        assert.ok(entry.arrivalTimelineEvents.length <= 64,
            "per-channel arrival ring exceeded 64 events");
        assert.ok(stats.arrivalTimeline.events.length <= 256,
            "global arrival ring exceeded 256 events");

        await drain(bridge, entry, boundedFrameCount * tiny.byteLength);
        assert.equal(entry.arrivalInboundMeta.length, 0,
            "bounded-frame poll left stale metadata");
        assert.ok(entry.arrivalTimelineEvents.length <= 64);
        assert.ok(stats.arrivalTimeline.events.length <= 256);
        assert.ok(stats.arrivalTimeline.dropped > 0,
            "global ring overflow was not counted");
        assert.ok(stats.arrivalTimeline.perChannelDropped > 0,
            "per-channel ring overflow was not counted");
    }

    if (enabled) {
        bridge.recordArrivalJavaPump(1, "pump-begin", 0, 0, 0);
        bridge.recordArrivalJavaPump(1, "pipeline-handoff", 1, 4, 0);
        bridge.recordArrivalJavaPump(1, "pump-end", 1, 4, 0.5);
        const kinds = stats.arrivalTimeline.events.map((event) => event.kind);
        assert.ok(kinds.includes("pump-begin"));
        assert.ok(kinds.includes("pipeline-handoff"));
        assert.ok(kinds.includes("pump-end"));
    }

    const result = {
        enabled,
        schemaVersion: stats.arrivalTimeline.schemaVersion,
        totalEvents: stats.arrivalTimeline.total,
        retainedEvents: stats.arrivalTimeline.events.length,
        perChannelEvents: entry.arrivalTimelineEvents.length,
        dropped: stats.arrivalTimeline.dropped,
        perChannelDropped: stats.arrivalTimeline.perChannelDropped,
        metadataAfterPoll: entry.arrivalInboundMeta.length,
        strictGatesChanged: stats.arrivalTimeline.strictGatesChanged,
    };
    try {
        bridge.close(entry.id);
    } catch {
        // The fixture has already collected its evidence; close is best effort.
    }
    await delay(5);
    return result;
}

const source = readFileSync(channelPath, "utf8");
const mode = process.argv[2];
if (mode === undefined) {
    const results = [];
    for (const childMode of ["disabled", "enabled"]) {
        const child = spawnSync(process.execPath, [scriptPath, childMode], {
            cwd: repository,
            encoding: "utf8",
            stdio: "inherit",
        });
        assert.equal(child.status, 0, `${childMode} child exited ${child.status}`);
        results.push(childMode);
    }
    console.log(JSON.stringify({
        ok: true,
        schemaVersion: "gaius.browser-client-arrival-timeline-runtime-smoke.v1",
        modes: results,
        verification: "production JSBody fixture; no Java/TeaVM/Chrome/RelayNode",
    }));
} else {
    assert.ok(mode === "enabled" || mode === "disabled",
        `unknown mode: ${mode}`);
    const result = await runCase(source, mode === "enabled");
    console.log(JSON.stringify({ ok: true, ...result }));
}
