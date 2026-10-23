package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FliesSound$ChunkLevelData.update moves a player's corpse-flies emitter: it clears hasFlies on the emitter's old
 * square, then sets it on the new one. Almost always the square is the same, and setHasFlies invalidates the chunk
 * level on every change, so each update (re-run for the chunks around the player whenever a chunk loads, i.e.
 * continuously while driving) re-bakes a level next to the player twice. Here the clear is held back and only
 * applied if the square that then gets the flies is a different one; the final state is the same as stock.
 */
public final class FliesSoundFix {

    public static volatile boolean active = true;
    public static final AtomicLong togglesAvoided = new AtomicLong();

    // Game thread only: the square whose clear is pending between the two calls of one update
    private static Object pendingClear;

    private FliesSoundFix() {}

    // Replaces fadeEmitter.sq.setHasFlies(false) before the emitter moves
    public static void deferClear(Object square, boolean value) {
        if (!active || value || square == null) {
            ((zombie.iso.IsoGridSquare) square).setHasFlies(value);
            return;
        }
        pendingClear = square;
    }

    // Replaces soundSq.setHasFlies(true)
    public static void setAfterMove(Object square, boolean value) {
        Object pending = pendingClear;
        pendingClear = null;
        if (pending != null) {
            if (pending == square && value) {
                togglesAvoided.incrementAndGet();
            } else {
                ((zombie.iso.IsoGridSquare) pending).setHasFlies(false);
            }
        }
        ((zombie.iso.IsoGridSquare) square).setHasFlies(value);
    }

    static boolean patchChunkLevelData(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "update", "(IIILzombie/characters/IsoPlayer;)V");
        if (m == null) return false;
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKEVIRTUAL && "zombie/iso/IsoGridSquare".equals(mi.owner)
                    && "setHasFlies".equals(mi.name) && "(Z)V".equals(mi.desc)) {
                calls.add(mi);
            }
        }
        // Stock order: the clear of the old square, the set on the new square, the clear when the flies fade out
        if (calls.size() != 3) return false;
        m.instructions.set(calls.get(0), new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/FliesSoundFix", "deferClear",
            "(Ljava/lang/Object;Z)V", false));
        m.instructions.set(calls.get(1), new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/FliesSoundFix", "setAfterMove",
            "(Ljava/lang/Object;Z)V", false));
        return true;
    }
}
