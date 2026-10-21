package com.pzoptimizer;

import java.lang.reflect.Field;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

/**
 * Vanilla RandStandard.INSTANCE is one CellularAutomatonRNG behind a fair ReentrantLock, so PZO workers
 * drawing random numbers concurrently (chunk parsing, animation, spatial checks) contend on it.
 * RandStandard.INSTANCE's internal Random is wrapped so that:
 * - the main game thread goes straight to the stock generator (a reference compare), keeping its sequence
 * - PZO worker threads (PZO-MultiCore-Worker, PZO-ChunkStreamer-Worker, PZO-MultiCoreHordeGovernor) use
 *   ThreadLocalRandom with no lock
 * - the wrapper is re-applied if world init replaces RandStandard.INSTANCE.rand
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

    public static final LongAdder workerLockFreeDraws = new LongAdder();

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
     * Installs or re-installs the worker RNG wrapper on zombie.core.random.RandStandard.INSTANCE. Cheap to
     * call every frame: one reference compare when already wrapped.
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

    // EngineThreadGovernor renames the game's main/render threads to "PZO-MainSimulationThread" /
    // "PZO-RenderThread", so a "PZO-" prefix alone would pull them off the stock generator.
    // Classified once per thread; worker names are fixed at creation.
    private static final ThreadLocal<Boolean> IS_WORKER = ThreadLocal.withInitial(() -> {
        Thread t = Thread.currentThread();
        if (t == zombie.MainThread.mainThread) return Boolean.FALSE;
        if (t instanceof WorkerThread) return Boolean.TRUE;
        String name = t.getName();
        return Boolean.valueOf(name != null && (name.startsWith("PZO-MultiCore-Worker-")
            || name.startsWith("PZO-ChunkStreamer-Worker-")
            || name.equals("PZO-MultiCoreHordeGovernor")));
    });

    private static boolean shouldUseWorkerStream() {
        if (!active) return false;
        Thread t = Thread.currentThread();
        if (t == mainThread) return false;
        if (t instanceof WorkerThread) return true;
        if (IS_WORKER.get()) return true;
        if (t == zombie.MainThread.mainThread) mainThread = t;
        return false;
    }

    @Override
    protected int next(int bits) {
        if (shouldUseWorkerStream()) {
            workerLockFreeDraws.increment();
            return ThreadLocalRandom.current().nextInt() >>> (32 - bits);
        }
        return delegate.nextInt() >>> (32 - bits);
    }

    @Override
    public int nextInt() {
        if (shouldUseWorkerStream()) {
            workerLockFreeDraws.increment();
            return ThreadLocalRandom.current().nextInt();
        }
        return delegate.nextInt();
    }

    @Override
    public int nextInt(int bound) {
        if (shouldUseWorkerStream()) {
            workerLockFreeDraws.increment();
            return ThreadLocalRandom.current().nextInt(bound);
        }
        return delegate.nextInt(bound);
    }

    @Override
    public long nextLong() {
        if (shouldUseWorkerStream()) {
            workerLockFreeDraws.increment();
            return ThreadLocalRandom.current().nextLong();
        }
        return delegate.nextLong();
    }

    @Override
    public float nextFloat() {
        if (shouldUseWorkerStream()) {
            workerLockFreeDraws.increment();
            return ThreadLocalRandom.current().nextFloat();
        }
        return delegate.nextFloat();
    }

    @Override
    public double nextDouble() {
        if (shouldUseWorkerStream()) {
            workerLockFreeDraws.increment();
            return ThreadLocalRandom.current().nextDouble();
        }
        return delegate.nextDouble();
    }

    @Override
    public boolean nextBoolean() {
        if (shouldUseWorkerStream()) {
            workerLockFreeDraws.increment();
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
