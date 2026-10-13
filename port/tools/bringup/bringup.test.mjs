import assert from "node:assert/strict";
import {spawnSync} from "node:child_process";
import {mkdirSync, mkdtempSync, readFileSync, symlinkSync, writeFileSync} from "node:fs";
import {join, resolve} from "node:path";
import test from "node:test";
import {choosePrevious, detectSeam, draftProfile, fingerprintEntries, inheritKernelGate,
    relocationDiff, run, safeOutput, setDiff, usageEntries, fullExitCode, fullStatus, shellPath} from "./gaius-bringup.mjs";

const out = process.env.GAIUS_BRINGUP_TEST_OUT;
if (!out) throw new Error("Set GAIUS_BRINGUP_TEST_OUT to a disposable output directory");
mkdirSync(out, {recursive: true});

test("closest dated profile handles nonnumeric snapshot identifiers", () => {
    const profiles = [{id: "older", official: {releaseTime: "2025-01-01"}},
        {id: "current", bringupAllowed: true, official: {releaseTime: "2025-03-01"}},
        {id: "future", official: {releaseTime: "2025-06-01"}}];
    assert.equal(choosePrevious("snapshot", profiles, "2025-04-01").id, "current");
    assert.equal(choosePrevious("snapshot", [...profiles, {id: "unreviewed", bringup: {provisional: true},
        official: {releaseTime: "2025-03-31"}}], "2025-04-01").id, "current");
    assert.throws(() => choosePrevious("alone", [{id: "alone"}]), /No previous/u);
});

test("SPI detection needs every declared class and exactly one generation", () => {
    const seam = {generation: "adapter-1", fingerprint: {implements: ["api/Device"], extends: [], instantiates: [], watch: ["api/Pipeline"]}};
    assert.equal(detectSeam(["api/Device", "api/Pipeline"], [seam]), "adapter-1");
    assert.throws(() => detectSeam(["api/Device"], [seam]), /found 0/u);
    assert.throws(() => detectSeam(["api/Device", "api/Pipeline"], [seam, seam]), /found 2/u);
});

test("fingerprints preserve type and body sections, ignoring profile headers", () => {
    const hash = "a".repeat(16);
    assert.deepEqual(fingerprintEntries(`profile alias\n[types]\n${hash} api/Device\n[bodies]\n${hash} api/Device.run()V\n`),
        [`[types] ${hash} api/Device`, `[bodies] ${hash} api/Device.run()V`]);
});

test("checked-in SPI rows use truncated hashes, not aggregate header hashes", () => {
    const root = resolve(import.meta.dirname, "../../..");
    const config = JSON.parse(readFileSync(join(root, "port/config.json"), "utf8"));
    const profile = JSON.parse(readFileSync(join(root, "port", config.versionProfile), "utf8"));
    const text = readFileSync(join(root, "port/mc/profiles", profile.id, "spi.fingerprint"), "utf8");
    const entries = fingerprintEntries(text);
    assert.ok(entries.length > 100);
    assert.ok(entries.some((entry) => entry.startsWith("[types]")));
    assert.ok(entries.some((entry) => entry.startsWith("[bodies]")));
    assert.equal(fingerprintEntries("signature " + "b".repeat(64)).length, 0);
});

test("usage comparison binds coordinates and kind, not section counts", () => {
    const before = "profile stable\n[library org.example:lib:1] 2\nC api/Device\nM api/Device.run()V\n";
    const after = "profile alias\n[library org.example:lib:1] 99\nC api/Device\nM api/Device.run()V\n";
    assert.deepEqual(setDiff(usageEntries(before), usageEntries(after)), {added: [], removed: [], unchanged: 2});
    assert.equal(setDiff(usageEntries(before), usageEntries(after.replace(":1]", ":2]"))).added.length, 2);
});

test("class relocation suggestions are not asserted when names are ambiguous", () => {
    const delta = relocationDiff(["old/Device", "old/Shared"], ["new/Device", "one/Shared", "two/Shared"]);
    assert.deepEqual(delta.relocations, [{from: "old/Device", to: "new/Device", confidence: "unique-simple-name-only"}]);
});

