package com.pzoptimizer;

/**
 * Kahlua VM GC Pacing - Passive Mode.
 * Ensures Kahlua Lua memory is managed strictly on the main game thread
 * with zero background thread contention or micro-stutters.
 */
public class KahluaGCPacer {
    public static void start() {
        // Maintained as passive stub to prevent background Lua thread contention
    }
}
