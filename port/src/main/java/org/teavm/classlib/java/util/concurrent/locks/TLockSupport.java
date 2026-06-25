package org.teavm.classlib.java.util.concurrent.locks;

public final class TLockSupport {
    private TLockSupport() {
    }

    public static void unpark(Thread thread) {
    }

    public static void park(Object blocker) {
        Thread.yield();
    }

    public static void parkNanos(Object blocker, long nanos) {
        sleepNanos(nanos);
    }

    public static void parkUntil(Object blocker, long deadline) {
        long millis = deadline - System.currentTimeMillis();
        if (millis > 0) {
            sleepMillis(millis);
        } else {
            Thread.yield();
        }
    }

    public static Object getBlocker(Thread thread) {
        if (thread == null) {
            throw new NullPointerException();
        }
        return null;
    }

    public static void park() {
        Thread.yield();
    }

    public static void parkNanos(long nanos) {
        sleepNanos(nanos);
    }

    public static void parkUntil(long deadline) {
        long millis = deadline - System.currentTimeMillis();
        if (millis > 0) {
            sleepMillis(millis);
        } else {
            Thread.yield();
        }
    }

    private static void sleepNanos(long nanos) {
        if (nanos <= 0) {
            Thread.yield();
            return;
        }
        long millis = Math.max(1, (nanos + 999_999L) / 1_000_000L);
        sleepMillis(millis);
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(Math.max(1, Math.min(millis, Integer.MAX_VALUE)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
