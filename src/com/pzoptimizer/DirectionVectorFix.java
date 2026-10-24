package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.TypeInsnNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;

/**
 * IsoDirections.ToVector() copies the direction into one static Vector2 and returns it. The game thread uses it
 * (zombie spawns: setForwardDirection(dir.ToVector())) while the render thread uses it for corpses
 * (FBORenderCorpses, DeadBodyAtlas); two interleaved copies can leave x from one direction and y from another, e.g.
 * (0, 0) from N and E, and setForwardDirection then throws "Forward Direction cannot be zero length vector". The game
 * thread keeps the shared vector exactly as stock; any other thread gets a vector of its own.
 */
public final class DirectionVectorFix {

    public static volatile boolean active = true;
    private static volatile Thread gameThread;

    private DirectionVectorFix() {}

    public static boolean useShared() {
        if (!active) return true;
        Thread t = Thread.currentThread();
        Thread g = gameThread;
        if (g == null) {
            String n = t.getName();
            if ("MainThread".equals(n) || "PZO-MainSimulationThread".equals(n)) {
                gameThread = t;
                return true;
            }
        }
        return t == g;
    }

    /** Prefixes ToVector() with: if (!useShared()) return this.ToVector(new Vector2()). */
    static boolean patchIsoDirections(ClassNode node) {
        String vec = "zombie/iso/Vector2";
        MethodNode m = AsmSupport.findMethod(node, "ToVector", "()L" + vec + ";");
        if (m == null || AsmSupport.findMethod(node, "ToVector", "(L" + vec + ";)L" + vec + ";") == null) return false;
        InsnList pre = new InsnList();
        LabelNode stock = new LabelNode();
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/DirectionVectorFix", "useShared", "()Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFNE, stock));
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pre.add(new TypeInsnNode(Opcodes.NEW, vec));
        pre.add(new InsnNode(Opcodes.DUP));
        pre.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, vec, "<init>", "()V", false));
        pre.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "ToVector", "(L" + vec + ";)L" + vec + ";", false));
        pre.add(new InsnNode(Opcodes.ARETURN));
        pre.add(stock);
        m.instructions.insert(pre);
        return true;
    }
}
