package com.pzoptimizer;

import zombie.characters.IsoPlayer;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.behavior.BaseAnimalBehavior;
import zombie.iso.IsoCamera;

/**
 * Throttles IsoAnimal.updateLOS() for calm animals far from every local player.
 * Vanilla walks the whole cell object list for every animal every frame to find nearby zombies and
 * players. Distant calm animals run that walk on a staggered 1-in-N frame cadence instead, so livestock
 * still notices an approaching zombie within a fraction of a second.
 *
 * Animals that have spotted something or were recently alerted always run the full walk.
 */
public final class AnimalLosSkipGovernor {

    private static volatile boolean active = true;
    private static final float PLAYER_REACH_SQ = 22.0f * 22.0f;
    private static final int DISTANT_CADENCE_FRAMES = 15;

    private static long animalWalksSkipped = 0L;
    private static long animalWalksExecuted = 0L;

    private AnimalLosSkipGovernor() {}

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean val) {
        active = val;
    }

    public static long getAnimalWalksSkipped() {
        return animalWalksSkipped;
    }

    public static long getAnimalWalksExecuted() {
        return animalWalksExecuted;
    }

    /** Prefix hook on IsoAnimal.updateLOS(): returning true skips the vanilla body for this frame. */
    public static boolean shouldSkipUpdateLOS(Object obj) {
        if (!active) return false;
        IsoAnimal animal = (IsoAnimal) obj;

        if (animal.spottedChr != null) {
            animalWalksExecuted++;
            return false;
        }
        BaseAnimalBehavior behavior = animal.getBehavior();
        if (behavior != null && behavior.lastAlerted > 0.0f) {
            animalWalksExecuted++;
            return false;
        }

        float ax = animal.getX();
        float ay = animal.getY();
        IsoPlayer[] players = IsoPlayer.players;
        for (int i = 0; i < players.length; i++) {
            IsoPlayer p = players[i];
            if (p != null) {
                float dx = ax - p.getX();
                float dy = ay - p.getY();
                if (dx * dx + dy * dy <= PLAYER_REACH_SQ) {
                    animalWalksExecuted++;
                    return false;
                }
            }
        }

        // Stagger by identity so a herd does not all scan on the same frame
        int phase = Math.floorMod(IsoCamera.frameState.frameCount + System.identityHashCode(animal), DISTANT_CADENCE_FRAMES);
        if (phase == 0) {
            animalWalksExecuted++;
            return false;
        }
        animalWalksSkipped++;
        return true;
    }
}
