package org.teavm.classlib.java.util.concurrent.locks;

import java.util.IdentityHashMap;

public final class TLockSupport {
    private static final IdentityHashMap<Thread, Boolean> permits = new IdentityHashMap<>();
    private static final IdentityHashMap<Thread, Boolean> parkedThreads = new IdentityHashMap<>();

    private TLockSupport() {
    }

    public static void unpark(Thread thread) {
        if (thread == null) {
            return;
        }
        permits.put(thread, Boolean.TRUE);
        if (parkedThreads.containsKey(thread)) {
            thread.interrupt();
        }
    }

    public static void park(Object blocker) {
        if (!takePermit(Thread.currentThread())) {
            Thread.yield();
        }
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
        if (!takePermit(Thread.currentThread())) {
            Thread.yield();
        }
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
        Thread thread = Thread.currentThread();
        if (takePermit(thread)) {
            return;
        }
        parkedThreads.put(thread, Boolean.TRUE);
        try {
            Thread.sleep(Math.max(1, Math.min(millis, Integer.MAX_VALUE)));
        } catch (InterruptedException e) {
            if (!takePermit(thread)) {
                thread.interrupt();
            }
        } finally {
            parkedThreads.remove(thread);
        }
    }

    private static boolean takePermit(Thread thread) {
        return permits.remove(thread) != null;
    }
}
