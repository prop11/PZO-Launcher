package com.pzoptimizer;

public class NettyBufferPooler {
    public static void apply() {
        try {
            int cores = Math.max(2, Runtime.getRuntime().availableProcessors());

            System.setProperty("io.netty.allocator.type", "pooled");
            System.setProperty("io.netty.allocator.numDirectArenas", String.valueOf(cores));
            System.setProperty("io.netty.allocator.numHeapArenas", String.valueOf(Math.max(1, cores / 2)));
            System.setProperty("io.netty.noPreferDirect", "false");

            System.setProperty("sun.net.maxDatagramSockets", "1024");
            System.setProperty("java.net.preferIPv4Stack", "true");

            PZOLogger.success("NettyBufferPooler active (Multiplayer zero-copy NIO buffer pool configured for " + cores + " cores)");
        } catch (Throwable t) {
            PZOLogger.warn("NettyBufferPooler notice: " + t.getMessage());
        }
    }
}
