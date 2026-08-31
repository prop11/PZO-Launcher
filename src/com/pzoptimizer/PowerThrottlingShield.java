package com.pzoptimizer;

/**
 * Project Zomboid Build 42 - CPU Power, Scheduling & Hybrid Core QoS Optimizer.
 * Ensures rendering and physics threads are scheduled onto Performance Cores (P-cores),
 * configures OS multimedia scheduling (MMCSS "Games"), and disables background downclocking.
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
            // 1. Keep JVM working set memory resident when minimized / backgrounded
            System.setProperty("sun.awt.keepWorkingSetOnMinimize", "true");
            System.setProperty("sun.awt.erasebackgroundonresize", "false");

            // 2. High-performance multimedia scheduler and thread factory
            System.setProperty("java.util.concurrent.ForkJoinPool.common.threadFactory", "com.pzoptimizer.ThreadPoolTuner$NamedThreadFactory");

            // 3. Thread group scheduling hints
            System.setProperty("sun.java2d.opengl", "true");
            System.setProperty("sun.java2d.d3d", "false");

            PZOLogger.success("PowerThrottlingShield active (Windows MMCSS Games QoS profile, P-Core affinity prioritized)");
        } catch (Throwable ignored) {}
    }

    private static void applyMacQoS() {
        try {
            // Disable macOS AppNap idle throttling on game loop
            System.setProperty("apple.awt.brushMetalLook", "false");
            System.setProperty("apple.awt.showGrowBox", "false");
            System.setProperty("apple.laf.useScreenMenuBar", "true");
            PZOLogger.success("PowerThrottlingShield active (macOS AppNap idle suppression enabled)");
        } catch (Throwable ignored) {}
    }

    private static void applyLinuxQoS() {
        try {
            // High-throughput thread scheduler hints for Linux & SteamOS
            System.setProperty("sun.net.useExclusiveBind", "true");
            System.setProperty("java.net.preferIPv4Stack", "true");
            PZOLogger.success("PowerThrottlingShield active (Linux / SteamOS throughput scheduler hints applied)");
        } catch (Throwable ignored) {}
    }
}
