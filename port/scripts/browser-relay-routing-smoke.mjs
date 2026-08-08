import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const directory = dirname(fileURLToPath(import.meta.url));
const root = resolve(directory, "../..");
const channelSourcePath = resolve(root,
        "port/overrides/libraries/netty-transport/src/main/java/" +
        "io/netty/channel/browser/BrowserWebSocketChannel.java");
const channelSource = await readFile(channelSourcePath, "utf8");
const initMarker = "private static native void initBridge();";
const markerOffset = channelSource.indexOf(initMarker);
const annotationOffset = channelSource.lastIndexOf('@JSBody(script = """', markerOffset);
const scriptOffset = channelSource.indexOf('"""', annotationOffset) + 3;
const scriptEnd = channelSource.lastIndexOf('""")', markerOffset);

assert.ok(markerOffset > 0 && annotationOffset > 0 && scriptOffset > annotationOffset &&
        scriptEnd > scriptOffset, "Browser bridge JSBody could not be extracted");

const delay = (millis) => new Promise((resolveDelay) => setTimeout(resolveDelay, millis));
const openedSockets = [];
const sentControls = [];
const manifestRequests = [];
const registryRequests = [];
let directPluginAvailable = false;
let manifestScenario = "unused";

globalThis.window = globalThis;
Object.defineProperty(globalThis, "location", {
    configurable: true,
    value: {
        href: "https://play.example/client/",
        hostname: "play.example",
        protocol: "https:",
        search: "",
    },
});
globalThis.localStorage = { getItem: () => null };
globalThis.__gaiusDefaultRelayRegistries = false;
globalThis.__gaiusRelayRegistryUrls = [
    "https://registry.example/relay-nodes.json",
    "https://broken-registry.example/relay-nodes.json",
];
globalThis.__gaiusBridgeUrl = "wss://priority.example/tunnel";
globalThis.__gaiusBridgeUrls = [
    {
        name: "Configured priority node",
        url: "wss://priority.example/tunnel",
        token: "priority-token",
        priority: 100,
    },
    {
        name: "Target affinity node",
        url: "wss://affinity.example/tunnel",
        token: "affinity-token",
        priority: 10,
    },
];

globalThis.fetch = async (input, options = {}) => {
    const url = new URL(String(input));
    if (url.pathname.endsWith("/relay-nodes.json")) {
        registryRequests.push({url, scenario: manifestScenario});
        if (url.hostname === "broken-registry.example") {
            return {ok: false, status: 503, json: async () => ({})};
        }
        if (url.hostname === "dynamic-registry.example") {
            return {
                ok: true,
                status: 200,
                json: async () => ({
                    kind: "gaius-relay-registry",
                    protocolVersion: 1,
                    registries: ["https://registry.example/relay-nodes.json"],
                    nodes: [{
                        name: "Dynamically registered node",
                        url: "wss://dynamic-node.example/tunnel",
                        priority: -100,
                    }],
                }),
            };
        }
        return {
            ok: true,
            status: 200,
            json: async () => ({
                kind: "gaius-relay-registry",
                protocolVersion: 1,
                registries: ["https://dynamic-registry.example/relay-nodes.json"],
                nodes: [{
                    name: "Registry node",
                    url: "wss://registry-node.example/tunnel",
                    priority: 20,
                }],
            }),
        };
    }
    manifestRequests.push({
        url,
        authorization: options.headers?.authorization,
        scenario: manifestScenario,
    });
    if (manifestScenario === "plugin") {
        await delay(60);
    }
    const affinity = url.hostname === "affinity.example";
    const registry = url.hostname === "registry-node.example";
    const dynamic = url.hostname === "dynamic-node.example";
    const active = (manifestScenario === "active" && affinity) ||
        (manifestScenario === "registry" && registry) ||
        (manifestScenario === "nested" && dynamic);
    const recent = manifestScenario === "recent" && affinity;
    return {
        ok: true,
        status: 200,
        json: async () => ({
            ok: true,
            kind: "gaius-relay-node",
            protocolVersion: 1,
            availableConnections: manifestScenario === "capacity" &&
                url.hostname === "priority.example"
                    ? 0
                    : (affinity ? 8 : (registry ? 32 : (dynamic ? 48 : 64))),
            targetConnectTimeoutMs: 10000,
            targetAffinityMs: 300000,
            target: {
                activeConnections: active ? 2 : 0,
                recentlyReachable: active || recent,
            },
        }),
    };
};

