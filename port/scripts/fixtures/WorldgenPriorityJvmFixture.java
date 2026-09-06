import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.util.thread.PriorityConsecutiveExecutor;

public final class WorldgenPriorityJvmFixture {
    private static final class Gate implements Executor {
        private final Queue<Runnable> pending = new ArrayDeque<>();
        private boolean draining;

        @Override
        public void execute(Runnable command) {
            if (draining) {
                command.run();
            } else {
                pending.add(command);
            }
        }

        void drainOne() {
            Runnable command = pending.poll();
            if (command == null) {
                throw new AssertionError("executor command was not registered");
            }
            draining = true;
            try {
                command.run();
            } finally {
                draining = false;
            }
        }
    }

    public static void main(String[] args) {
        Gate gate = new Gate();
        PriorityConsecutiveExecutor dispatcher =
                new PriorityConsecutiveExecutor(4, gate, "worldgen-dispatcher");
        AtomicInteger count = new AtomicInteger();
        for (int i = 0; i < 50_000; i++) {
            int expected = i;
            dispatcher.schedule(dispatcher.wrapRunnable(() -> {
                int actual = count.getAndIncrement();
                if (actual != expected) {
                    throw new AssertionError("FIFO mismatch: expected " + expected + " got " + actual);
                }
            }));
        }
        gate.drainOne();
        if (count.get() != 50_000 || dispatcher.hasWork()) {
            throw new AssertionError("worldgen backlog did not drain");
        }

        Gate errorGate = new Gate();
        PriorityConsecutiveExecutor errorDispatcher =
                new PriorityConsecutiveExecutor(4, errorGate, "worldgen-dispatcher");
        errorDispatcher.schedule(errorDispatcher.wrapRunnable(() -> { throw new ExpectedFailure(); }));
        try {
            errorGate.drainOne();
            throw new AssertionError("expected task failure");
        } catch (ExpectedFailure expected) {
            // The patched finally path must leave the executor schedulable.
        }
        if (errorDispatcher.hasWork()) {
            throw new AssertionError("exception path left stale work");
        }
        AtomicInteger recoveredCount = new AtomicInteger();
        errorDispatcher.schedule(errorDispatcher.wrapRunnable(recoveredCount::incrementAndGet));
        errorGate.drainOne();
        if (recoveredCount.get() != 1) {
            throw new AssertionError("exception path did not recover");
        }

        Gate closedGate = new Gate();
        PriorityConsecutiveExecutor closed =
                new PriorityConsecutiveExecutor(4, closedGate, "worldgen-dispatcher");
        closed.close();
        closed.schedule(closed.wrapRunnable(() -> { throw new AssertionError("closed task ran"); }));
        if (closed.hasWork()) {
            throw new AssertionError("closed dispatcher reports work");
        }

        AtomicInteger vanillaCount = new AtomicInteger();
        PriorityConsecutiveExecutor vanilla =
                new PriorityConsecutiveExecutor(4, Runnable::run, "dispatcher");
        vanilla.schedule(vanilla.wrapRunnable(vanillaCount::incrementAndGet));
        if (vanillaCount.get() != 1 || vanilla.hasWork()) {
            throw new AssertionError("vanilla dispatcher behavior changed");
        }

        AtomicInteger nullNameCount = new AtomicInteger();
        PriorityConsecutiveExecutor nullName =
                new PriorityConsecutiveExecutor(4, Runnable::run, null);
        nullName.schedule(nullName.wrapRunnable(nullNameCount::incrementAndGet));
        if (nullNameCount.get() != 1 || nullName.hasWork()) {
            throw new AssertionError("null-name dispatcher behavior changed");
        }
        if (org.teavm.classlib.java.lang.TModernRuntimeSupport.calls == 0) {
            throw new AssertionError("worldgen loop never reached the yield hook");
        }
        System.out.println("WORLDGEN_PRIORITY_JVM_OK count=" + count.get());
    }

    private static final class ExpectedFailure extends RuntimeException {
    }
}
