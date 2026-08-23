#!/usr/bin/env node

// 1.21.11 is the checkpoint-only profile.  This smoke exercises the dedicated
// task-layer holder cursor directly on the named client jar; it never invokes
// the 26.2 patcher and it rejects scheduler pulse/checkpoint bytecode.
import assert from "node:assert/strict";
import {execFileSync} from "node:child_process";
import {access, copyFile, mkdir, mkdtemp, readFile, readdir, rm, stat, writeFile} from "node:fs/promises";
import {existsSync} from "node:fs";
import {homedir, tmpdir} from "node:os";
import {basename, delimiter, join, relative} from "node:path";
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
const asmAnalysis = join(asmRoot, "asm-analysis/9.8/asm-analysis-9.8.jar");

function jdkTool(name) {
  const configuredHomes = [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME]
    .filter(Boolean).map(nativePath);
  if (process.platform === "win32") {
    configuredHomes.push(
      "C:\\Program Files\\Java\\jdk-26.0.1",
      "C:\\Program Files\\Java\\jdk-24",
      "C:\\Program Files\\Java\\jdk-21",
    );
  }
  for (const home of [...new Set(configuredHomes)]) {
    const candidate = join(home, "bin", name);
    for (const versionArgs of [["--version"], ["-version"]]) {
      try {
        const version = execFileSync(candidate, versionArgs, {
          encoding: "utf8", stdio: ["ignore", "pipe", "pipe"],
        });
        const major = Number(version.match(/(?:^|\s|version\s+)(\d+)(?:\.|\s|$)/i)?.[1]);
        if (Number.isInteger(major) && major >= 21) return candidate;
      } catch {
        // Try the alternate version flag, then the next configured JDK.
      }
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
  return methodBytecode.split(/\r?\n/).flatMap(line => {
    const match = line.match(/^\s*(\d+):\s+(.*)$/);
    return match ? [{offset: Number(match[1]), instruction: match[2]}] : [];
  });
}

function cursorModel(radius, stop = null) {
  const turns = [];
  let active = true;
  let yieldPending = false;
  for (let x = -radius; x <= radius && active; x++) {
    for (let z = -radius; z <= radius && active; z++) {
      const index = turns.length;
      const outcome = stop?.index === index ? stop.kind : "success";
      turns.push({x, z, outcome});
      if (outcome !== "success") {
        active = false;
        yieldPending = false;
        continue;
      }
      // One zero-delay Platform task completes the next turn boundary.
      yieldPending = true;
      yieldPending = false;
      if (x === radius && z === radius) active = false;
    }
  }
  return {turns, active, yieldPending};
}

for (const radius of [0, 1, 2]) {
  const run = cursorModel(radius);
  const expected = (radius * 2 + 1) ** 2;
  assert.equal(run.turns.length, expected,
    `cursor radius ${radius} must visit every holder exactly once`);
  assert.equal(run.active, false,
    `cursor radius ${radius} must close after its final holder`);
  assert.equal(new Set(run.turns.map(({x, z}) => `${x},${z}`)).size, expected,
    `cursor radius ${radius} must not duplicate holders`);
  assert.equal(run.turns.at(-1)?.outcome, "success",
    `cursor radius ${radius} must process its final holder before closing`);
}
const cancelledCursor = cursorModel(2, {index: 3, kind: "cancel"});
assert.equal(cancelledCursor.turns.length, 4, "cancellation must stop at its holder");
assert.equal(cancelledCursor.turns.at(-1)?.outcome, "cancel");
assert.equal(cancelledCursor.active, false);
assert.equal(cancelledCursor.yieldPending, false);
const failedCursor = cursorModel(2, {index: 4, kind: "failure"});
assert.equal(failedCursor.turns.length, 5, "failure must stop at its holder");
assert.equal(failedCursor.turns.at(-1)?.outcome, "failure");
assert.equal(failedCursor.active, false);
assert.equal(failedCursor.yieldPending, false);

await Promise.all([access(rawClientJar), access(asm), access(asmTree), access(asmAnalysis)]);
const patcherSource = await readFile(
  join(toolsSource, "Minecraft12111BrowserPatcher.java"), "utf8",
);
assert.match(patcherSource, /checkpoint-only/,
  "1.21.11 patcher must declare checkpoint-only ownership");
assert.doesNotMatch(patcherSource, /BrowserWorldgenScheduler/,
  "1.21.11 task patcher must not reference BrowserWorldgenScheduler");
assert.doesNotMatch(patcherSource, /BrowserWorldgenScheduler|"pulse"|"checkpoint"|\.pulse\(|\.checkpoint\(/,
  "1.21.11 task patcher must not add pulse/checkpoint calls");
assert.match(patcherSource, /Opcodes\.GETFIELD, CHUNK_POS, "x", "I"/,
  "1.21.11 cursor must read public ChunkPos.x");
assert.match(patcherSource, /Opcodes\.GETFIELD, CHUNK_POS, "z", "I"/,
  "1.21.11 cursor must read public ChunkPos.z");
assert.match(patcherSource, /Platform/,
  "1.21.11 cursor must schedule a browser-turn continuation");

const root = await mkdtemp(join(tmpdir(), "gaius-mc12111-cursor-"));
try {
  const classes = join(root, "classes");
  const patches = join(root, "patches");
  const clientJar = join(root, "client.jar");
  await Promise.all([
    mkdir(classes, {recursive: true}),
    mkdir(patches, {recursive: true}),
    copyFile(rawClientJar, clientJar),
  ]);
  const javac = jdkTool("javac");
  const java = jdkTool("java");
  const jar = jdkTool("jar");
  const javap = jdkTool("javap");
  const asmClasspath = [asm, asmTree].join(delimiter);
  execFileSync(javac, [
    "--release", "21", "-proc:none", "-classpath", asmClasspath, "-d", classes,
    join(toolsSource, "Minecraft12111BrowserPatcher.java"),
  ], {encoding: "utf8", timeout: 30_000});
  execFileSync(java, ["-Xverify:all", "-classpath", [classes, asmClasspath].join(delimiter),
    "dev.gaius.tools.Minecraft12111BrowserPatcher", clientJar, patches], {
    encoding: "utf8", timeout: 60_000,
  });
  const patchFiles = await filesUnder(patches);
  const patchNames = patchFiles.map(file => relative(patches, file).replaceAll("\\", "/"));
  assert.ok(patchNames.includes("net/minecraft/server/level/ChunkGenerationTask.class"),
    "dedicated patcher did not emit ChunkGenerationTask.class");
  assert.ok(patchNames.includes("dev/gaius/browser/BrowserChunkGenerationYield.class"),
    "dedicated patcher did not emit BrowserChunkGenerationYield.class");
  await execFileSync(jar, ["--update", "--file", clientJar, "-C", patches, "."], {
    encoding: "utf8", timeout: 30_000,
  });

  const verifierSource = join(root, "GaiusBytecodeVerifier.java");
  await writeFile(verifierSource, `
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;
public final class GaiusBytecodeVerifier {
    private static void verify(ZipFile jar, String name) throws Exception {
        var entry = jar.getEntry(name);
        if (entry == null) throw new IllegalStateException("missing verifier entry: " + name);
        ClassNode node = new ClassNode();
        try (var input = jar.getInputStream(entry)) {
            new ClassReader(input.readAllBytes()).accept(node, 0);
        }
        for (MethodNode method : node.methods) {
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
        }
        System.out.println("BASIC_VERIFIER_OK " + name);
    }
    public static void main(String[] args) throws Exception {
        try (ZipFile jar = new ZipFile(args[0])) {
            verify(jar, "net/minecraft/server/level/ChunkGenerationTask.class");
            verify(jar, "dev/gaius/browser/BrowserChunkGenerationYield.class");
        }
    }
}
`, "utf8");
  const verifierClasspath = [asm, asmTree, asmAnalysis].join(delimiter);
  execFileSync(javac, [
    "--release", "21", "-proc:none", "-classpath", verifierClasspath,
    "-d", classes, verifierSource,
  ], {encoding: "utf8", timeout: 30_000});
  const verifierOutput = execFileSync(java, [
    "-Xverify:all", "-classpath", [classes, verifierClasspath].join(delimiter),
    "GaiusBytecodeVerifier", clientJar,
  ], {encoding: "utf8", timeout: 30_000});
  assert.match(verifierOutput, /BASIC_VERIFIER_OK .*ChunkGenerationTask\.class/);
  assert.match(verifierOutput, /BASIC_VERIFIER_OK .*BrowserChunkGenerationYield\.class/);
  process.stdout.write(verifierOutput);

  const bytecode = execFileSync(javap, ["-classpath", clientJar, "-p", "-c",
    "net.minecraft.server.level.ChunkGenerationTask"], {
    encoding: "utf8", maxBuffer: 16 * 1024 * 1024, timeout: 30_000,
  });
  assert.doesNotMatch(bytecode, /BrowserWorldgenScheduler/,
    "1.21.11 ChunkGenerationTask must remain scheduler-call free");
  assert.match(bytecode, /Platform\.schedule/,
    "1.21.11 ChunkGenerationTask must schedule holder continuations");
  assert.match(bytecode, /Field net\/minecraft\/world\/level\/ChunkPos\.x:I/,
    "1.21.11 patched bytecode must use ChunkPos.x field");
  assert.match(bytecode, /Field net\/minecraft\/world\/level\/ChunkPos\.z:I/,
    "1.21.11 patched bytecode must use ChunkPos.z field");
  const runUntilWait = method(bytecode,
    "public java.util.concurrent.CompletableFuture<?> runUntilWait();",
    "private void scheduleNextLayer");
  const runInstructions = bytecodeInstructions(runUntilWait);
  const backedges = runInstructions.flatMap((entry, index) => {
    const match = entry.instruction.match(/^goto(?:_w)?\s+(\d+)\s*$/);
    return match && Number(match[1]) < entry.offset
      ? [{entry, index, target: Number(match[1])}] : [];
  });
  assert.equal(backedges.length, 1, "runUntilWait must retain one loop backedge");
  const yieldIndex = runInstructions.findIndex(entry =>
    entry.instruction.includes("Field browserLayerYield"));
  assert.ok(yieldIndex > 0, "runUntilWait yield gate is missing");
  assert.equal(backedges[0].target, runInstructions[yieldIndex - 1].offset,
    "runUntilWait backedge must re-enter the yield gate");
  const scheduleLayer = method(bytecode,
    "private void scheduleLayer(net.minecraft.world.level.chunk.status.ChunkStatus, boolean);",
    "private int getRadiusForLayer");
  assert.match(scheduleLayer, /Exception table:[\s\S]*Throwable/,
    "scheduleLayer must clean cursor state on holder exceptions");
  const cleanup = scheduleLayer.slice(scheduleLayer.lastIndexOf("astore"));
  assert.match(cleanup, /Field browserLayerActive/,
    "scheduleLayer exception path must clear active cursor state");
  assert.match(cleanup, /Field browserLayerYield/,
    "scheduleLayer exception path must clear pending continuation");
  assert.equal(occurrences(scheduleLayer, "BrowserWorldgenScheduler"), 0);
  console.log("Minecraft 1.21.11 checkpoint-only holder cursor smoke passed", JSON.stringify({
    patchClasses: patchFiles.length,
    inputBytes: (await stat(rawClientJar)).size,
    cursorRadii: [0, 1, 2],
    cancellation: true,
    failure: true,
    finalHolder: true,
  }));
} finally {
  await rm(root, {recursive: true, force: true});
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
