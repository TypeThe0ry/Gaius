#!/usr/bin/env node

// Static contract smoke for the state hook shared by the 26.2 and 1.21.11
// patchers.  This intentionally does not build TeaVM or launch Chrome: the
// expensive Java calls are guarded by source-level invariants and the public
// browser state schema is checked in the same way the automation consumers see
// it.
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import {fileURLToPath} from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const sourcePath = path.resolve(
  scriptDir,
  "../overrides/libraries/lwjgl-opengl/src/main/java/org/lwjgl/opengl/BrowserOpenGL.java",
);
const patcherPath = path.resolve(
  scriptDir,
  "../tools/src/main/java/dev/gaius/tools/MinecraftClientPatcher.java",
);
const source = fs.readFileSync(sourcePath, "utf8");
const patcher = fs.readFileSync(patcherPath, "utf8");

const reportStart = source.indexOf("public static void reportMinecraftState(");
const reportEnd = source.indexOf("\n    public static Object fallbackClientLevel(", reportStart);
assert.ok(reportStart >= 0 && reportEnd > reportStart, "reportMinecraftState method is missing");
const report = source.slice(reportStart, reportEnd);

assert.match(report, /boolean refreshDetails\s*=\s*MINECRAFT_STATE_DETAILS\.beginReport\(\)/,
  "state reporting must advance the bounded detail-refresh cadence");
assert.match(report, /loadedChunkCount\s*=\s*clientLevel\.getChunkSource\(\)\.getLoadedChunksCount\(\)/,
  "loadedChunkCount must remain a per-report core field");
assert.match(report, /collisionKeyChanged\s*\|\|\s*refreshDetails\s*\n\s*\|\|\s*!MINECRAFT_STATE_DETAILS\.collisionKnown/,
  "collision queries must be behind the detail refresh/key-change guard");
assert.equal((report.match(/clientLevel\.noCollision\(entity\)/g) || []).length, 1,
  "collision query should have one guarded call site");
assert.equal((report.match(/clientLevel\.getBlockState\(pos\)/g) || []).length, 2,
  "block-state lookup should only occur on hit changes or bounded refreshes");
assert.match(report, /selectedItemChanged\s*=\s*MINECRAFT_STATE_DETAILS\.selectedPlayer\s*!=\s*typedPlayer/,
  "selected-item strings must be keyed by player/slot/item identity");
assert.match(report, /if \(selectedItemChanged \|\| refreshDetails\)/,
  "selected-item string construction must be bounded by change/refresh");

// The first report is immediate, then three reports reuse detail data.  This
// is the explicit unit contract for the cache's maximum staleness bound.
const refreshAfterReports = Number(
  source.match(/REFRESH_AFTER_REPORTS\s*=\s*(\d+)/)?.[1],
);
assert.equal(refreshAfterReports, 3, "detail cache cadence changed unexpectedly");
let reportsUntilRefresh = 0;
const refreshPattern = [];
for (let i = 0; i < 8; i++) {
  const refresh = reportsUntilRefresh <= 0;
  refreshPattern.push(refresh);
  reportsUntilRefresh = refresh ? refreshAfterReports : reportsUntilRefresh - 1;
}
assert.deepEqual(refreshPattern, [true, false, false, false, true, false, false, false],
  "detail refresh cadence must be one refresh per four reports");

// Keep the JS object shape consumed by startup/chunk automation intact.  In
// particular, widgets and world-selection telemetry must not be moved to a
// slower side channel just to optimize the tick hook.
const reportJsStart = source.indexOf("private static native void reportMinecraftStateJs(", reportEnd);
assert.ok(reportJsStart > reportEnd, "reportMinecraftStateJs declaration is missing");
const reportJs = source.slice(reportEnd, source.indexOf("private static native void reportMinecraftStateJs(", reportJsStart));
for (const field of ["\"screenWidgets\"", "\"worldSelection\"", "\"loadedChunkCount\"", "\"running\"", "\"screen\"", "\"level\""]) {
  assert.ok(reportJs.includes(field), `state schema lost ${field}`);
}

const runTickStart = patcher.indexOf('method.name.equals("runTick") && method.desc.equals("(Z)V")');
assert.ok(runTickStart >= 0, "runTick hook is missing");
const runTick = patcher.slice(runTickStart, patcher.indexOf("} else if (method.name.equals(\"debugClientMetricsStart\")", runTickStart));
assert.match(runTick, /pumpBrowserChannels\(\)/, "runTick must continue pumping browser network channels");
assert.ok(runTick.indexOf("pumpBrowserChannels()") < runTick.indexOf("minecraftStateReport("),
  "state report must remain after the network pump");
const pumpHelperStart = patcher.indexOf("private static MethodInsnNode pumpBrowserChannels()");
const pumpHelperEnd = patcher.indexOf("\n    private static InsnList minecraftStateReport(", pumpHelperStart);
assert.ok(pumpHelperStart >= 0 && pumpHelperEnd > pumpHelperStart,
  "browser channel pump helper is missing");
const pumpHelper = patcher.slice(pumpHelperStart, pumpHelperEnd);
assert.match(pumpHelper, /dev\/gaius\/browser\/BrowserClientNetwork/,
  "runTick pump must use the shared BrowserClientNetwork guard");
assert.match(pumpHelper, /pumpBrowserChannelsAtFrameBoundary/,
  "runTick pump must use the frame-boundary re-entry-safe wrapper");
assert.doesNotMatch(pumpHelper, /io\/netty\/channel\/browser\/BrowserWebSocketChannel[\s\S]*pumpAll/,
  "runTick must not call the raw BrowserWebSocketChannel pump directly");
assert.match(runTick, /method\.instructions\.insertBefore\(\s*instruction,\s*minecraftStateReport\(/,
  "state report must remain on the existing runTick return hook");
const stateHelperStart = patcher.indexOf("private static InsnList minecraftStateReport(");
const stateHelperEnd = patcher.indexOf("\n    private static boolean hookMinecraftRunCatchDiagnostics", stateHelperStart);
assert.ok(stateHelperStart >= 0 && stateHelperEnd > stateHelperStart, "state helper is missing");
const stateHelper = patcher.slice(stateHelperStart, stateHelperEnd);
assert.ok(stateHelper.indexOf("shouldReportMinecraftState") < stateHelper.indexOf("reportMinecraftState"),
  "state report must remain cadence-gated");

console.log("Minecraft state report hot-path smoke passed", JSON.stringify({
  refreshCadenceReports: refreshAfterReports + 1,
  cachedCalls: {collision: 1, blockState: 2, selectedItem: true},
  schema: ["screenWidgets", "worldSelection", "loadedChunkCount"],
  networkPumpPreserved: true,
}));