class MockWebSocket {
    static CONNECTING = 0;
    static OPEN = 1;
    static CLOSING = 2;
    static CLOSED = 3;

    constructor(url) {
        this.url = String(url);
        this.readyState = MockWebSocket.CONNECTING;
        this.bufferedAmount = 0;
        openedSockets.push(this);
        const parsedUrl = new URL(this.url);
        const direct = parsedUrl.port === "8081";
        const failedRelay = manifestScenario === "failover" &&
            parsedUrl.hostname === "priority.example";
        setTimeout(() => {
            if (this.readyState !== MockWebSocket.CONNECTING) {
                return;
            }
            if ((direct && !directPluginAvailable) || failedRelay) {
                this.readyState = MockWebSocket.CLOSED;
                this.onclose?.({
                    code: 1006,
                    reason: direct ? "plugin unavailable" : "relay unavailable",
                });
                return;
            }
            this.readyState = MockWebSocket.OPEN;
            this.onopen?.();
        }, direct ? 1 : 0);
    }

    send(data) {
        if (typeof data !== "string") {
            return;
        }
        const message = JSON.parse(data);
        sentControls.push({ url: this.url, message, socket: this });
        if (message.type === "connect") {
            const connectedDelayMs = message.host === "slow.ellan.top" ? 9000 : 0;
            setTimeout(() => {
                if (this.readyState === MockWebSocket.OPEN) {
                    this.onmessage?.({ data: JSON.stringify({ type: "connected" }) });
                }
            }, connectedDelayMs);
        }
    }

    close() {
        if (this.readyState === MockWebSocket.CLOSED) {
            return;
        }
        this.readyState = MockWebSocket.CLOSED;
        queueMicrotask(() => this.onclose?.({ code: 1000, reason: "test complete" }));
    }
}

globalThis.WebSocket = MockWebSocket;
new Function(channelSource.slice(scriptOffset, scriptEnd))();

const bridge = globalThis.__gaiusNettyBridge;
const stats = globalThis.__gaiusNetworkStats;
assert.ok(bridge && stats, "Browser bridge JSBody did not initialize");

async function waitFor(predicate, label, timeoutMillis = 1000) {
    const deadline = Date.now() + timeoutMillis;
    while (!predicate()) {
        if (Date.now() >= deadline) {
            throw new Error(`Timed out waiting for ${label}`);
        }
        await delay(2);
    }
}

async function verifyDirectPluginFirst() {
    directPluginAvailable = true;
    manifestScenario = "plugin";
    const openedAtStart = openedSockets.length;
    const manifestsAtStart = manifestRequests.length;
    const registriesAtStart = registryRequests.length;
    const connectedAtStart = stats.connected;
    bridge.open(1, "ellan.top", 25565);
    assert.equal(openedSockets[openedAtStart]?.url, "wss://ellan.top:8081/tunnel",
            "Direct plugin was not attempted before RelayNodes");
    await waitFor(() => stats.connected === connectedAtStart + 1, "direct plugin connection");
    assert.equal(openedSockets.length, openedAtStart + 1,
            "RelayNode opened while the direct plugin was connected");
    await delay(70);
    assert.equal(openedSockets.length, openedAtStart + 1,
            "RelayNode opened after a successful direct plugin connection");
    assert.equal(manifestRequests.length, manifestsAtStart,
            "RelayNode manifests were probed before the direct plugin failed");
    assert.equal(registryRequests.length, registriesAtStart,
            "RelayNode registries were fetched while the direct plugin was available");
    bridge.close(1);
}

