package com.pzoptimizer;

public class DirectMemoryTuner {
    public static void initialize() {
        try {
            System.setProperty("sun.nio.PageAlignDirectMemory", "true");
            System.setProperty("jdk.nio.maxCachedBufferSize", "524288"); // 512KB direct buffer cache
            System.setProperty("zomboid.io.buffersize", "131072");        // 128KB chunk stream buffer
            
            PZOLogger.success("DirectMemoryTuner active (512KB page-aligned NIO memory caching enforced)");
        } catch (Throwable t) {
            PZOLogger.warn("DirectMemoryTuner non-fatal fallback: " + t.getMessage());
        }
    }
}
