package com.pzoptimizer;

/** No-op placeholder; dynamic lighting culling is left to the vanilla pipeline. */
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
