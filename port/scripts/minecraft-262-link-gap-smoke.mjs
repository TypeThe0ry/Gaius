#!/usr/bin/env node

import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {readFile, stat} from "node:fs/promises";
import {delimiter} from "node:path";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("../..", import.meta.url));
const patchedJar = fileURLToPath(
  new URL("../work/overlays/client-named-26.2-gaius.jar", import.meta.url),
);
const runtimeClasses = fileURLToPath(new URL("../target/maven/classes", import.meta.url));
const targetingSourcePath = fileURLToPath(
  new URL("../src/main/java/dev/gaius/browser/BrowserTargeting.java", import.meta.url),
);
const targetingClassPath = fileURLToPath(
  new URL("../target/maven/classes/dev/gaius/browser/BrowserTargeting.class", import.meta.url),
);
const clientPatcher = await readFile(
  new URL("../tools/src/main/java/dev/gaius/tools/MinecraftClientPatcher.java", import.meta.url),
  "utf8",
);
const versionPatcher = await readFile(
  new URL("../tools/src/main/java/dev/gaius/tools/Minecraft262BrowserPatcher.java", import.meta.url),
  "utf8",
);
const jdkCompat = await readFile(
  new URL("../src/main/java/dev/gaius/browser/BrowserJdkCompat.java", import.meta.url),
  "utf8",
);

for (const contract of [
  'field.name.equals("noRender") && field.desc.equals("Z")',
  "minecraftStateReport(hasNoRender)",
  "if (hasNoRender)",
  "new InsnNode(Opcodes.ICONST_0)",
]) {
  assert.ok(clientPatcher.includes(contract), "missing noRender field-shape contract: " + contract);
}
for (const contract of [
  "patchPreferredGraphicsApi(jar, root)",
  "patchVulkanBackend(jar, root)",
  "patchChunkGenerationCooperation(jar, root)",
  "patchRegionFileStorageCache(jar, root)",
  "BROWSER_REGION_FILE_CACHE_SIZE = 16",
  '"ChunkGenerationTask.runUntilWait"',
  '"ChunkGenerationTask.scheduleLayer"',
  '"ChunkGenerationTask.canLoadWithoutGeneration"',
  "patchGlBufferMappedViewRanges(jar, root)",
  "patchLiveFrameTargeting(jar, root)",
  "patchSectionRenderTaskRetryYields(jar, root)",
  '"CompileTask", "ResortTransparencyTask"',
  "replaceSpinWaitWithBoundedCancellation(method, owner)",
  "clearUploadRetryOnReturns(method)",
  "patchFileFixerUpperHardLinks(jar, root)",
  'find(node, "supportsHardLinks", "(Ljava/nio/file/Path;)Z")',
  "patchIdentifierResolveAgainst(jar, root)",
  '"(Ljava/nio/file/Path;Ljava/lang/String;[Ljava/lang/String;)"',
]) {
  assert.ok(versionPatcher.includes(contract), "missing 26.2 patch contract: " + contract);
}
assert.ok(jdkCompat.includes("public static Path resolve(Path root, String first, String... more)"),
  "BrowserJdkCompat does not expose the sequential Path.resolve adapter");
assert.ok(jdkCompat.includes("resolved = resolved.resolve(segment)"),
  "BrowserJdkCompat does not resolve every trailing segment");

function javap(className) {
  return execFileSync(
    "javap",
    ["-classpath", `${patchedJar}${delimiter}${runtimeClasses}`, "-p", "-c", className],
    {
    cwd: root,
    encoding: "utf8",
    },
  );
}

function methodBody(output, signature) {
  const start = output.indexOf(signature);
  assert.ok(start >= 0, `javap output is missing ${signature}`);
  const remaining = output.slice(start + signature.length);
  const nextMethod = remaining.search(
    /\n  (?:public|protected|private) [^\n]+\);\n/,
  );
  return nextMethod >= 0
    ? output.slice(start, start + signature.length + nextMethod)
    : output.slice(start);
}

const minecraft = javap("net.minecraft.client.Minecraft");
assert.ok(!minecraft.includes("Field noRender:Z"),
  "26.2 Minecraft telemetry still reads the removed noRender field");
