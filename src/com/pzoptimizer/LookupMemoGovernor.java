package com.pzoptimizer;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * O(1) Lock-Free Exact-String Memoization Governor for Case-Insensitive TreeMap Lookups.
 * Vanilla FileGuidTable (~12,000 clothing/item GUIDs) and AnimationVariableHandlePool use
 * TreeMap(String.CASE_INSENSITIVE_ORDER), comparing strings char-by-char on every lookup.
 *
 * Multi-threading optimizations:
 * 1. Two volatile identity fast-slots eliminate synchronizedMap(WeakHashMap) lock contention
 *    across MainThread and PZO-ChunkStreamer-Worker threads.
 * 2. Never caches null returns, so AnimationVariableHandlePool's lazy get-then-put initialization
 *    always sees newly registered handles immediately.
 * 3. Synchronizes on the underlying TreeMap during cache-miss reads so concurrent worker threads
 *    never race on non-thread-safe TreeMap traversal.
 */
public final class LookupMemoGovernor {

    private static volatile boolean active = true;

    // Lock-free L1 identity slots for the two primary TreeMaps (FileGuidTable & AnimationVariableHandlePool)
    private static volatile TreeMap<?, ?> slot1Tree = null;
    private static volatile ConcurrentHashMap<Object, Object> slot1Memo = null;
    private static volatile TreeMap<?, ?> slot2Tree = null;
    private static volatile ConcurrentHashMap<Object, Object> slot2Memo = null;

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
            slot1Tree = null;
            slot1Memo = null;
            slot2Tree = null;
            slot2Memo = null;
            treeMemos.clear();
        }
    }

    private static ConcurrentHashMap<Object, Object> getOrCreateMemo(TreeMap<?, ?> tree) {
        if (tree == slot1Tree) {
            ConcurrentHashMap<Object, Object> m = slot1Memo;
            if (m != null) return m;
        }
        if (tree == slot2Tree) {
            ConcurrentHashMap<Object, Object> m = slot2Memo;
            if (m != null) return m;
        }
        synchronized (treeMemos) {
            ConcurrentHashMap<Object, Object> memo = treeMemos.get(tree);
            if (memo == null) {
                memo = new ConcurrentHashMap<>(1024);
                treeMemos.put(tree, memo);
            }
            if (slot1Tree == null || slot1Tree == tree) {
                slot1Memo = memo;
                slot1Tree = tree;
            } else if (slot2Tree == null || slot2Tree == tree) {
                slot2Memo = memo;
                slot2Tree = tree;
            }
            return memo;
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
            synchronized (tree) {
                return tree.get(key);
            }
        }

        ConcurrentHashMap<Object, Object> memo = getOrCreateMemo(tree);
        Object cached = memo.get(key);
        if (cached != null) {
            memoHits.incrementAndGet();
            return cached;
        }

        memoMisses.incrementAndGet();
        Object actual;
        synchronized (tree) {
            actual = tree.get(key);
        }
        // Only cache non-null values so lazy get-then-put pools (AnimationVariableHandlePool) never see stale nulls
        if (actual != null) {
            memo.put(key, actual);
        }
        return actual;
    }

    /**
     * 3-byte bytecode redirection target for TreeMap.clear() inside FileGuidTable.
     */
    @SuppressWarnings("rawtypes")
    public static void treeMapClearFast(TreeMap tree) {
        if (tree == null) return;
        synchronized (tree) {
            tree.clear();
        }
        synchronized (treeMemos) {
            if (tree == slot1Tree) {
                slot1Tree = null;
                slot1Memo = null;
            }
            if (tree == slot2Tree) {
                slot2Tree = null;
                slot2Memo = null;
            }
            ConcurrentHashMap<Object, Object> memo = treeMemos.remove(tree);
            if (memo != null) {
                memo.clear();
            }
        }
    }
}
