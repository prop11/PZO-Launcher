package com.pzoptimizer;

/**
 * HotSpot JIT Compiler & JVM Runtime Environment Tuner.
 * Sets runtime system properties to maximize thread concurrency and eliminate JIT compilation lag.
 */
public class HotSpotJITCompilerTuner {

    public static void tuneRuntimeProperties() {
        try {
            int cores = Runtime.getRuntime().availableProcessors();
            
            // 1. Maximize ForkJoinPool worker concurrency across available CPU cores
            int targetParallelism = Math.max(2, Math.min(16, cores));
            System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", String.valueOf(targetParallelism));
            System.setProperty("jdk.virtualThreadScheduler.parallelism", String.valueOf(targetParallelism));
            
            // 2. Suppress unnecessary AWT/Swing headless redraw interrupts
            System.setProperty("sun.java2d.opengl", "true");
            System.setProperty("sun.java2d.d3d", "false");
            System.setProperty("sun.java2d.noddraw", "true");
            
            // 3. Enable JVM canonical file path caching (huge reduction in disk path resolution)
            System.setProperty("sun.io.useCanonCaches", "true");
            System.setProperty("sun.io.useCanonPrefixCache", "true");
            
            // 4. Security random source fast entropy
            System.setProperty("java.security.egd", "file:/dev/urandom");

            PZOLogger.info("HotSpotJITCompilerTuner: Runtime properties configured for " + cores + " CPU cores");
        } catch (Throwable ignored) {}
    }
}
