package com.pzoptimizer;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Resource Interner & String Deduplicator.
 * Deduplicates repeated texture filepaths, sound identifiers, and item names
 * across thousands of simulation objects to minimize heap bloat and GC pressure.
 */
public class ResourceInterner {
    private static final ConcurrentHashMap<String, String> STRING_POOL = new ConcurrentHashMap<>(4096);
    private static final int MAX_POOL_SIZE = 16384;

    public static String intern(String str) {
        if (str == null) return null;
        if (str.length() > 256) return str;

        if (STRING_POOL.size() >= MAX_POOL_SIZE) {
            STRING_POOL.clear();
        }

        String existing = STRING_POOL.putIfAbsent(str, str);
        return existing != null ? existing : str;
    }

    public static int getPoolSize() {
        return STRING_POOL.size();
    }
}
