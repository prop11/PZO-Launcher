package com.pzoptimizer;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Exact-string memo in front of case-insensitive TreeMap lookups. Vanilla FileGuidTable (~12,000 clothing
 * and item GUIDs) and AnimationVariableHandlePool use TreeMap(String.CASE_INSENSITIVE_ORDER), comparing
 * strings char by char on every lookup.
 *
 * Both maps are declared as Map, so the redirected call sites are invokeinterface Map.get/put/clear.
 * Only TreeMaps are memoized; any other Map implementation passes straight through.
 *
 * Correctness rules:
 * - Null results are never cached, so AnimationVariableHandlePool's lazy get-then-put initialization
 *   always sees newly registered handles immediately.
 * - Every put/clear on a memoized map drops that map's memo. A case-insensitive put can change the
 *   answer for several exact-case keys, so per-key invalidation would not be safe.
 * - The original map is only read under its own monitor on a miss; callers that already serialize
 *   (AnimationVariableHandlePool holds s_threadLock) are unaffected.
 */
public final class LookupMemoGovernor {

    private static volatile boolean active = true;

    // Identity slots for the two known maps (FileGuidTable, AnimationVariableHandlePool)
    private static volatile TreeMap<?, ?> slot1Tree = null;
    private static volatile ConcurrentHashMap<Object, Object> slot1Memo = null;
    private static volatile TreeMap<?, ?> slot2Tree = null;
    private static volatile ConcurrentHashMap<Object, Object> slot2Memo = null;

    // Must be identity-keyed: TreeMap.equals/hashCode are content-based, so a WeakHashMap entry
    // becomes unreachable as soon as the map is mutated. Only a handful of maps are ever memoized.
    private static final class IdentityWeakTable {
        private final ArrayList<WeakReference<TreeMap<?, ?>>> keys = new ArrayList<>();
        private final ArrayList<ConcurrentHashMap<Object, Object>> values = new ArrayList<>();

        private int indexOf(TreeMap<?, ?> tree) {
            for (int i = keys.size() - 1; i >= 0; i--) {
                TreeMap<?, ?> k = keys.get(i).get();
                if (k == null) {
                    keys.remove(i);
                    values.remove(i);
                } else if (k == tree) {
                    return i;
                }
            }
            return -1;
        }

        ConcurrentHashMap<Object, Object> get(TreeMap<?, ?> tree) {
            int i = indexOf(tree);
            return i < 0 ? null : values.get(i);
        }

        boolean containsKey(TreeMap<?, ?> tree) {
            return indexOf(tree) >= 0;
        }

        void put(TreeMap<?, ?> tree, ConcurrentHashMap<Object, Object> memo) {
            int i = indexOf(tree);
            if (i >= 0) {
                values.set(i, memo);
            } else {
                keys.add(new WeakReference<>(tree));
                values.add(memo);
            }
        }

        void clear() {
            keys.clear();
            values.clear();
        }
    }

    private static final IdentityWeakTable treeMemos = new IdentityWeakTable();

    public static final LongAdder memoHits = new LongAdder();
    public static final LongAdder memoMisses = new LongAdder();

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

    // Swaps in a fresh memo rather than clearing: a reader that missed before the write can only
    // populate the discarded instance, never the live one.
    private static void invalidate(TreeMap<?, ?> tree) {
        synchronized (treeMemos) {
            if (!treeMemos.containsKey(tree)) return;
            ConcurrentHashMap<Object, Object> fresh = new ConcurrentHashMap<>(1024);
            treeMemos.put(tree, fresh);
            if (tree == slot1Tree) slot1Memo = fresh;
            if (tree == slot2Tree) slot2Memo = fresh;
        }
    }

    /**
     * Redirect target for invokeinterface Map.get(Object) inside FileGuidTable and AnimationVariableHandlePool.
     */
    @SuppressWarnings("rawtypes")
    public static Object mapGetFast(Map map, Object key) {
        if (!active || key == null || !(map instanceof TreeMap)) {
            return map.get(key);
        }
        TreeMap tree = (TreeMap) map;

        ConcurrentHashMap<Object, Object> memo = getOrCreateMemo(tree);
        Object cached = memo.get(key);
        if (cached != null) {
            memoHits.increment();
            return cached;
        }

        memoMisses.increment();
        Object actual;
        synchronized (tree) {
            actual = tree.get(key);
        }
        if (actual != null) {
            memo.put(key, actual);
        }
        return actual;
    }

    /**
     * Redirect target for invokeinterface Map.put(Object, Object).
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Object mapPutFast(Map map, Object key, Object value) {
        if (!(map instanceof TreeMap)) {
            return map.put(key, value);
        }
        TreeMap tree = (TreeMap) map;
        Object previous;
        synchronized (tree) {
            previous = tree.put(key, value);
        }
        invalidate(tree);
        return previous;
    }

    /**
     * Redirect target for invokeinterface Map.clear().
     */
    @SuppressWarnings("rawtypes")
    public static void mapClearFast(Map map) {
        if (!(map instanceof TreeMap)) {
            map.clear();
            return;
        }
        TreeMap tree = (TreeMap) map;
        synchronized (tree) {
            tree.clear();
        }
        invalidate(tree);
    }
}
