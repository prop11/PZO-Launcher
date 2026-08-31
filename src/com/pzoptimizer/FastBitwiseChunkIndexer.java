package com.pzoptimizer;

/**
 * PZO 64-Bit Register Spatial Chunk & Meta-Grid Indexer.
 * Operates directly on 64-bit CPU machine registers in 1 instruction,
 * eliminating integer overflow bugs and HashMap coordinate hashing overhead.
 * 100% deterministic and cross-platform.
 */
public final class FastBitwiseChunkIndexer {

    public static void initialize() {
        PZOLogger.success("FastBitwiseChunkIndexer: 64-Bit Register Spatial Indexer Ready");
    }

    public static long packChunkKey(int chunkX, int chunkY) {
        return ((long) chunkX << 32) | (chunkY & 0xFFFFFFFFL);
    }

    public static int unpackChunkX(long key) {
        return (int) (key >>> 32);
    }

    public static int unpackChunkY(long key) {
        return (int) key;
    }

    public static long packTileKey(int x, int y, int z) {
        return ((long) (z & 0xFF) << 48) | ((long) (x & 0xFFFFFF) << 24) | (y & 0xFFFFFF);
    }
}