async function verifyRelaySelection(id, scenario, expectedUrl, host,
        connectWaitMillis = 1000) {
    directPluginAvailable = false;
    manifestScenario = scenario;
    const openedAtStart = openedSockets.length;
    const requestsAtStart = manifestRequests.length;
    const connectedAtStart = stats.connected;
    bridge.open(id, host, 25565);
    assert.equal(openedSockets[openedAtStart]?.url, `wss://${host}:8081/tunnel`,
            "Relay fallback skipped the direct-plugin probe");
    await waitFor(() => stats.connected === connectedAtStart + 1,
            `${scenario} RelayNode connection`, connectWaitMillis);
    const attempts = openedSockets.slice(openedAtStart).map((socket) => socket.url);
    assert.deepEqual(attempts, [`wss://${host}:8081/tunnel`, expectedUrl],
            `${scenario} selected the wrong RelayNode`);
    const control = sentControls.find((entry) =>
        entry.socket === openedSockets[openedSockets.length - 1]);
    assert.equal(control?.message.host, host);
    assert.equal(control?.message.port, 25565);
    const expectedToken = expectedUrl.includes("affinity")
        ? "affinity-token"
        : (expectedUrl.includes("priority") ? "priority-token" : undefined);
    assert.equal(control?.message.token, expectedToken);
    const requests = manifestRequests.slice(requestsAtStart);
    assert.ok(requests.length >= 2, "Configured RelayNodes were not preflighted");
    for (const request of requests) {
        assert.equal(request.url.searchParams.get("host"), host);
        assert.equal(request.url.searchParams.get("port"), "25565");
        if (request.url.hostname === "priority.example") {
            assert.equal(request.authorization, "Bearer priority-token");
        }
        if (request.url.hostname === "affinity.example") {
            assert.equal(request.authorization, "Bearer affinity-token");
        }
    }
    bridge.close(id);
    await delay(2);
}

async function verifyCachedRelayDiscovery(id, expectedUrl, host) {
    directPluginAvailable = false;
    manifestScenario = "cached";
    const openedAtStart = openedSockets.length;
    const requestsAtStart = manifestRequests.length;
    const connectedAtStart = stats.connected;
    bridge.open(id, host.toUpperCase() + ".", 25565);
    await waitFor(() => stats.connected === connectedAtStart + 1,
            "cached RelayNode connection");
    const attempts = openedSockets.slice(openedAtStart).map((socket) => socket.url);
    assert.deepEqual(attempts, [expectedUrl],
            "Cached discovery repeated the direct-plugin probe or selected another node");
    assert.equal(manifestRequests.length, requestsAtStart,
            "Cached discovery repeated RelayNode manifest requests");
    bridge.close(id);
    await delay(2);
}

async function verifyRelayFailover(id, host) {
    directPluginAvailable = false;
    manifestScenario = "failover";
    const openedAtStart = openedSockets.length;
    const connectedAtStart = stats.connected;
    const failoversAtStart = stats.relayFailovers;
    bridge.open(id, host, 25565);
    await waitFor(() => stats.connected === connectedAtStart + 1,
            "RelayNode failover connection");
    const attempts = openedSockets.slice(openedAtStart).map((socket) => socket.url);
    assert.deepEqual(attempts, [
        `wss://${host}:8081/tunnel`,
        "wss://priority.example/tunnel",
        "wss://registry-node.example/tunnel",
    ], "RelayNode connection failure did not advance to the next ranked node");
    assert.equal(stats.relayFailovers, failoversAtStart + 1,
            "RelayNode failover telemetry was not incremented");
    bridge.close(id);
    await delay(2);

    // The successful fallback is now the browser's short target lease. A status ping and
    // subsequent join must reuse that RelayNode even though the cached manifest still ranks
    // the previously failed high-priority node first. The Minecraft TCP stream itself remains
    // isolated and is opened again below.
    manifestScenario = "post-failover";
    const reuseOpenedAtStart = openedSockets.length;
    const reuseConnectedAtStart = stats.connected;
    bridge.open(id + 100, host.toUpperCase() + ".", 25565);
    await waitFor(() => stats.connected === reuseConnectedAtStart + 1,
            "locally leased RelayNode reuse");
    const reuseAttempts = openedSockets.slice(reuseOpenedAtStart)
            .map((socket) => socket.url);
    assert.deepEqual(reuseAttempts, ["wss://registry-node.example/tunnel"],
            "Successful fallback RelayNode was not reused for the same normalized target");
    bridge.close(id + 100);
    await delay(2);
}

await verifyDirectPluginFirst();
await verifyRelaySelection(
        2, "active", "wss://affinity.example/tunnel", "active.ellan.top");
await verifyRelaySelection(
        3, "recent", "wss://affinity.example/tunnel", "recent.ellan.top");
