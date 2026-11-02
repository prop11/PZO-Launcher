package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;

/**
 * FBORenderChunk.endRenderThread rebuilds a chunk texture's whole mip chain after every bake (11 levels at 1024x1024),
 * but the world composite samples around level 1 even at the widest zoom. So set GL_TEXTURE_MAX_LEVEL before
 * glGenerateMipmap and stop at MAX_LEVEL; anything sampling deeper just clamps. The levels I do build are unchanged.
 */
public final class ChunkMipLimit {

    public static volatile boolean active = true;
    public static volatile int maxLevel = 4;

    private static final int GL_TEXTURE_MAX_LEVEL = 0x813D;
    private static final int STOCK_MAX_LEVEL = 1000;

    private ChunkMipLimit() {}

    // Replaces GL30.glGenerateMipmap(target) in endRenderThread (render thread, chunk texture bound)
    public static void generate(int target) {
        org.lwjgl.opengl.GL11.glTexParameteri(target, GL_TEXTURE_MAX_LEVEL, active ? maxLevel : STOCK_MAX_LEVEL);
        org.lwjgl.opengl.GL30.glGenerateMipmap(target);
    }

    static boolean patchRenderChunk(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "endRenderThread", "()V");
        if (m == null) return false;
        int found = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC && "org/lwjgl/opengl/GL30".equals(mi.owner)
                    && "glGenerateMipmap".equals(mi.name) && "(I)V".equals(mi.desc)) {
                MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/ChunkMipLimit", "generate", "(I)V", false);
                m.instructions.set(mi, call);
                insn = call;
                found++;
            }
        }
        return found == 1;
    }
}
