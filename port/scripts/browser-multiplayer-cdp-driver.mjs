#!/usr/bin/env node
/*
 * Job-owned headed Chrome/TeaVM multiplayer diagnostic driver.
 *
 * This is deliberately a diagnostic driver, not a release verifier.  It
 * launches a fresh headed Chrome with a temporary profile and creates one
 * incognito BrowserContext/Target/CDP session per client.  The driver only
 * navigates pages and reads runtime/DOM state through CDP; it never attaches
 * to an operator browser and never dispatches pointer, keyboard, or synthetic
 * input events.
 *
 * A slow or wedged page must not hide the other clients.  Runtime.evaluate is
 * therefore issued concurrently and each command has a bounded timeout.  A
 * timeout, Runtime exception, or page-side diagnostic error is retained in
 * that client's bounded diagnostics and the remaining targets keep running.
 *
 * The output is explicitly marked diagnostic-only.  Even a green run does
 * not prove the exact Java/TeaVM artifact identity, encrypted protocol
 * handshake, public relay reachability, or the release acceptance contract.
 */

import {spawn} from "node:child_process";
import {createServer} from "node:net";
import {mkdtemp, mkdir, rm, writeFile} from "node:fs/promises";
import {tmpdir} from "node:os";
import {dirname, join, resolve} from "node:path";
import process from "node:process";
import WebSocket from "../../apps/bridge/node_modules/ws/wrapper.mjs";

const DRIVER_NAME = "gaius-headed-chrome-multiplayer-cdp-driver";
const SCHEMA_VERSION = 3;
const MAX_CLIENTS = 8;
const MAX_RECONNECT_WAVES = 4;
const MAX_SAMPLES_PER_CLIENT = 512;
const MAX_EVENT_ITEMS = 64;
const MAX_EXCEPTION_ITEMS = 32;
const MAX_DIAGNOSTIC_ITEMS = 64;
const PHASE_METRIC_KEYS = Object.freeze([
  ["eventLoopGapMs", "bridge", "longestEventLoopGapMillis"],
  ["clientFrameDrainMs", "bridge", "clientFrameMaxDrainDurationMillis"],
  ["networkPollMs", "counters", "networkPollMaxMillis"],
  ["clientTickMs", "counters", "clientTickMaxMillis"],
]);

const DEFAULTS = Object.freeze({
  serverHost: "192.168.1.62",
  pagePath: "/index.html",
  clientCount: 4,
  soakMs: 15_000,
  clientStartDelayMs: 0,
  startupTimeoutMs: 180_000,
  reconnectTimeoutMs: 180_000,
  pollMs: 1_000,
  cdpCommandTimeoutMs: 1_500,
  reconnectWaves: 1,
  offlineMode: false,
});

const sleep = (millis) => new Promise((resolveSleep) => setTimeout(resolveSleep, millis));
const now = () => Date.now();
const isoNow = () => new Date().toISOString();

function boundedNumber(value, fallback, minimum, maximum) {
  const number = Number(value);
  if (!Number.isFinite(number)) return fallback;
  return Math.max(minimum, Math.min(maximum, Math.floor(number)));
}

function normalizeOrigin(value) {
  return String(value || "").trim().replace(/\/+$/u, "");
}

function normalizePagePath(value) {
  const path = String(value || DEFAULTS.pagePath).trim() || DEFAULTS.pagePath;
  return path.startsWith("/") ? path : `/${path}`;
}

function defaultChromePath() {
  if (process.platform === "win32") {
    return "C:/Program Files/Google/Chrome/Application/chrome.exe";
  }
  if (process.platform === "darwin") {
    return "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
  }
  return "google-chrome";
}

function parseArgs(argv = process.argv.slice(2), env = process.env) {
  const config = {
    chromePath: env.GAIUS_CHROME_PATH || env.GAIUS_CHROME_BIN || defaultChromePath(),
    origin: normalizeOrigin(env.GAIUS_ORIGIN),
    serverHost: String(env.GAIUS_SERVER_HOST || DEFAULTS.serverHost),
    serverPort: boundedNumber(env.GAIUS_SERVER_PORT, 0, 0, 65_535),
    bridge: normalizeOrigin(env.GAIUS_BRIDGE),
    bridgeToken: String(env.GAIUS_BRIDGE_TOKEN || ""),
    pagePath: normalizePagePath(env.GAIUS_PAGE),
    clientCount: boundedNumber(env.GAIUS_CLIENTS, DEFAULTS.clientCount, 1, MAX_CLIENTS),
    soakMs: boundedNumber(env.GAIUS_SOAK_MS, DEFAULTS.soakMs, 1_000, 600_000),
    clientStartDelayMs: boundedNumber(
      env.GAIUS_CLIENT_START_DELAY_MS,
      DEFAULTS.clientStartDelayMs,
      0,
      60_000,
    ),
    startupTimeoutMs: boundedNumber(
      env.GAIUS_START_TIMEOUT_MS,
      DEFAULTS.startupTimeoutMs,
      30_000,
      900_000,
    ),
    reconnectTimeoutMs: boundedNumber(
      env.GAIUS_RECONNECT_TIMEOUT_MS,
      DEFAULTS.reconnectTimeoutMs,
      30_000,
      900_000,
    ),
    pollMs: boundedNumber(env.GAIUS_POLL_MS, DEFAULTS.pollMs, 250, 10_000),
    cdpCommandTimeoutMs: boundedNumber(
      env.GAIUS_CDP_TIMEOUT_MS,
      DEFAULTS.cdpCommandTimeoutMs,
      250,
      10_000,
    ),
    reconnectWaves: boundedNumber(
      env.GAIUS_RECONNECT_WAVES,
      DEFAULTS.reconnectWaves,
      0,
      MAX_RECONNECT_WAVES,
    ),
    offlineMode: env.GAIUS_OFFLINE === "1" || env.GAIUS_OFFLINE === "true",
    outputPath: String(env.GAIUS_OUTPUT || ""),
    mode: "run",
  };

  const requireValue = (index, option) => {
    if (index + 1 >= argv.length || String(argv[index + 1]).startsWith("--")) {
      throw new Error(`${option} requires a value`);
    }
    return argv[index + 1];
  };

  for (let index = 0; index < argv.length; index++) {
    const argument = String(argv[index]);
    if (argument === "--help" || argument === "-h") {
      config.mode = "help";
      continue;
    }
    if (argument === "--print-config") {
      config.mode = "print-config";
      continue;
    }
    if (argument === "--self-test") {
      config.mode = "self-test";
      continue;
    }
    if (argument === "--offline") {
      config.offlineMode = true;
      continue;
    }
    const [name, inlineValue] = argument.includes("=")
      ? argument.split(/=(.*)/u, 2)
      : [argument, undefined];
    const valueFor = (option) => inlineValue === undefined ? requireValue(index++, option) : inlineValue;
    switch (name) {
      case "--chrome":
      case "--chrome-path":
        config.chromePath = String(valueFor(name));
        break;
      case "--origin":
        config.origin = normalizeOrigin(valueFor(name));
        break;
      case "--server-host":
        config.serverHost = String(valueFor(name));
        break;
      case "--server-port":
        config.serverPort = boundedNumber(valueFor(name), 0, 0, 65_535);
        break;
      case "--bridge":
        config.bridge = normalizeOrigin(valueFor(name));
        break;
      case "--bridge-token":
        config.bridgeToken = String(valueFor(name));
        break;
      case "--page":
        config.pagePath = normalizePagePath(valueFor(name));
        break;
      case "--clients":
        config.clientCount = boundedNumber(valueFor(name), DEFAULTS.clientCount, 1, MAX_CLIENTS);
        break;
      case "--soak-ms":
        config.soakMs = boundedNumber(valueFor(name), DEFAULTS.soakMs, 1_000, 600_000);
        break;
      case "--client-start-delay-ms":
        config.clientStartDelayMs = boundedNumber(
          valueFor(name),
          DEFAULTS.clientStartDelayMs,
          0,
          60_000,
        );
        break;
      case "--start-timeout-ms":
        config.startupTimeoutMs = boundedNumber(valueFor(name), DEFAULTS.startupTimeoutMs, 30_000, 900_000);
        break;
      case "--reconnect-timeout-ms":
        config.reconnectTimeoutMs = boundedNumber(valueFor(name), DEFAULTS.reconnectTimeoutMs, 30_000, 900_000);
        break;
      case "--poll-ms":
        config.pollMs = boundedNumber(valueFor(name), DEFAULTS.pollMs, 250, 10_000);
        break;
      case "--cdp-timeout-ms":
        config.cdpCommandTimeoutMs = boundedNumber(valueFor(name), DEFAULTS.cdpCommandTimeoutMs, 250, 10_000);
        break;
      case "--reconnect-waves":
      case "--reconnect":
        config.reconnectWaves = boundedNumber(valueFor(name), DEFAULTS.reconnectWaves, 0, MAX_RECONNECT_WAVES);
        break;
      case "--output":
        config.outputPath = String(valueFor(name));
        break;
      default:
        if (!argument.startsWith("--")) {
          throw new Error(`unexpected positional argument: ${argument}`);
        }
        throw new Error(`unknown option: ${argument}`);
    }
  }
  return config;
}

