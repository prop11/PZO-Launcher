package com.pzoptimizer;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import zombie.iso.ChunkSaveWorker;
import zombie.iso.IsoChunk;

/**
 * While driving or running across chunks, ChunkSaveWorker's save queue drains about every 0.45 s and calls
 * HotsaveAncilliarySystems(), which serializes the whole IsoMetaGrid on the game thread via
 * MainThread.invokeOnMainThread: a 2-5 ms hitch twice a second.
 *
 * Mid-travel ancillary hot-saves are throttled to a configurable interval (default 10 s). Deferred saves
 * are flushed from ChunkSaveWorker.Update on the WorldStreamer thread, the only thread vanilla hot-saves
 * from, because HotsaveAncilliarySystems shares a static SaveBufferMap and does disk I/O, so it must never
 * run concurrently or on the game thread.
 */
public final class HotSaveGovernor {

    private static volatile boolean active = true;
    private static volatile long intervalNanos = 10_000_000_000L; // 10 seconds default
    private static volatile long lastHotSaveNanos = 0L;
    private static final AtomicBoolean pendingHotSave = new AtomicBoolean(false);

    private static volatile Method stockHotSaveMethod = null;
    private static volatile boolean reflectionFailed = false;

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
     * Redirect target for the invokevirtual ChunkSaveWorker.HotsaveAncilliarySystems() call inside
     * ChunkSaveWorker.Update (vanilla only reaches it in singleplayer, after the save queue drains).
     */
    public static void hotsaveAncilliarySystems(Object worker) {
        if (worldLoading()) {
            pendingHotSave.set(true);
            hotSavesDeferredWhileLoading.incrementAndGet();
            return;
        }
        long now = System.nanoTime();
        long last = lastHotSaveNanos;
        if (!active || intervalNanos <= 0L || last == 0L || (now - last) >= intervalNanos) {
            pendingHotSave.set(false);
            runStockHotSave(worker, now);
        } else {
            pendingHotSave.set(true);
            hotSavesSkipped.incrementAndGet();
        }
    }

    /**
     * Redirect target for ChunkSaveWorker.Update(IsoChunk) inside WorldStreamer. The idle streamer loop
     * calls Update(null) every ~140 ms, which gives the deferred save a safe thread to run on.
     */
    public static void update(Object worker, Object aboutToLoad) {
        ((ChunkSaveWorker) worker).Update((IsoChunk) aboutToLoad);
        if (!pendingHotSave.get() || worldLoading()) {
            return;
        }
        long now = System.nanoTime();
        if (!active || (now - lastHotSaveNanos) >= intervalNanos) {
            if (pendingHotSave.compareAndSet(true, false)) {
                runStockHotSave(worker, now);
            }
        }
    }

    private static void runStockHotSave(Object worker, long now) {
        lastHotSaveNanos = now;
        hotSavesExecuted.incrementAndGet();
        try {
            Method m = stockHotSaveMethod;
            if (m == null) {
                if (reflectionFailed) return;
                m = ChunkSaveWorker.class.getDeclaredMethod("HotsaveAncilliarySystems");
                m.setAccessible(true);
                stockHotSaveMethod = m;
            }
            m.invoke(worker);
        } catch (NoSuchMethodException e) {
            reflectionFailed = true;
            PZOLogger.warn("[HotSaveGovernor] HotsaveAncilliarySystems not found - hot-saves disabled until restart: " + e.getMessage());
        } catch (java.lang.reflect.InvocationTargetException e) {
            PZOLogger.warn("[HotSaveGovernor] Hot-save threw: " + e.getCause());
        } catch (Throwable t) {
            PZOLogger.warn("[HotSaveGovernor] Hot-save invoke failed: " + t.getMessage());
        }
    }

    /**
     * The game's loading thread is still running. Loading reads several save files (map_symbols.bin in
     * MapItem.LoadWorldMap among them) into the shared SliceY.SliceBuffer without SliceBufferLock, while the hot-save
     * fills the same buffer on the game thread: both corrupt each other (BufferUnderflowException in LoadWorldMap,
     * BufferOverflowException in IsoMetaGrid.savePart, the map symbols not loaded). Nothing needs a hot-save before
     * the world is loaded, so it waits and runs on the first streamer update afterwards. Applies with or without the
     * throttle.
     */
    private static boolean worldLoading() {
        Thread loader = zombie.gameStates.GameLoadingState.loader;
        return loader != null && loader.isAlive();
    }

    public static final AtomicLong hotSavesDeferredWhileLoading = new AtomicLong(0);

    public static void reset() {
        lastHotSaveNanos = 0L;
        pendingHotSave.set(false);
    }
}
