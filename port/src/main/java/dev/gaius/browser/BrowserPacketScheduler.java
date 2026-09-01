package dev.gaius.browser;

import io.netty.channel.browser.BrowserWebSocketChannel;

/** Applies a small time and count budget when draining browser packet queues. */
public final class BrowserPacketScheduler {
    private static final int MAX_PACKETS_PER_BATCH = 16;
    private static final int MIN_WORKER_PACKETS_PER_BATCH = 4;
    private static final int CLIENT_PACKET_DRAIN_THRESHOLD = 64;
    private static final int CLIENT_PACKET_DRAIN_TARGET_QUEUE =
            CLIENT_PACKET_DRAIN_THRESHOLD - 1;
    private static final int CLIENT_PACKET_DRAIN_HARD_MAX_PACKETS = 256;
    private static final int PACKET_QUEUE_HIGH_WATERMARK = 256;
    private static final int PACKET_QUEUE_LOW_WATERMARK = 64;
    private static final long BATCH_BUDGET_NANOS = 2_000_000L;

    private static int batchPacketLimit;
    private static int packetsRemaining;
    private static int minimumPackets;
    private static long deadlineNanos;
    private static int queuedPackets;
    private static boolean packetQueuePaused;
    private static boolean clientPacketDrainActive;
    private static boolean clientPacketDrainCritical;
    private static int clientPacketDrainRequestedPackets;
    private static int clientPacketDrainBatchTargetPackets;
    private static int clientPacketDrainRemainingDebt;
    private static String clientPacketDrainStopReason = "inactive";
    private static long clientPacketDrainEpoch;
    private static int clientPacketDrainHandlerCompletions;
    /**
     * Identity and PacketProcessor generation that claimed the active client drain.  The owner is
     * retained across an in-handler close/reset so the surrounding runTick finally block can
     * release the claim after the lifecycle owner has been retired.  It is never used to authorize
     * a foreign owner or a later generation.
     */
    private static Object clientPacketDrainOwner;
    private static long clientPacketDrainOwnerGeneration;
    private static int queuedPacketHandleDepth;
    private static long queuedPacketHandleStartedNanos;
    private static long longestQueuedPacketHandleNanos;
    private static Object queuedPacketHandleRoot;
    private static long clientFrameSequence;
    private static int clientFramePacketCount;
    private static long clientFramePacketHandleNanos;
    private static int clientFrameSafeDrainTurns;
    private static int clientFrameVanillaDrainTurns;
    private static boolean clientFrameAccountingActive;
    /**
     * PacketProcessor accounting is deliberately single-owner. A browser client normally has one
     * PacketProcessor fed by many channels, but reconnect/embedded topologies can briefly expose a
     * second instance. Never let that second instance mutate the first one's static queue ledger.
     */
    private static Object packetProcessorOwner;
    private static long packetProcessorGeneration;
    private static boolean packetProcessorOwnerConflict;
    private static boolean packetProcessorAccountingValid = true;
    /**
     * Once two PacketProcessor identities have shared this runtime, the static ledger is no
     * longer recoverable by a close/rebind sequence.  Keep the runtime poisoned so a surviving
     * foreign owner cannot re-enable adaptive accounting against a ledger that was already reset.
     */
    private static boolean packetProcessorConflictPoisoned;
    private static String packetProcessorFallbackReason = "unbound";
    private static long stalePacketProcessorResets;
    private static long stalePacketProcessorEvents;
    private static Object queuedPacketHandleOwner;
    /**
     * Bounded tombstones for PacketProcessor instances that have already crossed close/reset.
     * A late queue callback from one of these owners must not be allowed to claim a fresh static
     * accounting epoch.  Neither the frame bind nor the constructor lifecycle bind removes a
     * retired tombstone; a fresh PacketProcessor object must establish a new identity.
     */
    private static final int RETIRED_PACKET_PROCESSOR_OWNER_LIMIT = 16;
    private static final Object[] retiredPacketProcessorOwners =
            new Object[RETIRED_PACKET_PROCESSOR_OWNER_LIMIT];
    private static int retiredPacketProcessorOwnerCursor;

