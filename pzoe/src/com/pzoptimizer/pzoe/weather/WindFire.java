package com.pzoptimizer.pzoe.weather;

import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.objects.IsoFire;
import zombie.iso.objects.IsoFireManager;

/**
 * Wind-driven fire (sandbox PZOEWeather.WindFire, part of the weather Effects). Vanilla spreads a fire to a random
 * neighbour; outdoors this steers it with the wind (vanilla's own wind, the same on every machine):
 * - a spread into the wind often fails, more in a strong wind;
 * - a spread with the wind can carry on a square further downwind;
 * - dry, hot weather (no wet ground, 20 C and up) gives a second, downwind-leaning spread now and then;
 * - in a gale, embers can land 3 to 5 squares downwind and start a new fire there, across a road or a gap.
 * Only where vanilla spreads fires (server or single player), and rain stops spreading as in vanilla. Indoors it's
 * vanilla's spread untouched.
 */
public final class WindFire {
    private WindFire() {}

    private static boolean on() {
        return WeatherEffects.on("PZOEWeather.WindFire", true);
    }

    private static boolean outdoors(IsoGridSquare sq) {
        return sq != null && sq.isOutside() && sq.getRoom() == null;
    }

    private static float chance(float p) {
        return zombie.core.random.Rand.Next(10000) < p * 10000f ? 1f : 0f;
    }

    /** Whether vanilla's spread from source to target goes ahead. */
    public static boolean allow(IsoCell cell, IsoFire source, IsoGridSquare target) {
        try {
            if (source == null || source.getSquare() == null || !on() || !outdoors(source.getSquare()) || !outdoors(target)) return true;
            zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
            float wind = cm.getWindIntensity();
            if (wind < 0.1f) return true;
            float a = along(source.getSquare(), target, cm);
            if (a >= -0.3f) return true;
            return chance(Math.min(0.85f, wind * WeatherEffects.strength() * 0.8f * -a)) == 0f;
        } catch (Throwable t) {
            return true;
        }
    }

    /** After a spread: the extra ground the wind and the dry carry it over. */
    public static void carry(IsoCell cell, IsoFire source, IsoGridSquare target, int energy) {
        try {
            if (source == null || source.getSquare() == null || cell == null || !on() || !outdoors(source.getSquare()) || !outdoors(target)) return;
            if (zombie.iso.objects.RainManager.isRaining()) return;
            zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
            float s = WeatherEffects.strength();
            float wind = cm.getWindIntensity();
            double ang = cm.getWindAngleRadians();
            float wx = (float) Math.cos(ang), wy = (float) Math.sin(ang);
            int sx = Math.round(wx), sy = Math.round(wy);
            if (sx == 0 && sy == 0) return;
            float a = along(source.getSquare(), target, cm);
            if (a > 0.3f && chance(Math.min(0.6f, wind * s * a * 0.5f)) > 0f) ignite(cell, target, sx, sy, energy);
            float wet = zombie.iso.IsoPuddles.getInstance().getWetGroundFinalValue();
            float t = cm.getTemperature();
            float dry = (1f - Math.min(1f, wet * 4f)) * Math.max(0f, Math.min(1f, (t - 20f) / 12f));
            if (dry > 0.05f && chance(Math.min(0.4f, dry * s * 0.3f)) > 0f) {
                // Lean the extra spread downwind when there's any wind, else any neighbour
                int dx = wind > 0.2f ? sx : zombie.core.random.Rand.Next(3) - 1;
                int dy = wind > 0.2f ? sy : zombie.core.random.Rand.Next(3) - 1;
                if (dx != 0 || dy != 0) ignite(cell, target, dx, dy, energy);
            }
            if (wind > 0.7f && chance(Math.min(0.12f, 0.05f * s * wind)) > 0f) {
                int jump = 3 + zombie.core.random.Rand.Next(3);
                IsoGridSquare far = cell.getGridSquare(target.getX() + Math.round(wx * jump), target.getY() + Math.round(wy * jump), target.getZ());
                if (outdoors(far) && IsoFire.CanAddFire(far, false)) IsoFireManager.StartFire(cell, far, false, Math.max(40, energy / 2));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void ignite(IsoCell cell, IsoGridSquare from, int dx, int dy, int energy) {
        IsoGridSquare next = cell.getGridSquare(from.getX() + dx, from.getY() + dy, from.getZ());
        if (outdoors(next) && IsoFire.CanAddFire(next, false)) IsoFireManager.StartFire(cell, next, false, Math.max(30, energy));
    }

    // How well the spread lines up with the wind: 1 straight downwind, -1 straight into it
    private static float along(IsoGridSquare from, IsoGridSquare to, zombie.iso.weather.ClimateManager cm) {
        float dx = to.getX() - from.getX(), dy = to.getY() - from.getY();
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.5f) return 0f;
        double ang = cm.getWindAngleRadians();
        return (float) ((dx * Math.cos(ang) + dy * Math.sin(ang)) / len);
    }
}
