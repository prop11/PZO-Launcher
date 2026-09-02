package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Project Zomboid Build 42 - Rain & Weather Travel Performance Optimizer.
 * 
 * Forensically addresses the critical bottlenecks causing major hitching and stuttering
 * while driving in the rain:
 * 
 * 1. Lighting.SplitUpdate Capping:
 *    During rainstorms, thunder and lightning mark chunks dirty in LightingJNI.
 *    Vanilla PZ defaults Lighting.SplitUpdate to false, causing FBORenderCell.updateChunkLighting
 *    to recalculate lighting across ALL 169 chunks in a single frame.
 *    Setting Lighting.SplitUpdate = true caps this to 5 chunks per frame.
 * 
 * 2. Puddle Elevation Floor-Lock:
 *    Vanilla PZ options allow perfPuddles = 0 (All Levels).
 *    FBORenderCell.renderPuddles then scans all chunks across all 32 vertical levels (Z=0 to Z=31)
 *    and issues draw calls for each level.
 *    Restricting perfPuddles to 1 (Ground Floor Only) limits scanning strictly to Z=0,
 *    cutting 97% of redundant upper-floor chunk puddle traversal.
 * 
 * 3. WeatherFxMask Vehicle Bypass:
 *    When driving down roads at high speed, crossing 30-50 squares/sec, WeatherFxMask
 *    invalidates its cache constantly and runs rasterize.scanTriangle across the entire screen,
 *    scanning up to 30,000 mask entries down 32 levels, and performing 4 full-screen FBO render passes.
 *    While the player is driving, WeatherFxMask.maskingEnabled is bypassed so weather particles
 *    render directly without FBO churn, and restored when on foot.
 */
public final class RainAndWeatherOptimizer {

    private static volatile boolean initialized = false;
    private static volatile boolean wasDriving = false;

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        applyLightingSplitUpdate();
        applyPuddlesElevationCap();
        PZOLogger.success("[RainAndWeatherOptimizer] Rain & Weather Driving Governor initialized");
    }

    public static void checkAndMaintain() {
        if (!initialized) {
            initialize();
        }

        boolean driving = VehicleTravelOptimizer.isPlayerDriving();
        if (driving != wasDriving) {
            wasDriving = driving;
            setWeatherMaskingState(!driving);
        }
    }

    /**
     * Caps chunk relighting during rain storms to 5 chunks/frame.
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
                    setValueMethod.invoke(splitOption, true);
                    PZOLogger.success("[RainAndWeatherOptimizer] Lighting.SplitUpdate enabled (Storm lightning spikes smoothed)");
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
                if (currentPuddles == 0) {
                    // Option 0 is All Levels (scans 32 vertical levels). Level 1 is Ground Floor Only.
                    Method setPerfPuddlesMethod = coreClass.getMethod("setPerfPuddles", int.class);
                    setPerfPuddlesMethod.invoke(core, 1);
                    PZOLogger.success("[RainAndWeatherOptimizer] Puddle scanning restricted to Ground Floor (Z=0, 31 upper levels skipped)");
                }
            }
        } catch (Throwable t) {
            PZOLogger.info("[RainAndWeatherOptimizer] Puddles elevation cap notice: " + t.getMessage());
        }
    }

    /**
     * Dynamically controls WeatherFxMask.maskingEnabled.
     * When driving, masks are disabled to prevent 4 full-screen FBO passes & rasterize.scanTriangle.
     */
    private static void setWeatherMaskingState(boolean enabled) {
        try {
            Class<?> maskClass = Class.forName("zombie.iso.weather.fx.WeatherFxMask");
            Field maskingField = maskClass.getField("maskingEnabled");
            maskingField.setBoolean(null, enabled);
        } catch (Throwable ignored) {}
    }
}