    private BrowserPacketScheduler() {
    }

    public static void beginBatch() {
        boolean workerServer = BrowserIntegratedServerMain.isWorkerServer();
        if (clientPacketDrainActive && !workerServer) {
            // Pressure recovery is work-conserving within the same two-millisecond boundary.
            // The 256-packet count is only a fail-safe if clock accounting breaks; the target
            // remains the exact 64 -> 63 crossing and no second PacketProcessor call is added.
            clientPacketDrainRequestedPackets = Math.max(
                    1, queuedPackets - CLIENT_PACKET_DRAIN_TARGET_QUEUE);
            clientPacketDrainBatchTargetPackets = Math.min(
                    CLIENT_PACKET_DRAIN_HARD_MAX_PACKETS,
                    clientPacketDrainRequestedPackets);
            clientPacketDrainRemainingDebt = Math.max(
                    0, queuedPackets - CLIENT_PACKET_DRAIN_TARGET_QUEUE);
            clientPacketDrainStopReason = "pending";
            batchPacketLimit = clientPacketDrainBatchTargetPackets;
            minimumPackets = 1;
        } else {
            batchPacketLimit = MAX_PACKETS_PER_BATCH;
            minimumPackets = workerServer
                    ? MIN_WORKER_PACKETS_PER_BATCH
                    : 1;
        }
        packetsRemaining = batchPacketLimit;
        deadlineNanos = System.nanoTime() + BATCH_BUDGET_NANOS;
        if (clientFrameAccountingActive && !workerServer) {
            if (clientPacketDrainActive) {
                if (clientFrameSafeDrainTurns < Integer.MAX_VALUE) {
                    clientFrameSafeDrainTurns++;
                }
            } else if (clientFrameVanillaDrainTurns < Integer.MAX_VALUE) {
                clientFrameVanillaDrainTurns++;
            }
        }
    }

    /**
     * Claims the one PacketProcessor owner before any queue accounting is touched.
     *
     * <p>This method is called at every scheduled client frame.  It is deliberately not a
     * lifecycle reset point: a PacketProcessor that already crossed {@link #reset(Object)} stays
     * retired, so a late frame/queue callback cannot resurrect its static accounting epoch.</p>
     */
    public static boolean bindPacketProcessor(Object owner) {
        return claimPacketProcessorOwner(owner);
    }

    /**
     * Explicit PacketProcessor construction boundary.  The patched PacketProcessor constructor
     * calls this once after its fields are initialized.  This is still claim-only: PacketProcessor
     * close is one-shot in vanilla, so a retired owner must never be reactivated by any bind path.
     */
    public static boolean bindPacketProcessorLifecycle(Object owner) {
        return claimPacketProcessorOwner(owner);
    }

    /**
     * Owner-aware batch entry. A conflicting owner returns false so the patched PacketProcessor
     * can execute its retained vanilla method instead of sharing adaptive state.
     */
    public static boolean beginBatch(Object owner) {
        if (!claimPacketProcessorOwner(owner)) {
            return false;
        }
        beginBatch();
        return true;
    }

    /**
     * Starts the one authoritative multiplayer packet-accounting epoch for this Minecraft
     * {@code runTick}. The preceding frame is emitted before any new raw transport or PLAY work
     * can be attributed to it.
     */
    public static void beginClientFrame() {
        if (BrowserIntegratedServerMain.isWorkerServer()) {
            return;
        }
        flushClientFrameAccounting();
        clientFrameSequence = clientFrameSequence == Long.MAX_VALUE
                ? 1L
                : clientFrameSequence + 1L;
        clientFramePacketCount = 0;
        clientFramePacketHandleNanos = 0L;
        clientFrameSafeDrainTurns = 0;
        clientFrameVanillaDrainTurns = 0;
        clientFrameAccountingActive = true;
    }

