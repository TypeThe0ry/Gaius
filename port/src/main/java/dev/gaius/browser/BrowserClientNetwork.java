package dev.gaius.browser;

import io.netty.channel.browser.BrowserWebSocketChannel;
import net.minecraft.network.PacketProcessor;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/** Keeps browser transport decoding live without running PLAY handlers on a WebSocket callback. */
public final class BrowserClientNetwork {
    private static boolean installed;
    private static boolean pumping;

    private BrowserClientNetwork() {
    }

    public static void install() {
        if (installed) {
            return;
        }
        // Minecraft starts ticking before the first browser socket constructs its bridge.
        // Keep retrying from runTick until that bridge is available instead of permanently
        // missing the callback for a later multiplayer connection.
        installed = installInboundPump(BrowserClientNetwork::pumpInbound);
    }

    /**
     * Runs one existing bounded Netty transport turn from an independent browser macrotask.
     *
     * <p>The callback deliberately stops at raw transport decode. Ordinary PLAY packets still
     * enter PacketProcessor's FIFO and are handled only at the vanilla client tick's scheduled
     * packet-processing point; this path never invokes those handlers. The channel pump's boolean
     * is a continuation hint (slice progress or an aggregate-budget yield), so pairing it with
     * {@code hasPumpableInput()} retries a ready channel that fell beyond the round-robin cursor
     * without spinning while all channels are idle or exact-queue paused.</p>
     */
    private static boolean pumpInbound() {
        if (pumping) {
            recordJavaPumpSkipped();
            return false;
        }
        pumping = true;
        try {
            boolean progressed = BrowserWebSocketChannel.pumpAllAndReportProgress();
            return progressed && BrowserWebSocketChannel.hasPumpableInput();
        } finally {
            pumping = false;
        }
    }

    /**
     * Starts the authoritative packet-accounting epoch before raw transport work for this tick.
     */
    public static void beginClientPacketFrame() {
        BrowserPacketScheduler.beginClientFrame();
    }

    /**
     * Replaces the one vanilla {@link PacketProcessor#processQueuedPackets()} call in
     * Minecraft.runTick without moving its profiler/order boundary or adding a second batch.
     *
     * <p>The old experiment entered Java from a self-reposting JavaScript MessageChannel while
     * Minecraft's TeaVM continuation could already be suspended. That path could process packets
     * but starved the next real game present. This safe point stays on the main continuation and
     * preserves the patched {@code Queue.poll} FIFO. Below 64 packets (or when disabled) the
     * original sixteen-packet/two-millisecond batch remains unchanged. Pressure and exact-pause
     * recovery both target queue depth 63, with the same two-millisecond deadline and a
     * 256-packet clock-failure ceiling. Exactly one PacketProcessor call occurs per scheduled
     * runTick boundary.</p>
     */
    public static void processClientPacketsAtScheduledFrameBoundary(
            PacketProcessor packetProcessor) {
        int queueBefore = BrowserPacketScheduler.queuedPacketCount();
        if (queueBefore < 64 || !isClientPacketFrameBoundaryDrainEnabled()) {
            packetProcessor.processQueuedPackets();
            return;
        }
        long runTickSequence = BrowserPacketScheduler.currentClientFrameSequence();
        int handleDepthBefore = BrowserPacketScheduler.queuedPacketHandleDepth();
        boolean pausedBefore = BrowserPacketScheduler.isPacketQueuePaused();
        String mode = pausedBefore ? "critical" : "pressure";
        int targetQueue = BrowserPacketScheduler.clientPacketDrainTargetQueue();
        int requestedPackets = Math.max(1, queueBefore - targetQueue);
        int batchTargetPackets = Math.min(
                BrowserPacketScheduler.clientPacketDrainHardMaxPackets(),
                requestedPackets);
        long startedNanos = System.nanoTime();
        String outcome = "claim-skipped";
        String failureType = null;
        if (!BrowserPacketScheduler.tryBeginClientPacketDrain(pausedBefore)) {
            recordClientPacketDrainJavaSkipped("claim-frame-boundary");
            recordClientPacketFrameBoundaryDrain(
                    runTickSequence,
                    0L,
                    queueBefore,
                    BrowserPacketScheduler.queuedPacketCount(),
                    0,
                    handleDepthBefore,
                    BrowserPacketScheduler.queuedPacketHandleDepth(),
                    pausedBefore,
                    BrowserPacketScheduler.isPacketQueuePaused(),
                    0.0,
                    "claim-skipped",
                    targetQueue,
                    requestedPackets,
                    batchTargetPackets,
                    Math.max(0, BrowserPacketScheduler.queuedPacketCount() - targetQueue),
                    mode,
                    "claim-skipped",
                    null);
            return;
        }
        try {
            packetProcessor.processQueuedPackets();
            outcome = "completed";
        } catch (RuntimeException | Error failure) {
            outcome = "failure";
            failureType = failure.getClass().getName();
            BrowserPacketScheduler.interruptClientPacketDrain();
            throw failure;
        } finally {
            // Capture the exact scheduler-owned batch identity/count before releasing the claim.
            // A PacketProcessor.close/reset may have cleared its queue during a handler, so queue
            // depth delta is evidence of a clear, not evidence that every removed item ran.
            long drainEpoch = BrowserPacketScheduler.clientPacketDrainEpoch();
            int handlerCompletions =
                    BrowserPacketScheduler.clientPacketDrainHandlerCompletions();
            BrowserPacketScheduler.finishClientPacketDrain();
            recordClientPacketFrameBoundaryDrain(
                    runTickSequence,
                    drainEpoch,
                    queueBefore,
                    BrowserPacketScheduler.queuedPacketCount(),
                    handlerCompletions,
                    handleDepthBefore,
                    BrowserPacketScheduler.queuedPacketHandleDepth(),
                    pausedBefore,
                    BrowserPacketScheduler.isPacketQueuePaused(),
                    Math.max(0L, System.nanoTime() - startedNanos) / 1_000_000.0,
                    BrowserPacketScheduler.clientPacketDrainStopReason(),
                    BrowserPacketScheduler.clientPacketDrainTargetQueue(),
                    BrowserPacketScheduler.clientPacketDrainRequestedPackets(),
                    BrowserPacketScheduler.clientPacketDrainBatchTargetPackets(),
                    BrowserPacketScheduler.clientPacketDrainRemainingDebt(),
                    mode,
                    outcome,
                    failureType);
        }
    }

