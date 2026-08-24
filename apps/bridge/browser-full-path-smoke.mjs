/*
 * Browser transport -> RelayNode -> vanilla server acceptance smoke.
 *
 * This deliberately does not use the TeaVM output or a direct TCP socket. It
 * evaluates the same BrowserWebSocketChannel JSBody that the browser build
 * embeds, supplies a real ws WebSocket implementation, and drives the raw
 * Minecraft protocol through __gaiusNettyBridge. The only non-browser piece
 * is the Node event loop used to poll the JSBody bridge; every relay hop is a
 * real WebSocket binary frame.
 */

import assert from "node:assert/strict";
import {
    constants as cryptoConstants,
    createCipheriv,
    createDecipheriv,
    createHash,
    publicEncrypt,
    randomBytes,
} from "node:crypto";
import { createServer as createHttpServer } from "node:http";
import { createServer as createTcpServer } from "node:net";
import { once } from "node:events";
import { mkdtemp, mkdir, readFile, writeFile, lstat } from "node:fs/promises";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { deflateSync, inflateSync } from "node:zlib";
import { WebSocket as NodeWebSocket } from "./node_modules/ws/wrapper.mjs";
import {
    MINECRAFT_1_21_11,
    MINECRAFT_26_2,
} from "./dist/protocol.js";

const repository = fileURLToPath(new URL("../../", import.meta.url));
const bridgeDirectory = fileURLToPath(new URL(".", import.meta.url));
const channelSourceUrl = new URL(
    "../../port/overrides/libraries/netty-transport/src/main/java/" +
        "io/netty/channel/browser/BrowserWebSocketChannel.java",
    import.meta.url,
);
const origin = process.env.GAIUS_BROWSER_FULL_PATH_ORIGIN ?? "http://127.0.0.1:8781";
const relayToken = process.env.GAIUS_BROWSER_FULL_PATH_TOKEN ?? "browser-full-path-token";
const usernamePrefix = process.env.GAIUS_BROWSER_FULL_PATH_USERNAME_PREFIX ?? "GaiusBrowser";
const externalRelayUrl = process.env.GAIUS_EXTERNAL_RELAY_URL?.trim() || undefined;
const externalMode = externalRelayUrl !== undefined;
const externalTarget = externalMode
    ? parseTarget(process.env.GAIUS_EXTERNAL_TARGET ?? "ellan.top:16888")
    : undefined;
const acceptServerPrompts = externalMode ||
    process.env.GAIUS_BROWSER_FULL_PATH_ACCEPT_SERVER_PROMPTS === "1" ||
    process.env.GAIUS_BROWSER_FULL_PATH_ACCEPT_DIALOGS === "1";
const requestedDialogAction = process.env.GAIUS_SMOKE_DIALOG_ACTION_ID;
const dialogInputValues = parseDialogInputValues(
    process.env.GAIUS_SMOKE_DIALOG_INPUTS_JSON);
const commandLineArguments = process.argv.slice(2);
const printConfigOnly = commandLineArguments.includes("--print-config");
const printJavaResolutionOnly = commandLineArguments.includes("--print-java-resolution");
if (printConfigOnly && printJavaResolutionOnly) {
    throw new Error("--print-config and --print-java-resolution are mutually exclusive");
}
const malformedAcceptanceArgument = commandLineArguments.find((argument) =>
    argument.startsWith("--acceptance") && argument !== "--acceptance");
if (malformedAcceptanceArgument !== undefined) {
    throw new Error(`Unsupported acceptance argument ${malformedAcceptanceArgument}; use --acceptance`);
}
const acceptanceEnvironment = process.env.GAIUS_BROWSER_FULL_PATH_ACCEPTANCE;
if (acceptanceEnvironment !== undefined && acceptanceEnvironment !== "0" &&
    acceptanceEnvironment !== "1") {
    throw new Error("GAIUS_BROWSER_FULL_PATH_ACCEPTANCE must be exactly 0 or 1");
}
const acceptanceMode = commandLineArguments.includes("--acceptance") ||
    acceptanceEnvironment === "1";
const STRICT_ACCEPTANCE_TARGET = Object.freeze({
    clients: 4,
    minimumChunkPackets: 9,
    soakMillis: 15_000,
    reconnectWaves: 1,
});
const CANONICAL_PROFILES = Object.freeze({
    "26.2": Object.freeze({
        protocolVersion: 776,
        worldVersion: 4903,
        javaVersion: 25,
        serverSha1: "823e2250d24b3ddac457a60c92a6a941943fcd6a",
    }),
    "1.21.11": Object.freeze({
        protocolVersion: 774,
        worldVersion: 4671,
        javaVersion: 21,
        serverSha1: "64bb6d763bed0a9f1d632ec347938594144943ed",
    }),
});
const STRICT_RUNTIME_JAVA_POLICY = Object.freeze({
    "1.21.11": "major-exactly-21",
    "26.2": "major-at-least-25",
});
const RELAY_RUNTIME_GAUGES = Object.freeze([
    "activeLocalTunnelSessions",
    "pendingSyntheticPlayTicks",
    "activeServerFrameDrainHandles",
    "activeServerFrameDrainTimers",
    "activeClientStallTimers",
]);
const RELAY_DRAIN_MAX_DURATION_MILLIS = 16.7;
// These counters are exported by BrowserWebSocketChannel's JSBody stats. Keep
// their exact source names: a missing source field is evidence, not a made-up
// zero, and is handled explicitly by browserRuntimeCleanupGaugeEvidence().
const BROWSER_RUNTIME_CLEANUP_GAUGES = Object.freeze([
    "activeHighWatermarks",
    "decodedSliceBacklog",
    "decoderCumulationBytes",
    "decodedPacketQueue",
]);

function parseStrictAcceptanceNumber(name, expected) {
    const raw = process.env[name];
    if (raw === undefined) return expected;
    if (!/^(?:0|[1-9][0-9]*)$/u.test(raw) || raw !== String(expected)) {
        throw new Error(`${name} must be exactly ${expected} in strict acceptance mode`);
    }
    return expected;
}

const requestedClients = Number.parseInt(
    acceptanceMode
        ? parseStrictAcceptanceNumber("GAIUS_BROWSER_FULL_PATH_CLIENTS",
            STRICT_ACCEPTANCE_TARGET.clients)
        : process.env.GAIUS_BROWSER_FULL_PATH_CLIENTS ?? "2", 10);
const clientCount = Number.isInteger(requestedClients)
    ? acceptanceMode ? requestedClients : Math.max(1, Math.min(4, requestedClients))
    : 2;
const soakMs = acceptanceMode
    ? parseStrictAcceptanceNumber("GAIUS_BROWSER_FULL_PATH_SOAK_MS",
        STRICT_ACCEPTANCE_TARGET.soakMillis)
    : Math.max(0, Number.parseInt(
        process.env.GAIUS_BROWSER_FULL_PATH_SOAK_MS ?? "1000", 10) || 0);
const requestedMinimumChunkPackets = Number.parseInt(
    acceptanceMode
        ? parseStrictAcceptanceNumber("GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS",
            STRICT_ACCEPTANCE_TARGET.minimumChunkPackets)
        : process.env.GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS ?? "9", 10);
const minimumChunkPackets = Number.isInteger(requestedMinimumChunkPackets)
    ? acceptanceMode ? requestedMinimumChunkPackets :
        Math.max(1, Math.min(128, requestedMinimumChunkPackets))
    : 9;
const requestedReconnectWaves = Number.parseInt(
    acceptanceMode
        ? parseStrictAcceptanceNumber("GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES",
            STRICT_ACCEPTANCE_TARGET.reconnectWaves)
        : process.env.GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES ?? "0", 10);
const reconnectWaves = Number.isInteger(requestedReconnectWaves)
    ? acceptanceMode ? requestedReconnectWaves :
        Math.max(0, Math.min(8, requestedReconnectWaves))
    : 0;
const requestedClientStartDelayMs = Number.parseInt(
    process.env.GAIUS_BROWSER_FULL_PATH_CLIENT_START_DELAY_MS ??
        (externalMode ? "1500" : "0"), 10);
const clientStartDelayMs = Number.isInteger(requestedClientStartDelayMs)
    ? Math.max(0, Math.min(30_000, requestedClientStartDelayMs))
    : externalMode ? 1500 : 0;
const smokeStartedAt = performance.now();

let activeProfile;