    private static void flushClientFrameAccounting() {
        if (!clientFrameAccountingActive
                || clientFramePacketCount == 0) {
            return;
        }
        BrowserClientNetwork.recordClientPacketFrame(
                clientFrameSequence,
                clientFrameSafeDrainTurns,
                clientFrameVanillaDrainTurns,
                clientFramePacketCount,
                clientFramePacketHandleNanos / 1_000_000.0);
    }

    public static long currentClientFrameSequence() {
        return clientFrameSequence;
    }

    public static int queuedPacketCount() {
        return queuedPackets;
    }

    public static int queuedPacketHandleDepth() {
        return queuedPacketHandleDepth;
    }

    public static boolean isPacketQueuePaused() {
        return packetQueuePaused;
    }

    public static boolean shouldProcessNext() {
        if (packetsRemaining <= 0) {
            if (clientPacketDrainActive) {
                clientPacketDrainStopReason =
                        clientPacketDrainRequestedPackets
                                > CLIENT_PACKET_DRAIN_HARD_MAX_PACKETS
                                ? "hard-cap"
                                : "target";
            }
            return false;
        }
        int packetsProcessed = batchPacketLimit - packetsRemaining;
        if (packetsProcessed >= minimumPackets
                && System.nanoTime() >= deadlineNanos) {
            if (clientPacketDrainActive) {
                clientPacketDrainStopReason = "deadline";
            }
            return false;
        }
        packetsRemaining--;
        return true;
    }

    /** Stops the adaptive loop immediately if its PacketProcessor owner becomes stale/conflicted. */
    public static boolean shouldProcessNext(Object owner) {
        if (!isPacketProcessorAccountingValid(owner)) {
            return false;
        }
        return shouldProcessNext();
    }

    public static boolean hasPendingPackets() {
        return queuedPackets > 0;
    }

    /**
     * Owner-aware transition check. During a conflict it is conservative: transition packets stay
     * on the PacketProcessor FIFO rather than being incorrectly inlined ahead of another owner.
     */
    public static boolean hasPendingPackets(Object owner) {
        if (owner == null || packetProcessorOwnerConflict
                || packetProcessorOwner != owner) {
            return true;
        }
        return queuedPackets > 0;
    }

    /**
     * Claims the single vanilla PacketProcessor call at Minecraft's scheduled runTick boundary.
     *
     * <p>Pressure and critical modes both target the exact 64 -> 63 crossing. A 256-packet hard
     * ceiling is only a clock-failure guard; the ordinary stop condition remains the same
     * two-millisecond deadline and the original FIFO owner.</p>
     */
    public static boolean tryBeginClientPacketDrain(boolean critical) {
        if (BrowserIntegratedServerMain.isWorkerServer()
                || clientPacketDrainActive
                || queuedPacketHandleDepth > 0
                || queuedPackets < CLIENT_PACKET_DRAIN_THRESHOLD) {
            return false;
        }
        clientPacketDrainActive = true;
        clientPacketDrainCritical = critical;
        clientPacketDrainOwner = null;
        clientPacketDrainOwnerGeneration = 0L;
        clientPacketDrainEpoch = clientPacketDrainEpoch == Long.MAX_VALUE
                ? 1L
                : clientPacketDrainEpoch + 1L;
        clientPacketDrainHandlerCompletions = 0;
        return true;
    }

    public static boolean tryBeginClientPacketDrain(Object owner, boolean critical) {
        if (!claimPacketProcessorOwner(owner)) {
            return false;
        }
        if (!tryBeginClientPacketDrain(critical)) {
            return false;
        }
        clientPacketDrainOwner = owner;
        clientPacketDrainOwnerGeneration = packetProcessorGeneration;
        return true;
    }

    /** Marks an exceptional or reset-aborted active drain without deriving success from queue depth. */
    public static void interruptClientPacketDrain() {
        if (clientPacketDrainActive) {
            clientPacketDrainStopReason = "interrupted";
        }
    }

