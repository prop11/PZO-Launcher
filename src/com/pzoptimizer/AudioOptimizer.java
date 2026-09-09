package com.pzoptimizer;

public class AudioOptimizer {

    public static boolean shouldPlayAudio(String soundName) {
        return true;
    }

    public static boolean shouldProcessSpatialEmitter(float distSq, float volume) {
        return true;
    }

    public static void onVoiceStart() {}
    public static void onVoiceEnd() {}
    public static void onFireVoiceStart() {}
    public static void onFireVoiceEnd() {}
}
