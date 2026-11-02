package com.pzoptimizer;

import zombie.core.PerformanceSettings;
import zombie.iso.objects.RainManager;

/**
 * Engine settings the Lua mod tunes. Kahlua copies public static fields into Lua once, so setting them from Lua never
 * reaches the game; Lua calls PZOEngine.setEngineTuning instead. Only the names listed here can be set, and each is
 * clamped to a sane range.
 */
public final class EngineTuning {

    private EngineTuning() {}

    /** True if the name is known and the value got applied. */
    public static boolean set(String name, double value) {
        if (name == null || Double.isNaN(value)) return false;
        int v = (int) Math.round(value);
        switch (name) {
            case "numberZombiesBlended":
                PerformanceSettings.numberZombiesBlended = clamp(v, 1, 100);
                return true;
            case "zombieAnimationSpeedFalloffCount":
                PerformanceSettings.zombieAnimationSpeedFalloffCount = clamp(v, 1, 100);
                return true;
            case "zombieBonusFullspeedFalloff":
                PerformanceSettings.zombieBonusFullspeedFalloff = clamp(v, 1, 20);
                return true;
            case "maxRainSplashObjects":
                RainManager.maxRainSplashObjects = clamp(v, 0, 500);
                return true;
            case "maxRaindropObjects":
                RainManager.maxRaindropObjects = clamp(v, 0, 500);
                return true;
            default:
                return false;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