    public static void interruptClientPacketDrain(Object owner) {
        if (owner == packetProcessorOwner) {
            interruptClientPacketDrain();
        }
    }

    /** Releases the client drain claim without changing the exact queue or its FIFO. */
    public static void finishClientPacketDrain() {
        if (clientPacketDrainActive) {
            clientPacketDrainRemainingDebt = Math.max(
                    0, queuedPackets - CLIENT_PACKET_DRAIN_TARGET_QUEUE);
            if ("pending".equals(clientPacketDrainStopReason)) {
                clientPacketDrainStopReason = clientPacketDrainRemainingDebt == 0
                        ? "empty"
                        : "interrupted";
            }
        }
        clientPacketDrainActive = false;
        clientPacketDrainCritical = false;
        clientPacketDrainOwner = null;
        clientPacketDrainOwnerGeneration = 0L;
    }

    public static void finishClientPacketDrain(Object owner) {
        if (owner == null || owner != clientPacketDrainOwner
                || clientPacketDrainOwnerGeneration <= 0L) {
            return;
        }
        boolean currentOwner = owner == packetProcessorOwner
                && packetProcessorGeneration == clientPacketDrainOwnerGeneration;
        long nextGeneration = clientPacketDrainOwnerGeneration == Long.MAX_VALUE
                ? 1L
                : clientPacketDrainOwnerGeneration + 1L;
        boolean retiredOwnerAfterReset = packetProcessorOwner == null
                && packetProcessorGeneration == nextGeneration
                && isRetiredPacketProcessorOwner(owner);
        if (currentOwner || retiredOwnerAfterReset) {
            finishClientPacketDrain();
        }
    }

    public static String clientPacketDrainStopReason() {
        return clientPacketDrainStopReason;
    }

    public static int clientPacketDrainTargetQueue() {
        return CLIENT_PACKET_DRAIN_TARGET_QUEUE;
    }

    public static int clientPacketDrainHardMaxPackets() {
        return CLIENT_PACKET_DRAIN_HARD_MAX_PACKETS;
    }

    public static int clientPacketDrainRequestedPackets() {
        return clientPacketDrainRequestedPackets;
    }

    public static int clientPacketDrainBatchTargetPackets() {
        return clientPacketDrainBatchTargetPackets;
    }

    public static int clientPacketDrainRemainingDebt() {
        return clientPacketDrainRemainingDebt;
    }

    /** Monotonic identity of the active/most recently completed claimed batch. */
    public static long clientPacketDrainEpoch() {
        return clientPacketDrainEpoch;
    }

    /** Exact outer queued-handler completions in this batch; queue clear/reset is never counted. */
    public static int clientPacketDrainHandlerCompletions() {
        return clientPacketDrainHandlerCompletions;
    }

    /** Prevents a queued PLAY packet from scheduling itself again when its handler re-enters PacketUtils. */
    public static boolean isProcessingQueuedPacket() {
        return queuedPacketHandleDepth > 0;
    }

    public static boolean isProcessingQueuedPacket(Object owner) {
        return owner != null && owner == queuedPacketHandleOwner
                && queuedPacketHandleDepth > 0;
    }

    /** Marks the exact ListenerAndPacket.handle scope; nesting must not clear the outer drain guard. */
    public static void beginQueuedPacket(Object packet) {
        if (queuedPacketHandleDepth == 0) {
            queuedPacketHandleStartedNanos = System.nanoTime();
            queuedPacketHandleRoot = packet;
        }
        if (queuedPacketHandleDepth < Integer.MAX_VALUE) {
            queuedPacketHandleDepth++;
        }
    }

