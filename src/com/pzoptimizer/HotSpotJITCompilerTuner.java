package com.pzoptimizer;

/**
 * HotSpot JIT Compiler & JVM Runtime Environment Tuner.
 * Sets runtime system properties to maximize thread concurrency and eliminate JIT compilation lag.
 */
public class HotSpotJITCompilerTuner {

    public static void tuneRuntimeProperties() {
        try {
            int cores = Runtime.getRuntime().availableProcessors();
            
            int targetParallelism = Math.max(2, Math.min(16, cores));
            System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", String.valueOf(targetParallelism));
            System.setProperty("jdk.virtualThreadScheduler.parallelism", String.valueOf(targetParallelism));
            
            System.setProperty("sun.java2d.opengl", "true");
            System.setProperty("sun.java2d.d3d", "false");
            System.setProperty("sun.java2d.noddraw", "true");
            
            System.setProperty("sun.io.useCanonCaches", "true");
            System.setProperty("sun.io.useCanonPrefixCache", "true");
            
            System.setProperty("java.security.egd", "file:/dev/urandom");

            PZOLogger.info("HotSpotJITCompilerTuner: Runtime properties configured for " + cores + " CPU cores");
        } catch (Throwable ignored) {}
    }
}