async function runSmoke() {
    activeProfile = await loadActiveVersionProfile();
    assertCanonicalProfile(activeProfile);
    const wireProfile = resolveWireProfile(activeProfile);
    const verifiedServerJar = externalMode
        ? undefined
        : await resolveVerifiedServerJar(activeProfile);
    const serverJar = verifiedServerJar?.path;
    const evidenceRoot = path.join(repository, "port", "target", activeProfile.id,
        "browser-relay-full-path-evidence");
    await mkdir(evidenceRoot, { recursive: true });
    const workDirectory = await mkdtemp(path.join(evidenceRoot, "run-"));
    const sessionState = {
        publicKeyRequests: 0,
        joins: [],
        hasJoined: [],
        expectedProfiles: new Map(Array.from({ length: clientCount }, (_, index) => [
            `${relayToken}-${index + 1}`,
            `0000000000004000800000000000000${index + 2}`,
        ])),
    };
    const sessionServer = createSessionServer(sessionState);
    let sessionPort;
    let serverProcess;
    let relayProcess;
    let serverOutput = "";
    let relayOutput = "";
    let relayPort;
    let minecraftPort;
    let runtimeJava;
    let relayEndpoint;
    let readRelayRuntime;
    let waitRelayRuntime;
    let browserRuntime;
    let relayRuntimeBaseline;
    let relayRuntimeAtChunks;
    let relayRuntimeBeforeSoak;
    let relayRuntimeAfterSoak;
    let relayRuntimeAfterClose;
    let browserRuntimeAfterSoak;
    let soakHealth;
    let soakStartedAt;
    let soakCompletedAt;
    let failure;
    let serverSpawnError;
    let relaySpawnError;

try {
    await listen(sessionServer, 0, "127.0.0.1");
    sessionPort = sessionServer.address().port;
    const sessionBaseUrl = `http://127.0.0.1:${sessionPort}`;
    if (externalMode) {
        relayEndpoint = {
            url: externalRelayUrl,
            target: externalTarget,
            external: true,
        };
        readRelayRuntime = () => fetchExternalRelayRuntime(
            relayEndpoint.url, relayEndpoint.target, relayToken);
        waitRelayRuntime = (predicate, label, timeoutMillis) =>
            waitForRelayRuntimeReader(readRelayRuntime, predicate, label, timeoutMillis);
    }
    else {
        await writeFile(path.join(workDirectory, "eula.txt"), "eula=true\n");

        // The port is allocated before writing server.properties so the vanilla
        // child never races a later listener. Keep the generated world/evidence
        // directory; this smoke is intentionally post-mortem friendly.
        minecraftPort = await reservePort();
        await writeFile(path.join(workDirectory, "server.properties"),
            serverProperties(minecraftPort));
        runtimeJava = await resolveJavaExecutable(activeProfile);
        const javaExecutable = runtimeJava.executable;
        serverProcess = spawn(javaExecutable, [
            `-Dminecraft.api.session.host=${sessionBaseUrl}`,
            `-Dminecraft.api.services.host=${sessionBaseUrl}`,
            `-Dminecraft.api.profiles.host=${sessionBaseUrl}`,
            "-Xms512m",
            "-Xmx1536m",
            "-jar",
            serverJar,
            "nogui",
        ], {
            cwd: workDirectory,
            stdio: ["pipe", "pipe", "pipe"],
            windowsHide: true,
        });
        serverProcess.once("error", (error) => {
            serverSpawnError = error;
            serverOutput += `\nJava process error: ${error.stack || error}\n`;
        });
        for (const stream of [serverProcess.stdout, serverProcess.stderr]) {
            stream.setEncoding("utf8");
            stream.on("data", (chunk) => { serverOutput += chunk; });
        }
        await waitFor(() => serverOutput.includes("Done (") ||
            serverProcess.exitCode !== null || serverSpawnError !== undefined,
        "vanilla server startup", 180000, () => serverOutput.slice(-4000));
        if (serverSpawnError !== undefined || serverProcess.exitCode !== null ||
            !serverOutput.includes("Done (")) {
            throw new Error("Vanilla server failed to start:\n" + serverOutput);
        }

        relayPort = await reservePort();
        relayProcess = spawn(process.execPath, ["dist/main.js"], {
            cwd: bridgeDirectory,
            env: {
                ...process.env,
                NODE_ENV: "test",
                GAIUS_BRIDGE_HOST: "127.0.0.1",
                GAIUS_BRIDGE_PORT: String(relayPort),
                GAIUS_ALLOWED_ORIGINS: origin,
                GAIUS_ALLOWED_HOSTS: "127.0.0.1",
                GAIUS_BRIDGE_TOKEN: relayToken,
                GAIUS_IDLE_TIMEOUT_MS: "60000",
                GAIUS_CONNECT_TIMEOUT_MS: "10000",
                GAIUS_PROXY_KEEPALIVES: "1",
            },
            stdio: ["ignore", "pipe", "pipe"],
        });
        relayProcess.once("error", (error) => {
            relaySpawnError = error;
            relayOutput += `\nRelay process error: ${error.stack || error}\n`;
        });
        for (const stream of [relayProcess.stdout, relayProcess.stderr]) {
            stream.setEncoding("utf8");
            stream.on("data", (chunk) => { relayOutput += chunk; });
        }
        await waitFor(() => relayOutput.includes("Gaius translator node listening") ||
            relayProcess.exitCode !== null || relaySpawnError !== undefined,
        "RelayNode startup", 15000, () => relayOutput.slice(-4000));
        if (relaySpawnError !== undefined || relayProcess.exitCode !== null ||
            !relayOutput.includes("Gaius translator node listening")) {
            throw new Error("RelayNode failed to start:\n" + relayOutput);
        }
        relayEndpoint = {
            url: `ws://127.0.0.1:${relayPort}/tunnel`,
            target: { host: "127.0.0.1", port: minecraftPort,
                text: `127.0.0.1:${minecraftPort}` },
            external: false,
        };
        readRelayRuntime = () => fetchRelayRuntime(relayPort, minecraftPort);
        waitRelayRuntime = (predicate, label, timeoutMillis) =>
            waitForRelayRuntimeReader(readRelayRuntime, predicate, label, timeoutMillis);
    }
    relayRuntimeBaseline = await readRelayRuntime();

    browserRuntime = await createBrowserRuntime(relayEndpoint.url, relayToken);
    const createClients = (wave) => Array.from({ length: clientCount }, (_, index) =>
        new BrowserMinecraftClient({
            id: 700 + wave * 100 + index,
            index,
            wave,
            profile: wireProfile,
            bridge: browserRuntime.bridge,
            stats: browserRuntime.stats,
            host: relayEndpoint.target.host,
            port: relayEndpoint.target.port,
            sessionUrl: sessionBaseUrl,
        }));
    const allClients = [];
    const reconnectEvidence = [];
    let currentClients = createClients(0);
    allClients.push(...currentClients);
    const pollTimer = setInterval(() => {
        for (const client of currentClients) client.poll();
    }, 1);
    try {
        await connectClientWave(currentClients);
        await waitFor(
            () => currentClients.every((client) => client.failure === undefined &&
                client.phase === "play" &&
                client.loginFinished &&
                (externalMode || (client.encryptionRequest && client.aesCfb8Enabled)) &&
                client.playLoginPackets > 0 &&
                client.chunkPackets >= minimumChunkPackets),
            "browser Relay Minecraft PLAY/chunk", 90000,
            () => JSON.stringify(currentClients.map((client) => client.diagnostics())));
        for (const client of currentClients) {
            client.checkError();
            assertClientSecurity(client, `initial client ${client.id}`);
        }
        relayRuntimeAtChunks = await waitRelayRuntime(
            (snapshot) => snapshot.activeConnections === clientCount &&
                snapshot.target?.activeConnections === clientCount &&
                (externalMode || relayRuntimeGaugesAreZero(snapshot)),
            "initial RelayNode active tunnel quiescence",
            5000,
        );
        if (!externalMode) {
            assertRelayRuntimeGaugesZero(relayRuntimeAtChunks,
                "initial active tunnel gauge check");
            assertRelayDrainPerformance(
                [relayRuntimeAtChunks],
                "initial multiplayer chunk readiness",
            );
        }
        relayRuntimeBeforeSoak = relayRuntimeAtChunks;
        const seenBuffers = new Set(currentClients.map((client) => client.buffer));
        const seenCiphers = new Set(currentClients.map((client) => client.cipher));
        const seenDeciphers = new Set(currentClients.map((client) => client.decipher));
        const seenSecretFingerprints = new Set(currentClients.map((client) =>
            client.secretFingerprint));

        for (let wave = 1; wave <= reconnectWaves; wave++) {
            const previousClients = currentClients;
            const relayBeforeDrop = await readRelayRuntime();
            const browserBeforeDrop = browserRuntimeSnapshot(browserRuntime);
            const sessionBeforeDrop = sessionRuntimeSnapshot(sessionState);
            const dropAt = performance.now();

            // Freeze the Java-side poll analogue, queue a deterministic marker, and
            // abnormally tear down every live WebSocket in one JS turn. This is a
            // real mid-PLAY transport loss: the bridge's onclose path must retain
            // the synthetic onmessage marker and the non-1000 close error until the
            // Java channel observes them and invokes its final close hook. The marker
            // exercises JSBody queue ordering; it is not claimed as a network tail.
            for (const client of previousClients) client.pausePollingForTransportDrop();
            const transportDrop = forceAbnormalTransportDrop(
                browserRuntime, previousClients, wave);
            const dropDispatchSpreadMillis = timestampSpread(
                transportDrop.map((probe) => probe.terminatedAt));
            assert.ok(dropDispatchSpreadMillis <= 50,
                `reconnect wave ${wave} did not drop all clients together`);
            await waitFor(
                () => transportDrop.every((probe) => probe.entry.closed) &&
                    browserRuntime.wsStats.sockets.size === 0,
                `reconnect wave ${wave} abnormal WebSocket close`, 5000,
                () => JSON.stringify({
                    activeWebSockets: browserRuntime.wsStats.sockets.size,
                    entries: transportDrop.map((probe) => ({
                        id: probe.id,
                        closed: probe.entry.closed,
                        errors: probe.entry.errors,
                        hasPendingInbound:
                            browserRuntime.bridge.hasPendingInbound(probe.id),
                    })),
                }));
            const relayAfterDrop = await waitRelayRuntime(
                (snapshot) => relayRuntimeIsClean(snapshot),
                `reconnect wave ${wave} RelayNode final-close cleanup`,
                5000,
            );
            const transportDropEvidence = await captureAbnormalTransportDrop(
                browserRuntime, transportDrop);
            const browserAfterTransportDrop = browserRuntimeSnapshot(browserRuntime);
            assert.equal(browserAfterTransportDrop.activeChannels, clientCount,
                "abnormal transport close retired bridge entries before Java final close");
            assert.deepEqual({
                activeWebSockets: browserAfterTransportDrop.activeWebSockets,
                queuedBytes: browserAfterTransportDrop.queuedBytes,
                queuedFrames: browserAfterTransportDrop.queuedFrames,
                inboundQueuedBytes: browserAfterTransportDrop.inboundQueuedBytes,
                activeRelayTargetLeases:
                    browserAfterTransportDrop.activeRelayTargetLeases,
            }, {
                activeWebSockets: 0,
                queuedBytes: 0,
                queuedFrames: 0,
                inboundQueuedBytes: 0,
                activeRelayTargetLeases: 0,
            }, "abnormal transport close retained browser queue/lease state");
            if (!externalMode && relayAfterDrop.target !== undefined) {
                assert.equal(relayAfterDrop.target.totalConnections,
                    clientCount * wave,
                    `reconnect wave ${wave} changed target totals while dropping`);
            }
            const relayAfterDropGauges = externalMode
                ? relayRuntimeGaugeEvidence(relayAfterDrop)
                : assertRelayRuntimeGaugesZero(
                    relayAfterDrop,
                    `reconnect wave ${wave} abnormal-drop cleanup`,
                );

            const javaFinalCloseAt = performance.now();
            for (const client of previousClients) client.close("java-final-close");
            const javaFinalCloseDispatchSpreadMillis = timestampSpread(
                previousClients.map((client) => client.closedAt));
            assert.ok(javaFinalCloseDispatchSpreadMillis <= 50,
                `reconnect wave ${wave} did not finalize all Java channels together`);
            await waitForBrowserRuntimeCleanup(browserRuntime,
                `reconnect wave ${wave} browser Java final-close cleanup`);
            const browserAfterJavaFinalClose = browserRuntimeSnapshot(browserRuntime);
            assertBrowserRuntimeClean(browserAfterJavaFinalClose,
                `reconnect wave ${wave} Java final-close`);
            // Give the vanilla server one tick to retire the old player objects;
            // the reconnect timers below still start at the simultaneous drop.
            await delay(100);

            const replacementClients = createClients(wave);
            assert.equal(replacementClients.length, previousClients.length);
            for (let index = 0; index < replacementClients.length; index++) {
                const previous = previousClients[index];
                const replacement = replacementClients[index];
                assert.notEqual(replacement.id, previous.id,
                    "reconnect reused a BrowserWebSocketChannel id");
                assert.equal(replacement.username, previous.username,
                    "reconnect changed the Minecraft account username");
                assert.equal(replacement.profileId, previous.profileId,
                    "reconnect changed the Minecraft account profile");
                assert.equal(replacement.accessToken, previous.accessToken,
                    "reconnect changed the deterministic session identity");
                assert.ok(!seenBuffers.has(replacement.buffer),
                    "reconnect reused a protocol input buffer");
                assert.equal(replacement.cipher, undefined,
                    "reconnect inherited an AES cipher before login");
                assert.equal(replacement.decipher, undefined,
                    "reconnect inherited an AES decipher before login");
            }
            currentClients = replacementClients;
            allClients.push(...replacementClients);
            await connectClientWave(replacementClients);
            await waitFor(
                () => replacementClients.every((client) =>
                    client.failure === undefined &&
                    client.phase === "play" &&
                    client.loginFinished &&
                    (externalMode || (client.encryptionRequest && client.aesCfb8Enabled)) &&
                    client.playLoginPackets > 0 &&
                    client.chunkPackets >= minimumChunkPackets),
                `browser Relay reconnect wave ${wave} PLAY/chunk`, 90000,
                () => JSON.stringify(replacementClients.map((client) =>
                    client.diagnostics())));
            for (const client of replacementClients) {
                client.checkError();
                assertClientSecurity(client, `reconnect wave ${wave} client ${client.id}`);
                if (!externalMode || client.cipher !== undefined) {
                    assert.ok(client.cipher !== undefined && !seenCiphers.has(client.cipher),
                        "reconnect reused an AES cipher object");
                    assert.ok(client.decipher !== undefined &&
                        !seenDeciphers.has(client.decipher),
                        "reconnect reused an AES decipher object");
                    assert.ok(client.secretFingerprint !== undefined &&
                        !seenSecretFingerprints.has(client.secretFingerprint),
                    "reconnect reused an AES shared secret");
                }
                seenBuffers.add(client.buffer);
                seenCiphers.add(client.cipher);
                seenDeciphers.add(client.decipher);
                seenSecretFingerprints.add(client.secretFingerprint);
            }
            const secretFingerprints = allClients.map((client) =>
                client.secretFingerprint).filter(Boolean);
            assert.equal(new Set(secretFingerprints).size, secretFingerprints.length,
                "reconnect reused an AES shared secret");
            const relayAtChunks = await waitRelayRuntime(
                (snapshot) => snapshot.activeConnections === clientCount &&
                    snapshot.target?.activeConnections === clientCount &&
                    snapshot.target?.totalConnections === clientCount * (wave + 1) &&
                    (externalMode || relayRuntimeGaugesAreZero(snapshot)),
                `reconnect wave ${wave} RelayNode active tunnel quiescence`,
                5000,
            );
            relayRuntimeBeforeSoak = relayAtChunks;
            const browserAtChunks = browserRuntimeSnapshot(browserRuntime);
            const sessionAtChunks = sessionRuntimeSnapshot(sessionState);
            if (!externalMode) {
                assert.equal(sessionAtChunks.joins - sessionBeforeDrop.joins, clientCount,
                    `reconnect wave ${wave} did not create fresh session joins`);
                assert.equal(sessionAtChunks.hasJoined - sessionBeforeDrop.hasJoined, clientCount,
                    `reconnect wave ${wave} did not create fresh hasJoined checks`);
            }
            if (relayAtChunks.activeConnections !== undefined) {
                assert.equal(relayAtChunks.activeConnections, clientCount,
                    `reconnect wave ${wave} did not restore every Relay tunnel`);
            }
            if (relayAtChunks.target !== undefined) {
                assert.equal(relayAtChunks.target.activeConnections, clientCount,
                    `reconnect wave ${wave} did not restore every target route`);
                if (!externalMode) assert.equal(relayAtChunks.target.totalConnections,
                    clientCount * (wave + 1),
                    `reconnect wave ${wave} target connection count was not monotonic`);
            }
            const relayAtChunksGauges = externalMode
                ? relayRuntimeGaugeEvidence(relayAtChunks)
                : assertRelayRuntimeGaugesZero(
                    relayAtChunks,
                    `reconnect wave ${wave} active tunnel gauge check`,
                );
            if (!externalMode) {
                assertRelayDrainPerformance(
                    [relayAfterDrop, relayAtChunks],
                    `reconnect wave ${wave} multiplayer drain`,
                );
            }
            const replacementHealth = assertSoakLiveness(
                replacementClients,
                browserAtChunks,
                relayAtChunks,
                `reconnect wave ${wave}`,
            );
            reconnectEvidence.push({
                wave,
                simultaneousDrop: true,
                transportDrop: {
                    abnormalWebSocketClose: true,
                    method: "node-websocket-terminate",
                    harnessRequestedMinecraftDisconnectPacket: false,
                    dispatchSpreadMillis: dropDispatchSpreadMillis,
                    retainedEntriesBeforeJavaFinalClose: clientCount,
                    evidence: transportDropEvidence,
                    syntheticMarkerLabel: "synthetic-inbound-marker",
                    retireClosedEntry: {
                        defined: typeof browserRuntime.bridge.retireClosedEntry === "function",
                        invoked: false,
                        note: "optional hook is reported only; retention is tested, not repaired",
                    },
                },
                javaFinalClose: {
                    invoked: true,
                    atMillis: Number(javaFinalCloseAt.toFixed(3)),
                    dispatchSpreadMillis: javaFinalCloseDispatchSpreadMillis,
                    cleanupAllZero: true,
                },
                dropDispatchSpreadMillis,
                previousChannelIds: previousClients.map((client) => client.id),
                replacementChannelIds: replacementClients.map((client) => client.id),
                sameAccountIdentity: true,
                stateIsolation: {
                    newChannelIds: true,
                    newProtocolBuffers: true,
                    newCipherObjects: true,
                    uniqueSharedSecretFingerprints: true,
                },
                session: {
                    beforeDrop: sessionBeforeDrop,
                    atMinimumChunks: sessionAtChunks,
                    joinsDelta: sessionAtChunks.joins - sessionBeforeDrop.joins,
                    hasJoinedDelta:
                        sessionAtChunks.hasJoined - sessionBeforeDrop.hasJoined,
                },
                browser: {
                    beforeDrop: browserBeforeDrop,
                    afterTransportDrop: browserAfterTransportDrop,
                    afterJavaFinalClose: browserAfterJavaFinalClose,
                    atMinimumChunks: browserAtChunks,
                },
                relay: {
                    beforeDrop: relayBeforeDrop,
                    afterDrop: relayAfterDrop,
                    atMinimumChunks: relayAtChunks,
                    runtimeGauges: {
                        afterDrop: relayAfterDropGauges,
                        atMinimumChunks: relayAtChunksGauges,
                    },
                    reconnectDelta: relayRuntimeDelta(relayAfterDrop, relayAtChunks),
                },
                health: replacementHealth,
                clients: replacementClients.map((client) => ({
                    ...client.result(),
                    dropTiming: client.dropTimingResult(dropAt),
                })),
            });
        }
        soakStartedAt = performance.now();
        if (soakMs > 0) await delayAtLeast(soakMs);
        soakCompletedAt = performance.now();
        relayRuntimeAfterSoak = await waitRelayRuntime(
            (snapshot) => snapshot.activeConnections === clientCount &&
                snapshot.target?.activeConnections === clientCount &&
                (externalMode || relayRuntimeGaugesAreZero(snapshot)),
            "post-soak RelayNode active tunnel quiescence",
            5000,
        );
        browserRuntimeAfterSoak = browserRuntimeSnapshot(browserRuntime);
        soakHealth = assertSoakLiveness(
            currentClients,
            browserRuntimeAfterSoak,
            relayRuntimeAfterSoak,
            "post-soak",
        );
        if (!externalMode) {
            assertRelayDrainPerformance(
                [relayRuntimeAfterSoak],
                "post-soak multiplayer drain",
            );
        }
    }
    finally {
        clearInterval(pollTimer);
        for (const client of currentClients) client.close("final-close");
        await waitForBrowserRuntimeCleanup(browserRuntime,
            "browser Relay transport cleanup").catch(() => {
            // Preserve the primary protocol failure. Successful runs assert every
            // cleanup counter below with a more specific lifecycle error.
        });
        relayRuntimeAfterClose = await waitRelayRuntime(
            (snapshot) => relayRuntimeIsClean(snapshot),
            "RelayNode tunnel/timer cleanup",
            5000,
        ).catch(() => undefined);
    }

    const expectedConnections = clientCount * (reconnectWaves + 1);
    const phases = allClients.flatMap((client) => client.connectPhases);
    const relayConnections = phases.filter((event) => event.phase === "relay-connected");
    assert.equal(relayConnections.length, expectedConnections,
        "browser transport did not establish one real RelayNode WebSocket per client");
    assert.equal(browserRuntime.wsStats.connections, expectedConnections,
        "browser transport opened an unexpected number of WebSocket tunnels");
    assert.ok(browserRuntime.wsStats.urls.every((url) =>
        url === relayEndpoint.url),
    "browser transport connected to an unexpected RelayNode");
    assert.equal(browserRuntime.stats.relayTargetAttestationFailures, 0,
        "Relay target attestation rejected a valid browser tunnel");
    assert.ok(browserRuntime.wsStats.controlFrames >= expectedConnections,
        "browser runtime did not send WebSocket connect controls");
    assert.ok(browserRuntime.wsStats.binaryBytes > 0,
        "browser runtime did not send binary WebSocket frames");
    if (!externalMode) {
        assert.equal(sessionState.joins.length, expectedConnections,
            "vanilla online-mode login did not produce one authenticated session join per client");
        assert.equal(sessionState.hasJoined.length, expectedConnections,
            "vanilla online-mode login did not verify one hasJoined request per client");
        for (const [accessToken, profileId] of sessionState.expectedProfiles) {
            const identityJoins = sessionState.joins.filter((join) =>
                join.accessToken === accessToken && join.selectedProfile === profileId);
            assert.equal(identityJoins.length, reconnectWaves + 1,
                `session identity ${profileId} did not authenticate in every wave`);
        }
    }
    assert.equal(new Set(allClients.map((client) => client.id)).size,
        expectedConnections,
        "browser reconnect lifecycle reused a channel id");
    const observedSecretFingerprints = allClients.map((client) => client.secretFingerprint)
        .filter(Boolean);
    if (!externalMode || observedSecretFingerprints.length > 0) {
        assert.equal(new Set(observedSecretFingerprints).size,
            observedSecretFingerprints.length,
            "browser reconnect lifecycle reused encryption state");
    }
    assert.equal(browserRuntime.bridge.channels.size, 0,
        "browser transport leaked a channel after multiplayer cleanup");
    assert.equal(browserRuntime.wsStats.sockets.size, 0,
        "browser transport leaked a WebSocket after multiplayer cleanup");
    assert.equal(browserRuntime.stats.queuedBytes, 0,
        "browser transport retained outbound bytes after multiplayer cleanup");
    assert.equal(browserRuntime.stats.queuedFrames, 0,
        "browser transport retained outbound frames after multiplayer cleanup");
    assert.equal(browserRuntime.stats.inboundQueuedBytes, 0,
        "browser transport retained inbound bytes after multiplayer cleanup");
    assert.equal(browserRuntime.stats.activeRelayTargetLeases, 0,
        "browser transport retained a RelayNode target lease after multiplayer cleanup");
    assertBrowserRuntimeClean(browserRuntimeSnapshot(browserRuntime),
        "final browser transport cleanup");
    assert.equal(relayRuntimeBaseline.activeConnections, 0,
        "RelayNode baseline unexpectedly had active browser tunnels");
    if (!externalMode) {
        assert.equal(relayRuntimeBaseline.target, undefined,
            "RelayNode baseline unexpectedly reported a target route before first use");
        assert.equal(relayRuntimeBaseline.targetEvidence.available, false,
            "RelayNode baseline target evidence was synthesized instead of observed");
    }
    if (relayRuntimeAtChunks.activeConnections !== undefined) {
        assert.equal(relayRuntimeAtChunks.activeConnections, clientCount,
            "RelayNode did not report every active multiplayer tunnel at chunk readiness");
    }
    if (relayRuntimeAtChunks.target !== undefined) {
        assert.equal(relayRuntimeAtChunks.target.activeConnections, clientCount,
            "RelayNode did not report every active target route at initial chunk readiness");
        if (!externalMode) assert.equal(relayRuntimeAtChunks.target.totalConnections, clientCount,
            "RelayNode initial target connection count did not match client count");
    }
    if (!externalMode) {
        assert.equal(relayRuntimeAfterSoak.target.totalConnections, expectedConnections,
            "RelayNode target route did not count every reconnect tunnel");
        assertRelayRuntimeGaugesZero(relayRuntimeAtChunks,
            "encrypted online-mode tunnels retained RelayNode runtime gauges");
        assertRelayRuntimeGaugesZero(relayRuntimeAfterSoak,
            "encrypted online-mode soak retained RelayNode runtime gauges");
    }
    assert.equal(relayRuntimeAfterClose?.activeConnections, 0,
        "RelayNode retained an active tunnel after browser cleanup");
    assert.equal(relayRuntimeAfterClose?.target?.activeConnections, 0,
        "RelayNode retained an active target route after browser cleanup");
    if (!externalMode) {
        assert.equal(relayRuntimeAfterClose?.target?.totalConnections, expectedConnections,
            "RelayNode final target connection count omitted a reconnect tunnel");
        assertRelayRuntimeGaugesZero(relayRuntimeAfterClose,
            "RelayNode retained a runtime gauge after browser cleanup");
        assertRelayDrainPerformance(
            [relayRuntimeAfterClose],
            "final multiplayer close drain",
        );
    }

    const actualSoakMillis = elapsedMillis(soakStartedAt, soakCompletedAt) ?? 0;
    if (acceptanceMode) {
        assert.equal(clientCount, STRICT_ACCEPTANCE_TARGET.clients,
            "strict acceptance client count drifted");
        assert.equal(minimumChunkPackets, STRICT_ACCEPTANCE_TARGET.minimumChunkPackets,
            "strict acceptance chunk target drifted");
        assert.equal(soakMs, STRICT_ACCEPTANCE_TARGET.soakMillis,
            "strict acceptance soak target drifted");
        assert.equal(reconnectWaves, STRICT_ACCEPTANCE_TARGET.reconnectWaves,
            "strict acceptance reconnect target drifted");
        assert.ok(actualSoakMillis >= STRICT_ACCEPTANCE_TARGET.soakMillis,
            `strict acceptance soak elapsed only ${actualSoakMillis}ms`);
    }
    const result = {
        schemaVersion: "browser-full-path-result-v2",
        ok: true,
        acceptance: {
            mode: acceptanceMode ? "strict-acceptance" : "compatible-smoke",
            required: acceptanceMode
                ? {
                    ...STRICT_ACCEPTANCE_TARGET,
                    profiles: Object.keys(CANONICAL_PROFILES),
                    relayRuntimeGaugesZero: [...RELAY_RUNTIME_GAUGES],
                    relayDrainMaxDurationMillis: RELAY_DRAIN_MAX_DURATION_MILLIS,
                    relayDrainSendErrors: 0,
                    relayDrainCleanupRequired: true,
                    browserCleanupGaugesZero: [...BROWSER_RUNTIME_CLEANUP_GAUGES],
                    syntheticMarkerLabel: "synthetic-inbound-marker",
                    runtimeJavaPolicy: { ...STRICT_RUNTIME_JAVA_POLICY },
                }
                : {
                    clients: clientCount,
                    minimumChunkPackets,
                    soakMillis: soakMs,
                    reconnectWaves,
                    relayRuntimeGaugesZero: [...RELAY_RUNTIME_GAUGES],
                    relayDrainMaxDurationMillis: RELAY_DRAIN_MAX_DURATION_MILLIS,
                    relayDrainSendErrors: 0,
                    relayDrainCleanupRequired: true,
                    browserCleanupGaugesZero: [...BROWSER_RUNTIME_CLEANUP_GAUGES],
                    syntheticMarkerLabel: "synthetic-inbound-marker",
                },
            observed: {
                clients: clientCount,
                minimumChunkPackets,
                soakMillis: soakMs,
                actualSoakMillis,
                reconnectWaveCount: reconnectEvidence.length,
                expectedConnections,
                profile: {
                    id: activeProfile.id,
                    path: repositoryRelativePath(activeProfile.path),
                    canonicalProfilePath: repositoryRelativePath(activeProfile.path),
                    protocolVersion: activeProfile.protocolVersion,
                    worldVersion: activeProfile.worldVersion,
                    javaVersion: activeProfile.javaVersion,
                    runtimeJavaMajor: runtimeJava?.major ?? null,
                    runtimeJavaExecutable: runtimeJava?.executable ?? null,
                    serverSha1: verifiedServerJar?.sha1 ?? null,
                    expectedServerJarSha1: activeProfile.official.serverSha1,
                    actualServerJarSha1: verifiedServerJar?.sha1 ?? null,
                },
                runtimeJavaMajor: runtimeJava?.major ?? null,
                runtimeJavaExecutable: runtimeJava?.executable ?? null,
                soak: soakHealth,
                reconnectWaves: reconnectEvidence.map((wave) => ({
                    wave: wave.wave,
                    health: wave.health,
                    syntheticMarkerLabel: wave.transportDrop.syntheticMarkerLabel,
                    relayRuntimeGauges: wave.relay.runtimeGauges,
                })),
                finalCleanup: {
                    browser: browserRuntimeSnapshot(browserRuntime),
                    browserCleanupGauges: browserRuntimeCleanupGaugeEvidence(
                        browserRuntimeSnapshot(browserRuntime)),
                    relay: relayRuntimeAfterClose,
                    relayRuntimeGauges: relayRuntimeGaugeEvidence(relayRuntimeAfterClose),
                },
            },
            actual: {
                soakMillis: actualSoakMillis,
                soak: soakHealth,
                reconnectWaves: reconnectEvidence.map((wave) => ({
                    wave: wave.wave,
                    health: wave.health,
                    syntheticMarkerLabel: wave.transportDrop.syntheticMarkerLabel,
                    relayRuntimeGauges: wave.relay.runtimeGauges,
                })),
                serverJarSha1: verifiedServerJar?.sha1 ?? null,
                runtimeJavaMajor: runtimeJava?.major ?? null,
                runtimeJavaExecutable: runtimeJava?.executable ?? null,
                profile: {
                    id: activeProfile.id,
                    path: repositoryRelativePath(activeProfile.path),
                    canonicalProfilePath: repositoryRelativePath(activeProfile.path),
                    protocolVersion: activeProfile.protocolVersion,
                    worldVersion: activeProfile.worldVersion,
                    javaVersion: activeProfile.javaVersion,
                    runtimeJavaMajor: runtimeJava?.major ?? null,
                    runtimeJavaExecutable: runtimeJava?.executable ?? null,
                    serverSha1: verifiedServerJar?.sha1 ?? null,
                    expectedServerJarSha1: activeProfile.official.serverSha1,
                    actualServerJarSha1: verifiedServerJar?.sha1 ?? null,
                },
            },
        },
        transport: {
            browserChannelSource: fileURLToPath(channelSourceUrl),
            browserJsBody: true,
            teaVmBuildRequired: false,
            realWebSocketFraming: true,
            relayUrl: relayEndpoint.url,
            clients: clientCount,
            reconnectWaves,
            expectedConnections,
            webSocketConnections: browserRuntime.wsStats.connections,
            webSocketUrls: browserRuntime.wsStats.urls,
            controlFrames: browserRuntime.wsStats.controlFrames,
            binaryFrames: browserRuntime.wsStats.binaryFrames,
            binaryBytes: browserRuntime.wsStats.binaryBytes,
            inboundFrames: browserRuntime.stats.receivedFrames,
            inboundBytes: browserRuntime.stats.receivedBytes,
            relayTargetAttestationFailures: browserRuntime.stats.relayTargetAttestationFailures,
            activeChannelsAfterClose: browserRuntime.bridge.channels.size,
            activeWebSocketsAfterClose: browserRuntime.wsStats.sockets.size,
            queuedBytesAfterClose: browserRuntime.stats.queuedBytes,
            queuedFramesAfterClose: browserRuntime.stats.queuedFrames,
            inboundQueuedBytesAfterClose: browserRuntime.stats.inboundQueuedBytes,
            activeRelayTargetLeasesAfterClose:
                browserRuntime.stats.activeRelayTargetLeases,
            browserCleanupGaugesAfterClose: browserRuntimeCleanupGaugeEvidence(
                browserRuntimeSnapshot(browserRuntime)),
        },
        profile: {
            id: activeProfile.id,
            protocolVersion: activeProfile.protocolVersion,
            worldVersion: activeProfile.worldVersion,
            javaVersion: activeProfile.javaVersion,
            runtimeJavaMajor: runtimeJava?.major ?? null,
            runtimeJavaExecutable: runtimeJava?.executable ?? null,
            serverSha1: verifiedServerJar?.sha1 ?? null,
            path: repositoryRelativePath(activeProfile.path),
            canonicalProfilePath: repositoryRelativePath(activeProfile.path),
            serverJar,
            serverJarSha1: verifiedServerJar?.sha1 ?? null,
            expectedServerJarSha1: activeProfile.official.serverSha1,
        },
        vanilla: {
            onlineMode: externalMode ? null : true,
            unmodifiedServerJar: externalMode ? null : true,
            pluginsInstalled: externalMode ? null : false,
            workDirectory,
        },
        session: {
            joins: sessionState.joins.length,
            hasJoined: sessionState.hasJoined.length,
            publicKeyRequests: sessionState.publicKeyRequests,
        },
        clients: allClients.map((client) => client.result()),
        reconnectWaves: reconnectEvidence,
        relayPhases: phases,
        relayRuntime: {
            baseline: relayRuntimeBaseline,
            atMinimumChunks: relayRuntimeAtChunks,
            beforeSoak: relayRuntimeBeforeSoak,
            afterSoak: relayRuntimeAfterSoak,
            afterClose: relayRuntimeAfterClose,
            connectAndChunkDelta:
                relayRuntimeDelta(relayRuntimeBaseline, relayRuntimeAtChunks),
            soakDelta:
                relayRuntimeDelta(relayRuntimeBeforeSoak, relayRuntimeAfterSoak),
            totalDelta:
                relayRuntimeDelta(relayRuntimeBaseline, relayRuntimeAfterSoak),
        },
        performanceContract: browserFullPathPerformanceContract(),
        elapsedMillis: Number((performance.now() - smokeStartedAt).toFixed(1)),
    };
    await writeFile(path.join(workDirectory, "result.json"),
        JSON.stringify(result, null, 2) + "\n");
    console.log(JSON.stringify(result));
}
catch (error) {
    failure = error;
    await writeFile(path.join(workDirectory, "failure.json"), JSON.stringify({
        ok: false,
        profile: activeProfile.id,
        error: String(error?.stack || error),
        workDirectory,
    }, null, 2) + "\n").catch(() => {});
    throw error;
}
    finally {
    if (browserRuntime !== undefined) {
        // The runtime has no process resources beyond WebSockets; close() is
        // best effort because failures can happen before all channels exist.
        await browserRuntime.close?.();
    }
    await stopChildProcess(relayProcess);
    await stopChildProcess(serverProcess, { gracefulInput: "stop\n" });
    await closeHttpServer(sessionServer);
    await writeFile(path.join(workDirectory, "server.log"), serverOutput).catch(() => {});
    await writeFile(path.join(workDirectory, "relay.log"), relayOutput).catch(() => {});
    if (failure !== undefined) {
        console.error(`Browser full-path evidence retained at ${workDirectory}`);
    }
}

}

