package com.pzoptimizer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicLong;
import zombie.MovingObjectUpdateScheduler;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.iso.IsoMovingObject;

/**
 * Replaces the call to MovingObjectUpdateScheduler.getUpdateSchedulerSimulationLevelForObject in startFrame.
 * Vanilla lowers the update rate of rendered zombies no player can see (alpha below 0.25), then raises every
 * object one level for each of the 25/35/45/55 average-FPS thresholds, so above 55 FPS unseen zombies are back to
 * a full update every frame. For those unseen zombies only, the vanilla method is evaluated again with the FPS
 * clamped to capFps, i.e. exactly the level vanilla itself picks at that frame rate. Visible zombies, players,
 * other objects and each state's minimum simulation level are untouched. Also counts objects per level.
 */
public final class UnseenZombieSimGovernor {

    private static volatile boolean active = true;
    private static volatile float capFps = 30.0f;

    public static final AtomicLong zombiesCapped = new AtomicLong();
    public static final AtomicLong frames = new AtomicLong();
    // Zombies assigned per level, indexed by UpdateSchedulerSimulationLevel ordinal (SIXTEENTH .. FULL)
    public static final AtomicLong[] zombiesPerLevel = new AtomicLong[UpdateSchedulerSimulationLevel.numValues()];

    private static final MethodHandle VANILLA = lookupVanilla();

    static {
        for (int i = 0; i < zombiesPerLevel.length; i++) zombiesPerLevel[i] = new AtomicLong();
    }

    private UnseenZombieSimGovernor() {}

    private static MethodHandle lookupVanilla() {
        try {
            return MethodHandles.privateLookupIn(MovingObjectUpdateScheduler.class, MethodHandles.lookup())
                .findVirtual(MovingObjectUpdateScheduler.class, "getUpdateSchedulerSimulationLevelForObject",
                    MethodType.methodType(UpdateSchedulerSimulationLevel.class, IsoMovingObject.class, float.class));
        } catch (Throwable t) {
            PZOLogger.warn("[UnseenZombieSimGovernor] scheduler level method unavailable: " + t);
            return null;
        }
    }

    public static void setActive(boolean value) {
        active = value;
    }

    public static void setCapFps(float value) {
        capFps = value;
    }

    public static UpdateSchedulerSimulationLevel level(MovingObjectUpdateScheduler scheduler, IsoMovingObject obj, float averageFps) throws Throwable {
        UpdateSchedulerSimulationLevel sim = (UpdateSchedulerSimulationLevel) VANILLA.invokeExact(scheduler, obj, averageFps);
        if (obj instanceof IsoZombie) {
            float cap = capFps;
            // A Real Limbs body steps its physics in the zombie's update, so a capped rate would stall it
            if (active && averageFps > cap && sim != UpdateSchedulerSimulationLevel.minimum() && isUnseen(obj)
                    && !RealLimbsCompat.isBody(obj) && !ModApi.isFullRate((IsoZombie) obj)) {
                UpdateSchedulerSimulationLevel capped = (UpdateSchedulerSimulationLevel) VANILLA.invokeExact(scheduler, obj, cap);
                if (capped.ordinal() < sim.ordinal()) {
                    sim = capped;
                    zombiesCapped.incrementAndGet();
                }
            }
            zombiesPerLevel[sim.ordinal()].incrementAndGet();
        }
        return sim;
    }

    // Same test vanilla uses to demote a rendered object: no player sees it at alpha 0.25 or above, now or as a target
    private static boolean isUnseen(IsoMovingObject obj) {
        if (!obj.getDoRender() || obj.isSceneCulled()) {
            return false;
        }
        for (int i = 0; i < IsoPlayer.numPlayers; i++) {
            if (IsoPlayer.players[i] != null && (obj.getAlpha(i) >= 0.25f || obj.getTargetAlpha(i) >= 0.25f)) {
                return false;
            }
        }
        return true;
    }
}
