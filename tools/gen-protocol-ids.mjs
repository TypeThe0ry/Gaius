#!/usr/bin/env node
// Derive the RelayNode packet ids from a profile's official client jar and
// compare them with the hand-maintained tables.
//
//   node tools/gen-protocol-ids.mjs --profile 26.3
//   node tools/gen-protocol-ids.mjs --profile 26.3 --packets path/to/packets.json
//   node tools/gen-protocol-ids.mjs --all
//
// Without --packets the vanilla data generator bundled in the client jar is run
// (`net.minecraft.data.Main --reports`) against port/work/<id>/client-original.jar
// after its SHA-1 is checked against port/versions/<id>.json. The generator
// only reads the jar and writes reports into a temporary directory (no server
// is started, so no EULA is involved). port/work/<id> is produced by
// `GAIUS_VERSION_PROFILE_PATH=versions/<id>.json port/scripts/fetch-version.sh`
// (override the directory with --work <dir>).
//
// The reports' protocol_id values are then mapped to the RelayNode keys and
// compared, key by key, with apps/bridge/dist/protocol.js and the
// PLAY/CONFIGURATION tables of port/web/smoke/singleplayer-worker-smoke.js.
// Any mismatch or unmapped key exits non-zero. Never hand-edit a new profile
// by analogy with an older one; regenerate and compare instead.
import {spawnSync} from "node:child_process";
import {createHash} from "node:crypto";
import {existsSync, mkdtempSync, readFileSync, readdirSync, rmSync} from "node:fs";
import {tmpdir} from "node:os";
import {delimiter, dirname, join, resolve} from "node:path";
import {fileURLToPath, pathToFileURL} from "node:url";
import vm from "node:vm";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");

// RelayNode key -> [phase, direction, packet identifier] (reports/packets.json).
const RELAY_KEYS = Object.freeze({
    login: {
        clientboundDisconnect: ["login", "clientbound", "minecraft:login_disconnect"],
        clientboundEncryptionRequest: ["login", "clientbound", "minecraft:hello"],
        clientboundLoginFinished: ["login", "clientbound", "minecraft:login_finished"],
        clientboundCompression: ["login", "clientbound", "minecraft:login_compression"],
        serverboundHello: ["login", "serverbound", "minecraft:hello"],
        serverboundKey: ["login", "serverbound", "minecraft:key"],
        serverboundLoginAcknowledged: ["login", "serverbound", "minecraft:login_acknowledged"],
    },
    configuration: {
        clientboundDisconnect: ["configuration", "clientbound", "minecraft:disconnect"],
        clientboundFinish: ["configuration", "clientbound", "minecraft:finish_configuration"],
        clientboundKeepAlive: ["configuration", "clientbound", "minecraft:keep_alive"],
        clientboundPing: ["configuration", "clientbound", "minecraft:ping"],
        clientboundKnownPacks: ["configuration", "clientbound", "minecraft:select_known_packs"],
        clientboundResourcePackPush: ["configuration", "clientbound", "minecraft:resource_pack_push"],
        clientboundShowDialog: ["configuration", "clientbound", "minecraft:show_dialog"],
        clientboundCodeOfConduct: ["configuration", "clientbound", "minecraft:code_of_conduct"],
        serverboundFinish: ["configuration", "serverbound", "minecraft:finish_configuration"],
        serverboundKeepAlive: ["configuration", "serverbound", "minecraft:keep_alive"],
        serverboundPong: ["configuration", "serverbound", "minecraft:pong"],
        serverboundSelectKnownPacks: ["configuration", "serverbound", "minecraft:select_known_packs"],
        serverboundResourcePack: ["configuration", "serverbound", "minecraft:resource_pack"],
        serverboundCustomClickAction: ["configuration", "serverbound", "minecraft:custom_click_action"],
        serverboundAcceptCodeOfConduct: ["configuration", "serverbound", "minecraft:accept_code_of_conduct"],
    },
    play: {
        clientboundChunkBatchFinished: ["play", "clientbound", "minecraft:chunk_batch_finished"],
        clientboundChunkBatchStart: ["play", "clientbound", "minecraft:chunk_batch_start"],
        clientboundCustomPayload: ["play", "clientbound", "minecraft:custom_payload"],
        clientboundDisconnect: ["play", "clientbound", "minecraft:disconnect"],
        clientboundKeepAlive: ["play", "clientbound", "minecraft:keep_alive"],
        clientboundPing: ["play", "clientbound", "minecraft:ping"],
        clientboundLogin: ["play", "clientbound", "minecraft:login"],
        clientboundChunk: ["play", "clientbound", "minecraft:level_chunk_with_light"],
        clientboundSetChunkCacheCenter: ["play", "clientbound", "minecraft:set_chunk_cache_center"],
        clientboundSetChunkCacheRadius: ["play", "clientbound", "minecraft:set_chunk_cache_radius"],
        clientboundSetSimulationDistance: ["play", "clientbound", "minecraft:set_simulation_distance"],
        clientboundStartConfiguration: ["play", "clientbound", "minecraft:start_configuration"],
        serverboundCustomPayload: ["play", "serverbound", "minecraft:custom_payload"],
        serverboundChunkBatchReceived: ["play", "serverbound", "minecraft:chunk_batch_received"],
        serverboundKeepAlive: ["play", "serverbound", "minecraft:keep_alive"],
        serverboundPong: ["play", "serverbound", "minecraft:pong"],
        serverboundPlayerLoaded: ["play", "serverbound", "minecraft:player_loaded"],
        serverboundClientTickEnd: ["play", "serverbound", "minecraft:client_tick_end"],
        serverboundConfigurationAcknowledged: ["play", "serverbound", "minecraft:configuration_acknowledged"],
    },
});

