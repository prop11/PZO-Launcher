package com.pzoptimizer;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PZO Off-Screen Skeletal Skinning Governor (3D Model Animation Culler).
 * 
 * In Project Zomboid Build 42, 3D animated models (zombies, characters, animals) compute
 * full 60+ bone matrix skinning transforms on the CPU every frame for all active entities in the cell.
 * 
 * ModelSkinningGovernor checks whether a 3D model's screen projection is within the visible display frustum
 * (with a 128px safety buffer for wide limb sweeps):
 * - If outside the viewport: skips CPU skeletal skinning matrix recalculations.
 * - If inside the viewport: runs at full 100% native animation fidelity.
 * - Only active in Unstable / Beta channel builds.
 * - Saves millions of matrix multiplications during dense horde encounters.
 */
public final class ModelSkinningGovernor {

    public static final AtomicLong boneTransformsSaved = new AtomicLong(0);
    private static final float PADDING = 128.0f;

    private static volatile int screenWidth = 1920;
    private static volatile int screenHeight = 1080;
    private static volatile long lastDimensionCheck = 0;

    public static boolean shouldSkinModel(float screenX, float screenY) {
        if (!UnstableChannelGuard.isUnstableBuild()) {
            return true; // Pass through in stable builds
        }

        updateScreenDimensions();

        // Check if model's screen projection is completely outside screen plus padding
        if (screenX < -PADDING || screenX > (screenWidth + PADDING)
                || screenY < -PADDING || screenY > (screenHeight + PADDING)) {
            boneTransformsSaved.addAndGet(64); // Average 64 bone transforms per character model
            return false;
        }

        return true;
    }

    private static void updateScreenDimensions() {
        long now = System.currentTimeMillis();
        if (now - lastDimensionCheck > 2000L) {
            lastDimensionCheck = now;
            try {
                Class<?> coreClass = Class.forName("zombie.core.Core");
                Method getInst = coreClass.getMethod("getInstance");
                Object coreInst = getInst.invoke(null);
                if (coreInst != null) {
                    Method getW = coreClass.getMethod("getScreenWidth");
                    Method getH = coreClass.getMethod("getScreenHeight");
                    int w = ((Number) getW.invoke(coreInst)).intValue();
                    int h = ((Number) getH.invoke(coreInst)).intValue();
                    if (w > 0 && h > 0) {
                        screenWidth = w;
                        screenHeight = h;
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    public static long getSavedCount() {
        return boneTransformsSaved.get();
    }

    public static void reset() {
        boneTransformsSaved.set(0);
    }
}
