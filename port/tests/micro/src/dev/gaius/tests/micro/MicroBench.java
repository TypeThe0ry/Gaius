package dev.gaius.tests.micro;

import java.util.concurrent.locks.LockSupport;
import org.lwjgl.system.BrowserMemory;
import org.teavm.interop.Address;
import org.teavm.jso.JSBody;
import org.teavm.runtime.heap.Heap;

/**
 * Runtime micro benchmarks of the WebAssembly GC build in JSPI mode (v0.4.0 WP0.6), compiled by
 * port/tests/micro/build.sh and run by run-chrome.mjs:
 * <ul>
 *   <li>scheduler ping-pong: two Java threads hand the turn to each other through
 *   LockSupport.park/unpark and through Object.wait/notify; one hop is one handover (one
 *   suspension of the giving thread and one resumption of the other from the gaius-jspi.js run
 *   queue). Target: at most 50 us per hop.</li>
 *   <li>Heap.alloc and Heap.release (TeaVM's linear-memory allocator) timed separately, for
 *   batches of one size, for alloc/release pairs and on a fragmented heap. Target: alloc at most
 *   200 ns.</li>
 *   <li>the same for BrowserMemory.allocate/free, the LWJGL nmemAlloc path on top of it.</li>
 * </ul>
 * Results are nanoseconds per operation in globalThis.__gaiusMicro.results; each figure is the
 * median of {@link #ROUNDS} rounds, timed with performance.now() over whole batches.
 */
public final class MicroBench {
    private static final int ROUNDS = 7;
    private static final int PING_PONG_HOPS = 20000;
    private static final int BATCH = 20000;
    private static final int PAIRS = 200000;
    private static final int[] SIZES = {16, 64, 256, 1024, 4096, 65536};

    private static volatile int turn;
    private static Thread first;
    private static Thread second;

    private MicroBench() {
    }

    @JSBody(script = "return performance.now();")
    static native double now();

    @JSBody(params = {"key", "value"}, script = "var s = globalThis.__gaiusMicro"
            + " || (globalThis.__gaiusMicro = {results: {}, done: false});"
            + "s.results[key] = value; console.log('MICRO ' + key + ' ' + value);")
    static native void report(String key, double value);

    @JSBody(params = "summary", script = "var s = globalThis.__gaiusMicro"
            + " || (globalThis.__gaiusMicro = {results: {}, done: false});"
            + "s.summary = summary; s.done = true; console.log('MICRO_DONE ' + summary);")
    static native void done(String summary);

    @JSBody(params = "name", script = "var p = globalThis.__gaiusMicroParams;"
            + "var v = p ? p[name] : undefined; return v === undefined ? '' : String(v);")
    static native String param(String name);

    public static void main(String[] args) throws Exception {
        String only = param("only");
        if (only.isEmpty() || only.contains("pingpong")) {
            pingPongPark();
            pingPongMonitor();
        }
        if (only.isEmpty() || only.contains("heap")) {
            heap();
        }
        if (only.isEmpty() || only.contains("browsermemory")) {
            browserMemory();
        }
        done("ok");
    }

    // --- scheduler ping-pong --------------------------------------------------------------

    private static void pingPongPark() throws InterruptedException {
        double[] perHop = new double[ROUNDS];
        for (int round = -1; round < ROUNDS; round++) {
            int hops = round < 0 ? 2000 : PING_PONG_HOPS;
            turn = 0;
            Thread a = new Thread(() -> parkPlayer(0, hops), "ping");
            Thread b = new Thread(() -> parkPlayer(1, hops), "pong");
            first = a;
            second = b;
            double start = now();
            a.start();
            b.start();
            a.join();
            b.join();
            double elapsed = now() - start;
            if (round >= 0) {
                perHop[round] = elapsed * 1e6 / hops;
            }
        }
        report("pingpong.park.nsPerHop", median(perHop));
    }

    /** Player {@code me} takes the turn when it is its own, passes it on and wakes the other. */
    private static void parkPlayer(int me, int hops) {
        Thread other = me == 0 ? second : first;
        for (int hop = me; hop < hops; hop += 2) {
            while (turn != hop) {
                LockSupport.park();
            }
            turn = hop + 1;
            LockSupport.unpark(other);
        }
    }

    private static void pingPongMonitor() throws InterruptedException {
        Object lock = new Object();
        double[] perHop = new double[ROUNDS];
        for (int round = -1; round < ROUNDS; round++) {
            int hops = round < 0 ? 2000 : PING_PONG_HOPS;
            turn = 0;
            Thread a = new Thread(() -> monitorPlayer(lock, 0, hops), "ping");
            Thread b = new Thread(() -> monitorPlayer(lock, 1, hops), "pong");
            double start = now();
            a.start();
            b.start();
            a.join();
            b.join();
            double elapsed = now() - start;
            if (round >= 0) {
                perHop[round] = elapsed * 1e6 / hops;
            }
        }
        report("pingpong.monitor.nsPerHop", median(perHop));
    }

