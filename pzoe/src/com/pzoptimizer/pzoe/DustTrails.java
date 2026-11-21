package com.pzoptimizer.pzoe;

import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.PlayerCamera;
import zombie.vehicles.BaseVehicle;

/**
 * Dust and mud behind vehicles for PZO Enhancements' Dust and Mud Trails (screen.frag declares PZODust):
 *
 *   uniform vec4 PZODust[48];   per puff: u, v in the world image, radius in pixels, alpha (negative = a mud fleck)
 *   uniform vec4 PZODustInfo;   count, daylight, 0, 1 = live
 *
 * Rear wheels on natural ground (dirt, sand, gravel, grass tracks) at speed: dry, they kick up dust puffs that stay
 * where they rose, swell, lift and drift with the wind and fade over a few seconds, so a car on a dirt road leaves a
 * long trail; wet, they flick short arcs of mud back instead. Puffs live in world squares and are placed on screen
 * every frame. Main thread, from ScreenPass.main.
 */
final class DustTrails {

    static final int MAX = 48, RING = 96;
    private static final float RANGE_SQ = 45f * 45f;

    static volatile long askedNanos;
    // Card strength (modApi "dust")
    static volatile float amount = 1f;

    // x, y, z, birth, strength, kind, vx, vy (world squares a second)
    private static final int F = 8;
    private static final float[] ring = new float[RING * F];
    private static int next, used;
    private static float clock;
    private static long lastNs;
    private static int lastFrame = Integer.MIN_VALUE;
    private static final java.util.WeakHashMap<BaseVehicle, float[]> travelled = new java.util.WeakHashMap<>();
    private static final org.joml.Vector3f wheelPos = new org.joml.Vector3f(), fwd = new org.joml.Vector3f();

    private DustTrails() {}

    /** Main thread, once a frame from ScreenPass.main while the shader asks. */
    static void frame() {
        int f = IsoCamera.frameState.frameCount;
        if (f == lastFrame) return;
        lastFrame = f;
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0f : Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        if (zombie.GameTime.isGamePaused()) dt = 0f;
        clock += dt;
        if (dt <= 0f || amount <= 0f) return;
        try {
            emit();
        } catch (Throwable t) {
            // A vehicle mid-load: nothing this frame
        }
    }

