package com.pzoptimizer;

import java.util.concurrent.atomic.AtomicLong;
import org.joml.Vector3f;
import zombie.core.physics.Transform;
import zombie.core.physics.WorldSimulation;
import zombie.scripting.objects.VehicleScript;
import zombie.vehicles.BaseVehicle;

/**
 * Replaces BaseVehicle.getIntersectPoint(start, end, result) in IsoZombie.isVehicleBetween. Every zombie that
 * spots a player tests the sight line against every vehicle in the cell with a full oriented-box intersection,
 * which allocates and releases pooled vectors and transforms per vehicle. The box is centred on the physics
 * origin with half extents |extents/2 + centerOfMassOffset|, so it lies inside a sphere of that radius; if the
 * segment (projected into physics space exactly as getLocalPos does, with height 0) never comes within the
 * radius, the box test cannot hit and null is returned without allocating. Otherwise the vanilla test runs.
 */
public final class VehicleLosGovernor {

    private static volatile boolean active = true;
    public static final AtomicLong testsRejected = new AtomicLong();
    public static final AtomicLong testsRun = new AtomicLong();

    private VehicleLosGovernor() {}

    public static void setActive(boolean value) {
        active = value;
    }

    public static Vector3f getIntersectPoint(BaseVehicle vehicle, Vector3f start, Vector3f end, Vector3f result) {
        if (active) {
            VehicleScript script = vehicle.getScript();
            Transform xfrm = vehicle.jniTransform;
            if (script != null && xfrm != null) {
                Vector3f ext = script.getExtents();
                Vector3f com = script.getCenterOfMassOffset();
                float hx = Math.abs(ext.x * 0.5f + com.x);
                float hy = Math.abs(ext.y * 0.5f + com.y);
                float hz = Math.abs(ext.z * 0.5f + com.z);
                float radiusSq = hx * hx + hy * hy + hz * hz;

                float offX = WorldSimulation.instance.offsetX;
                float offY = WorldSimulation.instance.offsetY;
                Vector3f o = xfrm.origin;
                // Physics space: x = world x - offsetX, y = 0 (getLocalPos drops world z), z = world y - offsetY
                float ax = start.x - offX - o.x, ay = -o.y, az = start.y - offY - o.z;
                float dx = end.x - start.x, dz = end.y - start.y;
                float lenSq = dx * dx + dz * dz;
                float t = 0.0f;
                if (lenSq > 1.0e-12f) {
                    t = -(ax * dx + az * dz) / lenSq;
                    t = t < 0.0f ? 0.0f : (t > 1.0f ? 1.0f : t);
                }
                float cx = ax + dx * t, cz = az + dz * t;
                float distSq = cx * cx + ay * ay + cz * cz;
                // Small slack so float rounding can never reject a segment that grazes the box corner
                float r = (float) Math.sqrt(radiusSq) + 0.05f;
                if (distSq > r * r) {
                    testsRejected.incrementAndGet();
                    return null;
                }
            }
        }
        testsRun.incrementAndGet();
        return vehicle.getIntersectPoint(start, end, result);
    }
}
