import { spawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import {
    constants as cryptoConstants,
    createCipheriv,
    createDecipheriv,
    createHash,
    publicEncrypt,
    randomBytes,
} from "node:crypto";
import { createServer } from "node:net";
import { createServer as createHttpServer } from "node:http";
import { once } from "node:events";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";
import path from "node:path";
import { deflateSync, inflateSync } from "node:zlib";
import { WebSocket } from "./node_modules/ws/wrapper.mjs";
import { parseConnectRequest } from "./dist/policy.js";
import {
    decodeClientboundLoginDistances,
    MINECRAFT_1_21_11,
    MINECRAFT_26_2,
} from "./dist/protocol.js";

const host = "127.0.0.1";
const origin = "http://127.0.0.1:8781";
const bridgeToken = "relay-smoke-token";
const directory = fileURLToPath(new URL(".", import.meta.url));
const repository = fileURLToPath(new URL("../../", import.meta.url));
const requestedSmokeVersion = process.env.GAIUS_SMOKE_MINECRAFT_VERSION ??
        process.env.GAIUS_SMOKE_PROTOCOL_VERSION;
const activeVersionProfile = await loadActiveVersionProfile(requestedSmokeVersion);
const minecraftHost = process.env.GAIUS_SMOKE_MINECRAFT_HOST;
const minecraftPort = parseMinecraftPort(process.env.GAIUS_SMOKE_MINECRAFT_PORT ?? "25565");
const minecraftSessionUrl = process.env.GAIUS_SMOKE_SESSION_URL;
const minecraftAccessToken = process.env.GAIUS_SMOKE_ACCESS_TOKEN ?? "gaius-smoke-token";
const minecraftProfileId = process.env.GAIUS_SMOKE_PROFILE_ID ??
        "00000000000040008000000000000002";
const minecraftUsername = process.env.GAIUS_SMOKE_USERNAME ?? "GaiusSmoke";
const minecraftProfile = resolveSmokeMinecraftProfile(
        requestedSmokeVersion ?? activeVersionProfile.id);
if (activeVersionProfile.id !== minecraftProfile.name) {
    throw new Error(`Smoke profile ${activeVersionProfile.id} does not match ` +
        `Minecraft protocol profile ${minecraftProfile.name}`);
}
const dnsTransientHost = "dns-transient.gaius.test";
const dnsPermanentHost = "dns-permanent.gaius.test";
const srvTransientHost = "srv-transient.gaius.test";
const minecraftPlaySoakMs = Math.max(
        0,
        Number.parseInt(process.env.GAIUS_SMOKE_PLAY_SOAK_MS ?? "0", 10) || 0);
const minecraftClientViewDistance = parseBoundedInteger(
        "GAIUS_SMOKE_CLIENT_VIEW_DISTANCE", 6, 2, 32);
const minecraftDesiredChunksPerTick = parseBoundedFloat(
        "GAIUS_SMOKE_DESIRED_CHUNKS_PER_TICK", 64, 0.01, 64);
const acceptServerPrompts =
        process.env.GAIUS_SMOKE_ACCEPT_SERVER_PROMPTS === "1" ||
        process.env.GAIUS_SMOKE_ACCEPT_DIALOGS === "1";
const requestedDialogAction = process.env.GAIUS_SMOKE_DIALOG_ACTION_ID;
const dialogInputValues = parseDialogInputValues(
        process.env.GAIUS_SMOKE_DIALOG_INPUTS_JSON);
testConnectRequestNormalization();
const bridgePort = await reservePort();
const fixture = createServer();
await new Promise((resolve, reject) => {
    fixture.once("error", reject);
    fixture.listen(0, host, resolve);
});
const fixturePort = fixture.address().port;
const resourcePackPayload = Buffer.alloc(20 * 1024 * 1024);
for (let index = 0; index < resourcePackPayload.byteLength; index++) {
    resourcePackPayload[index] = index & 0xff;
}
const resourcePackHash = createHash("sha1").update(resourcePackPayload).digest("hex");
let resourcePackAttempts = 0;
let slowResourcePackAttempts = 0;
let slowResourcePackClosed = false;
const resourcePackFixture = createHttpServer((request, response) => {
    const requestUrl = new URL(request.url ?? "/", `http://${host}`);
    if (requestUrl.pathname === "/slow-resource-pack.zip") {
        slowResourcePackAttempts++;
        response.writeHead(200, {
            "content-type": "application/zip",
            "content-length": String(resourcePackPayload.byteLength),
        });
        let offset = 0;
        const interval = setInterval(() => {
            if (response.destroyed || offset >= resourcePackPayload.byteLength) {
                clearInterval(interval);
                if (!response.destroyed) response.end();
                return;
            }
            const next = Math.min(offset + 64 * 1024, resourcePackPayload.byteLength);
            response.write(resourcePackPayload.subarray(offset, next));
            offset = next;
        }, 25);
        response.once("close", () => {
            clearInterval(interval);
            slowResourcePackClosed = true;
        });
        return;
    }
    resourcePackAttempts++;
    response.writeHead(200, {
        "content-type": "application/zip",
        "content-length": String(resourcePackPayload.byteLength),
    });
    if (resourcePackAttempts < 3) {
        // End the response cleanly after a short body while retaining the
        // full Content-Length. This specifically exercises the RelayNode's
        // declared-length check rather than relying only on a socket reset.
        response.end(resourcePackPayload.subarray(0, 1024 * 1024));
        return;
    }
    response.end(resourcePackPayload);
});
await new Promise((resolve, reject) => {
    resourcePackFixture.once("error", reject);
    resourcePackFixture.listen(0, host, resolve);
});
const resourcePackFixturePort = resourcePackFixture.address().port;

let fixtureSocket;
let fixtureTcpBytes = 0;
let echoEnabled = true;
let proxiedKeepAlives = 0;
let proxiedPlayKeepAlives = 0;
let proxiedPlayTicks = 0;
fixture.on("connection", (socket) => {
    fixtureSocket = socket;
    socket.setNoDelay(true);
    socket.on("data", (chunk) => {
        fixtureTcpBytes += chunk.byteLength;
        if (isVanillaKeepAlive(chunk)) {
            proxiedKeepAlives++;
            return;
        }
        if (isPlayKeepAlive(chunk)) {
            proxiedPlayKeepAlives++;
            return;
        }
        if (isClientTickEnd(chunk)) {
            proxiedPlayTicks++;
            return;
        }
        if (echoEnabled) {
            socket.write(chunk);
        }
    });
});

const bridge = spawn(process.execPath, ["dist/main.js"], {
    cwd: directory,
    env: {
        ...process.env,
        NODE_ENV: "test",
        GAIUS_BRIDGE_HOST: host,
        GAIUS_BRIDGE_PORT: String(bridgePort),
        GAIUS_ALLOWED_ORIGINS: origin,
        GAIUS_ALLOWED_HOSTS: [
            host,
            minecraftHost,
            dnsTransientHost,
            dnsPermanentHost,
            srvTransientHost,
        ].filter(Boolean).join(","),
        GAIUS_ALLOWED_RESOURCE_PACK_HOSTS: [host, "localhost"].join(","),
        GAIUS_BRIDGE_TOKEN: bridgeToken,
        GAIUS_IDLE_TIMEOUT_MS: "60000",
        GAIUS_DNS_RETRY_ATTEMPTS: "2",
        GAIUS_DNS_RETRY_DELAY_MS: "1",
        GAIUS_DNS_TEST_LOOKUP_HOST: dnsTransientHost,
        GAIUS_DNS_TEST_LOOKUP_PERMANENT_HOST: dnsPermanentHost,
        GAIUS_DNS_TEST_LOOKUP_FAILURES: "1",
        GAIUS_DNS_TEST_SRV_HOST: srvTransientHost,
        GAIUS_DNS_TEST_SRV_FAILURES: "1",
        GAIUS_DNS_TEST_SRV_PORT: String(fixturePort),
    },
    stdio: ["ignore", "pipe", "pipe"],
});

let bridgeOutput = "";
bridge.stdout.setEncoding("utf8");
bridge.stderr.setEncoding("utf8");
bridge.stdout.on("data", (chunk) => {
    bridgeOutput += chunk;
});
bridge.stderr.on("data", (chunk) => {
    bridgeOutput += chunk;
});

let webSocket;
let dnsResolution;
try {
    await waitFor(
            () => bridgeOutput.includes("Gaius translator node listening"),
            "translator node startup");
    dnsResolution = await testDnsResolutionRetries(bridgePort, fixturePort, bridgeToken);

    const resourcePackProxyUrl = new URL(
            `http://${host}:${bridgePort}/proxy/resource-pack`);
    resourcePackProxyUrl.searchParams.set(
            "url", `http://${host}:${resourcePackFixturePort}/resource-pack.zip`);
    resourcePackProxyUrl.searchParams.set("token", bridgeToken);
    const resourcePackResponse = await fetch(resourcePackProxyUrl, {
        headers: {origin},
    });
    const resourcePackBytes = Buffer.from(await resourcePackResponse.arrayBuffer());
    const proxiedResourcePackHash = createHash("sha1")
            .update(resourcePackBytes).digest("hex");
    if (!resourcePackResponse.ok || resourcePackAttempts !== 3 ||
            resourcePackBytes.byteLength !== resourcePackPayload.byteLength ||
            resourcePackResponse.headers.get("content-length") !==
                String(resourcePackPayload.byteLength) ||
            proxiedResourcePackHash !== resourcePackHash) {
        throw new Error("Translator node did not retry and preserve an interrupted resource pack");
    }
    const cachedResourcePackResponse = await fetch(resourcePackProxyUrl, {
        headers: {origin},
    });
    const cachedResourcePackBytes = Buffer.from(
            await cachedResourcePackResponse.arrayBuffer());
    if (!cachedResourcePackResponse.ok || resourcePackAttempts !== 3 ||
            cachedResourcePackBytes.byteLength !== resourcePackPayload.byteLength ||
            createHash("sha1").update(cachedResourcePackBytes).digest("hex") !==
                resourcePackHash) {
        throw new Error("Translator node did not reuse its verified resource-pack cache");
    }

    const slowResourcePackProxyUrl = new URL(resourcePackProxyUrl);
    slowResourcePackProxyUrl.searchParams.set(
            "url", `http://${host}:${resourcePackFixturePort}/slow-resource-pack.zip`);
    const slowAbort = new AbortController();
    const slowRequest = fetch(slowResourcePackProxyUrl, {
        headers: {origin},
        signal: slowAbort.signal,
    });
    await waitFor(() => slowResourcePackAttempts === 1,
            "slow resource-pack request");
    slowAbort.abort();
    let slowRequestAborted = false;
    try {
        await slowRequest;
    }
    catch (error) {
        slowRequestAborted = error?.name === "AbortError";
    }
    await waitFor(() => slowResourcePackClosed,
            "aborted upstream resource-pack close");
    await delay(750);
    if (!slowRequestAborted || slowResourcePackAttempts !== 1) {
        throw new Error("Translator node retried a resource pack after its browser disconnected");
    }

    const manifestResponse = await fetch(`http://${host}:${bridgePort}/relay-node/v1`, {
        headers: {origin},
    });
    if (!manifestResponse.ok) {
        throw new Error(`Translator node manifest returned ${manifestResponse.status}`);
    }
    const manifest = await manifestResponse.json();
    if (manifest.kind !== "gaius-relay-node" || manifest.protocolVersion !== 1 ||
            manifest.tunnelPath !== "/tunnel" || manifest.availableConnections < 1 ||
            manifest.targetConnectTimeoutMs < 100 ||
            !manifest.requiresToken || !manifest.capabilities.includes("flow-control") ||
            !manifest.capabilities.includes("ephemeral-tunnel-lease") ||
            manifest.tunnelLease?.scope !== "websocket" ||
            manifest.tunnelLease?.protocolStreamsShared !== false ||
            manifest.tunnelLease?.releasedOn !== "websocket-close" ||
            !manifest.capabilities.includes("keepalive-proxy") ||
            !manifest.capabilities.includes("configuration-reentry") ||
            !manifest.capabilities.includes("target-affinity") ||
            !manifest.capabilities.includes("target-attestation") ||
            !manifest.capabilities.includes("runtime-telemetry") ||
            manifest.targetAffinityMs < 1000 ||
            !manifest.capabilities.includes("resource-pack-proxy") ||
            !manifest.capabilities.includes("resource-pack-cache") ||
            manifest.resourcePackCache?.entries !== 1 ||
            manifest.resourcePackCache?.bytes !== resourcePackPayload.byteLength ||
            manifest.runtime?.activeClientStallTimers !== 0 ||
            !Number.isSafeInteger(manifest.runtime?.rssBytes) ||
            !Number.isSafeInteger(manifest.runtime?.cpuUserMicros) ||
            !Number.isSafeInteger(manifest.runtime?.cpuSystemMicros) ||
            !Number.isSafeInteger(manifest.runtime?.publicDnsCacheEntries) ||
            !Number.isSafeInteger(manifest.runtime?.publicDnsCacheHits) ||
            !Number.isSafeInteger(manifest.runtime?.publicDnsCacheMisses) ||
            !Number.isSafeInteger(manifest.runtime?.publicDnsCacheInflightJoins)) {
        throw new Error("Translator node manifest did not describe the tunnel capability");
    }
    if (manifest.activeConnections !== 0 ||
            manifest.runtime?.activeTunnelLeases !== 0 ||
            manifest.runtime?.activeTransportWebSockets !== 0) {
        throw new Error("Translator node baseline retained a logical or physical tunnel");
    }
    const deniedTargetResponse = await fetchTargetManifest(
            bridgePort, fixturePort, undefined);
    if (deniedTargetResponse.status !== 403) {
        throw new Error("Translator node exposed target affinity without its token");
    }
    const targetBefore = await (await fetchTargetManifest(
            bridgePort, fixturePort, bridgeToken)).json();
    if (targetBefore.target?.activeConnections !== 0 ||
            targetBefore.target?.recentlyReachable !== false) {
        throw new Error("Translator node reported an unused target as reachable");
    }
    await testRejectedTunnel(bridgePort, fixturePort);

    webSocket = new WebSocket(`ws://${host}:${bridgePort}/tunnel`, {
        headers: { origin },
    });
    await once(webSocket, "open");

    const controls = [];
    const echoed = [];
    const flooded = [];
    let phase = "echo";
    let echoedBytes = 0;
    let floodedBytes = 0;
    webSocket.on("message", (data, binary) => {
        if (!binary) {
            controls.push(JSON.parse(data.toString("utf8")));
            return;
        }
        const copy = Buffer.from(data);
        if (phase === "echo") {
            echoed.push(copy);
            echoedBytes += copy.byteLength;
        }
        else {
            flooded.push(copy);
            floodedBytes += copy.byteLength;
        }
    });

    webSocket.send(JSON.stringify({
        type: "connect",
        host,
        port: fixturePort,
        token: bridgeToken,
    }));
    await waitFor(
            () => controls.some((message) => message.type === "connected"),
            "TCP tunnel connection");
    const connectedControl = controls.find((message) => message.type === "connected");
    if (connectedControl.host !== host || connectedControl.port !== fixturePort ||
            connectedControl.candidateHost !== host ||
            connectedControl.candidatePort !== fixturePort ||
            !["127.0.0.1", "::ffff:127.0.0.1"].includes(connectedControl.remoteAddress) ||
            connectedControl.remotePort !== fixturePort) {
        throw new Error("Translator node did not attest the actual TCP peer");
    }
    await waitFor(() => fixtureSocket !== undefined, "fixture connection");

    const targetActive = await (await fetchTargetManifest(
            bridgePort, fixturePort, bridgeToken)).json();
    if (targetActive.target?.activeConnections !== 1 ||
            targetActive.target?.recentlyReachable !== true ||
            targetActive.target?.totalConnections !== 1) {
        throw new Error("Translator node did not publish active target affinity");
    }
    if (targetActive.activeConnections !== 1 ||
            targetActive.runtime?.activeTunnelLeases !== 1 ||
            targetActive.runtime?.activeTransportWebSockets !== 1) {
        throw new Error("Translator node did not publish the active logical/physical tunnel");
    }

    const healthResponse = await fetch(`http://${host}:${bridgePort}/health`, {
        headers: {origin},
    });
    const health = await healthResponse.json();
    if (!healthResponse.ok || health.activeConnections < 1 ||
            health.availableConnections !== health.maximumConnections - health.activeConnections) {
        throw new Error("Translator node health did not report the active tunnel");
    }

    // Keepalive rewriting is profile-gated. Establish the selected packet
    // table before sending the fixture keepalive; an unprofiled/raw tunnel
    // must never fall back to the 1.21.11 ids.
    const profileHandshake = encodePacket(0, Buffer.concat([
        encodeVarInt(minecraftProfile.protocolVersion),
        encodeString("ellan.top"),
        Buffer.from([(fixturePort >>> 8) & 0xff, fixturePort & 0xff]),
        encodeVarInt(1),
    ]));
    const echoedBeforeHandshake = echoedBytes;
    webSocket.send(profileHandshake);
    await waitFor(
            () => echoedBytes === echoedBeforeHandshake + profileHandshake.byteLength,
            "profile handshake echo",
    );
    echoed.length = 0;
    echoedBytes = 0;

    const uploadChannel = Buffer.from("minecraft:brand", "utf8");
    const uploadPayload = Buffer.concat([
        Buffer.from([uploadChannel.byteLength]),
        uploadChannel,
        patternedBuffer(4 * 1024 * 1024 - 1 - uploadChannel.byteLength, 0x31),
    ]);
    const upload = encodePacket(
            minecraftProfile.play.serverboundCustomPayload,
            uploadPayload);
    webSocket.send(upload);
    await waitFor(() => echoedBytes === upload.byteLength, "4 MiB tunnel echo");
    if (!Buffer.concat(echoed, echoedBytes).equals(upload)) {
        throw new Error("Tunnel echo bytes did not match the upload");
    }

    const keepAlive = Buffer.from("0a00040000000000000001", "hex");
    const echoedBeforeKeepAlive = echoedBytes;
    fixtureSocket.write(keepAlive);
    await waitFor(() => proxiedKeepAlives === 1, "proxied vanilla keepalive");
    if (echoedBytes !== echoedBeforeKeepAlive) {
        throw new Error("Translator node forwarded a proxied keepalive to the browser");
    }

    echoEnabled = false;
    phase = "flood";
    webSocket.send(JSON.stringify({ type: "flow", paused: true }));
    await waitFor(
            () => controls.some((message) => message.type === "flow" && message.paused === true),
            "flow pause acknowledgement");

    const floodLength = 8 * 1024 * 1024;
    const expectedHash = createHash("sha256");
    const floodWrite = writePatterned(fixtureSocket, floodLength, expectedHash);
    await delay(250);
    if (floodedBytes !== 0) {
        throw new Error(`Flow pause leaked ${floodedBytes} server bytes`);
    }

    webSocket.send(JSON.stringify({ type: "flow", paused: false }));
    await waitFor(
            () => controls.some((message) => message.type === "flow" && message.paused === false),
            "flow resume acknowledgement");
    await floodWrite;
    await waitFor(() => floodedBytes === floodLength, "resumed 8 MiB server burst");

    const actualHash = createHash("sha256")
            .update(Buffer.concat(flooded, floodedBytes))
            .digest("hex");
    const wantedHash = expectedHash.digest("hex");
    if (actualHash !== wantedHash) {
        throw new Error(`Resumed server burst hash mismatch: ${actualHash} != ${wantedHash}`);
    }

    webSocket.close();
    await once(webSocket, "close");
    webSocket = undefined;
    fixtureSocket.destroy();
    fixtureSocket = undefined;

    // The client WebSocket close event can win the RelayNode process' matching
    // close callback. Wait for the server-side route release instead of racing
    // a single manifest fetch on slower CI runners.
    const targetRecent = await waitForTargetRoute(
            bridgePort, fixturePort, bridgeToken, 0, "recent target release");
    if (targetRecent.activeConnections !== 0 ||
            targetRecent.recentlyReachable !== true ||
            targetRecent.totalConnections !== 1) {
        throw new Error("Translator node did not retain recent target affinity after close: " +
            JSON.stringify(targetRecent));
    }
    const afterCloseRuntime = await waitForRelayRuntime(
        bridgePort,
        bridgeToken,
        (runtime) => runtime.activeTunnelLeases === 0 &&
            runtime.activeTransportWebSockets === 0,
        "logical and physical tunnel cleanup",
    );

    const sharedTargetLifecycle = await testSharedTargetLifecycle(bridgePort, bridgeToken);
    await testFramedPlayKeepAlive(bridgePort, fixturePort);

    const localTunnel = await testLocalTunnelPair(bridgePort, bridgeToken);
    const minecraftLogin = minecraftHost
            ? await testMinecraftLogin(bridgePort, minecraftHost, minecraftPort, {
                sessionUrl: minecraftSessionUrl,
                accessToken: minecraftAccessToken,
                profileId: minecraftProfileId,
                username: minecraftUsername,
            }, bridgeToken)
            : undefined;
    console.log(JSON.stringify({
        ok: true,
        profile: {
            id: minecraftProfile.name,
            protocolVersion: minecraftProfile.protocolVersion,
            profilePath: activeVersionProfile.path,
        },
        echoBytes: echoedBytes,
        pausedBytes: 0,
        resumedBytes: floodedBytes,
        sha256: actualHash,
        resourcePack: {
            bytes: resourcePackBytes.byteLength,
            sha1: proxiedResourcePackHash,
            upstreamAttempts: resourcePackAttempts,
            cached: true,
            abortedUpstreamAttempts: slowResourcePackAttempts,
        },
        localTunnel,
        dnsResolution,
        sharedTargetLifecycle,
        relayNode: {
            name: manifest.name,
            availableConnections: health.availableConnections,
            requiresToken: manifest.requiresToken,
            targetAffinityMs: manifest.targetAffinityMs,
            targetActiveConnections: targetActive.target.activeConnections,
            targetRecentlyReachable: targetRecent.recentlyReachable,
            activeTunnelLeasesAfterClose: afterCloseRuntime.activeTunnelLeases,
            activeTransportWebSocketsAfterClose: afterCloseRuntime.activeTransportWebSockets,
        },
        ...(minecraftLogin === undefined ? {} : { minecraftLogin }),
    }));
}
finally {
    webSocket?.close();
    fixtureSocket?.destroy();
    await new Promise((resolve) => fixture.close(resolve));
    await new Promise((resolve) => resourcePackFixture.close(resolve));
    bridge.kill("SIGTERM");
    if (bridge.exitCode === null) {
        await once(bridge, "exit");
    }
}

async function reservePort() {
    const server = createServer();
    await new Promise((resolve, reject) => {
        server.once("error", reject);
        server.listen(0, host, resolve);
    });
    const port = server.address().port;
    await new Promise((resolve) => server.close(resolve));
    return port;
}

function fetchTargetManifest(bridgePort, targetPort, token) {
    const url = new URL(`http://${host}:${bridgePort}/relay-node/v1`);
    url.searchParams.set("host", host);
    url.searchParams.set("port", String(targetPort));
    const headers = {origin};
    if (token !== undefined) {
        headers.authorization = `Bearer ${token}`;
    }
    return fetch(url, {headers});
}

async function fetchRelayRuntime(bridgePort, token) {
    const response = await fetch(`http://${host}:${bridgePort}/relay-node/v1`, {
        headers: {
            origin,
            authorization: `Bearer ${token}`,
        },
    });
    if (!response.ok) {
        throw new Error(`RelayNode runtime manifest returned ${response.status}`);
    }
    const manifest = await response.json();
    return manifest.runtime ?? {};
}

async function waitForRelayRuntime(bridgePort, token, predicate, label, timeoutMs = 10000) {
    const deadline = Date.now() + timeoutMs;
    let runtime;
    while (Date.now() < deadline) {
        runtime = await fetchRelayRuntime(bridgePort, token);
        if (predicate(runtime)) return runtime;
        await delay(10);
    }
    throw new Error(`${label} timed out: ${JSON.stringify(runtime ?? {})}`);
}

async function waitForFixtureBackpressureDrain(
        bridgePort,
        token,
        baselineBytes,
        expectedPayloadBytes,
        baselineSyntheticWrites,
        syntheticTickBytes,
        label,
        timeoutMs = 10000) {
    const deadline = Date.now() + timeoutMs;
    let runtime;
    while (Date.now() < deadline) {
        runtime = await fetchRelayRuntime(bridgePort, token);
        const syntheticWrites = Math.max(
                0,
                (runtime.syntheticPlayTickWrites ?? 0) - baselineSyntheticWrites);
        const expectedBytes = expectedPayloadBytes + syntheticWrites * syntheticTickBytes;
        if ((runtime.pendingSyntheticPlayTicks ?? 0) === 0 &&
                syntheticWrites > 0 &&
                fixtureTcpBytes - baselineBytes >= expectedBytes) {
            return runtime;
        }
        await delay(10);
    }
    throw new Error(`${label} timed out: ${JSON.stringify({
        runtime: runtime ?? {},
        fixtureTcpBytes,
        baselineBytes,
        expectedPayloadBytes,
    })}`);
}

async function testSharedTargetLifecycle(bridgePort, token) {
    const targetSockets = new Set();
    const target = createServer((socket) => {
        targetSockets.add(socket);
        socket.setNoDelay(true);
        socket.on("data", (chunk) => socket.write(chunk));
        socket.once("close", () => targetSockets.delete(socket));
    });
    await new Promise((resolve, reject) => {
        target.once("error", reject);
        target.listen(0, host, resolve);
    });
    const targetPort = target.address().port;
    const clients = [];
    try {
        for (let index = 0; index < 2; index++) {
            const socket = new WebSocket(`ws://${host}:${bridgePort}/tunnel`, {
                headers: {origin},
            });
            const controls = [];
            const inbound = [];
            let inboundBytes = 0;
            socket.on("message", (data, binary) => {
                if (binary) {
                    const copy = Buffer.from(data);
                    inbound.push(copy);
                    inboundBytes += copy.byteLength;
                }
                else {
                    controls.push(JSON.parse(data.toString("utf8")));
                }
            });
            clients.push({socket, controls, inbound, inboundBytes: () => inboundBytes});
        }
        await Promise.all(clients.map(({socket}) => once(socket, "open")));
        for (const {socket} of clients) {
            socket.send(JSON.stringify({type: "connect", host, port: targetPort, token}));
        }
        await waitFor(() => clients.every(({controls}) =>
                controls.some((message) => message.type === "connected")),
        "two independent target tunnels");
        await waitFor(() => targetSockets.size === 2, "two independent target TCP sockets");

        const active = await waitForTargetRoute(
                bridgePort, targetPort, token, 2, "two active target leases");
        if (active.totalConnections !== 2 || active.recentlyReachable !== true) {
            throw new Error("Translator node did not publish the shared target route");
        }

        const payloads = [
            Buffer.from("gaius-player-one", "utf8"),
            Buffer.from("gaius-player-two", "utf8"),
        ];
        for (let index = 0; index < clients.length; index++) {
            clients[index].socket.send(payloads[index]);
        }
        await waitFor(() => clients.every((client, index) =>
                client.inboundBytes() === payloads[index].byteLength),
        "isolated per-player echo streams");
        for (let index = 0; index < clients.length; index++) {
            if (!Buffer.concat(clients[index].inbound).equals(payloads[index])) {
                throw new Error("Shared RelayNode mixed two players' TCP streams");
            }
        }

        clients[0].socket.close();
        await once(clients[0].socket, "close");
        await waitFor(() => targetSockets.size === 1, "first target tunnel release");
        await waitForTargetRoute(
                bridgePort, targetPort, token, 1, "one remaining target lease");

        // A tab or browser process can disappear without completing a WebSocket close
        // handshake. The RelayNode must release the final per-player TCP tunnel on that path
        // too, otherwise a temporary target route can retain a live backend connection.
        clients[1].socket.terminate();
        await once(clients[1].socket, "close");
        await waitFor(() => targetSockets.size === 0, "abrupt last target tunnel release");
        const released = await waitForTargetRoute(
                bridgePort, targetPort, token, 0, "all target leases released");
        if (released.totalConnections !== 2 || released.recentlyReachable !== true) {
            throw new Error("Translator node lost bounded target affinity after releasing tunnels");
        }
        return {
            players: clients.length,
            independentTcpSockets: 2,
            activeAfterFirstExit: 1,
            activeAfterLastExit: released.activeConnections,
            abruptLastExit: true,
            recentlyReachableAfterLastExit: released.recentlyReachable,
        };
    }
    finally {
        for (const {socket} of clients) {
            if (socket.readyState === WebSocket.OPEN ||
                    socket.readyState === WebSocket.CONNECTING) {
                socket.close();
            }
        }
        for (const socket of targetSockets) socket.destroy();
        await new Promise((resolve) => target.close(resolve));
    }
}

async function waitForTargetRoute(bridgePort, targetPort, token, activeConnections, label) {
    const deadline = Date.now() + 10000;
    let lastTarget;
    while (Date.now() < deadline) {
        const response = await fetchTargetManifest(bridgePort, targetPort, token);
        if (!response.ok) {
            throw new Error(`Target manifest returned ${response.status} while waiting for ${label}`);
        }
        lastTarget = (await response.json()).target;
        if (lastTarget?.activeConnections === activeConnections) return lastTarget;
        await delay(10);
    }
    throw new Error(`Timed out waiting for ${label}: ${JSON.stringify(lastTarget)}`);
}

async function waitFor(predicate, label, timeoutMs = 10000, diagnostics) {
    const deadline = Date.now() + timeoutMs;
    while (!predicate()) {
        if (Date.now() >= deadline) {
            const detail = diagnostics === undefined
                    ? ""
                    : `: ${JSON.stringify(diagnostics())}`;
            throw new Error(`Timed out waiting for ${label}${detail}`);
        }
        await delay(10);
    }
}

async function testRejectedTunnel(bridgePort, fixturePort) {
    const socket = new WebSocket(`ws://${host}:${bridgePort}/tunnel`, {
        headers: { origin },
    });
    await once(socket, "open");
    socket.send(JSON.stringify({ type: "connect", host, port: fixturePort }));
    const [code] = await once(socket, "close");
    if (code !== 1008) {
        throw new Error(`Translator node accepted a tunnel without its required token (${code})`);
    }
}

async function testDnsResolutionRetries(bridgePort, fixturePort, token) {
    const srvSocket = new WebSocket(`ws://${host}:${bridgePort}/tunnel`, {
        headers: {origin},
    });
    const srvControls = [];
    srvSocket.on("message", (data, binary) => {
        if (!binary) srvControls.push(JSON.parse(data.toString("utf8")));
    });
    await once(srvSocket, "open");
    srvSocket.send(JSON.stringify({
        type: "connect",
        host: srvTransientHost,
        port: 25565,
        token,
    }));
    await waitFor(
            () => srvControls.some((message) => message.type === "connected"),
            "transient SRV retry tunnel");
    const connected = srvControls.find((message) => message.type === "connected");
    if (connected.candidateHost !== host || connected.candidatePort !== fixturePort) {
        throw new Error("RelayNode did not use the recovered SRV target");
    }
    srvSocket.close();
    await once(srvSocket, "close");
    fixtureSocket?.destroy();
    fixtureSocket = undefined;

    const retryBridgePort = await reservePort();
    const retryBridge = spawn(process.execPath, ["dist/main.js"], {
        cwd: directory,
        env: {
            ...process.env,
            NODE_ENV: "test",
            GAIUS_BRIDGE_HOST: host,
            GAIUS_BRIDGE_PORT: String(retryBridgePort),
            GAIUS_ALLOWED_ORIGINS: origin,
            GAIUS_ALLOWED_HOSTS: [dnsTransientHost, dnsPermanentHost].join(","),
            GAIUS_BRIDGE_TOKEN: token,
            GAIUS_ALLOW_PRIVATE_TARGETS: "0",
            GAIUS_DNS_RETRY_ATTEMPTS: "2",
            GAIUS_DNS_RETRY_DELAY_MS: "1",
            GAIUS_DNS_TEST_LOOKUP_HOST: dnsTransientHost,
            GAIUS_DNS_TEST_LOOKUP_PERMANENT_HOST: dnsPermanentHost,
            GAIUS_DNS_TEST_LOOKUP_FAILURES: "1",
            GAIUS_DNS_TEST_LOOKUP_ADDRESS: host,
        },
        stdio: ["ignore", "pipe", "pipe"],
    });
    let retryBridgeOutput = "";
    retryBridge.stdout.setEncoding("utf8");
    retryBridge.stderr.setEncoding("utf8");
    retryBridge.stdout.on("data", (chunk) => {
        retryBridgeOutput += chunk;
    });
    retryBridge.stderr.on("data", (chunk) => {
        retryBridgeOutput += chunk;
    });
    try {
        await waitFor(
                () => retryBridgeOutput.includes("Gaius translator node listening"),
                "DNS guard test bridge startup");
        const transientClose = await openDnsFailureTunnel(
                retryBridgePort, dnsTransientHost, fixturePort, token);
        if (transientClose.code !== 1011 ||
                !retryBridgeOutput.includes("DNS lookup dns-transient.gaius.test") ||
                !retryBridgeOutput.includes("retry 1/2") ||
                !retryBridgeOutput.includes("Target hostname resolves only to private addresses")) {
            throw new Error("RelayNode did not retry transient A/AAAA DNS errors before the private-target guard");
        }
        const transientRetryCount =
                (retryBridgeOutput.match(/DNS lookup dns-transient\.gaius\.test/g) ?? []).length;
        const permanentClose = await openDnsFailureTunnel(
                retryBridgePort, dnsPermanentHost, fixturePort, token);
        await delay(100);
        const permanentRetryCount =
                (retryBridgeOutput.match(/DNS lookup dns-permanent\.gaius\.test/g) ?? []).length;
        if (permanentClose.code !== 1011 || permanentRetryCount !== 0 ||
                !retryBridgeOutput.includes("ENOTFOUND resolving dns-permanent.gaius.test")) {
            throw new Error("RelayNode retried a permanent ENOTFOUND DNS error");
        }
        return {
            srvTransientFailures: 1,
            srvRecoveredTarget: `${host}:${fixturePort}`,
            lookupTransientFailures: transientRetryCount,
            lookupPermanentRetries: permanentRetryCount,
            privateTargetGuardPreserved: true,
        };
    }
    finally {
        retryBridge.kill("SIGTERM");
        if (retryBridge.exitCode === null) {
            await once(retryBridge, "exit");
        }
    }
}

async function openDnsFailureTunnel(bridgePort, targetHost, targetPort, token) {
    const socket = new WebSocket(`ws://${host}:${bridgePort}/tunnel`, {
        headers: {origin},
    });
    await once(socket, "open");
    socket.send(JSON.stringify({type: "connect", host: targetHost, port: targetPort, token}));
    const [code] = await once(socket, "close");
    return {code};
}

function testConnectRequestNormalization() {
    const cases = [
        { host: "example.test:25565", port: 25565, expected: "example.test" },
        { host: "127.0.0.1:25566", port: 25566, expected: "127.0.0.1" },
        { host: "[2001:db8::7]:25565", port: 25565, expected: "2001:db8::7" },
        { host: "2001:db8::7", port: 25565, expected: "2001:db8::7" },
    ];
    for (const sample of cases) {
        const parsed = parseConnectRequest(JSON.stringify({
            type: "connect",
            host: sample.host,
            port: sample.port,
        }));
        if (parsed.host !== sample.expected) {
            throw new Error(`Translator node did not normalize ${sample.host}`);
        }
    }
    for (const sample of [
        { host: "example.test:25566", port: 25565 },
        { host: "[2001:db8::7]:25566", port: 25565 },
    ]) {
        let rejected = false;
        try {
            parseConnectRequest(JSON.stringify({ type: "connect", ...sample }));
        } catch {
            rejected = true;
        }
        if (!rejected) {
            throw new Error(`Translator node accepted a mismatched address port: ${sample.host}`);
        }
    }
}

function patternedBuffer(length, seed) {
    const bytes = Buffer.allocUnsafe(length);
    for (let index = 0; index < bytes.length; index++) {
        bytes[index] = (index * 31 + (index >>> 7) + seed) & 0xff;
    }
    return bytes;
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
    return path.isAbsolute(normalized)
        ? path.resolve(normalized)
        : path.resolve(repository, normalized);
}

function pathInside(parent, child) {
    const relative = path.relative(path.resolve(parent), path.resolve(child));
    return relative === "" || (relative !== ".." && !relative.startsWith(`..${path.sep}`) &&
        !path.isAbsolute(relative));
}

async function loadActiveVersionProfile(requestedProfileSelector) {
    const config = JSON.parse(await readFile(path.join(repository, "port", "config.json"), "utf8"));
    let selected = nativePath(
            process.env.GAIUS_VERSION_PROFILE_PATH ?? config.versionProfile ?? "");
    if (process.env.GAIUS_VERSION_PROFILE_PATH === undefined &&
            requestedProfileSelector !== undefined) {
        selected = `versions/${resolveSmokeMinecraftProfile(requestedProfileSelector).name}.json`;
    }
    if (/^\d+(?:\.\d+)+$/u.test(selected)) selected = `versions/${selected}.json`;
    let profilePath;
    if (path.isAbsolute(selected)) {
        profilePath = path.resolve(selected);
    }
    else if (selected.startsWith("port/")) {
        profilePath = path.resolve(repository, selected);
    }
    else if (selected.startsWith("versions/")) {
        profilePath = path.resolve(repository, "port", selected);
    }
    else {
        profilePath = path.resolve(repository, selected);
    }
    const versionsDirectory = path.join(repository, "port", "versions");
    if (!pathInside(versionsDirectory, profilePath) || !profilePath.endsWith(".json")) {
        throw new Error(`Active version profile must be inside port/versions: ${profilePath}`);
    }
    const profile = JSON.parse(await readFile(profilePath, "utf8"));
    if (typeof profile.id !== "string" || !Number.isInteger(profile.protocolVersion)) {
        throw new Error(`Active version profile is invalid: ${profilePath}`);
    }
    return {...profile, path: profilePath};
}

function parseMinecraftPort(value) {
    const port = Number(value);
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
        throw new Error(`GAIUS_SMOKE_MINECRAFT_PORT must be an integer from 1 to 65535: ${value}`);
    }
    return port;
}

