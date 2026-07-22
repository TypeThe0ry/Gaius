import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { createServer } from "node:http";
import {
    mkdir,
    mkdtemp,
    readFile,
    symlink,
    writeFile,
} from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";

const repository = fileURLToPath(new URL("../../", import.meta.url));
const bridgeDirectory = fileURLToPath(new URL(".", import.meta.url));
const targetDirectory = path.join(repository, "port", "target");
const vanillaDirectory = path.join(targetDirectory, "multiplayer-smoke-server");
const serverJar = path.join(vanillaDirectory, "server.jar");
const smokeScript = path.join(bridgeDirectory, "multiplayer-smoke.mjs");
const profileId = "00000000000040008000000000000002";
const username = "GaiusOnline";
const accessToken = "gaius-online-mode-smoke-token";
const enforceSecureProfile = process.env.GAIUS_SMOKE_ENFORCE_SECURE_PROFILE === "true";

await mkdir(targetDirectory, {recursive: true});
const workDirectory = await mkdtemp(path.join(targetDirectory, "online-mode-smoke-"));
await Promise.all([
    symlink(path.join(vanillaDirectory, "libraries"), path.join(workDirectory, "libraries")),
    symlink(path.join(vanillaDirectory, "versions"), path.join(workDirectory, "versions")),
]);

const sessionState = {
    joins: [],
    hasJoined: [],
    publicKeyRequests: 0,
};
const sessionServer = createServer(async (request, response) => {
    try {
        const requestUrl = new URL(request.url ?? "/", "http://127.0.0.1");
        if (request.method === "GET" && requestUrl.pathname === "/publickeys") {
            sessionState.publicKeyRequests++;
            sendJson(response, 200, {
                profilePropertyKeys: [],
                playerCertificateKeys: [],
            });
            return;
        }
        if (request.method === "POST" &&
                requestUrl.pathname === "/session/minecraft/join") {
            const body = JSON.parse((await readBody(request)).toString("utf8"));
            if (body.accessToken !== accessToken || body.selectedProfile !== profileId ||
                    typeof body.serverId !== "string" || body.serverId.length === 0) {
                sendJson(response, 403, {error: "invalid smoke join"});
                return;
            }
            sessionState.joins.push(body);
            response.writeHead(204);
            response.end();
            return;
        }
        if (request.method === "GET" &&
                requestUrl.pathname === "/session/minecraft/hasJoined") {
            const query = Object.fromEntries(requestUrl.searchParams);
            sessionState.hasJoined.push(query);
            const join = sessionState.joins.at(-1);
            if (query.username !== username || join === undefined ||
                    query.serverId !== join.serverId) {
                response.writeHead(204);
                response.end();
                return;
            }
            sendJson(response, 200, {
                id: profileId,
                properties: [],
                profileActions: [],
            });
            return;
        }
        if (request.method === "GET" &&
                requestUrl.pathname.startsWith("/session/minecraft/profile/")) {
            sendJson(response, 200, {
                id: profileId,
                name: username,
                properties: [],
                profileActions: [],
            });
            return;
        }
        sendJson(response, 404, {error: "not found", path: requestUrl.pathname});
    }
    catch (error) {
        sendJson(response, 500, {error: String(error)});
    }
});
await new Promise((resolve, reject) => {
    sessionServer.once("error", reject);
    sessionServer.listen(0, "127.0.0.1", resolve);
});
const sessionPort = sessionServer.address().port;
const minecraftPort = await reservePort();
const sessionBaseUrl = `http://127.0.0.1:${sessionPort}`;

await Promise.all([
    writeFile(path.join(workDirectory, "eula.txt"), "eula=true\n"),
    writeFile(path.join(workDirectory, "server.properties"), serverProperties(minecraftPort)),
]);

const javaServer = spawn("/usr/bin/java", [
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
});
let serverOutput = "";
for (const stream of [javaServer.stdout, javaServer.stderr]) {
    stream.setEncoding("utf8");
    stream.on("data", (chunk) => {
        serverOutput += chunk;
    });
}

