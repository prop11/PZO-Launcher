package com.pzoptimizer.pzoe;

import zombie.characters.IsoPlayer;
import zombie.scripting.objects.MoodleType;

/**
 * The player's moodles for PZO Enhancements' Moodle Effects (screen.frag declares PZOMoodle):
 *
 *   uniform vec4 PZOMoodle;    panic, pain, tired, cold (hypothermia), each 0..1
 *   uniform vec4 PZOMoodle2;   heat (hyperthermia), drunk, card strength (0 = nothing to show), clock in seconds
 *
 * The game's moodle levels (0-4) step, so each eases toward its level over about a second and an effect fades in and
 * out instead of jumping. Main thread, from ScreenPass.main, per player.
 */
final class MoodleFx {

    static volatile long askedNanos;
    // Card strength (modApi "moodleFx")
    static volatile float amount = 1f;

    private static final float[][] eased = new float[4][6];
    private static final long[] lastNs = new long[4];
    private static final MoodleType[] TYPES = {MoodleType.PANIC, MoodleType.PAIN, MoodleType.TIRED, MoodleType.HYPOTHERMIA,
        MoodleType.HYPERTHERMIA, MoodleType.DRUNK};

    private static final float[] LEVEL = {0f, 0.2f, 0.45f, 0.75f, 1f};

    private MoodleFx() {}

    /** The 8 uniform values for this player, or null when there's nothing to show. */
    static float[] sample(int playerIndex) {
        if (playerIndex < 0 || playerIndex >= 4) return null;
        IsoPlayer p = IsoPlayer.players[playerIndex];
        float[] e = eased[playerIndex];
        long now = System.nanoTime();
        float dt = lastNs[playerIndex] == 0 ? 0f : Math.min(0.25f, (now - lastNs[playerIndex]) / 1e9f);
        lastNs[playerIndex] = now;
        float k = 1f - (float) Math.exp(-dt / 0.6);
        boolean any = false;
        for (int i = 0; i < TYPES.length; i++) {
            float want = 0f;
            if (p != null && !p.isDead() && p.getMoodles() != null) {
                // Level 1 is a faint hint, then it builds; starting at 2 left the common level-1 moodles showing nothing
                want = LEVEL[Math.max(0, Math.min(4, p.getMoodles().getMoodleLevel(TYPES[i])))];
            }
            e[i] += (want - e[i]) * k;
            if (e[i] > 0.005f) any = true;
        }
        if (!any) return null;
        return new float[] {e[0], e[1], e[2], e[3], e[4], e[5], amount, 1f};
    }
}