async function connectClientWave(clients) {
    if (clientStartDelayMs === 0) {
        await Promise.all(clients.map((client) => client.connect()));
        return;
    }
    for (let index = 0; index < clients.length; index++) {
        if (index > 0) await delay(clientStartDelayMs);
        await clients[index].connect();
    }
}

class BrowserMinecraftClient {
    constructor(options) {
        Object.assign(this, options);
        this.username = `${usernamePrefix}${options.index + 1}`.slice(0, 16);
        this.profileId = `0000000000004000800000000000000${options.index + 2}`;
        this.accessToken = `${relayToken}-${options.index + 1}`;
        this.phase = "login";
        this.buffer = Buffer.alloc(0);
        this.cipher = undefined;
        this.decipher = undefined;
        this.compressionThreshold = undefined;
        this.encryptionRequest = false;
        this.rsaSecretEncrypted = false;
        this.rsaChallengeEncrypted = false;
        this.aesCfb8Enabled = false;
        this.secretFingerprint = undefined;
        this.sessionJoin = false;
        this.loginFinished = false;
        this.configurationFinished = false;
        this.configurationCycles = 0;
        this.reconfigurationRequests = 0;
        this.configurationPackets = 0;
        this.configurationPacketIds = [];
        this.showDialogPackets = 0;
        this.showDialogAccepts = 0;
        this.showDialogActions = [];
        this.showDialogSummaries = [];
        this.acceptedDialogActions = new Set();
        this.codeOfConductRequests = 0;
        this.codeOfConductAccepts = 0;
        this.playPackets = 0;
        this.playPacketIds = [];
        this.playDisconnectPayloadBase64 = undefined;
        this.playLoginPackets = 0;
        this.chunkPackets = 0;
        this.playTickPackets = 0;
        this.playTickTimer = undefined;
        this.playerLoadedSent = false;
        this.connectPhases = [];
        this.failure = undefined;
        this.closed = false;
        this.pollingPaused = false;
        this.startedAt = performance.now();
        this.connectStartedAt = undefined;
        this.relayConnectedAt = undefined;
        this.handshakeSentAt = undefined;
        this.encryptionRequestAt = undefined;
        this.sessionJoinAt = undefined;
        this.loginFinishedAt = undefined;
        this.configurationFinishedAt = undefined;
        this.playLoginAt = undefined;
        this.firstChunkAt = undefined;
        this.minimumChunksAt = undefined;
        this.closedAt = undefined;
        this.closeReason = undefined;
        this.inboundFrames = 0;
        this.inboundBytes = 0;
        this.outboundFrames = 0;
        this.outboundBytes = 0;
        this.decodedPackets = 0;
        this.maximumBufferedBytes = 0;
    }

