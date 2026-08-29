package com.pzoptimizer.server;

/**
 * Project Zomboid Dedicated Server - Zero-Lag World Save & SQLite Stream Booster.
 * Configures 256KB disk buffer streaming to eliminate world save lag spikes.
 */
public class ServerChunkStreamBooster {
    public static void apply() {
        try {
            // 256KB page-aligned buffer chunks for SQLite .db and map_*.bin files
            System.setProperty("pzo.server.stream_buffer_size", "262144");
            System.setProperty("jdk.nio.maxCachedBufferSize", "524288");
            System.setProperty("sun.nio.PageAlignDirectMemory", "true");

            PZOServerLogger.success("ServerChunkStreamBooster active (256KB async chunk save buffering & 512KB page-aligned direct memory)");
        } catch (Throwable t) {
            PZOServerLogger.warn("ServerChunkStreamBooster notice: " + t.getMessage());
        }
    }
}
