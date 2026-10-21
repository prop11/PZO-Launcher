package com.pzoptimizer;

import java.util.concurrent.atomic.AtomicInteger;
import zombie.characters.IsoGameCharacter;
import zombie.core.physics.RagdollController;
import zombie.util.IPooledObject;
import zombie.util.Pool;

/**
 * isRagdoll() is queried for every character many times per frame, but ragdolls are rare. The
 * AnimationPlayer redirects (RagdollController.alloc / Pool.tryRelease) keep an exact count of live
 * ragdoll controllers, so while none exist anywhere isRagdoll() is a single volatile read.
 * When any exist it runs vanilla's own logic through direct calls.
 *
 * The gate only engages once the agent confirms both tracking redirects applied; otherwise an
 * undercount could report a live ragdoll as not ragdolling.
 */
public final class RagdollGateGovernor {

    private static volatile boolean active = true;
    private static volatile boolean trackingInstalled = false;
    private static final AtomicInteger liveControllers = new AtomicInteger(0);

    private static long fastPathChecksSaved = 0L;

    private RagdollGateGovernor() {}

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean val) {
        active = val;
    }

    public static boolean isTrackingInstalled() {
        return trackingInstalled;
    }

    public static void markTrackingInstalled() {
        trackingInstalled = true;
    }

    public static int getActiveRagdollCount() {
        return liveControllers.get();
    }

    public static long getFastPathChecksSaved() {
        return fastPathChecksSaved;
    }

    /** Redirect target for RagdollController.alloc() inside AnimationPlayer.initRagdollController. */
    public static RagdollController allocController() {
        RagdollController c = RagdollController.alloc();
        if (c != null) {
            liveControllers.incrementAndGet();
        }
        return c;
    }

    /** Redirect target for Pool.tryRelease(IPooledObject) inside AnimationPlayer.releaseRagdollController. */
    public static IPooledObject releaseController(IPooledObject obj) {
        if (obj instanceof RagdollController) {
            int current;
            do {
                current = liveControllers.get();
                if (current <= 0) break;
            } while (!liveControllers.compareAndSet(current, current - 1));
        }
        return Pool.tryRelease(obj);
    }

    /** Full-body replacement for IsoGameCharacter.isRagdoll(). */
    public static boolean isRagdollFast(Object chr) {
        if (active && trackingInstalled && liveControllers.get() == 0) {
            fastPathChecksSaved++;
            return false;
        }
        IsoGameCharacter c = (IsoGameCharacter) chr;
        return c.hasAnimationPlayer() && c.getAnimationPlayer().isRagdolling();
    }

    public static void reset() {
        liveControllers.set(0);
    }
}
