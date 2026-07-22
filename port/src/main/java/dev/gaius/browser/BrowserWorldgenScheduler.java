package dev.gaius.browser;

/** Marks integrated-server world generation scheduling points for browser builds. */
public final class BrowserWorldgenScheduler {
    private static final int YIELD_CHECKS_PER_TICK = 30;

    private BrowserWorldgenScheduler() {
    }

    public static void checkpoint() {
        // Called by runServer before processPacketsAndTick. Keeping the suspension
        // outside the tick method prevents Fiber-transforming its worldgen descendants.
        for (int i = 0; i < YIELD_CHECKS_PER_TICK; i++) {
            Thread.yield();
        }
    }

    public static void pulse() {
        // Retained as a bytecode marker for profiling world-generation loops.
    }

}
