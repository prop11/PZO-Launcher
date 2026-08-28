package com.pzoptimizer;

/**
 * Project Zomboid Build 42 - Zero-Stutter Chunk & Savegame I/O Accelerator.
 * Enhances background disk streaming for world save files (map_*.bin, zpop_*.bin).
 */
public class SaveGameStreamBooster {
    private static final int OPTIMAL_BUFFER_SIZE = 131072; // 128 KB high-throughput buffer

    public static int getOptimalBufferSize() {
        return OPTIMAL_BUFFER_SIZE;
    }

    public static void tuneSaveEngine() {
        try {
            // Tune native I/O buffer properties
            System.setProperty("zomboid.io.buffersize", String.valueOf(OPTIMAL_BUFFER_SIZE));
            System.setProperty("sun.nio.PageAlignDirectMemory", "true");
        } catch (Throwable ignored) {}
    }
}
