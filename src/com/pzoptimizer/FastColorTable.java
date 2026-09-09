package com.pzoptimizer;

public final class FastColorTable {
    private static final float[] BYTE_TO_FLOAT = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            BYTE_TO_FLOAT[i] = i / 255.0f;
        }
    }

    public static float getFloat(int byteVal) {
        if (byteVal < 0) return 0.0f;
        if (byteVal > 255) return 1.0f;
        return BYTE_TO_FLOAT[byteVal];
    }
}
