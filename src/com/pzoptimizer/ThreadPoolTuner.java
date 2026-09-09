package com.pzoptimizer;

public class ThreadPoolTuner {
    public static void initialize() {
        try {
            int availableCores = Runtime.getRuntime().availableProcessors();
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
