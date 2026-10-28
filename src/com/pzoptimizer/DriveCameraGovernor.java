package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import zombie.iso.PlayerCamera;

/**
 * Eliminates 1-pixel stair-stepping judder during camera movement (especially when driving)
 * by preserving subpixel floating-point coordinates instead of casting to whole integers.
 */
public final class DriveCameraGovernor {

    public static volatile boolean active = true;

    private DriveCameraGovernor() {}

    public static float getSmoothOffX(PlayerCamera cam) {
        if (!active || cam == null) {
            return (int) (cam != null ? cam.offX + cam.rightClickX : 0.0f);
        }
        float raw = cam.offX + cam.rightClickX;
        float zoom = cam.zoom;
        if (zoom < 0.999f && zoom > 0.01f) {
            return zoom * (int) (raw / zoom);
        }
        return raw;
    }

    public static float getSmoothOffY(PlayerCamera cam) {
        if (!active || cam == null) {
            return (int) (cam != null ? cam.offY + cam.rightClickY : 0.0f);
        }
        float raw = cam.offY + cam.rightClickY;
        float zoom = cam.zoom;
        if (zoom < 0.999f && zoom > 0.01f) {
            return zoom * (int) (raw / zoom);
        }
        return raw;
    }

    public static float getSmoothLastOffX(PlayerCamera cam) {
        if (!active || cam == null) {
            return (int) (cam != null ? cam.lastOffX + cam.rightClickX : 0.0f);
        }
        float raw = cam.lastOffX + cam.rightClickX;
        float zoom = cam.zoom;
        if (zoom < 0.999f && zoom > 0.01f) {
            return zoom * (int) (raw / zoom);
        }
        return raw;
    }

    public static float getSmoothLastOffY(PlayerCamera cam) {
        if (!active || cam == null) {
            return (int) (cam != null ? cam.lastOffY + cam.rightClickY : 0.0f);
        }
        float raw = cam.lastOffY + cam.rightClickY;
        float zoom = cam.zoom;
        if (zoom < 0.999f && zoom > 0.01f) {
            return zoom * (int) (raw / zoom);
        }
        return raw;
    }

    /**
     * ASM transformer for zombie.iso.PlayerCamera: routes integer-truncated offset getters
     * to DriveCameraGovernor subpixel float getters.
     */
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
