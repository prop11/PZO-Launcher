package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Global Active-Ragdoll Fast-Path Gate Governor.
 * Vanilla IsoGameCharacter.isRagdoll() is invoked tens of thousands of times per frame during
 * zombie separation, batch filtering, and postupdate collisions. Each call invokes getAnimationPlayer(),
 * which queries ModelManager.getBodyModel(this) and dereferences 3 objects (SurvivorDesc ->
 * AnimationPlayer -> RagdollController), causing severe CPU cache misses across 4,000+ zombies
 * even when zero ragdolls exist in the world (99% of frames).
 *
 * RagdollGateGovernor inspects the character's AnimationPlayer field directly without triggering
 * ModelManager.getBodyModel(this), returning false immediately when no RagdollController is attached.
 */
public final class RagdollGateGovernor {

    private static volatile boolean active = true;
    private static final AtomicInteger activeRagdollCount = new AtomicInteger(0);

    private static Field animPlayerField = null;
    private static Field ragdollControllerField = null;
    private static Method stockIsRagdollingMethod = null;
    private static boolean reflectionInitialized = false;
    private static boolean reflectionFailed = false;

    public static final AtomicLong fastPathChecksSaved = new AtomicLong(0);
    public static final AtomicLong fullPathChecks = new AtomicLong(0);

    private RagdollGateGovernor() {}

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean val) {
        active = val;
    }

    public static int getActiveRagdollCount() {
        return activeRagdollCount.get();
    }

    public static void onRagdollInit() {
        activeRagdollCount.incrementAndGet();
    }

    public static void onRagdollReleased() {
        int current;
        do {
            current = activeRagdollCount.get();
            if (current <= 0) return;
        } while (!activeRagdollCount.compareAndSet(current, current - 1));
    }

    private static synchronized void initReflection(Object chr) {
        if (reflectionInitialized || reflectionFailed || chr == null) return;
        try {
            Class<?> c = chr.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    String ftype = f.getType().getName();
                    String fname = f.getName();
                    if (ftype.endsWith("AnimationPlayer") || "animationPlayer".equals(fname) || "animPlayer".equals(fname)) {
                        f.setAccessible(true);
                        animPlayerField = f;
                        break;
                    }
                }
                if (animPlayerField != null) break;
                c = c.getSuperclass();
            }
            if (animPlayerField != null) {
                Class<?> apCls = animPlayerField.getType();
                try {
                    stockIsRagdollingMethod = apCls.getDeclaredMethod("isRagdolling");
                    stockIsRagdollingMethod.setAccessible(true);
                } catch (Throwable ignored) {}
                for (Field af : apCls.getDeclaredFields()) {
                    if (af.getName().toLowerCase().contains("ragdollcontroller")) {
                        af.setAccessible(true);
                        ragdollControllerField = af;
                        break;
                    }
                }
            }
            reflectionInitialized = (animPlayerField != null);
            if (!reflectionInitialized) {
                reflectionFailed = true;
            }
        } catch (Throwable t) {
            reflectionFailed = true;
        }
    }

    /**
     * Direct replacement for IsoGameCharacter.isRagdoll()Z.
     * Bypasses getAnimationPlayer()'s expensive ModelManager.getBodyModel(this) check.
     */
    public static boolean isRagdollFast(Object chr) {
        if (chr == null) return false;
        if (!reflectionInitialized && !reflectionFailed) {
            initReflection(chr);
        }
        if (reflectionInitialized) {
            try {
                Object ap = animPlayerField.get(chr);
                if (ap == null) {
                    if (active) fastPathChecksSaved.incrementAndGet();
                    return false;
                }
                if (active && ragdollControllerField != null && ragdollControllerField.get(ap) == null) {
                    fastPathChecksSaved.incrementAndGet();
                    return false;
                }
                fullPathChecks.incrementAndGet();
                if (stockIsRagdollingMethod != null) {
                    return ((Boolean) stockIsRagdollingMethod.invoke(ap)).booleanValue();
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    public static void reset() {
        activeRagdollCount.set(0);
    }
}
