package com.pzoptimizer.pzoe;

import java.util.ArrayList;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.textures.Texture;
import zombie.iso.IsoDirections;
import zombie.iso.IsoCamera;
import zombie.iso.IsoLightSource;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.PlayerCamera;
import zombie.iso.objects.IsoFire;
import zombie.iso.objects.IsoFireManager;
import zombie.iso.objects.IsoLightSwitch;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteManager;

/**
 * Lens glare on bright lights at night for PZO Enhancements' Lens Glare (screen.frag declares PZOGlare):
 *
 *   uniform vec4 PZOGlare[48];   per light: (u, v in the world image, one square's width in pixels, brightness),
 *                                (r, g, b, 0)
 *   uniform vec4 PZOGlareInfo;   count, rain 0..1, night 0..1, 1 = live
 *
 * Sources: the game's cone lights (headlights and torches, brighter the more they point toward the camera), active
 * outdoor lamps (street lights, porch lights; not the short-lived muzzle flash ones), and fires. The 24 nearest to
 * player 0 within 30 squares. Main thread, from ScreenPass.main: lamps and fires picked 4 times a second, cone
 * lights every frame since they move.
 */
final class Glare {

    static final int MAX = 24;
    private static final float RANGE_SQ = 30f * 30f;

    static volatile long askedNanos;
    // Card strength (modApi "glare")
    static volatile float amount = 1f;
    static volatile float rain, night;

    // x, y, z, r, g, b, brightness, distance squared, screen offset x, y (pixels at zoom 1)
    private static final int F = 10;
    private static final float[] fixed = new float[MAX * F], all = new float[MAX * 2 * F];
    private static int fixedCount, allCount;
    private static long lastGather;
    private static final ArrayList<IsoGameCharacter.TorchInfo> torches = new ArrayList<>();
    // Where the bulb sits on a lamp's sprite, from its lit overlay; NO_BULB = lamp has no overlay
    private static final java.util.IdentityHashMap<IsoSprite, float[]> bulbs = new java.util.IdentityHashMap<>();
    private static final float[] NO_BULB = new float[0];

    private Glare() {}

    /** Main thread, once a frame from ScreenPass.main while the shader asks. */
    static void frame() {
        try {
            zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
            night = Math.max(0f, Math.min(1f, 1f - cm.getDayLightStrength() * 1.3f));
            rain = cm.getPrecipitationIsSnow() ? 0f : Math.max(0f, Math.min(1f, com.pzoptimizer.pzoe.weather.WeatherLook.precipitation()));
            IsoPlayer p0 = IsoPlayer.players[0];
            if (p0 == null || night <= 0.02f) {
                allCount = 0;
                return;
            }
            float px = p0.getX(), py = p0.getY();
            long now = System.currentTimeMillis();
            if (now - lastGather > 250) {
                lastGather = now;
                gatherFixed(px, py);
            }
            System.arraycopy(fixed, 0, all, 0, fixedCount * F);
            allCount = fixedCount;
            torches.clear();
            zombie.iso.LightingJNI.getTorches(torches);
            for (int i = 0, n = torches.size(); i < n; i++) {
                IsoGameCharacter.TorchInfo t = torches.get(i);
                if (t == null || !t.cone || t.strength <= 0.05f) continue;
                float ax = t.angleX, ay = t.angleY, al = (float) Math.sqrt(ax * ax + ay * ay);
                // The camera sits toward +x +y: a light pointing that way shines into the lens
                float facing = al < 1e-4f ? 0f : Math.max(0f, (ax + ay) / al * 0.7071f);
                float k = Math.min(1.5f, t.strength) * (0.25f + 0.75f * facing * facing);
                if (t.r > t.g * 1.6f) k *= 0.4f;
                allCount = offer(all, allCount, MAX, px, py, t.x, t.y, t.z + 0.3f, t.r, t.g, t.b, k, 0f, 0f);
            }
        } catch (Throwable e) {
            allCount = 0;
        }
    }

    private static void gatherFixed(float px, float py) {
        fixedCount = 0;
        zombie.iso.IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (cell == null) return;
        java.util.Stack<IsoLightSource> lights = cell.getLamppostPositions();
        for (int i = 0, n = lights.size(); i < n; i++) {
            IsoLightSource l = lights.get(i);
            if (l == null || !l.isActive() || l.startlife > 0 || l.getRadius() < 4) continue;
            zombie.iso.IsoGridSquare sq = cell.getGridSquare(l.x, l.y, l.z);
            if (sq == null || !sq.isOutside()) continue;
            float[] b = bulb(l);
            if (b != null) {
                fixedCount = offer(fixed, fixedCount, MAX, px, py, l.x, l.y, l.z, l.r, l.g, l.b, 0.8f, b[0], b[1]);
            } else {
                fixedCount = offer(fixed, fixedCount, MAX, px, py, l.x + 0.5f, l.y + 0.5f, l.z + 1.1f, l.r, l.g, l.b, 0.8f, 0f, 0f);
            }
        }
        for (int i = 0, n = IsoFireManager.FireStack.size(); i < n; i++) {
            IsoFire f = IsoFireManager.FireStack.get(i);
            if (f == null || f.getSquare() == null) continue;
            fixedCount = offer(fixed, fixedCount, MAX, px, py, f.getSquare().getX() + 0.5f, f.getSquare().getY() + 0.5f,
                f.getSquare().getZ() + 0.3f, 1f, 0.55f, 0.2f, 0.55f, 0f, 0f);
        }
    }

