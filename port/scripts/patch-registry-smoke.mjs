#!/usr/bin/env node

// Unit-style smoke for the 26.3 bring-up infrastructure (migration plan contracts C2/C3):
// - dev.gaius.tools.PatchRegistry: bring-up list parsing, bring-up activation rules (only with
//   GAIUS_BRINGUP=1 and never for 26.2 or 1.21.11), BRINGUP_SKIP/PATCH_DROPPED/PATCH_SUMMARY
//   lines, dropped-target assertions, pending-list output on failure, profile conflicts, and the
//   patch id rule it shares with version-profile.sh, check-build-log-skips.mjs and
//   check-version-profile.mjs;
// - dev.gaius.tools.Minecraft263BrowserPatcher: refuses 26.2/1.21.11 and non-renderpearl jars,
//   runs the six m263 package domains and the JdkCompat domain in order on the 26.3 jar;
// - MinecraftClientPatcher/Minecraft262BrowserPatcher main(): every patch call is wrapped in
//   PatchRegistry.run("<Class>.<method>", () -> <method>(...)).
// When port/tools/bringup/26.3.txt exists (or --bringup-list PATH is given), it also runs
// MCP -> M262 -> M263 on a copy of the 26.3 jar in bring-up mode and checks that every skip is
// listed (pass --require-bringup-list to fail instead of skipping that part without a list).
import assert from "node:assert/strict";
import {execFileSync, spawnSync} from "node:child_process";
import {access, copyFile, mkdir, mkdtemp, readFile, readdir, rm, writeFile} from "node:fs/promises";
import {existsSync} from "node:fs";
import {homedir, tmpdir} from "node:os";
import {delimiter, join} from "node:path";
import {fileURLToPath} from "node:url";

const repositoryRoot = fileURLToPath(new URL("../..", import.meta.url));
const nativePath = (value) => {
  if (!value) return value;
  const text = String(value);
  return process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/.test(text)
    ? `${text[1].toUpperCase()}:${text.slice(2)}` : text;
};
const toolsRoot = join(repositoryRoot, "port/tools/src/main/java");
const toolsPackage = join(toolsRoot, "dev/gaius/tools");
const asmRoot = join(homedir(), ".m2/repository/org/ow2/asm");
const asm = join(asmRoot, "asm/9.8/asm-9.8.jar");
const asmTree = join(asmRoot, "asm-tree/9.8/asm-tree-9.8.jar");
const jar262 = join(repositoryRoot, "port/work/26.2/client-named.jar");
const jar263 = join(repositoryRoot, "port/work/26.3/client-named.jar");
const listFlag = process.argv.indexOf("--bringup-list");
const bringupList263 = listFlag >= 0 ? nativePath(process.argv[listFlag + 1])
  : join(repositoryRoot, "port/tools/bringup/26.3.txt");
const requireBringupList = listFlag >= 0 || process.argv.includes("--require-bringup-list");

function jdkTool(name) {
  const homes = [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME].filter(Boolean).map(nativePath);
  for (const home of [...new Set(homes)]) {
    const candidate = join(home, "bin", name);
    if (existsSync(candidate) || existsSync(`${candidate}.exe`)) return candidate;
  }
  return name;
}

