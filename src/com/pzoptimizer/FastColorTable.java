package com.pzoptimizer;

/**
 * L1 Pre-Computed RGBA Integer-to-Float Fast Lookup Table.
 * Replaces expensive floating-point divisions (b / 255.0f) with instant O(1) array lookups.
 */
public final class FastColorTable {
    private static final float[] BYTE_TO_FLOAT = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            BYTE_TO_FLOAT[i] = i / 255.0f;
        }
    }

    /**
     * Instantly converts an 8-bit unsigned color channel (0-255) to a 0.0f-1.0f float with zero division latency.
     */
    public static float getFloat(int byteVal) {
        if (byteVal < 0) return 0.0f;
        if (byteVal > 255) return 1.0f;
        return BYTE_TO_FLOAT[byteVal];
    }
}
