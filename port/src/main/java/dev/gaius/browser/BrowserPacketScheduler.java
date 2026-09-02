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

    /**
     * A TeaVM runtime normally owns one PacketProcessor, but reconnect and embedded deployments can
     * briefly expose more than one processor in the same JavaScript realm.  Keep a small fixed
     * ledger table instead of sharing queue/drain state or growing an unbounded identity map.
     * Retired slots are intentionally not reused: a late callback from the old object must fail
     * closed rather than being mistaken for a new generation.
     */
    private static final int PACKET_PROCESSOR_LEDGER_LIMIT = 16;

    private static final class PacketProcessorLedger {
        private Object owner;
        private long generation;
        private boolean retired;
        private boolean accountingValid = true;
        private String fallbackReason = "bound";

        private int batchPacketLimit;
        private int packetsRemaining;
        private int minimumPackets;
        private long deadlineNanos;
        private int queuedPackets;
        private boolean packetQueuePaused;
        private boolean clientPacketDrainActive;
        private boolean clientPacketDrainCritical;
        private int clientPacketDrainRequestedPackets;
        private int clientPacketDrainBatchTargetPackets;
        private int clientPacketDrainRemainingDebt;
        private String clientPacketDrainStopReason = "inactive";
        private long clientPacketDrainEpoch;
        private int clientPacketDrainHandlerCompletions;
        private int queuedPacketHandleDepth;
        private long queuedPacketHandleStartedNanos;
        private long longestQueuedPacketHandleNanos;
        private Object queuedPacketHandleRoot;

        private long clientFrameSequence;
        private int clientFramePacketCount;
        private long clientFramePacketHandleNanos;
        private int clientFrameSafeDrainTurns;
        private int clientFrameVanillaDrainTurns;
        private boolean clientFrameAccountingActive;

        private void clearAfterReset(boolean preserveHandlerScope) {
            boolean preserveActiveDrainEvidence = clientPacketDrainActive;
            if (preserveActiveDrainEvidence) {
                clientPacketDrainStopReason = "interrupted";
                clientPacketDrainRemainingDebt = 0;
            }
            if (!preserveHandlerScope) {
                queuedPacketHandleDepth = 0;
                queuedPacketHandleStartedNanos = 0L;
                longestQueuedPacketHandleNanos = 0L;
                queuedPacketHandleRoot = null;
            }
            batchPacketLimit = 0;
            packetsRemaining = 0;
            minimumPackets = 0;
            deadlineNanos = 0L;
            clientFramePacketCount = 0;
            clientFramePacketHandleNanos = 0L;
            clientFrameSafeDrainTurns = 0;
            clientFrameVanillaDrainTurns = 0;
            clientFrameAccountingActive = false;
            if (!preserveActiveDrainEvidence) {
                clientPacketDrainActive = false;
                clientPacketDrainCritical = false;
                clientPacketDrainRequestedPackets = 0;
                clientPacketDrainBatchTargetPackets = 0;
                clientPacketDrainRemainingDebt = 0;
                clientPacketDrainStopReason = "inactive";
                clientPacketDrainHandlerCompletions = 0;
            }
            queuedPackets = 0;
            packetQueuePaused = false;
        }
    }

    private static final PacketProcessorLedger[] packetProcessorLedgers =
            new PacketProcessorLedger[PACKET_PROCESSOR_LEDGER_LIMIT];
    private static Object packetProcessorAccessOwner;
    private static long packetProcessorAccessGeneration;
    private static long nextPacketProcessorGeneration = 1L;
    private static long packetProcessorLedgerSlotExhaustions;
    /** Once all retired owner slots are consumed, keep adaptive accounting poisoned. */
    private static boolean packetProcessorLedgerExhausted;
    private static long packetProcessorUnknownOwnerEvents;
    private static long pendingClientFrameSequence;
    private static boolean pendingClientFrame;

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

    private static PacketProcessorLedger findPacketProcessorLedger(Object owner) {
        if (owner == null) {
            return null;
        }
        for (PacketProcessorLedger ledger : packetProcessorLedgers) {
            if (ledger != null && ledger.owner == owner) {
                return ledger;
            }
        }
        return null;
    }

    private static PacketProcessorLedger currentPacketProcessorLedger() {
        if (packetProcessorAccessOwner == null) {
            return null;
        }
        PacketProcessorLedger ledger = findPacketProcessorLedger(packetProcessorAccessOwner);
        if (ledger == null || ledger.retired
                || ledger.generation != packetProcessorAccessGeneration) {
            return null;
        }
        return ledger;
    }

    private static int activePacketProcessorLedgerCount() {
        int count = 0;
        for (PacketProcessorLedger ledger : packetProcessorLedgers) {
            if (ledger != null && !ledger.retired) {
                count++;
            }
        }
        return count;
    }

    /** Returns a conservative aggregate for the owner-less browser bridge callback. */
    private static int aggregateQueuedPackets() {
        long total = 0L;
        int owners = 0;
        for (PacketProcessorLedger ledger : packetProcessorLedgers) {
            if (ledger != null && !ledger.retired && ledger.accountingValid) {
                owners++;
                total += Math.max(0, ledger.queuedPackets);
            }
        }
        if (owners == 0) {
            return Math.max(0, queuedPackets);
        }
        return total >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    private static boolean aggregateQueuePaused() {
        if (aggregateQueuedPackets() >= PACKET_QUEUE_HIGH_WATERMARK) {
            return true;
        }
        for (PacketProcessorLedger ledger : packetProcessorLedgers) {
            if (ledger != null && !ledger.retired && ledger.accountingValid
                    && ledger.packetQueuePaused) {
                return true;
            }
        }
        return false;
    }

    /**
     * The current bridge API has no owner argument.  Publish a conservative aggregate so an
     * owner reset cannot clear another live processor's queue/pause state.  The Java scheduler
     * itself remains owner-scoped; an ambiguous bridge state is therefore safe but may pause more
     * channels than necessary until the owner-aware bridge API is available.
     */
    private static void recordOwnerQueue(
            PacketProcessorLedger ledger, boolean processed, double handleMillis, String handleType) {
        // A late callback from a retired owner may arrive after a fresh owner has claimed the
        // runtime.  The bridge API is owner-less, so do not let that stale event overwrite the
        // fresh owner's queue/pause telemetry.  During the short post-reset unwind there is no
        // current owner yet; retaining that evidence is safe and lets the frame finalizer report
        // the retired drain accurately.
        if (ledger != null && ledger.retired && packetProcessorOwner != null
                && packetProcessorOwner != ledger.owner) {
            return;
        }
        BrowserWebSocketChannel.recordDecodedPacketQueue(
                aggregateQueuedPackets(), aggregateQueuePaused(), processed, handleMillis, handleType);
    }

    private static long nextPacketProcessorGeneration() {
        long generation = nextPacketProcessorGeneration;
        nextPacketProcessorGeneration = generation == Long.MAX_VALUE ? 1L : generation + 1L;
        return generation;
    }

    private static PacketProcessorLedger allocatePacketProcessorLedger(Object owner) {
        for (int index = 0; index < packetProcessorLedgers.length; index++) {
            if (packetProcessorLedgers[index] == null) {
                PacketProcessorLedger ledger = new PacketProcessorLedger();
                ledger.owner = owner;
                ledger.generation = nextPacketProcessorGeneration();
                packetProcessorLedgers[index] = ledger;
                return ledger;
            }
        }
        if (packetProcessorLedgerSlotExhaustions < Long.MAX_VALUE) {
            packetProcessorLedgerSlotExhaustions++;
        }
        packetProcessorLedgerExhausted = true;
        return null;
    }

    private static void selectPacketProcessorLedger(PacketProcessorLedger ledger) {
        packetProcessorAccessOwner = ledger == null ? null : ledger.owner;
        packetProcessorAccessGeneration = ledger == null ? 0L : ledger.generation;
        packetProcessorOwner = packetProcessorAccessOwner;
        packetProcessorGeneration = packetProcessorAccessGeneration;
        packetProcessorOwnerConflict = false;
        packetProcessorAccountingValid = ledger != null && ledger.accountingValid;
        packetProcessorFallbackReason = ledger == null ? "unbound" : ledger.fallbackReason;
        if (ledger != null) {
            queuedPacketHandleDepth = ledger.queuedPacketHandleDepth;
        }
    }

    private static PacketProcessorLedger packetProcessorLedger(Object owner) {
        PacketProcessorLedger ledger = findPacketProcessorLedger(owner);
        if (ledger == null || ledger.retired || !ledger.accountingValid
                || packetProcessorOwnerConflict || packetProcessorConflictPoisoned
                || (packetProcessorOwner != null && packetProcessorOwner != owner)) {
            return null;
        }
        return ledger;
    }

    private static PacketProcessorLedger packetProcessorLedgerIncludingRetired(Object owner) {
        return findPacketProcessorLedger(owner);
    }

    private static void noteUnknownPacketProcessorOwner(String reason) {
        if (packetProcessorUnknownOwnerEvents < Long.MAX_VALUE) {
            packetProcessorUnknownOwnerEvents++;
        }
        packetProcessorFallbackReason = reason;
        packetProcessorOwnerConflict = true;
        packetProcessorAccountingValid = false;
    }

    private static void startPendingOwnerFrame(PacketProcessorLedger ledger) {
        if (ledger != null && pendingClientFrame) {
            startOwnerClientFrame(ledger);
        }
    }

    private static boolean shouldProcessNextOwner(PacketProcessorLedger ledger) {
        if (ledger == null || ledger.retired || !ledger.accountingValid) {
            return false;
        }
        if (ledger.packetsRemaining <= 0) {
            if (ledger.clientPacketDrainActive) {
                ledger.clientPacketDrainStopReason =
                        ledger.clientPacketDrainRequestedPackets > CLIENT_PACKET_DRAIN_HARD_MAX_PACKETS
                                ? "hard-cap" : "target";
            }
            return false;
        }
        int packetsProcessed = ledger.batchPacketLimit - ledger.packetsRemaining;
        if (packetsProcessed >= ledger.minimumPackets
                && System.nanoTime() >= ledger.deadlineNanos) {
            if (ledger.clientPacketDrainActive) {
                ledger.clientPacketDrainStopReason = "deadline";
            }
            return false;
        }
        ledger.packetsRemaining--;
        return true;
    }

    private static boolean tryBeginOwnerClientPacketDrain(
            PacketProcessorLedger ledger, boolean critical) {
        if (ledger == null || ledger.retired || !ledger.accountingValid
                || BrowserIntegratedServerMain.isWorkerServer()
                || ledger.clientPacketDrainActive
                || ledger.queuedPacketHandleDepth > 0
                || ledger.queuedPackets < CLIENT_PACKET_DRAIN_THRESHOLD) {
            return false;
        }
        ledger.clientPacketDrainActive = true;
        ledger.clientPacketDrainCritical = critical;
        ledger.clientPacketDrainEpoch = ledger.clientPacketDrainEpoch == Long.MAX_VALUE
                ? 1L : ledger.clientPacketDrainEpoch + 1L;
        ledger.clientPacketDrainHandlerCompletions = 0;
        return true;
    }

    private static void interruptOwnerClientPacketDrain(PacketProcessorLedger ledger) {
        if (ledger != null && ledger.clientPacketDrainActive) {
            ledger.clientPacketDrainStopReason = "interrupted";
        }
    }

    private static void finishOwnerClientPacketDrain(PacketProcessorLedger ledger) {
        if (ledger == null || !ledger.clientPacketDrainActive) {
            return;
        }
        ledger.clientPacketDrainRemainingDebt = Math.max(
                0, ledger.queuedPackets - CLIENT_PACKET_DRAIN_TARGET_QUEUE);
        if ("pending".equals(ledger.clientPacketDrainStopReason)) {
            ledger.clientPacketDrainStopReason = ledger.clientPacketDrainRemainingDebt == 0
                    ? "empty" : "interrupted";
        }
        ledger.clientPacketDrainActive = false;
        ledger.clientPacketDrainCritical = false;
    }

    private static void markLedgerConflict(PacketProcessorLedger ledger, String reason) {
        if (ledger == null) {
            return;
        }
        ledger.accountingValid = false;
        ledger.fallbackReason = reason;
        interruptOwnerClientPacketDrain(ledger);
        packetProcessorOwnerConflict = true;
        packetProcessorAccountingValid = false;
        packetProcessorConflictPoisoned = true;
        packetProcessorFallbackReason = reason;
    }

    private static void resetOwnerLedger(PacketProcessorLedger ledger) {
        if (ledger == null || ledger.retired) {
            return;
        }
        boolean preserveHandlerScope = ledger.queuedPacketHandleDepth > 0;
        Object owner = ledger.owner;
        long generation = ledger.generation;
        ledger.clearAfterReset(preserveHandlerScope);
        ledger.retired = true;
        ledger.accountingValid = false;
        ledger.fallbackReason = "retired-owner";
        rememberRetiredPacketProcessorOwner(owner);
        BrowserClientNetwork.invalidateClientPacketDrain("packet-processor-reset");
        // The legacy fields remain for worker/old entry points. Clear them at the same lifecycle
        // boundary so a newly constructed processor can never observe the retired queue state.
        batchPacketLimit = 0;
        packetsRemaining = 0;
        minimumPackets = 0;
        deadlineNanos = 0L;
        clientPacketDrainActive = false;
        clientPacketDrainCritical = false;
        clientPacketDrainOwner = null;
        clientPacketDrainOwnerGeneration = 0L;
        clientPacketDrainRequestedPackets = 0;
        clientPacketDrainBatchTargetPackets = 0;
        clientPacketDrainRemainingDebt = 0;
        clientPacketDrainStopReason = preserveHandlerScope ? "interrupted" : "inactive";
        clientPacketDrainHandlerCompletions = 0;
        queuedPackets = 0;
        packetQueuePaused = false;
        clientFrameAccountingActive = false;
        clientFramePacketCount = 0;
        clientFramePacketHandleNanos = 0L;
        queuedPacketHandleDepth = ledger.queuedPacketHandleDepth;
        queuedPacketHandleOwner = preserveHandlerScope ? owner : null;
        BrowserWebSocketChannel.recordDecodedPacketQueue(0, false, false, -1.0, null);
        if (packetProcessorAccessOwner == owner
                && packetProcessorAccessGeneration == generation) {
            packetProcessorAccessOwner = null;
            packetProcessorAccessGeneration = 0L;
        }
        if (packetProcessorOwner == owner
                && packetProcessorGeneration == generation) {
            packetProcessorOwner = null;
            packetProcessorGeneration = generation == Long.MAX_VALUE ? 1L : generation + 1L;
            packetProcessorOwnerConflict = preserveHandlerScope;
            packetProcessorAccountingValid = !preserveHandlerScope;
            packetProcessorFallbackReason = preserveHandlerScope
                    ? "owner-close-during-handler" : "unbound";
        }
    }

    private static void completeOwnerPacket(PacketProcessorLedger ledger) {
        if (ledger == null || ledger.queuedPacketHandleDepth <= 0) {
            return;
        }
        ledger.queuedPacketHandleDepth--;
        // Keep the legacy depth mirror coherent while this owner is current.  A late callback from
        // a retired owner must never overwrite the mirror belonging to a fresh PacketProcessor.
        if ((packetProcessorOwner == ledger.owner && packetProcessorGeneration == ledger.generation)
                || (packetProcessorOwner == null && queuedPacketHandleOwner == ledger.owner
                && ledger.retired)) {
            queuedPacketHandleDepth = ledger.queuedPacketHandleDepth;
        }
        long completedHandleNanos = -1L;
        double handleMillis = -1.0;
        String handleType = null;
        if (ledger.queuedPacketHandleDepth == 0) {
            long elapsedNanos = Math.max(
                    0L, System.nanoTime() - ledger.queuedPacketHandleStartedNanos);
            completedHandleNanos = elapsedNanos;
            handleMillis = elapsedNanos / 1_000_000.0;
            if (elapsedNanos > ledger.longestQueuedPacketHandleNanos
                    || elapsedNanos >= 50_000_000L) {
                if (elapsedNanos > ledger.longestQueuedPacketHandleNanos) {
                    ledger.longestQueuedPacketHandleNanos = elapsedNanos;
                }
                handleType = ledger.queuedPacketHandleRoot == null
                        ? "unknown" : ledger.queuedPacketHandleRoot.getClass().getName();
            }
            ledger.queuedPacketHandleStartedNanos = 0L;
            ledger.queuedPacketHandleRoot = null;
        }
        if (completedHandleNanos >= 0L && ledger.clientFrameAccountingActive) {
            if (ledger.clientFramePacketCount < Integer.MAX_VALUE) {
                ledger.clientFramePacketCount++;
            }
            if (Long.MAX_VALUE - ledger.clientFramePacketHandleNanos < completedHandleNanos) {
                ledger.clientFramePacketHandleNanos = Long.MAX_VALUE;
            } else {
                ledger.clientFramePacketHandleNanos += completedHandleNanos;
            }
        }
        if (completedHandleNanos >= 0L && ledger.clientPacketDrainActive
                && ledger.clientPacketDrainHandlerCompletions < Integer.MAX_VALUE) {
            ledger.clientPacketDrainHandlerCompletions++;
        }
        if (!ledger.retired && ledger.accountingValid) {
            if (ledger.queuedPackets > 0) {
                ledger.queuedPackets--;
            }
            if (ledger.packetQueuePaused && ledger.queuedPackets <= PACKET_QUEUE_LOW_WATERMARK) {
                ledger.packetQueuePaused = false;
            }
        }
        if (ledger.queuedPacketHandleDepth == 0 && queuedPacketHandleOwner == ledger.owner) {
            queuedPacketHandleOwner = null;
        }
        recordOwnerQueue(ledger, true, handleMillis, handleType);
    }

    private static void queueOwnerPacket(PacketProcessorLedger ledger) {
        if (ledger == null || ledger.retired || !ledger.accountingValid) {
            return;
        }
        if (ledger.queuedPackets < Integer.MAX_VALUE) {
            ledger.queuedPackets++;
        }
        if (!ledger.packetQueuePaused && ledger.queuedPackets >= PACKET_QUEUE_HIGH_WATERMARK) {
            ledger.packetQueuePaused = true;
        }
        recordOwnerQueue(ledger, false, -1.0, null);
    }

    private static void beginQueuedPacketOwner(PacketProcessorLedger ledger, Object packet) {
        if (ledger == null || ledger.retired || !ledger.accountingValid) {
            return;
        }
        if (queuedPacketHandleOwner != null && queuedPacketHandleOwner != ledger.owner) {
            markLedgerConflict(ledger, "nested-owner-conflict");
            return;
        }
        if (ledger.queuedPacketHandleDepth == 0) {
            ledger.queuedPacketHandleStartedNanos = System.nanoTime();
            ledger.queuedPacketHandleRoot = packet;
            queuedPacketHandleOwner = ledger.owner;
        }
        if (ledger.queuedPacketHandleDepth < Integer.MAX_VALUE) {
            ledger.queuedPacketHandleDepth++;
        }
        if (packetProcessorOwner == ledger.owner) {
            queuedPacketHandleDepth = ledger.queuedPacketHandleDepth;
        }
    }

    public static void beginBatch() {
        PacketProcessorLedger ledger = currentPacketProcessorLedger();
        if (ledger != null) {
            beginOwnerBatch(ledger);
            return;
        }
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
        PacketProcessorLedger ledger = claimPacketProcessorLedger(owner);
        if (ledger == null) {
            return false;
        }
        beginOwnerBatch(ledger);
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
        PacketProcessorLedger previous = currentPacketProcessorLedger();
        if (previous != null) {
            flushOwnerClientFrameAccounting(previous);
        } else {
            flushClientFrameAccounting();
        }
        pendingClientFrameSequence = pendingClientFrameSequence == Long.MAX_VALUE
                ? 1L
                : pendingClientFrameSequence + 1L;
        pendingClientFrame = true;
    }

    private static void beginOwnerBatch(PacketProcessorLedger ledger) {
        boolean workerServer = BrowserIntegratedServerMain.isWorkerServer();
        if (ledger.clientPacketDrainActive && !workerServer) {
            ledger.clientPacketDrainRequestedPackets = Math.max(
                    1, ledger.queuedPackets - CLIENT_PACKET_DRAIN_TARGET_QUEUE);
            ledger.clientPacketDrainBatchTargetPackets = Math.min(
                    CLIENT_PACKET_DRAIN_HARD_MAX_PACKETS,
                    ledger.clientPacketDrainRequestedPackets);
            ledger.clientPacketDrainRemainingDebt = Math.max(
                    0, ledger.queuedPackets - CLIENT_PACKET_DRAIN_TARGET_QUEUE);
            ledger.clientPacketDrainStopReason = "pending";
            ledger.batchPacketLimit = ledger.clientPacketDrainBatchTargetPackets;
            ledger.minimumPackets = 1;
        } else {
            ledger.batchPacketLimit = MAX_PACKETS_PER_BATCH;
            ledger.minimumPackets = workerServer
                    ? MIN_WORKER_PACKETS_PER_BATCH
                    : 1;
        }
        ledger.packetsRemaining = ledger.batchPacketLimit;
        ledger.deadlineNanos = System.nanoTime() + BATCH_BUDGET_NANOS;
        if (ledger.clientFrameAccountingActive && !workerServer) {
            if (ledger.clientPacketDrainActive) {
                if (ledger.clientFrameSafeDrainTurns < Integer.MAX_VALUE) {
                    ledger.clientFrameSafeDrainTurns++;
                }
            } else if (ledger.clientFrameVanillaDrainTurns < Integer.MAX_VALUE) {
                ledger.clientFrameVanillaDrainTurns++;
            }
        }
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

    private static void flushOwnerClientFrameAccounting(PacketProcessorLedger ledger) {
        if (!ledger.clientFrameAccountingActive || ledger.clientFramePacketCount == 0) {
            return;
        }
        BrowserClientNetwork.recordClientPacketFrame(
                ledger.clientFrameSequence,
                ledger.clientFrameSafeDrainTurns,
                ledger.clientFrameVanillaDrainTurns,
                ledger.clientFramePacketCount,
                ledger.clientFramePacketHandleNanos / 1_000_000.0);
    }

    private static void startOwnerClientFrame(PacketProcessorLedger ledger) {
        flushOwnerClientFrameAccounting(ledger);
        ledger.clientFrameSequence = pendingClientFrame
                ? pendingClientFrameSequence
                : ledger.clientFrameSequence == Long.MAX_VALUE
                        ? 1L : ledger.clientFrameSequence + 1L;
        ledger.clientFramePacketCount = 0;
        ledger.clientFramePacketHandleNanos = 0L;
        ledger.clientFrameSafeDrainTurns = 0;
        ledger.clientFrameVanillaDrainTurns = 0;
        ledger.clientFrameAccountingActive = true;
        pendingClientFrame = false;
    }

    public static long currentClientFrameSequence() {
        PacketProcessorLedger ledger = currentPacketProcessorLedger();
        return ledger == null ? clientFrameSequence : ledger.clientFrameSequence;
    }

    public static int queuedPacketCount() {
        PacketProcessorLedger ledger = currentPacketProcessorLedger();
        return ledger == null ? aggregateQueuedPackets() : ledger.queuedPackets;
    }

    public static int queuedPacketHandleDepth() {
        PacketProcessorLedger ledger = currentPacketProcessorLedger();
        return ledger == null ? queuedPacketHandleDepth : ledger.queuedPacketHandleDepth;
    }

    public static boolean isPacketQueuePaused() {
        PacketProcessorLedger ledger = currentPacketProcessorLedger();
        return ledger == null ? aggregateQueuePaused() : ledger.packetQueuePaused;
    }

    public static boolean shouldProcessNext() {
        PacketProcessorLedger ledger = currentPacketProcessorLedger();
        if (ledger != null) {
            return shouldProcessNextOwner(ledger);
        }
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
        PacketProcessorLedger ledger = packetProcessorLedger(owner);
        return shouldProcessNextOwner(ledger);
    }

    public static boolean hasPendingPackets() {
        return aggregateQueuedPackets() > 0;
    }

    /**
     * Owner-aware transition check. During a conflict it is conservative: transition packets stay
     * on the PacketProcessor FIFO rather than being incorrectly inlined ahead of another owner.
     */
    public static boolean hasPendingPackets(Object owner) {
        PacketProcessorLedger ledger = packetProcessorLedger(owner);
        if (ledger == null) {
            return true;
        }
        return ledger.queuedPackets > 0;
    }

    /**
     * Claims the single vanilla PacketProcessor call at Minecraft's scheduled runTick boundary.
     *
     * <p>Pressure and critical modes both target the exact 64 -> 63 crossing. A 256-packet hard
     * ceiling is only a clock-failure guard; the ordinary stop condition remains the same
     * two-millisecond deadline and the original FIFO owner.</p>
     */
    public static boolean tryBeginClientPacketDrain(boolean critical) {
        PacketProcessorLedger ledger = currentPacketProcessorLedger();
        if (ledger != null) {
            return tryBeginOwnerClientPacketDrain(ledger, critical);
        }
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
        PacketProcessorLedger ledger = claimPacketProcessorLedger(owner);
        if (ledger == null) {
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
        PacketProcessorLedger ledger = currentPacketProcessorLedger();
        if (ledger != null) {
            interruptOwnerClientPacketDrain(ledger);
            return;
        }
        if (clientPacketDrainActive) {
            clientPacketDrainStopReason = "interrupted";
        }
    }

    public static void interruptClientPacketDrain(Object owner) {
        interruptOwnerClientPacketDrain(packetProcessorLedgerIncludingRetired(owner));
    }

    /** Releases the client drain claim without changing the exact queue or its FIFO. */
    public static void finishClientPacketDrain() {
        PacketProcessorLedger ledger = currentPacketProcessorLedger();
        if (ledger != null) {
            finishOwnerClientPacketDrain(ledger);
            return;
        }
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
        PacketProcessorLedger ledger = packetProcessorLedgerIncludingRetired(owner);
        boolean ownerScopedDrain = ledger != null && owner != null && owner == ledger.owner
                && ledger.generation > 0L && ledger.clientPacketDrainActive;
        boolean currentOwner = owner == packetProcessorOwner
                && packetProcessorGeneration == clientPacketDrainOwnerGeneration;
        boolean retiredOwnerAfterReset = ledger != null && ledger.retired
                && isRetiredPacketProcessorOwner(owner);
        if (ledger == null || owner == null
                || (owner != clientPacketDrainOwner && !ownerScopedDrain)
                || (clientPacketDrainOwnerGeneration <= 0L && !ownerScopedDrain)
                || (!ownerScopedDrain && !currentOwner && !retiredOwnerAfterReset)) {
            return;
        }
        finishOwnerClientPacketDrain(ledger);
        if (owner == clientPacketDrainOwner) {
            clientPacketDrainOwner = null;
            clientPacketDrainOwnerGeneration = 0L;
        }
    }

    public static String clientPacketDrainStopReason() {
        PacketProcessorLedger ledger = currentPacketProcessorLedger();
        return ledger == null ? clientPacketDrainStopReason : ledger.clientPacketDrainStopReason;
    }

    public static int clientPacketDrainTargetQueue() {
        return CLIENT_PACKET_DRAIN_TARGET_QUEUE;
    }

    public static int clientPacketDrainHardMaxPackets() {
        return CLIENT_PACKET_DRAIN_HARD_MAX_PACKETS;
    }

    public static int clientPacketDrainRequestedPackets() {
        PacketProcessorLedger ledger = currentPacketProcessorLedger();
        return ledger == null ? clientPacketDrainRequestedPackets
                : ledger.clientPacketDrainRequestedPackets;
    }

    public static int clientPacketDrainBatchTargetPackets() {
        PacketProcessorLedger ledger = currentPacketProcessorLedger();
        return ledger == null ? clientPacketDrainBatchTargetPackets
                : ledger.clientPacketDrainBatchTargetPackets;
    }

    public static int clientPacketDrainRemainingDebt() {
        PacketProcessorLedger ledger = currentPacketProcessorLedger();
        return ledger == null ? clientPacketDrainRemainingDebt : ledger.clientPacketDrainRemainingDebt;
    }

    /** Monotonic identity of the active/most recently completed claimed batch. */
    public static long clientPacketDrainEpoch() {
        PacketProcessorLedger ledger = currentPacketProcessorLedger();
        return ledger == null ? clientPacketDrainEpoch : ledger.clientPacketDrainEpoch;
    }

    /** Exact outer queued-handler completions in this batch; queue clear/reset is never counted. */
    public static int clientPacketDrainHandlerCompletions() {
        PacketProcessorLedger ledger = currentPacketProcessorLedger();
        return ledger == null ? clientPacketDrainHandlerCompletions
                : ledger.clientPacketDrainHandlerCompletions;
    }

    /** Prevents a queued PLAY packet from scheduling itself again when its handler re-enters PacketUtils. */
    public static boolean isProcessingQueuedPacket() {
        return queuedPacketHandleDepth > 0;
    }

    public static boolean isProcessingQueuedPacket(Object owner) {
        PacketProcessorLedger ledger = packetProcessorLedger(owner);
        return ledger != null && ledger.queuedPacketHandleDepth > 0;
    }

    /** Marks the exact ListenerAndPacket.handle scope; nesting must not clear the outer drain guard. */
    public static void beginQueuedPacket(Object packet) {
        PacketProcessorLedger ledger = currentPacketProcessorLedger();
        if (ledger != null) {
            beginQueuedPacketOwner(ledger, packet);
            return;
        }
        if (queuedPacketHandleDepth == 0) {
            queuedPacketHandleStartedNanos = System.nanoTime();
            queuedPacketHandleRoot = packet;
        }
        if (queuedPacketHandleDepth < Integer.MAX_VALUE) {
            queuedPacketHandleDepth++;
        }
    }

    public static void beginQueuedPacket(Object owner, Object packet) {
        PacketProcessorLedger ledger = packetProcessorLedger(owner);
        if (ledger == null) {
            return;
        }
        beginQueuedPacketOwner(ledger, packet);
    }

    /** Called only after PacketProcessor successfully appends a decoded packet to its queue. */
    public static void packetQueued() {
        PacketProcessorLedger ledger = currentPacketProcessorLedger();
        if (ledger != null) {
            queueOwnerPacket(ledger);
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

    public static void packetQueued(Object owner) {
        queueOwnerPacket(packetProcessorLedger(owner));
    }

    /** Called after a queued packet's handle method returns or throws. */
    public static void packetProcessed() {
        PacketProcessorLedger ledger = currentPacketProcessorLedger();
        if (ledger != null) {
            completeOwnerPacket(ledger);
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
        PacketProcessorLedger ledger = packetProcessorLedgerIncludingRetired(owner);
        if (ledger == null) {
            noteUnknownPacketProcessorOwner("unknown-owner-event");
            return;
        }
        completeOwnerPacket(ledger);
        // Keep the legacy mismatch path explicit for runtimes that still report the owner-less
        // fields while a callback is unwinding. The owner-scoped ledger was already completed
        // above; this branch only preserves legacy diagnostics and never decrements another queue.
        if (owner != packetProcessorOwner || !packetProcessorAccountingValid) {
            if (owner == clientPacketDrainOwner && clientPacketDrainActive
                    && ledger.queuedPacketHandleDepth == 0
                    && clientPacketDrainHandlerCompletions < Integer.MAX_VALUE) {
                clientPacketDrainHandlerCompletions++;
            }
            if (queuedPacketHandleDepth == 0 && packetProcessorOwner == null
                    && !packetProcessorConflictPoisoned) {
                packetProcessorOwnerConflict = false;
                packetProcessorAccountingValid = true;
                packetProcessorFallbackReason = "unbound";
            }
        }
    }

    /** Mirrors PacketProcessor.close after it clears the backing queue. */
    public static void reset() {
        PacketProcessorLedger ledger = currentPacketProcessorLedger();
        if (ledger != null) {
            resetOwnerLedger(ledger);
            return;
        }
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
        PacketProcessorLedger ledger = packetProcessorLedgerIncludingRetired(owner);
        if (ledger == null) {
            noteUnknownPacketProcessorOwner("unknown-owner-reset");
            if (stalePacketProcessorResets < Long.MAX_VALUE) {
                stalePacketProcessorResets++;
            }
            return;
        }
        if (ledger.retired) {
            if (stalePacketProcessorResets < Long.MAX_VALUE) {
                stalePacketProcessorResets++;
            }
            packetProcessorFallbackReason = "retired-owner-reset";
            return;
        }
        if (owner == null || owner != packetProcessorOwner || packetProcessorOwnerConflict
                || packetProcessorConflictPoisoned || !packetProcessorAccountingValid) {
            if (stalePacketProcessorResets < Long.MAX_VALUE) {
                stalePacketProcessorResets++;
            }
            packetProcessorOwnerConflict = packetProcessorOwnerConflict
                    || packetProcessorConflictPoisoned;
            packetProcessorAccountingValid = false;
            packetProcessorFallbackReason = "foreign-owner-reset";
            return;
        }
        resetOwnerLedger(ledger);
    }

    public static boolean isPacketProcessorAccountingValid(Object owner) {
        PacketProcessorLedger ledger = packetProcessorLedger(owner);
        return ledger != null;
    }

    public static boolean packetProcessorOwnerConflict() {
        return packetProcessorOwnerConflict;
    }

    public static boolean packetProcessorAccountingValid() {
        PacketProcessorLedger ledger = currentPacketProcessorLedger();
        if (ledger != null) {
            return ledger.accountingValid && !ledger.retired;
        }
        return packetProcessorAccountingValid && !packetProcessorOwnerConflict;
    }

    public static long packetProcessorGeneration() {
        PacketProcessorLedger ledger = currentPacketProcessorLedger();
        return ledger == null ? packetProcessorGeneration : ledger.generation;
    }

    public static boolean packetProcessorLedgerExhausted() {
        return packetProcessorLedgerExhausted;
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
        PacketProcessorLedger ledger = currentPacketProcessorLedger();
        return ledger == null ? packetProcessorFallbackReason : ledger.fallbackReason;
    }

    private static boolean claimPacketProcessorOwner(Object owner) {
        if (owner == null) {
            markPacketProcessorConflict("null-owner");
            return false;
        }
        if (packetProcessorOwner == null) {
            if (clientPacketDrainActive) {
                // A close/reset from inside the active drain handler intentionally leaves the
                // drain claim live until the surrounding frame finally block releases it.  Do not
                // let a reentrant/new PacketProcessor claim the empty owner slot in that window:
                // the retired owner's finish would then fail its generation check and strand the
                // global drain active forever.
                packetProcessorFallbackReason = "active-drain-owner-retiring";
                return false;
            }
        }
        return claimPacketProcessorLedger(owner) != null;
    }

    private static PacketProcessorLedger claimPacketProcessorLedger(Object owner) {
        if (isRetiredPacketProcessorOwner(owner)) {
            if (stalePacketProcessorEvents < Long.MAX_VALUE) {
                stalePacketProcessorEvents++;
            }
            packetProcessorFallbackReason = "retired-owner-event";
            return null;
        }
        if (packetProcessorLedgerExhausted) {
            packetProcessorOwnerConflict = true;
            packetProcessorAccountingValid = false;
            packetProcessorConflictPoisoned = true;
            packetProcessorFallbackReason = "ledger-slot-exhausted";
            return null;
        }
        if (packetProcessorConflictPoisoned || packetProcessorOwnerConflict
                || (!packetProcessorAccountingValid && packetProcessorOwner != null)) {
            packetProcessorFallbackReason = "runtime-accounting-poisoned";
            return null;
        }
        PacketProcessorLedger ledger = findPacketProcessorLedger(owner);
        if (packetProcessorOwner != null && packetProcessorOwner != owner) {
            markPacketProcessorConflict("packet-processor-owner-conflict");
            return null;
        }
        if (ledger == null && activePacketProcessorLedgerCount() > 0) {
            markPacketProcessorConflict("packet-processor-owner-conflict");
            return null;
        }
        if (ledger == null) {
            ledger = allocatePacketProcessorLedger(owner);
            if (ledger == null) {
                packetProcessorLedgerExhausted = true;
                packetProcessorFallbackReason = "ledger-slot-exhausted";
                packetProcessorOwnerConflict = true;
                packetProcessorAccountingValid = false;
                packetProcessorConflictPoisoned = true;
                return null;
            }
        }
        if (ledger.retired || !ledger.accountingValid) {
            if (stalePacketProcessorEvents < Long.MAX_VALUE) {
                stalePacketProcessorEvents++;
            }
            packetProcessorFallbackReason = ledger.fallbackReason;
            return null;
        }
        selectPacketProcessorLedger(ledger);
        startPendingOwnerFrame(ledger);
        return ledger;
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
        int queuedBeforeConflict = aggregateQueuedPackets();
        for (PacketProcessorLedger ledger : packetProcessorLedgers) {
            if (ledger != null && !ledger.retired) {
                ledger.accountingValid = false;
                ledger.fallbackReason = reason;
                interruptOwnerClientPacketDrain(ledger);
            }
        }
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
                queuedBeforeConflict, false, false, -1.0, null);
    }
}
