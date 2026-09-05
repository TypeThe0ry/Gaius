#!/usr/bin/env node

import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {access, copyFile, mkdir, mkdtemp, readFile, rm} from "node:fs/promises";
import {existsSync} from "node:fs";
import {homedir, tmpdir} from "node:os";
import {basename, delimiter, join} from "node:path";
import {fileURLToPath} from "node:url";

const repositoryRoot = fileURLToPath(new URL("../..", import.meta.url));
const nativePath = (value) => {
  if (!value) return value;
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
};
const profileIdFromPath = (value) => basename(nativePath(value).replaceAll("\\", "/"))
  .replace(/\.json$/, "");
const requestedProfile = process.env.GAIUS_MINECRAFT_VERSION
  || (process.env.GAIUS_VERSION_PROFILE_PATH
    ? profileIdFromPath(process.env.GAIUS_VERSION_PROFILE_PATH) : "26.2");
if (requestedProfile !== "26.2") {
  throw new Error(`Minecraft 26.2 P1 patcher smoke is 26.2-only; got ${requestedProfile}`);
}
const rawClientJar = join(repositoryRoot, "port/work/26.2/client-named.jar");
const raw121ClientJar = join(repositoryRoot, "port/work/1.21.11/client-named.jar");
const toolsSource = join(repositoryRoot, "port/tools/src/main/java/dev/gaius/tools");
const asmRoot = join(homedir(), ".m2/repository/org/ow2/asm");
const asm = join(asmRoot, "asm/9.8/asm-9.8.jar");
const asmTree = join(asmRoot, "asm-tree/9.8/asm-tree-9.8.jar");
const asmAnalysis = join(asmRoot, "asm-analysis/9.8/asm-analysis-9.8.jar");
const verifierSource = join(repositoryRoot, "port/scripts/fixtures/GaiusChunkLayerBytecodeVerifier.java");
const forbiddenDeepWorldgenPatchHelpers = Object.freeze([
  "patchFeatureDecorationCooperation",
  "patchCarverCooperation",
  "patchJigsawCooperation",
  "patchTemplatePlacementCooperation",
]);
const synchronousDeepWorldgenClasses = Object.freeze([
  "net.minecraft.world.level.chunk.ChunkGenerator",
  "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator",
  "net.minecraft.world.level.levelgen.carver.WorldCarver",
  "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement",
  "net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate",
  "net.minecraft.world.level.lighting.LightEngine",
  "net.minecraft.world.level.chunk.LevelChunkSection",
  "net.minecraft.world.level.levelgen.NoiseChunk",
  "net.minecraft.world.level.levelgen.SurfaceSystem",
  "net.minecraft.world.level.biome.Climate$RTree$SubTree",
]);

function jdkTool(name) {
  for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME]
    .filter(Boolean).map(nativePath)) {
    const candidate = join(home, "bin", name);
    // `jar -version` is not a portable probe (JDK 25 exits non-zero), while
    // the executable itself is perfectly usable.  Prefer an explicit JDK
    // binary whenever it exists and let execFileSync resolve `.exe` on Windows.
    if (existsSync(candidate) || existsSync(`${candidate}.exe`)) return candidate;
    try {
      execFileSync(candidate, ["-version"], {encoding: "utf8", stdio: "ignore"});
      return candidate;
    } catch {
      // Try the next configured JDK, then PATH.
    }
  }
  return name;
}

function method(bytecode, signature, nextSignature) {
  const start = bytecode.indexOf(signature);
  assert.notEqual(start, -1, `missing bytecode method: ${signature}`);
  const end = nextSignature ? bytecode.indexOf(nextSignature, start + signature.length) : -1;
  return bytecode.slice(start, end === -1 ? bytecode.length : end);
}

function occurrences(haystack, needle) {
  return haystack.split(needle).length - 1;
}

