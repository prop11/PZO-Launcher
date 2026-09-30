package com.pzoptimizer;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-Speed Player Line-Of-Sight (LOS) Query Accelerator.
 * In dense hordes, player line-of-sight tracking checks `lastSpotted` for each visible
 * zombie. The stock collection is a synchronized Vector/Stack, requiring an O(N) scan per zombie,
 * causing O(N^2) comparison cascades in large hordes.
 * 
 * PlayerLosOptimizer maintains an O(1) identity-hash mirror beside the tracking stack,
 * keeping the original stack intact while answering visibility membership probes in O(1) time.
 */
public final class PlayerLosOptimizer {

    private static volatile boolean active = true;
    public static final AtomicLong probesAccelerated = new AtomicLong(0);

    private final Set<Object> remembered = Collections.newSetFromMap(new IdentityHashMap<>());
    private Stack<?> mirroredStack = null;
    private int mirroredSize = -1;

    private static final ThreadLocal<PlayerLosOptimizer> LOCAL_INST = ThreadLocal.withInitial(PlayerLosOptimizer::new);

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    /**
     * Synchronizes internal identity set with the player's lastSpotted stack.
     */
    public void sync(Stack<?> stack) {
        if (stack == null) {
            remembered.clear();
            mirroredStack = null;
            mirroredSize = -1;
            return;
        }
        if (stack != mirroredStack || stack.size() != mirroredSize) {
            remembered.clear();
            int sz = stack.size();
            for (int i = 0; i < sz; i++) {
                try {
                    Object o = stack.get(i);
                    if (o != null) {
                        remembered.add(o);
                    }
                } catch (Throwable ignored) {
                    break;
                }
            }
            mirroredStack = stack;
            mirroredSize = sz;
        }
    }

    /**
     * Fast O(1) membership check.
     */
    public boolean contains(Stack<?> stack, Object target) {
        if (!active || target == null || stack == null) {
            return stack != null && target != null && stack.contains(target);
        }
        sync(stack);
        probesAccelerated.incrementAndGet();
        return remembered.contains(target);
    }

    /**
     * Adds an object to both the tracking stack and identity mirror.
     */
    @SuppressWarnings("unchecked")
    public boolean add(Stack<?> rawStack, Object target) {
        if (rawStack == null || target == null) return false;
        if (!active) {
            if (!rawStack.contains(target)) {
                ((Stack<Object>) rawStack).add(target);
                return true;
            }
            return false;
        }
        sync(rawStack);
        if (remembered.add(target)) {
            ((Stack<Object>) rawStack).add(target);
            mirroredSize = rawStack.size();
            return true;
        }
        return false;
    }

    /**
     * Clears both stack and mirror.
     */
    public void clear(Stack<?> stack) {
        if (stack != null) {
            stack.clear();
        }
        remembered.clear();
        mirroredStack = stack;
        mirroredSize = 0;
    }

    // Static facade methods for universal invocation

    public static boolean isObjectSpotted(Stack<?> stack, Object obj) {
        return LOCAL_INST.get().contains(stack, obj);
    }

    public static boolean recordObjectSpotted(Stack<?> stack, Object obj) {
        return LOCAL_INST.get().add(stack, obj);
    }

    public static void clearObjectSpotted(Stack<?> stack) {
        LOCAL_INST.get().clear(stack);
    }
}
