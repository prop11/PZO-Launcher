package com.pzoptimizer;

import java.io.File;
import java.nio.ByteBuffer;
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

            // 4. Boost process priority to Above Normal / High and lock working set to physical RAM
            boolean prio = setProcessPriority(1); // 1 = Above Normal
            boolean wsLock = lockProcessWorkingSet();

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
                "[PZONative] Kernel QoS Active: Timer: %.2fms (Status: %s) | PowerThrottling Disabled: %s | MMCSS: %s | RAM WorkingSet Locked: %s | AVX2: %s",
                timerMs, timerLock ? "LOCKED" : "FALLBACK",
                powerShield ? "SUCCESS" : "N/A",
                mmcss ? "ACTIVE" : "N/A",
                wsLock ? "YES" : "N/A",
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

    public static int calculateDistancesSqAVX2(FloatBuffer directCoords, int count, float originX, float originY, FloatBuffer directOutDistSq) {
        if (!isLoaded() || !directCoords.isDirect() || !directOutDistSq.isDirect()) {
            return fallbackDistancesSq(directCoords, count, originX, originY, directOutDistSq);
        }
        try {
            return batchCalculateDistancesSqAVX2(directCoords, count, originX, originY, directOutDistSq);
        } catch (Throwable t) {
            return fallbackDistancesSq(directCoords, count, originX, originY, directOutDistSq);
        }
    }

    public static int cullRadialAVX2(FloatBuffer directCoords, int count, float originX, float originY, float maxRadiusSq, ByteBuffer directOutMask) {
        if (!isLoaded() || !directCoords.isDirect() || !directOutMask.isDirect()) {
            return fallbackCullRadial(directCoords, count, originX, originY, maxRadiusSq, directOutMask);
        }
        try {
            return batchCullRadialAVX2(directCoords, count, originX, originY, maxRadiusSq, directOutMask);
        } catch (Throwable t) {
            return fallbackCullRadial(directCoords, count, originX, originY, maxRadiusSq, directOutMask);
        }
    }

    public static int cullAABBAVX2(FloatBuffer directCoords, int count, float minX, float minY, float maxX, float maxY, ByteBuffer directOutMask) {
        if (!isLoaded() || !directCoords.isDirect() || !directOutMask.isDirect()) {
            return fallbackCullAABB(directCoords, count, minX, minY, maxX, maxY, directOutMask);
        }
        try {
            return batchCullAABBAVX2(directCoords, count, minX, minY, maxX, maxY, directOutMask);
        } catch (Throwable t) {
            return fallbackCullAABB(directCoords, count, minX, minY, maxX, maxY, directOutMask);
        }
    }

    public static int classifyTiersAVX2(FloatBuffer directCoords, int count, float originX, float originY,
                                        float t0Sq, float t1Sq, float t2Sq, ByteBuffer directOutTiers) {
        if (!isLoaded() || !directCoords.isDirect() || !directOutTiers.isDirect()) {
            return fallbackClassifyTiers(directCoords, count, originX, originY, t0Sq, t1Sq, t2Sq, directOutTiers);
        }
        try {
            return batchClassifyTiersAVX2(directCoords, count, originX, originY, t0Sq, t1Sq, t2Sq, directOutTiers);
        } catch (Throwable t) {
            return fallbackClassifyTiers(directCoords, count, originX, originY, t0Sq, t1Sq, t2Sq, directOutTiers);
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

    private static int fallbackDistancesSq(FloatBuffer coords, int count, float ox, float oy, FloatBuffer out) {
        coords.rewind();
        out.rewind();
        for (int i = 0; i < count; i++) {
            float x = coords.get(i * 2);
            float y = coords.get(i * 2 + 1);
            float dx = x - ox;
            float dy = y - oy;
            out.put(i, dx * dx + dy * dy);
        }
        return count;
    }

    private static int fallbackCullRadial(FloatBuffer coords, int count, float ox, float oy, float maxRadSq, ByteBuffer outMask) {
        coords.rewind();
        outMask.rewind();
        int inside = 0;
        for (int i = 0; i < count; i++) {
            float dx = coords.get(i * 2) - ox;
            float dy = coords.get(i * 2 + 1) - oy;
            byte in = (byte) ((dx * dx + dy * dy <= maxRadSq) ? 1 : 0);
            outMask.put(i, in);
            if (in == 1) inside++;
        }
        return inside;
    }

    private static int fallbackCullAABB(FloatBuffer coords, int count, float minX, float minY, float maxX, float maxY, ByteBuffer outMask) {
        coords.rewind();
        outMask.rewind();
        int inside = 0;
        for (int i = 0; i < count; i++) {
            float x = coords.get(i * 2);
            float y = coords.get(i * 2 + 1);
            byte in = (byte) ((x >= minX && x <= maxX && y >= minY && y <= maxY) ? 1 : 0);
            outMask.put(i, in);
            if (in == 1) inside++;
        }
        return inside;
    }

    private static int fallbackClassifyTiers(FloatBuffer coords, int count, float ox, float oy,
                                             float t0Sq, float t1Sq, float t2Sq, ByteBuffer outTiers) {
        coords.rewind();
        outTiers.rewind();
        for (int i = 0; i < count; i++) {
            float dx = coords.get(i * 2) - ox;
            float dy = coords.get(i * 2 + 1) - oy;
            float dSq = dx * dx + dy * dy;
            if (dSq <= t0Sq) outTiers.put(i, (byte) 0);
            else if (dSq <= t1Sq) outTiers.put(i, (byte) 1);
            else if (dSq <= t2Sq) outTiers.put(i, (byte) 2);
            else outTiers.put(i, (byte) 3);
        }
        return count;
    }

    // ========================================================================
    // Phase 2: High-Speed SIMD Decompression & Win32 Chunk Stream Acceleration
    // ========================================================================

    public static int decompress(byte[] src, int srcOff, int srcLen, byte[] dst, int dstOff, int dstCap) {
        if (!isLoaded() || src == null || dst == null || srcLen <= 0 || dstCap <= 0) return -1;
        try {
            return decompressBytes(src, srcOff, srcLen, dst, dstOff, dstCap);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static int decompress(ByteBuffer src, int srcPos, int srcLen, ByteBuffer dst, int dstPos, int dstCap) {
        if (!isLoaded() || src == null || dst == null || !src.isDirect() || !dst.isDirect() || srcLen <= 0 || dstCap <= 0) return -1;
        try {
            return decompressDirect(src, srcPos, srcLen, dst, dstPos, dstCap);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static int readChunkFile(String path, byte[] dst, int maxCap) {
        if (!isLoaded() || path == null || dst == null || maxCap <= 0) return -1;
        try {
            return readChunkFileNative(path, dst, maxCap);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static boolean prewarmFile(String path) {
        if (!isLoaded() || path == null) return false;
        try {
            return prewarmFileNative(path);
        } catch (Throwable t) {
            return false;
        }
    }

    public static int prewarmFiles(String[] paths) {
        if (!isLoaded() || paths == null || paths.length == 0) return 0;
        try {
            return prewarmFilesNative(paths);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static boolean isSolidStateDrive(String path) {
        if (!isLoaded() || path == null) return true;
        try {
            return isDriveSSDNative(path);
        } catch (Throwable t) {
            return true;
        }
    }

    // ========================================================================
    // Native JNI Declarations (Implemented in pzo_native.c)
    // ========================================================================
    private static native boolean initNative();
    public static native boolean setHighPrecisionTimer(boolean enable);
    public static native boolean disablePowerThrottling();
    public static native boolean setMMCSSProfile(String profileName);
    public static native boolean setProcessPriority(int priorityLevel);
    public static native boolean lockProcessWorkingSet();
    public static native boolean optimizeCallingThread(int priorityLevel, boolean bindPCores, String profileStr);
    public static native long getPerformanceCoreMask();
    public static native boolean bindThreadToPerformanceCores();
    public static native int getPhysicalCores();
    public static native int getPerformanceCores();
    public static native int getLogicalProcessors();
    public static native int getTimerResolution100ns();
    public static native boolean isAVX2Supported();
    private static native int batchCalculateDistancesAVX2(
        FloatBuffer directCoords, int count, float originX, float originY, FloatBuffer directOutDist
    );
    private static native int batchCalculateDistancesSqAVX2(
        FloatBuffer directCoords, int count, float originX, float originY, FloatBuffer directOutDistSq
    );
    private static native int batchCullRadialAVX2(
        FloatBuffer directCoords, int count, float originX, float originY, float maxRadiusSq, ByteBuffer directOutMask
    );
    private static native int batchCullAABBAVX2(
        FloatBuffer directCoords, int count, float minX, float minY, float maxX, float maxY, ByteBuffer directOutMask
    );
    private static native int batchClassifyTiersAVX2(
        FloatBuffer directCoords, int count, float originX, float originY,
        float t0Sq, float t1Sq, float t2Sq, ByteBuffer directOutTiers
    );
    private static native int decompressBytes(byte[] src, int srcOff, int srcLen, byte[] dst, int dstOff, int dstCap);
    private static native int decompressDirect(ByteBuffer src, int srcPos, int srcLen, ByteBuffer dst, int dstPos, int dstCap);
    private static native int readChunkFileNative(String filePath, byte[] dstArray, int maxCap);
    private static native boolean prewarmFileNative(String filePath);
    private static native int prewarmFilesNative(String[] filePaths);
    private static native boolean isDriveSSDNative(String path);
}
