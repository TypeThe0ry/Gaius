#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";
import {resolve} from "node:path";
import {evaluateGameplayAuthority} from "./performance-metrics.mjs";

const benchmarkSource = await readFile(new URL("./chrome-chunk-benchmark.mjs", import.meta.url), "utf8");
const contract = JSON.parse(await readFile(new URL("./performance-contract.json", import.meta.url), "utf8"));
const portRoot = resolve(fileURLToPath(new URL("../", import.meta.url)));
const portConfig = JSON.parse(await readFile(resolve(portRoot, "config.json"), "utf8"));
const activeVersionProfile = JSON.parse(await readFile(
  resolve(portRoot, portConfig.versionProfile),
  "utf8",
));
const baseGameplayContract = contract.gameplayAuthority;
const protocol = baseGameplayContract.protocols[String(activeVersionProfile.protocolVersion)];
assert.ok(protocol?.packetIds, "active protocol must have a gameplay packet table");
const gameplayContract = {
  ...baseGameplayContract,
  protocolVersion: activeVersionProfile.protocolVersion,
  packetIds: protocol.packetIds,
};

for (const required of [
  "__gaiusNettyBridge",
  "bridge.send",
  "bridge.deliverInbound",
  "parseFrames(direction)",
  "clientbound-block-update",
  "stateTransitionAt",
  "breakAcknowledgementMillis",
  "placeAcknowledgementMillis",
  "workloadActiveAtEmission",
  "gameplayAuthority.finish",
]) {
  assert.ok(benchmarkSource.includes(required), `missing browser evidence hook: ${required}`);
}
assert.equal(gameplayContract.source, "browser-transport-and-client-state");
assert.equal(gameplayContract.route, "singleplayer-worker");
assert.equal(gameplayContract.protocolVersion, activeVersionProfile.protocolVersion);
assert.deepEqual(gameplayContract.packetIds, {
  outboundPlayerAction: 41,
  outboundUseItemOn: 66,
  inboundBlockChangedAck: 4,
  inboundBlockUpdate: 8,
});
assert.equal(gameplayContract.zeroLatencyIsInvalid, true);
assert.equal(gameplayContract.requireActiveWorkloadEvidence, true);

const validEvidence = {
  installed: true,
  source: gameplayContract.source,
  route: gameplayContract.route,
  protocolVersion: gameplayContract.protocolVersion,
  packetIds: gameplayContract.packetIds,
  workerSessionIds: ["client-session"],
  channelIds: [7],
  measurementStartedAt: 100,
  measurementEndedAt: 200,
  transportObserved: true,
  outboundTransportCalls: 12,
  inboundTransportCalls: 18,
  outboundTransportBytes: 240,
  inboundTransportBytes: 620,
  parsedOutboundPackets: 6,
  parsedInboundPackets: 9,
  compressedFrames: 0,
  parserFailures: 0,
  breakEmittedCount: 3,
  placeEmittedCount: 3,
  breakConfirmationCount: 3,
  placeConfirmationCount: 3,
  breakTransportConfirmationCount: 3,
  placeTransportConfirmationCount: 3,
  breakStateTransitionCount: 3,
  placeStateTransitionCount: 3,
  breakAcknowledgementMillis: [10.25, 11.5, 13.75],
  placeAcknowledgementMillis: [12.25, 15.5, 16.75],
  breakTransportMillis: [10.25, 11.5, 13.75],
  placeTransportMillis: [12.25, 15.5, 16.75],
  rollbacks: 0,
  actions: [
    ...[10.25, 11.5, 13.75].map((latency, index) => ({
      id: index + 1,
      type: "break",
      emittedAt: 100 + index * 10,
      emittedPacketId: 35,
      emittedSequence: index + 1,
      workloadActiveAtArm: true,
      workloadActiveAtEmission: true,
      transportConfirmationAt: 100 + index * 10 + latency,
      authoritativeStateAt: 100 + index * 10 + latency,
      stateTransitionAt: 101 + index * 10 + latency,
      confirmedAt: 101 + index * 10 + latency,
      confirmationKind: "clientbound-block-changed-ack",
    })),
    ...[12.25, 15.5, 16.75].map((latency, index) => ({
      id: index + 4,
      type: "place",
      emittedAt: 150 + index * 10,
      emittedPacketId: 56,
      emittedSequence: index + 4,
      workloadActiveAtArm: true,
      workloadActiveAtEmission: true,
      transportConfirmationAt: 150 + index * 10 + latency,
      authoritativeStateAt: 150 + index * 10 + latency,
      stateTransitionAt: 151 + index * 10 + latency,
      confirmedAt: 151 + index * 10 + latency,
      confirmationKind: "clientbound-block-update",
    })),
  ],
};
validEvidence.breakAcknowledgementMillis = validEvidence.actions
  .filter((action) => action.type === "break")
  .map((action) => action.confirmedAt - action.emittedAt);
validEvidence.placeAcknowledgementMillis = validEvidence.actions
  .filter((action) => action.type === "place")
  .map((action) => action.confirmedAt - action.emittedAt);

assert.equal(evaluateGameplayAuthority({evidence: validEvidence, contract: gameplayContract}).verdict,
  "pass", "complete positive evidence should pass");
assert.equal(evaluateGameplayAuthority({evidence: null, contract: gameplayContract}).verdict,
  "inconclusive", "missing browser evidence must not pass");
const noOperations = {...validEvidence,
  breakEmittedCount: 0,
  breakConfirmationCount: 0,
  breakTransportConfirmationCount: 0,
  breakStateTransitionCount: 0,
  breakAcknowledgementMillis: [],
  actions: validEvidence.actions.filter((action) => action.type !== "break"),
};
assert.equal(evaluateGameplayAuthority({evidence: noOperations, contract: gameplayContract}).verdict,
  "inconclusive", "no observed operation must be inconclusive");
const zeroLatency = {...validEvidence, breakAcknowledgementMillis: [0, 0, 0]};
assert.equal(evaluateGameplayAuthority({evidence: zeroLatency, contract: gameplayContract}).verdict,
  "fail", "zero latency samples must never be treated as measured");
const rollback = {...validEvidence, rollbacks: 1};
assert.equal(evaluateGameplayAuthority({evidence: rollback, contract: gameplayContract}).verdict,
  "fail", "an observed rollback must fail the authority gate");
const outsideWorkload = structuredClone(validEvidence);
outsideWorkload.actions[0].workloadActiveAtEmission = false;
assert.equal(evaluateGameplayAuthority({evidence: outsideWorkload, contract: gameplayContract}).verdict,
  "fail", "actions emitted outside the traversal workload must fail");

console.log("Gameplay authority evidence smoke passed");