function usage() {
  return [
    `Usage: node port/scripts/browser-multiplayer-cdp-driver.mjs [options]`,
    "",
    "Required for a live run (environment or option):",
    "  GAIUS_ORIGIN / --origin, GAIUS_SERVER_PORT / --server-port,",
    "  GAIUS_BRIDGE / --bridge, GAIUS_BRIDGE_TOKEN / --bridge-token",
    "",
    "Options:",
    "  --clients N                 independent headed targets (1..8; default 4)",
    "  --soak-ms N                soak duration per phase (default 15000)",
    "  --client-start-delay-ms N  delay between initial headed target launches (default 0)",
    "  --reconnect-waves N        page reconnect waves (default 1)",
    "  --start-timeout-ms N       initial PLAY deadline",
    "  --reconnect-timeout-ms N   reconnect PLAY deadline",
    "  --poll-ms N                per-client sample interval",
    "  --cdp-timeout-ms N         per-command Runtime/CDP bound",
    "  --offline                  use offlineDeveloperMode identity",
    "  --output FILE              write the complete JSON result",
    "  --print-config             print redacted configuration; never launches Chrome",
    "  --self-test                run pure Node config/URL/redaction checks",
    "  --help                     print this text",
  ].join("\n");
}

function redactText(value) {
  return String(value ?? "")
    .replace(/((?:[?&]|^)(?:bridgeToken|relayToken|accessToken|token)=)[^&#\s]*/giu, "$1<redacted>")
    .replace(/(Bearer\s+)[^\s]+/giu, "$1<redacted>");
}

function redactUrl(raw) {
  try {
    const value = new URL(String(raw));
    for (const key of ["bridgeToken", "relayToken", "accessToken", "token"]) {
      if (value.searchParams.has(key)) value.searchParams.set(key, "<redacted>");
    }
    return value.href;
  } catch {
    return redactText(raw);
  }
}

function configForOutput(config) {
  return {
    origin: config.origin || null,
    server: config.serverPort ? `${config.serverHost}:${config.serverPort}` : null,
    bridge: config.bridge || null,
    pagePath: config.pagePath,
    clientCount: config.clientCount,
    soakMs: config.soakMs,
    clientStartDelayMs: config.clientStartDelayMs,
    startupTimeoutMs: config.startupTimeoutMs,
    reconnectTimeoutMs: config.reconnectTimeoutMs,
    pollMs: config.pollMs,
    cdpCommandTimeoutMs: config.cdpCommandTimeoutMs,
    reconnectWaves: config.reconnectWaves,
    offlineMode: config.offlineMode,
    chromePath: config.chromePath,
    outputPath: config.outputPath || null,
    requiredInputsPresent: {
      origin: !!config.origin,
      serverPort: config.serverPort >= 1 && config.serverPort <= 65_535,
      bridge: !!config.bridge,
      bridgeToken: !!config.bridgeToken,
    },
  };
}

function strictEligibility(config) {
  const candidateShape = {
    headedChrome: true,
    isolatedTemporaryProfile: true,
    perClientTargetAndCdpSession: true,
    noSyntheticInput: true,
    requestedClientCountAtLeast4: config.clientCount >= 4,
    requestedSoakAtLeast15s: config.soakMs >= 15_000,
    requestedReconnectWave: config.reconnectWaves >= 1,
  };
  const missing = [
    "exact Java/TeaVM build identity and generated-artifact hashes are not verified by this driver",
    "protocol LOGIN/CONFIGURATION/PLAY and online encryption are observed only indirectly through page state",
    "public RelayNode/ellan.top reachability and external relay attestation are not part of this run",
    "this output has no release manifest, archive, or independent package-verification evidence",
  ];
  if (config.offlineMode) {
    missing.push("offlineDeveloperMode is enabled; this cannot be encrypted online-mode evidence");
  }
  return {
    status: "not-eligible",
    evidenceRole: "diagnostic-only",
    releaseEligible: false,
    releasePass: false,
    candidateShape,
    missing,
    rule: "A green diagnostic run must not be promoted to strict release evidence.",
  };
}

function createOutput(config) {
  return {
    schemaVersion: SCHEMA_VERSION,
    driver: DRIVER_NAME,
    mode: {
      diagnosticOnly: true,
      evidenceRole: "headed-chrome-diagnostic",
      headed: true,
      isolatedTemporaryProfile: true,
      existingChromeAttached: false,
      browserConnector: false,
      syntheticInput: false,
      inputEventsDispatched: false,
    },
    source: {
      origin: config.origin || null,
      server: config.serverPort ? `${config.serverHost}:${config.serverPort}` : null,
      bridge: config.bridge || null,
      page: config.pagePath,
    },
    configuration: configForOutput(config),
    strictEligibility: strictEligibility(config),
    startedAt: isoNow(),
    clients: [],
    phases: [],
    findings: [],
    violations: [],
    diagnostics: {
      boundedPerClientEvaluation: true,
      cdpCommandTimeouts: 0,
      cdpCommandErrors: 0,
      runtimeEvaluationTimeouts: 0,
      runtimeEvaluationExceptions: 0,
      pageDiagnosticErrors: 0,
      eventItemsDropped: 0,
    },
    cleanup: {
      targetsClosed: 0,
      contextsDisposed: 0,
      targetCloseErrors: [],
      contextDisposeErrors: [],
      browserClosed: false,
      browserExitCode: null,
      temporaryProfileRemoved: false,
      temporaryProfile: null,
    },
    diagnosticStatus: "not-started",
    diagnosticOk: false,
    productPass: false,
    ok: false,
  };
}

class CdpCommandTimeout extends Error {
  constructor(method, timeoutMs, sessionId) {
    super(`CDP command timeout: ${method} after ${timeoutMs} ms`);
    this.name = "CdpCommandTimeout";
    this.code = "CDP_TIMEOUT";
    this.method = method;
    this.timeoutMs = timeoutMs;
    this.sessionId = sessionId || null;
  }
}

class CdpConnection {
  constructor(url) {
    this.url = url;
    this.socket = null;
    this.nextId = 1;
    this.pending = new Map();
    this.listeners = new Map();
    this.closed = false;
  }

  async open() {
    this.socket = new WebSocket(this.url);
    await new Promise((resolveOpen, rejectOpen) => {
      let settled = false;
      const finish = (error) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        if (error) rejectOpen(error);
        else resolveOpen();
      };
      const timer = setTimeout(() => finish(new Error("CDP websocket open timeout")), 10_000);
      this.socket.once("open", () => finish());
      this.socket.once("error", (error) => finish(error));
    });
    this.socket.on("message", (raw) => this.#handleMessage(raw));
    this.socket.on("close", () => {
      this.closed = true;
      for (const pending of this.pending.values()) {
        pending.reject(new Error("Chrome DevTools WebSocket closed"));
      }
      this.pending.clear();
    });
    this.socket.on("error", () => {});
  }

  #handleMessage(raw) {
    let message;
    try {
      message = JSON.parse(String(raw));
    } catch {
      return;
    }
    if (message.id !== undefined) {
      const pending = this.pending.get(message.id);
      if (!pending) return;
      this.pending.delete(message.id);
      if (message.error) {
        pending.reject(new Error(`${pending.method}: ${message.error.message || "CDP error"}`));
      } else {
        pending.resolve(message.result || {});
      }
      return;
    }
    const key = `${message.sessionId || ""}:${message.method || ""}`;
    for (const listener of this.listeners.get(key) || []) {
      try {
        listener(message.params || {});
      } catch {
        // A diagnostics listener is bounded and must not damage the CDP loop.
      }
    }
  }

  on(method, sessionId, listener) {
    const key = `${sessionId || ""}:${method}`;
    const listeners = this.listeners.get(key) || [];
    listeners.push(listener);
    this.listeners.set(key, listeners);
  }

  send(method, params = {}, sessionId = undefined) {
    if (!this.socket || this.closed || this.socket.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error("CDP websocket is not open"));
    }
    const id = this.nextId++;
    const packet = {id, method, params};
    if (sessionId) packet.sessionId = sessionId;
    return new Promise((resolveSend, rejectSend) => {
      this.pending.set(id, {method, resolve: resolveSend, reject: rejectSend});
      try {
        this.socket.send(JSON.stringify(packet), (error) => {
          if (!error) return;
          this.pending.delete(id);
          rejectSend(error);
        });
      } catch (error) {
        this.pending.delete(id);
        rejectSend(error);
      }
    });
  }

  async sendWithTimeout(method, params = {}, sessionId = undefined, timeoutMs = 1_500) {
    if (!this.socket || this.closed || this.socket.readyState !== WebSocket.OPEN) {
      throw new Error("CDP websocket is not open");
    }
    const id = this.nextId++;
    const packet = {id, method, params};
    if (sessionId) packet.sessionId = sessionId;
    let timer;
    const promise = new Promise((resolveSend, rejectSend) => {
      this.pending.set(id, {method, resolve: resolveSend, reject: rejectSend});
      try {
        this.socket.send(JSON.stringify(packet), (error) => {
          if (!error) return;
          this.pending.delete(id);
          rejectSend(error);
        });
      } catch (error) {
        this.pending.delete(id);
        rejectSend(error);
      }
    });
    try {
      return await Promise.race([
        promise,
        new Promise((_, rejectTimeout) => {
          timer = setTimeout(() => {
            if (this.pending.delete(id)) {
              rejectTimeout(new CdpCommandTimeout(method, timeoutMs, sessionId));
            }
          }, timeoutMs);
        }),
      ]);
    } finally {
      clearTimeout(timer);
    }
  }

  async close() {
    for (const pending of this.pending.values()) {
      pending.reject(new Error("CDP connection closed by driver"));
    }
    this.pending.clear();
    this.closed = true;
    if (!this.socket) return;
    try {
      this.socket.close();
    } catch {
      // Already closed.
    }
    this.socket = null;
  }
}

