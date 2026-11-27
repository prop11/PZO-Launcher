package com.pzoptimizer.pzoe;

import com.pzoptimizer.pzoe.weather.WeatherLook;

/**
 * Morning frost for PZO Enhancements' Morning Frost (the chunk shader declares PZOFrost):
 *
 *   uniform vec4 PZOFrost;   how much frost formed 0..1, how far the morning melt has got 0..1, strength, 1 = live
 *
 * Frost forms on clear, still nights below about 1 C (cloud keeps the ground warm, rain and snow wash it out), over a
 * few game hours. Once the sun is up it melts: where the sun reaches first, then the shade, faster the warmer it gets.
 * Kept in game hours, so sleeping through the night still leaves a frosty morning. Main thread, once a frame.
 */
final class Frost {

    // Render thread sets it from the uniform lookup
    static volatile boolean wanted;
    // Card strength (modApi "frost")
    static volatile float amount = 1f;
    static volatile float frost, melt;
    private static double lastHours = -1;

    private Frost() {}

    static void frame(float dayLight, float sunVis, float cloud) {
        if (!wanted) {
            lastHours = -1;
            return;
        }
        double now = zombie.GameTime.getInstance().getWorldAgeHours();
        float dh = lastHours < 0 ? 0f : (float) Math.max(0.0, Math.min(2.0, now - lastHours));
        lastHours = now;
        if (dh <= 0f) return;
        float t = zombie.iso.weather.ClimateManager.getInstance().getTemperature();
        float precip = WeatherLook.precipitation();
        float f = frost, m = melt;
        if (dayLight < 0.35f) {
            // Night: a clear, cold, dry night frosts over in about three hours
            float form = Math.max(0f, Math.min(1f, (1f - t) / 4f)) * (1f - Math.min(1f, cloud * 1.4f)) * (precip > 0.05f ? 0f : 1f);
            f = Math.min(1f, f + dh * form / 3f);
            if (precip > 0.05f || t > 3f) f = Math.max(0f, f - dh * 0.8f);
            m = 0f;
        } else {
            // Day: the sun melts it in a couple of hours, warmth faster, and it never forms. The base rate clears it
            // by late morning on a cold overcast day (it was lingering into the afternoon)
            m = Math.min(1f, m + dh * (0.5f + 0.6f * sunVis + Math.max(0f, t) * 0.12f) / 2f);
            if (m >= 1f || precip > 0.05f) f = Math.max(0f, f - dh * 2f);
        }
        frost = f;
        melt = m;
    }

    /** Render thread, per chunk draw. */
    static void upload(int loc, boolean live) {
        if (loc < 0) return;
        float f = frost;
        org.lwjgl.opengl.GL20.glUniform4f(loc, f, melt, amount, live && f > 0.01f ? 1f : 0f);
    }
}
