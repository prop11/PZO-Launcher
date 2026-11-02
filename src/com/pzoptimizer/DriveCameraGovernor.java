package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import zombie.characters.IsoPlayer;
import zombie.iso.PlayerCamera;

/**
 * Stops the 1-pixel stair-step judder of the camera while driving by keeping the sub-pixel camera offset instead of
 * rounding it to whole pixels. Only while the camera's player is in a vehicle.
 */
public final class DriveCameraGovernor {

    public static volatile boolean active = true;

    private DriveCameraGovernor() {}

    public static float getSmoothOffX(PlayerCamera cam) {
        return cam == null ? 0.0f : offset(cam, cam.offX + cam.rightClickX);
    }

    public static float getSmoothOffY(PlayerCamera cam) {
        return cam == null ? 0.0f : offset(cam, cam.offY + cam.rightClickY);
    }

    public static float getSmoothLastOffX(PlayerCamera cam) {
        return cam == null ? 0.0f : offset(cam, cam.lastOffX + cam.rightClickX);
    }

    public static float getSmoothLastOffY(PlayerCamera cam) {
        return cam == null ? 0.0f : offset(cam, cam.lastOffY + cam.rightClickY);
    }

    // On foot keep the stock whole-pixel offset: chunk textures and sprites at fractional offsets come out filtered
    // (soft, with seams between chunks), and only a moving car's camera shows the judder anyway
    private static float offset(PlayerCamera cam, float raw) {
        if (!active || !inVehicle(cam.playerIndex) || ViewpointStanddownGovernor.isStanddownActive()) {
            return (int) raw;
        }
        float zoom = cam.zoom;
        if (zoom < 0.999f && zoom > 0.01f) {
            return zoom * (int) (raw / zoom);
        }
        return raw;
    }

    private static boolean inVehicle(int playerIndex) {
        IsoPlayer[] players = IsoPlayer.players;
        if (players == null || playerIndex < 0 || playerIndex >= players.length) return false;
        IsoPlayer player = players[playerIndex];
        return player != null && player.getVehicle() != null;
    }

    /** Transformer for zombie.iso.PlayerCamera: sends the rounded offset getters to my sub-pixel ones. */
    public static boolean patchPlayerCamera(ClassNode node) {
        boolean changed = false;
        changed |= rewriteGetter(node, "getOffX", "getSmoothOffX");
        changed |= rewriteGetter(node, "getOffY", "getSmoothOffY");
        changed |= rewriteGetter(node, "getLastOffX", "getSmoothLastOffX");
        changed |= rewriteGetter(node, "getLastOffY", "getSmoothLastOffY");
        return changed;
    }

    private static boolean rewriteGetter(ClassNode node, String methodName, String governorMethod) {
        MethodNode m = AsmSupport.findMethod(node, methodName, "()F");
        if (m == null) return false;
        m.instructions.clear();
        InsnList list = new InsnList();
        list.add(new VarInsnNode(Opcodes.ALOAD, 0));
        list.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "com/pzoptimizer/DriveCameraGovernor",
            governorMethod,
            "(Lzombie/iso/PlayerCamera;)F",
            false
        ));
        list.add(new InsnNode(Opcodes.FRETURN));
        m.instructions.add(list);
        return true;
    }
}
