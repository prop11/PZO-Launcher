package com.pzoptimizer;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Animals' animations read while the save loads (JVM_AnimSetsAhead).
 *
 * The game reads a character's animation set (every state folder under media/AnimSets/<set>, one XML each) and its
 * action group the first time a character of that kind is made. Players and zombies are made while the world loads,
 * but an animal kind that isn't near the player then gets read in the frame the first one shows up: a hitch the first
 * time you meet a deer, a pig or a rat. Every animal definition's set and group are now read at the end of the
 * loading screen, by the same names and on the same loading thread the game uses, so the result is the same.
 */
public final class AnimSetsAhead {

    public static volatile boolean active = true;
    private static volatile int lastCount;
    private static volatile long lastNanos;

    private AnimSetsAhead() {}

    /** GameLoadingState.SendDone / Done, through ModelShadersAhead.worldLoaded. */
    static void worldLoaded() {
        if (!active) return;
        // These tables are plain HashMaps the loading thread fills while a save loads; any other caller stays out
        if (!"GameLoadingThread".equals(Thread.currentThread().getName())) return;
        try {
            long t0 = System.nanoTime();
            Set<String> names = new LinkedHashSet<>();
            for (zombie.characters.animals.AnimalDefinitions def : zombie.characters.animals.AnimalDefinitions.getAnimalDefsArray()) {
                if (def != null && def.animset != null && !def.animset.isEmpty()) names.add(def.animset);
            }
            for (String n : names) {
                zombie.core.skinnedmodel.advancedanimation.AnimationSet.GetAnimationSet(n, false);
                zombie.characters.action.ActionGroup.getActionGroup(n);
            }
            lastCount = names.size();
            lastNanos = System.nanoTime() - t0;
            PZOLogger.info(String.format("[AnimSetsAhead] %d animal animation sets read with the save in %.0f ms", lastCount, lastNanos / 1e6));
        } catch (Throwable t) {
            PZOLogger.warn("[AnimSetsAhead] left to the game: " + t);
        }
    }

    public static String summary() {
        return "sets=" + lastCount + " ms=" + (lastNanos / 1_000_000L);
    }
}
