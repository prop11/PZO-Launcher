package com.pzoptimizer;

import java.util.concurrent.atomic.AtomicInteger;

public class AudioOptimizer {
    private static final int MAX_CONCURRENT_VOICES = 32;
    private static final AtomicInteger ACTIVE_VOICES = new AtomicInteger(0);

    public static boolean shouldPlayAudio(String soundName) {
        if (soundName != null && (soundName.contains("ZombieGroan") || soundName.contains("ZombieFootstep"))) {
            return ACTIVE_VOICES.get() < MAX_CONCURRENT_VOICES;
        }
        return true;
    }

    public static void onVoiceStart() {
        ACTIVE_VOICES.incrementAndGet();
    }

    public static void onVoiceEnd() {
        ACTIVE_VOICES.updateAndGet(v -> Math.max(0, v - 1));
    }
}
