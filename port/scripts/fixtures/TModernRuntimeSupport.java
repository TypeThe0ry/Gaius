package org.teavm.classlib.java.lang;

/** Test-only no-op stub: the real TeaVM runtime supplies this yield hook. */
public final class TModernRuntimeSupport {
    public static int calls;

    private TModernRuntimeSupport() {}

    public static void yieldToEventLoop(int delayMillis) {
        calls++;
    }
}
