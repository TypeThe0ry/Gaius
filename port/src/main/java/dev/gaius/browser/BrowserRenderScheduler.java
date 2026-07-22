package dev.gaius.browser;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import org.teavm.jso.browser.Window;
import org.teavm.platform.Platform;

/** Defers expensive renderer work so one client frame cannot drain the whole compile queue. */
public final class BrowserRenderScheduler {
    private static final int MAX_TASKS_PER_FRAME = 4;
    private static final long FRAME_WORK_BUDGET_NANOS = 3_000_000L;
    private static final Deque<Runnable> QUEUE = new ArrayDeque<>();
    private static final Executor DEFERRED_EXECUTOR = BrowserRenderScheduler::enqueue;
    private static boolean pumpScheduled;

    private BrowserRenderScheduler() {
    }

    public static Executor defer(Executor ignored) {
        return DEFERRED_EXECUTOR;
    }

    private static void enqueue(Runnable command) {
        if (command == null) {
            return;
        }
        QUEUE.addLast(command);
        schedulePump();
    }

    private static void schedulePump() {
        if (pumpScheduled) {
            return;
        }
        pumpScheduled = true;
        Window.requestAnimationFrame(timestamp -> Platform.schedule(BrowserRenderScheduler::runAfterPaint, 0));
    }

    private static void runAfterPaint() {
        long startedAt = System.nanoTime();
        int completed = 0;
        try {
            while (completed < MAX_TASKS_PER_FRAME) {
                Runnable command = QUEUE.pollFirst();
                if (command == null) {
                    return;
                }
                command.run();
                completed++;
                if (System.nanoTime() - startedAt >= FRAME_WORK_BUDGET_NANOS) {
                    return;
                }
            }
        } finally {
            pumpScheduled = false;
            if (!QUEUE.isEmpty()) {
                schedulePump();
            }
        }
    }
}
