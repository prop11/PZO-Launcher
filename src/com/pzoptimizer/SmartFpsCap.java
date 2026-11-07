package com.pzoptimizer;

import java.util.Arrays;

/**
 * Smart FPS cap analysis: picks a frame cap from a finished performance recording.
 *
 * A cap can't fix hitches (chunk loads, GC, saves, mod scripts: one frame that takes 100+ ms), it only stops the game
 * swinging between its fast frames and its slow-but-steady ones. So hitches are split off first: any frame over 3x the
 * median, and never under 33.4 ms. Of the frames left, the 10% slowest set the rate this PC holds steadily. The cap is
 * that rate less 5% headroom, snapped down to a whole divisor of the refresh rate so every frame stays on screen for
 * the same number of refreshes (144 Hz: 144, 72, 48, 36). If the nearest divisor would throw away more than 30%, the
 * cap is the rate itself rounded down to 5, which suits variable refresh screens.
 *
 * The result goes into the recording report as a K line, which the Lua mod stores and applies.
 */
public final class SmartFpsCap {

    static final int MIN_CAP = 30;
    // About 30 s at 60 FPS; less than that says too little about how the game runs
    static final int MIN_FRAMES = 1800;

    private SmartFpsCap() {}

    /** The display's refresh rate, 0 when unknown. */
    static int refreshHz() {
        try {
            org.lwjglx.opengl.DisplayMode m = org.lwjglx.opengl.Display.getDisplayMode();
            int f = m == null ? 0 : m.getFrequency();
            return f >= 24 && f <= 1000 ? f : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * K cap, steady FPS, median FPS, hitch frames, hitch seconds, refresh Hz, game cap that held the recording (0 =
     * none), result: ok | short | slow | capped. Cap 0 means no cap is suggested.
     */
    static String line(float[] ms, int n, boolean recordedUncapped, int recordedLockFps, int refresh) {
        if (n < MIN_FRAMES) return "K\t0\t0\t0\t0\t0\t" + refresh + "\t0\tshort";
        float[] s = Arrays.copyOf(ms, n);
        Arrays.sort(s);
        double median = Math.max(0.5, s[n / 2]);
        double hitchMs = Math.max(3 * median, 33.4);
        int steadyN = n;
        while (steadyN > 0 && s[steadyN - 1] > hitchMs) steadyN--;
        int hitches = n - steadyN;
        double hitchSec = 0;
        for (int i = steadyN; i < n; i++) hitchSec += s[i] / 1000.0;
        double steadyMs = s[Math.max(0, Math.min(steadyN - 1, (int) (steadyN * 0.90)))];
        double steadyFps = 1000.0 / Math.max(0.5, steadyMs);
        double medianFps = 1000.0 / median;

        double target = steadyFps * 0.95;
        // The game's own limit held the recording: the PC may manage more, but I can only vouch for this much
        int heldBy = 0;
        if (!recordedUncapped && recordedLockFps > 0 && medianFps >= recordedLockFps * 0.97) {
            heldBy = recordedLockFps;
            target = Math.min(target, recordedLockFps);
        }

        String result = heldBy > 0 ? "capped" : "ok";
        // It held the player's own limit with steady frames: that limit is a rate it keeps, no need to snap below it
        int cap = heldBy > 0 && steadyFps >= heldBy * 0.95 ? heldBy : pick(target, refresh);
        if (cap < MIN_CAP) {
            cap = 0;
            result = "slow";
        }
        return "K\t" + cap + '\t' + fmt(steadyFps) + '\t' + fmt(medianFps) + '\t' + hitches + '\t' + fmt(hitchSec)
            + '\t' + refresh + '\t' + heldBy + '\t' + result;
    }

    static int pick(double target, int refresh) {
        if (target < MIN_CAP) return 0;
        if (refresh > 0) {
            if (target >= refresh) return refresh;
            int best = 0;
            for (int d = 1; d <= 8; d++) {
                if (refresh % d != 0) continue;
                int c = refresh / d;
                if (c <= target && c >= MIN_CAP && c > best) best = c;
            }
            if (best >= target * 0.7) return best;
        }
        return (int) (Math.floor(target / 5.0) * 5);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
