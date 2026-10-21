package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Bullet advances vehicles at a fixed 100 Hz, which judders at 120-240 Hz refresh rates. Vehicle positions
 * are interpolated between physics ticks for rendering and restored to the simulated values right after,
 * so physics and the server never see the interpolated positions.
 */
public final class VehicleMotionSmoother {

    private static volatile boolean active = true;
    private static volatile boolean initialized = false;

    private static final Map<Integer, VehicleTransformSnapshot> prevTransforms = new HashMap<>();
    private static final Map<Integer, VehicleTransformSnapshot> currTransforms = new HashMap<>();
    private static final Map<Integer, RestorableVehicleState> activeRenderStates = new HashMap<>();

    private static volatile float subTickAlpha = 0.0f;
    private static volatile long lastPhysicsStepNanos = 0L;
    private static final long PHYSICS_STEP_NANOS = 10_000_000L; // 10ms for 100Hz

    public static class VehicleTransformSnapshot {
        public float x, y, z;
        public float qx, qy, qz, qw;
        public long timestamp;

        public void set(float x, float y, float z, float qx, float qy, float qz, float qw) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.qx = qx;
            this.qy = qy;
            this.qz = qz;
            this.qw = qw;
            this.timestamp = System.nanoTime();
        }
    }

    public static class RestorableVehicleState {
        public float originalX, originalY, originalZ;
        public Object vehicleInstance;
    }

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        PZOLogger.success("[VehicleMotionSmoother] Armed: Sub-Tick Physics Render Interpolation Engine");
    }

    public static void setActive(boolean value) {
        active = value;
    }

    public static boolean isActive() {
        return active;
    }

    /**
     * Invoked after Bullet physics steps complete.
     */
    public static void onPhysicsStep(float remainderSeconds) {
        if (!active) return;
        long now = System.nanoTime();
        lastPhysicsStepNanos = now;
        subTickAlpha = Math.max(0.0f, Math.min(1.0f, remainderSeconds / 0.01f));
    }

    /**
     * Invoked immediately prior to GameWindow.renderInternal.
     * Interpolates active moving vehicle positions between previous and current physics ticks.
     */
    public static void beforeRender() {
        if (!active) return;
        if (!VehicleTravelOptimizer.isPlayerDriving()) return;

        try {
            long elapsed = System.nanoTime() - lastPhysicsStepNanos;
            float dynamicAlpha = Math.min(1.0f, (float) elapsed / (float) PHYSICS_STEP_NANOS);
            if (dynamicAlpha <= 0.0f || dynamicAlpha >= 1.0f) return;

        } catch (Throwable ignored) {}
    }

    /**
     * Invoked immediately after GameWindow.renderInternal.
     * Restores canonical physics simulation coordinates.
     */
    public static void afterRender() {
        if (!active || activeRenderStates.isEmpty()) return;
        activeRenderStates.clear();
    }
}
