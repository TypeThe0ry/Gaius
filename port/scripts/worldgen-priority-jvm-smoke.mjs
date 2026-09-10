#!/usr/bin/env node
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { delimiter, dirname, join, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const args = process.argv.slice(2);
const jarIndex = args.indexOf("--jar");
const cpIndex = args.indexOf("--classpath-file");
if (jarIndex < 0 || !args[jarIndex + 1]) {
  console.error("usage: node port/scripts/worldgen-priority-jvm-smoke.mjs --jar PATCHED_CLIENT.jar [--classpath-file classpath.txt]");
  process.exit(2);
}

const jar = resolve(args[jarIndex + 1]);
if (!existsSync(jar)) throw new Error(`patched jar not found: ${jar}`);
const dependencyClasspath = cpIndex < 0 ? [] : parseClasspath(readFileSync(resolve(args[cpIndex + 1]), "utf8"));
const javaHome = process.env.JAVA_HOME;
const suffix = process.platform === "win32" ? ".exe" : "";
const java = javaHome ? join(javaHome, "bin", `java${suffix}`) : `java${suffix}`;
const javac = javaHome ? join(javaHome, "bin", `javac${suffix}`) : `javac${suffix}`;
const root = mkdtempSync(join(tmpdir(), "gaius-worldgen-priority-"));
const classes = join(root, "classes");
mkdirSync(classes, { recursive: true });
const fixtureDir = join(dirname(fileURLToPath(import.meta.url)), "fixtures");
const compileCp = [jar, ...dependencyClasspath].join(delimiter);
try {
  const compile = spawnSync(javac, ["--release", "21", "-proc:none", "-classpath", compileCp,
    "-d", classes, join(fixtureDir, "TModernRuntimeSupport.java"), join(fixtureDir, "WorldgenPriorityJvmFixture.java")],
    { encoding: "utf8", stdio: "inherit", timeout: 60_000 });
  if (compile.error) throw compile.error;
  if (compile.status !== 0) process.exitCode = compile.status ?? 1;
  else {
    const runCp = [classes, jar, ...dependencyClasspath].join(delimiter);
    const run = spawnSync(java, ["-cp", runCp, "WorldgenPriorityJvmFixture"],
      { encoding: "utf8", stdio: "inherit", timeout: 30_000 });
    if (run.error) throw run.error;
    process.exitCode = run.status ?? 1;
  }
} finally {
  rmSync(root, { recursive: true, force: true });
}
process.exit(process.exitCode ?? 1);

function parseClasspath(text) {
  const normalized = text.trim();
  if (!normalized) return [];
  if (process.platform === "win32") {
    // fetch-version.sh emits ':'-separated /c/... entries; accept native paths too.
    const entries = normalized.includes(";")
      ? normalized.split(";")
      : normalized.split(/:(?=\/(?:[a-zA-Z])\/|[A-Za-z]:[\\/])/);
    return entries
      .map((entry) => entry.replace(/^\/([a-zA-Z])\//, "$1:/"))
      .filter(Boolean);
  }
  return normalized.split(":").filter(Boolean);
}
