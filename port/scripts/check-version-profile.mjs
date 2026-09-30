#!/usr/bin/env node

import {access, readdir, readFile} from "node:fs/promises";
import {dirname, isAbsolute, relative, resolve, sep} from "node:path";
import {fileURLToPath} from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");

// build-overlays.sh dispatches the client patcher chain on patchSet and runs
// extraPatchers (dev.gaius.tools.<name>) in order at the tail of that chain.
// Keep these values in sync with the dispatch in build-overlays.sh.
const PATCH_SETS = {
    "modern": "named",
    "legacy-12111": "obfuscated-with-mappings",
};

// port/tools/bringup/<id>.txt lists the patches a GAIUS_BRINGUP=1 build may
// skip. Lines are "<patchId> | <owner package> | <reason>"; '#' starts a
// comment. Bring-up mode never applies to the release profiles, so they must
// not carry entries.
const BRINGUP_FORBIDDEN_PROFILES = new Set(["26.2", "1.21.11"]);
const configPath = resolve(root, "port/config.json");
const config = JSON.parse(await readFile(configPath, "utf8"));
const versionsDirectory = resolve(root, "port/versions");
const allProfiles = process.argv.includes("--all");
const profileOptionIndex = process.argv.indexOf("--profile");
if (allProfiles && profileOptionIndex >= 0) {
    fail("--all and --profile cannot be combined");
}
if (profileOptionIndex >= 0 &&
        (!process.argv[profileOptionIndex + 1] || process.argv[profileOptionIndex + 1].startsWith("--"))) {
    fail("--profile requires a version id or profile path");
}
const selectedProfile = profileOptionIndex >= 0
    ? process.argv[profileOptionIndex + 1]
    : (process.env.GAIUS_VERSION_PROFILE_PATH || requiredString(config, "versionProfile"));
const configuredProfilePath = resolveProfilePath(selectedProfile);

let profilePaths;
if (allProfiles) {
    profilePaths = (await readdir(versionsDirectory, {withFileTypes: true}))
        .filter((entry) => entry.isFile() && entry.name.endsWith(".json"))
        .map((entry) => resolve(versionsDirectory, entry.name))
        .sort();
    if (profilePaths.length === 0) {
        fail("port/versions contains no JSON profiles");
    }
} else {
    profilePaths = [configuredProfilePath];
}

function resolveProfilePath(value) {
    let normalized = nativePath(String(value).trim().replaceAll("\\", "/"));
    if (/^\d+(?:\.\d+)+$/u.test(normalized)) {
        normalized = `versions/${normalized}.json`;
    }
    const path = isAbsolute(normalized)
        ? resolve(normalized)
        : resolve(root, normalized.startsWith("versions/") ? "port" : "", normalized);
    const relativePath = relative(versionsDirectory, path);
    if (relativePath === "" || relativePath.startsWith(`..${sep}`) ||
            relativePath === ".." || isAbsolute(relativePath) || !path.endsWith(".json")) {
        fail(`version profile must point to a JSON file inside port/versions: ${path}`);
    }
    return path;
}

function nativePath(value) {
    if (process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/u.test(value)) {
        return `${value[1].toUpperCase()}:${value.slice(2)}`;
    }
    return value;
}

