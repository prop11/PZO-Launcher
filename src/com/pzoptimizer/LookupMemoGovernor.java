package com.pzoptimizer;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * O(1) Exact-String Memoization Governor for Case-Insensitive TreeMap Lookups.
 * Vanilla FileGuidTable (~12,000 clothing/item GUIDs) and AnimationVariableHandlePool use
 * TreeMap(String.CASE_INSENSITIVE_ORDER), comparing strings char-by-char on every lookup.
 * During zombie spawn and outfit dressing bursts (~20 GUID lookups per zombie), this consumes
 * 8-20 ms/sec on the GameThread.
 *
 * LookupMemoGovernor places a thread-safe ConcurrentHashMap in front of these TreeMap lookups,
 * returning exact cached results in O(1) time and clearing automatically when TreeMap.clear() runs.
 */
public final class LookupMemoGovernor {

    private static volatile boolean active = true;
    private static final Object NULL_SENTINEL = new Object();

    private static final Map<TreeMap<?, ?>, ConcurrentHashMap<Object, Object>> treeMemos =
        Collections.synchronizedMap(new WeakHashMap<>());

    public static final AtomicLong memoHits = new AtomicLong(0);
    public static final AtomicLong memoMisses = new AtomicLong(0);

    private LookupMemoGovernor() {}

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean val) {
        active = val;
        if (!val) {
            clearAllMemos();
        }
    }

    public static void clearAllMemos() {
        synchronized (treeMemos) {
            treeMemos.clear();
        }
    }

    /**
     * 3-byte bytecode redirection target for TreeMap.get(Object) inside FileGuidTable
     * and AnimationVariableHandlePool.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Object treeMapGetFast(TreeMap tree, Object key) {
        if (tree == null) return null;
        if (!active || key == null) {
            return tree.get(key);
        }

        ConcurrentHashMap<Object, Object> memo = treeMemos.get(tree);
        if (memo == null) {
            synchronized (treeMemos) {
                memo = treeMemos.get(tree);
                if (memo == null) {
                    memo = new ConcurrentHashMap<>(1024);
                    treeMemos.put(tree, memo);
                }
            }
        }

        Object cached = memo.get(key);
        if (cached != null) {
            memoHits.incrementAndGet();
            return cached == NULL_SENTINEL ? null : cached;
        }

        memoMisses.incrementAndGet();
        Object actual = tree.get(key);
        memo.put(key, actual == null ? NULL_SENTINEL : actual);
        return actual;
    }

    /**
     * 3-byte bytecode redirection target for TreeMap.clear() inside FileGuidTable.
     */
    @SuppressWarnings("rawtypes")
    public static void treeMapClearFast(TreeMap tree) {
        if (tree == null) return;
        tree.clear();
        ConcurrentHashMap<Object, Object> memo = treeMemos.remove(tree);
        if (memo != null) {
            memo.clear();
        }
    }
}
