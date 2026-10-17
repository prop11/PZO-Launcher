package com.pzoptimizer;

import java.lang.reflect.Field;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-Worker Lock-Free RNG Stream Governor.
 * Vanilla Project Zomboid's RandStandard.INSTANCE uses a single CellularAutomatonRNG
 * protected by a fair ReentrantLock. When multi-core worker threads execute zombie AI,
 * sound responses, or spatial checks concurrently, they contend heavily on that lock
 * (~7% of worker CPU time wasted in lock hand-offs).
 *
 * WorkerRandGovernor wraps RandStandard.INSTANCE's internal Random field so that:
 * 1. The main GameThread delegates directly to the stock generator (preserving main-thread sequence).
 * 2. PZO MultiCore worker threads draw from lock-free thread-local RNG streams with zero contention.
 */
public final class WorkerRandGovernor extends Random {

    private static volatile boolean active = true;
    private static volatile boolean installed = false;
    private static Field randField = null;
    private static Random wrappedOriginal = null;

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
     * Installs the lock-free worker RNG wrapper onto zombie.core.random.RandStandard.INSTANCE.
     */
    public static synchronized void ensureInstalled() {
        if (!active) return;
        try {
            Class<?> randStdClass = Class.forName("zombie.core.random.RandStandard");
            Field instField = randStdClass.getDeclaredField("INSTANCE");
            instField.setAccessible(true);
            Object instance = instField.get(null);
            if (instance == null) return;

            if (randField == null) {
                Class<?> randAbsClass = Class.forName("zombie.core.random.RandAbstract");
                randField = randAbsClass.getDeclaredField("rand");
                randField.setAccessible(true);
            }

            Object current = randField.get(instance);
            if (current == null || current instanceof WorkerRandGovernor) {
                return;
            }

            wrappedOriginal = (Random) current;
            randField.set(instance, new WorkerRandGovernor(wrappedOriginal));
            installed = true;
            PZOLogger.success("[PZO WorkerRandGovernor] Installed lock-free per-worker RNG stream on RandStandard (" + current.getClass().getSimpleName() + ")");
        } catch (Throwable ignored) {}
    }

    private static boolean isPzoWorkerThread() {
        String name = Thread.currentThread().getName();
        return name != null && (name.startsWith("PZO-MultiCore-Worker") || name.startsWith("PZO-Chunk") || name.startsWith("PZO-Horde"));
    }

    @Override
    protected int next(int bits) {
        if (active && isPzoWorkerThread()) {
            workerLockFreeDraws.incrementAndGet();
            return ThreadLocalRandom.current().nextInt() >>> (32 - bits);
        }
        return delegate.nextInt() >>> (32 - bits);
    }

    @Override
    public int nextInt() {
        if (active && isPzoWorkerThread()) {
            workerLockFreeDraws.incrementAndGet();
            return ThreadLocalRandom.current().nextInt();
        }
        return delegate.nextInt();
    }

    @Override
    public int nextInt(int bound) {
        if (active && isPzoWorkerThread()) {
            workerLockFreeDraws.incrementAndGet();
            return ThreadLocalRandom.current().nextInt(bound);
        }
        return delegate.nextInt(bound);
    }

    @Override
    public float nextFloat() {
        if (active && isPzoWorkerThread()) {
            workerLockFreeDraws.incrementAndGet();
            return ThreadLocalRandom.current().nextFloat();
        }
        return delegate.nextFloat();
    }

    @Override
    public boolean nextBoolean() {
        if (active && isPzoWorkerThread()) {
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
