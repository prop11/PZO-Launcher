package com.pzoptimizer;

import java.nio.ByteBuffer;

/**
 * Project Zomboid Build 42 - Reusable Thread-Local Chunk Decompression Buffers.
 * Eliminates repeated 128KB-256KB byte array allocations during high-speed vehicle driving.
 */
public class ChunkBufferPool {
    public static volatile boolean enabled = true;

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    private static final int BUFFER_SIZE = 262144; // 256 KB

    private static final ThreadLocal<byte[]> THREAD_BYTE_BUFFER = ThreadLocal.withInitial(() -> new byte[BUFFER_SIZE]);
    private static final ThreadLocal<ByteBuffer> THREAD_DIRECT_BUFFER = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(BUFFER_SIZE));

    public static byte[] getByteArray() {
        return THREAD_BYTE_BUFFER.get();
    }

    public static ByteBuffer getDirectByteBuffer() {
        ByteBuffer buf = THREAD_DIRECT_BUFFER.get();
        buf.clear();
        return buf;
    }
}
