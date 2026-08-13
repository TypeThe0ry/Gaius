import assert from "node:assert/strict";
import {createRequire} from "node:module";
import {readFile} from "node:fs/promises";

const require = createRequire(new URL("../../apps/bridge/package.json", import.meta.url));
const {WebSocket: NodeWebSocket} = require("ws");
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
const outboundMarker = "private static native void initOutboundScheduler();";
const outboundMarkerOffset = source.indexOf(outboundMarker);
const outboundAnnotationOffset = source.lastIndexOf(
  '@JSBody(script = """',
  outboundMarkerOffset,
);
const outboundScriptOffset = source.indexOf('"""', outboundAnnotationOffset) + 3;
const outboundScriptEnd = source.lastIndexOf('""")', outboundMarkerOffset);
assert.ok(outboundMarkerOffset > 0 && outboundAnnotationOffset > 0
    && outboundScriptEnd > outboundScriptOffset,
"Browser outbound scheduler JSBody could not be extracted");

const relayUrl = process.env.GAIUS_PUBLIC_RELAY_URL || "wss://ellan.site/tunnel";
const target = process.env.GAIUS_PUBLIC_RELAY_TARGET || "ellan.top:25565";
const separator = target.lastIndexOf(":");
const host = separator > 0 ? target.slice(0, separator) : target;
const port = separator > 0 ? Number(target.slice(separator + 1)) : 25565;
assert.ok(host && Number.isInteger(port) && port > 0 && port <= 65535,
  "GAIUS_PUBLIC_RELAY_TARGET must be host:port");

const controls = [];
class InstrumentedWebSocket extends NodeWebSocket {
  constructor(url) {
    super(url, {origin: "null"});
  }

  send(data, ...rest) {
    if (typeof data === "string") {
      try {
        const message = JSON.parse(data);
        if (message && message.type === "connect") {
          controls.push({url: this.url, message, at: performance.now()});
        }
      } catch (ignored) {}
    }
    return super.send(data, ...rest);
  }
}

globalThis.window = globalThis;
Object.defineProperty(globalThis, "location", {
  configurable: true,
  value: {
    href: "https://portable.gaius.invalid/Gaius.html",
    hostname: "portable.gaius.invalid",
    protocol: "https:",
    search: "",
  },
});
globalThis.localStorage = {getItem: () => null};
globalThis.WebSocket = InstrumentedWebSocket;
globalThis.__gaiusDefaultRelayRegistries = false;
globalThis.__gaiusRelayRegistryUrls = [];
globalThis.__gaiusBridgeUrl = relayUrl;
globalThis.__gaiusBridgeUrls = [{name: "Live public relay", url: relayUrl, priority: 100}];

new Function(source.slice(scriptOffset, scriptEnd))();
new Function(source.slice(outboundScriptOffset, outboundScriptEnd))();
const bridge = globalThis.__gaiusNettyBridge;
const stats = globalThis.__gaiusNetworkStats;
const startedAt = performance.now();
bridge.open(91, host, port);

const deadline = Date.now() + 15000;
while (stats.connected !== 1) {
  const error = bridge.pollError(91);
  if (error) {
    throw new Error(error + "\n" + JSON.stringify({
      phases: stats.connectPhases.filter((event) => event.id === 91),
      relayNodes: stats.relayNodes,
      controls,
    }, null, 2));
  }
  if (Date.now() >= deadline) throw new Error("Timed out waiting for live browser Relay path");
  await new Promise((resolve) => setTimeout(resolve, 10));
}

const phases = stats.connectPhases.filter((event) => event.id === 91);
const preparation = phases.find((event) => event.phase === "relay-preparation-start");
const directFailure = phases.find((event) => event.phase === "direct-failed");
const relayStart = phases.find((event) => event.phase === "relay-websocket-start");
const relayConnected = phases.find((event) => event.phase === "relay-connected");
assert.ok(preparation && directFailure && relayStart && relayConnected,
  "Live connect phase telemetry omitted direct-to-relay transition phases");
assert.ok(preparation.elapsedMillis < directFailure.elapsedMillis,
  "Live Relay preparation did not overlap the direct plugin probe");
assert.ok(relayStart.elapsedMillis - directFailure.elapsedMillis < 50,
  "A fixed selection/preflight delay remained after the direct plugin failed");
assert.equal(controls.length, 1,
  "Live candidate selection sent duplicate target connect controls");
assert.equal(new URL(controls[0].url).href, new URL(relayUrl).href);
assert.equal(controls[0].message.host, host);
assert.equal(controls[0].message.port, port);

const connectedMillis = performance.now() - startedAt;
bridge.close(91);
await new Promise((resolve) => setTimeout(resolve, 20));
console.log(JSON.stringify({
  ok: true,
  relayUrl,
  target: `${host}:${port}`,
  connectedMillis: Number(connectedMillis.toFixed(1)),
  directToRelayMillis: Number(
    (relayStart.elapsedMillis - directFailure.elapsedMillis).toFixed(1)
  ),
  targetConnectControls: controls.length,
  phases,
}));
