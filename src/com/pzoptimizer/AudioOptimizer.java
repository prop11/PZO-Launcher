package com.pzoptimizer;

/** Audio hooks that deliberately keep vanilla behaviour; only parameter upkeep is rate-limited. */
public class AudioOptimizer {

    public static boolean shouldPlayAudio(String soundName) {
        // Never drop gameplay or zombie sounds
        return true;
    }

    public static boolean shouldProcessSpatialEmitter(float distSq, float volume) {
        // Leave attenuation to FMOD's own spatial processing
        return true;
    }

    public static void onVoiceStart() {}
    public static void onVoiceEnd() {}
    public static void onFireVoiceStart() {}
    public static void onFireVoiceEnd() {}

    public static boolean isParameterUpkeepDue(int frameCount) {
        return AudioCadenceGovernor.isCadenceTickDue(frameCount);
    }
}