    private static void emit() {
        zombie.iso.IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        zombie.characters.IsoPlayer p0 = zombie.characters.IsoPlayer.players[0];
        if (cell == null || p0 == null) return;
        float px = p0.getX(), py = p0.getY();
        float a = com.pzoptimizer.pzoe.weather.WeatherLook.windAngleRadians(), ws = com.pzoptimizer.pzoe.weather.WeatherLook.wind();
        float wx = (float) Math.cos(a) * ws * 1.2f, wy = (float) Math.sin(a) * ws * 1.2f;
        for (BaseVehicle v : cell.getVehicles()) {
            float dx = v.getX() - px, dy = v.getY() - py;
            if (dx * dx + dy * dy > RANGE_SQ || v.getZ() >= 1f) continue;
            float kmh = Math.abs(v.getCurrentSpeedKmHour());
            zombie.scripting.objects.VehicleScript script = v.getScript();
            if (kmh < 8f || script == null) continue;
            float[] tr = travelled.get(v);
            int n = script.getWheelCount();
            // per wheel: last x, y, distance since the last puff; then the bumper's distance since its last puff
            if (tr == null || tr.length != n * 3 + 3) travelled.put(v, tr = new float[n * 3 + 3]);
            org.joml.Vector3f fw = v.getForwardVector(fwd);
            float back = v.getCurrentSpeedKmHour() >= 0f ? -1f : 1f;
            for (int i = 0; i < n; i++) {
                zombie.scripting.objects.VehicleScript.Wheel wh = script.getWheel(i);
                if (wh == null || wh.front) continue;
                org.joml.Vector3f w = v.getWorldPos(wh.offset.x, 0f, wh.offset.z, wheelPos);
                int o = i * 3;
                float mx = w.x - tr[o], my = w.y - tr[o + 1];
                tr[o] = w.x;
                tr[o + 1] = w.y;
                float d = (float) Math.sqrt(mx * mx + my * my);
                if (d > 3f) continue;
                tr[o + 2] += d;
                IsoGridSquare sq = cell.getGridSquare((int) Math.floor(w.x), (int) Math.floor(w.y), (int) Math.floor(w.z));
                if (sq == null || !sq.isOutside()) continue;
                // Snow (the game's or PZOE's deep snow) is thrown up as white powder; otherwise dust or mud off bare dirt
                float snowH = Math.max(PuddleSplash.snowAt(sq) ? 0.02f : 0f, com.pzoptimizer.pzoe.weather.WeatherSnow.depthAt(w.x, w.y, w.z));
                boolean snow = snowH > 0.01f;
                if (!snow && (kmh < 12f || !natural(sq))) continue;
                boolean wet = !snow && PuddleSplash.groundIsWet(sq);
                float every = snow ? 0.3f : wet ? 0.25f : 0.45f;
                if (tr[o + 2] < every) continue;
                tr[o + 2] = 0f;
                float k = Math.min(1f, (kmh - 10f) / 50f);
                if (snow) {
                    // Powder flung back off the tread and up, more out of deep snow
                    float ks = Math.min(1f, (kmh - 6f) / 45f) * Math.min(1f, snowH / 0.15f + 0.4f);
                    float sp = 1.5f + 3f * ks;
                    add(w.x, w.y, w.z + 0.05f, ks, 2f, fw.x * back * sp + wx * 0.5f, fw.z * back * sp + wy * 0.5f);
                } else if (wet) {
                    // Mud flies back off the tyre and up
                    float sp = 2f + 4f * k;
                    add(w.x, w.y, w.z + 0.05f, k, 1f, fw.x * back * sp, fw.z * back * sp);
                } else {
                    add(w.x, w.y, w.z + 0.05f, k, 0f, wx + fw.x * back * 0.6f, wy + fw.z * back * 0.6f);
                }
            }
            bowSpray(v, script, tr, n * 3, fw, kmh, wx, wy);
        }
    }

    // Ploughing forward through deep snow, the bumper throws snow up and out to both sides
    private static void bowSpray(BaseVehicle v, zombie.scripting.objects.VehicleScript script, float[] tr, int o, org.joml.Vector3f fw,
        float kmh, float wx, float wy) {
        if (v.getCurrentSpeedKmHour() < 0f) return;
        org.joml.Vector3f ext = script.getExtents();
        float fl = (float) Math.sqrt(fw.x * fw.x + fw.z * fw.z);
        if (fl < 1e-4f) return;
        float fx = fw.x / fl, fy = fw.z / fl;
        float half = ext.z * 0.5f + 0.15f, side = ext.x * 0.5f;
        float bx = v.getX() + fx * half, by = v.getY() + fy * half;
        float mx = bx - tr[o], my = by - tr[o + 1];
        tr[o] = bx;
        tr[o + 1] = by;
        float d = (float) Math.sqrt(mx * mx + my * my);
        if (d > 3f) return;
        tr[o + 2] += d;
        if (tr[o + 2] < 0.35f) return;
        tr[o + 2] = 0f;
        float h = com.pzoptimizer.pzoe.weather.WeatherSnow.depthAt(bx, by, v.getZ());
        if (h < 0.05f) return;
        float k = Math.min(1f, (h - 0.05f) / 0.15f + 0.4f) * Math.min(1f, kmh / 30f);
        float out = 1.2f + 2f * k;
        for (int s = -1; s <= 1; s += 2) {
            float cx = bx - fy * side * s, cy = by + fx * side * s;
            add(cx, cy, v.getZ() + 0.1f, k, 2f, fx * 0.8f * k - fy * out * s + wx * 0.5f, fy * 0.8f * k + fx * out * s + wy * 0.5f);
        }
    }

