package com.pzoptimizer;

/**
 * Build 42 Direct Memory & NIO Stream Acceleration.
 * Configures per-thread direct buffer caches and CPU core worker concurrency.
 */
public class StreamBufferBooster {

    public static void applyStreamTweaks() {
        try {
            // Enable canonical file path caching in JVM
            System.setProperty("sun.io.useCanonCaches", "true");
            System.setProperty("sun.io.useCanonPrefixCache", "true");

            // Java 17 NIO Direct Memory cache (256 KB per thread buffer)
            System.setProperty("jdk.nio.maxCachedBufferSize", "262144");

            // Concurrency scaling for background async loaders
            int cores = Runtime.getRuntime().availableProcessors();
            System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", String.valueOf(Math.max(4, cores)));

            // High-throughput network DNS cache timeout for MP servers
            System.setProperty("sun.net.inetaddr.ttl", "60");
        } catch (Throwable ignored) {}
    }
}