    public static void beginQueuedPacket(Object owner, Object packet) {
        if (!claimPacketProcessorOwner(owner)) {
            return;
        }
        if (queuedPacketHandleDepth == 0) {
            queuedPacketHandleStartedNanos = System.nanoTime();
            queuedPacketHandleRoot = packet;
            queuedPacketHandleOwner = owner;
        } else if (queuedPacketHandleOwner != owner) {
            markPacketProcessorConflict("nested-owner-conflict");
            return;
        }
        if (queuedPacketHandleDepth < Integer.MAX_VALUE) {
            queuedPacketHandleDepth++;
        }
    }

    /** Called only after PacketProcessor successfully appends a decoded packet to its queue. */
    public static void packetQueued() {
        if (queuedPackets < Integer.MAX_VALUE) {
            queuedPackets++;
        }
        if (!packetQueuePaused && queuedPackets >= PACKET_QUEUE_HIGH_WATERMARK) {
            packetQueuePaused = true;
        }
        BrowserWebSocketChannel.recordDecodedPacketQueue(
                queuedPackets, packetQueuePaused, false, -1.0, null);
    }

    public static void packetQueued(Object owner) {
        if (!claimPacketProcessorOwner(owner)) {
            return;
        }
        if (!packetProcessorAccountingValid) {
            return;
        }
        if (queuedPackets < Integer.MAX_VALUE) {
            queuedPackets++;
        }
        if (!packetQueuePaused && queuedPackets >= PACKET_QUEUE_HIGH_WATERMARK) {
            packetQueuePaused = true;
        }
        BrowserWebSocketChannel.recordDecodedPacketQueue(
                queuedPackets, packetQueuePaused, false, -1.0, null);
    }

    /** Called after a queued packet's handle method returns or throws. */
    public static void packetProcessed() {
        double handleMillis = -1.0;
        String handleType = null;
        long completedHandleNanos = -1L;
        if (queuedPacketHandleDepth > 0) {
            queuedPacketHandleDepth--;
            if (queuedPacketHandleDepth == 0) {
                long elapsedNanos = Math.max(
                        0L, System.nanoTime() - queuedPacketHandleStartedNanos);
                completedHandleNanos = elapsedNanos;
                handleMillis = elapsedNanos / 1_000_000.0;
                if (elapsedNanos > longestQueuedPacketHandleNanos
                        || elapsedNanos >= 50_000_000L) {
                    if (elapsedNanos > longestQueuedPacketHandleNanos) {
                        longestQueuedPacketHandleNanos = elapsedNanos;
                    }
                    handleType = queuedPacketHandleRoot == null
                            ? "unknown"
                            : queuedPacketHandleRoot.getClass().getName();
                }
                queuedPacketHandleStartedNanos = 0L;
                queuedPacketHandleRoot = null;
            }
        }
        if (clientFrameAccountingActive && completedHandleNanos >= 0L) {
            if (clientFramePacketCount < Integer.MAX_VALUE) {
                clientFramePacketCount++;
            }
            if (Long.MAX_VALUE - clientFramePacketHandleNanos < completedHandleNanos) {
                clientFramePacketHandleNanos = Long.MAX_VALUE;
            } else {
                clientFramePacketHandleNanos += completedHandleNanos;
            }
        }
        if (clientPacketDrainActive && completedHandleNanos >= 0L
                && clientPacketDrainHandlerCompletions < Integer.MAX_VALUE) {
            clientPacketDrainHandlerCompletions++;
        }
        if (queuedPackets > 0) {
            queuedPackets--;
        }
        if (packetQueuePaused && queuedPackets <= PACKET_QUEUE_LOW_WATERMARK) {
            packetQueuePaused = false;
        }
        BrowserWebSocketChannel.recordDecodedPacketQueue(
                queuedPackets, packetQueuePaused, true, handleMillis, handleType);
    }

