package com.pzoptimizer;

import java.lang.reflect.Field;

public final class CorpseAudioGovernor {

    public static void applyCorpseAudioLimits() {
        try {
            Class<?> fliesClass = Class.forName("zombie.FliesSound");
            Field maxCorpseField = fliesClass.getField("maxCorpseCount");
            int currentMax = maxCorpseField.getInt(null);
            
            if (currentMax > 12) {
                maxCorpseField.setInt(null, 12);
                PZOLogger.success("CorpseAudioGovernor: Paced corpse audio emitters (maxCorpseCount = 12)");
            }
        } catch (Throwable t) {
            PZOLogger.info("CorpseAudioGovernor: FliesSound hook skipped: " + t.getMessage());
        }
    }
}
