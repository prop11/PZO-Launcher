package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class RainAndWeatherOptimizer {

    private static volatile boolean initialized = false;

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        applyLightingSplitUpdate();
        applyPuddlesElevationCap();
        ensureWeatherMaskingRestored();
        PZOLogger.success("[RainAndWeatherOptimizer] Rain & Weather Driving Governor initialized");
    }

    public static void checkAndMaintain() {
        if (!initialized) {
            initialize();
        }
    }

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

    private static void ensureWeatherMaskingRestored() {
        try {
            Class<?> maskClass = Class.forName("zombie.iso.weather.fx.WeatherFxMask");
            Field maskingField = maskClass.getField("maskingEnabled");
            maskingField.setBoolean(null, true);
        } catch (Throwable ignored) {}
    }
}
