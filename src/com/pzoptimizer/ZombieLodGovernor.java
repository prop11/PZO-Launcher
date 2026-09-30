package com.pzoptimizer;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dynamic Zombie LOD & Animation Blending Governor.
 * In vanilla Build 42, the engine always targets 510 3D animated models and 20 blended animations
 * even if the CPU/GPU is overwhelmed by a 1,000+ zombie horde, crashing framerates.
 * 
 * ZombieLodGovernor monitors real-time frame budget headroom (p50 and p90 frame times).
 * Under heavy load, it dynamically reduces 3D skeletal models to flat atlas sprites,
 * restoring full visual fidelity when frame headroom recovers.
 */
public final class ZombieLodGovernor {

    public static final int STOCK_3D_MODELS = 510;
    public static final int MIN_3D_MODELS = 150;
    public static final int STOCK_BLENDED_ANIMATIONS = 20;
    public static final int MIN_BLENDED_ANIMATIONS = 6;

    private static volatile boolean active = true;
    private static volatile float currentLodLevel = 1.0f; // 0.0 (min) to 1.0 (full 510)
    private static volatile int targetFps = 60;

    private static final long[] frameStepWindow = new long[128];
    private static int windowIndex = 0;
    private static int windowCount = 0;

    private static long lastDecideNanos = 0L;
    private static long climbHoldUntilNanos = 0L;
    private static final long DECIDE_INTERVAL_NANOS = 250_000_000L; // 250ms
    private static final long CLIMB_HOLD_NANOS = 2_000_000_000L;    // 2s hold after drop

    private static final AtomicInteger dynamicMax3D = new AtomicInteger(STOCK_3D_MODELS);
    private static final AtomicInteger dynamicMaxBlend = new AtomicInteger(STOCK_BLENDED_ANIMATIONS);

    private ZombieLodGovernor() {}

    public static void setActive(boolean val) {
        active = val;
    }

    public static boolean isActive() {
        return active;
    }

    public static void setTargetFps(int fps) {
        if (fps > 0) targetFps = Math.max(30, Math.min(360, fps));
    }

    public static int getTargetFps() {
        return targetFps;
    }

    public static int getMax3DModels() {
        return active ? dynamicMax3D.get() : STOCK_3D_MODELS;
    }

    public static int getMaxBlendedAnimations() {
        return active ? dynamicMaxBlend.get() : STOCK_BLENDED_ANIMATIONS;
    }

    public static float getCurrentLodLevel() {
        return currentLodLevel;
    }

    /**
     * Records the duration of a completed game/render frame step.
     */
    public static void recordFrameStep(long stepDurationNanos) {
        if (!active || stepDurationNanos <= 0) return;

        synchronized (frameStepWindow) {
            frameStepWindow[windowIndex] = stepDurationNanos;
            windowIndex = (windowIndex + 1) % frameStepWindow.length;
            if (windowCount < frameStepWindow.length) windowCount++;
        }

        long now = System.nanoTime();
        if (now - lastDecideNanos >= DECIDE_INTERVAL_NANOS) {
            lastDecideNanos = now;
            evaluateLodBudget(now);
        }
    }

    private static void evaluateLodBudget(long now) {
        if (windowCount < 16) return;

        long[] copy;
        synchronized (frameStepWindow) {
            copy = Arrays.copyOf(frameStepWindow, windowCount);
        }
        Arrays.sort(copy);

        long p50 = copy[copy.length / 2];
        long p90 = copy[(int) (copy.length * 0.90)];

        long budgetNanos = 1_000_000_000L / targetFps;

        // If median frame time misses 97% of the budget, step down
        if (p50 > (long) (budgetNanos * 0.97f)) {
            currentLodLevel = Math.max(0.0f, currentLodLevel - 0.08f);
            climbHoldUntilNanos = now + CLIMB_HOLD_NANOS;
        } else if (now >= climbHoldUntilNanos && p90 < (long) (budgetNanos * 0.85f)) {
            // If even 90th percentile fits comfortably with headroom, step up
            currentLodLevel = Math.min(1.0f, currentLodLevel + 0.03f);
        }

        int m3d = MIN_3D_MODELS + Math.round(currentLodLevel * (STOCK_3D_MODELS - MIN_3D_MODELS));
        int mBlend = MIN_BLENDED_ANIMATIONS + Math.round(currentLodLevel * (STOCK_BLENDED_ANIMATIONS - MIN_BLENDED_ANIMATIONS));
        dynamicMax3D.set(m3d);
        dynamicMaxBlend.set(mBlend);
    }
}
