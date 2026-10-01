package com.pzoptimizer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-Efficiency Vehicle Spatial Culler for Zombie Line-of-Sight Calculations.
 * In vehicle-dense urban environments, zombies line-of-sight checks (`IsoZombie.isVehicleBetween`)
 * execute full 3D coordinate transformation matrices and ray-box intersections against every vehicle.
 * 
 * VehicleSpatialCuller provides fast 2D distance-squared rejection against the vehicle's
 * outer bounding sphere before expensive 3D transformation matrices are invoked.
 */
public final class VehicleSpatialCuller {

    private static volatile boolean active = true;
    public static final AtomicLong intersectionTestsSaved = new AtomicLong(0);

    private static final float BOUNDING_MARGIN = 1.0f;
    private static final Map<Object, Float> radiusCache = new ConcurrentHashMap<>();

    // Per-frame candidate filtering
    private static volatile int lastFrameId = -1;
    private static final List<Object> nearCandidates = new ArrayList<>();
    private static float lastCandidateX = Float.NaN;
    private static float lastCandidateY = Float.NaN;

    private static Method getScriptMethod = null;
    private static Method getExtentsMethod = null;
    private static Method getCenterOfMassOffsetMethod = null;
    private static Method getXMethod = null;
    private static Method getYMethod = null;
    private static boolean reflectionInitialized = false;

    private static synchronized void initReflection(Object vehicle) {
        if (reflectionInitialized || vehicle == null) return;
        try {
            Class<?> vClass = vehicle.getClass();
            try { getXMethod = vClass.getMethod("getX"); } catch (Throwable ignored) {}
            try { getYMethod = vClass.getMethod("getY"); } catch (Throwable ignored) {}
            try { getScriptMethod = vClass.getMethod("getScript"); } catch (Throwable ignored) {}

            Object script = getScriptMethod != null ? getScriptMethod.invoke(vehicle) : null;
            if (script != null) {
                Class<?> sClass = script.getClass();
                try { getExtentsMethod = sClass.getMethod("getExtents"); } catch (Throwable ignored) {}
                try { getCenterOfMassOffsetMethod = sClass.getMethod("getCenterOfMassOffset"); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        reflectionInitialized = true;
    }

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    /**
     * Fast geometric rejection. Returns false if the vehicle definitely cannot intersect the segment.
     */
    public static boolean mayVehicleIntersect(Object vehicle, float x1, float y1, float x2, float y2) {
        if (!active || vehicle == null) return true;

        float r = getBoundingRadius(vehicle);
        if (r < 0.0f) return true; // Cannot determine radius, proceed with vanilla check

        float vx = getVehicleX(vehicle);
        float vy = getVehicleY(vehicle);

        float distSq = distanceSquaredToSegment(vx, vy, x1, y1, x2, y2);
        if (distSq > r * r) {
            intersectionTestsSaved.incrementAndGet();
            return false;
        }
        return true;
    }

    /**
     * Resolves the maximum bounding radius in world tiles for a given vehicle.
     */
    public static float getBoundingRadius(Object vehicle) {
        if (vehicle == null) return -1.0f;
        initReflection(vehicle);

        Object script = null;
        try {
            if (getScriptMethod != null) {
                script = getScriptMethod.invoke(vehicle);
            }
        } catch (Throwable ignored) {}

        if (script == null) return -1.0f;

        Float cached = radiusCache.get(script);
        if (cached != null) return cached;

        try {
            if (getExtentsMethod != null && getCenterOfMassOffsetMethod != null) {
                Object ext = getExtentsMethod.invoke(script);
                Object com = getCenterOfMassOffsetMethod.invoke(script);
                if (ext != null && com != null) {
                    Class<?> vecCls = ext.getClass();
                    float ex = vecCls.getField("x").getFloat(ext);
                    float ez = vecCls.getField("z").getFloat(ext);
                    float cx = vecCls.getField("x").getFloat(com);
                    float cz = vecCls.getField("z").getFloat(com);

                    float halfDiag = 0.5f * (float) Math.sqrt(ex * ex + ez * ez);
                    float comOffset = (float) Math.sqrt(cx * cx + cz * cz);
                    float radius = halfDiag + comOffset + BOUNDING_MARGIN;
                    radiusCache.put(script, radius);
                    return radius;
                }
            }
        } catch (Throwable ignored) {}

        return 5.0f; // Safe fallback radius for typical vehicles
    }

    private static float getVehicleX(Object v) {
        try {
            if (getXMethod != null) return ((Number) getXMethod.invoke(v)).floatValue();
        } catch (Throwable ignored) {}
        return 0.0f;
    }

    private static float getVehicleY(Object v) {
        try {
            if (getYMethod != null) return ((Number) getYMethod.invoke(v)).floatValue();
        } catch (Throwable ignored) {}
        return 0.0f;
    }

    /**
     * Minimum squared distance from point (px, py) to line segment (x1, y1)-(x2, y2).
     */
    public static float distanceSquaredToSegment(float px, float py, float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float lenSq = dx * dx + dy * dy;
        if (lenSq <= 1e-6f) {
            float ox = px - x1;
            float oy = py - y1;
            return ox * ox + oy * oy;
        }

        float t = ((px - x1) * dx + (py - y1) * dy) / lenSq;
        if (t < 0.0f) {
            t = 0.0f;
        } else if (t > 1.0f) {
            t = 1.0f;
        }

        float projX = x1 + t * dx;
        float projY = y1 + t * dy;
        float rx = px - projX;
        float ry = py - projY;
        return rx * rx + ry * ry;
    }
}