    @JSBody(params = "callback", script = """
            const bridge = globalThis.__gaiusNettyBridge;
            if (!bridge) return false;
            const stats = bridge.stats || globalThis.__gaiusNetworkStats || {};
            const clock = function() {
              return typeof performance !== 'undefined' && performance.now
                ? performance.now()
                : Date.now();
            };
            let scheduler = bridge.inboundPumpScheduler;
            if (!scheduler || scheduler.version !== 2 || scheduler.__gaiusRetired === true) {
              let nextGeneration = ((Number(bridge.inboundPumpGeneration) || 0) + 1) >>> 0;
              if (nextGeneration === 0) nextGeneration = 1;
              if (scheduler) {
                scheduler.__gaiusRetired = true;
                const retiredPending = scheduler.pending;
                if (retiredPending && retiredPending.watchdog) {
                  try { clearTimeout(retiredPending.watchdog); } catch (ignored) {}
                  retiredPending.watchdog = 0;
                }
                scheduler.pending = null;
                // Invalidate a queued diagnostics microtask from a retired scheduler before
                // replacing it; transport callbacks use a separate token/queue and are untouched.
                let retiredGeneration =
                  ((Number(scheduler.reportGeneration) || 0) + 1) >>> 0;
                if (retiredGeneration === 0) retiredGeneration = 1;
                scheduler.reportGeneration = retiredGeneration;
                scheduler.reportPending = false;
                scheduler.reportDirty = false;
              }
              if (scheduler && scheduler.channel) {
                try { scheduler.channel.port1.onmessage = null; } catch (ignored) {}
                try { if (scheduler.channel.port1.close) scheduler.channel.port1.close(); }
                catch (ignored) {}
                try { if (scheduler.channel.port2.close) scheduler.channel.port2.close(); }
                catch (ignored) {}
              }
              scheduler = {
                version: 2,
                // Pump generation changes whenever this scheduler is replaced.  A
                // pending callback captures both the object identity and generation so
                // a late MessageChannel/watchdog callback from a retired scheduler can
                // never clear state or enter Java on the current bridge.
                generation: nextGeneration,
                channel: null,
                pending: null,
                running: false,
                nextToken: 1,
                reportPending: false,
                reportDirty: false,
                reportStage: '',
                reportGeneration: 0,
                reportHandle: 0
              };
              bridge.inboundPumpScheduler = scheduler;
              bridge.inboundPumpGeneration = nextGeneration;
              bridge.inboundPumpPending = false;
            }
            if (!Number.isFinite(Number(scheduler.generation)) ||
                Number(scheduler.generation) <= 0) {
              scheduler.generation = Math.max(
                1, Number(bridge.inboundPumpGeneration) || 1);
            }
            bridge.inboundPumpGeneration = scheduler.generation;
            // Keep the scheduler object compatible with older generated clients while adding
            // a bounded latest-only diagnostics lane. These fields never gate transport work.
            if (typeof scheduler.reportPending !== 'boolean') scheduler.reportPending = false;
            if (typeof scheduler.reportDirty !== 'boolean') scheduler.reportDirty = false;
            if (typeof scheduler.reportStage !== 'string') scheduler.reportStage = '';
            scheduler.reportGeneration = (Number(scheduler.reportGeneration) || 0) >>> 0;
            if (!Number.isFinite(Number(scheduler.reportHandle))) scheduler.reportHandle = 0;
            const retiredClientScheduler = bridge.clientPacketDrainScheduler;
            if (retiredClientScheduler && retiredClientScheduler.channel) {
              try { retiredClientScheduler.channel.port1.onmessage = null; } catch (ignored) {}
              try {
                if (retiredClientScheduler.channel.port1.close) {
                  retiredClientScheduler.channel.port1.close();
                }
              } catch (ignored) {}
              try {
                if (retiredClientScheduler.channel.port2.close) {
                  retiredClientScheduler.channel.port2.close();
                }
              } catch (ignored) {}
            }
            bridge.clientPacketDrainScheduler = null;
            bridge.clientPacketDrainPending = false;
            bridge.clientPacketDrainDemand = false;
            stats.inboundPumpInstalled = (stats.inboundPumpInstalled|0) + 1;
            stats.inboundPumpRequested = stats.inboundPumpRequested|0;
            stats.inboundPumpScheduled = stats.inboundPumpScheduled|0;
            stats.inboundPumpCoalesced = stats.inboundPumpCoalesced|0;
            stats.inboundPumpCallbacks = stats.inboundPumpCallbacks|0;
            stats.inboundPumpMessageCallbacks = stats.inboundPumpMessageCallbacks|0;
            stats.inboundPumpTimerCallbacks = stats.inboundPumpTimerCallbacks|0;
            stats.inboundPumpWatchdogCallbacks = stats.inboundPumpWatchdogCallbacks|0;
            stats.inboundPumpStaleCallbacks = stats.inboundPumpStaleCallbacks|0;
            stats.inboundPumpJavaStarted = stats.inboundPumpJavaStarted|0;
            stats.inboundPumpJavaCompleted = stats.inboundPumpJavaCompleted|0;
            stats.inboundPumpJavaSkipped = stats.inboundPumpJavaSkipped|0;
            stats.inboundPumpJavaFailures = stats.inboundPumpJavaFailures|0;
            stats.inboundPumpRescheduled = stats.inboundPumpRescheduled|0;
            stats.inboundPumpBlockedByExactQueue = stats.inboundPumpBlockedByExactQueue|0;
            stats.inboundPumpMessageChannelFailures =
              stats.inboundPumpMessageChannelFailures|0;
            stats.maxInboundPumpScheduleWaitMillis =
              Math.max(0, Number(stats.maxInboundPumpScheduleWaitMillis) || 0);
            stats.inboundPumpDomReports = stats.inboundPumpDomReports|0;
            stats.inboundPumpDomReportCoalesced = stats.inboundPumpDomReportCoalesced|0;
            stats.inboundPumpDomReportFlushes = stats.inboundPumpDomReportFlushes|0;
            stats.inboundPumpDomReportBytes =
              Math.max(0, Number(stats.inboundPumpDomReportBytes) || 0);
            stats.clientPacketDrainInstalled = (stats.clientPacketDrainInstalled|0) + 1;
            stats.clientPacketDrainRequested = stats.clientPacketDrainRequested|0;
            stats.clientPacketDrainScheduled = stats.clientPacketDrainScheduled|0;
            stats.clientPacketDrainCoalesced = stats.clientPacketDrainCoalesced|0;
            stats.clientPacketDrainCallbacks = stats.clientPacketDrainCallbacks|0;
            stats.clientPacketDrainMessageCallbacks =
              stats.clientPacketDrainMessageCallbacks|0;
            stats.clientPacketDrainTimerCallbacks = stats.clientPacketDrainTimerCallbacks|0;
            stats.clientPacketDrainWatchdogCallbacks =
              stats.clientPacketDrainWatchdogCallbacks|0;
            stats.clientPacketDrainStaleCallbacks = stats.clientPacketDrainStaleCallbacks|0;
            stats.clientPacketDrainJavaStarted = stats.clientPacketDrainJavaStarted|0;
            stats.clientPacketDrainJavaCompleted = stats.clientPacketDrainJavaCompleted|0;
            stats.clientPacketDrainJavaSkipped = stats.clientPacketDrainJavaSkipped|0;
            stats.clientPacketDrainJavaFailures = stats.clientPacketDrainJavaFailures|0;
            stats.clientPacketDrainRescheduled = stats.clientPacketDrainRescheduled|0;
            stats.clientPacketDrainBelowThreshold =
              stats.clientPacketDrainBelowThreshold|0;
            stats.clientPacketDrainDisabledRequests =
              stats.clientPacketDrainDisabledRequests|0;
            stats.clientPacketDrainInvalidations = stats.clientPacketDrainInvalidations|0;
            stats.clientPacketDrainCancelled = stats.clientPacketDrainCancelled|0;
            stats.clientPacketDrainCancelledBeforeJava =
              stats.clientPacketDrainCancelledBeforeJava|0;
            stats.clientPacketDrainInvalidatedWhileRunning =
              stats.clientPacketDrainInvalidatedWhileRunning|0;
            stats.clientPacketDrainMessageChannelFailures =
              stats.clientPacketDrainMessageChannelFailures|0;
            stats.clientPacketDrainPacketsProcessed =
              Math.max(0, Number(stats.clientPacketDrainPacketsProcessed) || 0);
            stats.maxClientPacketDrainPacketsPerTurn =
              Math.max(0, Number(stats.maxClientPacketDrainPacketsPerTurn) || 0);
            stats.maxClientPacketDrainScheduleWaitMillis =
              Math.max(0, Number(stats.maxClientPacketDrainScheduleWaitMillis) || 0);
            stats.maxClientPacketDrainCallbackMillis =
              Math.max(0, Number(stats.maxClientPacketDrainCallbackMillis) || 0);
            stats.clientPacketDrainEventSequence =
              Math.max(0, Number(stats.clientPacketDrainEventSequence) || 0);
            stats.clientPacketDrainEventsDropped =
              Math.max(0, Number(stats.clientPacketDrainEventsDropped) || 0);
            if (!Array.isArray(stats.clientPacketDrainEvents)) {
              stats.clientPacketDrainEvents = [];
            } else if (stats.clientPacketDrainEvents.length > 64) {
              const retiredDrainEventsDropped = stats.clientPacketDrainEvents.length - 64;
              stats.clientPacketDrainEvents.splice(0, retiredDrainEventsDropped);
              stats.clientPacketDrainEventsDropped += retiredDrainEventsDropped;
            }
            stats.clientPacketDrainDemandSignals =
              Math.max(0, Number(stats.clientPacketDrainDemandSignals) || 0);
            stats.frameBoundaryDrainAttempts =
              Math.max(0, Number(stats.frameBoundaryDrainAttempts) || 0);
            stats.frameBoundaryDrainClaims =
              Math.max(0, Number(stats.frameBoundaryDrainClaims) || 0);
            stats.frameBoundaryDrainCompleted =
              Math.max(0, Number(stats.frameBoundaryDrainCompleted) || 0);
            stats.frameBoundaryDrainFailures =
              Math.max(0, Number(stats.frameBoundaryDrainFailures) || 0);
            stats.frameBoundaryDrainSkippedNested =
              Math.max(0, Number(stats.frameBoundaryDrainSkippedNested) || 0);
            stats.frameBoundaryDrainSkippedClaim =
              Math.max(0, Number(stats.frameBoundaryDrainSkippedClaim) || 0);
            stats.frameBoundaryDrainSkippedMinecraft =
              Math.max(0, Number(stats.frameBoundaryDrainSkippedMinecraft) || 0);
            stats.frameBoundaryDrainSkippedProcessor =
              Math.max(0, Number(stats.frameBoundaryDrainSkippedProcessor) || 0);
            stats.frameBoundaryDrainPacketsProcessed =
              Math.max(0, Number(stats.frameBoundaryDrainPacketsProcessed) || 0);
            stats.frameBoundaryDrainQueueDepthReduction =
              Math.max(0, Number(stats.frameBoundaryDrainQueueDepthReduction) || 0);
            stats.frameBoundaryDrainUnattributedQueueReduction =
              Math.max(0, Number(stats.frameBoundaryDrainUnattributedQueueReduction) || 0);
            stats.frameBoundaryDrainTotalMillis =
              Math.max(0, Number(stats.frameBoundaryDrainTotalMillis) || 0);
            stats.frameBoundaryDrainMaxMillis =
              Math.max(0, Number(stats.frameBoundaryDrainMaxMillis) || 0);
            stats.frameBoundaryDrainMaxPacketsPerTurn =
              Math.max(0, Number(stats.frameBoundaryDrainMaxPacketsPerTurn) || 0);
            stats.frameBoundaryDrainTargetStops =
              Math.max(0, Number(stats.frameBoundaryDrainTargetStops) || 0);
            stats.frameBoundaryDrainDeadlineStops =
              Math.max(0, Number(stats.frameBoundaryDrainDeadlineStops) || 0);
            stats.frameBoundaryDrainEmptyStops =
              Math.max(0, Number(stats.frameBoundaryDrainEmptyStops) || 0);
            stats.frameBoundaryDrainInterruptedStops =
              Math.max(0, Number(stats.frameBoundaryDrainInterruptedStops) || 0);
            stats.frameBoundaryDrainHardCapStops =
              Math.max(0, Number(stats.frameBoundaryDrainHardCapStops) || 0);
            stats.frameBoundaryDrainMaxRequestedPackets =
              Math.max(0, Number(stats.frameBoundaryDrainMaxRequestedPackets) || 0);
            stats.frameBoundaryDrainMaxBatchTargetPackets =
              Math.max(0, Number(stats.frameBoundaryDrainMaxBatchTargetPackets) || 0);
            stats.frameBoundaryDrainMaxRemainingDebt =
              Math.max(0, Number(stats.frameBoundaryDrainMaxRemainingDebt) || 0);
            stats.frameBoundaryDrainEventSequence =
              Math.max(0, Number(stats.frameBoundaryDrainEventSequence) || 0);
            stats.frameBoundaryDrainEventsDropped =
              Math.max(0, Number(stats.frameBoundaryDrainEventsDropped) || 0);
            if (!Array.isArray(stats.frameBoundaryDrainEvents)) {
              stats.frameBoundaryDrainEvents = [];
            } else if (stats.frameBoundaryDrainEvents.length > 64) {
              const boundaryDrainEventsDropped = stats.frameBoundaryDrainEvents.length - 64;
              stats.frameBoundaryDrainEvents.splice(0, boundaryDrainEventsDropped);
              stats.frameBoundaryDrainEventsDropped += boundaryDrainEventsDropped;
            }
            stats.clientPacketFrameSequence =
              Math.max(0, Number(stats.clientPacketFrameSequence) || 0);
            stats.clientPacketFramesWithWork =
              Math.max(0, Number(stats.clientPacketFramesWithWork) || 0);
            stats.clientPacketFramePacketsProcessed =
              Math.max(0, Number(stats.clientPacketFramePacketsProcessed) || 0);
            stats.clientPacketFrameHandlerMillis =
              Math.max(0, Number(stats.clientPacketFrameHandlerMillis) || 0);
            stats.clientPacketFrameSafeDrainTurns =
              Math.max(0, Number(stats.clientPacketFrameSafeDrainTurns) || 0);
            stats.clientPacketFrameVanillaDrainTurns =
              Math.max(0, Number(stats.clientPacketFrameVanillaDrainTurns) || 0);
            stats.maxClientPacketFramePackets =
              Math.max(0, Number(stats.maxClientPacketFramePackets) || 0);
            stats.maxClientPacketFrameHandlerMillis =
              Math.max(0, Number(stats.maxClientPacketFrameHandlerMillis) || 0);
            stats.maxClientPacketFrameSafeDrainTurns =
              Math.max(0, Number(stats.maxClientPacketFrameSafeDrainTurns) || 0);
            stats.maxClientPacketFrameVanillaDrainTurns =
              Math.max(0, Number(stats.maxClientPacketFrameVanillaDrainTurns) || 0);
            stats.clientPacketFrameEventSequence =
              Math.max(0, Number(stats.clientPacketFrameEventSequence) || 0);
            stats.clientPacketFrameEventsDropped =
              Math.max(0, Number(stats.clientPacketFrameEventsDropped) || 0);
            if (!Array.isArray(stats.clientPacketFrameEvents)) {
              stats.clientPacketFrameEvents = [];
            } else if (stats.clientPacketFrameEvents.length > 64) {
              const packetFrameEventsDropped = stats.clientPacketFrameEvents.length - 64;
              stats.clientPacketFrameEvents.splice(0, packetFrameEventsDropped);
              stats.clientPacketFrameEventsDropped += packetFrameEventsDropped;
            }
            function writeReport(stage) {
              const root = typeof document !== 'undefined' ? document.documentElement : null;
              if (!root) return false;
              const payload = JSON.stringify({
                stage: stage,
                requested: stats.inboundPumpRequested|0,
                scheduled: stats.inboundPumpScheduled|0,
                coalesced: stats.inboundPumpCoalesced|0,
                callback: stats.inboundPumpCallbacks|0,
                started: stats.inboundPumpJavaStarted|0,
                completed: stats.inboundPumpJavaCompleted|0,
                skipped: stats.inboundPumpJavaSkipped|0,
                rescheduled: stats.inboundPumpRescheduled|0,
                watchdog: stats.inboundPumpWatchdogCallbacks|0,
                pending: !!scheduler.pending,
                running: !!scheduler.running,
                received: stats.receivedFrames|0,
                pumped: stats.pumpCalls|0
              });
              root.setAttribute('data-gaius-network-pump', payload);
              stats.inboundPumpDomReports = (stats.inboundPumpDomReports|0) + 1;
              stats.inboundPumpDomReportBytes =
                Math.max(0, Number(stats.inboundPumpDomReportBytes) || 0) + payload.length;
              stats.inboundPumpDomReportLastStage = String(stage || 'unknown');
              return true;
            }
            function flushReport() {
              if (!scheduler.reportPending) return false;
              scheduler.reportPending = false;
              scheduler.reportHandle = 0;
              if (!scheduler.reportDirty) return false;
              const stage = scheduler.reportStage || 'unknown';
              scheduler.reportStage = '';
              scheduler.reportDirty = false;
              stats.inboundPumpDomReportFlushes =
                (stats.inboundPumpDomReportFlushes|0) + 1;
              return writeReport(stage);
            }
            function scheduleReportFlush() {
              const generation = scheduler.reportGeneration;
              const run = function() {
                if (generation !== scheduler.reportGeneration) return;
                scheduler.reportHandle = 0;
                flushReport();
              };
              if (typeof queueMicrotask === 'function') {
                try {
                  queueMicrotask(run);
                  return;
                } catch (error) {}
              }
              scheduler.reportHandle = setTimeout(run, 0);
            }
            function report(stage) {
              const root = typeof document !== 'undefined' ? document.documentElement : null;
              if (!root) return;
              const normalizedStage = String(stage || 'unknown');
              scheduler.reportStage = normalizedStage;
              scheduler.reportDirty = true;
              if (scheduler.reportPending) {
                stats.inboundPumpDomReportCoalesced =
                  (stats.inboundPumpDomReportCoalesced|0) + 1;
                return;
              }
              scheduler.reportPending = true;
              // Preserve the old synchronous observer contract for the first report in a turn;
              // only later reports in that same turn are latest-only coalesced.
              if (writeReport(normalizedStage)) scheduler.reportDirty = false;
              let generation = ((Number(scheduler.reportGeneration) || 0) + 1) >>> 0;
              if (generation === 0) generation = 1;
              scheduler.reportGeneration = generation;
              scheduleReportFlush();
            }
            bridge.flushInboundPumpReport = function() { return flushReport(); };
            function ensureMessageChannel() {
              if (scheduler.channel || typeof MessageChannel !== 'function') return;
              try {
                const channel = new MessageChannel();
                const channelScheduler = scheduler;
                channel.port1.onmessage = function(event) {
                  const token = Number(event && event.data) || 0;
                  const pending = channelScheduler.pending;
                  if (!pending || pending.token !== token) {
                    stats.inboundPumpStaleCallbacks++;
                    return;
                  }
                  pending.finish('message');
                };
                scheduler.channel = channel;
              } catch (error) {
                stats.inboundPumpMessageChannelFailures++;
              }
            }
            function schedulePump(reason) {
              if (globalThis.__gaiusNettyBridge !== bridge ||
                  scheduler.__gaiusRetired === true ||
                  scheduler.version !== 2 ||
                  bridge.inboundPumpScheduler !== scheduler) {
                stats.inboundPumpStaleCallbacks++;
                return false;
              }
              if (bridge.exactPacketQueuePaused) {
                stats.inboundPumpBlockedByExactQueue++;
                report('exact-paused');
                return false;
              }
              if (scheduler.pending || scheduler.running) {
                stats.inboundPumpCoalesced++;
                report('coalesced');
                return false;
              }
              let token = (Number(scheduler.nextToken) || 1) >>> 0;
              if (token === 0) token = 1;
              scheduler.nextToken = (token + 1) >>> 0;
              const scheduledAt = clock();
              const ownerScheduler = scheduler;
              const ownerGeneration = Number(ownerScheduler.generation) || 1;
              let watchdog = 0;
              let finished = false;
              let staleReported = false;
              const pending = {token: token, finish: null, watchdog: 0};
              const finish = function(source) {
                // The bridge may have installed a replacement scheduler while this
                // callback was queued.  Fail closed: only the exact pending record
                // on the owning generation may mutate bridge/scheduler state or call
                // the Java pump callback.  Retired callbacks are telemetry-only.
                if (globalThis.__gaiusNettyBridge !== bridge ||
                    bridge.inboundPumpScheduler !== ownerScheduler ||
                    ownerScheduler.__gaiusRetired === true ||
                    ownerScheduler.version !== 2 ||
                    (Number(ownerScheduler.generation) || 0) !== ownerGeneration ||
                    ownerScheduler.pending !== pending) {
                  if (!staleReported) {
                    staleReported = true;
                    if (pending.watchdog) {
                      try { clearTimeout(pending.watchdog); } catch (ignored) {}
                      pending.watchdog = 0;
                    }
                    stats.inboundPumpStaleCallbacks++;
                  }
                  return;
                }
                if (finished) {
                  if (!staleReported) {
                    staleReported = true;
                    stats.inboundPumpStaleCallbacks++;
                  }
                  return;
                }
                finished = true;
                if (watchdog) clearTimeout(watchdog);
                pending.watchdog = 0;
                if (scheduler.pending === pending) scheduler.pending = null;
                bridge.inboundPumpPending = false;
                const waitMillis = Math.max(0, clock() - scheduledAt);
                stats.maxInboundPumpScheduleWaitMillis = Math.max(
                  stats.maxInboundPumpScheduleWaitMillis,
                  waitMillis
                );
                stats.inboundPumpCallbacks++;
                if (source === 'message') stats.inboundPumpMessageCallbacks++;
                else if (source === 'watchdog') stats.inboundPumpWatchdogCallbacks++;
                else stats.inboundPumpTimerCallbacks++;
                scheduler.running = true;
                stats.inboundPumpJavaStarted++;
                let continuePumping = false;
                try {
                  continuePumping = !!callback();
                  stats.inboundPumpLastCallbackAtMillis = clock();
                } catch (error) {
                  stats.inboundPumpJavaFailures++;
                  globalThis.__gaiusLastNetworkPumpError = String(
                    error && (error.stack || error.message) || error
                  );
                } finally {
                  stats.inboundPumpJavaCompleted++;
                  scheduler.running = false;
                }
                // callback() may synchronously install a replacement bridge scheduler;
                // do not publish reports or schedule work against retired state.
                if (globalThis.__gaiusNettyBridge !== bridge ||
                    bridge.inboundPumpScheduler !== ownerScheduler ||
                    (Number(ownerScheduler.generation) || 0) !== ownerGeneration) {
                  return;
                }
                report(source);
                if (continuePumping && !bridge.exactPacketQueuePaused) {
                  stats.inboundPumpRescheduled++;
                  schedulePump('continuation');
                }
              };
              pending.finish = finish;
              scheduler.pending = pending;
              bridge.inboundPumpPending = true;
              stats.inboundPumpScheduled++;
              ensureMessageChannel();
              watchdog = setTimeout(function() { finish('watchdog'); }, 100);
              pending.watchdog = watchdog;
              if (scheduler.channel) {
                try {
                  scheduler.channel.port2.postMessage(token);
                } catch (error) {
                  stats.inboundPumpMessageChannelFailures++;
                  try {
                    scheduler.channel.port1.onmessage = null;
                    if (scheduler.channel.port1.close) scheduler.channel.port1.close();
                    if (scheduler.channel.port2.close) scheduler.channel.port2.close();
                  } catch (ignored) {}
                  scheduler.channel = null;
                  setTimeout(function() { finish('timer'); }, 0);
                }
              } else {
                setTimeout(function() { finish('timer'); }, 0);
              }
              report(reason || 'scheduled');
              return true;
            }
            function clientPacketQueueDepth() {
              return Math.max(0, Number(stats.decodedPacketQueue) || 0);
            }
            function reportClientPacketDrain(stage) {
              const root = typeof document !== 'undefined' ? document.documentElement : null;
              if (!root) return;
              root.setAttribute('data-gaius-client-packet-drain', JSON.stringify({
                stage: stage,
                requested: stats.clientPacketDrainRequested|0,
                scheduled: stats.clientPacketDrainScheduled|0,
                coalesced: stats.clientPacketDrainCoalesced|0,
                callbacks: stats.clientPacketDrainCallbacks|0,
                completed: stats.clientPacketDrainJavaCompleted|0,
                skipped: stats.clientPacketDrainJavaSkipped|0,
                failures: stats.clientPacketDrainJavaFailures|0,
                rescheduled: stats.clientPacketDrainRescheduled|0,
                enabled: globalThis.__gaiusClientPacketDrainEnabled === true,
                disabled: stats.clientPacketDrainDisabledRequests|0,
                invalidations: stats.clientPacketDrainInvalidations|0,
                cancelled: stats.clientPacketDrainCancelled|0,
                watchdog: stats.clientPacketDrainWatchdogCallbacks|0,
                queueDepth: clientPacketQueueDepth(),
                 generation: 0,
                 pending: false,
                 running: false,
                 demand: !!bridge.clientPacketDrainDemand,
                 mode: 'single-call-runTick-boundary'
              }));
            }
            bridge.inboundPump = function() {
              stats.inboundPumpRequested++;
              schedulePump('requested');
            };
             bridge.clientPacketDrain = function() {
               stats.clientPacketDrainRequested++;
               if (globalThis.__gaiusClientPacketDrainEnabled !== true) {
                 stats.clientPacketDrainDisabledRequests++;
                 if (stats.clientPacketDrainDisabledRequests === 1) {
                   reportClientPacketDrain('disabled');
                 }
                 return false;
               }
               const demand = clientPacketQueueDepth() >= 64;
               if (!demand) {
                 bridge.clientPacketDrainDemand = false;
                 stats.clientPacketDrainBelowThreshold++;
                 reportClientPacketDrain('below-threshold');
                 return false;
               }
               if (bridge.clientPacketDrainDemand) return false;
               bridge.clientPacketDrainDemand = true;
               stats.clientPacketDrainDemandSignals++;
               reportClientPacketDrain('frame-boundary-demand');
               return false;
             };
             bridge.invalidateClientPacketDrain = function(reason) {
               stats.clientPacketDrainInvalidations++;
               bridge.clientPacketDrainDemand = false;
               bridge.clientPacketDrainPending = false;
               stats.clientPacketDrainLastInvalidationReason = String(
                 reason || 'invalidated'
               );
               reportClientPacketDrain('invalidated');
               return false;
             };
            return true;
            """)
    private static native boolean installInboundPump(BrowserPumpCallback callback);