// port/web/smoke/singleplayer-worker-smoke.js table keys -> identifiers.
const WORKER_SMOKE_KEYS = Object.freeze({
    PLAY_PROTOCOLS: ["play", {
        clientbound: {
            disconnect: "minecraft:disconnect",
            keepAlive: "minecraft:keep_alive",
            levelChunkWithLight: "minecraft:level_chunk_with_light",
            login: "minecraft:login",
            ping: "minecraft:ping",
        },
        serverbound: {
            chunkBatchReceived: "minecraft:chunk_batch_received",
            keepAlive: "minecraft:keep_alive",
            playerLoaded: "minecraft:player_loaded",
            pong: "minecraft:pong",
        },
    }],
    CONFIGURATION_PROTOCOLS: ["configuration", {
        clientbound: {
            disconnect: "minecraft:disconnect",
            finish: "minecraft:finish_configuration",
            keepAlive: "minecraft:keep_alive",
            ping: "minecraft:ping",
            selectKnownPacks: "minecraft:select_known_packs",
        },
        serverbound: {
            clientInformation: "minecraft:client_information",
            finish: "minecraft:finish_configuration",
            keepAlive: "minecraft:keep_alive",
            pong: "minecraft:pong",
            selectKnownPacks: "minecraft:select_known_packs",
        },
    }],
});

function usage(message) {
    if (message) console.error(message);
    console.error("usage: node tools/gen-protocol-ids.mjs (--profile <id> | --all) " +
        "[--packets <packets.json>] [--work <port/work/<id> dir>] [--java <java>]");
    process.exit(2);
}

function option(name) {
    const index = process.argv.indexOf(name);
    if (index < 0) return undefined;
    const value = process.argv[index + 1];
    if (value === undefined || value.startsWith("--")) usage(`${name} requires a value`);
    return value;
}

function loadProfile(id) {
    const path = join(root, "port", "versions", `${id}.json`);
    if (!existsSync(path)) usage(`unknown profile ${id}: ${path} does not exist`);
    return JSON.parse(readFileSync(path, "utf8"));
}

function sha1(path) {
    return createHash("sha1").update(readFileSync(path)).digest("hex");
}

function javaExecutable(profile) {
    const explicit = option("--java") ?? process.env[`GAIUS_JAVA_${profile.javaVersion}`] ??
        process.env.GAIUS_JAVA;
    if (explicit) {
        return existsSync(join(explicit, "bin")) ? join(explicit, "bin", "java") : explicit;
    }
    return process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin", "java") : "java";
}

