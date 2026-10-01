package com.pzoptimizer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * World Resolution Scaling & FSR 1.0 Upscaler Governor.
 * For players on integrated APUs (Steam Deck, ROG Ally, Intel Iris Xe, Radeon 780M/890M)
 * or 4K displays, GPU fillrate and shading pixel cost create a strict framerate ceiling.
 * 
 * WorldUpscalerGovernor manages rendering the game world viewport at a reduced render scale
 * (e.g., 50% to 75% resolution) while preserving full native resolution for the user interface,
 * resolving the world pass using AMD FidelityFX Super Resolution 1.0 (EASU + RCAS sharpening)
 * to deliver massive FPS increases with sharp visual clarity.
 */
public final class WorldUpscalerGovernor {

    private static volatile boolean active = false; // Disabled by default unless -pzo_fsr launch param is present
    private static volatile boolean checkedParams = false;
    private static volatile float renderScale = 0.75f; // 75% render scale default
    private static volatile float sharpness = 0.80f; // RCAS sharpness
    private static volatile String upscalerMode = "fsr1"; // "fsr1", "bicubic", "native"

    public static final AtomicLong framesUpscaled = new AtomicLong(0);

    private WorldUpscalerGovernor() {}

    /**
     * Inspects command-line arguments and system properties for -pzo_fsr launch parameter.
     */
    public static synchronized void checkLaunchParams(String[] args) {
        if (checkedParams && (args == null || args.length == 0)) return;
        checkedParams = true;

        boolean found = false;

        // 1. Inspect command-line arguments (passed into PZOEntrypoint.main)
        if (args != null) {
            for (String arg : args) {
                if (arg != null) {
                    String clean = arg.trim();
                    if ("-pzo_fsr".equalsIgnoreCase(clean) || "--pzo_fsr".equalsIgnoreCase(clean)) {
                        found = true;
                        break;
                    }
                }
            }
        }

        // 2. Inspect full command line string from JVM
        if (!found) {
            try {
                String sunCmd = System.getProperty("sun.java.command");
                if (sunCmd != null && (sunCmd.contains("-pzo_fsr") || sunCmd.contains("--pzo_fsr"))) {
                    found = true;
                }
            } catch (Throwable ignored) {}
        }

        // 3. Inspect system properties and environment variables
        if (!found) {
            try {
                String sysProp = System.getProperty("pzo_fsr");
                if (sysProp == null) sysProp = System.getProperty("pzo.fsr");
                if (sysProp == null) sysProp = System.getProperty("-pzo_fsr");
                if (sysProp != null && ("true".equalsIgnoreCase(sysProp) || "1".equals(sysProp) || sysProp.isEmpty())) {
                    found = true;
                }
            } catch (Throwable ignored) {}
        }
        if (!found) {
            try {
                String envVal = System.getenv("PZO_FSR");
                if (envVal != null && ("true".equalsIgnoreCase(envVal) || "1".equals(envVal))) {
                    found = true;
                }
            } catch (Throwable ignored) {}
        }

        if (found) {
            active = true;
            PZOLogger.success("[WorldUpscalerGovernor] AMD FSR 1.0 Upscaler activated via -pzo_fsr launch parameter (Render Scale: " + (int)(renderScale * 100) + "%, Sharpness: " + (int)(sharpness * 100) + "%)");
        } else {
            active = false;
            PZOLogger.info("[WorldUpscalerGovernor] FSR 1.0 Upscaler inactive (-pzo_fsr launch parameter not detected). Native resolution preserved.");
        }
    }

    public static boolean isActive() {
        if (!checkedParams) {
            checkLaunchParams(null);
        }
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
        checkedParams = true;
    }

    public static float getRenderScale() {
        return renderScale;
    }

    public static void setRenderScale(float scale) {
        renderScale = Math.max(0.25f, Math.min(1.0f, scale));
    }

    public static float getSharpness() {
        return sharpness;
    }

    public static void setSharpness(float sharp) {
        sharpness = Math.max(0.0f, Math.min(1.0f, sharp));
    }

    public static String getUpscalerMode() {
        return upscalerMode;
    }

    public static void setUpscalerMode(String mode) {
        if (mode != null) {
            upscalerMode = mode.toLowerCase();
        }
    }

    /**
     * Calculates scaled viewport dimensions based on native display size.
     * Output array: [scaledWidth, scaledHeight].
     */
    public static int[] getScaledViewport(int nativeWidth, int nativeHeight) {
        if (!active || renderScale >= 0.999f) {
            return new int[] {nativeWidth, nativeHeight};
        }
        int w = Math.max(320, (int) (nativeWidth * renderScale));
        int h = Math.max(240, (int) (nativeHeight * renderScale));
        // Align to even numbers for texture alignment
        w = (w + 1) & ~1;
        h = (h + 1) & ~1;
        framesUpscaled.incrementAndGet();
        return new int[] {w, h};
    }
}
