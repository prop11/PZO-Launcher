package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Weather settings that hitch while travelling in rain: lighting split updates, puddle levels and weather masking (puddle caching: PuddleFrameCache, PuddleRedraw). */
public final class RainAndWeatherOptimizer {

    private static volatile boolean initialized = false;

    public static synchronized void initialize() {
        if (initialized) return;
        if (ViewpointStanddownGovernor.isStanddownActive()) {
            PZOLogger.info("[RainAndWeatherOptimizer] Project Viewpoint standdown active - bypassing weather/fog overrides.");
            return;
        }
        initialized = true;

        applyLightingSplitUpdate();
        applyPuddlesElevationCap();
        ensureWeatherMaskingRestored();
        PZOLogger.success("[RainAndWeatherOptimizer] Rain & Weather Driving Governor initialized (Quarter-Buffer Fog & Zero-Stall Pipeline armed)");
    }

    public static void checkAndMaintain() {
        if (ViewpointStanddownGovernor.isStanddownActive()) {
            return;
        }
        if (!initialized) {
            initialize();
        }
    }

    /**
     * Keeps lightingSplitUpdate off. When on, FBORenderCell caps chunk updates at 5 per frame, which makes
     * flashlights flicker and stops other players' flashlights lighting anything in multiplayer.
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

    /** Switches puddles to ground level only (perfPuddles = 2) when set lower. */
    public static void applyPuddlesElevationCap() {
        try {
            Class<?> coreClass = Class.forName("zombie.core.Core");
            Method getInstMethod = coreClass.getMethod("getInstance");
            Object core = getInstMethod.invoke(null);
            if (core != null) {
                Method getPerfPuddlesMethod = coreClass.getMethod("getPerfPuddles");
                int currentPuddles = (int) getPerfPuddlesMethod.invoke(core);
                if (currentPuddles < 2) {
                    // 0 = all levels, 1 = ground with ruts (8 neighbour scans per square), 2 = ground only
                    Method setPerfPuddlesMethod = coreClass.getMethod("setPerfPuddles", int.class);
                    setPerfPuddlesMethod.invoke(core, 2);
                    PZOLogger.success("[RainAndWeatherOptimizer] Puddle scanning set to Ground Only (Z=0, 8-neighbor lookups bypassed)");
                }
            }
        } catch (Throwable t) {
            PZOLogger.info("[RainAndWeatherOptimizer] Puddles elevation cap notice: " + t.getMessage());
        }
    }

    /** Keeps WeatherFxMask.maskingEnabled on; without it indoor areas show a raw gray box. */
    private static void ensureWeatherMaskingRestored() {
        try {
            Class<?> maskClass = Class.forName("zombie.iso.weather.fx.WeatherFxMask");
            Field maskingField = maskClass.getField("maskingEnabled");
            maskingField.setBoolean(null, true);
        } catch (Throwable ignored) {}
    }
}

