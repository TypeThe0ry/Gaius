import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

const root = new URL("../..", import.meta.url);
const source = async relative => readFile(new URL(relative, root), "utf8");

const listenerPatcher = await source("port/tools/src/main/java/dev/gaius/tools/MinecraftClientPatcher.java");
const server = await source("port/src/main/java/dev/gaius/browser/BrowserIntegratedServerMain.java");
const lan = await source("port/src/main/java/dev/gaius/browser/BrowserLanSession.java");
const launcher = await source("port/web/launcher/index.template.html");

assert.match(listenerPatcher, /openAdditionalBrowserConnection/);
assert.match(listenerPatcher, /browserInstance/);
assert.match(listenerPatcher, /BrowserWebSocketChannel/);
assert.match(server, /openLanServerConnection/);
assert.match(server, /Class\.forName\(/);
assert.match(server, /max-players=8/);
assert.match(lan, /UUID\.randomUUID\(\)/);
assert.match(lan, /publishLanInvite\(brokerSessionId\)/);
assert.match(launcher, /brokerSessionId/);
assert.match(launcher, /client-" \+ brokerSessionId \+ "\.gaius-local:25565/);

console.log("relay-backed LAN broker contract passed");
