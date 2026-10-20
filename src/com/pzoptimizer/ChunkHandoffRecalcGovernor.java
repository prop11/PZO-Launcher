package com.pzoptimizer;

import zombie.iso.IsoGridSquare;

/**
 * Chunk Border Hand-off Recalc Gate.
 * During IsoChunk.loadInMainThread() and WorldStreamer neighbor recalculations, border squares
 * invoke IsoGridSquare.RecalcPropertiesIfNeeded() up to ~15 times per square within the same
 * micro-window. Vanilla already guards the work with the public propertiesDirty flag, so the only
 * safe dedupe is that same flag: a clean square returns immediately, a dirty one always recalcs.
 * (A time-window dedupe would skip legitimate recalcs on squares re-dirtied inside the window.)
 *
 * Counters are plain longs: this sits on a very hot path and exact telemetry is not worth a
 * contended atomic per call.
 */
public final class ChunkHandoffRecalcGovernor {

    private static volatile boolean active = true;

    private static long recalcsExecuted = 0L;
    private static long cleanSquaresSkipped = 0L;

    private ChunkHandoffRecalcGovernor() {}

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean val) {
        active = val;
    }

    public static long getRecalcsExecuted() {
        return recalcsExecuted;
    }

    public static long getCleanSquaresSkipped() {
        return cleanSquaresSkipped;
    }

    /**
     * Redirect target for invokevirtual IsoGridSquare.RecalcPropertiesIfNeeded()V inside IsoGridSquare.
     */
    public static void recalcPropertiesIfNeededFast(Object square) {
        IsoGridSquare sq = (IsoGridSquare) square;
        if (active && !sq.propertiesDirty) {
            cleanSquaresSkipped++;
            return;
        }
        recalcsExecuted++;
        sq.RecalcPropertiesIfNeeded();
    }
}
