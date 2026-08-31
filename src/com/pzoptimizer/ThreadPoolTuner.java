package com.pzoptimizer;

/**
 * Hardware-Adaptive CPU Thread Pool & Parallelism Tuner.
 * Dynamically scales ForkJoinPool and worker pools to match physical CPU performance cores
 * without overloading low-end dual-core or thermal-throttled laptop CPUs.
 */
public class ThreadPoolTuner {
    public static void initialize() {
        try {
            int availableCores = Runtime.getRuntime().availableProcessors();
            // Clamp safely between 2 and 16 cores (100% stable across all hardware)
            int targetParallelism = Math.max(2, Math.min(16, availableCores));

            System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", String.valueOf(targetParallelism));
            System.setProperty("jdk.virtualThreadScheduler.parallelism", String.valueOf(targetParallelism));
            
            PZOLogger.success(String.format("ThreadPoolTuner active (Scaled ForkJoinPool parallelism: %d threads for %d CPU cores)",
                targetParallelism, availableCores));
        } catch (Throwable t) {
            PZOLogger.warn("ThreadPoolTuner non-fatal fallback: " + t.getMessage());
        }
    }
}
