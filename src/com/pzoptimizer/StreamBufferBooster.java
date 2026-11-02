package com.pzoptimizer;

/** System properties for the direct buffer cache, path cache and loader concurrency. */
public class StreamBufferBooster {

    public static void applyStreamTweaks() {
        try {
            // Canonical path caching in the JVM
            System.setProperty("sun.io.useCanonCaches", "true");
            System.setProperty("sun.io.useCanonPrefixCache", "true");

            // NIO direct buffer cache, 256 KB per thread
            System.setProperty("jdk.nio.maxCachedBufferSize", "262144");

            // Concurrency for the background async loaders
            int cores = Runtime.getRuntime().availableProcessors();
            System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", String.valueOf(Math.max(4, cores)));

            // DNS cache timeout, for multiplayer servers
            System.setProperty("sun.net.inetaddr.ttl", "60");
        } catch (Throwable ignored) {}
    }
}
