package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PropertyContainer / TilePropertyAliasMap Property ID Cache Governor.
 * During chunk hand-offs and property recalculations, PropertyContainer.has/Val looks up
 * the integer property ID by string name in TilePropertyAliasMap's HashMap on every call
 * (~10% of chunk hand-off time and ~0.8% of total GameThread CPU).
 *
 * PropIdCacheGovernor caches the resolved integer property ID per generation (and by ordinal
 * or string reference), invalidated automatically when TilePropertyAliasMap.Generate() runs.
 */
public final class PropIdCacheGovernor {

    private static volatile boolean active = true;
    private static final int UNSET = Integer.MIN_VALUE;
    private static final AtomicInteger generation = new AtomicInteger(1);

    private static volatile int cachedGen = 0;
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
    public static void onAliasMapGenerated() {
        generation.incrementAndGet();
        nameIds.clear();
        int[] fresh = new int[512];
        java.util.Arrays.fill(fresh, UNSET);
        ordinalIds = fresh;
        cachedGen = generation.get();
        aliasMapInstance = null;
    }

    /**
     * Bytecode hook replacement for TilePropertyAliasMap.getIDFromPropertyName(Object aliasMap, String name).
     * Caches the integer ID in a lock-free map for the current alias generation.
     */
    public static int getIDFromPropertyNameFast(Object aliasMap, String propName) {
        if (propName == null) return -1;
        if (active) {
            int g = generation.get();
            if (g != cachedGen || (aliasMap != null && aliasMap != aliasMapInstance)) {
                nameIds.clear();
                cachedGen = g;
                if (aliasMap != null) aliasMapInstance = aliasMap;
            }
            Integer cached = nameIds.get(propName);
            if (cached != null) {
                cacheHits.incrementAndGet();
                return cached.intValue();
            }
        }
        cacheMisses.incrementAndGet();
        int resolved = invokeStockGetId(aliasMap, propName);
        if (active && resolved != UNSET) {
            nameIds.put(propName, Integer.valueOf(resolved));
        }
        return resolved;
    }

    private static int invokeStockGetId(Object aliasMap, String propName) {
        try {
            if (aliasMap == null) {
                if (instanceField == null) {
                    Class<?> cls = Class.forName("zombie.core.TilePropertyAliasMap");
                    instanceField = cls.getDeclaredField("instance");
                    instanceField.setAccessible(true);
                }
                aliasMap = instanceField.get(null);
            }
            if (aliasMap != null) {
                if (getIdMethod == null) {
                    getIdMethod = aliasMap.getClass().getDeclaredMethod("getIDFromPropertyName", String.class);
                    getIdMethod.setAccessible(true);
                }
                return ((Number) getIdMethod.invoke(aliasMap, propName)).intValue();
            }
        } catch (Throwable ignored) {}
        return -1;
    }
}
