import { readFile } from "node:fs/promises";
import { parseConnectRequest } from "./dist/policy.js";

const descriptor = {
  uuid: "00000000000040008000000000000001",
  username: "GuestSkin",
  value: Buffer.from(JSON.stringify({textures:{SKIN:{url:"data:image/png;base64,AA=="}}})).toString("base64"),
  signature: "",
};

const parsed = parseConnectRequest(JSON.stringify({
  type: "connect",
  host: "client-0123456789abcdef0123456789abcdef.gaius-local",
  port: 25565,
  skinDescriptor: descriptor,
}));
if (parsed.skinDescriptor?.uuid !== descriptor.uuid ||
    parsed.skinDescriptor?.username !== descriptor.username ||
    parsed.skinDescriptor?.value !== descriptor.value) {
  throw new Error("LAN connect policy did not preserve the bounded skin descriptor");
}

for (const invalid of [
  {...descriptor, uuid: "bad"},
  {...descriptor, username: "x".repeat(17)},
  {...descriptor, value: "x".repeat(16385)},
  {...descriptor, signature: "x".repeat(16385)},
]) {
  let rejected = false;
  try {
    parseConnectRequest(JSON.stringify({
      type: "connect",
      host: "client-0123456789abcdef0123456789abcdef.gaius-local",
      port: 25565,
      skinDescriptor: invalid,
    }));
  } catch {
    rejected = true;
  }
  if (!rejected) throw new Error("Invalid LAN skin descriptor was accepted");
}

const bridge = await readFile(new URL("./dist/main.js", import.meta.url), "utf8");
const channel = await readFile(new URL("../../port/overrides/libraries/netty-transport/src/main/java/io/netty/channel/browser/BrowserWebSocketChannel.java", import.meta.url), "utf8");
const profile = await readFile(new URL("../../port/src/main/java/dev/gaius/browser/BrowserSkinProfile.java", import.meta.url), "utf8");
for (const marker of [
  "skinDescriptor: request.skinDescriptor",
  'type: "skin"',
  "session.client.skinDescriptor",
]) {
  if (!bridge.includes(marker)) throw new Error(`Relay LAN skin forwarding marker missing: ${marker}`);
}
for (const marker of [
  "if (skin) control.skinDescriptor = skin",
  "acceptRemoteSkinDescriptor(message.skinDescriptor)",
  "__gaiusRemoteSkinDescriptors",
]) {
  if (!channel.includes(marker)) throw new Error(`Browser LAN skin marker missing: ${marker}`);
}
if (!profile.includes("__gaiusRemoteSkinDescriptors") ||
    !profile.includes("descriptorValue(profileUuid, profileName)")) {
  throw new Error("Server profile lookup does not consume remote LAN skins");
}

console.log(JSON.stringify({
  ok: true,
  descriptorBytes: descriptor.value.length,
  invalidCases: 4,
  relayForwarding: true,
  serverUuidLookup: true,
}));
