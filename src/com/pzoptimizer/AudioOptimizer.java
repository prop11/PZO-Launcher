package com.pzoptimizer;

/**
 * Project Zomboid Build 42 - Audio Pipeline & Thread Priority Protector.
 * Preserves 100% vanilla and modded sound fidelity with zero voice dropping or clipping.
 */
public class AudioOptimizer {

    public static boolean shouldPlayAudio(String soundName) {
        // 100% transparent: never drop any gameplay or zombie survival sounds
        return true;
    }

    public static boolean shouldProcessSpatialEmitter(float distSq, float volume) {
        // 100% transparent: allow FMOD native spatial processing to handle attenuation naturally
        return true;
    }

    public static void onVoiceStart() {}
    public static void onVoiceEnd() {}
    public static void onFireVoiceStart() {}
    public static void onFireVoiceEnd() {}
}
