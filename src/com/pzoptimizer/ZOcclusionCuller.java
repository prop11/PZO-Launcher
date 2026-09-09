package com.pzoptimizer;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

public final class ZOcclusionCuller {

    public static final AtomicLong subterraneanTilesCulled = new AtomicLong(0);

    private static volatile int cachedPlayerZ = 0;
    private static volatile long lastPlayerZCheck = 0;

    public static boolean shouldRenderZLevel(int z) {
        if (!UnstableChannelGuard.isUnstableBuild()) {
            return true; // Pass through in stable builds
        }

        if (z >= 0) {
            return true; // Surface and above are always rendered normally
        }

        updatePlayerZ();

        if (cachedPlayerZ >= 0) {
            subterraneanTilesCulled.addAndGet(64); // 8x8 tile layer equivalent
            return false;
        }

        return true;
    }

    private static void updatePlayerZ() {
        long now = System.currentTimeMillis();
        if (now - lastPlayerZCheck > 250L) {
            lastPlayerZCheck = now;
            try {
                Class<?> playerClass = Class.forName("zombie.characters.IsoPlayer");
                Method getInst = playerClass.getMethod("getInstance");
                Object player = getInst.invoke(null);
                if (player != null) {
                    Method getZMethod = playerClass.getMethod("getZ");
                    float z = ((Number) getZMethod.invoke(player)).floatValue();
                    cachedPlayerZ = (int) z;
                }
            } catch (Throwable ignored) {}
        }
    }

    public static long getCulledCount() {
        return subterraneanTilesCulled.get();
    }

    public static void reset() {
        subterraneanTilesCulled.set(0);
    }
}
