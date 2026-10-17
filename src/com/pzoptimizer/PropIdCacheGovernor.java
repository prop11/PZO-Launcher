package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PropertyContainer / TilePropertyAliasMap Property ID Cache Governor.
 * Thread-safe and lock-free across MainThread and PZO-ChunkStreamer-Worker threads.
 */
public final class PropIdCacheGovernor {

    private static volatile boolean active = true;
    private static final int UNSET = Integer.MIN_VALUE;
    private static final AtomicInteger generation = new AtomicInteger(1);

    private static volatile int cachedGen = 1;
    private static volatile int[] ordinalIds = new int[512];
    private static final ConcurrentHashMap<String, Integer> nameIds = new ConcurrentHashMap<>(512);

    private static volatile Object aliasMapInstance = null;
    private static volatile Method getIdMethod = null;
    private static volatile Field instanceField = null;

    public static final AtomicLong cacheHits = new AtomicLong(0);
    public static final AtomicLong cacheMisses = new AtomicLong(0);

    static {
        java.util.Arrays.fill(ordinalIds, UNSET);
    }

    private PropIdCacheGovernor() {}

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean val) {
        active = val;
    }

    /**
     * Called when TilePropertyAliasMap.Generate() runs on world initialization.
     */
    public static synchronized void onAliasMapGenerated() {
        int nextGen = generation.incrementAndGet();
        nameIds.clear();
        int[] fresh = new int[512];
        java.util.Arrays.fill(fresh, UNSET);
        ordinalIds = fresh;
        cachedGen = nextGen;
        aliasMapInstance = null;
    }

    /**
     * Bytecode hook replacement for TilePropertyAliasMap.getIDFromPropertyName(Object aliasMap, String name).
     * Caches valid integer IDs (>= 0) in a lock-free ConcurrentHashMap for the current alias generation.
     */
    public static int getIDFromPropertyNameFast(Object aliasMap, String propName) {
        if (propName == null) return -1;
        if (active) {
            int g = generation.get();
            Object currentInst = aliasMapInstance;
            if (g != cachedGen || (aliasMap != null && currentInst != aliasMap)) {
                synchronized (PropIdCacheGovernor.class) {
                    if (g != cachedGen || (aliasMap != null && aliasMapInstance != aliasMap)) {
                        if (aliasMapInstance != null || g != cachedGen) {
                            nameIds.clear();
                        }
                        cachedGen = g;
                        if (aliasMap != null) {
                            aliasMapInstance = aliasMap;
                        }
                    }
                }
            }
            Integer cached = nameIds.get(propName);
            if (cached != null) {
                cacheHits.incrementAndGet();
                return cached.intValue();
            }
        }
        cacheMisses.incrementAndGet();
        int resolved = invokeStockGetId(aliasMap, propName);
        if (active && resolved >= 0) {
            nameIds.put(propName, Integer.valueOf(resolved));
        }
        return resolved;
    }

    private static int invokeStockGetId(Object aliasMap, String propName) {
        try {
            if (aliasMap == null) {
                Field f = instanceField;
                if (f == null) {
                    Class<?> cls = Class.forName("zombie.core.TilePropertyAliasMap");
                    f = cls.getDeclaredField("instance");
                    f.setAccessible(true);
                    instanceField = f;
                }
                aliasMap = f.get(null);
            }
            if (aliasMap != null) {
                Method m = getIdMethod;
                if (m == null) {
                    m = aliasMap.getClass().getDeclaredMethod("getIDFromPropertyName", String.class);
                    m.setAccessible(true);
                    getIdMethod = m;
                }
                return ((Number) m.invoke(aliasMap, propName)).intValue();
            }
        } catch (Throwable ignored) {}
        return -1;
    }
}
