package dev.gaius.browser;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import org.teavm.jso.JSBody;
import org.teavm.platform.Platform;

/**
 * Keeps large resource-pack reloads cooperative with the browser event loop.
 *
 * <p>Minecraft submits many small model and atlas preparation tasks through an executor that
 * becomes synchronous in the browser. Submitting a bounded batch per browser task preserves the
 * original executor's completion behavior while letting input, painting, and network timers run.
 */
public final class BrowserResourceReloadScheduler {
    // Large server packs can otherwise exceed proxy/backend configuration timeouts.
    // Eleven milliseconds keeps each browser task below a 60 Hz frame budget.
    private static final long FRAME_WORK_BUDGET_NANOS = 11_000_000L;
    private static final int MAX_SUBMISSIONS_PER_BATCH = 384;
    private static final Deque<Runnable> QUEUE = new ArrayDeque<>();
    private static boolean pumpScheduled;

    private BrowserResourceReloadScheduler() {
    }

    public static Executor defer(Executor delegate) {
        // The dedicated server runs in a Worker: there is no canvas to keep
        // paintable, and delaying its datapack preparation delays listener
        // registration and the first local login. Preserve vanilla execution
        // there; frame-budget only the foreground client reload.
        if (isWorkerRuntime()) {
            return delegate;
        }
        return command -> enqueue(() -> delegate.execute(command));
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
        // Waiting for the next frame callback here capped thousands of tiny model
        // preparation batches at the display refresh rate and dominated startup. A
        // zero-delay browser task still yields to input, networking, and rendering,
        // while allowing the next batch to run as soon as the browser is ready.
        Platform.schedule(BrowserResourceReloadScheduler::runAfterYield, 0);
    }

    @JSBody(script = "return typeof WorkerGlobalScope !== 'undefined' && globalThis instanceof WorkerGlobalScope;")
    private static native boolean isWorkerRuntime();

    private static void runAfterYield() {
        long startedAt = System.nanoTime();
        int submitted = 0;
        try {
            while (submitted < MAX_SUBMISSIONS_PER_BATCH) {
                Runnable command = QUEUE.pollFirst();
                if (command == null) {
                    return;
                }
                command.run();
                submitted++;
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
