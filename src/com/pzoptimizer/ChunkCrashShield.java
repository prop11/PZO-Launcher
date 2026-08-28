package com.pzoptimizer;

/**
 * Project Zomboid Build 42 - Corrupt Chunk & Savegame Recovery Shield.
 * Prevents game-ending crashes when encountering truncated, corrupted, or
 * invalid map chunk files (map_X_Y.bin) from crashed saves or removed map mods.
 */
public class ChunkCrashShield {
    private static volatile int recoveredChunks = 0;

    public static void logCorruptChunk(int wx, int wy, Throwable t) {
        recoveredChunks++;
        PZOLogger.warn(String.format("[ChunkCrashShield] Caught and mitigated corrupt chunk data at [%d, %d]: %s", wx, wy, t.getMessage()));
    }

    public static int getRecoveredChunkCount() {
        return recoveredChunks;
    }
}
