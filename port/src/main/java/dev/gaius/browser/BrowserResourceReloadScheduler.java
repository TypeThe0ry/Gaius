package dev.gaius.browser;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import org.teavm.jso.browser.Window;
import org.teavm.jso.JSBody;
import org.teavm.platform.Platform;

/**
 * Keeps large resource-pack reloads cooperative with the browser event loop.
 *
 * <p>Minecraft submits many small model and atlas preparation tasks through an executor that
 * becomes synchronous in the browser. Submitting a bounded batch each animation frame preserves
 * the original executor's completion behavior while letting input, painting, and network timers run.
 */
public final class BrowserResourceReloadScheduler {
    private static final long FRAME_WORK_BUDGET_NANOS = 7_000_000L;
    private static final int MAX_SUBMISSIONS_PER_FRAME = 384;
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
        if (isWorkerRuntime()) {
            // Workers have no requestAnimationFrame. Resource reload is also used
            // while the integrated server loads datapacks, so yield to its message
            // queue instead of failing startup before it can accept the local port.
            Platform.schedule(BrowserResourceReloadScheduler::runAfterPaint, 0);
            return;
        }
        Window.requestAnimationFrame(timestamp -> Platform.schedule(BrowserResourceReloadScheduler::runAfterPaint, 0));
    }

    @JSBody(script = "return typeof WorkerGlobalScope !== 'undefined' && globalThis instanceof WorkerGlobalScope;")
    private static native boolean isWorkerRuntime();

    private static void runAfterPaint() {
        long startedAt = System.nanoTime();
        int submitted = 0;
        try {
            while (submitted < MAX_SUBMISSIONS_PER_FRAME) {
                Runnable command = QUEUE.pollFirst();
                if (command == null) {
                    return;
                }
                // Keep vanilla keepalives flowing while a server resource pack is rebuilding.
                BrowserClientNetwork.pumpNow();
                command.run();
                BrowserClientNetwork.pumpNow();
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
