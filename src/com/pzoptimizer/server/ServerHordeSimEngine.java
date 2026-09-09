package com.pzoptimizer.server;

import java.util.concurrent.ForkJoinPool;

public class ServerHordeSimEngine {
    public static void apply() {
        try {
            int availableCores = Runtime.getRuntime().availableProcessors();
            int serverThreads = Math.min(32, Math.max(4, availableCores));

            System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", String.valueOf(serverThreads));
            PZOServerLogger.success("ServerHordeSimEngine active (Scaled ForkJoinPool to " + serverThreads + " worker threads for server zombie simulation)");
        } catch (Throwable t) {
            PZOServerLogger.warn("ServerHordeSimEngine notice: " + t.getMessage());
        }
    }
}
