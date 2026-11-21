package com.pzoptimizer.pzoe;

import com.pzoptimizer.pzoe.weather.WeatherLook;
import zombie.characters.IsoPlayer;
import zombie.vehicles.BaseVehicle;

/**
 * Rain on the windscreen for PZO Enhancements' Rain on the Windscreen (screen.frag declares PZOWindscreen):
 *
 *   uniform vec4 PZOWindscreen;    how wet the glass is 0..1, speed 0..1, clock (s), wiper period (s, 0 = parked)
 *   uniform vec4 PZOWindscreen2;   snow 0..1, card strength, how hard it's raining 0..1, 1 = live
 *
 * Only while the player sits in a vehicle. The glass wets as the rain falls on it and dries slowly once it stops; the
 * wipers (the game has none, so they're drawn here) run faster the harder it rains and park once the glass is clear.
 * The clock only runs while there's something on the glass, so the drops don't jump. Main thread, from ScreenPass.main.
 */
final class Windscreen {

    static volatile long askedNanos;
    // Card strength (modApi "windscreen")
    static volatile float amount = 1f;

    private static final float[] wet = new float[4], clock = new float[4], period = new float[4];
    private static final long[] lastNs = new long[4];

    private Windscreen() {}

    /** The 8 uniform values for this player, or null when there's nothing on the glass. */
    static float[] sample(int playerIndex) {
        if (playerIndex < 0 || playerIndex >= 4) return null;
        IsoPlayer p = IsoPlayer.players[playerIndex];
        long now = System.nanoTime();
        float dt = lastNs[playerIndex] == 0 ? 0f : Math.min(0.25f, (now - lastNs[playerIndex]) / 1e9f);
        lastNs[playerIndex] = now;
        if (zombie.GameTime.isGamePaused()) dt = 0f;
        BaseVehicle v = p == null ? null : p.getVehicle();
        if (v == null || amount <= 0f) {
            wet[playerIndex] = 0f;
            return null;
        }
        zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
        float rain = Math.max(0f, Math.min(1f, WeatherLook.precipitation()));
        boolean snow = cm.getPrecipitationIsSnow();
        zombie.iso.IsoGridSquare sq = v.getSquare();
        // Under a roof (a garage) nothing lands
        if (sq != null && !sq.isOutside()) rain = 0f;
        float w = wet[playerIndex];
        w = rain > 0.01f ? Math.min(1f, w + dt * (0.25f + 0.6f * rain)) : Math.max(0f, w - dt * 0.03f);
        wet[playerIndex] = w;
        if (w < 0.01f) return null;
        clock[playerIndex] = (clock[playerIndex] + dt) % 3600f;
        // Wipers: a sweep every 3 s in drizzle, 1.2 s in a downpour; parked once it's stopped and the glass is clearing
        period[playerIndex] = rain > 0.03f ? 3f - 1.8f * rain : (w > 0.25f ? 3f : 0f);
        float speed = Math.min(1f, Math.abs(v.getCurrentSpeedKmHour()) / 80f);
        return new float[] {w, speed, clock[playerIndex], period[playerIndex], snow ? 1f : 0f, amount, rain, 1f};
    }
}
