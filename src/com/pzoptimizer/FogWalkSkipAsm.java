package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;

/** FogWalkSkip's patch of FBORenderCell.renderFog. Names no game type. */
final class FogWalkSkipAsm {

    private FogWalkSkipAsm() {}

    static boolean patchRenderCell(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "renderFog", "(I)V");
        if (m == null) return false;
        boolean feedsFog = false;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && "zombie/iso/weather/fog/ImprovedFog".equals(mi.owner) && "renderRowsBehind".equals(mi.name)) {
                feedsFog = true;
            }
        }
        if (!feedsFog) return false;
        int n = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD && "onScreenChunks".equals(f.name)
                    && "Ljava/util/ArrayList;".equals(f.desc)) {
                MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/FogWalkSkip", "chunks",
                    "(Ljava/util/ArrayList;)Ljava/util/ArrayList;", false);
                m.instructions.insert(f, call);
                insn = call;
                n++;
            }
        }
        // The loop's size() and get(i) reads
        return n == 2;
    }
}
