package com.pzoptimizer;

public class HordeHibernationEngine {
    private static int frameCounter = 0;
    private static final int HIBERNATION_DISTANCE_SQ = 35 * 35; // 1225 tiles^2
    private static final int FAR_DISTANCE_SQ = 60 * 60;         // 3600 tiles^2

    public static boolean shouldProcessZombieAI(int zombieIndex, int zombieId) {
        int tier = HordeSpatialCuller.getLODTier(zombieIndex);
        if (tier <= 1) {
            return true;
        } else if (tier == 2) {
            int slot = Math.abs(zombieId) % 3;
            return (frameCounter % 3) == slot;
        } else {
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

        int slot = Math.abs(zombieId) % 3;
        return (frameCounter % 3) == slot;
    }

    public static void onFrameTick() {
        frameCounter = (frameCounter + 1) % 60000;
    }
}