function parseBoundedInteger(name, defaultValue, minimum, maximum) {
    const raw = process.env[name];
    if (raw === undefined) return defaultValue;
    if (!/^(?:0|[1-9][0-9]*)$/u.test(raw)) {
        throw new Error(`${name} must be an integer from ${minimum} to ${maximum}`);
    }
    const value = Number(raw);
    if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
        throw new Error(`${name} must be an integer from ${minimum} to ${maximum}`);
    }
    return value;
}

function parseBoundedFloat(name, defaultValue, minimum, maximum) {
    const raw = process.env[name];
    if (raw === undefined) return defaultValue;
    if (!/^(?:[0-9]+(?:\.[0-9]+)?|\.[0-9]+)$/u.test(raw)) {
        throw new Error(`${name} must be a finite number from ${minimum} to ${maximum}`);
    }
    const value = Number(raw);
    if (!Number.isFinite(value) || value < minimum || value > maximum) {
        throw new Error(`${name} must be a finite number from ${minimum} to ${maximum}`);
    }
    return value;
}

function resolveSmokeMinecraftProfile(value) {
    const key = String(value ?? "").trim();
    const profile = [MINECRAFT_1_21_11, MINECRAFT_26_2]
            .find((candidate) => candidate.name === key ||
                    String(candidate.protocolVersion) === key);
    if (profile === undefined) {
        throw new Error(
                `Unsupported smoke Minecraft version ${value}; expected 1.21.11/774 or 26.2/776`);
    }
    return profile;
}

