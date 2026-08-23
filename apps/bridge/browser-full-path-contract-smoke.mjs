import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { chmod, mkdtemp, readFile, rm, unlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";
import path from "node:path";

const bridgeDirectory = fileURLToPath(new URL(".", import.meta.url));
const fullPathScript = fileURLToPath(
    new URL("./browser-full-path-smoke.mjs", import.meta.url));
const acceptanceRunnerPath = fileURLToPath(
    new URL("./browser-full-path-acceptance.mjs", import.meta.url));
const relayMainPath = fileURLToPath(new URL("./dist/main.js", import.meta.url));
const packagePath = fileURLToPath(new URL("./package.json", import.meta.url));

const COMPATIBILITY_ENVIRONMENT = Object.freeze({
    GAIUS_BROWSER_FULL_PATH_ACCEPTANCE: "0",
    GAIUS_BROWSER_FULL_PATH_CLIENTS: "2",
    GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS: "9",
    GAIUS_BROWSER_FULL_PATH_SOAK_MS: "1000",
    GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES: "0",
});

function sanitizedEnvironment(profilePath, overrides = {}) {
    const environment = { ...process.env };
    for (const name of Object.keys(environment)) {
        if (name.toUpperCase().startsWith("GAIUS_BROWSER_FULL_PATH_")) {
            delete environment[name];
        }
    }
    return {
        ...environment,
        ...COMPATIBILITY_ENVIRONMENT,
        ...overrides,
        GAIUS_VERSION_PROFILE_PATH: profilePath,
    };
}

function configuration(profilePath, overrides = {}, argumentsList = ["--print-config"]) {
    return JSON.parse(execFileSync(process.execPath, [fullPathScript, ...argumentsList], {
        cwd: bridgeDirectory,
        env: sanitizedEnvironment(profilePath, overrides),
        encoding: "utf8",
        stdio: ["ignore", "pipe", "pipe"],
    }));
}

const expectedProfiles = [
    {
        path: "versions/26.2.json", id: "26.2", protocol: 776, world: 4903,
        java: 25, serverSha1: "823e2250d24b3ddac457a60c92a6a941943fcd6a",
    },
    {
        path: "versions/1.21.11.json", id: "1.21.11", protocol: 774, world: 4671,
        java: 21, serverSha1: "64bb6d763bed0a9f1d632ec347938594144943ed",
    },
];

for (const expected of expectedProfiles) {
    const config = configuration(expected.path);
    assert.equal(config.profile.id, expected.id);
    assert.equal(config.profile.protocolVersion, expected.protocol);
    assert.equal(config.profile.worldVersion, expected.world);
    assert.equal(config.profile.javaVersion, expected.java);
    assert.equal(config.profile.serverSha1, expected.serverSha1);
    assert.equal(config.profile.expectedServerJarSha1, expected.serverSha1);
    assert.equal(config.wireProfile.name, expected.id);
    assert.equal(config.wireProfile.protocolVersion, expected.protocol);
    assert.equal(config.acceptanceMode, false);
    assert.equal(config.clients, 2);
    assert.equal(config.performanceContract.mode, "compatible-smoke");
    assert.equal(config.performanceContract.strictAcceptanceTarget, null);
    assert.equal(config.performanceContract.soakMillis, 1000);
    assert.equal(config.performanceContract.reconnectWaves, 0);
    assert.deepEqual(config.performanceContract.canonicalProfiles[expected.id], {
        protocolVersion: expected.protocol,
        worldVersion: expected.world,
        javaVersion: expected.java,
        serverSha1: expected.serverSha1,
    });
    assert.deepEqual(config.performanceContract.relayRuntimeGauges, [
        "activeLocalTunnelSessions",
        "pendingSyntheticPlayTicks",
        "activeServerFrameDrainHandles",
        "activeServerFrameDrainTimers",
        "activeClientStallTimers",
    ]);
    assert.deepEqual(config.performanceContract.browserRuntimeCleanupGauges, [
        "activeHighWatermarks",
        "decodedSliceBacklog",
        "decoderCumulationBytes",
        "decodedPacketQueue",
    ]);
    assert.equal(config.performanceContract.syntheticMarkerLabel,
        "synthetic-inbound-marker");
    assert.equal(config.performanceContract.minimumChunkPackets, 9);
    assert.equal(config.performanceContract.reconnectWaves, 0);
    assert.equal(config.performanceContract.lifecycleCleanupRequired, true);
    assert.deepEqual(config.performanceContract.reconnect, {
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
            "chunk-9",
        ],
    });
    assert.deepEqual(config.performanceContract.requiredMilestones, [
        "relay-connected",
        "login-finished",
        "configuration-finished",
        "play-login",
        "first-chunk",
        "chunk-9",
    ]);
}