function assertClientPlayPacketQueueContract(packetUtilsBytecode, profileId) {
  const contract = method(packetUtilsBytecode,
    "void ensureRunningOnSameThread(net.minecraft.network.protocol.Packet<T>, T, net.minecraft.network.PacketProcessor)",
    "public static <T extends net.minecraft.network.PacketListener> net.minecraft.ReportedException");
  const transitionPackets = [
    "ClientboundStartConfigurationPacket",
    "ClientboundLoginPacket",
  ];
  const commonInlinePackets = [
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
  ];
  const instructions = bytecodeInstructions(contract);
  const byOffset = new Map(instructions.map((instruction) =>
    [instruction.offset, instruction.instruction]));
  const indexOfInstruction = (predicate, start = 0) => {
    const index = instructions.findIndex((entry, candidate) => candidate >= start && predicate(entry));
    assert.ok(index >= 0, `${profileId} PacketUtils bytecode instruction is missing`);
    return index;
  };
  const branchAfterInstanceof = (packetName, occurrence) => {
    const matches = [];
    for (let index = 0; index < instructions.length; index++) {
      if (!instructions[index].instruction.includes("instanceof") ||
          !instructions[index].instruction.includes(packetName)) {
        continue;
      }
      const next = instructions[index + 1]?.instruction.match(/^(ifne|ifeq)\s+(\d+)/);
      if (next) matches.push({index, opcode: next[1], target: Number(next[2])});
    }
    assert.equal(matches.length, 2,
      `${profileId} ${packetName} must have one non-PLAY and one guarded PLAY classifier`);
    return matches[occurrence];
  };
  const inlineIndex = indexOfInstruction((entry) =>
    entry.instruction.includes("BrowserWebSocketChannel.recordInlineDecodedPacket"));
  const inlineTarget = instructions[inlineIndex].offset;
  const configurationIndex = indexOfInstruction((entry) =>
    entry.instruction.includes("ClientConfigurationPacketListenerImpl"));
  assert.match(instructions[configurationIndex + 1]?.instruction || "", /^ifne\s+/,
    `${profileId} configuration listener does not use the inline boundary`);
  assert.equal(Number(instructions[configurationIndex + 1].instruction.match(/^(?:ifne)\s+(\d+)/)[1]),
    inlineTarget,
    `${profileId} configuration listener no longer uses the inline boundary`);
  const playIndex = indexOfInstruction((entry) =>
    entry.instruction.includes("instanceof") &&
    entry.instruction.includes("ClientPacketListener") &&
    !entry.instruction.includes("ClientConfigurationPacketListenerImpl"));
  const playBranch = instructions[playIndex + 1]?.instruction.match(/^ifne\s+(\d+)/);
  assert.ok(playBranch, `${profileId} PLAY listener guard must branch to the guarded path`);
  const playTarget = Number(playBranch[1]);
  const playTargetIndex = instructions.findIndex((entry) => entry.offset === playTarget);
  assert.ok(playTargetIndex > playIndex,
    `${profileId} PLAY listener guard must precede common inline classification`);
  const drainIndex = indexOfInstruction((entry) =>
    entry.instruction.includes("BrowserPacketScheduler.isProcessingQueuedPacket"));
  assert.ok(drainIndex > playTargetIndex,
    `${profileId} queued-drain guard must execute after entering the PLAY path`);
  const drainBranch = instructions[drainIndex + 1]?.instruction.match(/^ifne\s+(\d+)/);
  assert.ok(drainBranch, `${profileId} PacketUtils queued-drain guard branch is missing`);
  const queuedReturnTarget = Number(drainBranch[1]);
  assert.equal(byOffset.get(queuedReturnTarget), "return",
    `${profileId} queued PLAY handler re-enters vanilla thread identity instead of returning`);
  const commonBranches = commonInlinePackets.map((packet) => ({
    packet,
    first: branchAfterInstanceof(packet, 0),
    second: branchAfterInstanceof(packet, 1),
  }));
  const commonInlineTargets = new Set(commonBranches.map(({first}) => {
    assert.equal(first.opcode, "ifne");
    return first.target;
  }));
  assert.deepEqual([...commonInlineTargets], [inlineTarget],
    `${profileId} non-PLAY common packets must retain the inline fast path`);
  const commonBacklogTargets = new Set(commonBranches.map(({second}) => {
    assert.equal(second.opcode, "ifne");
    return second.target;
  }));
  assert.equal(commonBacklogTargets.size, 1,
    `${profileId} PLAY common packets must share one backlog gate`);
  const commonBacklogTarget = [...commonBacklogTargets][0];
  assert.ok(commonBacklogTarget > playTarget,
    `${profileId} PLAY common packet gate must be after the owner guard`);
  const transitionBranches = transitionPackets.map((packet) => {
    const index = indexOfInstruction((entry) =>
      entry.instruction.includes("instanceof") && entry.instruction.includes(packet));
    const branch = instructions[index + 1]?.instruction.match(/^ifne\s+(\d+)/);
    assert.ok(branch, `${profileId} transition classifier ${packet} is missing`);
    return {packet, target: Number(branch[1])};
  });
  const transitionBacklogTargets = new Set(transitionBranches.map(({target}) => target));
  assert.equal(transitionBacklogTargets.size, 1,
    `${profileId} transition packets must share one backlog gate`);
  const transitionBacklogTarget = [...transitionBacklogTargets][0];
  const pendingCallIndices = [];
  for (let index = 0; index < instructions.length; index++) {
    if (instructions[index].instruction.includes("BrowserPacketScheduler.hasPendingPackets")) {
      pendingCallIndices.push(index);
    }
  }
  assert.equal(pendingCallIndices.length, 2,
    `${profileId} PacketUtils must have separate common and transition FIFO backlog checks`);
  const assertBacklogBlock = (target, label) => {
    const targetIndex = instructions.findIndex((entry) => entry.offset === target);
    assert.ok(targetIndex >= 0, `${profileId} ${label} backlog target is missing`);
    const pendingIndex = indexOfInstruction((entry) =>
      entry.instruction.includes("BrowserPacketScheduler.hasPendingPackets"), targetIndex);
    assert.ok(pendingIndex > targetIndex,
      `${profileId} ${label} backlog gate is not reachable from its classifier`);
    const branch = instructions[pendingIndex + 1]?.instruction.match(/^ifeq\s+(\d+)/);
    assert.ok(branch, `${profileId} ${label} backlog gate must branch on an empty owner queue`);
    assert.equal(Number(branch[1]), inlineTarget,
      `${profileId} ${label} empty backlog must use the inline boundary`);
    return pendingIndex;
  };
  const commonPendingIndex = assertBacklogBlock(commonBacklogTarget, "common");
  const transitionPendingIndex = assertBacklogBlock(transitionBacklogTarget, "transition");
  assert.ok(commonPendingIndex < transitionPendingIndex,
    `${profileId} common and transition backlog blocks must remain ordered`);
  const forcedSchedule = indexOfInstruction((entry) =>
    entry.instruction.includes("PacketProcessor.scheduleIfPossible"));
  const forcedAbort = indexOfInstruction((entry) =>
    entry.instruction.includes("RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD"),
    forcedSchedule);
  const forcedThrow = indexOfInstruction((entry) => entry.instruction === "athrow", forcedAbort);
  const vanillaThreadCheck = indexOfInstruction((entry) =>
    entry.instruction.includes("PacketProcessor.isSameThread"));
  const vanillaSchedule = indexOfInstruction((entry) =>
    entry.instruction.includes("PacketProcessor.scheduleIfPossible"), forcedSchedule + 1);
  const ordered = [configurationIndex, playIndex, ...commonBranches.map(({first}) => first.index),
    drainIndex, ...transitionBranches.map(({packet}) => indexOfInstruction((entry) =>
      entry.instruction.includes("instanceof") && entry.instruction.includes(packet), playTargetIndex)),
    ...commonBranches.map(({second}) => second.index), commonPendingIndex, transitionPendingIndex,
    forcedSchedule, forcedAbort, forcedThrow, inlineIndex, vanillaThreadCheck, vanillaSchedule];
  assert.ok(ordered.every((value, index) => value >= 0 &&
    (index === 0 || value > ordered[index - 1])),
  `${profileId} PacketUtils does not force PLAY traffic through PacketProcessor before vanilla same-thread return`);
  assert.equal(occurrences(contract, "PacketProcessor.scheduleIfPossible"), 2,
    `${profileId} PacketUtils must contain one forced PLAY queue and one vanilla foreign-thread queue`);
  assert.equal(occurrences(contract, "BrowserWebSocketChannel.recordInlineDecodedPacket"), 1,
    `${profileId} PacketUtils must retire exactly one shared inline packet boundary`);
  for (const packet of transitionPackets) {
    assert.equal(occurrences(contract, packet), 1,
      `${profileId} PacketUtils must keep one exact ${packet} transition classifier`);
  }
  for (const packet of commonInlinePackets) {
    assert.equal(occurrences(contract, packet), 2,
      `${profileId} PacketUtils must classify ${packet} in both non-PLAY and guarded PLAY paths`);
  }
  assert.equal(occurrences(contract, "ClientPacketListener"), 1,
    `${profileId} PacketUtils must have exactly one PLAY-listener force-queue branch`);
  assert.equal(occurrences(contract, "BrowserPacketScheduler.isProcessingQueuedPacket"), 1,
    `${profileId} PacketUtils must bypass force-queue exactly while draining it`);
  assert.equal(occurrences(contract, "BrowserPacketScheduler.hasPendingPackets"), 2,
    `${profileId} PacketUtils must preserve queued PLAY FIFO for common and transition packets`);
}

function assertKeepAliveImmediateSendContract(listenerBytecode, profileId) {
  const keepAlive = method(listenerBytecode,
    "public void handleKeepAlive(net.minecraft.network.protocol.common.ClientboundKeepAlivePacket);",
    "public void handlePing(net.minecraft.network.protocol.common.ClientboundPingPacket);");
  const accounting = keepAlive.indexOf("BrowserWebSocketChannel.recordInlineDecodedPacket");
  const packetId = keepAlive.indexOf("ClientboundKeepAlivePacket.getId:()J");
  const response = keepAlive.indexOf("ServerboundKeepAlivePacket.\"<init>\":(J)V");
  const sendWhen = keepAlive.indexOf("Method sendWhen:");
  assert.ok(accounting >= 0 && accounting < packetId && packetId < response &&
    response < sendWhen,
  `${profileId} keepalive does not retire decoder accounting before immediate sendWhen`);
  assert.equal(occurrences(keepAlive, "BrowserWebSocketChannel.recordInlineDecodedPacket"), 1,
    `${profileId} keepalive must retire exactly one inline decoder boundary`);
  assert.equal(occurrences(keepAlive, "Method sendWhen:"), 1,
    `${profileId} keepalive must retain exactly one immediate sendWhen path`);
  assert.equal(occurrences(keepAlive, "PacketUtils.ensureRunningOnSameThread"), 0,
    `${profileId} keepalive unexpectedly entered the ordinary PLAY PacketUtils queue`);

  const predicateSignature = listenerBytecode.match(
    /private static boolean lambda\$handleKeepAlive\$\d+\(\);/)?.[0];
  assert.ok(predicateSignature, `${profileId} keepalive predicate signature is missing`);
  const predicate = method(listenerBytecode, predicateSignature, "private static java.util.List");
  assert.deepEqual(bytecodeInstructions(predicate).map(({instruction}) => instruction),
    ["iconst_1", "ireturn"],
    `${profileId} keepalive predicate is not the exact immediate-send constant`);
  assert.doesNotMatch(predicate, /RenderSystem\.isFrozenAtPollEvents/,
    `${profileId} keepalive predicate still waits for the desktop render poll gate`);

  const sendWhenContract = method(listenerBytecode,
    "private void sendWhen(net.minecraft.network.protocol.Packet<? extends net.minecraft.network.ServerboundPacketListener>, java.util.function.BooleanSupplier, java.time.Duration);",
    "private net.minecraft.client.gui.screens.Screen addOrUpdatePackPrompt");
  const sendWhenInstructions = bytecodeInstructions(sendWhenContract);
  const supplierIndex = sendWhenInstructions.findIndex(({instruction}) =>
    instruction.includes("BooleanSupplier.getAsBoolean:()Z"));
  assert.ok(supplierIndex >= 0 &&
    /^ifeq\s+\d+/.test(sendWhenInstructions[supplierIndex + 1]?.instruction || "") &&
    sendWhenInstructions[supplierIndex + 2]?.instruction === "aload_0" &&
    sendWhenInstructions[supplierIndex + 3]?.instruction === "aload_1" &&
    sendWhenInstructions[supplierIndex + 4]?.instruction.includes(
      "Method send:(Lnet/minecraft/network/protocol/Packet;)V"),
  `${profileId} sendWhen true branch no longer synchronously sends the keepalive response`);
  assert.equal(occurrences(sendWhenContract, "BooleanSupplier.getAsBoolean:()Z"), 1,
    `${profileId} sendWhen must evaluate one keepalive liveness predicate`);
  assert.equal(occurrences(sendWhenContract,
    "Method send:(Lnet/minecraft/network/protocol/Packet;)V"), 1,
  `${profileId} sendWhen must contain one direct send path`);
  assert.equal(occurrences(sendWhenContract, "java/util/List.add:(Ljava/lang/Object;)Z"), 1,
    `${profileId} sendWhen deferred fallback was unexpectedly removed`);
}

