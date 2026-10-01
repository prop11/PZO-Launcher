package com.pzoptimizer;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UI Frame Pacing & Tick Staggering Governor.
 * Vanilla Project Zomboid triggers Lua update passes for all UI elements concurrently
 * every 100ms (`UIManager.doTick`). This concentrates 1-2ms of Lua scripting execution
 * into a single periodic frame, resulting in recurring micro-stutters when multiple
 * inventory, health, or crafting containers are open.
 * 
 * UIFramePacingGovernor phases and distributes each top-level UI element's 100ms update
 * window evenly across consecutive frames, smoothing out frame time deltas while maintaining
 * identical 10Hz element update rates.
 */
public final class UIFramePacingGovernor {

    private static volatile boolean active = true;
    public static final AtomicLong ticksStaggered = new AtomicLong(0);

    private static final class ElementTickState {
        long nextMs;
        long lastMs;
        long seenFrame;
    }

    private static final IdentityHashMap<Object, ElementTickState> ticks = new IdentityHashMap<>();
    private static long currentFrame = 0L;
    private static long nextCleanupMs = 0L;

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    /**
     * Called at the start of each frame before UI element traversal.
     */
    public static void onFrameBoundary(long nowMs) {
        currentFrame++;
        if (nowMs >= nextCleanupMs) {
            nextCleanupMs = nowMs + 5000L;
            Iterator<Map.Entry<Object, ElementTickState>> it = ticks.entrySet().iterator();
            while (it.hasNext()) {
                if (currentFrame - it.next().getValue().seenFrame > 600L) {
                    it.remove();
                }
            }
        }
    }

    /**
     * Evaluates whether a specific top-level UI element's 100ms tick is due.
     */
    public static boolean isElementDue(Object element, int index, int totalCount, long nowMs) {
        if (!active || element == null) return true;

        ElementTickState state = ticks.get(element);
        if (state == null) {
            state = new ElementTickState();
            long phase = totalCount <= 0 ? 0L : (index * 100L / totalCount) % 100L;
            state.nextMs = nowMs + phase;
            state.lastMs = nowMs - 100L;
            ticks.put(element, state);
        }

        state.seenFrame = currentFrame;
        if (nowMs < state.nextMs) {
            ticksStaggered.incrementAndGet();
            return false;
        }

        state.lastMs = state.nextMs;
        state.nextMs = nowMs + 100L;
        return true;
    }

    /**
     * Returns the elapsed milliseconds since the element's previous update tick.
     */
    public static long getElapsedIntervalMs(Object element, long fallbackInterval) {
        if (!active || element == null) return fallbackInterval;
        ElementTickState state = ticks.get(element);
        if (state == null || state.lastMs <= 0L) return fallbackInterval;
        long elapsed = System.currentTimeMillis() - state.lastMs;
        return elapsed > 0L ? elapsed : fallbackInterval;
    }
}
