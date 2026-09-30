#!/usr/bin/env node
// Worldgen seed-parity harness (migration plan P6, acceptance "seed parity").
//
// Generates the same chunks of a fresh default world ("minecraft:normal", structures on,
// vanilla data pack) on the JVM with vanilla Minecraft and hashes their block states and
// biomes, so a browser run or a run of the patched worldgen classes can be compared with it.
// The JVM side is port/scripts/worldgen-seed-parity/WorldgenSeedParity.java: a headless
// server built like vanilla's GameTestServer (no dedicated-server EULA prompt is involved).
//
// Per seed it runs two modes (see WorldgenSeedParity.java):
//   terrain  chunks up to ChunkStatus.TERRAIN (noise, surface, aquifers, carvers) and biomes,
//            hashed before any feature ran: independent of generation order;
//   full     vanilla setInitialSpawn, then FULL chunks, hashed before the first level tick.
// Chunks: a side x side square centred on ChunkGenerator.getOrigin (default 8 x 8 = 64).
// The worldgen executor runs with one thread (-Dmax.bg.threads=1) so full hashes repeat.
//
//   node port/scripts/worldgen-seed-parity.mjs [--profile 26.3] [--seeds 12345,20260930,...]
//        [--side 8] [--modes terrain,full] [--out-dir <dir>]
//        [--vanilla client | --server-jar <server.jar> | --download-server]
//        [--patched <client-named-26.3-gaius.jar>]
//   node port/scripts/worldgen-seed-parity.mjs --compare <reference.json> <candidate.json>
//
// Vanilla input: by default the profile's client-original.jar, SHA-1 checked against
// port/work/<id>/version.json (downloads.client) and run with the libraries of
// port/work/<id>/classpath.txt; the client jar holds the same worldgen and server classes as
// the dedicated server. --server-jar uses a local vanilla server.jar instead and
// --download-server fetches downloads.server.url; both are SHA-1 checked against
// downloads.server and the bundled jars against the bundler's SHA-256 lists.
//
// --patched: after the vanilla runs, runs the P6-patched worldgen classes taken from the given
// patched client jar (P6_WORLDGEN_CLASSES) in front of an unsigned copy of the vanilla jar,
// with JVM transcriptions of the browser helpers (port/scripts/worldgen-seed-parity/shims),
// and requires identical hashes. This proves that the browser bytecode patches keep vanilla
// worldgen semantics; TeaVM arithmetic (no Math.fround for float) is only covered by a browser
// run compared with --compare.
//
// Exit status: 0 parity (or results written), 1 mismatch or failed run, 2 usage/environment.

import {createHash} from "node:crypto";
import {spawnSync} from "node:child_process";
import {existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync,
  statSync, writeFileSync} from "node:fs";
