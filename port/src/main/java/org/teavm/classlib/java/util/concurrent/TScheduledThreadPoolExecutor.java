package org.teavm.classlib.java.util.concurrent;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import org.teavm.classlib.java.lang.TRunnable;

public class TScheduledThreadPoolExecutor extends TAbstractExecutorService
        implements TScheduledExecutorService {
    private boolean shutdown;
    private final BlockingQueue<TRunnable> queue = new TLinkedBlockingQueue<>();

    public TScheduledThreadPoolExecutor(int corePoolSize) {
    }

    public TScheduledThreadPoolExecutor(int corePoolSize, TThreadFactory threadFactory) {
    }

    public BlockingQueue<TRunnable> getQueue() {
        return queue;
    }

    public void setContinueExistingPeriodicTasksAfterShutdownPolicy(boolean value) {
    }

    @Override
    public void execute(TRunnable command) {
        if (shutdown) {
            throw new IllegalStateException("executor is shut down");
        }
        command.run();
    }

    @Override
    public TScheduledFuture<?> schedule(
            TRunnable command, long delay, TTimeUnit unit) {
        return schedule(() -> {
            command.run();
            return null;
        }, delay, unit);
    }

    @Override
    public <V> TScheduledFuture<V> schedule(
            TCallable<V> callable, long delay, TTimeUnit unit) {
        TSimpleScheduledFuture<V> future = new TSimpleScheduledFuture<>();
        execute(() -> {
            try {
                future.complete(callable.call());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    @Override
    public TScheduledFuture<?> scheduleAtFixedRate(
            TRunnable command, long initialDelay, long period, TTimeUnit unit) {
        return schedule(command, initialDelay, unit);
    }

    @Override
    public TScheduledFuture<?> scheduleWithFixedDelay(
            TRunnable command, long initialDelay, long delay, TTimeUnit unit) {
        return schedule(command, initialDelay, unit);
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<TRunnable> shutdownNow() {
        shutdown = true;
        return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TTimeUnit unit) {
        return shutdown;
    }
}