    public static void packetProcessed(Object owner) {
        boolean handlerOwner = owner != null && owner == queuedPacketHandleOwner;
        if (!handlerOwner) {
            return;
        }
        double handleMillis = -1.0;
        String handleType = null;
        long completedHandleNanos = -1L;
        if (queuedPacketHandleDepth > 0) {
            queuedPacketHandleDepth--;
            if (queuedPacketHandleDepth == 0) {
                long elapsedNanos = Math.max(
                        0L, System.nanoTime() - queuedPacketHandleStartedNanos);
                completedHandleNanos = elapsedNanos;
                handleMillis = elapsedNanos / 1_000_000.0;
                if (elapsedNanos > longestQueuedPacketHandleNanos
                        || elapsedNanos >= 50_000_000L) {
                    if (elapsedNanos > longestQueuedPacketHandleNanos) {
                        longestQueuedPacketHandleNanos = elapsedNanos;
                    }
                    handleType = queuedPacketHandleRoot == null
                            ? "unknown"
                            : queuedPacketHandleRoot.getClass().getName();
                }
                queuedPacketHandleStartedNanos = 0L;
                queuedPacketHandleRoot = null;
                queuedPacketHandleOwner = null;
            }
        }
        // A conflict intentionally stops all global queue/frame mutation. The handler scope above
        // is still unwound so a later vanilla packet cannot be mistaken for nested queued work.
        if (owner != packetProcessorOwner || !packetProcessorAccountingValid) {
            // PacketProcessor.close/reset may have retired the owner while this exact queued
            // handler was still unwinding.  Count the handler that really completed, but do not
            // decrement the queue: reset() already cleared the queue and the dropped remainder is
            // reported as unattributed reduction by the frame-boundary evidence.
            if (owner == clientPacketDrainOwner
                    && clientPacketDrainActive
                    && completedHandleNanos >= 0L
                    && clientPacketDrainHandlerCompletions < Integer.MAX_VALUE) {
                clientPacketDrainHandlerCompletions++;
            }
            if (queuedPacketHandleDepth == 0 && packetProcessorOwner == null
                    && !packetProcessorConflictPoisoned) {
                packetProcessorOwnerConflict = false;
                packetProcessorAccountingValid = true;
                packetProcessorFallbackReason = "unbound";
            }
            return;
        }
        if (clientFrameAccountingActive && completedHandleNanos >= 0L) {
            if (clientFramePacketCount < Integer.MAX_VALUE) {
                clientFramePacketCount++;
            }
            if (Long.MAX_VALUE - clientFramePacketHandleNanos < completedHandleNanos) {
                clientFramePacketHandleNanos = Long.MAX_VALUE;
            } else {
                clientFramePacketHandleNanos += completedHandleNanos;
            }
        }
        if (clientPacketDrainActive && completedHandleNanos >= 0L
                && clientPacketDrainHandlerCompletions < Integer.MAX_VALUE) {
            clientPacketDrainHandlerCompletions++;
        }
        if (queuedPackets > 0) {
            queuedPackets--;
        }
        if (packetQueuePaused && queuedPackets <= PACKET_QUEUE_LOW_WATERMARK) {
            packetQueuePaused = false;
        }
        BrowserWebSocketChannel.recordDecodedPacketQueue(
                queuedPackets, packetQueuePaused, true, handleMillis, handleType);
    }