import {tmpdir} from "node:os";
import {delimiter, dirname, join, relative, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const harnessDir = join(root, "port/scripts/worldgen-seed-parity");
const DEFAULT_SEEDS = ["12345", "20260930", "-7327290349473744307"];

/**
 * Classes whose browser patches can change generated blocks or biomes, or that pulse inside
 * generation: every MinecraftClientPatcher/Minecraft262BrowserPatcher/WorldgenPatches263
 * worldgen target that the P6 work package owns. Their Gaius helpers are the shims.
 */
export const P6_WORLDGEN_CLASSES = [
  "net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator",
  "net/minecraft/world/level/levelgen/material/MaterialSystem",
  "net/minecraft/world/level/levelgen/material/MaterialRuleContext",
  "net/minecraft/world/level/levelgen/material/MaterialRuleContext$LazyXZCondition",
  "net/minecraft/world/level/levelgen/material/MaterialRuleContext$LazyYCondition",
  "net/minecraft/world/level/chunk/CarvingMask",
  "net/minecraft/world/level/levelgen/Aquifer$NoiseBasedAquifer",
  "net/minecraft/world/level/biome/BiomeManager",
  "net/minecraft/world/level/chunk/ChunkGenerator",
  "net/minecraft/world/level/levelgen/carver/WorldCarver",
  "net/minecraft/world/level/chunk/LevelChunkSection",
  "net/minecraft/world/level/NaturalSpawner",
  "net/minecraft/world/level/biome/Climate$RTree$SubTree",
  "net/minecraft/world/level/biome/Climate$RTree$Node",
  "net/minecraft/world/level/lighting/LightEngine",
  "net/minecraft/world/level/levelgen/structure/pools/JigsawPlacement$Placer",
  "net/minecraft/world/level/chunk/ChunkGeneratorStructureState",
  "net/minecraft/util/CubicSpline$Constant",
  "net/minecraft/util/CubicSpline$Multipoint",
  "net/minecraft/world/level/chunk/ProtoChunk",
  "net/minecraft/world/level/levelgen/Heightmap",
  "net/minecraft/world/level/chunk/storage/RegionFileStorage",
];

/** Runtime helpers without JavaScript bodies, compiled from port/src for the patched run. */
const REAL_HELPERS = ["dev/gaius/browser/BrowserProtoChunk.java"];

function usage(message) {
  console.error(`worldgen-seed-parity: ${message}`);
  process.exit(2);
}

function parseArgs(argv) {
  const options = {profile: "26.3", seeds: DEFAULT_SEEDS, side: 8, modes: ["terrain", "full"],
    vanilla: "client"};
  for (let index = 0; index < argv.length; index++) {
    const argument = argv[index];
    const value = () => argv[++index] ?? usage(`${argument} needs a value`);
    switch (argument) {
      case "--profile": options.profile = value(); break;
      case "--seeds": options.seeds = value().split(",").map((seed) => seed.trim()).filter(Boolean); break;
      case "--side": options.side = Number(value()); break;
      case "--modes": options.modes = value().split(",").map((mode) => mode.trim()); break;
      case "--out-dir": options.outDir = resolve(value()); break;
      case "--vanilla": options.vanilla = value(); break;
      case "--server-jar": options.vanilla = "server"; options.serverJar = resolve(value()); break;
      case "--download-server": options.vanilla = "server"; options.downloadServer = true; break;
      case "--patched": options.patched = resolve(value()); break;
      case "--java": options.java = value(); break;
      case "--compare": options.compare = [resolve(value()), resolve(value())]; break;
      default: usage(`unknown argument ${argument}`);
    }
  }
  if (!/^\d+(?:\.\d+)+$/.test(options.profile)) usage(`bad profile ${options.profile}`);
  if (!Number.isInteger(options.side) || options.side < 1 || options.side > 32) usage("--side must be 1..32");
  for (const seed of options.seeds) if (!/^-?\d+$/.test(seed)) usage(`bad seed ${seed}`);
  for (const mode of options.modes) if (mode !== "terrain" && mode !== "full") usage(`bad mode ${mode}`);
  if (!["client", "server"].includes(options.vanilla)) usage("--vanilla must be client or server");
  return options;
}

function nativePath(value) {
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z]\//.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
}

function javaTool(options, name) {
  if (name === "java" && options.java) return options.java;
  const home = process.env.GAIUS_JAVA_HOME || process.env.JAVA_HOME;
  const suffix = process.platform === "win32" ? ".exe" : "";
  return home ? join(nativePath(home), "bin", `${name}${suffix}`) : `${name}${suffix}`;
}

function run(command, args, label, extra = {}) {
  const result = spawnSync(command, args, {encoding: "utf8", maxBuffer: 256 * 1024 * 1024, ...extra});
  if (result.error) throw result.error;
  if (result.status !== 0) {
    const output = `${result.stdout || ""}${result.stderr || ""}`;
    throw new Error(`${label} failed (exit ${result.status})\n${output.slice(-8000)}`);
  }
  return result.stdout || "";
}

function sha(algorithm, file) {
  return createHash(algorithm).update(readFileSync(file)).digest("hex");
}

function readClasspathFile(file) {
  const text = readFileSync(file, "utf8").trim();
  // fetch-version.sh writes a POSIX ':' list of /d/... paths.
  const entries = text.startsWith("/") ? text.split(":") : text.split(delimiter);
  return entries.filter(Boolean).map(nativePath)
    .filter((entry) => !/client-(named|original)\.jar$/.test(entry));
}