await verifyRelaySelection(
        4, "unused", "wss://priority.example/tunnel", "unused.ellan.top");
await verifyCachedRelayDiscovery(
        5, "wss://priority.example/tunnel", "unused.ellan.top");
await verifyRelaySelection(
        6, "slow", "wss://priority.example/tunnel", "slow.ellan.top", 12000);
await verifyRelaySelection(
        7, "registry", "wss://registry-node.example/tunnel", "registry.ellan.top");
await verifyRelaySelection(
        8, "capacity", "wss://registry-node.example/tunnel", "capacity.ellan.top");
await verifyRelayFailover(9, "failover.ellan.top");
await verifyRelaySelection(
        10, "nested", "wss://dynamic-node.example/tunnel", "nested.ellan.top");

const chosenRelaySockets = sentControls
        .filter((entry) => entry.url !== "wss://ellan.top:8081/tunnel")
        .map((entry) => entry.socket);
assert.equal(new Set(chosenRelaySockets).size, 10,
        "Browser channels shared a Minecraft TCP tunnel instead of node affinity");
assert.ok(stats.relayTargetActiveSelections >= 1,
        "Active target-affinity selection was not recorded");
assert.ok(stats.relayTargetRecentSelections >= 1,
        "Recent target-affinity selection was not recorded");
assert.equal(stats.directPluginCachedMisses, 2,
        "A recent direct-plugin miss was not reused for the join");
assert.equal(stats.relayPreflightCacheHits, 8,
        "RelayNode discovery metadata was not reused for the join");
assert.ok(stats.relayTargetLocalRecentSelections >= 2,
        "Successful local target leases did not guide subsequent RelayNode selection");
assert.equal(stats.relayTargetLeaseAcquires, stats.relayTargetLeaseReleases,
        "Closing browser channels leaked RelayNode target leases");
assert.equal(stats.activeRelayTargetLeases, 0,
        "A closed browser channel retained an active RelayNode target lease");
assert.equal(stats.relayNodes["wss://priority.example/tunnel"].targetConnectTimeoutMs,
        10000, "RelayNode target-connect timeout was not retained from discovery");
assert.equal(registryRequests.length, 3,
        "Configured and nested RelayNode registries were not fetched exactly once");
assert.equal(stats.relayRegistryRequests, 3,
        "RelayNode registry request telemetry is incorrect");
assert.equal(stats.relayRegistrySuccesses, 2,
        "A compatible RelayNode registry was not accepted");
assert.equal(stats.relayRegistryFailures, 1,
        "An unavailable RelayNode registry did not fail independently");
assert.equal(stats.relayRegistryNodesLoaded, 2,
        "RelayNode registry entries were not loaded");
assert.equal(stats.relayRegistryRegistriesLoaded, 2,
        "Nested RelayNode registry entries were not loaded or deduplicated");

console.log(JSON.stringify({
    ok: true,
    directAttempts: stats.directAttempts,
    relayAttempts: stats.relayAttempts,
    relayPreflights: stats.relayPreflights,
    directPluginCachedMisses: stats.directPluginCachedMisses,
    relayPreflightCacheHits: stats.relayPreflightCacheHits,
    relayRegistryRequests: stats.relayRegistryRequests,
    relayRegistrySuccesses: stats.relayRegistrySuccesses,
    relayRegistryFailures: stats.relayRegistryFailures,
    relayRegistryNodesLoaded: stats.relayRegistryNodesLoaded,
    relayRegistryRegistriesLoaded: stats.relayRegistryRegistriesLoaded,
    relayFailovers: stats.relayFailovers,
    relayTargetActiveSelections: stats.relayTargetActiveSelections,
    relayTargetRecentSelections: stats.relayTargetRecentSelections,
    relayTargetLocalRecentSelections: stats.relayTargetLocalRecentSelections,
    relayTargetLeaseAcquires: stats.relayTargetLeaseAcquires,
    relayTargetLeaseReleases: stats.relayTargetLeaseReleases,
    activeRelayTargetLeases: stats.activeRelayTargetLeases,
    targetConnectTimeoutMs:
        stats.relayNodes["wss://priority.example/tunnel"].targetConnectTimeoutMs,
    isolatedRelaySockets: new Set(chosenRelaySockets).size,
}));