    @JSBody(script = """
            return globalThis.__gaiusClientPacketDrainEnabled === true;
            """)
    private static native boolean isClientPacketFrameBoundaryDrainEnabled();

    /** Records one pressure-triggered safe drain; no Java handler is entered from this hook. */
    @JSBody(params = {
            "runTickSequence", "drainEpoch", "queueBefore", "queueAfter",
            "handlerCompletions", "handleDepthBefore", "handleDepthAfter",
            "pausedBefore", "pausedAfter", "elapsedMillis",
            "stopReason", "targetQueue", "requestedPackets", "batchTargetPackets",
            "remainingDebt", "mode", "outcome", "failureType"
    }, script = """
            const bridge = globalThis.__gaiusNettyBridge;
            const stats = bridge && (bridge.stats || globalThis.__gaiusNetworkStats);
            if (!stats) return;
            const bounded = function(value) {
              const number = Math.max(0, Number(value) || 0);
              return Number.isFinite(number) ? number : 0;
            };
            const result = String(outcome || 'unknown');
            const drainMode = String(mode || 'pressure');
            const rawStopReason = String(stopReason || 'unknown');
            const drainStopReason = rawStopReason === 'target' ||
              rawStopReason === 'deadline' || rawStopReason === 'empty' ||
              rawStopReason === 'interrupted' ||
              rawStopReason === 'hard-cap' || rawStopReason === 'claim-skipped'
                ? rawStopReason : 'unknown';
            const before = bounded(queueBefore);
            const after = bounded(queueAfter);
            const epoch = bounded(drainEpoch);
            const packets = bounded(handlerCompletions);
            const queueDepthReduction = Math.max(0, before - after);
            const unattributedQueueReduction = Math.max(
              0, queueDepthReduction - packets);
            const millis = bounded(elapsedMillis);
            const target = bounded(targetQueue);
            const requested = bounded(requestedPackets);
            const batchTarget = bounded(batchTargetPackets);
            const debt = bounded(remainingDebt);
            stats.frameBoundaryDrainAttempts = bounded(stats.frameBoundaryDrainAttempts) + 1;
            if (result !== 'nested-skipped' && result !== 'claim-skipped') {
              stats.frameBoundaryDrainClaims = bounded(stats.frameBoundaryDrainClaims) + 1;
            }
            if (result === 'completed') {
              stats.frameBoundaryDrainCompleted = bounded(stats.frameBoundaryDrainCompleted) + 1;
            } else if (result === 'failure') {
              stats.frameBoundaryDrainFailures = bounded(stats.frameBoundaryDrainFailures) + 1;
            } else if (result === 'nested-skipped') {
              stats.frameBoundaryDrainSkippedNested =
                bounded(stats.frameBoundaryDrainSkippedNested) + 1;
            } else if (result === 'claim-skipped') {
              stats.frameBoundaryDrainSkippedClaim =
                bounded(stats.frameBoundaryDrainSkippedClaim) + 1;
            } else if (result === 'minecraft-skipped') {
              stats.frameBoundaryDrainSkippedMinecraft =
                bounded(stats.frameBoundaryDrainSkippedMinecraft) + 1;
            } else if (result === 'processor-skipped') {
              stats.frameBoundaryDrainSkippedProcessor =
                bounded(stats.frameBoundaryDrainSkippedProcessor) + 1;
            }
            stats.frameBoundaryDrainPacketsProcessed =
              bounded(stats.frameBoundaryDrainPacketsProcessed) + packets;
            stats.frameBoundaryDrainQueueDepthReduction =
              bounded(stats.frameBoundaryDrainQueueDepthReduction) + queueDepthReduction;
            stats.frameBoundaryDrainUnattributedQueueReduction =
              bounded(stats.frameBoundaryDrainUnattributedQueueReduction) +
                unattributedQueueReduction;
            stats.frameBoundaryDrainTotalMillis =
              bounded(stats.frameBoundaryDrainTotalMillis) + millis;
            stats.frameBoundaryDrainMaxMillis = Math.max(
              bounded(stats.frameBoundaryDrainMaxMillis), millis);
            stats.frameBoundaryDrainMaxPacketsPerTurn = Math.max(
              bounded(stats.frameBoundaryDrainMaxPacketsPerTurn), packets);
            if (drainStopReason === 'target') {
              stats.frameBoundaryDrainTargetStops =
                bounded(stats.frameBoundaryDrainTargetStops) + 1;
            } else if (drainStopReason === 'deadline') {
              stats.frameBoundaryDrainDeadlineStops =
                bounded(stats.frameBoundaryDrainDeadlineStops) + 1;
            } else if (drainStopReason === 'empty') {
              stats.frameBoundaryDrainEmptyStops =
                bounded(stats.frameBoundaryDrainEmptyStops) + 1;
            } else if (drainStopReason === 'interrupted') {
              stats.frameBoundaryDrainInterruptedStops =
                bounded(stats.frameBoundaryDrainInterruptedStops) + 1;
            } else if (drainStopReason === 'hard-cap') {
              stats.frameBoundaryDrainHardCapStops =
                bounded(stats.frameBoundaryDrainHardCapStops) + 1;
            }
            stats.frameBoundaryDrainMaxRequestedPackets = Math.max(
              bounded(stats.frameBoundaryDrainMaxRequestedPackets), requested);
            stats.frameBoundaryDrainMaxBatchTargetPackets = Math.max(
              bounded(stats.frameBoundaryDrainMaxBatchTargetPackets), batchTarget);
            stats.frameBoundaryDrainMaxRemainingDebt = Math.max(
              bounded(stats.frameBoundaryDrainMaxRemainingDebt), debt);
            stats.frameBoundaryDrainLastRunTickSequence = bounded(runTickSequence);
            stats.frameBoundaryDrainLastEpoch = epoch;
            stats.frameBoundaryDrainLastHandlerCompletions = packets;
            stats.frameBoundaryDrainLastQueueDepthReduction = queueDepthReduction;
            stats.frameBoundaryDrainLastUnattributedQueueReduction =
              unattributedQueueReduction;
            stats.frameBoundaryDrainLastOutcome = result;
            stats.frameBoundaryDrainLastMode = drainMode;
            stats.frameBoundaryDrainLastStopReason = drainStopReason;
            stats.frameBoundaryDrainLastTargetQueue = target;
            stats.frameBoundaryDrainLastRequestedPackets = requested;
            stats.frameBoundaryDrainLastBatchTargetPackets = batchTarget;
            stats.frameBoundaryDrainLastRemainingDebt = debt;
            stats.frameBoundaryDrainLastFailureType = failureType == null
              ? '' : String(failureType);
            const sequence = bounded(stats.frameBoundaryDrainEventSequence) + 1;
            stats.frameBoundaryDrainEventSequence = sequence;
            const events = Array.isArray(stats.frameBoundaryDrainEvents)
              ? stats.frameBoundaryDrainEvents
              : (stats.frameBoundaryDrainEvents = []);
            events.push({
              sequence: sequence,
              atMillis: typeof performance !== 'undefined' && performance.now
                ? performance.now() : Date.now(),
              runTickSequence: bounded(runTickSequence),
              drainEpoch: epoch,
              queueBefore: before,
              queueAfter: after,
              packetsProcessed: packets,
              handlerCompletions: packets,
              queueDepthReduction: queueDepthReduction,
              unattributedQueueReduction: unattributedQueueReduction,
              elapsedMillis: millis,
              stopReason: drainStopReason,
              targetQueue: target,
              requestedPackets: requested,
              batchTargetPackets: batchTarget,
              remainingDebt: debt,
              handleDepthBefore: bounded(handleDepthBefore),
              handleDepthAfter: bounded(handleDepthAfter),
              flowPausedBefore: !!pausedBefore,
              flowPausedAfter: !!pausedAfter,
              mode: drainMode,
              outcome: result,
              failureType: failureType == null ? '' : String(failureType)
            });
            if (events.length > 64) {
              const dropped = events.length - 64;
              events.splice(0, dropped);
              stats.frameBoundaryDrainEventsDropped =
                bounded(stats.frameBoundaryDrainEventsDropped) + dropped;
            }
            bridge.clientPacketDrainDemand = after >= 64;
            const root = typeof document !== 'undefined' ? document.documentElement : null;
            if (root) root.setAttribute('data-gaius-client-packet-frame-boundary', JSON.stringify({
              mode: 'single-call-runTick-boundary',
              lastMode: drainMode,
              attempts: bounded(stats.frameBoundaryDrainAttempts),
              claims: bounded(stats.frameBoundaryDrainClaims),
              completed: bounded(stats.frameBoundaryDrainCompleted),
              failures: bounded(stats.frameBoundaryDrainFailures),
              packets: bounded(stats.frameBoundaryDrainPacketsProcessed),
              maxPacketsPerTurn: bounded(stats.frameBoundaryDrainMaxPacketsPerTurn),
              maxMillis: bounded(stats.frameBoundaryDrainMaxMillis),
              stopReason: drainStopReason,
              targetQueue: target,
              requestedPackets: requested,
              batchTargetPackets: batchTarget,
              remainingDebt: debt,
              runTickSequence: bounded(stats.frameBoundaryDrainLastRunTickSequence),
              drainEpoch: epoch,
              handlerCompletions: packets,
              queueDepthReduction: queueDepthReduction,
              unattributedQueueReduction: unattributedQueueReduction,
              queueAfter: after,
              demand: !!bridge.clientPacketDrainDemand
            }));
            """)
    private static native void recordClientPacketFrameBoundaryDrain(
            long runTickSequence,
            long drainEpoch,
            int queueBefore,
            int queueAfter,
            int handlerCompletions,
            int handleDepthBefore,
            int handleDepthAfter,
            boolean pausedBefore,
            boolean pausedAfter,
            double elapsedMillis,
            String stopReason,
            int targetQueue,
            int requestedPackets,
            int batchTargetPackets,
            int remainingDebt,
            String mode,
            String outcome,
            String failureType);

