package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pre-warms DeadBodyAtlas's generic zombie LOD sprites. The first time a horde switches to flat LOD sprites
 * (IsoZombie.renderTextureInsteadOfModel -> DeadBodyAtlas.getBodyTexture), vanilla poses and renders all 128
 * generic body combinations (female/male x idle/walktoward x 8 directions x 4 frames) on the game thread in
 * one burst, a ~30 ms stutter. This requests 2 per frame starting 60 frames after world init, so the atlas
 * is filled before the first horde shows up.
 */
public final class BodyAtlasPrewarmGovernor {

    private static volatile boolean active = true;
    private static final int TOTAL_COMBINATIONS = 2 * 2 * 8 * 4; // 128 generic zombie body sprites
    private static final int DELAY_FRAMES = 60;
    private static final int PER_FRAME_BUDGET = 2;

    private static volatile int nextIndex = 0;
    private static volatile int worldFrames = 0;

    private static Object atlasInstance = null;
    private static Method getBodyTextureMethod = null;
    private static Method fromIndexMethod = null;
    private static boolean reflectionInitialized = false;
    private static boolean reflectionFailed = false;

    public static final AtomicLong spritesPrewarmed = new AtomicLong(0);

    private BodyAtlasPrewarmGovernor() {}

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean val) {
        active = val;
    }

    public static void reset() {
        nextIndex = 0;
        worldFrames = 0;
    }

    private static synchronized void initReflection() {
        if (reflectionInitialized || reflectionFailed) return;
        try {
            Class<?> atlasCls = Class.forName("zombie.core.skinnedmodel.DeadBodyAtlas");
            Field instField = atlasCls.getDeclaredField("instance");
            instField.setAccessible(true);
            atlasInstance = instField.get(null);

            Class<?> dirCls = Class.forName("zombie.iso.IsoDirections");
            fromIndexMethod = dirCls.getMethod("fromIndex", int.class);

            getBodyTextureMethod = atlasCls.getMethod(
                "getBodyTexture",
                boolean.class,
                String.class,
                String.class,
                dirCls,
                int.class,
                float.class
            );
            reflectionInitialized = (atlasInstance != null && getBodyTextureMethod != null && fromIndexMethod != null);
        } catch (Throwable t) {
            reflectionFailed = true;
        }
    }

    public static void onFrameTick() {
        if (!active || nextIndex >= TOTAL_COMBINATIONS || reflectionFailed) {
            return;
        }
        if (++worldFrames < DELAY_FRAMES) {
            return;
        }
        if (!reflectionInitialized) {
            initReflection();
            if (!reflectionInitialized) return;
        }

        try {
            for (int k = 0; k < PER_FRAME_BUDGET && nextIndex < TOTAL_COMBINATIONS; k++, nextIndex++) {
                int i = nextIndex;
                boolean female = (i & 1) != 0;
                boolean moving = ((i >> 1) & 1) != 0;
                int frame = (i >> 2) & 3;
                int dirIdx = (i >> 4) & 7;

                Object dir = fromIndexMethod.invoke(null, Integer.valueOf(dirIdx));
                float trackTime = (moving ? 0.67f : 1.0f) * (frame / 4.0f);
                getBodyTextureMethod.invoke(
                    atlasInstance,
                    Boolean.valueOf(female),
                    "zombie",
                    moving ? "walktoward" : "idle",
                    dir,
                    Integer.valueOf(frame),
                    Float.valueOf(trackTime)
                );
                spritesPrewarmed.incrementAndGet();
            }
            if (nextIndex == TOTAL_COMBINATIONS) {
                PZOLogger.success("[PZO BodyAtlasPrewarmGovernor] Pre-warmed all 128 generic zombie LOD body sprites into DeadBodyAtlas");
            }
        } catch (Throwable t) {
            nextIndex = TOTAL_COMBINATIONS;
        }
    }
}
