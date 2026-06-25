package net.minecraft.client.sounds;

import net.minecraft.util.thread.BlockableEventLoop;

/**
 * Browser-safe sound executor.
 *
 * <p>The official client starts a dedicated daemon thread here and blocks it in
 * {@code managedBlock}. TeaVM targets a single JavaScript thread, so starting
 * that thread can monopolize the browser event loop before the main menu is
 * shown. Browser audio is currently stubbed elsewhere, so sound tasks are run
 * synchronously on the caller or ignored after shutdown.</p>
 */
public class SoundEngineExecutor extends BlockableEventLoop<Runnable> {
    private volatile boolean shutdown;

    public SoundEngineExecutor() {
        super("Sound executor");
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
        // No background sound thread exists in the browser build.
    }

    public void shutDown() {
        this.shutdown = true;
        this.dropAllTasks();
    }

    public void startUp() {
        this.shutdown = false;
    }
}
