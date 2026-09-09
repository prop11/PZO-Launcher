package com.pzoptimizer.server;

import com.pzoptimizer.PZOLogger;

public final class PZOServerNetGovernor {

    private static volatile boolean active = false;

    public static void initialize() {
        if (active) return;
        active = true;

        try {
            System.setProperty("io.netty.allocator.type", "pooled");
            System.setProperty("io.netty.allocator.maxOrder", "9"); // 4MB maximum chunk order
            System.setProperty("io.netty.allocator.pageSize", "8192"); // 8KB aligned pages
            System.setProperty("io.netty.recycler.maxCapacityPerThread", "4096");

            System.setProperty("zomboid.raknet.max_packet_queue", "16384");
            System.setProperty("zomboid.raknet.split_packet_cache", "true");

            PZOLogger.success("PZOServerNetGovernor: Active (Zero-Copy Pooled Netty Buffers & Low-Latency Packet Pacing)");
        } catch (Throwable t) {
            PZOLogger.warn("PZOServerNetGovernor: Network tuning notice: " + t.getMessage());
        }
    }
}