const renderFrame = methodBody(minecraft, "public void renderFrame(boolean)");
const rendererUpdate = renderFrame.indexOf("GameRenderer.update");
const deferredPick = renderFrame.indexOf("BrowserTargeting.deferFramePick");
const rendererExtract = renderFrame.indexOf("GameRenderer.extract");
assert.ok(rendererUpdate >= 0 && deferredPick > rendererUpdate && rendererExtract > deferredPick,
  "26.2 renderFrame no longer defers its one pick between update and extract");
assert.ok(!renderFrame.includes("Method pick:(F)V"),
  "26.2 renderFrame still performs the stale pre-extract pick");

const gameRenderer = javap("net.minecraft.client.renderer.GameRenderer");
const gameRendererExtract = methodBody(
  gameRenderer,
  "public void extract(net.minecraft.client.DeltaTracker, boolean)",
);
const cameraExtract = gameRendererExtract.indexOf("Method extractCamera:");
const refreshedPick = gameRendererExtract.indexOf("BrowserTargeting.refreshFramePick");
const levelExtract = gameRendererExtract.indexOf("LevelExtractor.extract");
assert.ok(cameraExtract >= 0 && refreshedPick > cameraExtract && levelExtract > refreshedPick,
  "26.2 GameRenderer does not refresh targeting between camera and level extraction");
const cameraCallEnd = gameRendererExtract.indexOf("\n", cameraExtract);
const targetingBridge = gameRendererExtract.slice(cameraCallEnd, refreshedPick);
assert.match(targetingBridge, /fload\s+6/,
  "26.2 frame targeting does not use the render camera's entity partial tick");
assert.doesNotMatch(targetingBridge, /fload\s+5/,
  "26.2 frame targeting still uses world partial ticks and can drift from the crosshair");
assert.equal(gameRenderer.match(/BrowserTargeting\.refreshFramePick/g)?.length, 1,
  "26.2 GameRenderer must refresh targeting exactly once per rendered frame");
assert.ok(!gameRenderer.includes("LocalPlayer.raycastHitResult"),
  "26.2 GameRenderer embeds a second targeting raycast");

const [targetingSourceStat, targetingClassStat] = await Promise.all([
  stat(targetingSourcePath),
  stat(targetingClassPath),
]);
assert.ok(targetingClassStat.mtimeMs >= targetingSourceStat.mtimeMs,
  "BrowserTargeting runtime class is stale; rebuild the Gaius runtime sources");
const browserTargeting = javap("dev.gaius.browser.BrowserTargeting");
const refreshFramePick = methodBody(
  browserTargeting,
  "public static void refreshFramePick(net.minecraft.client.Minecraft, net.minecraft.client.Camera, float)",
);
const stabilizeBlockHit = methodBody(
  browserTargeting,
  "public static net.minecraft.world.phys.HitResult stabilizeBlockHit(net.minecraft.world.phys.HitResult, net.minecraft.client.Minecraft, net.minecraft.client.Camera, float)",
);
const pickFromRenderCamera = methodBody(
  browserTargeting,
  "private static net.minecraft.world.phys.HitResult pickFromRenderCamera(net.minecraft.client.Minecraft, net.minecraft.client.Camera, net.minecraft.world.phys.Vec3)",
);
assert.match(refreshFramePick, /Method (?:dev\/gaius\/browser\/BrowserTargeting\.)?stabilizeBlockHit:/,
  "frame targeting does not use the live camera raycast helper");
assert.ok(refreshFramePick.includes("Field net/minecraft/client/Minecraft.hitResult"),
  "frame targeting does not update the shared hit result");
assert.ok(refreshFramePick.includes("Field net/minecraft/client/Minecraft.crosshairPickEntity"),
  "frame targeting does not keep entity targeting coherent");
assert.equal(stabilizeBlockHit.match(/pickFromRenderCamera/g)?.length, 1,
  "frame targeting must invoke its render-camera picker exactly once");
assert.match(stabilizeBlockHit, /Camera\.position/,
  "frame targeting does not use the live render-camera origin");
