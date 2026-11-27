package com.pzoptimizer.pzoe;

import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.PlayerCamera;
import zombie.vehicles.BaseVehicle;

/**
 * Breath in the cold and cold exhaust, for PZO Enhancements' Breath and Exhaust card (screen.frag declares PZOBreath):
 *
 *   uniform vec4 PZOBreath[32];   per source: (u, v in the world image at the mouth or exhaust, one square's width in
 *                                 pixels, kind 0 breath / 1 exhaust), (seed 0..1, breath period in s, strength, 0)
 *   uniform vec4 PZOBreathInfo;   count, clock (the screen pass's), sideways wind on screen, cold 0..1
 *
 * Breathers are the living: players, survivors and animals (the dead don't breathe), outdoors or anywhere below
 * freezing. Running makes it faster. Vehicles with the engine running steam from behind the rear axle while it's cold.
 * Who is near is picked 4 times a second; where they are is read every frame, so the puff stays at the mouth.
 */
final class Breath {

    static final int MAX = 16;
    private static final float RANGE_SQ = 28f * 28f;

    static volatile long askedNanos;
    // Card strength (modApi "breath")
    static volatile float amount = 1f;
    static volatile float cold, windScreen;

    private static final IsoMovingObject[] picked = new IsoMovingObject[MAX];
    private static final float[] seed = new float[MAX];
    private static int count;
    private static long lastGather;
    private static final org.joml.Vector3f exhaust = new org.joml.Vector3f();

    private Breath() {}

    /** Main thread, once a frame from ScreenPass.main while the shader asks. */
    static void frame() {
        long now = System.currentTimeMillis();
        if (now - lastGather < 250) return;
        lastGather = now;
        try {
            float t = zombie.iso.weather.ClimateManager.getInstance().getTemperature();
            // Breath shows from about 7 C down, fully below -3 C
            cold = Math.max(0f, Math.min(1f, (7f - t) / 10f));
            float a = com.pzoptimizer.pzoe.weather.WeatherLook.windAngleRadians();
            // World x - y runs across the screen, as for the smoke
            windScreen = Math.max(-1f, Math.min(1f, ((float) Math.cos(a) - (float) Math.sin(a)) * 0.7071f))
                * (0.25f + 0.75f * com.pzoptimizer.pzoe.weather.WeatherLook.wind());
            gather();
        } catch (Throwable e) {
            count = 0;
        }
    }

    private static void gather() {
        count = 0;
        if (cold <= 0f || amount <= 0f) return;
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        IsoPlayer p0 = IsoPlayer.players[0];
        if (cell == null || p0 == null) return;
        float px = p0.getX(), py = p0.getY();
        for (IsoMovingObject o : cell.getObjectList()) {
            if (count >= MAX) break;
            if (!(o instanceof IsoGameCharacter c) || o instanceof IsoZombie || c.isDead()) continue;
            float dx = o.getX() - px, dy = o.getY() - py;
            if (dx * dx + dy * dy > RANGE_SQ) continue;
            zombie.iso.IsoGridSquare sq = o.getCurrentSquare();
            if (sq == null || (!sq.isOutside() && cold < 0.7f)) continue;
            pick(o);
        }
        for (BaseVehicle v : cell.getVehicles()) {
            if (count >= MAX) break;
            if (!v.isEngineRunning()) continue;
            float dx = v.getX() - px, dy = v.getY() - py;
            if (dx * dx + dy * dy > RANGE_SQ) continue;
            pick(v);
        }
    }

    private static void pick(IsoMovingObject o) {
        picked[count] = o;
        // Same seed for the same one each time, so a puff doesn't jump when the list is redone
        seed[count] = ((System.identityHashCode(o) >>> 4) & 1023) / 1023f;
        count++;
    }

    /** This frame's sources for one player's camera, as the shader wants them, or null with none. */
    static float[] project(int playerIndex, float zoom, int texW, int texH) {
        if (count == 0) return null;
        PlayerCamera cam = IsoCamera.cameras[playerIndex];
        if (cam == null || texW <= 0 || texH <= 0 || zoom <= 0f) return null;
        float left = IsoCamera.getScreenLeft(playerIndex), top = IsoCamera.getScreenTop(playerIndex);
        float sh = IsoCamera.getScreenHeight(playerIndex);
        float tile = (IsoUtils.XToScreen(1f, 0f, 0f, 0) - IsoUtils.XToScreen(0f, 1f, 0f, 0)) / zoom;
        float[] out = new float[count * 8];
        boolean depthWanted = SceneDepth.asked(System.nanoTime());
        int n = 0;
        for (int i = 0; i < count; i++) {
            IsoMovingObject o = picked[i];
            if (o == null) continue;
            float x, y, z, kind, period, strength;
            if (o instanceof BaseVehicle v) {
                if (!v.isEngineRunning()) continue;
                zombie.scripting.objects.VehicleScript script = v.getScript();
                if (script == null) continue;
                org.joml.Vector3f ext = script.getExtents(), com = script.getCenterOfMassOffset();
                v.getWorldPos(ext.x * 0.25f, 0f, com.z - ext.z * 0.5f - 0.15f, exhaust);
                x = exhaust.x;
                y = exhaust.y;
                z = v.getZ() + 0.08f;
                kind = 1f;
                period = 1f;
                float kmh = Math.abs(v.getCurrentSpeedKmHour());
                strength = Math.max(0.25f, 1f - kmh / 50f);
            } else {
                IsoGameCharacter c = (IsoGameCharacter) o;
                if (c.isDead()) continue;
                // A little in front of the face, about head height (a level is about 2.5 m here)
                zombie.iso.Vector2 f = c.getForwardDirection();
                boolean animal = c instanceof zombie.characters.animals.IsoAnimal;
                float ahead = animal ? 0.45f : 0.22f;
                x = c.getX() + (f != null ? f.x * ahead : 0f);
                y = c.getY() + (f != null ? f.y * ahead : 0f);
                z = c.getZ() + (animal ? 0.28f : 0.6f);
                kind = 0f;
                boolean hard = c instanceof IsoPlayer pl && (pl.isRunning() || pl.isSprinting());
                period = hard ? 1.5f : 3.4f;
                strength = hard ? 1f : 0.8f;
            }
            float sx = cam.XToScreenExact(x, y, z, 0) / zoom, sy = cam.YToScreenExact(x, y, z, 0) / zoom;
            int d = n * 8;
            out[d] = (left + sx) / texW;
            out[d + 1] = (top + sh - sy) / texH;
            out[d + 2] = tile;
            out[d + 3] = kind;
            out[d + 4] = seed[i];
            out[d + 5] = period;
            out[d + 6] = strength * amount * (kind > 0.5f ? Math.min(1f, cold * 1.4f) : cold);
            // Scene depth at the mouth or exhaust, so a puff behind a wall or a car stays behind it
            out[d + 7] = depthWanted ? SceneDepth.depthOf(playerIndex, x, y, z) : -1f;
            n++;
        }
        if (n == 0) return null;
        return n * 8 == out.length ? out : java.util.Arrays.copyOf(out, n * 8);
    }

    static String stateText() {
        return "breathSources=" + count + ";breathCold=" + cold;
    }
}