function isVanillaKeepAlive(chunk) {
    return chunk.byteLength === 11 && chunk[0] === 0x0a &&
        chunk[1] === 0x00 &&
        chunk[2] === minecraftProfile.configuration.clientboundKeepAlive;
}

function isPlayKeepAlive(chunk) {
    return chunk.byteLength === 11 && chunk[0] === 0x0a &&
        chunk[1] === 0x00 &&
        chunk[2] === minecraftProfile.play.serverboundKeepAlive;
}

function isClientTickEnd(chunk) {
    return chunk.byteLength === 3 && chunk[0] === 0x02 &&
        chunk[1] === 0x00 &&
        chunk[2] === minecraftProfile.play.serverboundClientTickEnd;
}

async function testFramedPlayKeepAlive(bridgePort, fixturePort) {
    const socket = new WebSocket(`ws://${host}:${bridgePort}/tunnel`, {
        headers: { origin },
    });
    await once(socket, "open");
    const controls = [];
    const serverFrames = [];
    socket.on("message", (data, binary) => {
        if (!binary) {
            controls.push(JSON.parse(data.toString("utf8")));
        }
        else {
            serverFrames.push(Buffer.from(data));
        }
    });
    socket.send(JSON.stringify({ type: "connect", host, port: fixturePort, token: bridgeToken }));
    await waitFor(
            () => controls.some((message) => message.type === "connected"),
            "framed tunnel connection");
    await waitFor(() => fixtureSocket !== undefined, "framed fixture connection");

    socket.send(encodePacket(0, Buffer.concat([
        encodeVarInt(minecraftProfile.protocolVersion),
        encodeString("ellan.top"),
        Buffer.from("dd02", "hex"),
        encodeVarInt(2),
    ])));
    const splitClientFrames = Buffer.concat([
        encodePacket(minecraftProfile.configuration.serverboundFinish, Buffer.alloc(0), 256),
        encodePacket(minecraftProfile.configuration.serverboundFinish, Buffer.alloc(0), 256),
    ]);
    socket.send(splitClientFrames.subarray(0, 4));
    socket.send(splitClientFrames.subarray(4));
    await waitFor(() => proxiedPlayTicks >= 1, "synthetic initial play tick");
    // Regression: one upstream TCP write can contain several complete framed
    // packets. Pause the browser reader so RelayNode's WebSocket high-water
    // guard must stop the parser inside that same data callback, then prove
    // that the retained complete frames drain on a scheduled turn before TCP
    // read resumes.
    const serverBackpressureBefore = await fetchRelayRuntime(bridgePort, bridgeToken);
    const backpressureChannel = Buffer.from("minecraft:brand", "utf8");
    // Fill well below the 4 MiB high-water mark first, then write a separate
    // tail large enough to cross it. The tail therefore starts on a frame
    // boundary and must leave several complete frames retained when parsing
    // pauses; this avoids accepting a partial-frame-only false positive.
    const backpressurePrefixFrameCount = 512;
    const backpressureTailFrameCount = 4096;
    const backpressureFrameCount =
        backpressurePrefixFrameCount + backpressureTailFrameCount;
    // Keep frames smaller than the usual TCP data callback.  That makes the
    // high-water assertion deterministic: when ws crosses 4 MiB, the pause is
    // recorded inside the tail callback.  TCP callback boundaries may leave a
    // partial frame or complete frames in the accumulator; the integration
    // gate uses the cumulative pause evidence and the final byte/hash checks.
    const backpressurePayloadBytes = 2 * 1024;
    const backpressureFrames = Array.from({ length: backpressureFrameCount }, (_, index) => encodePacket(
        minecraftProfile.play.clientboundCustomPayload,
        Buffer.concat([
            Buffer.from([backpressureChannel.byteLength]),
            backpressureChannel,
            patternedBuffer(
                backpressurePayloadBytes - 1 - backpressureChannel.byteLength,
                0x71 + index,
            ),
        ]),
    ));
    const backpressureBurst = Buffer.concat(backpressureFrames);
    const backpressurePrefixBurst = Buffer.concat(
        backpressureFrames.slice(0, backpressurePrefixFrameCount));
    const backpressureTailBurst = Buffer.concat(
        backpressureFrames.slice(backpressurePrefixFrameCount));
    const backpressureExpectedHash = createHash("sha256")
        .update(backpressureBurst)
        .digest("hex");
    const serverFramesBeforeBackpressure = serverFrames.length;
    const pausedReader = socket._socket;
    if (pausedReader === undefined || typeof pausedReader.pause !== "function" ||
        typeof pausedReader.resume !== "function") {
        throw new Error("WebSocket smoke transport did not expose a pausable reader");
    }
    pausedReader.pause();
    try {
        fixtureSocket.write(backpressurePrefixBurst);
        const prefixRuntime = await waitForRelayRuntime(
            bridgePort,
            bridgeToken,
            (runtime) =>
                (runtime.serverFramePauses ?? 0) ===
                    (serverBackpressureBefore.serverFramePauses ?? 0) &&
                (runtime.serverFrameBufferedBytes ??
                    runtime.serverFrameBackpressure?.bufferedFrameBytes ?? 0) === 0 &&
                (runtime.serverFramesSent ?? 0) -
                    (serverBackpressureBefore.serverFramesSent ?? 0) ===
                    backpressurePrefixFrameCount,
            "server framed WebSocket below-water prefix",
        );
        fixtureSocket.write(backpressureTailBurst);
        const pausedRuntime = await waitForRelayRuntime(
            bridgePort,
            bridgeToken,
            (runtime) =>
                (runtime.serverFramePauses ?? 0) >
                    (serverBackpressureBefore.serverFramePauses ?? 0) &&
                (runtime.serverFrameDataCallbacksAtPause ?? 0) >
                    (prefixRuntime.serverFrameDataCallbacks ?? 0) &&
                (runtime.serverFramesAfterPause ?? 0) ===
                    (serverBackpressureBefore.serverFramesAfterPause ?? 0),
            "server framed WebSocket high-water pause",
        );
        if ((pausedRuntime.serverFramesAfterPause ?? 0) !==
                (serverBackpressureBefore.serverFramesAfterPause ?? 0) ||
            (pausedRuntime.serverFrameDataCallbacksAtPause ?? 0) <=
                (prefixRuntime.serverFrameDataCallbacks ?? 0)) {
            throw new Error(
                "RelayNode parser advanced after TCP pause or did not record a tail pause",
            );
        }
    }
    finally {
        pausedReader.resume();
    }
    const drainedServerRuntime = await waitForRelayRuntime(
        bridgePort,
        bridgeToken,
        (runtime) =>
            (runtime.serverFrameResumes ?? 0) >
                (serverBackpressureBefore.serverFrameResumes ?? 0) &&
            (runtime.serverFrameBufferedBytes ??
                runtime.serverFrameBackpressure?.bufferedFrameBytes ?? 0) === 0 &&
            (runtime.serverFramesSent ?? 0) -
                (serverBackpressureBefore.serverFramesSent ?? 0) === backpressureFrameCount &&
            (runtime.serverFrameScheduledDrains ?? 0) >
                (serverBackpressureBefore.serverFrameScheduledDrains ?? 0) &&
            (runtime.serverFrameDrainCompletions ?? 0) >
                (serverBackpressureBefore.serverFrameDrainCompletions ?? 0) &&
            (runtime.serverFrameBufferedCompleteFrames ?? 0) === 0 &&
            (runtime.serverFrameDataCallbacksAtDrainStart ?? -1) ===
                (runtime.serverFrameDataCallbacksAtPause ?? -2) &&
            (runtime.serverFrameDataCallbacksAtDrainCompletion ?? -1) ===
                (runtime.serverFrameDataCallbacksAtPause ?? -2),
        "server framed WebSocket low-water drain",
    );
    await waitFor(
        () => serverFrames.length >= serverFramesBeforeBackpressure + backpressureFrameCount,
        "server framed WebSocket messages after low-water drain",
    );
    const drainedServerFrames = serverFrames.slice(serverFramesBeforeBackpressure);
    if (drainedServerFrames.length !== backpressureFrameCount ||
        !Buffer.concat(drainedServerFrames).equals(backpressureBurst)) {
        throw new Error(`RelayNode did not preserve framed server bytes across backpressure: ` +
            `frames=${drainedServerFrames.length}/${backpressureFrameCount} ` +
            `bytes=${Buffer.concat(drainedServerFrames).byteLength}/${backpressureBurst.byteLength}`);
    }
    const drainedServerHash = createHash("sha256")
        .update(Buffer.concat(drainedServerFrames))
        .digest("hex");
    if (drainedServerHash !== backpressureExpectedHash ||
        (drainedServerRuntime.serverFrameBytesSent ?? 0) -
            (serverBackpressureBefore.serverFrameBytesSent ?? 0) !== backpressureBurst.byteLength ||
        (drainedServerRuntime.serverFrameScheduledDrains ?? 0) <=
            (serverBackpressureBefore.serverFrameScheduledDrains ?? 0) ||
        (drainedServerRuntime.serverFrameDataCallbacksAtDrainStart ?? -1) !==
            (drainedServerRuntime.serverFrameDataCallbacksAtPause ?? -2) ||
        (drainedServerRuntime.serverFrameDataCallbacksAtDrainCompletion ?? -1) !==
            (drainedServerRuntime.serverFrameDataCallbacksAtPause ?? -2) ||
        (drainedServerRuntime.serverFrameBufferedCompleteFrames ?? 0) !== 0 ||
        (drainedServerRuntime.serverFramesAfterPause ?? 0) !==
            (serverBackpressureBefore.serverFramesAfterPause ?? 0) ||
        (drainedServerRuntime.serverFrameSendErrors ?? 0) !==
            (serverBackpressureBefore.serverFrameSendErrors ?? 0) ||
        (drainedServerRuntime.serverFrameCleanupBytes ?? 0) !==
            (serverBackpressureBefore.serverFrameCleanupBytes ?? 0) ||
        (drainedServerRuntime.serverFrameBufferedUnderflows ?? 0) !==
            (serverBackpressureBefore.serverFrameBufferedUnderflows ?? 0) ||
        (drainedServerRuntime.activeServerFrameDrainHandleUnderflows ?? 0) !==
            (serverBackpressureBefore.activeServerFrameDrainHandleUnderflows ?? 0) ||
        (drainedServerRuntime.serverFrameMaxBufferedAmount ?? 0) <= 0 ||
        (drainedServerRuntime.serverFrameMaxBufferedBytes ??
            drainedServerRuntime.serverFrameBackpressure?.maxBufferedFrameBytes ?? 0) <= 0) {
        throw new Error("RelayNode server framed backpressure telemetry or SHA mismatch");
    }
    // Pause the backend reader and fill RelayNode's TCP write queue with one
    // legal, bounded PLAY custom-payload frame. This makes write() return
    // false deterministically instead of relying on a timing-only pause.
    const backpressureBefore = await fetchRelayRuntime(bridgePort, bridgeToken);
    const customChannel = Buffer.from("minecraft:brand", "utf8");
    const largePayloadBytes = 4 * 1024 * 1024;
    const largeCustomPayload = Buffer.concat([
        Buffer.from([customChannel.byteLength]),
        customChannel,
        Buffer.alloc(largePayloadBytes - 1 - customChannel.byteLength, 0x5a),
    ]);
    const largeClientFrame = encodePacket(
            minecraftProfile.play.serverboundCustomPayload,
            largeCustomPayload);
    const largeClientBurst = Buffer.concat([
        largeClientFrame,
        largeClientFrame,
        largeClientFrame,
    ]);
    const fixtureBytesBeforeBackpressure = fixtureTcpBytes;
    const syntheticWritesBeforeBackpressure =
        backpressureBefore.syntheticPlayTickWrites ?? 0;
    const syntheticTickFrame = encodePacket(
            minecraftProfile.play.serverboundClientTickEnd,
            Buffer.alloc(0),
            256);
    fixtureSocket.pause();
    echoEnabled = false;
    try {
        for (let index = 0; index < 8; index++) {
            socket.send(largeClientBurst);
        }
        await waitForRelayRuntime(
                bridgePort,
                bridgeToken,
                (runtime) =>
                    (runtime.syntheticPlayTickBackpressureEvents ?? 0) >
                        (backpressureBefore.syntheticPlayTickBackpressureEvents ?? 0) &&
                    (runtime.pendingSyntheticPlayTicks ?? 0) === 1 &&
                    (runtime.maxPendingSyntheticPlayTicks ?? 0) <= 1,
                "deterministic synthetic PLAY tick backpressure",
        );
    }
    finally {
        fixtureSocket.resume();
    }
    const drainedRuntime = await waitForFixtureBackpressureDrain(
            bridgePort,
            bridgeToken,
            fixtureBytesBeforeBackpressure,
            largeClientBurst.byteLength * 8,
            syntheticWritesBeforeBackpressure,
            syntheticTickFrame.byteLength,
            "synthetic PLAY tick drain",
    );
    if ((drainedRuntime.syntheticPlayTickWrites ?? 0) <= syntheticWritesBeforeBackpressure) {
        throw new Error("Synthetic PLAY tick backpressure drained without a retry write");
    }
    // Do not re-enable fixture echo until every queued large frame and the
    // drain-triggered synthetic tick have reached the fixture reader. Otherwise
    // trailing probe bytes can be echoed back into the framed client parser.
    echoEnabled = true;
    socket.send(encodePacket(
            minecraftProfile.play.serverboundClientTickEnd,
            Buffer.alloc(0),
            256));
    await waitFor(() => proxiedPlayTicks >= 3, "proxied observed play ticks at vanilla cadence");
    const playKeepAlive = encodePacket(
            minecraftProfile.play.clientboundKeepAlive,
            Buffer.from("0000000000000002", "hex"),
            256);
    // Packet boundaries are independent from TCP chunks, so split this frame.
    fixtureSocket.write(playKeepAlive.subarray(0, 4));
    await delay(5);
    fixtureSocket.write(playKeepAlive.subarray(4));
    await waitFor(() => proxiedPlayKeepAlives === 1, "proxied framed play keepalive");

    const startConfiguration = encodePacket(
            minecraftProfile.play.clientboundStartConfiguration,
            Buffer.alloc(0),
            256);
    fixtureSocket.write(startConfiguration);
    await waitFor(
            () => serverFrames.some((frame) => frame.equals(startConfiguration)),
            "forwarded PLAY to CONFIGURATION transition");
    const ticksAtConfigurationStart = proxiedPlayTicks;
    await delay(200);
    if (proxiedPlayTicks !== ticksAtConfigurationStart) {
        throw new Error("Translator node injected PLAY ticks while reconfiguration was pending");
    }

    // The server is already in CONFIGURATION as soon as Start Configuration
    // arrives, even before the browser sends its acknowledgement.
    fixtureSocket.write(configurationKeepAliveFrame(minecraftProfile, 4));
    await waitFor(
            () => proxiedKeepAlives === 2,
            "proxied keepalive during reconfiguration transition",
    );

    socket.send(encodePacket(
            minecraftProfile.play.serverboundConfigurationAcknowledged,
            Buffer.alloc(0),
            256));
    await delay(20);
    const configurationKeepAlive = configurationKeepAliveFrame(minecraftProfile, 3);
    fixtureSocket.write(configurationKeepAlive);
    await waitFor(() => proxiedKeepAlives === 3, "proxied reconfiguration keepalive");
    const ticksDuringConfiguration = proxiedPlayTicks;
    await delay(200);
    if (proxiedPlayTicks !== ticksDuringConfiguration) {
        throw new Error("Translator node injected PLAY ticks during CONFIGURATION");
    }

    socket.send(encodePacket(
            minecraftProfile.configuration.serverboundFinish,
            Buffer.alloc(0),
            256));
    await waitFor(
            () => proxiedPlayTicks > ticksDuringConfiguration,
            "re-armed play ticks after reconfiguration");
    fixtureSocket.write(playKeepAlive);
    await waitFor(() => proxiedPlayKeepAlives === 2, "proxied play keepalive after reconfiguration");
    socket.close();
    await once(socket, "close");
    fixtureSocket.destroy();
    fixtureSocket = undefined;
    await waitForRelayRuntime(
            bridgePort,
            bridgeToken,
        (runtime) => (runtime.activeClientStallTimers ?? 0) === 0 &&
                (runtime.pendingSyntheticPlayTicks ?? 0) === 0 &&
            (runtime.serverFrameBufferedBytes ??
                    runtime.serverFrameBackpressure?.bufferedFrameBytes ?? 0) === 0 &&
                (runtime.serverFrameBufferedCompleteFrames ?? 0) === 0 &&
                (runtime.serverFrameCleanupBytes ?? 0) ===
                    (serverBackpressureBefore.serverFrameCleanupBytes ?? 0) &&
                (runtime.serverFrameBufferedUnderflows ?? 0) ===
                    (serverBackpressureBefore.serverFrameBufferedUnderflows ?? 0) &&
                (runtime.activeServerFrameDrainHandles ?? 0) === 0,
            "closed PLAY stall state",
    );
}