assert.equal(pickFromRenderCamera.match(/ClientLevel\.clip/g)?.length, 1,
  "frame targeting must perform exactly one render-camera block raycast");
assert.equal(pickFromRenderCamera.match(/ProjectileUtil\.getEntityHitResult/g)?.length, 1,
  "frame targeting must perform exactly one coherent entity raycast");
assert.match(pickFromRenderCamera, /Camera\.forwardVector/,
  "frame targeting does not use the live render-camera direction");
assert.ok(!browserTargeting.includes("LocalPlayer.raycastHitResult"),
  "frame targeting still reuses the player-eye ray and can drift from the crosshair");

const glBufferDirect = javap("com.mojang.blaze3d.opengl.GlBuffer$Direct");
const mapBuffer = methodBody(
  glBufferDirect,
  "public com.mojang.blaze3d.buffers.GpuBufferSlice$MappedView map(long, long, boolean, boolean)",
);
assert.ok(
  mapBuffer.includes(
    'GlBuffer$Direct$1."<init>":(Lcom/mojang/blaze3d/opengl/GlBuffer$Direct;JJ)V',
  ),
  "mapped views do not capture their exact write range",
);
assert.ok(!mapBuffer.includes(
  'GlBuffer$Direct$1."<init>":(Lcom/mojang/blaze3d/opengl/GlBuffer$Direct;)V',
), "mapped views still use the range-blind close callback");

const mappedViewClose = javap("com.mojang.blaze3d.opengl.GlBuffer$Direct$1");
const mappedViewRun = methodBody(mappedViewClose, "public void run()");
for (const contract of [
  "Field mappedOffset:J",
  "Field mappedLength:J",
  "DirectStateAccess.flushMappedBufferRange:(IJJI)V",
  "GlBuffer$Direct.unmap:()V",
]) {
  assert.ok(mappedViewRun.includes(contract),
    `mapped-view close callback is missing ${contract}`);
}
assert.ok(!mappedViewRun.includes("GlBuffer$Direct.slice"),
  "mapped-view close callback rebuilds or flushes a whole-buffer slice");

const chunkGenerationTask = javap("net.minecraft.server.level.ChunkGenerationTask");
const runUntilWait = methodBody(
  chunkGenerationTask,
  "public java.util.concurrent.CompletableFuture<?> runUntilWait()",
);
const scheduleLayer = methodBody(
  chunkGenerationTask,
  "private void scheduleLayer(net.minecraft.world.level.chunk.status.ChunkStatus, boolean)",
);
const canLoadWithoutGeneration = methodBody(
  chunkGenerationTask,
  "private boolean canLoadWithoutGeneration()",
);
assert.equal(runUntilWait.match(/BrowserWorldgenScheduler\.pulse/g)?.length, 1,
  "runUntilWait must pulse exactly once per immediately-completed layer");
assert.ok(runUntilWait.indexOf("scheduleNextLayer")
    < runUntilWait.indexOf("BrowserWorldgenScheduler.pulse"),
"runUntilWait pulses before making layer progress");
assert.equal(scheduleLayer.match(/BrowserWorldgenScheduler\.pulse/g)?.length, 2,
  "scheduleLayer must pulse on its two chunk-scan backedges");
assert.equal(canLoadWithoutGeneration.match(/BrowserWorldgenScheduler\.pulse/g)?.length, 2,
  "canLoadWithoutGeneration must pulse on its two dependency-scan backedges");
for (const body of [runUntilWait, scheduleLayer, canLoadWithoutGeneration]) {
  assert.ok(!body.includes("BrowserWorldgenScheduler.checkpoint"),
    "chunk generation uses an unconditional checkpoint instead of bounded pulses");
  assert.ok(!body.includes("java/util/concurrent/CompletableFuture.then"),
    "chunk generation moved server-state progress into a future continuation");
  assert.ok(!body.includes("java/util/concurrent/Executor"),
    "chunk generation moved server-state progress to another executor");
}