async function freePort() {
  const server = createServer();
  await new Promise((resolveListen, rejectListen) => {
    server.once("error", rejectListen);
    server.listen(0, "127.0.0.1", resolveListen);
  });
  const address = server.address();
  const port = typeof address === "object" && address ? address.port : 0;
  await new Promise((resolveClose) => server.close(resolveClose));
  if (!port) throw new Error("could not allocate a local Chrome debugging port");
  return port;
}

async function fetchJson(url, timeoutMs = 10_000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, {signal: controller.signal});
    if (!response.ok) throw new Error(`${url} HTTP ${response.status}`);
    return await response.json();
  } finally {
    clearTimeout(timer);
  }
}

async function waitForJson(url, timeoutMs = 30_000) {
  const deadline = now() + timeoutMs;
  let lastError = null;
  while (now() < deadline) {
    try {
      return await fetchJson(url, 5_000);
    } catch (error) {
      lastError = error;
      await sleep(100);
    }
  }
  throw new Error(`timed out waiting for ${url}: ${lastError?.message || "no response"}`);
}

async function waitChildExit(child, timeoutMs = 8_000) {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;
  await new Promise((resolveWait) => {
    const timer = setTimeout(resolveWait, timeoutMs);
    child.once("exit", () => {
      clearTimeout(timer);
      resolveWait();
    });
  });
}

async function removeWithRetry(path) {
  for (let attempt = 0; attempt < 10; attempt++) {
    try {
      await rm(path, {recursive: true, force: true});
      return true;
    } catch (error) {
      if (attempt === 9) throw error;
      await sleep(250 * (attempt + 1));
    }
  }
  return false;
}

async function launchChrome(config) {
  const temporaryProfile = await mkdtemp(join(tmpdir(), "gaius-chrome-cdp-driver-"));
  const debuggingPort = await freePort();
  const chrome = spawn(config.chromePath, [
    `--remote-debugging-port=${debuggingPort}`,
    `--user-data-dir=${temporaryProfile}`,
    "--remote-allow-origins=*",
    "--no-first-run",
    "--no-default-browser-check",
    "--disable-popup-blocking",
    "--ignore-gpu-blocklist",
    "--enable-webgl",
    "--enable-unsafe-swiftshader",
    "--use-angle=swiftshader",
    "--disable-background-networking",
    "--disable-background-timer-throttling",
    "--disable-renderer-backgrounding",
    "--disable-backgrounding-occluded-windows",
    "--disable-features=CalculateNativeWinOcclusion",
    "--disable-pointer-lock",
    "--enable-precise-memory-info",
    "--window-size=1280,720",
    "--force-device-scale-factor=1",
    "--new-window",
    "about:blank",
  ], {stdio: "ignore", windowsHide: false});
  try {
    chrome.once("error", () => {});
    const version = await waitForJson(`http://127.0.0.1:${debuggingPort}/json/version`, 30_000);
    if (!version.webSocketDebuggerUrl) throw new Error("Chrome did not expose webSocketDebuggerUrl");
    return {chrome, temporaryProfile, debuggingPort, version};
  } catch (error) {
    try { chrome.kill(); } catch {}
    await waitChildExit(chrome);
    await removeWithRetry(temporaryProfile).catch(() => {});
    throw error;
  }
}

function makeUuid(index) {
  return `000000000000400080000000${String(index + 1).padStart(8, "0")}`;
}

function makeUrl(config, index, wave = 0) {
  const params = new URLSearchParams({
    server: `${config.serverHost}:${config.serverPort}`,
    // Keep the relay endpoint's path exact.  The bridge server accepts
    // `/tunnel`, not `/tunnel/`; appending a slash here makes a real headed
    // browser receive HTTP 404 during the WebSocket upgrade before the
    // product ever creates a channel.  BrowserWebSocketChannel already
    // normalizes a bare host/path to `/tunnel`, so do not rewrite the caller's
    // endpoint here.
    bridge: config.bridge,
    bridgeToken: config.bridgeToken,
    relayRegistry: "0",
    directPlugin: "0",
    allowMultipleTabs: "1",
    username: `GaiusChrome${index + 1}`,
    uuid: makeUuid(index),
    accessToken: config.offlineMode ? "0" : `chrome-cdp-token-${index + 1}`,
    clientId: `chrome-cdp-${index + 1}`,
    reconnectWave: String(wave),
    diag: "network",
  });
  return `${config.origin}${config.pagePath}?${params.toString()}`;
}

