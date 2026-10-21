package com.pzoptimizer;

/** Wind sway parameters and per-position phase offsets for trees, bushes and grass. */
public final class FoliageWindGovernor {

    private static volatile boolean active = true;
    private static volatile float globalWindIntensity = 1.0f;
    private static volatile float windSpeed = 1.0f;

    private FoliageWindGovernor() {}

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    public static float getGlobalWindIntensity() {
        return globalWindIntensity;
    }

    public static void setGlobalWindIntensity(float intensity) {
        globalWindIntensity = Math.max(0.0f, Math.min(3.0f, intensity));
    }

    public static float calculateSwayOffset(float worldX, float worldY, float heightFraction, long timeMs) {
        if (!active || heightFraction <= 0.05f) return 0.0f;

        double t = timeMs * 0.0015 * windSpeed;
        double phase = (worldX * 0.35 + worldY * 0.25) + t;
        double primaryWave = Math.sin(phase);
        double secondaryWave = Math.cos(phase * 1.7) * 0.3;

        float amplitude = heightFraction * heightFraction * globalWindIntensity * 0.15f;
        return (float) ((primaryWave + secondaryWave) * amplitude);
    }
}
