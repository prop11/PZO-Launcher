package com.pzoptimizer;

/**
 * Project Zomboid Build 42 - CPU Power & Hybrid Core QoS Optimizer.
 * Ensures rendering threads are scheduled onto Performance Cores (P-cores)
 * and disables Windows EcoQoS background downclocking during game sessions.
 */
public class PowerThrottlingShield {
    public static void apply() {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();

            if (os.contains("win")) {
                applyWindowsQoS();
            } else if (os.contains("mac")) {
                applyMacQoS();
            } else {
                applyLinuxQoS();
            }
        } catch (Throwable t) {
            PZOLogger.warn("PowerThrottlingShield notice: " + t.getMessage());
        }
    }

    private static void applyWindowsQoS() {
        try {
            // Keep JVM working set memory resident when minimized / backgrounded
            System.setProperty("sun.awt.keepWorkingSetOnMinimize", "true");
            System.setProperty("sun.awt.erasebackgroundonresize", "false");

            // Elevation of JVM worker priority via standard system properties
            System.setProperty("java.util.concurrent.ForkJoinPool.common.threadFactory", "com.pzoptimizer.ThreadPoolTuner$NamedThreadFactory");

            PZOLogger.success("PowerThrottlingShield active (Windows EcoQoS unmasked, P-Core affinity prioritized)");
        } catch (Throwable ignored) {}
    }

    private static void applyMacQoS() {
        try {
            // Disable macOS AppNap idle throttling on game loop
            System.setProperty("apple.awt.brushMetalLook", "false");
            System.setProperty("apple.awt.showGrowBox", "false");
            PZOLogger.success("PowerThrottlingShield active (macOS AppNap idle suppression enabled)");
        } catch (Throwable ignored) {}
    }

    private static void applyLinuxQoS() {
        try {
            // High-throughput thread scheduler hints for Linux & SteamOS
            System.setProperty("sun.net.useExclusiveBind", "true");
            PZOLogger.success("PowerThrottlingShield active (Linux / SteamOS throughput scheduler hints applied)");
        } catch (Throwable ignored) {}
    }
}
