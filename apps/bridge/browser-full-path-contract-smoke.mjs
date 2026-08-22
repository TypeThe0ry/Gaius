import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const bridgeDirectory = fileURLToPath(new URL(".", import.meta.url));
const fullPathScript = fileURLToPath(
    new URL("./browser-full-path-smoke.mjs", import.meta.url));
const relayMainPath = fileURLToPath(new URL("./dist/main.js", import.meta.url));

function configuration(profilePath, overrides = {}) {
    return JSON.parse(execFileSync(process.execPath, [fullPathScript, "--print-config"], {
        cwd: bridgeDirectory,
        env: {
            ...process.env,
            GAIUS_VERSION_PROFILE_PATH: profilePath,
            ...overrides,
        },
        encoding: "utf8",
    }));
}

const expectedProfiles = [
    { path: "versions/26.2.json", id: "26.2", protocol: 776, world: 4903 },
    { path: "versions/1.21.11.json", id: "1.21.11", protocol: 774, world: 4671 },
];

for (const expected of expectedProfiles) {
    const config = configuration(expected.path);
    assert.equal(config.profile.id, expected.id);
    assert.equal(config.profile.protocolVersion, expected.protocol);
    assert.equal(config.profile.worldVersion, expected.world);
    assert.equal(config.wireProfile.name, expected.id);
    assert.equal(config.wireProfile.protocolVersion, expected.protocol);
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

console.log(JSON.stringify({
    ok: true,
    profiles: expectedProfiles.map(({ id, protocol, world }) => ({ id, protocol, world })),
    minimumChunkPackets: { default: 9, minimum: 1, maximum: 128 },
    reconnectWaves: { default: 0, minimum: 0, maximum: 8 },
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
