package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldInsnNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;

/**
 * Asks the lighting engine about a square at most once per lighting tick (JVM_LightInfoOnce).
 *
 * JNILighting.updateFBORenderChunk skips its native calls once updateTick equals this tick's counter, but it only
 * records the tick when the square was dirty and got re-lit. A square that wasn't dirty ran the native getSquareDirty
 * again on every light lookup, and the renderer looks up the same squares several times a frame (cut-away prep, bakes,
 * neighbour squares, characters). Within one counter value the answer can't change, so the not-dirty branch now
 * records the tick too. Squares that were never lit (updateTick -1) are left alone: stock re-bakes their level on the
 * first real fetch, and skipping that would leave them black.
 *
 * No game type is named here, so loading this class while JNILighting is being defined is safe.
 */
public final class LightInfoOnce {

    public static volatile boolean active = true;

    private LightInfoOnce() {}

    static boolean patchLighting(ClassNode node) {
        String owner = "zombie/iso/LightingJNI$JNILighting";
        if (!owner.equals(node.name)) return false;
        MethodNode m = AsmSupport.findMethod(node, "updateFBORenderChunk", "()V");
        if (m == null) return false;
        int n = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC && owner.equals(f.owner)
                    && "notDirty".equals(f.name) && "I".equals(f.desc)) {
                LabelNode skip = new LabelNode();
                InsnList c = new InsnList();
                c.add(new FieldInsnNode(Opcodes.GETSTATIC, "com/pzoptimizer/LightInfoOnce", "active", "Z"));
                c.add(new JumpInsnNode(Opcodes.IFEQ, skip));
                c.add(new VarInsnNode(Opcodes.ALOAD, 0));
                c.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "updateTick", "I"));
                c.add(new InsnNode(Opcodes.ICONST_M1));
                c.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, skip));
                c.add(new VarInsnNode(Opcodes.ALOAD, 0));
                c.add(new FieldInsnNode(Opcodes.GETSTATIC, "zombie/iso/LightingJNI", "updateCounter", "[I"));
                c.add(new VarInsnNode(Opcodes.ALOAD, 0));
                c.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "playerIndex", "I"));
                c.add(new InsnNode(Opcodes.IALOAD));
                c.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, "updateTick", "I"));
                c.add(skip);
                m.instructions.insert(f, c);
                insn = skip;
                n++;
            }
        }
        return n == 1;
    }
}