async function vanillaInputs(options, work, outDir) {
  const version = JSON.parse(readFileSync(join(work, "version.json"), "utf8"));
  if (options.vanilla === "client") {
    const jar = join(work, "client-original.jar");
    if (!existsSync(jar)) usage(`missing ${jar} (run the profile fetch first)`);
    const expected = version.downloads?.client?.sha1;
    const actual = sha("sha1", jar);
    if (!expected || expected !== actual) {
      throw new Error(`client-original.jar SHA-1 ${actual} does not match version.json ${expected}`);
    }
    console.log(`WORLDGEN_SEED_PARITY_VANILLA client ${relative(root, jar)} sha1=${actual}`);
    return {jar, libraries: readClasspathFile(join(work, "classpath.txt")), kind: "client", sha1: actual};
  }
  const expected = version.downloads?.server?.sha1;
  const url = version.downloads?.server?.url;
  if (!expected) throw new Error("version.json has no downloads.server.sha1");
  let serverJar = options.serverJar;
  if (!serverJar) {
    serverJar = join(outDir, "cache", `server-${expected}.jar`);
    if (!existsSync(serverJar) || sha("sha1", serverJar) !== expected) {
      if (!url?.startsWith("https://")) throw new Error("version.json has no https downloads.server.url");
      console.log(`WORLDGEN_SEED_PARITY_DOWNLOAD ${url}`);
      const response = await fetch(url);
      if (!response.ok) throw new Error(`download failed: HTTP ${response.status}`);
      mkdirSync(dirname(serverJar), {recursive: true});
      writeFileSync(serverJar, Buffer.from(await response.arrayBuffer()));
    }
  }
  const actual = sha("sha1", serverJar);
  if (actual !== expected) {
    throw new Error(`server jar SHA-1 ${actual} does not match version.json ${expected}`);
  }
  // The server jar is a bundler: the game jar and its libraries are nested entries listed,
  // with SHA-256, in META-INF/versions.list and META-INF/libraries.list.
  const unpacked = join(outDir, "cache", `server-${expected}`);
  mkdirSync(unpacked, {recursive: true});
  const listed = [];
  for (const [listName, folder] of [["versions.list", "versions"], ["libraries.list", "libraries"]]) {
    run(javaTool(options, "jar"), ["xf", serverJar, `META-INF/${listName}`],
      `extract ${listName}`, {cwd: unpacked});
    const text = readFileSync(join(unpacked, "META-INF", listName), "utf8");
    for (const line of text.split(/\r?\n/).filter(Boolean)) {
      const [sha256, id, path] = line.split("\t");
      if (!sha256 || !id || !path) throw new Error(`bad ${listName} line: ${line}`);
      listed.push({sha256, entry: `META-INF/${folder}/${path}`, folder});
    }
  }
  run(javaTool(options, "jar"), ["xf", serverJar, ...listed.map((item) => item.entry)],
    "extract bundled jars", {cwd: unpacked});
  for (const item of listed) {
    const file = join(unpacked, item.entry);
    if (sha("sha256", file) !== item.sha256) throw new Error(`bundled ${item.entry} SHA-256 mismatch`);
  }
  const game = listed.filter((item) => item.folder === "versions").map((item) => join(unpacked, item.entry));
  if (game.length !== 1) throw new Error(`expected one bundled game jar, found ${game.length}`);
  console.log(`WORLDGEN_SEED_PARITY_VANILLA server sha1=${actual} game=${relative(unpacked, game[0])}`);
  return {jar: game[0], kind: "server", sha1: actual,
    libraries: listed.filter((item) => item.folder === "libraries").map((item) => join(unpacked, item.entry))};
}

function compileHarness(options, classpath, output) {
  mkdirSync(output, {recursive: true});
  run(javaTool(options, "javac"), ["-J-Duser.language=en", "--release", "25", "-proc:none",
    "-cp", classpath.join(delimiter), "-d", output, join(harnessDir, "WorldgenSeedParity.java")],
  "compile WorldgenSeedParity");
}

