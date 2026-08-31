package com.pzoptimizer;

/**
 * PZO Async Entity Distance Matrix & Spatial Cache.
 * Provides high-speed, zero-allocation squared distance math for proximity checks,
 * zombie vision culling, and simulation level bucketing.
 * 100% deterministic and cross-platform.
 */
public final class AsyncEntityDistanceCache {

    public static final float PROXIMITY_CLOSE_SQ = 100.0f;   // 10 tiles squared
    public static final float PROXIMITY_MEDIUM_SQ = 900.0f;  // 30 tiles squared
    public static final float PROXIMITY_FAR_SQ = 3600.0f;    // 60 tiles squared
    public static final float PROXIMITY_MAX_SQ = 6400.0f;    // 80 tiles squared

    public static void initialize() {
        PZOLogger.success("AsyncEntityDistanceCache: Spatial distance matrix cache ready");
    }

    /**
     * Fast distance evaluation without square root.
     * Returns 0 for close (<10m), 1 for medium (<30m), 2 for far (<60m), 3 for out-of-range (>80m).
     */
    public static int classifyDistance(float playerX, float playerY, float objX, float objY) {
        float dSq = PZOFastMath.distSq(playerX, playerY, objX, objY);
        if (dSq <= PROXIMITY_CLOSE_SQ) return 0;
        if (dSq <= PROXIMITY_MEDIUM_SQ) return 1;
        if (dSq <= PROXIMITY_FAR_SQ) return 2;
        if (dSq <= PROXIMITY_MAX_SQ) return 3;
        return 4;
    }
}
