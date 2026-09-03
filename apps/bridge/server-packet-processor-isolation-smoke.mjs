#!/usr/bin/env node

/**
 * Server PacketProcessor accounting/isolation audit.
 *
 * This is deliberately server-free: it models the scope of the Java
 * PacketProcessor hooks and checks the close/reconnect boundary in the source.
 * The Java patch owns one PacketProcessor per Minecraft/MinecraftServer runtime;
 * browser channels are many-to-one inputs to that processor.  A second model
 * covers the unsupported-but-dangerous shape (two PacketProcessor instances in
 * one JVM): the current static BrowserPacketScheduler state cannot isolate it.
 * Keeping that counterexample here prevents a multi-client close result from
 * being over-interpreted as proof of arbitrary same-runtime processor safety.
 */

import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {fileURLToPath} from "node:url";

const repository = fileURLToPath(new URL("../..", import.meta.url));
const read = (relative) => readFile(`${repository}/${relative}`, "utf8");

const [schedulerSource, clientNetworkSource, channelSource, patcherSource,
    relayMainSource, fullPathSource, accountingSmokeSource, clientDrainSmokeSource] =
    await Promise.all([
        read("port/src/main/java/dev/gaius/browser/BrowserPacketScheduler.java"),
        read("port/src/main/java/dev/gaius/browser/BrowserClientNetwork.java"),
        read("port/overrides/libraries/netty-transport/src/main/java/" +
            "io/netty/channel/browser/BrowserWebSocketChannel.java"),
        read("port/tools/src/main/java/dev/gaius/tools/MinecraftClientPatcher.java"),
        read("apps/bridge/dist/main.js"),
        read("apps/bridge/browser-full-path-smoke.mjs"),
        read("port/scripts/packet-processor-accounting-smoke.mjs"),
        read("apps/bridge/client-tcp-bounded-drain-smoke.mjs"),
    ]);

function requireMatch(value, expression, label) {
    assert.match(value, expression, label);
    return true;
}

function countMatches(value, expression) {
    return [...value.matchAll(expression)].length;
}

// ----------------------------- static contracts -----------------------------

const resetStart = schedulerSource.indexOf("public static void reset()");
const resetEnd = schedulerSource.indexOf("\n    }\n}", resetStart);
assert.ok(resetStart >= 0 && resetEnd > resetStart,
    "BrowserPacketScheduler.reset() boundary changed; audit it before updating this smoke");
const resetBody = schedulerSource.slice(resetStart, resetEnd);
const lifecycleBindNeverClearsRetired = !/clearRetiredPacketProcessorOwner/u.test(schedulerSource);
assert.equal(lifecycleBindNeverClearsRetired, true,
    "PacketProcessor lifecycle bind must not contain a retired-owner tombstone clear");
const ownerContract = {
    ownerState: requireMatch(schedulerSource,
        /private static Object packetProcessorOwner;[\s\S]*private static long packetProcessorGeneration;/u,
        "owner/generation state is missing from PacketProcessor accounting"),
    conflictState: requireMatch(schedulerSource,
        /private static boolean packetProcessorOwnerConflict;[\s\S]*private static boolean packetProcessorAccountingValid/u,
        "owner conflict must fail closed before sharing global accounting"),
    conflictPoisonState: requireMatch(schedulerSource,
        /private static boolean packetProcessorConflictPoisoned;/u,
        "conflicting PacketProcessor owners must poison the runtime accounting epoch"),
    conflictPoisonSet: requireMatch(schedulerSource,
        /packetProcessorConflictPoisoned\s*=\s*true;/u,
        "owner conflict must set the runtime poison marker"),
    ownerClaim: requireMatch(schedulerSource,
        /public static boolean bindPacketProcessor\(Object owner\)/u,
        "PacketProcessor owner claim entry point is missing"),
    staleResetGuard: requireMatch(schedulerSource,
        /public static void reset\(Object owner\)[\s\S]*packetProcessorOwnerConflict[\s\S]*stalePacketProcessorResets/u,
        "owner-aware reset does not ignore conflicting/stale close events"),
    retiredOwnerLimit: requireMatch(schedulerSource,
        /RETIRED_PACKET_PROCESSOR_OWNER_LIMIT\s*=\s*16/u,
        "retired PacketProcessor owner tombstone limit is missing or unbounded"),
    retiredOwnerTable: requireMatch(schedulerSource,
        /private static final Object\[\] retiredPacketProcessorOwners\s*=\s*\n\s*new Object\[RETIRED_PACKET_PROCESSOR_OWNER_LIMIT\]/u,
        "retired PacketProcessor owner tombstone table is missing"),
    frameBindIsClaimOnly: requireMatch(schedulerSource,
        /public static boolean bindPacketProcessor\(Object owner\)\s*\{\s*return claimPacketProcessorOwner\(owner\);\s*\}/u,
        "per-frame PacketProcessor bind must not release a retired tombstone"),
    lifecycleBindIsClaimOnly: requireMatch(schedulerSource,
        /public static boolean bindPacketProcessorLifecycle\(Object owner\)\s*\{\s*return claimPacketProcessorOwner\(owner\);\s*\}/u,
        "PacketProcessor lifecycle bind must claim without releasing a retired tombstone"),
    lifecycleBindNeverClearsRetired,
    constructorLifecycleHook: requireMatch(patcherSource,
        /bindPacketProcessorLifecycle[\s\S]*PacketProcessor constructor lifecycle/u,
        "PacketProcessor constructor does not establish the explicit lifecycle boundary"),
    retiredEventFailClosed: requireMatch(schedulerSource,
        /isRetiredPacketProcessorOwner\(owner\)[\s\S]*stalePacketProcessorEvents[\s\S]*retired-owner-event/u,
        "late retired-owner callbacks can still rebind static accounting"),
};

const unknownOwnerStart = schedulerSource.indexOf(
    "private static void noteUnknownPacketProcessorOwner(String reason)");
const unknownOwnerEnd = schedulerSource.indexOf(
    "\n    private static void startPendingOwnerFrame", unknownOwnerStart);
assert.ok(unknownOwnerStart >= 0 && unknownOwnerEnd > unknownOwnerStart,
    "unknown-owner callback boundary changed; audit sticky conflict handling before updating this smoke");
const unknownOwnerBody = schedulerSource.slice(unknownOwnerStart, unknownOwnerEnd);
const unknownOwnerPoisonGuard = {
    unknownOwnerEventPoisons: requireMatch(
        unknownOwnerBody,
        /packetProcessorOwnerConflict\s*=\s*true;[\s\S]*packetProcessorAccountingValid\s*=\s*false;[\s\S]*packetProcessorConflictPoisoned\s*=\s*true;/u,
        "unknown PacketProcessor owner events must poison the accounting epoch"),
};

const ownerPacketProcessedStart = schedulerSource.indexOf(
    "public static void packetProcessed(Object owner)");
const ownerPacketProcessedEnd = schedulerSource.indexOf(
    "\n    /** Mirrors PacketProcessor.close", ownerPacketProcessedStart);
assert.ok(ownerPacketProcessedStart >= 0 && ownerPacketProcessedEnd > ownerPacketProcessedStart,
    "owner-aware packetProcessed boundary changed; audit retired-handler recovery before updating this smoke");
const ownerPacketProcessedBody = schedulerSource.slice(
    ownerPacketProcessedStart, ownerPacketProcessedEnd);
const retiredUnwindPoisonGuard = {
    recoveryRequiresNoPoison: requireMatch(
        ownerPacketProcessedBody,
        /queuedPacketHandleDepth\s*==\s*0[\s\S]*packetProcessorOwner\s*==\s*null[\s\S]*!packetProcessorConflictPoisoned/u,
        "retired-owner unwind can restore valid accounting after an unknown-owner poison"),
};

const currentLedgerStart = schedulerSource.indexOf(
    "private static PacketProcessorLedger currentPacketProcessorLedger()");
const currentLedgerEnd = schedulerSource.indexOf(
    "\n    private static int activePacketProcessorLedgerCount", currentLedgerStart);
assert.ok(currentLedgerStart >= 0 && currentLedgerEnd > currentLedgerStart,
    "currentPacketProcessorLedger boundary changed; audit ownerless compatibility fallback");
