package com.pzoptimizer.server;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Project Zomboid Build 42 & 41 - Dedicated Server Master Optimization Entrypoint.
 * Bootstraps zero-lag networking, multi-threaded horde simulation, and 24/7 memory stability
 * before handing execution over to zombie.network.GameServer.
 */
public class PZOServerEntrypoint {
    public static final String SERVER_VERSION = "0.9.7";

    private static volatile boolean initialized = false;

    public static void premain(String agentArgs, java.lang.instrument.Instrumentation inst) {
        initServerPipelines(inst);
    }

    public static void agentmain(String agentArgs, java.lang.instrument.Instrumentation inst) {
        premain(agentArgs, inst);
    }

    public static synchronized void initServerPipelines(java.lang.instrument.Instrumentation inst) {
        if (initialized) return;
        initialized = true;

        long startTime = System.currentTimeMillis();

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

        if (inst != null) {
            try {
                com.pzoptimizer.PZOptimAgent.premain(null, inst);
            } catch (Throwable t) {
                PZOServerLogger.warn("Notice during server agent transformer registration: " + t.getMessage());
            }
        }

        LinuxSteamServerSanitizer.sanitize();

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

        com.pzoptimizer.PZOEngineBridge.initialize();
        ServerNetworkTuner.apply();
        PZOServerNetGovernor.initialize();

        ServerHordeSimEngine.apply();

        ServerChunkStreamBooster.apply();

        checkZombieBuddyServer();

        ServerTelemetryBridge.startTelemetryDaemon();

        long initDuration = System.currentTimeMillis() - startTime;
        PZOServerLogger.success("All server optimization pipelines armed in " + initDuration + "ms!");
        PZOServerLogger.info("================================================================================");
    }

    public static void main(String[] args) {
        initServerPipelines(null);
        PZOServerLogger.info("Handing execution over to Project Zomboid Dedicated Server (zombie.network.GameServer)...");

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