function assertPacketProcessorQueueContract(packetProcessorBytecode, profileId) {
  const contract = method(packetProcessorBytecode,
    "public void processQueuedPackets();", "public void close();");
  assert.match(contract, /BrowserPacketScheduler\.beginBatch[^\n]*\(Ljava\/lang\/Object;\)Z/,
    `${profileId} PacketProcessor does not claim an owner-aware batch`);
  assert.match(contract, /BrowserPacketScheduler\.shouldProcessNext[^\n]*\(Ljava\/lang\/Object;\)Z/,
    `${profileId} PacketProcessor does not use owner-aware batch accounting`);
  const packetAccessor = contract.indexOf("ListenerAndPacket.packet");
  const beginGuard = contract.indexOf("BrowserPacketScheduler.beginQueuedPacket");
  const handle = contract.indexOf("ListenerAndPacket.handle");
  assert.ok(packetAccessor >= 0 && packetAccessor < beginGuard && beginGuard < handle,
    `${profileId} PacketProcessor does not bind timing/recursion guard to the exact packet handle`);
  assert.equal(occurrences(contract, "BrowserPacketScheduler.beginQueuedPacket"), 1,
    `${profileId} PacketProcessor must enter one queued packet guard per poll`);
  assert.equal(occurrences(contract, "BrowserPacketScheduler.packetProcessed"), 2,
    `${profileId} PacketProcessor normal/exception exits must each retire one queued packet`);
  assert.match(contract, /Exception table:[\s\S]*any/,
    `${profileId} PacketProcessor handle scope lacks a catch-all retirement path`);
  const instructions = bytecodeInstructions(contract);
  const beginInstruction = instructions.find(({instruction}) =>
    instruction.includes("BrowserPacketScheduler.beginQueuedPacket"));
  assert.ok(beginInstruction?.instruction.includes(
    "(Ljava/lang/Object;Ljava/lang/Object;)V"),
    `${profileId} PacketProcessor does not pass owner + exact Packet object into telemetry`);
  assert.match(packetProcessorBytecode,
    /private void gaius\$vanillaProcessQueuedPackets\(\);/,
    `${profileId} PacketProcessor has no retained vanilla fallback`);
}

function assertPacketProcessorLifecycleContract(packetProcessorBytecode, profileId) {
  const contract = method(packetProcessorBytecode,
    "public net.minecraft.network.PacketProcessor(java.lang.Thread);",
    "public boolean isSameThread();");
  assert.equal(occurrences(contract, "BrowserPacketScheduler.bindPacketProcessorLifecycle"), 1,
    `${profileId} PacketProcessor constructor must bind the explicit lifecycle owner exactly once`);
  assert.match(contract,
    /invokestatic[^\n]*BrowserPacketScheduler\.bindPacketProcessorLifecycle[^\n]*\(Ljava\/lang\/Object;\)Z/,
    `${profileId} PacketProcessor constructor lifecycle bind descriptor changed`);
  assert.match(contract, /pop\s*\n\s*\d+:\s+return/,
    `${profileId} PacketProcessor constructor must discard lifecycle bind status before return`);
}

function bytecodeInstructions(methodBytecode) {
  return methodBytecode.split(/\r?\n/).flatMap(line => {
    const match = line.match(/^\s*(\d+):\s+(.*)$/);
    return match ? [{offset: Number(match[1]), instruction: match[2]}] : [];
  });
}

function graphicsPresetApply(bytecode) {
  return method(bytecode,
    "public void apply(net.minecraft.client.Minecraft);",
    "private static <T> void set(");
}

function graphicsPresetDistanceConstants(applyBytecode, getter) {
  const instructions = bytecodeInstructions(applyBytecode);
  const matches = instructions.flatMap((entry, index) =>
    entry.instruction.includes(`Options.${getter}:`) ? [index] : []);
  assert.equal(matches.length, 3,
    `GraphicsPreset.apply must have three ${getter} setter calls`);
  return matches.map(index => (instructions[index + 1]?.instruction ?? "").replace(/\s+/g, " "));
}

function graphicsPresetCustomReturn(applyBytecode) {
  const target = applyBytecode.match(/default:\s+(\d+)/);
  assert.ok(target, "GraphicsPreset.apply ordinal switch has no CUSTOM default");
  const entry = bytecodeInstructions(applyBytecode)
    .find(instruction => instruction.offset === Number(target[1]));
  assert.equal(entry?.instruction, "return",
    "GraphicsPreset.apply CUSTOM arm is no longer the default return arm");
  return entry.instruction;
}

const HOLDERS_PER_TURN = 16;

function cursorModel(radius, stop = null, batchLimit = HOLDERS_PER_TURN) {
  const visits = [];
  const batches = [];
  let batch = [];
  let active = true;
  let yieldPending = false;
  for (let x = -radius; x <= radius && active; x++) {
    for (let z = -radius; z <= radius && active; z++) {
      const index = visits.length;
      const outcome = stop?.index === index ? stop.kind : "success";
      const visit = {x, z, outcome};
      visits.push(visit);
      if (outcome !== "success") {
        active = false;
        yieldPending = false;
        if (batch.length) batches.push(batch);
        continue;
      }
      batch.push(visit);
      if (x === radius && z === radius) {
        active = false;
      }
      if (batch.length === batchLimit || !active) {
        batches.push(batch);
        batch = [];
        // One zero-delay continuation separates bounded batches.  The final
        // batch also crosses this barrier before scheduled futures are drained.
        yieldPending = true;
        yieldPending = false;
      }
    }
  }
  return {visits, batches, active, yieldPending};
}

function pendingDependencyLayerModel(
  turns,
  dependencyOf = new Map(),
  {drainAfterEachTurn = false} = {},
) {
  const submitted = [];
  const submittedSet = new Set();
  const completed = [];
  const completedSet = new Set();
  const pending = [];
  // Keep the planned turn shape separate from the turns actually submitted.
  // The old one-holder await stops after turn 0, but its intended schedule is
  // still [1, ...] and must be visible to the regression assertion.
  const batches = turns.map(turn => [...turn]);
  const submittedBatches = [];
  const drains = [];

  function drainPending(stage) {
    let progress = true;
    const drained = [];
    while (progress) {
      progress = false;
      const remaining = [];
      for (const item of pending) {
        const dependency = item.dependsOn;
        if (dependency == null || completedSet.has(dependency)) {
          completed.push(item.holder);
          completedSet.add(item.holder);
          drained.push(item.holder);
          progress = true;
        } else {
          remaining.push(item);
        }
      }
      pending.splice(0, pending.length, ...remaining);
    }
    drains.push({stage, drained, pending: pending.map(item => item.holder)});
  }

  for (const [turnIndex, turn] of turns.entries()) {
    const submittedBatch = [];
    for (const holder of turn) {
      assert.equal(submittedSet.has(holder), false,
        `layer model submitted holder ${holder} more than once`);
      submittedSet.add(holder);
      submitted.push(holder);
      submittedBatch.push(holder);
      pending.push({holder, dependsOn: dependencyOf.get(holder) ?? null});
    }
    submittedBatches.push(submittedBatch);
    if (drainAfterEachTurn) {
      drainPending(`turn-${turnIndex}`);
      if (pending.length) {
        return {
          blocked: true,
          blockedAtTurn: turnIndex,
          waitedAt: submitted.length,
          submitted,
          completed,
          pending: pending.map(item => ({...item})),
          batches,
          submittedBatches,
          drains,
        };
      }
    }
  }

  // The fixed barrier drains only after every holder in the layer was submitted.
  drainPending("final-layer-barrier");
  return {
    blocked: pending.length !== 0,
    blockedAtTurn: null,
    waitedAt: submitted.length,
    submitted,
    completed,
    pending: pending.map(item => ({...item})),
    batches,
    submittedBatches,
    drains,
  };
}

