#!/usr/bin/env node
// G3 link-integrity gate for any profile.
//
// Scans the patched client jar (port/work/overlays/<id>/client-named-<id>-gaius.jar)
// and the compiled Gaius runtime classes (port/target/<id>/maven/classes) and
// resolves every class, field and method reference against the same class path
// TeaVM compiles with (the dependencies of port/target/<id>/generated-pom.xml:
// overlay libraries, the TeaVM classlib overlay and TeaVM jars), then the JDK.
// It also checks that INVOKEINTERFACE/INVOKEVIRTUAL and the invoke itf flag
// match the owner's kind and that static accesses hit static members.  This is
// the gate for "patch applied silently but its references dangle".
//
// Prerequisites for a profile: build-overlays.sh, generate-pom.sh and a
// javac-only Maven compile of the generated POM (mvnw -f <pom> compile).
//
//   node port/scripts/minecraft-link-gap-smoke.mjs --profile 26.2 --baseline base.json
//   node port/scripts/minecraft-link-gap-smoke.mjs --profile 26.3 --expect-zero
//   node port/scripts/minecraft-link-gap-smoke.mjs --profile 26.2 --write-baseline base.json
//
// Options:
//   --profile <id>           profile id (default: GAIUS_MINECRAFT_VERSION,
//                            GAIUS_VERSION_PROFILE_PATH, then port/config.json)
//   --overlay-dir <dir>      default GAIUS_OVERLAY_DIRECTORY or port/work/overlays/<id>
//   --build-root <dir>       default GAIUS_BUILD_ROOT or port/target/<id>
//   --pom <file>             default <build-root>/generated-pom.xml
//   --runtime-classes <dir>  default <build-root>/maven/classes
//   --no-runtime-classes     scan the client jar only (Gaius runtime references
//                            are then reported as missing classes)
//   --baseline <json>        fail on issues that are not in the baseline (26.2 rule)
//   --expect-zero            fail on any issue (26.3 rule)
//   --write-baseline <json>  record the current issue set
//   --report <json>          write every issue with counts and sample referrers
// Exit status: 0 pass, 1 gate failure, 2 usage or environment error.

