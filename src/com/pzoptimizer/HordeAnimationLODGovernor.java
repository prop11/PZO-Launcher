package com.pzoptimizer;

import java.util.concurrent.atomic.AtomicLong;

/** Placeholder: B42 already handles skeletal blending and bone matrices itself; only the counters are used. */
public final class HordeAnimationLODGovernor {

    private static volatile boolean active = false;

    public static final AtomicLong boneTransformsSaved = new AtomicLong(0);
    public static final AtomicLong activeModelsTracked = new AtomicLong(0);

    public static void initialize() {
        if (active) return;
        active = true;
        PZOLogger.info("HordeAnimationLODGovernor: Active (Engine native skeletal animation)");
    }

    public static long getBoneTransformsSaved() {
        return boneTransformsSaved.get();
    }

    public static long getActiveModelsTracked() {
        return activeModelsTracked.get();
    }

    public static void shutdown() {
        active = false;
    }
}
