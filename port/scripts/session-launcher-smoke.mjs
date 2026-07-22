import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const htmlPath = new URL("../web/dist/index.html", import.meta.url);
const html = fs.readFileSync(htmlPath, "utf8");
const blockStart = html.indexOf("    function createGaiusProxyUrl(target, kind)");
const blockEnd = html.indexOf("\n\n    const gaiusDiagMode", blockStart);
assert.ok(blockStart >= 0 && blockEnd > blockStart, "session launcher block was not found");
const launcher = html.slice(blockStart, blockEnd);

async function runScenario({search = "", stored, injected, fetchImpl}) {
  const storage = new Map();
  if (stored !== undefined) storage.set("gaius.session", JSON.stringify(stored));
  const historyCalls = [];
  const location = {
    href: `http://127.0.0.1:8781/dist/index.html${search}`,
    protocol: "http:",
    hostname: "127.0.0.1",
    search,
  };
  const window = {
    innerWidth: 1280,
    innerHeight: 720,
    __gaiusSession: injected,
  };
  const context = {
    URL,
    URLSearchParams,
    console,
    fetch: fetchImpl ?? (() => {
      throw new Error("unexpected fetch");
    }),
    history: {
      state: null,
      replaceState(...args) {
        historyCalls.push(args);
      },
    },
    location,
    sessionStorage: {
      getItem(key) {
        return storage.get(key) ?? null;
      },
      setItem(key, value) {
        storage.set(key, String(value));
      },
      removeItem(key) {
        storage.delete(key);
      },
    },
    window,
  };
  window.window = window;
  vm.runInNewContext(
    `const urlParams = new URLSearchParams(location.search);\n${launcher}`,
    context,
    {filename: "gaius-session-launcher.js"},
  );
  return {
    args: await window.__gaiusDefaultArgsPromise,
    historyCalls,
    mode: window.__gaiusSessionMode,
    stored: storage.has("gaius.session")
      ? JSON.parse(storage.get("gaius.session"))
      : undefined,
  };
}

const offline = await runScenario({});
assert.equal(offline.mode, "offline");
assert.ok(offline.args.includes("--offlineDeveloperMode"));
assert.equal(offline.args[offline.args.indexOf("--username") + 1], "BrowserPlayer");
assert.equal(
  offline.args[offline.args.indexOf("--uuid") + 1],
  "00000000000040008000000000000001",
);

let profileRequests = 0;
const online = await runScenario({
  search: "?accessToken=secret-token&server=example.org",
  async fetchImpl(url, init) {
    profileRequests++;
    const proxy = new URL(url);
    assert.equal(proxy.pathname, "/proxy/auth");
    assert.equal(
      proxy.searchParams.get("url"),
      "https://api.minecraftservices.com/minecraft/profile",
    );
    assert.equal(init.headers.authorization, "Bearer secret-token");
    return {
      ok: true,
      async json() {
        return {name: "OnlinePlayer", id: "00112233445566778899aabbccddeeff"};
      },
    };
  },
});
assert.equal(profileRequests, 1);
assert.equal(online.mode, "online");
assert.ok(!online.args.includes("--offlineDeveloperMode"));
assert.equal(online.args[online.args.indexOf("--username") + 1], "OnlinePlayer");
assert.equal(
  online.args[online.args.indexOf("--uuid") + 1],
  "00112233445566778899aabbccddeeff",
);
assert.equal(online.args[online.args.indexOf("--accessToken") + 1], "secret-token");
assert.equal(online.args[online.args.indexOf("--quickPlayMultiplayer") + 1], "example.org");
assert.equal(online.stored.username, "OnlinePlayer");
assert.equal(online.stored.uuid, "00112233445566778899aabbccddeeff");
assert.equal(online.historyCalls.length, 1);
assert.ok(!String(online.historyCalls[0][2]).includes("accessToken"));

let unexpectedFetches = 0;
const complete = await runScenario({
  stored: {
    accessToken: "stored-token",
    username: "StoredPlayer",
    uuid: "ffeeddccbbaa99887766554433221100",
  },
  fetchImpl() {
    unexpectedFetches++;
    throw new Error("complete sessions must not request the profile");
  },
});
assert.equal(unexpectedFetches, 0);
assert.equal(complete.mode, "online");
assert.equal(complete.args[complete.args.indexOf("--username") + 1], "StoredPlayer");

await assert.rejects(
  runScenario({
    search: "?accessToken=invalid-profile-token",
    async fetchImpl() {
      return {
        ok: true,
        async json() {
          return {name: "invalid name", id: "not-a-uuid"};
        },
      };
    },
  }),
  /valid Java profile/,
);

console.log(JSON.stringify({
  offline: true,
  profileResolution: true,
  completeSessionBypassesFetch: true,
  invalidProfileRejected: true,
  accessTokenScrubbed: true,
}));
