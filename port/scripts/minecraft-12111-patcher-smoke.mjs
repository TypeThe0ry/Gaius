#!/usr/bin/env node

// The 1.21.11 profile deliberately uses the generic MinecraftClientPatcher;
// keep a disposable smoke so a profile switch cannot silently stop producing
// browser patch classes while 26.2-specific work evolves independently.
import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {access, copyFile, mkdir, mkdtemp, readdir, rm, stat} from "node:fs/promises";
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
    ? profileIdFromPath(process.env.GAIUS_VERSION_PROFILE_PATH) : "1.21.11");
if (requestedProfile !== "1.21.11") {
  throw new Error(`Minecraft 1.21.11 patcher smoke got profile ${requestedProfile}`);
}
const rawClientJar = join(repositoryRoot, "port/work/1.21.11/client-named.jar");
const toolsSource = join(repositoryRoot, "port/tools/src/main/java/dev/gaius/tools");
const asmRoot = join(homedir(), ".m2/repository/org/ow2/asm");
const asm = join(asmRoot, "asm/9.8/asm-9.8.jar");
const asmTree = join(asmRoot, "asm-tree/9.8/asm-tree-9.8.jar");

function jdkTool(name) {
  for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME]
    .filter(Boolean).map(nativePath)) {
    const candidate = join(home, "bin", name);
    if (existsSync(candidate) || existsSync(`${candidate}.exe`)) return candidate;
  }
  return name;
}

async function filesUnder(directory) {
  const files = [];
  async function visit(current) {
    for (const entry of await readdir(current, {withFileTypes: true})) {
      const path = join(current, entry.name);
      if (entry.isDirectory()) await visit(path);
      else if (entry.isFile()) files.push(path);
    }
  }
  await visit(directory);
  return files;
}

await Promise.all([access(rawClientJar), access(asm), access(asmTree)]);
const temporaryRoot = await mkdtemp(join(tmpdir(), "gaius-mc12111-patcher-"));
try {
  const classes = join(temporaryRoot, "classes");
  const patches = join(temporaryRoot, "patches");
  const clientJar = join(temporaryRoot, "client.jar");
  await Promise.all([
    mkdir(classes, {recursive: true}),
    mkdir(patches, {recursive: true}),
    copyFile(rawClientJar, clientJar),
  ]);

  const javac = jdkTool("javac");
  const java = jdkTool("java");
  const classpath = [asm, asmTree].join(delimiter);
  execFileSync(javac, [
    "--release", "21", "-proc:none", "-classpath", classpath, "-d", classes,
    join(toolsSource, "MinecraftClientPatcher.java"),
  ], {encoding: "utf8", timeout: 30_000});
  execFileSync(java, ["-classpath", [classes, classpath].join(delimiter),
    "dev.gaius.tools.MinecraftClientPatcher", clientJar, patches, "1.21.11"], {
    encoding: "utf8", timeout: 60_000,
  });

  const patchFiles = await filesUnder(patches);
  assert.ok(patchFiles.length > 0, "generic 1.21.11 patcher produced no patch classes");
  console.log("Minecraft 1.21.11 generic patcher smoke passed", JSON.stringify({
    patchClasses: patchFiles.length,
    inputBytes: (await stat(rawClientJar)).size,
  }));
} finally {
  await rm(temporaryRoot, {recursive: true, force: true});
}
