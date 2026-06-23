package org.teavm.classlib.java.util.concurrent.locks;

public final class TLockSupport {
    private TLockSupport() {
    }

    public static void unpark(Thread thread) {
    }

    public static void park(Object blocker) {
    }

    public static void parkNanos(Object blocker, long nanos) {
    }

    public static void parkUntil(Object blocker, long deadline) {
    }

    public static Object getBlocker(Thread thread) {
        if (thread == null) {
            throw new NullPointerException();
        }
        return null;
    }

    public static void park() {
    }

    public static void parkNanos(long nanos) {
    }

    public static void parkUntil(long deadline) {
    }
}
