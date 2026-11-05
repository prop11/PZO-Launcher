package com.pzoptimizer;

/**
 * Where the time before the game window goes, in pzo_engine.log. "JVM start -> premain" is the JVM itself (heap
 * pre-touch) and any agent loaded before mine, like ZombieBuddy's; after that each of my own steps that took 50 ms or
 * more gets a line, and the total is logged when the game's main() takes over.
 */
public final class StartupTimer {

    private static final long SLOW_MS = 50;
    private static long last = System.nanoTime();
    private static boolean begun;

    private StartupTimer() {}

    private static long sinceJvmStartMs() {
        try {
            long start = java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
            return System.currentTimeMillis() - start;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** First thing in premain (or main without the agent). */
    public static synchronized void begin(String where) {
        if (begun) return;
        begun = true;
        last = System.nanoTime();
        PZOLogger.info("[PZO Startup] " + where + " began " + sinceJvmStartMs() + " ms after the JVM started");
    }

    /** After a step: logs it when it was slow. */
    public static synchronized void step(String name) {
        long now = System.nanoTime();
        long ms = (now - last) / 1_000_000L;
        last = now;
        if (ms >= SLOW_MS) PZOLogger.info("[PZO Startup] " + name + ": " + ms + " ms");
    }

    /** Right before the game's own main() runs. */
    public static synchronized void handOver() {
        step("last steps");
        PZOLogger.info("[PZO Startup] Handing over to the game " + sinceJvmStartMs() + " ms after the JVM started");
    }
}
