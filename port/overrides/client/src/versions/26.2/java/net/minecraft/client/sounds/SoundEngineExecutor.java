package net.minecraft.client.sounds;

import net.minecraft.util.thread.BlockableEventLoop;

/** Executes sound work on the browser event-loop thread without starting a JVM thread. */
public class SoundEngineExecutor extends BlockableEventLoop<Runnable> {
    private volatile boolean shutdown;

    public SoundEngineExecutor() {
        super("Sound executor", false);
    }

    @Override
    public Runnable wrapRunnable(Runnable runnable) {
        return runnable;
    }

    @Override
    public void schedule(Runnable runnable) {
        if (!this.shutdown) {
            this.doRunTask(runnable);
        }
    }

    @Override
    protected boolean shouldRun(Runnable runnable) {
        return !this.shutdown;
    }

    @Override
    protected Thread getRunningThread() {
        return Thread.currentThread();
    }

    @Override
    protected void waitForTasks() {
    }

    public void shutDown() {
        this.shutdown = true;
        this.dropAllTasks();
    }

    public void startUp() {
        this.shutdown = false;
    }
}