const EVALUATE_EXPRESSION = String.raw`(() => {
  try {
    const finite = value => Number.isFinite(Number(value)) ? Number(value) : null;
    const scalar = value => {
      if (value === null || value === undefined) return null;
      if (typeof value === "boolean") return value;
      if (typeof value === "number") return Number.isFinite(value) ? value : null;
      if (typeof value === "string") return value.slice(0, 512);
      return null;
    };
    const pick = (object, keys) => Object.fromEntries(keys.flatMap(key => {
      if (!object || !Object.prototype.hasOwnProperty.call(object, key)) return [];
      const value = scalar(object[key]);
      return value === null ? [] : [[key, value]];
    }));
    const state = window.__gaiusMinecraftState || {};
    const bridge = window.__gaiusNettyBridge || null;
    const stats = bridge && bridge.stats || window.__gaiusNetworkStats || {};
    const counters = window.__gaiusMinecraftCounters || {};
    const fps = window.__gaiusFps || {};
    const gl = window.__gaiusGLStats || {};
    const channelSnapshot = bridge && bridge.channels && typeof bridge.channels.values === "function"
      ? Array.from(bridge.channels.values()).slice(0, 8).map(channel => ({
          id: finite(channel && (channel.id ?? channel.channelId)),
          closed: !!(channel && channel.closed),
          connected: !!(channel && channel.connected),
          queuedFrames: finite(channel && channel.queuedFrames),
          queuedBytes: finite(channel && channel.queuedBytes),
          generation: finite(channel && channel.webSocketGeneration)
        }))
      : [];
    const boot = window.__gaiusBootTimings || {};
    const canvas = document.querySelector("canvas");
    return {
      at: Date.now(),
      monotonicAt: typeof performance !== "undefined" ? performance.now() : null,
      visibilityState: document.visibilityState || null,
      readyState: document.readyState || null,
      title: String(document.title || "").slice(0, 256),
      bodyText: String(document.body && document.body.innerText || "").slice(0, 600),
      bootError: window.__gaiusBootError ? String(window.__gaiusBootError.stack || window.__gaiusBootError).slice(0, 4000) : null,
      bootProgress: scalar(window.__gaiusBootProgress),
      sessionMode: scalar(window.__gaiusSessionMode),
      state: {
        screen: scalar(state.screen),
        level: scalar(state.level),
        running: state.running === undefined ? null : !!state.running,
        pause: state.pause === undefined ? null : !!state.pause,
        loadedChunkCount: finite(state.loadedChunkCount),
        at: finite(state.at)
      },
      identity: pick(window, [
        "__gaiusProfileId", "__gaiusWorldVersion", "__gaiusProtocolVersion",
        "__gaiusStoragePrefix", "__gaiusFsBackend", "__gaiusRuntimeLeaseHeld"
      ]),
      bridge: pick(stats, [
        "openCalls", "closeCalls", "dataCallbacks", "dataBytes", "sendCalls", "sendBytes",
        "inboundPumpCalls", "inboundPumpChunks", "inboundPumpBytes", "inboundPumpMillis",
        "queuedFrames", "queuedBytes", "maxQueuedFrames", "maxQueuedBytes",
        "clientFrameReadyQueueDepth", "clientFrameReadyQueueMaxDepth",
        "clientFrameDrainCompletions", "clientFrameMaxDrainDurationMillis",
        "flowPausedChannels", "decodeFlowPausedChannels", "eventLoopGapSamples",
        "longestEventLoopGapMillis", "errors", "staleCallbacks", "relayAttempts",
        "relayPreflightFailures", "relayTargetActiveSelections"
      ]),
      bridgeShape: bridge ? {
        keys: (() => { try { return Object.keys(bridge).slice(0, 80); } catch (error) { return [String(error).slice(0, 256)]; } })(),
        channelCount: bridge.channels && typeof bridge.channels.size === "number" ? bridge.channels.size : null,
        channelSnapshot,
        relayNodeKeys: stats.relayNodes && typeof stats.relayNodes === "object"
          ? Object.keys(stats.relayNodes).slice(0, 16) : null
      } : null,
      counters: pick(counters, [
        "networkPackets", "networkBytes", "networkPolls", "networkPollMaxMillis",
        "clientTickCount", "clientTickMaxMillis", "worldRenderFrames", "guiRenderFrames"
      ]),
      fps: pick(fps, ["samples", "lowSamples", "average", "min", "p99", "last"]),
      gl: pick(gl, ["drawCalls", "frames", "finishCalls", "readPixelsCalls"]),
      canvas: canvas ? {width: finite(canvas.width), height: finite(canvas.height)} : null,
      bootTimings: pick(boot, [
        "pageStart", "vanillaAssetsRequestStart", "vanillaAssetsDecoded", "vanillaAssetsReady",
        "fsReady", "sessionReady", "runtimeLeaseReady", "classesStart", "beforeClassesPaint",
        "classesLoaded", "mainStart", "mainReturned"
      ]),
      vanillaAssets: window.__gaiusVanillaAssets ? {
        resourceCount: finite(window.__gaiusVanillaAssets.resourceCount),
        byteLength: finite(window.__gaiusVanillaAssets.bytes && window.__gaiusVanillaAssets.bytes.byteLength)
      } : null,
      vanillaReady: {
        type: Object.prototype.toString.call(window.__gaiusVanillaAssetsReady),
        constructor: window.__gaiusVanillaAssetsReady && window.__gaiusVanillaAssetsReady.constructor
          ? String(window.__gaiusVanillaAssetsReady.constructor.name) : null,
        probe: scalar(window.__gaiusVanillaReadyProbe)
      },
      scripts: Array.from(document.scripts).slice(0, 64).map(script => ({
        src: script.src ? String(script.src).slice(0, 512) : null,
        async: !!script.async,
        defer: !!script.defer
      })),
      status: (() => {
        const node = document.querySelector("#status");
        return node ? {
          text: String(node.textContent || "").slice(0, 600),
          state: node.dataset ? scalar(node.dataset.state) : null,
          hidden: !!node.hidden
        } : null;
      })()
    };
  } catch (error) {
    return {at: Date.now(), driverEvaluationError: String(error && (error.stack || error) || "unknown").slice(0, 4000)};
  }
})()`;

function addBounded(list, item, limit) {
  if (list.length >= limit) return false;
  list.push(item);
  return true;
}

function newClient(index) {
  return {
    index,
    clientId: `chrome-cdp-${index + 1}`,
    targetId: null,
    contextId: null,
    sessionId: null,
    url: null,
    phase: "created",
    startedAtMs: now(),
    startedAt: isoNow(),
    setupErrors: [],
    commandErrors: [],
    events: {console: [], exceptions: [], logs: [], websocket: [], page: []},
    diagnostics: {
      evaluationCount: 0,
      evaluationSuccesses: 0,
      evaluationTimeouts: 0,
      evaluationExceptions: 0,
      evaluationErrors: 0,
      commandTimeouts: 0,
      commandErrors: 0,
      pageDiagnosticErrors: 0,
      samplesDropped: 0,
      lastEvaluationAt: null,
      longestEvaluationMs: 0,
      longestPollGapMs: 0,
    },
    samples: [],
    waves: [],
    currentWave: 0,
    firstPlayAt: null,
    firstChunkAt: null,
    failure: null,
    lastSample: null,
  };
}

function newWave(client, wave) {
  const state = {
    wave,
    url: null,
    phase: "created",
    startedAtMs: now(),
    startedAt: isoNow(),
    playAt: null,
    firstChunkAt: null,
    startupCompleted: false,
    startupAllPlay: false,
    soakElapsedMs: null,
    sampleCount: 0,
    diagnosticEvaluationTimeouts: 0,
    playObservedAtMs: null,
    playBaseline: null,
  };
  client.currentWave = wave;
  client.waves.push(state);
  return state;
}

