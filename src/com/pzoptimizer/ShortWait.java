package com.pzoptimizer;

import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

/**
 * On Linux and macOS, LockSupport.parkNanos(10_000) sleeps for tens of microseconds as requested. On
 * Windows, HotSpot rounds any park below 1 ms up to a full millisecond (or the current timer quantum),
 * turning a 10 us barrier wait into a ~1 ms game-thread stall, so Windows uses Thread.onSpinWait() +
 * Thread.yield() (SwitchToThread) instead.
 */
public final class ShortWait {

    private static final boolean USE_YIELD_ON_SHORT_WAIT;

    static {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String override = System.getProperty("pzo.shortWait", "").toLowerCase(Locale.ROOT);
        if ("yield".equals(override)) {
            USE_YIELD_ON_SHORT_WAIT = true;
        } else if ("park".equals(override)) {
            USE_YIELD_ON_SHORT_WAIT = false;
        } else {
            USE_YIELD_ON_SHORT_WAIT = os.contains("win");
        }
    }

    private ShortWait() {}

    public static boolean isYieldMode() {
        return USE_YIELD_ON_SHORT_WAIT;
    }

    public static void pause(long nanos) {
        if (USE_YIELD_ON_SHORT_WAIT && nanos < 1_000_000L) {
            Thread.onSpinWait();
            Thread.yield();
        } else {
            LockSupport.parkNanos(nanos);
        }
    }
}
