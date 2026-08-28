package com.pzoptimizer;

public class VectorPool {
    private static final int POOL_SIZE = 64;

    private static final ThreadLocal<PoolState> THREAD_POOL = ThreadLocal.withInitial(PoolState::new);

    private static class PoolState {
        final float[] x = new float[POOL_SIZE];
        final float[] y = new float[POOL_SIZE];
        int index = 0;
    }

    public static int allocate(float px, float py) {
        PoolState state = THREAD_POOL.get();
        int idx = state.index;
        state.x[idx] = px;
        state.y[idx] = py;
        state.index = (state.index + 1) % POOL_SIZE;
        return idx;
    }

    public static float getX(int handle) {
        return THREAD_POOL.get().x[handle];
    }

    public static float getY(int handle) {
        return THREAD_POOL.get().y[handle];
    }
}