function libraryClasspath(workDirectory) {
    const listing = join(workDirectory, "classpath.txt");
    if (!existsSync(listing)) {
        throw new Error(`${listing} is missing; run port/scripts/fetch-version.sh for this profile first`);
    }
    return readFileSync(listing, "utf8").trim().split(":")
        .filter((entry) => entry.length > 0 && !entry.includes("natives") &&
            !/client-(?:named|original)\.jar$/u.test(entry))
        .map((entry) => process.platform === "win32"
            ? entry.replace(/^\/([a-zA-Z])\//u, "$1:/")
            : entry);
}

function generatePackets(profile, workDirectory) {
    const jar = join(workDirectory, "client-original.jar");
    if (!existsSync(jar)) throw new Error(`${jar} is missing; run port/scripts/fetch-version.sh for this profile first`);
    const actual = sha1(jar);
    if (actual !== profile.official.clientSha1.toLowerCase()) {
        throw new Error(`${jar} SHA-1 ${actual} != ${profile.official.clientSha1}`);
    }
    const output = mkdtempSync(join(tmpdir(), `gaius-packets-${profile.id}-`));
    try {
        const result = spawnSync(javaExecutable(profile), [
            "-cp", [jar, ...libraryClasspath(workDirectory)].join(delimiter),
            "net.minecraft.data.Main", "--reports", "--output", join(output, "generated"),
        ], {cwd: output, encoding: "utf8", maxBuffer: 64 * 1024 * 1024});
        const packets = join(output, "generated", "reports", "packets.json");
        if (result.status !== 0 || !existsSync(packets)) {
            throw new Error(`data generator failed for ${profile.id} (status ${result.status}):\n` +
                `${result.error ?? ""}${result.stdout.slice(-4000)}${result.stderr.slice(-4000)}`);
        }
        return {report: JSON.parse(readFileSync(packets, "utf8")), jarSha1: actual};
    }
    finally {
        rmSync(output, {recursive: true, force: true});
    }
}

function idOf(report, phase, direction, identifier) {
    const id = report[phase]?.[direction]?.[identifier]?.protocol_id;
    if (!Number.isInteger(id)) throw new Error(`${phase}/${direction}/${identifier} is not registered`);
    return id;
}

const relayModule = await import(pathToFileURL(join(root, "apps/bridge/dist/protocol.js")).href);
const workerSource = readFileSync(join(root, "port/web/smoke/singleplayer-worker-smoke.js"), "utf8");
const workerTables = vm.runInNewContext(`${workerSource.slice(0, workerSource.indexOf("const runButton"))}
    ({PLAY_PROTOCOLS, CONFIGURATION_PROTOCOLS});`, {Object});

function compareProfile(profile, report) {
    const failures = [];
    const derived = {};
    for (const [group, keys] of Object.entries(RELAY_KEYS)) {
        derived[group] = Object.fromEntries(Object.entries(keys)
            .map(([key, [phase, direction, identifier]]) =>
                [key, idOf(report, phase, direction, identifier)]));
    }
    const relay = relayModule.resolveMinecraftProfile(profile.protocolVersion);
    if (relay === undefined) {
        failures.push(`apps/bridge/dist/protocol.js has no profile for ${profile.protocolVersion}`);
    }
    else {
        if (relay.name !== profile.id) failures.push(`protocol ${profile.protocolVersion} resolves to ${relay.name}`);
        for (const group of Object.keys(RELAY_KEYS)) {
            for (const key of new Set([...Object.keys(derived[group]), ...Object.keys(relay[group])])) {
                if (!(key in RELAY_KEYS[group])) failures.push(`${group}.${key} has no identifier mapping`);
                else if (relay[group][key] !== derived[group][key]) {
                    failures.push(`${group}.${key}: protocol.js ${relay[group][key]} != jar ${derived[group][key]}`);
                }
            }
        }
    }
    for (const [table, [phase, directions]] of Object.entries(WORKER_SMOKE_KEYS)) {
        const actual = workerTables[table]?.[profile.protocolVersion];
        if (actual === undefined) {
            failures.push(`singleplayer-worker-smoke.js ${table} has no ${profile.protocolVersion} entry`);
            continue;
        }
        for (const [direction, keys] of Object.entries(directions)) {
            for (const [key, identifier] of Object.entries(keys)) {
                const expected = idOf(report, phase, direction, identifier);
                if (actual[direction]?.[key] !== expected) {
                    failures.push(`singleplayer-worker-smoke.js ${table}[${profile.protocolVersion}]` +
                        `.${direction}.${key}: ${actual[direction]?.[key]} != jar ${expected}`);
                }
            }
        }
    }
    return {derived, failures};
}

const packetsPath = option("--packets");
const requested = option("--profile");
const profiles = process.argv.includes("--all")
    ? readdirSync(join(root, "port", "versions")).filter((name) => name.endsWith(".json"))
        .map((name) => name.slice(0, -".json".length)).sort()
    : requested !== undefined ? [requested] : usage("--profile or --all is required");
if (packetsPath !== undefined && profiles.length !== 1) usage("--packets needs exactly one --profile");

let failed = false;
const summary = [];
for (const id of profiles) {
    const profile = loadProfile(id);
    const {report, jarSha1} = packetsPath !== undefined
        ? {report: JSON.parse(readFileSync(resolve(packetsPath), "utf8")), jarSha1: null}
        : generatePackets(profile, resolve(option("--work") ?? join(root, "port", "work", id)));
    const {derived, failures} = compareProfile(profile, report);
    for (const failure of failures) console.error(`FAIL ${id}: ${failure}`);
    failed ||= failures.length > 0;
    summary.push({profile: id, protocolVersion: profile.protocolVersion, jarSha1, ok: failures.length === 0, ...derived});
}
console.log(JSON.stringify(summary, null, 2));
process.exit(failed ? 1 : 0);
