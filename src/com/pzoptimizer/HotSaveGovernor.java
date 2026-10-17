package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ChunkSaveWorker Ancillary Hot-Save Throttler.
 * While driving or running across chunks, ChunkSaveWorker's save queue drains roughly every 0.45s
 * and calls HotsaveAncilliarySystems(), which serializes the entire IsoMetaGrid on the GameThread
 * via MainThread.invokeOnMainThread, causing a 2-5 ms GameThread hitch twice a second.
 *
 * HotSaveGovernor throttles mid-travel ancillary hot-saves to a configurable interval (default 10s),
 * tracks deferred requests via pendingHotSave so idle frames never trigger unnecessary saves,
 * and always permits immediate saves during world shutdown (ChunkSaveWorker.bSaving).
 */
public final class HotSaveGovernor {

    private static volatile boolean active = true;
    private static volatile long intervalNanos = 10_000_000_000L; // 10 seconds default
    private static volatile long lastHotSaveNanos = 0L;
    private static final AtomicBoolean pendingHotSave = new AtomicBoolean(false);

    private static volatile Method stockHotSaveMethod = null;
    private static volatile Field instanceField = null;
    private static volatile Field bSavingField = null;
    private static volatile boolean reflectionReady = false;

    public static final AtomicLong hotSavesExecuted = new AtomicLong(0);
    public static final AtomicLong hotSavesSkipped = new AtomicLong(0);

    private HotSaveGovernor() {}

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean val) {
        active = val;
    }

    public static void setIntervalSeconds(int seconds) {
        intervalNanos = Math.max(0L, seconds) * 1_000_000_000L;
    }

    public static int getIntervalSeconds() {
        return (int) (intervalNanos / 1_000_000_000L);
    }

    private static void initReflection() {
        if (reflectionReady) return;
        try {
            Class<?> cls = Class.forName("zombie.iso.ChunkSaveWorker");
            stockHotSaveMethod = cls.getDeclaredMethod("HotsaveAncilliarySystems");
            stockHotSaveMethod.setAccessible(true);
            try {
                instanceField = cls.getDeclaredField("instance");
                instanceField.setAccessible(true);
                bSavingField = cls.getDeclaredField("bSaving");
                bSavingField.setAccessible(true);
            } catch (Throwable ignored) {}
            reflectionReady = true;
        } catch (Throwable ignored) {}
    }

    private static boolean isWorldShuttingDown() {
        try {
            if (instanceField != null && bSavingField != null) {
                Object inst = instanceField.get(null);
                if (inst != null && bSavingField.getBoolean(inst)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Direct 3-byte bytecode redirection target matching ChunkSaveWorker.HotsaveAncilliarySystems()V.
     * Called by ChunkSaveWorker when a chunk save batch finishes.
     */
    public static void HotsaveAncilliarySystems() {
        if (!reflectionReady) initReflection();

        if (!active || intervalNanos <= 0L || isWorldShuttingDown()) {
            pendingHotSave.set(false);
            lastHotSaveNanos = System.nanoTime();
            invokeStockHotSave();
            return;
        }

        long now = System.nanoTime();
        long last = lastHotSaveNanos;
        if (last == 0L || (now - last) >= intervalNanos) {
            lastHotSaveNanos = now;
            pendingHotSave.set(false);
            invokeStockHotSave();
        } else {
            pendingHotSave.set(true);
            hotSavesSkipped.incrementAndGet();
        }
    }

    /**
     * Called once per frame on the GameThread.
     * Fast-exits in 1ns when no hot-save is pending; flushes a deferred hot-save once the 10s window elapses.
     */
    public static void flushPendingIfDue() {
        if (!pendingHotSave.get()) {
            return;
        }
        long now = System.nanoTime();
        long last = lastHotSaveNanos;
        if (last == 0L || (now - last) >= intervalNanos || !active) {
            if (pendingHotSave.compareAndSet(true, false)) {
                lastHotSaveNanos = now;
                invokeStockHotSave();
            }
        }
    }

    public static void runHotSaveIfDue() {
        flushPendingIfDue();
    }

    private static void invokeStockHotSave() {
        hotSavesExecuted.incrementAndGet();
        try {
            if (!reflectionReady) initReflection();
            if (stockHotSaveMethod != null) {
                stockHotSaveMethod.invoke(null);
            }
        } catch (Throwable ignored) {}
    }

    public static void reset() {
        lastHotSaveNanos = 0L;
        pendingHotSave.set(false);
    }
}