const DRIVER = String.raw`
import dev.gaius.tools.PatchRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

public final class PatchRegistrySmokeDriver {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "parse" -> {
                try {
                    var entries = PatchRegistry.parseBringupList(Path.of(args[1]));
                    for (var entry : entries.values()) {
                        System.out.println("ENTRY " + entry.patchId() + " | " + entry.owner() + " | "
                                + entry.reason());
                    }
                } catch (IllegalStateException error) {
                    System.out.println("PARSE_ERROR " + error.getMessage());
                }
            }
            case "skip" -> {
                if (!args[1].equals("-")) PatchRegistry.configureProfile(args[1]);
                System.out.println("ACTIVE " + PatchRegistry.bringupActive() + " PROFILE "
                        + PatchRegistry.profile());
                for (int i = 2; i < args.length; i++) {
                    System.out.println("QUERY " + args[i] + " " + PatchRegistry.bringupSkip(args[i]));
                }
                PatchRegistry.printSummary();
            }
            case "run" -> {
                PatchRegistry.configureProfile(args[1]);
                for (int i = 2; i < args.length; i++) {
                    String id = args[i];
                    boolean fail = id.endsWith("Fail");
                    try {
                        PatchRegistry.run(id, () -> {
                            System.out.println("BODY " + id);
                            if (fail) throw new IOException("boom " + id);
                        });
                    } catch (IOException error) {
                        System.out.println("RETHROWN " + error.getMessage());
                    }
                }
                PatchRegistry.applied("Manual.patch");
                PatchRegistry.printSummary();
            }
            case "dropped" -> {
                String[] targets = java.util.Arrays.copyOfRange(args, 3, args.length);
                try {
                    PatchRegistry.dropped(args[2], args[1], targets);
                    System.out.println("DROPPED_OK");
                } catch (IllegalStateException | IllegalArgumentException error) {
                    System.out.println("DROPPED_ERROR " + error.getMessage());
                }
            }
            case "make-jar" -> {
                ClassWriter writer = new ClassWriter(0);
                writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "a/B", null, "java/lang/Object", null);
                writer.visitField(Opcodes.ACC_PUBLIC, "field", "I", null, null).visitEnd();
                var method = writer.visitMethod(Opcodes.ACC_PUBLIC, "m", "(I)V", null, null);
                method.visitCode();
                method.visitInsn(Opcodes.RETURN);
                method.visitMaxs(0, 2);
                method.visitEnd();
                writer.visitEnd();
                try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(Path.of(args[1])))) {
                    out.putNextEntry(new JarEntry("a/B.class"));
                    out.write(writer.toByteArray());
                    out.closeEntry();
                    out.putNextEntry(new JarEntry("assets/x.txt"));
                    out.write(1);
                    out.closeEntry();
                }
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }
}
`;

const javac = jdkTool("javac");
const java = jdkTool("java");
const jarTool = jdkTool("jar");
await Promise.all([asm, asmTree, jar262, jar263].map((path) => access(path)));

function mainBody(source, name) {
  const start = source.indexOf("public static void main(String[] args)");
  assert.ok(start >= 0, `${name}: main() not found`);
  let index = source.indexOf("{", start) + 1;
  let depth = 1;
  const bodyStart = index;
  while (depth > 0) {
    const ch = source[index++];
    if (ch === "{") depth++;
    else if (ch === "}") depth--;
  }
  return source.slice(bodyStart, index - 1);
}

