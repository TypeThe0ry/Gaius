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
const requestedClients = Number.parseInt(
    process.env.GAIUS_BROWSER_FULL_PATH_CLIENTS ?? "2", 10);
const clientCount = Number.isInteger(requestedClients)
    ? Math.max(1, Math.min(4, requestedClients))
    : 2;
const soakMs = Math.max(0, Number.parseInt(
    process.env.GAIUS_BROWSER_FULL_PATH_SOAK_MS ?? "1000", 10) || 0);
const requestedMinimumChunkPackets = Number.parseInt(
    process.env.GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS ?? "9", 10);
const minimumChunkPackets = Number.isInteger(requestedMinimumChunkPackets)
    ? Math.max(1, Math.min(128, requestedMinimumChunkPackets))
    : 9;
const printConfigOnly = process.argv.includes("--print-config");
const smokeStartedAt = performance.now();

let activeProfile;

async function runSmoke() {
    activeProfile = await loadActiveVersionProfile();
    const wireProfile = resolveWireProfile(activeProfile);
    const verifiedServerJar = await resolveVerifiedServerJar(activeProfile);
    const serverJar = verifiedServerJar.path;
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
    let browserRuntime;
    let failure;
    let serverSpawnError;
    let relaySpawnError;

try {
    await listen(sessionServer, 0, "127.0.0.1");
    sessionPort = sessionServer.address().port;
    await writeFile(path.join(workDirectory, "eula.txt"), "eula=true\n");

    // The port is allocated before writing server.properties so the vanilla
    // child never races a later listener. Keep the generated world/evidence
    // directory; this smoke is intentionally post-mortem friendly.
    const minecraftPort = await reservePort();
    await writeFile(path.join(workDirectory, "server.properties"),
        serverProperties(minecraftPort));
    const sessionBaseUrl = `http://127.0.0.1:${sessionPort}`;
    const javaExecutable = await resolveJavaExecutable(activeProfile.javaVersion);
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

    browserRuntime = await createBrowserRuntime(relayPort, relayToken);
    const clients = Array.from({ length: clientCount }, (_, index) =>
        new BrowserMinecraftClient({
            id: 700 + index,
            index,
            profile: wireProfile,
            bridge: browserRuntime.bridge,
            stats: browserRuntime.stats,
            host: "127.0.0.1",
            port: minecraftPort,
            sessionUrl: sessionBaseUrl,
        }));
    const pollTimer = setInterval(() => {
        for (const client of clients) client.poll();
    }, 1);
    try {
        await Promise.all(clients.map((client) => client.connect()));
        await waitFor(
            () => clients.every((client) => client.failure === undefined &&
                client.playLoginPackets > 0 &&
                client.chunkPackets >= minimumChunkPackets),
            "browser Relay Minecraft PLAY/chunk", 90000,
            () => JSON.stringify(clients.map((client) => client.diagnostics())));
        for (const client of clients) client.checkError();
        if (soakMs > 0) {
            await delay(soakMs);
        }
    }
    finally {
        clearInterval(pollTimer);
        for (const client of clients) client.close();
        await waitFor(() => browserRuntime.bridge.channels.size === 0 &&
            browserRuntime.wsStats.sockets.size === 0,
        "browser Relay transport cleanup", 5000, () => JSON.stringify({
            activeChannels: browserRuntime.bridge.channels.size,
            activeWebSockets: browserRuntime.wsStats.sockets.size,
            queuedBytes: browserRuntime.stats.queuedBytes,
            queuedFrames: browserRuntime.stats.queuedFrames,
            inboundQueuedBytes: browserRuntime.stats.inboundQueuedBytes,
            activeRelayTargetLeases: browserRuntime.stats.activeRelayTargetLeases,
        })).catch(() => {
            // Preserve the primary protocol failure. Successful runs assert every
            // cleanup counter below with a more specific lifecycle error.
        });
    }

    const phases = clients.flatMap((client) => client.connectPhases);
    const relayConnections = phases.filter((event) => event.phase === "relay-connected");
    assert.equal(relayConnections.length, clientCount,
        "browser transport did not establish one real RelayNode WebSocket per client");
    assert.equal(browserRuntime.wsStats.connections, clientCount,
        "browser transport opened an unexpected number of WebSocket tunnels");
    assert.ok(browserRuntime.wsStats.urls.every((url) =>
        url === `ws://127.0.0.1:${relayPort}/tunnel`),
    "browser transport connected to a relay other than the local test node");
    assert.equal(browserRuntime.stats.relayTargetAttestationFailures, 0,
        "Relay target attestation rejected a valid browser tunnel");
    assert.ok(browserRuntime.wsStats.controlFrames >= clientCount,
        "browser runtime did not send WebSocket connect controls");
    assert.ok(browserRuntime.wsStats.binaryBytes > 0,
        "browser runtime did not send binary WebSocket frames");
    assert.equal(sessionState.joins.length, clientCount,
        "vanilla online-mode login did not produce one authenticated session join per client");
    assert.equal(sessionState.hasJoined.length, clientCount,
        "vanilla online-mode login did not verify one hasJoined request per client");
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

    const result = {
        ok: true,
        transport: {
            browserChannelSource: fileURLToPath(channelSourceUrl),
            browserJsBody: true,
            teaVmBuildRequired: false,
            realWebSocketFraming: true,
            relayUrl: `ws://127.0.0.1:${relayPort}/tunnel`,
            clients: clientCount,
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
        },
        profile: {
            id: activeProfile.id,
            protocolVersion: activeProfile.protocolVersion,
            worldVersion: activeProfile.worldVersion,
            javaVersion: activeProfile.javaVersion,
            serverJar,
            serverJarSha1: verifiedServerJar.sha1,
            expectedServerJarSha1: activeProfile.official.serverSha1,
        },
        vanilla: {
            onlineMode: true,
            unmodifiedServerJar: true,
            pluginsInstalled: false,
            workDirectory,
        },
        session: {
            joins: sessionState.joins.length,
            hasJoined: sessionState.hasJoined.length,
            publicKeyRequests: sessionState.publicKeyRequests,
        },
        clients: clients.map((client) => client.result()),
        relayPhases: phases,
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
        this.sessionJoin = false;
        this.loginFinished = false;
        this.configurationFinished = false;
        this.configurationCycles = 0;
        this.reconfigurationRequests = 0;
        this.configurationPackets = 0;
        this.playPackets = 0;
        this.playLoginPackets = 0;
        this.chunkPackets = 0;
        this.playerLoadedSent = false;
        this.connectPhases = [];
        this.failure = undefined;
        this.closed = false;
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
        if (this.closed) return;
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
            if (packetId === this.profile.configuration.clientboundDisconnect) {
                throw new Error(`${this.username}: server disconnected during configuration`);
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
        if (packetId === this.profile.play.clientboundDisconnect) {
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

    close() {
        if (this.closed) return;
        this.closed = true;
        this.closedAt = performance.now();
        try { this.bridge.close(this.id); } catch {}
    }

    diagnostics() {
        return {
            username: this.username,
            id: this.id,
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
            reconfigurationRequests: this.reconfigurationRequests,
            playPackets: this.playPackets,
            playLoginPackets: this.playLoginPackets,
            chunkPackets: this.chunkPackets,
            bufferedBytes: this.buffer.byteLength,
            minimumChunkPackets,
            inboundFrames: this.inboundFrames,
            inboundBytes: this.inboundBytes,
            outboundFrames: this.outboundFrames,
            outboundBytes: this.outboundBytes,
            decodedPackets: this.decodedPackets,
            maximumBufferedBytes: this.maximumBufferedBytes,
            failure: this.failure === undefined ? null : String(this.failure),
        };
    }

    result() {
        return {
            ...this.diagnostics(),
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
}

function elapsedMillis(start, end) {
    if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return null;
    return Number((end - start).toFixed(3));
}

function ratePerSecond(value, millis) {
    if (!Number.isFinite(value) || !Number.isFinite(millis) || millis <= 0) return null;
    return Number((value * 1000 / millis).toFixed(3));
}

function browserFullPathPerformanceContract() {
    return {
        minimumChunkPackets,
        soakMillis: soakMs,
        lifecycleCleanupRequired: true,
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

async function printConfiguration() {
    activeProfile = await loadActiveVersionProfile();
    const wireProfile = resolveWireProfile(activeProfile);
    console.log(JSON.stringify({
        profile: {
            id: activeProfile.id,
            protocolVersion: activeProfile.protocolVersion,
            worldVersion: activeProfile.worldVersion,
            javaVersion: activeProfile.javaVersion,
            path: activeProfile.path,
        },
        wireProfile: {
            name: wireProfile.name,
            protocolVersion: wireProfile.protocolVersion,
        },
        clients: clientCount,
        performanceContract: browserFullPathPerformanceContract(),
    }));
}

async function createBrowserRuntime(port, token) {
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
    globalThis.__gaiusBridgeUrl = `ws://127.0.0.1:${port}/tunnel`;
    globalThis.__gaiusBridgeToken = token;
    globalThis.__gaiusBridgeUrls = [{
        name: "local full-path RelayNode",
        url: `ws://127.0.0.1:${port}/tunnel`,
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
            for (const id of [700, 701, 702, 703]) {
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
    return {
        ...profile,
        path: profilePath,
        official: { ...profile.official, serverSha1: profile.official.serverSha1.toLowerCase() },
    };
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

async function resolveJavaExecutable(requiredVersion) {
    const candidates = [];
    const addCandidate = (value) => {
        if (!value) return;
        const candidate = nativePath(value);
        candidates.push(candidate);
        if (path.extname(candidate) === "" && /[\\/]/u.test(candidate)) {
            candidates.push(`${candidate}.exe`);
        }
    };
    addCandidate(process.env.GAIUS_JAVA);
    for (const home of [process.env.GAIUS_JAVA_HOME, process.env.JAVA_HOME]) {
        if (!home) continue;
        const normalizedHome = nativePath(home);
        addCandidate(path.join(normalizedHome, "bin", "java"));
        addCandidate(path.join(normalizedHome, "bin", "java.exe"));
    }
    candidates.push(
        "C:\\Program Files\\Java\\jdk-24\\bin\\java.exe",
        "C:\\Program Files\\Java\\jdk-26.0.1\\bin\\java.exe",
        "java",
    );
    const diagnostics = [];
    for (const candidate of [...new Set(candidates)]) {
        const result = await probeJava(candidate);
        if (result.error === undefined && result.major >= requiredVersion) return candidate;
        diagnostics.push(`${candidate}: ${result.error ?? `Java ${result.major}`}`);
    }
    throw new Error(`No Java >= ${requiredVersion} for ${activeProfile.id}: ${diagnostics.join("; ")}`);
}

async function probeJava(candidate) {
    return await new Promise((resolve) => {
        let output = "";
        const child = spawn(candidate, ["-version"], {
            stdio: ["ignore", "pipe", "pipe"],
            windowsHide: true,
        });
        const timer = setTimeout(() => {
            child.kill();
            resolve({ error: "timed out" });
        }, 5000);
        child.stdout.setEncoding("utf8");
        child.stderr.setEncoding("utf8");
        child.stdout.on("data", (chunk) => { output += chunk; });
        child.stderr.on("data", (chunk) => { output += chunk; });
        child.once("error", (error) => {
            clearTimeout(timer);
            resolve({ error: error.message });
        });
        child.once("close", (code) => {
            clearTimeout(timer);
            if (code !== 0) {
                resolve({ error: `exited ${code}: ${output.trim()}` });
                return;
            }
            const match = output.match(/version\s+["']?(\d+)/iu);
            resolve(match ? { major: Number(match[1]) } :
                { error: `could not parse version: ${output.trim()}` });
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

if (printConfigOnly) await printConfiguration();
else await runSmoke();
