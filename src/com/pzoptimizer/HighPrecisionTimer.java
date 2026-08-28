package com.pzoptimizer;

/**
 * Project Zomboid - High Precision Timer & Windows Micro-Sleep Stabilizer.
 * Forces the Windows kernel timer resolution from default 15.6ms (64 Hz)
 * down to 1.0ms (1000 Hz) to eliminate frame pacing jitter and micro-stutters.
 */
public class HighPrecisionTimer {
    private static volatile boolean active = false;

    public static void initialize() {
        if (active) return;
        active = true;

        Thread timerThread = new Thread(() -> {
            while (active) {
                try {
                    // Holding a 1ms sleep loop signals the Windows OS kernel to maintain
                    // high-resolution 1ms scheduling across all game threads.
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "PZO-HighPrecisionTimer");

        timerThread.setDaemon(true);
        timerThread.setPriority(Thread.MIN_PRIORITY);
        timerThread.start();
    }

    public static void stop() {
        active = false;
    }
}