// The contract runner itself must not inherit a developer's strict gate.  This
// deliberately pollutes the parent environment with every browser gate knob;
// configuration() must clear all of them and inject the compatibility baseline.
const pollutedGateValues = {
    GAIUS_BROWSER_FULL_PATH_ACCEPTANCE: "1",
    GAIUS_BROWSER_FULL_PATH_CLIENTS: "4",
    GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS: "127",
    GAIUS_BROWSER_FULL_PATH_SOAK_MS: "15000",
    GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES: "1",
    GAIUS_BROWSER_FULL_PATH_SERVER_JAR: "poison.jar",
};
const savedPollutedGateValues = new Map(Object.keys(pollutedGateValues).map((name) => [
    name, process.env[name],
]));
try {
    Object.assign(process.env, pollutedGateValues);
    const sanitized = configuration("versions/26.2.json");
    assert.equal(sanitized.acceptanceMode, false,
        "configuration inherited strict acceptance pollution");
    assert.equal(sanitized.clients, 2,
        "configuration inherited polluted client count");
    assert.equal(sanitized.performanceContract.minimumChunkPackets, 9,
        "configuration inherited polluted chunk target");
    assert.equal(sanitized.performanceContract.soakMillis, 1000,
        "configuration inherited polluted soak target");
    assert.equal(sanitized.performanceContract.reconnectWaves, 0,
        "configuration inherited polluted reconnect wave count");
}
finally {
    for (const [name, value] of savedPollutedGateValues) {
        if (value === undefined) delete process.env[name];
        else process.env[name] = value;
    }
}

for (const expected of expectedProfiles) {
    const strict = configuration(expected.path, {
        GAIUS_BROWSER_FULL_PATH_ACCEPTANCE: "1",
        GAIUS_BROWSER_FULL_PATH_CLIENTS: "4",
        GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS: "9",
        GAIUS_BROWSER_FULL_PATH_SOAK_MS: "15000",
        GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES: "1",
    }, ["--print-config", "--acceptance"]);
    assert.equal(strict.acceptanceMode, true);
    assert.deepEqual(strict.strictAcceptanceTarget, {
        clients: 4,
        minimumChunkPackets: 9,
        soakMillis: 15000,
        reconnectWaves: 1,
    });
    assert.equal(strict.clients, 4);
    assert.deepEqual(strict.performanceContract.strictAcceptanceTarget,
        strict.strictAcceptanceTarget);
    assert.equal(strict.performanceContract.mode, "strict-acceptance");
}

function expectConfigurationFailure(profilePath, overrides, argumentsList = ["--print-config"]) {
    assert.throws(() => configuration(profilePath, overrides, argumentsList));
}

const strictProfileOverrides = {
    GAIUS_BROWSER_FULL_PATH_ACCEPTANCE: "1",
    GAIUS_BROWSER_FULL_PATH_CLIENTS: "4",
    GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS: "9",
    GAIUS_BROWSER_FULL_PATH_SOAK_MS: "15000",
    GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES: "1",
};
const aliasProfileName = `.contract-profile-alias-${process.pid}-${Date.now()}.json`;
const aliasProfileRelativePath = `versions/${aliasProfileName}`;
const aliasProfileAbsolutePath = path.join(bridgeDirectory, "../../port", aliasProfileRelativePath);
await writeFile(aliasProfileAbsolutePath,
    await readFile(path.join(bridgeDirectory, "../../port/versions/26.2.json")));
