
package com.pzoptimizer.pzoe.gore;

import org.lwjgl.util.vector.Matrix4f;
import zombie.characters.IsoGameCharacter;
import zombie.core.physics.BallisticsController;
import zombie.core.skinnedmodel.model.SkinningBone;
import zombie.network.fields.hit.HitInfo;

/** Converts an existing Build 42 contact into a wound position that follows the struck bone. */
public final class GoreImpact {
    private static final String[] PART_BONE = {"Bip01_L_Hand", "Bip01_R_Hand", "Bip01_L_Forearm", "Bip01_R_Forearm",
        "Bip01_L_UpperArm", "Bip01_R_UpperArm", "Bip01_Spine1", "Bip01_Spine", "Bip01_Head", "Bip01_Neck", "Bip01_Pelvis",
        "Bip01_L_Thigh", "Bip01_R_Thigh", "Bip01_L_Calf", "Bip01_R_Calf", "Bip01_L_Foot", "Bip01_R_Foot", "Bip01_Spine1"};
    private static final ThreadLocal<GoreImpact> SCRATCH = ThreadLocal.withInitial(GoreImpact::new);
    private static final float NATIVE_HEIGHT = 2.44949f;
    private static final float WORLD_VERTICAL = 0.61237234f, WORLD_HORIZONTAL = 1.5f;
    private static final float POINT_SCALE = 4096f;
    private static final long PRESENT = 1L << 48;
    private final HitInfo muzzle = new HitInfo();
    private final Matrix4f inverse = new Matrix4f();
    long bindPoint;
    float x, y, z;

    private GoreImpact() {}

    public float worldX() { return x; }
    public float worldY() { return y; }
    public float worldZ() { return z; }

    public static String boneName(int part) {
        return part >= 0 && part < PART_BONE.length ? PART_BONE[part] : null;
    }

    static GoreImpact capture(IsoGameCharacter victim, IsoGameCharacter attacker, int region, int part, boolean singleProjectile) {
        try {
            return captureReady(victim, attacker, region, part, singleProjectile);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static GoreImpact captureReady(IsoGameCharacter victim, IsoGameCharacter attacker, int region, int part, boolean singleProjectile) {
        BallisticsController controller = attacker.getBallisticsController();
        if (controller == null || !attacker.isLocal()) return null;
        var anim = victim.getAnimationPlayer();
        if (anim == null || !anim.isReady() || anim.isBoneTransformsNeedFirstFrame()) return null;
        var sd = anim.getSkinningData();
        var hierarchy = anim.getSkeletonBoneHierarchy();
        String name = boneName(part);
        SkinningBone bone = hierarchy == null || name == null ? null : hierarchy.getBone(name);
        if (sd == null || bone == null || bone.index < 0 || bone.index >= anim.getModelTransformsCount()
            || bone.index >= sd.boneOffset.size()) return null;
        var offset = sd.boneOffset.get(bone.index);
        if (offset == null) return null;

        GoreImpact point = SCRATCH.get();
        int id = victim.getID();
        if (!point.readCamera(controller, id, region)) {
            if (!singleProjectile) return null;
            point.muzzle.x = Float.NaN;
            controller.setBallisticsTargetHitLocation(id, point.muzzle);
            if (!Float.isFinite(point.muzzle.x)) return null;
            point.x = point.muzzle.x;
            point.y = point.muzzle.y;
            point.z = point.muzzle.z;
        }
        float dx = point.x - victim.getX(), dy = point.y - victim.getY(), dz = point.z - victim.getZ();
        if (!Float.isFinite(dx) || !Float.isFinite(dy) || !Float.isFinite(dz)
            || dx * dx + dy * dy > 2.25f || dz < -0.4f || dz > 1.25f) return null;
        point.bindPoint = point.toBind(dx, dy, dz, anim.getRenderedAngle(), anim.getModelTransformAt(bone.index), offset);
        return point.bindPoint == 0L ? null : point;
    }

    private boolean readCamera(BallisticsController controller, int id, int region) {
        float[] targets = controller.getCameraTargets();
        if (targets == null) return false;
        int n = Math.min(controller.getNumberOfCameraTargets(), targets.length / 5);
        for (int i = 0; i < n * 5; i += 5) {
            if (targets[i] != id || (int) targets[i + 4] != region) continue;
            x = targets[i + 1];
            y = targets[i + 3];
            z = targets[i + 2] / NATIVE_HEIGHT;
            return true;
        }
        return false;
    }

    long toBind(float dx, float dy, float dz, float angle, Matrix4f pose, Matrix4f offset) {
        if (pose == null || !Float.isFinite(angle) || Matrix4f.invert(pose, inverse) == null) return 0L;
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        float px = -(dx * c + dy * s) / WORLD_HORIZONTAL;
        float py = dz / WORLD_VERTICAL;
        float pz = (-dx * s + dy * c) / WORLD_HORIZONTAL;
        float bx = rowX(inverse, px, py, pz), by = rowY(inverse, px, py, pz), bz = rowZ(inverse, px, py, pz);
        if (bx * bx + by * by + bz * bz > 0.18f || Matrix4f.invert(offset, inverse) == null) return 0L;
        return pack(rowX(inverse, bx, by, bz), rowY(inverse, bx, by, bz), rowZ(inverse, bx, by, bz));
    }

    private static float rowX(Matrix4f m, float x, float y, float z) { return m.m00 * x + m.m01 * y + m.m02 * z + m.m03; }
    private static float rowY(Matrix4f m, float x, float y, float z) { return m.m10 * x + m.m11 * y + m.m12 * z + m.m13; }
    private static float rowZ(Matrix4f m, float x, float y, float z) { return m.m20 * x + m.m21 * y + m.m22 * z + m.m23; }

    public static long pack(float x, float y, float z) {
        if (!(Math.abs(x) < 2f && Math.abs(y) < 2f && Math.abs(z) < 2f)) return 0L;
        return PRESENT | (Math.round(x * POINT_SCALE) & 65535L) | ((Math.round(y * POINT_SCALE) & 65535L) << 16)
            | ((Math.round(z * POINT_SCALE) & 65535L) << 32);
    }

    public static boolean valid(long point) {
        return (point >>> 48) == 1L && Math.abs(x(point)) < 2f && Math.abs(y(point)) < 2f && Math.abs(z(point)) < 2f;
    }

    public static float x(long point) { return (short) point / POINT_SCALE; }
    public static float y(long point) { return (short) (point >>> 16) / POINT_SCALE; }
    public static float z(long point) { return (short) (point >>> 32) / POINT_SCALE; }

    static boolean samePoint(long first, long second) {
        if (first == 0L || second == 0L) return first == second;
        float dx = x(first) - x(second), dy = y(first) - y(second), dz = z(first) - z(second);
        return dx * dx + dy * dy + dz * dz < 0.002025f;
    }
}
