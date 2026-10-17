package com.pzoptimizer;

import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chunk Border Hand-off Recalc Deduplicator (Lock-Free Per-Thread).
 * During IsoChunk.loadInMainThread() and WorldStreamer neighbor recalculations, border squares
 * invoke IsoGridSquare.RecalcPropertiesIfNeeded() up to ~15 times per square within the same
 * micro-window.
 *
 * Uses per-thread (ThreadLocal) deduplication state so MainThread, WorldStreamer, and
 * PZO-ChunkStreamer-Worker threads never contend on a shared lock and never suppress
 * another thread's legitimate recalculation pass.
 */
public final class ChunkHandoffRecalcGovernor {

    private static volatile boolean active = true;
    private static final long DEDUPE_WINDOW_NANOS = 1_500_000L; // 1.5 ms burst window per thread
    private static volatile Method recalcMethod = null;

    private static final class ThreadWindow {
        long windowStartNanos = 0L;
        final IdentityHashMap<Object, Boolean> seenSquares = new IdentityHashMap<>(256);
    }

    private static final ThreadLocal<ThreadWindow> THREAD_WINDOW =
        ThreadLocal.withInitial(ThreadWindow::new);

    public static final AtomicLong recalcsExecuted = new AtomicLong(0);
    public static final AtomicLong redundantRecalcsSkipped = new AtomicLong(0);

    private ChunkHandoffRecalcGovernor() {}

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean val) {
        active = val;
    }

    /**
     * 3-byte bytecode redirection target for invokevirtual IsoGridSquare.RecalcPropertiesIfNeeded()Z
     * inside IsoChunk.
     */
    public static boolean recalcPropertiesIfNeededFast(Object square) {
        if (square == null) return false;
        if (active) {
            long now = System.nanoTime();
            ThreadWindow tw = THREAD_WINDOW.get();
            if ((now - tw.windowStartNanos) > DEDUPE_WINDOW_NANOS) {
                tw.seenSquares.clear();
                tw.windowStartNanos = now;
            }
            if (tw.seenSquares.put(square, Boolean.TRUE) != null) {
                redundantRecalcsSkipped.incrementAndGet();
                return false;
            }
        }
        recalcsExecuted.incrementAndGet();
        try {
            Method m = recalcMethod;
            if (m == null) {
                m = square.getClass().getDeclaredMethod("RecalcPropertiesIfNeeded");
                m.setAccessible(true);
                recalcMethod = m;
            }
            return ((Boolean) m.invoke(square)).booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }
}
