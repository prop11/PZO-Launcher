package com.pzoptimizer;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs work from PZO's background threads on the game thread. Kahlua tables and the Lua exposer are not thread-safe:
 * the game thread reads them constantly (every LuaManager.getFunctionObject, every event), so writing them from
 * another thread can make a lookup fail for a moment ('no such function "Vehicles.Init.Default"' when a vehicle
 * spawns). Tasks are run after each MainThread loop step by EngineThreadGovernor's hook, which also ticks at the
 * main menu. If that hook is not running at all (it failed to install), a task runs on the caller after
 * NO_HOOK_FALLBACK_NS so nothing waits forever.
 */
public final class GameThreadQueue {

    private static final long NO_HOOK_FALLBACK_NS = 60_000_000_000L;

    private static final ConcurrentLinkedQueue<FutureTask<?>> tasks = new ConcurrentLinkedQueue<>();
    private static volatile long lastDrainNs;

    private GameThreadQueue() {}

    // Game thread, after each MainThread loop step
    public static void drain() {
        lastDrainNs = System.nanoTime();
        FutureTask<?> t;
        while ((t = tasks.poll()) != null) t.run();
    }

    /** Runs the task on the game thread and returns its result; called from a background thread. */
    public static <T> T runOnGameThread(Callable<T> task) throws Throwable {
        FutureTask<T> f = new FutureTask<>(task);
        tasks.add(f);
        long posted = System.nanoTime();
        while (true) {
            try {
                return f.get(250, TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                throw e.getCause() != null ? e.getCause() : e;
            } catch (TimeoutException e) {
                long now = System.nanoTime(), last = lastDrainNs;
                boolean hookSilent = last == 0 ? now - posted > NO_HOOK_FALLBACK_NS : now - last > NO_HOOK_FALLBACK_NS;
                if (hookSilent && tasks.remove(f)) {
                    PZOLogger.warn("[GameThreadQueue] game thread hook not running; task runs on " + Thread.currentThread().getName());
                    f.run();
                }
            }
        }
    }
}
