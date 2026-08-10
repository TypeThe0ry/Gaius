package dev.gaius.browser;

import io.netty.channel.browser.BrowserWebSocketChannel;

/** Applies a small time and count budget when draining browser packet queues. */
public final class BrowserPacketScheduler {
    private static final int MAX_PACKETS_PER_BATCH = 16;
    private static final int MIN_WORKER_PACKETS_PER_BATCH = 4;
    private static final int PACKET_QUEUE_HIGH_WATERMARK = 256;
    private static final int PACKET_QUEUE_LOW_WATERMARK = 64;
    private static final long BATCH_BUDGET_NANOS = 2_000_000L;

    private static int packetsRemaining;
    private static int minimumPackets;
    private static long deadlineNanos;
    private static int queuedPackets;
    private static boolean packetQueuePaused;

    private BrowserPacketScheduler() {
    }

    public static void beginBatch() {
        packetsRemaining = MAX_PACKETS_PER_BATCH;
        minimumPackets = BrowserIntegratedServerMain.isWorkerServer()
                ? MIN_WORKER_PACKETS_PER_BATCH
                : 1;
        deadlineNanos = System.nanoTime() + BATCH_BUDGET_NANOS;
    }

    public static boolean shouldProcessNext() {
        if (packetsRemaining <= 0) {
            return false;
        }
        int packetsProcessed = MAX_PACKETS_PER_BATCH - packetsRemaining;
        if (packetsProcessed >= minimumPackets
                && System.nanoTime() >= deadlineNanos) {
            return false;
        }
        packetsRemaining--;
        return true;
    }

    public static boolean hasPendingPackets() {
        return queuedPackets > 0;
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
                queuedPackets, packetQueuePaused, false);
    }

    /** Called after a queued packet's handle method returns or throws. */
    public static void packetProcessed() {
        if (queuedPackets > 0) {
            queuedPackets--;
        }
        if (packetQueuePaused && queuedPackets <= PACKET_QUEUE_LOW_WATERMARK) {
            packetQueuePaused = false;
        }
        BrowserWebSocketChannel.recordDecodedPacketQueue(
                queuedPackets, packetQueuePaused, true);
    }

    /** Mirrors PacketProcessor.close after it clears the backing queue. */
    public static void reset() {
        queuedPackets = 0;
        packetQueuePaused = false;
        BrowserWebSocketChannel.recordDecodedPacketQueue(0, false, false);
    }
}
