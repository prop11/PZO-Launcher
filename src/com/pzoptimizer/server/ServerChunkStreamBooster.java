package com.pzoptimizer.server;

public class ServerChunkStreamBooster {
    public static void apply() {
        try {
            System.setProperty("pzo.server.stream_buffer_size", "262144");
            System.setProperty("jdk.nio.maxCachedBufferSize", "524288");
            System.setProperty("sun.nio.PageAlignDirectMemory", "true");

            PZOServerLogger.success("ServerChunkStreamBooster active (256KB async chunk save buffering & 512KB page-aligned direct memory)");
        } catch (Throwable t) {
            PZOServerLogger.warn("ServerChunkStreamBooster notice: " + t.getMessage());
        }
    }
}
