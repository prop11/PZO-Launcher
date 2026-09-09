package com.pzoptimizer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public final class SpatialBufferPool {

    public static final int MAX_ENTITIES = 8192;

    private static volatile boolean initialized = false;

    private static ByteBuffer rawCoordBuf;
    private static FloatBuffer coordBuffer;

    private static ByteBuffer rawDistanceBuf;
    private static FloatBuffer distanceBuffer;

    private static ByteBuffer rawDistSqBuf;
    private static FloatBuffer distSqBuffer;

    private static ByteBuffer cullMaskBuffer;
    private static ByteBuffer tiersBuffer;
    private static ByteBuffer rawHeadingBuf;
    private static FloatBuffer headingBuffer;
    private static ByteBuffer fovMaskBuffer;
    private static ByteBuffer rawRepulsionBuf;
    private static FloatBuffer repulsionBuffer;

    public static synchronized void initialize() {
        if (initialized) return;

        try {
            rawCoordBuf = ByteBuffer.allocateDirect(MAX_ENTITIES * 2 * Float.BYTES).order(ByteOrder.nativeOrder());
            coordBuffer = rawCoordBuf.asFloatBuffer();

            rawDistanceBuf = ByteBuffer.allocateDirect(MAX_ENTITIES * Float.BYTES).order(ByteOrder.nativeOrder());
            distanceBuffer = rawDistanceBuf.asFloatBuffer();

            rawDistSqBuf = ByteBuffer.allocateDirect(MAX_ENTITIES * Float.BYTES).order(ByteOrder.nativeOrder());
            distSqBuffer = rawDistSqBuf.asFloatBuffer();

            cullMaskBuffer = ByteBuffer.allocateDirect(MAX_ENTITIES).order(ByteOrder.nativeOrder());

            tiersBuffer = ByteBuffer.allocateDirect(MAX_ENTITIES).order(ByteOrder.nativeOrder());

            rawHeadingBuf = ByteBuffer.allocateDirect(MAX_ENTITIES * 2 * Float.BYTES).order(ByteOrder.nativeOrder());
            headingBuffer = rawHeadingBuf.asFloatBuffer();

            fovMaskBuffer = ByteBuffer.allocateDirect(MAX_ENTITIES).order(ByteOrder.nativeOrder());

            rawRepulsionBuf = ByteBuffer.allocateDirect(MAX_ENTITIES * 2 * Float.BYTES).order(ByteOrder.nativeOrder());
            repulsionBuffer = rawRepulsionBuf.asFloatBuffer();

            initialized = true;
            PZOLogger.success(String.format(
                "SpatialBufferPool: Active (Pre-allocated %dKB off-heap direct memory for up to %d entities)",
                (64 + 32 + 32 + 8 + 8 + 64 + 8 + 64), MAX_ENTITIES
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

    public static FloatBuffer getHeadingBuffer() {
        return headingBuffer;
    }

    public static ByteBuffer getFovMaskBuffer() {
        return fovMaskBuffer;
    }

    public static FloatBuffer getRepulsionBuffer() {
        return repulsionBuffer;
    }

    public static void rewindAll() {
        if (coordBuffer != null) coordBuffer.rewind();
        if (distanceBuffer != null) distanceBuffer.rewind();
        if (distSqBuffer != null) distSqBuffer.rewind();
        if (cullMaskBuffer != null) cullMaskBuffer.rewind();
        if (tiersBuffer != null) tiersBuffer.rewind();
        if (headingBuffer != null) headingBuffer.rewind();
        if (fovMaskBuffer != null) fovMaskBuffer.rewind();
        if (repulsionBuffer != null) repulsionBuffer.rewind();
    }
}
