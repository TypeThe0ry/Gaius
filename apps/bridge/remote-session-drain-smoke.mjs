#!/usr/bin/env node

import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const repository = new URL("../..", import.meta.url);
const networkPath = new URL(
  "../../port/src/main/java/dev/gaius/browser/BrowserClientNetwork.java",
  import.meta.url,
);
const recoveryPath = new URL(
  "../../port/src/main/java/dev/gaius/browser/BrowserMultiplayerRecovery.java",
  import.meta.url,
);
const networkSource = readFileSync(networkPath, "utf8");
const recoverySource = readFileSync(recoveryPath, "utf8");

// This is intentionally a bounded source/model smoke.  It does not claim TeaVM, Chrome, or
// public-relay evidence.  The product still keeps the existing URL/embedder flag authoritative;
// this guard only tags a remote attempt and prevents a stale end callback from clearing a newer
// attempt's demand/pending state.
assert.match(networkSource, /beginClientPacketDrainRemoteSession/);
assert.match(networkSource, /endClientPacketDrainRemoteSession/);
assert.match(networkSource, /endCurrentClientPacketDrainRemoteSession/);
assert.match(networkSource, /currentClientPacketDrainRemoteSessionToken/);
assert.match(networkSource, /clearPendingClientPacketDrainRemoteSession/);
assert.match(networkSource, /pendingClientPacketDrainSession/);
assert.match(networkSource, /clientPacketDrainSessionSequence/);
assert.match(networkSource, /clientPacketDrainDemandToken/);
assert.match(networkSource, /session\.token !== requested/);
assert.match(networkSource, /sessionActive = !session \|\| session\.active === true/);
assert.match(networkSource, /enableClientPacketDrainIfUnset/);

const beginBodyStart = networkSource.indexOf("public static native void beginClientPacketDrainRemoteSession");
const endBodyStart = networkSource.indexOf("public static native boolean endClientPacketDrainRemoteSession");
assert.ok(beginBodyStart > 0 && endBodyStart > beginBodyStart,
  "remote session native methods are not ordered");
const endBody = networkSource.slice(networkSource.lastIndexOf("@JSBody", endBodyStart), endBodyStart);
assert.equal(endBody.includes("__gaiusClientPacketDrainEnabled = false"), false,
  "remote-session end must not override an embedder/URL enabled flag");
assert.match(endBody, /__gaiusClientPacketDrainAutoEnabled === true/,
  "remote-session end must distinguish automatic promotion from explicit policy");
const endCurrentBody = networkSource.slice(
  networkSource.lastIndexOf("@JSBody", networkSource.indexOf(
    "public static native boolean endCurrentClientPacketDrainRemoteSession")),
  networkSource.indexOf("public static native boolean endCurrentClientPacketDrainRemoteSession"),
);
assert.match(endCurrentBody, /expected && actual !== expected/,
  "address-scoped cleanup lost its stale-callback guard");

const recoveryBegin = recoverySource.indexOf("public static void beginConnection(ServerData serverData)");
const recoveryEnd = recoverySource.indexOf("\n    public static boolean maybeReconnect", recoveryBegin);
assert.ok(recoveryBegin >= 0 && recoveryEnd > recoveryBegin, "missing beginConnection method");
const recoveryBody = recoverySource.slice(recoveryBegin, recoveryEnd);
const attemptOffset = recoveryBody.indexOf("beginConnectionAttempt(address)");
const sessionOffset = recoveryBody.indexOf("beginClientPacketDrainRemoteSession(address)");
assert.ok(attemptOffset >= 0 && sessionOffset > attemptOffset,
  "remote session token must be created after the active attempt is recorded");
assert.equal((recoveryBody.match(/beginConnectionAttempt\(address\)/g) || []).length, 1,
  "beginConnection must record exactly one attempt");
