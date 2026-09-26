import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

const root = new URL("../..", import.meta.url);
const source = async relative => readFile(new URL(relative, root), "utf8");

const listenerPatcher = await source("port/tools/src/main/java/dev/gaius/tools/MinecraftClientPatcher.java");
const server = await source("port/src/main/java/dev/gaius/browser/BrowserIntegratedServerMain.java");
const lan = await source("port/src/main/java/dev/gaius/browser/BrowserLanSession.java");
const launcher = await source("port/web/launcher/index.template.html");
const channel = await source("port/overrides/libraries/netty-transport/src/main/java/io/netty/channel/browser/BrowserWebSocketChannel.java");
const worker = await source("port/web/singleplayer/server-worker-bootstrap.js");
const skin = await source("port/src/main/java/dev/gaius/browser/BrowserSkinProfile.java");
const relayPolicy = await source("apps/bridge/dist/policy.js");
const relay = await source("apps/bridge/dist/main.js");

assert.match(listenerPatcher, /openAdditionalBrowserConnection/);
assert.match(listenerPatcher, /browserInstance/);
assert.match(listenerPatcher, /BrowserWebSocketChannel/);
assert.match(server, /openLanServerConnection/);
assert.match(server, /Class\.forName\(/);
assert.match(server, /@JSExport\s+public static boolean openLanServerConnection/);
assert.match(server, /max-players=8/);
assert.match(lan, /UUID\.randomUUID\(\)/);
assert.match(lan, /publishLanInvite\(brokerSessionId\)/);
assert.match(launcher, /brokerSessionId/);
assert.match(launcher, /client-" \+ brokerSessionId \+ "\.gaius-local:25565/);
assert.match(channel, /continue through the relay/);
assert.match(channel, /localGeneration = sessionId === null \? '' : localWorkerGeneration\(sessionId\)/);
assert.match(worker, /message\.type === "lan-open"/);
assert.match(worker, /openLanServerConnection\(brokerSessionId\)/);
assert.match(relayPolicy, /parseSkinDescriptor/);
assert.match(relayPolicy, /16384/);
assert.match(relay, /session\.client\.skinDescriptor/);
assert.match(relay, /session\.server\.skinDescriptor/);
assert.match(relay, /type: "skin"/);
assert.match(channel, /control\.skinDescriptor = skin/);
assert.match(channel, /acceptRemoteSkinDescriptor\(message\.skinDescriptor\)/);
assert.match(channel, /localSkinDescriptor\(role\)/);
assert.match(channel, /role === 'server'/);
assert.match(skin, /__gaiusRemoteSkinDescriptors/);
assert.match(skin, /remoteKey = profileUuid \+ ':' \+ profileName/);
assert.match(skin, /descriptorValue\(profileUuid, profileName\)/);
assert.match(worker, /__gaiusLanSkinDescriptor/);

console.log("relay-backed LAN broker contract passed");
