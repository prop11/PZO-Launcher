package com.pzoptimizer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Opt-in world render scaling (the JAVA tab's FSR switch or -pzo_fsr): the world renders below native resolution
 * (77%, FSR's Ultra Quality, by default; JAVA tab or -pzo_fsr_quality / -pzo_fsr_scale / -pzo_fsr_sharpness) and is
 * upscaled with FSR 1.0 (EASU + RCAS) while the UI stays native. Aimed at fill-rate-limited setups such as
 * APUs (Steam Deck, ROG Ally, Iris Xe, Radeon 780M/890M) and 4K displays.
 */
public final class WorldUpscalerGovernor {

    private static volatile boolean active = false; // Hooks installed: -pzo_fsr at launch or FSR switched on in the JAVA tab
    private static volatile boolean userEnabled = true; // JAVA tab switch while the hooks are in
    private static volatile boolean checkedParams = false;
    private static volatile float userScale = 0.77f; // FSR 1.0 Ultra Quality
    private static volatile float renderScale = userScale;
    private static volatile float sharpness = 0.80f; // RCAS sharpness
    private static volatile String upscalerMode = "fsr1"; // "fsr1", "bicubic", "native"

    public static final AtomicLong framesUpscaled = new AtomicLong(0);

    private WorldUpscalerGovernor() {}

    /** AMD's FSR 1.0 quality modes as render scales; null for an unknown name. */
    static Float presetScale(String name) {
        switch (name.toLowerCase()) {
            case "ultra": case "ultraquality": case "ultra_quality": return 0.77f;
            case "quality": return 0.67f;
            case "balanced": return 0.59f;
            case "performance": return 0.50f;
            default: return null;
        }
    }

    // -pzo_fsr_quality=ultra|quality|balanced|performance, -pzo_fsr_scale=50..100, -pzo_fsr_sharpness=0..100
    private static boolean applyOptionArg(String arg) {
        String a = arg.trim().toLowerCase();
        if (a.startsWith("--")) a = a.substring(1);
        int eq = a.indexOf('=');
        if (!a.startsWith("-pzo_fsr_") || eq < 0) return false;
        String key = a.substring(0, eq), val = a.substring(eq + 1).replace("%", "");
        try {
            if ("-pzo_fsr_quality".equals(key)) {
                Float s = presetScale(val);
                if (s != null) setUserScale(s);
            } else if ("-pzo_fsr_scale".equals(key)) {
                setUserScale(Integer.parseInt(val) / 100.0f);
            } else if ("-pzo_fsr_sharpness".equals(key)) {
                setSharpness(Integer.parseInt(val) / 100.0f);
            }
        } catch (NumberFormatException ignored) {
        }
        return true;
    }

    /** Looks for -pzo_fsr in the launch arguments, the JVM command line, system properties and environment. */
    public static synchronized void checkLaunchParams(String[] args) {
        if (checkedParams && (args == null || args.length == 0)) return;
        checkedParams = true;

        boolean found = false;

        // Arguments passed to PZOEntrypoint.main
        if (args != null) {
            for (String arg : args) {
                if (arg != null) {
                    String clean = arg.trim();
                    if ("-pzo_fsr".equalsIgnoreCase(clean) || "--pzo_fsr".equalsIgnoreCase(clean)) {
                        found = true;
                    } else if (applyOptionArg(clean)) {
                        found = true;
                    }
                }
            }
        }


        // Full JVM command line
        if (!found) {
            try {
                String sunCmd = System.getProperty("sun.java.command");
                if (sunCmd != null && (sunCmd.contains("-pzo_fsr") || sunCmd.contains("--pzo_fsr"))) {
                    found = true;
                    for (String tok : sunCmd.split("\\s+")) applyOptionArg(tok);
                }
            } catch (Throwable ignored) {}
        }

        // System properties and environment variables
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
        boolean fromTab = false;
        if (!found) {
            try {
                found = fromTab = PZOConfig.isFsrEnabled();
            } catch (Throwable ignored) {}
        }

        if (found) {
            active = true;
            PZOLogger.success("[WorldUpscalerGovernor] AMD FSR 1.0 Upscaler activated via " + (fromTab ? "the JAVA tab" : "-pzo_fsr launch parameter")
                + " (Render Scale: " + Math.round(userScale * 100) + "%, Sharpness: " + Math.round(sharpness * 100) + "%)");
            try {
                PZOptimAgent.enableRenderScaleHooks();
            } catch (Throwable t) {
                PZOLogger.warn("[WorldUpscalerGovernor] Could not install render-scale hooks: " + t.getMessage());
            }
        } else {
            active = false;
            // The agent runs before main() receives the game arguments, so only main's check is conclusive
            if (args != null) {
                PZOLogger.info("[WorldUpscalerGovernor] FSR 1.0 Upscaler inactive (-pzo_fsr launch parameter not detected). Native resolution preserved.");
            }
        }
    }

    public static boolean isActive() {
        if (!checkedParams) {
            checkLaunchParams(null);
        }
        return active && userEnabled && !pipelineFailed;
    }

    /**
     * The JAVA tab's FSR switch. Saved for the next launch, where it installs the render hooks; while they are in
     * already, it also switches FSR on or off right away.
     */
    public static void setUserEnabled(boolean enabled) {
        try {
            PZOConfig.setFsrEnabled(enabled);
        } catch (Throwable ignored) {}
        userEnabled = enabled;
        if (enabled && !WorldRenderScale.isInstalled()) {
            PZOLogger.info("[WorldUpscalerGovernor] FSR switched on in the JAVA tab: takes effect on the next launch");
        }
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

    /** The scale the player chose (JAVA tab or -pzo_fsr_quality / -pzo_fsr_scale); dynamic mode never goes above it. */
    public static void setUserScale(float scale) {
        userScale = Math.max(0.50f, Math.min(1.0f, scale));
        renderScale = userScale;
        consecutiveOverBudget = 0;
        consecutiveUnderBudget = 0;
    }

    public static float getUserScale() {
        return userScale;
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
    private static final float MIN_DYNAMIC_SCALE = 0.65f;
    private static int consecutiveOverBudget = 0;
    private static int consecutiveUnderBudget = 0;

    public static boolean isDynamicScaleActive() {
        return dynamicScaleActive;
    }

    public static void setDynamicScaleActive(boolean enabled) {
        dynamicScaleActive = enabled;
        consecutiveOverBudget = 0;
        consecutiveUnderBudget = 0;
        if (!enabled) renderScale = userScale;
    }

    /**
     * Dynamically adjusts the FSR render scale between 65% (or the chosen scale, if lower) and the chosen scale based
     * on frame-time headroom against the target frame interval, using deadband and hysteresis against oscillation.
     */
    public static void updateDynamicScale(long frameStepNanos, int targetFps) {
        if (!active || !dynamicScaleActive || frameStepNanos <= 0 || targetFps <= 0) {
            return;
        }
        long targetIntervalNs = 1_000_000_000L / Math.max(30, Math.min(360, targetFps));
        long highThresholdNs = (long) (targetIntervalNs * 0.94);
        long lowThresholdNs = (long) (targetIntervalNs * 0.76);
        float max = userScale, min = Math.min(MIN_DYNAMIC_SCALE, max);

        if (frameStepNanos > highThresholdNs) {
            consecutiveOverBudget++;
            consecutiveUnderBudget = 0;
            if (consecutiveOverBudget >= 3 && renderScale > min) {
                setRenderScale(Math.max(min, renderScale - 0.04f));
                consecutiveOverBudget = 0;
            }
        } else if (frameStepNanos < lowThresholdNs) {
            consecutiveUnderBudget++;
            consecutiveOverBudget = 0;
            if (consecutiveUnderBudget >= 15 && renderScale < max) {
                setRenderScale(Math.min(max, renderScale + 0.02f));
                consecutiveUnderBudget = 0;
            }
        } else {
            consecutiveOverBudget = 0;
            consecutiveUnderBudget = 0;
        }
    }

    /** Scaled viewport size for the native display size, as [width, height]. */
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