function configurationKeepAliveFrame(profile, value) {
    return encodePacket(
            profile.configuration.clientboundKeepAlive,
            Buffer.from(`000000000000000${value}`, "hex"),
            256);
}

async function writePatterned(socket, length, hash) {
    const chunkBytes = 64 * 1024;
    for (let offset = 0; offset < length; offset += chunkBytes) {
        const chunk = patternedBuffer(Math.min(chunkBytes, length - offset), offset >>> 16);
        hash.update(chunk);
        if (!socket.write(chunk)) {
            await once(socket, "drain");
        }
    }
}

async function testMinecraftLogin(bridgePort, serverHost, serverPort, session, token) {
    const socket = new WebSocket(`ws://${host}:${bridgePort}/tunnel`, {
        headers: { origin },
    });
    await once(socket, "open");

    const controls = [];
    let buffered = Buffer.alloc(0);
    let cipher;
    let decipher;
    let encryptionRequest = false;
    let rsaSecretEncrypted = false;
    let rsaChallengeEncrypted = false;
    let aesCfb8Enabled = false;
    let sessionJoin = false;
    let compressionThreshold;
    let phase = "login";
    let loginFinished = false;
    let configurationPackets = 0;
    let configurationFinished = false;
    let configurationCycles = 0;
    let reconfigurationRequests = 0;
    let knownPackRequests = 0;
    let showDialogPackets = 0;
    let showDialogPayload;
    let showDialogAccepts = 0;
    const showDialogActions = [];
    const showDialogSummaries = [];
    const acceptedDialogActions = new Set();
    let codeOfConductRequests = 0;
    let codeOfConductAccepts = 0;
    let resourcePackPushes = 0;
    const resourcePackTargets = [];
    let playPackets = 0;
    let playLoginPackets = 0;
    let playLoginDistanceContracts = 0;
    let chunkPackets = 0;
    const uniqueChunkPositions = new Set();
    let duplicateChunkPackets = 0;
    let observedChunkCacheCenter = null;
    let observedChunkCacheRadius = null;
    let observedSimulationDistance = null;
    let chunkCacheCenterUpdates = 0;
    let chunkCacheRadiusUpdates = 0;
    let simulationDistanceUpdates = 0;
    let chunkBatchStarts = 0;
    let chunkBatchFinished = 0;
    let chunkBatchAcknowledgements = 0;
    let chunkBatchCountMismatches = 0;
    let chunkBatchOpen = false;
    let currentChunkBatchPackets = 0;
    let playerLoadedSent = false;
    let protocolFailure;
    let expectedClose = false;
    const packetCounts = { login: {}, configuration: {}, play: {} };
    const recentPackets = [];
    socket.on("error", (error) => {
        protocolFailure = error;
    });
    socket.on("close", (code, reason) => {
        if (!expectedClose && protocolFailure === undefined) {
            protocolFailure = new Error(
                    `Minecraft tunnel closed (${code}): ${reason.toString("utf8")}`);
        }
    });
    socket.on("message", (data, binary) => {
        try {
            if (!binary) {
                controls.push(JSON.parse(data.toString("utf8")));
                return;
            }
            const received = Buffer.from(data);
            buffered = Buffer.concat([
                buffered,
                decipher === undefined ? received : decipher.update(received),
            ]);
            while (true) {
                const outerLength = decodeVarInt(buffered, 0);
                if (outerLength === undefined) {
                    return;
                }
                const frameStart = outerLength.bytesRead;
                const frameEnd = frameStart + outerLength.value;
                if (frameEnd > buffered.byteLength) {
                    return;
                }
                const frame = buffered.subarray(frameStart, frameEnd);
                buffered = buffered.subarray(frameEnd);
                let packet = frame;
                if (compressionThreshold !== undefined) {
                    const dataLength = decodeVarInt(frame, 0);
                    if (dataLength === undefined) {
                        throw new Error("Compressed Minecraft frame omitted its data length");
                    }
                    packet = dataLength.value === 0
                            ? frame.subarray(dataLength.bytesRead)
                            : inflateSync(frame.subarray(dataLength.bytesRead));
                    if (dataLength.value !== 0 && packet.byteLength !== dataLength.value) {
                        throw new Error("Minecraft compressed frame length did not match");
                    }
                }

                const packetId = decodeVarInt(packet, 0);
                if (packetId === undefined) {
                    throw new Error("Minecraft frame omitted its packet id");
                }
                const payload = packet.subarray(packetId.bytesRead);
                const observedPhase = phase;
                const phaseCounts = packetCounts[observedPhase];
                phaseCounts[packetId.value] = (phaseCounts[packetId.value] ?? 0) + 1;
                recentPackets.push({
                    phase: observedPhase,
                    id: packetId.value,
                    bytes: payload.byteLength,
                });
                if (recentPackets.length > 24) recentPackets.shift();
                if (phase === "login" &&
                        packetId.value === minecraftProfile.login.clientboundDisconnect) {
                    throw new Error("Minecraft server rejected the smoke login");
                }
                if (phase === "login" &&
                        packetId.value === minecraftProfile.login.clientboundEncryptionRequest) {
                    if (session.sessionUrl === undefined) {
                        throw new Error(
                                "Minecraft server requested online-mode encryption without a smoke session service");
                    }
                    encryptionRequest = true;
                    void answerEncryptionRequest(payload).catch((error) => {
                        protocolFailure = error;
                    });
                }
                else if (phase === "login" &&
                        packetId.value === minecraftProfile.login.clientboundCompression) {
                    const threshold = decodeVarInt(packet, packetId.bytesRead);
                    if (threshold === undefined || threshold.value < 0) {
                        throw new Error("Minecraft server sent an invalid compression threshold");
                    }
                    compressionThreshold = threshold.value;
                }
                else if (phase === "login" &&
                        packetId.value === minecraftProfile.login.clientboundLoginFinished) {
                    loginFinished = true;
                    phase = "configuration";
                    sendMinecraftPacket(
                            minecraftProfile.login.serverboundLoginAcknowledged,
                            Buffer.alloc(0));
                    sendMinecraftPacket(
                            minecraftProfile.login.serverboundHello,
                            encodeClientInformation());
                }
                else if (phase === "configuration") {
                    configurationPackets++;
                    if (packetId.value === minecraftProfile.configuration.clientboundDisconnect) {
                        throw new Error("Minecraft server disconnected during configuration");
                    }
                    if (packetId.value === minecraftProfile.configuration.clientboundKnownPacks) {
                        knownPackRequests++;
                        sendMinecraftPacket(
                                minecraftProfile.configuration.serverboundSelectKnownPacks,
                                encodeVarInt(0));
                    }
                    else if (packetId.value === minecraftProfile.configuration.clientboundResourcePackPush) {
                        if (payload.byteLength < 16) {
                            throw new Error("Resource-pack push omitted its UUID");
                        }
                        resourcePackPushes++;
                        const packId = payload.subarray(0, 16);
                        const packUrl = decodeString(payload, 16);
                        const packHash = decodeString(payload, packUrl.nextOffset);
                        if (packHash.nextOffset >= payload.byteLength) {
                            throw new Error("Resource-pack push omitted its required flag");
                        }
                        let target = "<invalid>";
                        try {
                            const parsed = new URL(packUrl.value);
                            target = parsed.origin + parsed.pathname;
                        }
                        catch {
                            // The client still needs to report INVALID_URL for malformed targets.
                        }
                        resourcePackTargets.push({
                            target,
                            hash: packHash.value,
                            required: payload[packHash.nextOffset] !== 0,
                        });
                        // This protocol smoke verifies the transport and configuration
                        // handshake. Browser resource downloading is covered separately.
                        for (const action of [3, 4, 0]) {
                            sendMinecraftPacket(
                                    minecraftProfile.configuration.serverboundResourcePack,
                                    Buffer.concat([packId, encodeVarInt(action)]));
                        }
                    }
                    else if (packetId.value === minecraftProfile.configuration.clientboundShowDialog) {
                        showDialogPackets++;
                        showDialogPayload ??= payload.toString("base64");
                        const dialog = decodeNetworkNbt(payload);
                        if (process.env.GAIUS_SMOKE_DUMP_DIALOG === "1") {
                            console.error(JSON.stringify(dialog, null, 2));
                        }
                        const prompt = inspectServerDialog(dialog);
                        showDialogSummaries.push(prompt.summary);
                        showDialogActions.push(...prompt.actionIds);
                        if (!acceptServerPrompts) {
                            throw new Error(
                                    `Minecraft server requires an interactive dialog (${prompt.summary}); ` +
                                    "rerun with GAIUS_SMOKE_ACCEPT_SERVER_PROMPTS=1 to model an explicit click");
                        }
                        const actionId = selectDialogAction(prompt.actionIds);
                        if (acceptedDialogActions.has(actionId)) {
                            throw new Error(`Minecraft server repeated dialog action ${actionId}`);
                        }
                        const inputValues = resolveDialogInputValues(prompt.inputs);
                        sendMinecraftPacket(
                                minecraftProfile.configuration.serverboundCustomClickAction,
                                encodeCustomClickAction(actionId, inputValues));
                        acceptedDialogActions.add(actionId);
                        showDialogAccepts++;
                    }
                    else if (packetId.value === minecraftProfile.configuration.clientboundCodeOfConduct) {
                        const codeOfConduct = decodeString(payload, 0);
                        if (codeOfConduct.nextOffset !== payload.byteLength ||
                                codeOfConduct.value.length === 0) {
                            throw new Error("Code of Conduct packet was malformed");
                        }
                        if (++codeOfConductRequests !== 1) {
                            throw new Error("Minecraft server sent duplicate Code of Conduct");
                        }
                        if (!acceptServerPrompts) {
                            throw new Error(
                                    "Minecraft server requires Code of Conduct acceptance; rerun with " +
                                    "GAIUS_SMOKE_ACCEPT_SERVER_PROMPTS=1 to model explicit acceptance");
                        }
                        // This models the explicit acceptance performed by the vanilla UI.
                        sendMinecraftPacket(
                                minecraftProfile.configuration.serverboundAcceptCodeOfConduct,
                                Buffer.alloc(0));
                        codeOfConductAccepts++;
                    }
                    else if (packetId.value === minecraftProfile.configuration.clientboundFinish) {
                        configurationFinished = true;
                        configurationCycles++;
                        phase = "play";
                        sendMinecraftPacket(
                                minecraftProfile.configuration.serverboundFinish,
                                Buffer.alloc(0));
                    }
                    else if (packetId.value === minecraftProfile.configuration.clientboundKeepAlive) {
                        sendMinecraftPacket(
                                minecraftProfile.configuration.serverboundKeepAlive,
                                payload);
                    }
                    else if (packetId.value === minecraftProfile.configuration.clientboundPing) {
                        sendMinecraftPacket(
                                minecraftProfile.configuration.serverboundPong,
                                payload);
                    }
                }
                else if (phase === "play") {
                    playPackets++;
                    if (packetId.value === minecraftProfile.play.clientboundDisconnect) {
                        throw new Error("Minecraft server disconnected after entering PLAY");
                    }
                    if (packetId.value === minecraftProfile.play.clientboundKeepAlive) {
                        sendMinecraftPacket(
                                minecraftProfile.play.serverboundKeepAlive,
                                payload);
                    }
                    else if (packetId.value === minecraftProfile.play.clientboundPing) {
                        sendMinecraftPacket(
                                minecraftProfile.play.serverboundPong,
                                payload);
                    }
                    else if (packetId.value === minecraftProfile.play.clientboundLogin) {
                        playLoginPackets++;
                        const initialDistances = decodeClientboundLoginDistances(payload);
                        playLoginDistanceContracts++;
                        observedChunkCacheRadius = initialDistances.chunkRadius;
                        observedSimulationDistance = initialDistances.simulationDistance;
                        chunkCacheRadiusUpdates++;
                        simulationDistanceUpdates++;
                        if (!playerLoadedSent) {
                            playerLoadedSent = true;
                            sendMinecraftPacket(
                                    minecraftProfile.play.serverboundPlayerLoaded,
                                    Buffer.alloc(0));
                        }
                    }
                    else if (packetId.value ===
                            minecraftProfile.play.clientboundSetChunkCacheCenter) {
                        const x = decodeVarInt(payload, 0);
                        const z = x === undefined
                                ? undefined : decodeVarInt(payload, x.bytesRead);
                        if (x === undefined || z === undefined ||
                                x.bytesRead + z.bytesRead !== payload.byteLength) {
                            throw new Error("Malformed PLAY chunk-cache center");
                        }
                        observedChunkCacheCenter = {
                            x: x.value | 0,
                            z: z.value | 0,
                        };
                        chunkCacheCenterUpdates++;
                    }
                    else if (packetId.value ===
                            minecraftProfile.play.clientboundSetChunkCacheRadius) {
                        const radius = decodeVarInt(payload, 0);
                        if (radius === undefined || radius.bytesRead !== payload.byteLength ||
                                radius.value < 2 || radius.value > 32) {
                            throw new Error("Malformed PLAY chunk-cache radius");
                        }
                        observedChunkCacheRadius = radius.value;
                        chunkCacheRadiusUpdates++;
                    }
                    else if (packetId.value ===
                            minecraftProfile.play.clientboundSetSimulationDistance) {
                        const distance = decodeVarInt(payload, 0);
                        if (distance === undefined ||
                                distance.bytesRead !== payload.byteLength ||
                                distance.value < 2 || distance.value > 32) {
                            throw new Error("Malformed PLAY simulation distance");
                        }
                        observedSimulationDistance = distance.value;
                        simulationDistanceUpdates++;
                    }
                    else if (packetId.value ===
                            minecraftProfile.play.clientboundChunkBatchStart) {
                        if (payload.byteLength !== 0 || chunkBatchOpen) {
                            throw new Error("Malformed or repeated PLAY chunk-batch start");
                        }
                        chunkBatchStarts++;
                        chunkBatchOpen = true;
                        currentChunkBatchPackets = 0;
                    }
                    else if (packetId.value ===
                            minecraftProfile.play.clientboundChunkBatchFinished) {
                        const advertised = decodeVarInt(payload, 0);
                        if (!chunkBatchOpen || advertised === undefined || advertised.value < 0 ||
                                advertised.bytesRead !== payload.byteLength) {
                            throw new Error("Malformed PLAY chunk-batch finish");
                        }
                        chunkBatchFinished++;
                        chunkBatchOpen = false;
                        if (advertised.value !== currentChunkBatchPackets) {
                            chunkBatchCountMismatches++;
                        }
                        const acknowledgement = Buffer.allocUnsafe(4);
                        acknowledgement.writeFloatBE(minecraftDesiredChunksPerTick, 0);
                        sendMinecraftPacket(
                                minecraftProfile.play.serverboundChunkBatchReceived,
                                acknowledgement);
                        chunkBatchAcknowledgements++;
                        currentChunkBatchPackets = 0;
                    }
                    else if (packetId.value === minecraftProfile.play.clientboundChunk) {
                        if (payload.byteLength < 8) {
                            throw new Error("PLAY chunk packet omitted coordinates");
                        }
                        const key = `${payload.readInt32BE(0)},${payload.readInt32BE(4)}`;
                        chunkPackets++;
                        if (chunkBatchOpen) currentChunkBatchPackets++;
                        if (uniqueChunkPositions.has(key)) duplicateChunkPackets++;
                        else uniqueChunkPositions.add(key);
                    }
                    else if (packetId.value === minecraftProfile.play.clientboundStartConfiguration) {
                        if (payload.byteLength !== 0) {
                            throw new Error("PLAY start-configuration packet was not payloadless");
                        }
                        reconfigurationRequests++;
                        sendMinecraftPacket(
                                minecraftProfile.play.serverboundConfigurationAcknowledged,
                                Buffer.alloc(0));
                        phase = "configuration";
                    }
                }
            }
        }
        catch (error) {
            protocolFailure = error;
        }
    });

    function sendMinecraftPacket(id, payload) {
        const encoded = encodePacket(id, payload, compressionThreshold);
        socket.send(cipher === undefined ? encoded : cipher.update(encoded));
    }

    function loginDiagnostics() {
        return {
            phase,
            encryptionRequest,
            sessionJoin,
            rsaSecretEncrypted,
            rsaChallengeEncrypted,
            aesCfb8Enabled,
            compressionThreshold: compressionThreshold ?? null,
            loginFinished,
            configurationPackets,
            configurationFinished,
            configurationCycles,
            reconfigurationRequests,
            knownPackRequests,
            showDialogPackets,
            showDialogPayload,
            showDialogAccepts,
            showDialogActions,
            showDialogSummaries,
            codeOfConductRequests,
            codeOfConductAccepts,
            resourcePackPushes,
            resourcePackTargets,
            playPackets,
            playLoginPackets,
            playLoginDistanceContracts,
            chunkPackets,
            uniqueChunkPositions: uniqueChunkPositions.size,
            duplicateChunkPackets,
            observedChunkCacheCenter,
            observedChunkCacheRadius,
            observedSimulationDistance,
            chunkCacheCenterUpdates,
            chunkCacheRadiusUpdates,
            simulationDistanceUpdates,
            chunkBatchStarts,
            chunkBatchFinished,
            chunkBatchAcknowledgements,
            chunkBatchCountMismatches,
            chunkBatchOpen,
            currentChunkBatchPackets,
            bufferedBytes: buffered.byteLength,
            packetCounts,
            recentPackets,
            controls: controls.slice(-3),
        };
    }

    async function answerEncryptionRequest(payload) {
        const serverId = decodeString(payload, 0);
        const publicKey = decodeByteArray(payload, serverId.nextOffset);
        const challenge = decodeByteArray(payload, publicKey.nextOffset);
        if (challenge.nextOffset >= payload.byteLength) {
            throw new Error("Minecraft encryption request omitted shouldAuthenticate");
        }
        const shouldAuthenticate = payload[challenge.nextOffset] !== 0;
        if (!shouldAuthenticate) {
            throw new Error("Online-mode smoke server disabled session authentication");
        }

        const secret = randomBytes(16);
        const serverHash = minecraftServerHash(
                serverId.value,
                secret,
                publicKey.value);
        const joinResponse = await fetch(
                new URL("session/minecraft/join", ensureTrailingSlash(session.sessionUrl)),
                {
                    method: "POST",
                    headers: { "content-type": "application/json" },
                    body: JSON.stringify({
                        accessToken: session.accessToken,
                        selectedProfile: session.profileId,
                        serverId: serverHash,
                    }),
                });
        if (!joinResponse.ok) {
            throw new Error(`Smoke session join failed with HTTP ${joinResponse.status}`);
        }
        sessionJoin = true;

        const rsaKey = {
            key: publicKey.value,
            format: "der",
            type: "spki",
            padding: cryptoConstants.RSA_PKCS1_PADDING,
        };
        const encryptedSecret = publicEncrypt(rsaKey, secret);
        const encryptedChallenge = publicEncrypt(rsaKey, challenge.value);
        rsaSecretEncrypted = encryptedSecret.byteLength > 0;
        rsaChallengeEncrypted = encryptedChallenge.byteLength > 0;
        const keyPayload = Buffer.concat([
            encodeByteArray(encryptedSecret),
            encodeByteArray(encryptedChallenge),
        ]);
        socket.send(encodePacket(minecraftProfile.login.serverboundKey, keyPayload));
        cipher = createCipheriv("aes-128-cfb8", secret, secret);
        decipher = createDecipheriv("aes-128-cfb8", secret, secret);
        aesCfb8Enabled = true;
    }

    socket.send(JSON.stringify({ type: "connect", host: serverHost, port: serverPort, token }));
    await waitFor(() => {
        if (protocolFailure !== undefined) throw protocolFailure;
        return controls.some((message) => message.type === "connected");
    }, "Minecraft TCP connection", 10000, loginDiagnostics);

    const handshake = Buffer.concat([
        encodeVarInt(minecraftProfile.protocolVersion),
        encodeString(serverHost),
        Buffer.from([(serverPort >>> 8) & 0xff, serverPort & 0xff]),
        encodeVarInt(2),
    ]);
    const hello = Buffer.concat([
        encodeString(session.username),
        Buffer.from(session.profileId, "hex"),
    ]);
    socket.send(encodePacket(0, handshake));
    socket.send(encodePacket(0, hello));
    await waitFor(() => {
        if (protocolFailure !== undefined) throw protocolFailure;
        return loginFinished;
    }, "Minecraft Login Finished", 10000, loginDiagnostics);
    await waitFor(() => {
        if (protocolFailure !== undefined) throw protocolFailure;
        return configurationFinished && playLoginPackets > 0 && chunkPackets > 0;
    }, "Minecraft PLAY login and chunk data", 30000, loginDiagnostics);
    if (minecraftPlaySoakMs > 0) {
        const soakDeadline = Date.now() + minecraftPlaySoakMs;
        await waitFor(() => {
            if (protocolFailure !== undefined) throw protocolFailure;
            return Date.now() >= soakDeadline && phase === "play";
        }, "Minecraft PLAY soak and any reconfiguration", minecraftPlaySoakMs + 30000,
        loginDiagnostics);
    }

    expectedClose = true;
    socket.close();
    await once(socket, "close");
    return {
        server: `${serverHost}:${serverPort}`,
        onlineMode: encryptionRequest,
        rsa: {
            requested: encryptionRequest,
            secretEncrypted: rsaSecretEncrypted,
            challengeEncrypted: rsaChallengeEncrypted,
            padding: "RSA_PKCS1_PADDING",
        },
        aes: {
            cipher: "aes-128-cfb8",
            enabled: aesCfb8Enabled,
            iv: "shared-secret",
        },
        sessionJoin,
        compressionThreshold: compressionThreshold ?? null,
        loginFinished,
        configurationPackets,
        configurationFinished,
        configurationCycles,
        reconfigurationRequests,
        knownPackRequests,
        showDialogPackets,
        showDialogAccepts,
        showDialogActions,
        showDialogSummaries,
        codeOfConductRequests,
        codeOfConductAccepts,
        resourcePackPushes,
        resourcePackTargets,
        playPackets,
        playLoginPackets,
        playLoginDistanceContracts,
        chunkPackets,
        uniqueChunkPositions: uniqueChunkPositions.size,
        duplicateChunkPackets,
        chunkWindow: {
            clientViewDistance: minecraftClientViewDistance,
            observedChunkCacheCenter,
            observedChunkCacheRadius,
            observedSimulationDistance,
            chunkCacheCenterUpdates,
            chunkCacheRadiusUpdates,
            simulationDistanceUpdates,
        },
        chunkBatch: {
            starts: chunkBatchStarts,
            finished: chunkBatchFinished,
            acknowledgements: chunkBatchAcknowledgements,
            countMismatches: chunkBatchCountMismatches,
            openAtClose: chunkBatchOpen,
            clientboundStartPacketId: minecraftProfile.play.clientboundChunkBatchStart,
            clientboundFinishedPacketId: minecraftProfile.play.clientboundChunkBatchFinished,
            serverboundAcknowledgementPacketId:
                    minecraftProfile.play.serverboundChunkBatchReceived,
            desiredChunksPerTick: minecraftDesiredChunksPerTick,
            acknowledgementEncoding: "float32-be",
        },
        playSoakMs: minecraftPlaySoakMs,
    };
}

