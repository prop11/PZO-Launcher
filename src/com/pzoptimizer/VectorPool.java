package com.pzoptimizer;

/**
 * Thread-Local Vector & Coordinate Pool.
 * Eliminates temporary 3D/2D vector allocations in high-frequency raycasting,
 * horde spatial queries, and line-of-sight math.
 */
public class VectorPool {

    public static class Vec2 {
        public float x;
        public float y;

        public Vec2 set(float x, float y) {
            this.x = x;
            this.y = y;
            return this;
        }

        public float lengthSq() {
            return x * x + y * y;
        }
    }

    private static final ThreadLocal<Vec2[]> POOL = ThreadLocal.withInitial(() -> {
        Vec2[] array = new Vec2[32];
        for (int i = 0; i < array.length; i++) {
            array[i] = new Vec2();
        }
        return array;
    });

    private static final ThreadLocal<int[]> INDEX = ThreadLocal.withInitial(() -> new int[]{0});

    public static Vec2 get(float x, float y) {
        Vec2[] array = POOL.get();
        int[] idx = INDEX.get();
        int slot = idx[0];
        idx[0] = (slot + 1) & 31;
        return array[slot].set(x, y);
    }
}
