package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;

/**
 * IsoDirections.ToVector() without the race or the garbage. In 42.21 it handed out one shared mutable Vector2, which
 * crashed when the main and render threads raced on it; 42.22 fixed that by allocating a new Vector2 every call. I
 * keep 8 vectors per thread (one per direction) and hand back the caller's own, so no allocations and no races.
 */
public final class DirectionVectorFix {

    public static volatile boolean active = true;

    // Per-thread cache: 8 vectors, one per IsoDirections ordinal
    private static final ThreadLocal<zombie.iso.Vector2[]> THREAD_VECTORS = ThreadLocal.withInitial(() -> {
        zombie.iso.Vector2[] arr = new zombie.iso.Vector2[8];
        for (int i = 0; i < 8; i++) {
            arr[i] = new zombie.iso.Vector2();
        }
        return arr;
    });

    private DirectionVectorFix() {}

    /** Kept for old reflection callers. */
    public static boolean useShared() {
        return true;
    }

    /** Dispatches ToVector() to the per-thread vector cache. */
    public static zombie.iso.Vector2 toVector(zombie.iso.IsoDirections dir) {
        if (dir == null) return new zombie.iso.Vector2();
        if (!active) return dir.ToVector(new zombie.iso.Vector2());
        int ord = dir.ordinal() & 7;
        zombie.iso.Vector2 v = THREAD_VECTORS.get()[ord];
        return dir.ToVector(v);
    }

    /** Prefixes IsoDirections.ToVector() to route through toVector(this). */
    static boolean patchIsoDirections(ClassNode node) {
        String vec = "zombie/iso/Vector2";
        MethodNode m = AsmSupport.findMethod(node, "ToVector", "()L" + vec + ";");
        if (m == null || AsmSupport.findMethod(node, "ToVector", "(L" + vec + ";)L" + vec + ";") == null) return false;
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/DirectionVectorFix", "toVector",
            "(Lzombie/iso/IsoDirections;)Lzombie/iso/Vector2;", false));
        pre.add(new InsnNode(Opcodes.ARETURN));
        m.instructions.insert(pre);
        return true;
    }
}
