import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const patcherPath = path.join(
  repository,
  "port",
  "tools",
  "src",
  "main",
  "java",
  "dev",
  "gaius",
  "tools",
  "MinecraftClientPatcher.java",
);
const networkPath = path.join(
  repository,
  "port",
  "src",
  "main",
  "java",
  "dev",
  "gaius",
  "browser",
  "BrowserClientNetwork.java",
);

const patcher = fs.readFileSync(patcherPath, "utf8");
const network = fs.readFileSync(networkPath, "utf8");

function block(source, startMarker, endMarker) {
  const start = source.indexOf(startMarker);
  const end = source.indexOf(endMarker, start + startMarker.length);
  assert.ok(start >= 0 && end > start, `missing block: ${startMarker}`);
  return source.slice(start, end);
}

const connectionPatch = block(
  patcher,
  "private static void patchConnectionBrowserWebSocket",
  "private static void patchGlx",
);
assert.doesNotMatch(
  connectionPatch,
  /io\/netty\/channel\/browser\/BrowserWebSocketChannel[\s\S]*pumpAll/,
  "Connection.tick must not inject a second raw global transport pump",
);
assert.doesNotMatch(
  connectionPatch,
  /\.name\(\s*\"pumpAll\"\s*\)/,
  "Connection patch must not retain a raw pumpAll method call",
);
assert.match(
  connectionPatch,
  /Bootstrap[\s\S]*connect[\s\S]*writeComputeFrames\(node, output\)/,
  "Connection WebSocket connect replacement must remain installed",
);

const helper = block(
  patcher,
  "private static MethodInsnNode pumpBrowserChannels()",
  "private static InsnList minecraftStateReport",
);
assert.match(helper, /dev\/gaius\/browser\/BrowserClientNetwork/);
assert.match(helper, /pumpBrowserChannelsAtFrameBoundary/);
assert.doesNotMatch(helper, /io\/netty\/channel\/browser\/BrowserWebSocketChannel/);

const networkWrapper = block(
  network,
  "public static void pumpBrowserChannelsAtFrameBoundary()",
  "public static void beginClientPacketFrame()",
);
assert.match(networkWrapper, /if\s*\(pumping\)/);
assert.match(networkWrapper, /recordJavaPumpSkipped\(\)/);
assert.match(networkWrapper, /BrowserWebSocketChannel\.pumpAll\(\)/);
assert.match(networkWrapper, /finally\s*\{/);

const runTickHook = block(
  patcher,
  'method.name.equals("runTick") && method.desc.equals("(Z)V")',
  "private static MethodInsnNode pumpBrowserChannels()",
);
assert.match(runTickHook, /MethodInsnNode pumpClientChannels = pumpBrowserChannels\(\)/);
assert.match(runTickHook, /beginClientPacketFrame/);
assert.match(runTickHook, /processClientPacketsAtScheduledFrameBoundary/);

const serverHook = block(
  patcher,
  'method.name.equals("processPacketsAndTick")',
  'method.name.equals("pollTask")',
);
assert.match(serverHook, /browserPackets\.add\(pumpBrowserChannels\(\)\)/);
assert.match(serverHook, /tickIntegratedServerDistances/);

const result = {
  smoke: "browser-pump-entry",
  rawConnectionPump: false,
  frameBoundaryWrapper: true,
  clientRunTickHook: true,
  serverFrameHook: true,
  result: "pass",
};
console.log(JSON.stringify(result));
