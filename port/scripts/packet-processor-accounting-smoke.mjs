#!/usr/bin/env node

import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {readFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";
import path from "node:path";

const scheduler = await readFile(new URL(
  "../src/main/java/dev/gaius/browser/BrowserPacketScheduler.java",
  import.meta.url,
), "utf8");
const patcher = await readFile(new URL(
  "../tools/src/main/java/dev/gaius/tools/MinecraftClientPatcher.java",
  import.meta.url,
), "utf8");

const shouldStart = scheduler.indexOf("public static boolean shouldProcessNext()");
const shouldEnd = [
  scheduler.indexOf("public static boolean hasPendingPackets()", shouldStart),
  scheduler.indexOf("public static void packetQueued()", shouldStart),
].filter((index) => index > shouldStart).sort((left, right) => left - right)[0] ?? -1;
assert.ok(shouldStart >= 0 && shouldEnd > shouldStart,
  "BrowserPacketScheduler methods were not found");
const shouldProcessNext = scheduler.slice(shouldStart, shouldEnd);
assert.ok(shouldProcessNext.includes("packetsRemaining--"),
  "per-batch packet execution budget is not bounded");
assert.ok(!shouldProcessNext.includes("queuedPackets"),
  "shouldProcessNext must not pre-decrement the decoded packet queue");
assert.ok(!shouldProcessNext.includes("recordDecodedPacket"),
  "shouldProcessNext must not emit decoded packet completion");
assert.match(scheduler, /private static int queuedPacketHandleDepth;/,
  "nested queued PLAY packet drain guard state is missing");
assert.match(scheduler,
  /public static boolean isProcessingQueuedPacket\(\) \{\s*return queuedPacketHandleDepth > 0;/,
  "queued PLAY packet guard does not cover the complete nested handle scope");
assert.match(scheduler,
  /public static void beginQueuedPacket\(Object packet\) \{[\s\S]*queuedPacketHandleDepth\+\+;/,
  "queued PLAY packet handle entry does not increment its nesting depth");
assert.match(scheduler,
  /public static void packetProcessed\(\) \{[\s\S]*queuedPacketHandleDepth--;[\s\S]*if \(queuedPackets > 0\)/,
  "queued PLAY packet guard is not retired before completion accounting");
assert.match(scheduler,
  /public static void reset\(\) \{[\s\S]*queuedPacketHandleDepth = 0;[\s\S]*clientPacketDrainActive = false;[\s\S]*clientPacketDrainCritical = false;/,
  "PacketProcessor close/reset can leave the queued PLAY guard active");
assert.match(scheduler, /queuedPacketHandleStartedNanos = System\.nanoTime\(\)/,
  "queued PLAY packet handler timing does not start at the exact drain scope");
assert.match(scheduler, /elapsedNanos >= 50_000_000L/,
  "slow queued PLAY packet classification threshold changed");
assert.match(scheduler,
  /recordDecodedPacketQueue\([\s\S]*handleMillis, handleType\);/,
  "queued PLAY packet handler latency/type is not emitted with completion accounting");

let nestedHandleDepth = 0;
const beginHandle = () => { nestedHandleDepth++; };
const endHandle = () => { if (nestedHandleDepth > 0) nestedHandleDepth--; };
beginHandle();
beginHandle();
endHandle();
assert.equal(nestedHandleDepth > 0, true,
  "nested queued handle cleared the outer PacketUtils drain guard");
endHandle();
assert.equal(nestedHandleDepth, 0, "queued handle depth did not return to zero");

for (const contract of [
  '"packetQueued"',
  '"packetProcessed"',
  '"reset"',
  "Queue.add result is no longer discarded",
  "method.tryCatchBlocks.add(new TryCatchBlockNode",
  "packetsToBeHandled",
  '"clear"',
  '"beginQueuedPacket"',
  '"isProcessingQueuedPacket"',
  "ClientPacketListener",
  "ClientboundResourcePackPushPacket",
]) {
  assert.ok(patcher.includes(contract), "missing PacketProcessor patch contract: " + contract);
}

const repository = fileURLToPath(new URL("../..", import.meta.url));
const nativePath = (value) => {
  if (!value) return value;
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
};
const profileIdFromPath = (value) => path.basename(nativePath(value).replaceAll("\\", "/"))
  .replace(/\.json$/, "");
const overlayProfileId = process.env.GAIUS_OVERLAY_DIRECTORY
  ? profileIdFromPath(process.env.GAIUS_OVERLAY_DIRECTORY) : "";
const profileId = process.env.GAIUS_MINECRAFT_VERSION
  || (process.env.GAIUS_VERSION_PROFILE_PATH
    ? profileIdFromPath(process.env.GAIUS_VERSION_PROFILE_PATH)
    : (/^\d+(?:\.\d+)+$/.test(overlayProfileId) ? overlayProfileId : "26.2"));
if (profileId !== "26.2") {
  throw new Error(`PacketProcessor accounting smoke is 26.2-only; got profile ${profileId}`);
}
const overlayRoot = nativePath(process.env.GAIUS_OVERLAY_DIRECTORY ||
  `${repository}/port/work/overlays/${profileId}`);
const patchedClasses = `${overlayRoot}/client-patches`;
function javaTool(name) {
  for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME]
    .filter(Boolean).map(nativePath)) {
    const candidate = path.join(home, "bin", name);
    try {
      execFileSync(candidate, ["-version"], {encoding: "utf8", stdio: "ignore"});
      return candidate;
    } catch {
      // Try the next configured JDK, then the process PATH below.
    }
  }
  return name;
}
const javap = javaTool("javap");
let bytecode;
try {
  bytecode = execFileSync(javap, [
    "-classpath",
    patchedClasses,
    "-p",
    "-c",
    "-v",
    "net.minecraft.network.PacketProcessor",
  ], {encoding: "utf8"});
} catch (error) {
  throw new Error(
    "PacketProcessor patched bytecode is unavailable; run port/scripts/build-overlays.sh first",
    {cause: error},
  );
}

const scheduleStart = bytecode.indexOf("scheduleIfPossible(");
const processStart = bytecode.indexOf("public void processQueuedPackets();", scheduleStart);
const closeStart = bytecode.indexOf("public void close();", processStart);
assert.ok(scheduleStart >= 0 && processStart > scheduleStart && closeStart > processStart,
  "PacketProcessor javap method boundaries were not found");
const scheduleBytecode = bytecode.slice(scheduleStart, processStart);
const processBytecode = bytecode.slice(processStart, closeStart);
const closeBytecode = bytecode.slice(closeStart);
// JDK javap may wrap long method comments at the console width.  Normalize
// whitespace before checking the exact queued-packet call order so wrapping
// cannot turn a valid bytecode trace into a false negative.
const processTrace = processBytecode
  .replace(/\s+/g, " ")
  .replace(/([A-Za-z])\s+([A-Za-z])/g, "$1$2");

assert.match(scheduleBytecode,
  /Queue\.add[\s\S]{0,400}?\n\s*\d+:\s+dup[\s\S]{0,120}?\n\s*\d+:\s+ifeq[\s\S]{0,120}?\n\s*\d+:\s+invokestatic[\s\S]{0,400}?BrowserPacketScheduler\.packetQueued/,
  "packetQueued is not conditional on successful Queue.add");
assert.equal(
  (processBytecode.match(/BrowserPacketScheduler\.packetProcessed/g) || []).length,
  2,
  "handle normal and exceptional paths must each retire one decoded packet",
);
assert.match(
  processTrace,
  /ListenerAndPacket\.packet\s*:[\s\S]{0,500}?BrowserPacketScheduler\.beginQueuedPacket\s*:[\s\S]{0,500}?ListenerAndPacket\.handle\s*:/,
  "queued drain guard does not capture the exact packet immediately before handle",
);
assert.match(processBytecode, /Exception table:[\s\S]*any/,
  "packetProcessed is not protected by a catch-all finally path");
assert.ok(
  closeBytecode.indexOf("Queue.clear") >= 0
    && closeBytecode.indexOf("Queue.clear") < closeBytecode.indexOf("BrowserPacketScheduler.reset"),
  "close must clear PacketProcessor's real queue before resetting accounting",
);

console.log("PacketProcessor decoded-packet accounting smoke passed");
