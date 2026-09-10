package com.pzoptimizer;

import java.util.concurrent.locks.LockSupport;

/**
 * PZO Nanosecond-Precise Engine Frame Pacer & Jitter Eraser.
 * Uses hybrid OS timer sleep and CPU nanosecond spin-yielding to guarantee
 */
public final class EngineFramePacer {

    private static volatile boolean enabled = false;
    private static long targetFrameTimeNanos = 16_666_666L; // 60 FPS default

    public static void initialize() {
        PZOLogger.success("EngineFramePacer: Nanosecond Frame Timing & Jitter Eraser Ready");
    }

    public static void setTargetFps(int targetFps) {
        if (targetFps <= 0) {
            enabled = false;
            return;
        }
        enabled = true;
        targetFrameTimeNanos = 1_000_000_000L / targetFps;
    }

    public static void paceFrame(long frameStartNanos) {
        // Record frame time in telemetry diagnostic probe
        FrameDropDiagnosticEngine.onFrameTick();

        if (!enabled) return;

        long targetEnd = frameStartNanos + targetFrameTimeNanos;
        long remainingNanos = targetEnd - System.nanoTime();

        if (remainingNanos > 1_500_000L) {
            long sleepMillis = (remainingNanos - 1_000_000L) / 1_000_000L;
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException ignored) {}
        }

        remainingNanos = targetEnd - System.nanoTime();
        if (remainingNanos > 100_000L) {
            LockSupport.parkNanos(remainingNanos - 50_000L);
        }

        while (System.nanoTime() < targetEnd) {
            Thread.onSpinWait();
        }
    }
}
