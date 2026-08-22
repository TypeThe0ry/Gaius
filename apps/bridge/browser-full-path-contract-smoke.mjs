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
    assert.equal(config.performanceContract.lifecycleCleanupRequired, true);
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

const relayMain = await readFile(relayMainPath, "utf8");
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

console.log(JSON.stringify({
    ok: true,
    profiles: expectedProfiles.map(({ id, protocol, world }) => ({ id, protocol, world })),
    minimumChunkPackets: { default: 9, minimum: 1, maximum: 128 },
    lifecycleCleanupRequired: true,
    traceFormattingGuarded: true,
}));
