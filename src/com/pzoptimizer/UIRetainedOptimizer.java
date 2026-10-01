package com.pzoptimizer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Retained-Mode UI Command Replay Optimizer.
 * In vanilla Build 42, every UI element's Lua prerender/render runs repeatedly at 60 Hz
 * even when the entire screen is completely static (e.g. inventory windows, hotbars, status panels).
 * 
 * UIRetainedOptimizer tracks user input and dirty events:
 * - On input (mouse click, key press, scroll, hover), marks UI elements dirty for fresh rendering.
 * - On clean frames, allows replaying previously generated draw commands, completely skipping
 *   redundant Lua script execution.
 */
public final class UIRetainedOptimizer {

    private static volatile boolean active = true;
    private static final AtomicBoolean inputDirtyThisFrame = new AtomicBoolean(false);
    private static volatile long lastInputNanos = 0L;

    public static final AtomicLong totalReplays = new AtomicLong(0);
    public static final AtomicLong totalFreshRenders = new AtomicLong(0);

    private UIRetainedOptimizer() {}

    public static void setActive(boolean value) {
        active = value;
    }

    public static boolean isActive() {
        return active;
    }

    /**
     * Call when user interaction is detected (mouse click, keystroke, drag).
     */
    public static void onInputEvent() {
        inputDirtyThisFrame.set(true);
        lastInputNanos = System.nanoTime();
    }

    public static void onFrameBoundary() {
        inputDirtyThisFrame.set(false);
    }

    /**
     * Determines whether an element can safely replay its cached draw commands.
     */
    public static boolean canReplayElement(long elementId, boolean elementInternallyDirty) {
        if (!active) return false;
        if (inputDirtyThisFrame.get() || elementInternallyDirty) {
            totalFreshRenders.incrementAndGet();
            return false;
        }

        // Element is clean and no input occurred
        totalReplays.incrementAndGet();
        return true;
    }
}