    /** Mirrors PacketProcessor.close after it clears the backing queue. */
    public static void reset() {
        boolean preserveActiveDrainEvidence = clientPacketDrainActive;
        if (preserveActiveDrainEvidence) {
            // PacketProcessor.close can run from inside a queued handler. Preserve its scope and
            // batch epoch until the surrounding runTick wrapper captures exact completions.
            clientPacketDrainStopReason = "interrupted";
            clientPacketDrainRemainingDebt = 0;
        }
        flushClientFrameAccounting();
        clientFramePacketCount = 0;
        clientFramePacketHandleNanos = 0L;
        clientFrameSafeDrainTurns = 0;
        clientFrameVanillaDrainTurns = 0;
        clientFrameAccountingActive = false;
        // A PacketProcessor.close may run from inside ListenerAndPacket.handle. Keep the
        // queued-handler scope alive until packetProcessed() unwinds it, even when no adaptive
        // client drain claim is active. Clearing depth here would make a still-running queued
        // handler look like inline work and can re-enter PacketUtils or lose its completion
        // accounting. A reset outside a handler still clears all scope state because depth is 0.
        if (queuedPacketHandleDepth == 0) {
            queuedPacketHandleDepth = 0;
            queuedPacketHandleStartedNanos = 0L;
            longestQueuedPacketHandleNanos = 0L;
            queuedPacketHandleRoot = null;
        }
        // Clear the passive browser pressure signal before this PacketProcessor can be replaced.
        // No client packet task or Java callback exists outside Minecraft.runTick.
        BrowserClientNetwork.invalidateClientPacketDrain("packet-processor-reset");
        batchPacketLimit = 0;
        packetsRemaining = 0;
        minimumPackets = 0;
        deadlineNanos = 0L;
        if (!preserveActiveDrainEvidence) {
            clientPacketDrainActive = false;
            clientPacketDrainCritical = false;
            clientPacketDrainOwner = null;
            clientPacketDrainOwnerGeneration = 0L;
            clientPacketDrainRequestedPackets = 0;
            clientPacketDrainBatchTargetPackets = 0;
            clientPacketDrainRemainingDebt = 0;
            clientPacketDrainStopReason = "inactive";
            clientPacketDrainHandlerCompletions = 0;
        }
        queuedPackets = 0;
        packetQueuePaused = false;
        BrowserWebSocketChannel.recordDecodedPacketQueue(0, false, false, -1.0, null);
    }

    /**
     * Clears accounting only for the owner that established it. A stale close from another
     * PacketProcessor is ignored, preventing reconnect teardown from clearing the live owner's
     * queue/depth/frame state.
     */
    public static void reset(Object owner) {
        // A close from the active owner retires the epoch.  If a second live owner previously
        // forced a conflict, keep the runtime poisoned after retirement: the static ledger has
        // already lost ownership and must not be rebound against a surviving foreign queue.
        if (owner == null || packetProcessorOwner == null || owner != packetProcessorOwner
                || packetProcessorOwnerConflict || packetProcessorConflictPoisoned
                || !packetProcessorAccountingValid) {
            if (owner != null && (owner != packetProcessorOwner
                    || packetProcessorOwnerConflict
                    || packetProcessorConflictPoisoned
                    || !packetProcessorAccountingValid)) {
                // A conflicted close must not clear the shared ledger.  Keep the runtime poisoned
                // and leave the live owner bound so a foreign owner cannot rebind an empty epoch.
                packetProcessorConflictPoisoned = packetProcessorOwnerConflict
                        || packetProcessorConflictPoisoned;
                packetProcessorAccountingValid = false;
                if (stalePacketProcessorResets < Long.MAX_VALUE) {
                    stalePacketProcessorResets++;
                }
            }
            return;
        }
        reset();
        rememberRetiredPacketProcessorOwner(owner);
        packetProcessorOwner = null;
        packetProcessorGeneration = packetProcessorGeneration == Long.MAX_VALUE
                ? 1L
                : packetProcessorGeneration + 1L;
        packetProcessorConflictPoisoned = false;
        packetProcessorOwnerConflict = queuedPacketHandleDepth > 0;
        packetProcessorAccountingValid = !packetProcessorOwnerConflict;
        packetProcessorFallbackReason = packetProcessorOwnerConflict
                ? "owner-close-during-handler" : "unbound";
        if (!packetProcessorOwnerConflict) {
            queuedPacketHandleOwner = null;
        }
    }

    public static boolean isPacketProcessorAccountingValid(Object owner) {
        return owner != null && owner == packetProcessorOwner
                && packetProcessorAccountingValid && !packetProcessorOwnerConflict;
    }

    public static boolean packetProcessorOwnerConflict() {
        return packetProcessorOwnerConflict;
    }

    public static boolean packetProcessorAccountingValid() {
        return packetProcessorAccountingValid && !packetProcessorOwnerConflict;
    }

