package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.util.concurrent.atomic.AtomicLong;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;

/**
 * Fixes a vanilla freeze in IsoGridSquare.BurnWalls, usually hit when a burnt building (RBBurnt) generates in a new
 * chunk while you drive. BurnWalls removes doors, windows and curtains and steps its index back assuming they left
 * the list. For a double door or multi-tile window whose other half isn't loaded, the safe removal removes nothing
 * and returns -1, so it loops on the same object forever. Inside BurnWalls only, if the object is still there I
 * remove it on its own, the way RemoveTileObject already does while a chunk is loading.
 */
public final class BurnLoopGuard {

    private static volatile boolean active = true;
    public static final AtomicLong forcedRemovals = new AtomicLong();

    private static final String SQUARE = "zombie/iso/IsoGridSquare";
    private static final String REMOVE_DESC = "(Lzombie/iso/IsoObject;)I";

    private BurnLoopGuard() {}

    public static void setActive(boolean value) {
        active = value;
    }

    public static int removeTileObject(IsoGridSquare square, IsoObject obj) {
        int r = square.RemoveTileObject(obj);
        if (active && obj != null && GameCompat.objects(square).contains(obj)) {
            forcedRemovals.incrementAndGet();
            r = square.RemoveTileObject(obj, false);
        }
        return r;
    }

    static boolean patch(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "BurnWalls", "(ZZ)V");
        if (m == null) {
            return false;
        }
        int n = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKEVIRTUAL && SQUARE.equals(mi.owner)
                    && "RemoveTileObject".equals(mi.name) && REMOVE_DESC.equals(mi.desc)) {
                MethodInsnNode repl = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/BurnLoopGuard",
                    "removeTileObject", "(L" + SQUARE + ";Lzombie/iso/IsoObject;)I", false);
                m.instructions.set(mi, repl);
                insn = repl;
                n++;
            }
        }
        return n > 0;
    }
}
