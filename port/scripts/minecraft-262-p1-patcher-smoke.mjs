#!/usr/bin/env node

import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {access, copyFile, mkdir, mkdtemp, rm} from "node:fs/promises";
import {homedir, tmpdir} from "node:os";
import {join} from "node:path";
import {fileURLToPath} from "node:url";

const repositoryRoot = fileURLToPath(new URL("../..", import.meta.url));
const rawClientJar = join(repositoryRoot, "port/work/26.2/client-named.jar");
const toolsSource = join(repositoryRoot, "port/tools/src/main/java/dev/gaius/tools");
const asmRoot = join(homedir(), ".m2/repository/org/ow2/asm");
const asm = join(asmRoot, "asm/9.8/asm-9.8.jar");
const asmTree = join(asmRoot, "asm-tree/9.8/asm-tree-9.8.jar");

function jdkTool(name) {
  for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME].filter(Boolean)) {
    const candidate = join(home, "bin", name);
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

function bytecodeInstructions(methodBytecode) {
  return methodBytecode.split("\n").flatMap(line => {
    const match = line.match(/^\s*(\d+):\s+(.*)$/);
    return match ? [{offset: Number(match[1]), instruction: match[2]}] : [];
  });
}

await Promise.all([access(rawClientJar), access(asm), access(asmTree)]);
const root = await mkdtemp(join(tmpdir(), "gaius-mc262-p1-"));
try {
  const classes = join(root, "classes");
  const clientPatches = join(root, "client-patches");
  const browserPatches = join(root, "browser-patches");
  const clientJar = join(root, "client.jar");
  await Promise.all([
    mkdir(classes, {recursive: true}),
    mkdir(clientPatches, {recursive: true}),
    mkdir(browserPatches, {recursive: true}),
    copyFile(rawClientJar, clientJar),
  ]);

  const javac = jdkTool("javac");
  const java = jdkTool("java");
  const jar = jdkTool("jar");
  const javap = jdkTool("javap");
  const classpath = `${asm}:${asmTree}`;
  execFileSync(javac, [
    "--release", "21", "-proc:none", "-classpath", classpath, "-d", classes,
    join(toolsSource, "MinecraftClientPatcher.java"),
    join(toolsSource, "Minecraft262BrowserPatcher.java"),
  ], {encoding: "utf8", timeout: 30_000});
  execFileSync(java, ["-classpath", `${classes}:${classpath}`,
    "dev.gaius.tools.MinecraftClientPatcher", clientJar, clientPatches], {
    encoding: "utf8", timeout: 30_000,
  });
  execFileSync(jar, ["--update", "--file", clientJar, "-C", clientPatches, "."], {
    encoding: "utf8", timeout: 30_000,
  });
  execFileSync(java, ["-classpath", `${classes}:${classpath}`,
    "dev.gaius.tools.Minecraft262BrowserPatcher", clientJar, browserPatches], {
    encoding: "utf8", timeout: 30_000,
  });
  execFileSync(jar, ["--update", "--file", clientJar, "-C", browserPatches, "."], {
    encoding: "utf8", timeout: 30_000,
  });

  const bytecode = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.server.level.DistanceManager",
    "net.minecraft.server.level.LoadingChunkTracker",
    "net.minecraft.server.level.ServerChunkCache",
    "com.mojang.blaze3d.vertex.UberGpuBuffer",
    "net.minecraft.client.renderer.GameRenderer"], {
    encoding: "utf8", maxBuffer: 32 * 1024 * 1024, timeout: 30_000,
  });
  const distance = method(bytecode, "public boolean runAllUpdates", "public void addPlayer");
  assert.equal(occurrences(distance, "int 2147483647"), 2,
    "DistanceManager no longer preserves vanilla full-propagation semantics");
  assert.equal(occurrences(distance, "distanceManagerUpdateBudget"), 0,
    "DistanceManager still returns between partial ticket-propagation batches");
  assert.equal(occurrences(distance, "pulseDistanceManager"), 3,
    "DistanceManager futures/ticket loops are not each cooperatively bounded");
  const distanceInstructions = bytecodeInstructions(distance);
  const backwardGotos = distanceInstructions.flatMap((entry, index) => {
    const match = entry.instruction.match(/^goto\s+(\d+)$/);
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
    distancePulses: occurrences(distance, "pulseDistanceManager"),
    broadcastSnapshot: true,
    uberCleanupCursor: true,
    targetingPartialTickLocal: 6,
  }));
} finally {
  await rm(root, {recursive: true, force: true});
}
