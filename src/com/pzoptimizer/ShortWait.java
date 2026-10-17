package com.pzoptimizer;

import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

/**
 * OS-Aware Sub-Millisecond Worker Join Wait Utility.
 * On Linux and macOS, LockSupport.parkNanos(10_000) sleeps for tens of microseconds as requested.
 * On Windows, HotSpot's kernel park rounds any timeout below 1 ms up to a full millisecond (or the
 * current Windows timer quantum), turning a 10 us barrier wait into a ~1 ms GameThread stall.
 *
 * ShortWait uses Thread.onSpinWait() + Thread.yield() (SwitchToThread) on Windows for sub-millisecond
 * worker barrier joins, while retaining parkNanos on Linux/macOS.
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

    /**
     * Pauses the current thread briefly while waiting on a per-frame worker barrier.
     */
    public static void pause(long nanos) {
        if (USE_YIELD_ON_SHORT_WAIT && nanos < 1_000_000L) {
            Thread.onSpinWait();
            Thread.yield();
        } else {
            LockSupport.parkNanos(nanos);
        }
    }
}
