import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {MessageChannel} from "node:worker_threads";
import vm from "node:vm";

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

const bridgeScript = source.slice(scriptOffset, scriptEnd).replaceAll("\\\\", "\\");
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
const outboundSchedulerScript = source.slice(outboundScriptOffset, outboundScriptEnd)
  .replaceAll("\\\\", "\\");
const sessionA = "0123456789abcdef0123456789abcdef";
const sessionB = "1123456789abcdef0123456789abcdef";
const sessionC = "2123456789abcdef0123456789abcdef";
const sessionD = "3123456789abcdef0123456789abcdef";

function createRuntime() {
  const context = {
    AbortController,
    Array,
    ArrayBuffer,
    Boolean,
    Date,
    Int8Array,
    JSON,
    Map,
    Math,
    Number,
    Object,
    Promise,
    RegExp,
    Set,
    String,
    TextDecoder,
    TextEncoder,
    URL,
    URLSearchParams,
    Uint8Array,
    clearTimeout,
    console,
    fetch: async () => {
      throw new Error("Portable local sessions must not fetch a relay");
    },
    localStorage: {getItem: () => null},
    location: {
      href: "file:///Downloads/Gaius.html",
      hostname: "",
      protocol: "file:",
      search: "",
    },
    performance,
    queueMicrotask,
    setTimeout,
    WebSocket: class UnexpectedWebSocket {
      constructor() {
        throw new Error("Portable local sessions must not open a WebSocket");
      }
    },
    __gaiusLocalPortClaimTimeoutMs: 80,
    __gaiusLocalServerPorts: new Map(),
  };
  context.globalThis = context;
  context.window = context;
  vm.runInNewContext(`(function() {${bridgeScript}\n})();`, context);
  vm.runInNewContext(`(function() {${outboundSchedulerScript}\n})();`, context);
  return context;
}

async function waitFor(predicate, label, timeoutMillis = 500) {
  const deadline = Date.now() + timeoutMillis;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error(`Timed out waiting for ${label}`);
    await new Promise((resolve) => setTimeout(resolve, 2));
  }
}

function localHost(sessionId) {
  return `client-${sessionId}.gaius-local`;
}

{
  const runtime = createRuntime();
  const {port1, port2} = new MessageChannel();
  runtime.__gaiusLocalServerPorts.set(sessionA, port1);
  runtime.__gaiusNettyBridge.open(1, localHost(sessionA), 25565);
  assert.equal(runtime.__gaiusNetworkStats.localOpened, 1, "first claim did not connect");
  assert.equal(runtime.__gaiusNettyBridge.pollError(1), null);

  runtime.__gaiusNettyBridge.open(1, localHost(sessionA), 25565);
  assert.equal(runtime.__gaiusNetworkStats.localDuplicateOpens, 1,
    "same-socket duplicate open was not idempotent");
  assert.equal(runtime.__gaiusNetworkStats.localOpened, 1,
    "same-socket duplicate open consumed a second port");

  const inbound = new Uint8Array([1, 2, 3, 4]);
  port2.postMessage(inbound.buffer, [inbound.buffer]);
  await waitFor(() => runtime.__gaiusNettyBridge.hasPendingInbound(1), "local inbound");
  assert.deepEqual(Array.from(runtime.__gaiusNettyBridge.pollInbound(1)), [1, 2, 3, 4]);
  runtime.__gaiusNettyBridge.close(1);
  port2.close();
}

{
  const runtime = createRuntime();
  runtime.__gaiusNettyBridge.open(2, localHost(sessionB), 25565);
  assert.equal(runtime.__gaiusNetworkStats.localClaimWaits, 1,
    "missing port did not enter the bounded claim wait");
  const {port1, port2} = new MessageChannel();
  setTimeout(() => runtime.__gaiusLocalServerPorts.set(sessionB, port1), 20);
  await waitFor(() => runtime.__gaiusNetworkStats.localOpened === 1, "late port claim");
  assert.equal(runtime.__gaiusNettyBridge.pollError(2), null,
    "late port registration produced a transport error");
  runtime.__gaiusNettyBridge.close(2);
  port2.close();
}

{
  const runtime = createRuntime();
  runtime.__gaiusNettyBridge.open(3, localHost(sessionC), 25565);
  runtime.__gaiusNettyBridge.open(3, localHost(sessionC), 25565);
  const {port1, port2} = new MessageChannel();
  runtime.__gaiusLocalServerPorts.set(sessionC, port1);
  await waitFor(() => runtime.__gaiusNetworkStats.localOpened === 1,
    "duplicate connect retry claim");
  assert.equal(runtime.__gaiusNettyBridge.pollError(3), null);
  runtime.__gaiusNettyBridge.close(3);
  port2.close();
}

{
  const runtime = createRuntime();
  runtime.__gaiusNettyBridge.open(4, localHost(sessionD), 25565);
  runtime.__gaiusNettyBridge.open(5, localHost(sessionD), 25565);
  const superseded = runtime.__gaiusNettyBridge.pollError(4);
  assert.match(superseded, /superseded/,
    "new retry did not retire the older pending claim");
  const {port1, port2} = new MessageChannel();
  runtime.__gaiusLocalServerPorts.set(sessionD, port1);
  await waitFor(() => runtime.__gaiusNetworkStats.localOpened === 1,
    "superseding connect claim");
  assert.equal(runtime.__gaiusNettyBridge.pollError(5), null);
  runtime.__gaiusNettyBridge.failLocalSession(sessionD, "worker stopped");
  assert.equal(runtime.__gaiusNettyBridge.pollError(5), "worker stopped");
  port2.close();
}

{
  const runtime = createRuntime();
  runtime.__gaiusNettyBridge.open(6,
    localHost("4123456789abcdef0123456789abcdef"), 25565);
  await waitFor(() => runtime.__gaiusNetworkStats.localClaimTimeouts === 1,
    "missing port timeout");
  const error = runtime.__gaiusNettyBridge.pollError(6);
  assert.match(error, /did not register within 80 ms/);
  assert.doesNotMatch(error, /is unavailable/,
    "legacy immediate MessagePort failure remained active");
}

console.log(JSON.stringify({
  ok: true,
  scenarios: [
    "first-connect",
    "same-socket-duplicate",
    "late-registration",
    "retry-supersede",
    "worker-stop",
    "bounded-timeout",
    "portable-file-origin",
  ],
}));
