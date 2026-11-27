package com.pzoptimizer.pzoe.weather;

import zombie.iso.IsoGridSquare;

/**
 * Mud (sandbox PZOEWeather.Mud, part of the weather Effects): after heavy rain, dirt tracks, fields and verges turn to
 * mud. Cars lose engine pull, grip and top speed on it and can bog down if they crawl through the deep stuff, and
 * anyone on foot (people and the dead alike) is slowed. Roads and pavements stay firm, frozen ground doesn't soften.
 *
 * How muddy a square is: the ground's own footstep material (vanilla's FootstepMaterial: Dirt soaks most, then Grass,
 * Sand, Gravel) times how wet the ground is now (vanilla's wet ground, which follows the rain and dries by itself, so
 * every machine agrees without sending anything). Runs through the deep snow hooks (WeatherSnow: deferred movement,
 * CarController.checkTire, BaseVehicle.getMaxSpeed) and the wheel grip hook (WeatherEffects.grip).
 */
public final class Mud {
    private static volatile float wet;
    private static volatile boolean on;
    private static long checked;

    private Mud() {}

    // Twice a second at most: the switch and how wet the ground is
    private static void refresh() {
        long now = System.nanoTime();
        if (now - checked < 500_000_000L) return;
        checked = now;
        on = WeatherEffects.on("PZOEWeather.Mud", true);
        if (!on) return;
        zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
        // Frozen ground doesn't turn to mud
        if (cm == null || cm.getTemperature() < -1f) {
            wet = 0f;
            return;
        }
        float rain = cm.getPrecipitationIsSnow() ? 0f : cm.getRainIntensity();
        wet = Math.max(zombie.iso.IsoPuddles.getInstance().getWetGroundFinalValue(), rain * 0.8f);
    }

    /** 0 firm .. 1 deep mud, for an outdoor ground square. */
    public static float at(IsoGridSquare sq) {
        refresh();
        if (!on || wet < 0.3f || sq == null || sq.getZ() != 0 || !sq.isOutside()) return 0f;
        zombie.iso.IsoObject floor = sq.getFloor();
        var props = floor == null ? null : floor.getProperties();
        String m = props == null ? null : props.get(zombie.core.properties.IsoPropertyType.FOOTSTEP_MATERIAL);
        if (m == null) return 0f;
        float soak = switch (m) {
            case "Dirt" -> 1f;
            case "Grass" -> 0.55f;
            case "Sand" -> 0.45f;
            case "Gravel" -> 0.2f;
            default -> 0f;
        };
        if (soak <= 0f) return 0f;
        float t = Math.min(1f, (wet - 0.3f) / 0.6f);
        return soak * t * t * (3f - 2f * t);
    }

    static float strength() {
        return WeatherEffects.strength();
    }

    /** Engine pull on the mud under the car, much less while it's bogged (deep mud, barely moving). */
    static float engine(float force, zombie.vehicles.BaseVehicle v) {
        float mud = v == null ? 0f : at(v.getSquare());
        if (mud <= 0.01f) return force;
        float k = 1f - Math.min(0.6f, 0.45f * mud * strength());
        if (mud > 0.7f && Math.abs(v.getCurrentSpeedKmHour()) < 4f) k *= 1f - Math.min(0.7f, 0.6f * strength());
        return force * k;
    }

    /** Mud sucks at the tyres of a moving car like a light brake. */
    static float brake(float force, zombie.vehicles.BaseVehicle v) {
        float mud = v == null ? 0f : at(v.getSquare());
        if (mud <= 0.01f) return force;
        float drag = v.getBrakingForce() * 0.5f * mud * strength() * Math.min(1f, Math.abs(v.getCurrentSpeedKmHour()) / 15f);
        return Math.max(0f, force) + drag;
    }

    static float maxSpeed(float speed, zombie.vehicles.BaseVehicle v) {
        float mud = v == null ? 0f : at(v.getSquare());
        return mud <= 0.01f ? speed : speed * (1f - Math.min(0.7f, 0.5f * mud * strength()));
    }

    static float gripLoss(IsoGridSquare sq) {
        float mud = at(sq);
        return mud <= 0.01f ? 0f : 0.25f * mud * strength();
    }

    /** Walking speed on mud, 1 = none. */
    static float walk(zombie.characters.IsoGameCharacter c) {
        if (c == null || c.getVehicle() != null) return 1f;
        float mud = at(c.getCurrentSquare());
        return mud <= 0.01f ? 1f : 1f - Math.min(0.4f, 0.35f * mud * strength());
    }
}
