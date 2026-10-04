package dev.gaius.browser;

import org.teavm.interop.AsyncCallback;
import org.teavm.interop.Import;
import org.teavm.interop.PlatformMarker;
import org.teavm.runtime.EventQueue;
import org.teavm.runtime.Fiber;

/**
 * WebAssembly GC implementation of the host event-loop entry points (the JavaScript one in
 * {@code src/main/java} delegates to TeaVM's {@code Platform}, which this backend does not
 * support).
 *
 * <p>TeaVM's WebAssembly GC runtime keeps no queue of its own: {@link EventQueue#offer} hands
 * the event to the {@code teavmAsync.offer} import of the runtime JS, which Gaius patches to
 * run zero-delay events from a MessageChannel ready queue instead of a clamped
 * {@code setTimeout} (port/web/runtime/wasmgc).
 *
 * <p>In JSPI mode (gaius.teavm.jspi, the default of the wasmgc roles) every Java thread is a
 * {@code WebAssembly.promising} activation (JspiHost): new threads start as activations and
 * a thread waits for host work by parking its activation ({@link #awaitHost}). Without JSPI
 * the fiber paths below remain for comparison builds.
 */
public final class HostLoop {
    private HostLoop() {
    }

    /**
     * Runs {@code task} later on a new daemon fiber, which may suspend. Like
     * {@code Platform.startThread} of the JavaScript build it does not switch
     * {@code Thread.currentThread()}.
     */
    public static void startThread(Runnable task) {
        if (JspiHost.enabled()) {
            JspiHost.startTask(task);
            return;
        }
        EventQueue.offer(() -> Fiber.start(task::run, true));
    }

    /**
     * Runs {@code task} from a host callback after {@code delayMillis}; the task has no fiber
     * and must not suspend. Returns an id for {@link #killSchedule}.
     */
    public static int schedule(Runnable task, int delayMillis) {
        return EventQueue.offer(task::run, System.currentTimeMillis() + Math.max(0, delayMillis));
    }

    /**
     * Cancels a {@link #schedule} that has not run yet. TeaVM 0.15's EventQueue.kill only
     * searches the event heap of the other backends; under WebAssembly GC the events live in
     * the runtime JS (a setTimeout id, or a run-queue id of gaius-jspi.js), so the id goes to
     * the runtime's teavmAsync.kill import directly.
     */
    public static void killSchedule(int id) {
        killHost(id);
    }

    @Import(module = "teavmAsync", name = "kill")
    private static native int killHost(int id);

    /** False when the caller runs from a plain host callback that has no fiber to suspend. */
    public static boolean canSuspend() {
        if (JspiHost.enabled()) {
            return JspiHost.inActivation();
        }
        return Fiber.current() != null;
    }

    /** True in the WebAssembly GC build (a platform marker, folded by TeaVM). */
    @PlatformMarker("webassembly-gc")
    public static boolean isWasmGc() {
        return true;
    }

    /** True in JSPI mode (a platform marker, folded by TeaVM; see JspiHost#enabled). */
    @PlatformMarker("gaius-jspi")
    public static boolean jspi() {
        return false;
    }

    /** Starts host work that calls {@code resume} once (from any host callback) when done. */
    @FunctionalInterface
    public interface HostCall {
        void start(Runnable resume);
    }

    /**
     * JSPI only: suspends the current Java thread (its whole wasm stack) until the host work
     * started by {@code call} resumes it. {@code resume} may run from a JS callback.
     */
    public static void awaitHost(HostCall call) {
        if (!JspiHost.enabled()) {
            throw new UnsupportedOperationException("HostLoop.awaitHost needs the JSPI build");
        }
        AsyncCallback<Object> result = JspiHost.newResult();
        call.start(() -> result.complete(null));
        JspiHost.awaitResult(result);
    }

    /**
     * JSPI only: suspends the current Java thread for at least {@code delayMillis}, or for one
     * pass of the run queue when it is 0 or less (the queue yields to the browser every few
     * milliseconds). Returns at once from a JS callback, which has no thread to suspend; the
     * yield is optional there (TModernRuntimeSupport.yieldToEventLoop).
     */
    public static void yieldToEventLoop(int delayMillis) {
        if (!JspiHost.enabled() || !JspiHost.inActivation()) {
            return;
        }
        awaitHost(resume -> {
            if (delayMillis > 0) {
                schedule(resume, delayMillis);
            } else {
                postMacrotask(resume);
            }
        });
    }

    /**
     * Runs {@code task} from a host macrotask as soon as the browser is ready; the task has no
     * fiber. Zero-delay events go through the runtime's MessageChannel ready queue.
     */
    public static void postMacrotask(Runnable task) {
        EventQueue.offer(task::run);
    }

    /**
     * The WebAssembly GC roles compile without gaius.teavm.syncClinits, asyncBarrier and
     * syncMonitors, so no frame is marked as one that must not suspend.
     */
    public static boolean inNoSuspendFrame() {
        return false;
    }
}
