import { spawn } from "node:child_process";
import {
    constants as cryptoConstants,
    createCipheriv,
    createDecipheriv,
    createHash,
    publicEncrypt,
    randomBytes,
} from "node:crypto";
import { createServer } from "node:net";
import { once } from "node:events";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";
import { deflateSync, inflateSync } from "node:zlib";
import { WebSocket } from "./node_modules/ws/wrapper.mjs";

const host = "127.0.0.1";
const origin = "http://127.0.0.1:8781";
const directory = fileURLToPath(new URL(".", import.meta.url));
const minecraftHost = process.env.GAIUS_SMOKE_MINECRAFT_HOST;
const minecraftPort = Number(process.env.GAIUS_SMOKE_MINECRAFT_PORT ?? "25565");
const minecraftSessionUrl = process.env.GAIUS_SMOKE_SESSION_URL;
const minecraftAccessToken = process.env.GAIUS_SMOKE_ACCESS_TOKEN ?? "gaius-smoke-token";
const minecraftProfileId = process.env.GAIUS_SMOKE_PROFILE_ID ??
        "00000000000040008000000000000002";
const minecraftUsername = process.env.GAIUS_SMOKE_USERNAME ?? "GaiusSmoke";
const bridgePort = await reservePort();
const fixture = createServer();
await new Promise((resolve, reject) => {
    fixture.once("error", reject);
    fixture.listen(0, host, resolve);
});
const fixturePort = fixture.address().port;

let fixtureSocket;
let echoEnabled = true;
fixture.on("connection", (socket) => {
    fixtureSocket = socket;
    socket.setNoDelay(true);
    socket.on("data", (chunk) => {
        if (echoEnabled) {
            socket.write(chunk);
        }
    });
});

