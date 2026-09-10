import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { createServer } from "node:http";
import {
    copyFile,
    lstat,
    mkdir,
    mkdtemp,
    readFile,
    writeFile,
    rename,
} from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";

const repository = fileURLToPath(new URL("../../", import.meta.url));
const bridgeDirectory = fileURLToPath(new URL(".", import.meta.url));
const smokeScript = path.join(bridgeDirectory, "multiplayer-smoke.mjs");
const profileId = "00000000000040008000000000000002";
const username = "GaiusOnline";
const accessToken = "gaius-online-mode-smoke-token";
const enforceSecureProfile = process.env.GAIUS_SMOKE_ENFORCE_SECURE_PROFILE === "true";

if (process.argv.includes("--self-smoke") || process.argv.includes("--self-test") ||
        process.env.GAIUS_ONLINE_MODE_SELF_SMOKE === "1") {
    await runStaticSelfSmoke();
    process.exit(0);
}

const activeProfile = await loadActiveVersionProfile();
const targetDirectory = resolveBuildRoot(activeProfile.id);
const vanillaDirectory = resolveSmokeServerDirectory(targetDirectory);
const serverJar = resolveSmokeServerJar(vanillaDirectory);
const evidenceDirectory = resolveEvidenceDirectory(targetDirectory);
await mkdir(targetDirectory, {recursive: true});
await mkdir(evidenceDirectory, {recursive: true});
const verifiedServerJar = await ensureVerifiedServerJar(serverJar, activeProfile);
const workDirectory = await mkdtemp(path.join(evidenceDirectory, "run-"));
const runtimeServerJar = path.join(workDirectory, "server.jar");
await copyFile(verifiedServerJar.path, runtimeServerJar);
const runtimeServerJarSha1 = await sha1File(runtimeServerJar);
if (runtimeServerJarSha1 !== activeProfile.official.serverSha1.toLowerCase()) {
    throw new Error("The isolated vanilla server.jar copy failed SHA-1 verification");
}

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

let javaExecutable;
let javaServer;
let javaSpawnError;
let serverOutput = "";