import {execFileSync} from "node:child_process";
import {existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from "node:fs";
import {homedir, tmpdir} from "node:os";
import {basename, dirname, isAbsolute, join, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const scannerSource = join(root, "port/scripts/link-gap/LinkGapScanner.java");
const ASM_VERSION = "9.8";

const nativePath = (value) => {
  if (!value) return value;
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
};
const fromRoot = (value) => {
  const path = nativePath(value);
  return isAbsolute(path) ? path : resolve(root, path);
};

function usage(message) {
  console.error(`minecraft-link-gap-smoke: ${message}`);
  process.exit(2);
}

const options = {};
const flags = new Set(["--no-runtime-classes", "--expect-zero", "--quiet"]);
for (let index = 2; index < process.argv.length; index++) {
  const argument = process.argv[index];
  if (flags.has(argument)) {
    options[argument.slice(2)] = true;
  } else if (argument.startsWith("--") && index + 1 < process.argv.length) {
    options[argument.slice(2)] = process.argv[++index];
  } else {
    usage(`unknown or incomplete argument ${argument}`);
  }
}
const known = new Set(["profile", "overlay-dir", "build-root", "pom", "runtime-classes",
  "no-runtime-classes", "baseline", "expect-zero", "write-baseline", "report", "quiet"]);
for (const key of Object.keys(options)) {
  if (!known.has(key)) usage(`unknown option --${key}`);
}

function activeProfile() {
  if (options.profile) return options.profile;
  if (process.env.GAIUS_MINECRAFT_VERSION) return process.env.GAIUS_MINECRAFT_VERSION;
  const profilePath = process.env.GAIUS_VERSION_PROFILE_PATH
    || JSON.parse(readFileSync(join(root, "port/config.json"), "utf8")).versionProfile;
  return basename(nativePath(profilePath).replaceAll("\\", "/")).replace(/\.json$/, "");
}

const profile = activeProfile();
if (!/^\d+(?:\.\d+)+$/.test(profile)) usage(`invalid profile id ${profile}`);
if (!existsSync(join(root, "port/versions", `${profile}.json`))) {
  usage(`port/versions/${profile}.json does not exist`);
}
const overlayDir = fromRoot(options["overlay-dir"] || process.env.GAIUS_OVERLAY_DIRECTORY
  || join("port/work/overlays", profile));
const buildRoot = fromRoot(options["build-root"] || process.env.GAIUS_BUILD_ROOT
  || join("port/target", profile));
const pomPath = fromRoot(options.pom || join(buildRoot, "generated-pom.xml"));
const runtimeClasses = options["no-runtime-classes"] ? null
  : fromRoot(options["runtime-classes"] || join(buildRoot, "maven/classes"));
const patchedJar = join(overlayDir, `client-named-${profile}-gaius.jar`);
const mavenRepository = fromRoot(process.env.GAIUS_MAVEN_REPOSITORY
  || join(homedir(), ".m2/repository"));

for (const [label, path] of [["patched client jar", patchedJar], ["generated POM", pomPath]]) {
  if (!existsSync(path)) {
    usage(`${label} is missing: ${path} (run build-overlays.sh and generate-pom.sh)`);
  }
}
if (runtimeClasses && !existsSync(runtimeClasses)) {
  usage(`runtime classes are missing: ${runtimeClasses} ` +
    "(run mvnw -f <generated-pom> compile, or pass --no-runtime-classes)");
}

// The generated POM is the TeaVM class path: system-scoped jars by path and
// the TeaVM artifacts by Maven coordinates.
function pomClasspath(text) {
  const properties = {};
  const propertyBlock = /<properties>([\s\S]*?)<\/properties>/.exec(text);
  if (propertyBlock) {
    for (const match of propertyBlock[1].matchAll(/<([\w.-]+)>([^<]*)<\/\1>/g)) {
      properties[match[1]] = match[2].trim();
    }
  }
  const expand = (value) => value.replace(/\$\{([\w.-]+)\}/g,
    (whole, key) => properties[key] ?? whole);
  const dependencies = /<dependencies>([\s\S]*?)<\/dependencies>/.exec(text)?.[1] ?? "";
  const paths = [];
  for (const block of dependencies.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)) {
    const field = (name) => new RegExp(`<${name}>([^<]*)</${name}>`).exec(block[1])?.[1]?.trim();
    const systemPath = field("systemPath");
    if (systemPath) {
      paths.push(nativePath(expand(systemPath)));
      continue;
    }
    const groupId = expand(field("groupId") ?? "");
    const artifactId = expand(field("artifactId") ?? "");
    const version = expand(field("version") ?? "");
    paths.push(join(mavenRepository, ...groupId.split("."), artifactId, version,
      `${artifactId}-${version}.jar`));
  }
  return paths;
}

const classpath = pomClasspath(readFileSync(pomPath, "utf8"));
const missingJars = classpath.filter((path) => !existsSync(path));
if (missingJars.length > 0) {
  usage(`generated POM dependencies are missing:\n  ${missingJars.join("\n  ")}`);
}

function javaTool(name) {
  for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME]
    .filter(Boolean).map(nativePath)) {
    const candidate = join(home, "bin", name);
    if (existsSync(candidate) || existsSync(`${candidate}.exe`)) return candidate;
  }
  return name;
}

const asmJar = join(mavenRepository, "org/ow2/asm/asm", ASM_VERSION, `asm-${ASM_VERSION}.jar`);
if (!existsSync(asmJar)) usage(`ASM ${ASM_VERSION} is missing: ${asmJar}`);

const work = mkdtempSync(join(tmpdir(), "gaius-link-gap-"));
const issuesPath = join(work, "issues.tsv");
const scannerArguments = ["-cp", asmJar, scannerSource, "--out", issuesPath,
  "--subject", patchedJar];