function runSeed(options, classpath, {seed, mode, label, outFile, universe}) {
  const started = Date.now();
  const stdout = run(javaTool(options, "java"), ["-Xmx4g", "-Dmax.bg.threads=1",
    "-cp", classpath.join(delimiter), "dev.gaius.parity.WorldgenSeedParity",
    "--seed", seed, "--mode", mode, "--side", String(options.side), "--universe", universe,
    "--out", outFile, "--label", label], `${label} ${mode} seed ${seed}`,
  {timeout: 30 * 60 * 1000});
  const line = stdout.split(/\r?\n/).find((text) => text.includes("WORLDGEN_SEED_PARITY_OK"));
  if (!line) throw new Error(`${label} ${mode} seed ${seed}: no result line\n${stdout.slice(-4000)}`);
  console.log(`${line.slice(line.indexOf("WORLDGEN_SEED_PARITY_OK"))} label=${label} wallMillis=${Date.now() - started}`);
  return JSON.parse(readFileSync(outFile, "utf8"));
}

/** Differences between two result files; an empty list means parity. */
export function compareResults(reference, candidate) {
  const problems = [];
  for (const key of ["seed", "mode", "side", "preset"]) {
    if (String(reference[key]) !== String(candidate[key])) {
      problems.push(`${key} differs: ${reference[key]} vs ${candidate[key]}`);
    }
  }
  if (problems.length) return problems;
  if (reference.origin.x !== candidate.origin.x || reference.origin.z !== candidate.origin.z) {
    problems.push(`spawn chunk (getOrigin) differs: ${JSON.stringify(reference.origin)} vs ${JSON.stringify(candidate.origin)}`);
  }
  if (reference.spawn?.vanillaSpawnSearch && candidate.spawn?.vanillaSpawnSearch
      && (reference.spawn.x !== candidate.spawn.x || reference.spawn.y !== candidate.spawn.y
        || reference.spawn.z !== candidate.spawn.z)) {
    problems.push(`world spawn differs: ${JSON.stringify(reference.spawn)} vs ${JSON.stringify(candidate.spawn)}`);
  }
  const byPosition = new Map(candidate.chunks.map((chunk) => [`${chunk.x},${chunk.z}`, chunk]));
  const blockDiffs = [];
  const biomeDiffs = [];
  for (const chunk of reference.chunks) {
    const other = byPosition.get(`${chunk.x},${chunk.z}`);
    if (!other) {
      problems.push(`chunk ${chunk.x},${chunk.z} missing from candidate`);
      continue;
    }
    if (chunk.blocks !== other.blocks) blockDiffs.push(`${chunk.x},${chunk.z}`);
    if (chunk.biomes !== other.biomes) biomeDiffs.push(`${chunk.x},${chunk.z}`);
  }
  if (blockDiffs.length) problems.push(`block hashes differ in ${blockDiffs.length} chunk(s): ${blockDiffs.join(" ")}`);
  if (biomeDiffs.length) problems.push(`biome hashes differ in ${biomeDiffs.length} chunk(s): ${biomeDiffs.join(" ")}`);
  return problems;
}

function report(label, problems) {
  if (problems.length === 0) {
    console.log(`WORLDGEN_SEED_PARITY_MATCH ${label}`);
  } else {
    for (const problem of problems) console.log(`WORLDGEN_SEED_PARITY_MISMATCH ${label}: ${problem}`);
  }
  return problems.length === 0;
}

