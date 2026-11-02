package com.pzoptimizer;

import zombie.core.utils.BoundedQueue;
import zombie.iso.IsoChunk;
import zombie.iso.IsoFloorBloodSplat;
import zombie.iso.IsoGridSquare;

/**
 * Caps floor blood per tile. Past a few layers a tile looks the same, but every extra splat still gets drawn into the
 * chunk floor texture, so once a tile has the configured number I skip further splatBlood calls. Driven by
 * Blood_CapPerTile / Blood_MaxPerTile in the Lua mod, with JVM_BytecodeBloodCap as the engine switch.
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
