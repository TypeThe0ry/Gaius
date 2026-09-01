#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";
import {join} from "node:path";

const repositoryRoot = fileURLToPath(new URL("../..", import.meta.url));
const patcherPath = join(repositoryRoot,
  "port/tools/src/main/java/dev/gaius/tools/MinecraftClientPatcher.java");
const source = await readFile(patcherPath, "utf8");
const start = source.indexOf("private static void patchClientPacketUtilsBrowserInline");
const end = source.indexOf("private static void patchPacketProcessorBrowserSlice", start);
assert.ok(start >= 0 && end > start, "PacketUtils patch method is missing");
const method = source.slice(start, end);

const commonPackets = Object.freeze([
  "ClientboundPingPacket",
  "ClientboundCustomPayloadPacket",
  "ClientboundResourcePackPushPacket",
  "ClientboundResourcePackPopPacket",
  "ClientboundCookieRequestPacket",
  "ClientboundStoreCookiePacket",
  "ClientboundCustomReportDetailsPacket",
  "ClientboundServerLinksPacket",
  "ClientboundShowDialogPacket",
  "ClientboundClearDialogPacket",
  "ClientboundTransferPacket",
]);

function dispatchModel({listener, packet, queuedPackets, processingQueuedPacket = false}) {
  const common = commonPackets.includes(packet);
  if (listener === "configuration") return {path: "inline", order: [packet]};
  if (listener === "play") {
    if (processingQueuedPacket) return {path: "queued-return", order: []};
    if (common && queuedPackets.length === 0) return {path: "inline", order: [packet]};
    if (queuedPackets.length !== 0 || !common) {
      return {path: "schedule", order: [...queuedPackets, packet]};
    }
  }
  if (common) return {path: "inline", order: [packet]};
  return {path: "vanilla", order: [packet]};
}

function oldUnsafeModel({listener, packet, queuedPackets, processingQueuedPacket = false}) {
  const common = commonPackets.includes(packet);
  if (listener === "configuration" || common) return {path: "inline", order: [packet]};
  if (listener === "play" && processingQueuedPacket) return {path: "queued-return", order: []};
  if (listener === "play" && queuedPackets.length !== 0) {
    return {path: "schedule", order: [...queuedPackets, packet]};
  }
  return listener === "play"
    ? {path: "schedule", order: [...queuedPackets, packet]}
    : {path: "vanilla", order: [packet]};
}

const queuedPlay = ["PLAY-A"];
const unsafeCommon = oldUnsafeModel({
  listener: "play", packet: "ClientboundCustomPayloadPacket", queuedPackets: queuedPlay,
});
assert.equal(unsafeCommon.path, "inline", "regression model no longer captures old inline bypass");
assert.deepEqual(unsafeCommon.order, ["ClientboundCustomPayloadPacket"],
  "old model must demonstrate common packet overtaking queued PLAY");

const fixedCommon = dispatchModel({
  listener: "play", packet: "ClientboundCustomPayloadPacket", queuedPackets: queuedPlay,
});
assert.equal(fixedCommon.path, "schedule", "queued PLAY must force common packet through FIFO");
assert.deepEqual(fixedCommon.order, ["PLAY-A", "ClientboundCustomPayloadPacket"],
  "common packet must not overtake queued PLAY");

const emptyCommon = dispatchModel({
  listener: "play", packet: "ClientboundPingPacket", queuedPackets: [],
});
assert.equal(emptyCommon.path, "inline", "empty PLAY queue should keep common fast path");
assert.deepEqual(emptyCommon.order, ["ClientboundPingPacket"]);

const reentrantCommon = dispatchModel({
  listener: "play", packet: "ClientboundPingPacket", queuedPackets: ["PLAY-A"],
  processingQueuedPacket: true,
});
assert.equal(reentrantCommon.path, "queued-return", "queued handler re-entry must not recurse");
assert.deepEqual(reentrantCommon.order, [], "queued handler re-entry must not duplicate a packet");

const transition = dispatchModel({
  listener: "play", packet: "ClientboundLoginPacket", queuedPackets: ["PLAY-A"],
});
assert.equal(transition.path, "schedule", "protocol transition must preserve queued PLAY FIFO");
assert.deepEqual(transition.order, ["PLAY-A", "ClientboundLoginPacket"]);

const configuration = dispatchModel({
  listener: "configuration", packet: "ClientboundCustomPayloadPacket", queuedPackets: ["PLAY-A"],
});
assert.equal(configuration.path, "inline", "configuration listener bypass must remain unchanged");

// Source-level guard: every common classifier must have a guarded PLAY target
// (commonBacklogCheck) as well as the non-PLAY inline target.  The generated
// bytecode smoke verifies the actual CFG and target offsets for both profiles.
assert.match(method, /LabelNode playListener = new LabelNode\(\);/);
assert.match(method, /LabelNode commonBacklogCheck = new LabelNode\(\);/);
assert.match(method, /code\.add\(new JumpInsnNode\(Opcodes\.IFNE, playListener\)\);/);
assert.match(method, /code\.add\(new MethodInsnNode\([\s\S]*?"isProcessingQueuedPacket"[\s\S]*?\)\);/);
assert.match(method, /code\.add\(new JumpInsnNode\(Opcodes\.IFNE, commonBacklogCheck\)\);/);
assert.match(method, /int commonBacklogChecks = 0;/);
assert.match(method, /commonPlayPacketBranches/);
assert.match(method, /commonBacklogChecks != 1/);
assert.equal((method.match(/"hasPendingPackets"/g) || []).length, 3,
  "the patch must keep separate common and transition backlog gates plus one verifier");

const result = {
  schema: "gaius.packet-utils-fifo-order-smoke.v1",
  status: "pass",
  oldOrderBlocked: true,
  queuedCommon: fixedCommon.order,
  emptyCommon: emptyCommon.order,
  reentrantPath: reentrantCommon.path,
  transitionOrder: transition.order,
  configurationPath: configuration.path,
  commonPacketCount: commonPackets.length,
  sourceGuard: "play-owner-before-common-inline",
};
process.stdout.write(`${JSON.stringify(result)}\n`);
