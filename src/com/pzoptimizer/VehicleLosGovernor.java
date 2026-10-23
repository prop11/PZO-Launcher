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
 * snapshot and a zombie's update. The snapshot is also bucketed into 16-tile cells, so a zombie only looks at the
 * vehicles near its sight line, and the box test itself (boxHit) is vanilla's arithmetic on reused scratch objects,
 * against the vehicle's current transform, instead of allocating from the vehicle pools per test.
 */
public final class VehicleLosGovernor {

    private static volatile boolean active = true;
    public static final AtomicLong testsRejected = new AtomicLong();
    public static final AtomicLong testsRun = new AtomicLong();
    // One box test in 32 is repeated through vanilla's getIntersectPoint; any disagreement is counted
    public static final AtomicLong boxChecks = new AtomicLong();
    public static final AtomicLong boxMismatches = new AtomicLong();
    private static int boxCheckTick;

    private VehicleLosGovernor() {}

    public static void setActive(boolean value) {
        active = value;
    }

    private static final float SNAPSHOT_SLACK = 1.0f;
    private static final float CELL = 16.0f;
    private static final int MAX_CELLS = 4096;

    // Main thread only
    private static int snapshotFrame = Integer.MIN_VALUE;
    private static int snapshotCount;
    // Allocated on first use: patchZombie runs inside IsoZombie's class load, so static init stays free of game types
    private static BaseVehicle[] snapVehicles;
    private static float[] snapShape; // world x, world y, physics height, radius with slack
    private static float maxRadius;
    // Vehicles bucketed by centre into CELL-sized cells: cellStart[c]..cellStart[c+1] index cellItems
    private static float gridX0, gridY0;
    private static int gridW, gridH;
    private static int[] cellStart, cellItems, cellOf, cellFill;
    private static Transform scratchInverse;
    private static Vector3f scratchStart, scratchEnd;

    private static void snapshot() {
        int frame = IsoWorld.instance.getFrameNo();
        if (frame == snapshotFrame) return;
        snapshotFrame = frame;
        if (snapVehicles == null) {
            snapVehicles = new BaseVehicle[32];
            snapShape = new float[32 * 4];
            cellOf = new int[32];
            cellItems = new int[32];
            cellStart = new int[2];
            cellFill = new int[1];
            scratchInverse = new Transform();
            scratchStart = new Vector3f();
            scratchEnd = new Vector3f();
        }
        int n = 0;
        float offX = WorldSimulation.instance.offsetX;
        float offY = WorldSimulation.instance.offsetY;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        maxRadius = 0.0f;
        for (BaseVehicle vehicle : IsoWorld.instance.currentCell.getVehicles()) {
            if (n == snapVehicles.length) {
                snapVehicles = Arrays.copyOf(snapVehicles, n * 2);
                snapShape = Arrays.copyOf(snapShape, n * 8);
                cellOf = Arrays.copyOf(cellOf, n * 2);
                cellItems = Arrays.copyOf(cellItems, n * 2);
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
                float x = xfrm.origin.x + offX, y = xfrm.origin.z + offY;
                float r = (float) Math.sqrt(hx * hx + hy * hy + hz * hz) + 0.05f + SNAPSHOT_SLACK;
                snapShape[k] = x;
                snapShape[k + 1] = y;
                snapShape[k + 2] = xfrm.origin.y;
                snapShape[k + 3] = r;
                if (r > maxRadius) maxRadius = r;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            } else {
                snapShape[k + 3] = Float.NaN; // no shape known: always run the vanilla test
            }
            n++;
        }
        for (int i = n; i < snapshotCount; i++) snapVehicles[i] = null;
        snapshotCount = n;
        buildGrid(n, minX, minY, maxX, maxY);
    }

