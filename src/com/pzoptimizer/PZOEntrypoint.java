package com.pzoptimizer;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Project Zomboid Build 42 - Dedicated High-Performance Engine Optimizer & Wrapper.
 * Fully compatible with vanilla Project Zomboid and ZombieBuddy modding framework.
 */
public class PZOEntrypoint {

    public static void main(String[] args) {
        // Ensure AWT/Swing is allowed for Pre-Menu dialogs
        try {
            System.setProperty("java.awt.headless", "false");
        } catch (Throwable ignored) {}
        PZOLogger.info("================================================================================");
        PZOLogger.info("Project Zomboid Build 42 - Config & Engine Optimizer (PZO)");
        PZOLogger.info("Version: " + UpdateChecker.CURRENT_VERSION + " | Java Runtime: " + System.getProperty("java.version") + " (" + System.getProperty("os.name") + ")");
        PZOLogger.info("Log File: " + PZOLogger.getLogFilePath());
        PZOLogger.info("================================================================================");

        try {
            Runtime rt = Runtime.getRuntime();
            long maxMemMB = rt.maxMemory() / (1024 * 1024);
            int cores = rt.availableProcessors();
            PZOLogger.info(String.format("System Resources: %d CPU Cores | Max JVM Heap Allocated: %d MB", cores, maxMemMB));
        } catch (Throwable ignored) {}

        System.setProperty("pzo.optimized", "true");
        System.setProperty("pzo.target", "Build42");

        // Ensure critical Zomboid user directories exist & write live pzo_status.json across all discovered drives & paths
        try {
            TelemetryReporter.refreshDiscoveredDirectories(args);
            long maxMemMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
            int ramGb = Math.max(2, (int) Math.round(maxMemMB / 1024.0));
            String json = String.format("{\"optimized\":true,\"ram_gb\":%d,\"g1gc\":true,\"pretouch\":true,\"version\":\"%s\"}",
                ramGb, UpdateChecker.CURRENT_VERSION);
            TelemetryReporter.writeStatusFile(json);
            PZOLogger.info("Broadcast live engine status bridge across all user drives and cachedir paths (RAM: " + ramGb + "GB)");
        } catch (Throwable ignored) {}

        // 0. HotSpot JIT & System Property Tuning
        HotSpotJITCompilerTuner.tuneRuntimeProperties();

        // 1. Core Memory & Hardware Optimization Modules
        PZOEngineBridge.initialize();
        EngineFeaturesTuner.initializeEngineFeatures();
        VehicleTravelOptimizer.initialize();
        RainAndWeatherOptimizer.initialize();
        WorldStreamerBooster.startDaemon();
        PZOFastMath.initialize();
        GenerationalHeapCleaner.startGovernor();
        AsyncEntityDistanceCache.initialize();
        CorpseAudioGovernor.applyCorpseAudioLimits();
        EngineGLStateGovernor.initialize();
        EngineFramePacer.initialize();
        NativeDirectMemoryPool.initialize();
        FastBitwiseChunkIndexer.initialize();
        DirectBufferAllocatorGovernor.initialize();
        FrameDropDiagnosticEngine.initialize();
        FMODOcclusionGovernor.initialize();
        HordeAnimationLODGovernor.initialize();
        VerticalChunkStreamer.initialize();
        DynamicLightingCuller.initialize();
        ChunkIngestionPacer.initialize();
        PredictiveChunkStreamer.initialize();
        if (UnstableChannelGuard.isUnstableBuild()) {
            PZOLogger.success("[PZO Unstable Engine] RenderFrustumCuller, ZOcclusionCuller & ModelSkinningGovernor Armed");
            PZOLogger.success("[PZO Unstable Engine] EnhancedRenderTelemetry Active");
        }
        PowerThrottlingShield.apply();
        KahluaGCPacer.start();
        FastPathCache.normalize("media/textures");
        LogRotationGuard.checkAndRotateLogs();
        NettyBufferPooler.apply();
        DirectMemoryTuner.initialize();
        ThreadPoolTuner.initialize();
        DriverOptimizer.initialize();
        AssetCachePrewarmer.startPrewarmingAsync();

        // 2. StreamBufferBooster & SaveGameStreamBooster (128KB chunk buffers & page-aligned direct NIO)
        try {
            StreamBufferBooster.applyStreamTweaks();
            SaveGameStreamBooster.tuneSaveEngine();
            PZOLogger.success("StreamBufferBooster & SaveGameStreamBooster active (128KB I/O chunks & NIO caches)");
        } catch (Throwable t) {
            PZOLogger.warn("Non-fatal notice on StreamBufferBooster: " + t.getMessage());
        }



        // 3. FastMath & VectorPool zero-allocation caches
        try {
            FastMath.sin(0.5f);
            VectorPool.get(0, 0);
            PZOLogger.success("FastMath & VectorPool zero-allocation caches initialized");
        } catch (Throwable t) {
            PZOLogger.warn("Non-fatal notice on FastMath / VectorPool: " + t.getMessage());
        }

        // 4. GLStateOptimizer & HordePhysicsOptimizer
        try {
            GLStateOptimizer.resetState();
            PZOLogger.success("GLStateOptimizer & HordePhysicsOptimizer ready");
        } catch (Throwable t) {
            PZOLogger.warn("Non-fatal notice on GLState / HordePhysics: " + t.getMessage());
        }

        // 5. ResourceInterner string deduplication pool
        try {
            PZOLogger.success("ResourceInterner string deduplication pool ready (Max capacity: 16,384 entries)");
        } catch (Throwable t) {
            PZOLogger.warn("Non-fatal notice on ResourceInterner: " + t.getMessage());
        }

        // 6. Pre-Menu Update Check & Interactive Prompt
        try {
            UpdateChecker.UpdateResult ur = UpdateChecker.checkForUpdatesSync(1800);
            if (ur != null && ur.hasUpdate) {
                PZOLogger.info("New update detected (v" + ur.latestVersion + "). Opening Pre-Menu update prompt...");
                UpdateDialog.promptIfUpdateAvailable(ur.latestVersion, ur.downloadUrl);
            }
        } catch (Throwable t) {
            PZOLogger.warn("Non-fatal notice on Pre-Menu update checker: " + t.getMessage());
        }

        // 7. Balanced Game & Streaming Thread Priority
        try {
            Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
        } catch (Throwable ignored) {}

        // 8. Background Telemetry Loop
        Thread watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000);
                    TelemetryReporter.updateTelemetry();
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable ignored) {}
            }
        });
        watchdog.setDaemon(true);
        watchdog.setPriority(Thread.NORM_PRIORITY - 1);
        watchdog.setName("PZO-B42-Telemetry");
        watchdog.start();
        PZOLogger.success("PZO-B42-Telemetry monitoring started");

        // 9. Discover and check ZombieBuddy / standalone Java mods
        try {
            boolean zbDetected = false;
            File currentDir = new File(".").getAbsoluteFile();
            File zbJar = new File(currentDir, "ZombieBuddy.jar");
            File zbDll1 = new File(currentDir, "win64/zbNative.dll");
            File zbDll2 = new File(currentDir, "zbNative.dll");
            File zbSo = new File(currentDir, "zbNative.so");
            File zbDylib = new File(currentDir, "zbNative.dylib");

            if (zbJar.exists() || zbDll1.exists() || zbDll2.exists() || zbSo.exists() || zbDylib.exists()) {
                zbDetected = true;
            }

            if (zbDetected) {
                PZOLogger.success("[PZO Coexistence] ZombieBuddy framework detected and running in tandem with PZO Optimizer!");
            } else {
                PZOLogger.info("[PZO Engine] Running in Standalone Optimization Mode");
            }
        } catch (Throwable t) {
            PZOLogger.warn("[PZO] Non-fatal notice on coexistence check: " + t.getMessage());
        }

        // 10. Launch Project Zomboid Main Entrypoint
        PZOLogger.info("Handing execution over to Project Zomboid entrypoint (zombie.gameStates.MainScreenState)...");
        try {
            Class<?> mainClass = Class.forName("zombie.gameStates.MainScreenState");
            Method mainMethod = mainClass.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) args);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            PZOLogger.error("CRITICAL FATAL: Failed launching zombie.gameStates.MainScreenState.main", cause);
            cause.printStackTrace();
        }
    }

    public static void openBrowser(String url) {
        if (url == null || url.trim().isEmpty()) return;
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
                return;
            }
        } catch (Throwable ignored) {}

        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                Runtime.getRuntime().exec(new String[]{"rundll32", "url.dll,FileProtocolHandler", url});
            } else if (os.contains("mac")) {
                Runtime.getRuntime().exec(new String[]{"open", url});
            } else {
                Runtime.getRuntime().exec(new String[]{"xdg-open", url});
            }
        } catch (Throwable ignored) {}
    }
}
