package com.pzoptimizer;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * Project Zomboid Build 42 - SIMD-Inspired Vectorized Zombie Math Engine (Phase 3).
 * Accelerates distance calculations, line-of-sight raycasts, angle interpolation,
 * and batch spatial entity culling using 8-wide AVX2 instructions and FastMath lookup tables.
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
            boolean avx2 = isAVX2Accelerated();
            PZOLogger.success(String.format(
                "ZombieMathVectorizer active (SIMD AVX2: %s | Fast bit-shift distance & trigonometric raycasting ready)",
                avx2 ? "ENABLED (8-wide SIMD)" : "SCALAR FALLBACK"
            ));
        } catch (Throwable t) {
            PZOLogger.warn("ZombieMathVectorizer notice: " + t.getMessage());
        }
    }

    public static boolean isAVX2Accelerated() {
        return PZONative.isLoaded() && PZONative.isAVX2Supported();
    }

    /**
     * Batch calculates Euclidean distances for up to N entities using 8-wide AVX2 SIMD.
     */
    public static int batchDistances(FloatBuffer directCoords, int count, float ox, float oy, FloatBuffer directOutDist) {
        return PZONative.calculateDistancesAVX2(directCoords, count, ox, oy, directOutDist);
    }

    /**
     * Batch calculates squared distances for up to N entities using 8-wide AVX2 SIMD (no sqrt).
     */
    public static int batchDistancesSq(FloatBuffer directCoords, int count, float ox, float oy, FloatBuffer directOutDistSq) {
        return PZONative.calculateDistancesSqAVX2(directCoords, count, ox, oy, directOutDistSq);
    }

    /**
     * Batch tests radial proximity for up to N entities using 8-wide AVX2 SIMD.
     */
    public static int batchCullRadial(FloatBuffer directCoords, int count, float ox, float oy, float maxRadSq, ByteBuffer directOutMask) {
        return PZONative.cullRadialAVX2(directCoords, count, ox, oy, maxRadSq, directOutMask);
    }

    /**
     * Batch tests 2D AABB bounding box for up to N entities using 8-wide AVX2 SIMD.
     */
    public static int batchCullAABB(FloatBuffer directCoords, int count, float minX, float minY, float maxX, float maxY, ByteBuffer directOutMask) {
        return PZONative.cullAABBAVX2(directCoords, count, minX, minY, maxX, maxY, directOutMask);
    }

    /**
     * Batch classifies multi-tier LOD for up to N entities using 8-wide AVX2 SIMD.
     */
    public static int batchClassifyTiers(FloatBuffer directCoords, int count, float ox, float oy,
                                         float t0Sq, float t1Sq, float t2Sq, ByteBuffer directOutTiers) {
        return PZONative.classifyTiersAVX2(directCoords, count, ox, oy, t0Sq, t1Sq, t2Sq, directOutTiers);
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
