import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const htmlPath = new URL("../web/dist/index.html", import.meta.url);
const html = fs.readFileSync(htmlPath, "utf8");
const blockStart = html.indexOf("    function createGaiusProxyUrl(target, kind)");
const blockEnd = html.indexOf("\n\n    const gaiusDiagMode", blockStart);
assert.ok(blockStart >= 0 && blockEnd > blockStart, "session launcher block was not found");
const launcher = html.slice(blockStart, blockEnd);

async function runScenario({
  search = "",
  stored,
  injected,
  fetchImpl,
  enteredName,
  rememberedName,
  switchFromTitle = false,
}) {
  const storage = new Map();
  if (stored !== undefined) storage.set("gaius.session", JSON.stringify(stored));
  const remembered = new Map();
  if (rememberedName !== undefined) remembered.set("gaius.playerName", rememberedName);
  const historyCalls = [];
  let submitListener;
  let profileSwitchListener;
  let replacedLocation;
  let runtimeLeaseReleases = 0;
  const profileGate = {hidden: true};
  const profileName = {
    value: "",
    focus() {},
    select() {},
  };
  const profileError = {textContent: ""};
  const profileForm = {
    addEventListener(type, listener) {
      if (type === "submit") submitListener = listener;
    },
    removeEventListener(type, listener) {
      if (type === "submit" && submitListener === listener) submitListener = undefined;
    },
  };
  const profileSwitch = switchFromTitle ? {
    disabled: false,
    hidden: true,
    addEventListener(type, listener) {
      if (type === "click") profileSwitchListener = listener;
    },
  } : null;
  const location = {
    href: `http://127.0.0.1:8781/dist/index.html${search}`,
    protocol: "http:",
    hostname: "127.0.0.1",
    search,
    replace(value) {
      replacedLocation = String(value);
    },
  };
  const window = {
    innerWidth: 1280,
    innerHeight: 720,
    __gaiusSession: injected,
    __gaiusMinecraftState: switchFromTitle ? {screen: "TitleScreen"} : undefined,
    __gaiusReleaseRuntimeLease: switchFromTitle ? () => runtimeLeaseReleases++ : undefined,
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
    localStorage: {
      getItem(key) {
        return remembered.get(key) ?? null;
      },
      setItem(key, value) {
        remembered.set(key, String(value));
      },
    },
    profileGate,
    profileForm,
    profileName,
    profileError,
    profileSwitch,
    bootBrand: {hidden: false},
    bootProgress: {hidden: false},
    bootProgressText: {hidden: false},
    statusBox: {hidden: false},
    showLauncherDetails: false,
    bootProgressValue: 0,
    setBootProgress() {},
    requestAnimationFrame(callback) {
      callback(0);
      return 1;
    },
    setInterval(callback) {
      callback();
      return 1;
    },
    window,
  };
  window.window = window;
  vm.runInNewContext(
    `const urlParams = new URLSearchParams(location.search);\n${launcher}`,
    context,
    {filename: "gaius-session-launcher.js"},
  );
  const prompted = typeof submitListener === "function";
  const promptedInitialName = profileName.value;
  if (prompted && enteredName !== undefined) {
    profileName.value = enteredName;
    submitListener({preventDefault() {}});
  }
  const args = await window.__gaiusDefaultArgsPromise;
  const profileSwitchVisible = profileSwitch ? !profileSwitch.hidden : false;
  if (switchFromTitle) {
    assert.equal(typeof profileSwitchListener, "function");
    profileSwitchListener({preventDefault() {}});
  }
  return {
    args,
    historyCalls,
    mode: window.__gaiusSessionMode,
    prompted,
    promptedInitialName,
    rememberedName: remembered.get("gaius.playerName"),
    stored: storage.has("gaius.session")
      ? JSON.parse(storage.get("gaius.session"))
      : undefined,
    profileSwitchVisible,
    profileSwitchDisabled: profileSwitch?.disabled ?? false,
    replacedLocation,
    runtimeLeaseReleases,
  };
}

const offline = await runScenario({
  enteredName: "GaiusPlayer",
  rememberedName: "PreviousPlayer",
});
assert.equal(offline.mode, "offline");
assert.equal(offline.prompted, true);
assert.equal(offline.promptedInitialName, "PreviousPlayer");
assert.equal(offline.rememberedName, "GaiusPlayer");
assert.ok(offline.args.includes("--offlineDeveloperMode"));
assert.equal(offline.args[offline.args.indexOf("--username") + 1], "GaiusPlayer");
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
assert.equal(online.prompted, false);
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
assert.equal(complete.prompted, false);
assert.equal(complete.args[complete.args.indexOf("--username") + 1], "StoredPlayer");

const switched = await runScenario({
  search: "?username=QueryPlayer&uuid=00112233445566778899aabbccddeeff&server=example.org",
  stored: {
    username: "StoredPlayer",
    uuid: "ffeeddccbbaa99887766554433221100",
  },
  rememberedName: "StoredPlayer",
  switchFromTitle: true,
});
assert.equal(switched.profileSwitchVisible, true);
assert.equal(switched.profileSwitchDisabled, true);
assert.equal(switched.stored, undefined);
assert.equal(switched.rememberedName, "StoredPlayer");
assert.equal(switched.runtimeLeaseReleases, 1);
const switchedUrl = new URL(switched.replacedLocation);
assert.equal(switchedUrl.searchParams.get("server"), "example.org");
for (const key of ["username", "uuid", "accessToken", "xuid", "clientId"]) {
  assert.equal(switchedUrl.searchParams.has(key), false);
}

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
  offlineNameGate: true,
  rememberedNamePrefill: true,
  profileResolution: true,
  completeSessionBypassesFetch: true,
  titleScreenNameSwitch: true,
  identityQueryScrubbedOnSwitch: true,
  invalidProfileRejected: true,
  accessTokenScrubbed: true,
}));
