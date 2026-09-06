import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import vm from "node:vm";

const sourcePath = new URL("../src/main/java/dev/gaius/browser/BrowserHttpProxy.java", import.meta.url);
const source = await readFile(sourcePath, "utf8");
function extractJsBody(marker) {
  const markerOffset = source.indexOf(marker);
  const annotationOffset = source.lastIndexOf('@JSBody(params = {"target", "kind"}, script = """', markerOffset);
  const scriptOffset = source.indexOf('"""', annotationOffset) + 3;
  const scriptEnd = source.lastIndexOf('""")', markerOffset);
  assert.ok(markerOffset > 0 && annotationOffset > 0 && scriptEnd > scriptOffset,
    `JSBody could not be extracted for ${marker}`);
  return source.slice(scriptOffset, scriptEnd).replaceAll("\\\\", "\\");
}
const body = extractJsBody("private static native String createProxyUrl(String target, String kind);");
const context = {URLSearchParams, URL, Map, location: new URL("https://play.example/client/index.html"),
  __gaiusNettyBridge: {channels: new Map()}};
context.globalThis = context;
const call = vm.runInNewContext(`(function(target, kind) { ${body} })`, context);
function proxyUrl({search = "", channels = [], bridgeUrl, bridgeToken} = {}) {
  context.location = new URL("https://play.example/client/index.html" + search);
  context.__gaiusNettyBridge = {channels: new Map(channels.map((entry, index) => [index, entry]))};
  context.__gaiusBridgeUrl = bridgeUrl;
  context.__gaiusBridgeToken = bridgeToken;
  return new URL(call("https://packs.example.test/server-pack.zip", "resource-pack"));
}

let result = proxyUrl({channels: [{connected: true, currentCandidate:
  {url: "wss://ellan.site/tunnel", token: "relay-token", direct: false}}]});
assert.equal(result.origin, "https://ellan.site");
assert.equal(result.pathname, "/proxy/resource-pack");
assert.equal(result.searchParams.get("stream"), "1");
assert.equal(new URL(call("https://textures.minecraft.net/texture/test", "texture"))
  .searchParams.has("stream"), false);
assert.equal(result.searchParams.get("token"), "relay-token");

result = proxyUrl({channels: [
  {connected: true, currentCandidate: {url: "wss://direct.example/tunnel", direct: true}},
  {connected: false, currentCandidate: {url: "wss://stale.example/tunnel", token: "stale", direct: false}},
]});
assert.equal(result.origin, "https://play.example:8080");
assert.equal(result.searchParams.has("token"), false);

result = proxyUrl({channels: [
  {connected: true, currentCandidate: {url: "wss://status.example/tunnel", token: "status", direct: false}},
  {connected: true, currentCandidate: {url: "wss://play.example/tunnel", token: "play", direct: false}},
]});
assert.equal(result.origin, "https://play.example:8080");

result = proxyUrl({channels: [
  {connected: true, currentCandidate: {url: "wss://ellan.site/tunnel", direct: false}},
  {connected: true, currentCandidate: {url: "wss://ellan.site/tunnel", token: "relay-token", direct: false}},
]});
assert.equal(result.origin, "https://ellan.site");
assert.equal(result.searchParams.get("token"), "relay-token");

result = proxyUrl({search: "?bridge=https%3A%2F%2Fconfigured.example%2Ftunnel&bridgeToken=explicit",
  channels: [{connected: true, currentCandidate:
    {url: "wss://ellan.site/tunnel", token: "relay-token", direct: false}}]});
assert.equal(result.origin, "https://configured.example");
assert.equal(result.searchParams.get("token"), "explicit");

console.log("browser-http-proxy-routing-smoke: PASS");
