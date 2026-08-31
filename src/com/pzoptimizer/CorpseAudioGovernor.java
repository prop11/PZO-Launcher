package com.pzoptimizer;

import java.lang.reflect.Field;

/**
 * PZO Corpse Audio Governor & Spatial Sound Limiter.
 * Dynamically caps dead body audio scan counts in massive slaughter zones
 * to prevent FMOD mixer thread contention while preserving immersive ambient flies.
 * 100% safe, reflection-based, and cross-platform.
 */
public final class CorpseAudioGovernor {

    public static void applyCorpseAudioLimits() {
        try {
            Class<?> fliesClass = Class.forName("zombie.FliesSound");
            Field maxCorpseField = fliesClass.getField("maxCorpseCount");
            int currentMax = maxCorpseField.getInt(null);
            
            // Limit from 25-50 down to a responsive 12 bodies per chunk
            if (currentMax > 12) {
                maxCorpseField.setInt(null, 12);
                PZOLogger.success("CorpseAudioGovernor: Paced corpse audio emitters (maxCorpseCount = 12)");
            }
        } catch (Throwable t) {
            PZOLogger.info("CorpseAudioGovernor: FliesSound hook skipped: " + t.getMessage());
        }
    }
}
