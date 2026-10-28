package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;

/**
 * Thread-safe, zero-allocation IsoDirections.ToVector() optimization.
 *
 * In vanilla 42.21, ToVector() used a single shared mutable Vector2, causing thread-race crashes between main and
 * render threads. In 42.22, vanilla changed this to return new Vector2(), allocating a new heap object on every call.
 *
 * PZO provides a per-thread cache of 8 Vector2 instances (one per cardinal/intercardinal direction). Calls to ToVector()
 * populate and return the calling thread's dedicated vector instance, eliminating all heap allocations while remaining
 * completely thread-safe across all game, render, and worker threads.
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

    /** Compatibility stub for legacy reflection/callers. */
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
