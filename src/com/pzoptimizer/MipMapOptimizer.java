package com.pzoptimizer;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-Speed Bulk Row MipMap Generation Engine.
 * Vanilla `ImageData.scaleMipLevelMaxAlpha` and `scaleMipLevelAverage` execute per-byte
 * absolute offset reads and writes on direct ByteBuffers inside deeply nested loops.
 * 
 * MipMapOptimizer buffers parent row pairs into thread-local scratch arrays via bulk transfers,
 * performs fast local array indexing for alpha downsampling, and commits results with
 * bulk row puts, significantly accelerating texture streaming and mip generation.
 */
public final class MipMapOptimizer {

    private static volatile boolean active = true;
    public static final AtomicLong mipRowsProcessed = new AtomicLong(0);

    private static final class ScratchBuffers {
        byte[] row0 = new byte[0];
        byte[] row1 = new byte[0];
        byte[] outRow = new byte[0];

        byte[] getRow0(int size) {
            if (row0.length < size) row0 = new byte[size];
            return row0;
        }

        byte[] getRow1(int size) {
            if (row1.length < size) row1 = new byte[size];
            return row1;
        }

        byte[] getOutRow(int size) {
            if (outRow.length < size) outRow = new byte[size];
            return outRow;
        }
    }

    private static final ThreadLocal<ScratchBuffers> SCRATCH = ThreadLocal.withInitial(ScratchBuffers::new);

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    /**
     * Downsamples 2x2 parent texels into 1 output texel taking max alpha and average RGB.
     */
    public static void downsampleMipLevel(ByteBuffer src, int srcWidth, int srcHeight,
                                          ByteBuffer dst, int dstWidth, int dstHeight) {
        if (!active || src == null || dst == null) return;

        ScratchBuffers scratch = SCRATCH.get();
        int srcRowBytes = srcWidth * 4;
        int dstRowBytes = dstWidth * 4;

        byte[] r0 = scratch.getRow0(srcRowBytes);
        byte[] r1 = scratch.getRow1(srcRowBytes);
        byte[] out = scratch.getOutRow(dstRowBytes);

        for (int y = 0; y < dstHeight; y++) {
            int srcY0 = y * 2;
            int srcY1 = Math.min(srcY0 + 1, srcHeight - 1);

            src.position(srcY0 * srcRowBytes);
            src.get(r0, 0, srcRowBytes);

            src.position(srcY1 * srcRowBytes);
            src.get(r1, 0, srcRowBytes);

            for (int x = 0; x < dstWidth; x++) {
                int srcX0 = x * 2 * 4;
                int srcX1 = Math.min((x * 2 + 1) * 4, srcRowBytes - 4);

                int a00 = r0[srcX0 + 3] & 0xFF;
                int a01 = r0[srcX1 + 3] & 0xFF;
                int a10 = r1[srcX0 + 3] & 0xFF;
                int a11 = r1[srcX1 + 3] & 0xFF;

                int maxAlpha = Math.max(Math.max(a00, a01), Math.max(a10, a11));

                int avgR = ((r0[srcX0] & 0xFF) + (r0[srcX1] & 0xFF) + (r1[srcX0] & 0xFF) + (r1[srcX1] & 0xFF)) >> 2;
                int avgG = ((r0[srcX0 + 1] & 0xFF) + (r0[srcX1 + 1] & 0xFF) + (r1[srcX0 + 1] & 0xFF) + (r1[srcX1 + 1] & 0xFF)) >> 2;
                int avgB = ((r0[srcX0 + 2] & 0xFF) + (r0[srcX1 + 2] & 0xFF) + (r1[srcX0 + 2] & 0xFF) + (r1[srcX1 + 2] & 0xFF)) >> 2;

                int dstIdx = x * 4;
                out[dstIdx] = (byte) avgR;
                out[dstIdx + 1] = (byte) avgG;
                out[dstIdx + 2] = (byte) avgB;
                out[dstIdx + 3] = (byte) maxAlpha;
            }

            dst.position(y * dstRowBytes);
            dst.put(out, 0, dstRowBytes);
            mipRowsProcessed.incrementAndGet();
        }
    }
}