function ensureTrailingSlash(value) {
    return value.endsWith("/") ? value : value + "/";
}

function minecraftServerHash(serverId, secret, publicKey) {
    const digest = createHash("sha1")
            .update(Buffer.from(serverId, "utf8"))
            .update(secret)
            .update(publicKey)
            .digest();
    let value = BigInt("0x" + digest.toString("hex"));
    if ((digest[0] & 0x80) !== 0) {
        value -= 1n << BigInt(digest.byteLength * 8);
    }
    return value.toString(16);
}

async function testLocalTunnelPair(bridgePort, token) {
    const sessionId = "0123456789abcdef0123456789abcdef";
    const openPair = async () => {
        const client = new WebSocket(`ws://${host}:${bridgePort}/tunnel`, {
            headers: { origin },
        });
        const server = new WebSocket(`ws://${host}:${bridgePort}/tunnel`, {
            headers: { origin },
        });
        await Promise.all([once(client, "open"), once(server, "open")]);
        const clientControls = [];
        const serverControls = [];
        const clientFrames = [];
        const serverFrames = [];
        let clientBytes = 0;
        let serverBytes = 0;
        client.on("message", (data, binary) => {
            if (binary) {
                const bytes = Buffer.from(data);
                clientFrames.push(bytes);
                clientBytes += bytes.byteLength;
            }
            else {
                clientControls.push(JSON.parse(data.toString("utf8")));
            }
        });
        server.on("message", (data, binary) => {
            if (binary) {
                const bytes = Buffer.from(data);
                serverFrames.push(bytes);
                serverBytes += bytes.byteLength;
            }
            else {
                serverControls.push(JSON.parse(data.toString("utf8")));
            }
        });
        client.send(JSON.stringify({
            type: "connect",
            host: `client-${sessionId}.gaius-local`,
            port: 25565,
            token,
        }));
        server.send(JSON.stringify({
            type: "connect",
            host: `server-${sessionId}.gaius-local`,
            port: 25565,
            token,
        }));
        await waitFor(
                () => clientControls.some((message) => message.type === "connected") &&
                    serverControls.some((message) => message.type === "connected"),
                "paired local server tunnel");
        return {
            client,
            server,
            clientFrames,
            serverFrames,
            get clientBytes() { return clientBytes; },
            get serverBytes() { return serverBytes; },
        };
    };

    const firstPair = await openPair();
    await waitForLocalTunnelSessions(bridgePort, token, 1,
            "one active local tunnel session after pairing");

    const clientPayload = patternedBuffer(2 * 1024 * 1024, 0x53);
    firstPair.client.send(clientPayload);
    await waitFor(() => firstPair.serverBytes === clientPayload.byteLength,
            "local client-to-server payload");
    if (!Buffer.concat(firstPair.serverFrames).equals(clientPayload)) {
        throw new Error("Local client-to-server bytes did not match");
    }

    const serverPayload = patternedBuffer(2 * 1024 * 1024, 0x71);
    firstPair.server.send(serverPayload);
    await waitFor(() => firstPair.clientBytes === serverPayload.byteLength,
            "local server-to-client payload");
    if (!Buffer.concat(firstPair.clientFrames).equals(serverPayload)) {
        throw new Error("Local server-to-client bytes did not match");
    }

    firstPair.client.close();
    await Promise.all([once(firstPair.client, "close"), once(firstPair.server, "close")]);
    await waitForLocalTunnelSessions(bridgePort, token, 0,
            "local tunnel session cleanup after peer close");

    // Reuse the exact same session id after both sides closed. The old
    // implementation cleared only the initiating role and left a closed peer
    // in the map, so this pair was rejected as a duplicate and leaked state.
    const reconnectPair = await openPair();
    await waitForLocalTunnelSessions(bridgePort, token, 1,
            "one active local tunnel session after same-session reconnect");
    const reconnectClientPayload = Buffer.from("same-session-reconnect-client", "utf8");
    const reconnectServerPayload = Buffer.from("same-session-reconnect-server", "utf8");
    reconnectPair.client.send(reconnectClientPayload);
    await waitFor(() => reconnectPair.serverBytes === reconnectClientPayload.byteLength,
            "same-session reconnect client-to-server payload");
    if (!Buffer.concat(reconnectPair.serverFrames).equals(reconnectClientPayload)) {
        throw new Error("Same-session reconnect client-to-server bytes did not match");
    }
    reconnectPair.server.send(reconnectServerPayload);
    await waitFor(() => reconnectPair.clientBytes === reconnectServerPayload.byteLength,
            "same-session reconnect server-to-client payload");
    if (!Buffer.concat(reconnectPair.clientFrames).equals(reconnectServerPayload)) {
        throw new Error("Same-session reconnect server-to-client bytes did not match");
    }
    reconnectPair.server.close();
    await Promise.all([once(reconnectPair.server, "close"), once(reconnectPair.client, "close")]);
    const finalRuntime = await waitForLocalTunnelSessions(bridgePort, token, 0,
            "final local tunnel session cleanup");
    return {
        paired: true,
        clientToServerBytes: firstPair.serverBytes,
        serverToClientBytes: firstPair.clientBytes,
        sameSessionReconnect: true,
        reconnectClientToServerBytes: reconnectPair.serverBytes,
        reconnectServerToClientBytes: reconnectPair.clientBytes,
        activeLocalTunnelSessions: finalRuntime.activeLocalTunnelSessions,
    };
}