function recordClientCommandError(client, output, method, error, category = "command") {
  const text = redactText(error?.stack || error?.message || error);
  const item = {at: isoNow(), method, category, error: text.slice(0, 2000)};
  addBounded(client.commandErrors, item, MAX_DIAGNOSTIC_ITEMS);
  if (error instanceof CdpCommandTimeout || error?.code === "CDP_TIMEOUT") {
    client.diagnostics.commandTimeouts++;
    output.diagnostics.cdpCommandTimeouts++;
  } else {
    client.diagnostics.commandErrors++;
    output.diagnostics.cdpCommandErrors++;
  }
  return item;
}

function installClientEvents(cdp, client, output) {
  const sessionId = client.sessionId;
  cdp.on("Runtime.consoleAPICalled", sessionId, (params) => {
    const args = (params.args || []).slice(0, 4).map((argument) => redactText(
      argument && (argument.value ?? argument.description ?? argument.unserializableValue ?? ""),
    ).slice(0, 512));
    if (!addBounded(client.events.console, {at: isoNow(), type: params.type || null, args}, MAX_EVENT_ITEMS)) {
      output.diagnostics.eventItemsDropped++;
    }
  });
  cdp.on("Runtime.exceptionThrown", sessionId, (params) => {
    const details = params.exceptionDetails || {};
    if (!addBounded(client.events.exceptions, {
      at: isoNow(),
      text: redactText(details.text || "").slice(0, 1024),
      description: redactText(details.exception?.description || "").slice(0, 2000),
      lineNumber: Number.isFinite(Number(details.lineNumber)) ? Number(details.lineNumber) : null,
      url: details.url ? redactUrl(details.url) : null,
    }, MAX_EXCEPTION_ITEMS)) {
      output.diagnostics.eventItemsDropped++;
    }
  });
  cdp.on("Log.entryAdded", sessionId, (params) => {
    const entry = params.entry || {};
    if (!addBounded(client.events.logs, {
      at: isoNow(),
      level: entry.level || null,
      source: entry.source || null,
      text: redactText(entry.text || "").slice(0, 2000),
      url: entry.url ? redactUrl(entry.url) : null,
    }, MAX_EVENT_ITEMS)) {
      output.diagnostics.eventItemsDropped++;
    }
  });
  const websocketEvent = (name, params) => {
    const response = params.response || {};
    const request = params.request || {};
    const record = {
      at: isoNow(),
      type: name,
      requestId: params.requestId || null,
      url: params.url ? redactUrl(params.url) : null,
      status: Number.isFinite(Number(response.status)) ? Number(response.status) : null,
      protocol: response.protocol || request.protocol || null,
      errorMessage: params.errorMessage ? redactText(params.errorMessage).slice(0, 1024) : null,
    };
    if (!addBounded(client.events.websocket, record, MAX_EVENT_ITEMS)) {
      output.diagnostics.eventItemsDropped++;
    }
  };
  for (const name of [
    "Network.webSocketCreated",
    "Network.webSocketWillSendHandshakeRequest",
    "Network.webSocketHandshakeResponseReceived",
    "Network.webSocketClosed",
    "Network.webSocketFrameError",
  ]) {
    cdp.on(name, sessionId, (params) => websocketEvent(name, params));
  }
  cdp.on("Page.loadEventFired", sessionId, () => {
    addBounded(client.events.page, {at: isoNow(), type: "loadEventFired"}, MAX_EVENT_ITEMS);
  });
  cdp.on("Page.lifecycleEvent", sessionId, (params) => {
    addBounded(client.events.page, {
      at: isoNow(),
      type: "lifecycleEvent",
      name: params.name || null,
      timestamp: Number.isFinite(Number(params.timestamp)) ? Number(params.timestamp) : null,
    }, MAX_EVENT_ITEMS);
  });
}

async function clientCommand(cdp, client, output, method, params = {}, required = false, timeoutMs) {
  try {
    return await cdp.sendWithTimeout(method, params, client.sessionId, timeoutMs || output.configuration.cdpCommandTimeoutMs);
  } catch (error) {
    recordClientCommandError(client, output, method, error);
    if (required) client.setupErrors.push({at: isoNow(), method, error: redactText(error?.message || error).slice(0, 2000)});
    return null;
  }
}

async function createClient(cdp, config, output, index, resources) {
  const client = newClient(index);
  const wave = newWave(client, 0);
  const runtimeUrl = makeUrl(config, index, 0);
  wave.url = redactUrl(runtimeUrl);
  client.url = wave.url;
  output.clients.push(client);
  try {
    const context = await cdp.sendWithTimeout("Target.createBrowserContext", {disposeOnDetach: true}, undefined, config.cdpCommandTimeoutMs);
    client.contextId = context.browserContextId || null;
    if (client.contextId) resources.contexts.push(client.contextId);
    const target = await cdp.sendWithTimeout("Target.createTarget", {
      url: "about:blank",
      browserContextId: client.contextId,
      width: 1280,
      height: 720,
      newWindow: true,
    }, undefined, config.cdpCommandTimeoutMs);
    client.targetId = target.targetId || null;
    if (client.targetId) resources.targets.push(client.targetId);
    const attached = await cdp.sendWithTimeout("Target.attachToTarget", {
      targetId: client.targetId,
      flatten: true,
    }, undefined, config.cdpCommandTimeoutMs);
    client.sessionId = attached.sessionId || null;
    if (!client.sessionId) throw new Error("Target.attachToTarget returned no sessionId");
    installClientEvents(cdp, client, output);
    for (const [method, params] of [
      ["Page.enable", {}],
      ["Runtime.enable", {}],
      ["Log.enable", {}],
      ["Network.enable", {}],
      ["Performance.enable", {}],
      ["Emulation.setDeviceMetricsOverride", {
        width: 1280,
        height: 720,
        deviceScaleFactor: 1,
        mobile: false,
      }],
    ]) {
      await clientCommand(cdp, client, output, method, params, true);
    }
    // Keep the target visible and headed.  These are CDP tab operations, not
    // operating-system mouse/keyboard operations.
    try {
      await cdp.sendWithTimeout("Target.activateTarget", {targetId: client.targetId}, undefined, config.cdpCommandTimeoutMs);
    } catch (error) {
      recordClientCommandError(client, output, "Target.activateTarget", error, "activation");
    }
    await clientCommand(cdp, client, output, "Page.bringToFront", {}, false);
    const navigation = await clientCommand(cdp, client, output, "Page.navigate", {url: runtimeUrl}, true);
    if (!navigation) {
      client.phase = "navigation-failed";
    } else {
      client.phase = "navigating";
      wave.phase = "navigating";
    }
  } catch (error) {
    client.phase = "setup-failed";
    client.setupErrors.push({at: isoNow(), method: "client-setup", error: redactText(error?.stack || error).slice(0, 4000)});
    output.findings.push(`client ${index + 1} setup failed: ${redactText(error?.message || error).slice(0, 1000)}`);
  }
  return client;
}

function classifyEvaluationError(error) {
  if (error instanceof CdpCommandTimeout || error?.code === "CDP_TIMEOUT") return "cdp-timeout";
  return "cdp-error";
}

function updateMilestones(client, sample, output) {
  if (!sample || typeof sample !== "object") return;
  if (sample.bootError) {
    client.failure = redactText(sample.bootError).slice(0, 4000);
    client.diagnostics.pageDiagnosticErrors++;
    output.diagnostics.pageDiagnosticErrors++;
  }
  if (sample.driverEvaluationError) {
    client.diagnostics.pageDiagnosticErrors++;
    output.diagnostics.pageDiagnosticErrors++;
  }
  const state = sample.state || {};
  const playing = typeof state.level === "string" && state.level.length > 0;
  const chunks = Number(state.loadedChunkCount);
  const wave = client.waves[client.waves.length - 1];
  if (!wave) return;
  if (playing && !wave.playAt) {
    wave.playAt = now();
    wave.playObservedAtMs = Number(sample.driver?.capturedAtMs) || wave.playAt;
    // Bridge/counter values are cumulative gauges. Capture their value at the
    // first observed PLAY sample so the final report can separate bootstrap
    // stalls from newly observed post-PLAY stalls.
    wave.playBaseline = metricSnapshot(sample);
    wave.phase = "play";
    if (client.firstPlayAt === null) client.firstPlayAt = wave.playAt;
  }
  if (Number.isFinite(chunks) && chunks > 0 && !wave.firstChunkAt) {
    wave.firstChunkAt = now();
    if (client.firstChunkAt === null) client.firstChunkAt = wave.firstChunkAt;
  }
}

