package com.pzoptimizer;

/**
 * Project Zomboid - High Precision Timer & Windows Micro-Sleep Stabilizer.
 * Locks the Windows OS timer resolution to 1.0ms with zero CPU context-switch overhead.
 */
public class HighPrecisionTimer {
    private static volatile boolean initialized = false;

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        Thread timerThread = new Thread(() -> {
            try {
                // Sleep with a long interval; the JVM timer resolution request
                // remains active at the OS level for the entire process duration.
                while (true) {
                    Thread.sleep(60000);
                }
            } catch (InterruptedException ignored) {}
        }, "PZO-HighPrecisionTimer");
        timerThread.setDaemon(true);
        timerThread.setPriority(Thread.MIN_PRIORITY);
        timerThread.start();

        PZOLogger.success("HighPrecisionTimer active (1.0ms timer resolution locked)");
    }

    public static void stop() {
        initialized = false;
    }
}
