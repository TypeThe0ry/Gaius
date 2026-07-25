package dev.gaius.browser;

/** Marks integrated-server world generation scheduling points for browser builds. */
public final class BrowserWorldgenScheduler {
    private BrowserWorldgenScheduler() {
    }

    public static void checkpoint() {
        // Keep the browser Worker responsive to local MessagePort traffic between server ticks.
        // The less-frequent-yield experiment did not improve the measured startup profile.
        Thread.yield();
    }

    public static void pulse() {
        // Retained as a bytecode marker for profiling world-generation loops.
    }

}