    private static void buildGrid(int n, float minX, float minY, float maxX, float maxY) {
        gridW = 0;
        if (n == 0 || minX > maxX) return;
        int w = (int) ((maxX - minX) / CELL) + 1, h = (int) ((maxY - minY) / CELL) + 1;
        if ((long) w * h > MAX_CELLS) return; // vehicles too spread out: the linear walk is used
        gridX0 = minX;
        gridY0 = minY;
        gridH = h;
        int cells = w * h;
        if (cellStart.length < cells + 1) cellStart = new int[cells + 1];
        if (cellFill.length < cells) cellFill = new int[cells];
        Arrays.fill(cellStart, 0, cells + 1, 0);
        Arrays.fill(cellFill, 0, cells, 0);
        for (int i = 0; i < n; i++) {
            int c = -1;
            if (snapShape[i * 4 + 3] == snapShape[i * 4 + 3]) {
                int cx = (int) ((snapShape[i * 4] - minX) / CELL), cy = (int) ((snapShape[i * 4 + 1] - minY) / CELL);
                c = cy * w + cx;
                cellStart[c + 1]++;
            }
            cellOf[i] = c;
        }
        for (int c = 0; c < cells; c++) cellStart[c + 1] += cellStart[c];
        for (int i = 0; i < n; i++) {
            int c = cellOf[i];
            if (c >= 0) cellItems[cellStart[c] + cellFill[c]++] = i;
        }
        gridW = w;
    }

    /** Replaces the body of IsoZombie.isVehicleBetween(targetX, targetY, targetZ) while active. */
    public static boolean isVehicleBetween(IsoZombie zombie, float targetX, float targetY, float targetZ) {
        snapshot();
        if (snapshotCount == 0) return false;
        float sx = targetX, sy = targetY;
        float ex = zombie.getX(), ey = zombie.getY();
        long rejected = 0, run = 0;
        try {
            // Vehicles without a known shape sit in no cell, so they always get the vanilla test
            for (int i = 0; i < snapshotCount; i++) {
                if (snapShape[i * 4 + 3] != snapShape[i * 4 + 3]) {
                    run++;
                    if (vanillaTest(snapVehicles[i], targetX, targetY, targetZ, zombie)) return true;
                }
            }
            if (gridW == 0) {
                for (int i = 0; i < snapshotCount; i++) {
                    if (snapShape[i * 4 + 3] != snapShape[i * 4 + 3]) continue;
                    if (!nearSegment(i, sx, sy, ex, ey)) {
                        rejected++;
                        continue;
                    }
                    run++;
                    if (checkedBoxHit(snapVehicles[i], targetX, targetY, targetZ, zombie)) return true;
                }
                return false;
            }
            int cx0 = Math.max(0, (int) Math.floor((Math.min(sx, ex) - maxRadius - gridX0) / CELL));
            int cx1 = Math.min(gridW - 1, (int) Math.floor((Math.max(sx, ex) + maxRadius - gridX0) / CELL));
            int cy0 = Math.max(0, (int) Math.floor((Math.min(sy, ey) - maxRadius - gridY0) / CELL));
            int cy1 = Math.min(gridH - 1, (int) Math.floor((Math.max(sy, ey) + maxRadius - gridY0) / CELL));
            for (int cy = cy0; cy <= cy1; cy++) {
                for (int cx = cx0; cx <= cx1; cx++) {
                    int c = cy * gridW + cx;
                    for (int j = cellStart[c], end = cellStart[c + 1]; j < end; j++) {
                        int i = cellItems[j];
                        if (!nearSegment(i, sx, sy, ex, ey)) {
                            rejected++;
                            continue;
                        }
                        run++;
                        if (checkedBoxHit(snapVehicles[i], targetX, targetY, targetZ, zombie)) return true;
                    }
                }
            }
            return false;
        } finally {
            if (rejected > 0) testsRejected.addAndGet(rejected);
            if (run > 0) testsRun.addAndGet(run);
        }
    }

