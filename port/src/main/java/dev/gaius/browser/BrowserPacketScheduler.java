package dev.gaius.browser;

/** Applies a small time and count budget when draining browser packet queues. */
public final class BrowserPacketScheduler {
    private static final int MAX_PACKETS_PER_BATCH = 16;
    private static final int MIN_WORKER_PACKETS_PER_BATCH = 4;
    private static final long BATCH_BUDGET_NANOS = 2_000_000L;

    private static int packetsRemaining;
    private static int minimumPackets;
    private static long deadlineNanos;

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
}
