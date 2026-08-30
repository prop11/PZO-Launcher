package com.pzoptimizer;

/**
 * Project Zomboid Build 42 - Zero-Allocation Fast Trigonometry & Distance Engine.
 * Precomputes sin/cos lookup tables and 65x65 tile grid distance squared matrices.
 */
public class FastMath {
    private static final int SIN_BITS = 16;
    private static final int SIN_MASK = (1 << SIN_BITS) - 1;
    private static final float[] SIN_TABLE = new float[SIN_MASK + 1];
    private static final float RAD_TO_INDEX = (SIN_MASK + 1) / (float) (Math.PI * 2);

    // Fast integer tile distance table for offsets -32 to +32
    private static final int OFFSET_RANGE = 32;
    private static final int TABLE_SIZE = (OFFSET_RANGE * 2) + 1; // 65
    private static final int[][] TILE_DIST_SQ = new int[TABLE_SIZE][TABLE_SIZE];

    static {
        for (int i = 0; i <= SIN_MASK; i++) {
            SIN_TABLE[i] = (float) Math.sin((i + 0.5f) / (SIN_MASK + 1) * Math.PI * 2);
        }

        for (int dx = -OFFSET_RANGE; dx <= OFFSET_RANGE; dx++) {
            for (int dy = -OFFSET_RANGE; dy <= OFFSET_RANGE; dy++) {
                TILE_DIST_SQ[dx + OFFSET_RANGE][dy + OFFSET_RANGE] = (dx * dx) + (dy * dy);
            }
        }
    }

    public static float sin(float rad) {
        return SIN_TABLE[(int) (rad * RAD_TO_INDEX) & SIN_MASK];
    }

    public static float cos(float rad) {
        return SIN_TABLE[(int) ((rad + (float) (Math.PI / 2)) * RAD_TO_INDEX) & SIN_MASK];
    }

    public static float fastInvSqrt(float x) {
        float xhalf = 0.5f * x;
        int i = Float.floatToIntBits(x);
        i = 0x5f3759df - (i >> 1);
        x = Float.intBitsToFloat(i);
        x = x * (1.5f - xhalf * x * x);
        return x;
    }

    public static float distance(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float sq = dx * dx + dy * dy;
        return sq * fastInvSqrt(sq);
    }

    /**
     * Instant L1-cache integer distance squared lookup for tile grids.
     */
    public static int tileDistSq(int dx, int dy) {
        if (dx >= -OFFSET_RANGE && dx <= OFFSET_RANGE && dy >= -OFFSET_RANGE && dy <= OFFSET_RANGE) {
            return TILE_DIST_SQ[dx + OFFSET_RANGE][dy + OFFSET_RANGE];
        }
        return (dx * dx) + (dy * dy);
    }

    public static boolean isWithinTileRange(int dx, int dy, int maxDist) {
        return tileDistSq(dx, dy) <= (maxDist * maxDist);
    }
}
