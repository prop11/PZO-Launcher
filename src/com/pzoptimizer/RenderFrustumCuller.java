package com.pzoptimizer;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PZO Screen-Space Frustum Draw Culler (Build 42 Rendering Acceleration).
 * 
 * Intercepts 2D sprite draw calls before they enter SpriteRenderer command buffers.
 * If a sprite's screen-projected bounding box falls completely outside the active display viewport
 * (with a generous 64px safety bleed margin for tall trees and wall tops), the draw command is discarded.
 * 
 * - Only active in Unstable / Beta channel builds.
 * - Saves CPU command buffer memory and GPU rasterization bandwidth.
 * - 100% thread-safe with real-time atomic telemetry.
 */
public final class RenderFrustumCuller {

    public static final AtomicLong drawCallsCulled = new AtomicLong(0);
    private static final float BLEED_MARGIN = 64.0f;

    private static volatile int screenWidth = 1920;
    private static volatile int screenHeight = 1080;
    private static volatile long lastDimensionCheck = 0;

    public static boolean shouldRender(float x, float y, float width, float height) {
        if (!UnstableChannelGuard.isUnstableBuild()) {
            return true; // Pass through in stable builds
        }

        updateScreenDimensions();

        // Check if bounding box is completely outside viewport with bleed margin
        if ((x + width) < -BLEED_MARGIN || x > (screenWidth + BLEED_MARGIN)
                || (y + height) < -BLEED_MARGIN || y > (screenHeight + BLEED_MARGIN)) {
            drawCallsCulled.incrementAndGet();
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
                Method getInstMethod = coreClass.getMethod("getInstance");
                Object coreInst = getInstMethod.invoke(null);
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

    public static long getCulledCount() {
        return drawCallsCulled.get();
    }

    public static void reset() {
        drawCallsCulled.set(0);
    }
}
