package com.pzoptimizer;

/**
 * Project Zomboid Build 42 - Distant Horde AI & Spatial Staggering Engine.
 * Staggers expensive pathfinding and sensory checks for distant zombies (>35 tiles)
 * across interleaved frames to eliminate frame drops in high-density zombie towns.
 */
public class HordeHibernationEngine {
    private static int frameCounter = 0;
    private static final int HIBERNATION_DISTANCE_SQ = 35 * 35; // 1225 tiles^2

    public static boolean shouldProcessZombieAI(float playerX, float playerY, float zombieX, float zombieY, int zombieId) {
        float dx = zombieX - playerX;
        float dy = zombieY - playerY;
        float distSq = dx * dx + dy * dy;

        // Close-range zombies (<35 tiles) always update every single frame (60 FPS)
        if (distSq < HIBERNATION_DISTANCE_SQ) {
            return true;
        }

        // Distant zombies: stagger updates across 3 interleaved frames based on ID
        int slot = Math.abs(zombieId) % 3;
        return (frameCounter % 3) == slot;
    }

    public static void onFrameTick() {
        frameCounter = (frameCounter + 1) % 60000;
    }
}
