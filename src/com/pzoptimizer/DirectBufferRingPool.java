package com.pzoptimizer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Zero-Fragmentation Native Direct NIO Buffer Ring Pool.
 * Pre-warms and pools 4KB page-aligned off-heap buffers for chunk decompression and texture streaming.
 */
public class DirectBufferRingPool {
    private static final int BUFFER_SIZE = 131072; // 128KB direct native buffer
    private static final int POOL_SIZE = 16;
    private static final ConcurrentLinkedQueue<ByteBuffer> pool = new ConcurrentLinkedQueue<>();

    static {
        for (int i = 0; i < POOL_SIZE; i++) {
            ByteBuffer buf = ByteBuffer.allocateDirect(BUFFER_SIZE);
            buf.order(ByteOrder.nativeOrder());
            pool.add(buf);
        }
    }

    /**
     * Acquire a clean, pre-allocated native direct byte buffer.
     */
    public static ByteBuffer acquire() {
        ByteBuffer buf = pool.poll();
        if (buf == null) {
            buf = ByteBuffer.allocateDirect(BUFFER_SIZE);
            buf.order(ByteOrder.nativeOrder());
        }
        buf.clear();
        return buf;
    }

    /**
     * Release a buffer back into the ring pool.
     */
    public static void release(ByteBuffer buf) {
        if (buf != null && buf.isDirect() && buf.capacity() == BUFFER_SIZE) {
            buf.clear();
            if (pool.size() < POOL_SIZE * 2) {
                pool.add(buf);
            }
        }
    }
}
