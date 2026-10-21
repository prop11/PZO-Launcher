package com.pzoptimizer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every UI element's Lua prerender/render runs every frame even when nothing on screen changes. Input
 * (click, key, scroll, hover) marks elements dirty; clean elements can replay their previous draw commands
 * instead of running the Lua again.
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

        totalReplays.incrementAndGet();
        return true;
    }
}