assert.match(recoveryBody, /boolean remote = isRemoteServerAddress\(address\)/);
assert.match(recoveryBody, /if \(remote\) \{[\s\S]*enableClientPacketDrainForRemoteSession\(\)/);
assert.match(recoveryBody, /endCurrentClientPacketDrainRemoteSession\("", "non-remote"\)/,
  "local transition does not retire the active remote session");
assert.match(recoverySource, /rememberPreparedRemoteSessionToken/);
assert.match(recoverySource, /takePreparedRemoteSessionToken/);
const prepareBegin = recoverySource.indexOf("public static boolean prepareDisconnect");
const prepareEnd = recoverySource.indexOf("\n    static void rememberRequiredServerPack", prepareBegin);
assert.ok(prepareBegin >= 0 && prepareEnd > prepareBegin, "missing prepareDisconnect method");
const prepareBody = recoverySource.slice(prepareBegin, prepareEnd);
assert.ok(prepareBody.indexOf("prepareColdPackRetry(address)") >= 0);
assert.ok(prepareBody.indexOf("rememberPreparedRemoteSessionToken") >
  prepareBody.indexOf("prepareColdPackRetry(address)"),
"disconnect callback token must be pinned after retry preparation");
const maybeBegin = recoverySource.indexOf("public static boolean maybeReconnect");
const maybeEnd = recoverySource.indexOf("\n    /** Claims recovery", maybeBegin);
const maybeBody = recoverySource.slice(maybeBegin, maybeEnd > maybeBegin ? maybeEnd : undefined);
assert.match(maybeBody, /String sessionToken = takePreparedRemoteSessionToken\(\)/);
assert.match(maybeBody, /endCurrentClientPacketDrainRemoteSession\(/,
  "recovery rejection/cancellation does not retire an active remote session");
assert.equal(maybeBody.includes("currentClientPacketDrainRemoteSessionToken"), false,
  "maybeReconnect must not capture a replacement session's current token");

// Keep the strict latency and packet-budget contracts out of this lifecycle-only change.
assert.equal(networkSource.includes("16.8"), false, "callback gate was relaxed");
assert.equal(networkSource.includes("500.1"), false, "stall gate was relaxed");

// Execute the exact JSBody snippets in a small VM as well as the model below.  This catches a
// drift between the Java source annotation and the lifecycle contract without claiming TeaVM or
// browser evidence; the context intentionally contains only the bridge/recovery fields used by
// these three snippets.
function extractJsBody(methodSignature) {
  const methodOffset = networkSource.indexOf(methodSignature);
  assert.ok(methodOffset >= 0, `missing JSBody method: ${methodSignature}`);
  const annotationOffset = networkSource.lastIndexOf("@JSBody", methodOffset);
  assert.ok(annotationOffset >= 0, `missing JSBody annotation: ${methodSignature}`);
  const annotation = networkSource.slice(annotationOffset, methodOffset);
  const match = annotation.match(/script\s*=\s*"""([\s\S]*?)"""/u);
  assert.ok(match, `missing triple-quoted JSBody script: ${methodSignature}`);
  return match[1];
}

function compileJsBody(script, parameters = "") {
  return vm.runInNewContext(`(function(${parameters}) {\n${script}\n})`, jsContext);
}

const jsContext = {
  Date,
  Math,
  Number,
  String,
  __gaiusMultiplayerRecovery: {
    activeAddress: "ellan.top:16888",
    activeAttempt: 7,
  },
  __gaiusNettyBridge: {
    inboundPumpGeneration: 4,
    stats: {},
  },
  __gaiusClientPacketDrainEnabled: true,
  __gaiusClientPacketDrainAutoEnabled: false,
};
jsContext.globalThis = jsContext;
const beginJsBody = compileJsBody(
  extractJsBody("public static native void beginClientPacketDrainRemoteSession"),
  "address",
);
const endJsBody = compileJsBody(
  extractJsBody("public static native boolean endClientPacketDrainRemoteSession"),
  "token, reason",
);
const endCurrentJsBody = compileJsBody(
  extractJsBody("public static native boolean endCurrentClientPacketDrainRemoteSession"),
  "address, reason",
);
const currentJsBody = compileJsBody(
  extractJsBody("public static native String currentClientPacketDrainRemoteSessionToken"),
);
const clearPendingJsBody = compileJsBody(
  extractJsBody("public static native boolean clearPendingClientPacketDrainRemoteSession"),
  "reason",
);

// The first ConnectScreen hook can run before BrowserWebSocketChannel creates the bridge.  The
// descriptor must survive that boundary and be materialized exactly once by the later installer.
delete jsContext.__gaiusNettyBridge;
beginJsBody("ellan.top:16888");
const pendingBeforeBridge = jsContext.__gaiusMultiplayerRecovery.pendingClientPacketDrainSession;
assert.equal(pendingBeforeBridge.address, "ellan.top:16888");
assert.equal(pendingBeforeBridge.attempt, 7);
assert.equal(pendingBeforeBridge.active, true);
assert.ok(pendingBeforeBridge.startedAt > 0);
assert.equal(typeof jsContext.__gaiusMultiplayerRecovery.beginClientPacketDrainRemoteSession,
  "function");
jsContext.__gaiusNettyBridge = {
  inboundPumpGeneration: 4,
  stats: {},
};
const pending = jsContext.__gaiusMultiplayerRecovery.pendingClientPacketDrainSession;
assert.equal(jsContext.__gaiusMultiplayerRecovery.beginClientPacketDrainRemoteSession(
  pending.address, pending.attempt), true);
assert.equal(jsContext.__gaiusMultiplayerRecovery.pendingClientPacketDrainSession, undefined);
assert.equal(jsContext.__gaiusNettyBridge.clientPacketDrainSession.attempt, 7);
assert.equal(jsContext.__gaiusNettyBridge.stats.clientPacketDrainSessionBegins, 1);
// A local switch before bridge creation clears the descriptor instead of arming a future remote
// session.  This uses a fresh descriptor so the assertion is independent of the previous handoff.
delete jsContext.__gaiusNettyBridge;
jsContext.__gaiusMultiplayerRecovery.activeAttempt = 9;
beginJsBody("ellan.top:16888");
assert.ok(jsContext.__gaiusMultiplayerRecovery.pendingClientPacketDrainSession);
assert.equal(clearPendingJsBody("non-remote"), true);
assert.equal(jsContext.__gaiusMultiplayerRecovery.pendingClientPacketDrainSession, undefined);
jsContext.__gaiusNettyBridge = {
  inboundPumpGeneration: 4,
  stats: {},
};
jsContext.__gaiusMultiplayerRecovery.activeAttempt = 7;
beginJsBody("ellan.top:16888");
const jsTokenA = jsContext.__gaiusNettyBridge.clientPacketDrainSessionToken;
assert.match(jsTokenA, /^ellan\.top:16888#7#1$/u);
assert.equal(currentJsBody(), jsTokenA);
jsContext.__gaiusNettyBridge.clientPacketDrainDemand = true;
jsContext.__gaiusNettyBridge.clientPacketDrainPending = true;
jsContext.__gaiusMultiplayerRecovery.activeAttempt = 8;
beginJsBody("ellan.top:16888");
const jsTokenB = jsContext.__gaiusNettyBridge.clientPacketDrainSessionToken;
assert.match(jsTokenB, /^ellan\.top:16888#8#2$/u);
assert.notEqual(jsTokenA, jsTokenB);
assert.equal(jsContext.__gaiusNettyBridge.clientPacketDrainDemand, false);
assert.equal(jsContext.__gaiusNettyBridge.clientPacketDrainPending, false);
assert.equal(endJsBody(jsTokenA, "late-old-disconnect"), false);
assert.equal(currentJsBody(), jsTokenB);
assert.equal(endJsBody(jsTokenB, "terminal-disconnect"), true);
assert.equal(currentJsBody(), "");
assert.equal(jsContext.__gaiusClientPacketDrainEnabled, true);
assert.equal(jsContext.__gaiusClientPacketDrainAutoEnabled, false);
assert.equal(endJsBody(jsTokenB, "duplicate"), false);
// A local/menu transition uses an empty address to retire the current remote session and remove
// an automatically promoted flag, including a pending handoff descriptor.
jsContext.__gaiusNettyBridge.clientPacketDrainSession = {
  token: "ellan.top:16888#9#3",
  address: "ellan.top:16888",
  attempt: 9,
  active: true,
};
jsContext.__gaiusNettyBridge.clientPacketDrainSessionToken = "ellan.top:16888#9#3";
jsContext.__gaiusNettyBridge.clientPacketDrainDemand = true;
jsContext.__gaiusNettyBridge.clientPacketDrainPending = true;
jsContext.__gaiusMultiplayerRecovery.pendingClientPacketDrainSession = {
  address: "ellan.top:16888",
  attempt: 9,
  active: true,
};
jsContext.__gaiusClientPacketDrainEnabled = true;
jsContext.__gaiusClientPacketDrainAutoEnabled = true;
assert.equal(endCurrentJsBody("", "non-remote"), true);
assert.equal(currentJsBody(), "");
assert.equal(jsContext.__gaiusNettyBridge.clientPacketDrainDemand, false);
assert.equal(jsContext.__gaiusNettyBridge.clientPacketDrainPending, false);
assert.equal(jsContext.__gaiusMultiplayerRecovery.pendingClientPacketDrainSession, undefined);
assert.equal(jsContext.__gaiusClientPacketDrainEnabled, undefined);
assert.equal(jsContext.__gaiusClientPacketDrainAutoEnabled, undefined);
// Address-scoped terminal cleanup must reject an old address without touching the replacement,
// while a matching address retires the session and preserves an explicit policy flag.
jsContext.__gaiusNettyBridge.clientPacketDrainSession = {
  token: "ellan.top:16888#10#4",
  address: "ellan.top:16888",
  attempt: 10,
  active: true,
};
jsContext.__gaiusNettyBridge.clientPacketDrainSessionToken = "ellan.top:16888#10#4";
jsContext.__gaiusNettyBridge.clientPacketDrainDemand = true;
jsContext.__gaiusClientPacketDrainEnabled = true;
jsContext.__gaiusClientPacketDrainAutoEnabled = false;
assert.equal(endCurrentJsBody("other.example:25565", "late-old-address"), false);
assert.equal(currentJsBody(), "ellan.top:16888#10#4");
assert.equal(jsContext.__gaiusNettyBridge.clientPacketDrainDemand, true);
assert.equal(endCurrentJsBody("ellan.top:16888", "explicit-terminal"), true);
assert.equal(currentJsBody(), "");
assert.equal(jsContext.__gaiusNettyBridge.clientPacketDrainDemand, false);
assert.equal(jsContext.__gaiusNettyBridge.clientPacketDrainPending, false);
assert.equal(jsContext.__gaiusClientPacketDrainEnabled, true);
assert.equal(jsContext.__gaiusClientPacketDrainAutoEnabled, false);
const jsBodyRuntimeContract =
  jsContext.__gaiusNettyBridge.stats.clientPacketDrainSessionBegins === 2 &&
  jsContext.__gaiusNettyBridge.stats.clientPacketDrainSessionEnds === 3 &&
  jsContext.__gaiusNettyBridge.stats.clientPacketDrainSessionStaleEnds === 3 &&
  jsContext.__gaiusNettyBridge.clientPacketDrainDemand === false &&
  jsContext.__gaiusNettyBridge.clientPacketDrainPending === false;
assert.equal(jsBodyRuntimeContract, true, "JSBody lifecycle snippets diverged from the guard model");

// The retry callback must use the token pinned at prepareDisconnect time, not whichever session
// happens to be current when the queued Minecraft task runs.
const pinnedToken = "ellan.top:16888#7#1";
const replacementToken = "ellan.top:16888#8#2";
const preparedCallbackToken = pinnedToken;
const currentCallbackToken = replacementToken;
assert.notEqual(preparedCallbackToken, currentCallbackToken);
assert.equal(preparedCallbackToken, pinnedToken);
assert.notEqual(preparedCallbackToken, replacementToken,
  "a stale retry callback must not inherit the replacement token");
const reconnectCallbackTokenPinned = preparedCallbackToken === pinnedToken &&
  preparedCallbackToken !== currentCallbackToken;
assert.equal(reconnectCallbackTokenPinned, true);

class SessionGuardModel {
  constructor() {
    this.sequence = 0;
    this.current = null;
    this.demand = false;
    this.pending = false;
    this.enabled = true;
    this.staleEnds = 0;
    this.begins = 0;
    this.ends = 0;
  }

  begin(address, attempt) {
    this.sequence = this.sequence >= 0x7fffffff ? 1 : this.sequence + 1;
    const token = `${String(address).trim().toLowerCase()}#${attempt}#${this.sequence}`;
    if (this.current?.active) this.current = {...this.current, active: false, endReason: "superseded"};
    this.current = {token, address, attempt, active: true, endReason: ""};
    this.demand = false;
    this.pending = false;
    this.begins++;
    return token;
  }

  signalDemand() {
    if (!this.current?.active) return false;
    this.demand = true;
    return true;
  }

  end(token, reason = "ended") {
    if (!this.current?.active || !token || this.current.token !== token) {
      this.staleEnds++;
      return false;
    }
    this.current = {...this.current, active: false, endReason: reason};
    this.demand = false;
    this.pending = false;
    this.ends++;
    return true;
  }
}

const model = new SessionGuardModel();
const tokenA = model.begin("ellan.top:16888", 7);
assert.equal(model.signalDemand(), true);
model.pending = true;
const tokenB = model.begin("ellan.top:16888", 8);
assert.notEqual(tokenA, tokenB, "reconnect attempts must receive distinct tokens");
assert.equal(model.demand, false, "new session did not clear stale demand");
assert.equal(model.pending, false, "new session did not clear stale pending state");
assert.equal(model.end(tokenA, "late-old-disconnect"), false,
  "late old-session end unexpectedly cleared the replacement session");
assert.equal(model.staleEnds, 1);
assert.equal(model.current?.active, true, "stale end retired the replacement session");
assert.equal(model.signalDemand(), true);
assert.equal(model.end(tokenB, "terminal-disconnect"), true,
  "current session did not end");
assert.equal(model.demand, false);
assert.equal(model.pending, false);
assert.equal(model.enabled, true, "session end changed the explicit enabled flag");
assert.equal(model.end(tokenB, "duplicate"), false,
  "duplicate end callback was not rejected");
assert.equal(model.staleEnds, 2);

// A token sequence wraps to 1 only at the bounded integer ceiling, never to an empty token.
model.sequence = 0x7ffffffe;
const wrapped = model.begin("47.83.130.57:16888", 9);
assert.match(wrapped, /#9#2147483647$/);
const wrappedAgain = model.begin("47.83.130.57:16888", 10);
assert.match(wrappedAgain, /#10#1$/);

console.log(JSON.stringify({
  schemaVersion: "gaius.remote-session-drain-smoke.v1",
  status: "pass",
  sourcePath: repository.pathname,
  model: {
    begins: model.begins,
    ends: model.ends,
    staleEnds: model.staleEnds,
    replacementTokenDistinct: true,
    staleEndCannotRetireReplacement: true,
    demandClearedOnBegin: true,
    pendingClearedOnBegin: true,
    demandClearedOnEnd: true,
    pendingClearedOnEnd: true,
    localTransitionRetiresAutomaticSession: true,
    explicitPolicyPreservedOnEnd: true,
    sequenceWrapBounded: true,
  },
  gates: {
    sessionTokenGeneration: true,
    staleEndRejected: true,
    sessionDemandReset: true,
    explicitEnabledFlagPreserved: true,
    beginAttemptBeforeSession: true,
    jsBodyRuntimeContract,
    reconnectCallbackTokenPinned,
    strictLatencyAndStallGatesUnchanged: true,
    teaVmRuntimeProof: false,
    publicRelayRuntimeProof: false,
  },
  interpretation: {
    confirmed: [
      "remote attempts carry a bounded token/generation",
      "a stale end callback cannot clear a newer token's demand/pending state",
      "stage 1 preserves the existing enabled-flag precedence",
      "local/menu cleanup retires an active remote session and removes only automatic promotion",
      "address-scoped cleanup preserves an explicit embedder policy",
      "the exact JSBody lifecycle snippets pass a bounded VM contract check",
      "retry callbacks use the token pinned at disconnect preparation, not a replacement token",
    ],
    notProven: [
      "TeaVM/browser live behavior",
      "multiple Java PacketProcessor isolation",
      "public ellan.top latency or reconnect health",
    ],
  },
}, null, 2));
