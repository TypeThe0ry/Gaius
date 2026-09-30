import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

const root = new URL("../..", import.meta.url);
const source = async relative => readFile(new URL(relative, root), "utf8");

const listenerPatcher = await source("port/tools/src/main/java/dev/gaius/tools/MinecraftClientPatcher.java");
const nettyPatcher = await source("port/tools/src/main/java/dev/gaius/tools/NettyBrowserPatcher.java");
const server = await source("port/src/main/java/dev/gaius/browser/BrowserIntegratedServerMain.java");
const lan = await source("port/src/main/java/dev/gaius/browser/BrowserLanSession.java");
const singleplayer = await source("port/src/main/java/dev/gaius/browser/BrowserSingleplayerClient.java");
const launcher = await source("port/web/launcher/index.template.html");
const channel = await source("port/overrides/libraries/netty-transport/src/main/java/io/netty/channel/browser/BrowserWebSocketChannel.java");
const worker = await source("port/web/singleplayer/server-worker-bootstrap.js");
const skin = await source("port/src/main/java/dev/gaius/browser/BrowserSkinProfile.java");
const relayPolicy = await source("apps/bridge/dist/policy.js");
const relay = await source("apps/bridge/dist/main.js");

assert.match(listenerPatcher, /openAdditionalBrowserConnection/);
assert.match(listenerPatcher, /openAdditionalBrowserConnection",\s*"\(Ljava\/lang\/String;\)V/);
assert.match(listenerPatcher, /browserInstance/);
assert.match(listenerPatcher, /BrowserWebSocketChannel/);
// The injected LAN opener intentionally uses the standard Bootstrap.group path;
// gaiusGroup remains an optional Netty patch for other call sites.
assert.match(nettyPatcher, /gaiusGroup/);
assert.match(server, /openLanServerConnection/);
assert.doesNotMatch(server, /Class\.forName\(/);
assert.match(server, /ServerConnectionListener\.openAdditionalBrowserConnection/);
assert.match(server, /ServerConnectionListener\.openAdditionalBrowserConnection\(host\)/);
assert.match(server, /@JSExport\s+public static boolean openLanServerConnection/);
assert.match(server, /BrowserWebSocketChannel\.beginRelayOnlySession\(sessionId\)/);
assert.match(server, /BrowserWebSocketChannel\.endRelayOnlySession\(sessionId\)/);
assert.match(server, /max-players=8/);
assert.match(lan, /UUID\.randomUUID\(\)/);
assert.match(lan, /publishLanInvite\(brokerSessionId\)/);
// The 26.3 copy (port/src/versions/26.3) also passes the running client version as the
// invite's version fallback; the broker contract is otherwise the same.
const lan263 = await source("port/src/versions/26.3/java/dev/gaius/browser/BrowserLanSession.java");
assert.match(lan263, /UUID\.randomUUID\(\)/);
assert.match(lan263, /BrowserSingleplayerClient\.requestLanServerConnection\(brokerSessionId\)/);
assert.match(lan263, /publishLanInvite\(brokerSessionId, clientVersionId\(\)\)/);
assert.match(lan263, /private static native void publishLanInvite\(String brokerSessionId, String clientVersionId\)/);
for (const copy of [lan, lan263]) {
  assert.match(copy, /__gaiusOpenToLan/);
  assert.match(copy, /brokerSessionId/);
}
assert.match(launcher, /brokerSessionId/);
assert.match(launcher, /client-" \+ brokerSessionId \+ "\.gaius-local:25565/);
assert.match(channel, /continue through the relay/);
assert.match(channel, /localGeneration = sessionId === null \? '' : localWorkerGeneration\(sessionId\)/);
assert.match(channel, /__gaiusRelayOnlySessions/);
assert.match(channel, /!relayOnly &&\s+ownsLocalWorkerSession\(sessionId\)/);
assert.match(worker, /message\.type === "lan-open"/);
assert.match(worker, /openLanServerConnection\(brokerSessionId\)/);
assert.match(singleplayer, /hasReadyWorker/);
assert.match(singleplayer, /__gaiusServerReady/);
assert.match(relayPolicy, /parseSkinDescriptor/);
assert.match(relayPolicy, /16384/);
assert.match(relayPolicy, /client\|server\|lan-server/);
assert.match(relay, /session\.client\.skinDescriptor/);
assert.match(relay, /session\.server\.skinDescriptor/);
assert.match(relay, /type: "skin"/);
assert.match(relay, /host: `client-\$\{request\.sessionId\}\.gaius-local`/);
assert.match(relay, /host: session\.server\.host/);
assert.match(channel, /control\.skinDescriptor = skin/);
assert.match(channel, /acceptRemoteSkinDescriptor\(message\.skinDescriptor\)/);
assert.match(channel, /localSkinDescriptor\(role\)/);
assert.match(channel, /role === 'server'/);
assert.match(skin, /__gaiusRemoteSkinDescriptors/);
assert.match(skin, /remoteKey = profileUuid \+ ':' \+ profileName/);
assert.match(skin, /descriptorValue\(profileUuid, profileName\)/);
assert.match(skin, /class MutablePropertyMap extends PropertyMap/);
assert.match(skin, /protected Multimap<String, Property> delegate/);
assert.match(skin, /new GameProfile\(profile\.id\(\), profile\.name\(\), properties\)/);
assert.match(worker, /__gaiusLanSkinDescriptor/);

// Relay candidates must come from bridgeUrls(); an empty list left LAN joiners
// with relayAttempts=0 and stuck on the connect screen.
assert.match(channel, /appendRelayCandidates\(entry, bridgeUrls\(\[\]\)\)/);
assert.match(channel, /appendRelayCandidates\(entry, bridgeUrls\(discovered\)\)/);
// Offline players need distinct UUIDs; a shared placeholder made every joiner
// kick the host ("logged in from another location").
assert.match(launcher, /function offlineGaiusPlayerUuid\(name\)/);
assert.match(launcher, /"OfflinePlayer:" \+ name/);
assert.match(launcher, /uuid === LEGACY_OFFLINE_GAIUS_UUID/);
// Uploaded data:image/png skins must survive authlib's domain check and be
// rendered for remote players, not only for the local player.
const authlibPatcher = await source("port/tools/src/main/java/dev/gaius/tools/AuthlibBrowserPatcher.java");
const uploadedSkin = await source("port/src/main/java/dev/gaius/browser/BrowserUploadedSkin.java");
assert.match(authlibPatcher, /allowUploadedSkinTextures\(sessionNode\)/);
assert.match(authlibPatcher, /"gaiusAllowedTextureUrl"/);
assert.match(listenerPatcher, /patchSkinManagerUploadedSkinSecurity\(args\[0\]/);
assert.match(listenerPatcher, /"skinSignatureState"/);
assert.match(uploadedSkin, /textures\.cape\(\) == null && textures\.elytra\(\) == null/);

console.log("relay-backed LAN broker contract passed");