    public static long packetProcessorGeneration() {
        return packetProcessorGeneration;
    }

    public static long stalePacketProcessorResets() {
        return stalePacketProcessorResets;
    }

    /** Number of late callbacks rejected because their PacketProcessor was retired. */
    public static long stalePacketProcessorEvents() {
        return stalePacketProcessorEvents;
    }

    /** Number of bounded retired-owner tombstones currently retained. */
    public static int retiredPacketProcessorOwnerCount() {
        return countRetiredPacketProcessorOwners();
    }

    public static String packetProcessorFallbackReason() {
        return packetProcessorFallbackReason;
    }

    private static boolean claimPacketProcessorOwner(Object owner) {
        if (owner == null) {
            markPacketProcessorConflict("null-owner");
            return false;
        }
        if (isRetiredPacketProcessorOwner(owner)) {
            if (stalePacketProcessorEvents < Long.MAX_VALUE) {
                stalePacketProcessorEvents++;
            }
            packetProcessorFallbackReason = "retired-owner-event";
            return false;
        }
        if (packetProcessorOwner == null) {
            if (packetProcessorConflictPoisoned || packetProcessorOwnerConflict
                    || !packetProcessorAccountingValid) {
                packetProcessorFallbackReason = "runtime-accounting-poisoned";
                return false;
            }
            if (queuedPacketHandleDepth > 0 && queuedPacketHandleOwner != owner) {
                markPacketProcessorConflict("owner-while-handler-active");
                return false;
            }
            packetProcessorOwner = owner;
            packetProcessorGeneration = packetProcessorGeneration == Long.MAX_VALUE
                    ? 1L : packetProcessorGeneration + 1L;
            packetProcessorOwnerConflict = false;
            packetProcessorAccountingValid = true;
            packetProcessorFallbackReason = "bound";
            return true;
        }
        if (packetProcessorOwner != owner) {
            markPacketProcessorConflict("packet-processor-owner-conflict");
            return false;
        }
        return packetProcessorAccountingValid && !packetProcessorOwnerConflict;
    }

    private static int countRetiredPacketProcessorOwners() {
        int count = 0;
        for (Object retiredOwner : retiredPacketProcessorOwners) {
            if (retiredOwner != null) {
                count++;
            }
        }
        return count;
    }

    private static boolean isRetiredPacketProcessorOwner(Object owner) {
        for (Object retiredOwner : retiredPacketProcessorOwners) {
            if (retiredOwner == owner) {
                return true;
            }
        }
        return false;
    }

    private static void rememberRetiredPacketProcessorOwner(Object owner) {
        if (owner == null || isRetiredPacketProcessorOwner(owner)) {
            return;
        }
        for (int index = 0; index < retiredPacketProcessorOwners.length; index++) {
            if (retiredPacketProcessorOwners[index] == null) {
                retiredPacketProcessorOwners[index] = owner;
                return;
            }
        }
        retiredPacketProcessorOwners[retiredPacketProcessorOwnerCursor] = owner;
        retiredPacketProcessorOwnerCursor =
                (retiredPacketProcessorOwnerCursor + 1) % retiredPacketProcessorOwners.length;
    }

    private static void markPacketProcessorConflict(String reason) {
        packetProcessorOwnerConflict = true;
        packetProcessorAccountingValid = false;
        packetProcessorConflictPoisoned = true;
        packetProcessorFallbackReason = reason;
        clientPacketDrainActive = false;
        clientPacketDrainCritical = false;
        clientPacketDrainRequestedPackets = 0;
        clientPacketDrainBatchTargetPackets = 0;
        clientPacketDrainRemainingDebt = 0;
        packetQueuePaused = false;
        clientPacketDrainOwner = null;
        clientPacketDrainOwnerGeneration = 0L;
        BrowserWebSocketChannel.recordDecodedPacketQueue(
                queuedPackets, false, false, -1.0, null);
    }
}
