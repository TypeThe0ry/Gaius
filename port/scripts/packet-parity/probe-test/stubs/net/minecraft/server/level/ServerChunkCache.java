package net.minecraft.server.level;

import java.util.ArrayDeque;

/**
 * Test stub of the chunk main-thread executor: any thread may post, only the server thread polls,
 * as with vanilla's ServerChunkCache.MainThreadExecutor.
 */
public class ServerChunkCache {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    public void post(Runnable task) {
        synchronized (tasks) {
            tasks.addLast(task);
        }
    }

    public boolean pollTask() {
        Runnable task;
        synchronized (tasks) {
            task = tasks.pollFirst();
        }
        if (task == null) return false;
        task.run();
        return true;
    }

    public int getPendingTasksCount() {
        synchronized (tasks) {
            return tasks.size();
        }
    }
}
