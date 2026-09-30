#!/usr/bin/env node
// G4 gate: a build-overlays.sh log must not contain silent skips.
//
// Every patch invocation has exactly three allowed outcomes: applied,
// registered as dropped (PatchRegistry prints "PATCH_DROPPED <id>" after
// asserting that the old target is really absent), or an exception.  In a
// GAIUS_BRINGUP=1 build of an unfinished profile a patch may also be skipped,
// which prints "BRINGUP_SKIP <id>", but only when <id> is listed in
// port/tools/bringup/<profile>.txt.
//
//   node port/scripts/check-build-log-skips.mjs --profile 26.3 build.log
//   node port/scripts/check-build-log-skips.mjs --profile 26.2 build.log
//
// Checks:
//   - any other line containing "Skipped"/"Skipping" is an unregistered skip;
//   - BRINGUP_SKIP ids must be listed, and bring-up skips are refused for the
//     release profiles (26.2, 1.21.11) and for logs of non-bring-up builds;
//   - PATCH_SUMMARY / BRINGUP_STEPS counters must agree with the lines seen.
// Options:
//   --allow <regex>   exempt matching lines from the unregistered-skip rule
//   --strict-list     also fail when a listed bring-up id was never skipped
//                     (default: reported as a warning)
// Exit status: 0 pass, 1 gate failure, 2 usage error.

import {existsSync, readFileSync} from "node:fs";
import {basename, dirname, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const RELEASE_PROFILES = new Set(["26.2", "1.21.11"]);

function usage(message) {
  console.error(`check-build-log-skips: ${message}`);
  process.exit(2);
}

let profile = process.env.GAIUS_MINECRAFT_VERSION || "";
let strictList = false;
const allow = [];
const logs = [];
for (let index = 2; index < process.argv.length; index++) {
  const argument = process.argv[index];
  if (argument === "--profile") {
    profile = process.argv[++index] ?? usage("--profile needs a value");
  } else if (argument === "--allow") {
    allow.push(new RegExp(process.argv[++index] ?? usage("--allow needs a value")));
  } else if (argument === "--strict-list") {
    strictList = true;
  } else if (argument.startsWith("--")) {
    usage(`unknown option ${argument}`);
  } else {
    logs.push(argument);
  }
}
if (!profile && process.env.GAIUS_VERSION_PROFILE_PATH) {
  profile = basename(process.env.GAIUS_VERSION_PROFILE_PATH).replace(/\.json$/, "");
}
if (!/^\d+(?:\.\d+)+$/.test(profile)) usage("--profile <id> is required");
if (logs.length === 0) usage("pass at least one build log");

function readBringupList(id) {
  const path = resolve(root, "port/tools/bringup", `${id}.txt`);
  const entries = new Map();
  if (!existsSync(path)) return entries;
  readFileSync(path, "utf8").split(/\r?\n/).forEach((rawLine, index) => {
    const line = rawLine.replace(/#.*$/, "").trim();
    if (!line) return;
    const fields = line.split("|").map((field) => field.trim());
    if (fields.length !== 3 || !/^[A-Za-z0-9_$][A-Za-z0-9_$.:@-]*$/.test(fields[0])
        || !/^P[1-9][a-z]?$/.test(fields[1]) || !fields[2]) {
      usage(`port/tools/bringup/${id}.txt:${index + 1} must be "<patchId> | <owner> | <reason>"`);
    }
    if (entries.has(fields[0])) usage(`port/tools/bringup/${id}.txt lists ${fields[0]} twice`);
    entries.set(fields[0], {owner: fields[1], reason: fields[2]});
  });
  return entries;
}

const releaseProfile = RELEASE_PROFILES.has(profile);
const listed = readBringupList(profile);
const errors = [];
const warnings = [];
const skipped = new Map();
const dropped = new Map();
let summaryBringup = 0;
let summaryDropped = 0;
let summaries = 0;
let bringupBuild = false;

for (const log of logs) {
  if (!existsSync(log)) usage(`log does not exist: ${log}`);
  const lines = readFileSync(log, "utf8").split(/\r?\n/);
  lines.forEach((line, index) => {
    const where = `${basename(log)}:${index + 1}`;
    if (/^Bring-up mode for Minecraft /.test(line)) {
      bringupBuild = true;
      return;
    }
    let match = /^BRINGUP_SKIP (\S+)\s*$/.exec(line);
    if (match) {
      skipped.set(match[1], (skipped.get(match[1]) ?? 0) + 1);
      if (releaseProfile) {
        errors.push(`${where}: release profile ${profile} skipped ${match[1]} in bring-up mode`);
      } else if (!listed.has(match[1])) {
        errors.push(`${where}: ${match[1]} was skipped but is not listed in port/tools/bringup/${profile}.txt`);
      }
      return;
    }
    match = /^PATCH_DROPPED (\S+)\s*$/.exec(line);
    if (match) {
      dropped.set(match[1], (dropped.get(match[1]) ?? 0) + 1);
      return;
    }
    match = /^PATCH_SUMMARY applied=(\d+) dropped=(\d+) bringupSkipped=(\d+)\s*$/.exec(line);
    if (match) {
      summaries++;
      summaryDropped += Number(match[2]);
      summaryBringup += Number(match[3]);
      return;
    }
    match = /^BRINGUP_STEPS skipped=(\d+)\s*$/.exec(line);
    if (match) {
      summaries++;
      summaryBringup += Number(match[1]);
      return;
    }
    if (/\b(?:skipped|skipping)\b/i.test(line) && !allow.some((pattern) => pattern.test(line))) {
      errors.push(`${where}: unregistered skip: ${line.trim()}`);
    }
  });
}

const skipCount = [...skipped.values()].reduce((sum, count) => sum + count, 0);
const dropCount = [...dropped.values()].reduce((sum, count) => sum + count, 0);
if (skipCount > 0 && !bringupBuild) {
  errors.push(`${skipCount} bring-up skip(s) in a log without "Bring-up mode for Minecraft ${profile}"`);
}
if (summaries > 0 && summaryBringup !== skipCount) {
  errors.push(`summaries count ${summaryBringup} bring-up skips but the log has ${skipCount} BRINGUP_SKIP lines`);
}
if (summaries > 0 && summaryDropped !== dropCount) {
  errors.push(`summaries count ${summaryDropped} dropped patches but the log has ${dropCount} PATCH_DROPPED lines`);
}
if (bringupBuild) {
  const stale = [...listed.keys()].filter((id) => !skipped.has(id));
  for (const id of stale) {
    const message = `listed bring-up id ${id} (${listed.get(id).owner}) was never skipped; ` +
      "remove it if the patch now applies, or fix the id";
    (strictList ? errors : warnings).push(message);
  }
}

for (const warning of warnings) console.log(`WARNING ${warning}`);
for (const error of errors) console.log(`ERROR ${error}`);
const owners = {};
for (const id of skipped.keys()) {
  const owner = listed.get(id)?.owner ?? "unlisted";
  owners[owner] = (owners[owner] ?? 0) + 1;
}
console.log(`BUILD_LOG_SKIPS profile=${profile} bringupBuild=${bringupBuild} ` +
  `bringupSkipped=${skipped.size} dropped=${dropped.size} listed=${listed.size} ` +
  `owners=${Object.entries(owners).sort().map(([owner, count]) => `${owner}:${count}`).join(",") || "-"} ` +
  `errors=${errors.length} warnings=${warnings.length}`);
console.log(`BUILD_LOG_SKIPS_RESULT ${errors.length === 0 ? "PASS" : "FAIL"}`);
process.exit(errors.length === 0 ? 0 : 1);
