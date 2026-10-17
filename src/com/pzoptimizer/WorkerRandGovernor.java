package com.pzoptimizer;

import java.lang.reflect.Field;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-Worker Lock-Free RNG Stream Governor.
 * Vanilla Project Zomboid's RandStandard.INSTANCE uses a single CellularAutomatonRNG
 * protected by a fair ReentrantLock. When multi-core worker threads execute chunk parsing,
 * animation updates, or spatial checks concurrently, they contend heavily on that lock.
 *
 * WorkerRandGovernor wraps RandStandard.INSTANCE's internal Random field so that:
 * 1. The main GameThread takes a 1-cycle pointer equality fast-path (Thread.currentThread() == mainThread)
 *    straight to the stock generator, preserving main-thread RNG sequence with zero string overhead.
 * 2. All PZO worker threads (PZO-MultiCore-Worker, PZO-ChunkStreamer-Worker, PZO-MultiCoreHordeGovernor)
 *    draw from lock-free ThreadLocalRandom streams with zero lock contention.
 * 3. Automatically re-wraps RandStandard.INSTANCE.rand if world init replaces it.
 */
public final class WorkerRandGovernor extends Random {

    public static final class WorkerThread extends Thread {
        public WorkerThread(Runnable target, String name) {
            super(target, name);
        }
    }

    private static volatile boolean active = true;
    private static volatile boolean installed = false;
    private static volatile Thread mainThread = null;
    private static volatile Object randStdInstance = null;
    private static volatile Field randField = null;

    public static final AtomicLong workerLockFreeDraws = new AtomicLong(0);

    private final Random delegate;

    private WorkerRandGovernor(Random original) {
        super(0L);
        this.delegate = original;
    }

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean val) {
        active = val;
    }

    public static boolean isInstalled() {
        return installed;
    }

    /**
     * Installs or re-installs the lock-free worker RNG wrapper onto zombie.core.random.RandStandard.INSTANCE.
     * Safe and fast to call periodically on frame ticks (single reference comparison when already wrapped).
     */
    public static void ensureInstalled() {
        if (!active) return;
        try {
            Object instance = randStdInstance;
            Field rf = randField;
            if (instance != null && rf != null) {
                Object current = rf.get(instance);
                if (current instanceof WorkerRandGovernor) {
                    return;
                }
            }
            installSlowPath();
        } catch (Throwable ignored) {}
    }

    private static synchronized void installSlowPath() {
        if (!active) return;
        try {
            if (randStdInstance == null) {
                Class<?> randStdClass = Class.forName("zombie.core.random.RandStandard");
                Field instField = randStdClass.getDeclaredField("INSTANCE");
                instField.setAccessible(true);
                randStdInstance = instField.get(null);
            }
            if (randStdInstance == null) return;

            if (randField == null) {
                Class<?> randAbsClass = Class.forName("zombie.core.random.RandAbstract");
                Field f = randAbsClass.getDeclaredField("rand");
                f.setAccessible(true);
                randField = f;
            }

            Object current = randField.get(randStdInstance);
            if (current == null || current instanceof WorkerRandGovernor) {
                return;
            }

            WorkerRandGovernor wrapper = new WorkerRandGovernor((Random) current);
            randField.set(randStdInstance, wrapper);
            installed = true;
            PZOLogger.success("[PZO WorkerRandGovernor] Installed lock-free per-worker RNG stream on RandStandard (" + current.getClass().getSimpleName() + ")");
        } catch (Throwable ignored) {}
    }

    private static boolean shouldUseWorkerStream() {
        if (!active) return false;
        Thread t = Thread.currentThread();
        if (t == mainThread) return false;
        if (t instanceof WorkerThread) return true;
        String name = t.getName();
        if (name != null) {
            if (name.startsWith("PZO-")) {
                return true;
            }
            if ("MainThread".equals(name) || "main".equals(name)) {
                mainThread = t;
                return false;
            }
        }
        return false;
    }

    @Override
    protected int next(int bits) {
        if (shouldUseWorkerStream()) {
            workerLockFreeDraws.incrementAndGet();
            return ThreadLocalRandom.current().nextInt() >>> (32 - bits);
        }
        return delegate.nextInt() >>> (32 - bits);
    }

    @Override
    public int nextInt() {
        if (shouldUseWorkerStream()) {
            workerLockFreeDraws.incrementAndGet();
            return ThreadLocalRandom.current().nextInt();
        }
        return delegate.nextInt();
    }

    @Override
    public int nextInt(int bound) {
        if (shouldUseWorkerStream()) {
            workerLockFreeDraws.incrementAndGet();
            return ThreadLocalRandom.current().nextInt(bound);
        }
        return delegate.nextInt(bound);
    }

    @Override
    public long nextLong() {
        if (shouldUseWorkerStream()) {
            workerLockFreeDraws.incrementAndGet();
            return ThreadLocalRandom.current().nextLong();
        }
        return delegate.nextLong();
    }

    @Override
    public float nextFloat() {
        if (shouldUseWorkerStream()) {
            workerLockFreeDraws.incrementAndGet();
            return ThreadLocalRandom.current().nextFloat();
        }
        return delegate.nextFloat();
    }

    @Override
    public double nextDouble() {
        if (shouldUseWorkerStream()) {
            workerLockFreeDraws.incrementAndGet();
            return ThreadLocalRandom.current().nextDouble();
        }
        return delegate.nextDouble();
    }

    @Override
    public boolean nextBoolean() {
        if (shouldUseWorkerStream()) {
            workerLockFreeDraws.incrementAndGet();
            return ThreadLocalRandom.current().nextBoolean();
        }
        return delegate.nextBoolean();
    }

    @Override
    public void setSeed(long seed) {
        if (this.delegate != null) {
            this.delegate.setSeed(seed);
        }
    }
}
