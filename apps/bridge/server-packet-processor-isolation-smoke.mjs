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

const supported = testSupportedSingleRuntime();
const concurrent = testUnsupportedConcurrentProcessors();
const activeRace = testActiveDrainCloseRace();

const staticContract = {
    schedulerGlobalFields,
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
    },
    gates: {
        supportedChannelCloseDoesNotResetAccounting: supported.isolation,
        supportedReconnectRetainsLiveProcessorAccounting: supported.queuedAfterReconnect === 130,
        unsupportedConcurrentProcessorIsolation: false,
        activeDrainConcurrentProcessorIsolation: false,
        publicRelayRuntimeProof: false,
    },
    interpretation: {
        confirmed: [
            "existing Java hook accounting is runtime-static and reset is tied to PacketProcessor.close",
            "BrowserWebSocketChannel.doClose retires only its channel/socket",
            "supported one-processor runtime model preserves B after A channel close and reconnect",
            "Node relay/channel close tests are separate from Java PacketProcessor evidence",
        ],
        knownRisk: [
            "if a future architecture puts two PacketProcessor instances in one JVM, close(A) clears B accounting",
            "an active drain claim/demand is also global in that unsupported topology",
            "a browser channel close does not purge already-decoded entries from the shared FIFO; the closed listener must be discarded by the normal packet path without resetting B",
        ],
        notProven: [
            "this model is not a TeaVM/browser runtime proof",
            "this does not prove external ellan.top latency or reconnect health",
            "this does not prove multiple Java Minecraft instances share one page",
        ],
    },
};

console.log(JSON.stringify(result));

