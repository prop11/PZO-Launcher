package com.pzoptimizer;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ChunkSaveWorker Ancillary Hot-Save Throttler.
 * While driving or running across chunks, ChunkSaveWorker's save queue drains roughly every 0.45s
 * and calls HotsaveAncilliarySystems(), which serializes the entire IsoMetaGrid on the GameThread
 * via MainThread.invokeOnMainThread, causing a 2-5 ms GameThread hitch twice a second.
 *
 * HotSaveGovernor throttles mid-travel ancillary hot-saves to a configurable interval (default 10s),
 * while always permitting immediate saves during world shutdown or explicit flush.
 */
public final class HotSaveGovernor {

    private static volatile boolean active = true;
    private static volatile long intervalNanos = 10_000_000_000L; // 10 seconds default
    private static volatile long lastHotSaveNanos = 0L;
    private static Method stockHotSaveMethod = null;

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

    /**
     * Returns true if the ancillary hot-save should proceed now, or false if throttled.
     */
    public static boolean shouldRunHotSave() {
        if (!active || intervalNanos <= 0L) {
            hotSavesExecuted.incrementAndGet();
            return true;
        }
        long now = System.nanoTime();
        long last = lastHotSaveNanos;
        if (last == 0L || (now - last) >= intervalNanos) {
            lastHotSaveNanos = now;
            hotSavesExecuted.incrementAndGet();
            return true;
        }
        hotSavesSkipped.incrementAndGet();
        return false;
    }

    /**
     * Direct 3-byte bytecode redirection target matching ChunkSaveWorker.HotsaveAncilliarySystems()V.
     */
    public static void HotsaveAncilliarySystems() {
        runHotSaveIfDue();
    }

    /**
     * 3-byte bytecode redirection target for invokestatic ChunkSaveWorker.HotsaveAncilliarySystems()V.
     */
    public static void runHotSaveIfDue() {
        if (!shouldRunHotSave()) {
            return;
        }
        try {
            if (stockHotSaveMethod == null) {
                Class<?> cls = Class.forName("zombie.iso.ChunkSaveWorker");
                stockHotSaveMethod = cls.getDeclaredMethod("HotsaveAncilliarySystems");
                stockHotSaveMethod.setAccessible(true);
            }
            stockHotSaveMethod.invoke(null);
        } catch (Throwable ignored) {}
    }

    public static void reset() {
        lastHotSaveNanos = 0L;
    }
}
