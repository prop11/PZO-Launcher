package com.pzoptimizer;


/**
 * Vanilla's animation code keeps its scratch vectors and matrices in static holder classes (AnimationPlayer$L_*),
 * shared by every caller, and HelperFunctions.getMatrix hands out matrices from one spin-locked stack. That is fine
 * on one thread; on several it would corrupt results or serialise them on the lock. AnimationThreadingPatches sends
 * every read of an L_* scratch field through get(original, slot): the game thread and any other thread get the original
 * object as before, a FrameWorkers.Worker gets its own copy, made once from the original. HelperFunctions'
 * matrix stack is likewise per worker. Nothing changes for code that never runs on a worker.
 */
public final class PerThreadScratch {

    static final int MAX_SLOTS = 96;

    private PerThreadScratch() {}

    public static Object get(Object original, int slot) {
        if (!(Thread.currentThread() instanceof FrameWorkers.Worker w)) return original;
        Object o = w.scratch[slot];
        if (o == null) {
            o = copyOf(original);
            w.scratch[slot] = o;
        }
        return o;
    }

    private static Object copyOf(Object o) {
        if (o instanceof org.lwjgl.util.vector.Matrix4f m) return new org.lwjgl.util.vector.Matrix4f().load(m);
        if (o instanceof org.lwjgl.util.vector.Vector3f v) return new org.lwjgl.util.vector.Vector3f(v);
        if (o instanceof org.lwjgl.util.vector.Vector4f v) return new org.lwjgl.util.vector.Vector4f(v);
        if (o instanceof org.lwjgl.util.vector.Quaternion q) return new org.lwjgl.util.vector.Quaternion(q);
        if (o instanceof zombie.core.skinnedmodel.animation.Keyframe k) {
            return new zombie.core.skinnedmodel.animation.Keyframe(new org.lwjgl.util.vector.Vector3f(k.position),
                new org.lwjgl.util.vector.Quaternion(k.rotation), new org.lwjgl.util.vector.Vector3f(k.scale));
        }
        if (o instanceof java.util.ArrayList) return new java.util.ArrayList<>();
        throw new IllegalStateException("no per-thread copy for scratch type " + (o == null ? "null" : o.getClass().getName()));
    }

    /** A matrix from this worker's own stack, or null on any other thread (the caller then uses vanilla's stack). */
    public static Object workerMatrix() {
        if (!(Thread.currentThread() instanceof FrameWorkers.Worker w)) return null;
        Object m = w.matrices.poll();
        return m != null ? m : new org.lwjgl.util.vector.Matrix4f();
    }

    public static boolean returnWorkerMatrix(Object m) {
        if (!(Thread.currentThread() instanceof FrameWorkers.Worker w)) return false;
        w.matrices.push(m);
        return true;
    }
}