async function waitForLocalTunnelSessions(bridgePort, token, expected, label) {
    const deadline = Date.now() + 10000;
    let lastRuntime;
    while (Date.now() < deadline) {
        const response = await fetch(`http://${host}:${bridgePort}/relay-node/v1`, {
            headers: {
                origin,
                authorization: `Bearer ${token}`,
            },
        });
        if (!response.ok) {
            throw new Error(`RelayNode runtime manifest returned ${response.status} while waiting for ${label}`);
        }
        lastRuntime = (await response.json()).runtime;
        if (lastRuntime?.activeLocalTunnelSessions === expected) {
            return lastRuntime;
        }
        await delay(10);
    }
    throw new Error(`Timed out waiting for ${label}: ${JSON.stringify(lastRuntime)}`);
}

function encodePacket(id, payload, compressionThreshold) {
    const packet = Buffer.concat([encodeVarInt(id), payload]);
    let body = packet;
    if (compressionThreshold !== undefined) {
        body = packet.byteLength >= compressionThreshold
                ? Buffer.concat([encodeVarInt(packet.byteLength), deflateSync(packet)])
                : Buffer.concat([encodeVarInt(0), packet]);
    }
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

function decodeString(bytes, offset) {
    const encoded = decodeByteArray(bytes, offset);
    return {
        value: encoded.value.toString("utf8"),
        nextOffset: encoded.nextOffset,
    };
}

function decodeByteArray(bytes, offset) {
    const length = decodeVarInt(bytes, offset);
    if (length === undefined || length.value < 0) {
        throw new Error("Minecraft byte array omitted a valid length");
    }
    const start = offset + length.bytesRead;
    const end = start + length.value;
    if (end > bytes.byteLength) {
        throw new Error("Minecraft byte array exceeded its packet");
    }
    return { value: bytes.subarray(start, end), nextOffset: end };
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
        if (depth > maximumDepth) {
            throw new Error("Network NBT exceeded its maximum nesting depth");
        }
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
    const booleanInputKeys = inputDefinitions
            .filter((value) => value.type === "minecraft:boolean")
            .map((value) => value.key);
    const title = summarizeNbtValue(findFirstNamedValue(dialog, "title"));
    return {
        actionIds,
        inputs: inputDefinitions,
        booleanInputKeys,
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
            throw new Error(
                    `Requested dialog action ${requestedDialogAction} was not offered by the server`);
        }
        return requestedDialogAction;
    }
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
            if (/[^\x20-\x7e]/.test(supplied)) {
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
        if (key.byteLength === 0 || key.byteLength > 65_535 || /[^\x20-\x7e]/.test(name)) {
            throw new Error(`Server dialog supplied unsafe boolean input key ${name}`);
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

function encodeClientInformation() {
    return Buffer.concat([
        encodeString("en_us"),
        Buffer.from([minecraftClientViewDistance]),
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
        if ((next & 0x80) === 0) {
            return { value, bytesRead: index + 1 };
        }
    }
    throw new Error("Minecraft VarInt exceeded five bytes");
}
