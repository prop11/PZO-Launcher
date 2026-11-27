package com.pzoptimizer.pzoe;

import com.pzoptimizer.pzoe.weather.WeatherLook;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoUtils;
import zombie.iso.PlayerCamera;
import zombie.iso.objects.IsoFire;
import zombie.iso.objects.IsoFireManager;

/**
 * Fire glow on cloud and fog, for PZO Enhancements' Fire Glow (screen.frag declares PZOFireGlow):
 *
 *   uniform vec4 PZOFireGlow[8];    xy = fire in the world image's UV, z = one square's width in pixels,
 *                                   w = seed * 2 + strength (strength 0..1)
 *   uniform vec4 PZOFireGlowInfo;   count, clock in seconds, haze overhead 0..1, night 0..1
 *
 * A big fire at night lights the cloud base, the fog and its own smoke above it, and that light comes back down over
 * a wide area: the orange sky over a burning town you can steer by. The fires within 140 squares are clustered
 * into blazes (a burning house is dozens of fires), the strongest 8 kept, off screen too so their glow reaches in
 * from the edge. Main thread, gathered 4 times a second, projected with each frame's camera.
 */
final class FireGlow {
    static final int MAX = 8;
    private static final float RANGE_SQ = 140f * 140f, CLUSTER_SQ = 8f * 8f;
    private static final int POOL = 32, F = 6;

    static volatile long askedNanos;
    static volatile float amount = 1f, haze, night;
    // x, y, z, fires in it, nearest player distance squared, seed
    private static final float[] pool = new float[POOL * F];
    private static final float[] src = new float[MAX * F];
    private static int count;
    private static long lastGather;

    private FireGlow() {}

    /** Main thread, once a frame from ScreenPass.main while the shader asks. */
    static void frame() {
        long now = System.currentTimeMillis();
        if (now - lastGather < 250) return;
        lastGather = now;
        try {
            gather();
            zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
            night = cm.getNightStrength();
            float rain = cm.getPrecipitationIsSnow() ? 0.6f * WeatherLook.precipitation() : WeatherLook.precipitation();
            // Something has to be up there to light. A clear night still has the fire's own smoke and some haze.
            haze = Math.min(1f, 0.3f + 0.45f * WeatherLook.cloud() + 0.7f * WeatherLook.fog() + 0.35f * rain);
        } catch (Throwable t) {
            count = 0;
        }
    }

    private static void gather() {
        int n = 0;
        IsoPlayer[] players = IsoPlayer.players;
        var fires = IsoFireManager.FireStack;
        for (int i = 0, size = fires.size(); i < size; i++) {
            IsoFire f = fires.get(i);
            IsoGridSquare square = f == null ? null : f.getSquare();
            // Campfires and smouldering ends don't light the sky
            if (square == null || f.isPermanent() || f.smoke || f.lifeStage >= 5) continue;
            float x = square.getX() + 0.5f, y = square.getY() + 0.5f;
            float d = nearest(x, y, players);
            if (d < 0f) continue;
            boolean joined = false;
            for (int c = 0; c < n; c++) {
                int o = c * F;
                float dx = pool[o] - x, dy = pool[o + 1] - y;
                if (dx * dx + dy * dy < CLUSTER_SQ) {
                    float m = pool[o + 3];
                    pool[o] = (pool[o] * m + x) / (m + 1f);
                    pool[o + 1] = (pool[o + 1] * m + y) / (m + 1f);
                    pool[o + 2] = Math.max(pool[o + 2], square.getZ());
                    pool[o + 3] = m + 1f;
                    pool[o + 4] = Math.min(pool[o + 4], d);
                    joined = true;
                    break;
                }
            }
            if (joined || n >= POOL) continue;
            int o = n++ * F;
            pool[o] = x;
            pool[o + 1] = y;
            pool[o + 2] = square.getZ();
            pool[o + 3] = 1f;
            pool[o + 4] = d;
            pool[o + 5] = SmokePlumes.seed(square.getX(), square.getY(), square.getZ());
        }
        // The biggest blazes win, nearer ones first among equals
        int kept = 0;
        for (int k = 0; k < MAX && k < n; k++) {
            int best = -1;
            float score = -1f;
            for (int c = 0; c < n; c++) {
                int o = c * F;
                if (pool[o + 3] <= 0f) continue;
                float s = pool[o + 3] / (1f + (float) Math.sqrt(pool[o + 4]) * 0.02f);
                if (s > score) {
                    score = s;
                    best = c;
                }
            }
            if (best < 0) break;
            System.arraycopy(pool, best * F, src, kept++ * F, F);
            pool[best * F + 3] = 0f;
        }
        count = kept;
    }

    private static float nearest(float x, float y, IsoPlayer[] players) {
        float d = -1f;
        for (IsoPlayer p : players) {
            if (p == null) continue;
            float dx = p.getX() - x, dy = p.getY() - y, dd = dx * dx + dy * dy;
            if (dd < RANGE_SQ && (d < 0f || dd < d)) d = dd;
        }
        return d;
    }

    /** This frame's blazes for one player's camera, 4 floats each, or null with none (or by day). */
    static float[] project(int playerIndex, float zoom, int texW, int texH) {
        int n = count;
        if (n == 0 || night <= 0.01f || amount <= 0f) return null;
        PlayerCamera cam = IsoCamera.cameras[playerIndex];
        if (cam == null || texW <= 0 || texH <= 0 || zoom <= 0f) return null;
        float left = IsoCamera.getScreenLeft(playerIndex), top = IsoCamera.getScreenTop(playerIndex);
        float sh = IsoCamera.getScreenHeight(playerIndex);
        float tile = (IsoUtils.XToScreen(1f, 0f, 0f, 0) - IsoUtils.XToScreen(0f, 1f, 0f, 0)) / zoom;
        float[] out = new float[n * 4];
        for (int i = 0; i < n; i++) {
            int o = i * F;
            float sx = cam.XToScreenExact(src[o], src[o + 1], src[o + 2], 0) / zoom;
            float sy = cam.YToScreenExact(src[o], src[o + 1], src[o + 2], 0) / zoom;
            // One fire is a faint glow, a house well alight (25 or so) the full orange sky
            float strength = Math.min(1f, (float) Math.sqrt(src[o + 3]) * 0.2f * amount);
            out[i * 4] = (left + sx) / texW;
            out[i * 4 + 1] = (top + sh - sy) / texH;
            out[i * 4 + 2] = tile;
            out[i * 4 + 3] = src[o + 5] * 2f + Math.min(0.999f, strength);
        }
        return out;
    }

    static String stateText() {
        return "blazes=" + count + " haze=" + Math.round(haze * 100) + " night=" + Math.round(night * 100);
    }
}
