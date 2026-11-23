package com.pzoptimizer.pzoe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.lwjgl.opengl.GL20;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.PlayerCamera;
import zombie.iso.objects.IsoDeadBody;

/**
 * Flies over corpses and the rot stain spreading under them, for PZO Enhancements' Flies and Rot card. Two users:
 *
 *   screen.frag:      uniform vec4 PZOFlies[16];    per corpse: (u, v in the world image, flies 0..12, seed)
 *                     uniform vec4 PZOFliesInfo;    count, clock, daylight, 1 = live
 *   chunkShader.frag: uniform vec4 PZORot[32];      per corpse: (world x, y, level, stain radius), (rot 0..1, seed, 0, 0)
 *                     uniform vec4 PZORotInfo;      count, 0, 0, 1 = live
 *
 * Flies come a couple of hours after death and grow over the first day, stay for about two weeks and thin out once
 * the body is old; few at night, none in the cold or heavy rain. The stain darkens over the first two days and
 * spreads to about a square across over three. Main thread (PixelLight.frame): corpses within 20 squares of player
 * 0, gathered twice a second, the 16 nearest kept. Render thread: one upload per chunk draw, like FlashLights.
 */
final class CorpseFlies {

    static final int MAX = 16;
    private static final int RANGE = 20;

    static volatile long screenAskedNanos;
    // Render thread sets it from the chunk program's uniforms
    static volatile boolean chunkWanted;
    // Card amount (modApi "corpseFlies"), 1 = 100 percent; stains (0 = off)
    static volatile float amount = 1f;
    static volatile boolean stains = true;

    // x, y, z, age (hours), seed, distance squared
    private static final int F = 6;
    private static final float[] bodies = new float[MAX * F];
    private static int count;
    private static long lastGather;
    private static float fliesK;
    private static volatile float[] published = new float[0];

    // Render thread
    private static final FloatBuffer buf = ByteBuffer.allocateDirect(MAX * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static float[] uploaded;

    private CorpseFlies() {}

    /** Main thread, once a frame. */
    static void frame() {
        boolean screen = System.nanoTime() - screenAskedNanos < 1_000_000_000L;
        boolean chunk = chunkWanted && stains;
        if (!screen && !chunk) {
            count = 0;
            if (published.length > 0) published = new float[0];
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastGather < 500) return;
        lastGather = now;
        try {
            gather();
        } catch (Throwable t) {
            count = 0;
        }
        float[] out = new float[chunk ? count * 8 : 0];
        for (int i = 0; chunk && i < count; i++) {
            int o = i * F, d = i * 8;
            float age = bodies[o + 3];
            out[d] = bodies[o];
            out[d + 1] = bodies[o + 1];
            out[d + 2] = bodies[o + 2];
            out[d + 3] = 0.35f + 0.6f * smooth(2f, 72f, age);
            out[d + 4] = smooth(1f, 48f, age) * Math.min(1.5f, amount);
            out[d + 5] = bodies[o + 4];
        }
        published = out;
    }

    private static void gather() {
        count = 0;
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        IsoPlayer p = IsoPlayer.players[0];
        if (cell == null || p == null) return;
        zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
        float cold = smooth(2f, 10f, cm.getTemperature());
        float rain = 1f - 0.8f * smooth(0.3f, 0.8f, com.pzoptimizer.pzoe.weather.WeatherLook.precipitation());
        float night = 1f - 0.8f * smooth(0.3f, 0.8f, cm.getNightStrength());
        fliesK = cold * rain * night;
        double world = zombie.GameTime.getInstance().getWorldAgeHours();
        int px = (int) Math.floor(p.getX()), py = (int) Math.floor(p.getY()), pz = (int) Math.floor(p.getZ());
        for (int z = pz - 1; z <= pz + 1; z++) {
            for (int y = py - RANGE; y <= py + RANGE; y++) {
                for (int x = px - RANGE; x <= px + RANGE; x++) {
                    IsoGridSquare sq = cell.getGridSquare(x, y, z);
                    if (sq == null) continue;
                    java.util.List<IsoDeadBody> list = sq.getDeadBodys();
                    if (list == null || list.isEmpty()) continue;
                    for (int i = 0, n = list.size(); i < n; i++) {
                        IsoDeadBody b = list.get(i);
                        if (b == null || b.getDeathTime() < 0f) continue;
                        offer(p.getX(), p.getY(), b.getX(), b.getY(), b.getZ(), (float) (world - b.getDeathTime()), b.isSkeleton());
                    }
                }
            }
        }
    }

    private static void offer(float px, float py, float x, float y, float z, float age, boolean skeleton) {
        if (age < 0f) age = 0f;
        // A skeleton has nothing left to rot
        if (skeleton) age = Math.max(age, 24f * 30f);
        float dx = x - px, dy = y - py, d = dx * dx + dy * dy;
        int at = count;
        if (count >= MAX) {
            at = -1;
            float far = d;
            for (int i = 0; i < MAX; i++) {
                if (bodies[i * F + 5] > far) {
                    far = bodies[i * F + 5];
                    at = i;
                }
            }
            if (at < 0) return;
        } else {
            count++;
        }
        int o = at * F;
        bodies[o] = x;
        bodies[o + 1] = y;
        bodies[o + 2] = z;
        bodies[o + 3] = age;
        // Same seed for the same body while it lies there
        bodies[o + 4] = (float) Math.floorMod((int) (x * 73.13f) * 31 + (int) (y * 51.7f), 997);
        bodies[o + 5] = d;
    }

    // Flies on a body of this age, 0..12
    private static float flies(float age) {
        if (age > 24f * 30f) return 0f;
        float grow = smooth(1.5f, 24f, age);
        float fade = 1f - 0.75f * smooth(24f * 14f, 24f * 28f, age);
        return 12f * grow * fade;
    }

    /** This frame's flies for one player's camera, or null with none. */
    static float[] project(int playerIndex, float zoom, int texW, int texH) {
        int n = count;
        if (n == 0 || playerIndex != 0 || fliesK <= 0.01f) return null;
        PlayerCamera cam = IsoCamera.cameras[playerIndex];
        if (cam == null || texW <= 0 || texH <= 0 || zoom <= 0f) return null;
        float left = IsoCamera.getScreenLeft(playerIndex), top = IsoCamera.getScreenTop(playerIndex);
        float sh = IsoCamera.getScreenHeight(playerIndex);
        float[] out = new float[n * 4];
        int k = 0;
        for (int i = 0; i < n; i++) {
            int o = i * F;
            float f = flies(bodies[o + 3]) * fliesK * Math.min(2f, amount);
            if (f < 0.5f) continue;
            out[k * 4] = (left + cam.XToScreenExact(bodies[o], bodies[o + 1], bodies[o + 2], 0) / zoom) / texW;
            out[k * 4 + 1] = (top + sh - cam.YToScreenExact(bodies[o], bodies[o + 1], bodies[o + 2], 0) / zoom) / texH;
            out[k * 4 + 2] = Math.min(16f, f);
            out[k * 4 + 3] = bodies[o + 4];
            k++;
        }
        return k == 0 ? null : java.util.Arrays.copyOf(out, k * 4);
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
        uploaded = d;
        GL20.glUniform4f(locInfo, n, 0f, 0f, n > 0 ? 1f : 0f);
    }

    /** A new or rebuilt chunk program: send the stains again. */
    static void invalidate() {
        uploaded = null;
    }

    private static float smooth(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    static String stateText() {
        return "corpses=" + count + ";rotStains=" + (published.length / 8);
    }
}
