package dev.gaius.browser;

/** Marks integrated-server world generation scheduling points for browser builds. */
public final class BrowserWorldgenScheduler {
    private static final int YIELD_CHECKS_PER_TICK = 8;

    private BrowserWorldgenScheduler() {
    }

    public static void checkpoint() {
        // Called by runServer before processPacketsAndTick. Eight event-loop turns
        // keep MessagePort and future continuations responsive without spending most
        // of the server tick repeatedly rescheduling the same Fiber.
        for (int i = 0; i < YIELD_CHECKS_PER_TICK; i++) {
            Thread.yield();
        }
    }

    public static void pulse() {
        // Retained as a bytecode marker for profiling world-generation loops.
    }

}
