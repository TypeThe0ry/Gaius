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

    public static boolean hasPendingPackets() {
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
        clientPacketDrainEpoch = clientPacketDrainEpoch == Long.MAX_VALUE
                ? 1L
                : clientPacketDrainEpoch + 1L;
        clientPacketDrainHandlerCompletions = 0;
        return true;
    }

    /** Marks an exceptional or reset-aborted active drain without deriving success from queue depth. */
    public static void interruptClientPacketDrain() {
        if (clientPacketDrainActive) {
            clientPacketDrainStopReason = "interrupted";
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
        if (!preserveActiveDrainEvidence || queuedPacketHandleDepth == 0) {
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
}
