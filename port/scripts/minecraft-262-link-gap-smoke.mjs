#!/usr/bin/env node

import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {readFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("../..", import.meta.url));
const patchedJar = fileURLToPath(
  new URL("../work/overlays/client-named-26.2-gaius.jar", import.meta.url),
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
  "patchVanillaTargetingObservation(jar, root)",
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
  return execFileSync("javap", ["-classpath", patchedJar, "-p", "-c", className], {
    cwd: root,
    encoding: "utf8",
  });
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
const vanillaPick = renderFrame.indexOf("Method pick:(F)V");
const rendererExtract = renderFrame.indexOf("GameRenderer.extract");
assert.ok(rendererUpdate >= 0 && vanillaPick > rendererUpdate && rendererExtract > vanillaPick,
  "26.2 renderFrame no longer updates, picks, and extracts in the expected order");

const gameRenderer = javap("net.minecraft.client.renderer.GameRenderer");
assert.ok(gameRenderer.includes("BrowserTargeting.observeVanillaPick"),
  "26.2 GameRenderer does not observe the vanilla targeting result");
assert.ok(!gameRenderer.includes("BrowserTargeting.stabilizeBlockHit"),
  "26.2 GameRenderer still performs a duplicate browser targeting raycast");
assert.ok(!gameRenderer.includes("LocalPlayer.raycastHitResult"),
  "26.2 GameRenderer targeting telemetry performs an extra raycast");

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
