package com.pzoptimizer.pzoe.water;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.util.vector.Matrix4f;
import zombie.characters.IsoPlayer;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.SkinningData;
import zombie.network.GameServer;

public final class WaterAnimation {
    public static final String TARGET = "zombie/core/skinnedmodel/animation/AnimationPlayer";
    private static final String OWNER = "com/pzoptimizer/pzoe/water/WaterAnimation";
    private static final float TAU = (float) (2.0 * Math.PI);
    private static final Map<IsoPlayer, Motion> motions = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Quaternionf IDENTITY = new Quaternionf();

    private record Branch(int joint, int end, int[] members) {}

    private static final class Rig {
        final Branch pelvis, neck, leftThigh, rightThigh, leftCalf, rightCalf, leftArm, rightArm, leftForearm, rightForearm;
        final Branch leftFoot, rightFoot;

        Rig(SkinningData skin) {
            pelvis = branch(skin, "Pelvis", "Spine");
            neck = branch(skin, "Neck", "Head");
            leftThigh = branch(skin, "L_Thigh", "L_Calf");
            rightThigh = branch(skin, "R_Thigh", "R_Calf");
            leftCalf = branch(skin, "L_Calf", "L_Foot");
            rightCalf = branch(skin, "R_Calf", "R_Foot");
            leftFoot = branch(skin, "L_Foot", "L_Toe0");
            rightFoot = branch(skin, "R_Foot", "R_Toe0");
            leftArm = branch(skin, "L_UpperArm", "L_Forearm");
            rightArm = branch(skin, "R_UpperArm", "R_Forearm");
            leftForearm = branch(skin, "L_Forearm", "L_Hand");
            rightForearm = branch(skin, "R_Forearm", "R_Hand");
        }

        boolean usable() {
            return pelvis != null && leftThigh != null && rightThigh != null && leftArm != null && rightArm != null;
        }
    }

    private static final class Motion {
        SkinningData skin;
        Rig rig;
        float wade, swim, stroke, cycle;
        float surface;
        float heading = Float.NaN, yaw;
        final Quaternionf rotation = new Quaternionf();
        final Vector3f direction = new Vector3f(), target = new Vector3f(), vector = new Vector3f();
    }

    private WaterAnimation() {}

    public static void pose(AnimationPlayer animator, float dt) {
        if (GameServer.server || !(animator.getIsoGameCharacter() instanceof IsoPlayer player)
            || animator.parentPlayer != null) return;
        if (player.isDead() || player.isOnFloor() || player.isClimbing() || player.isFalling()
            || player.isAttacking() || player.getVehicle() != null || animator.isRagdolling()
            || !locomotion(player.getActionStateName())) {
            if (!motions.isEmpty()) motions.remove(player);
            return;
        }
        var movement = player.getMovementLastFrame();
        boolean moving = player.isPlayerMoving() || travelled(movement.getX(), movement.getY());
        boolean swimming = WaterSwimming.isSwimming(player);
        float wading = !swimming && moving ? WaterWading.depth(player) : 0f;
        Motion motion = motions.get(player);
        if (motion == null) {
            if (!swimming && wading <= 0f) return;
            motion = new Motion();
            motions.put(player, motion);
        }
        SkinningData skin = animator.getSkinningData();
        if (skin == null || animator.getModelTransformsCount() < skin.numBones()) return;
        if (motion.skin != skin) {
            motion.skin = skin;
            motion.rig = new Rig(skin);
        }
        if (!motion.rig.usable()) return;

        dt = Math.max(0f, Math.min(0.1f, dt));
        motion.wade = approach(motion.wade, wading > 0f ? Math.min(1f, 0.55f + wading * 0.45f) : 0f, dt * 5.5f);
        motion.swim = approach(motion.swim, swimming ? 1f : 0f, dt * 5.5f);
        motion.stroke = approach(motion.stroke, swimming && moving ? 1f : 0f, dt * 4f);
        if (motion.wade <= 0f && motion.swim <= 0f) {
            motions.remove(player);
            return;
        }
        motion.cycle = (motion.cycle + dt * (motion.swim > 0f ? 0.9f - 0.22f * motion.stroke : 0.94f)) % 1f;
        float wave = (float) Math.sin(motion.cycle * TAU), quarter = (float) Math.cos(motion.cycle * TAU);
        motion.surface = 0f;
        if (motion.wade > 0f) wade(animator, motion, wave, quarter);
        if (motion.swim > 0f) {
            updateHeading(animator, player, motion, swimming && moving, dt);
            swim(animator, motion, wave, quarter);
        }
    }

    static boolean locomotion(String state) {
        if (state == null) return false;
        return switch (state) {
            case "idle", "slowidleblend", "movement", "walk", "run", "sprint", "strafe", "strafing", "aim", "aim-sneak",
                "aim-strafe", "turning", "turning180", "turningAim180", "turningIdle180", "turningMovement180" -> true;
            default -> false;
        };
    }