function addSample(client, wave, sample, evaluationStartedAt, evaluationEndedAt, successful = true) {
  const record = sample && typeof sample === "object" ? sample : {value: sample};
  record.driver = {
    at: isoNow(),
    capturedAtMs: evaluationEndedAt,
    wave: wave.wave,
    evaluateElapsedMs: Math.max(0, evaluationEndedAt - evaluationStartedAt),
    diagnosticOnly: true,
  };
  if (client.samples.length >= MAX_SAMPLES_PER_CLIENT) {
    client.diagnostics.samplesDropped++;
  } else {
    client.samples.push(record);
  }
  wave.sampleCount++;
  client.lastSample = record;
  if (successful) client.diagnostics.evaluationSuccesses++;
}

function metricNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.max(0, number) : null;
}

function metricSnapshot(sample) {
  const snapshot = {};
  for (const [name, group, key] of PHASE_METRIC_KEYS) {
    snapshot[name] = metricNumber(sample?.[group]?.[key]);
  }
  return snapshot;
}

function successfulWaveSamples(client, wave) {
  const startAt = Number.isFinite(Number(wave.playAt))
    ? Number(wave.playAt)
    : Number(wave.startedAtMs) || 0;
  return client.samples.filter((sample) => {
    if (sample?.driver?.wave !== wave.wave || sample?.driverError) return false;
    const capturedAt = Number(sample?.driver?.capturedAtMs);
    return Number.isFinite(capturedAt) && capturedAt >= startAt;
  });
}

function summarizePhaseMetrics(client, wave) {
  const samples = successfulWaveSamples(client, wave);
  const baseline = wave.playBaseline || null;
  const metrics = {};
  for (const [name, group, key] of PHASE_METRIC_KEYS) {
    const values = samples
      .map((sample) => metricNumber(sample?.[group]?.[key]))
      .filter((value) => value !== null);
    const rawMax = maxOrNull(values);
    const baselineValue = metricNumber(baseline?.[name]);
    metrics[name] = {
      rawMax,
      // This is a delta from a cumulative runtime gauge, not a replacement for
      // the release gate. Zero means no larger value was observed after PLAY.
      newMaxFromPlay: rawMax === null || baselineValue === null
        ? null
        : Math.max(0, rawMax - baselineValue),
    };
  }
  return {
    sampleCount: samples.length,
    firstCapturedAtMs: samples.length
      ? Number(samples[0].driver.capturedAtMs)
      : null,
    lastCapturedAtMs: samples.length
      ? Number(samples.at(-1).driver.capturedAtMs)
      : null,
    playObservedAtMs: Number.isFinite(Number(wave.playObservedAtMs))
      ? Number(wave.playObservedAtMs)
      : null,
    baseline,
    metrics,
  };
}

async function sampleClient(cdp, client, output) {
  const wave = client.waves[client.waves.length - 1] || newWave(client, client.currentWave);
  const startedAt = now();
  if (client.diagnostics.lastEvaluationAt !== null) {
    client.diagnostics.longestPollGapMs = Math.max(
      client.diagnostics.longestPollGapMs,
      Math.max(0, startedAt - client.diagnostics.lastEvaluationAt),
    );
  }
  client.diagnostics.lastEvaluationAt = startedAt;
  client.diagnostics.evaluationCount++;
  if (!client.sessionId || client.phase === "setup-failed") {
    client.diagnostics.evaluationErrors++;
    return {ok: false, kind: "no-session"};
  }
  try {
    const result = await cdp.sendWithTimeout("Runtime.evaluate", {
      expression: EVALUATE_EXPRESSION,
      awaitPromise: false,
      returnByValue: true,
      userGesture: false,
      includeCommandLineAPI: false,
    }, client.sessionId, output.configuration.cdpCommandTimeoutMs);
    const endedAt = now();
    client.diagnostics.longestEvaluationMs = Math.max(client.diagnostics.longestEvaluationMs, endedAt - startedAt);
    if (result && result.exceptionDetails) {
      const text = redactText(result.exceptionDetails.text || "Runtime.evaluate exception").slice(0, 2000);
      client.diagnostics.evaluationExceptions++;
      output.diagnostics.runtimeEvaluationExceptions++;
      const sample = {driverError: {kind: "runtime-exception", error: text, at: isoNow()}};
      addSample(client, wave, sample, startedAt, endedAt, false);
      return {ok: false, kind: "runtime-exception", sample};
    }
    const value = result?.result?.value;
    if (!value || typeof value !== "object") {
      client.diagnostics.evaluationErrors++;
      const sample = {driverError: {kind: "empty-evaluation", at: isoNow()}};
      addSample(client, wave, sample, startedAt, endedAt, false);
      return {ok: false, kind: "empty-evaluation", sample};
    }
    addSample(client, wave, value, startedAt, endedAt);
    updateMilestones(client, value, output);
    return {ok: true, kind: "sample", sample: value};
  } catch (error) {
    const endedAt = now();
    const kind = classifyEvaluationError(error);
    client.diagnostics.evaluationErrors++;
    if (kind === "cdp-timeout") {
      client.diagnostics.evaluationTimeouts++;
      output.diagnostics.runtimeEvaluationTimeouts++;
      wave.diagnosticEvaluationTimeouts++;
    }
    const sample = {
      driverError: {
        kind,
        error: redactText(error?.message || error).slice(0, 2000),
        at: isoNow(),
      },
    };
    addSample(client, wave, sample, startedAt, endedAt, false);
    if (kind === "cdp-timeout") {
      // Runtime.evaluate timeout is already bounded and is intentionally
      // isolated to this target.  It is not a global abort condition.
      recordClientCommandError(client, output, "Runtime.evaluate", error, "evaluation");
    }
    return {ok: false, kind, error};
  }
}

async function sampleAll(cdp, output) {
  // Promise.allSettled is intentional: one unexpected target-side rejection
  // cannot serialize or stop sampling of the remaining targets.  The normal
  // timeout/exception paths are handled inside sampleClient; this outer layer
  // is the last bounded guard for an unforeseen driver error.
  const results = await Promise.allSettled(
    output.clients.map((client) => sampleClient(cdp, client, output)),
  );
  results.forEach((result, index) => {
    if (result.status !== "rejected") return;
    const client = output.clients[index];
    const wave = client?.waves.at(-1);
    if (!client || !wave) return;
    client.diagnostics.evaluationErrors++;
    const at = now();
    const sample = {
      driverError: {
        kind: "unexpected-sample-rejection",
        error: redactText(result.reason?.message || result.reason).slice(0, 2000),
        at: isoNow(),
      },
    };
    addSample(client, wave, sample, at, at, false);
  });
  return results;
}

function phaseClientSummary(client, wave) {
  return {
    index: client.index,
    clientId: client.clientId,
    wave: wave.wave,
    phase: wave.phase,
    firstPlayAt: wave.playAt,
    firstChunkAt: wave.firstChunkAt,
    sampleCount: wave.sampleCount,
    diagnosticEvaluationTimeouts: wave.diagnosticEvaluationTimeouts,
    failure: client.failure,
  };
}

