package com.pzoptimizer;

import java.util.concurrent.ConcurrentHashMap;

public final class ChunkRetentionRing {

    private static final int MAX_RETAINED_CHUNKS = 512;
    private static final long RETENTION_DURATION_MS = 45_000L; // 45 seconds

    private static final ConcurrentHashMap<Long, Long> RETAINED_CHUNKS = new ConcurrentHashMap<>(MAX_RETAINED_CHUNKS);
    private static volatile long lastSweepTime = 0;

    public static void touch(int wx, int wy) {
        long key = FastChunkKey.pack(wx, wy);
        long now = System.currentTimeMillis();
        RETAINED_CHUNKS.put(key, now);

        if (now - lastSweepTime > 15_000L) {
            lastSweepTime = now;
            sweepExpired(now);
        }
    }

    public static boolean isRetained(int wx, int wy) {
        long key = FastChunkKey.pack(wx, wy);
        Long ts = RETAINED_CHUNKS.get(key);
        if (ts == null) return false;
        return (System.currentTimeMillis() - ts) < RETENTION_DURATION_MS;
    }

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
