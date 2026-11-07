package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;

/**
 * Faster per-thread lookups (JVM_ThreadLocalFastPath).
 *
 * GameProfiler.getInstance() and IsoDepthHelper's depth results are ThreadLocals read many thousands of times a frame
 * (every profiled block, every tile drawn), about 1.3% of the game thread. Neither one is ever set or removed, so a
 * thread's value never changes after the first read. I keep the game thread's value in a slot and hand it back after a
 * single thread compare; other threads take the normal path.
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

    // By name, because GameProfiler gets used before GameWindow (which holds the game thread) may be initialised.
    // EngineThreadGovernor renames it once the main loop runs, so the depth slot (first read in game) needs both.
    private static boolean isGameThread(Thread t) {
        String n = t.getName();
        return "MainThread".equals(n) || "PZO-MainSimulationThread".equals(n);
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
