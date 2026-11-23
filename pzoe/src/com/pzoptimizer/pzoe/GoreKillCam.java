package com.pzoptimizer.pzoe;

import com.pzoptimizer.pzoe.gore.GoreCore;
import com.pzoptimizer.pzoe.gore.GoreImpact;
import com.pzoptimizer.pzoe.gore.GoreState;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.*;
import zombie.IndieGL;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.SpriteRenderer;
import zombie.iso.Vector3;
import zombie.core.skinnedmodel.model.Model;
import zombie.iso.IsoCamera;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.PlayerCamera;

public final class GoreKillCam {
    static final String TARGET = "zombie/iso/PlayerCamera";
    private static final float DURATION = 0.9f;
    private static final float COOLDOWN = 6f;
    private static final String[] BONES = {"Bip01_Pelvis", "Bip01_Spine1", "Bip01_Neck", "Bip01_Head",
        "Bip01_L_UpperArm", "Bip01_L_Forearm", "Bip01_L_Hand", "Bip01_R_UpperArm", "Bip01_R_Forearm",
        "Bip01_R_Hand", "Bip01_L_Thigh", "Bip01_L_Calf", "Bip01_L_Foot", "Bip01_R_Thigh", "Bip01_R_Calf", "Bip01_R_Foot"};
    private static final int[] PARENT = {-1, 0, 1, 2, 2, 4, 5, 2, 7, 8, 0, 10, 11, 0, 13, 14};
    private static final float[] points = new float[BONES.length * 2];
    private static final Vector3 bone = new Vector3();
    private static IsoPlayer player;
    private static float started, next, focusX, focusY, impactX, impactY;
    private static int floor;

    private GoreKillCam() {}

    static void start(IsoGameCharacter victim, IsoPlayer killer, GoreState gore, int part) {
        if (!PerformanceSettings.fboRenderChunk || IsoPlayer.numPlayers != 1 || killer != IsoPlayer.players[0] || killer.getVehicle() != null
            || !(victim instanceof IsoZombie) || victim.getCurrentSquare() == null
            || !victim.getCurrentSquare().isCouldSee(0) || (int) victim.getZ() != (int) killer.getZ()) return;
        float now = GoreCore.clock();
        if (player != null || now < next) return;
        float dx = victim.getX() - killer.getX(), dy = victim.getY() - killer.getY();
        if (dx * dx + dy * dy > 64f) return;
        var anim = victim.getAnimationPlayer();
        if (anim == null || !anim.isReady() || anim.isBoneTransformsNeedFirstFrame()) return;

        float originX = victim.getX(), originY = victim.getY();
        for (int i = 0; i < BONES.length; i++) {
            int index = anim.getSkinningBoneIndex(BONES[i], -1);
            int at = i * 2;
            points[at] = Float.NaN;
            if (index < 0 || index >= anim.getModelTransformsCount()) continue;
            Model.boneToWorldCoords(victim, index, bone);
            if (!Float.isFinite(bone.x) || !Float.isFinite(bone.y) || !Float.isFinite(bone.z)
                || Math.abs(bone.x - originX) > 1.7f || Math.abs(bone.y - originY) > 1.7f
                || Math.abs(bone.z - victim.getZ()) > 1.2f) continue;
            points[at] = IsoUtils.XToScreen(bone.x, bone.y, bone.z, 0);
            points[at + 1] = IsoUtils.YToScreen(bone.x, bone.y, bone.z, 0);
        }
        if (!Float.isFinite(points[0]) || !Float.isFinite(points[6])) return;
        int hit = 1;
        String name = GoreImpact.boneName(part);
        for (int i = 0; i < BONES.length; i++) {
            if (BONES[i].equals(name) && Float.isFinite(points[i * 2])) hit = i;
        }
        if (!Float.isFinite(points[hit * 2])) hit = 0;
        impactX = points[hit * 2];
        impactY = points[hit * 2 + 1];
        if (now - gore.camHitAt < 0.3f && gore.camHitPart == part) {
            impactX = IsoUtils.XToScreen(gore.camHitX, gore.camHitY, gore.camHitZ, 0);
            impactY = IsoUtils.YToScreen(gore.camHitX, gore.camHitY, gore.camHitZ, 0);
        }
        focusX = (points[0] + points[6] + impactX) / 3f;
        focusY = (points[1] + points[7] + impactY) / 3f;
        floor = (int) victim.getZ();
        started = now;
        next = now + COOLDOWN;
        player = killer;
    }

