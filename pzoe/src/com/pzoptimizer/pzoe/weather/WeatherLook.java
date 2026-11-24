package com.pzoptimizer.pzoe.weather;

/**
 * PZOE weather: what the player sees. ClimateManager.updateFx hands every value it gives IsoWeatherFX (rain and snow
 * particles, fog and cloud sprites) through fx()/fxSnow(), and PZOE's own effects read the getters here instead of
 * ClimateManager. So the look can differ from the climate values gameplay reads: that's what lets gameplay stay
 * vanilla (Sim off) while the looks keep their extra detail.
 *
 * WeatherFronts.post sets the look every frame. Until it has (patch refused, frame failed), everything falls back to
 * ClimateManager's own values.
 *
 * Main thread writes, render code reads; a frame of lag doesn't matter.
 */
public final class WeatherLook {
    static final int PRECIP = 0, WIND = 1, WIND_PRECIP = 2, WIND_ANGLE = 3, FOG = 4, CLOUD = 5, FX_COUNT = 6;

    static volatile boolean lookValid;
    private static volatile float precip, wind, windAngle, fog, cloud, daylight = 1f;
    private static volatile float precipVanilla, windVanilla;
    static boolean vanillaSnow, shownSnow;
    // -1 vanilla, 0 rain, 1 snow
    static int snowOverride = -1;

    private WeatherLook() {}

    static void setLook(float p, float w, float a, float f, float c, float d) {
        precip = p;
        wind = w;
        windAngle = a;
        fog = f;
        cloud = c;
        daylight = d;
        lookValid = true;
    }

    static int fxId(String setter) {
        return switch (setter) {
            case "setPrecipitationIntensity" -> PRECIP;
            case "setWindIntensity" -> WIND;
            case "setWindPrecipIntensity" -> WIND_PRECIP;
            case "setWindAngleIntensity" -> WIND_ANGLE;
            case "setFogIntensity" -> FOG;
            case "setCloudIntensity" -> CLOUD;
            case "setPrecipitationIsSnow" -> FX_COUNT;
            default -> -1;
        };
    }

    /** Injected before each IsoWeatherFX float setter in ClimateManager.updateFx. */
    public static float fx(float v, int id) {
        if (!lookValid) return v;
        return switch (id) {
            case PRECIP -> {
                precipVanilla = v;
                yield precip;
            }
            case WIND -> {
                windVanilla = v;
                yield wind;
            }
            // Vanilla's gusty wind squared, for how far rain slants: same gusts on the look's wind
            case WIND_PRECIP -> windVanilla > 0.01f ? Math.min(1f, v * (wind * wind) / (windVanilla * windVanilla)) : v;
            case WIND_ANGLE -> windAngle;
            case FOG -> fog;
            case CLOUD -> cloud;
            default -> v;
        };
    }

    /** Injected before IsoWeatherFX.setPrecipitationIsSnow. */
    public static boolean fxSnow(boolean b) {
        vanillaSnow = b;
        shownSnow = snowOverride < 0 ? b : snowOverride == 1;
        return shownSnow;
    }

    private static zombie.iso.weather.ClimateManager cm() {
        return zombie.iso.weather.ClimateManager.getInstance();
    }

    public static float precipitation() {
        return lookValid ? precip : cm().getPrecipitationIntensity();
    }

    public static boolean isSnow() {
        return lookValid ? shownSnow : cm().getPrecipitationIsSnow();
    }

    public static float wind() {
        return lookValid ? wind : cm().getWindIntensity();
    }

    public static float fog() {
        return lookValid ? fog : cm().getFogIntensity();
    }

    public static float cloud() {
        return lookValid ? cloud : cm().getCloudIntensity();
    }

    public static float dayLight() {
        if (!lookValid) return cm().getDayLightStrength();
        // Vanilla's always-day cheat wins
        zombie.characters.IsoPlayer pl = zombie.characters.IsoPlayer.getInstance();
        return pl != null && pl.isAlwaysDayCheat() ? 1f : daylight;
    }

    /** ClimateManager.getWindAngleDegrees' mapping, on the look's angle. */
    public static float windAngleRadians() {
        if (!lookValid) return cm().getWindAngleRadians();
        return (float) Math.toRadians(angleDegrees(windAngle));
    }

    /** Degrees for vanilla's wind angle value (ClimateManager.getWindAngleDegrees). */
    static float angleDegrees(float v) {
        if (v > 0f) return lerp(v, 45f, 225f);
        if (v > -0.25f) return lerp(Math.abs(v), 45f, 0f);
        return lerp(Math.abs(v) - 0.25f, 360f, 180f);
    }

    /**
     * Vanilla's wind angle value for a direction. Its mapping only reaches 33.75..360 degrees (0.25 below zero jumps
     * from 33.75 to 360), so 0..33.75 is folded onto 360.
     */
    static float angleValue(float deg) {
        deg = ((deg % 360f) + 360f) % 360f;
        if (deg >= 45f && deg <= 225f) return (deg - 45f) / 180f;
        if (deg > 225f) return -(0.25f + (360f - deg) / 180f);
        if (deg >= 33.75f) return -(45f - deg) / 45f;
        return -0.25f;
    }

    private static float lerp(float t, float a, float b) {
        return a + (b - a) * t;
    }

    static String stateText() {
        return "weatherLook=" + (lookValid ? 1 : 0) + ";lookRain=" + Math.round(precip * 100) + ";lookWind=" + Math.round(wind * 100)
            + ";lookCloud=" + Math.round(cloud * 100) + ";lookFog=" + Math.round(fog * 100);
    }
}