function preparePatched(options, work, vanilla, harnessClasses, temp) {
  const overlay = join(temp, "patched-classes");
  const shims = join(temp, "shim-classes");
  mkdirSync(overlay, {recursive: true});
  run(javaTool(options, "jar"), ["xf", options.patched,
    ...P6_WORLDGEN_CLASSES.map((owner) => `${owner}.class`)], "extract patched worldgen classes",
  {cwd: overlay});
  for (const owner of P6_WORLDGEN_CLASSES) {
    if (!existsSync(join(overlay, `${owner}.class`))) {
      throw new Error(`patched jar ${options.patched} has no ${owner}.class`);
    }
  }
  const unsigned = join(temp, "vanilla-unsigned.jar");
  run(javaTool(options, "java"), ["-cp", harnessClasses, "dev.gaius.parity.WorldgenSeedParity",
    "--strip-signature", vanilla.jar, unsigned], "strip vanilla jar signature");
  const shimSources = [];
  const collect = (dir) => {
    for (const name of readdirSync(dir)) {
      const path = join(dir, name);
      if (statSync(path).isDirectory()) collect(path);
      else if (name.endsWith(".java")) shimSources.push(path);
    }
  };
  collect(join(harnessDir, "shims"));
  for (const helper of REAL_HELPERS) {
    const versioned = join(root, "port/src/versions", options.profile, "java", helper);
    shimSources.push(existsSync(versioned) ? versioned : join(root, "port/src/main/java", helper));
  }
  mkdirSync(shims, {recursive: true});
  run(javaTool(options, "javac"), ["-J-Duser.language=en", "--release", "25", "-proc:none",
    "-cp", [unsigned, ...vanilla.libraries].join(delimiter), "-d", shims, ...shimSources],
  "compile helper shims");
  return [shims, overlay, harnessClasses, unsigned, ...vanilla.libraries];
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.compare) {
    const [reference, candidate] = options.compare.map((file) => JSON.parse(readFileSync(file, "utf8")));
    const ok = report(`${reference.label || "reference"} vs ${candidate.label || "candidate"} `
      + `${reference.mode} seed ${reference.seed}`, compareResults(reference, candidate));
    console.log(`WORLDGEN_SEED_PARITY_RESULT ${ok ? "PASS" : "FAIL"}`);
    process.exit(ok ? 0 : 1);
  }
  const work = join(root, "port/work", options.profile);
  if (!existsSync(join(work, "version.json"))) usage(`missing ${work}/version.json`);
  const outDir = options.outDir || join(root, "port/target", options.profile, "worldgen-seed-parity");
  mkdirSync(outDir, {recursive: true});
  const vanilla = await vanillaInputs(options, work, outDir);
  const temp = mkdtempSync(join(tmpdir(), "gaius-seed-parity-"));
  try {
    const harnessClasses = join(temp, "harness-classes");
    const vanillaClasspath = [vanilla.jar, ...vanilla.libraries];
    compileHarness(options, vanillaClasspath, harnessClasses);
    const patchedClasspath = options.patched
      ? preparePatched(options, work, vanilla, harnessClasses, temp) : null;
    const universe = join(temp, "universe");
    let ok = true;
    const summary = [];
    for (const seed of options.seeds) {
      for (const mode of options.modes) {
        const reference = runSeed(options, [harnessClasses, ...vanillaClasspath], {seed, mode,
          label: `vanilla-${vanilla.kind}`, universe,
          outFile: join(outDir, `vanilla-${mode}-${seed}.json`)});
        summary.push({seed, mode, label: reference.label, aggregate: reference.aggregate,
          origin: reference.origin, spawn: reference.spawn});
        if (patchedClasspath) {
          const candidate = runSeed(options, patchedClasspath, {seed, mode, label: "patched-jvm",
            universe, outFile: join(outDir, `patched-jvm-${mode}-${seed}.json`)});
          summary.push({seed, mode, label: candidate.label, aggregate: candidate.aggregate,
            pulses: candidate.pulses?.total});
          ok = report(`patched-jvm vs vanilla ${mode} seed ${seed}`,
            compareResults(reference, candidate)) && ok;
        }
      }
    }
    writeFileSync(join(outDir, "summary.json"), `${JSON.stringify({profile: options.profile,
      vanilla: {kind: vanilla.kind, sha1: vanilla.sha1}, patched: options.patched || null,
      side: options.side, results: summary}, null, 2)}\n`);
    console.log(`WORLDGEN_SEED_PARITY_OUTPUT ${outDir}`);
    console.log(`WORLDGEN_SEED_PARITY_RESULT ${ok ? "PASS" : "FAIL"}`);
    process.exitCode = ok ? 0 : 1;
  } finally {
    rmSync(temp, {recursive: true, force: true});
  }
}

main().catch((error) => {
  console.error(error.stack || String(error));
  process.exit(1);
});