try {
    javaExecutable = await resolveJavaExecutable(activeProfile.javaVersion);
    javaServer = spawn(javaExecutable, [
        `-Dminecraft.api.session.host=${sessionBaseUrl}`,
        `-Dminecraft.api.services.host=${sessionBaseUrl}`,
        `-Dminecraft.api.profiles.host=${sessionBaseUrl}`,
        "-Xms512m",
        "-Xmx1536m",
        "-jar",
        runtimeServerJar,
        "nogui",
    ], {
        cwd: workDirectory,
        stdio: ["pipe", "pipe", "pipe"],
        windowsHide: true,
    });
    javaServer.once("error", (error) => {
        javaSpawnError = error;
        serverOutput += `\nJava process error: ${error.stack || error}\n`;
    });
    for (const stream of [javaServer.stdout, javaServer.stderr]) {
        stream.setEncoding("utf8");
        stream.on("data", (chunk) => {
            serverOutput += chunk;
        });
    }
    await waitFor(
            () => serverOutput.includes("Done (") || javaServer.exitCode !== null ||
                javaSpawnError !== undefined,
            "online-mode vanilla server startup",
            Number.parseInt(process.env.GAIUS_SMOKE_STARTUP_TIMEOUT_MS ?? "180000", 10) || 180000);
    if (javaSpawnError !== undefined || javaServer.exitCode !== null ||
            !serverOutput.includes("Done (")) {
        throw new Error("Vanilla online-mode server failed to start:\n" + serverOutput);
    }

    const smoke = await runSmoke({
        GAIUS_SMOKE_MINECRAFT_HOST: "127.0.0.1",
        GAIUS_SMOKE_MINECRAFT_PORT: String(minecraftPort),
        GAIUS_SMOKE_SESSION_URL: sessionBaseUrl,
        GAIUS_SMOKE_ACCESS_TOKEN: accessToken,
        GAIUS_SMOKE_PROFILE_ID: profileId,
        GAIUS_SMOKE_USERNAME: username,
        GAIUS_SMOKE_MINECRAFT_VERSION: activeProfile.id,
        GAIUS_VERSION_PROFILE_PATH: activeProfile.relativePath,
    }, path.join(workDirectory, "bridge-smoke.log"));
    const login = smoke.minecraftLogin;
    if (!login?.onlineMode || !login.rsa?.requested || !login.rsa.secretEncrypted ||
            !login.rsa.challengeEncrypted || login.rsa.padding !== "RSA_PKCS1_PADDING" ||
            !login.aes?.enabled || login.aes.cipher !== "aes-128-cfb8" ||
            !login.sessionJoin || !login.loginFinished ||
            !login.configurationFinished || login.playLoginPackets < 1 ||
            login.chunkPackets < 1) {
        throw new Error("Online-mode smoke did not prove RSA/AES/session join and PLAY chunk data");
    }
    if (sessionState.joins.length !== 1 || sessionState.hasJoined.length !== 1 ||
            sessionState.hasJoined[0].serverId !== sessionState.joins[0].serverId) {
        throw new Error("Session join/hasJoined authentication did not match");
    }

    const serverJarSha256 = createHash("sha256")
            .update(await readFile(verifiedServerJar.path))
            .digest("hex");
    const result = {
        ok: true,
        profile: {
            id: activeProfile.id,
            protocolVersion: activeProfile.protocolVersion,
            javaVersion: activeProfile.javaVersion,
            profilePath: activeProfile.path,
        },
        serverJar: {
            path: verifiedServerJar.path,
            sha1: verifiedServerJar.sha1,
            expectedSha1: activeProfile.official.serverSha1,
            sha256: serverJarSha256,
            downloaded: verifiedServerJar.downloaded,
        },
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
        evidenceDirectory,
        javaExecutable,
    };
    await writeFile(path.join(workDirectory, "result.json"),
            JSON.stringify(result, null, 2) + "\n");
    console.log(JSON.stringify(result));
}
finally {
    if (javaServer !== undefined) {
        if (javaServer.exitCode === null && !javaServer.stdin.destroyed) {
            javaServer.stdin.write("stop\n");
        }
        if (javaServer.exitCode === null) {
            await Promise.race([
                new Promise((resolve) => javaServer.once("exit", resolve)),
                delay(15000).then(() => {
                    if (javaServer.exitCode === null) javaServer.kill("SIGTERM");
                }),
            ]);
        }
    }
    await new Promise((resolve) => sessionServer.close(resolve));
    await writeFile(path.join(workDirectory, "server.log"), serverOutput);
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

function nativePath(value) {
    if (value === undefined || value === null) return value;
    const text = String(value).trim().replaceAll("\\", "/");
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
    return relative === "" || (relative !== ".." && !relative.startsWith(`..${path.sep}`) &&
        !path.isAbsolute(relative));
}

async function loadActiveVersionProfile() {
    const configPath = path.join(repository, "port", "config.json");
    const config = JSON.parse(await readFile(configPath, "utf8"));
    let selected = String(
            process.env.GAIUS_VERSION_PROFILE_PATH ?? config.versionProfile ?? "").trim();
    if (!selected) throw new Error("GAIUS_VERSION_PROFILE_PATH or port/config.json.versionProfile is required");
    selected = nativePath(selected);
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
        throw new Error(`Active version profile must be a JSON file inside port/versions: ${profilePath}`);
    }
    const profile = JSON.parse(await readFile(profilePath, "utf8"));
    if (typeof profile.id !== "string" || profile.id.length === 0 ||
            !Number.isInteger(profile.protocolVersion) || !Number.isInteger(profile.javaVersion) ||
            typeof profile.official?.serverSha1 !== "string" ||
            !/^[0-9a-f]{40}$/iu.test(profile.official.serverSha1)) {
        throw new Error(`Active version profile is missing required server smoke fields: ${profilePath}`);
    }
    const relativePath = path.relative(path.join(repository, "port"), profilePath)
            .replaceAll(path.sep, "/");
    return {
        ...profile,
        path: profilePath,
        relativePath,
        official: {
            ...profile.official,
            serverSha1: profile.official.serverSha1.toLowerCase(),
        },
    };
}

function resolveBuildRoot(profile) {
    return process.env.GAIUS_BUILD_ROOT?.trim()
        ? resolveRepositoryPath(process.env.GAIUS_BUILD_ROOT)
        : path.join(repository, "port", "target", profile);
}