    /** Emits the preceding runTick's combined safe and vanilla PLAY handler work. */
    @JSBody(params = {
            "runTickSequence", "safeDrainTurns", "vanillaDrainTurns", "packetsProcessed",
            "handlerMillis"
    }, script = """
            const bridge = globalThis.__gaiusNettyBridge;
            const stats = bridge && (bridge.stats || globalThis.__gaiusNetworkStats);
            if (!stats) return;
            const bounded = function(value) {
              const number = Math.max(0, Number(value) || 0);
              return Number.isFinite(number) ? number : 0;
            };
            const sequence = bounded(stats.clientPacketFrameEventSequence) + 1;
            const frameSequence = bounded(runTickSequence);
            const safeTurns = bounded(safeDrainTurns);
            const vanillaTurns = bounded(vanillaDrainTurns);
            const packets = bounded(packetsProcessed);
            const millis = bounded(handlerMillis);
            stats.clientPacketFrameEventSequence = sequence;
            stats.clientPacketFrameSequence = frameSequence;
            stats.clientPacketFramesWithWork = bounded(stats.clientPacketFramesWithWork) + 1;
            stats.clientPacketFramePacketsProcessed =
              bounded(stats.clientPacketFramePacketsProcessed) + packets;
            stats.clientPacketFrameHandlerMillis =
              bounded(stats.clientPacketFrameHandlerMillis) + millis;
            stats.clientPacketFrameSafeDrainTurns =
              bounded(stats.clientPacketFrameSafeDrainTurns) + safeTurns;
            stats.clientPacketFrameVanillaDrainTurns =
              bounded(stats.clientPacketFrameVanillaDrainTurns) + vanillaTurns;
            stats.maxClientPacketFramePackets = Math.max(
              bounded(stats.maxClientPacketFramePackets), packets);
            stats.maxClientPacketFrameHandlerMillis = Math.max(
              bounded(stats.maxClientPacketFrameHandlerMillis), millis);
            stats.maxClientPacketFrameSafeDrainTurns = Math.max(
              bounded(stats.maxClientPacketFrameSafeDrainTurns), safeTurns);
            stats.maxClientPacketFrameVanillaDrainTurns = Math.max(
              bounded(stats.maxClientPacketFrameVanillaDrainTurns), vanillaTurns);
            const events = Array.isArray(stats.clientPacketFrameEvents)
              ? stats.clientPacketFrameEvents
              : (stats.clientPacketFrameEvents = []);
            events.push({
              sequence: sequence,
              runTickSequence: frameSequence,
              safeDrainTurns: safeTurns,
              vanillaDrainTurns: vanillaTurns,
              packetsProcessed: packets,
              handlerMillis: millis
            });
            if (events.length > 64) {
              const dropped = events.length - 64;
              events.splice(0, dropped);
              stats.clientPacketFrameEventsDropped =
                bounded(stats.clientPacketFrameEventsDropped) + dropped;
            }
            """)
    public static native void recordClientPacketFrame(
            long runTickSequence,
            int safeDrainTurns,
            int vanillaDrainTurns,
            int packetsProcessed,
            double handlerMillis);

    /** Clears pressure demand when PacketProcessor.close/reset retires its FIFO. */
    @JSBody(params = "reason", script = """
            const bridge = globalThis.__gaiusNettyBridge;
            if (bridge && typeof bridge.invalidateClientPacketDrain === 'function') {
              bridge.invalidateClientPacketDrain(reason);
            }
            """)
    public static native void invalidateClientPacketDrain(String reason);

    @JSBody(script = """
            const stats = globalThis.__gaiusNetworkStats;
            if (stats) stats.inboundPumpJavaSkipped =
              (stats.inboundPumpJavaSkipped|0) + 1;
            """)
    private static native void recordJavaPumpSkipped();

    @JSBody(params = "reason", script = """
            const stats = globalThis.__gaiusNetworkStats;
            if (stats) {
              stats.clientPacketDrainJavaSkipped =
                (stats.clientPacketDrainJavaSkipped|0) + 1;
              stats.clientPacketDrainLastSkipReason = String(reason || 'unknown');
            }
            """)
    private static native void recordClientPacketDrainJavaSkipped(String reason);

    @JSFunctor
    private interface BrowserPumpCallback extends JSObject {
        boolean run();
    }
}
