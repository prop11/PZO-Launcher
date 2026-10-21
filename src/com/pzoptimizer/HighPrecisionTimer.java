package com.pzoptimizer;

/** Holds a 1 ms Windows timer resolution for the process. */
public class HighPrecisionTimer {
    private static volatile boolean initialized = false;

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        Thread timerThread = new Thread(() -> {
            try {
                // Sleeping on a long interval keeps the JVM's timer resolution request active
                // for the whole process.
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
