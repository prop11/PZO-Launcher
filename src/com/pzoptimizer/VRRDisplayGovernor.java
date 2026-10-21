package com.pzoptimizer;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * On G-Sync / FreeSync displays, reaching the refresh rate pushes the driver into V-Sync buffering and adds
 * 20-40 ms of input lag. This reads the display refresh rate and caps just below it (58 at 60 Hz, 141 at
 * 144 Hz, 237 at 240 Hz) to stay inside the VRR window.
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
