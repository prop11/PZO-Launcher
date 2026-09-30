package com.pzoptimizer;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-Precision Camera Drive & Zoom Smoother.
 * In Build 42, `PlayerCamera.update` drives look-ahead camera panning using
 * coarse `System.currentTimeMillis()` deltas (10-15ms Windows timer granularity)
 * and truncates camera offsets to whole offscreen pixels. At high refresh rates
 * (120Hz-240Hz) and fractional zoom levels (< 1.0), this creates noticeable camera
 * micro-judder.
 * 
 * CameraDriveSmoother calculates look-ahead panning using nanosecond-resolution delta
 * timing and snaps camera offsets to screen-space pixel boundaries.
 */
public final class CameraDriveSmoother {

    private static volatile boolean active = true;
    public static final AtomicLong framesSmoothed = new AtomicLong(0);

    private static final long[] lastNanos = new long[4];

    private static Method getCoreInstanceMethod = null;
    private static Method getZoomMethod = null;
    private static boolean zoomReflectionInitialized = false;

    private static synchronized void initZoomReflection() {
        if (zoomReflectionInitialized) return;
        try {
            Class<?> coreClass = Class.forName("zombie.core.Core");
            getCoreInstanceMethod = coreClass.getMethod("getInstance");
            getZoomMethod = coreClass.getMethod("getZoom", int.class);
        } catch (Throwable ignored) {}
        zoomReflectionInitialized = true;
    }

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    /**
     * Calculates smooth look-ahead panning multiplier using high-precision nanosecond timing.
     */
    public static float computePanMultiplier(int playerIndex, float stockMultiplier, float panSpeed) {
        if (!active || playerIndex < 0 || playerIndex >= lastNanos.length) {
            return stockMultiplier;
        }

        long now = System.nanoTime();
        long last = lastNanos[playerIndex];
        lastNanos[playerIndex] = now;

        if (last == 0L) {
            return stockMultiplier;
        }

        long deltaNanos = now - last;
        if (deltaNanos <= 0L || deltaNanos > 200_000_000L) { // clamp frame hangs > 200ms
            return stockMultiplier;
        }

        framesSmoothed.incrementAndGet();
        float deltaSec = (float) (deltaNanos / 1_000_000_000.0);
        return Math.max(1e-4f, deltaSec * panSpeed);
    }

    /**
     * Snaps camera offset to screen-pixel grid. When zoomed in (zoom < 1.0), aligns with
     * screen pixel multiples rather than offscreen texture pixels.
     */
    public static float snapCameraOffset(float offset, int playerIndex) {
        if (!active) {
            return (float) ((int) offset);
        }

        float zoom = getPlayerZoom(playerIndex);
        if (zoom >= 0.999f || zoom <= 0.01f) {
            return (float) ((int) offset);
        }

        // Align to screen pixel boundary
        return zoom * (float) ((int) (offset / zoom));
    }

    /**
     * Resolves player camera zoom level safely.
     */
    public static float getPlayerZoom(int playerIndex) {
        initZoomReflection();
        try {
            if (getCoreInstanceMethod != null && getZoomMethod != null) {
                Object core = getCoreInstanceMethod.invoke(null);
                if (core != null) {
                    return ((Number) getZoomMethod.invoke(core, playerIndex)).floatValue();
                }
            }
        } catch (Throwable ignored) {}
        return 1.0f;
    }
}
