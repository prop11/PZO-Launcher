package com.pzoptimizer;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Project Zomboid Build 42 - High-Performance FMOD Audio & Voice Optimizer.
 * Prevents audio thread CPU exhaustion and DSP channel starvation in 100+ zombie hordes.
 */
public class AudioOptimizer {
    private static final int MAX_CONCURRENT_VOICES = 32;
    private static final int MAX_FIRE_VOICES = 16;
    private static final AtomicInteger ACTIVE_VOICES = new AtomicInteger(0);
    private static final AtomicInteger ACTIVE_FIRE_VOICES = new AtomicInteger(0);

    public static boolean shouldPlayAudio(String soundName) {
        if (soundName == null) return true;

        if (soundName.contains("ZombieGroan") || soundName.contains("ZombieFootstep") || soundName.contains("ZombieAttack")) {
            return ACTIVE_VOICES.get() < MAX_CONCURRENT_VOICES;
        }

        if (soundName.contains("Fire") || soundName.contains("Burn") || soundName.contains("Flame")) {
            return ACTIVE_FIRE_VOICES.get() < MAX_FIRE_VOICES;
        }

        return true;
    }

    public static boolean shouldProcessSpatialEmitter(float distSq, float volume) {
        // Cull sub-audible DSP emitter calculations (> 35 tiles away or near-zero volume)
        if (distSq > 1225.0f || volume < 0.015f) {
            return false;
        }
        return true;
    }

    public static void onVoiceStart() {
        ACTIVE_VOICES.incrementAndGet();
    }

    public static void onVoiceEnd() {
        ACTIVE_VOICES.updateAndGet(v -> Math.max(0, v - 1));
    }

    public static void onFireVoiceStart() {
        ACTIVE_FIRE_VOICES.incrementAndGet();
    }

    public static void onFireVoiceEnd() {
        ACTIVE_FIRE_VOICES.updateAndGet(v -> Math.max(0, v - 1));
    }
}
