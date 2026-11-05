package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;

/** FirstBakeBudget's prefix on FBORenderCell.renderOneLevel. Names no game type. */
final class FirstBakeBudgetAsm {

    private FirstBakeBudgetAsm() {}

    static boolean patchRenderCell(ClassNode node) {
        MethodNode m = null;
        for (MethodNode mn : node.methods) {
            // renderOneLevel(IsoChunk, int level, PerPlayerRender, int playerIndex, long, Shader, Shader)
            if ("renderOneLevel".equals(mn.name) && mn.desc.startsWith("(Lzombie/iso/IsoChunk;ILzombie/iso/IsoCell$PerPlayerRender;IJ")) {
                m = mn;
                break;
            }
        }
        if (m == null || (m.access & Opcodes.ACC_STATIC) != 0) return false;
        LabelNode go = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pre.add(new VarInsnNode(Opcodes.ILOAD, 2));
        pre.add(new VarInsnNode(Opcodes.ILOAD, 4));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/FirstBakeBudget", "defer", "(Ljava/lang/Object;II)Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, go));
        pre.add(new InsnNode(Opcodes.RETURN));
        pre.add(go);
        m.instructions.insert(pre);
        return true;
    }
}
