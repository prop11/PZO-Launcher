package com.pzoptimizer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * PZO Off-Heap Direct Spatial Buffer Pool (Phase 3 SIMD Acceleration).
 * 
 * Pre-allocates dedicated off-heap direct NIO buffers for up to 8,192 concurrent entities.
 * Eliminates all JVM heap allocation and garbage collection overhead during hot-loop
 * horde distance calculations, AABB culling, and LOD classification sweeps.
 */
public final class SpatialBufferPool {

    public static final int MAX_ENTITIES = 8192;

    private static volatile boolean initialized = false;

    // Off-heap native memory buffers
    private static ByteBuffer rawCoordBuf;
    private static FloatBuffer coordBuffer;

    private static ByteBuffer rawDistanceBuf;
    private static FloatBuffer distanceBuffer;

    private static ByteBuffer rawDistSqBuf;
    private static FloatBuffer distSqBuffer;

    private static ByteBuffer cullMaskBuffer;
    private static ByteBuffer tiersBuffer;

    public static synchronized void initialize() {
        if (initialized) return;

        try {
            // 8,192 entities * 2 coordinates (x,y) * 4 bytes/float = 65,536 bytes (64 KB)
            rawCoordBuf = ByteBuffer.allocateDirect(MAX_ENTITIES * 2 * Float.BYTES).order(ByteOrder.nativeOrder());
            coordBuffer = rawCoordBuf.asFloatBuffer();

            // 8,192 entities * 4 bytes/float = 32,768 bytes (32 KB)
            rawDistanceBuf = ByteBuffer.allocateDirect(MAX_ENTITIES * Float.BYTES).order(ByteOrder.nativeOrder());
            distanceBuffer = rawDistanceBuf.asFloatBuffer();

            // 8,192 entities * 4 bytes/float = 32,768 bytes (32 KB)
            rawDistSqBuf = ByteBuffer.allocateDirect(MAX_ENTITIES * Float.BYTES).order(ByteOrder.nativeOrder());
            distSqBuffer = rawDistSqBuf.asFloatBuffer();

            // 8,192 entities * 1 byte = 8,192 bytes (8 KB)
            cullMaskBuffer = ByteBuffer.allocateDirect(MAX_ENTITIES).order(ByteOrder.nativeOrder());

            // 8,192 entities * 1 byte = 8,192 bytes (8 KB)
            tiersBuffer = ByteBuffer.allocateDirect(MAX_ENTITIES).order(ByteOrder.nativeOrder());

            initialized = true;
            PZOLogger.success(String.format(
                "SpatialBufferPool: Active (Pre-allocated %dKB off-heap direct memory for up to %d entities)",
                (64 + 32 + 32 + 8 + 8), MAX_ENTITIES
            ));
        } catch (Throwable t) {
            PZOLogger.warn("SpatialBufferPool allocation notice: " + t.getMessage());
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static FloatBuffer getCoordBuffer() {
        return coordBuffer;
    }

    public static FloatBuffer getDistanceBuffer() {
        return distanceBuffer;
    }

    public static FloatBuffer getDistSqBuffer() {
        return distSqBuffer;
    }

    public static ByteBuffer getCullMaskBuffer() {
        return cullMaskBuffer;
    }

    public static ByteBuffer getTiersBuffer() {
        return tiersBuffer;
    }

    public static void rewindAll() {
        if (coordBuffer != null) coordBuffer.rewind();
        if (distanceBuffer != null) distanceBuffer.rewind();
        if (distSqBuffer != null) distSqBuffer.rewind();
        if (cullMaskBuffer != null) cullMaskBuffer.rewind();
        if (tiersBuffer != null) tiersBuffer.rewind();
    }
}
