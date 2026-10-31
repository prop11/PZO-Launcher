package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;

/**
 * Settings screen "Faster per-thread lookups" (JVM_ThreadLocalFastPath).
 *
 * GameProfiler.getInstance() and IsoDepthHelper's depth results are ThreadLocals read many thousand times a frame
 * (every profiled block, every tile drawn), about 1.3% of the game thread. Neither ThreadLocal is ever set or removed,
 * so a thread's value never changes after its first read: the game thread's value is kept in a slot and handed back
 * after a single thread comparison. Other threads take the normal ThreadLocal path.
 */
public final class ThreadLocalFastPath {

    public static volatile boolean active = true;

    private static final class Slot {
        final Thread thread;
        final Object value;

        Slot(Thread thread, Object value) {
            this.thread = thread;
            this.value = value;
        }
    }

    private static volatile Slot profiler, depth;

    private ThreadLocalFastPath() {}

    /** Replaces s_instance.get() in GameProfiler.getInstance(). */
    public static Object profiler(ThreadLocal<?> tl) {
        Thread cur = Thread.currentThread();
        Slot s = profiler;
        if (s != null && s.thread == cur && active) return s.value;
        Object v = tl.get();
        if (s == null && isGameThread(cur)) profiler = new Slot(cur, v);
        return v;
    }

    /** Replaces results.get() in IsoDepthHelper. */
    public static Object depth(ThreadLocal<?> tl) {
        Thread cur = Thread.currentThread();
        Slot s = depth;
        if (s != null && s.thread == cur && active) return s.value;
        Object v = tl.get();
        if (s == null && isGameThread(cur)) depth = new Slot(cur, v);
        return v;
    }

    // By name: GameProfiler is used before GameWindow (which holds the game thread) may be initialised
    private static boolean isGameThread(Thread t) {
        return "MainThread".equals(t.getName());
    }

    static boolean patchProfiler(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "getInstance", "()Lzombie/GameProfiler;");
        return m != null && AsmSupport.redirectCalls(m, "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;",
            "com/pzoptimizer/ThreadLocalFastPath", "profiler", "(Ljava/lang/ThreadLocal;)Ljava/lang/Object;") == 1;
    }

    static boolean patchDepthHelper(ClassNode node) {
        int n = 0;
        for (MethodNode m : node.methods) {
            n += AsmSupport.redirectCalls(m, "java/lang/ThreadLocal", "get", "()Ljava/lang/Object;",
                "com/pzoptimizer/ThreadLocalFastPath", "depth", "(Ljava/lang/ThreadLocal;)Ljava/lang/Object;");
        }
        return n > 0;
    }
}
