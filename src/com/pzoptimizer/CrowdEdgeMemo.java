package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import zombie.iso.IsoGridSquare;

/**
 * Settings screen "Crowd push-apart wall memo" (JVM_CrowdEdgeMemo).
 *
 * IsoMovingObject.separate asks current.isBlockedTo(neighbour) for each of the up to 8 neighbour squares holding
 * moving objects, every frame, for every zombie. In a crowd many zombies stand on the same squares and ask the same
 * question; since 42.21 each answer walks four edge tests (wall, window, door, stairs) through method references.
 *
 * Answers are kept in a small direct-mapped table for the current game tick only. Anything that can change a wall,
 * window, door or barricade between two squares reaches PolygonalMap2.squareChanged (the pathfinder has to hear of
 * it) or IsoGridSquare.RecalcAllWithNeighbours; both bump an epoch that invalidates every kept answer, so a door
 * opened halfway through the zombie loop is seen by the next zombie, as before. Both squares are compared by
 * identity, so a square reused for another chunk never matches. Game thread only.
 */
public final class CrowdEdgeMemo {

    public static volatile boolean active = true;
    public static final AtomicLong hits = new AtomicLong();
    public static final AtomicLong misses = new AtomicLong();
    // The memo is only trusted once both reset hooks are in; until then every call is answered live
    static volatile boolean separateHooked, gridSquareHooked, pathfinderHooked;

    private static final int SIZE = 8192;
    private static final int MASK = SIZE - 1;
    // Object[], not IsoGridSquare[]: this class is first touched while IsoMovingObject is being patched, and creating
    // an IsoGridSquare array then would load IsoGridSquare mid-transform and lose all of its own patches
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
        // tick in the high bits, epoch in the low ones: either moving on invalidates the slot
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
