package org.teavm.classlib.java.lang;

import com.ibm.icu.lang.UCharacter;
import java.time.Duration;
import org.teavm.classlib.java.util.TCollections;
import org.teavm.classlib.java.util.TMap;
import org.teavm.classlib.java.lang.reflect.TType;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.platform.Platform;
import org.teavm.platform.PlatformRunnable;

public final class TModernRuntimeSupport {
    private TModernRuntimeSupport() {
    }

    public static String characterToString(int codePoint) {
        return new String(TCharacter.toChars(codePoint));
    }

    public static int codePointOf(String name) {
        int result = UCharacter.getCharFromName(name);
        if (result < 0) {
            throw new IllegalArgumentException("Unrecognized Unicode character name: " + name);
        }
        return result;
    }

    public static int parseUnsignedInt(String value, int radix) {
        long result = TLong.parseLong(value, radix);
        if (result < 0 || result > 0xffff_ffffL) {
            throw new NumberFormatException("Unsigned integer out of range: " + value);
        }
        return (int) result;
    }

    public static long parseUnsignedLong(String value, int radix) {
        if (value == null || value.isEmpty()) {
            throw new NumberFormatException("empty String");
        }
        if (radix < Character.MIN_RADIX || radix > Character.MAX_RADIX) {
            throw new NumberFormatException("radix " + radix + " out of range");
        }
        long result = 0;
        long maxQuotient = TLong.divideUnsigned(-1L, radix);
        int maxRemainder = (int) TLong.remainderUnsigned(-1L, radix);
        for (int index = 0; index < value.length(); index++) {
            int digit = TCharacter.digit(value.charAt(index), radix);
            if (digit < 0) {
                throw new NumberFormatException("Invalid digit in " + value);
            }
            int comparison = TLong.compareUnsigned(result, maxQuotient);
            if (comparison > 0 || comparison == 0 && digit > maxRemainder) {
                throw new NumberFormatException("Unsigned long out of range: " + value);
            }
            result = result * radix + digit;
        }
        return result;
    }

    @JSBody(
            params = {"left", "right", "addend"},
            script = "return Math.fround(left * right + addend);")
    public static native float fma(float left, float right, float addend);

    @JSBody(
            params = {"left", "right", "addend"},
            script = "return left * right + addend;")
    public static native double fma(double left, double right, double addend);

    public static long maxMemory(TRuntime runtime) {
        // Conservative heap budget used by vanilla render-buffer sizing.
        return 1024L * 1024L * 1024L;
    }

    public static TMap<String, String> getenv() {
        return TCollections.emptyMap();
    }

    public static boolean isAnonymousClass(TClass<?> type) {
        return type.getEnclosingClass() != null && type.getSimpleName().isEmpty();
    }

    public static long threadId(TThread thread) {
        return thread.getId();
    }

    public static TThread$State threadState(TThread thread) {
        return thread.isAlive() ? TThread$State.RUNNABLE : TThread$State.TERMINATED;
    }

    public static void sleep(Duration duration) throws TInterruptedException {
        TThread.sleep(duration.toMillis());
    }

    /**
     * Suspends only the current TeaVM continuation without installing a Thread interrupt handler.
     * Browser world generation can have several continuations sharing one emulated Java thread;
     * using TThread.sleep there lets an unrelated wake-up cancel the wrong continuation.
     * Returns at once while a class initializer or a method that the role options compiled
     * synchronously runs (see {@link #inClassInitializer}), and when called from a plain
     * JavaScript callback that has no TeaVM thread to suspend: the yield is optional, so
     * skipping it there is correct where suspending would throw.
     */
    public static void yieldToEventLoop(int delayMillis) {
        if (inClassInitializer()) {
            return;
        }
        try {
            suspendToEventLoop(delayMillis);
        } catch (RuntimeException e) {
            String message = e.getMessage();
            if (message == null || !message.contains("Suspension point reached from non-threading context")) {
                throw e;
            }
        }
    }

    /**
     * True while the current frame must not suspend: a TeaVM class initializer, or a method
     * that the role options compiled synchronously, is on the stack. Both roles are compiled
     * with class initialization edges that do not make their callers TeaVM-async
     * (gaius.teavm.syncClinits, TeaVMCoreBrowserPatcher), so a static initializer can run
     * under a synchronous caller; the patched TeaVM counts those frames in
     * globalThis.__gaiusClinitDepth. Methods cut by gaius.teavm.asyncBarrier or
     * gaius.teavm.syncMonitors keep globalThis.__gaiusNoSuspendDepth positive, and a
     * suspension reached there throws. Cooperative yields and LockSupport parking are
     * optional, so their callers skip them while either counter is positive instead of
     * suspending. Builds without those options never set the counters.
     */
    @JSBody(script = """
            return (globalThis.__gaiusClinitDepth | 0) > 0
              || (globalThis.__gaiusNoSuspendDepth | 0) > 0;
            """)
    public static native boolean inClassInitializer();

    @Async
    private static native void suspendToEventLoop(int delayMillis);

    private static void suspendToEventLoop(int delayMillis, AsyncCallback<Void> callback) {
        TThread thread = TThread.currentThread();
        PlatformRunnable resume = () -> {
            TThread.setCurrentThread(thread);
            callback.complete(null);
        };
        if (delayMillis > 0) {
            Platform.schedule(resume, delayMillis);
        } else {
            postMacrotask(() -> resume.run());
        }
    }

    @JSFunctor
    private interface ResumeCallback extends JSObject {
        void run();
    }

    // Platform.postpone uses setTimeout(0), which is still subject to nested
    // timer clamping. A MessagePort turn gives other Worker messages a chance
    // to run without imposing that delay on every cooperative work slice.
    @JSBody(params = "callback", script = """
            let state = globalThis.__gaiusMacrotaskScheduler;
            if (!state) {
              state = {channel: null, pending: new Map(), sequence: 0, failed: false};
              globalThis.__gaiusMacrotaskScheduler = state;
              try {
                state.channel = new MessageChannel();
                state.channel.port1.onmessage = function(event) {
                  const resume = state.pending.get(event.data);
                  if (!resume) return;
                  state.pending.delete(event.data);
                  resume();
                };
              } catch (_) {
                state.failed = true;
              }
            }
            if (state.failed) {
              setTimeout(callback, 0);
              return;
            }
            const id = ++state.sequence;
            state.pending.set(id, callback);
            try {
              state.channel.port2.postMessage(id);
            } catch (_) {
              state.failed = true;
              try { state.channel.port1.close(); } catch (ignored) {}
              try { state.channel.port2.close(); } catch (ignored) {}
              const callbacks = Array.from(state.pending.values());
              state.pending.clear();
              for (let index = 0; index < callbacks.length; index++) {
                setTimeout(callbacks[index], 0);
              }
            }
            """)
    private static native void postMacrotask(ResumeCallback callback);

    public static void postRunnableMacrotask(Runnable callback) {
        ResumeCallback jsCallback = callback::run;
        postMacrotask(jsCallback);
    }

    public static TType genericSuperclass(TClass<?> type) {
        return type.getSuperclass();
    }

    public static TType[] genericInterfaces(TClass<?> type) {
        TClass<?>[] interfaces = type.getInterfaces();
        TType[] result = new TType[interfaces.length];
        System.arraycopy(interfaces, 0, result, 0, interfaces.length);
        return result;
    }
}