    async connect() {
        this.connectStartedAt = performance.now();
        this.bridge.open(this.id, this.host, this.port);
        await waitFor(() => {
            this.checkError();
            this.recordPhases();
            return this.connectPhases.some((event) => event.phase === "relay-connected");
        }, `browser Relay client ${this.index + 1}`, 20000,
        () => JSON.stringify(this.diagnostics()));
        this.relayConnectedAt = performance.now();
        this.sendPacket(0, Buffer.concat([
            encodeVarInt(this.profile.protocolVersion),
            encodeString(this.host),
            Buffer.from([(this.port >>> 8) & 0xff, this.port & 0xff]),
            encodeVarInt(2),
        ]));
        this.sendPacket(0, Buffer.concat([
            encodeString(this.username),
            Buffer.from(this.profileId, "hex"),
        ]));
        this.handshakeSentAt = performance.now();
    }

    poll() {
        if (this.closed || this.pollingPaused) return;
        try {
            this.checkError();
            this.recordPhases();
            let chunk;
            while ((chunk = this.bridge.pollInbound(this.id)) !== null) {
                const bytes = Buffer.from(chunk);
                this.buffer = Buffer.concat([
                    this.buffer,
                    this.decipher === undefined ? bytes : this.decipher.update(bytes),
                ]);
                this.inboundFrames++;
                this.inboundBytes += bytes.byteLength;
                this.maximumBufferedBytes = Math.max(
                    this.maximumBufferedBytes,
                    this.buffer.byteLength,
                );
                this.parsePackets();
            }
        }
        catch (error) {
            this.failure ??= error;
        }
    }

    parsePackets() {
        while (true) {
            const outerLength = decodeVarInt(this.buffer, 0);
            if (outerLength === undefined) return;
            const frameStart = outerLength.bytesRead;
            const frameEnd = frameStart + outerLength.value;
            if (frameEnd > this.buffer.byteLength) return;
            let frame = this.buffer.subarray(frameStart, frameEnd);
            this.buffer = this.buffer.subarray(frameEnd);
            if (this.compressionThreshold !== undefined) {
                const dataLength = decodeVarInt(frame, 0);
                if (dataLength === undefined) throw new Error("compressed frame omitted data length");
                if (dataLength.value !== 0) {
                    frame = inflateSync(frame.subarray(dataLength.bytesRead));
                    if (frame.byteLength !== dataLength.value) {
                        throw new Error("compressed frame length mismatch");
                    }
                }
                else {
                    frame = frame.subarray(dataLength.bytesRead);
                }
            }
            const packetId = decodeVarInt(frame, 0);
            if (packetId === undefined) throw new Error("packet omitted id");
            this.decodedPackets++;
            this.handlePacket(packetId.value, frame.subarray(packetId.bytesRead));
            if (this.failure !== undefined) throw this.failure;
        }
    }

    handlePacket(packetId, payload) {
        if (this.phase === "login") {
            if (packetId === this.profile.login.clientboundDisconnect) {
                throw new Error(`${this.username}: server rejected login ${decodeReason(payload)}`);
            }
            if (packetId === this.profile.login.clientboundEncryptionRequest) {
                this.encryptionRequest = true;
                this.encryptionRequestAt ??= performance.now();
                void this.answerEncryptionRequest(payload).catch((error) => {
                    this.failure ??= error;
                });
                return;
            }
            if (packetId === this.profile.login.clientboundCompression) {
                const threshold = decodeVarInt(payload, 0);
                if (threshold === undefined || threshold.value < 0) {
                    throw new Error(`${this.username}: invalid compression threshold`);
                }
                this.compressionThreshold = threshold.value;
                return;
            }
            if (packetId === this.profile.login.clientboundLoginFinished) {
                this.loginFinished = true;
                this.loginFinishedAt ??= performance.now();
                this.phase = "configuration";
                this.sendPacket(this.profile.login.serverboundLoginAcknowledged, Buffer.alloc(0));
                this.sendPacket(this.profile.login.serverboundHello, encodeClientInformation());
            }
            return;
        }
        if (this.phase === "configuration") {
            this.configurationPackets++;
            this.configurationPacketIds.push(packetId);
            if (this.configurationPacketIds.length > 64) this.configurationPacketIds.shift();
            if (packetId === this.profile.configuration.clientboundDisconnect) {
                throw new Error(`${this.username}: server disconnected during configuration ` +
                    decodeReason(payload));
            }
            if (packetId === this.profile.configuration.clientboundKnownPacks) {
                this.sendPacket(this.profile.configuration.serverboundSelectKnownPacks, encodeVarInt(0));
            }
            else if (packetId === this.profile.configuration.clientboundResourcePackPush) {
                if (payload.byteLength < 16) throw new Error("resource-pack push omitted UUID");
                const packId = payload.subarray(0, 16);
                const url = decodeString(payload, 16);
                const hash = decodeString(payload, url.nextOffset);
                for (const action of [3, 4, 0]) {
                    this.sendPacket(this.profile.configuration.serverboundResourcePack,
                        Buffer.concat([packId, encodeVarInt(action)]));
                }
                void hash;
            }
            else if (packetId === this.profile.configuration.clientboundShowDialog) {
                this.showDialogPackets++;
                const dialog = decodeNetworkNbt(payload);
                const prompt = inspectServerDialog(dialog);
                this.showDialogSummaries.push(prompt.summary);
                this.showDialogActions.push(...prompt.actionIds);
                if (!acceptServerPrompts) {
                    throw new Error(`${this.username}: Minecraft server requires an interactive ` +
                        `dialog (${prompt.summary})`);
                }
                const actionId = selectDialogAction(prompt.actionIds);
                if (this.acceptedDialogActions.has(actionId)) {
                    throw new Error(`${this.username}: server repeated dialog action ${actionId}`);
                }
                const inputValues = resolveDialogInputValues(prompt.inputs);
                this.sendPacket(this.profile.configuration.serverboundCustomClickAction,
                    encodeCustomClickAction(actionId, inputValues));
                this.acceptedDialogActions.add(actionId);
                this.showDialogAccepts++;
            }
            else if (packetId === this.profile.configuration.clientboundCodeOfConduct) {
                const codeOfConduct = decodeString(payload, 0);
                if (codeOfConduct.nextOffset !== payload.byteLength ||
                    codeOfConduct.value.length === 0) {
                    throw new Error(`${this.username}: malformed Code of Conduct packet`);
                }
                this.codeOfConductRequests++;
                if (this.codeOfConductRequests !== 1) {
                    throw new Error(`${this.username}: duplicate Code of Conduct packet`);
                }
                if (!acceptServerPrompts) {
                    throw new Error(`${this.username}: Minecraft server requires Code of Conduct ` +
                        "acceptance");
                }
                this.sendPacket(this.profile.configuration.serverboundAcceptCodeOfConduct,
                    Buffer.alloc(0));
                this.codeOfConductAccepts++;
            }
            else if (packetId === this.profile.configuration.clientboundKeepAlive) {
                this.sendPacket(this.profile.configuration.serverboundKeepAlive, payload);
            }
            else if (packetId === this.profile.configuration.clientboundPing) {
                this.sendPacket(this.profile.configuration.serverboundPong, payload);
            }
            else if (packetId === this.profile.configuration.clientboundFinish) {
                this.configurationFinished = true;
                this.configurationFinishedAt ??= performance.now();
                this.configurationCycles++;
                this.phase = "play";
                this.sendPacket(this.profile.configuration.serverboundFinish, Buffer.alloc(0));
            }
            return;
        }
        if (this.phase !== "play") return;
        this.playPackets++;
        this.playPacketIds.push(packetId);
        if (this.playPacketIds.length > 128) this.playPacketIds.shift();
        if (packetId === this.profile.play.clientboundDisconnect) {
            this.playDisconnectPayloadBase64 = Buffer.from(payload).toString("base64");
            throw new Error(`${this.username}: server disconnected in PLAY ${decodeReason(payload)}`);
        }
        if (packetId === this.profile.play.clientboundKeepAlive) {
            this.sendPacket(this.profile.play.serverboundKeepAlive, payload);
        }
        else if (packetId === this.profile.play.clientboundPing) {
            this.sendPacket(this.profile.play.serverboundPong, payload);
        }
        else if (packetId === this.profile.play.clientboundLogin) {
            this.playLoginPackets++;
            this.playLoginAt ??= performance.now();
            if (!this.playerLoadedSent) {
                this.playerLoadedSent = true;
                this.sendPacket(this.profile.play.serverboundPlayerLoaded, Buffer.alloc(0));
            }
            this.startPlayTickLoop();
        }
        else if (packetId === this.profile.play.clientboundChunk) {
            this.chunkPackets++;
            this.firstChunkAt ??= performance.now();
            if (this.chunkPackets >= minimumChunkPackets) {
                this.minimumChunksAt ??= performance.now();
            }
        }
        else if (packetId === this.profile.play.clientboundStartConfiguration) {
            if (payload.byteLength !== 0) throw new Error("PLAY start-configuration had payload");
            this.reconfigurationRequests++;
            this.stopPlayTickLoop();
            this.phase = "configuration";
            this.sendPacket(this.profile.play.serverboundConfigurationAcknowledged, Buffer.alloc(0));
        }
    }

    async answerEncryptionRequest(payload) {
        const serverId = decodeString(payload, 0);
        const publicKey = decodeByteArray(payload, serverId.nextOffset);
        const challenge = decodeByteArray(payload, publicKey.nextOffset);
        if (challenge.nextOffset >= payload.byteLength || payload[challenge.nextOffset] === 0) {
            throw new Error(`${this.username}: server disabled session authentication`);
        }
        const secret = randomBytes(16);
        this.secretFingerprint = createHash("sha256").update(secret).digest("hex");
        const serverHash = minecraftServerHash(serverId.value, secret, publicKey.value);
        const joinResponse = await fetch(new URL(
            "session/minecraft/join", `${this.sessionUrl}/`), {
            method: "POST",
            headers: { "content-type": "application/json" },
            body: JSON.stringify({
                accessToken: this.accessToken,
                selectedProfile: this.profileId,
                serverId: serverHash,
            }),
        });
        if (!joinResponse.ok) throw new Error(`${this.username}: session join HTTP ${joinResponse.status}`);
        this.sessionJoin = true;
        this.sessionJoinAt ??= performance.now();
        const encryptedSecret = publicEncrypt({
            key: publicKey.value,
            format: "der",
            type: "spki",
            padding: cryptoConstants.RSA_PKCS1_PADDING,
        }, secret);
        const encryptedChallenge = publicEncrypt({
            key: publicKey.value,
            format: "der",
            type: "spki",
            padding: cryptoConstants.RSA_PKCS1_PADDING,
        }, challenge.value);
        this.rsaSecretEncrypted = encryptedSecret.byteLength > 0;
        this.rsaChallengeEncrypted = encryptedChallenge.byteLength > 0;
        this.sendRaw(encodePacket(this.profile.login.serverboundKey, Buffer.concat([
            encodeByteArray(encryptedSecret), encodeByteArray(encryptedChallenge),
        ])));
        this.cipher = createCipheriv("aes-128-cfb8", secret, secret);
        this.decipher = createDecipheriv("aes-128-cfb8", secret, secret);
        this.aesCfb8Enabled = true;
    }

    sendPacket(id, payload) {
        this.sendRaw(encodePacket(id, payload, this.compressionThreshold));
    }

    sendRaw(packet) {
        const wire = this.cipher === undefined ? packet : this.cipher.update(packet);
        if (!this.bridge.send(this.id, new Uint8Array(wire))) {
            throw new Error(`${this.username}: browser transport rejected outbound frame`);
        }
        this.outboundFrames++;
        this.outboundBytes += wire.byteLength;
    }

    startPlayTickLoop() {
        if (this.playTickTimer !== undefined) return;
        const sendTick = () => {
            if (this.closed || this.failure !== undefined || this.phase !== "play") return;
            try {
                this.sendPacket(this.profile.play.serverboundClientTickEnd, Buffer.alloc(0));
                this.playTickPackets++;
            }
            catch (error) {
                this.failure ??= error;
            }
        };
        sendTick();
        this.playTickTimer = setInterval(sendTick, 50);
    }

    stopPlayTickLoop() {
        if (this.playTickTimer === undefined) return;
        clearInterval(this.playTickTimer);
        this.playTickTimer = undefined;
    }

    checkError() {
        const error = this.bridge.pollError(this.id);
        if (error) this.failure ??= new Error(`${this.username}: ${error}`);
        if (this.failure !== undefined) throw this.failure;
    }

    recordPhases() {
        const events = this.stats.connectPhases.filter((event) => event.id === this.id);
        if (events.length > this.connectPhases.length) {
            this.connectPhases = events.slice();
        }
    }

    close(reason = "final-close") {
        if (this.closed) return;
        this.closed = true;
        this.stopPlayTickLoop();
        this.closedAt = performance.now();
        this.closeReason = reason;
        try { this.bridge.close(this.id); } catch {}
    }

    pausePollingForTransportDrop() {
        assert.equal(this.closed, false, "cannot pause a closed reconnect client");
        this.pollingPaused = true;
    }

    diagnostics() {
        return {
            username: this.username,
            id: this.id,
            wave: this.wave,
            phase: this.phase,
            encryptionRequest: this.encryptionRequest,
            rsaSecretEncrypted: this.rsaSecretEncrypted,
            rsaChallengeEncrypted: this.rsaChallengeEncrypted,
            aesCfb8Enabled: this.aesCfb8Enabled,
            sessionJoin: this.sessionJoin,
            compressionThreshold: this.compressionThreshold ?? null,
            loginFinished: this.loginFinished,
            configurationFinished: this.configurationFinished,
            configurationCycles: this.configurationCycles,
            configurationPacketIds: [...this.configurationPacketIds],
            showDialogPackets: this.showDialogPackets,
            showDialogAccepts: this.showDialogAccepts,
            showDialogActions: [...this.showDialogActions],
            showDialogSummaries: [...this.showDialogSummaries],
            codeOfConductRequests: this.codeOfConductRequests,
            codeOfConductAccepts: this.codeOfConductAccepts,
            reconfigurationRequests: this.reconfigurationRequests,
            playPackets: this.playPackets,
            playPacketIds: [...this.playPacketIds],
            playDisconnectPayloadBase64: this.playDisconnectPayloadBase64 ?? null,
            playLoginPackets: this.playLoginPackets,
            chunkPackets: this.chunkPackets,
            playTickPackets: this.playTickPackets,
            bufferedBytes: this.buffer.byteLength,
            minimumChunkPackets,
            inboundFrames: this.inboundFrames,
            inboundBytes: this.inboundBytes,
            outboundFrames: this.outboundFrames,
            outboundBytes: this.outboundBytes,
            decodedPackets: this.decodedPackets,
            maximumBufferedBytes: this.maximumBufferedBytes,
            closeReason: this.closeReason ?? null,
            pollingPaused: this.pollingPaused,
            failure: this.failure === undefined ? null : String(this.failure),
        };
    }

