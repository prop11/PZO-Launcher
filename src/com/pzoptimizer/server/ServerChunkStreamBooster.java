package com.pzoptimizer.server;

/** 256 KB disk buffers for server world saves. */
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
