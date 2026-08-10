#!/usr/bin/env node

import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {readFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";

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

for (const contract of [
  '"packetQueued"',
  '"packetProcessed"',
  '"reset"',
  "Queue.add result is no longer discarded",
  "method.tryCatchBlocks.add(new TryCatchBlockNode",
  "packetsToBeHandled",
  '"clear"',
]) {
  assert.ok(patcher.includes(contract), "missing PacketProcessor patch contract: " + contract);
}

const repository = fileURLToPath(new URL("../..", import.meta.url));
const patchedClasses = repository + "/port/work/overlays/client-patches";
let bytecode;
try {
  bytecode = execFileSync("javap", [
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

assert.match(scheduleBytecode,
  /Queue\.add:[^\n]*\n\s*\d+: dup\n\s*\d+: ifeq[^\n]*\n\s*\d+: invokestatic[^\n]*BrowserPacketScheduler\.packetQueued/,
  "packetQueued is not conditional on successful Queue.add");
assert.equal(
  (processBytecode.match(/BrowserPacketScheduler\.packetProcessed/g) || []).length,
  2,
  "handle normal and exceptional paths must each retire one decoded packet",
);
assert.match(processBytecode, /Exception table:[\s\S]*any/,
  "packetProcessed is not protected by a catch-all finally path");
assert.ok(
  closeBytecode.indexOf("Queue.clear") >= 0
    && closeBytecode.indexOf("Queue.clear") < closeBytecode.indexOf("BrowserPacketScheduler.reset"),
  "close must clear PacketProcessor's real queue before resetting accounting",
);

console.log("PacketProcessor decoded-packet accounting smoke passed");
