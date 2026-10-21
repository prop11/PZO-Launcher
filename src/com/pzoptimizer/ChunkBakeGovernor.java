package com.pzoptimizer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Crossing chunk boundaries or passing tall buildings can re-bake 30-90 chunk levels in one burst and
 * stall the render thread. This governor:
 * - caps bakes per frame (4-6 while driving), except for the player's own 3x3 chunks
 * - clamps FBO mip chains to levels 1-3
 * - only dirties chunk levels for corpse flies when flies presence actually toggles
 */
public final class ChunkBakeGovernor {

    private static volatile boolean active = true;
    private static volatile int maxBakesPerFrame = 6;
    private static volatile int bakeMipLevels = 3; // Levels 1-3 sufficient for maximum zoom LOD <= 1.33

    private static final AtomicInteger bakesThisFrame = new AtomicInteger(0);
    public static final AtomicLong totalBakesGoverned = new AtomicLong(0);
    public static final AtomicLong totalDeferredBakes = new AtomicLong(0);
    public static final AtomicLong totalRedundantFliesAvoided = new AtomicLong(0);

    private static volatile int playerChunkX = -1;
    private static volatile int playerChunkY = -1;

    private ChunkBakeGovernor() {}

    public static void setActive(boolean value) {
        active = value;
    }

    public static boolean isActive() {
        return active;
    }

    public static void setMaxBakesPerFrame(int max) {
        maxBakesPerFrame = Math.max(1, Math.min(32, max));
    }

    public static int getMaxBakesPerFrame() {
        return maxBakesPerFrame;
    }

    public static int getBakeMipLevels() {
        return bakeMipLevels;
    }

    public static void setBakeMipLevels(int levels) {
        bakeMipLevels = Math.max(1, Math.min(11, levels));
    }

    public static void onFrameBoundary(int frameCount, int pChunkX, int pChunkY) {
        bakesThisFrame.set(0);
        playerChunkX = pChunkX;
        playerChunkY = pChunkY;
    }

    /**
     * Whether a chunk level may bake this frame. The player's surrounding chunks (dist <= 1) always may;
     * the rest share the per-frame budget.
     */
    public static boolean allowChunkBake(int wx, int wy, int level) {
        if (!active) return true;

        if (playerChunkX != -1 && playerChunkY != -1) {
            int dx = Math.abs(wx - playerChunkX);
            int dy = Math.abs(wy - playerChunkY);
            if (dx <= 1 && dy <= 1) {
                bakesThisFrame.incrementAndGet();
                totalBakesGoverned.incrementAndGet();
                return true;
            }
        }

        int current = bakesThisFrame.get();
        if (current < maxBakesPerFrame) {
            bakesThisFrame.incrementAndGet();
            totalBakesGoverned.incrementAndGet();
            return true;
        }

        totalDeferredBakes.incrementAndGet();
        return false;
    }

    /** Only dirties chunk levels for FliesSound when flies presence actually toggled. */
    public static boolean shouldUpdateFlies(boolean previousHasFlies, boolean newHasFlies) {
        if (!active) return true;
        if (previousHasFlies == newHasFlies) {
            totalRedundantFliesAvoided.incrementAndGet();
            return false;
        }
        return true;
    }
}