const bridge = spawn(process.execPath, ["dist/main.js"], {
    cwd: directory,
    env: {
        ...process.env,
        GAIUS_BRIDGE_HOST: host,
        GAIUS_BRIDGE_PORT: String(bridgePort),
        GAIUS_ALLOWED_ORIGINS: origin,
        GAIUS_ALLOWED_HOSTS: [host, minecraftHost].filter(Boolean).join(","),
        GAIUS_IDLE_TIMEOUT_MS: "60000",
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
try {
    await waitFor(
            () => bridgeOutput.includes("Gaius bridge listening"),
            "bridge startup");

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
    }));
    await waitFor(
            () => controls.some((message) => message.type === "connected"),
            "TCP tunnel connection");
    await waitFor(() => fixtureSocket !== undefined, "fixture connection");

    const upload = patternedBuffer(4 * 1024 * 1024, 0x31);
    webSocket.send(upload);
    await waitFor(() => echoedBytes === upload.byteLength, "4 MiB tunnel echo");
    if (!Buffer.concat(echoed, echoedBytes).equals(upload)) {
        throw new Error("Tunnel echo bytes did not match the upload");
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

    const localTunnel = await testLocalTunnelPair(bridgePort);
    const minecraftLogin = minecraftHost
            ? await testMinecraftLogin(bridgePort, minecraftHost, minecraftPort, {
                sessionUrl: minecraftSessionUrl,
                accessToken: minecraftAccessToken,
                profileId: minecraftProfileId,
                username: minecraftUsername,
            })
            : undefined;
    console.log(JSON.stringify({
        ok: true,
        echoBytes: echoedBytes,
        pausedBytes: 0,
        resumedBytes: floodedBytes,
        sha256: actualHash,
        localTunnel,
        ...(minecraftLogin === undefined ? {} : { minecraftLogin }),
    }));
}
finally {
    webSocket?.close();
    fixtureSocket?.destroy();
    await new Promise((resolve) => fixture.close(resolve));
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

async function waitFor(predicate, label, timeoutMs = 10000) {
    const deadline = Date.now() + timeoutMs;
    while (!predicate()) {
        if (Date.now() >= deadline) {
            throw new Error(`Timed out waiting for ${label}`);
        }
        await delay(10);
    }
}

function patternedBuffer(length, seed) {
    const bytes = Buffer.allocUnsafe(length);
    for (let index = 0; index < bytes.length; index++) {
        bytes[index] = (index * 31 + (index >>> 7) + seed) & 0xff;
    }
    return bytes;
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

async function testMinecraftLogin(bridgePort, serverHost, serverPort, session) {
    const socket = new WebSocket(`ws://${host}:${bridgePort}/tunnel`, {
        headers: { origin },
    });
    await once(socket, "open");

    const controls = [];
    let buffered = Buffer.alloc(0);
    let cipher;
    let decipher;
    let encryptionRequest = false;
    let sessionJoin = false;
    let compressionThreshold;
    let phase = "login";
    let loginFinished = false;
    let configurationPackets = 0;
    let configurationFinished = false;
    let knownPackRequests = 0;
    let playPackets = 0;
    let playLoginPackets = 0;
    let chunkPackets = 0;
    let playerLoadedSent = false;
    let protocolFailure;
    socket.on("error", (error) => {
        protocolFailure = error;
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
                if (phase === "login" && packetId.value === 0) {
                    throw new Error("Minecraft server rejected the smoke login");
                }
                if (phase === "login" && packetId.value === 1) {
                    if (session.sessionUrl === undefined) {
                        throw new Error(
                                "Minecraft server requested online-mode encryption without a smoke session service");
                    }
                    encryptionRequest = true;
                    void answerEncryptionRequest(payload).catch((error) => {
                        protocolFailure = error;
                    });
                }
                else if (phase === "login" && packetId.value === 3) {
                    const threshold = decodeVarInt(packet, packetId.bytesRead);
                    if (threshold === undefined || threshold.value < 0) {
                        throw new Error("Minecraft server sent an invalid compression threshold");
                    }
                    compressionThreshold = threshold.value;
                }
                else if (phase === "login" && packetId.value === 2) {
                    loginFinished = true;
                    phase = "configuration";
                    sendMinecraftPacket(3, Buffer.alloc(0));
                    sendMinecraftPacket(0, encodeClientInformation());
                }
                else if (phase === "configuration") {
                    configurationPackets++;
                    if (packetId.value === 2) {
                        throw new Error("Minecraft server disconnected during configuration");
                    }
                    if (packetId.value === 14) {
                        knownPackRequests++;
                        sendMinecraftPacket(7, encodeVarInt(0));
                    }
                    else if (packetId.value === 3) {
                        configurationFinished = true;
                        phase = "play";
                        sendMinecraftPacket(3, Buffer.alloc(0));
                    }
                    else if (packetId.value === 4) {
                        sendMinecraftPacket(4, payload);
                    }
                    else if (packetId.value === 5) {
                        sendMinecraftPacket(5, payload);
                    }
                }
                else if (phase === "play") {
                    playPackets++;
                    if (packetId.value === 32) {
                        throw new Error("Minecraft server disconnected after entering PLAY");
                    }
                    if (packetId.value === 43) {
                        sendMinecraftPacket(27, payload);
                    }
                    else if (packetId.value === 59) {
                        sendMinecraftPacket(44, payload);
                    }
                    else if (packetId.value === 48) {
                        playLoginPackets++;
                        if (!playerLoadedSent) {
                            playerLoadedSent = true;
                            sendMinecraftPacket(43, Buffer.alloc(0));
                        }
                    }
                    else if (packetId.value === 44) {
                        chunkPackets++;
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
        const keyPayload = Buffer.concat([
            encodeByteArray(encryptedSecret),
            encodeByteArray(encryptedChallenge),
        ]);
        socket.send(encodePacket(1, keyPayload));
        cipher = createCipheriv("aes-128-cfb8", secret, secret);
        decipher = createDecipheriv("aes-128-cfb8", secret, secret);
    }

    socket.send(JSON.stringify({ type: "connect", host: serverHost, port: serverPort }));
    await waitFor(() => {
        if (protocolFailure !== undefined) throw protocolFailure;
        return controls.some((message) => message.type === "connected");
    }, "Minecraft TCP connection");

    const handshake = Buffer.concat([
        encodeVarInt(774),
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
    }, "Minecraft Login Finished");
    await waitFor(() => {
        if (protocolFailure !== undefined) throw protocolFailure;
        return configurationFinished && playLoginPackets > 0 && chunkPackets > 0;
    }, "Minecraft PLAY login and chunk data", 30000);

    socket.close();
    await once(socket, "close");
    return {
        server: `${serverHost}:${serverPort}`,
        onlineMode: encryptionRequest,
        sessionJoin,
        compressionThreshold: compressionThreshold ?? null,
        loginFinished,
        configurationPackets,
        configurationFinished,
        knownPackRequests,
        playPackets,
        playLoginPackets,
        chunkPackets,
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

async function testLocalTunnelPair(bridgePort) {
    const sessionId = "0123456789abcdef0123456789abcdef";
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
    }));
    server.send(JSON.stringify({
        type: "connect",
        host: `server-${sessionId}.gaius-local`,
        port: 25565,
    }));
    await waitFor(
            () => clientControls.some((message) => message.type === "connected") &&
                serverControls.some((message) => message.type === "connected"),
            "paired local server tunnel");

    const clientPayload = patternedBuffer(2 * 1024 * 1024, 0x53);
    client.send(clientPayload);
    await waitFor(() => serverBytes === clientPayload.byteLength, "local client-to-server payload");
    if (!Buffer.concat(serverFrames, serverBytes).equals(clientPayload)) {
        throw new Error("Local client-to-server bytes did not match");
    }

    const serverPayload = patternedBuffer(2 * 1024 * 1024, 0x71);
    server.send(serverPayload);
    await waitFor(() => clientBytes === serverPayload.byteLength, "local server-to-client payload");
    if (!Buffer.concat(clientFrames, clientBytes).equals(serverPayload)) {
        throw new Error("Local server-to-client bytes did not match");
    }

    client.close();
    await Promise.all([once(client, "close"), once(server, "close")]);
    return {
        paired: true,
        clientToServerBytes: serverBytes,
        serverToClientBytes: clientBytes,
    };
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
        if ((next & 0x80) === 0) {
            return { value, bytesRead: index + 1 };
        }
    }
    throw new Error("Minecraft VarInt exceeded five bytes");
}
