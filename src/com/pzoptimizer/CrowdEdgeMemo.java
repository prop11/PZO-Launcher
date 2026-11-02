package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import zombie.iso.IsoGridSquare;

/**
 * Memoises wall checks for the crowd push-apart (JVM_CrowdEdgeMemo).
 *
 * IsoMovingObject.separate calls isBlockedTo for up to 8 neighbour squares, every frame, for every zombie. In a crowd
 * lots of zombies stand on the same squares asking the same thing, and since 42.21 each answer is four edge tests
 * through method references.
 *
 * Answers are kept for the current tick only, in a small direct-mapped table. Anything that changes a wall, window,
 * door or barricade goes through PolygonalMap2.squareChanged or IsoGridSquare.RecalcAllWithNeighbours, and both bump an
 * epoch that throws every answer away, so a door opened mid-loop is seen by the next zombie like before. Squares are
 * compared by identity, so a square reused for another chunk never matches. Game thread only.
 */
public final class CrowdEdgeMemo {

    public static volatile boolean active = true;
    public static final AtomicLong hits = new AtomicLong();
    public static final AtomicLong misses = new AtomicLong();
    // Only trusted once both reset hooks are in; until then everything is answered live
    static volatile boolean separateHooked, gridSquareHooked, pathfinderHooked;

    private static final int SIZE = 8192;
    private static final int MASK = SIZE - 1;
    // Object[], not IsoGridSquare[]: this class is first touched while IsoMovingObject is being patched, and making an
    // IsoGridSquare array then loads IsoGridSquare mid-transform and it loses all its own patches
    private static final Object[] from = new Object[SIZE];
    private static final Object[] to = new Object[SIZE];
    private static final long[] stamp = new long[SIZE];
    private static final boolean[] answer = new boolean[SIZE];
    // Bumped from any thread that changes a square (chunk loaders included)
    private static final AtomicInteger epoch = new AtomicInteger();

    private CrowdEdgeMemo() {}

    public static void invalidate(Object ignored) {
        epoch.incrementAndGet();
    }

    /** Replaces from.isBlockedTo(to) inside IsoMovingObject.separate. */
    public static boolean blocked(IsoGridSquare a, IsoGridSquare b) {
        if (!active || b == null || !gridSquareHooked || !pathfinderHooked || !FrameStamp.onGameThread()) return a.isBlockedTo(b);
        long tick = FrameStamp.tick();
        if (tick < 0) return a.isBlockedTo(b);
        // Tick in the high bits, epoch in the low ones; either one moving on invalidates the slot
        long key = (tick << 32) | (epoch.get() & 0xFFFFFFFFL);
        int dir = (b.x - a.x + 1) * 3 + (b.y - a.y + 1);
        int h = (a.x * 0x9E3779B1) ^ (a.y * 0x85EBCA77) ^ (a.z * 0xC2B2AE3D) ^ (dir * 0x27D4EB2F);
        int i = (h ^ (h >>> 15)) & MASK;
        if (from[i] == a && to[i] == b && stamp[i] == key) {
            hits.incrementAndGet();
            return answer[i];
        }
        boolean r = a.isBlockedTo(b);
        from[i] = a;
        to[i] = b;
        stamp[i] = key;
        answer[i] = r;
        misses.incrementAndGet();
        return r;
    }

    static boolean patchMovingObject(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "separate", "()V");
        if (m == null) return false;
        return AsmSupport.redirectCalls(m, "zombie/iso/IsoGridSquare", "isBlockedTo", "(Lzombie/iso/IsoGridSquare;)Z",
            "com/pzoptimizer/CrowdEdgeMemo", "blocked", "(Lzombie/iso/IsoGridSquare;Lzombie/iso/IsoGridSquare;)Z") > 0;
    }

    static boolean patchPathfinder(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "squareChanged", "(Lzombie/iso/IsoGridSquare;)V");
        if (m == null) return false;
        AsmSupport.prependCall(m, "com/pzoptimizer/CrowdEdgeMemo", "invalidate", "(Ljava/lang/Object;)V");
        return true;
    }

    static boolean patchGridSquare(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "RecalcAllWithNeighbours", "(ZLzombie/iso/IsoGridSquare$GetSquare;)V");
        if (m == null) return false;
        AsmSupport.prependCall(m, "com/pzoptimizer/CrowdEdgeMemo", "invalidate", "(Ljava/lang/Object;)V");
        return true;
    }
}
