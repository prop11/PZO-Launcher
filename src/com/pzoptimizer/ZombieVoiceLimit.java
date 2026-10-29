package com.pzoptimizer;

/**
 * Settings screen "Horde voice limit" (Horde_AudioConcurrencyLimit + Horde_VoiceLimit).
 *
 * ZombieVocalsManager.update gives the nearest zombies, re-sorted every 0.5 s, one of its 20 voice slots:
 * count = PZMath.min(slots.length, zombies.size()). That call is redirected here so fewer of those slots are filled;
 * zombies past the limit are the farthest ones and simply stay quiet, as zombies past the 20th already do in vanilla.
 */
public final class ZombieVoiceLimit {

    public static volatile boolean active = true;
    private static volatile int limit = 16;

    private ZombieVoiceLimit() {}

    public static void setLimit(int value) {
        limit = Math.max(4, Math.min(20, value));
    }

    /** Replaces PZMath.min(slots, zombies) in ZombieVocalsManager.update. */
    public static int count(int slots, int zombies) {
        int n = Math.min(slots, zombies);
        return active ? Math.min(n, limit) : n;
    }
}
