#!/usr/bin/env node

import {access, readFile} from "node:fs/promises";
import {dirname, resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const configPath = resolve(root, "port/config.json");
const config = JSON.parse(await readFile(configPath, "utf8"));
const profilePath = resolve(root, "port", requiredString(config, "versionProfile"));
const versionsDirectory = resolve(root, "port/versions");

if (profilePath !== versionsDirectory && !profilePath.startsWith(`${versionsDirectory}/`)) {
    fail("port/config.json versionProfile must point inside port/versions");
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
if (process.argv.includes("--remote")) {
    const manifestResponse = await fetch(
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
    if (!manifestResponse.ok) {
        fail(`official version manifest returned HTTP ${manifestResponse.status}`);
    }
    const manifest = await manifestResponse.json();
    const entry = manifest.versions?.find((candidate) => candidate.id === profile.id);
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

console.log(JSON.stringify({
    ok: true,
    profile: profile.id,
    protocolVersion: profile.protocolVersion,
    worldVersion: profile.worldVersion,
    javaVersion: profile.javaVersion,
    classFileVersion: profile.classFileVersion,
    clientDistribution: profile.clientDistribution,
    checkedLocalMetadata,
    checkedClientVersion,
    checkedRemoteMetadata,
}, null, 2));

function validateProfile(value) {
    requiredString(value, "id");
    requiredString(value, "releaseType");
    requiredInteger(value, "protocolVersion");
    requiredInteger(value, "worldVersion");
    requiredInteger(value, "javaVersion");
    requiredInteger(value, "classFileVersion");
    if (!["named", "obfuscated-with-mappings"].includes(value.clientDistribution)) {
        fail("clientDistribution must be named or obfuscated-with-mappings");
    }
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