    private static float approach(float value, float target, float limit) {
        return value + Math.max(-limit, Math.min(limit, target - value));
    }

    private static void updateHeading(AnimationPlayer animator, IsoPlayer player, Motion motion, boolean moving, float dt) {
        if (moving) {
            var movement = player.getMovementLastFrame();
            float dx = movement.getX(), dy = movement.getY();
            if (travelled(dx, dy)) {
                float target = (float) Math.atan2(dy, dx);
                motion.heading = Float.isNaN(motion.heading) ? target
                    : motion.heading + approach(0f, travelYaw(motion.heading, target), dt * 10f);
            }
        } else if (motion.stroke <= 0f) {
            motion.heading = Float.NaN;
        }
        motion.yaw = Float.isNaN(motion.heading) ? 0f : travelYaw(animator.getAngle(), motion.heading);
    }

    static boolean travelled(float dx, float dy) {
        float distance = dx * dx + dy * dy;
        return distance > 1e-8f && distance < 0.25f;
    }

    static float travelYaw(float facing, float travel) {
        return (float) Math.IEEEremainder(travel - facing, TAU);
    }

    private static void wade(AnimationPlayer animator, Motion m, float stride, float phase) {
        Rig rig = m.rig;
        bend(animator, m, rig.pelvis, 0.11f * m.wade, -0.04f * m.wade);
        step(animator, m, rig.leftThigh, rig.leftCalf, stride, phase, m.wade);
        step(animator, m, rig.rightThigh, rig.rightCalf, -stride, -phase, m.wade);
    }

    private static void step(AnimationPlayer animator, Motion m, Branch thigh, Branch calf, float stride, float phase, float weight) {
        float lift = Math.max(0f, phase);
        aim(animator, m, thigh, -stride * 0.46f - lift * 0.19f, -0.96f + 0.39f * lift, 0f, weight);
        aim(animator, m, calf, 0.67f * lift + stride * 0.10f, -0.94f + 0.26f * lift, 0f, weight);
    }

    private static void swim(AnimationPlayer animator, Motion m, float wave, float quarter) {
        Rig rig = m.rig;
        float weight = m.swim, forward = m.stroke;
        Matrix4f left = animator.getModelTransformAt(rig.leftArm.joint()), right = animator.getModelTransformAt(rig.rightArm.joint());
        float bodyYaw = (float) Math.atan2(right.m23 - left.m23, left.m03 - right.m03);
        yaw(animator, m, -bodyYaw * weight);
        bend(animator, m, rig.pelvis, (0.06f + 1.30f * forward) * weight, (-0.24f + wave * 0.026f) * weight);
        aim(animator, m, rig.neck, -0.15f * forward, 1f, 0f, weight * 0.75f);
        stroke(animator, m, rig.leftArm, rig.leftForearm, 1f, wave, quarter);
        stroke(animator, m, rig.rightArm, rig.rightForearm, -1f, wave, quarter);
        float flutter = (float) Math.sin(m.cycle * TAU * 2f);
        float kick = mix(wave, flutter, forward);
        kick(animator, m, rig.leftThigh, rig.leftCalf, rig.leftFoot, 1f, kick);
        kick(animator, m, rig.rightThigh, rig.rightCalf, rig.rightFoot, -1f, -kick);
        yaw(animator, m, m.yaw * forward * weight);
        if (rig.neck != null) {
            float chest = mix(animator.getModelTransformAt(rig.pelvis.joint()).m13,
                animator.getModelTransformAt(rig.neck.joint()).m13, 0.64f);
            m.surface = Math.max(0.02f, chest * 0.61237234f);
        }
    }

    public static float waterline(IsoPlayer player, float standing) {
        Motion motion = motions.get(player);
        if (motion == null || motion.surface <= 0f) return standing;
        return mix(standing, Math.min(standing, motion.surface), motion.swim);
    }

    private static void stroke(AnimationPlayer animator, Motion m, Branch arm, Branch forearm, float side, float wave, float quarter) {
        float travel = m.stroke, spread = 0.5f - 0.5f * quarter;
        float reach = mix(-0.22f + wave * 0.10f, -0.98f + 0.87f * spread, travel);
        float height = mix(-0.72f, -0.10f + 0.20f * wave, travel);
        float sweep = mix(0.46f + 0.14f * quarter, 0.15f + 0.79f * spread, travel) * side;
        aim(animator, m, arm, reach, height, sweep, m.swim);
        aim(animator, m, forearm, mix(-0.38f + 0.16f * wave, -0.96f + 1.38f * spread, travel),
            mix(-0.07f + 0.12f * wave, 0.04f - 0.24f * wave, travel),
            mix(0.61f + 0.24f * quarter, 0.12f + 0.38f * spread, travel) * side, m.swim);
    }

