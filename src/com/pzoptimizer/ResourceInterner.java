package com.pzoptimizer;

import java.util.concurrent.ConcurrentHashMap;

public class ResourceInterner {
    private static final ConcurrentHashMap<String, String> STRING_CACHE = new ConcurrentHashMap<>(4096);

    public static String intern(String input) {
        if (input == null) return null;
        if (input.length() > 128) return input;
        String cached = STRING_CACHE.get(input);
        if (cached != null) return cached;
        STRING_CACHE.putIfAbsent(input, input);
        return input;
    }

    public static int getCachedCount() {
        return STRING_CACHE.size();
    }
}
