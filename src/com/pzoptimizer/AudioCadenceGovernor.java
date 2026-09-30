package com.pzoptimizer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Audio Parameter Upkeep & Cadence Governor.
 * FMOD Studio operates asynchronously with an internal update interval of 20ms (50 Hz).
 * On high-refresh displays running at 120-240 FPS, vanilla PZ evaluates character,
 * emitter, and occlusion parameters every render frame, performing 2-5 redundant recalculations
 * for every audio mixer frame.
 * 
 * AudioCadenceGovernor rates parameter refresh passes to a steady 60 Hz cadence while
 * ensuring new sound triggers and stop events execute with zero latency.
 */
public final class AudioCadenceGovernor {

    private static volatile boolean active = true;
    private static volatile int targetHz = 60;
    public static final AtomicLong parameterPassesDampened = new AtomicLong(0);

    private static int lastFrame = Integer.MIN_VALUE;
    private static boolean tickDue = true;
    private static long lastCadenceNanos = 0L;

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    public static void setTargetHz(int hz) {
        targetHz = Math.max(30, Math.min(120, hz));
    }

    /**
     * Determines if a recurring audio parameter evaluation pass is due this frame.
     */
    public static boolean isCadenceTickDue(int frameCount) {
        if (!active || targetHz <= 0) return true;

        if (frameCount != lastFrame) {
            lastFrame = frameCount;
            long now = System.nanoTime();
            long stepNanos = 1_000_000_000L / targetHz;

            if (now - lastCadenceNanos >= stepNanos - (stepNanos / 8)) {
                tickDue = true;
                lastCadenceNanos = (now - lastCadenceNanos > 2 * stepNanos) ? now : (lastCadenceNanos + stepNanos);
            } else {
                tickDue = false;
                parameterPassesDampened.incrementAndGet();
            }
        }
        return tickDue;
    }
}