    private static void kick(AnimationPlayer animator, Motion m, Branch thigh, Branch calf, Branch foot, float side, float phase) {
        float travel = m.stroke;
        aim(animator, m, thigh, mix(-0.04f + 0.12f * phase, 0.98f, travel),
            mix(-0.98f, -0.06f + phase * 0.20f, travel), side * mix(0.14f, 0.065f, travel), m.swim);
        aim(animator, m, calf, mix(0.18f + 0.15f * phase, 0.96f, travel),
            mix(-0.94f, -0.04f + phase * 0.32f, travel), side * mix(0.10f, 0.035f, travel), m.swim);
        if (travel > 0f) aim(animator, m, foot, 1f, -0.09f + phase * 0.16f, 0f, m.swim * travel);
    }

    private static float mix(float a, float b, float weight) {
        return a + (b - a) * weight;
    }

    private static Branch branch(SkinningData skin, String joint, String end) {
        Integer root = skin.boneIndices.get("Bip01_" + joint), tip = skin.boneIndices.get("Bip01_" + end);
        int count = skin.numBones();
        if (root == null || tip == null || root < 0 || tip < 0 || root >= count || tip >= count) return null;
        int[] members = new int[count];
        int used = 0;
        for (int i = 0; i < count; i++) {
            int parent = i;
            for (int steps = 0; steps < count && parent >= 0 && parent < count; steps++) {
                if (parent == root) {
                    members[used++] = i;
                    break;
                }
                parent = skin.getParentBoneIdx(parent);
            }
        }
        return new Branch(root, tip, Arrays.copyOf(members, used));
    }

    private static void aim(AnimationPlayer animator, Motion m, Branch branch, float x, float y, float z, float weight) {
        if (branch == null) return;
        Matrix4f joint = animator.getModelTransformAt(branch.joint()), tip = animator.getModelTransformAt(branch.end());
        Vector3f from = m.direction.set(tip.m03 - joint.m03, tip.m13 - joint.m13, tip.m23 - joint.m23);
        if (from.lengthSquared() < 0.000001f) return;
        // The human rig faces -Z, with its left shoulder along +X.
        m.target.set(z, y, x).normalize();
        from.normalize();
        m.rotation.rotationTo(from, m.target).slerp(IDENTITY, 1f - weight);
        rotate(animator, m, branch, joint, 0f);
    }

    private static void bend(AnimationPlayer animator, Motion m, Branch branch, float angle, float yOffset) {
        m.rotation.rotationX(-angle);
        rotate(animator, m, branch, animator.getModelTransformAt(branch.joint()), yOffset);
    }

    private static void yaw(AnimationPlayer animator, Motion m, float angle) {
        if (Math.abs(angle) < 0.0001f) return;
        Branch pelvis = m.rig.pelvis;
        m.rotation.rotationY(angle);
        rotate(animator, m, pelvis, animator.getModelTransformAt(pelvis.joint()), 0f);
    }

    private static void rotate(AnimationPlayer animator, Motion motion, Branch branch, Matrix4f joint, float yOffset) {
        float x = joint.m03, y = joint.m13, z = joint.m23;
        for (int member : branch.members()) rotateAt(animator.getModelTransformAt(member), motion.rotation, motion.vector, x, y, z, yOffset);
    }

    // Edit model-space bones after stock root motion and reparenting have been resolved.
    static void rotateAt(Matrix4f matrix, Quaternionf rotation, Vector3f vector, float x, float y, float z, float yOffset) {
        rotation.transform(vector.set(matrix.m00, matrix.m10, matrix.m20));
        matrix.m00 = vector.x; matrix.m10 = vector.y; matrix.m20 = vector.z;
        rotation.transform(vector.set(matrix.m01, matrix.m11, matrix.m21));
        matrix.m01 = vector.x; matrix.m11 = vector.y; matrix.m21 = vector.z;
        rotation.transform(vector.set(matrix.m02, matrix.m12, matrix.m22));
        matrix.m02 = vector.x; matrix.m12 = vector.y; matrix.m22 = vector.z;
        rotation.transform(vector.set(matrix.m03 - x, matrix.m13 - y, matrix.m23 - z));
        matrix.m03 = x + vector.x;
        matrix.m13 = y + vector.y + yOffset;
        matrix.m23 = z + vector.z;
    }

    public static boolean patch(ClassNode node) {
        var method = AsmSupport.findMethod(node, "updateAnimation_StandardAnimation", "(F)V");
        if (method == null) return false;
        for (var instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || !TARGET.equals(call.owner)
                || !"UpdateSkinTransforms".equals(call.name) || !"()V".equals(call.desc)) continue;
            InsnList animate = new InsnList();
            animate.add(new VarInsnNode(Opcodes.ALOAD, 0));
            animate.add(new VarInsnNode(Opcodes.FLOAD, 1));
            animate.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "pose", "(L" + TARGET + ";F)V", false));
            method.instructions.insertBefore(call, animate);
            return true;
        }
        return false;
    }
}
