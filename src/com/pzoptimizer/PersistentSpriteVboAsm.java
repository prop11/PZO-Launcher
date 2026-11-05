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

/** The bytecode patches for PersistentSpriteVbo. They live apart because PersistentSpriteVbo refers to the game class being patched, and
 * loading it while that class is defined made the JVM define it twice (LinkageError at startup, 2026-11-05). */
final class PersistentSpriteVboAsm {

    private PersistentSpriteVboAsm() {}


    static boolean patchBufferObject(ClassNode node) {
        String self = "com/pzoptimizer/PersistentSpriteVbo";
        String vboDesc = "(Lzombie/core/VBO/GLVertexBufferObject;)";
        MethodNode map = AsmSupport.findMethod(node, "map", "()Ljava/nio/ByteBuffer;");
        MethodNode unmap = AsmSupport.findMethod(node, "unmap", "()Z");
        MethodNode clear = AsmSupport.findMethod(node, "clear", "()V");
        MethodNode destroy = AsmSupport.findMethod(node, "doDestroy", "()V");
        if (map == null || unmap == null || clear == null) return false;

        LabelNode stock = new LabelNode();
        InsnList m = new InsnList();
        m.add(new VarInsnNode(Opcodes.ALOAD, 0));
        m.add(new MethodInsnNode(Opcodes.INVOKESTATIC, self, "map", vboDesc + "Ljava/nio/ByteBuffer;", false));
        m.add(new InsnNode(Opcodes.DUP));
        m.add(new JumpInsnNode(Opcodes.IFNULL, stock));
        m.add(new InsnNode(Opcodes.ARETURN));
        m.add(stock);
        m.add(new InsnNode(Opcodes.POP));
        map.instructions.insert(m);

        LabelNode stockUnmap = new LabelNode();
        InsnList u = new InsnList();
        u.add(new VarInsnNode(Opcodes.ALOAD, 0));
        u.add(new MethodInsnNode(Opcodes.INVOKESTATIC, self, "unmap", vboDesc + "Z", false));
        u.add(new JumpInsnNode(Opcodes.IFEQ, stockUnmap));
        u.add(new InsnNode(Opcodes.ICONST_1));
        u.add(new InsnNode(Opcodes.IRETURN));
        u.add(stockUnmap);
        unmap.instructions.insert(u);

        LabelNode stockClear = new LabelNode();
        InsnList c = new InsnList();
        c.add(new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, self, "keep", vboDesc + "Z", false));
        c.add(new JumpInsnNode(Opcodes.IFEQ, stockClear));
        c.add(new InsnNode(Opcodes.RETURN));
        c.add(stockClear);
        clear.instructions.insert(c);

        if (destroy != null) {
            InsnList d = new InsnList();
            d.add(new VarInsnNode(Opcodes.ALOAD, 0));
            d.add(new MethodInsnNode(Opcodes.INVOKESTATIC, self, "destroyed", vboDesc + "V", false));
            destroy.instructions.insert(d);
        }
        return true;
    }

    /** SpriteRenderer$RingBuffer.create(): register the ring right before it returns. */
    static boolean patchRingBuffer(ClassNode node) {
        MethodNode create = AsmSupport.findMethod(node, "create", "()V");
        if (create == null) return false;
        boolean hasVbo = false, hasIbo = false;
        for (var f : node.fields) {
            if ("vbo".equals(f.name) && "[Lzombie/core/VBO/GLVertexBufferObject;".equals(f.desc)) hasVbo = true;
            if ("ibo".equals(f.name) && "[Lzombie/core/VBO/GLVertexBufferObject;".equals(f.desc)) hasIbo = true;
        }
        if (!hasVbo || !hasIbo) return false;
        int n = 0;
        for (AbstractInsnNode insn = create.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                InsnList r = new InsnList();
                r.add(new VarInsnNode(Opcodes.ALOAD, 0));
                r.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "vbo", "[Lzombie/core/VBO/GLVertexBufferObject;"));
                r.add(new VarInsnNode(Opcodes.ALOAD, 0));
                r.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "ibo", "[Lzombie/core/VBO/GLVertexBufferObject;"));
                r.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/PersistentSpriteVbo", "register",
                    "([Lzombie/core/VBO/GLVertexBufferObject;[Lzombie/core/VBO/GLVertexBufferObject;)V", false));
                create.instructions.insertBefore(insn, r);
                n++;
            }
        }
        return n > 0;
    }
}
