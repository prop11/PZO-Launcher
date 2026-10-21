package com.pzoptimizer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Faster decoding for the texture packs and tile depth maps loaded at boot and world load:
 * - Paeth un-filtering for 4-channel scanlines that keeps neighbouring channel values in locals,
 *   with byte-identical output
 * - 8-bit palette indices to packed 32-bit RGBA through a 256-entry table
 */
public final class FastTextureDecompressor {

    private static volatile boolean active = true;
    public static final AtomicLong scanlinesProcessed = new AtomicLong(0);

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    public static void unfilterPaeth4(byte[] cur, byte[] prev) {
        if (!active || cur == null || prev == null) return;
        int n = cur.length;
        if (n < 5) {
            for (int i = 1; i < n; i++) {
                cur[i] += prev[i];
            }
            return;
        }

        scanlinesProcessed.incrementAndGet();

        int a0 = (cur[1] += prev[1]) & 0xFF;
        int a1 = (cur[2] += prev[2]) & 0xFF;
        int a2 = (cur[3] += prev[3]) & 0xFF;
        int a3 = (cur[4] += prev[4]) & 0xFF;

        int c0 = prev[1] & 0xFF;
        int c1 = prev[2] & 0xFF;
        int c2 = prev[3] & 0xFF;
        int c3 = prev[4] & 0xFF;

        int i = 5;
        for (; i + 3 < n; i += 4) {
            int b0 = prev[i] & 0xFF;
            int b1 = prev[i + 1] & 0xFF;
            int b2 = prev[i + 2] & 0xFF;
            int b3 = prev[i + 3] & 0xFF;

            a0 = (cur[i] + predictPaeth(a0, b0, c0)) & 0xFF;
            a1 = (cur[i + 1] + predictPaeth(a1, b1, c1)) & 0xFF;
            a2 = (cur[i + 2] + predictPaeth(a2, b2, c2)) & 0xFF;
            a3 = (cur[i + 3] + predictPaeth(a3, b3, c3)) & 0xFF;

            cur[i] = (byte) a0;
            cur[i + 1] = (byte) a1;
            cur[i + 2] = (byte) a2;
            cur[i + 3] = (byte) a3;

            c0 = b0;
            c1 = b1;
            c2 = b2;
            c3 = b3;
        }

        // Tail bytes if line is not an exact multiple of 4
        for (; i < n; i++) {
            cur[i] += (byte) predictPaeth(cur[i - 4] & 0xFF, prev[i] & 0xFF, prev[i - 4] & 0xFF);
        }
    }

    public static int predictPaeth(int a, int b, int c) {
        int pa = Math.abs(b - c);
        int pb = Math.abs(a - c);
        int pc = Math.abs(a + b - 2 * c);
        if (pa <= pb && pa <= pc) return a;
        if (pb <= pc) return b;
        return c;
    }

    public static void decodePaletteToRgba(byte[] indices, int[] paletteTable, int[] outRgba, int count) {
        if (!active || indices == null || paletteTable == null || outRgba == null) return;
        int limit = Math.min(count, Math.min(indices.length, outRgba.length));
        for (int i = 0; i < limit; i++) {
            int idx = indices[i] & 0xFF;
            outRgba[i] = paletteTable[idx];
        }
    }
}