async function pollUntilPlay(cdp, output, wave, timeoutMs, name) {
  const startedAt = now();
  const deadline = startedAt + timeoutMs;
  let allPlay = false;
  while (now() < deadline) {
    await sampleAll(cdp, output);
    allPlay = output.clients.length > 0 && output.clients.every((client) => {
      const current = client.waves.at(-1);
      // `wave` is the mutable wave record while `current.wave` stores its
      // numeric identifier in the serialized evidence.  Comparing the record
      // object with that number made a successfully playing tab fail the
      // startup gate forever; compare the stable id instead.
      return current && current.wave === wave.wave && !!current.playAt;
    });
    if (allPlay) break;
    await sleep(output.configuration.pollMs);
  }
  wave.startupCompleted = true;
  wave.startupAllPlay = allPlay;
  if (!allPlay) {
    output.findings.push(`${name}: not all browser clients reached PLAY before deadline`);
    output.violations.push(`${name}: not all browser clients reached PLAY before deadline`);
  }
  const phase = {
    name,
    wave: wave.wave,
    at: isoNow(),
    elapsedMs: now() - startedAt,
    allPlay,
    clients: output.clients.map((client) => phaseClientSummary(client, client.waves.at(-1) || wave)),
  };
  output.phases.push(phase);
  return phase;
}

async function runSoak(cdp, output, wave, name) {
  const startedAt = now();
  const deadline = startedAt + output.configuration.soakMs;
  while (now() < deadline) {
    await sampleAll(cdp, output);
    await sleep(output.configuration.pollMs);
  }
  wave.soakElapsedMs = now() - startedAt;
  output.phases.push({
    name,
    wave: wave.wave,
    at: isoNow(),
    elapsedMs: wave.soakElapsedMs,
    allPlay: output.clients.every((client) => !!client.waves.at(-1)?.playAt),
    clients: output.clients.map((client) => phaseClientSummary(client, client.waves.at(-1) || wave)),
  });
}

async function navigateForReconnect(cdp, config, output, client, waveNumber) {
  const wave = newWave(client, waveNumber);
  const runtimeUrl = makeUrl(config, client.index, waveNumber);
  wave.url = redactUrl(runtimeUrl);
  client.url = wave.url;
  client.phase = "reconnecting";
  try {
    await cdp.sendWithTimeout("Target.activateTarget", {targetId: client.targetId}, undefined, config.cdpCommandTimeoutMs);
  } catch (error) {
    recordClientCommandError(client, output, "Target.activateTarget", error, "activation");
  }
  await clientCommand(cdp, client, output, "Page.bringToFront", {}, false);
  const navigation = await clientCommand(cdp, client, output, "Page.navigate", {url: runtimeUrl}, true);
  if (!navigation) {
    wave.phase = "navigation-failed";
    client.phase = "navigation-failed";
  } else {
    wave.phase = "navigating";
    client.phase = "navigating";
  }
}

function numberValues(client, selector) {
  return client.samples.map(selector).filter((value) => Number.isFinite(Number(value))).map(Number);
}

function maxOrNull(values) {
  return values.length ? Math.max(...values) : null;
}

function summarizeClient(client) {
  const loopGaps = numberValues(client, (sample) => sample.bridge?.longestEventLoopGapMillis);
  const drainTimes = numberValues(client, (sample) => sample.bridge?.clientFrameMaxDrainDurationMillis);
  const pollTimes = numberValues(client, (sample) => sample.counters?.networkPollMaxMillis);
  const tickTimes = numberValues(client, (sample) => sample.counters?.clientTickMaxMillis);
  return {
    samples: client.samples.length,
    samplesDropped: client.diagnostics.samplesDropped,
    firstPlayLatencyMs: client.firstPlayAt ? client.firstPlayAt - client.startedAtMs : null,
    firstChunkLatencyMs: client.firstChunkAt ? client.firstChunkAt - client.startedAtMs : null,
    maxEventLoopGapMs: maxOrNull(loopGaps),
    maxClientFrameDrainMs: maxOrNull(drainTimes),
    maxNetworkPollMs: maxOrNull(pollTimes),
    maxClientTickMs: maxOrNull(tickTimes),
    maxObservedLoopOrDrainMs: maxOrNull([...loopGaps, ...drainTimes, ...pollTimes, ...tickTimes]),
    phaseMetrics: client.waves.map((wave) => ({
      wave: wave.wave,
      playObservedAtMs: wave.playObservedAtMs,
      postPlay: wave.playAt ? summarizePhaseMetrics(client, wave) : null,
    })),
    diagnostics: {...client.diagnostics},
    waves: client.waves.map((wave) => ({...wave})),
    final: client.lastSample,
  };
}

async function closeResources(cdp, resources, output, config) {
  if (cdp) {
    for (const targetId of resources.targets) {
      try {
        await cdp.sendWithTimeout("Target.closeTarget", {targetId}, undefined, config.cdpCommandTimeoutMs);
        output.cleanup.targetsClosed++;
      } catch (error) {
        addBounded(output.cleanup.targetCloseErrors, redactText(error?.message || error).slice(0, 1000), MAX_DIAGNOSTIC_ITEMS);
      }
    }
    for (const contextId of resources.contexts) {
      try {
        await cdp.sendWithTimeout("Target.disposeBrowserContext", {browserContextId: contextId}, undefined, config.cdpCommandTimeoutMs);
        output.cleanup.contextsDisposed++;
      } catch (error) {
        addBounded(output.cleanup.contextDisposeErrors, redactText(error?.message || error).slice(0, 1000), MAX_DIAGNOSTIC_ITEMS);
      }
    }
  }
  if (resources.chrome) {
    try { resources.chrome.kill(); } catch {}
    await waitChildExit(resources.chrome);
    output.cleanup.browserClosed = resources.chrome.exitCode !== null || resources.chrome.signalCode !== null;
    output.cleanup.browserExitCode = resources.chrome.exitCode;
  }
  if (cdp) await cdp.close();
  if (resources.temporaryProfile) {
    try {
      output.cleanup.temporaryProfileRemoved = await removeWithRetry(resources.temporaryProfile);
    } catch (error) {
      output.cleanup.temporaryProfileRemoved = false;
      output.cleanup.contextDisposeErrors.push(`temporary profile: ${redactText(error?.message || error).slice(0, 1000)}`);
    }
  }
}

async function writeOutput(output, outputPath) {
  if (!outputPath) return;
  const resolved = resolve(outputPath);
  await mkdir(dirname(resolved), {recursive: true});
  await writeFile(resolved, `${JSON.stringify(output, null, 2)}\n`, "utf8");
}

function validateLiveConfig(config) {
  const missing = [];
  if (!config.origin) missing.push("GAIUS_ORIGIN/--origin");
  if (!config.serverPort) missing.push("GAIUS_SERVER_PORT/--server-port");
  if (!config.bridge) missing.push("GAIUS_BRIDGE/--bridge");
  if (!config.bridgeToken) missing.push("GAIUS_BRIDGE_TOKEN/--bridge-token");
  if (missing.length) throw new Error(`missing required live-run inputs: ${missing.join(", ")}`);
  let parsed;
  try { parsed = new URL(config.origin); } catch { throw new Error(`invalid GAIUS_ORIGIN: ${config.origin}`); }
  if (!/^https?:$/u.test(parsed.protocol)) throw new Error(`GAIUS_ORIGIN must use http(s): ${config.origin}`);
  if (config.serverPort < 1 || config.serverPort > 65_535) throw new Error("server port must be 1..65535");
}

