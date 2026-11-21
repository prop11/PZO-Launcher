package com.pzoptimizer.pzoe;

import com.pzoptimizer.pzoe.weather.WeatherLook;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.PlayerCamera;
import zombie.iso.objects.IsoFire;
import zombie.iso.objects.IsoFireManager;

/**
 * Smoke columns over fires, for PZO Enhancements' Smoke Plumes (screen.frag declares PZOSmoke):
 *
 *   uniform vec4 PZOSmoke[16];    xy = plume base in the world image's UV, z = plume scale in screen pixels (one
 *                                 square's width times the plume's size), w = strength 0..1
 *   uniform vec4 PZOSmokeInfo;    count, clock in seconds, sideways wind on screen (-1..1 times strength), night 0..1
 *
 * Sources: the fire manager's fires (a burning house is dozens of them, so fires within 3 squares of each other make
 * one plume that grows with how many there are) and lit heat sources outdoors (campfires, barbecues). 60 squares out,
 * further than the haze, since a column of smoke is how you spot a fire across town. Main thread, from
 * ScreenPass.main: gathered 4 times a second, projected every frame with that frame's camera.
 */
final class SmokePlumes {

    static final int MAX = 16;
    private static final float RANGE_SQ = 60f * 60f;

    static volatile long askedNanos;
    static volatile float windScreen, night;
    // x, y, z, size, strength, distance squared, members
    private static final float[] src = new float[MAX * 7];
    private static int count;
    private static long lastGather;
    private static java.lang.reflect.Field heatField;
    private static boolean heatTried;

    private SmokePlumes() {}

    /** Main thread, once a frame from ScreenPass.main while the shader asks. */
    static void frame() {
        long now = System.currentTimeMillis();
        if (now - lastGather < 250) return;
        lastGather = now;
        try {
            gather();
            float a = WeatherLook.windAngleRadians();
            float wx = (float) Math.cos(a), wy = (float) Math.sin(a);
            // World x - y runs across the screen
            windScreen = Math.max(-1f, Math.min(1f, (wx - wy) * 0.7071f)) * (0.25f + 0.75f * WeatherLook.wind());
            night = zombie.iso.weather.ClimateManager.getInstance().getNightStrength();
        } catch (Throwable t) {
            count = 0;
        }
    }

    private static void gather() {
        count = 0;
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (cell == null) return;
        IsoPlayer[] players = IsoPlayer.players;
        for (int i = 0, n = IsoFireManager.FireStack.size(); i < n; i++) {
            IsoFire f = IsoFireManager.FireStack.get(i);
            if (f == null || f.getSquare() == null) continue;
            add(f.getSquare().getX() + 0.5f, f.getSquare().getY() + 0.5f, f.getSquare().getZ(), 1f, players);
        }
        java.util.ArrayList<?> heat = heatSources(cell);
        if (heat != null) {
            for (int i = 0, n = heat.size(); i < n; i++) {
                if (!(heat.get(i) instanceof zombie.iso.IsoHeatSource h)) continue;
                zombie.iso.IsoGridSquare sq = cell.getGridSquare(h.getX(), h.getY(), h.getZ());
                // Indoors it would come out of the ceiling: fireplaces and stoves have no chimney point to use
                if (sq == null || !sq.isOutside()) continue;
                add(h.getX() + 0.5f, h.getY() + 0.5f, h.getZ(), Math.min(1f, h.getTemperature() / 35f) * 0.6f, players);
            }
        }
        // Size from how many fires make it up: one campfire 0.6, a house well alight about 2.5
        for (int i = 0; i < count; i++) {
            int o = i * 7;
            src[o + 3] = Math.min(2.8f, 0.6f + 0.35f * (float) Math.sqrt(src[o + 6] - 1f));
        }
    }

    private static void add(float x, float y, float z, float strength, IsoPlayer[] players) {
        if (strength <= 0f) return;
        float d = -1f;
        for (IsoPlayer p : players) {
            if (p == null) continue;
            float dx = p.getX() - x, dy = p.getY() - y, dd = dx * dx + dy * dy;
            if (dd < RANGE_SQ && (d < 0 || dd < d)) d = dd;
        }
        if (d < 0) return;
        for (int i = 0; i < count; i++) {
            int o = i * 7;
            float dx = src[o] - x, dy = src[o + 1] - y;
            if (dx * dx + dy * dy < 9f && Math.abs(src[o + 2] - z) < 2f) {
                // The plume stands over the middle of its fires
                float m = src[o + 6];
                src[o] = (src[o] * m + x) / (m + 1f);
                src[o + 1] = (src[o + 1] * m + y) / (m + 1f);
                src[o + 2] = Math.max(src[o + 2], z);
                src[o + 4] = Math.max(src[o + 4], strength);
                src[o + 5] = Math.min(src[o + 5], d);
                src[o + 6] = m + 1f;
                return;
            }
        }
        int at;
        if (count < MAX) {
            at = count++;
        } else {
            at = -1;
            float far = d;
            for (int i = 0; i < MAX; i++) {
                if (src[i * 7 + 5] > far) {
                    far = src[i * 7 + 5];
                    at = i;
                }
            }
            if (at < 0) return;
        }
        int o = at * 7;
        src[o] = x;
        src[o + 1] = y;
        src[o + 2] = z;
        src[o + 3] = 0.6f;
        src[o + 4] = strength;
        src[o + 5] = d;
        src[o + 6] = 1f;
    }

    /** This frame's plumes for one player's camera, as the shader wants them, or null with none. */
    static float[] project(int playerIndex, float zoom, int texW, int texH) {
        if (count == 0) return null;
        PlayerCamera cam = IsoCamera.cameras[playerIndex];
        if (cam == null || texW <= 0 || texH <= 0 || zoom <= 0f) return null;
        float left = IsoCamera.getScreenLeft(playerIndex), top = IsoCamera.getScreenTop(playerIndex);
        float sh = IsoCamera.getScreenHeight(playerIndex);
        float tile = (IsoUtils.XToScreen(1f, 0f, 0f, 0) - IsoUtils.XToScreen(0f, 1f, 0f, 0)) / zoom;
        float[] out = new float[count * 4];
        for (int i = 0; i < count; i++) {
            int o = i * 7;
            float px = cam.XToScreenExact(src[o], src[o + 1], src[o + 2], 0) / zoom;
            float py = cam.YToScreenExact(src[o], src[o + 1], src[o + 2], 0) / zoom;
            out[i * 4] = (left + px) / texW;
            out[i * 4 + 1] = (top + sh - py) / texH;
            out[i * 4 + 2] = tile * src[o + 3];
            out[i * 4 + 3] = src[o + 4];
        }
        return out;
    }

    private static java.util.ArrayList<?> heatSources(IsoCell cell) {
        if (!heatTried) {
            heatTried = true;
            try {
                heatField = IsoCell.class.getDeclaredField("heatSources");
                heatField.setAccessible(true);
            } catch (Throwable t) {
                heatField = null;
            }
        }
        try {
            return heatField == null ? null : (java.util.ArrayList<?>) heatField.get(cell);
        } catch (Throwable t) {
            return null;
        }
    }

    static String stateText() {
        return "smokePlumes=" + count;
    }
}
