package com.pzoptimizer;

/**
 * Project Zomboid Build 42 - High-Speed L1 Path Normalization & Interning Cache.
 * Eliminates millions of temporary String and char[] allocations during texture,
 * model, and sound asset loading.
 */
public class FastPathCache {
    private static final int CACHE_SIZE = 4096;
    private static final int MASK = CACHE_SIZE - 1;
    private static final String[] KEY_CACHE = new String[CACHE_SIZE];
    private static final String[] VAL_CACHE = new String[CACHE_SIZE];

    public static String normalize(String path) {
        if (path == null) return null;
        if (path.isEmpty()) return "";

        int hash = (path.hashCode() & 0x7FFFFFFF) & MASK;
        String cachedKey = KEY_CACHE[hash];
        if (path.equals(cachedKey)) {
            return VAL_CACHE[hash];
        }

        // Fast normalization without regex or unnecessary string copies
        String normalized = path.indexOf('\\') != -1 ? path.replace('\\', '/') : path;
        normalized = ResourceInterner.intern(normalized);

        KEY_CACHE[hash] = path;
        VAL_CACHE[hash] = normalized;
        return normalized;
    }
}