    result() {
        return {
            ...this.diagnostics(),
            onlineEncryption: this.onlineEncryptionResult(),
            rsa: {
                requested: this.encryptionRequest,
                secretEncrypted: this.rsaSecretEncrypted,
                challengeEncrypted: this.rsaChallengeEncrypted,
                padding: "RSA_PKCS1_PADDING",
            },
            aes: {
                cipher: "aes-128-cfb8",
                enabled: this.aesCfb8Enabled,
                iv: "shared-secret",
                secretFingerprint: this.secretFingerprint ?? null,
            },
            onlineMode: this.encryptionRequest,
            configurationCycles: this.configurationCycles,
            reconfigurationRequests: this.reconfigurationRequests,
            playLoginPackets: this.playLoginPackets,
            chunkPackets: this.chunkPackets,
            timing: this.timingResult(),
            traffic: {
                inboundFrames: this.inboundFrames,
                inboundBytes: this.inboundBytes,
                outboundFrames: this.outboundFrames,
                outboundBytes: this.outboundBytes,
                decodedPackets: this.decodedPackets,
                maximumBufferedBytes: this.maximumBufferedBytes,
                packetsPerSecondToMinimumChunks: ratePerSecond(
                    this.decodedPackets,
                    elapsedMillis(this.connectStartedAt, this.minimumChunksAt),
                ),
                inboundBytesPerSecondToMinimumChunks: ratePerSecond(
                    this.inboundBytes,
                    elapsedMillis(this.connectStartedAt, this.minimumChunksAt),
                ),
            },
        };
    }

    onlineEncryptionResult() {
        return {
            required: true,
            encryptionRequest: this.encryptionRequest,
            sessionJoin: this.sessionJoin,
            rsaSecretEncrypted: this.rsaSecretEncrypted,
            rsaChallengeEncrypted: this.rsaChallengeEncrypted,
            aesCfb8Enabled: this.aesCfb8Enabled,
            secretFingerprint: this.secretFingerprint ?? null,
            failClosed: this.failure === undefined && this.encryptionRequest &&
                this.sessionJoin && this.rsaSecretEncrypted &&
                this.rsaChallengeEncrypted && this.aesCfb8Enabled &&
                typeof this.secretFingerprint === "string" &&
                /^[0-9a-f]{64}$/u.test(this.secretFingerprint),
        };
    }

    timingResult() {
        return {
            relayConnectedMillis:
                elapsedMillis(this.connectStartedAt, this.relayConnectedAt),
            relayToHandshakeMillis:
                elapsedMillis(this.relayConnectedAt, this.handshakeSentAt),
            handshakeToEncryptionRequestMillis:
                elapsedMillis(this.handshakeSentAt, this.encryptionRequestAt),
            encryptionRequestToSessionJoinMillis:
                elapsedMillis(this.encryptionRequestAt, this.sessionJoinAt),
            handshakeToLoginFinishedMillis:
                elapsedMillis(this.handshakeSentAt, this.loginFinishedAt),
            loginToConfigurationFinishedMillis:
                elapsedMillis(this.loginFinishedAt, this.configurationFinishedAt),
            configurationToPlayLoginMillis:
                elapsedMillis(this.configurationFinishedAt, this.playLoginAt),
            playLoginToFirstChunkMillis:
                elapsedMillis(this.playLoginAt, this.firstChunkAt),
            firstChunkToMinimumChunksMillis:
                elapsedMillis(this.firstChunkAt, this.minimumChunksAt),
            connectToFirstChunkMillis:
                elapsedMillis(this.connectStartedAt, this.firstChunkAt),
            connectToMinimumChunksMillis:
                elapsedMillis(this.connectStartedAt, this.minimumChunksAt),
            connectedLifetimeMillis:
                elapsedMillis(this.connectStartedAt, this.closedAt),
        };
    }

    dropTimingResult(dropAt) {
        return {
            dropToRelayConnectedMillis: elapsedMillis(dropAt, this.relayConnectedAt),
            dropToHandshakeSentMillis: elapsedMillis(dropAt, this.handshakeSentAt),
            dropToEncryptionRequestMillis:
                elapsedMillis(dropAt, this.encryptionRequestAt),
            dropToSessionJoinMillis: elapsedMillis(dropAt, this.sessionJoinAt),
            dropToLoginFinishedMillis: elapsedMillis(dropAt, this.loginFinishedAt),
            dropToConfigurationFinishedMillis:
                elapsedMillis(dropAt, this.configurationFinishedAt),
            dropToPlayLoginMillis: elapsedMillis(dropAt, this.playLoginAt),
            dropToFirstChunkMillis: elapsedMillis(dropAt, this.firstChunkAt),
            dropToMinimumChunksMillis: elapsedMillis(dropAt, this.minimumChunksAt),
        };
    }
}

function assertOnlineEncryption(client, label) {
    const observed = client.onlineEncryptionResult();
    assert.equal(observed.failClosed, true,
        `${label} did not complete online-mode RSA/AES encryption fail-closed: ` +
        JSON.stringify(observed));
    assert.equal(client.failure, undefined,
        `${label} reported a client failure after encrypted login`);
}

function assertClientSecurity(client, label) {
    if (!externalMode && !client.encryptionRequest) {
        throw new Error(`${label}: local full-path server did not request online encryption`);
    }
    if (client.encryptionRequest) assertOnlineEncryption(client, label);
}

function elapsedMillis(start, end) {
    if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return null;
    return Number((end - start).toFixed(3));
}

async function delayAtLeast(durationMillis) {
    const requested = Math.max(0, Number(durationMillis) || 0);
    const startedAt = performance.now();
    while (true) {
        const remaining = requested - (performance.now() - startedAt);
        if (remaining <= 0) return;
        // Node timers may wake a fraction of a millisecond early. Re-check the
        // monotonic deadline instead of letting a strict 15 s soak fail on
        // timer quantization (for example, 14999.936 ms).
        await delay(Math.max(1, Math.ceil(remaining)));
    }
}

function ratePerSecond(value, millis) {
    if (!Number.isFinite(value) || !Number.isFinite(millis) || millis <= 0) return null;
    return Number((value * 1000 / millis).toFixed(3));
}

function timestampSpread(values) {
    const finite = values.filter(Number.isFinite);
    if (finite.length === 0) return null;
    return Number((Math.max(...finite) - Math.min(...finite)).toFixed(3));
}

function sessionRuntimeSnapshot(state) {
    return {
        joins: state.joins.length,
        hasJoined: state.hasJoined.length,
        publicKeyRequests: state.publicKeyRequests,
    };
}

function browserRuntimeSnapshot(runtime) {
    const snapshot = {
        activeChannels: runtime.bridge.channels.size,
        activeWebSockets: runtime.wsStats.sockets.size,
        webSocketConnections: runtime.wsStats.connections,
        queuedBytes: runtime.stats.queuedBytes,
        queuedFrames: runtime.stats.queuedFrames,
        inboundQueuedBytes: runtime.stats.inboundQueuedBytes,
        activeRelayTargetLeases: runtime.stats.activeRelayTargetLeases,
        relayTargetAttestationFailures: runtime.stats.relayTargetAttestationFailures,
    };
    for (const name of BROWSER_RUNTIME_CLEANUP_GAUGES) {
        if (Object.prototype.hasOwnProperty.call(runtime.stats, name)) {
            snapshot[name] = runtime.stats[name];
        }
    }
    return snapshot;
}

function browserRuntimeCleanupGaugeEvidence(snapshot) {
    const observed = Object.fromEntries(BROWSER_RUNTIME_CLEANUP_GAUGES.map((name) => [
        name,
        snapshot !== undefined && Object.prototype.hasOwnProperty.call(snapshot, name)
            ? snapshot[name]
            : null,
    ]));
    const missing = BROWSER_RUNTIME_CLEANUP_GAUGES.filter((name) =>
        !Number.isSafeInteger(observed[name]));
    return {
        source: "BrowserWebSocketChannel.__gaiusNettyBridge.stats",
        fields: [...BROWSER_RUNTIME_CLEANUP_GAUGES],
        observed,
        missing,
        available: missing.length === 0,
        allZero: missing.length === 0 &&
            BROWSER_RUNTIME_CLEANUP_GAUGES.every((name) => observed[name] === 0),
        note: missing.length === 0
            ? "Browser JSBody cleanup counters observed directly"
            : "Browser JSBody cleanup counters were absent; no zero was synthesized",
    };
}

function assertBrowserRuntimeCleanupGaugesZero(snapshot, label) {
    const evidence = browserRuntimeCleanupGaugeEvidence(snapshot);
    assert.deepEqual(evidence.missing, [],
        `${label}: browser cleanup telemetry omitted required fields: ` +
        JSON.stringify(evidence));
    assert.deepEqual(evidence.observed,
        Object.fromEntries(BROWSER_RUNTIME_CLEANUP_GAUGES.map((name) => [name, 0])),
        `${label}: browser cleanup gauges did not drain to zero`);
    return evidence;
}

function relayRuntimeGaugeEvidence(snapshot) {
    const runtime = snapshot?.runtime;
    const observed = Object.fromEntries(RELAY_RUNTIME_GAUGES.map((name) => [
        name,
        runtime !== undefined && Object.prototype.hasOwnProperty.call(runtime, name)
            ? runtime[name]
            : null,
    ]));
    const missing = RELAY_RUNTIME_GAUGES.filter((name) =>
        !Number.isSafeInteger(observed[name]));
    return {
        source: "/relay-node/v1.runtime",
        fields: [...RELAY_RUNTIME_GAUGES],
        observed,
        missing,
        available: missing.length === 0,
        allZero: missing.length === 0 &&
            RELAY_RUNTIME_GAUGES.every((name) => observed[name] === 0),
    };
}

function relayRuntimeGaugesAreZero(snapshot) {
    const evidence = relayRuntimeGaugeEvidence(snapshot);
    return evidence.available && evidence.allZero;
}

function assertRelayRuntimeGaugesZero(snapshot, label) {
    const evidence = relayRuntimeGaugeEvidence(snapshot);
    assert.deepEqual(evidence.missing, [],
        `${label}: RelayNode runtime telemetry omitted required gauges: ` +
        JSON.stringify(evidence));
    assert.deepEqual(evidence.observed,
        Object.fromEntries(RELAY_RUNTIME_GAUGES.map((name) => [name, 0])),
        `${label}: RelayNode runtime gauges did not drain to zero`);
    return evidence;
}

function assertRelayDrainPerformance(snapshots, label) {
    const entries = snapshots.filter((snapshot) => snapshot?.runtime !== undefined);
    assert.ok(entries.length > 0, `${label}: RelayNode runtime telemetry missing`);
    for (const snapshot of entries) {
        const runtime = snapshot.runtime;
        assert.ok(Number.isFinite(runtime.serverFrameMaxDrainDurationMillis),
            `${label}: RelayNode drain duration telemetry missing`);
        assert.ok(runtime.serverFrameMaxDrainDurationMillis <=
            RELAY_DRAIN_MAX_DURATION_MILLIS,
            `${label}: RelayNode server-frame drain reached ` +
            `${runtime.serverFrameMaxDrainDurationMillis}ms`);
        assert.equal(runtime.serverFrameSendErrors, 0,
            `${label}: RelayNode server-frame send error detected`);
    }
    const final = entries.at(-1).runtime;
    assert.equal(final.serverFrameBufferedBytes, 0,
        `${label}: RelayNode retained server-frame bytes`);
    assert.equal(final.activeServerFrameDrainHandles, 0,
        `${label}: RelayNode retained an active server-frame drain`);
}

function relayRuntimeIsClean(snapshot) {
    return snapshot !== undefined && snapshot.activeConnections === 0 &&
        (snapshot.target === undefined || snapshot.target.activeConnections === 0) &&
        (externalMode || relayRuntimeGaugesAreZero(snapshot));
}

function clientLivenessEvidence(client) {
    return {
        id: client.id,
        username: client.username,
        wave: client.wave,
        phase: client.phase,
        loginFinished: client.loginFinished,
        playLoginPackets: client.playLoginPackets,
        chunkPackets: client.chunkPackets,
        failure: client.failure === undefined ? null : String(client.failure),
        onlineEncryption: client.onlineEncryptionResult(),
    };
}

function assertSoakLiveness(clients, browser, relay, label) {
    const observedClients = clients.map((client) => {
        const evidence = clientLivenessEvidence(client);
        assert.equal(evidence.failure, null,
            `${label}: ${evidence.username} reported failure: ${evidence.failure}`);
        assert.equal(evidence.phase, "play",
            `${label}: ${evidence.username} is not in PLAY`);
        assert.equal(evidence.loginFinished, true,
            `${label}: ${evidence.username} did not finish LOGIN`);
        assert.ok(evidence.playLoginPackets > 0,
            `${label}: ${evidence.username} did not receive PLAY login`);
        assert.ok(evidence.chunkPackets >= minimumChunkPackets,
            `${label}: ${evidence.username} received only ${evidence.chunkPackets} chunks`);
        assertClientSecurity(client, `${label} ${evidence.username}`);
        return evidence;
    });
    assert.equal(browser.activeChannels, clientCount,
        `${label}: browser active channel count drifted`);
    assert.equal(browser.activeWebSockets, clientCount,
        `${label}: browser active WebSocket count drifted`);
    if (!externalMode) {
        assert.equal(relay.target !== undefined, true,
            `${label}: RelayNode omitted the target route telemetry`);
        assert.equal(relay.activeConnections, clientCount,
            `${label}: RelayNode active connection count drifted`);
        assert.equal(relay.target.activeConnections, clientCount,
            `${label}: RelayNode active target connection count drifted`);
    }
    else {
        assert.ok(relay === undefined || relay.activeConnections === undefined ||
            relay.activeConnections === clientCount,
        `${label}: external RelayNode active connection count drifted`);
        assert.ok(relay?.target === undefined ||
            relay.target.activeConnections === clientCount,
        `${label}: external RelayNode target connection count drifted`);
    }
    const relayGauges = relayRuntimeGaugeEvidence(relay);
    if (!externalMode) assertRelayRuntimeGaugesZero(relay, `${label}: RelayNode gauges`);
    return {
        required: {
            clientCount,
            phase: "play",
            loginFinished: true,
            minimumChunkPackets,
            failure: null,
            onlineEncryptionFailClosed: true,
            browserActiveChannels: clientCount,
            browserActiveWebSockets: clientCount,
            relayActiveConnections: clientCount,
            relayTargetActiveConnections: clientCount,
            relayRuntimeGaugesZero: [...RELAY_RUNTIME_GAUGES],
        },
        observed: {
            clients: observedClients,
            browser: {
                activeChannels: browser.activeChannels,
                activeWebSockets: browser.activeWebSockets,
            },
            relay: {
                activeConnections: relay.activeConnections,
                targetActiveConnections: relay.target.activeConnections,
            },
            relayRuntimeGauges: relayGauges,
        },
        ok: true,
    };
}

function forceAbnormalTransportDrop(runtime, clients, wave) {
    const probes = clients.map((client) => {
        const entry = runtime.bridge.channels.get(client.id);
        assert.ok(entry && !entry.closed && entry.ws,
            `reconnect wave ${wave} client ${client.id} has no live bridge entry`);
        const tail = Buffer.from(`gaius-reconnect-tail:${wave}:${client.id}`, "utf8");
        assert.equal(typeof entry.ws.onmessage, "function",
            "live bridge entry omitted its WebSocket message handler");
        entry.ws.onmessage({
            data: tail.buffer.slice(tail.byteOffset, tail.byteOffset + tail.byteLength),
        });
        return {
            id: client.id,
            entry,
            tail,
            terminatedAt: undefined,
        };
    });
    for (const probe of probes) {
        probe.terminatedAt = performance.now();
        probe.entry.ws.terminate();
    }
    return probes;
}

