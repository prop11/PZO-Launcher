package com.pzoptimizer;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Variable Refresh Rate (VRR) & Display Synchronization Governor.
 * On G-Sync, FreeSync, and high-refresh gaming displays, allowing framerate to hit
 * or exceed the monitor refresh rate triggers driver V-Sync buffering, introducing 20-40ms
 * of sudden input lag.
 * 
 * VRRDisplayGovernor detects display refresh frequencies and automatically locks the optimal
 * sub-refresh cap (e.g., 58 on 60Hz, 141 on 144Hz, 237 on 240Hz) to keep the display
 * continuously inside its native hardware VRR window.
 */
public final class VRRDisplayGovernor {

    private static volatile boolean active = true;
    private static volatile boolean autoCapEnabled = true;

    private static final AtomicInteger detectedRefreshHz = new AtomicInteger(60);
    private static final AtomicInteger targetFrameCap = new AtomicInteger(60);
    private static boolean detectionDone = false;

    private VRRDisplayGovernor() {}

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    public static void setAutoCapEnabled(boolean value) {
        autoCapEnabled = value;
    }

    /**
     * Detects desktop display refresh frequency.
     */
    public static int detectMonitorRefreshRate() {
        if (detectionDone) return detectedRefreshHz.get();

        int hz = 60;
        try {
            Class<?> displayClass = Class.forName("org.lwjglx.opengl.Display");
            Method getModeMethod = displayClass.getMethod("getDesktopDisplayMode");
            Object mode = getModeMethod.invoke(null);
            if (mode != null) {
                Method getFreqMethod = mode.getClass().getMethod("getFrequency");
                Object freq = getFreqMethod.invoke(mode);
                if (freq instanceof Number) {
                    hz = Math.max(30, ((Number) freq).intValue());
                }
            }
        } catch (Throwable ignored) {}

        detectedRefreshHz.set(hz);
        targetFrameCap.set(calculateOptimalCap(hz));
        detectionDone = true;
        return hz;
    }

    /**
     * Calculates the sub-refresh frame cap using standard VRR framing formula:
     * floor(hz - (hz * hz / 3600.0))
     */
    public static int calculateOptimalCap(int refreshRate) {
        if (refreshRate <= 60) return 58;
        if (refreshRate <= 120) return 117;
        if (refreshRate <= 144) return 141;
        if (refreshRate <= 165) return 160;
        if (refreshRate <= 240) return 237;
        if (refreshRate <= 360) return 355;
        return Math.max(30, (int) Math.floor(refreshRate - ((double) refreshRate * refreshRate) / 3600.0));
    }

    public static int getTargetFrameCap() {
        if (!active || !autoCapEnabled) return 0;
        if (!detectionDone) detectMonitorRefreshRate();
        return targetFrameCap.get();
    }

    public static int getDetectedHz() {
        if (!detectionDone) detectMonitorRefreshRate();
        return detectedRefreshHz.get();
    }
}
