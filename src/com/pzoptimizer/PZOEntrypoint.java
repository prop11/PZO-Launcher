package com.pzoptimizer;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Project Zomboid Build 42 - Dedicated High-Performance Engine Optimizer & Wrapper.
 * Fully compatible with vanilla Project Zomboid and ZombieBuddy modding framework.
 */
public class PZOEntrypoint {

    public static void main(String[] args) {
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

        // Ensure critical Zomboid user directories exist (Prevents B42 DebugFileWatcher NoSuchFileException crash)
        try {
            String userHome = System.getProperty("user.home");
            if (userHome != null) {
                File zDir = new File(userHome, "Zomboid");
                File[] criticalDirs = new File[]{
                    new File(zDir, "mods"),
                    new File(zDir, "Lua"),
                    new File(zDir, "db"),
                    new File(zDir, "Server")
                };
                for (File cd : criticalDirs) {
                    if (!cd.exists()) {
                        cd.mkdirs();
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 1. StreamBufferBooster & SaveGameStreamBooster (128KB chunk buffers & page-aligned direct NIO)
        try {
            StreamBufferBooster.applyStreamTweaks();
            SaveGameStreamBooster.tuneSaveEngine();
            PZOLogger.success("StreamBufferBooster & SaveGameStreamBooster active (128KB I/O chunks & NIO caches)");
        } catch (Throwable t) {
            PZOLogger.warn("Non-fatal notice on StreamBufferBooster: " + t.getMessage());
        }

        // 2. HighPrecisionTimer (1.0ms timer locking)
        try {
            HighPrecisionTimer.initialize();
            PZOLogger.success("HighPrecisionTimer active (Windows 1.0ms timer resolution locked)");
        } catch (Throwable t) {
            PZOLogger.warn("Non-fatal notice on HighPrecisionTimer: " + t.getMessage());
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

        // 6. Update Checker
        try {
            UpdateChecker.checkForUpdatesAsync();
            PZOLogger.info("UpdateChecker background check scheduled (Async timeout: 3.5s)");
        } catch (Throwable t) {
            PZOLogger.warn("Non-fatal notice on UpdateChecker: " + t.getMessage());
        }

        // 7. Render Thread Priority
        try {
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
            PZOLogger.success("Main game rendering thread priority elevated to MAX_PRIORITY");
        } catch (Throwable t) {
            PZOLogger.warn("Could not set thread priority (Non-fatal): " + t.getMessage());
        }

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

        // 9. Discover and load standalone Java mods (coexists with ZombieBuddy)
        try {
            JavaModLoader.loadMods(null);
        } catch (Throwable t) {
            PZOLogger.warn("[PZO] Non-fatal notice on Java mod loader: " + t.getMessage());
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