async function captureAbnormalTransportDrop(runtime, probes) {
    const evidence = [];
    for (const probe of probes) {
        await waitFor(
            () => probe.entry.errors.length > 0 &&
                runtime.bridge.hasPendingInbound(probe.id),
            `abnormal close evidence for channel ${probe.id}`, 5000,
            () => JSON.stringify({
                closed: probe.entry.closed,
                errors: probe.entry.errors,
                pendingInbound: runtime.bridge.hasPendingInbound(probe.id),
            }));
        const chunks = [];
        const deadline = Date.now() + 5000;
        while (runtime.bridge.hasPendingInbound(probe.id)) {
            const chunk = runtime.bridge.pollInbound(probe.id);
            if (chunk === null) {
                if (Date.now() >= deadline) {
                    throw new Error(`timed out draining channel ${probe.id} close tail`);
                }
                await delay(0);
                continue;
            }
            chunks.push(Buffer.from(chunk));
        }
        const drained = Buffer.concat(chunks);
        const tailOffset = drained.indexOf(probe.tail);
        assert.ok(tailOffset >= 0,
            `abnormal close discarded channel ${probe.id} synthetic inbound marker`);
        assert.equal(tailOffset + probe.tail.byteLength, drained.byteLength,
            `channel ${probe.id} synthetic inbound marker was not the final queued bytes`);
        const error = runtime.bridge.pollError(probe.id);
        assert.match(String(error), /^WebSocket transport closed: (?!1000\b)\d+/,
            `abnormal close omitted channel ${probe.id} transport error`);
        assert.equal(runtime.bridge.channels.get(probe.id), probe.entry,
            `abnormal close retired channel ${probe.id} before Java final close`);
        evidence.push({
            channelId: probe.id,
            closeError: error,
            nonNormalClose: true,
            retainedEntry: true,
            label: "synthetic-inbound-marker",
            syntheticInboundMarker: {
                preserved: true,
                networkFrame: false,
                source: "websocket-onmessage-before-abnormal-close",
                markerSha256: createHash("sha256").update(probe.tail).digest("hex"),
                drainedChunks: chunks.length,
                drainedBytes: drained.byteLength,
                markerOffset: tailOffset,
                finalQueuedBytes: true,
            },
        });
    }
    return evidence;
}

function assertBrowserRuntimeClean(snapshot, label) {
    assert.deepEqual({
        activeChannels: snapshot.activeChannels,
        activeWebSockets: snapshot.activeWebSockets,
        queuedBytes: snapshot.queuedBytes,
        queuedFrames: snapshot.queuedFrames,
        inboundQueuedBytes: snapshot.inboundQueuedBytes,
        activeRelayTargetLeases: snapshot.activeRelayTargetLeases,
    }, {
        activeChannels: 0,
        activeWebSockets: 0,
        queuedBytes: 0,
        queuedFrames: 0,
        inboundQueuedBytes: 0,
        activeRelayTargetLeases: 0,
    }, `${label} retained browser transport state`);
    assertBrowserRuntimeCleanupGaugesZero(snapshot, label);
}

async function waitForBrowserRuntimeCleanup(runtime, label) {
    await waitFor(() => {
        const snapshot = browserRuntimeSnapshot(runtime);
        return snapshot.activeChannels === 0 &&
            snapshot.activeWebSockets === 0 &&
            snapshot.queuedBytes === 0 &&
            snapshot.queuedFrames === 0 &&
            snapshot.inboundQueuedBytes === 0 &&
            snapshot.activeRelayTargetLeases === 0 &&
            browserRuntimeCleanupGaugeEvidence(snapshot).allZero;
    }, label, 5000, () => JSON.stringify(browserRuntimeSnapshot(runtime)));
}

function browserFullPathPerformanceContract() {
    return {
        mode: externalMode
            ? "external-full-path"
            : acceptanceMode ? "strict-acceptance" : "compatible-smoke",
        externalRelay: externalMode ? {
            relayUrl: externalRelayUrl,
            target: externalTarget?.text,
            runtimeTelemetryRequired: false,
            note: "External node runtime gauges are optional evidence; browser/client PLAY and cleanup remain required",
        } : null,
        configurationPrompts: {
            autoAccept: acceptServerPrompts,
            requestedDialogAction: requestedDialogAction ?? null,
            inputOverridesProvided: Object.keys(dialogInputValues).length > 0,
            supportedPackets: ["clientboundShowDialog", "clientboundCodeOfConduct"],
        },
        clientStartDelayMillis: clientStartDelayMs,
        strictAcceptanceTarget: acceptanceMode ? { ...STRICT_ACCEPTANCE_TARGET } : null,
        canonicalProfiles: CANONICAL_PROFILES,
        relayRuntimeGauges: [...RELAY_RUNTIME_GAUGES],
        relayDrainMaxDurationMillis: RELAY_DRAIN_MAX_DURATION_MILLIS,
        relayDrainSendErrors: 0,
        relayDrainCleanupRequired: true,
        browserRuntimeCleanupGauges: [...BROWSER_RUNTIME_CLEANUP_GAUGES],
        syntheticMarkerLabel: "synthetic-inbound-marker",
        runtimeJavaPolicy: acceptanceMode
            ? { ...STRICT_RUNTIME_JAVA_POLICY }
            : null,
        minimumChunkPackets,
        soakMillis: soakMs,
        reconnectWaves,
        lifecycleCleanupRequired: true,
        reconnect: {
            simultaneousDrop: true,
            abnormalTransportDrop: true,
            transportCloseErrorRetained: true,
            syntheticInboundMarkerRetained: true,
            javaFinalCloseAfterTransportDrop: true,
            freshChannelIds: true,
            sameAccountIdentity: true,
            freshProtocolBuffers: true,
            freshEncryptionState: true,
            requiredSessionChecksPerClientPerWave: {
                joins: 1,
                hasJoined: 1,
            },
            requiredMilestonesPerWave: [
                "abnormal-transport-drop",
                "close-error-retained",
                "synthetic-inbound-marker-retained",
                "java-final-close-all-zero",
                "relay-connected",
                "login-finished",
                "configuration-finished",
                "play-login",
                "first-chunk",
                `chunk-${minimumChunkPackets}`,
            ],
        },
        requiredMilestones: [
            "relay-connected",
            "login-finished",
            "configuration-finished",
            "play-login",
            "first-chunk",
            `chunk-${minimumChunkPackets}`,
        ],
    };
}

async function fetchRelayRuntime(port, targetPort) {
    const runtimeUrl = new URL(`http://127.0.0.1:${port}/relay-node/v1`);
    if (Number.isInteger(targetPort)) {
        runtimeUrl.searchParams.set("host", "127.0.0.1");
        runtimeUrl.searchParams.set("port", String(targetPort));
    }
    const response = await fetch(runtimeUrl, {
        headers: {
            origin,
            authorization: `Bearer ${relayToken}`,
        },
    });
    if (!response.ok) {
        throw new Error(`RelayNode runtime manifest returned ${response.status}`);
    }
    const manifest = await response.json();
    if (!manifest?.capabilities?.includes("runtime-telemetry") ||
        !Number.isSafeInteger(manifest.runtime?.rssBytes) ||
        !Number.isSafeInteger(manifest.runtime?.cpuUserMicros) ||
        !Number.isSafeInteger(manifest.runtime?.cpuSystemMicros)) {
        throw new Error("RelayNode runtime manifest omitted bounded performance telemetry");
    }
    if (!Number.isSafeInteger(manifest.activeConnections) ||
        !Number.isSafeInteger(manifest.availableConnections)) {
        throw new Error("RelayNode runtime manifest omitted active connection gauges");
    }
    const targetManifest = Number.isInteger(targetPort) ? manifest.target : undefined;
    const target = targetManifest !== undefined &&
        Number.isSafeInteger(targetManifest.activeConnections) &&
        Number.isSafeInteger(targetManifest.totalConnections) &&
        typeof targetManifest.recentlyReachable === "boolean" &&
        (targetManifest.lastSuccessAgeMs === null ||
            Number.isSafeInteger(targetManifest.lastSuccessAgeMs))
        ? targetManifest
        : undefined;
    const runtimeGaugeEvidence = relayRuntimeGaugeEvidence(manifest);
    if (acceptanceMode && !runtimeGaugeEvidence.available) {
        throw new Error("strict acceptance requires every RelayNode runtime gauge: " +
            JSON.stringify(runtimeGaugeEvidence));
    }
    return {
        activeConnections: manifest.activeConnections,
        availableConnections: manifest.availableConnections,
        target: target === undefined ? undefined : {
            activeConnections: target.activeConnections,
            totalConnections: target.totalConnections,
            recentlyReachable: target.recentlyReachable,
            lastSuccessAgeMs: target.lastSuccessAgeMs,
        },
        targetEvidence: {
            source: "/relay-node/v1?host=127.0.0.1&port=target",
            available: target !== undefined,
            observed: targetManifest === undefined ? null : targetManifest,
            note: target === undefined
                ? "RelayNode target route telemetry was absent or partial; no zero was synthesized"
                : "RelayNode target route fields observed directly",
        },
        runtime: manifest.runtime,
        runtimeGaugeEvidence,
    };
}