const regionFileStorage = javap(
  "net.minecraft.world.level.chunk.storage.RegionFileStorage",
);
const getRegionFile = methodBody(
  regionFileStorage,
  "private net.minecraft.world.level.chunk.storage.RegionFile getRegionFile(net.minecraft.world.level.ChunkPos) throws java.io.IOException",
);
assert.ok(getRegionFile.includes("bipush        16"),
  "RegionFileStorage does not enforce the 16-file browser cache bound");
assert.ok(!getRegionFile.includes("sipush        256"),
  "RegionFileStorage still retains the vanilla 256-file cache bound");
const eviction = getRegionFile.indexOf("Long2ObjectLinkedOpenHashMap.removeLast");
const closeEvicted = getRegionFile.indexOf("RegionFile.close", eviction);
assert.ok(eviction >= 0 && closeEvicted > eviction,
  "RegionFileStorage no longer closes the least-recently-used region on eviction");

const preferredGraphicsApi = javap("net.minecraft.client.PreferredGraphicsApi");
const backendsToTry = preferredGraphicsApi.slice(
  preferredGraphicsApi.indexOf("getBackendsToTry()"),
  preferredGraphicsApi.indexOf("private static net.minecraft.client.PreferredGraphicsApi[] $values"),
);
assert.ok(backendsToTry.includes("com/mojang/blaze3d/opengl/GlBackend"),
  "PreferredGraphicsApi does not expose the browser OpenGL backend");
assert.ok(!backendsToTry.includes("com/mojang/blaze3d/vulkan/VulkanBackend"),
  "PreferredGraphicsApi still exposes Vulkan to the browser backend loop");

const vulkanBackend = javap("com.mojang.blaze3d.vulkan.VulkanBackend");
const browserVulkanEntrypoints = [
  vulkanBackend.slice(
    vulkanBackend.indexOf("checkBackendAvailable()"),
    vulkanBackend.indexOf("handleWindowCreationErrors("),
  ),
  vulkanBackend.slice(
    vulkanBackend.indexOf("createDevice(long,"),
    vulkanBackend.indexOf("private static long createVma("),
  ),
  vulkanBackend.slice(vulkanBackend.lastIndexOf("static {};")),
].join("\n");
for (const forbidden of [
  "org/lwjgl/util/vma",
  "org/lwjgl/vulkan",
  "VulkanInstance",
  "VulkanDevice",
]) {
  assert.ok(!browserVulkanEntrypoints.includes(forbidden),
    "browser VulkanBackend still reaches desktop backend code: " + forbidden);
}
assert.ok(browserVulkanEntrypoints.includes("Vulkan is unavailable in the browser runtime"),
  "browser VulkanBackend does not reject unsupported execution explicitly");

for (const className of [
  "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask",
  "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$ResortTransparencyTask",
]) {
  assert.ok(!javap(className).includes("java/lang/Thread.onSpinWait:()V"),
    className + " still calls Thread.onSpinWait");
}

const fileFixer = javap("net.minecraft.util.filefix.FileFixerUpper");
const supportsHardLinks = fileFixer.slice(
  fileFixer.indexOf("supportsHardLinks(java.nio.file.Path)"),
);
assert.ok(supportsHardLinks.includes("iconst_0"),
  "FileFixerUpper.supportsHardLinks does not return false");
assert.ok(!supportsHardLinks.includes("java/nio/file/Files.createLink"),
  "FileFixerUpper.supportsHardLinks still reaches Files.createLink");

const identifier = javap("net.minecraft.resources.Identifier");
const resolveAgainst = identifier.slice(
  identifier.indexOf("resolveAgainst(java.nio.file.Path)"),
  identifier.indexOf("public java.lang.String toDebugFileName"),
);
assert.ok(resolveAgainst.includes("dev/gaius/browser/BrowserJdkCompat.resolve"),
  "Identifier.resolveAgainst does not call BrowserJdkCompat.resolve");
assert.ok(!resolveAgainst.includes("Path.resolve:(Ljava/lang/String;[Ljava/lang/String;)"),
  "Identifier.resolveAgainst still calls unsupported varargs Path.resolve");

console.log("Minecraft 26.2 link-gap smoke passed");
