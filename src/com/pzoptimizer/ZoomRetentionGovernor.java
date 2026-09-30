package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Zoom Retention Governor & FBO Eviction Damper.
 * In Build 42, zooming in shrinks the visible viewport and immediately evicts chunk FBO
 * textures that leave the screen. Zooming back out re-triggers hundreds of chunk bakes
 * simultaneously, causing severe 50-300ms frame drops.
 * 
 * ZoomRetentionGovernor retains baked chunk-level FBO textures within the bounding
 * rectangle of the widest possible camera zoom, avoiding mass re-bakes on zoom out.
 */
public final class ZoomRetentionGovernor {

    private static volatile boolean active = true;
    public static final AtomicLong texturesRetainedOnZoom = new AtomicLong(0);

    private static final float MARGIN_TILES = 8.0f;

    private static Field chunkXField = null;
    private static Field chunkYField = null;
    private static Method getCameraCharMethod = null;
    private static Method getCharXMethod = null;
    private static Method getCharYMethod = null;
    private static boolean reflectionInit = false;

    private static synchronized void initReflection() {
        if (reflectionInit) return;
        try {
            Class<?> isoCameraCls = Class.forName("zombie.iso.IsoCamera");
            getCameraCharMethod = isoCameraCls.getMethod("getCameraCharacter");

            Class<?> chunkCls = Class.forName("zombie.iso.IsoChunk");
            chunkXField = chunkCls.getField("wx");
            chunkYField = chunkCls.getField("wy");
        } catch (Throwable ignored) {}
        reflectionInit = true;
    }

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    /**
     * Determines whether a chunk's standard-scale FBO texture should be retained during zoom changes.
     */
    public static boolean shouldRetainNormal(Object chunk, int playerIndex, float maxZoom) {
        if (!active || chunk == null) return false;
        boolean retain = isWithinZoomBounds(chunk, maxZoom > 0.0f ? maxZoom : 2.5f);
        if (retain) {
            texturesRetainedOnZoom.incrementAndGet();
        }
        return retain;
    }

    /**
     * Checks if a chunk is within the screen bounds of a camera at the given zoom level.
     */
    public static boolean isWithinZoomBounds(Object chunk, float zoom) {
        initReflection();
        try {
            if (getCameraCharMethod == null) return false;
            Object character = getCameraCharMethod.invoke(null);
            if (character == null) return false;

            if (getCharXMethod == null) {
                getCharXMethod = character.getClass().getMethod("getX");
                getCharYMethod = character.getClass().getMethod("getY");
            }

            float cx = ((Number) getCharXMethod.invoke(character)).floatValue();
            float cy = ((Number) getCharYMethod.invoke(character)).floatValue();

            int wx = 0;
            int wy = 0;
            if (chunkXField != null && chunkYField != null) {
                wx = chunkXField.getInt(chunk);
                wy = chunkYField.getInt(chunk);
            }

            // Chunk world tile coordinates: chunk is 10x10 tiles
            float chunkMinX = wx * 10.0f;
            float chunkMaxX = chunkMinX + 10.0f;
            float chunkMinY = wy * 10.0f;
            float chunkMaxY = chunkMinY + 10.0f;

            // Viewport half-extent at zoom (approximate screen radius in world tiles)
            float viewExtent = 50.0f * zoom + MARGIN_TILES;

            return (chunkMaxX >= cx - viewExtent && chunkMinX <= cx + viewExtent &&
                    chunkMaxY >= cy - viewExtent && chunkMinY <= cy + viewExtent);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