async function fetchExternalRelayRuntime(relayUrl, target, token) {
    const runtimeUrl = new URL(relayUrl);
    runtimeUrl.protocol = runtimeUrl.protocol === "wss:" ? "https:" : "http:";
    runtimeUrl.pathname = "/relay-node/v1";
    runtimeUrl.search = "";
    runtimeUrl.searchParams.set("host", target.host);
    runtimeUrl.searchParams.set("port", String(target.port));
    const headers = { origin };
    if (token !== undefined && token.length > 0) {
        headers.authorization = `Bearer ${token}`;
    }
    const response = await fetch(runtimeUrl, {
        headers,
        signal: AbortSignal.timeout(15000),
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
        throw new Error(`External RelayNode runtime manifest returned ${response.status}`);
    }
    const targetManifest = body?.target;
    const targetAvailable = targetManifest !== undefined &&
        Number.isSafeInteger(targetManifest.activeConnections) &&
        Number.isSafeInteger(targetManifest.totalConnections) &&
        typeof targetManifest.recentlyReachable === "boolean" &&
        (targetManifest.lastSuccessAgeMs === null ||
            Number.isSafeInteger(targetManifest.lastSuccessAgeMs));
    const activeConnections = Number.isSafeInteger(body?.activeConnections)
        ? body.activeConnections : undefined;
    return {
        external: true,
        activeConnections,
        availableConnections: Number.isSafeInteger(body?.availableConnections)
            ? body.availableConnections : undefined,
        target: targetAvailable ? {
            activeConnections: targetManifest.activeConnections,
            totalConnections: targetManifest.totalConnections,
            recentlyReachable: targetManifest.recentlyReachable,
            lastSuccessAgeMs: targetManifest.lastSuccessAgeMs,
        } : undefined,
        targetEvidence: {
            source: runtimeUrl.href,
            available: targetAvailable,
            observed: targetManifest ?? null,
            note: targetAvailable
                ? "External RelayNode target route fields observed directly"
                : "External RelayNode omitted complete target route fields",
        },
        runtime: body?.runtime !== undefined && typeof body.runtime === "object"
            ? body.runtime : undefined,
        runtimeGaugeEvidence: relayRuntimeGaugeEvidence({ runtime: body?.runtime }),
        raw: body,
    };
}

async function waitForRelayRuntimeReader(reader, predicate, label, timeoutMillis) {
    const deadline = Date.now() + timeoutMillis;
    let snapshot;
    while (true) {
        snapshot = await reader();
        if (predicate(snapshot)) return snapshot;
        if (Date.now() >= deadline) {
            throw new Error(`Timed out waiting for ${label}: ${JSON.stringify(snapshot)}`);
        }
        await delay(100);
    }
}

async function waitForRelayRuntime(port, predicate, label, timeoutMillis, targetPort) {
    const deadline = Date.now() + timeoutMillis;
    let snapshot;
    while (true) {
        snapshot = await fetchRelayRuntime(port, targetPort);
        if (predicate(snapshot)) return snapshot;
        if (Date.now() >= deadline) {
            throw new Error(`Timed out waiting for ${label}: ${JSON.stringify(snapshot)}`);
        }
        await delay(20);
    }
}

function relayRuntimeDelta(before, after) {
    if (!before?.runtime || !after?.runtime) return null;
    const elapsed = Math.max(0,
        after.runtime.uptimeMillis - before.runtime.uptimeMillis);
    const userMicros = Math.max(0,
        after.runtime.cpuUserMicros - before.runtime.cpuUserMicros);
    const systemMicros = Math.max(0,
        after.runtime.cpuSystemMicros - before.runtime.cpuSystemMicros);
    const cpuMicros = userMicros + systemMicros;
    return {
        elapsedMillis: elapsed,
        cpuUserMicros: userMicros,
        cpuSystemMicros: systemMicros,
        cpuTotalMicros: cpuMicros,
        cpuPercentOfOneCore: elapsed > 0
            ? Number((cpuMicros / (elapsed * 10)).toFixed(3))
            : null,
        rssDeltaBytes: after.runtime.rssBytes - before.runtime.rssBytes,
        heapUsedDeltaBytes:
            after.runtime.heapUsedBytes - before.runtime.heapUsedBytes,
        externalDeltaBytes:
            after.runtime.externalBytes - before.runtime.externalBytes,
    };
}

async function printConfiguration() {
    activeProfile = await loadActiveVersionProfile();
    assertCanonicalProfile(activeProfile);
    const wireProfile = resolveWireProfile(activeProfile);
    console.log(JSON.stringify({
        profile: {
            id: activeProfile.id,
            protocolVersion: activeProfile.protocolVersion,
            worldVersion: activeProfile.worldVersion,
            javaVersion: activeProfile.javaVersion,
            serverSha1: activeProfile.official.serverSha1,
            expectedServerJarSha1: activeProfile.official.serverSha1,
            path: activeProfile.path,
            canonicalProfilePath: repositoryRelativePath(activeProfile.path),
        },
        wireProfile: {
            name: wireProfile.name,
            protocolVersion: wireProfile.protocolVersion,
        },
        clients: clientCount,
        acceptanceMode,
        strictAcceptanceTarget: acceptanceMode ? STRICT_ACCEPTANCE_TARGET : null,
        performanceContract: browserFullPathPerformanceContract(),
    }));
}

async function printJavaResolution() {
    activeProfile = await loadActiveVersionProfile();
    assertCanonicalProfile(activeProfile);
    const runtimeJava = await resolveJavaExecutable(activeProfile);
    console.log(JSON.stringify({
        profile: {
            id: activeProfile.id,
            javaVersion: activeProfile.javaVersion,
            path: repositoryRelativePath(activeProfile.path),
        },
        runtimeJavaMajor: runtimeJava.major,
        runtimeJavaExecutable: runtimeJava.executable,
        runtimeJavaSource: runtimeJava.source,
        runtimeJavaPolicy: acceptanceMode
            ? STRICT_RUNTIME_JAVA_POLICY[activeProfile.id] ?? null
            : `major-at-least-${activeProfile.javaVersion}`,
    }));
}

async function createBrowserRuntime(relayUrl, token) {
    const source = await readFile(channelSourceUrl, "utf8");
    const init = extractJsBody(source, "private static native void initBridge();");
    const outbound = extractJsBody(source,
        "private static native void initOutboundScheduler();");
    const inbound = extractJsBody(source,
        "private static native void initInboundScheduler();");
    const wsStats = {
        connections: 0,
        controlFrames: 0,
        binaryFrames: 0,
        binaryBytes: 0,
        urls: [],
        sockets: new Set(),
    };
    class BrowserWebSocket extends NodeWebSocket {
        constructor(url) {
            super(url, { origin });
            wsStats.connections++;
            wsStats.urls.push(String(url));
            wsStats.sockets.add(this);
            this.once("close", () => wsStats.sockets.delete(this));
        }

        send(data, ...rest) {
            if (typeof data === "string") {
                try {
                    const message = JSON.parse(data);
                    if (message?.type === "connect") wsStats.controlFrames++;
                }
                catch {}
            }
            else {
                wsStats.binaryFrames++;
                wsStats.binaryBytes += data?.byteLength ?? 0;
            }
            return super.send(data, ...rest);
        }
    }
    globalThis.window = globalThis;
    Object.defineProperty(globalThis, "location", {
        configurable: true,
        value: {
            href: "http://127.0.0.1:8781/Gaius.html",
            hostname: "127.0.0.1",
            protocol: "http:",
            search: "",
        },
    });
    globalThis.localStorage = { getItem: () => null };
    globalThis.WebSocket = BrowserWebSocket;
    globalThis.__gaiusDefaultRelayRegistries = false;
    globalThis.__gaiusRelayRegistryUrls = [];
    globalThis.__gaiusDirectPlugin = false;
    globalThis.__gaiusBridgeUrl = relayUrl;
    globalThis.__gaiusBridgeToken = token;
    globalThis.__gaiusBridgeUrls = [{
        name: externalMode ? "external full-path RelayNode" : "local full-path RelayNode",
        url: relayUrl,
        token,
        priority: 100,
    }];
    new Function(init)();
    new Function(outbound)();
    new Function(inbound)();
    const bridge = globalThis.__gaiusNettyBridge;
    const stats = globalThis.__gaiusNetworkStats;
    assert.ok(bridge && stats, "BrowserWebSocketChannel JSBody did not initialize");
    return {
        bridge,
        stats,
        wsStats,
        async close() {
            const sockets = [...wsStats.sockets];
            const closed = Promise.all(sockets.map((socket) =>
                socket.readyState === NodeWebSocket.CLOSED
                    ? Promise.resolve()
                    : once(socket, "close").catch(() => {})));
            for (const id of [...bridge.channels.keys()]) {
                try { bridge.close(id); } catch {}
            }
            await Promise.race([
                closed,
                delay(1000),
            ]);
            for (const socket of wsStats.sockets) {
                try { socket.terminate(); } catch {}
            }
        },
    };
}

function extractJsBody(source, marker) {
    const markerOffset = source.indexOf(marker);
    const annotationOffset = source.lastIndexOf("@JSBody(script = \"\"\"", markerOffset);
    const scriptOffset = source.indexOf("\"\"\"", annotationOffset) + 3;
    const scriptEnd = source.lastIndexOf('\"\"\")', markerOffset);
    assert.ok(markerOffset > 0 && annotationOffset > 0 && scriptEnd > scriptOffset,
        `could not extract BrowserWebSocketChannel JSBody for ${marker}`);
    return source.slice(scriptOffset, scriptEnd);
}

function createSessionServer(state) {
    return createHttpServer(async (request, response) => {
        try {
            const url = new URL(request.url ?? "/", "http://127.0.0.1");
            if (request.method === "GET" && url.pathname === "/publickeys") {
                state.publicKeyRequests++;
                sendJson(response, 200, { profilePropertyKeys: [], playerCertificateKeys: [] });
                return;
            }
            if (request.method === "POST" && url.pathname === "/session/minecraft/join") {
                const body = JSON.parse((await readBody(request)).toString("utf8"));
                const expectedProfile = state.expectedProfiles.get(body.accessToken);
                if (expectedProfile === undefined || body.selectedProfile !== expectedProfile ||
                    typeof body.serverId !== "string" || body.serverId.length === 0) {
                    sendJson(response, 403, { error: "invalid smoke join" });
                    return;
                }
                state.joins.push(body);
                response.writeHead(204);
                response.end();
                return;
            }
            if (request.method === "GET" && url.pathname === "/session/minecraft/hasJoined") {
                const query = Object.fromEntries(url.searchParams);
                state.hasJoined.push(query);
                const join = [...state.joins].reverse().find((candidate) =>
                    candidate.serverId === query.serverId &&
                    query.username === profileUsernameForId(candidate.selectedProfile));
                if (join === undefined) {
                    response.writeHead(204);
                    response.end();
                    return;
                }
                sendJson(response, 200, {
                    id: join.selectedProfile,
                    name: query.username,
                    properties: [],
                    profileActions: [],
                });
                return;
            }
            if (request.method === "GET" &&
                url.pathname.startsWith("/session/minecraft/profile/")) {
                const id = url.pathname.slice("/session/minecraft/profile/".length);
                sendJson(response, 200, {
                    id,
                    name: profileUsernameForId(id),
                    properties: [],
                    profileActions: [],
                });
                return;
            }
            sendJson(response, 404, { error: "not found" });
        }
        catch (error) {
            sendJson(response, 500, { error: String(error) });
        }
    });
}

function profileUsernameForId(id) {
    const suffix = Number.parseInt(String(id).slice(-1), 16);
    return `${usernamePrefix}${Number.isInteger(suffix) ? suffix - 1 : 0}`.slice(0, 16);
}

function sendJson(response, status, value) {
    const body = Buffer.from(JSON.stringify(value));
    response.writeHead(status, {
        "content-type": "application/json",
        "content-length": String(body.byteLength),
    });
    response.end(body);
}

async function readBody(request) {
    const chunks = [];
    let bytes = 0;
    for await (const chunk of request) {
        bytes += chunk.byteLength;
        if (bytes > 1024 * 1024) throw new Error("request body too large");
        chunks.push(chunk);
    }
    return Buffer.concat(chunks, bytes);
}

function serverProperties(port) {
    return [
        "accepts-transfers=false",
        "allow-flight=true",
        "allow-nether=false",
        "difficulty=peaceful",
        "enable-command-block=false",
        "enable-query=false",
        "enable-rcon=false",
        "enforce-secure-profile=false",
        "gamemode=creative",
        "generate-structures=false",
        "level-name=world-browser-relay",
        "level-seed=1",
        "level-type=minecraft:flat",
        "log-ips=false",
        "max-players=4",
        "motd=Gaius browser RelayNode full-path smoke",
        "network-compression-threshold=256",
        "online-mode=true",
        "pause-when-empty-seconds=0",
        "player-idle-timeout=0",
        "prevent-proxy-connections=false",
        "pvp=false",
        "rate-limit=0",
        "server-ip=127.0.0.1",
        `server-port=${port}`,
        "simulation-distance=2",
        "spawn-animals=false",
        "spawn-monsters=false",
        "spawn-npcs=false",
        "spawn-protection=0",
        "sync-chunk-writes=false",
        "use-native-transport=false",
        "view-distance=2",
        "white-list=false",
        "",
    ].join("\n");
}

function nativePath(value) {
    const text = String(value ?? "").trim().replaceAll("\\", "/");
    if (process.platform === "win32" && /^\/[A-Za-z](?:\/|$)/u.test(text)) {
        return `${text[1].toUpperCase()}:${text.slice(2)}`;
    }
    return text;
}

function resolveRepositoryPath(value) {
    const normalized = nativePath(value);
    if (!normalized) throw new Error("Configured path must not be empty");
    return path.isAbsolute(normalized)
        ? path.resolve(normalized)
        : path.resolve(repository, normalized);
}

function repositoryRelativePath(value) {
    const relative = path.relative(repository, path.resolve(value))
        .replaceAll(path.sep, "/");
    assert.ok(relative !== "" && relative !== "." &&
        !relative.startsWith("../") && relative !== ".." &&
        !path.isAbsolute(relative),
    `path is outside the repository: ${value}`);
    return relative;
}

function pathInside(parent, child) {
    const relative = path.relative(path.resolve(parent), path.resolve(child));
    return relative === "" || (relative !== ".." &&
        !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative));
}

async function loadActiveVersionProfile() {
    const config = JSON.parse(await readFile(path.join(repository, "port", "config.json"), "utf8"));
    let selected = nativePath(
        process.env.GAIUS_VERSION_PROFILE_PATH ?? config.versionProfile ?? "");
    if (!selected) throw new Error("GAIUS_VERSION_PROFILE_PATH or port/config.json.versionProfile is required");
    if (/^\d+(?:\.\d+)+$/u.test(selected)) selected = `versions/${selected}.json`;
    const relativeSelected = selected.replace(/^\.\//u, "");
    const profilePath = relativeSelected.startsWith("port/")
        ? resolveRepositoryPath(relativeSelected)
        : path.isAbsolute(relativeSelected)
            ? path.resolve(relativeSelected)
            : resolveRepositoryPath(`port/${relativeSelected}`);
    const versionsDirectory = path.join(repository, "port", "versions");
    if (!pathInside(versionsDirectory, profilePath) || !profilePath.endsWith(".json")) {
        throw new Error(`Active version profile must be a JSON file inside port/versions: ${profilePath}`);
    }
    const profile = JSON.parse(await readFile(profilePath, "utf8"));
    assert.ok(typeof profile.id === "string" && profile.id.length > 0 &&
        Number.isInteger(profile.protocolVersion) && Number.isInteger(profile.worldVersion) &&
        Number.isInteger(profile.javaVersion) &&
        typeof profile.official?.serverSha1 === "string" &&
        /^[0-9a-f]{40}$/iu.test(profile.official.serverSha1),
        `invalid version profile ${profilePath}`);
    if (acceptanceMode && path.basename(profilePath) !== `${profile.id}.json`) {
        throw new Error(
            `strict acceptance profile basename must be exactly ${profile.id}.json: ` +
            `${path.basename(profilePath)}`,
        );
    }
    return {
        ...profile,
        path: profilePath,
        official: { ...profile.official, serverSha1: profile.official.serverSha1.toLowerCase() },
    };
}

function assertCanonicalProfile(profile) {
    const canonical = CANONICAL_PROFILES[profile.id];
    if (canonical === undefined) {
        if (acceptanceMode) {
            throw new Error(`strict acceptance does not support profile ${profile.id}`);
        }
        return;
    }
    if (!acceptanceMode) return;
    assert.equal(path.basename(profile.path), `${profile.id}.json`,
        `non-canonical strict acceptance profile basename ${profile.path}`);
    assert.equal(repositoryRelativePath(profile.path), `port/versions/${profile.id}.json`,
        `non-canonical strict acceptance profile path ${profile.path}`);
    assert.deepEqual({
        protocolVersion: profile.protocolVersion,
        worldVersion: profile.worldVersion,
        javaVersion: profile.javaVersion,
        serverSha1: profile.official.serverSha1,
    }, canonical, `non-canonical strict acceptance profile ${profile.id}`);
}

function resolveWireProfile(profile) {
    const resolved = [MINECRAFT_1_21_11, MINECRAFT_26_2]
        .find((candidate) => candidate.protocolVersion === profile.protocolVersion &&
            candidate.name === profile.id);
    assert.ok(resolved, `unsupported browser full-path profile ${profile.id}/${profile.protocolVersion}`);
    return resolved;
}

async function resolveVerifiedServerJar(profile) {
    const configured = process.env.GAIUS_BROWSER_FULL_PATH_SERVER_JAR?.trim() ||
        process.env.GAIUS_SMOKE_SERVER_JAR?.trim();
    const candidate = configured !== undefined
        ? resolveRepositoryPath(configured)
        : path.join(repository, "port", "target", profile.id,
            "multiplayer-smoke-server", "server.jar");
    const info = await lstat(candidate).catch((error) => {
        if (error?.code === "ENOENT") return undefined;
        throw error;
    });
    if (info === undefined || !info.isFile() || info.isSymbolicLink()) {
        throw new Error(`Verified vanilla server.jar is required at ${candidate}; ` +
            "set GAIUS_BROWSER_FULL_PATH_SERVER_JAR to an existing profile-scoped jar");
    }
    const sha1 = createHash("sha1").update(await readFile(candidate)).digest("hex");
    assert.equal(sha1, profile.official.serverSha1,
        `${profile.id} server.jar SHA-1 mismatch: ${sha1}`);
    return { path: candidate, sha1 };
}

async function reservePort() {
    const server = createTcpServer();
    await listen(server, 0, "127.0.0.1");
    const port = server.address().port;
    await new Promise((resolve) => server.close(resolve));
    return port;
}

async function listen(server, port, host) {
    await new Promise((resolve, reject) => {
        server.once("error", reject);
        server.listen(port, host, resolve);
    });
}

function runtimeJavaMajorMeetsPolicy(profile, major) {
    if (!Number.isSafeInteger(major)) return false;
    if (acceptanceMode && profile.id === "1.21.11") return major === 21;
    return major >= profile.javaVersion;
}

function javaCandidateVariants(value) {
    if (typeof value !== "string" || value.trim() === "") return [];
    const normalized = nativePath(value.trim());
    const variants = [normalized];
    const baseName = path.basename(normalized).toLowerCase();
    if (baseName !== "java" && baseName !== "java.exe") {
        variants.push(path.join(normalized, "bin", "java"));
        variants.push(path.join(normalized, "bin", "java.exe"));
    }
    else if (path.extname(normalized) === "") {
        variants.push(`${normalized}.exe`);
    }
    return variants;
}

async function resolveJavaExecutable(profile) {
    const candidates = [];
    const addCandidate = (value, source) => {
        for (const candidate of javaCandidateVariants(value)) {
            candidates.push({ candidate, source });
        }
    };
    // The profile-specific variable is first and may name either a java
    // executable or a JDK home. This prevents a globally selected JDK from
    // silently satisfying the wrong Minecraft profile.
    const profileJavaVariable = `GAIUS_JAVA_${profile.javaVersion}`;
    addCandidate(process.env[profileJavaVariable], profileJavaVariable);
    addCandidate(process.env.GAIUS_JAVA, "GAIUS_JAVA");
    for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME]) {
        addCandidate(home, home === process.env.GAIUS_JAVA_HOME
            ? "GAIUS_JAVA_HOME" : "JAVA_HOME");
    }
    candidates.push(
        { candidate: "C:\\Program Files\\Java\\jdk-24\\bin\\java.exe", source: "known-jdk" },
        { candidate: "C:\\Program Files\\Java\\jdk-26.0.1\\bin\\java.exe", source: "known-jdk" },
        { candidate: "java", source: "PATH" },
    );
    const diagnostics = [];
    for (const { candidate, source } of candidates) {
        const result = await probeJava(candidate);
        if (result.error === undefined &&
            runtimeJavaMajorMeetsPolicy(profile, result.major)) {
            return {
                executable: result.executable ?? candidate,
                major: result.major,
                source,
            };
        }
        const version = result.error ?? `Java ${result.major}`;
        diagnostics.push(`${candidate} [${source}]: ${version}`);
    }
    const policy = acceptanceMode && profile.id === "1.21.11"
        ? "Java 21 exactly"
        : `Java >= ${profile.javaVersion}`;
    throw new Error(`No ${policy} for ${profile.id}: ${diagnostics.join("; ")}`);
}

