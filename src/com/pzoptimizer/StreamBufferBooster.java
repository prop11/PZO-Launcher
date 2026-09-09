package com.pzoptimizer;

public class StreamBufferBooster {

    public static void applyStreamTweaks() {
        try {
            System.setProperty("sun.io.useCanonCaches", "true");
            System.setProperty("sun.io.useCanonPrefixCache", "true");

            System.setProperty("jdk.nio.maxCachedBufferSize", "262144");

            int cores = Runtime.getRuntime().availableProcessors();
            System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", String.valueOf(Math.max(4, cores)));

            System.setProperty("sun.net.inetaddr.ttl", "60");
        } catch (Throwable ignored) {}
    }
}
