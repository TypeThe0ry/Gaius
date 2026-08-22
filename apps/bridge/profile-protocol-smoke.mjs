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
