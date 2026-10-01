package com.pzoptimizer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenGL Shader Uniform State Cache.
 * In Project Zomboid Build 42, every tile sprite drawn with the tile-depth shader re-issues
 * depth uniforms hundreds of times per chunk bake and thousands of times per frame.
 * On modern threaded GPU drivers (NVIDIA / AMD), each glUniform* call produces driver thread
 * synchronization overhead.
 * PZOUniformCache memoizes active shader uniforms and skips redundant driver calls.
 */
public final class PZOUniformCache {

    private static volatile boolean active = true;
    private static final int SLOTS = 256;
    private static final int[] cachedBits = new int[SLOTS];
    private static final int[] cachedGen = new int[SLOTS];
    private static int currentGeneration = 1;
    private static int currentProgram = Integer.MIN_VALUE;

    public static final AtomicLong totalUniformsSkipped = new AtomicLong(0);
    public static final AtomicLong totalUniformsSent = new AtomicLong(0);

    private PZOUniformCache() {}

    public static void setActive(boolean value) {
        active = value;
    }

    public static boolean isActive() {
        return active;
    }

    public static void onProgramSwitch(int programId) {
        if (!active) return;
        if (currentProgram != programId) {
            currentProgram = programId;
            currentGeneration++;
        }
    }

    public static void reset() {
        if (currentProgram != Integer.MIN_VALUE) {
            currentProgram = Integer.MIN_VALUE;
            currentGeneration++;
        }
    }

    public static boolean isRedundant1i(int location, int value) {
        if (!active || location < 0) return true; // Invalid location ignored by GL
        if (location >= SLOTS) {
            totalUniformsSent.incrementAndGet();
            return false;
        }

        if (cachedGen[location] == currentGeneration && cachedBits[location] == value) {
            totalUniformsSkipped.incrementAndGet();
            return true;
        }

        cachedGen[location] = currentGeneration;
        cachedBits[location] = value;
        totalUniformsSent.incrementAndGet();
        return false;
    }

    public static boolean isRedundant1f(int location, float value) {
        return isRedundant1i(location, Float.floatToRawIntBits(value));
    }
}
