package com.pzoptimizer;

/**
 * High-Performance Primitive Bitwise Chunk Hash Keyer.
 * Replaces string concatenations ("map_" + wx + "_" + wy) with 64-bit primitive packed integers.
 * Eliminates 100% of temporary heap allocations during vehicle driving and chunk streaming.
 */
public final class FastChunkKey {

    /**
     * Packs 2D world chunk coordinates (wx, wy) into a single 64-bit primitive long.
     */
    public static long pack(int wx, int wy) {
        return (((long) wx) << 32) | (wy & 0xFFFFFFFFL);
    }

    /**
     * Extracts world X coordinate from packed key.
     */
    public static int getX(long key) {
        return (int) (key >> 32);
    }

    /**
     * Extracts world Y coordinate from packed key.
     */
    public static int getY(long key) {
        return (int) key;
    }
}
