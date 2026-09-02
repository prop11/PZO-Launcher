package com.pzoptimizer.server;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Project Zomboid Build 42 & 41 - Dedicated Server Master Optimization Entrypoint.
 * Bootstraps zero-lag networking, multi-threaded horde simulation, and 24/7 memory stability
 * before handing execution over to zombie.network.GameServer.
 */
public class PZOServerEntrypoint {
    public static final String SERVER_VERSION = "0.8.3.1-unstable";

    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();

        // 1. Enforce headless execution for dedicated servers
        try {
            System.setProperty("java.awt.headless", "true");
        } catch (Throwable ignored) {}

        PZOServerLogger.info("================================================================================");
        PZOServerLogger.info("Project Zomboid Dedicated Server Optimizer (PZO Server Suite)");
        PZOServerLogger.info("Version: " + SERVER_VERSION + " | Java Runtime: " + System.getProperty("java.version") + " (" + System.getProperty("os.name") + ")");
        PZOServerLogger.info("================================================================================");

        int cores = Runtime.getRuntime().availableProcessors();
        long maxHeapMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        PZOServerLogger.info("Host Server Resources: " + cores + " CPU Cores | Max JVM Heap: " + maxHeapMB + " MB");

        // 1.2. Automatic Linux Dedicated Server Steam Native Sanitizer
        LinuxSteamServerSanitizer.sanitize();

        // 1.5. HotSpot JIT & Engine Architecture Tuner
        com.pzoptimizer.HotSpotJITCompilerTuner.tuneRuntimeProperties();
        com.pzoptimizer.EngineFeaturesTuner.initializeEngineFeatures();
        com.pzoptimizer.WorldStreamerBooster.startDaemon();
        com.pzoptimizer.PZOFastMath.initialize();
        com.pzoptimizer.GenerationalHeapCleaner.startGovernor();
        com.pzoptimizer.AsyncEntityDistanceCache.initialize();
        com.pzoptimizer.CorpseAudioGovernor.applyCorpseAudioLimits();
        com.pzoptimizer.EngineGLStateGovernor.initialize();
        com.pzoptimizer.EngineFramePacer.initialize();
        com.pzoptimizer.NativeDirectMemoryPool.initialize();
        com.pzoptimizer.FastBitwiseChunkIndexer.initialize();

        // 2. Initialize Server Network Buffer Pooler
        com.pzoptimizer.PZOEngineBridge.initialize();
        ServerNetworkTuner.apply();
        PZOServerNetGovernor.initialize();

        // 3. Initialize Multi-Threaded Zombie Simulation
        ServerHordeSimEngine.apply();

        // 4. Initialize Zero-Lag World Save & Chunk Stream Booster
        ServerChunkStreamBooster.apply();

        // 5. Check & Support ZombieBuddy Server Coexistence
        checkZombieBuddyServer();

        // 6. Launch Server Telemetry Daemon
        ServerTelemetryBridge.startTelemetryDaemon();

        long initDuration = System.currentTimeMillis() - startTime;
        PZOServerLogger.success("All server optimization pipelines armed in " + initDuration + "ms!");
        PZOServerLogger.info("Handing execution over to Project Zomboid Dedicated Server (zombie.network.GameServer)...");
        PZOServerLogger.info("================================================================================");

        // 7. Invoke Vanilla GameServer Entrypoint
        try {
            Class<?> targetClass = Class.forName("zombie.network.GameServer");
            Method mainMethod = targetClass.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) args);
        } catch (ClassNotFoundException e) {
            PZOServerLogger.error("CRITICAL: Failed to locate zombie.network.GameServer! Ensure projectzomboid.jar is present on classpath.", e);
            System.exit(1);
        } catch (Throwable t) {
            PZOServerLogger.error("Server execution encountered an unhandled exception: " + t.getMessage(), t);
        }
    }

    private static void checkZombieBuddyServer() {
        try {
            File currentDir = new File(".").getAbsoluteFile();
            File zbJar = new File(currentDir, "ZombieBuddy.jar");
            if (zbJar.exists()) {
                PZOServerLogger.success("ZombieBuddy server library detected (" + zbJar.getName() + ") - Coexistence mode active");
            }
        } catch (Throwable ignored) {}
    }
}
