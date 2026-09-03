#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import {fileURLToPath} from "node:url";

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
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
const network = fs.readFileSync(networkPath, "utf8");
const patcher = fs.readFileSync(patcherPath, "utf8");

const installMethodStart = network.indexOf("public static void install() {");
const installMethodEnd = network.indexOf("    /**", installMethodStart + 1);
assert.ok(installMethodStart >= 0 && installMethodEnd > installMethodStart,
  "BrowserClientNetwork.install() could not be isolated");
const installMethod = network.slice(installMethodStart, installMethodEnd);
assert.doesNotMatch(installMethod, /if\s*\(installed\)\s*\{\s*return;/,
  "install() must not permanently bind the first bridge object");
assert.match(installMethod,
  /configureClientPacketDrain\(\);[\s\S]*installed\s*=\s*installInboundPump\(PUMP_CALLBACK\)/,
  "install() must resolve drain configuration before bridge installation");
assert.match(network,
  /private static final BrowserPumpCallback PUMP_CALLBACK = BrowserClientNetwork::pumpInbound;/,
  "per-frame bridge retries must reuse one cached JSFunctor");

const jsBodyStart = network.indexOf("@JSBody(params = \"callback\", script = \"\"\"");
const jsBodyEnd = network.indexOf("    private static native boolean installInboundPump", jsBodyStart);
assert.ok(jsBodyStart >= 0 && jsBodyEnd > jsBodyStart,
  "installInboundPump() JSBody could not be isolated");
const installScript = network.slice(jsBodyStart, jsBodyEnd);
assert.match(installScript, /installedForThisBridge/,
  "bridge installation has no same-object idempotence guard");
assert.match(installScript,
  /__gaiusInboundPumpInstalledBy\s*===\s*bridge/,
  "bridge installation guard is not bound to object identity");
assert.match(installScript,
  /typeof bridge\.inboundPump === 'function'[\s\S]*typeof bridge\.clientPacketDrain === 'function'/,
  "same-object guard does not verify the required bridge hooks");
assert.match(installScript,
  /bridge\.inboundPumpScheduler\.version === 2[\s\S]*__gaiusRetired !== true/,
  "same-object guard does not verify the scheduler generation state");
assert.match(installScript, /bridge\.__gaiusInboundPumpInstalledBy\s*=\s*bridge/,
  "successful installation does not publish its identity marker");

const runTickHookStart = patcher.indexOf('method.name.equals("runTick") && method.desc.equals("(Z)V")');
const runTickHookEnd = patcher.indexOf('} else if (method.name.equals("debugClientMetricsStart")', runTickHookStart);
assert.ok(runTickHookStart >= 0 && runTickHookEnd > runTickHookStart,
  "Minecraft.runTick hook could not be isolated");
const runTickHook = patcher.slice(runTickHookStart, runTickHookEnd);
assert.match(runTickHook, /"dev\/gaius\/browser\/BrowserClientNetwork",\s*\n\s*"install"/,
  "Minecraft.runTick no longer retries bridge installation each frame");

// Keep this model deliberately equivalent to the bounded JS guard above.  It checks the
// lifecycle semantics without launching Chrome or entering Java/TeaVM: one healthy bridge is
// reused, a replacement bridge is installed, and a retired scheduler is rebuilt.
function hook(name) {
  return function bridgeHook() { return name; };
}
function ensureInstalled(bridge, stats) {
  if (!bridge) return false;
  const scheduler = bridge.inboundPumpScheduler;
  const installedForThisBridge = bridge.__gaiusInboundPumpInstalledBy === bridge &&
    typeof bridge.inboundPump === "function" &&
    typeof bridge.clientPacketDrain === "function" &&
    typeof bridge.invalidateClientPacketDrain === "function" &&
    scheduler && scheduler.version === 2 && scheduler.__gaiusRetired !== true;
  if (installedForThisBridge) return true;
  bridge.inboundPumpScheduler = {
    version: 2,
    generation: (Number(scheduler?.generation) || 0) + 1,
    pending: null,
    __gaiusRetired: false,
  };
  bridge.inboundPump = hook("inbound");
  bridge.clientPacketDrain = hook("drain");
  bridge.invalidateClientPacketDrain = hook("invalidate");
  bridge.__gaiusInboundPumpInstalledBy = bridge;
  stats.inboundPumpInstalled++;
  return true;
}

const stats = {inboundPumpInstalled: 0};
const firstBridge = {stats};
assert.equal(ensureInstalled(firstBridge, stats), true);
assert.equal(stats.inboundPumpInstalled, 1);
const pending = {token: 7};
firstBridge.inboundPumpScheduler.pending = pending;
assert.equal(ensureInstalled(firstBridge, stats), true,
  "reinstall on the same bridge should remain a no-op");
assert.equal(stats.inboundPumpInstalled, 1,
  "same-bridge install recreated the scheduler");
assert.equal(firstBridge.inboundPumpScheduler.pending, pending,
  "same-bridge install cancelled a pending callback");

const secondBridge = {stats};
assert.equal(ensureInstalled(secondBridge, stats), true,
  "replacement bridge was not installed");
assert.equal(stats.inboundPumpInstalled, 2,
  "replacement bridge did not create exactly one new installation");
assert.notEqual(secondBridge.inboundPumpScheduler, firstBridge.inboundPumpScheduler,
  "replacement bridge reused the retired scheduler");
assert.equal(firstBridge.__gaiusInboundPumpInstalledBy, firstBridge);
assert.equal(secondBridge.__gaiusInboundPumpInstalledBy, secondBridge);

// A copied marker must not authorize closures from the old bridge.
const copiedMarkerBridge = {
  stats,
  __gaiusInboundPumpInstalledBy: firstBridge,
  inboundPump: firstBridge.inboundPump,
  clientPacketDrain: firstBridge.clientPacketDrain,
  invalidateClientPacketDrain: firstBridge.invalidateClientPacketDrain,
  inboundPumpScheduler: firstBridge.inboundPumpScheduler,
};
assert.equal(ensureInstalled(copiedMarkerBridge, stats), true);
assert.equal(stats.inboundPumpInstalled, 3,
  "copied bridge marker incorrectly skipped replacement installation");

secondBridge.inboundPumpScheduler.__gaiusRetired = true;
const installationCountBeforeRetired = stats.inboundPumpInstalled;
assert.equal(ensureInstalled(secondBridge, stats), true,
  "retired scheduler was not rebuilt");
assert.equal(stats.inboundPumpInstalled, installationCountBeforeRetired + 1,
  "retired scheduler rebuild did not produce one replacement");
assert.equal(secondBridge.inboundPumpScheduler.__gaiusRetired, false);

console.log(JSON.stringify({
  smoke: "browser-bridge-install-lifecycle",
  result: "pass",
  sameBridgeIdempotent: true,
  pendingCallbackPreserved: true,
  replacementBridgeInstalled: true,
  copiedMarkerRejected: true,
  retiredSchedulerRebuilt: true,
  installations: stats.inboundPumpInstalled,
  teaVmRuntimeProof: false,
  publicRelayRuntimeProof: false,
}));
