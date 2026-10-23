package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.IntInsnNode;
import com.pzoptimizer.shaded.asm.tree.LdcInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;

/**
 * VBORenderer batch size. VBORenderer is the immediate-mode quad and line batcher behind weather particles, model
 * atlases, shadows, trees, the vision polygon and debug lines. Stock sizes its vertex buffer at 4 KB, so it uploads
 * and draws every ~30 quads; each flush is a buffer upload, an index upload, a shader and attribute set-up and a draw
 * call, which on a weak CPU or driver is most of the render thread in rain or at a wide zoom. The buffers become
 * 1 MB of vertices and 256 KB of indices (the stock 4:1 ratio). Indices stay 16-bit: the smallest vertex format is
 * 28 bytes, so a full buffer holds 37,449 vertices, under the 65,536 an unsigned short can address. Same draws,
 * same order, fewer and larger batches. Applied when the class loads, so it is fixed for the session.
 */
public final class RenderBatchPatches {

    static final int VERTEX_BYTES = 1 << 20;
    static final int INDEX_BYTES = VERTEX_BYTES / 4;
    private static final int MIN_VERTEX_BYTES = 28;

    private RenderBatchPatches() {}

    static boolean patchVboRenderer(ClassNode node) {
        if (VERTEX_BYTES / MIN_VERTEX_BYTES > 0xFFFF) return false;
        MethodNode create = AsmSupport.findMethod(node, "create", "()V");
        MethodNode setFormat = AsmSupport.findMethod(node, mn -> "setFormat".equals(mn.name));
        if (create == null || setFormat == null) return false;
        // create(): elements 4096, indices 1024, vbo 4096L, ibo 1024L; setFormat(): numElements = 4096 / elementSize
        int vertexSites = replaceInt(create, 4096, VERTEX_BYTES) + replaceLong(create, 4096L, VERTEX_BYTES);
        int indexSites = replaceInt(create, 1024, INDEX_BYTES) + replaceLong(create, 1024L, INDEX_BYTES);
        int formatSites = replaceInt(setFormat, 4096, VERTEX_BYTES);
        return vertexSites == 2 && indexSites == 2 && formatSites == 1;
    }

    private static int replaceInt(MethodNode m, int from, int to) {
        int n = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            boolean match = (insn instanceof IntInsnNode i && i.getOpcode() == Opcodes.SIPUSH && i.operand == from)
                || (insn instanceof LdcInsnNode l && l.cst instanceof Integer v && v == from);
            if (match) {
                LdcInsnNode repl = new LdcInsnNode(to);
                m.instructions.set(insn, repl);
                insn = repl;
                n++;
            }
        }
        return n;
    }

    private static int replaceLong(MethodNode m, long from, long to) {
        int n = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LdcInsnNode l && l.cst instanceof Long v && v == from) {
                LdcInsnNode repl = new LdcInsnNode(to);
                m.instructions.set(insn, repl);
                insn = repl;
                n++;
            }
        }
        return n;
    }
}
