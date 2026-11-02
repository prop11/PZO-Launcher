package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.IntInsnNode;
import com.pzoptimizer.shaded.asm.tree.LdcInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;

/**
 * Bigger VBORenderer batches. VBORenderer is the immediate-mode quad and line batcher behind weather particles, model
 * atlases, shadows, trees, the vision polygon and debug lines. Stock sizes its vertex buffer at 4 KB, so it uploads and
 * draws every ~30 quads, and every flush is a buffer upload, an index upload, shader and attribute setup and a draw
 * call. On a weak CPU or driver that's most of the render thread in rain or at a wide zoom. Now it's 1 MB of vertices
 * and 256 KB of indices (same 4:1 ratio). Indices stay 16-bit: the smallest vertex format is 28 bytes, so a full buffer
 * is 37,449 vertices, under the 65,536 a short can address. Same draws in the same order, just fewer and bigger
 * batches. Set when the class loads, so it's fixed for the session.
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