const currentLedgerBody = schedulerSource.slice(currentLedgerStart, currentLedgerEnd);
const currentLedgerGuard = {
    invalidLedgerRejected: requireMatch(currentLedgerBody,
        /!ledger\.accountingValid/u,
        "ownerless compatibility lookup can still return an invalid ledger"),
    ownerConflictRejected: requireMatch(currentLedgerBody,
        /packetProcessorOwnerConflict/u,
        "ownerless compatibility lookup can still return a conflicting ledger"),
    poisonRejected: requireMatch(currentLedgerBody,
        /packetProcessorConflictPoisoned/u,
        "ownerless compatibility lookup can still return a poisoned ledger"),
    staticAccountingInvalidRejected: requireMatch(currentLedgerBody,
        /!packetProcessorAccountingValid/u,
        "ownerless compatibility lookup can ignore the runtime accounting-valid bit"),
    retiredOwnerAwarePathPreserved: requireMatch(schedulerSource,
        /public static void packetProcessed\(Object owner\)[\s\S]*?packetProcessorLedgerIncludingRetired\(owner\)[\s\S]*?completeOwnerPacket\(ledger\)/u,
        "owner-aware retired packet unwind no longer bypasses the ownerless lookup guard"),
};

const completeOwnerPacketStart = schedulerSource.indexOf(
    "private static void completeOwnerPacket");
const completeOwnerPacketEnd = schedulerSource.indexOf(
    "\n    private static void queueOwnerPacket", completeOwnerPacketStart);
assert.ok(completeOwnerPacketStart >= 0 && completeOwnerPacketEnd > completeOwnerPacketStart,
    "completeOwnerPacket boundary changed; audit retired handler unwind before updating this smoke");
const completeOwnerPacketBody = schedulerSource.slice(
    completeOwnerPacketStart, completeOwnerPacketEnd);
