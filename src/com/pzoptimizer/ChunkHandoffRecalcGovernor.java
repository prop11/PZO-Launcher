package com.pzoptimizer;

import zombie.iso.IsoGridSquare;

/**
 * Border squares call RecalcPropertiesIfNeeded() up to ~15 times in a row during IsoChunk.loadInMainThread() and the
 * streamer's neighbour recalcs. Vanilla's propertiesDirty flag is the only safe dedupe (clean returns, dirty always
 * recalcs); a time-window dedupe would miss squares that get dirtied again inside the window.
 *
 * The counters are plain longs on purpose: this path is very hot and exact stats aren't worth a contended atomic.
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

    /** Redirect target for IsoGridSquare.RecalcPropertiesIfNeeded() calls inside IsoGridSquare. */
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
