package com.pzoptimizer;

/** Staggers AI updates for distant zombies: every 3rd frame at medium range, every 6th beyond that, phased by zombie ID. */
public class HordeHibernationEngine {
    private static int frameCounter = 0;
    private static final int HIBERNATION_DISTANCE_SQ = 35 * 35; // 1225 tiles^2
    private static final int FAR_DISTANCE_SQ = 60 * 60;         // 3600 tiles^2

    public static boolean shouldProcessZombieAI(int zombieIndex, int zombieId) {
        int tier = HordeSpatialCuller.getLODTier(zombieIndex);
        if (tier <= 1) {
            return true; // LOD tiers 0-1: every frame
        } else if (tier == 2) {
            // tier 2: every 3rd frame
            int slot = Math.abs(zombieId) % 3;
            return (frameCounter % 3) == slot;
        } else {
            // beyond: every 6th frame
            int slot = Math.abs(zombieId) % 6;
            return (frameCounter % 6) == slot;
        }
    }

    public static boolean shouldProcessZombieAI(float playerX, float playerY, float zombieX, float zombieY, int zombieId) {
        float dx = zombieX - playerX;
        float dy = zombieY - playerY;
        float distSq = dx * dx + dy * dy;

        if (distSq < HIBERNATION_DISTANCE_SQ) {
            return true;
        }

        if (distSq > FAR_DISTANCE_SQ) {
            int slot = Math.abs(zombieId) % 6;
            return (frameCounter % 6) == slot;
        }

        // medium range: every 3rd frame, phased by ID
        int slot = Math.abs(zombieId) % 3;
        return (frameCounter % 3) == slot;
    }

    public static void onFrameTick() {
        frameCounter = (frameCounter + 1) % 60000;
    }
}
