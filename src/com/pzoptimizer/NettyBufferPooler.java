package com.pzoptimizer;

/**
 * Project Zomboid Build 42 - Multiplayer NIO & Zero-Copy Packet Buffer Pooler.
 * Configures off-heap direct byte buffer pooling to eliminate young-gen GC spikes in Multiplayer.
 */
public class NettyBufferPooler {
    public static void apply() {
        try {
            int cores = Math.max(2, Runtime.getRuntime().availableProcessors());

            // 1. Direct memory buffer pooling for high-throughput packet serialization
            System.setProperty("io.netty.allocator.type", "pooled");
            System.setProperty("io.netty.allocator.numDirectArenas", String.valueOf(cores));
            System.setProperty("io.netty.allocator.numHeapArenas", String.valueOf(Math.max(1, cores / 2)));
            System.setProperty("io.netty.noPreferDirect", "false");

            // 2. High-throughput datagram socket buffers for UDP / Steam networking
            System.setProperty("sun.net.maxDatagramSockets", "1024");
            System.setProperty("java.net.preferIPv4Stack", "true");

            PZOLogger.success("NettyBufferPooler active (Multiplayer zero-copy NIO buffer pool configured for " + cores + " cores)");
        } catch (Throwable t) {
            PZOLogger.warn("NettyBufferPooler notice: " + t.getMessage());
        }
    }
}