function boundedLayerTurns(holderCount, batchLimit) {
  const turns = [];
  for (let holder = 0; holder < holderCount; holder += batchLimit) {
    turns.push(Array.from(
      {length: Math.min(batchLimit, holderCount - holder)},
      (_, offset) => holder + offset,
    ));
  }
  return turns;
}

function oneHolderTurns(holderCount) {
  return boundedLayerTurns(holderCount, 1);
}

function layerBarrierSafetyModel(holderCount, batchLimit, waitBetweenBatches) {
  const run = pendingDependencyLayerModel(
    boundedLayerTurns(holderCount, batchLimit),
    new Map([[0, holderCount - 1]]),
    {drainAfterEachTurn: waitBetweenBatches},
  );
  return {
    blocked: run.blocked,
    waitedAt: run.waitedAt,
    scheduled: run.submitted,
    batches: run.batches,
    submittedBatches: run.submittedBatches,
    completed: run.completed,
    pending: run.pending,
    drains: run.drains,
  };
}

for (const radius of [0, 1, 2]) {
  const run = cursorModel(radius);
  const expected = (radius * 2 + 1) ** 2;
  assert.equal(run.visits.length, expected,
    `cursor radius ${radius} must visit every holder exactly once`);
  assert.equal(run.active, false,
    `cursor radius ${radius} must close after its final holder`);
  assert.equal(new Set(run.visits.map(({x, z}) => `${x},${z}`)).size, expected,
    `cursor radius ${radius} must not duplicate holders`);
  assert.equal(run.visits.at(-1)?.outcome, "success",
    `cursor radius ${radius} must process its final holder before closing`);
  assert.equal(run.batches.length, Math.ceil(expected / HOLDERS_PER_TURN),
    `cursor radius ${radius} must use the minimum bounded continuation count`);
  assert.ok(run.batches.every(batch => batch.length > 0 && batch.length <= HOLDERS_PER_TURN),
    `cursor radius ${radius} exceeded the holder batch limit`);
}
const cancelledCursor = cursorModel(2, {index: 3, kind: "cancel"});
assert.equal(cancelledCursor.visits.length, 4,
  "cursor cancellation must stop at the cancellation holder");
assert.equal(cancelledCursor.visits.at(-1)?.outcome, "cancel",
  "cursor cancellation outcome must be observable at the holder boundary");
assert.equal(cancelledCursor.active, false,
  "cursor cancellation must clear active state");
assert.equal(cancelledCursor.yieldPending, false,
  "cursor cancellation must not retain a continuation future");
const failedCursor = cursorModel(2, {index: 4, kind: "failure"});
assert.equal(failedCursor.visits.length, 5,
  "cursor failure must stop at the failed holder");
assert.equal(failedCursor.visits.at(-1)?.outcome, "failure",
  "cursor failure outcome must be observable at the holder boundary");
assert.equal(failedCursor.active, false,
  "cursor failure must clear active state");
assert.equal(failedCursor.yieldPending, false,
  "cursor failure must not retain a continuation future");

const unsafeLayerOrder = layerBarrierSafetyModel(17, HOLDERS_PER_TURN, true);
assert.equal(unsafeLayerOrder.blocked, true,
  "the dependency fixture must expose per-batch await starvation");
assert.equal(unsafeLayerOrder.waitedAt, HOLDERS_PER_TURN,
  "the unsafe model must wait before the final holder is submitted");
const fixedLayerOrder = layerBarrierSafetyModel(17, HOLDERS_PER_TURN, false);
assert.equal(fixedLayerOrder.blocked, false,
  "the active cursor must submit the complete layer before waiting");
assert.deepEqual(fixedLayerOrder.batches.map(batch => batch.length), [16, 1],
  "a 17-holder layer must span two bounded turns without an intermediate layer wait");
assert.deepEqual(fixedLayerOrder.submittedBatches.map(batch => batch.length), [16, 1],
  "the fixed barrier must submit both bounded turns before draining");
assert.deepEqual(fixedLayerOrder.scheduled,
  Array.from({length: 17}, (_, holder) => holder),
  "the fixed barrier must submit all 17 holders before waiting");
assert.equal(fixedLayerOrder.waitedAt, 17,
  "the fixed model must reach the layer barrier only after every holder is submitted");
assert.equal(fixedLayerOrder.pending.length, 0,
  "the fixed layer barrier must drain every pending holder future");
assert.deepEqual(fixedLayerOrder.completed.sort((a, b) => a - b),
  Array.from({length: 17}, (_, holder) => holder),
  "the fixed layer barrier must complete all holders after the final batch");
assert.equal(fixedLayerOrder.drains.length, 1,
  "the fixed model must not drain any holder batch before the final barrier");
assert.equal(fixedLayerOrder.drains.at(-1)?.stage, "final-layer-barrier",
  "the fixed layer must drain pending futures only at its final barrier");

const oldOneHolderOrder = pendingDependencyLayerModel(
  oneHolderTurns(17),
  new Map([[0, 16]]),
  {drainAfterEachTurn: true},
);
assert.equal(oldOneHolderOrder.blocked, true,
  "the old one-holder await order must block on a same-layer dependency");
assert.equal(oldOneHolderOrder.blockedAtTurn, 0,
  "the old order must await holder 0 before submitting its dependency holder");
assert.deepEqual(oldOneHolderOrder.batches.map(batch => batch.length),
  Array.from({length: 17}, () => 1),
  "the old regression model must use one holder per browser turn");
assert.deepEqual(oldOneHolderOrder.pending, [{holder: 0, dependsOn: 16}],
  "the old blocked order must retain holder 0 pending on holder 16");
assert.deepEqual(oldOneHolderOrder.submitted, [0],
  "the old await model must not submit the dependency holder after blocking");

await Promise.all([
  access(rawClientJar),
  access(raw121ClientJar),
  access(asm),
  access(asmTree),
  access(asmAnalysis),
  access(verifierSource),
]);
const browserPatcherSource = await readFile(
  join(toolsSource, "Minecraft262BrowserPatcher.java"),
  "utf8",
);
assert.match(browserPatcherSource, /BROWSER_HOLDERS_PER_TURN = 16/,
  "26.2 holder batching must retain the reviewed 16-holder upper bound");
for (const forbidden of [
  "BROWSER_LAYER_YIELD",
  "CHUNK_GENERATION_YIELD",
  "BrowserChunkGenerationYield",
  "browserLayerYield",
  "Platform.schedule",
  "writeChunkGenerationYieldHelper",
]) {
  assert.equal(browserPatcherSource.includes(forbidden), false,
    `26.2 patcher must not retain artificial yield path: ${forbidden}`);
}
const clientPatcherSource = await readFile(
  join(toolsSource, "MinecraftClientPatcher.java"),
  "utf8",
);
const schedulerSource = await readFile(
  join(repositoryRoot, "port/src/main/java/dev/gaius/browser/BrowserWorldgenScheduler.java"),
  "utf8",
);
for (const contract of [
  "patchMobBrowserAiCooperation(args[0], root.resolve(",
  '"net/minecraft/world/entity/Mob.class"',
  "browserWorldgenMobAiPulse()",
  "browserWorldgenMobEntityPulse()",
  '"Mob.serverAiStep AI stage shape changed: "',
]) {
  assert.ok(clientPatcherSource.includes(contract),
    `missing Mob.serverAiStep cooperation contract: ${contract}`);
}
assert.match(schedulerSource, /public static void mobAiPulse\(\)/,
  "scheduler is missing the Mob AI cooperative checkpoint");
assert.match(schedulerSource, /public static void mobEntityPulse\(Object entity\)/,
  "scheduler is missing the LivingEntity Mob fallback checkpoint");
assert.match(schedulerSource, /__gaiusMobAiTelemetry/,
  "Mob AI telemetry must remain opt-in and bounded");
