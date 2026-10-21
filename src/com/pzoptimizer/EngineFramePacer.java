package com.pzoptimizer;

/**
 * With a capped framerate, vanilla MainThread.mainLoop spins mainThreadStep() + Thread.yield() until
 * GameWindow's accumulator reaches the next frame, burning a full core between frames. The pacer is
 * called once per frame (from EngineThreadGovernor's mainThreadLoop hook) with the next frame's due
 * time and uses the slack instead:
 * - GcSlackGovernor may tip an imminent G1 young collection into the idle window
 * - the thread sleeps until shortly before the due time, then ShortWait covers the rest
 * The last ~150us are always left to vanilla's own yield loop, so frame timing is never delayed.
 *
 * Sleeping is only used while a background calibration confirms the OS timer is precise; with the
 * default 15.6 ms Windows timer a 1 ms sleep could cost a whole frame.
 */
public final class EngineFramePacer {

    private static volatile boolean enabled = true;
    private static volatile boolean preciseSleep = false;
    private static volatile boolean calibratorStarted = false;
    private static long targetFrameTimeNanos = 16_666_666L; // 60 FPS default

    private static final long SLEEP_MARGIN_NANOS = 2_000_000L;
    private static final long SPIN_RESERVE_NANOS = 150_000L;
    private static final long MAX_PLAUSIBLE_SLACK_NANOS = 250_000_000L;

    public static void initialize() {
        startCalibrator();
        PZOLogger.success("EngineFramePacer: Nanosecond Frame Timing & Jitter Eraser Ready");
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isPreciseSleepAvailable() {
        return preciseSleep;
    }

    public static void setTargetFps(int targetFps) {
        if (targetFps <= 0) {
            enabled = false;
            return;
        }
        enabled = true;
        targetFrameTimeNanos = 1_000_000_000L / targetFps;
    }

    private static synchronized void startCalibrator() {
        if (calibratorStarted) return;
        calibratorStarted = true;
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    long worst = 0L;
                    for (int i = 0; i < 8; i++) {
                        long s = System.nanoTime();
                        Thread.sleep(1L);
                        worst = Math.max(worst, System.nanoTime() - s);
                    }
                    // Windows 11 can drop timer resolution for occluded windows, so keep re-checking
                    preciseSleep = worst <= 2_200_000L;
                    Thread.sleep(10_000L);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable ignored) {
                    preciseSleep = false;
                }
            }
        }, "PZO-FramePacerCalibrator");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /**
     * Spends the time until dueNanos (the next vanilla frameStep) without delaying it.
     */
    public static void paceUntil(long dueNanos) {
        if (!enabled) return;
        // Agent-only launches never run PZOEntrypoint.main, so initialize() may not have been called
        if (!calibratorStarted) startCalibrator();
        long remaining = dueNanos - System.nanoTime();
        if (remaining <= SPIN_RESERVE_NANOS || remaining > MAX_PLAUSIBLE_SLACK_NANOS) return;

        if (remaining > 3_500_000L) {
            GcSlackGovernor.onFrameSlack(remaining);
            remaining = dueNanos - System.nanoTime();
        }

        if (!preciseSleep) return;

        if (remaining > SLEEP_MARGIN_NANOS + 1_000_000L) {
            try {
                Thread.sleep((remaining - SLEEP_MARGIN_NANOS) / 1_000_000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
            remaining = dueNanos - System.nanoTime();
        }

        if (remaining > SPIN_RESERVE_NANOS * 2) {
            ShortWait.pause(remaining - SPIN_RESERVE_NANOS);
        }
    }

    /** Legacy entry point: paces a frame that started at frameStartNanos to the configured target. */
    public static void paceFrame(long frameStartNanos) {
        paceUntil(frameStartNanos + targetFrameTimeNanos);
    }
}
