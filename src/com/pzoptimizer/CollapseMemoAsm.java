package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;

/** CollapseMemo's patch of FBORenderCutaways.CalculateBuildingsToCollapse. Names no game type, so it is safe to load
 * while FBORenderCutaways is being defined. */
final class CollapseMemoAsm {

    private CollapseMemoAsm() {}

    static boolean patchCutaways(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "CalculateBuildingsToCollapse", "()Z");
        if (m == null) return false;
        String self = "com/pzoptimizer/CollapseMemo";
        int returns = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.IRETURN) {
                InsnList a = new InsnList();
                a.add(new VarInsnNode(Opcodes.ALOAD, 0));
                a.add(new MethodInsnNode(Opcodes.INVOKESTATIC, self, "after", "(Ljava/lang/Object;)V", false));
                m.instructions.insertBefore(insn, a);
                returns++;
            }
        }
        if (returns == 0) return false;
        LabelNode stock = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, self, "skip", "(Ljava/lang/Object;)Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, stock));
        pre.add(new InsnNode(Opcodes.ICONST_0));
        pre.add(new InsnNode(Opcodes.IRETURN));
        pre.add(stock);
        m.instructions.insert(pre);
        return true;
    }
}
