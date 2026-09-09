package com.pzoptimizer;

public final class FastChunkKey {

    public static long pack(int wx, int wy) {
        return (((long) wx) << 32) | (wy & 0xFFFFFFFFL);
    }

    public static int getX(long key) {
        return (int) (key >> 32);
    }

    public static int getY(long key) {
        return (int) key;
    }
}