try {
    await waitFor(
            () => serverOutput.includes("Done (") || javaServer.exitCode !== null,
            "online-mode vanilla server startup",
            60000);
    if (javaServer.exitCode !== null || !serverOutput.includes("Done (")) {
        throw new Error("Vanilla online-mode server failed to start:\n" + serverOutput);
    }

    const smoke = await runSmoke({
        GAIUS_SMOKE_MINECRAFT_HOST: "127.0.0.1",
        GAIUS_SMOKE_MINECRAFT_PORT: String(minecraftPort),
        GAIUS_SMOKE_SESSION_URL: sessionBaseUrl,
        GAIUS_SMOKE_ACCESS_TOKEN: accessToken,
        GAIUS_SMOKE_PROFILE_ID: profileId,
        GAIUS_SMOKE_USERNAME: username,
    });
    const login = smoke.minecraftLogin;
    if (!login?.onlineMode || !login.sessionJoin || !login.loginFinished ||
            !login.configurationFinished || login.playLoginPackets < 1 ||
            login.chunkPackets < 1) {
        throw new Error("Online-mode smoke did not reach PLAY with chunk data");
    }
    if (sessionState.joins.length !== 1 || sessionState.hasJoined.length !== 1 ||
            sessionState.hasJoined[0].serverId !== sessionState.joins[0].serverId) {
        throw new Error("Session join/hasJoined authentication did not match");
    }

    const serverJarSha256 = createHash("sha256")
            .update(await readFile(serverJar))
            .digest("hex");
    console.log(JSON.stringify({
        ok: true,
        serverJarSha256,
        unmodifiedVanillaServer: true,
        pluginsInstalled: false,
        enforceSecureProfile,
        session: {
            joins: sessionState.joins.length,
            hasJoined: sessionState.hasJoined.length,
            serverHash: sessionState.joins[0].serverId,
            publicKeyRequests: sessionState.publicKeyRequests,
        },
        minecraftLogin: login,
        workDirectory,
    }));
}
finally {
    javaServer.stdin.write("stop\n");
    await Promise.race([
        new Promise((resolve) => javaServer.once("exit", resolve)),
        delay(15000).then(() => {
            if (javaServer.exitCode === null) javaServer.kill("SIGTERM");
        }),
    ]);
    await new Promise((resolve) => sessionServer.close(resolve));
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

async function reservePort() {
    const server = createServer();
    await new Promise((resolve, reject) => {
        server.once("error", reject);
        server.listen(0, "127.0.0.1", resolve);
    });
    const port = server.address().port;
    await new Promise((resolve) => server.close(resolve));
    return port;
}

async function waitFor(predicate, label, timeoutMs) {
    const deadline = Date.now() + timeoutMs;
    while (!predicate()) {
        if (Date.now() >= deadline) throw new Error(`Timed out waiting for ${label}`);
        await delay(25);
    }
}

async function runSmoke(extraEnvironment) {
    const child = spawn(process.execPath, [smokeScript], {
        cwd: repository,
        env: {...process.env, ...extraEnvironment},
        stdio: ["ignore", "pipe", "pipe"],
    });
    let output = "";
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => { output += chunk; });
    child.stderr.on("data", (chunk) => { output += chunk; });
    const exitCode = await new Promise((resolve) => child.once("exit", resolve));
    if (exitCode !== 0) throw new Error("Bridge online-mode smoke failed:\n" + output);
    const lines = output.trim().split("\n");
    return JSON.parse(lines.at(-1));
}

function serverProperties(serverPort) {
    return [
        "accepts-transfers=false",
        "allow-flight=true",
        "allow-nether=false",
        "difficulty=peaceful",
        "enable-command-block=false",
        "enable-query=false",
        "enable-rcon=false",
        "enable-status=true",
        `enforce-secure-profile=${enforceSecureProfile}`,
        "gamemode=creative",
        "generate-structures=false",
        "level-name=world-online",
        "level-seed=1",
        "level-type=minecraft:flat",
        "log-ips=false",
        "max-players=4",
        "motd=Gaius vanilla online-mode smoke",
        "network-compression-threshold=256",
        "online-mode=true",
        "pause-when-empty-seconds=0",
        "player-idle-timeout=0",
        "prevent-proxy-connections=false",
        "pvp=false",
        "rate-limit=0",
        "server-ip=127.0.0.1",
        `server-port=${serverPort}`,
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