if (runtimeClasses) scannerArguments.push("--subject", runtimeClasses);
for (const path of classpath) {
  if (resolve(path) !== resolve(patchedJar)) scannerArguments.push("--classpath", path);
}

let scanned;
try {
  const output = execFileSync(javaTool("java"), scannerArguments,
    {encoding: "utf8", maxBuffer: 64 * 1024 * 1024, stdio: ["ignore", "pipe", "inherit"]});
  process.stdout.write(output);
  scanned = readFileSync(issuesPath, "utf8");
} catch (error) {
  console.error(`link-gap scanner failed: ${error.message}`);
  process.exit(2);
} finally {
  rmSync(work, {recursive: true, force: true});
}

const issues = new Map();
for (const line of scanned.split("\n")) {
  if (!line) continue;
  const [key, count, ...referrers] = line.split("\t");
  issues.set(key, {count: Number(count), referrers});
}
const byKind = {};
for (const key of issues.keys()) {
  const kind = key.slice(0, key.indexOf(" "));
  byKind[kind] = (byKind[kind] ?? 0) + 1;
}

console.log(`LINK_GAP_PROFILE ${profile}`);
console.log(`LINK_GAP_ISSUES total=${issues.size} ` +
  Object.entries(byKind).sort().map(([kind, count]) => `${kind}=${count}`).join(" "));

if (options.report) {
  writeFileSync(fromRoot(options.report), JSON.stringify({
    format: "gaius-link-gap-report/1",
    profile,
    patchedJar,
    runtimeClasses,
    issues: Object.fromEntries([...issues].map(([key, value]) => [key, value])),
  }, null, 1) + "\n");
}
if (options["write-baseline"]) {
  writeFileSync(fromRoot(options["write-baseline"]), JSON.stringify({
    format: "gaius-link-gap-baseline/1",
    profile,
    createdUtc: new Date().toISOString(),
    issues: Object.fromEntries([...issues].map(([key, value]) => [key, value.count])),
  }, null, 1) + "\n");
  console.log(`LINK_GAP_BASELINE_WRITTEN ${options["write-baseline"]}`);
}

let failed = false;
const show = (label, keys) => {
  const limit = options.quiet ? 20 : keys.length;
  for (const key of keys.slice(0, limit)) {
    const referrers = issues.get(key)?.referrers ?? [];
    console.log(`${label} ${key}${referrers.length ? `  <- ${referrers.join(", ")}` : ""}`);
  }
  if (keys.length > limit) console.log(`${label} ... ${keys.length - limit} more`);
};
if (options.baseline) {
  const baseline = JSON.parse(readFileSync(fromRoot(options.baseline), "utf8"));
  if (baseline.format !== "gaius-link-gap-baseline/1") usage("baseline has an unknown format");
  if (baseline.profile !== profile) {
    usage(`baseline profile ${baseline.profile} does not match ${profile}`);
  }
  const known = new Set(Object.keys(baseline.issues));
  const added = [...issues.keys()].filter((key) => !known.has(key)).sort();
  const resolved = [...known].filter((key) => !issues.has(key)).sort();
  show("NEW", added);
  for (const key of resolved) console.log(`RESOLVED ${key}`);
  console.log(`LINK_GAP_BASELINE new=${added.length} resolved=${resolved.length} ` +
    `unchanged=${issues.size - added.length}`);
  if (added.length > 0) failed = true;
}
if (options["expect-zero"]) {
  show("ISSUE", [...issues.keys()].sort());
  if (issues.size > 0) failed = true;
}
if (!options.baseline && !options["expect-zero"] && !options.quiet) {
  show("ISSUE", [...issues.keys()].sort());
}
console.log(`LINK_GAP_RESULT ${failed ? "FAIL" : "PASS"}`);
process.exit(failed ? 1 : 0);
