package com.pzoptimizer.pzoe;

import zombie.characters.IsoPlayer;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.PlayerCamera;

/**
 * Fireflies on warm summer nights for PZO Enhancements' Fireflies card (screen.frag declares PZOFireflies):
 *
 *   uniform vec4 PZOFireflies[16];   per swarm: (u, v in the world image, seed, strength)
 *   uniform vec4 PZOFireflyInfo;     count, clock, 0, 1 = live
 *
 * A swarm is a spot of grass or bushes outdoors on the ground near player 0; the shader flies a handful of
 * fireflies round it on their own paths (through PZOWorldBasis, the world's axes on screen) and blinks them. Spots
 * are picked again every 20 seconds or when the player has walked off. Only June to August (a few in May and
 * September), at night, above about 14 C, without rain or strong wind. Main thread, from ScreenPass.main.
 */
final class Fireflies {

    static final int MAX = 16;
    private static final int RANGE = 18;

    static volatile long askedNanos;
    // Card amount (modApi "fireflies"), 1 = 100 percent
    static volatile float amount = 1f;

    // x, y, z, seed per spot
    private static final float[] spots = new float[MAX * 4];
    private static final IsoGridSquare[] squares = new IsoGridSquare[MAX];
    private static int count;
    private static long lastPick;
    private static float pickX = Float.NaN, pickY = Float.NaN, strength;
    private static final java.util.Random rnd = new java.util.Random();

    private Fireflies() {}

    // How many fireflies the month has (0-based months)
    private static final float[] MONTH = {0f, 0f, 0f, 0f, 0.35f, 1f, 1f, 0.9f, 0.3f, 0f, 0f, 0f};

    /** Main thread, once a frame for player 0 while the shader asks. */
    static void frame() {
        try {
            zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
            zombie.GameTime gt = zombie.GameTime.getInstance();
            IsoPlayer p0 = IsoPlayer.players[0];
            if (cm == null || gt == null || p0 == null) {
                count = 0;
                return;
            }
            float season = MONTH[Math.floorMod(gt.getMonth(), 12)];
            float night = smooth(0.35f, 0.75f, cm.getNightStrength());
            float warm = smooth(12f, 17f, cm.getTemperature());
            float dry = 1f - smooth(0.02f, 0.12f, com.pzoptimizer.pzoe.weather.WeatherLook.precipitation());
            float calm = 1f - smooth(0.35f, 0.7f, com.pzoptimizer.pzoe.weather.WeatherLook.wind());
            strength = season * night * warm * dry * calm;
            if (strength < 0.01f || p0.getZ() < -0.5f || p0.getZ() > 0.9f) {
                count = 0;
                return;
            }
            long now = System.currentTimeMillis();
            float dx = p0.getX() - pickX, dy = p0.getY() - pickY;
            if (now - lastPick > 20_000 || Float.isNaN(pickX) || dx * dx + dy * dy > 100f) {
                lastPick = now;
                pickX = p0.getX();
                pickY = p0.getY();
                pick(p0);
            }
        } catch (Throwable t) {
            count = 0;
        }
    }

    private static void pick(IsoPlayer p0) {
        count = 0;
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (cell == null) return;
        int px = (int) Math.floor(p0.getX()), py = (int) Math.floor(p0.getY());
        int want = Math.max(4, Math.min(MAX, Math.round(12 * Math.min(1.5f, amount))));
        for (int tries = 0; tries < 90 && count < want; tries++) {
            int x = px + rnd.nextInt(2 * RANGE + 1) - RANGE, y = py + rnd.nextInt(2 * RANGE + 1) - RANGE;
            if ((x - px) * (x - px) + (y - py) * (y - py) < 9) continue;
            IsoGridSquare sq = cell.getGridSquare(x, y, 0);
            if (sq == null || !sq.isOutside() || !leafy(sq)) continue;
            int o = count * 4;
            spots[o] = x + 0.5f;
            spots[o + 1] = y + 0.5f;
            spots[o + 2] = 0f;
            spots[o + 3] = rnd.nextFloat() * 97f;
            squares[count] = sq;
            count++;
        }
    }

    // Grass, bushes or a natural floor
    private static boolean leafy(IsoGridSquare sq) {
        zombie.iso.IsoObject floor = sq.getFloor();
        String name = floor == null || floor.sprite == null ? null : floor.sprite.getName();
        if (NaturalGround.isNaturalName(name)) return true;
        java.util.List<zombie.iso.IsoObject> objs = com.pzoptimizer.GameCompat.objects(sq);
        for (int i = 0, n = objs.size(); i < n; i++) {
            zombie.iso.IsoObject o = objs.get(i);
            zombie.core.properties.PropertyContainer pr = o == null ? null : o.getProperties();
            if (pr != null && pr.has(zombie.iso.SpriteDetails.IsoFlagType.vegitation)) return true;
        }
        return false;
    }

    /** This frame's swarms for one player's camera, or null with none. */
    static float[] project(int playerIndex, float zoom, int texW, int texH) {
        int n = count;
        if (n == 0 || playerIndex != 0) return null;
        PlayerCamera cam = IsoCamera.cameras[playerIndex];
        if (cam == null || texW <= 0 || texH <= 0 || zoom <= 0f) return null;
        float left = IsoCamera.getScreenLeft(playerIndex), top = IsoCamera.getScreenTop(playerIndex);
        float sh = IsoCamera.getScreenHeight(playerIndex);
        float[] out = new float[n * 4];
        int k = 0;
        for (int i = 0; i < n; i++) {
            IsoGridSquare sq = squares[i];
            // Squares out of sight are dark under the vision cone; no fireflies there
            if (sq == null || !sq.isCanSee(playerIndex)) continue;
            int o = i * 4;
            out[k * 4] = (left + cam.XToScreenExact(spots[o], spots[o + 1], spots[o + 2], 0) / zoom) / texW;
            out[k * 4 + 1] = (top + sh - cam.YToScreenExact(spots[o], spots[o + 1], spots[o + 2], 0) / zoom) / texH;
            out[k * 4 + 2] = spots[o + 3];
            out[k * 4 + 3] = strength * Math.min(2f, amount);
            k++;
        }
        return k == 0 ? null : k == n ? out : java.util.Arrays.copyOf(out, k * 4);
    }

    private static float smooth(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    static String stateText() {
        return "fireflies=" + count + ";fireflyStrength=" + strength;
    }
}