    private static boolean nearSegment(int i, float sx, float sy, float ex, float ey) {
        int k = i * 4;
        float dx = ex - sx, dy = ey - sy;
        float lenSq = dx * dx + dy * dy;
        float ax = sx - snapShape[k], ay = sy - snapShape[k + 1];
        float t = 0.0f;
        if (lenSq > 1.0e-12f) {
            t = -(ax * dx + ay * dy) / lenSq;
            t = t < 0.0f ? 0.0f : (t > 1.0f ? 1.0f : t);
        }
        float cx = ax + dx * t, cy = ay + dy * t, h = snapShape[k + 2], r = snapShape[k + 3];
        return cx * cx + cy * cy + h * h <= r * r;
    }

    /**
     * BaseVehicle.getIntersectPoint(start, end, result) != null, with the same transform, the same JOML calls and
     * the same slab test in the same order, on reused scratch objects instead of the vehicle pools. Only x and y
     * of the points matter: getLocalPos drops the world z.
     */
    private static boolean boxHit(BaseVehicle vehicle, float sx, float sy, float ex, float ey) {
        VehicleScript script = vehicle.getScript();
        float hx = script.getExtents().x * 0.5F + script.getCenterOfMassOffset().x();
        float hy = script.getExtents().y * 0.5F + script.getCenterOfMassOffset().y();
        float hz = script.getExtents().z * 0.5F + script.getCenterOfMassOffset().z();
        Transform inv = scratchInverse;
        inv.set(vehicle.jniTransform);
        inv.inverse();
        float offX = WorldSimulation.instance.offsetX, offY = WorldSimulation.instance.offsetY;
        Vector3f ls = scratchStart.set(sx - offX, 0.0F, sy - offY);
        inv.transform(ls);
        Vector3f le = scratchEnd.set(ex - offX, 0.0F, ey - offY);
        inv.transform(le);
        float tmin = 0.0F, tmax = 1.0F;
        for (int i = 0; i < 3; i++) {
            float maxCoord = i == 0 ? hx : (i == 1 ? hy : hz);
            float minCoord = maxCoord * -1.0F;
            float startCoord = ls.get(i);
            float directionCoord = le.get(i) - startCoord;
            if (Math.abs(directionCoord) < 1.0E-6) {
                if (startCoord < minCoord || startCoord > maxCoord) return false;
            } else {
                float t1 = (minCoord - startCoord) / directionCoord;
                float t2 = (maxCoord - startCoord) / directionCoord;
                if (t1 > t2) {
                    float temp = t1;
                    t1 = t2;
                    t2 = temp;
                }
                tmin = Math.max(tmin, t1);
                tmax = Math.min(tmax, t2);
                if (tmin > tmax) return false;
            }
        }
        return true;
    }

    private static boolean checkedBoxHit(BaseVehicle vehicle, float targetX, float targetY, float targetZ, IsoZombie zombie) {
        boolean hit = boxHit(vehicle, targetX, targetY, zombie.getX(), zombie.getY());
        if ((++boxCheckTick & 31) == 0) {
            boxChecks.incrementAndGet();
            if (vanillaTest(vehicle, targetX, targetY, targetZ, zombie) != hit) boxMismatches.incrementAndGet();
        }
        return hit;
    }

    private static boolean vanillaTest(BaseVehicle vehicle, float targetX, float targetY, float targetZ, IsoZombie zombie) {
        Vector3f start = BaseVehicle.allocVector3f(), end = BaseVehicle.allocVector3f(), hit = BaseVehicle.allocVector3f();
        try {
            start.set(targetX, targetY, targetZ + 0.1F);
            end.set(zombie.getX(), zombie.getY(), zombie.getZ() + 0.1F);
            return vehicle.getIntersectPoint(start, end, hit) != null;
        } finally {
            BaseVehicle.releaseVector3f(start);
            BaseVehicle.releaseVector3f(end);
            BaseVehicle.releaseVector3f(hit);
        }
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