try {
    assert.throws(() => configuration(aliasProfileRelativePath, strictProfileOverrides),
        /strict acceptance profile basename|strict acceptance profile path/u,
    "strict acceptance accepted a profile whose basename was not <id>.json");
}
finally {
    await unlink(aliasProfileAbsolutePath).catch(() => {});
}

expectConfigurationFailure("versions/26.2.json", {
    GAIUS_BROWSER_FULL_PATH_ACCEPTANCE: "1",
    GAIUS_BROWSER_FULL_PATH_CLIENTS: "4suffix",
});
expectConfigurationFailure("versions/26.2.json", {
    GAIUS_BROWSER_FULL_PATH_ACCEPTANCE: "1",
    GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS: "09",
});
expectConfigurationFailure("versions/26.2.json", {
    GAIUS_BROWSER_FULL_PATH_ACCEPTANCE: "1",
    GAIUS_BROWSER_FULL_PATH_SOAK_MS: "15000ms",
});
expectConfigurationFailure("versions/26.2.json", {
    GAIUS_BROWSER_FULL_PATH_ACCEPTANCE: "1",
    GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES: "1.0",
});
expectConfigurationFailure("versions/26.2.json", {
    GAIUS_BROWSER_FULL_PATH_ACCEPTANCE: "true",
});
expectConfigurationFailure("versions/26.2.json", {}, ["--print-config", "--acceptance-suffix"]);

// Compatibility mode keeps the historical parseInt/clamp behavior, including
// values with a suffix; only strict acceptance is fail-closed.
assert.equal(configuration("versions/26.2.json", {
    GAIUS_BROWSER_FULL_PATH_CLIENTS: "4suffix",
}).clients, 4);

assert.equal(configuration("versions/26.2.json", {
    GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS: "0",
}).performanceContract.minimumChunkPackets, 1);
assert.equal(configuration("versions/1.21.11.json", {
    GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS: "999",
}).performanceContract.minimumChunkPackets, 128);
assert.equal(configuration("versions/26.2.json", {
    GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES: "1",
}).performanceContract.reconnectWaves, 1);
assert.equal(configuration("versions/26.2.json", {
    GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES: "-1",
}).performanceContract.reconnectWaves, 0);
assert.equal(configuration("versions/1.21.11.json", {
    GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES: "999",
}).performanceContract.reconnectWaves, 8);

const [relayMain, fullPathSource] = await Promise.all([
    readFile(relayMainPath, "utf8"),
    readFile(fullPathScript, "utf8"),
]);
const [acceptanceRunner, packageSource] = await Promise.all([
    readFile(acceptanceRunnerPath, "utf8"),
    readFile(packagePath, "utf8"),
]);
assert.match(fullPathSource,
    /for \(let wave = 1; wave <= reconnectWaves; wave\+\+\)/);
assert.match(fullPathSource,
    /forceAbnormalTransportDrop\(\s*browserRuntime, previousClients, wave\)/);
assert.match(fullPathSource,
    /probe\.entry\.ws\.terminate\(\)/);
