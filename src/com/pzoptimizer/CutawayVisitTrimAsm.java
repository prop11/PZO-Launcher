package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;

/** CutawayVisitTrim's patch of FBORenderCutaways.doCutawayVisitSquares(playerIndex, chunks). Names no game type. */
final class CutawayVisitTrimAsm {

    private CutawayVisitTrimAsm() {}

    static boolean patchCutaways(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "doCutawayVisitSquares", "(ILjava/util/ArrayList;)V");
        if (m == null) return false;
        int n = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && node.name.equals(mi.owner) && "doCutawayVisitSquares".equals(mi.name)
                    && "(Lzombie/iso/IsoGridSquare;JLjava/util/ArrayList;)V".equals(mi.desc)
                    && (mi.getOpcode() == Opcodes.INVOKESPECIAL || mi.getOpcode() == Opcodes.INVOKEVIRTUAL)) {
                MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/CutawayVisitTrim", "visit",
                    "(Ljava/lang/Object;Ljava/lang/Object;JLjava/lang/Object;)V", false);
                m.instructions.set(mi, call);
                insn = call;
                n++;
            }
        }
        if (n != 1) return false;
        m.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/CutawayVisitTrim", "begin", "()V", false));
        return true;
    }
}
