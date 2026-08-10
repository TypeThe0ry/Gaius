import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

const sourcePath = new URL(
  "../overrides/libraries/netty-transport/src/main/java/" +
    "io/netty/channel/browser/BrowserWebSocketChannel.java",
  import.meta.url,
);
const source = await readFile(sourcePath, "utf8");
const marker = "private static native void initBridge();";
const markerOffset = source.indexOf(marker);
const annotationOffset = source.lastIndexOf('@JSBody(script = """', markerOffset);
const scriptOffset = source.indexOf('"""', annotationOffset) + 3;
const scriptEnd = source.lastIndexOf('""")', markerOffset);
assert.ok(markerOffset > 0 && annotationOffset > 0 && scriptEnd > scriptOffset,
  "Browser bridge JSBody could not be extracted");

const delay = (millis) => new Promise((resolve) => setTimeout(resolve, millis));
const startedAt = performance.now();
const sockets = [];
const connectControls = [];
const fetchEvents = [];

globalThis.window = globalThis;
Object.defineProperty(globalThis, "location", {
  configurable: true,
  value: {
    href: "file:///Downloads/Gaius.html",
    hostname: "",
    protocol: "file:",
    search: "",
  },
});
globalThis.localStorage = {getItem: () => null};
globalThis.__gaiusDefaultRelayRegistries = false;
globalThis.__gaiusRelayRegistryUrls = ["https://slow-registry.example/relay-nodes.json"];
globalThis.__gaiusBridgeUrl = "wss://public-relay.example/tunnel";
globalThis.__gaiusBridgeUrls = [{
  name: "Public relay",
  url: "wss://public-relay.example/tunnel",
  priority: 100,
}];

globalThis.fetch = async (input) => {
  const url = new URL(String(input));
  fetchEvents.push({url: url.href, at: performance.now() - startedAt});
  if (url.pathname.endsWith("relay-nodes.json")) {
    await delay(1200);
    return {
      ok: true,
      status: 200,
      json: async () => ({
        kind: "gaius-relay-registry",
        protocolVersion: 1,
        nodes: [],
      }),
    };
  }
  assert.equal(url.hostname, "public-relay.example");
  await delay(40);
  return {
    ok: true,
    status: 200,
    json: async () => ({
      kind: "gaius-relay-node",
      protocolVersion: 1,
      capabilities: ["target-attestation"],
      availableConnections: 64,
      targetConnectTimeoutMs: 10000,
      targetAffinityMs: 300000,
      target: {activeConnections: 1, recentlyReachable: true},
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
    sockets.push(this);
    const direct = new URL(this.url).port === "8081";
    setTimeout(() => {
      if (this.readyState !== MockWebSocket.CONNECTING) return;
      if (direct) {
        this.readyState = MockWebSocket.CLOSED;
        this.onclose?.({code: 1006, reason: "plugin unavailable"});
        return;
      }
      this.readyState = MockWebSocket.OPEN;
      this.onopen?.();
    }, direct ? 300 : 5);
  }

  send(data) {
    if (typeof data !== "string") return;
    const message = JSON.parse(data);
    if (message.type !== "connect") return;
    connectControls.push({url: this.url, message, at: performance.now() - startedAt});
    setTimeout(() => {
      if (this.readyState !== MockWebSocket.OPEN) return;
      this.onmessage?.({data: JSON.stringify({
        type: "connected",
        host: message.host,
        port: message.port,
      })});
    }, 5);
  }

  close() {
    if (this.readyState === MockWebSocket.CLOSED) return;
    this.readyState = MockWebSocket.CLOSED;
    queueMicrotask(() => this.onclose?.({code: 1000, reason: "closed"}));
  }
}

globalThis.WebSocket = MockWebSocket;
new Function(source.slice(scriptOffset, scriptEnd))();
const bridge = globalThis.__gaiusNettyBridge;
const stats = globalThis.__gaiusNetworkStats;

bridge.open(71, "ellan.top", 25565);
const deadline = Date.now() + 800;
while (stats.connected !== 1) {
  if (Date.now() >= deadline) throw new Error("Relay remained on the fixed-delay critical path");
  await delay(2);
}
const connectedAt = performance.now() - startedAt;
assert.ok(connectedAt < 700,
  `Relay connection took ${connectedAt.toFixed(1)} ms despite parallel preparation`);
assert.equal(connectControls.length, 1,
  "candidate preparation created duplicate target tunnel connections");
assert.equal(connectControls[0].url, "wss://public-relay.example/tunnel");
assert.deepEqual(connectControls[0].message,
  {type: "connect", host: "ellan.top", port: 25565});
assert.ok(stats.relayTargetActiveSelections >= 1,
  "existing target tunnel affinity was not consumed before selection");
assert.ok(stats.relaySelectionReadyBeforeDeadline + stats.relaySelectionDeadlineHits >= 1,
  "relay selection deadline telemetry was not recorded");

const phases = stats.connectPhases.filter((event) => event.id === 71);
const preparation = phases.find((event) => event.phase === "relay-preparation-start");
const directFailure = phases.find((event) => event.phase === "direct-failed");
const relayConnected = phases.find((event) => event.phase === "relay-connected");
assert.ok(preparation && directFailure && relayConnected,
  "connect phase telemetry omitted the critical relay phases");
assert.ok(preparation.elapsedMillis < directFailure.elapsedMillis,
  "relay preparation did not overlap the direct plugin probe");
assert.ok(relayConnected.elapsedMillis < 700,
  "fixed two-second delay remained in connect phase telemetry");
assert.ok(fetchEvents.some((event) => event.url.includes("relay-node/v1")),
  "public Relay manifest was not probed in parallel");

bridge.close(71);
console.log(JSON.stringify({
  ok: true,
  connectedMillis: Number(connectedAt.toFixed(1)),
  targetConnectControls: connectControls.length,
  relayParallelPreparations: stats.relayParallelPreparations,
  relaySelectionDeadlineHits: stats.relaySelectionDeadlineHits,
  relaySelectionReadyBeforeDeadline: stats.relaySelectionReadyBeforeDeadline,
  phases,
}));
