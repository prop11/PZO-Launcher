package com.pzoptimizer;

import zombie.core.utils.BoundedQueue;
import zombie.iso.IsoChunk;
import zombie.iso.IsoFloorBloodSplat;
import zombie.iso.IsoGridSquare;

/**
 * Per-tile floor blood cap. Every hit queues more splats on the chunk, and a tile that already shows
 * several layers of blood looks the same with more, but each extra splat still has to be rendered into
 * the chunk floor texture. Once a tile holds the configured number of splats, further splatBlood calls
 * on it are skipped.
 *
 * Driven by the Lua mod's Blood_CapPerTile and Blood_MaxPerTile options; JVM_BytecodeBloodCap is the
 * engine-side master switch.
 */
public final class BloodCapGovernor {

    private static volatile boolean masterEnabled = true;
    private static volatile boolean capEnabled = true;
    private static volatile int maxPerTile = 4;

    private static long splatsSkipped = 0L;

    private BloodCapGovernor() {}

    public static void setMasterEnabled(boolean enabled) {
        masterEnabled = enabled;
    }

    public static void setCapEnabled(boolean enabled) {
        capEnabled = enabled;
    }

    public static void setMaxPerTile(int max) {
        maxPerTile = Math.max(1, Math.min(32, max));
    }

    public static long getSplatsSkipped() {
        return splatsSkipped;
    }

    /** Prefix hook on IsoGridSquare.splatBlood(int, float): returning true skips the vanilla body. */
    public static boolean shouldSkipSplat(Object obj) {
        if (!masterEnabled || !capEnabled) return false;
        try {
            IsoGridSquare sq = (IsoGridSquare) obj;
            IsoChunk chunk = sq.getChunk();
            if (chunk == null) return false;
            BoundedQueue<IsoFloorBloodSplat> splats = chunk.floorBloodSplats;
            int size = splats.size();
            if (size < maxPerTile) return false;

            // Same tile mapping as IsoGridSquare.haveBlood(): splat coordinates are chunk-local
            int localX = sq.getX() - chunk.wx * 8;
            int localY = sq.getY() - chunk.wy * 8;
            int z = sq.getZ();
            int count = 0;
            for (int i = 0; i < size; i++) {
                IsoFloorBloodSplat splat = splats.get(i);
                if (splat != null && (int) splat.x == localX && (int) splat.y == localY && (int) splat.z == z) {
                    if (++count >= maxPerTile) {
                        splatsSkipped++;
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