assert.match(fullPathSource,
    /entry\.ws\.onmessage\(\{\s*data: tail\.buffer\.slice/);
assert.match(fullPathSource,
    /client\.close\("java-final-close"\)/);
assert.match(fullPathSource,
    /abnormal close discarded channel \$\{probe\.id\} synthetic inbound marker/);
assert.match(fullPathSource,
    /tailOffset \+ probe\.tail\.byteLength, drained\.byteLength/);
assert.match(fullPathSource,
    /syntheticInboundMarker: \{[\s\S]*?networkFrame: false/);
assert.match(fullPathSource,
    /\^WebSocket transport closed: \(\?!1000\\b\)\\d\+/);
assert.match(fullPathSource,
    /id: 700 \+ wave \* 100 \+ index/);
assert.match(fullPathSource,
    /"reconnect reused an AES shared secret"/);
assert.match(fullPathSource,
    /sessionAtChunks\.joins - sessionBeforeDrop\.joins/);
assert.match(fullPathSource,
    /snapshot\.target\.activeConnections === 0/);
assert.match(fullPathSource,
    /strict acceptance mode|strict acceptance/iu);
assert.match(fullPathSource,
    /GAIUS_BROWSER_FULL_PATH_CLIENTS/);
assert.match(fullPathSource,
    /GAIUS_BROWSER_FULL_PATH_SOAK_MS/);
assert.match(fullPathSource,
    /GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES/);
assert.match(fullPathSource,
    /assertSoakLiveness\(/);
assert.match(fullPathSource,
    /onlineEncryptionResult\(\)/);
assert.match(fullPathSource,
    /activeLocalTunnelSessions/);
assert.match(fullPathSource,
    /pendingSyntheticPlayTicks/);
assert.match(fullPathSource,
    /activeServerFrameDrainHandles/);
assert.match(fullPathSource,
    /activeServerFrameDrainTimers/);
assert.match(fullPathSource,
    /activeClientStallTimers/);
assert.match(fullPathSource,
    /BROWSER_RUNTIME_CLEANUP_GAUGES/);
assert.match(fullPathSource,
    /activeHighWatermarks/);
assert.match(fullPathSource,
    /decodedSliceBacklog/);
assert.match(fullPathSource,
    /decoderCumulationBytes/);
assert.match(fullPathSource,
    /decodedPacketQueue/);
assert.match(fullPathSource,
    /browserRuntimeCleanupGaugeEvidence/);
assert.match(fullPathSource,
    /const profileJavaVariable = `GAIUS_JAVA_\$\{profile\.javaVersion\}`/);
assert.match(fullPathSource,
    /runtimeJavaMajorMeetsPolicy\(profile, result\.major\)/);
assert.match(fullPathSource,
    /executable: result\.executable/);
assert.match(fullPathSource,
    /runtimeJavaExecutable/);
assert.match(fullPathSource,
    /runtimeJavaMajor/);
assert.match(fullPathSource,
    /--print-java-resolution/);
assert.match(fullPathSource,
    /major-exactly-21/);
assert.match(fullPathSource,
    /major-at-least-25/);
assert.match(fullPathSource,
    /path\.basename\(profilePath\) !== `\$\{profile\.id\}\.json`/);
assert.match(fullPathSource,
    /canonicalProfilePath: repositoryRelativePath\(activeProfile\.path\)/);
assert.match(fullPathSource,
    /syntheticMarkerLabel: "synthetic-inbound-marker"/);
assert.match(fullPathSource,
    /schemaVersion: "browser-full-path-result-v2"/);
assert.match(fullPathSource,
    /authorization: `Bearer \$\{relayToken\}`/);
assert.match(fullPathSource,
    /client\.dropTimingResult\(dropAt\)/);
assert.match(relayMain,
    /if \(traceTunnel\) \{\s+traceTunnelEvent\(\s+`server data [\s\S]*?toString\("hex"\)/);
assert.match(relayMain,
    /if \(traceTunnel\) \{\s+traceTunnelEvent\(\s+`client data [\s\S]*?toString\("hex"\)/);
assert.match(relayMain,
    /if \(traceTunnel\) \{\s+traceTunnelEvent\(\s+`proxied [\s\S]*?response\.toString\("hex"\)/);
assert.equal(
    (relayMain.match(/if \(traceTunnel && protocolPhase === "play"\)/g) ?? []).length,
    2,
    "RelayNode must not decode PLAY packet ids solely for disabled tracing",
);
assert.match(relayMain, /const armClientStallTimer = \(\) => \{/);
assert.match(relayMain, /const clearClientStallTimer = \(\) => \{/);
assert.match(relayMain, /armClientStallTimer\(\);/);
assert.match(relayMain, /clearClientStallTimer\(\);/);
assert.match(relayMain, /"runtime-telemetry"/);
assert.match(relayMain, /runtime: relayRuntimeSnapshot\(\)/);
assert.doesNotMatch(
    relayMain,
    /updateTcpReadState = \(\) => \{[\s\S]*?\};\s+clientStallTimer = setInterval/,
    "RelayNode must not arm an idle stall timer before a connection reaches PLAY",
);
assert.match(acceptanceRunner,
    /for \(const profile of profiles\)/);
assert.match(acceptanceRunner,
    /await runProfile\(profile\)/);
assert.match(acceptanceRunner,
    /GAIUS_BROWSER_FULL_PATH_CLIENTS: "4"/);
assert.match(acceptanceRunner,
    /GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS: "9"/);
assert.match(acceptanceRunner,
    /GAIUS_BROWSER_FULL_PATH_SOAK_MS: "15000"/);
assert.match(acceptanceRunner,
    /GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES: "1"/);
assert.match(acceptanceRunner,
    /validateChildResult\(profile, report/);
assert.match(acceptanceRunner,
    /performanceContract\?\.canonicalProfiles\?\.\[profile\]/);
assert.match(acceptanceRunner,
    /const actual = results\.map\(\(entry\) => entry\.actual/);
assert.match(acceptanceRunner,
    /const observed = results\.map\(\(entry\) => entry\.observed/);
assert.match(acceptanceRunner,
    /schemaVersion: "browser-full-path-acceptance-v3"/);
assert.match(acceptanceRunner,
    /acceptance\.required exact schema/);
assert.match(acceptanceRunner,
    /runtimeJavaPolicy/);
assert.match(acceptanceRunner,
    /checkRuntimeJava\(/);
assert.match(acceptanceRunner,
    /requiredChildAcceptance/);
assert.match(acceptanceRunner,
    /stableSerialize\(actual\) === stableSerialize\(wanted\)/);
assert.match(acceptanceRunner,
    /queuedBytesAfterClose/);
assert.match(acceptanceRunner,
    /queuedFramesAfterClose/);
assert.match(acceptanceRunner,
    /inboundQueuedBytesAfterClose/);
assert.match(acceptanceRunner,
    /activeRelayTargetLeasesAfterClose/);
assert.match(acceptanceRunner,
    /runs: results\.map/);
assert.match(acceptanceRunner,
    /PROFILE_TIMEOUT_MS = 600000/);
assert.match(acceptanceRunner,
    /detached: process\.platform !== "win32"/);
assert.match(acceptanceRunner,
    /process\.kill\(-pid, signal\)/);
assert.match(acceptanceRunner,
    /process\.kill\(-pid, 0\)/);
assert.match(acceptanceRunner,
    /groupProbe/);
assert.match(acceptanceRunner,
    /taskkill\.exe/);
assert.match(acceptanceRunner,
    /"\/T", "\/F"/);
assert.match(acceptanceRunner,
    /shell: false/);
assert.match(acceptanceRunner,
    /cleanupConfirmed: false/);
assert.match(acceptanceRunner,
    /PRESERVED_GAIUS_ENV_NAMES/);
assert.match(acceptanceRunner,
    /upperName\.startsWith\("GAIUS_"\)/);
assert.match(acceptanceRunner,
    /GAIUS_MAXIMUM_CONNECTIONS/);
assert.match(acceptanceRunner,
    /GAIUS_FRAME_MAX_BYTES/);
assert.match(acceptanceRunner,
    /GAIUS_REGISTRY_URL/);
assert.match(acceptanceRunner,
    /GAIUS_DNS_RETRY_LIMIT/);
assert.match(acceptanceRunner,
    /--contract-env/);
assert.match(acceptanceRunner,
    /process\.exitCode = 1/);
assert.throws(() => execFileSync(process.execPath, [acceptanceRunnerPath], {
    cwd: bridgeDirectory,
    env: {
        ...process.env,
        GAIUS_BROWSER_FULL_PATH_CLIENTS: "4suffix",
    },
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
}), "acceptance runner must reject inherited suffixed gate parameters");
const packageJson = JSON.parse(packageSource);
assert.equal(packageJson.scripts["smoke:browser-full-path-acceptance"],
    "node browser-full-path-acceptance.mjs");
const environmentProbe = JSON.parse(execFileSync(process.execPath, [
    acceptanceRunnerPath, "--contract-env",
], {
    cwd: bridgeDirectory,
    env: {
        ...process.env,
        GAIUS_MAXIMUM_CONNECTIONS: "poison-connections",
        GAIUS_MAXIMUM_FRAME_BYTES: "poison-frame",
        GAIUS_FRAME_MAX_BYTES: "poison-frame-alias",
        GAIUS_RELAY_URL: "ws://poison.invalid/relay",
        GAIUS_RELAY_REGISTRY: "https://poison.invalid/registry",
        GAIUS_REGISTRY_URL: "https://poison.invalid/registry",
        GAIUS_REGISTRY_TOKEN: "poison-token",
        GAIUS_DNS_TEST_MODE: "poison-dns-test",
        GAIUS_DNS_RETRY_LIMIT: "poison-dns",
        GAIUS_DNS_RETRY_BACKOFF_MS: "poison-backoff",
        GAIUS_TRACE_TUNNEL: "poison-trace",
        GAIUS_ALLOW_PRIVATE_TARGETS: "1",
        GAIUS_TARGET_AFFINITY: "poison-target",
        GAIUS_BROWSER_FULL_PATH_SERVER_JAR: "poison-server.jar",
        GAIUS_SMOKE_SERVER_JAR: "poison-smoke-server.jar",
        GAIUS_JAVA: "contract-java",
        GAIUS_JAVA_HOME: "contract-java-home",
        GAIUS_JAVA_21: "contract-java-21",
        GAIUS_JAVA_25: "contract-java-25",
    },
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
}));
assert.deepEqual(environmentProbe.remainingPollution, [],
    "runner leaked inherited GAIUS_* pollution into the child environment");
assert.deepEqual(environmentProbe.removedPollution, [
    "GAIUS_MAXIMUM_CONNECTIONS",
    "GAIUS_MAXIMUM_FRAME_BYTES",
    "GAIUS_FRAME_MAX_BYTES",
    "GAIUS_RELAY_URL",
    "GAIUS_RELAY_REGISTRY",
    "GAIUS_REGISTRY_URL",
    "GAIUS_REGISTRY_TOKEN",
    "GAIUS_DNS_TEST_MODE",
    "GAIUS_DNS_RETRY_LIMIT",
    "GAIUS_DNS_RETRY_BACKOFF_MS",
    "GAIUS_TRACE_TUNNEL",
    "GAIUS_ALLOW_PRIVATE_TARGETS",
    "GAIUS_TARGET_AFFINITY",
    "GAIUS_BROWSER_FULL_PATH_SERVER_JAR",
    "GAIUS_SMOKE_SERVER_JAR",
]);
assert.deepEqual(environmentProbe.preservedJava, [
    "GAIUS_JAVA",
    "GAIUS_JAVA_HOME",
    "GAIUS_JAVA_21",
    "GAIUS_JAVA_25",
]);
assert.deepEqual(environmentProbe.fixed, {
    acceptance: "1",
    clients: "4",
    minChunks: "9",
    soakMillis: "15000",
    reconnectWaves: "1",
    origin: "http://127.0.0.1:8781",
    allowedHosts: "127.0.0.1",
});

// Dynamic resolver contract: profile-specific candidates must win over a
// generic candidate, and the returned major/source must satisfy each strict
// profile policy. This launches only tiny local fake-java fixtures, never a
// server or RelayNode.
const javaFixtureDirectory = await mkdtemp(path.join(tmpdir(),
    "gaius-browser-java-contract-"));
const javaFixtureSuffix = process.platform === "win32" ? ".cmd" : ".sh";
const javaFixture = async (name, major) => {
    const fixturePath = path.join(javaFixtureDirectory, `${name}${javaFixtureSuffix}`);
    const contents = process.platform === "win32"
        ? `@echo off\r\necho openjdk version "${major}.0.0" 1>&2\r\nexit /b 0\r\n`
        : `#!/bin/sh\nprintf 'openjdk version "${major}.0.0"\\n' 1>&2\n`;
    await writeFile(fixturePath, contents, "utf8");
    if (process.platform !== "win32") await chmod(fixturePath, 0o755);
    return fixturePath;
};
try {
    const genericJava = await javaFixture("generic", 26);
    const java21 = await javaFixture("profile-21", 21);
    const java25 = await javaFixture("profile-25", 25);
    const resolutionEnvironment = (profilePath, profileVariable) => ({
        ...sanitizedEnvironment(profilePath, {
            GAIUS_BROWSER_FULL_PATH_ACCEPTANCE: "1",
            GAIUS_BROWSER_FULL_PATH_CLIENTS: "4",
            GAIUS_BROWSER_FULL_PATH_MIN_CHUNKS: "9",
            GAIUS_BROWSER_FULL_PATH_SOAK_MS: "15000",
            GAIUS_BROWSER_FULL_PATH_RECONNECT_WAVES: "1",
            GAIUS_JAVA: genericJava,
            GAIUS_JAVA_21: java21,
            GAIUS_JAVA_25: java25,
        }),
        [profileVariable]: profileVariable === "GAIUS_JAVA_21" ? java21 : java25,
    });
    const resolveProfile = (profilePath, profileVariable) => JSON.parse(
        execFileSync(process.execPath, [fullPathScript, "--print-java-resolution"], {
            cwd: bridgeDirectory,
            env: resolutionEnvironment(profilePath, profileVariable),
            encoding: "utf8",
            stdio: ["ignore", "pipe", "pipe"],
        }));
    const resolved21 = resolveProfile("versions/1.21.11.json", "GAIUS_JAVA_21");
    assert.equal(resolved21.profile.id, "1.21.11");
    assert.equal(resolved21.runtimeJavaMajor, 21);
    assert.equal(resolved21.runtimeJavaSource, "GAIUS_JAVA_21");
    assert.match(resolved21.runtimeJavaExecutable,
        /profile-21\.(?:cmd|sh)$/u);
    assert.equal(resolved21.runtimeJavaPolicy, "major-exactly-21");
    const resolved25 = resolveProfile("versions/26.2.json", "GAIUS_JAVA_25");
    assert.equal(resolved25.profile.id, "26.2");
    assert.equal(resolved25.runtimeJavaMajor, 25);
    assert.equal(resolved25.runtimeJavaSource, "GAIUS_JAVA_25");
    assert.match(resolved25.runtimeJavaExecutable,
        /profile-25\.(?:cmd|sh)$/u);
    assert.equal(resolved25.runtimeJavaPolicy, "major-at-least-25");
}
finally {
    await rm(javaFixtureDirectory, { recursive: true, force: true });
}

console.log(JSON.stringify({
    ok: true,
    profiles: expectedProfiles.map(({ id, protocol, world }) => ({ id, protocol, world })),
    minimumChunkPackets: { default: 9, minimum: 1, maximum: 128 },
    reconnectWaves: { default: 0, minimum: 0, maximum: 8 },
    strictAcceptance: {
        clients: 4,
        minimumChunkPackets: 9,
        soakMillis: 15000,
        reconnectWaves: 1,
        profileOrder: ["26.2", "1.21.11"],
        runtimeJavaPolicy: {
            "1.21.11": "major-exactly-21",
            "26.2": "major-at-least-25",
        },
    },
    reconnectContract: {
        simultaneousDrop: true,
        abnormalTransportDrop: true,
        closeErrorAndSyntheticMarkerRetained: true,
        javaFinalCloseAfterTransportDrop: true,
        freshChannelIds: true,
        sameAccountIdentity: true,
        freshProtocolBuffers: true,
        freshEncryptionState: true,
        sessionJoinPerClientPerWave: true,
        relayAndBrowserCleanupEvidence: true,
    },
    lifecycleCleanupRequired: true,
    traceFormattingGuarded: true,
    stallTimerArmedOnlyInPlay: true,
}));