    // Bare natural ground: the natural floor tiles without much grass growing on the square
    static boolean natural(IsoGridSquare sq) {
        zombie.iso.IsoObject floor = sq.getFloor();
        String name = floor == null || floor.sprite == null ? null : floor.sprite.getName();
        if (!NaturalGround.isNaturalName(name)) return false;
        java.util.List<zombie.iso.IsoObject> objs = com.pzoptimizer.GameCompat.objects(sq);
        for (int i = 0, n = objs.size(); i < n; i++) {
            zombie.iso.IsoObject o = objs.get(i);
            zombie.core.properties.PropertyContainer pr = o == null ? null : o.getProperties();
            if (pr != null && pr.has(zombie.iso.SpriteDetails.IsoFlagType.vegitation)) return false;
        }
        return true;
    }

    private static void add(float x, float y, float z, float strength, float kind, float vx, float vy) {
        int o = next * F;
        ring[o] = x;
        ring[o + 1] = y;
        ring[o + 2] = z;
        ring[o + 3] = clock;
        ring[o + 4] = strength;
        ring[o + 5] = kind;
        ring[o + 6] = vx;
        ring[o + 7] = vy;
        next = (next + 1) % RING;
        used = Math.min(RING, used + 1);
    }

    /** This frame's puffs for one player's camera, as the shader wants them, or null with none. */
    static float[] project(int playerIndex, float zoom, int texW, int texH) {
        if (used == 0) return null;
        PlayerCamera cam = IsoCamera.cameras[playerIndex];
        if (cam == null || texW <= 0 || texH <= 0 || zoom <= 0f) return null;
        float left = IsoCamera.getScreenLeft(playerIndex), top = IsoCamera.getScreenTop(playerIndex);
        float sh = IsoCamera.getScreenHeight(playerIndex);
        float tile = (IsoUtils.XToScreen(1f, 0f, 0f, 0) - IsoUtils.XToScreen(0f, 1f, 0f, 0)) / zoom;
        float[] out = new float[MAX * 4];
        int n = 0;
        // Newest first, so a long trail drops its oldest puffs when there are too many
        for (int j = 0; j < used && n < MAX; j++) {
            int i = Math.floorMod(next - 1 - j, RING), o = i * F;
            float age = clock - ring[o + 3];
            boolean snow = ring[o + 5] > 1.5f;
            boolean mud = !snow && ring[o + 5] > 0.5f;
            float life = snow ? 1.3f : mud ? 0.6f : 3.2f;
            if (age < 0f || age >= life) continue;
            float f = age / life;
            float slow = snow ? 1f - 0.5f * f : mud ? 1f : 0.6f;
            float x = ring[o] + ring[o + 6] * age * slow, y = ring[o + 1] + ring[o + 7] * age * slow;
            // Mud arcs up and falls back; snow powder is thrown up and settles as it spreads; dust lifts slowly
            float z = ring[o + 2] + (mud ? 0.5f * age * (1.6f - 2.7f * age) : snow ? 0.3f * age * (1.4f - age) : 0.25f * f);
            float r = mud ? 0.07f : snow ? 0.12f + 0.55f * (float) Math.sqrt(f) : 0.25f + 1.2f * (float) Math.sqrt(f);
            float alpha = ring[o + 4] * amount * (mud ? 0.9f * (1f - f) : snow ? 0.6f * (1f - f) * Math.min(1f, age * 10f)
                : 0.32f * (1f - f) * (1f - f) * Math.min(1f, age * 6f));
            float sx = cam.XToScreenExact(x, y, z, 0) / zoom, sy = cam.YToScreenExact(x, y, z, 0) / zoom;
            out[n * 4] = (left + sx) / texW;
            out[n * 4 + 1] = (top + sh - sy) / texH;
            out[n * 4 + 2] = r * tile * 0.5f;
            // Shader: negative = mud, 0..1 = dust, 2 + alpha = snow
            out[n * 4 + 3] = mud ? -alpha : snow ? 2f + Math.min(alpha, 0.99f) : Math.min(alpha, 1f);
            n++;
        }
        if (n == 0) return null;
        return java.util.Arrays.copyOf(out, n * 4);
    }

    static String stateText() {
        return "dustPuffs=" + used;
    }
}
