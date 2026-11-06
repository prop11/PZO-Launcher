package com.pzoptimizer;

import java.util.Arrays;

/**
 * long key -> true/false, open addressing, no boxing. For per-frame lookups that a HashMap<Long, Boolean> made
 * allocate and hash twice (FoliageLod's far/near chunk set). Not thread-safe.
 */
final class LongFlagMap {

    private static final byte EMPTY = 0, FALSE = 1, TRUE = 2;

    private long[] keys = new long[1024];
    private byte[] states = new byte[1024];
    private int size;

    /** 1 for true, 0 for false, -1 when the key isn't there. */
    int get(long key) {
        int mask = keys.length - 1;
        for (int i = slot(key, mask); ; i = (i + 1) & mask) {
            byte s = states[i];
            if (s == EMPTY) return -1;
            if (keys[i] == key) return s == TRUE ? 1 : 0;
        }
    }

    void put(long key, boolean value) {
        if ((size + 1) * 2 > keys.length) grow();
        int mask = keys.length - 1;
        for (int i = slot(key, mask); ; i = (i + 1) & mask) {
            if (states[i] == EMPTY) {
                keys[i] = key;
                states[i] = value ? TRUE : FALSE;
                size++;
                return;
            }
            if (keys[i] == key) {
                states[i] = value ? TRUE : FALSE;
                return;
            }
        }
    }

    boolean isEmpty() {
        return size == 0;
    }

    void clear() {
        if (size == 0) return;
        Arrays.fill(states, EMPTY);
        size = 0;
    }

    private void grow() {
        long[] oldKeys = keys;
        byte[] oldStates = states;
        keys = new long[oldKeys.length * 2];
        states = new byte[oldKeys.length * 2];
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldStates[i] != EMPTY) put(oldKeys[i], oldStates[i] == TRUE);
        }
    }

    private static int slot(long key, int mask) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32)) & mask;
    }
}