for (const helper of forbiddenDeepWorldgenPatchHelpers) {
  assert.equal(browserPatcherSource.includes(helper), false,
    `${helper} must not exist under the synchronous deep-worldgen policy`);
}
for (const contract of [
  "browserWorldgenBeginServerWorkTurn()",
  "method.instructions.insertBefore(instruction, browserWorldgenBeginServerWorkTurn())",
  "method.instructions.insert(instruction, browserWorldgenCheckpoint())",
  "browserWorldgenBeginTaskWork()",
  "browserWorldgenEndTaskWork()",
  "instrumentBrowserTaskScope(",
  '"MinecraftServer.pollTask"',
  '"beginTaskWork",\n                "(Ljava/lang/String;)I"',
  '"endTaskWork",\n                "(I)V"',
]) {
  assert.ok(clientPatcherSource.includes(contract),
    `missing server tick scheduler boundary contract: ${contract}`);
}
assert.ok(browserPatcherSource.includes("requireNoServerWorkTurnReset"),
  "26.2 task patcher does not guard the shared server-work clock from per-task resets");
for (const contract of [
  '"beginTaskWork",\n                "(Ljava/lang/String;)I"',
  '"endTaskWork",\n                "(I)V"',
]) {
  assert.ok(browserPatcherSource.includes(contract),
    `missing 26.2 task-scope token contract: ${contract}`);
}
assert.ok(browserPatcherSource.includes(
  'writeComputeFrames(node, root.resolve(owner + ".class"))',
), "26.2 task patcher does not recompute frames after task-scope instrumentation");
const root = await mkdtemp(join(tmpdir(), "gaius-mc262-p1-"));
try {
  const classes = join(root, "classes");
  const clientPatches = join(root, "client-patches");
  const browserPatches = join(root, "browser-patches");
  const generic121Patches = join(root, "generic-121-patches");
  const clientJar = join(root, "client.jar");
  const generic121Jar = join(root, "client-1.21.11.jar");
  await Promise.all([
    mkdir(classes, {recursive: true}),
    mkdir(clientPatches, {recursive: true}),
    mkdir(browserPatches, {recursive: true}),
    mkdir(generic121Patches, {recursive: true}),
    copyFile(rawClientJar, clientJar),
    copyFile(raw121ClientJar, generic121Jar),
  ]);

  const javac = jdkTool("javac");
  const java = jdkTool("java");
  const jar = jdkTool("jar");
  const javap = jdkTool("javap");
  // Java's classpath separator follows the host OS, not the path separator
  // used to compose the individual jar paths.  The smoke is also run from
  // PowerShell on Windows, where ':' silently turns the second jar into an
  // invalid drive-qualified classpath entry.
  const classpath = [asm, asmTree].join(delimiter);
  execFileSync(javac, [
    "--release", "21", "-proc:none", "-classpath", classpath, "-d", classes,
    join(toolsSource, "MinecraftClientPatcher.java"),
    join(toolsSource, "Minecraft262BrowserPatcher.java"),
  ], {encoding: "utf8", timeout: 30_000});
  execFileSync(java, ["-classpath", [classes, classpath].join(delimiter),
    "dev.gaius.tools.MinecraftClientPatcher", clientJar, clientPatches], {
    encoding: "utf8", timeout: 30_000,
  });
  execFileSync(jar, ["--update", "--file", clientJar, "-C", clientPatches, "."], {
    encoding: "utf8", timeout: 30_000,
  });
  execFileSync(java, ["-classpath", [classes, classpath].join(delimiter),
    "dev.gaius.tools.Minecraft262BrowserPatcher", clientJar, browserPatches], {
    encoding: "utf8", timeout: 30_000,
  });
  execFileSync(jar, ["--update", "--file", clientJar, "-C", browserPatches, "."], {
    encoding: "utf8", timeout: 30_000,
  });

  const verifierClasspath = [asm, asmTree, asmAnalysis].join(delimiter);
  execFileSync(javac, [
    "--release", "21", "-proc:none", "-classpath", verifierClasspath,
    "-d", classes, verifierSource,
  ], {encoding: "utf8", timeout: 30_000});
  const verifierOutput = execFileSync(java, [
    "-classpath", [classes, verifierClasspath].join(delimiter),
    "GaiusChunkLayerBytecodeVerifier", clientJar, "26.2",
  ], {encoding: "utf8", timeout: 30_000});
  assert.match(verifierOutput, /BASIC_VERIFIER_OK .*ChunkGenerationTask\.class/,
    "ASM BasicVerifier did not validate ChunkGenerationTask");
  assert.match(verifierOutput, /NO_ARTIFICIAL_LAYER_YIELD_OK .*ChunkGenerationTask/,
    "ASM CFG verifier did not reject artificial 26.2 layer yield paths");
  assert.match(verifierOutput, /NO_HELPER_CLASS_OK 26\.2/,
    "26.2 patcher still emitted the artificial yield helper");
  assert.match(verifierOutput, /CFG_VERIFIER_OK net\/minecraft\/server\/level\/ChunkGenerationTask/,
    "ASM CFG verifier did not validate the chunk layer barrier");
  assert.match(verifierOutput,
    /PROFILE_CFG_OK 26\.2 net\/minecraft\/server\/level\/ChunkGenerationTask/,
    "ASM profile verifier did not validate 26.2 holder pulse paths");
  process.stdout.write(verifierOutput);

  const rawGraphics = execFileSync(javap, ["-classpath", rawClientJar, "-p", "-c",
    "net.minecraft.client.GraphicsPreset"], {
    encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000,
  });
  const patchedGraphics = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.client.GraphicsPreset"], {
    encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000,
  });
  const rawGraphicsApply = graphicsPresetApply(rawGraphics);
  const patchedGraphicsApply = graphicsPresetApply(patchedGraphics);
  assert.deepEqual(graphicsPresetDistanceConstants(patchedGraphicsApply, "renderDistance"),
    ["bipush 6", "bipush 16", "bipush 32"],
    "26.2 FAST render distance was not overlaid to 6");
  assert.deepEqual(graphicsPresetDistanceConstants(patchedGraphicsApply, "simulationDistance"),
    ["bipush 4", "bipush 12", "bipush 12"],
    "26.2 FAST simulation distance was not overlaid to 4");
  assert.deepEqual(graphicsPresetDistanceConstants(rawGraphicsApply, "renderDistance"),
    ["bipush 8", "bipush 16", "bipush 32"],
    "26.2 raw FAST render distance shape changed");
  assert.deepEqual(graphicsPresetDistanceConstants(rawGraphicsApply, "simulationDistance"),
    ["bipush 6", "bipush 12", "bipush 12"],
    "26.2 raw FAST simulation distance shape changed");
  assert.equal(graphicsPresetCustomReturn(patchedGraphicsApply),
    graphicsPresetCustomReturn(rawGraphicsApply),
    "26.2 CUSTOM graphics preset arm changed");

  // The generic 1.21.11 path never invokes Minecraft262BrowserPatcher.  Run it on
  // a disposable 1.21 jar and verify its FAST values remain vanilla 8/6.
  execFileSync(java, ["-classpath", [classes, classpath].join(delimiter),
    "dev.gaius.tools.MinecraftClientPatcher", generic121Jar, generic121Patches,
    "1.21.11"], {encoding: "utf8", timeout: 60_000});
  execFileSync(jar, ["--update", "--file", generic121Jar, "-C", generic121Patches, "."], {
    encoding: "utf8", timeout: 30_000,
  });
  const generic121Graphics = execFileSync(javap, ["-classpath", generic121Jar, "-p", "-c",
    "net.minecraft.client.GraphicsPreset"], {
    encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000,
  });
  const generic121Apply = graphicsPresetApply(generic121Graphics);
  assert.deepEqual(graphicsPresetDistanceConstants(generic121Apply, "renderDistance"),
    ["bipush 8", "bipush 16", "bipush 32"],
    "1.21.11 FAST render distance was changed by the 26.2 overlay path");
  assert.deepEqual(graphicsPresetDistanceConstants(generic121Apply, "simulationDistance"),
    ["bipush 6", "bipush 12", "bipush 12"],
    "1.21.11 FAST simulation distance was changed by the 26.2 overlay path");

  const patchedPacketUtils = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.network.protocol.PacketUtils"], {
    encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000,
  });
  const patched121PacketUtils = execFileSync(javap, [
    "-classpath", generic121Jar, "-p", "-c", "net.minecraft.network.protocol.PacketUtils",
  ], {
    encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000,
  });
  assertClientPlayPacketQueueContract(patchedPacketUtils, "26.2");
  assertClientPlayPacketQueueContract(patched121PacketUtils, "1.21.11");
  const patchedCommonListener = execFileSync(javap, [
    "-classpath", clientJar, "-p", "-c",
    "net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl",
  ], {encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000});
  const patched121CommonListener = execFileSync(javap, [
    "-classpath", generic121Jar, "-p", "-c",
    "net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl",
  ], {encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000});
  assertKeepAliveImmediateSendContract(patchedCommonListener, "26.2");
  assertKeepAliveImmediateSendContract(patched121CommonListener, "1.21.11");
  const patchedPacketProcessor = execFileSync(javap, [
    "-classpath", clientJar, "-p", "-c", "net.minecraft.network.PacketProcessor",
  ], {encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000});
  const patched121PacketProcessor = execFileSync(javap, [
    "-classpath", generic121Jar, "-p", "-c", "net.minecraft.network.PacketProcessor",
  ], {encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30_000});
  assertPacketProcessorQueueContract(patchedPacketProcessor, "26.2");
  assertPacketProcessorQueueContract(patched121PacketProcessor, "1.21.11");
  assertPacketProcessorLifecycleContract(patchedPacketProcessor, "26.2");
  assertPacketProcessorLifecycleContract(patched121PacketProcessor, "1.21.11");

  const rawMob = execFileSync(javap, ["-classpath", rawClientJar, "-p", "-c",
    "net.minecraft.world.entity.Mob"], {
    encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000,
  });
  const patchedMob = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.world.entity.Mob"], {
    encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000,
  });
  const patched121Mob = execFileSync(javap, ["-classpath", generic121Jar, "-p", "-c",
    "net.minecraft.world.entity.Mob"], {
    encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000,
  });
  const rawMobAi = method(rawMob,
    "protected final void serverAiStep();", "protected void customServerAiStep");
  const patchedMobAi = method(patchedMob,
    "protected final void serverAiStep();", "protected void customServerAiStep");
  const patched121MobAi = method(patched121Mob,
    "protected final void serverAiStep();", "protected void customServerAiStep");
  const rawMobAiStep = method(rawMob,
    "public void aiStep();", "protected final void serverAiStep");
  const patchedMobAiStep = method(patchedMob,
    "public void aiStep();", "protected final void serverAiStep");
  const patched121MobAiStep = method(patched121Mob,
    "public void aiStep();", "protected final void serverAiStep");
  const rawMobTick = method(rawMob,
    "public void tick();", "protected void updateControlFlags");
  const patchedMobTick = method(patchedMob,
    "public void tick();", "protected void updateControlFlags");
  const patched121MobTick = method(patched121Mob,
    "public void tick();", "protected void updateControlFlags");
  const rawLivingEntity = execFileSync(javap, ["-classpath", rawClientJar, "-p", "-c",
    "net.minecraft.world.entity.LivingEntity"], {
    encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000,
  });
  const patchedLivingEntity = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.world.entity.LivingEntity"], {
    encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000,
  });
  const patched121LivingEntity = execFileSync(javap, ["-classpath", generic121Jar, "-p", "-c",
    "net.minecraft.world.entity.LivingEntity"], {
    encoding: "utf8", maxBuffer: 8 * 1024 * 1024, timeout: 30_000,
  });
  const rawLivingTick = method(rawLivingEntity,
    "public void tick();", "protected void updateFallFlying");
  const patchedLivingTick = method(patchedLivingEntity,
    "public void tick();", "protected void updateFallFlying");
  const patched121LivingTick = method(patched121LivingEntity,
    "public void tick();", "protected void updateFallFlying");
  assert.doesNotMatch(rawMobAi, /BrowserWorldgenScheduler\.mobAiPulse/,
    "raw Mob.serverAiStep unexpectedly contains browser scheduler hooks");
  assert.doesNotMatch(rawMobAiStep, /BrowserWorldgenScheduler\.mobAiPulse/,
    "raw Mob.aiStep unexpectedly contains browser scheduler hooks");
  assert.doesNotMatch(rawMobTick, /BrowserWorldgenScheduler\.mobAiPulse/,
    "raw Mob.tick unexpectedly contains browser scheduler hooks");
  assert.doesNotMatch(rawLivingTick, /BrowserWorldgenScheduler\.mobEntityPulse/,
    "raw LivingEntity.tick unexpectedly contains browser scheduler hooks");
  assert.equal(occurrences(patchedMobAi, "BrowserWorldgenScheduler.mobAiPulse"), 7,
    "26.2 Mob.serverAiStep must checkpoint all seven vanilla AI stage boundaries");
  assert.equal(occurrences(patched121MobAi, "BrowserWorldgenScheduler.mobAiPulse"), 7,
    "1.21.11 Mob.serverAiStep must checkpoint all seven vanilla AI stage boundaries");
  assert.equal(occurrences(patchedMobAiStep, "BrowserWorldgenScheduler.mobAiPulse"), 2,
    "26.2 Mob.aiStep must retain a cooperative boundary around the vanilla super call");
  assert.equal(occurrences(patched121MobAiStep, "BrowserWorldgenScheduler.mobAiPulse"), 2,
    "1.21.11 Mob.aiStep must retain a cooperative boundary around the vanilla super call");
  assert.equal(occurrences(patchedMobTick, "BrowserWorldgenScheduler.mobAiPulse"), 0,
    "26.2 Mob.tick must remain unmodified after moving the fallback to LivingEntity");
  assert.equal(occurrences(patched121MobTick, "BrowserWorldgenScheduler.mobAiPulse"), 0,
    "1.21.11 Mob.tick must remain unmodified after moving the fallback to LivingEntity");
  assert.equal(occurrences(patchedLivingTick, "BrowserWorldgenScheduler.mobEntityPulse"), 1,
    "26.2 LivingEntity.tick must retain the profile-generic Mob fallback boundary");
  assert.equal(occurrences(patched121LivingTick, "BrowserWorldgenScheduler.mobEntityPulse"), 1,
    "1.21.11 LivingEntity.tick must retain the profile-generic Mob fallback boundary");
  assert.equal(occurrences(patchedMobAi, "BrowserWorldgenScheduler.mobAiPulse"),
    occurrences(patched121MobAi, "BrowserWorldgenScheduler.mobAiPulse"),
    "Mob AI cooperation must remain profile-generic");

  const deepWorldgenBytecode = execFileSync(javap, [
    "-classpath", clientJar, "-p", "-c", ...synchronousDeepWorldgenClasses,
  ], {
    encoding: "utf8", maxBuffer: 32 * 1024 * 1024, timeout: 30_000,
  });
  assert.doesNotMatch(deepWorldgenBytecode, /BrowserWorldgenScheduler/,
    "deep worldgen bytecode must contain zero scheduler calls");

  const bytecode = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.server.level.ChunkGenerationTask",
    "net.minecraft.server.MinecraftServer",
    "net.minecraft.server.level.DistanceManager",
    "net.minecraft.server.level.LoadingChunkTracker",
    "net.minecraft.server.level.ServerChunkCache",
    "com.mojang.blaze3d.vertex.UberGpuBuffer",
    "net.minecraft.client.renderer.GameRenderer"], {
    encoding: "utf8", maxBuffer: 32 * 1024 * 1024, timeout: 30_000,
  });
  const generationWait = method(bytecode,
    "private java.util.concurrent.CompletableFuture<?> waitForScheduledLayer();",
    "private static net.minecraft.server.level.GenerationChunkHolder lambda$create$0");
  const generationWaitInstructions = bytecodeInstructions(generationWait);
  const generationWaitBackedges = generationWaitInstructions.flatMap((entry, index) => {
    const match = entry.instruction.match(/^goto(?:_w)?\s+(\d+)\s*$/);
    return match && Number(match[1]) < entry.offset ? [{entry, index}] : [];
  });
  assert.equal(generationWaitBackedges.length, 1,
    "ChunkGenerationTask.waitForScheduledLayer loop topology changed");
  for (const {entry, index} of generationWaitBackedges) {
    const previous = generationWaitInstructions[index - 1]?.instruction ?? "";
    const beforePrevious = generationWaitInstructions[index - 2]?.instruction ?? "";
    assert.match(previous, /BrowserWorldgenScheduler\.pulse/,
      `ChunkGenerationTask.waitForScheduledLayer backedge ${entry.offset} is missing its cooperative pulse`);
    assert.doesNotMatch(beforePrevious, /BrowserWorldgenScheduler\.pulse/,
      `ChunkGenerationTask.waitForScheduledLayer backedge ${entry.offset} received a duplicate cooperative pulse`);
  }
  assert.equal(occurrences(generationWait, "BrowserWorldgenScheduler.pulse"), 1,
    "waitForScheduledLayer must pulse once per completed-future drain backedge");
  assert.doesNotMatch(generationWait, /BrowserWorldgenScheduler\.checkpoint/,
    "waitForScheduledLayer must use bounded pulses rather than unconditional checkpoints");
  const runUntilWait = method(bytecode,
    "public java.util.concurrent.CompletableFuture<?> runUntilWait();",
    "private void scheduleNextLayer");
  const scheduleLayer = method(bytecode,
    "private void scheduleLayer(net.minecraft.world.level.chunk.status.ChunkStatus, boolean);",
    "private int getRadiusForLayer");
  const runUntilWaitInstructions = bytecodeInstructions(runUntilWait);
  const runUntilWaitBackedges = runUntilWaitInstructions.flatMap((entry, index) => {
    const match = entry.instruction.match(/^goto(?:_w)?\s+(\d+)\s*$/);
    return match && Number(match[1]) < entry.offset
      ? [{entry, index, target: Number(match[1])}] : [];
  });
  assert.equal(runUntilWaitBackedges.length, 1,
    "ChunkGenerationTask.runUntilWait must retain one loop backedge");
  assert.doesNotMatch(runUntilWait,
    /Field browserLayerYield|BrowserChunkGenerationYield|Platform\.schedule/,
    "runUntilWait still contains an artificial yield path");
  const activeFieldIndex = runUntilWaitInstructions.findIndex(entry =>
    entry.instruction.includes("Field browserLayerActive"));
  const firstScheduleNextIndex = runUntilWaitInstructions.findIndex(entry =>
    entry.instruction.includes("Method scheduleNextLayer:()V"));
  const firstLayerWaitIndex = runUntilWaitInstructions.findIndex(entry =>
    entry.instruction.includes("Method waitForScheduledLayer:"));
  const activeResumeGoto = runUntilWaitInstructions
    .map((entry, index) => ({entry, index}))
    .find(({entry, index}) => {
    if (index <= firstScheduleNextIndex || index >= firstLayerWaitIndex) return false;
    const match = entry.instruction.match(/^goto(?:_w)?\s+(\d+)\s*$/);
    return match && Number(match[1]) > entry.offset;
  });
  assert.ok(activeResumeGoto
      && activeFieldIndex >= 0
      && activeFieldIndex < firstScheduleNextIndex
      && firstScheduleNextIndex < activeResumeGoto.index
      && activeResumeGoto.index < firstLayerWaitIndex,
  "active cursor must schedule next layer and forward to the vanilla edge before wait");
  assert.equal(activeResumeGoto?.entry.instruction.match(/^goto(?:_w)?\s+(\d+)/)?.[1],
    String(runUntilWaitInstructions[runUntilWaitBackedges[0].index - 1].offset),
    "active branch must target the original backedge prologue");
  assert.match(runUntilWaitInstructions[runUntilWaitBackedges[0].index - 1].instruction,
    /BrowserWorldgenScheduler\.pulse/,
    "original runUntilWait backedge lost its scheduler pulse");
  assert.equal(occurrences(runUntilWait, "Method scheduleNextLayer:()V"), 2,
    "runUntilWait must retain active-resume and vanilla fresh-layer scheduling paths");
  assert.equal(occurrences(runUntilWait, "BrowserWorldgenScheduler.beginServerWorkTurn"), 0,
    "runUntilWait must not reset the shared tick budget per task invocation");
  assert.equal(occurrences(runUntilWait, "BrowserWorldgenScheduler.beginTaskWork"), 1,
    "runUntilWait must enter one active-work task scope");
  assert.match(runUntilWait,
    /String ChunkGenerationTask\.runUntilWait[\s\S]*BrowserWorldgenScheduler\.beginTaskWork:\(Ljava\/lang\/String;\)I/,
    "runUntilWait must label and store the integer task-scope token");
  assert.match(runUntilWait, /BrowserWorldgenScheduler\.endTaskWork:\(I\)V/,
    "runUntilWait must close task scopes with the integer token");
  assert.ok(occurrences(runUntilWait, "BrowserWorldgenScheduler.endTaskWork") >= 3,
    "runUntilWait must close its scope on returns and exceptions");
  assert.match(runUntilWait, /Exception table:[\s\S]*Throwable/,
    "runUntilWait task scope has no catch-all exception cleanup");
  assert.match(scheduleLayer, /Exception table:[\s\S]*Throwable/,
    "scheduleLayer must clean cursor state when holder work throws");
  const scheduleLayerExceptionCleanup = scheduleLayer.slice(scheduleLayer.lastIndexOf("astore"));
  assert.match(scheduleLayerExceptionCleanup, /Field browserLayerActive/,
    "scheduleLayer exception path must clear active cursor state");
  assert.doesNotMatch(scheduleLayerExceptionCleanup,
    /Field browserLayerYield|BrowserChunkGenerationYield|Platform\.schedule/,
    "scheduleLayer exception path still contains artificial continuation state");
  const scheduleLayerInstructions = bytecodeInstructions(scheduleLayer);
  const batchBackedges = scheduleLayerInstructions.flatMap((entry, index) => {
    const match = entry.instruction.match(/^if_icmplt\s+(\d+)\s*$/);
    return match && Number(match[1]) < entry.offset
      ? [{entry, index, target: Number(match[1])}] : [];
  });
  assert.equal(batchBackedges.length, 1,
    "scheduleLayer must retain one bounded holder-batch backedge");
  assert.match(scheduleLayerInstructions[batchBackedges[0].index - 1]?.instruction ?? "",
    /(?:bipush\s+16|ldc(?:_w)?\s+.*\/\/ int 16)/,
    "scheduleLayer batch backedge must enforce the 16-holder upper bound");
  assert.match(scheduleLayerInstructions[batchBackedges[0].index + 1]?.instruction ?? "",
    /^goto(?:_w)?\s+\d+$/,
    "scheduleLayer full batch must return through the original task continuation");
  assert.doesNotMatch(scheduleLayer,
    /Field browserLayerYield|BrowserChunkGenerationYield|Platform\.schedule|CompletableFuture/,
    "scheduleLayer must not synthesize a future continuation");
  const runServer = method(bytecode, "protected void runServer", "private void");
  const tickStart = runServer.indexOf("BrowserWorldgenScheduler.beginServerWorkTurn");
  const processTick = runServer.indexOf("processPacketsAndTick");
  const tickCheckpoint = runServer.indexOf("BrowserWorldgenScheduler.checkpoint");
  assert.equal(occurrences(runServer, "BrowserWorldgenScheduler.beginServerWorkTurn"), 1,
    "runServer must reset the scheduler clock exactly once per tick");
  assert.equal(occurrences(runServer, "BrowserWorldgenScheduler.checkpoint"), 1,
    "runServer must checkpoint exactly once per tick");
  assert.ok(tickStart >= 0 && processTick > tickStart && tickCheckpoint > processTick,
    "server tick must reset before processPacketsAndTick and checkpoint after it");
  const pollTask = method(bytecode, "protected boolean pollTask();", "private boolean pollTaskInternal");
  assert.equal(occurrences(pollTask, "BrowserWorldgenScheduler.beginTaskWork"), 1,
    "MinecraftServer.pollTask must enter one active-work task scope");
  assert.match(pollTask,
    /String MinecraftServer\.pollTask[\s\S]*BrowserWorldgenScheduler\.beginTaskWork:\(Ljava\/lang\/String;\)I/,
    "MinecraftServer.pollTask must label and store the integer task-scope token");
  assert.match(pollTask, /BrowserWorldgenScheduler\.endTaskWork:\(I\)V/,
    "MinecraftServer.pollTask must close task scopes with the integer token");
  const pollBegin = pollTask.indexOf(
    "BrowserWorldgenScheduler.beginTaskWork:(Ljava/lang/String;)I",
  );
  const pollPump = pollTask.indexOf("BrowserIntegratedServerMain.pumpUrgentPacketsIfPending");
  assert.ok(pollBegin >= 0 && pollPump > pollBegin,
    "MinecraftServer.pollTask must pump urgent packets after token initialization");
  assert.ok(occurrences(pollTask, "BrowserWorldgenScheduler.endTaskWork") >= 2,
    "MinecraftServer.pollTask must close its scope on returns and exceptions");
  const distance = method(bytecode, "public boolean runAllUpdates", "public void addPlayer");
  assert.equal(occurrences(distance, "int 2147483647"), 2,
    "DistanceManager no longer preserves vanilla full-propagation semantics");
  assert.equal(occurrences(distance, "distanceManagerUpdateBudget"), 0,
    "DistanceManager still returns between partial ticket-propagation batches");
  assert.equal(occurrences(distance, "pulseDistanceManager"), 3,
    "DistanceManager futures/ticket loops are not each cooperatively bounded");
  const distanceInstructions = bytecodeInstructions(distance);
  const backwardGotos = distanceInstructions.flatMap((entry, index) => {
    // ASM may widen a branch after the browser patch set grows the method;
    // javap prints that form as `goto_w` and keeps alignment spaces.
    const match = entry.instruction.match(/^goto(?:_w)?\s+(\d+)\s*$/);
    return match && Number(match[1]) < entry.offset ? [{entry, index}] : [];
  });
  assert.equal(backwardGotos.length, 3,
    "DistanceManager loop topology changed; update the one-pulse-per-backedge contract");
  for (const {entry, index} of backwardGotos) {
    const previous = distanceInstructions[index - 1]?.instruction ?? "";
    const beforePrevious = distanceInstructions[index - 2]?.instruction ?? "";
    assert.match(previous, /BrowserWorldgenScheduler\.pulseDistanceManager/,
      `DistanceManager backedge ${entry.offset} is missing its cooperative pulse`);
    assert.doesNotMatch(beforePrevious, /BrowserWorldgenScheduler\.pulseDistanceManager/,
      `DistanceManager backedge ${entry.offset} received a duplicate cooperative pulse`);
  }
  assert.equal(occurrences(distance, "InterfaceMethod java/util/Set.clear"), 1,
    "DistanceManager can clear newly queued future work after its snapshot");
  assert.equal(occurrences(distance, "InterfaceMethod it/unimi/dsi/fastutil/longs/LongSet.clear"), 1,
    "DistanceManager can clear newly queued ticket work after its snapshot");
  assert.ok(distance.indexOf("ArrayList.\"<init>\"") <
      distance.indexOf("InterfaceMethod java/util/Set.clear") &&
      distance.indexOf("InterfaceMethod java/util/Set.clear") <
        distance.indexOf("InterfaceMethod java/util/List.iterator"),
  "DistanceManager futures retain a live set until after the cooperative iterator starts");
  assert.ok(distance.indexOf("LongSet.toLongArray") <
      distance.indexOf("LongSet.clear"),
  "DistanceManager tickets are cleared before their snapshot is retained");
  assert.ok(distance.indexOf("LongSet.clear") < distance.indexOf("laload"),
    "DistanceManager can hold a live ticket iterator across a cooperative pulse");

  const loading = method(bytecode, "public int runDistanceUpdates", "static {}");
  assert.equal(occurrences(loading, "distanceManagerUpdateBudget"), 1,
    "LoadingChunkTracker does not bound its internal propagation batch");
  assert.equal(occurrences(loading, "recordDistanceManagerUpdates"), 1,
    "LoadingChunkTracker propagation telemetry is missing");
  assert.equal(occurrences(loading, "pulseDistanceManager"), 1,
    "LoadingChunkTracker cannot yield between internal propagation batches");
  assert.ok(loading.indexOf("Math.min") < loading.indexOf("runUpdates") &&
      loading.indexOf("runUpdates") < loading.indexOf("recordDistanceManagerUpdates") &&
      loading.indexOf("recordDistanceManagerUpdates") <
        loading.indexOf("pulseDistanceManager"),
  "LoadingChunkTracker lost batch-run-record-yield ordering");

  const broadcast = method(bytecode, "private void broadcastChangedChunks", "private void tickChunks");
  assert.ok(broadcast.includes("Set.toArray") && broadcast.includes("Set.clear"),
    "changed-chunk broadcast is not snapshot-backed");
  assert.equal(occurrences(broadcast, "Set.iterator"), 0,
    "changed-chunk broadcast can retain a live iterator across a pulse");
  assert.ok(broadcast.includes(
    "ChunkHolder.broadcastChanges:(Lnet/minecraft/world/level/chunk/LevelChunk;)V"),
  "changed-chunk broadcast uses an invalid LevelChunk method descriptor");
  assert.ok(broadcast.indexOf("Set.toArray") < broadcast.indexOf("Set.clear") &&
      broadcast.indexOf("Set.clear") < broadcast.indexOf("beginChunkBroadcast") &&
      broadcast.indexOf("beginChunkBroadcast") < broadcast.indexOf("pulseChunkBroadcast") &&
      broadcast.indexOf("pulseChunkBroadcast") < broadcast.indexOf("finishChunkBroadcast"),
  "changed-chunk broadcast no longer has snapshot-to-completion ordering");

  const upload = method(bytecode, "public boolean uploadStagedAllocations",
    "private static <T, U extends T> void runCallbackUnchecked");
  const cleanup = upload.slice(upload.indexOf("finishUploadBuffer"));
  assert.ok(cleanup.includes("beginUberNodeCleanup") && cleanup.includes("shouldCleanUberNode") &&
      cleanup.includes("finishUberNodeCleanup"),
  "UberGpuBuffer cleanup is not cursor-budgeted");
  assert.ok(cleanup.includes("List.remove:(I)Ljava/lang/Object;") &&
      !cleanup.includes("List.iterator"),
  "UberGpuBuffer cleanup still retains an unbounded iterator");
  assert.ok(cleanup.indexOf("beginUberNodeCleanup") < cleanup.indexOf("shouldCleanUberNode") &&
      cleanup.indexOf("shouldCleanUberNode") < cleanup.indexOf("List.remove:(I)") &&
      cleanup.indexOf("List.remove:(I)") < cleanup.indexOf("finishUberNodeCleanup"),
  "UberGpuBuffer cleanup cursor lifecycle order changed");

  const gameRenderer = method(bytecode,
    "public void extract(net.minecraft.client.DeltaTracker, boolean);",
    "private void extractCamera(net.minecraft.client.DeltaTracker, float, float);");
  const cameraExtract = gameRenderer.indexOf("Method extractCamera:");
  const targetingRefresh = gameRenderer.indexOf("BrowserTargeting.refreshFramePick");
  const levelExtract = gameRenderer.indexOf("LevelExtractor.extract");
  assert.ok(cameraExtract >= 0 && targetingRefresh > cameraExtract &&
      levelExtract > targetingRefresh,
  "frame targeting is not refreshed between camera and level extraction");
  const targetingInstructions = bytecodeInstructions(gameRenderer);
  const refreshInstructionIndex = targetingInstructions.findIndex(entry =>
    entry.instruction.includes("BrowserTargeting.refreshFramePick"));
  assert.ok(refreshInstructionIndex >= 0,
    "frame targeting refresh instruction is missing");
  assert.match(targetingInstructions[refreshInstructionIndex - 1]?.instruction ?? "",
    /^fload\s+6$/,
    "frame targeting does not use Camera.getCameraEntityPartialTicks");
  assert.doesNotMatch(targetingInstructions[refreshInstructionIndex - 1]?.instruction ?? "",
    /^fload\s+5$/,
    "frame targeting still uses the world partial tick and can drift");

  console.log("Minecraft 26.2 P1 patcher smoke passed", JSON.stringify({
    scheduledLayerPulses: occurrences(generationWait, "BrowserWorldgenScheduler.pulse"),
    distancePulses: occurrences(distance, "pulseDistanceManager"),
    graphicsPresetDistances: "6/4",
    oneTwentyOneFastDistances: "8/6",
    holderBatchLimit: HOLDERS_PER_TURN,
    layerBarrier: true,
    broadcastSnapshot: true,
    uberCleanupCursor: true,
    targetingPartialTickLocal: 6,
  }));
} finally {
  await rm(root, {recursive: true, force: true});
}