const results = [];
let remoteManifest;
if (process.argv.includes("--remote")) {
    const manifestResponse = await fetch(
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
    if (!manifestResponse.ok) {
        fail(`official version manifest returned HTTP ${manifestResponse.status}`);
    }
    remoteManifest = await manifestResponse.json();
}

for (const profilePath of profilePaths) {
    results.push(await checkProfile(profilePath, remoteManifest));
}

if (allProfiles) {
    validateProfileUniqueness(results);
}

if (allProfiles) {
    console.log(JSON.stringify({ok: true, profiles: results}, null, 2));
} else {
    console.log(JSON.stringify({ok: true, ...results[0]}, null, 2));
}

async function checkProfile(profilePath, remoteManifest) {

if (profilePath !== versionsDirectory && !profilePath.startsWith(versionsDirectory + sep)) {
    fail(`version profile must point inside port/versions: ${profilePath}`);
}

const profile = JSON.parse(await readFile(profilePath, "utf8"));
validateProfile(profile);

const work = resolve(root, "port/work", profile.id);
const localMetadataPath = resolve(work, "version.json");
const localClientVersionPath = resolve(work, "client-version.json");
let checkedLocalMetadata = false;
let checkedClientVersion = false;

if (await exists(localMetadataPath)) {
    validateOfficialMetadata(
        JSON.parse(await readFile(localMetadataPath, "utf8")), profile, "local version metadata");
    checkedLocalMetadata = true;
}
if (await exists(localClientVersionPath)) {
    validateClientVersion(
        JSON.parse(await readFile(localClientVersionPath, "utf8")), profile);
    checkedClientVersion = true;
}

let checkedRemoteMetadata = false;
if (remoteManifest) {
    const entry = remoteManifest.versions?.find((candidate) => candidate.id === profile.id);
    if (!entry) fail(`official version manifest does not contain ${profile.id}`);
    if (entry.type !== profile.releaseType) {
        fail(`official manifest type ${entry.type} does not match ${profile.releaseType}`);
    }
    const metadataResponse = await fetch(entry.url);
    if (!metadataResponse.ok) {
        fail(`official ${profile.id} metadata returned HTTP ${metadataResponse.status}`);
    }
    validateOfficialMetadata(await metadataResponse.json(), profile, "official version metadata");
    checkedRemoteMetadata = true;
}

if (process.argv.includes("--require-local") &&
        (!checkedLocalMetadata || !checkedClientVersion)) {
    fail(`local ${profile.id} metadata is incomplete; run fetch-version.sh first`);
}

const bringup = await validateBringupList(profile.id);

return {
    profile: profile.id,
    protocolVersion: profile.protocolVersion,
    worldVersion: profile.worldVersion,
    javaVersion: profile.javaVersion,
    classFileVersion: profile.classFileVersion,
    clientDistribution: profile.clientDistribution,
    worldgenTelemetryMode: profile.worldgenTelemetryMode,
    patchSet: profile.patchSet,
    extraPatchers: profile.extraPatchers,
    bringup,
    packVersions: profile.packVersions,
    assetIndex: {id: profile.official.assetIndexId, sha1: profile.official.assetIndexSha1},
    storage: profile.storage,
    checkedLocalMetadata,
    checkedClientVersion,
    checkedRemoteMetadata,
};
}

function validateProfile(value) {
    requiredString(value, "id");
    requiredString(value, "releaseType");
    requiredInteger(value, "protocolVersion");
    requiredInteger(value, "worldVersion");
    requiredInteger(value, "javaVersion");
    requiredInteger(value, "classFileVersion");
    validateWorldgenTelemetryMode(value);
    validateStorage(value);
    if (!["named", "obfuscated-with-mappings"].includes(value.clientDistribution)) {
        fail("clientDistribution must be named or obfuscated-with-mappings");
    }
    validatePatchSet(value);
    for (const pack of ["resource", "data"]) {
        requiredInteger(value.packVersions?.[pack], "major", `packVersions.${pack}`);
        requiredInteger(value.packVersions?.[pack], "minor", `packVersions.${pack}`);
    }
    requiredString(value.official, "releaseTime", "official");
    requiredSha1(value.official, "clientSha1");
    requiredSha1(value.official, "serverSha1");
    requiredString(value.official, "assetIndexId", "official");
    requiredSha1(value.official, "assetIndexSha1");
    if (value.clientDistribution === "obfuscated-with-mappings") {
        requiredSha1(value.official, "clientMappingsSha1");
    } else if (value.official.clientMappingsSha1 !== null) {
        fail("official.clientMappingsSha1 must be null for a named client");
    }
}

function validatePatchSet(profile) {
    const id = profile?.id ?? "<unknown>";
    const patchSet = profile?.patchSet;
    if (typeof patchSet !== "string" || !Object.hasOwn(PATCH_SETS, patchSet)) {
        fail(`profile ${id}.patchSet must be one of ${Object.keys(PATCH_SETS).join(", ")} ` +
            `(received ${JSON.stringify(patchSet)})`);
    }
    if (profile.clientDistribution !== PATCH_SETS[patchSet]) {
        fail(`profile ${id}.patchSet ${patchSet} requires clientDistribution ` +
            `${PATCH_SETS[patchSet]} (received ${JSON.stringify(profile.clientDistribution)})`);
    }
    const extraPatchers = profile.extraPatchers;
    if (!Array.isArray(extraPatchers)) {
        fail(`profile ${id}.extraPatchers must be an array of dev.gaius.tools class names`);
    }
    const seen = new Set();
    for (const name of extraPatchers) {
        if (typeof name !== "string" || !/^[A-Z][A-Za-z0-9_]*$/u.test(name)) {
            fail(`profile ${id}.extraPatchers entries must be simple Java class names ` +
                `(received ${JSON.stringify(name)})`);
        }
        if (seen.has(name)) {
            fail(`profile ${id}.extraPatchers lists ${name} twice`);
        }
        seen.add(name);
    }
    if (patchSet !== "modern" && extraPatchers.length > 0) {
        fail(`profile ${id}.extraPatchers is only supported by the modern patch set`);
    }
}

async function validateBringupList(id) {
    const path = resolve(root, "port/tools/bringup", `${id}.txt`);
    if (!(await exists(path))) {
        return {list: null, entries: 0};
    }
    const entries = parseBringupList(await readFile(path, "utf8"), path);
    if (BRINGUP_FORBIDDEN_PROFILES.has(id) && entries.length > 0) {
        fail(`profile ${id} must not have bring-up entries (${relative(root, path)})`);
    }
    return {list: relative(root, path).replaceAll("\\", "/"), entries: entries.length};
}

function parseBringupList(text, path) {
    const entries = [];
    const ids = new Set();
    text.split(/\r?\n/u).forEach((rawLine, index) => {
        const line = rawLine.replace(/#.*$/u, "").trim();
        if (line === "") return;
        const fields = line.split("|").map((field) => field.trim());
        const where = `${relative(root, path)}:${index + 1}`;
        if (fields.length !== 3) {
            fail(`${where} must be "<patchId> | <owner> | <reason>"`);
        }
        const [patchId, owner, reason] = fields;
        if (!/^[A-Za-z0-9_$.:@-]+$/u.test(patchId)) {
            fail(`${where} has an invalid patch id ${JSON.stringify(patchId)}`);
        }
        if (!/^P[1-9][a-z]?$/u.test(owner)) {
            fail(`${where} owner must be a work package P1..P9 (received ${JSON.stringify(owner)})`);
        }
        if (reason === "") {
            fail(`${where} must give a reason`);
        }
        if (ids.has(patchId)) {
            fail(`${where} lists ${patchId} twice`);
        }
        ids.add(patchId);
        entries.push({patchId, owner, reason});
    });
    return entries;
}

function validateWorldgenTelemetryMode(profile) {
    const mode = profile?.worldgenTelemetryMode;
    if (mode !== "task-pulsed" && mode !== "checkpoint-only") {
        fail(`profile ${profile?.id ?? "<unknown>"}.worldgenTelemetryMode must be "task-pulsed" or "checkpoint-only"`);
    }
}

function validateStorage(profile) {
    const storage = profile?.storage;
    if (storage === null || typeof storage !== "object" || Array.isArray(storage)) {
        fail(`profile ${profile?.id ?? "<unknown>"}.storage must be an object`);
    }
    const schema = storage.schema;
    const id = requiredString(profile, "id");
    if (schema !== 2) {
        fail(`profile ${id}.storage.schema must be exactly 2 (received ${JSON.stringify(schema)})`);
    }
    const expected = {
        databaseName: `gaius-fs-v2-${id}`,
        prefix: `gaius.fs.v2:${id}:`,
        opfsDirectory: `regions-v2-${id}`,
    };
    for (const [key, expectedValue] of Object.entries(expected)) {
        const field = requiredString(storage, key, "profile.storage");
        if (field !== expectedValue) {
            fail(`profile ${id}.storage.${key} must be exactly ${JSON.stringify(expectedValue)} ` +
                `(received ${JSON.stringify(field)})`);
        }
    }
}

function validateProfileUniqueness(profiles) {
    for (const field of ["profile", "storage.databaseName", "storage.prefix", "storage.opfsDirectory"]) {
        const seen = new Set();
        for (const result of profiles) {
            const value = field === "profile"
                ? result.profile
                : result.storage[field.slice("storage.".length)];
            if (seen.has(value)) {
                fail(`all version profiles must have unique ${field}`);
            }
            seen.add(value);
        }
    }
}

function validateOfficialMetadata(metadata, expected, label) {
    equal(metadata.id, expected.id, `${label} id`);
    equal(metadata.type, expected.releaseType, `${label} type`);
    equal(metadata.releaseTime, expected.official.releaseTime, `${label} releaseTime`);
    equal(metadata.downloads?.client?.sha1, expected.official.clientSha1, `${label} client SHA-1`);
    equal(metadata.downloads?.server?.sha1, expected.official.serverSha1, `${label} server SHA-1`);
    equal(String(metadata.assetIndex?.id), expected.official.assetIndexId,
        `${label} asset index id`);
    equal(metadata.assetIndex?.sha1, expected.official.assetIndexSha1,
        `${label} asset index SHA-1`);
    equal(metadata.javaVersion?.majorVersion, expected.javaVersion, `${label} Java version`);

    const mappingsSha1 = metadata.downloads?.client_mappings?.sha1 ?? null;
    equal(mappingsSha1, expected.official.clientMappingsSha1,
        `${label} client mappings SHA-1`);
}

function validateClientVersion(metadata, expected) {
    equal(metadata.id, expected.id, "client version id");
    equal(metadata.protocol_version, expected.protocolVersion, "client protocol version");
    equal(metadata.world_version, expected.worldVersion, "client world version");
    equal(metadata.java_version, expected.javaVersion, "client Java version");
    equal(metadata.pack_version?.resource_major,
        expected.packVersions.resource.major, "client resource pack major version");
    equal(metadata.pack_version?.resource_minor,
        expected.packVersions.resource.minor, "client resource pack minor version");
    equal(metadata.pack_version?.data_major,
        expected.packVersions.data.major, "client data pack major version");
    equal(metadata.pack_version?.data_minor,
        expected.packVersions.data.minor, "client data pack minor version");
}

function requiredString(value, key, prefix = "profile") {
    const result = value?.[key];
    if (typeof result !== "string" || result.length === 0) {
        fail(`${prefix}.${key} must be a non-empty string`);
    }
    return result;
}

function requiredInteger(value, key, prefix = "profile") {
    const result = value?.[key];
    if (!Number.isInteger(result) || result < 0) {
        fail(`${prefix}.${key} must be a non-negative integer`);
    }
    return result;
}

function requiredSha1(value, key) {
    const result = value?.[key];
    if (typeof result !== "string" || !/^[0-9a-f]{40}$/u.test(result)) {
        fail(`official.${key} must be a lowercase SHA-1`);
    }
}

function equal(actual, expected, label) {
    if (actual !== expected) {
        fail(`${label} ${JSON.stringify(actual)} does not match ${JSON.stringify(expected)}`);
    }
}

async function exists(path) {
    try {
        await access(path);
        return true;
    } catch {
        return false;
    }
}

function fail(message) {
    console.error(`Version profile check failed: ${message}`);
    process.exit(1);
}