// Every statement of main() is a declaration, control flow, a comment, a PatchRegistry
// bookkeeping call or PatchRegistry.run("<Class>.<method>", () -> <method>(...)).
function checkWrappedMain(source, className, minimumWrapped) {
  const body = mainBody(source, className);
  const ids = [];
  let statementStart = true;
  let depth = 0;
  for (const raw of body.split(/\r?\n/)) {
    const line = raw.trim();
    if (line === "" || line.startsWith("//")) continue;
    if (statementStart && depth === 0) {
      const wrapped = line.match(/^PatchRegistry\.run\("([A-Za-z0-9]+)\.([A-Za-z0-9_]+)", \(\) -> ([A-Za-z0-9_]+)\(/);
      if (wrapped) {
        assert.equal(wrapped[1], className, `${className}: wrong class in id: ${line}`);
        assert.equal(wrapped[2], wrapped[3], `${className}: id does not name the called method: ${line}`);
        ids.push(wrapped[2]);
      } else {
        assert.match(line, new RegExp(
          "^(if \\(|} else \\{|}|throw new |String |Path |boolean |int "
          + "|PatchRegistry\\.configureProfile\\(|PatchRegistry\\.printSummary\\(\\);)"),
          `${className} main(): unwrapped statement: ${line}`);
      }
    }
    for (const ch of line.replace(/"(?:[^"\\]|\\.)*"/g, "\"\"")) {
      if (ch === "(") depth++;
      else if (ch === ")") depth--;
    }
    statementStart = depth === 0 && /[;{}]$/.test(line);
  }
  assert.ok(ids.length >= minimumWrapped,
    `${className}: only ${ids.length} wrapped patch calls (expected >= ${minimumWrapped})`);
  assert.match(body, /PatchRegistry\.configureProfile\(/, `${className}: profile not configured`);
  assert.match(body, /PatchRegistry\.printSummary\(\);\s*$/, `${className}: summary missing`);
  return ids;
}

const work = await mkdtemp(join(tmpdir(), "gaius-patch-registry-"));
try {
  // Source contract for the main() dispatch.
  const mcpIds = checkWrappedMain(
    await readFile(join(toolsPackage, "MinecraftClientPatcher.java"), "utf8"),
    "MinecraftClientPatcher", 170);
  const m262Ids = checkWrappedMain(
    await readFile(join(toolsPackage, "Minecraft262BrowserPatcher.java"), "utf8"),
    "Minecraft262BrowserPatcher", 30);
  assert.equal(new Set(m262Ids).size, m262Ids.length, "M262 patch ids must be unique");

  const classes = join(work, "classes");
  await mkdir(classes, {recursive: true});
  const sources = [
    ...(await readdir(toolsPackage)).filter((name) => name.endsWith(".java"))
      .map((name) => join(toolsPackage, name)),
    ...(await readdir(join(toolsPackage, "m263"))).filter((name) => name.endsWith(".java"))
      .map((name) => join(toolsPackage, "m263", name)),
  ];
  const driverSource = join(work, "PatchRegistrySmokeDriver.java");
  await writeFile(driverSource, DRIVER);
  execFileSync(javac, ["-J-Duser.language=en", "--release", "21", "-proc:none",
    "-classpath", [asm, asmTree].join(delimiter), "-sourcepath", toolsRoot, "-d", classes,
    ...sources, driverSource], {encoding: "utf8", timeout: 180_000});
  const classpath = [classes, asm, asmTree].join(delimiter);
  const baseEnv = {...process.env};
  for (const name of ["GAIUS_BRINGUP", "GAIUS_BRINGUP_LIST", "GAIUS_PROFILE",
    "GAIUS_MINECRAFT_VERSION", "GAIUS_PATCH_VERBOSE", "GAIUS_AUTHLIB_JAR"]) {
    delete baseEnv[name];
  }
  const run = (args, env = {}, cwd = work) => {
    const result = spawnSync(java, ["-Duser.language=en", "-classpath", classpath, ...args], {
      encoding: "utf8", cwd, env: {...baseEnv, ...env}, timeout: 300_000,
      maxBuffer: 64 * 1024 * 1024,
    });
    return {status: result.status, out: result.stdout, err: result.stderr};
  };
  const driver = (args, env, cwd) => run(["PatchRegistrySmokeDriver", ...args], env, cwd);

  // Bring-up list parsing.
  const list = join(work, "bringup.txt");
  await writeFile(list, [
    "# comment line",
    "   # indented comment",
    "",
    "Demo.patchA | P3 | reason text   # trailing comment | not a field",
    "Demo.patchFail | P7a | fails on purpose",
    "step:LwjglSdlBrowserPatcher | P2 | shell step",
    "step:LwjglMemoryPatcher@lwjgl | P1 | module shell step",
    "",
  ].join("\n"));
  let result = driver(["parse", list]);
  assert.equal(result.status, 0, result.err);
  // Same rules as version-profile.sh, check-version-profile.mjs and
  // check-build-log-skips.mjs: '#' starts a comment anywhere on the line.
  assert.equal(result.out.trim().split(/\r?\n/).join("\n"), [
    "ENTRY Demo.patchA | P3 | reason text",
    "ENTRY Demo.patchFail | P7a | fails on purpose",
    "ENTRY step:LwjglSdlBrowserPatcher | P2 | shell step",
    "ENTRY step:LwjglMemoryPatcher@lwjgl | P1 | module shell step",
  ].join("\n"));
  for (const [text, message] of [
    ["Demo.x | Q3 | bad owner", "invalid owner package"],
    ["Demo.x | P0 | lead is not an owner", "invalid owner package"],
    ["Demo.x | P3 |   ", "missing reason"],
    ["Demo.x | P3 | # only a comment", "missing reason"],
    ["Demo.x | P3", "expected '<patchId> | <owner> | <reason>'"],
    ["Demo.x | P3 | reason | extra field", "expected '<patchId> | <owner> | <reason>'"],
    ["Demo.x | P3 | one\nDemo.x | P5 | two", "duplicate bring-up entry Demo.x"],
    ["bad id | P3 | reason", "invalid patch id"],
    ["a/b | P3 | slash", "invalid patch id"],
    [":x | P3 | leading colon", "invalid patch id"],
  ]) {
    const bad = join(work, "bad.txt");
    await writeFile(bad, text + "\n");
    result = driver(["parse", bad]);
    assert.match(result.out, new RegExp("^PARSE_ERROR .*" + message.replace(/[|()<>]/g, "\\$&")),
      `bad list not rejected: ${JSON.stringify(text)}`);
  }

  // The four readers of a bring-up list accept exactly the same patch ids:
  // ^[A-Za-z0-9_$][A-Za-z0-9_$.:@-]*$ (PatchRegistry, version-profile.sh,
  // check-build-log-skips.mjs, check-version-profile.mjs).
  const canonicalId = "[A-Za-z0-9_$][A-Za-z0-9_$.:@-]*";
  for (const [file, text] of [
    ["port/tools/src/main/java/dev/gaius/tools/PatchRegistry.java", `Pattern.compile("${canonicalId}")`],
    ["port/scripts/version-profile.sh", `part[1] !~ /^${canonicalId}$/`],
    ["port/scripts/check-build-log-skips.mjs", `!/^${canonicalId}$/.test(fields[0])`],
    ["port/scripts/check-version-profile.mjs", `!/^${canonicalId}$/u.test(patchId)`],
  ]) {
    assert.ok((await readFile(join(repositoryRoot, file), "utf8")).includes(text),
      `${file} must use the patch id rule ${canonicalId}`);
  }
  const idRoot = join(work, "id-root");
  await mkdir(join(idRoot, "port/tools/bringup"), {recursive: true});
  await mkdir(join(idRoot, "port/scripts"), {recursive: true});
  await copyFile(join(repositoryRoot, "port/scripts/check-build-log-skips.mjs"),
    join(idRoot, "port/scripts/check-build-log-skips.mjs"));
  const emptyLog = join(work, "empty.log");
  await writeFile(emptyLog, "");
  const idList = join(idRoot, "port/tools/bringup/26.9.txt");
  const bash = process.platform === "win32" ? "bash.exe" : "bash";
  for (const [id, valid] of [
    ["Demo.patchA", true], ["step:LwjglMemoryPatcher@lwjgl", true], ["$x", true], ["_x", true],
    ["9x-y", true], [":x", false], [".x", false], ["@x", false], ["-x", false], ["a/b", false],
    ["Demo/patch", false],
  ]) {
    await writeFile(idList, `${id} | P3 | boundary case\n`);
    result = driver(["parse", idList]);
    assert.equal(/^ENTRY /m.test(result.out), valid, `PatchRegistry on ${id}: ${result.out}`);
    const shell = spawnSync(bash, ["-c",
      'source "$1/port/scripts/version-profile.sh"; GAIUS_MINECRAFT_VERSION=26.9; gaius_bringup_ids "$2"',
      "_", repositoryRoot.replaceAll("\\", "/"), idRoot.replaceAll("\\", "/")], {encoding: "utf8"});
    assert.equal(shell.status === 0 && shell.stdout.trim() === id, valid,
      `version-profile.sh on ${id}: ${shell.status} ${shell.stdout} ${shell.stderr}`);
    const lint = spawnSync(process.execPath, [join(idRoot, "port/scripts/check-build-log-skips.mjs"),
      "--profile", "26.9", emptyLog], {encoding: "utf8"});
    assert.equal(lint.status, valid ? 0 : 2,
      `check-build-log-skips.mjs on ${id}: ${lint.stdout} ${lint.stderr}`);
  }
  // Ids outside the rule are refused at run time as well.
  result = driver(["skip", "26.3", "a/b"], {GAIUS_BRINGUP_LIST: list});
  assert.notEqual(result.status, 0);
  assert.match(result.err, /Invalid patch id: a\/b/);

  // Bring-up is active only with the flag and an eligible profile.
  result = driver(["skip", "26.3", "Demo.patchA", "Demo.patchB"],
    {GAIUS_BRINGUP: "1", GAIUS_BRINGUP_LIST: list});
  assert.equal(result.status, 0, result.err);
  assert.match(result.out, /^ACTIVE true PROFILE 26\.3$/m);
  assert.match(result.out, /^BRINGUP_SKIP Demo\.patchA$/m);
  assert.match(result.out, /^QUERY Demo\.patchA true$/m);
  assert.match(result.out, /^QUERY Demo\.patchB false$/m);
  assert.match(result.out, /^PATCH_SUMMARY applied=0 dropped=0 bringupSkipped=1$/m);
  assert.match(result.err, /^BRINGUP_UNUSED Demo\.patchFail$/m, "unused listed ids are reported");
  assert.doesNotMatch(result.err, /BRINGUP_UNUSED step:/, "other scopes are not reported");
  for (const profile of ["26.2", "1.21.11"]) {
    result = driver(["skip", profile, "Demo.patchA"], {GAIUS_BRINGUP: "1", GAIUS_BRINGUP_LIST: list});
    assert.equal(result.status, 0, result.err);
    assert.match(result.out, new RegExp(`^ACTIVE false PROFILE ${profile.replaceAll(".", "\\.")}$`, "m"));
    assert.match(result.out, /^QUERY Demo\.patchA false$/m);
    assert.doesNotMatch(result.out, /BRINGUP_SKIP/);
    assert.match(result.err, new RegExp(`^BRINGUP_IGNORED profile=${profile.replaceAll(".", "\\.")} `, "m"));
  }
  result = driver(["skip", "26.3", "Demo.patchA"], {GAIUS_BRINGUP_LIST: list});
  assert.match(result.out, /^ACTIVE false PROFILE 26\.3$/m, "no flag, no bring-up");
  assert.match(result.out, /^QUERY Demo\.patchA false$/m);
  for (const flag of ["true", "yes"]) {
    result = driver(["skip", "26.3", "Demo.patchA"], {GAIUS_BRINGUP: flag, GAIUS_BRINGUP_LIST: list});
    assert.match(result.out, /^QUERY Demo\.patchA true$/m, `GAIUS_BRINGUP=${flag}`);
  }
  // Profile sources: configureProfile, then gaius.profile / GAIUS_PROFILE, then GAIUS_MINECRAFT_VERSION.
  result = driver(["skip", "-", "Demo.patchA"],
    {GAIUS_BRINGUP: "1", GAIUS_BRINGUP_LIST: list, GAIUS_MINECRAFT_VERSION: "26.3"});
  assert.match(result.out, /^ACTIVE true PROFILE 26\.3$/m, "GAIUS_MINECRAFT_VERSION fallback");
  result = run(["-Dgaius.profile=26.3", "-Dgaius.bringup=1", `-Dgaius.bringup.list=${list}`,
    "PatchRegistrySmokeDriver", "skip", "-", "Demo.patchA"]);
  assert.match(result.out, /^QUERY Demo\.patchA true$/m, "system properties");
  result = driver(["skip", "26.3", "Demo.patchA"], {GAIUS_PROFILE: "26.2"});
  assert.notEqual(result.status, 0);
  assert.match(result.err, /Patcher profile 26\.3 disagrees with environment variable GAIUS_PROFILE=26\.2/);
  result = driver(["skip", "26.3", "Demo.patchA"], {GAIUS_MINECRAFT_VERSION: "26.2"});
  assert.equal(result.status, 0, "GAIUS_MINECRAFT_VERSION is only a fallback");
  result = driver(["skip", "26.3", "Demo.patchA"], {GAIUS_BRINGUP: "1"}, work);
  assert.notEqual(result.status, 0, "bring-up without a list must fail");
  assert.match(result.err, /Bring-up mode for profile 26\.3 needs port\/tools\/bringup\/26\.3\.txt/);
  // The default list location is port/tools/bringup/<profile>.txt above the working directory.
  const fakeRoot = join(work, "fake-root");
  await mkdir(join(fakeRoot, "port/tools/bringup"), {recursive: true});
  await mkdir(join(fakeRoot, "nested/dir"), {recursive: true});
  await copyFile(list, join(fakeRoot, "port/tools/bringup/26.9.txt"));
  result = driver(["skip", "26.9", "Demo.patchA"], {GAIUS_BRINGUP: "1"}, join(fakeRoot, "nested/dir"));
  assert.equal(result.status, 0, result.err);
  assert.match(result.out, /^QUERY Demo\.patchA true$/m, "list located from the working directory");

  // run(): applied/skip accounting, failures propagate and print the pending list.
  result = driver(["run", "26.3", "Demo.patchA", "Demo.patchOk"],
    {GAIUS_BRINGUP: "1", GAIUS_BRINGUP_LIST: list, GAIUS_PATCH_VERBOSE: "1"});
  assert.equal(result.status, 0, result.err);
  assert.doesNotMatch(result.out, /^BODY Demo\.patchA$/m, "skipped body must not run");
  assert.match(result.out, /^BODY Demo\.patchOk$/m);
  assert.match(result.out, /^PATCH_APPLIED Demo\.patchOk$/m);
  assert.match(result.out, /^PATCH_SUMMARY applied=2 dropped=0 bringupSkipped=1$/m);
  result = driver(["run", "26.3", "Demo.patchOk", "Demo.patchFail"], {GAIUS_BRINGUP_LIST: list});
  assert.equal(result.status, 0, result.err);
  assert.match(result.out, /^RETHROWN boom Demo\.patchFail$/m);
  assert.match(result.out, /^PATCH_SUMMARY applied=2 dropped=0 bringupSkipped=0$/m,
    "a failed body is not counted as applied");
  assert.match(result.err, /Patch Demo\.patchFail failed for profile 26\.3 \(bring-up owner P7a: fails on purpose\)/);
  assert.match(result.err, /^BRINGUP_PENDING Demo\.patchA \| P3 \| reason text$/m);
  assert.match(result.err, /^BRINGUP_PENDING step:LwjglSdlBrowserPatcher \| P2 \| shell step$/m);
  result = driver(["run", "26.3", "Demo.patchA", "Demo.otherFail"],
    {GAIUS_BRINGUP: "1", GAIUS_BRINGUP_LIST: list});
  assert.match(result.out, /^RETHROWN boom Demo\.otherFail$/m);
  assert.match(result.err, /Patch Demo\.otherFail failed in bring-up mode for profile 26\.3 and is not listed in /);
  assert.doesNotMatch(result.err, /BRINGUP_PENDING/);
  result = driver(["run", "26.2", "Demo.patchFail"], {GAIUS_BRINGUP_LIST: list});
  assert.match(result.out, /^RETHROWN boom Demo\.patchFail$/m);
  assert.doesNotMatch(result.err, /BRINGUP_PENDING/, "26.2 never loads a bring-up list");

  // dropped(): targets must be absent; members are checked by name or exact descriptor.
  const tinyJar = join(work, "tiny.jar");
  result = driver(["make-jar", tinyJar]);
  assert.equal(result.status, 0, result.err);
  for (const [targets, expected] of [
    [["a/Gone.class", "a/Gone", "a/B#gone", "a/B#m(J)V", "c/D#m"], "DROPPED_OK"],
    [["a/B.class"], "DROPPED_ERROR .*still present.*a/B\\.class"],
    [["a/B"], "DROPPED_ERROR .*still present.*a/B"],
    [["assets/x.txt"], "DROPPED_ERROR .*assets/x\\.txt"],
    [["a/B#m"], "DROPPED_ERROR .*a/B#m"],
    [["a/B#m(I)V"], "DROPPED_ERROR .*a/B#m\\(I\\)V"],
    [["a/B#field"], "DROPPED_ERROR .*a/B#field"],
    [[], "DROPPED_ERROR .*at least one target"],
  ]) {
    result = driver(["dropped", tinyJar, "Demo.dropped", ...targets]);
    assert.match(result.out, new RegExp("^" + expected, "m"), `dropped ${targets.join(",")}`);
    if (expected === "DROPPED_OK") assert.match(result.out, /^PATCH_DROPPED Demo\.dropped$/m);
    else assert.doesNotMatch(result.out, /PATCH_DROPPED/);
  }

  // Minecraft263BrowserPatcher refuses 26.2/1.21.11 and blaze3d jars, runs the shells on 26.3.
  const m263Root = join(work, "m263-out");
  for (const profile of ["26.2", "1.21.11"]) {
    result = run(["dev.gaius.tools.Minecraft263BrowserPatcher", jar263, m263Root, profile]);
    assert.notEqual(result.status, 0, `M263 ran for ${profile}`);
    assert.match(result.err, new RegExp(`runs only for 26\\.3 and later, not for profile ${profile.replaceAll(".", "\\.")}`));
  }
  result = run(["dev.gaius.tools.Minecraft263BrowserPatcher", jar262, m263Root, "26.3"]);
  assert.notEqual(result.status, 0, "M263 accepted a blaze3d jar");
  assert.match(result.err, /is not a 26\.3\+ client \(renderApi=BLAZE3D/);
  result = run(["dev.gaius.tools.Minecraft263BrowserPatcher", jar263, m263Root]);
  assert.notEqual(result.status, 0, "M263 requires the version argument");
  // The 26.3 domains patch the output of the modern chain; on the vanilla jar the terrain
  // chain check (TerrainPatches263.verifyTerrainChain) must refuse the missing chain hooks.
  result = run(["dev.gaius.tools.Minecraft263BrowserPatcher", jar263, m263Root, "26.3"]);
  assert.notEqual(result.status, 0, "M263 accepted a jar the modern chain never patched");
  assert.match(result.err, /26\.3 terrain chain is incomplete/);

  // Minecraft262BrowserPatcher: optional version argument, modern profiles only.
  result = run(["dev.gaius.tools.Minecraft262BrowserPatcher", jar262, join(work, "m262-out"), "1.21.11"]);
  assert.notEqual(result.status, 0);
  assert.match(result.err, /serves the modern patch set only, not profile 1\.21\.11/);
  result = run(["dev.gaius.tools.Minecraft262BrowserPatcher", jar262]);
  assert.notEqual(result.status, 0);
  assert.match(result.err, /usage: Minecraft262BrowserPatcher INPUT_JAR OUTPUT_ROOT \[MINECRAFT_VERSION\]/);

  // End to end on 26.3 in bring-up mode (needs the committed bring-up list).
  if (existsSync(bringupList263)) {
    const listed = new Set((await readFile(bringupList263, "utf8")).split(/\r?\n/)
      .map((line) => line.replace(/#.*$/, "").trim()).filter(Boolean)
      .map((line) => line.split("|")[0].trim()));
    const stage = join(work, "stage-26.3.jar");
    await copyFile(jar263, stage);
    const env = {GAIUS_BRINGUP: "1", GAIUS_BRINGUP_LIST: bringupList263};
    const skipped = [];
    // The client chain of build-overlays.sh (MinecraftServerWorkerPatcher left out: no M263
    // domain reads its classes). The chunk-draw telemetry step does not use PatchRegistry.
    for (const [tool, args, output] of [
      ["dev.gaius.tools.MinecraftClientPatcher", [stage, join(work, "e2e-mcp"), "26.3"], join(work, "e2e-mcp")],
      ["dev.gaius.tools.MinecraftChunkDrawTelemetryPatcher", ["26.3", stage, join(work, "e2e-cdt")],
        join(work, "e2e-cdt")],
      ["dev.gaius.tools.Minecraft262BrowserPatcher", [stage, join(work, "e2e-m262"), "26.3"], join(work, "e2e-m262")],
      ["dev.gaius.tools.Minecraft263BrowserPatcher", [stage, join(work, "e2e-m263"), "26.3"], join(work, "e2e-m263")],
    ]) {
      result = run([tool, ...args], env);
      assert.equal(result.status, 0, `${tool} failed in bring-up mode:\n${result.err}`);
      if (tool === "dev.gaius.tools.MinecraftChunkDrawTelemetryPatcher") {
        assert.match(result.out, /^CHUNK_DRAW_TELEMETRY_PATCH_OK profile=26\.3 /m);
      } else {
        assert.match(result.out, /^PATCH_SUMMARY applied=\d+ dropped=\d+ bringupSkipped=\d+$/m);
      }
      if (tool === "dev.gaius.tools.Minecraft263BrowserPatcher") {
        const m263Applied = Number(/^PATCH_SUMMARY applied=(\d+) /m.exec(result.out)[1]);
        assert.ok(m263Applied > 0, "the 26.3 domains applied no patch on the chained jar");
        const domainLines = result.out.split(/\r?\n/)
          .filter((line) => /^[A-Za-z]+Patches263: /.test(line)).map((line) => line.split(":")[0]);
        assert.deepEqual(domainLines, ["RenderPatches263", "InputPatches263", "TerrainPatches263",
          "WorldgenPatches263", "ServerPatches263", "UiPatches263", "JdkCompatPatches263"]);
        assert.match(result.out,
          /^Minecraft263BrowserPatcher: profile 26\.3, renderApi=RENDERPEARL .*input=SDL/m);
      }
      assert.doesNotMatch(result.out + result.err, /^(?!BRINGUP_).*\bSkipp(ed|ing)\b/m,
        `${tool}: unregistered skip in the log`);
      skipped.push(...[...result.out.matchAll(/^BRINGUP_SKIP (\S+)$/gm)].map((match) => match[1]));
      if (existsSync(output)) {
        execFileSync(jarTool, ["--update", "--file", stage, "-C", output, "."], {timeout: 120_000});
      }
    }
    for (const id of skipped) assert.ok(listed.has(id), `skip of unlisted id ${id}`);
    console.log(`patch-registry-smoke: 26.3 bring-up chain completed, ${skipped.length} listed skips`);
  } else if (requireBringupList) {
    throw new Error(`missing bring-up list ${bringupList263}`);
  } else {
    console.log("patch-registry-smoke: 26.3 bring-up chain not run (no port/tools/bringup/26.3.txt)");
  }
  console.log(`patch-registry-smoke: OK (MCP ${mcpIds.length} and M262 ${m262Ids.length} wrapped calls)`);
} finally {
  await rm(work, {recursive: true, force: true});
}
