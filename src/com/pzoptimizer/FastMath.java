package com.pzoptimizer;

public class FastMath {
    private static final int SIN_BITS = 16;
    private static final int SIN_MASK = (1 << SIN_BITS) - 1;
    private static final float[] SIN_TABLE = new float[SIN_MASK + 1];
    private static final float RAD_TO_INDEX = (SIN_MASK + 1) / (float) (Math.PI * 2);

    static {
        for (int i = 0; i <= SIN_MASK; i++) {
            SIN_TABLE[i] = (float) Math.sin((i + 0.5f) / (SIN_MASK + 1) * Math.PI * 2);
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
}
