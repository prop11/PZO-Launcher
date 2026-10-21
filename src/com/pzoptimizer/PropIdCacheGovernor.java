package com.pzoptimizer;

import java.util.HashMap;
import java.util.Map;
import zombie.core.TilePropertyAliasMap;

/**
 * Vanilla resolves every tile property lookup through HashMap<String, Integer>.getOrDefault, which walks a
 * node and unboxes an Integer per call. After each TilePropertyAliasMap.Generate() this governor snapshots
 * the table into a flat open-addressed String[]/int[] pair, so a hit is one array probe with an identity
 * compare (property names are mostly interned literals) and no boxing.
 *
 * Thread-safety: the snapshot is immutable and published through a single volatile field, so the main
 * thread and PZO worker threads read it lock-free. Generate() runs on the main thread during world load;
 * while it runs (and before the first one completes) lookups fall through to vanilla.
 */
public final class PropIdCacheGovernor {

    private static volatile boolean active = true;

    private static final class Snapshot {
        final String[] keys;
        final int[] ids;
        final int mask;

        Snapshot(Map<String, Integer> source) {
            int cap = Integer.highestOneBit(Math.max(16, source.size() * 4) - 1) << 1;
            keys = new String[cap];
            ids = new int[cap];
            mask = cap - 1;
            for (Map.Entry<String, Integer> e : source.entrySet()) {
                String k = e.getKey();
                if (k == null || e.getValue() == null) continue;
                int i = k.hashCode() & mask;
                while (keys[i] != null) {
                    i = (i + 1) & mask;
                }
                keys[i] = k;
                ids[i] = e.getValue();
            }
        }

        int lookup(String name) {
            int i = name.hashCode() & mask;
            String k;
            while ((k = keys[i]) != null) {
                if (k == name || k.equals(name)) {
                    return ids[i];
                }
                i = (i + 1) & mask;
            }
            return -1;
        }
    }

    private static volatile Snapshot snapshot = null;

    private PropIdCacheGovernor() {}

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean val) {
        active = val;
    }

    public static boolean isSnapshotReady() {
        return snapshot != null;
    }

    /**
     * Redirect target for invokevirtual TilePropertyAliasMap.Generate(HashMap) inside IsoWorld.
     */
    public static void generate(Object aliasMap, HashMap<String, java.util.ArrayList<String>> propertyValueMap) {
        TilePropertyAliasMap map = (TilePropertyAliasMap) aliasMap;
        snapshot = null;
        map.Generate(propertyValueMap);
        if (map == TilePropertyAliasMap.instance) {
            try {
                snapshot = new Snapshot(map.propertyToId);
                PZOLogger.info("[PropIdCacheGovernor] Property-ID snapshot rebuilt (" + map.propertyToId.size() + " properties)");
            } catch (Throwable t) {
                snapshot = null;
                PZOLogger.warn("[PropIdCacheGovernor] Snapshot build failed, using vanilla lookups: " + t.getMessage());
            }
        }
    }

    /**
     * Redirect target for invokevirtual TilePropertyAliasMap.getIDFromPropertyName(String) inside PropertyContainer.
     */
    public static int getIDFromPropertyNameFast(Object aliasMap, String propName) {
        Snapshot s = snapshot;
        if (active && s != null && propName != null && aliasMap == TilePropertyAliasMap.instance) {
            return s.lookup(propName);
        }
        return ((TilePropertyAliasMap) aliasMap).getIDFromPropertyName(propName);
    }
}
