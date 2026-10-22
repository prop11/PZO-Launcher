package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Raises process, render-thread and main-thread scheduling priority, and wraps MainThread.mainThreadLoop
 * so the per-frame governors and the frame pacer run after every step.
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
            if (PZONative.isLoaded()) {
                PZONative.setHighPrecisionTimer(true); // 0.5ms kernel resolution
                PZONative.disablePowerThrottling(); // Exemption from Windows 11 EcoQoS
                if (PZONative.isLowCoreCpu()) {
                    PZONative.setProcessPriority(0);
                    PZOLogger.info("[EngineThreadGovernor] Low core count CPU: process left at NORMAL_PRIORITY_CLASS, working set not locked");
                } else {
                    PZONative.lockProcessWorkingSet();
                    PZONative.setProcessPriority(2); // HIGH_PRIORITY_CLASS
                    PZOLogger.success("[EngineThreadGovernor] Process working set locked and set to HIGH_PRIORITY_CLASS");
                }
            }
        } catch (Throwable t) {
            PZOLogger.warn("[EngineThreadGovernor] Process lock notice: " + t.getMessage());
        }

        try {
            GCLauncherOptimizer.tuneLauncherGcIfBeneficial();
        } catch (Throwable ignored) {}

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
                if (!renderThreadOptimized) {
                    tryHookRenderThread();
                }

                if (!mainThreadOptimized || !mainThreadPacerHooked) {
                    tryHookMainThread();
                }

                maintainWorkerThreads();

                if (renderThreadOptimized && mainThreadOptimized && mainThreadPacerHooked) {
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

    /** Applies the native scheduler boost from inside the render thread, via its invoke queue. */
    private static void tryHookRenderThread() {
        try {
            Class<?> renderThreadClass = Class.forName("zombie.core.opengl.RenderThread");
            Method isRunningMethod = renderThreadClass.getMethod("isRunning");
            boolean isRunning = (boolean) isRunningMethod.invoke(null);

            if (isRunning) {
                renderThreadOptimized = true;
                Method queueMethod = renderThreadClass.getMethod("queueInvokeOnRenderContext", Runnable.class);
                queueMethod.invoke(null, (Runnable) () -> {
                    try {
                        if (PZONative.isLoaded()) {
                            try {
                                boolean lowCore = PZONative.isLowCoreCpu();
                                PZONative.optimizeCallingThread(2, true, lowCore ? "" : "Games");
                                PZOLogger.success("[EngineThreadGovernor] RenderThread successfully optimized: Priority HIGHEST | " + (lowCore ? "MMCSS skipped (low core count)" : "MMCSS Games") + " | P-Core Affinity");
                            } catch (Throwable t) {
                                PZOLogger.warn("[EngineThreadGovernor] RenderThread native optimization notice: " + t.getMessage());
                            }
                        }
                        Thread.currentThread().setName("PZO-RenderThread");
                    } catch (Throwable t) {
                        PZOLogger.warn("[EngineThreadGovernor] RenderThread hook notice: " + t.getMessage());
                    }
                });
            }
        } catch (Throwable ignored) {}
    }

    private static volatile boolean mainThreadPacerHooked = false;

    /** Same for the main thread, and installs the mainThreadLoop wrapper. */
    private static void tryHookMainThread() {
        try {
            Class<?> mainThreadClass = Class.forName("zombie.MainThread");
            Method isRunningMethod = mainThreadClass.getMethod("isRunning");
            boolean isRunning = (boolean) isRunningMethod.invoke(null);

            if (isRunning && !mainThreadOptimized) {
                mainThreadOptimized = true;
                Method queueMethod = mainThreadClass.getMethod("queueInvokeOnMainThread", Runnable.class);
                queueMethod.invoke(null, (Runnable) () -> {
                    try {
                        if (PZONative.isLoaded()) {
                            try {
                                boolean lowCore = PZONative.isLowCoreCpu();
                                PZONative.optimizeCallingThread(1, true, lowCore ? "" : "Games");
                                PZOLogger.success("[EngineThreadGovernor] MainThread successfully optimized: Priority ABOVE_NORMAL | " + (lowCore ? "MMCSS skipped (low core count)" : "MMCSS Games") + " | P-Core Affinity");
                            } catch (Throwable t) {
                                PZOLogger.warn("[EngineThreadGovernor] MainThread native optimization notice: " + t.getMessage());
                            }
                        }
                        Thread.currentThread().setName("PZO-MainSimulationThread");
                    } catch (Throwable t) {
                        PZOLogger.warn("[EngineThreadGovernor] MainThread hook notice: " + t.getMessage());
                    }
                });
            }

            // Wrap MainThread.mainThreadLoop so the per-frame governors and the frame pacer run after each step
            if (!mainThreadPacerHooked) {
                try {
                    Field loopField = mainThreadClass.getDeclaredField("mainThreadLoop");
                    loopField.setAccessible(true);
                    Runnable origLoop = (Runnable) loopField.get(null);
                    if (origLoop != null && !(origLoop instanceof MainThreadDiagnosticHook)) {
                        loopField.set(null, new MainThreadDiagnosticHook(origLoop));
                        mainThreadPacerHooked = true;
                        PZOLogger.success("[EngineThreadGovernor] MainThread.mainThreadLoop hooked with Zero-Lock Diagnostic Probe (Uncapped Native Clock)");
                    }
                } catch (Throwable t) {
                    // Non-fatal if field not yet assigned
                }
            }
        } catch (Throwable ignored) {}
    }

    public static class MainThreadDiagnosticHook implements Runnable {
        private final Runnable target;
        private int lastFrameCount = -1;
        private static Field fsField = null;
        private static Field fcField = null;
        private static boolean reflectionInit = false;

        public MainThreadDiagnosticHook(Runnable target) {
            this.target = target;
        }

        private static java.lang.invoke.VarHandle gwCurrentTime = null;
        private static java.lang.invoke.VarHandle gwAccumulator = null;
        private static boolean pacerReflectionFailed = false;

        /**
         * Mirrors GameWindow.mainThreadStep(): with a capped framerate the next frameStep runs once
         * accumulator + (now - currentTime) reaches 1e9 / lockFPS.
         */
        private static void paceToNextFrame() {
            if (pacerReflectionFailed || zombie.core.PerformanceSettings.instance.isFramerateUncapped()) return;
            try {
                if (gwCurrentTime == null) {
                    java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.privateLookupIn(
                        zombie.GameWindow.class, java.lang.invoke.MethodHandles.lookup());
                    gwCurrentTime = lookup.findStaticVarHandle(zombie.GameWindow.class, "currentTime", long.class);
                    gwAccumulator = lookup.findStaticVarHandle(zombie.GameWindow.class, "accumulator", long.class);
                }
                int lockFps = zombie.core.PerformanceSettings.getLockFPS();
                if (lockFps <= 0) return;
                long desiredDt = 1_000_000_000L / lockFps;
                long currentTime = (long) gwCurrentTime.get();
                long accumulator = (long) gwAccumulator.get();
                EngineFramePacer.paceUntil(currentTime + (desiredDt - accumulator));
            } catch (Throwable t) {
                pacerReflectionFailed = true;
                PZOLogger.warn("[EngineThreadGovernor] Frame pacer disabled (GameWindow timing unavailable): " + t.getMessage());
            }
        }

        private static void ensureReflection() {
            if (reflectionInit) return;
            try {
                Class<?> camClass = Class.forName("zombie.iso.IsoCamera");
                fsField = camClass.getField("frameState");
                Object sampleFs = fsField.get(null);
                if (sampleFs != null) {
                    Class<?> fsClass = sampleFs.getClass();
                    fcField = fsClass.getField("frameCount");
                }
            } catch (Throwable ignored) {}
            reflectionInit = true;
        }

        @Override
        public void run() {
            try {
                target.run();
            } finally {
                if (BenchmarkHarness.isActive()) {
                    BenchmarkHarness.tick();
                }
                try {
                    ensureReflection();
                    if (fsField != null) {
                        Object fs = fsField.get(null);
                        if (fs != null) {
                            if (fcField == null) {
                                Class<?> fsClass = fs.getClass();
                                fcField = fsClass.getField("frameCount");
                            }
                            int fc = fcField.getInt(fs);
                            if (fc != lastFrameCount) {
                                lastFrameCount = fc;
                                ChunkIngestionPacer.onFrameBoundary(fc);
                                FrameDropDiagnosticEngine.onFrameTick();
                                PopmanGovernor.onFrameBoundary(fc);
                                VRRDisplayGovernor.detectMonitorRefreshRate();
                                paceToNextFrame();
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    /** Keeps LightingThread and PathfindNativeThread at normal priority or above. */
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
