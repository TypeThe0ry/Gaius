import assert from "node:assert/strict";
import {mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import {spawn} from "node:child_process";
import {createServer} from "node:net";
import {once} from "node:events";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {setTimeout as delay} from "node:timers/promises";
import {fileURLToPath, pathToFileURL} from "node:url";
import {WebSocket} from "./node_modules/ws/wrapper.mjs";
import {
  decodeClientboundLoginDistances,
  MINECRAFT_1_21_11 as RELAY_MINECRAFT_1_21_11,
  MINECRAFT_26_2 as RELAY_MINECRAFT_26_2,
} from "./dist/protocol.js";
import {
  MINECRAFT_1_21_11,
  MINECRAFT_26_2,
  resolveMinecraftProtocol,
} from "../../packages/protocol/dist/constants.js";
import {createStatusHandshake} from "../../packages/protocol/dist/status.js";
import {encodePacket} from "../../packages/protocol/dist/framing.js";
import {encodeVarInt} from "../../packages/protocol/dist/varint.js";
import {encodeString} from "../../packages/protocol/dist/binary.js";

const directory = fileURLToPath(new URL(".", import.meta.url));
const origin = "http://127.0.0.1:8781";
const token = "profile-smoke-token";
const profiles = [MINECRAFT_1_21_11, MINECRAFT_26_2];

const bridgeSource = await readFile(new URL("./dist/main.js", import.meta.url), "utf8");
assert.match(
  bridgeSource,
  /profile === undefined/,
  "keepalive rewrite must require a resolved Minecraft profile",
);
assert.match(
  bridgeSource,
  /minecraftProfile = undefined/,
  "opaque/encrypted transitions must clear the selected profile",
);
assert.doesNotMatch(
  bridgeSource,
  /profile\s*=\s*MINECRAFT_1_21_11/,
  "RelayNode must not retain a legacy default profile for rewrites",
);
assert.match(
  bridgeSource,
  /maximumMinecraftHandshakeBytes/,
  "RelayNode handshake probing must remain bounded",
);
assert.match(
  bridgeSource,
  /activeLocalTunnelSessions/,
  "RelayNode must expose local tunnel session cleanup telemetry",
);
assert.match(
  bridgeSource,
  /writableNeedDrain/,
  "RelayNode synthetic ticks must honor TCP write backpressure",
);
assert.match(
  bridgeSource,
  /relayNodeRuntimePath\s*=\s*"\/relay-node\/v1\.runtime"/,
  "RelayNode must expose the dedicated runtime telemetry endpoint",
);
assert.match(
  bridgeSource,
  /"keepalive-proxy-v2-profiled"/,
  "RelayNode must advertise the versioned profiled KeepAlive proxy capability",
);
assert.doesNotMatch(
  bridgeSource,
  /proxied.*keepalive.*(?:toString\("hex"\)|head=)/i,
  "KeepAlive proxy diagnostics must not retain the KeepAlive value or raw frame",
);

const keepAliveTelemetryKeys = [
  "enabled",
  "encryptionOpaqueTransitions",
  "lastAt",
  "maxGapMillis",
  "opaqueTransitions",
  "profilesSelected774",
  "profilesSelected776",
  "proxiedKeepAlives",
  "proxiedKeepAlives774Configuration",
  "proxiedKeepAlives774Play",
  "proxiedKeepAlives776Configuration",
  "proxiedKeepAlives776Play",
  "schemaVersion",
  "writeBackpressure",
  "writeErrors",
].sort();

const expected = {
  774: {
    name: "1.21.11",
    worldVersion: 4671,
    play: {
      clientboundKeepAlive: 43,
      clientboundStartConfiguration: 116,
      serverboundKeepAlive: 27,
      serverboundClientTickEnd: 12,
      serverboundConfigurationAcknowledged: 15,
    },
  },
  776: {
    name: "26.2",
    worldVersion: 4903,
    play: {
      clientboundKeepAlive: 44,
      clientboundStartConfiguration: 118,
      serverboundKeepAlive: 28,
      serverboundClientTickEnd: 13,
      serverboundConfigurationAcknowledged: 16,
    },
  },
};
for (const profile of profiles) {
  const contract = expected[profile.protocolVersion];
  assert.ok(contract, `missing expected profile ${profile.protocolVersion}`);
  assert.equal(profile.name, contract.name);
  assert.equal(profile.worldVersion, contract.worldVersion);
  const relayProfile = profile.protocolVersion === 774
    ? RELAY_MINECRAFT_1_21_11
    : RELAY_MINECRAFT_26_2;
  assert.deepEqual(
    Object.fromEntries(Object.keys(contract.play).map((key) => [key, relayProfile.play[key]])),
    contract.play,
  );
  assert.equal(resolveMinecraftProtocol(profile.protocolVersion), profile);
  assert.equal(resolveMinecraftProtocol(profile.name), profile);
  const handshake = createStatusHandshake("profile-smoke.test", 25565, profile);
  const packetLength = decodeVarInt(handshake, 0);
  const packetId = decodeVarInt(handshake, packetLength.bytesRead);
  const protocol = decodeVarInt(
    handshake,
    packetLength.bytesRead + packetId.bytesRead,
  );
  assert.equal(packetId.value, 0);
  assert.equal(protocol.value, profile.protocolVersion);
}
assert.throws(() => resolveMinecraftProtocol(775), /Unsupported Minecraft protocol/);
assert.throws(
  () => resolveMinecraftProtocol({name: "26.2", protocolVersion: 774}),
  /name\/protocol mismatch/,
);

const loginDistanceFixture = Buffer.concat([
  Buffer.from([0x00, 0x00, 0x00, 0x2a]), // player id
  Buffer.from([0x00]), // hardcore
  Buffer.from(encodeVarInt(2)),
  Buffer.from(encodeString("minecraft:overworld")),
  Buffer.from(encodeString("minecraft:the_nether")),
  Buffer.from(encodeVarInt(20)), // max players
  Buffer.from(encodeVarInt(8)), // chunk radius
  Buffer.from(encodeVarInt(4)), // simulation distance
  Buffer.from([0x00, 0x01, 0x00]), // remaining login fields are intentionally opaque
]);
assert.deepEqual(
  decodeClientboundLoginDistances(loginDistanceFixture),
  {chunkRadius: 8, simulationDistance: 4, prefixBytesRead: 50},
  "PLAY login must be the authoritative initial distance contract",
);
assert.throws(
  () => decodeClientboundLoginDistances(loginDistanceFixture.subarray(0, 7)),
  /truncated|invalid/,
  "PLAY login distance parser must fail closed on truncation",
);

const fixture = createServer();
await once(fixture.listen(0, "127.0.0.1"), "listening");
const fixturePort = fixture.address().port;
let fixtureSocket;
let fixtureData = Buffer.alloc(0);
let keepAliveSent = false;
let postKeepAliveData = Buffer.alloc(0);
fixture.on("connection", (socket) => {
  fixtureSocket = socket;
  socket.setNoDelay(true);
  socket.on("data", (chunk) => {
    fixtureData = Buffer.concat([fixtureData, chunk]);
    if (keepAliveSent) {
      postKeepAliveData = Buffer.concat([postKeepAliveData, chunk]);
    }
  });
});

const bridgePort = await reservePort();
const bridge = spawn(process.execPath, ["dist/main.js"], {
  cwd: directory,
  env: {
    ...process.env,
    NODE_ENV: "test",
    GAIUS_BRIDGE_HOST: "127.0.0.1",
    GAIUS_BRIDGE_PORT: String(bridgePort),
    GAIUS_ALLOWED_ORIGINS: origin,
    GAIUS_ALLOWED_HOSTS: "127.0.0.1",
    GAIUS_BRIDGE_TOKEN: token,
    GAIUS_IDLE_TIMEOUT_MS: "60000",
    // Keep this primary profiled-transport case deterministic even when the
    // parent shell has disabled the production KeepAlive proxy.
    GAIUS_PROXY_KEEPALIVES: "1",
  },
  stdio: ["ignore", "pipe", "pipe"],
});
let bridgeOutput = "";
bridge.stdout.setEncoding("utf8");
bridge.stderr.setEncoding("utf8");
bridge.stdout.on("data", (chunk) => { bridgeOutput += chunk; });
bridge.stderr.on("data", (chunk) => { bridgeOutput += chunk; });

let socket;
try {
  await waitFor(() => bridgeOutput.includes("Gaius translator node listening"),
    "RelayNode startup");
  const initialRuntime = await fetchRelayRuntime(bridgePort);
  assert.equal(initialRuntime.runtime.keepAliveProxy.enabled, true);
  assert.equal(initialRuntime.runtime.keepAliveProxy.schemaVersion, 2);
  assert.deepEqual(
    Object.keys(initialRuntime.runtime.keepAliveProxy).sort(),
    keepAliveTelemetryKeys,
    "KeepAlive telemetry must remain a fixed scalar schema",
  );
  assert.ok(initialRuntime.capabilities.includes("keepalive-proxy-v2-profiled"));
  assertKeepAliveTelemetryScalar(initialRuntime.runtime.keepAliveProxy);
  socket = new WebSocket(`ws://127.0.0.1:${bridgePort}/tunnel`, {
    headers: {origin},
  });
  const controls = [];
  const serverFrames = [];
  socket.on("message", (data, binary) => {
    if (binary) {
      serverFrames.push(Buffer.from(data));
    } else {
      controls.push(JSON.parse(data.toString("utf8")));
    }
  });
  await once(socket, "open");
  socket.send(JSON.stringify({
    type: "connect",
    host: "127.0.0.1",
    port: fixturePort,
    token,
  }));
  await waitFor(
    () => controls.some((message) => message.type === "connected"),
    "RelayNode TCP connection",
  );
  await waitFor(() => fixtureSocket !== undefined, "fixture TCP connection");

  const unsupportedHandshake = encodeHandshake(999, fixturePort, 2);
  socket.send(unsupportedHandshake);
  await waitFor(
    () => fixtureData.includes(Buffer.from(unsupportedHandshake)),
    "opaque unsupported handshake forwarding",
  );
  const unknownKeepAlive = Buffer.from("0a00040000000000000007", "hex");
  keepAliveSent = true;
  fixtureSocket.write(unknownKeepAlive);
  await waitFor(
    () => serverFrames.some((frame) => frame.equals(unknownKeepAlive)),
    "opaque unsupported keepalive forwarding",
  );
  await delay(100);
  assert.equal(
    postKeepAliveData.byteLength,
    0,
    "unsupported protocol must not receive a guessed keepalive rewrite",
  );

  // A second, later-looking supported handshake must not reopen rewrite
  // eligibility after the first handshake selected an unsupported profile.
  keepAliveSent = false;
  socket.send(encodeHandshake(774, fixturePort, 2));
  await waitFor(
    () => fixtureData.includes(Buffer.from(encodeHandshake(774, fixturePort, 2))),
    "late supported handshake forwarding",
  );
  postKeepAliveData = Buffer.alloc(0);
  const frameCountBeforeLateKeepAlive = serverFrames.length;
  keepAliveSent = true;
  fixtureSocket.write(unknownKeepAlive);
  await waitFor(
    () => serverFrames.length > frameCountBeforeLateKeepAlive,
    "late opaque keepalive forwarding",
  );
  await delay(100);
  assert.equal(
    postKeepAliveData.byteLength,
    0,
    "unsupported handshake must close profile rewrite eligibility for the tunnel",
  );

  socket.close();
  await once(socket, "close");
  socket = undefined;
  fixtureSocket?.destroy();
  fixtureSocket = undefined;
  await delay(20);
  for (const profile of profiles) {
    await testFragmentedHandshakeProfile(profile, bridgePort, fixturePort);
  }
  await testOversizeOpaqueTunnel(bridgePort, fixturePort);
  for (const profile of profiles) {
    await testRawPreambleLocksOpaque(profile, bridgePort, fixturePort);
  }
  const finalRuntime = await fetchRelayRuntime(bridgePort);
  const keepAlive = finalRuntime.runtime.keepAliveProxy;
  assertKeepAliveTelemetryScalar(keepAlive);
  assert.equal(keepAlive.profilesSelected774, 1);
  assert.equal(keepAlive.profilesSelected776, 1);
  assert.equal(keepAlive.proxiedKeepAlives, 4);
  assert.equal(keepAlive.proxiedKeepAlives774Configuration, 1);
  assert.equal(keepAlive.proxiedKeepAlives774Play, 1);
  assert.equal(keepAlive.proxiedKeepAlives776Configuration, 1);
  assert.equal(keepAlive.proxiedKeepAlives776Play, 1);
  assert.ok(keepAlive.lastAt > 0, "KeepAlive telemetry omitted its last event time");
  assert.ok(keepAlive.maxGapMillis >= 0, "KeepAlive telemetry gap became negative");
  assert.equal(keepAlive.writeBackpressure, 0);
  assert.equal(keepAlive.writeErrors, 0);
  assert.ok(keepAlive.opaqueTransitions >= 4,
    "unsupported/raw/malformed tunnels were not counted as opaque transitions");
  assert.equal(
    keepAlive.encryptionOpaqueTransitions,
    0,
    "PLAY packet-id=0x01 was misclassified as a LOGIN encryption request",
  );
} finally {
  socket?.close();
  fixtureSocket?.destroy();
  fixture.close();
  bridge.kill();
  await once(bridge, "exit").catch(() => {});
}

await testKeepAliveTelemetryMode("disabled");
await testKeepAliveTelemetryMode("write-false");
await testKeepAliveTelemetryMode("write-error");

console.log("Relay profile protocol smoke passed", JSON.stringify({
  profiles: profiles.map((profile) => ({
    name: profile.name,
    protocolVersion: profile.protocolVersion,
    worldVersion: profile.worldVersion,
  })),
  unsupportedProtocolRewrite: "disabled",
  keepAliveTelemetry: {
    schemaVersion: 2,
    profiles: [774, 776],
    faultModes: ["disabled", "write-false", "write-error"],
    storage: "fixed-scalars-only",
  },
}));

function decodeVarInt(bytes, offset = 0) {
  let value = 0;
  for (let index = 0; index < 5; index++) {
    const current = bytes[offset + index];
    if (current === undefined) throw new Error("truncated VarInt");
    value |= (current & 0x7f) << (index * 7);
    if ((current & 0x80) === 0) {
      return {value: value | 0, bytesRead: index + 1};
    }
  }
  throw new Error("invalid VarInt");
}

function encodeHandshake(protocolVersion, port, state) {
  return Buffer.from(encodePacket(0, Buffer.concat([
    Buffer.from(encodeVarInt(protocolVersion)),
    Buffer.from(encodeString("profile-smoke.test")),
    Buffer.from([(port >>> 8) & 0xff, port & 0xff]),
    Buffer.from(encodeVarInt(state)),
  ])));
}

function encodeCompressedPacket(id, payload) {
  const packet = Buffer.concat([
    Buffer.from(encodeVarInt(id)),
    Buffer.from(payload),
  ]);
  const body = Buffer.concat([Buffer.from([0x00]), packet]);
  return Buffer.concat([Buffer.from(encodeVarInt(body.byteLength)), body]);
}

async function testFragmentedHandshakeProfile(profile, bridgePort, fixturePort) {
  const relayProfile = profile.protocolVersion === 774
    ? RELAY_MINECRAFT_1_21_11
    : RELAY_MINECRAFT_26_2;
  fixtureData = Buffer.alloc(0);
  fixtureSocket = undefined;
  const socket = new WebSocket(`ws://127.0.0.1:${bridgePort}/tunnel`, {
    headers: {origin},
  });
  const controls = [];
  const serverFrames = [];
  socket.on("message", (data, binary) => {
    if (binary) {
      serverFrames.push(Buffer.from(data));
    } else {
      controls.push(JSON.parse(data.toString("utf8")));
    }
  });
  await once(socket, "open");
  socket.send(JSON.stringify({
    type: "connect",
    host: "127.0.0.1",
    port: fixturePort,
    token,
  }));
  await waitFor(
    () => controls.some((message) => message.type === "connected"),
    `RelayNode ${profile.protocolVersion} fragmented tunnel connection`,
  );
  await waitFor(
    () => fixtureSocket !== undefined,
    `RelayNode ${profile.protocolVersion} fragmented fixture connection`,
  );

  const handshake = encodeHandshake(profile.protocolVersion, fixturePort, 2);
  const loginAcknowledged = Buffer.from(encodePacket(3, Buffer.alloc(0), 256));
  const configurationFinished = Buffer.from(encodePacket(3, Buffer.alloc(0), 256));
  const expectedHandshakeForwarded = Buffer.concat([
    handshake,
    loginAcknowledged,
  ]);
  // Cut through the handshake frame and place later login/configuration frames
  // in the final WebSocket message. This catches both cross-message accumulation
  // and the handshake+remainder double-forward regression.
  socket.send(expectedHandshakeForwarded.subarray(0, 1));
  socket.send(expectedHandshakeForwarded.subarray(1, handshake.byteLength - 1));
  socket.send(expectedHandshakeForwarded.subarray(handshake.byteLength - 1));
  await waitFor(
    () => fixtureData.byteLength >= expectedHandshakeForwarded.byteLength,
    `RelayNode ${profile.protocolVersion} fragmented handshake forwarding`,
  );
  assert.deepEqual(
    fixtureData.subarray(0, expectedHandshakeForwarded.byteLength),
    expectedHandshakeForwarded,
    `RelayNode ${profile.protocolVersion} forwarded handshake bytes exactly once`,
  );

  const configurationKeepAlive = encodeCompressedPacket(
    relayProfile.configuration.clientboundKeepAlive,
    Buffer.from("0000000000000001", "hex"),
  );
  const beforeConfigurationKeepAlive = fixtureData.byteLength;
  fixtureSocket.write(configurationKeepAlive);
  const configurationResponsePrefix = Buffer.from([
    0x0a,
    0x00,
    relayProfile.configuration.serverboundKeepAlive,
  ]);
  await waitFor(
    () => fixtureData.indexOf(configurationResponsePrefix, beforeConfigurationKeepAlive) >= 0,
    `RelayNode ${profile.protocolVersion} CONFIGURATION keepalive response`,
  );

  const beforeConfigurationFinished = fixtureData.byteLength;
  socket.send(configurationFinished);
  await waitFor(
    () => fixtureData.indexOf(configurationFinished, beforeConfigurationFinished) >= 0,
    `RelayNode ${profile.protocolVersion} configuration finish forwarding`,
  );

  const clientboundKeepAlive = encodeCompressedPacket(
    relayProfile.play.clientboundKeepAlive,
    Buffer.from("0000000000000002", "hex"),
  );
  const beforeKeepAlive = fixtureData.byteLength;
  fixtureSocket.write(clientboundKeepAlive);
  const responsePrefix = Buffer.from([0x0a, 0x00, relayProfile.play.serverboundKeepAlive]);
  await waitFor(
    () => fixtureData.indexOf(responsePrefix, beforeKeepAlive) >= 0,
    `RelayNode ${profile.protocolVersion} framed keepalive response`,
  );
  const responseOffset = fixtureData.indexOf(responsePrefix, beforeKeepAlive);
  const response = fixtureData.subarray(responseOffset, responseOffset + 11);
  assert.equal(response[0], 0x0a);
  assert.equal(response[1], 0x00);
  assert.equal(
    response[2],
    relayProfile.play.serverboundKeepAlive,
    `RelayNode ${profile.protocolVersion} selected the matching PLAY packet table`,
  );

  // A packet id of 0x01 is legal in PLAY and must not be mistaken for the
  // LOGIN encryption request.  The dedicated encryption-request smoke covers
  // the positive LOGIN transition with a complete fragmented frame.
  const playPacketIdOne = Buffer.from(encodePacket(1, Buffer.from([0x42])));
  fixtureSocket.write(playPacketIdOne);
  await waitFor(
    () => serverFrames.some((frame) => frame.equals(playPacketIdOne)),
    `RelayNode ${profile.protocolVersion} PLAY packet-id=0x01 forwarding`,
  );
  const opaqueResponse = Buffer.from("opaque-encrypted-response", "utf8");
  const beforeOpaque = fixtureData.byteLength;
  socket.send(opaqueResponse);
  await waitFor(
    () => fixtureData.indexOf(opaqueResponse, beforeOpaque) >= 0,
    `RelayNode ${profile.protocolVersion} opaque encryption response forwarding`,
  );
  const opaqueOffset = fixtureData.indexOf(opaqueResponse, beforeOpaque);
  assert.deepEqual(
    fixtureData.subarray(opaqueOffset, opaqueOffset + opaqueResponse.byteLength),
    opaqueResponse,
    `RelayNode ${profile.protocolVersion} forwarded opaque encryption bytes once`,
  );
  socket.close();
  await once(socket, "close");
  fixtureSocket?.destroy();
  fixtureSocket = undefined;
}

async function testOversizeOpaqueTunnel(bridgePort, fixturePort) {
  fixtureData = Buffer.alloc(0);
  fixtureSocket = undefined;
  const socket = new WebSocket(`ws://127.0.0.1:${bridgePort}/tunnel`, {
    headers: {origin},
  });
  const controls = [];
  const serverFrames = [];
  socket.on("message", (data, binary) => {
    if (binary) serverFrames.push(Buffer.from(data));
    else controls.push(JSON.parse(data.toString("utf8")));
  });
  await once(socket, "open");
  socket.send(JSON.stringify({
    type: "connect",
    host: "127.0.0.1",
    port: fixturePort,
    token,
  }));
  await waitFor(() => controls.some((message) => message.type === "connected"),
    "RelayNode oversize opaque tunnel connection");
  await waitFor(() => fixtureSocket !== undefined, "RelayNode oversize opaque fixture connection");

  // The declared frame is larger than the bounded handshake probe while still
  // looking like a Minecraft packet (packet id 0), so the relay must fail closed
  // and forward the bytes once without ever selecting a guessed profile.
  const opaque = Buffer.concat([
    Buffer.from([0x82, 0x20, 0x00]),
    Buffer.alloc(5 * 1024, 0x44),
  ]);
  socket.send(opaque);
  await waitFor(() => fixtureData.byteLength >= opaque.byteLength,
    "RelayNode oversize opaque forwarding");
  assert.deepEqual(
    fixtureData.subarray(0, opaque.byteLength),
    opaque,
    "RelayNode oversize opaque bytes were forwarded exactly once",
  );

  const laterHandshake = encodeHandshake(774, fixturePort, 2);
  socket.send(laterHandshake);
  await waitFor(() => fixtureData.indexOf(laterHandshake, opaque.byteLength) >= 0,
    "RelayNode late handshake opaque forwarding");
  const unknownKeepAlive = Buffer.from("0a00040000000000000007", "hex");
  fixtureSocket.write(unknownKeepAlive);
  await waitFor(() => serverFrames.some((frame) => frame.equals(unknownKeepAlive)),
    "RelayNode opaque keepalive forwarding after oversize probe");
  socket.close();
  await once(socket, "close");
  fixtureSocket?.destroy();
  fixtureSocket = undefined;
}

async function testRawPreambleLocksOpaque(profile, bridgePort, fixturePort) {
  const relayProfile = profile.protocolVersion === 774
    ? RELAY_MINECRAFT_1_21_11
    : RELAY_MINECRAFT_26_2;
  fixtureData = Buffer.alloc(0);
  fixtureSocket = undefined;
  const socket = new WebSocket(`ws://127.0.0.1:${bridgePort}/tunnel`, {
    headers: {origin},
  });
  const controls = [];
  const serverFrames = [];
  socket.on("message", (data, binary) => {
    if (binary) serverFrames.push(Buffer.from(data));
    else controls.push(JSON.parse(data.toString("utf8")));
  });
  await once(socket, "open");
  socket.send(JSON.stringify({
    type: "connect",
    host: "127.0.0.1",
    port: fixturePort,
    token,
  }));
  await waitFor(
    () => controls.some((message) => message.type === "connected"),
    `RelayNode ${profile.protocolVersion} raw preamble connection`,
  );
  await waitFor(
    () => fixtureSocket !== undefined,
    `RelayNode ${profile.protocolVersion} raw preamble fixture connection`,
  );

  // Packet id 0x7f makes this an immediately opaque preamble. The later
  // profile handshake must remain bytes, not reopen handshake probing.
  const rawPreamble = Buffer.from([0x01, 0x7f, 0x52, 0x41, 0x57]);
  const laterHandshake = encodeHandshake(profile.protocolVersion, fixturePort, 2);
  const loginAcknowledged = Buffer.from(encodePacket(3, Buffer.alloc(0), 256));
  const configurationFinished = Buffer.from(encodePacket(3, Buffer.alloc(0), 256));
  const expectedForwarded = Buffer.concat([
    rawPreamble,
    laterHandshake,
    loginAcknowledged,
    configurationFinished,
  ]);
  socket.send(rawPreamble);
  socket.send(Buffer.concat([laterHandshake, loginAcknowledged, configurationFinished]));
  await waitFor(
    () => fixtureData.byteLength >= expectedForwarded.byteLength,
    `RelayNode ${profile.protocolVersion} raw preamble forwarding`,
  );
  assert.deepEqual(
    fixtureData.subarray(0, expectedForwarded.byteLength),
    expectedForwarded,
    `RelayNode ${profile.protocolVersion} raw preamble and late handshake forwarded once`,
  );
  await delay(150);
  assert.equal(
    fixtureData.byteLength,
    expectedForwarded.byteLength,
    `RelayNode ${profile.protocolVersion} did not synthesize a tick after raw preamble`,
  );

  const keepAlive = encodeCompressedPacket(
    relayProfile.play.clientboundKeepAlive,
    Buffer.from("0000000000000002", "hex"),
  );
  keepAliveSent = true;
  postKeepAliveData = Buffer.alloc(0);
  const frameCountBeforeKeepAlive = serverFrames.length;
  fixtureSocket.write(keepAlive);
  await waitFor(
    () => serverFrames.length > frameCountBeforeKeepAlive &&
      serverFrames.some((frame) => frame.equals(keepAlive)),
    `RelayNode ${profile.protocolVersion} raw keepalive forwarding`,
  );
  await delay(150);
  assert.equal(
    postKeepAliveData.byteLength,
    0,
    `RelayNode ${profile.protocolVersion} raw tunnel rewrote or synthesized after keepalive`,
  );
  keepAliveSent = false;
  socket.close();
  await once(socket, "close");
  fixtureSocket?.destroy();
  fixtureSocket = undefined;
  await delay(20);
}

async function fetchRelayRuntime(port) {
  const response = await fetch(`http://127.0.0.1:${port}/relay-node/v1.runtime`, {
    headers: {origin},
  });
  assert.equal(response.status, 200, "RelayNode runtime endpoint was unavailable");
  const body = await response.json();
  assert.equal(body.ok, true);
  assert.equal(body.kind, "gaius-relay-node");
  assert.ok(body.capabilities?.includes("runtime-telemetry"));
  assert.ok(body.runtime?.keepAliveProxy,
    "RelayNode runtime endpoint omitted KeepAlive proxy telemetry");
  assertKeepAliveTelemetryAliases(body.runtime);
  return body;
}

function assertKeepAliveTelemetryScalar(telemetry) {
  assert.deepEqual(
    Object.keys(telemetry).sort(),
    keepAliveTelemetryKeys,
    "KeepAlive telemetry schema gained an unbounded or unknown field",
  );
  for (const [name, value] of Object.entries(telemetry)) {
    if (name === "enabled") {
      assert.equal(typeof value, "boolean", `${name} must be boolean`);
      continue;
    }
    assert.ok(Number.isSafeInteger(value) && value >= 0,
      `${name} must be a bounded non-negative scalar integer`);
  }
}

function assertKeepAliveTelemetryAliases(runtime) {
  const telemetry = runtime.keepAliveProxy;
  assert.deepEqual({
    keepAliveProxyEnabled: runtime.keepAliveProxyEnabled,
    profilesSelected774: runtime.profilesSelected774,
    profilesSelected776: runtime.profilesSelected776,
    proxiedKeepAlives: runtime.proxiedKeepAlives,
    proxiedKeepAlives774Configuration: runtime.proxiedKeepAlives774Configuration,
    proxiedKeepAlives774Play: runtime.proxiedKeepAlives774Play,
    proxiedKeepAlives776Configuration: runtime.proxiedKeepAlives776Configuration,
    proxiedKeepAlives776Play: runtime.proxiedKeepAlives776Play,
    proxiedKeepAliveLastAt: runtime.proxiedKeepAliveLastAt,
    proxiedKeepAliveMaxGapMillis: runtime.proxiedKeepAliveMaxGapMillis,
    keepAliveProxyWriteBackpressure: runtime.keepAliveProxyWriteBackpressure,
    keepAliveProxyWriteErrors: runtime.keepAliveProxyWriteErrors,
    keepAliveProxyOpaqueTransitions: runtime.keepAliveProxyOpaqueTransitions,
    keepAliveProxyEncryptionOpaqueTransitions:
      runtime.keepAliveProxyEncryptionOpaqueTransitions,
  }, {
    keepAliveProxyEnabled: telemetry.enabled,
    profilesSelected774: telemetry.profilesSelected774,
    profilesSelected776: telemetry.profilesSelected776,
    proxiedKeepAlives: telemetry.proxiedKeepAlives,
    proxiedKeepAlives774Configuration: telemetry.proxiedKeepAlives774Configuration,
    proxiedKeepAlives774Play: telemetry.proxiedKeepAlives774Play,
    proxiedKeepAlives776Configuration: telemetry.proxiedKeepAlives776Configuration,
    proxiedKeepAlives776Play: telemetry.proxiedKeepAlives776Play,
    proxiedKeepAliveLastAt: telemetry.lastAt,
    proxiedKeepAliveMaxGapMillis: telemetry.maxGapMillis,
    keepAliveProxyWriteBackpressure: telemetry.writeBackpressure,
    keepAliveProxyWriteErrors: telemetry.writeErrors,
    keepAliveProxyOpaqueTransitions: telemetry.opaqueTransitions,
    keepAliveProxyEncryptionOpaqueTransitions: telemetry.encryptionOpaqueTransitions,
  }, "flat KeepAlive runtime scalars drifted from the fixed telemetry object");
}

async function testKeepAliveTelemetryMode(mode) {
  const testFixture = createServer();
  await once(testFixture.listen(0, "127.0.0.1"), "listening");
  const testFixturePort = testFixture.address().port;
  let testFixtureSocket;
  let testFixtureData = Buffer.alloc(0);
  testFixture.on("connection", (nextSocket) => {
    testFixtureSocket = nextSocket;
    nextSocket.setNoDelay(true);
    nextSocket.on("data", (chunk) => {
      testFixtureData = Buffer.concat([testFixtureData, chunk]);
    });
  });

  const testBridgePort = await reservePort();
  let preloadDirectory;
  const env = {
    ...process.env,
    NODE_ENV: "test",
    GAIUS_BRIDGE_HOST: "127.0.0.1",
    GAIUS_BRIDGE_PORT: String(testBridgePort),
    GAIUS_ALLOWED_ORIGINS: origin,
    GAIUS_ALLOWED_HOSTS: "127.0.0.1",
    GAIUS_BRIDGE_TOKEN: token,
    GAIUS_IDLE_TIMEOUT_MS: "60000",
    GAIUS_PROXY_KEEPALIVES: mode === "disabled" ? "0" : "1",
  };
  if (mode === "write-false" || mode === "write-error") {
    preloadDirectory = await mkdtemp(join(tmpdir(), "gaius-relay-keepalive-smoke-"));
    const preloadPath = join(preloadDirectory, "socket-write-fault.mjs");
    await writeFile(preloadPath, `
import {Socket} from "node:net";
const originalWrite = Socket.prototype.write;
let intercepted = false;
Socket.prototype.write = function (chunk, encoding, callback) {
  const keepAliveWrite = !intercepted && Buffer.isBuffer(chunk) &&
    chunk.byteLength === 11 && chunk[0] === 0x0a && chunk[1] === 0x00;
  if (!keepAliveWrite) return Reflect.apply(originalWrite, this, arguments);
  intercepted = true;
  const completion = typeof encoding === "function" ? encoding : callback;
  if (process.env.GAIUS_KEEPALIVE_WRITE_FAULT === "false") {
    Reflect.apply(originalWrite, this, arguments);
    return false;
  }
  let accepted;
  if (typeof encoding === "function") accepted = originalWrite.call(this, chunk);
  else accepted = originalWrite.call(this, chunk, encoding);
  queueMicrotask(() => completion?.(new Error("injected KeepAlive write error")));
  return accepted;
};
`, "utf8");
    env.GAIUS_KEEPALIVE_WRITE_FAULT = mode === "write-false" ? "false" : "error";
    const importOption = `--import=${pathToFileURL(preloadPath).href}`;
    env.NODE_OPTIONS = process.env.NODE_OPTIONS
      ? `${process.env.NODE_OPTIONS} ${importOption}`
      : importOption;
  }

  const testBridge = spawn(process.execPath, ["dist/main.js"], {
    cwd: directory,
    env,
    stdio: ["ignore", "pipe", "pipe"],
  });
  let output = "";
  testBridge.stdout.setEncoding("utf8");
  testBridge.stderr.setEncoding("utf8");
  testBridge.stdout.on("data", (chunk) => { output += chunk; });
  testBridge.stderr.on("data", (chunk) => { output += chunk; });
  let testSocket;
  try {
    await waitFor(() => output.includes("Gaius translator node listening"),
      `RelayNode ${mode} startup`);
    const initial = await fetchRelayRuntime(testBridgePort);
    const enabled = mode !== "disabled";
    assert.equal(initial.runtime.keepAliveProxy.enabled, enabled);
    assert.equal(
      initial.capabilities.includes("keepalive-proxy-v2-profiled"),
      enabled,
      "KeepAlive capability must reflect the actual switch",
    );
    assert.equal(initial.capabilities.includes("keepalive-proxy"), enabled);

    testSocket = new WebSocket(`ws://127.0.0.1:${testBridgePort}/tunnel`, {
      headers: {origin},
    });
    const controls = [];
    const serverFrames = [];
    testSocket.on("message", (data, binary) => {
      if (binary) serverFrames.push(Buffer.from(data));
      else controls.push(JSON.parse(data.toString("utf8")));
    });
    await once(testSocket, "open");
    testSocket.send(JSON.stringify({
      type: "connect",
      host: "127.0.0.1",
      port: testFixturePort,
      token,
    }));
    await waitFor(() => controls.some((message) => message.type === "connected"),
      `RelayNode ${mode} TCP connection`);
    await waitFor(() => testFixtureSocket !== undefined,
      `RelayNode ${mode} fixture connection`);

    const prefix = Buffer.concat([
      encodeHandshake(774, testFixturePort, 2),
      Buffer.from(encodePacket(3, Buffer.alloc(0), 256)),
    ]);
    testSocket.send(prefix);
    await waitFor(() => testFixtureData.byteLength >= prefix.byteLength,
      `RelayNode ${mode} profile preamble`);
    const beforeKeepAlive = testFixtureData.byteLength;
    const keepAlive = encodeCompressedPacket(
      RELAY_MINECRAFT_1_21_11.configuration.clientboundKeepAlive,
      Buffer.from("0000000000000003", "hex"),
    );
    testFixtureSocket.write(keepAlive);
    if (enabled) {
      const responsePrefix = Buffer.from([
        0x0a,
        0x00,
        RELAY_MINECRAFT_1_21_11.configuration.serverboundKeepAlive,
      ]);
      await waitFor(() => testFixtureData.indexOf(responsePrefix, beforeKeepAlive) >= 0,
        `RelayNode ${mode} proxied KeepAlive`);
    } else {
      await waitFor(() => serverFrames.some((frame) => frame.equals(keepAlive)),
        "disabled RelayNode opaque KeepAlive forwarding");
      await delay(50);
      assert.equal(testFixtureData.byteLength, beforeKeepAlive,
        "disabled KeepAlive proxy wrote an acknowledgement");
    }

    if (mode === "write-error") {
      await waitFor(async () => {
        const current = await fetchRelayRuntime(testBridgePort);
        return current.runtime.keepAliveProxy.writeErrors === 1;
      }, "RelayNode KeepAlive write error telemetry");
    }
    const runtime = (await fetchRelayRuntime(testBridgePort)).runtime.keepAliveProxy;
    assertKeepAliveTelemetryScalar(runtime);
    assert.equal(runtime.profilesSelected774, enabled ? 1 : 0);
    assert.equal(runtime.profilesSelected776, 0);
    assert.equal(runtime.proxiedKeepAlives, enabled ? 1 : 0);
    assert.equal(runtime.proxiedKeepAlives774Configuration, enabled ? 1 : 0);
    assert.equal(runtime.proxiedKeepAlives774Play, 0);
    assert.equal(runtime.proxiedKeepAlives776Configuration, 0);
    assert.equal(runtime.proxiedKeepAlives776Play, 0);
    assert.equal(runtime.writeBackpressure, mode === "write-false" ? 1 : 0);
    assert.equal(runtime.writeErrors, mode === "write-error" ? 1 : 0);
    assert.equal(runtime.opaqueTransitions, 0);
    assert.equal(runtime.encryptionOpaqueTransitions, 0);
  } finally {
    testSocket?.close();
    if (testSocket?.readyState === WebSocket.CLOSING) {
      await once(testSocket, "close").catch(() => {});
    }
    testFixtureSocket?.destroy();
    await new Promise((resolveClose) => testFixture.close(resolveClose));
    if (testBridge.exitCode === null) {
      testBridge.kill();
      await once(testBridge, "exit").catch(() => {});
    }
    if (preloadDirectory !== undefined) {
      await rm(preloadDirectory, {recursive: true, force: true});
    }
  }
}

async function reservePort() {
  const server = createServer();
  await once(server.listen(0, "127.0.0.1"), "listening");
  const port = server.address().port;
  await once(server.close(), "close");
  return port;
}

async function waitFor(predicate, label, timeoutMs = 5000) {
  const deadline = Date.now() + timeoutMs;
  while (!(await predicate())) {
    if (Date.now() >= deadline) {
      throw new Error(`${label} timed out`);
    }
    await delay(10);
  }
}
