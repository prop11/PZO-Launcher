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
        return active && !pipelineFailed;
    }

    // Kept here (not read from WorldUpscaleRenderer) so isActive() never loads game render classes early
    private static volatile boolean pipelineFailed = false;

    static void markPipelineFailed() {
        pipelineFailed = true;
    }

    /** Whether -pzo_fsr was present when the agent started; only then are the render-scale hooks installed. */
    public static boolean isRequestedAtLaunch() {
        if (!checkedParams) {
            checkLaunchParams(null);
        }
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
        checkedParams = true;
    }

    /** Scale the world should render at next frame (1.0 = native, no upscale). */
    public static float getEffectiveScale() {
        if (!WorldRenderScale.isInstalled() || !isActive() || "native".equals(upscalerMode)) {
            return 1.0f;
        }
        return renderScale;
    }

    private static int lastQueuedFrame = Integer.MIN_VALUE;

    /**
     * Redirect target for invokevirtual Core.RenderOffScreenBuffer(). Queues the FSR pass on the render
     * thread right before the world composite; only once per frame because a second upscale would read
     * the already-upscaled image as its low-resolution input.
     */
    public static void renderOffScreenBuffer(Object core) {
        try {
            if (WorldRenderScale.isInstalled()) {
                int frame = zombie.iso.IsoCamera.frameState.frameCount;
                if (frame != lastQueuedFrame) {
                    lastQueuedFrame = frame;
                    zombie.core.SpriteRenderer.instance.drawGeneric(WorldUpscaleRenderer.INSTANCE);
                }
            }
        } catch (Throwable t) {
            PZOLogger.warn("[WorldUpscalerGovernor] Could not queue FSR pass: " + t.getMessage());
        }
        ((zombie.core.Core) core).RenderOffScreenBuffer();
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

    private static volatile boolean dynamicScaleActive = false;
    private static volatile float minDynamicScale = 0.65f;
    private static volatile float maxDynamicScale = 1.00f;
    private static int consecutiveOverBudget = 0;
    private static int consecutiveUnderBudget = 0;

    public static boolean isDynamicScaleActive() {
        return dynamicScaleActive;
    }

    public static void setDynamicScaleActive(boolean enabled) {
        dynamicScaleActive = enabled;
        consecutiveOverBudget = 0;
        consecutiveUnderBudget = 0;
    }

    /**
     * Dynamically adjusts FSR render scale between 0.65f and 1.00f based on frame-time headroom
     * against the target frame interval, using deadband and hysteresis to prevent scale oscillation.
     */
    public static void updateDynamicScale(long frameStepNanos, int targetFps) {
        if (!active || !dynamicScaleActive || frameStepNanos <= 0 || targetFps <= 0) {
            return;
        }
        long targetIntervalNs = 1_000_000_000L / Math.max(30, Math.min(360, targetFps));
        long highThresholdNs = (long) (targetIntervalNs * 0.94);
        long lowThresholdNs = (long) (targetIntervalNs * 0.76);

        if (frameStepNanos > highThresholdNs) {
            consecutiveOverBudget++;
            consecutiveUnderBudget = 0;
            if (consecutiveOverBudget >= 3 && renderScale > minDynamicScale) {
                setRenderScale(Math.max(minDynamicScale, renderScale - 0.04f));
                consecutiveOverBudget = 0;
            }
        } else if (frameStepNanos < lowThresholdNs) {
            consecutiveUnderBudget++;
            consecutiveOverBudget = 0;
            if (consecutiveUnderBudget >= 15 && renderScale < maxDynamicScale) {
                setRenderScale(Math.min(maxDynamicScale, renderScale + 0.02f));
                consecutiveUnderBudget = 0;
            }
        } else {
            consecutiveOverBudget = 0;
            consecutiveUnderBudget = 0;
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