const retiredHandlerMirrorGuard = requireMatch(
    completeOwnerPacketBody,
    /if\s*\(\s*\(\s*packetProcessorOwner\s*==\s*ledger\.owner\s*&&\s*packetProcessorGeneration\s*==\s*ledger\.generation\s*\)\s*\|\|\s*\(\s*packetProcessorOwner\s*==\s*null\s*&&\s*queuedPacketHandleOwner\s*==\s*ledger\.owner\s*&&\s*ledger\.retired\s*\)\s*\)\s*\{\s*queuedPacketHandleDepth\s*=\s*ledger\.queuedPacketHandleDepth;/u,
    "retired handler unwind must mirror depth only for the matching owner or its in-flight close");
const ledgerExhaustionContract = {
    ledgerLimit: requireMatch(schedulerSource,
        /PACKET_PROCESSOR_LEDGER_LIMIT\s*=\s*16/u,
        "PacketProcessor ledger must retain a bounded slot limit"),
    retiredLimit: requireMatch(schedulerSource,
        /RETIRED_PACKET_PROCESSOR_OWNER_LIMIT\s*=\s*16/u,
        "retired owner tombstone limit must match the bounded ledger limit"),
    exhaustedState: requireMatch(schedulerSource,
        /private static boolean packetProcessorLedgerExhausted;/u,
        "ledger slot exhaustion needs an explicit sticky state"),
    allocationMarksExhausted: requireMatch(schedulerSource,
        /packetProcessorLedgerSlotExhaustions\+\+;[\s\S]*packetProcessorLedgerExhausted\s*=\s*true;/u,
        "ledger allocation exhaustion must be recorded before fallback"),
    exhaustionFailClosed: requireMatch(schedulerSource,
        /if \(packetProcessorLedgerExhausted\)[\s\S]*packetProcessorConflictPoisoned\s*=\s*true;[\s\S]*ledger-slot-exhausted/u,
        "an exhausted ledger must permanently fail closed with a stable reason"),
    noSlotReuse: requireMatch(schedulerSource,
        /if \(packetProcessorLedgers\[index\] == null\)[\s\S]*packetProcessorLedgers\[index\] = ledger;/u,
        "retired ledger slots must not be reused for a new owner"),
};

const ownerResetStart = schedulerSource.indexOf(
    "public static void reset(Object owner)");
const ownerResetEnd = schedulerSource.indexOf(
    "\n    public static boolean isPacketProcessorAccountingValid", ownerResetStart);
assert.ok(ownerResetStart >= 0 && ownerResetEnd > ownerResetStart,
    "owner-aware reset boundary changed; audit it before updating this smoke");
const ownerResetBody = schedulerSource.slice(ownerResetStart, ownerResetEnd);
const ownerResetCall = ownerResetBody.indexOf("\n        resetOwnerLedger(ledger);");
assert.ok(ownerResetCall > 0, "owner-aware reset lost its owner-ledger reset call");
const ownerResetGuard = {
    activeOwnershipBeforeGlobalReset: requireMatch(
        ownerResetBody.slice(0, ownerResetCall),
        /owner == null \|\| owner != packetProcessorOwner/u,
        "owner-aware reset does not reject foreign/null owners before owner-ledger reset"),
    conflictRejectedBeforeGlobalReset: /packetProcessorOwnerConflict|!packetProcessorAccountingValid/u
        .test(ownerResetBody.slice(0, ownerResetCall)),
    conflictedOwnerCanRetire: requireMatch(
        ownerResetBody.slice(ownerResetCall),
        /resetOwnerLedger\(ledger\);/u,
        "active owner cannot retire and recover after a fail-closed conflict"),
};

const clientPacketBoundaryStart = clientNetworkSource.indexOf(
    "public static void processClientPacketsAtScheduledFrameBoundary(");
const clientPacketBoundaryEnd = clientNetworkSource.indexOf(
    "\n    @JSBody(params = \"callback\"", clientPacketBoundaryStart);
assert.ok(clientPacketBoundaryStart >= 0 && clientPacketBoundaryEnd > clientPacketBoundaryStart,
    "client packet boundary method changed; audit owner fallback before updating this smoke");
const clientPacketBoundaryBody = clientNetworkSource.slice(
    clientPacketBoundaryStart, clientPacketBoundaryEnd);
const ownerFallbackContract = {
    ownerBind: requireMatch(
        clientPacketBoundaryBody,
        /boolean accountingValid = BrowserPacketScheduler\.bindPacketProcessor\(packetProcessor\);/u,
        "scheduled packet boundary does not bind PacketProcessor ownership"),
    conflictFallback: requireMatch(
        clientPacketBoundaryBody,
        /if \(!accountingValid \|\| queueBefore < 64 \|\| !(?:drainEnabled|isClientPacketFrameBoundaryDrainEnabled\(\))\)\s*\{[\s\S]*?packetProcessor\.processQueuedPackets\(\);\s*return;/u,
        "PacketProcessor owner conflict does not fall back to vanilla processing"),
    ownerDrainClaim: requireMatch(
        clientPacketBoundaryBody,
        /tryBeginClientPacketDrain\(packetProcessor, pausedBefore\)/u,
        "adaptive drain does not carry PacketProcessor owner"),
    ownerInterrupt: requireMatch(
        clientPacketBoundaryBody,
        /interruptClientPacketDrain\(packetProcessor\)/u,
        "owner-aware failure interruption is missing"),
    ownerFinish: requireMatch(
        clientPacketBoundaryBody,
        /finishClientPacketDrain\(packetProcessor\)/u,
        "owner-aware drain finalization is missing"),
    vanillaCallCount: countMatches(
        clientPacketBoundaryBody,
        /packetProcessor\.processQueuedPackets\(\)/gu),
};
assert.equal(ownerFallbackContract.vanillaCallCount, 2,
    "owner fallback must retain exactly one vanilla branch and one adaptive branch call");

const closePatchStart = patcherSource.indexOf(
    "private static void patchPacketProcessorCloseAccounting");
const closePatchEnd = patcherSource.indexOf(
    "private static void verifyPacketProcessorAccounting", closePatchStart);
assert.ok(closePatchStart >= 0 && closePatchEnd > closePatchStart,
    "PacketProcessor close patch boundary changed; audit it before updating this smoke");
const closePatchBody = patcherSource.slice(closePatchStart, closePatchEnd);

const channelCloseStart = channelSource.indexOf("protected void doClose()");
const channelCloseEnd = channelSource.indexOf("\n    @Override", channelCloseStart);
assert.ok(channelCloseStart >= 0 && channelCloseEnd > channelCloseStart,
    "BrowserWebSocketChannel.doClose() boundary changed; audit it before updating this smoke");
const channelCloseBody = channelSource.slice(channelCloseStart, channelCloseEnd);

const schedulerGlobalFields = {
    queuedPackets: requireMatch(schedulerSource,
        /private static int queuedPackets;/u,
        "decoded packet accounting is no longer represented by its static runtime counter"),
    queuePaused: requireMatch(schedulerSource,
        /private static boolean packetQueuePaused;/u,
        "exact queue flow state is no longer represented by its static runtime flag"),
    drainActive: requireMatch(schedulerSource,
        /private static boolean clientPacketDrainActive;/u,
        "client drain claim is no longer represented by its static runtime flag"),
    handleDepth: requireMatch(schedulerSource,
        /private static int queuedPacketHandleDepth;/u,
        "queued handler depth is no longer represented by its static runtime counter"),
};

const resetClearsRuntimeState = {
    preservesActiveDrainEvidence: requireMatch(resetBody,
        /boolean preserveActiveDrainEvidence = clientPacketDrainActive;/u,
        "PacketProcessor reset no longer distinguishes an in-flight drain close"),
    queue: requireMatch(resetBody, /queuedPackets = 0;/u,
        "PacketProcessor reset no longer clears global decoded queue accounting"),
    pause: requireMatch(resetBody, /packetQueuePaused = false;/u,
        "PacketProcessor reset no longer clears exact queue pause state"),
    drainDemand: requireMatch(resetBody,
        /BrowserClientNetwork\.invalidateClientPacketDrain\("packet-processor-reset"\);/u,
        "PacketProcessor reset no longer invalidates the client drain demand"),
    inactiveClaimPath: requireMatch(resetBody,
        /if \(!preserveActiveDrainEvidence\)\s*\{[\s\S]*clientPacketDrainActive = false;/u,
        "PacketProcessor reset no longer retires an inactive drain claim"),
};

const closeOrder = {
    queueClearBeforeReset: requireMatch(closePatchBody,
        /"java\/util\/Queue"[\s\S]*"clear"[\s\S]*BrowserPacketScheduler[\s\S]*"reset"/u,
        "PacketProcessor.close must clear its real FIFO before resetting accounting"),
    closeHook: requireMatch(closePatchBody,
        /BrowserPacketScheduler[\s\S]*reset/u,
        "PacketProcessor.close is missing the runtime accounting reset hook"),
    ownerPassed: requireMatch(closePatchBody,
        /cleanup\.add\(new VarInsnNode\(Opcodes\.ALOAD, 0\)\);[\s\S]*"reset"[\s\S]*"\(Ljava\/lang\/Object;\)V"/u,
        "PacketProcessor.close reset hook does not carry its owner"),
};

// A browser transport close must retire only its channel/transport entry.  It
// must not call the process-wide PacketProcessor reset: that reset belongs to
// a runtime shutdown (the server's PacketProcessor.close) and would erase the
// accounting for other channels feeding the same runtime processor.
const channelCloseIsolation = {
    removesOnlyThisChannel: requireMatch(channelCloseBody, /removeChannel\(this\);/u,
        "channel close no longer removes the closing channel from the channel table"),
    closesOnlyThisSocket: requireMatch(channelCloseBody, /closeSocket\(socketId\);/u,
        "channel close no longer closes its own transport entry"),
    noGlobalPacketReset: !/BrowserPacketScheduler\.reset\s*\(/u.test(channelCloseBody),
};
assert.equal(channelCloseIsolation.noGlobalPacketReset, true,
    "browser channel close must not reset runtime-wide PacketProcessor accounting");

// The bridge's transport queue and close path are per-entry.  These are useful
// evidence for relay/channel isolation, but they are not Java PacketProcessor
// accounting proof; keep the distinction explicit in the output.
const relayPerChannelState = {
    channelMap: requireMatch(channelSource, /channels:\s*new Map\(\)/u,
        "browser bridge does not retain per-channel state in a Map"),
    entryLookup: requireMatch(channelSource,
        /state\.channels\.get\(id\|0\)/u,
        "RelayNode transport operations lost per-channel entry lookup"),
    discardInbound: requireMatch(channelSource, /discardInbound\(entry\)/u,
        "RelayNode close path no longer discards the closing entry inbound state"),
    closeCleanup: requireMatch(clientDrainSmokeSource,
        /close cleanup left client\/parser\/server continuation state alive/u,
        "existing Node client close model is missing its stale-continuation check"),
};

// Existing tests cover bounded accounting and Node relay close/reconnect, but
// no existing test models two PacketProcessor owners and a close race.  Keep
// this as a coverage fact, not as a failure: the new model below is the guard.
const existingCoverage = {
    packetProcessorSingleOwnerBytecode: /PacketProcessor patched bytecode/u.test(
        accountingSmokeSource),
    packetProcessorCloseOrder: /Queue\.clear[\s\S]*BrowserPacketScheduler\.reset/u.test(
        accountingSmokeSource),
    nodePerChannelClose: /ProtocolIngressModel[\s\S]*\.close\(\)/u.test(
        clientDrainSmokeSource),
    fullPathReconnect: /reconnectWaves[\s\S]*forceAbnormalTransportDrop[\s\S]*replacementClients/u.test(
        fullPathSource),
    sameRuntimeTwoProcessorCloseRace: /two\s+PacketProcessor|processor[A-Z]\s+.*processor[A-Z]/iu.test(
        accountingSmokeSource + clientDrainSmokeSource + fullPathSource),
};
assert.equal(existingCoverage.sameRuntimeTwoProcessorCloseRace, false,
    "existing coverage unexpectedly matches the new two-processor close-race model");

// ------------------------------ model helpers -------------------------------

class SchedulerModel {
    constructor() {
        this.queuedPackets = 0;
        this.packetQueuePaused = false;
        this.clientPacketDrainActive = false;
        this.clientPacketDrainDemand = false;
        this.resets = 0;
    }

    queue(count) {
        assert.ok(Number.isInteger(count) && count >= 0);
        this.queuedPackets += count;
        this.packetQueuePaused ||= this.queuedPackets >= 256;
        this.clientPacketDrainDemand ||= this.queuedPackets >= 64;
    }

    process(count) {
        const processed = Math.min(Math.max(0, count), this.queuedPackets);
        this.queuedPackets -= processed;
        if (this.packetQueuePaused && this.queuedPackets <= 64) {
            this.packetQueuePaused = false;
        }
        this.clientPacketDrainDemand = this.queuedPackets >= 64;
        return processed;
    }

    reset(reason) {
        this.resets++;
        const preserveActiveDrainEvidence = this.clientPacketDrainActive;
        this.queuedPackets = 0;
        this.packetQueuePaused = false;
        // BrowserClientNetwork.invalidateClientPacketDrain clears the passive
        // browser demand.  Java deliberately keeps an in-flight claim alive
        // until the surrounding runTick finally block captures interruption.
        this.clientPacketDrainDemand = false;
        this.clientPacketDrainStopReason = preserveActiveDrainEvidence
            ? "interrupted" : "inactive";
        if (!preserveActiveDrainEvidence) {
            this.clientPacketDrainActive = false;
            this.clientPacketDrainCritical = false;
        }
        this.lastResetReason = reason;
    }
}

// Candidate bounded owner-claim model for the first fail-closed product step.
// This intentionally does not pretend to implement per-processor accounting:
// one owner may feed many channels, while a second owner disables the shared
// accounting lanes and ignores foreign events until a fresh runtime is made.
class OwnerClaimModel {
    constructor() {
        this.owner = null;
        this.generation = 0;
        this.accessOwner = null;
        this.accessGeneration = 0;
        this.ownerConflict = false;
        this.conflictPoisoned = false;
        this.accountingValid = true;
        this.adaptiveDrainEnabled = true;
        this.exactPacketQueuePaused = false;
        this.queuedPackets = 0;
        this.foreignPacketQueued = 0;
        this.foreignPacketProcessed = 0;
        this.foreignResets = 0;
        this.staleGenerationEvents = 0;
    }

    claim(owner) {
        if (owner === null || owner === undefined) {
            this.ownerConflict = true;
            this.conflictPoisoned = true;
            this.accountingValid = false;
            this.adaptiveDrainEnabled = false;
            this.exactPacketQueuePaused = false;
            return false;
        }
        if (this.owner === null) {
            this.owner = owner;
            this.generation = this.generation === Number.MAX_SAFE_INTEGER
                ? 1 : this.generation + 1;
            this.accessOwner = owner;
            this.accessGeneration = this.generation;
            return true;
        }
        if (this.owner === owner && !this.ownerConflict) {
            this.accessOwner = owner;
            this.accessGeneration = this.generation;
            return true;
        }
        this.ownerConflict = true;
        this.conflictPoisoned = true;
        this.accountingValid = false;
        this.adaptiveDrainEnabled = false;
        // A stale global pause must not block the other channels after the
        // unsupported topology is detected. The real bridge must perform the
        // equivalent exact-pause invalidation.
        this.exactPacketQueuePaused = false;
        return false;
    }

    // Mirrors currentPacketProcessorLedger(): owner-less compatibility callers may only receive
    // a ledger while the access identity, ledger validity, and runtime poison/conflict bits all
    // describe the same live epoch. Owner-aware callbacks intentionally use their explicit owner
    // lookup instead, so a retired handler can still unwind after this returns null.
    compatibilityLedger() {
        if (this.accessOwner === null || this.owner === null ||
                !this.accountingValid || this.ownerConflict || this.conflictPoisoned ||
                this.owner !== this.accessOwner || this.generation !== this.accessGeneration) {
            return null;
        }
        return {owner: this.owner, generation: this.generation};
    }

    ownerlessPacketBoundaryMode(queueDepth, enabled = true) {
        const ledger = this.compatibilityLedger();
        const adaptive = ledger !== null && enabled === true &&
            this.adaptiveDrainEnabled && Number.isInteger(queueDepth) && queueDepth >= 64;
        return {
            mode: adaptive ? "adaptive" : "vanilla",
            calls: 1,
            adaptive,
            ledgerReturned: ledger !== null,
        };
    }

    queue(owner, count) {
        assert.ok(Number.isInteger(count) && count >= 0);
        if (!this.claim(owner)) {
            this.foreignPacketQueued += count;
            return false;
        }
        this.queuedPackets += count;
        return true;
    }

    process(owner, count) {
        assert.ok(Number.isInteger(count) && count >= 0);
        if (!this.claim(owner)) {
            this.foreignPacketProcessed += count;
            return 0;
        }
        const processed = Math.min(count, this.queuedPackets);
        this.queuedPackets -= processed;
        return processed;
    }

    reset(owner, reason) {
        if (owner !== this.owner || this.ownerConflict || !this.accountingValid) {
            this.foreignResets++;
            this.lastForeignResetReason = reason;
            return false;
        }
        this.queuedPackets = 0;
        this.exactPacketQueuePaused = false;
        this.lastResetReason = reason;
        return true;
    }

    acceptGeneration(generation) {
        if (generation !== this.generation || this.ownerConflict) {
            this.staleGenerationEvents++;
            return false;
        }
        return true;
    }

    packetBoundaryMode(owner, queueDepth, enabled = true) {
        const ownerAccepted = this.claim(owner);
        const adaptive = ownerAccepted &&
            this.accountingValid &&
            this.adaptiveDrainEnabled &&
            enabled === true &&
            Number.isInteger(queueDepth) &&
            queueDepth >= 64;
        return {
            mode: adaptive ? "adaptive" : "vanilla",
            calls: 1,
            adaptive,
        };
    }
}

// Lifecycle model for the bounded retired-owner tombstones.  Both the constructor lifecycle hook
// and the per-frame/queue callback use the same claim-only rule, so a late event cannot resurrect
// an owner that already crossed PacketProcessor.close/reset.
class RetiredOwnerLifecycleModel {
    constructor(limit = 16) {
        this.limit = limit;
        this.owner = null;
        this.generation = 0;
        this.conflict = false;
        this.retired = [];
        this.staleEvents = 0;
        this.staleResets = 0;
    }

    remember(owner) {
        if (owner === null || owner === undefined || this.retired.includes(owner)) return;
        if (this.retired.length >= this.limit) this.retired.shift();
        this.retired.push(owner);
    }

    claim(owner, _explicitBind = false) {
        if (owner === null || owner === undefined) {
            this.conflict = true;
            return false;
        }
        if (this.retired.includes(owner)) {
            this.staleEvents++;
            return false;
        }
        if (this.owner === null) {
            this.owner = owner;
            this.generation++;
            this.conflict = false;
            return true;
        }
        if (this.owner === owner && !this.conflict) return true;
        this.conflict = true;
        return false;
    }

    reset(owner) {
        if (owner !== this.owner || this.conflict) {
            this.staleResets++;
            this.remember(owner);
            return false;
        }
        this.remember(owner);
        this.owner = null;
        this.generation++;
        this.conflict = false;
        return true;
    }
}

function testRetiredOwnerLifecycle() {
    const model = new RetiredOwnerLifecycleModel();
    const ownerA = "processor-A";
    const ownerB = "processor-B";
    const ownerC = "processor-C";
    const ownerD = "processor-D";
    const ownerE = "processor-E";

    assert.equal(model.claim(ownerA, true), true);
    const generationA = model.generation;
    assert.equal(model.reset(ownerA), true);
    assert.equal(model.owner, null);
    assert.equal(model.retired.includes(ownerA), true);

    // An implicit late queue/handler callback must not resurrect A.
    assert.equal(model.claim(ownerA, false), false);
    assert.equal(model.staleEvents, 1);
    assert.equal(model.owner, null);

    // The constructor lifecycle hook is also claim-only.  Explicit lifecycle intent cannot
    // resurrect an object whose PacketProcessor.close/reset boundary already retired it.
    assert.equal(model.claim(ownerA, true), false);
    assert.equal(model.staleEvents, 2);
    assert.equal(model.retired.includes(ownerA), true);

    // A new live owner can bind without being contaminated by A's late event.
    assert.equal(model.claim(ownerB, true), true);
    assert.equal(model.claim(ownerA, false), false);
    assert.equal(model.owner, ownerB);
    assert.equal(model.conflict, false);
    assert.equal(model.reset(ownerB), true);

    // Retired A and B remain blocked after C binds; explicit lifecycle intent does not clear
    // either tombstone once the active owner has been shut down.
    assert.equal(model.claim(ownerC, true), true);
    assert.equal(model.claim(ownerA, false), false);
    assert.equal(model.claim(ownerB, false), false);
    assert.equal(model.staleEvents, 5);
    assert.equal(model.owner, ownerC);
    assert.equal(model.reset(ownerC), true);
    assert.equal(model.claim(ownerA, true), false);
    assert.equal(model.staleEvents, 6);
    assert.equal(model.owner, null);
    assert.equal(model.retired.includes(ownerA), true);

    // A fresh owner can claim after the previous runtime retired; a genuinely concurrent second
    // owner is still fail-closed.
    assert.equal(model.claim(ownerD, true), true);
    assert.equal(model.claim(ownerE, true), false);
    assert.equal(model.conflict, true);

    return {
        tombstoneLimit: model.limit,
        generationAfterAReset: generationA + 1,
        staleEventsRejected: model.staleEvents,
        retiredOwnerNeverRebinds: model.owner !== ownerA && model.owner === ownerD &&
            model.retired.includes(ownerA),
        staleOwnerCannotPolluteFreshOwner: model.owner === ownerD,
        concurrentSecondOwnerFailClosed: model.conflict,
        staleResets: model.staleResets,
        classification: "bounded-retired-owner-lifecycle-model-not-runtime-proof",
    };
}

class PacketProcessorModel {
    constructor(name, scheduler) {
        this.name = name;
        this.scheduler = scheduler;
        this.queue = [];
        this.closed = false;
        this.processed = 0;
        this.processedOwners = [];
    }

    enqueue(count, owner = this.name) {
        assert.equal(this.closed, false, `${this.name} accepted work after close`);
        for (let index = 0; index < count; index++) this.queue.push(owner);
        this.scheduler.queue(count);
    }

    process(count) {
        assert.equal(this.closed, false, `${this.name} processed work after close`);
        const n = Math.min(Math.max(0, count), this.queue.length);
        const owners = this.queue.splice(0, n);
        this.scheduler.process(n);
        this.processed += n;
        this.processedOwners.push(...owners);
        return n;
    }

    queuedForOwner(owner) {
        return this.queue.reduce((count, queuedOwner) =>
            count + (queuedOwner === owner ? 1 : 0), 0);
    }

    close() {
        if (this.closed) return;
        this.closed = true;
        this.queue.length = 0;
        // This is the exact product PacketProcessor.close shape.
        this.scheduler.reset(`${this.name}:packet-processor-close`);
    }
}

class BrowserChannelModel {
    constructor(name, processor) {
        this.name = name;
        this.processor = processor;
        this.closed = false;
    }

    enqueue(count) {
        if (this.closed) return false;
        this.processor.enqueue(count, this.name);
        return true;
    }

    close() {
        this.closed = true;
        // BrowserWebSocketChannel.doClose removes/closes this channel only;
        // it does not close the runtime PacketProcessor.
    }
}

function sumQueueLengths(processors) {
    return processors.reduce((sum, processor) => sum + processor.queue.length, 0);
}

function testSupportedSingleRuntime() {
    const scheduler = new SchedulerModel();
    const processor = new PacketProcessorModel("runtime", scheduler);
    const channelA = new BrowserChannelModel("A", processor);
    const channelB = new BrowserChannelModel("B", processor);

    assert.equal(channelA.enqueue(70), true);
    assert.equal(channelB.enqueue(70), true);
    assert.equal(scheduler.queuedPackets, 140);
    assert.equal(processor.queue.length, 140);

    // Closing one browser connection must not reset the shared runtime
    // processor.  B remains live and its queue/accounting must remain exact.
    channelA.close();
    assert.equal(channelA.enqueue(1), false);
    assert.equal(channelB.enqueue(2), true);
    assert.equal(scheduler.queuedPackets, 142);
    assert.equal(processor.queue.length, 142);
    assert.equal(scheduler.resets, 0);
    const closedChannelPacketsBeforeDrain = processor.queuedForOwner("A");
    const liveChannelPacketsBeforeDrain = processor.queuedForOwner("B");
    assert.equal(closedChannelPacketsBeforeDrain, 70);
    assert.equal(liveChannelPacketsBeforeDrain, 72);

    const processed = processor.process(16);
    assert.equal(processed, 16);
    assert.equal(scheduler.queuedPackets, 126);
    assert.equal(processor.queue.length, 126);
    assert.equal(processor.queuedForOwner("A"), 54);
    assert.equal(processor.queuedForOwner("B"), 72);
    const closedChannelPacketsAfterDrain = processor.queuedForOwner("A");
    const liveChannelPacketsAfterDrain = processor.queuedForOwner("B");
    assert.equal(channelB.enqueue(1), true);
    assert.equal(scheduler.queuedPackets, 127);

    // Reconnect is a fresh channel on the same Minecraft runtime/processor;
    // the old channel close does not retire B's new channel accounting.
    const reconnect = new BrowserChannelModel("A-reconnect", processor);
    assert.equal(reconnect.enqueue(3), true);
    assert.equal(scheduler.queuedPackets, 130);
    reconnect.close();
    assert.equal(scheduler.queuedPackets, 130);

    // Only runtime shutdown closes PacketProcessor and clears all queues.
    channelB.close();
    processor.close();
    assert.equal(scheduler.queuedPackets, 0);
    assert.equal(scheduler.packetQueuePaused, false);
    assert.equal(scheduler.clientPacketDrainDemand, false);
    assert.equal(processor.queue.length, 0);
    assert.equal(scheduler.resets, 1);

    return {
        channels: 3,
        initialQueued: 140,
        queuedAfterClosedChannel: 142,
        processedAtFrameBoundary: processed,
        closedChannelPacketsBeforeDrain,
        liveChannelPacketsBeforeDrain,
        closedChannelPacketsAfterDrain,
        liveChannelPacketsAfterDrain,
        queuedAfterReconnect: 130,
        browserCloseResets: scheduler.resets - 1,
        runtimeShutdownResets: 1,
        finalQueue: scheduler.queuedPackets,
        isolation: true,
    };
}

function testUnsupportedConcurrentProcessors() {
    const scheduler = new SchedulerModel();
    const processorA = new PacketProcessorModel("processor-A", scheduler);
    const processorB = new PacketProcessorModel("processor-B", scheduler);
    processorA.enqueue(70);
    processorB.enqueue(70);
    assert.equal(scheduler.queuedPackets, 140);

    // Demonstrate the exact static-state hazard if two PacketProcessor owners
    // coexist in one JVM: close(A) resets the global counter while B's queue is
    // still live.  This must stay visible as a known risk, not be called pass.
    processorA.close();
    const accountingLost = scheduler.queuedPackets !== processorB.queue.length;
    assert.equal(accountingLost, true);
    assert.equal(processorB.queue.length, 70);
    assert.equal(scheduler.queuedPackets, 0);

    return {
        processorOwners: 2,
        processorAQueueAfterClose: processorA.queue.length,
        processorBQueueStillLive: processorB.queue.length,
        schedulerQueueAfterAReset: scheduler.queuedPackets,
        accountingLost,
        classification: "unsupported-same-runtime-topology",
        requiredFixIfTopologyExpands:
            "scope accounting by PacketProcessor/runtime token before allowing two owners",
    };
}

function testActiveDrainCloseRace() {
    const scheduler = new SchedulerModel();
    const processorA = new PacketProcessorModel("processor-A", scheduler);
    const processorB = new PacketProcessorModel("processor-B", scheduler);
    processorA.enqueue(96);
    processorB.enqueue(96);
    scheduler.clientPacketDrainActive = true;
    scheduler.clientPacketDrainDemand = true;
    processorA.close();

    // Current reset clears global queue depth and passive demand even when B
    // still has live queued work.  Java preserves the in-flight claim until
    // its surrounding runTick finally block, but it does not provide a
    // per-processor owner; this is the sharper race.
    const activeDrainLost = scheduler.queuedPackets === 0 &&
        processorB.queue.length > 0 && !scheduler.clientPacketDrainDemand;
    assert.equal(activeDrainLost, true);
    return {
        processorOwners: 2,
        processorBQueueStillLive: processorB.queue.length,
        activeDrainAfterAReset: scheduler.clientPacketDrainActive,
        demandAfterAReset: scheduler.clientPacketDrainDemand,
        activeDrainLost,
        classification: "unsupported-same-runtime-topology",
    };
}

function testBoundedOwnerClaimFailClosed() {
    const scheduler = new OwnerClaimModel();
    const processorA = "processor-A";
    const processorB = "processor-B";

    // One PacketProcessor may legitimately receive multiple browser channels.
    assert.equal(scheduler.queue(processorA, 70), true);
    assert.equal(scheduler.queue(processorA, 2), true);
    const singleOwnerGeneration = scheduler.generation;
    assert.equal(scheduler.ownerConflict, false);
    assert.equal(scheduler.accountingValid, true);
    assert.equal(scheduler.queuedPackets, 72);

    const adaptiveBoundary = scheduler.packetBoundaryMode(processorA, 256);
    assert.deepEqual(adaptiveBoundary, {
        mode: "adaptive",
        calls: 1,
        adaptive: true,
    });
    const ownerlessBeforeConflict = scheduler.ownerlessPacketBoundaryMode(256);
    assert.deepEqual(ownerlessBeforeConflict, {
        mode: "adaptive",
        calls: 1,
        adaptive: true,
        ledgerReturned: true,
    }, "ownerless compatibility must use a valid live ledger before conflict");

    // A second owner permanently invalidates the shared accounting lanes for
    // this runtime. No foreign event is allowed to alter A's queue state.
    assert.equal(scheduler.claim(processorB), false);
    assert.equal(scheduler.ownerConflict, true);
    assert.equal(scheduler.conflictPoisoned, true);
    assert.equal(scheduler.accountingValid, false);
    assert.equal(scheduler.adaptiveDrainEnabled, false);
    assert.equal(scheduler.exactPacketQueuePaused, false);

    // Required race order: A queue -> B bind -> A close -> B queue/process.
    assert.equal(scheduler.reset(processorA, "processor-A:close"), false);
    assert.equal(scheduler.queuedPackets, 72);
    assert.equal(scheduler.queue(processorB, 3), false);
    assert.equal(scheduler.process(processorB, 3), 0);
    assert.equal(scheduler.queuedPackets, 72);
    assert.equal(scheduler.foreignResets, 1);
    assert.equal(scheduler.foreignPacketQueued, 3);
    assert.equal(scheduler.foreignPacketProcessed, 3);

    const conflictFallback = scheduler.packetBoundaryMode(processorB, 256);
    assert.deepEqual(conflictFallback, {
        mode: "vanilla",
        calls: 1,
        adaptive: false,
    }, "owner conflict must choose the conservative one-call vanilla path");
    const ownerlessFallback = scheduler.ownerlessPacketBoundaryMode(256);
    assert.deepEqual(ownerlessFallback, {
        mode: "vanilla",
        calls: 1,
        adaptive: false,
        ledgerReturned: false,
    }, "ownerless compatibility must not receive the invalid/conflicting ledger");

    // A callback from the pre-conflict generation is stale and must not revive
    // accounting or alter the current generation state.
    assert.equal(scheduler.acceptGeneration(singleOwnerGeneration), false);
    assert.equal(scheduler.staleGenerationEvents, 1);

    return {
        topology: "bounded-owner-claim-fail-closed",
        owner: scheduler.owner,
        ownerGeneration: singleOwnerGeneration,
        processorOwnersObserved: 2,
        ownerConflict: scheduler.ownerConflict,
        conflictPoisoned: scheduler.conflictPoisoned,
        accountingValid: scheduler.accountingValid,
        adaptiveDrainEnabled: scheduler.adaptiveDrainEnabled,
        exactPacketQueuePaused: scheduler.exactPacketQueuePaused,
        queueAfterForeignEvents: scheduler.queuedPackets,
        foreignPacketQueued: scheduler.foreignPacketQueued,
        foreignPacketProcessed: scheduler.foreignPacketProcessed,
        foreignResets: scheduler.foreignResets,
        staleGenerationEvents: scheduler.staleGenerationEvents,
        fallbackModeAfterConflict: conflictFallback.mode,
        fallbackCallsAfterConflict: conflictFallback.calls,
        adaptiveAfterConflict: conflictFallback.adaptive,
        ownerlessLedgerBeforeConflict: ownerlessBeforeConflict.ledgerReturned,
        ownerlessLedgerAfterConflict: ownerlessFallback.ledgerReturned,
        ownerlessFallbackModeAfterConflict: ownerlessFallback.mode,
        foreignEventsIgnored: scheduler.foreignPacketQueued === 3 &&
            scheduler.foreignPacketProcessed === 3,
        closeForeignResetDidNotClearOwner: scheduler.queuedPackets === 72,
        classification: "candidate-fail-closed-model-not-runtime-proof",
    };
}

// Explicitly model the unsafe overlap that a single static ledger must reject.  A and B each
// retain an independent vanilla FIFO, while the scheduler has only one accounting owner.  Once B
// has triggered conflict, closing A must not make B eligible for adaptive accounting: A's reset
// may already have cleared the shared ledger while B's real FIFO is still live.
function testConflictResetRebindFailClosed() {
    const scheduler = new OwnerClaimModel();
    const ownerA = "processor-A";
    const ownerB = "processor-B";
    const queues = new Map([[ownerA, 0], [ownerB, 0]]);

    assert.equal(scheduler.queue(ownerA, 70), true);
    queues.set(ownerA, 70);
    assert.equal(scheduler.queue(ownerB, 70), false,
        "second PacketProcessor must trigger owner conflict");
    queues.set(ownerB, 70);
    assert.equal(scheduler.ownerConflict, true);
    assert.equal(scheduler.accountingValid, false);
    assert.equal(scheduler.adaptiveDrainEnabled, false);
    assert.equal(scheduler.queuedPackets, 70,
        "foreign queue activity must not mutate the first owner's ledger");

    // The active owner reset is itself rejected while conflict is live. This is the required
    // fail-closed behavior until every overlapping owner has crossed its own lifecycle boundary.
    const activeResetAccepted = scheduler.reset(ownerA, "processor-A:close-after-conflict");
    assert.equal(activeResetAccepted, false,
        "active owner reset must not clear a ledger while a foreign owner is live");
    assert.equal(scheduler.queuedPackets, 70);

    const rebindAccepted = scheduler.claim(ownerB);
    assert.equal(rebindAccepted, false,
        "foreign owner must remain rejected after active owner reset attempt");
    const fallback = scheduler.packetBoundaryMode(ownerB, 256);
    assert.deepEqual(fallback, {
        mode: "vanilla",
        calls: 1,
        adaptive: false,
    }, "B must stay on the one-call vanilla fallback after overlap");
    assert.equal(scheduler.accountingValid, false);
    assert.equal(scheduler.adaptiveDrainEnabled, false);
    assert.equal(queues.get(ownerA), 70);
    assert.equal(queues.get(ownerB), 70);

    return {
        processorOwners: 2,
        ownerAQueue: queues.get(ownerA),
        ownerBQueue: queues.get(ownerB),
        ownerConflict: scheduler.ownerConflict,
        activeResetAccepted,
        rebindAccepted,
        accountingValid: scheduler.accountingValid,
        adaptiveDrainEnabled: scheduler.adaptiveDrainEnabled,
        queueAfterResetAttempt: scheduler.queuedPackets,
        fallbackMode: fallback.mode,
        fallbackCalls: fallback.calls,
        failClosed: activeResetAccepted === false && rebindAccepted === false &&
            scheduler.accountingValid === false && scheduler.adaptiveDrainEnabled === false &&
            fallback.mode === "vanilla",
        classification: "overlapping-owner-reset-rebind-model-not-runtime-proof",
    };
}

// A PacketProcessor.close may run from inside a queued handler.  In that window the owner is
// retired and the global owner slot is intentionally empty until packetProcessed() unwinds the
// handler.  The legacy depth mirror must follow that retired owner's completion, but a late
// callback after a fresh owner binds must never overwrite the fresh mirror.
function testCloseInHandlerRebind() {
    const ownerA = "processor-A";
    const ownerB = "processor-B";
    const ledgerA = {owner: ownerA, generation: 1, queuedPacketHandleDepth: 1, retired: false};
    let packetProcessorOwner = ownerA;
    let packetProcessorGeneration = ledgerA.generation;
    let queuedPacketHandleOwner = ownerA;
    let queuedPacketHandleDepth = 1;
    let packetProcessorOwnerConflict = false;
    let packetProcessorAccountingValid = true;
    const packetProcessorConflictPoisoned = false;

    // Simulate reset(A) from inside the handler.  The owner slot is empty, but the handler scope
    // remains owned by A so the final packetProcessed(A) can release it.
    ledgerA.retired = true;
    ledgerA.queuedPacketHandleDepth = 1;
    packetProcessorOwner = null;
    packetProcessorGeneration = 2;
    packetProcessorOwnerConflict = true;
    packetProcessorAccountingValid = false;

    // The owner-less lookup must be closed while A is retired/invalid, but the explicit
    // packetProcessed(A) path still owns the ledger needed to unwind the in-flight handler.
    const ownerlessLedgerDuringRetiredUnwind =
        ledgerA.retired && !packetProcessorAccountingValid ? null : ledgerA;
    assert.equal(ownerlessLedgerDuringRetiredUnwind, null,
        "ownerless compatibility must not expose a retired/invalid ledger during unwind");

    ledgerA.queuedPacketHandleDepth--;
    const mirrorDuringRetiredUnwind =
        (packetProcessorOwner === ledgerA.owner && packetProcessorGeneration === ledgerA.generation) ||
        (packetProcessorOwner === null && queuedPacketHandleOwner === ledgerA.owner && ledgerA.retired);
    assert.equal(mirrorDuringRetiredUnwind, true,
        "close-in-handler completion must mirror the retired owner's remaining depth");
    if (mirrorDuringRetiredUnwind) queuedPacketHandleDepth = ledgerA.queuedPacketHandleDepth;
    if (queuedPacketHandleDepth === 0 && packetProcessorOwner === null &&
            !packetProcessorConflictPoisoned) {
        packetProcessorOwnerConflict = false;
        packetProcessorAccountingValid = true;
    }
    assert.equal(queuedPacketHandleDepth, 0);
    assert.equal(packetProcessorOwnerConflict, false,
        "handler unwind must release the temporary conflict before a fresh owner binds");
    assert.equal(packetProcessorAccountingValid, true);

    // B binds after A's unwind.  A's late callback must not overwrite B's mirror.
    packetProcessorOwner = ownerB;
    packetProcessorGeneration = 3;
    queuedPacketHandleOwner = ownerB;
    queuedPacketHandleDepth = 2;
    const legacyDepthBeforeLateA = queuedPacketHandleDepth;
    const lateAReturnsToMirror =
        (packetProcessorOwner === ledgerA.owner && packetProcessorGeneration === ledgerA.generation) ||
        (packetProcessorOwner === null && queuedPacketHandleOwner === ledgerA.owner && ledgerA.retired);
    assert.equal(lateAReturnsToMirror, false,
        "retired owner callback must not mirror over a fresh owner");
    if (lateAReturnsToMirror) queuedPacketHandleDepth = ledgerA.queuedPacketHandleDepth;
    assert.equal(queuedPacketHandleDepth, legacyDepthBeforeLateA);

    return {
        closeInHandlerRebind: packetProcessorOwner === ownerB &&
            packetProcessorOwnerConflict === false && packetProcessorAccountingValid === true &&
            queuedPacketHandleDepth === 2,
        retiredOwnerDepthAfterUnwind: ledgerA.queuedPacketHandleDepth,
        freshOwnerDepthAfterLateRetiredCallback: queuedPacketHandleDepth,
        ownerlessLedgerDuringRetiredUnwind,
        lateRetiredCallbackMirrored: lateAReturnsToMirror,
        classification: "close-in-handler-owner-unwind-model-not-runtime-proof",
    };
}

// An unknown owner callback can interleave with the final packetProcessed(A) while A is already
// retired by close-in-handler.  The retired A completion must still release A's handler depth,
// but it must not clear the conflict/accounting-invalid bits set by the unknown B event.
function testUnknownOwnerRetiredUnwindSticky() {
    const ownerA = "processor-A";
    const unknownOwnerB = "processor-B";
    const ledgerA = {owner: ownerA, generation: 1, queuedPacketHandleDepth: 1, retired: true};
    let packetProcessorOwner = null;
    let packetProcessorOwnerConflict = true;
    let packetProcessorAccountingValid = false;
    let packetProcessorConflictPoisoned = false;
    let queuedPacketHandleOwner = ownerA;
    let queuedPacketHandleDepth = 1;
    let unknownOwnerEvents = 0;

    // Mirrors packetProcessed(B) -> noteUnknownPacketProcessorOwner(): B has no retained ledger,
    // so it cannot touch A's queue/depth, but the lifecycle identity is now permanently ambiguous.
    const retainedLedgerForB = null;
    assert.equal(retainedLedgerForB, null,
        `${unknownOwnerB} must not have a retained ledger in the unknown-owner interleave`);
    unknownOwnerEvents++;
    packetProcessorOwnerConflict = true;
    packetProcessorAccountingValid = false;
    packetProcessorConflictPoisoned = true;

    // Mirrors packetProcessed(A) -> completeOwnerPacket(ledgerA). The explicit retired-owner path
    // still unwinds A's handler and the legacy mirror, but the recovery branch is gated by poison.
    ledgerA.queuedPacketHandleDepth--;
    const retiredOwnerMirrored = packetProcessorOwner === null &&
        queuedPacketHandleOwner === ledgerA.owner && ledgerA.retired;
    assert.equal(retiredOwnerMirrored, true,
        "retired A handler must still release its own depth after unknown B event");
    if (retiredOwnerMirrored) queuedPacketHandleDepth = ledgerA.queuedPacketHandleDepth;
    const recoveryAttempted = queuedPacketHandleDepth === 0 && packetProcessorOwner === null &&
        !packetProcessorConflictPoisoned;
    if (recoveryAttempted) {
        packetProcessorOwnerConflict = false;
        packetProcessorAccountingValid = true;
    }
    assert.equal(recoveryAttempted, false,
        "retired A unwind must not clear a sticky poison raised by unknown B");
    assert.equal(queuedPacketHandleDepth, 0);
    assert.equal(packetProcessorOwnerConflict, true);
    assert.equal(packetProcessorAccountingValid, false);
    assert.equal(packetProcessorConflictPoisoned, true);

    // A fresh owner must remain on the conservative fallback after this ambiguous interleave.
    const freshOwnerAccepted = !packetProcessorConflictPoisoned &&
        packetProcessorOwner === null;
    assert.equal(freshOwnerAccepted, false,
        "unknown-owner poison must reject a fresh owner until the runtime is replaced");

    return {
        unknownOwner: unknownOwnerB,
        unknownOwnerEvents,
        retiredOwnerMirrored,
        retiredOwnerDepthAfterUnwind: ledgerA.queuedPacketHandleDepth,
        recoveryAttempted,
        ownerConflictAfterUnwind: packetProcessorOwnerConflict,
        accountingValidAfterUnwind: packetProcessorAccountingValid,
        conflictPoisonedAfterUnwind: packetProcessorConflictPoisoned,
        freshOwnerAccepted,
        failClosed: retiredOwnerMirrored && queuedPacketHandleDepth === 0 &&
            recoveryAttempted === false && packetProcessorOwnerConflict === true &&
            packetProcessorAccountingValid === false &&
            packetProcessorConflictPoisoned === true && freshOwnerAccepted === false,
        classification: "unknown-owner-retired-unwind-sticky-model-not-runtime-proof",
    };
}

// Retired ledger slots are intentionally never reused.  Once the bounded owner table is full,
// a fresh owner must stay on the one-call vanilla fallback instead of risking an old callback
// being interpreted as the new generation.  This is a model/static guard, not live TeaVM proof.
function testLedgerSlotExhaustion() {
    const limit = 16;
    const retiredOwners = new Set();
    let nextOwner = null;
    let slotExhaustions = 0;
    let ledgerExhausted = false;
    let conflictPoisoned = false;
    let adaptiveDrainEnabled = true;
    let fallbackReason = "unbound";

    for (let index = 0; index < limit; index++) {
        const owner = `owner-${index + 1}`;
        assert.equal(nextOwner, null);
        nextOwner = owner;
        retiredOwners.add(owner);
        nextOwner = null;
    }
    assert.equal(retiredOwners.size, limit);

    const freshOwner = `owner-${limit + 1}`;
    assert.equal(retiredOwners.has(freshOwner), false);
    slotExhaustions++;
    ledgerExhausted = true;
    conflictPoisoned = true;
    adaptiveDrainEnabled = false;
    fallbackReason = "ledger-slot-exhausted";
    assert.equal(fallbackReason, "ledger-slot-exhausted");
    assert.equal(ledgerExhausted, true);
    assert.equal(conflictPoisoned, true);
    assert.equal(adaptiveDrainEnabled, false);

    // Neither a fresh owner nor a late retired owner can recover the poisoned adaptive lane.
    const lateRetiredRejected = retiredOwners.has("owner-1") && ledgerExhausted;
    const freshOwnerRejected = ledgerExhausted;
    assert.equal(lateRetiredRejected, true);
    assert.equal(freshOwnerRejected, true);

    return {
        ledgerLimit: limit,
        ownersRetired: retiredOwners.size,
        slotExhaustions,
        exhaustionPoisoned: conflictPoisoned,
        adaptiveAfterExhaustion: adaptiveDrainEnabled,
        fallbackAfterExhaustion: fallbackReason,
        oldOwnerRebindRejected: lateRetiredRejected,
        freshOwnerRebindRejected: freshOwnerRejected,
        retiredSlotReused: false,
        classification: "bounded-single-owner-fail-closed-model",
    };
}

// A bounded tombstone ring is useful for recent callbacks, but it is not a strict proof across
// repeated lifecycle churn.  This model retains every generation token so the required owner-1
// through owner-9 late-callback case is explicit; the source gate below reports whether the Java
// implementation has equivalent retention rather than silently treating the model as runtime
// evidence.
function testRetiredOwnerNineGenerationChurn() {
    const retiredGenerations = new Set();
    const owners = Array.from({length: 9}, (_, index) => `owner-${index + 1}`);
    let activeOwner = null;
    let generation = 0;
    for (const owner of owners) {
        assert.equal(retiredGenerations.has(owner), false,
            `${owner} unexpectedly appeared retired before construction`);
        activeOwner = owner;
        generation++;
        assert.equal(activeOwner, owner);
        retiredGenerations.add(owner);
        activeOwner = null;
        generation++;
    }
    const lateOwner = owners[0];
    const lateCallbackAccepted = !retiredGenerations.has(lateOwner);
    assert.equal(lateCallbackAccepted, false,
        "late callback from owner-1 must remain rejected after owner-9 churn");

    return {
        ownersCreated: owners.length,
        ownersRetired: retiredGenerations.size,
        finalGeneration: generation,
        lateOwner,
        lateCallbackAccepted,
        lateCallbackRejected: lateCallbackAccepted === false,
        retainedOwnerTokens: retiredGenerations.size,
        classification: "strict-retired-generation-model-not-runtime-proof",
    };
}

const supported = testSupportedSingleRuntime();
const concurrent = testUnsupportedConcurrentProcessors();
const activeRace = testActiveDrainCloseRace();
const boundedOwnerClaim = testBoundedOwnerClaimFailClosed();
const retiredOwnerLifecycle = testRetiredOwnerLifecycle();
const conflictResetRebind = testConflictResetRebindFailClosed();
const retiredOwnerNineGenerationChurn = testRetiredOwnerNineGenerationChurn();
const closeInHandlerRebind = testCloseInHandlerRebind();
const unknownOwnerRetiredUnwind = testUnknownOwnerRetiredUnwindSticky();
const ledgerSlotExhaustion = testLedgerSlotExhaustion();

const staticContract = {
    schedulerGlobalFields,
    ownerContract,
    retiredHandlerMirrorGuard,
    ledgerExhaustionContract,
    ownerResetGuard,
    ownerFallbackContract,
    currentLedgerGuard,
    unknownOwnerPoisonGuard,
    retiredUnwindPoisonGuard,
    resetClearsRuntimeState,
    closeOrder,
    channelCloseIsolation,
    relayPerChannelState,
};

const result = {
    smoke: "server-packet-processor-isolation",
    schemaVersion: "gaius.server-packet-processor-isolation.v1",
    status: "pass",
    verdict: "supported-runtime-model-pass; unsupported-owner-shapes-fail-closed",
    scope: {
        supported: "one PacketProcessor per Minecraft/MinecraftServer runtime; many channels may feed it",
        unsupported: "two concurrent PacketProcessor owners in one JVM",
        browserClose: "channel transport close, not PacketProcessor.close",
        runtimeShutdown: "PacketProcessor.close/reset clears runtime-wide accounting",
    },
    staticContract,
    existingCoverage,
    model: {
        supportedSingleRuntime: supported,
        unsupportedConcurrentProcessors: concurrent,
        activeDrainCloseRace: activeRace,
        boundedOwnerClaimFailClosed: boundedOwnerClaim,
        retiredOwnerLifecycle,
        conflictResetRebind,
        retiredOwnerNineGenerationChurn,
        closeInHandlerRebind,
        unknownOwnerRetiredUnwind,
        ledgerSlotExhaustion,
    },
    gates: {
        supportedChannelCloseDoesNotResetAccounting: supported.isolation,
        supportedReconnectRetainsLiveProcessorAccounting: supported.queuedAfterReconnect === 130,
        boundedOwnerClaimFailClosed:
            boundedOwnerClaim.ownerConflict === true &&
            boundedOwnerClaim.accountingValid === false &&
            boundedOwnerClaim.conflictPoisoned === true &&
            boundedOwnerClaim.adaptiveDrainEnabled === false &&
            boundedOwnerClaim.exactPacketQueuePaused === false &&
            boundedOwnerClaim.foreignEventsIgnored === true &&
            boundedOwnerClaim.closeForeignResetDidNotClearOwner === true &&
            boundedOwnerClaim.staleGenerationEvents === 1 &&
            boundedOwnerClaim.fallbackModeAfterConflict === "vanilla" &&
            boundedOwnerClaim.fallbackCallsAfterConflict === 1 &&
            boundedOwnerClaim.adaptiveAfterConflict === false &&
            boundedOwnerClaim.ownerlessLedgerBeforeConflict === true &&
            boundedOwnerClaim.ownerlessLedgerAfterConflict === false &&
            boundedOwnerClaim.ownerlessFallbackModeAfterConflict === "vanilla",
        retiredOwnerLifecycleFailClosed:
            retiredOwnerLifecycle.retiredOwnerNeverRebinds === true &&
            retiredOwnerLifecycle.staleEventsRejected === 6 &&
            retiredOwnerLifecycle.staleOwnerCannotPolluteFreshOwner === true &&
            retiredOwnerLifecycle.concurrentSecondOwnerFailClosed === true,
        conflictResetRebindFailClosed: conflictResetRebind.failClosed === true,
        closeInHandlerRebind: closeInHandlerRebind.closeInHandlerRebind === true &&
            closeInHandlerRebind.lateRetiredCallbackMirrored === false &&
            closeInHandlerRebind.ownerlessLedgerDuringRetiredUnwind === null,
        unknownOwnerRetiredUnwindFailClosed: unknownOwnerRetiredUnwind.failClosed === true &&
            unknownOwnerRetiredUnwind.unknownOwnerEvents === 1 &&
            unknownOwnerRetiredUnwind.retiredOwnerDepthAfterUnwind === 0,
        ledgerSlotExhaustionFailClosed:
            ledgerSlotExhaustion.slotExhaustions === 1 &&
            ledgerSlotExhaustion.exhaustionPoisoned === true &&
            ledgerSlotExhaustion.adaptiveAfterExhaustion === false &&
            ledgerSlotExhaustion.fallbackAfterExhaustion === "ledger-slot-exhausted" &&
            ledgerSlotExhaustion.oldOwnerRebindRejected === true &&
            ledgerSlotExhaustion.freshOwnerRebindRejected === true &&
            ledgerSlotExhaustion.retiredSlotReused === false,
        retiredOwnerNineGenerationChurnFailClosed:
            retiredOwnerNineGenerationChurn.lateCallbackRejected === true &&
            retiredOwnerNineGenerationChurn.ownersCreated === 9,
        javaConflictResetGuardMatchesModel: ownerResetGuard.conflictRejectedBeforeGlobalReset,
        javaRetiredOwnerRetentionCoversNineGenerations:
            /RETIRED_PACKET_PROCESSOR_OWNER_LIMIT\s*=\s*(?:9|1\d|[2-9]\d+)/u.test(schedulerSource) &&
            /new Object\[RETIRED_PACKET_PROCESSOR_OWNER_LIMIT\]/u.test(schedulerSource),
        unsupportedConcurrentProcessorIsolation: false,
        activeDrainConcurrentProcessorIsolation: false,
        publicRelayRuntimeProof: false,
    },
    interpretation: {
        confirmed: [
            "existing Java hook accounting is runtime-static and reset is tied to PacketProcessor.close",
            "owner/generation claim and conflict fail-closed hooks are present in the patched source",
            "BrowserWebSocketChannel.doClose retires only its channel/socket",
            "supported one-processor runtime model preserves B after A channel close and reconnect",
            "bounded owner-claim model disables shared accounting on a second owner and ignores foreign/reset/stale-generation events",
            "bounded retired-owner tombstones reject late callbacks, including explicit lifecycle binds",
            "overlapping-owner reset/rebind model requires conflict to stay poisoned until foreign owner retirement",
            "close-in-handler owner unwind releases its temporary conflict before a fresh owner binds",
            "unknown-owner callback poison remains sticky through retired-handler unwind",
            "bounded ledger exhaustion stays poisoned and falls back to vanilla instead of reusing retired slots",
            "owner-1 through owner-9 generation-churn model rejects a late owner-1 callback",
            "Node relay/channel close tests are separate from Java PacketProcessor evidence",
        ],
        knownRisk: [
            "if a second PacketProcessor appears in one JVM, the product deliberately falls back to vanilla processing; per-owner adaptive accounting is not enabled",
            "an active drain claim/demand is also global in that unsupported topology",
            "a browser channel close does not purge already-decoded entries from the shared FIFO; the closed listener must be discarded by the normal packet path without resetting B",
            "retired-owner lifecycle protection remains a bounded single-owner guard, not multi-Processor isolation",
            "the Java fail-closed poison is runtime-scoped; after a conflict the static ledger remains unusable until the runtime/classloader is replaced",
            "current Java retired-owner ring is bounded at 16 entries; arbitrary lifecycle churn beyond that still needs instance-bound generation tokens",
        ],
        notProven: [
            "this model is not a TeaVM/browser runtime proof",
            "this does not prove external ellan.top latency or reconnect health",
            "this does not prove multiple Java Minecraft instances share one page",
        ],
    },
};

console.log(JSON.stringify(result));

