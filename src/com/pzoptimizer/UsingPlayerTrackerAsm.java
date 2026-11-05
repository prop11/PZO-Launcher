package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldInsnNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;

/** The bytecode patches for UsingPlayerTracker. They live apart because UsingPlayerTracker refers to the game class being patched, and
 * loading it while that class is defined made the JVM define it twice (LinkageError at startup, 2026-11-05). */
final class UsingPlayerTrackerAsm {

    // Set by the agent once GameEntity really carries the hook; without it the stock walk has to run
    static volatile boolean entityHooked;

    private UsingPlayerTrackerAsm() {}


    /** GameEntity: note the entity after each putfield of usingPlayer. */
    static boolean patchGameEntity(ClassNode node) {
        int n = 0;
        for (MethodNode m : node.methods) {
            if ((m.access & Opcodes.ACC_STATIC) != 0) continue;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD && "usingPlayer".equals(fi.name)
                        && "zombie/entity/GameEntity".equals(fi.owner)) {
                    InsnList after = new InsnList();
                    after.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/UsingPlayerTracker", "note",
                        "(Lzombie/entity/GameEntity;)V", false);
                    after.add(call);
                    m.instructions.insert(fi, after);
                    insn = call;
                    n++;
                }
            }
        }
        // setUsingPlayer, reset and receiveUpdateUsingPlayer at least; without the setter the tracking would be blind
        return n >= 2;
    }

    /** UsingPlayerUpdateSystem.update(): return early when the tracker handled it. */
    static boolean patchSystem(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "update", "()V");
        if (m == null) return false;
        LabelNode go = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/UsingPlayerTracker", "update", "()Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, go));
        pre.add(new InsnNode(Opcodes.RETURN));
        pre.add(go);
        m.instructions.insert(pre);
        return true;
    }
}