function resolveSmokeServerDirectory(buildRoot) {
    if (process.env.GAIUS_SMOKE_SERVER_DIRECTORY?.trim()) {
        return resolveRepositoryPath(process.env.GAIUS_SMOKE_SERVER_DIRECTORY);
    }
    if (process.env.GAIUS_SMOKE_SERVER_JAR?.trim()) {
        return path.dirname(resolveRepositoryPath(process.env.GAIUS_SMOKE_SERVER_JAR));
    }
    return path.join(buildRoot, "multiplayer-smoke-server");
}

function resolveSmokeServerJar(serverDirectory) {
    return process.env.GAIUS_SMOKE_SERVER_JAR?.trim()
        ? resolveRepositoryPath(process.env.GAIUS_SMOKE_SERVER_JAR)
        : path.join(serverDirectory, "server.jar");
}

function resolveEvidenceDirectory(buildRoot) {
    return process.env.GAIUS_SMOKE_EVIDENCE_DIRECTORY?.trim()
        ? resolveRepositoryPath(process.env.GAIUS_SMOKE_EVIDENCE_DIRECTORY)
        : path.join(buildRoot, "online-mode-evidence");
}

async function lstatRegularFile(filePath) {
    let info;
    try {
        info = await lstat(filePath);
    }
    catch (error) {
        if (error?.code === "ENOENT") return false;
        throw error;
    }
    if (info.isSymbolicLink()) {
        throw new Error(`Refusing symlink server.jar; provide the actual file: ${filePath}`);
    }
    if (!info.isFile()) {
        throw new Error(`Configured server.jar is not a regular file: ${filePath}`);
    }
    return true;
}

async function sha1File(filePath) {
    return createHash("sha1").update(await readFile(filePath)).digest("hex");
}

async function ensureVerifiedServerJar(filePath, profile) {
    let downloaded = false;
    let exists = await lstatRegularFile(filePath);
    if (!exists) {
        if (process.env.GAIUS_SMOKE_SERVER_JAR?.trim()) {
            throw new Error(`Configured GAIUS_SMOKE_SERVER_JAR does not exist: ${filePath}`);
        }
        await mkdir(path.dirname(filePath), {recursive: true});
        await downloadOfficialServerJar(filePath, profile);
        downloaded = true;
        exists = await lstatRegularFile(filePath);
    }
    if (!exists) throw new Error(`Vanilla server.jar is missing: ${filePath}`);
    const sha1 = await sha1File(filePath);
    if (sha1 !== profile.official.serverSha1.toLowerCase()) {
        throw new Error(`server.jar SHA-1 mismatch for ${profile.id}: ${sha1} != ${profile.official.serverSha1}`);
    }
    return {path: filePath, sha1, downloaded};
}