test("predecessor default-off propagates transitively to dependent kernels", () => {
    const delta = {kernels: Object.fromEntries(["noise", "mesh", "world"].map((name) => [name, {enabled: true, reasons: []}]))};
    const previous = {kernelMirror: {enabled: {noise: false, mesh: true, world: true}}};
    inheritKernelGate(delta, previous, [{name: "world", dependencies: ["mesh"]},
        {name: "mesh", dependencies: ["noise"]}, {name: "noise", dependencies: []}]);
    assert.ok(Object.values(delta.kernels).every((kernel) => !kernel.enabled));
    assert.deepEqual(delta.kernels.world.reasons, ["DEPENDENCY:mesh"]);
});

test("output is rejected before creating a path under shared inputs", () => {
    const root = mkdtempSync(join(out, "path-test-"));
    const work = join(root, "work");
    mkdirSync(work);
    assert.throws(() => safeOutput(join(work, "new", "report"), work), /Output must not/u);
    assert.equal(safeOutput(join(root, "results"), work), resolve(root, "results"));
});

test("jar-derived draft never inherits a reviewed kernel approval", () => {
    const template = {id: "baseline", renderSeam: "adapter-1", inputSeam: "glfw", storage: {schema: 2},
        kernelMirror: {lockSha256: "a".repeat(64), enabled: {noise: true}}, kernelSemantics: {code: 1}};
    const metadata = {type: "snapshot", releaseTime: "2025-04-01", downloads: {client: {sha1: "b".repeat(40)},
        server: {sha1: "c".repeat(40)}}, assetIndex: {id: "assets", sha1: "d".repeat(40)}, libraries: [{name: "org.example:lib:1"}]};
    const data = {maxMajor: 65, javaRelease: 21, version: {protocol_version: 100, world_version: 200,
        pack_version: {resource_major: 1, resource_minor: 0, data_major: 2, data_minor: 0}}};
    const draft = draftProfile("new-snapshot", data, metadata, template);
    assert.equal(draft.protocolVersion, 100);
    assert.equal(draft.storage.databaseName, "gaius-fs-v2-new-snapshot");
    assert.equal(draft.kernelMirror.enabled.noise, false);
    assert.equal(draft.bringup.provisional, true);
    assert.equal(template.kernelMirror.enabled.noise, true);
});

test("a child library junction does not permit output under the input path", () => {
    const root = mkdtempSync(join(out, "junction-test-"));
    const work = join(root, "work");
    const cache = join(root, "cache");
    mkdirSync(work);
    mkdirSync(cache);
    symlinkSync(cache, join(work, "libraries"), "junction");
    assert.throws(() => safeOutput(join(work, "libraries/report"), work), /Output must not/u);
});

test("missing jars still produce an explicit blocking report", () => {
    const root = mkdtempSync(join(out, "missing-test-"));
    mkdirSync(join(root, "port/versions"), {recursive: true});
    mkdirSync(join(root, "port/work"), {recursive: true});
    writeFileSync(join(root, "port/versions/base.json"), JSON.stringify({id: "base"}));
    const reportOut = join(root, "report");
    const report = run({id: "snapshot", mode: "fast", root, work: join(root, "port/work"), out: reportOut, from: "base", draftOnly: false});
    assert.equal(report.exitCode, 2);
    assert.equal(report.noBlockingItems, false);
    assert.equal(JSON.parse(readFileSync(join(reportOut, "bringup-report.json"))).claims.teavm, false);
});

test("full report status rules distinguish skipped and required failures", () => {
    assert.deepEqual(fullStatus(new Error("tool not found"), false), {status: "SKIPPED", required: false});
    assert.deepEqual(fullStatus(new Error("tool not found"), true), {status: "FAIL", required: true});
    assert.equal(fullExitCode([{status: "SKIPPED", required: false}]), 0);
    assert.equal(fullExitCode([{status: "FAIL", required: false}]), 0);
    assert.equal(fullExitCode([{status: "FAIL", required: true}]), 2);
    assert.equal(fullExitCode([{status: "PASS", required: true}, {status: "SKIPPED", required: false}]), 0);
});

test("shader dump paths use portable separators on Windows", () => {
    const value = shellPath("D:\\scratch\\shader-target");
    assert.equal(value.includes("\\"), process.platform !== "win32");
    assert.match(value, /shader-target$/u);
});

test("snapshot bootstrap refuses local execution before inspecting inputs", () => {
    const result = spawnSync(process.execPath, [join(import.meta.dirname, "snapshot-profile.mjs"), out], {
        encoding: "utf8", env: {...process.env, GITHUB_ACTIONS: "false"},
    });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /Snapshot bootstrap is CI-only/u);
});
