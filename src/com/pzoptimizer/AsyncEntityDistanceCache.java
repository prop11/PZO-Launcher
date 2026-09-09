package com.pzoptimizer;

public final class AsyncEntityDistanceCache {

    public static final float PROXIMITY_CLOSE_SQ = 100.0f;   // 10 tiles squared
    public static final float PROXIMITY_MEDIUM_SQ = 900.0f;  // 30 tiles squared
    public static final float PROXIMITY_FAR_SQ = 3600.0f;    // 60 tiles squared
    public static final float PROXIMITY_MAX_SQ = 6400.0f;    // 80 tiles squared

    public static void initialize() {
        boolean avx2 = PZONative.isLoaded() && PZONative.isAVX2Supported();
        PZOLogger.success(String.format(
            "AsyncEntityDistanceCache: Spatial distance matrix cache ready (SIMD AVX2: %s)",
            avx2 ? "ENABLED" : "PURE_JVM"
        ));
    }

    public static boolean isAVX2Active() {
        return PZONative.isLoaded() && PZONative.isAVX2Supported();
    }

    public static float getDistance(int entityIndex) {
        return HordeSpatialCuller.getDistance(entityIndex);
    }

    public static int getLODTier(int entityIndex) {
        return HordeSpatialCuller.getLODTier(entityIndex);
    }

    public static boolean isOffscreen(int entityIndex) {
        return HordeSpatialCuller.isOffscreen(entityIndex);
    }

    public static int getTrackedEntitiesCount() {
        return HordeSpatialCuller.getZombieCount();
    }

    /** Returns tiers 0..4 for squared distances <=100, <=900, <=3600, <=6400, and beyond. */
    public static int classifyDistance(float playerX, float playerY, float objX, float objY) {
        float dSq = PZOFastMath.distSq(playerX, playerY, objX, objY);
        if (dSq <= PROXIMITY_CLOSE_SQ) return 0;
        if (dSq <= PROXIMITY_MEDIUM_SQ) return 1;
        if (dSq <= PROXIMITY_FAR_SQ) return 2;
        if (dSq <= PROXIMITY_MAX_SQ) return 3;
        return 4;
    }
}
