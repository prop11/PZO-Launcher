package com.pzoptimizer;

/**
 * PZO Dynamic Lighting & Frustum Shadow Culler.
 * Dynamic light cones and viewport shadow passes are handled natively in the render pipeline.
 */
public final class DynamicLightingCuller {

    private static volatile boolean active = false;

    public static void initialize() {
        if (active) return;
        active = true;
        PZOLogger.info("DynamicLightingCuller: Active (Native pipeline culling)");
    }

    public static void shutdown() {
        active = false;
    }
}
