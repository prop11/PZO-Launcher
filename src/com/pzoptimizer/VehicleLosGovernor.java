package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import org.joml.Vector3f;
import zombie.characters.IsoZombie;
import zombie.iso.IsoWorld;
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
 *
 * IsoZombie.isVehicleBetween itself is also short-circuited: it walks the cell's vehicle HashSet for every zombie
 * that looks at a player. Those spheres are snapshotted once per frame into flat arrays (world centre, height
 * offset, radius), so each zombie only does a segment-to-point distance per vehicle and calls the vanilla box
 * test for the few it can actually cross. The radius carries a tile of slack for vehicles that move between the
 * snapshot and a zombie's update.
 */
public final class VehicleLosGovernor {

    private static volatile boolean active = true;
    public static final AtomicLong testsRejected = new AtomicLong();
    public static final AtomicLong testsRun = new AtomicLong();

    private VehicleLosGovernor() {}

    public static void setActive(boolean value) {
        active = value;
    }

    private static final float SNAPSHOT_SLACK = 1.0f;

    // Main thread only
    private static int snapshotFrame = Integer.MIN_VALUE;
    private static int snapshotCount;
    // Allocated on first use: patchZombie runs inside IsoZombie's class load, so static init stays free of game types
    private static BaseVehicle[] snapVehicles;
    private static float[] snapShape; // world x, world y, physics height, radius with slack

    private static void snapshot() {
        int frame = IsoWorld.instance.getFrameNo();
        if (frame == snapshotFrame) return;
        snapshotFrame = frame;
        if (snapVehicles == null) {
            snapVehicles = new BaseVehicle[32];
            snapShape = new float[32 * 4];
        }
        int n = 0;
        float offX = WorldSimulation.instance.offsetX;
        float offY = WorldSimulation.instance.offsetY;
        for (BaseVehicle vehicle : IsoWorld.instance.currentCell.getVehicles()) {
            if (n == snapVehicles.length) {
                snapVehicles = Arrays.copyOf(snapVehicles, n * 2);
                snapShape = Arrays.copyOf(snapShape, n * 8);
            }
            snapVehicles[n] = vehicle;
            VehicleScript script = vehicle.getScript();
            Transform xfrm = vehicle.jniTransform;
            int k = n * 4;
            if (script != null && xfrm != null) {
                Vector3f ext = script.getExtents();
                Vector3f com = script.getCenterOfMassOffset();
                float hx = Math.abs(ext.x * 0.5f + com.x);
                float hy = Math.abs(ext.y * 0.5f + com.y);
                float hz = Math.abs(ext.z * 0.5f + com.z);
                snapShape[k] = xfrm.origin.x + offX;
                snapShape[k + 1] = xfrm.origin.z + offY;
                snapShape[k + 2] = xfrm.origin.y;
                snapShape[k + 3] = (float) Math.sqrt(hx * hx + hy * hy + hz * hz) + 0.05f + SNAPSHOT_SLACK;
            } else {
                snapShape[k + 3] = Float.NaN; // no shape known: always run the vanilla test
            }
            n++;
        }
        for (int i = n; i < snapshotCount; i++) snapVehicles[i] = null;
        snapshotCount = n;
    }

    /** Replaces the body of IsoZombie.isVehicleBetween(targetX, targetY, targetZ) while active. */
    public static boolean isVehicleBetween(IsoZombie zombie, float targetX, float targetY, float targetZ) {
        snapshot();
        if (snapshotCount == 0) return false;
        float sx = targetX, sy = targetY;
        float dx = zombie.getX() - sx, dy = zombie.getY() - sy;
        float lenSq = dx * dx + dy * dy;
        Vector3f start = null, end = null, hit = null;
        boolean blocked = false;
        try {
            for (int i = 0; i < snapshotCount; i++) {
                int k = i * 4;
                float r = snapShape[k + 3];
                if (r == r) {
                    float ax = sx - snapShape[k], ay = sy - snapShape[k + 1];
                    float t = 0.0f;
                    if (lenSq > 1.0e-12f) {
                        t = -(ax * dx + ay * dy) / lenSq;
                        t = t < 0.0f ? 0.0f : (t > 1.0f ? 1.0f : t);
                    }
                    float cx = ax + dx * t, cy = ay + dy * t, h = snapShape[k + 2];
                    if (cx * cx + cy * cy + h * h > r * r) {
                        testsRejected.incrementAndGet();
                        continue;
                    }
                }
                if (start == null) {
                    start = BaseVehicle.allocVector3f();
                    end = BaseVehicle.allocVector3f();
                    hit = BaseVehicle.allocVector3f();
                }
                start.set(targetX, targetY, targetZ + 0.1F);
                end.set(zombie.getX(), zombie.getY(), zombie.getZ() + 0.1F);
                testsRun.incrementAndGet();
                if (snapVehicles[i].getIntersectPoint(start, end, hit) != null) {
                    blocked = true;
                    break;
                }
            }
        } finally {
            if (start != null) {
                BaseVehicle.releaseVector3f(start);
                BaseVehicle.releaseVector3f(end);
                BaseVehicle.releaseVector3f(hit);
            }
        }
        return blocked;
    }

    public static boolean isActive() {
        return active;
    }

    /** Prefixes IsoZombie.isVehicleBetween(FFF)Z with: if (VehicleLosGovernor.isActive()) return isVehicleBetween(this, x, y, z). */
    static boolean patchZombie(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "isVehicleBetween", "(FFF)Z");
        if (m == null) return false;
        InsnList pre = new InsnList();
        LabelNode stock = new LabelNode();
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/VehicleLosGovernor", "isActive", "()Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, stock));
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pre.add(new VarInsnNode(Opcodes.FLOAD, 1));
        pre.add(new VarInsnNode(Opcodes.FLOAD, 2));
        pre.add(new VarInsnNode(Opcodes.FLOAD, 3));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/VehicleLosGovernor", "isVehicleBetween",
            "(Lzombie/characters/IsoZombie;FFF)Z", false));
        pre.add(new InsnNode(Opcodes.IRETURN));
        pre.add(stock);
        m.instructions.insert(pre);
        return true;
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
