import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {dirname, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const build = readFileSync(resolve(root, "port/scripts/build-teavm.sh"), "utf8");
const patcher = readFileSync(resolve(
  root,
  "port/tools/src/main/java/dev/gaius/tools/GsonTypeTokenClientPatcher.java",
), "utf8");

const invocationStart = build.indexOf("dev.gaius.tools.GsonTypeTokenClientPatcher");
const invocationEnd = build.indexOf("jar --update", invocationStart);
assert.ok(invocationStart >= 0 && invocationEnd > invocationStart,
  "Gson TypeToken patcher invocation is missing");
const invocation = build.slice(invocationStart, invocationEnd);

assert.ok(build.indexOf('work="$root/port/work/$version"') < invocationStart,
  "profile work root must be resolved before the patcher invocation");
assert.match(invocation, /"\$work\/libraries"/,
  "patcher must receive the profile library root explicitly");
assert.match(patcher, /args\.length != 3/,
  "patcher must reject ambiguous two-argument invocations");
assert.match(patcher, /Path versionLibraries = Path\.of\(args\[2\]\);/,
  "patcher must use the explicit profile library root");
assert.match(patcher, /findJar\(versionLibraries,/,
  "patcher must resolve dependencies from the explicit library root");
assert.doesNotMatch(patcher, /workLibrariesFor|clientJar\.getParent\(\)/,
  "patcher must not infer port/work from an external overlay path");

console.log(JSON.stringify({
  ok: true,
  explicitVersionLibraries: true,
  externalOverlayIndependent: true,
}));
