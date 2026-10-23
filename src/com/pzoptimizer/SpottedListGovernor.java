package com.pzoptimizer;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.WeakReference;
import java.util.AbstractList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Replaces Stack.contains and Stack.add in IsoPlayer. updateLOS checks every spotted character against
 * lastSpotted, a Stack that only empties once no zombie has been seen for 1000 ticks, so in a horde each frame
 * does spotted x lastSpotted linear scans. Large stacks get a mirrored HashSet (same equals semantics as
 * Vector.contains). The mirror is valid only while the stack's modCount matches the one it was built at; adds
 * made through add() keep it in sync, and any other change (clear, a mod calling remove) rebuilds it.
 * Vector.set does not bump modCount and would go unnoticed; nothing in the game calls it on these stacks.
 */
public final class SpottedListGovernor {

    private static volatile boolean active = true;
    public static final AtomicLong lookupsHashed = new AtomicLong();
    public static final AtomicLong rebuilds = new AtomicLong();

    private static final int MIN_SIZE = 16;
    private static final VarHandle MOD_COUNT = lookupModCount();

    // One entry per player stack; a handful at most, so a linear scan beats hashing a Vector (O(n) hashCode)
    private static final Mirror[] mirrors = new Mirror[16];

    private static final class Mirror {
        final WeakReference<Stack<?>> stack;
        final HashSet<Object> set = new HashSet<>();
        int modCount = -1;
        int size = -1;

        Mirror(Stack<?> s) {
            stack = new WeakReference<>(s);
        }
    }

    private SpottedListGovernor() {}

    private static VarHandle lookupModCount() {
        try {
            Instrumentation inst = PZOptimAgent.getInstrumentation();
            if (inst != null) {
                inst.redefineModule(Object.class.getModule(), Set.of(), Map.of(),
                    Map.of("java.util", Set.of(SpottedListGovernor.class.getModule())), Set.of(), Map.of());
            }
            return MethodHandles.privateLookupIn(AbstractList.class, MethodHandles.lookup())
                .findVarHandle(AbstractList.class, "modCount", int.class);
        } catch (Throwable t) {
            PZOLogger.warn("[SpottedListGovernor] AbstractList.modCount unavailable, governor disabled: " + t);
            active = false;
            return null;
        }
    }

    public static void setActive(boolean value) {
        active = value;
        if (!value) {
            synchronized (mirrors) {
                java.util.Arrays.fill(mirrors, null);
            }
        }
    }

    private static Mirror find(Stack<?> s, boolean create) {
        int free = -1;
        for (int i = 0; i < mirrors.length; i++) {
            Mirror m = mirrors[i];
            Stack<?> ms = m == null ? null : m.stack.get();
            if (ms == s) {
                return m;
            }
            if (ms == null && free < 0) {
                free = i;
            }
        }
        if (!create || free < 0) {
            return null;
        }
        return mirrors[free] = new Mirror(s);
    }

    public static boolean contains(Stack<?> s, Object o) {
        if (!active || s.size() < MIN_SIZE) {
            return s.contains(o);
        }
        synchronized (s) {
            Mirror m;
            synchronized (mirrors) {
                m = find(s, true);
            }
            if (m == null) {
                return s.contains(o);
            }
            int mc = (int) MOD_COUNT.get((AbstractList<?>) s);
            if (m.modCount != mc || m.size != s.size()) {
                m.set.clear();
                m.set.addAll(s);
                m.modCount = mc;
                m.size = s.size();
                rebuilds.incrementAndGet();
            }
            lookupsHashed.incrementAndGet();
            return m.set.contains(o);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean add(Stack s, Object o) {
        if (!active) {
            return s.add(o);
        }
        synchronized (s) {
            Mirror m;
            synchronized (mirrors) {
                m = find(s, false);
            }
            boolean inSync = m != null && m.modCount == (int) MOD_COUNT.get((AbstractList<?>) s) && m.size == s.size();
            boolean r = s.add(o);
            if (inSync) {
                m.set.add(o);
                m.modCount = (int) MOD_COUNT.get((AbstractList<?>) s);
                m.size = s.size();
            }
            return r;
        }
    }
}
