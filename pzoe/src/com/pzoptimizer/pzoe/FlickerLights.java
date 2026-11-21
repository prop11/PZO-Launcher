package com.pzoptimizer.pzoe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.lwjgl.opengl.GL20;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoLightSource;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoFire;
import zombie.iso.objects.IsoFireManager;

/**
 * Flickering fires and failing lights, for the chunk shader patch that declares PZOFlicker (PZO Enhancements'
 * Flickering Lights). Only the picture flickers; the game's light, which gameplay reads, never changes.
 *
 *   uniform vec4 PZOFlicker[32];     per light: (world x, y, level, reach in squares), (kind, seed, strength, 0)
 *   uniform vec4 PZOFlickerInfo;     count, clock in seconds, darkness 0..1, 1 = live
 *
 * Kind 0: fires (IsoFireManager's fires, and lit fireplaces, stoves and campfires through the cell's heat sources).
 * Kind 1: mains-powered lights in the last 3 days before the power goes off (sandbox ElecShutModifier): more of them
 * fail the nearer the day, each one picked by its position so the same lamps stay bad.
 *
 * Main thread, 4 times a second while the shader asks: the 16 nearest to player 0 within 30 squares. Render thread:
 * one upload per chunk draw, from the last array published.
 */
final class FlickerLights {

    static final int MAX = 16;
    private static final float RANGE_SQ = 30f * 30f;

    // Render thread sets it from the uniform lookup
    static volatile boolean wanted;

    private static volatile float[] published = new float[0];
    private static volatile float darkness;
    private static long lastGather;
    private static final float[] cand = new float[MAX * 9];
    private static int count;
    private static java.lang.reflect.Field heatField;
    private static boolean heatTried;

    // Render thread
    private static final FloatBuffer buf = ByteBuffer.allocateDirect(MAX * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static float[] uploaded;
    private static long startNs = System.nanoTime();

    private FlickerLights() {}

    /** Main thread, once a frame (PixelLight.frame). */
    static void frame(float dayLight) {
        if (!wanted) {
            if (published.length > 0) published = new float[0];
            return;
        }
        darkness = Math.max(0f, Math.min(1f, 1f - dayLight * 1.4f));
        long now = System.currentTimeMillis();
        if (now - lastGather < 250) return;
        lastGather = now;
        try {
            gather();
        } catch (Throwable t) {
            count = 0;
        }
        float[] out = new float[count * 8];
        for (int i = 0; i < count; i++) System.arraycopy(cand, i * 9, out, i * 8, 8);
        published = out;
    }

    private static void gather() {
        count = 0;
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        IsoPlayer p = IsoPlayer.players[0];
        if (cell == null || p == null) return;
        float px = p.getX(), py = p.getY();
        for (int i = 0, n = IsoFireManager.FireStack.size(); i < n; i++) {
            IsoFire f = IsoFireManager.FireStack.get(i);
            if (f == null || f.getSquare() == null) continue;
            int r = f.lightSource != null ? f.lightSource.getRadius() : 5;
            offer(px, py, f.getSquare().getX(), f.getSquare().getY(), f.getSquare().getZ(), Math.max(3, r), 0f, 0.22f);
        }
        java.util.ArrayList<?> heat = heatSources(cell);
        if (heat != null) {
            for (int i = 0, n = heat.size(); i < n; i++) {
                if (!(heat.get(i) instanceof zombie.iso.IsoHeatSource h)) continue;
                // Fires and fireplaces register 35, barbecues 25; a fire already listed adds nothing new
                float k = Math.min(1f, h.getTemperature() / 35f);
                offer(px, py, h.getX(), h.getY(), h.getZ(), Math.max(3, Math.min(8, h.getRadius())), 0f, 0.16f * k);
            }
        }
        float failing = failing();
        if (failing > 0f) {
            java.util.Stack<IsoLightSource> lights = cell.getLamppostPositions();
            for (int i = 0, n = lights.size(); i < n; i++) {
                IsoLightSource l = lights.get(i);
                if (l == null || !l.isActive() || !l.isHydroPowered()) continue;
                // The same lamps stay bad: picked by position, more of them nearer the day
                int hsh = (l.getX() * 73856093) ^ (l.getY() * 19349663) ^ (l.getZ() * 83492791);
                float pick = ((hsh >>> 8) & 0xFFFF) / 65535f;
                if (pick > 0.1f + 0.5f * failing) continue;
                offer(px, py, l.getX(), l.getY(), l.getZ(), Math.max(2, l.getRadius()), 1f, 0.55f + 0.35f * failing);
            }
        }
    }

    // 0 until 3 days before the power goes off, 1 on its last day; 0 again once it's off (the lights are dark)
    private static float failing() {
        int shut = zombie.SandboxOptions.instance.getElecShutModifier();
        if (shut < 0) return 0f;
        double age = zombie.GameTime.getInstance().getWorldAgeDaysSinceBegin();
        double left = shut - age;
        if (left <= 0 || left > 3) return 0f;
        return (float) (1.0 - left / 3.0);
    }

    // Same square as one already kept: keep the stronger. Otherwise the MAX nearest.
    private static void offer(float px, float py, int x, int y, int z, int radius, float kind, float strength) {
        float cx = x + 0.5f, cy = y + 0.5f;
        float dx = cx - px, dy = cy - py, d = dx * dx + dy * dy;
        if (d > RANGE_SQ || strength <= 0f) return;
        for (int i = 0; i < count; i++) {
            int o = i * 9;
            if (cand[o] == cx && cand[o + 1] == cy && cand[o + 2] == z) {
                if (strength > cand[o + 6]) cand[o + 6] = strength;
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
                if (cand[i * 9 + 8] > far) {
                    far = cand[i * 9 + 8];
                    at = i;
                }
            }
            if (at < 0) return;
        }
        int o = at * 9;
        cand[o] = cx;
        cand[o + 1] = cy;
        cand[o + 2] = z;
        cand[o + 3] = radius;
        cand[o + 4] = kind;
        // A seed per light so neighbours don't flicker in step
        cand[o + 5] = (float) ((x * 12.9898 + y * 78.233 + z * 37.719) % 97.0);
        cand[o + 6] = strength;
        cand[o + 7] = 0f;
        cand[o + 8] = d;
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

    /** Render thread, per chunk draw. */
    static void upload(int locArray, int locInfo) {
        if (locInfo < 0) return;
        float[] d = published;
        int n = d.length / 8;
        if (n > 0 && locArray >= 0 && d != uploaded) {
            buf.clear();
            buf.put(d);
            buf.flip();
            GL20.glUniform4fv(locArray, buf);
        }
        // The array uniform keeps its value in the program; only a new program (uploaded reset) needs it again
        uploaded = d;
        float clock = ((System.nanoTime() - startNs) / 1_000_000_000L) % 600L + ((System.nanoTime() - startNs) % 1_000_000_000L) / 1e9f;
        GL20.glUniform4f(locInfo, n, clock, darkness, n > 0 ? 1f : 0f);
    }

    /** A new or rebuilt chunk program: send the lights again. */
    static void invalidate() {
        uploaded = null;
    }

    static String stateText() {
        return "flickerWanted=" + (wanted ? 1 : 0) + ";flickerLights=" + (published.length / 8);
    }
}