async function downloadOfficialServerJar(filePath, profile) {
    const manifestUrl = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    const manifestResponse = await fetch(manifestUrl);
    if (!manifestResponse.ok) {
        throw new Error(`Mojang version manifest returned HTTP ${manifestResponse.status}`);
    }
    const manifest = await manifestResponse.json();
    const entry = manifest.versions?.find((candidate) => candidate.id === profile.id);
    if (!entry?.url) throw new Error(`Mojang version manifest has no ${profile.id} metadata`);
    const metadataResponse = await fetch(entry.url);
    if (!metadataResponse.ok) {
        throw new Error(`Mojang ${profile.id} metadata returned HTTP ${metadataResponse.status}`);
    }
    const metadata = await metadataResponse.json();
    const download = metadata.downloads?.server;
    if (!download?.url || download.sha1?.toLowerCase() !== profile.official.serverSha1.toLowerCase()) {
        throw new Error(`Mojang ${profile.id} server metadata SHA-1 does not match the active profile`);
    }
    const response = await fetch(download.url);
    if (!response.ok) throw new Error(`Mojang ${profile.id} server.jar returned HTTP ${response.status}`);
    const bytes = Buffer.from(await response.arrayBuffer());
    const actualSha1 = createHash("sha1").update(bytes).digest("hex");
    if (actualSha1 !== profile.official.serverSha1.toLowerCase()) {
        throw new Error(`Downloaded ${profile.id} server.jar SHA-1 mismatch: ${actualSha1}`);
    }
    const temporaryPath = `${filePath}.download-${process.pid}-${Date.now()}-${Math.random()
            .toString(16).slice(2)}.tmp`;
    // wx plus a unique name means concurrent smoke runs never overwrite each
    // other. Keep a failed temporary artifact for post-mortem evidence.
    await writeFile(temporaryPath, bytes, {flag: "wx"});
    try {
        await lstat(filePath);
        throw new Error(`Refusing to overwrite an existing server.jar: ${filePath}`);
    }
    catch (error) {
        if (error?.code !== "ENOENT") throw error;
    }
    await rename(temporaryPath, filePath);
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
        const normalized = nativePath(home);
        addCandidate(path.join(normalized, "bin", "java"));
        addCandidate(path.join(normalized, "bin", "java.exe"));
    }
    candidates.push("java");
    const diagnostics = [];
    for (const candidate of [...new Set(candidates)]) {
        if (candidate !== "java" && candidate !== "java.exe") {
            try {
                const info = await lstat(candidate);
                if (!info.isFile()) continue;
            }
            catch (error) {
                if (error?.code === "ENOENT") continue;
                diagnostics.push(`${candidate}: ${error.message}`);
                continue;
            }
        }
        const result = await probeJava(candidate);
        if (result.error !== undefined) {
            diagnostics.push(`${candidate}: ${result.error}`);
            continue;
        }
        if (result.major >= requiredVersion) return candidate;
        diagnostics.push(`${candidate}: Java ${result.major} is older than ${requiredVersion}`);
    }
    throw new Error(`No compatible Java executable for Minecraft ${activeProfile.id}: ${diagnostics.join("; ")}`);
}

async function probeJava(candidate) {
    return await new Promise((resolve) => {
        let output = "";
        let settled = false;
        let timer;
        const child = spawn(candidate, ["-version"], {
            stdio: ["ignore", "pipe", "pipe"],
            windowsHide: true,
        });
        const finish = (value) => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            resolve(value);
        };
        child.stdout.setEncoding("utf8");
        child.stderr.setEncoding("utf8");
        child.stdout.on("data", (chunk) => { output += chunk; });
        child.stderr.on("data", (chunk) => { output += chunk; });
        child.once("error", (error) => finish({error: error.message}));
        child.once("close", (code) => {
            if (code !== 0) {
                finish({error: `exited ${code}: ${output.trim()}`});
                return;
            }
            const match = output.match(/version\s+["']?(\d+)/iu) ||
                output.match(/(?:openjdk|java)\s+(\d+)/iu);
            finish(match ? {major: Number(match[1])} : {error: `could not parse version: ${output.trim()}`});
        });
        timer = setTimeout(() => {
            child.kill();
            finish({error: "timed out probing Java"});
        }, 5000);
    });
}

async function runStaticSelfSmoke() {
    const source = await readFile(fileURLToPath(import.meta.url), "utf8");
    const forbiddenJavaPath = ["/usr", "bin", "java"].join("/");
    if (source.includes(forbiddenJavaPath) || /^\s*symlink,\s*$/mu.test(source)) {
        throw new Error("online-mode smoke retained a platform-specific Java or symlink dependency");
    }
    const profile = await loadActiveVersionProfile();
    const target = resolveBuildRoot(profile.id);
    const serverDirectory = resolveSmokeServerDirectory(target);
    const serverJarPath = resolveSmokeServerJar(serverDirectory);
    if (!pathInside(target, resolveEvidenceDirectory(target)) &&
            !process.env.GAIUS_SMOKE_EVIDENCE_DIRECTORY) {
        throw new Error("default smoke evidence escaped the profile-scoped build root");
    }
    if (!serverJarPath.toLowerCase().endsWith(`${path.sep}server.jar`)) {
        throw new Error("smoke server jar resolver did not produce server.jar");
    }
    console.log(JSON.stringify({
        ok: true,
        selfSmoke: true,
        profile: profile.id,
        protocolVersion: profile.protocolVersion,
        buildRoot: target,
        serverDirectory,
        serverJar: serverJarPath,
        evidenceDirectory: resolveEvidenceDirectory(target),
    }));
}

async function runSmoke(extraEnvironment, outputPath) {
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
    if (outputPath !== undefined) await writeFile(outputPath, output);
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
