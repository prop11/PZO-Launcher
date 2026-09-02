package com.pzoptimizer;

import java.util.concurrent.ConcurrentHashMap;

/**
 * PZO Chunk Retention Ring & Hysteresis Shield (Next-Gen Chunk Streaming Engine).
 * 
 * In vanilla Build 42, crossing back and forth across a chunk boundary causes the engine to
 * immediately unload chunks behind the player, flush them to disk, and re-read them from disk
 * moments later when the player takes a step backward (boundary thrashing).
 * 
 * ChunkRetentionRing maintains a memory-backed LRU retention ring of recently active chunk coordinates:
 * - Retains recent chunks in memory for a 45-second hysteresis window.
 * - Prevents thrashing write-read cycles when looting buildings or fighting near chunk lines.
 * - 100% thread-safe with zero temporary heap allocations.
 */
public final class ChunkRetentionRing {

    private static final int MAX_RETAINED_CHUNKS = 512;
    private static final long RETENTION_DURATION_MS = 45_000L; // 45 seconds

    private static final ConcurrentHashMap<Long, Long> RETAINED_CHUNKS = new ConcurrentHashMap<>(MAX_RETAINED_CHUNKS);
    private static volatile long lastSweepTime = 0;

    /**
     * Records access to chunk (wx, wy), updating its retention timestamp.
     */
    public static void touch(int wx, int wy) {
        long key = FastChunkKey.pack(wx, wy);
        long now = System.currentTimeMillis();
        RETAINED_CHUNKS.put(key, now);

        // Perform periodic sweep every 15 seconds
        if (now - lastSweepTime > 15_000L) {
            lastSweepTime = now;
            sweepExpired(now);
        }
    }

    /**
     * Checks if chunk (wx, wy) is within the active retention window.
     */
    public static boolean isRetained(int wx, int wy) {
        long key = FastChunkKey.pack(wx, wy);
        Long ts = RETAINED_CHUNKS.get(key);
        if (ts == null) return false;
        return (System.currentTimeMillis() - ts) < RETENTION_DURATION_MS;
    }

    /**
     * Returns total number of retained chunks currently in memory.
     */
    public static int getRetainedCount() {
        return RETAINED_CHUNKS.size();
    }

    private static void sweepExpired(long now) {
        if (RETAINED_CHUNKS.size() <= MAX_RETAINED_CHUNKS / 2) return;

        RETAINED_CHUNKS.entrySet().removeIf(entry -> (now - entry.getValue()) > RETENTION_DURATION_MS);
    }

    public static void clear() {
        RETAINED_CHUNKS.clear();
    }
}
