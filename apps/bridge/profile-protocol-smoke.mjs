import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {spawn} from "node:child_process";
import {createServer} from "node:net";
import {once} from "node:events";
import {setTimeout as delay} from "node:timers/promises";
import {fileURLToPath} from "node:url";
import {WebSocket} from "./node_modules/ws/wrapper.mjs";
import {
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
} finally {
  socket?.close();
  fixtureSocket?.destroy();
  fixture.close();
  bridge.kill();
  await once(bridge, "exit").catch(() => {});
}

console.log("Relay profile protocol smoke passed", JSON.stringify({
  profiles: profiles.map((profile) => ({
    name: profile.name,
    protocolVersion: profile.protocolVersion,
    worldVersion: profile.worldVersion,
  })),
  unsupportedProtocolRewrite: "disabled",
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
  const expectedForwarded = Buffer.concat([
    handshake,
    loginAcknowledged,
    configurationFinished,
  ]);
  // Cut through the handshake frame and place later login/configuration frames
  // in the final WebSocket message. This catches both cross-message accumulation
  // and the handshake+remainder double-forward regression.
  socket.send(expectedForwarded.subarray(0, 1));
  socket.send(expectedForwarded.subarray(1, handshake.byteLength - 1));
  socket.send(expectedForwarded.subarray(handshake.byteLength - 1));
  await waitFor(
    () => fixtureData.byteLength >= expectedForwarded.byteLength,
    `RelayNode ${profile.protocolVersion} fragmented handshake forwarding`,
  );
  assert.deepEqual(
    fixtureData.subarray(0, expectedForwarded.byteLength),
    expectedForwarded,
    `RelayNode ${profile.protocolVersion} forwarded handshake bytes exactly once`,
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

  // Login encryption transitions must clear framing without dropping or
  // duplicating the opaque response bytes.
  const encryptionRequest = Buffer.from(encodePacket(1, Buffer.alloc(0)));
  fixtureSocket.write(encryptionRequest);
  await waitFor(
    () => serverFrames.some((frame) => frame.equals(encryptionRequest)),
    `RelayNode ${profile.protocolVersion} encryption request forwarding`,
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

async function reservePort() {
  const server = createServer();
  await once(server.listen(0, "127.0.0.1"), "listening");
  const port = server.address().port;
  await once(server.close(), "close");
  return port;
}

async function waitFor(predicate, label, timeoutMs = 5000) {
  const deadline = Date.now() + timeoutMs;
  while (!predicate()) {
    if (Date.now() >= deadline) {
      throw new Error(`${label} timed out`);
    }
    await delay(10);
  }
}