    // The arm puts the bulb a tile or so off the pole's square, on a side that depends on the sprite. The lit overlay
    // (<tileset>_on_<n>) is trimmed to just the lit part, so its centre is the bulb.
    private static float[] bulb(IsoLightSource l) {
        if (l.switches == null || l.switches.isEmpty()) return null;
        IsoLightSwitch sw = l.switches.get(0);
        IsoSprite s = sw == null ? null : sw.getSprite();
        if (s == null || s.tilesetName == null) return null;
        float[] b = bulbs.get(s);
        if (b == null) {
            IsoSprite on = IsoSpriteManager.instance.namedMap.get(s.tilesetName + "_on_" + s.tileSheetIndex);
            Texture t = on == null ? null : on.getTextureForFrame(0, IsoDirections.N);
            // Not loaded yet: try again next gather
            if (on != null && (t == null || t.getWidth() <= 0)) return null;
            b = NO_BULB;
            if (t != null) {
                float sc = t.getWidthOrig() == 64 && t.getHeightOrig() == 128 ? 2f : 1f;
                b = new float[] {
                    (t.getOffsetX() + t.getWidth() * 0.5f) * sc + on.soffX - sw.offsetX,
                    (t.getOffsetY() + t.getHeight() * 0.5f) * sc + on.soffY - sw.offsetY};
            }
            bulbs.put(s, b);
        }
        return b == NO_BULB ? null : b;
    }

    // The max nearest; a full list drops its farthest for a nearer one
    private static int offer(float[] a, int count, int max, float px, float py, float x, float y, float z, float r, float g, float b, float k, float ox, float oy) {
        float dx = x - px, dy = y - py, d = dx * dx + dy * dy;
        if (d > RANGE_SQ || k <= 0.01f) return count;
        int at = count;
        if (count >= max) {
            at = -1;
            float far = d;
            for (int i = 0; i < max; i++) {
                if (a[i * F + 7] > far) {
                    far = a[i * F + 7];
                    at = i;
                }
            }
            if (at < 0) return count;
        } else {
            count++;
        }
        int o = at * F;
        a[o] = x;
        a[o + 1] = y;
        a[o + 2] = z;
        a[o + 3] = r;
        a[o + 4] = g;
        a[o + 5] = b;
        a[o + 6] = k;
        a[o + 7] = d;
        a[o + 8] = ox;
        a[o + 9] = oy;
        return count;
    }

    /** This frame's lights for one player's camera, as the shader wants them, or null with none. */
    static float[] project(int playerIndex, float zoom, int texW, int texH) {
        int n = allCount;
        if (n == 0) return null;
        PlayerCamera cam = IsoCamera.cameras[playerIndex];
        if (cam == null || texW <= 0 || texH <= 0 || zoom <= 0f) return null;
        float left = IsoCamera.getScreenLeft(playerIndex), top = IsoCamera.getScreenTop(playerIndex);
        float sh = IsoCamera.getScreenHeight(playerIndex);
        float tile = (IsoUtils.XToScreen(1f, 0f, 0f, 0) - IsoUtils.XToScreen(0f, 1f, 0f, 0)) / zoom;
        float[] out = new float[n * 8];
        boolean depthWanted = SceneDepth.asked(System.nanoTime());
        for (int i = 0; i < n; i++) {
            int o = i * F;
            float sx = (cam.XToScreenExact(all[o], all[o + 1], all[o + 2], 0) + all[o + 8]) / zoom;
            float sy = (cam.YToScreenExact(all[o], all[o + 1], all[o + 2], 0) + all[o + 9]) / zoom;
            int d = i * 8;
            out[d] = (left + sx) / texW;
            out[d + 1] = (top + sh - sy) / texH;
            out[d + 2] = tile;
            out[d + 3] = all[o + 6] * amount;
            out[d + 4] = all[o + 3];
            out[d + 5] = all[o + 4];
            out[d + 6] = all[o + 5];
            // Scene depth of the bulb: the shader hides the glare when a wall or roof stands in front of it
            out[d + 7] = depthWanted ? SceneDepth.depthOf(playerIndex, all[o], all[o + 1], all[o + 2]) : -1f;
        }
        return out;
    }
}
