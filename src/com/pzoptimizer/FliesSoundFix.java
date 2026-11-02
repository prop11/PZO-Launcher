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
 * FliesSound$ChunkLevelData.update moves a player's corpse-flies emitter by clearing hasFlies on the old square and
 * setting it on the new one. It's nearly always the same square, and setHasFlies invalidates the chunk level on every
 * change, so each update (which re-runs for nearby chunks whenever one loads, i.e. constantly while driving) re-bakes a
 * level next to the player twice. I hold the clear back and only apply it if the flies end up on a different square.
 * Same end state as stock.
 */
public final class FliesSoundFix {

    public static volatile boolean active = true;
    public static final AtomicLong togglesAvoided = new AtomicLong();

    // Game thread only: the square with a pending clear, between the two calls of one update
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
        // Stock order: clear the old square, set the new one, clear again when the flies fade out
        if (calls.size() != 3) return false;
        m.instructions.set(calls.get(0), new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/FliesSoundFix", "deferClear",
            "(Ljava/lang/Object;Z)V", false));
        m.instructions.set(calls.get(1), new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/FliesSoundFix", "setAfterMove",
            "(Ljava/lang/Object;Z)V", false));
        return true;
    }
}
