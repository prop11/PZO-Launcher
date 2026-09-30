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

    private static volatile boolean active = false; // Disabled by default until enabled in options
    private static volatile float renderScale = 0.75f; // 75% render scale default
    private static volatile float sharpness = 0.80f; // RCAS sharpness
    private static volatile String upscalerMode = "fsr1"; // "fsr1", "bicubic", "native"

    public static final AtomicLong framesUpscaled = new AtomicLong(0);

    private WorldUpscalerGovernor() {}

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
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
