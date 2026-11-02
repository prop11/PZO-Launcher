package com.pzoptimizer;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-frame fork/join pool for work the game thread splits across cores. The game thread posts a batch of indexed
 * tasks, helps out, and returns once every index is done. Workers spin for a few microseconds after a batch and then
 * sleep, so an idle game costs nothing.
 *
 * One batch at a time, only started by the game thread. Each batch has its own cursor, so a worker that wakes up late
 * can't grab an index from the next one. A task that throws is recorded and the batch still finishes; the caller
 * decides what that means.
 */
public final class FrameWorkers {

    /** Worker threads have their own scratch objects; PerThreadScratch hands those out instead of shared statics. */
    public static final class Worker extends Thread {
        final Object[] scratch = new Object[PerThreadScratch.MAX_SLOTS];
        final java.util.ArrayDeque<Object> matrices = new java.util.ArrayDeque<>();

        Worker(Runnable body, String name) {
            super(body, name);
            setDaemon(true);
        }
    }

    public interface Task {
        void run(int index) throws Throwable;
    }

    private static final class Batch {
        final Task task;
        final int count;
        final AtomicInteger cursor = new AtomicInteger();
        final AtomicInteger remaining;
        volatile Throwable failure;

        Batch(Task task, int count) {
            this.task = task;
            this.count = count;
            this.remaining = new AtomicInteger(count);
        }

        void drain() {
            int i;
            while ((i = cursor.getAndIncrement()) < count) {
                try {
                    task.run(i);
                } catch (Throwable e) {
                    if (failure == null) failure = e;
                } finally {
                    remaining.decrementAndGet();
                }
            }
        }
    }

    private static final int SPIN_NS = 50_000;

    private static final Object lock = new Object();
    private static volatile Batch current;
    private static volatile int generation;
    private static Worker[] workers;

    private FrameWorkers() {}

    // Counted in real cores, not hyperthreads. The game thread works on every batch too and the render thread
    // needs a core of its own, so on a normal CPU that's two fewer than CpuTopologyGovernor's count (which already
    // leaves those two out on a low-core CPU).
    public static int workerCount() {
        int cores = CpuTopologyGovernor.getOptimalWorkerCount();
        int n = PZONative.isLowCoreCpu() ? cores : cores - 2;
        return Math.max(1, Math.min(8, n));
    }

    private static void start() {
        int n = workerCount();
        workers = new Worker[n];
        for (int i = 0; i < n; i++) {
            Worker w = new Worker(FrameWorkers::workerLoop, "PZO-FrameWorker-" + (i + 1));
            workers[i] = w;
            w.start();
        }
        PZOLogger.success("[FrameWorkers] " + n + " frame worker threads started");
    }

    /** Runs task(0..count-1) on the workers and the calling thread; returns the first failure, or null. */
    public static Throwable run(int count, Task t) {
        if (count <= 0) return null;
        if (workers == null) start();
        Batch b = new Batch(t, count);
        synchronized (lock) {
            current = b;
            generation++;
            lock.notifyAll();
        }
        b.drain();
        long spinUntil = System.nanoTime() + SPIN_NS;
        while (b.remaining.get() > 0) {
            if (System.nanoTime() < spinUntil) {
                Thread.onSpinWait();
            } else {
                Thread.yield();
            }
        }
        current = null;
        return b.failure;
    }

    private static void workerLoop() {
        int seen = 0;
        while (true) {
            long spinUntil = System.nanoTime() + SPIN_NS;
            while (generation == seen && System.nanoTime() < spinUntil) {
                Thread.onSpinWait();
            }
            if (generation == seen) {
                synchronized (lock) {
                    while (generation == seen) {
                        try {
                            lock.wait();
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
            seen = generation;
            Batch b = current;
            if (b != null) b.drain();
        }
    }
}
