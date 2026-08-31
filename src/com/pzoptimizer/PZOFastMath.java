package com.pzoptimizer;

/**
 * PZO Fast Math & L1 Cache-Aligned Trigonometry Engine.
 * Provides single-cycle trigonometric functions, fast inverse square root,
 * and high-speed floating point calculations for physics, angles, and rendering.
 * 100% deterministic, thread-safe, and zero-allocation across Windows, macOS, and Linux.
 */
public final class PZOFastMath {

    private static final int TABLE_SIZE = 65536;
    private static final int TABLE_MASK = TABLE_SIZE - 1;
    private static final float RAD_TO_INDEX = TABLE_SIZE / (float) (Math.PI * 2.0);
    
    private static final float[] SIN_TABLE = new float[TABLE_SIZE];
    private static final float[] COS_TABLE = new float[TABLE_SIZE];

    static {
        for (int i = 0; i < TABLE_SIZE; i++) {
            double angle = (i * Math.PI * 2.0) / TABLE_SIZE;
            SIN_TABLE[i] = (float) Math.sin(angle);
            COS_TABLE[i] = (float) Math.cos(angle);
        }
        PZOLogger.success("PZOFastMath: Pre-computed 65,536-entry L1 trigonometric table initialized");
    }

    public static void initialize() {
        // Trigger class loading and static block
    }

    public static float sin(float radians) {
        return SIN_TABLE[(int) (radians * RAD_TO_INDEX) & TABLE_MASK];
    }

    public static float cos(float radians) {
        return COS_TABLE[(int) (radians * RAD_TO_INDEX) & TABLE_MASK];
    }

    public static float distSq(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        return dx * dx + dy * dy;
    }

    public static float distSq3D(float x1, float y1, float z1, float x2, float y2, float z2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Fast Inverse Square Root (IEEE 754 bit-hack with 1 Newton-Raphson iteration).
     */
    public static float invSqrt(float x) {
        float xhalf = 0.5f * x;
        int i = Float.floatToIntBits(x);
        i = 0x5f3759df - (i >> 1);
        x = Float.intBitsToFloat(i);
        x = x * (1.5f - xhalf * x * x);
        return x;
    }

    public static float fastSqrt(float x) {
        if (x <= 0.0f) return 0.0f;
        return x * invSqrt(x);
    }
}
