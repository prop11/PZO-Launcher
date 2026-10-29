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
 * Fixes a vanilla freeze in IsoGridSquare.BurnWalls, hit when a randomised burnt building (RBBurnt) generates in a
 * newly loaded chunk, typically while driving into unexplored areas. BurnWalls removes doors, windows and curtains
 * with RemoveTileObject(obj) and then steps its loop index back, assuming the object left the list. For a double
 * door or multi-tile window whose other half is in a chunk that is not loaded yet, the "safe" removal cannot find
 * every part, removes nothing and returns -1, so the loop revisits the same object forever.
 *
 * Inside BurnWalls only, RemoveTileObject is routed here: when the safe removal left the object in place, the
 * object is removed on its own the way RemoveTileObject already does while a chunk is loading. Any other caller of
 * RemoveTileObject is unchanged.
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
