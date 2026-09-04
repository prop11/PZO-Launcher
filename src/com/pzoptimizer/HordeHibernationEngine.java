package com.pzoptimizer;

/**
 * Project Zomboid Build 42 - Distant Horde AI & Spatial Staggering Engine (Phase 3).
 * Staggers expensive pathfinding and sensory checks for distant zombies (>32 tiles)
 * across interleaved frames to eliminate frame drops in high-density zombie towns.
 * 
 * Leverages HordeSpatialCuller's AVX2 multi-tier classification for zero-math O(1) checks.
 */
public class HordeHibernationEngine {
    private static int frameCounter = 0;
    private static final int HIBERNATION_DISTANCE_SQ = 35 * 35; // 1225 tiles^2
    private static final int FAR_DISTANCE_SQ = 60 * 60;         // 3600 tiles^2

    public static boolean shouldProcessZombieAI(int zombieIndex, int zombieId) {
        int tier = HordeSpatialCuller.getLODTier(zombieIndex);
        if (tier <= 1) {
            return true; // Close zombies (<32 tiles) always process at full 60 FPS
        } else if (tier == 2) {
            // Medium-far zombies (32-50 tiles): stagger every 3rd frame
            int slot = Math.abs(zombieId) % 3;
            return (frameCounter % 3) == slot;
        } else {
            // Out of range (>50 tiles / offscreen): stagger every 6th frame
            int slot = Math.abs(zombieId) % 6;
            return (frameCounter % 6) == slot;
        }
    }

    public static boolean shouldProcessZombieAI(float playerX, float playerY, float zombieX, float zombieY, int zombieId) {
        float dx = zombieX - playerX;
        float dy = zombieY - playerY;
        float distSq = dx * dx + dy * dy;

        // Close-range zombies (<35 tiles) always update every single frame (60 FPS)
        if (distSq < HIBERNATION_DISTANCE_SQ) {
            return true;
        }

        if (distSq > FAR_DISTANCE_SQ) {
            int slot = Math.abs(zombieId) % 6;
            return (frameCounter % 6) == slot;
        }

        // Distant zombies: stagger updates across 3 interleaved frames based on ID
        int slot = Math.abs(zombieId) % 3;
        return (frameCounter % 3) == slot;
    }

    public static void onFrameTick() {
        frameCounter = (frameCounter + 1) % 60000;
    }
}
