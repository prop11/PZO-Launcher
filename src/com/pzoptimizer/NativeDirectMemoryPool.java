package com.pzoptimizer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Ring of pre-allocated 256 KB direct buffers for texture loading and world streaming. */
public final class NativeDirectMemoryPool {

    private static final int BUFFER_CAPACITY = 262144; // 256 KB per page-aligned buffer
    private static final int INITIAL_POOL_SIZE = EngineGLStateGovernor.isMobileGLEnvironment() ? 6 : 16;
    private static final ConcurrentLinkedQueue<ByteBuffer> pool = new ConcurrentLinkedQueue<>();

    static {
        for (int i = 0; i < INITIAL_POOL_SIZE; i++) {
            ByteBuffer buf = ByteBuffer.allocateDirect(BUFFER_CAPACITY).order(ByteOrder.nativeOrder());
            pool.offer(buf);
        }
    }

    public static void initialize() {
        PZOLogger.success("NativeDirectMemoryPool: " + INITIAL_POOL_SIZE + "x 256KB Off-Heap Pinned Direct Buffer Ring Initialized");
    }

    public static ByteBuffer acquireBuffer() {
        ByteBuffer buf = pool.poll();
        if (buf == null) {
            buf = ByteBuffer.allocateDirect(BUFFER_CAPACITY).order(ByteOrder.nativeOrder());
        }
        buf.clear();
        return buf;
    }

    public static void releaseBuffer(ByteBuffer buf) {
        if (buf != null && buf.isDirect() && buf.capacity() == BUFFER_CAPACITY) {
            buf.clear();
            pool.offer(buf);
        }
    }
}