async function probeJava(candidate) {
    return await new Promise((resolve) => {
        let output = "";
        let settled = false;
        const finish = (value) => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            resolve(value);
        };
        const windowsScript = process.platform === "win32" &&
            /\.(?:cmd|bat)$/iu.test(candidate);
        const command = windowsScript
            ? (process.env.ComSpec ?? process.env.COMSPEC ?? "cmd.exe")
            : candidate;
        const argumentsList = windowsScript
            ? ["/d", "/c", candidate, "-version"]
            : ["-version"];
        const child = spawn(command, argumentsList, {
            stdio: ["ignore", "pipe", "pipe"],
            windowsHide: true,
            shell: false,
        });
        const timer = setTimeout(() => {
            try { child.kill(); } catch {}
            finish({ executable: candidate, error: "timed out" });
        }, 5000);
        child.stdout.setEncoding("utf8");
        child.stderr.setEncoding("utf8");
        child.stdout.on("data", (chunk) => { output += chunk; });
        child.stderr.on("data", (chunk) => { output += chunk; });
        child.once("error", (error) => {
            finish({ executable: candidate, error: error.message });
        });
        child.once("close", (code) => {
            if (code !== 0) {
                finish({ executable: candidate,
                    error: `exited ${code}: ${output.trim()}` });
                return;
            }
            const match = output.match(/version\s+["']?(\d+)/iu);
            finish(match ? { executable: candidate, major: Number(match[1]) } : {
                executable: candidate,
                error: `could not parse version: ${output.trim()}`,
            });
        });
    });
}

function minecraftServerHash(serverId, secret, publicKey) {
    const digest = createHash("sha1")
        .update(Buffer.from(serverId, "utf8"))
        .update(secret)
        .update(publicKey)
        .digest();
    let value = BigInt(`0x${digest.toString("hex")}`);
    if ((digest[0] & 0x80) !== 0) value -= 1n << BigInt(digest.byteLength * 8);
    return value.toString(16);
}

function encodePacket(id, payload = Buffer.alloc(0), compressionThreshold) {
    const packet = Buffer.concat([encodeVarInt(id), payload]);
    const body = compressionThreshold === undefined
        ? packet
        : packet.byteLength >= compressionThreshold
            ? Buffer.concat([encodeVarInt(packet.byteLength), deflateSync(packet)])
            : Buffer.concat([Buffer.from([0]), packet]);
    return Buffer.concat([encodeVarInt(body.byteLength), body]);
}

function parseTarget(value) {
    const text = String(value).trim();
    const separator = text.lastIndexOf(":");
    const host = separator > 0 ? text.slice(0, separator) : text;
    const port = separator > 0 ? Number(text.slice(separator + 1)) : 25565;
    assert.ok(host.length > 0 && Number.isInteger(port) && port >= 1 && port <= 65535,
        "GAIUS_EXTERNAL_TARGET must be host:port");
    return { host, port, text: `${host}:${port}` };
}

function decodeNetworkNbt(bytes) {
    const input = Buffer.from(bytes);
    let offset = 0;
    const maximumDepth = 32;
    const maximumCollectionLength = 65_536;

    function requireBytes(length, label) {
        if (length < 0 || offset + length > input.byteLength) {
            throw new Error(`Network NBT ${label} exceeded its packet`);
        }
    }

    function readUnsignedByte(label) {
        requireBytes(1, label);
        return input[offset++];
    }

    function readLength(label) {
        requireBytes(4, label);
        const length = input.readInt32BE(offset);
        offset += 4;
        if (length < 0 || length > maximumCollectionLength) {
            throw new Error(`Network NBT ${label} had invalid length ${length}`);
        }
        return length;
    }

    function readString(label) {
        requireBytes(2, `${label} length`);
        const length = input.readUInt16BE(offset);
        offset += 2;
        requireBytes(length, label);
        const value = input.toString("utf8", offset, offset + length);
        offset += length;
        return value;
    }

    function readPayload(type, depth) {
        if (depth > maximumDepth) throw new Error("Network NBT exceeded its maximum nesting depth");
        switch (type) {
            case 0:
                return null;
            case 1:
                requireBytes(1, "byte");
                return input.readInt8(offset++);
            case 2: {
                requireBytes(2, "short");
                const value = input.readInt16BE(offset);
                offset += 2;
                return value;
            }
            case 3: {
                requireBytes(4, "int");
                const value = input.readInt32BE(offset);
                offset += 4;
                return value;
            }
            case 4: {
                requireBytes(8, "long");
                const value = input.readBigInt64BE(offset).toString();
                offset += 8;
                return value;
            }
            case 5: {
                requireBytes(4, "float");
                const value = input.readFloatBE(offset);
                offset += 4;
                return value;
            }
            case 6: {
                requireBytes(8, "double");
                const value = input.readDoubleBE(offset);
                offset += 8;
                return value;
            }
            case 7: {
                const length = readLength("byte array");
                requireBytes(length, "byte array");
                const value = input.subarray(offset, offset + length);
                offset += length;
                return value;
            }
            case 8:
                return readString("string");
            case 9: {
                const childType = readUnsignedByte("list type");
                const length = readLength("list");
                if (childType === 0 && length !== 0) {
                    throw new Error("Network NBT used END as a non-empty list type");
                }
                return Array.from({ length }, () => readPayload(childType, depth + 1));
            }
            case 10: {
                const value = {};
                while (true) {
                    const childType = readUnsignedByte("compound type");
                    if (childType === 0) return value;
                    const name = readString("compound key");
                    if (Object.hasOwn(value, name)) {
                        throw new Error(`Network NBT repeated compound key ${name}`);
                    }
                    value[name] = readPayload(childType, depth + 1);
                }
            }
            case 11: {
                const length = readLength("int array");
                requireBytes(length * 4, "int array");
                return Array.from({ length }, () => {
                    const value = input.readInt32BE(offset);
                    offset += 4;
                    return value;
                });
            }
            case 12: {
                const length = readLength("long array");
                requireBytes(length * 8, "long array");
                return Array.from({ length }, () => {
                    const value = input.readBigInt64BE(offset).toString();
                    offset += 8;
                    return value;
                });
            }
            default:
                throw new Error(`Network NBT used unknown tag type ${type}`);
        }
    }

    const rootType = readUnsignedByte("root type");
    const value = readPayload(rootType, 0);
    if (offset !== input.byteLength) {
        throw new Error(`Network NBT left ${input.byteLength - offset} unread bytes`);
    }
    return value;
}

function inspectServerDialog(dialog) {
    const actionRoots = findNamedArrays(dialog, "actions");
    const inputRoots = findNamedArrays(dialog, "inputs");
    const actionIds = uniqueStrings(actionRoots.flatMap((root) =>
        collectNbtObjects(root)
            .filter((value) => value.type === "minecraft:dynamic/custom")
            .map((value) => value.id)
            .filter((value) => typeof value === "string")));
    const inputs = inputRoots.flatMap((root) => collectNbtObjects(root))
        .filter((value) => typeof value.key === "string" &&
            typeof value.type === "string");
    const inputDefinitions = [];
    const inputKeys = new Set();
    for (const input of inputs) {
        if (inputKeys.has(input.key)) continue;
        inputKeys.add(input.key);
        inputDefinitions.push({
            key: input.key,
            type: input.type,
            maxLength: Number.isInteger(input.max_length) ? input.max_length : undefined,
        });
    }
    const title = summarizeNbtValue(findFirstNamedValue(dialog, "title"));
    return {
        actionIds,
        inputs: inputDefinitions,
        summary: `title=${title}; actions=${actionIds.join(",") || "none"}; ` +
            `inputs=${inputDefinitions.map((input) => `${input.key}:${input.type}`).join(",") || "none"}`,
    };
}

function findNamedArrays(value, name, output = []) {
    if (Array.isArray(value)) {
        for (const child of value) findNamedArrays(child, name, output);
    }
    else if (value !== null && typeof value === "object" && !Buffer.isBuffer(value)) {
        for (const [key, child] of Object.entries(value)) {
            if (key === name && Array.isArray(child)) output.push(child);
            findNamedArrays(child, name, output);
        }
    }
    return output;
}

function collectNbtObjects(value, output = []) {
    if (Array.isArray(value)) {
        for (const child of value) collectNbtObjects(child, output);
    }
    else if (value !== null && typeof value === "object" && !Buffer.isBuffer(value)) {
        output.push(value);
        for (const child of Object.values(value)) collectNbtObjects(child, output);
    }
    return output;
}

function findFirstNamedValue(value, name) {
    if (Array.isArray(value)) {
        for (const child of value) {
            const found = findFirstNamedValue(child, name);
            if (found !== undefined) return found;
        }
    }
    else if (value !== null && typeof value === "object" && !Buffer.isBuffer(value)) {
        if (Object.hasOwn(value, name)) return value[name];
        for (const child of Object.values(value)) {
            const found = findFirstNamedValue(child, name);
            if (found !== undefined) return found;
        }
    }
    return undefined;
}

function summarizeNbtValue(value) {
    if (value === undefined) return "<untitled>";
    const encoded = typeof value === "string" ? value : JSON.stringify(value);
    return encoded.length <= 160 ? encoded : encoded.slice(0, 157) + "...";
}

function uniqueStrings(values) {
    return [...new Set(values)];
}

function selectDialogAction(actionIds) {
    if (requestedDialogAction !== undefined) {
        if (!actionIds.includes(requestedDialogAction)) {
            throw new Error(`Requested dialog action ${requestedDialogAction} was not offered by the server`);
        }
        return requestedDialogAction;
    }
    const loginAction = "authmeui:action/login";
    if (actionIds.includes(loginAction)) return loginAction;
    if (actionIds.length !== 1) {
        throw new Error(
            `Smoke requires exactly one dynamic dialog action, received ${actionIds.length}; ` +
            "set GAIUS_SMOKE_DIALOG_ACTION_ID when the intended action is known");
    }
    return actionIds[0];
}

function parseDialogInputValues(encoded) {
    if (encoded === undefined) return {};
    let parsed;
    try {
        parsed = JSON.parse(encoded);
    }
    catch (error) {
        throw new Error(`GAIUS_SMOKE_DIALOG_INPUTS_JSON is invalid JSON: ${error.message}`);
    }
    if (parsed === null || Array.isArray(parsed) || typeof parsed !== "object") {
        throw new Error("GAIUS_SMOKE_DIALOG_INPUTS_JSON must be a JSON object");
    }
    for (const [key, value] of Object.entries(parsed)) {
        if (typeof value !== "string" && typeof value !== "boolean") {
            throw new Error(`Dialog input ${key} must be a string or boolean`);
        }
    }
    return parsed;
}

function resolveDialogInputValues(inputs) {
    const values = {};
    for (const input of inputs) {
        if (input.type === "minecraft:boolean") {
            const supplied = dialogInputValues[input.key];
            if (supplied !== undefined && typeof supplied !== "boolean") {
                throw new Error(`Dialog boolean input ${input.key} requires a JSON boolean`);
            }
            values[input.key] = supplied ?? true;
        }
        else if (input.type === "minecraft:text") {
            const supplied = dialogInputValues[input.key];
            if (typeof supplied !== "string") {
                throw new Error(
                    `Dialog text input ${input.key} requires a value in ` +
                    "GAIUS_SMOKE_DIALOG_INPUTS_JSON");
            }
            if (/[^ -~]/.test(supplied)) {
                throw new Error(`Dialog text input ${input.key} must use printable ASCII in smoke`);
            }
            if (input.maxLength !== undefined && supplied.length > input.maxLength) {
                throw new Error(
                    `Dialog text input ${input.key} exceeds max_length ${input.maxLength}`);
            }
            values[input.key] = supplied;
        }
        else {
            throw new Error(`Smoke cannot safely fill ${input.key}:${input.type}`);
        }
    }
    return values;
}

function encodeCustomClickAction(actionId, values) {
    if (!/^[a-z0-9_.-]+:[a-z0-9/._-]+$/.test(actionId)) {
        throw new Error(`Server dialog supplied invalid action identifier ${actionId}`);
    }
    const nbt = encodeDialogCompound(values);
    return Buffer.concat([encodeString(actionId), encodeVarInt(nbt.byteLength), nbt]);
}

function encodeDialogCompound(values) {
    const parts = [Buffer.from([10])];
    for (const [name, value] of Object.entries(values)) {
        const key = Buffer.from(name, "utf8");
        if (key.byteLength === 0 || key.byteLength > 65_535 || /[^ -~]/.test(name)) {
            throw new Error(`Server dialog supplied unsafe input key ${name}`);
        }
        const keyLength = Buffer.allocUnsafe(2);
        keyLength.writeUInt16BE(key.byteLength);
        if (typeof value === "boolean") {
            parts.push(Buffer.from([1]), keyLength, key, Buffer.from([value ? 1 : 0]));
        }
        else if (typeof value === "string") {
            const text = Buffer.from(value, "utf8");
            if (text.byteLength > 65_535) {
                throw new Error(`Dialog text input ${name} exceeds the NBT string limit`);
            }
            const textLength = Buffer.allocUnsafe(2);
            textLength.writeUInt16BE(text.byteLength);
            parts.push(Buffer.from([8]), keyLength, key, textLength, text);
        }
        else {
            throw new Error(`Dialog input ${name} has an unsupported value type`);
        }
    }
    parts.push(Buffer.from([0]));
    return Buffer.concat(parts);
}

function encodeString(value) {
    const bytes = Buffer.from(value, "utf8");
    return Buffer.concat([encodeVarInt(bytes.byteLength), bytes]);
}

function encodeByteArray(value) {
    const bytes = Buffer.from(value);
    return Buffer.concat([encodeVarInt(bytes.byteLength), bytes]);
}

function encodeClientInformation() {
    return Buffer.concat([
        encodeString("en_us"),
        Buffer.from([6]),
        encodeVarInt(0),
        Buffer.from([1, 0x7f]),
        encodeVarInt(1),
        Buffer.from([0, 1]),
        encodeVarInt(0),
    ]);
}

function encodeVarInt(value) {
    const bytes = [];
    let remaining = value >>> 0;
    do {
        let next = remaining & 0x7f;
        remaining >>>= 7;
        if (remaining !== 0) next |= 0x80;
        bytes.push(next);
    } while (remaining !== 0);
    return Buffer.from(bytes);
}

function decodeVarInt(bytes, offset) {
    let value = 0;
    for (let index = 0; index < 5; index++) {
        const position = offset + index;
        if (position >= bytes.byteLength) return undefined;
        const next = bytes[position];
        value |= (next & 0x7f) << (index * 7);
        if ((next & 0x80) === 0) return { value, bytesRead: index + 1 };
    }
    throw new Error("Minecraft VarInt exceeded five bytes");
}

function decodeByteArray(bytes, offset) {
    const length = decodeVarInt(bytes, offset);
    if (length === undefined || length.value < 0) throw new Error("invalid byte-array length");
    const start = offset + length.bytesRead;
    const end = start + length.value;
    if (end > bytes.byteLength) throw new Error("byte array exceeded packet");
    return { value: bytes.subarray(start, end), nextOffset: end };
}

function decodeString(bytes, offset) {
    const encoded = decodeByteArray(bytes, offset);
    return { value: encoded.value.toString("utf8"), nextOffset: encoded.nextOffset };
}

function decodeReason(payload) {
    try {
        return decodeString(payload, 0).value;
    }
    catch {
        return "<malformed disconnect>";
    }
}

async function waitFor(predicate, label, timeoutMs, diagnostics) {
    const deadline = Date.now() + timeoutMs;
    while (!predicate()) {
        if (Date.now() >= deadline) {
            const suffix = typeof diagnostics === "function" ? `\n${diagnostics()}` : "";
            throw new Error(`Timed out waiting for ${label}${suffix}`);
        }
        await delay(20);
    }
}

async function stopChildProcess(child, options = {}) {
    if (child === undefined || child.exitCode !== null || child.signalCode !== null) return;
    const exited = once(child, "exit").catch(() => {});
    if (options.gracefulInput !== undefined && child.stdin !== undefined &&
        !child.stdin.destroyed) {
        // The Java process can win the startup/exit race between the check and
        // write. Swallow that expected EPIPE instead of masking the smoke error.
        child.stdin.once("error", () => {});
        try { child.stdin.write(options.gracefulInput, () => {}); } catch {}
    }
    await Promise.race([exited, delay(options.gracefulTimeoutMs ?? 15000)]);
    if (child.exitCode !== null || child.signalCode !== null) return;
    try { child.kill("SIGTERM"); } catch {}
    await Promise.race([exited, delay(5000)]);
}

async function closeHttpServer(server) {
    if (!server?.listening) return;
    await new Promise((resolve) => {
        let settled = false;
        let forceCloseTimer;
        const finish = () => {
            if (settled) return;
            settled = true;
            clearTimeout(forceCloseTimer);
            resolve();
        };
        forceCloseTimer = setTimeout(() => {
            server.closeAllConnections?.();
        }, 5000);
        server.close(finish);
        server.closeIdleConnections?.();
    });
}

if (printJavaResolutionOnly) await printJavaResolution();
else if (printConfigOnly) await printConfiguration();
else await runSmoke();
