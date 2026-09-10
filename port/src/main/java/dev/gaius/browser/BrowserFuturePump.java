package dev.gaius.browser;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.teavm.classlib.java.lang.TModernRuntimeSupport;
import org.teavm.jso.JSBody;

/** Keeps Minecraft's queue-backed future waits cooperative in a browser Worker. */
public final class BrowserFuturePump {
    private BrowserFuturePump() {
    }

    /**
     * TeaVM's browser {@code BlockingQueue.poll(timeout)} cannot block a JavaScript Worker.
     * Yield once when the queue is empty so asynchronous future completions and queued work can
     * run before Minecraft checks the completion predicate again.
     */
    public static Object poll(BlockingQueue<?> queue, long timeout, TimeUnit unit) {
        Object task = queue.poll();
        if (task != null) {
            recordPoll(false, queue.size());
            return task;
        }
        recordPoll(true, queue.size());
        TModernRuntimeSupport.yieldToEventLoop(1);
        return queue.poll();
    }

    @JSBody(params = {"empty", "queueDepth"}, script = """
            try {
              const state = globalThis.__gaiusFuturePumpTelemetry ||
                (globalThis.__gaiusFuturePumpTelemetry = {
                  polls: 0,
                  tasks: 0,
                  emptyYields: 0,
                  lastQueueDepth: 0,
                  lastYieldAt: 0,
                  lastReportAt: 0
                });
              state.polls++;
              state.lastQueueDepth = Number(queueDepth) || 0;
              if (empty) {
                state.emptyYields++;
                state.lastYieldAt = Date.now();
                if (typeof WorkerGlobalScope !== 'undefined' &&
                    globalThis instanceof WorkerGlobalScope &&
                    state.lastYieldAt - state.lastReportAt >= 1000) {
                  state.lastReportAt = state.lastYieldAt;
                  postMessage({
                    type: 'server-startup-progress',
                    detail: 'future-pump-waiting polls=' + state.polls +
                      ' tasks=' + state.tasks +
                      ' emptyYields=' + state.emptyYields +
                      ' queue=' + state.lastQueueDepth,
                    at: state.lastYieldAt
                  });
                }
              } else {
                state.tasks++;
              }
            } catch (ignored) {}
            """)
    private static native void recordPoll(boolean empty, int queueDepth);
}