    static boolean active() { return player != null; }

    static void cancel() { player = null; }

    static float remaining() {
        if (player == null) return 0f;
        float left = started + DURATION - GoreCore.clock();
        if (left > 0f && PerformanceSettings.fboRenderChunk && player == IsoPlayer.players[0] && player.getVehicle() == null && !player.isDead()
            && IsoPlayer.numPlayers == 1 && IsoWorld.instance != null && player.getCell() == IsoWorld.instance.currentCell) return left;
        cancel();
        return 0f;
    }

    private static float blend(float left) {
        float amount = Math.min(1f, Math.min((DURATION - left) / 0.15f, left / 0.23f));
        return amount * amount * (3f - 2f * amount);
    }

    public static void pan(PlayerCamera camera) {
        if (player == null || camera.playerIndex != 0 || IsoCamera.getCameraCharacter() != player) return;
        float left = remaining();
        if (left <= 0f) return;
        float pan = blend(left);
        float width = IsoCamera.getOffscreenWidth(0), height = IsoCamera.getOffscreenHeight(0);
        camera.offX += pan * clamp(focusX - camera.offX - width * 0.5f - camera.rightClickX, width * 0.42f);
        camera.offY += pan * clamp(focusY - camera.offY - height * 0.5f - camera.rightClickY, height * 0.42f);
    }

    private static float clamp(float value, float max) {
        return Math.max(-max, Math.min(max, value));
    }

    static void draw(int index, int z) {
        if (index != 0 || z != floor) return;
        float left = remaining();
        float fade = blend(left);
        if (fade <= 0f) return;
        var frame = IsoCamera.frameState;
        if (frame.playerIndex != 0 || frame.camCharacter != player) return;
        var sprite = SpriteRenderer.instance;
        int scale = Core.tileScale;
        sprite.StartShader(0, index);
        IndieGL.disableDepthTest();
        IndieGL.glBlendFunc(770, 771);
        for (int i = 1; i < BONES.length; i++) {
            int to = i * 2, from = PARENT[i] * 2;
            if (!Float.isFinite(points[to]) || !Float.isFinite(points[from])) continue;
            float x = points[from] - frame.offX, y = points[from + 1] - frame.offY;
            float tx = points[to] - frame.offX, ty = points[to + 1] - frame.offY;
            sprite.renderlinef(null, x, y, tx, ty, 0.03f, 0.13f, 0.17f, fade * 0.65f, 5 * scale);
            sprite.renderlinef(null, x, y, tx, ty, 0.66f, 0.91f, 0.95f, fade * 0.9f, 2 * scale);
        }
        float x = impactX - frame.offX, y = impactY - frame.offY;
        float r = (4f + (DURATION - left) * 7f) * scale;
        sprite.renderlinef(null, x - r, y, x, y - r, 0.93f, 0.35f, 0.17f, fade, scale);
        sprite.renderlinef(null, x, y - r, x + r, y, 0.93f, 0.35f, 0.17f, fade, scale);
        sprite.renderlinef(null, x + r, y, x, y + r, 0.93f, 0.35f, 0.17f, fade, scale);
        sprite.renderlinef(null, x, y + r, x - r, y, 0.93f, 0.35f, 0.17f, fade, scale);
        IndieGL.enableDepthTest();
    }

    static boolean patchCamera(ClassNode node) {
        for (MethodNode m : node.methods) {
            if (!m.name.equals("center") || !m.desc.equals("()V")) continue;
            for (AbstractInsnNode at : m.instructions.toArray()) {
                if (at.getOpcode() != Opcodes.RETURN) continue;
                InsnList hook = new InsnList();
                hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/GoreKillCam",
                    "pan", "(L" + TARGET + ";)V", false));
                m.instructions.insertBefore(at, hook);
                return true;
            }
        }
        return false;
    }
}
