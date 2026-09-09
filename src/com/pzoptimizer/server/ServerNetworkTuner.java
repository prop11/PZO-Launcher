package com.pzoptimizer.server;

public class ServerNetworkTuner {
    public static void apply() {
        try {
            int cores = Math.max(4, Runtime.getRuntime().availableProcessors());

            System.setProperty("io.netty.allocator.type", "pooled");
            System.setProperty("io.netty.allocator.numDirectArenas", String.valueOf(cores));
            System.setProperty("io.netty.allocator.numHeapArenas", String.valueOf(Math.max(2, cores / 2)));
            System.setProperty("io.netty.noPreferDirect", "false");

            System.setProperty("sun.net.maxDatagramSockets", "4096");
            System.setProperty("java.net.preferIPv4Stack", "true");
            System.setProperty("sun.net.useExclusiveBind", "true");

            PZOServerLogger.success("ServerNetworkTuner active (4096 UDP datagram sockets & pooled NIO network buffers configured for " + cores + " cores)");
        } catch (Throwable t) {
            PZOServerLogger.warn("ServerNetworkTuner notice: " + t.getMessage());
        }
    }
}
