package com.pzoptimizer;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Player LOS checks test lastSpotted for every visible zombie, and the stock collection is a synchronized
 * Stack, so each test is a linear scan and big hordes go quadratic. This keeps an identity-hash mirror
 * next to the stack (which stays intact) for O(1) membership checks.
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

    public boolean contains(Stack<?> stack, Object target) {
        if (!active || target == null || stack == null) {
            return stack != null && target != null && stack.contains(target);
        }
        sync(stack);
        probesAccelerated.incrementAndGet();
        return remembered.contains(target);
    }

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

    public void clear(Stack<?> stack) {
        if (stack != null) {
            stack.clear();
        }
        remembered.clear();
        mirroredStack = stack;
        mirroredSize = 0;
    }

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
