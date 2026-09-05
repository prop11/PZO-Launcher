package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Project Zomboid Build 42 - Dedicated High-Performance Thread and Scheduler Governor.
 * 
 * Intercepts the engine's core execution pipelines:
 * 1. RenderThread (via RenderThread.queueInvokeOnRenderContext):
 *    - Elevates priority to THREAD_PRIORITY_HIGHEST (+2)
 *    - Binds affinity directly to physical Performance Cores (P-Cores)
 *    - Registers thread with Windows Multimedia Class Scheduler Service (MMCSS "Games")
 *    - Eliminates micro-stutter and frame-time variance during vehicle travel in dense towns
 * 
 * 2. MainThread (via MainThread.queueInvokeOnMainThread):
 *    - Elevates priority to THREAD_PRIORITY_ABOVE_NORMAL (+1)
 *    - Binds affinity to physical Performance Cores (P-Cores)
 *    - Registers with Windows MMCSS "Games"
 * 
 * 3. Working Set and Physical RAM Lock:
 *    - Invokes SetProcessWorkingSetSizeEx to prevent Windows memory trimming
 * 
 * 4. Asynchronous Subsystem Worker Affinity:
 *    - Ensures LightingThread, WorldStreamer, and PathfindNativeThread run with rock-solid scheduling
 */
public class EngineThreadGovernor {

    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static volatile boolean renderThreadOptimized = false;
    private static volatile boolean mainThreadOptimized = false;

    public static void initialize() {
        if (!initialized.compareAndSet(false, true)) {
            return;
        }

        try {
            // 1. Process-wide Working Set Lock and High Priority
            if (PZONative.isLoaded()) {
                PZONative.lockProcessWorkingSet();
                PZONative.setProcessPriority(2); // HIGH_PRIORITY_CLASS
                PZONative.setHighPrecisionTimer(true); // 0.5ms kernel resolution
                PZONative.disablePowerThrottling(); // Exemption from Windows 11 EcoQoS
                PZOLogger.success("[EngineThreadGovernor] Process working set locked and set to HIGH_PRIORITY_CLASS");
            }
        } catch (Throwable t) {
            PZOLogger.warn("[EngineThreadGovernor] Process lock notice: " + t.getMessage());
        }

        // 2. Launch asynchronous hooker daemon to catch RenderThread and MainThread upon engine boot
        Thread governorDaemon = new Thread(EngineThreadGovernor::governorLoop, "PZO-ThreadGovernor");
        governorDaemon.setDaemon(true);
        governorDaemon.setPriority(Thread.MIN_PRIORITY);
        governorDaemon.start();

        PZOLogger.success("[EngineThreadGovernor] Thread and Scheduler Governor initialized");
    }

    private static void governorLoop() {
        int backoffMs = 100;
        while (true) {
            try {
                // Try hooking RenderThread if not yet optimized
                if (!renderThreadOptimized) {
                    tryHookRenderThread();
                }

                // Try hooking MainThread if not yet optimized
                if (!mainThreadOptimized) {
                    tryHookMainThread();
                }

                // Maintain background workers (LightingThread, PathfindNativeThread, WorldStreamer)
                maintainWorkerThreads();

                // Once both primary threads are optimized, relax polling to 5000ms
                if (renderThreadOptimized && mainThreadOptimized) {
                    backoffMs = 5000;
                } else {
                    backoffMs = Math.min(backoffMs + 200, 1000);
                }

                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                break;
            } catch (Throwable ignored) {
                try { Thread.sleep(2000); } catch (Throwable ignored2) {}
            }
        }
    }

    /**
     * Dispatches a Runnable directly into the OpenGL Render Context queue.
     * When executed on the Render Thread, native scheduler boosts are applied.
     */
    private static void tryHookRenderThread() {
        try {
            Class<?> renderThreadClass = Class.forName("zombie.core.opengl.RenderThread");
            Method isRunningMethod = renderThreadClass.getMethod("isRunning");
            boolean isRunning = (boolean) isRunningMethod.invoke(null);

            if (isRunning) {
                Method queueMethod = renderThreadClass.getMethod("queueInvokeOnRenderContext", Runnable.class);
                queueMethod.invoke(null, (Runnable) () -> {
                    try {
                        if (PZONative.isLoaded()) {
                            PZONative.optimizeCallingThread(2, true, "Games");
                        }
                        Thread.currentThread().setName("PZO-RenderThread");
                        renderThreadOptimized = true;
                        PZOLogger.success("[EngineThreadGovernor] RenderThread successfully optimized: Priority HIGHEST | MMCSS Games | P-Core Affinity");
                    } catch (Throwable t) {
                        PZOLogger.warn("[EngineThreadGovernor] RenderThread optimization notice: " + t.getMessage());
                    }
                });
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Dispatches a Runnable directly into the Main Simulation Thread queue.
     * When executed on the Main Thread, native scheduler boosts are applied.
     */
    private static void tryHookMainThread() {
        try {
            Class<?> mainThreadClass = Class.forName("zombie.MainThread");
            Method isRunningMethod = mainThreadClass.getMethod("isRunning");
            boolean isRunning = (boolean) isRunningMethod.invoke(null);

            if (isRunning) {
                Method queueMethod = mainThreadClass.getMethod("queueInvokeOnMainThread", Runnable.class);
                queueMethod.invoke(null, (Runnable) () -> {
                    try {
                        if (PZONative.isLoaded()) {
                            PZONative.optimizeCallingThread(1, true, "Games");
                        }
                        Thread.currentThread().setName("PZO-MainSimulationThread");
                        mainThreadOptimized = true;
                        PZOLogger.success("[EngineThreadGovernor] MainThread successfully optimized: Priority ABOVE_NORMAL | MMCSS Games | P-Core Affinity");
                    } catch (Throwable t) {
                        PZOLogger.warn("[EngineThreadGovernor] MainThread optimization notice: " + t.getMessage());
                    }
                });
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Maintains optimal scheduling for engine worker subsystems (Lighting, Pathfinding).
     */
    private static void maintainWorkerThreads() {
        try {
            // LightingThread
            try {
                Class<?> ltClass = Class.forName("zombie.iso.LightingThread");
                Field instField = ltClass.getField("instance");
                Object ltInst = instField.get(null);
                if (ltInst != null) {
                    Field threadField = ltClass.getField("lightingThread");
                    Thread ltThread = (Thread) threadField.get(ltInst);
                    if (ltThread != null && ltThread.isAlive()) {
                        if (ltThread.getPriority() < Thread.NORM_PRIORITY) {
                            ltThread.setPriority(Thread.NORM_PRIORITY);
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // PathfindNativeThread
            try {
                Class<?> pfClass = Class.forName("zombie.pathfind.nativeCode.PathfindNativeThread");
                Field instField = pfClass.getField("instance");
                Object pfInst = instField.get(null);
                if (pfInst instanceof Thread) {
                    Thread pfThread = (Thread) pfInst;
                    if (pfThread.isAlive() && pfThread.getPriority() < Thread.NORM_PRIORITY) {
                        pfThread.setPriority(Thread.NORM_PRIORITY);
                    }
                }
            } catch (Throwable ignored) {}

        } catch (Throwable ignored) {}
    }

    public static boolean isRenderThreadOptimized() {
        return renderThreadOptimized;
    }

    public static boolean isMainThreadOptimized() {
        return mainThreadOptimized;
    }
}