function runSelfTest(config) {
  const fake = {
    ...config,
    origin: "http://127.0.0.1:8781",
    serverHost: "127.0.0.1",
    serverPort: 25565,
    bridge: "http://127.0.0.1:8080/tunnel",
    bridgeToken: "secret-token",
    offlineMode: true,
    clientCount: 4,
    soakMs: 15_000,
    reconnectWaves: 1,
  };
  const url = makeUrl(fake, 0, 2);
  const parsed = new URL(url);
  if (parsed.searchParams.get("bridge") !== "http://127.0.0.1:8080/tunnel") {
    throw new Error("self-test bridge endpoint path preservation failed");
  }
  if (parsed.searchParams.get("relayRegistry") !== "0") throw new Error("self-test relayRegistry policy failed");
  if (parsed.searchParams.get("directPlugin") !== "0") throw new Error("self-test directPlugin policy failed");
  if (parsed.searchParams.get("allowMultipleTabs") !== "1") throw new Error("self-test tab policy failed");
  if (parsed.searchParams.get("reconnectWave") !== "2") throw new Error("self-test reconnect parameter failed");
  if (parsed.searchParams.get("bridgeToken") !== "secret-token") throw new Error("self-test URL token placement failed");
  const redacted = redactUrl(url);
  if (redacted.includes("secret-token")) throw new Error("self-test URL redaction failed");
  const delayed = parseArgs(["--client-start-delay-ms", "1234"], {});
  if (delayed.clientStartDelayMs !== 1234) {
    throw new Error("self-test client start delay parsing failed");
  }
  const clampedDelay = parseArgs(["--client-start-delay-ms", "999999"], {});
  if (clampedDelay.clientStartDelayMs !== 60_000) {
    throw new Error("self-test client start delay bound failed");
  }
  const eligibility = strictEligibility(fake);
  if (eligibility.releaseEligible !== false || eligibility.releasePass !== false) {
    throw new Error("self-test strict release gate must remain false");
  }
  const output = createOutput(fake);
  if (output.mode.syntheticInput || output.strictEligibility.releaseEligible) {
    throw new Error("self-test diagnostic mode contract failed");
  }
  const phaseClient = newClient(0);
  const phaseWave = newWave(phaseClient, 0);
  phaseWave.startedAtMs = 1_000;
  phaseWave.playAt = 2_000;
  phaseWave.playObservedAtMs = 2_100;
  phaseWave.playBaseline = {
    eventLoopGapMs: 100,
    clientFrameDrainMs: 4,
    networkPollMs: 3,
    clientTickMs: 5,
  };
  phaseClient.samples = [
    {
      driver: {wave: 0, capturedAtMs: 1_500},
      bridge: {longestEventLoopGapMillis: 999},
      counters: {networkPollMaxMillis: 999, clientTickMaxMillis: 999},
    },
    {
      driver: {wave: 0, capturedAtMs: 2_200},
      bridge: {
        longestEventLoopGapMillis: 120,
        clientFrameMaxDrainDurationMillis: 6,
      },
      counters: {networkPollMaxMillis: 5, clientTickMaxMillis: 8},
    },
    {
      driver: {wave: 0, capturedAtMs: 2_300},
      bridge: {
        longestEventLoopGapMillis: 135,
        clientFrameMaxDrainDurationMillis: 7,
      },
      counters: {networkPollMaxMillis: 6, clientTickMaxMillis: 9},
    },
  ];
  const phaseMetrics = summarizeClient(phaseClient).phaseMetrics[0].postPlay;
  if (phaseMetrics.sampleCount !== 2 ||
      phaseMetrics.metrics.eventLoopGapMs.rawMax !== 135 ||
      phaseMetrics.metrics.eventLoopGapMs.newMaxFromPlay !== 35) {
    throw new Error("self-test post-PLAY phase metric separation failed");
  }
  return {
    ok: true,
    driver: DRIVER_NAME,
    checks: [
      "isolated headed mode metadata",
      "loopback URL policy and reconnect wave",
      "secret redaction",
      "bounded initial headed-client launch staggering",
      "strict release eligibility hard-false",
      "post-PLAY cumulative-gauge phase metrics",
    ],
    configuration: configForOutput(fake),
    strictEligibility: eligibility,
  };
}

async function run(config) {
  validateLiveConfig(config);
  const output = createOutput(config);
  const resources = {chrome: null, temporaryProfile: null, debuggingPort: null, targets: [], contexts: []};
  let cdp = null;
  try {
    const launched = await launchChrome(config);
    Object.assign(resources, launched);
    output.cleanup.temporaryProfile = launched.temporaryProfile;
    const version = launched.version || {};
    output.runtime = {
      chromePath: config.chromePath,
      userAgent: version.UserAgent || null,
      browser: version.Browser || null,
      protocolVersion: version["Protocol-Version"] || null,
      debuggingPort: launched.debuggingPort,
    };
    cdp = new CdpConnection(version.webSocketDebuggerUrl);
    await cdp.open();
    await cdp.sendWithTimeout("Target.setDiscoverTargets", {discover: true}, undefined, config.cdpCommandTimeoutMs);
    for (let index = 0; index < config.clientCount; index++) {
      // A setup failure is retained on that client; it does not prevent the
      // other independent contexts from being created.
      await createClient(cdp, config, output, index, resources);
      if (config.clientStartDelayMs > 0 && index + 1 < config.clientCount) {
        await sleep(config.clientStartDelayMs);
      }
    }
    const initialWave = output.clients.map((client) => client.waves[0]).filter(Boolean);
    if (initialWave.length) {
      const startup = await pollUntilPlay(cdp, output, initialWave[0], config.startupTimeoutMs, "startup");
      await runSoak(cdp, output, initialWave[0], "soak");
      if (!startup.allPlay) output.findings.push("initial diagnostic phase incomplete; soak samples retained");
    }
    for (let waveNumber = 1; waveNumber <= config.reconnectWaves; waveNumber++) {
      await Promise.all(output.clients.map((client) => navigateForReconnect(cdp, config, output, client, waveNumber)));
      const wave = output.clients.map((client) => client.waves.at(-1)).find(Boolean);
      if (!wave) break;
      await pollUntilPlay(cdp, output, wave, config.reconnectTimeoutMs, `reconnect-${waveNumber}-startup`);
      await runSoak(cdp, output, wave, `reconnect-${waveNumber}-soak`);
    }
    for (const client of output.clients) {
      client.summary = summarizeClient(client);
      if (client.failure) output.findings.push(`client ${client.index + 1} page diagnostic error captured`);
    }
    output.diagnosticStatus = output.violations.length === 0 ? "completed" : "incomplete";
    output.diagnosticOk = output.diagnosticStatus === "completed";
  } catch (error) {
    output.diagnosticStatus = "failed";
    output.fatalError = redactText(error?.stack || error).slice(0, 8000);
    output.violations.push(output.fatalError);
  } finally {
    await closeResources(cdp, resources, output, config);
    output.endedAt = isoNow();
    output.productPass = false;
    output.ok = output.diagnosticOk;
    await writeOutput(output, config.outputPath).catch((error) => {
      output.diagnosticStatus = "failed";
      output.diagnosticOk = false;
      output.ok = false;
      output.violations.push(`result write failed: ${redactText(error?.message || error).slice(0, 1000)}`);
    });
  }
  process.stdout.write(`${JSON.stringify(output)}\n`);
  process.exitCode = output.ok ? 0 : 1;
}

async function main() {
  let config;
  try {
    config = parseArgs();
    if (config.mode === "help") {
      process.stdout.write(`${usage()}\n`);
      return;
    }
    if (config.mode === "print-config") {
      process.stdout.write(`${JSON.stringify({
        schemaVersion: SCHEMA_VERSION,
        driver: DRIVER_NAME,
        mode: {
          diagnosticOnly: true,
          headed: true,
          isolatedTemporaryProfile: true,
          syntheticInput: false,
          existingChromeAttached: false,
        },
        configuration: configForOutput(config),
        strictEligibility: strictEligibility(config),
      })}\n`);
      return;
    }
    if (config.mode === "self-test") {
      process.stdout.write(`${JSON.stringify(runSelfTest(config))}\n`);
      return;
    }
    await run(config);
  } catch (error) {
    process.stderr.write(`${redactText(error?.stack || error)}\n`);
    process.exitCode = 2;
  }
}

await main();
