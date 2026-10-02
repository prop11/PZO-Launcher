package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Project Zomboid Build 42 - Rain & Weather Travel Performance Optimizer.
 * Forensically addresses the critical bottlenecks causing major hitching and stuttering
 */
public final class RainAndWeatherOptimizer {

    private static volatile boolean initialized = false;

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        applyLightingSplitUpdate();
        applyPuddlesElevationCap();
        ensureWeatherMaskingRestored();
        GLPipelineGovernor.setActive(true);
        FogQuarterBufferGovernor.setActive(true);
        PZOLogger.success("[RainAndWeatherOptimizer] Rain & Weather Driving Governor initialized (Quarter-Buffer Fog & Zero-Stall Pipeline armed)");
    }

    public static void checkAndMaintain() {
        if (!initialized) {
            initialize();
        }
    }

    /**
     * Ensures lightingSplitUpdate remains disabled.
     * When true, FBORenderCell caps chunk updates to 5 chunks/frame, causing flashlight flickering
     * and preventing other players' flashlights in multiplayer from illuminating.
     */
    public static void applyLightingSplitUpdate() {
        try {
            Class<?> debugOptionsClass = Class.forName("zombie.debug.DebugOptions");
            Field instanceField = debugOptionsClass.getField("instance");
            Object debugOptions = instanceField.get(null);
            if (debugOptions != null) {
                Field splitField = debugOptionsClass.getField("lightingSplitUpdate");
                Object splitOption = splitField.get(debugOptions);
                if (splitOption != null) {
                    Method setValueMethod = splitOption.getClass().getMethod("setValue", boolean.class);
                    setValueMethod.invoke(splitOption, false);
                }
            }
        } catch (Throwable t) {
            PZOLogger.info("[RainAndWeatherOptimizer] lightingSplitUpdate notice: " + t.getMessage());
        }
    }

    /**
     * Enforces ground-level puddles (perfPuddles = 1) if currently set to all 32 levels (0).
     */
    public static void applyPuddlesElevationCap() {
        try {
            Class<?> coreClass = Class.forName("zombie.core.Core");
            Method getInstMethod = coreClass.getMethod("getInstance");
            Object core = getInstMethod.invoke(null);
            if (core != null) {
                Method getPerfPuddlesMethod = coreClass.getMethod("getPerfPuddles");
                int currentPuddles = (int) getPerfPuddlesMethod.invoke(core);
                if (currentPuddles < 2) {
                    // Option 0 is All Levels, 1 is Ground with Ruts (8 neighbor scans/square). Level 2 is Ground Only (neighbor lookups skipped).
                    Method setPerfPuddlesMethod = coreClass.getMethod("setPerfPuddles", int.class);
                    setPerfPuddlesMethod.invoke(core, 2);
                    PZOLogger.success("[RainAndWeatherOptimizer] Puddle scanning set to Ground Only (Z=0, 8-neighbor lookups bypassed)");
                }
            }
        } catch (Throwable t) {
            PZOLogger.info("[RainAndWeatherOptimizer] Puddles elevation cap notice: " + t.getMessage());
        }
    }

    /**
     * Ensures WeatherFxMask.maskingEnabled is unconditionally active (prevents raw gray box indoors).
     */
    private static void ensureWeatherMaskingRestored() {
        try {
            Class<?> maskClass = Class.forName("zombie.iso.weather.fx.WeatherFxMask");
            Field maskingField = maskClass.getField("maskingEnabled");
            maskingField.setBoolean(null, true);
        } catch (Throwable ignored) {}
    }

    /**
     * Puddle Geometry & Vertex Array Cache.
     * Caches 32-float packed puddle vertex batches per chunk level to avoid rebuilding
     * ~8,000 vertex records from scratch each frame during rain and thunderstorms.
     */
    public static final class PuddleMeshCache {
        private static final java.util.Map<Long, float[]> cachedPuddleVertices = new java.util.concurrent.ConcurrentHashMap<>();
        private static final java.util.concurrent.atomic.AtomicLong totalPuddleBatchesReused = new java.util.concurrent.atomic.AtomicLong(0);

        public static float[] getCachedBatch(int wx, int wy, int level) {
            long key = (((long) wx) << 36) | ((((long) wy) & 0xFFFFFFFFL) << 4) | (level & 0xFL);
            float[] batch = cachedPuddleVertices.get(key);
            if (batch != null) {
                totalPuddleBatchesReused.incrementAndGet();
            }
            return batch;
        }

        public static void putCachedBatch(int wx, int wy, int level, float[] vertices) {
            if (vertices == null || vertices.length == 0) return;
            long key = (((long) wx) << 36) | ((((long) wy) & 0xFFFFFFFFL) << 4) | (level & 0xFL);
            cachedPuddleVertices.put(key, vertices);
        }

        public static void invalidateChunk(int wx, int wy) {
            long prefix = (((long) wx) << 36) | ((((long) wy) & 0xFFFFFFFFL) << 4);
            cachedPuddleVertices.keySet().removeIf(k -> (k & ~0xFL) == prefix);
        }

        public static void clearAll() {
            cachedPuddleVertices.clear();
        }

        public static long getReusedBatchCount() {
            return totalPuddleBatchesReused.get();
        }
    }
}