    private static void monitorPlayer(Object lock, int me, int hops) {
        for (int hop = me; hop < hops; hop += 2) {
            synchronized (lock) {
                while (turn != hop) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                turn = hop + 1;
                lock.notifyAll();
            }
        }
    }

    // --- Heap.alloc / Heap.release --------------------------------------------------------

    private interface Allocator {
        int alloc(int bytes);

        void release(int address);
    }

    private static final Allocator HEAP = new Allocator() {
        @Override
        public int alloc(int bytes) {
            return Heap.alloc(bytes).toInt();
        }

        @Override
        public void release(int address) {
            Heap.release(Address.fromInt(address));
        }
    };

    private static final Allocator BROWSER_MEMORY = new Allocator() {
        @Override
        public int alloc(int bytes) {
            return (int) BrowserMemory.allocate(bytes);
        }

        @Override
        public void release(int address) {
            BrowserMemory.free(Integer.toUnsignedLong(address));
        }
    };

    private static void heap() {
        allocator("heap", HEAP);
    }

    private static void browserMemory() {
        allocator("browsermemory", BROWSER_MEMORY);
    }

    private static void allocator(String name, Allocator allocator) {
        int[] addresses = new int[BATCH];
        for (int size : SIZES) {
            int count = size >= 65536 ? BATCH / 20 : BATCH;
            double[] alloc = new double[ROUNDS];
            double[] release = new double[ROUNDS];
            for (int round = -1; round < ROUNDS; round++) {
                double start = now();
                for (int i = 0; i < count; i++) {
                    addresses[i] = allocator.alloc(size);
                }
                double allocated = now();
                for (int i = 0; i < count; i++) {
                    allocator.release(addresses[i]);
                }
                double released = now();
                if (round >= 0) {
                    alloc[round] = (allocated - start) * 1e6 / count;
                    release[round] = (released - allocated) * 1e6 / count;
                }
            }
            report(name + ".batch" + size + ".allocNs", median(alloc));
            report(name + ".batch" + size + ".releaseNs", median(release));
            double[] pair = new double[ROUNDS];
            int pairs = size >= 65536 ? PAIRS / 20 : PAIRS;
            for (int round = -1; round < ROUNDS; round++) {
                double start = now();
                for (int i = 0; i < pairs; i++) {
                    allocator.release(allocator.alloc(size));
                }
                if (round >= 0) {
                    pair[round] = (now() - start) * 1e6 / pairs;
                }
            }
            report(name + ".pair" + size + ".ns", median(pair));
        }
        fragmented(name, allocator);
    }

    /**
     * 40000 live blocks of 16 to 2064 bytes with every second one released, then batches of the
     * same size mix allocated and released in the holes.
     */
    private static void fragmented(String name, Allocator allocator) {
        int live = 40000;
        int[] background = new int[live];
        int seed = 12345;
        for (int i = 0; i < live; i++) {
            seed = seed * 1103515245 + 12345;
            background[i] = allocator.alloc(16 + ((seed >>> 16) & 0x7ff));
        }
        for (int i = 0; i < live; i += 2) {
            allocator.release(background[i]);
            background[i] = 0;
        }
        int[] addresses = new int[BATCH];
        int[] sizes = new int[BATCH];
        for (int i = 0; i < BATCH; i++) {
            seed = seed * 1103515245 + 12345;
            sizes[i] = 16 + ((seed >>> 16) & 0x7ff);
        }
        double[] alloc = new double[ROUNDS];
        double[] release = new double[ROUNDS];
        for (int round = -1; round < ROUNDS; round++) {
            double start = now();
            for (int i = 0; i < BATCH; i++) {
                addresses[i] = allocator.alloc(sizes[i]);
            }
            double allocated = now();
            for (int i = BATCH - 1; i >= 0; i--) {
                allocator.release(addresses[i]);
            }
            double released = now();
            if (round >= 0) {
                alloc[round] = (allocated - start) * 1e6 / BATCH;
                release[round] = (released - allocated) * 1e6 / BATCH;
            }
        }
        report(name + ".fragmented.allocNs", median(alloc));
        report(name + ".fragmented.releaseNs", median(release));
        for (int i = 1; i < live; i += 2) {
            allocator.release(background[i]);
        }
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return Math.round(sorted[sorted.length / 2] * 10.0) / 10.0;
    }
}
