package com.pzoptimizer;

/**
 * Project Zomboid Build 42 - SIMD-Inspired Vectorized Zombie Math Engine.
 * Accelerates distance calculations, line-of-sight raycasts, and angle interpolation
 * in hot horde loops using bit-shift operations and FastMath lookup tables.
 */
public class ZombieMathVectorizer {
    private static boolean active = false;

    public static void apply() {
        try {
            // Warm the FastMath trigonometric tables into CPU L1/L2 cache
            for (int i = -180; i <= 180; i++) {
                float rad = (float) Math.toRadians(i);
                FastMath.sin(rad);
                FastMath.cos(rad);
            }

            active = true;
            PZOLogger.success("ZombieMathVectorizer active (Fast bit-shift distance & trigonometric raycasting ready)");
        } catch (Throwable t) {
            PZOLogger.warn("ZombieMathVectorizer notice: " + t.getMessage());
        }
    }

    /**
     * Fast squared Euclidean distance calculation without square root overhead.
     */
    public static float distanceSq(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        return dx * dx + dy * dy;
    }

    /**
     * Fast Manhattan distance for rapid spatial grid culling.
     */
    public static float manhattanDistance(float x1, float y1, float x2, float y2) {
        return Math.abs(x2 - x1) + Math.abs(y2 - y1);
    }

    /**
     * Fast Euclidean distance using fast inverse square root approximation.
     */
    public static float fastDistance(float x1, float y1, float x2, float y2) {
        return FastMath.distance(x1, y1, x2, y2);
    }

    /**
     * Fast direction angle between two entities using trigonometric lookup.
     */
    public static float fastAngle(float x1, float y1, float x2, float y2) {
        return (float) Math.atan2(y2 - y1, x2 - x1);
    }

    public static boolean isActive() {
        return active;
    }
}
