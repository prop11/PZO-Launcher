package com.pzoptimizer;

import java.io.File;
import java.nio.FloatBuffer;

/**
 * Project Zomboid Build 42 - Dedicated High-Performance Native Kernel & Hardware Governor.
 * Bridges Java to pzo_native64.dll for low-latency OS scheduling, 0.5ms timer locking,
 * Windows 11 EcoQoS Power Throttling exemption, and AVX2 spatial SIMD batching.
 */
public class PZONative {

    private static volatile boolean loaded = false;
    private static volatile boolean initialized = false;

    static {
        loadNativeLibrary();
    }

    private static synchronized void loadNativeLibrary() {
        if (loaded) return;

        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            PZOLogger.info("[PZONative] Operating system is not Windows (" + os + "). Native Windows governor skipped.");
            return;
        }

        // 1. Try standard Java library path
        try {
            System.loadLibrary("pzo_native64");
            loaded = true;
            PZOLogger.success("[PZONative] Loaded pzo_native64.dll via System.loadLibrary");
        } catch (Throwable t1) {
            // 2. Try direct paths relative to working directory or game root
            String[] candidatePaths = new String[] {
                "pzo_native64.dll",
                "win64/pzo_native64.dll",
                System.getProperty("user.dir") + File.separator + "pzo_native64.dll",
                "K:/SteamLibrary/steamapps/common/ProjectZomboid/pzo_native64.dll"
            };

            for (String p : candidatePaths) {
                try {
                    File f = new File(p);
                    if (f.exists() && f.isFile()) {
                        System.load(f.getAbsolutePath());
                        loaded = true;
                        PZOLogger.success("[PZONative] Loaded pzo_native64.dll from: " + f.getAbsolutePath());
                        break;
                    }
                } catch (Throwable ignored) {}
            }
        }

        if (loaded) {
            try {
                if (initNative()) {
                    initialized = true;
                    applyKernelGovernors();
                }
            } catch (Throwable t) {
                PZOLogger.warn("[PZONative] initNative failed: " + t.getMessage());
            }
        } else {
            PZOLogger.info("[PZONative] Native companion library pzo_native64.dll not found; operating in pure JVM mode.");
        }
    }

    private static void applyKernelGovernors() {
        if (!initialized) return;

        try {
            // 1. Completely exempt Project Zomboid from Windows 11 EcoQoS Power Throttling
            boolean powerShield = disablePowerThrottling();

            // 2. Lock OS Interrupt Timer to 0.5ms high-precision
            boolean timerLock = setHighPrecisionTimer(true);

            // 3. Register Multimedia Class Scheduler (MMCSS) Games profile
            boolean mmcss = setMMCSSProfile("Games");

            // 4. Boost process priority to Above Normal to protect against background hitching
            boolean prio = setProcessPriority(1); // 1 = Above Normal

            // 5. Query hardware topology
            int physCores = getPhysicalCores();
            int pCores = getPerformanceCores();
            int logProc = getLogicalProcessors();
            long pCoreMask = getPerformanceCoreMask();
            int timerRes100ns = getTimerResolution100ns();
            boolean avx2 = isAVX2Supported();

            double timerMs = timerRes100ns / 10000.0;

            PZOLogger.success(String.format(
                "[PZONative] Hardware Governor Active: CPU [%d Physical | %d P-Cores | %d Threads] Mask: 0x%X",
                physCores, pCores, logProc, pCoreMask
            ));
            PZOLogger.success(String.format(
                "[PZONative] Kernel QoS Active: Timer: %.2fms (Status: %s) | PowerThrottling Disabled: %s | MMCSS: %s | Priority: %s | AVX2: %s",
                timerMs, timerLock ? "LOCKED" : "FALLBACK",
                powerShield ? "SUCCESS" : "N/A",
                mmcss ? "ACTIVE" : "N/A",
                prio ? "ABOVE_NORMAL" : "NORMAL",
                avx2 ? "ENABLED" : "DISABLED"
            ));
        } catch (Throwable t) {
            PZOLogger.warn("[PZONative] applyKernelGovernors notice: " + t.getMessage());
        }
    }

    public static boolean isLoaded() {
        return loaded && initialized;
    }

    public static boolean bindCallingThreadToPCores() {
        if (!isLoaded()) return false;
        try {
            return bindThreadToPerformanceCores();
        } catch (Throwable t) {
            return false;
        }
    }

    public static int calculateDistancesAVX2(FloatBuffer directCoords, int count, float originX, float originY, FloatBuffer directOutDist) {
        if (!isLoaded() || !directCoords.isDirect() || !directOutDist.isDirect()) {
            return fallbackDistances(directCoords, count, originX, originY, directOutDist);
        }
        try {
            return batchCalculateDistancesAVX2(directCoords, count, originX, originY, directOutDist);
        } catch (Throwable t) {
            return fallbackDistances(directCoords, count, originX, originY, directOutDist);
        }
    }

    private static int fallbackDistances(FloatBuffer coords, int count, float ox, float oy, FloatBuffer out) {
        coords.rewind();
        out.rewind();
        for (int i = 0; i < count; i++) {
            float x = coords.get(i * 2);
            float y = coords.get(i * 2 + 1);
            float dx = x - ox;
            float dy = y - oy;
            out.put(i, (float) Math.sqrt(dx * dx + dy * dy));
        }
        return count;
    }

    // ========================================================================
    // Native JNI Declarations (Implemented in pzo_native.c)
    // ========================================================================
    private static native boolean initNative();
    public static native boolean setHighPrecisionTimer(boolean enable);
    public static native boolean disablePowerThrottling();
    public static native boolean setMMCSSProfile(String profileName);
    public static native boolean setProcessPriority(int priorityLevel);
    public static native long getPerformanceCoreMask();
    private static native boolean bindThreadToPerformanceCores();
    public static native int getPhysicalCores();
    public static native int getPerformanceCores();
    public static native int getLogicalProcessors();
    public static native int getTimerResolution100ns();
    public static native boolean isAVX2Supported();
    private static native int batchCalculateDistancesAVX2(
        FloatBuffer directCoords, int count, float originX, float originY, FloatBuffer directOutDist
    );
}
