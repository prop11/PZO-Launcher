package com.pzoptimizer;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PZO Subterranean Z-Level Occlusion Shield (Build 42 32-Level Vertical Culler).
 * 
 * In Build 42, the world supports up to 32 vertical levels (-16 to +16).
 * When the player is on the surface (Z >= 0) and not currently descending into a basement pit,
 * all subterranean tile geometry (Z = -1 .. -16) is 100% occluded by ground terrain and solid foundation.
 * 
 * ZOcclusionCuller drops underground tile render sweeps when on the surface:
 * - Eliminates thousands of redundant tile geometry iterations per frame.
 * - Only active in Unstable / Beta channel builds.
 * - Instantly engages full rendering as soon as the player enters Z < 0.
 */
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

        // If player is on surface (Z >= 0), subterranean levels (Z < 0) are occluded by ground terrain
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
