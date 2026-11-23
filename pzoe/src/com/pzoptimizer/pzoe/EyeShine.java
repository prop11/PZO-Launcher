package com.pzoptimizer.pzoe;

import java.util.ArrayList;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.PlayerCamera;

/**
 * Eye shine for PZO Enhancements' Eye Shine card (screen.frag declares PZOEyes): zombies and animals caught in a
 * torch beam or headlights near player 0 and looking back toward the light show two small glints where their eyes
 * are, the way eyes throw light straight back at its source. The viewer stands next to the light, so it reads.
 *
 *   uniform vec4 PZOEyes[16];    per pair: (u, v in the world image, brightness, half the gap between the eyes in
 *                                pixels; negative brightness = an animal's green shine)
 *   uniform vec4 PZOEyesInfo;    count, 0, 0, 1 = live
 *
 * Main thread, from ScreenPass.main: every frame, since the lights and heads both move. Only cone lights (torches
 * and headlights) within 16 squares of player 0, and only in the dark.
 */
final class EyeShine {

    static final int MAX = 16;
    private static final float RANGE = 16f;

    static volatile long askedNanos;
    // Card strength (modApi "eyeShine")
    static volatile float amount = 1f;

    // x, y, z, brightness (signed), facing x, y, distance squared
    private static final int F = 7;
    private static final float[] eyes = new float[MAX * F];
    private static int count;
    private static final ArrayList<IsoGameCharacter.TorchInfo> torches = new ArrayList<>();
    private static final ArrayList<IsoGameCharacter> near = new ArrayList<>();
    private static long lastNear;

    private EyeShine() {}

    /** Main thread, once a frame for player 0 while the shader asks. */
    static void frame() {
        count = 0;
        try {
            zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
            IsoPlayer p0 = IsoPlayer.players[0];
            IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
            if (p0 == null || cell == null) return;
            float dark = Math.max(0f, Math.min(1f, 1f - cm.getDayLightStrength() * 1.25f));
            boolean inside = p0.getCurrentSquare() != null && !p0.getCurrentSquare().isOutside();
            if (inside) dark = Math.max(dark, 0.6f);
            if (dark < 0.05f) return;
            torches.clear();
            zombie.iso.LightingJNI.getTorches(torches);
            if (torches.isEmpty()) return;
            float px = p0.getX(), py = p0.getY();
            long now = System.currentTimeMillis();
            if (now - lastNear > 250) {
                lastNear = now;
                near.clear();
                for (IsoMovingObject o : cell.getObjectList()) {
                    if (!(o instanceof IsoGameCharacter c) || c instanceof IsoPlayer || c.isDead()) continue;
                    if (!(c instanceof IsoZombie) && !(c instanceof IsoAnimal)) continue;
                    float dx = c.getX() - px, dy = c.getY() - py;
                    if (dx * dx + dy * dy < (RANGE + 4f) * (RANGE + 4f)) near.add(c);
                }
            }
            for (int ci = 0, cn = near.size(); ci < cn; ci++) {
                IsoGameCharacter c = near.get(ci);
                if (c.isDead() || c.getCurrentSquare() == null || !c.getCurrentSquare().isCanSee(0)) continue;
                zombie.iso.Vector2 fwd = c.getForwardDirection();
                float best = 0f;
                for (int i = 0, n = torches.size(); i < n; i++) {
                    IsoGameCharacter.TorchInfo t = torches.get(i);
                    if (t == null || !t.cone || t.strength <= 0.05f || Math.abs(t.z - c.getZ()) > 1.2f) continue;
                    // Only lights next to the viewer throw the shine back at the camera
                    float ox = t.x - px, oy = t.y - py;
                    if (ox * ox + oy * oy > 9f) continue;
                    float vx = c.getX() - t.x, vy = c.getY() - t.y, d = (float) Math.sqrt(vx * vx + vy * vy);
                    float reach = Math.max(4f, Math.min(RANGE, t.dist > 0f ? t.dist * 1.3f : RANGE));
                    if (d < 0.8f || d > reach) continue;
                    float al = (float) Math.sqrt(t.angleX * t.angleX + t.angleY * t.angleY);
                    if (al < 1e-4f) continue;
                    float inCone = (vx * t.angleX + vy * t.angleY) / (d * al);
                    float edge = Math.min(0.97f, Math.max(0.5f, t.dot));
                    if (inCone < edge) continue;
                    float cone = Math.min(1f, (inCone - edge) / Math.max(0.02f, 1f - edge) * 2.5f);
                    // Looking back toward the light
                    float look = -(fwd.x * vx + fwd.y * vy) / d;
                    if (look < 0.4f) continue;
                    float k = cone * (look - 0.4f) / 0.6f * (1f - d / reach) * Math.min(1.2f, t.strength);
                    if (k > best) best = k;
                }
                if (best < 0.03f) continue;
                boolean animal = c instanceof IsoAnimal;
                float h = animal ? 0.32f * Math.max(0.4f, Math.min(1.6f, ((IsoAnimal) c).getAnimalSize()))
                    : (c.isCrawling() || c.isOnFloor() ? 0.1f : 0.64f);
                float k = best * dark * amount * (animal ? 1.2f : 1f);
                offer(px, py, c.getX() + fwd.x * 0.12f, c.getY() + fwd.y * 0.12f, c.getZ() + h, animal ? -k : k, fwd.x, fwd.y);
            }
        } catch (Throwable t) {
            count = 0;
        }
    }

    private static void offer(float px, float py, float x, float y, float z, float k, float fx, float fy) {
        float dx = x - px, dy = y - py, d = dx * dx + dy * dy;
        int at = count;
        if (count >= MAX) {
            at = -1;
            float far = d;
            for (int i = 0; i < MAX; i++) {
                if (eyes[i * F + 6] > far) {
                    far = eyes[i * F + 6];
                    at = i;
                }
            }
            if (at < 0) return;
        } else {
            count++;
        }
        int o = at * F;
        eyes[o] = x;
        eyes[o + 1] = y;
        eyes[o + 2] = z;
        eyes[o + 3] = k;
        eyes[o + 4] = fx;
        eyes[o + 5] = fy;
        eyes[o + 6] = d;
    }

    /** This frame's eyes for one player's camera, or null with none. */
    static float[] project(int playerIndex, float zoom, int texW, int texH) {
        int n = count;
        if (n == 0 || playerIndex != 0) return null;
        PlayerCamera cam = IsoCamera.cameras[playerIndex];
        if (cam == null || texW <= 0 || texH <= 0 || zoom <= 0f) return null;
        float left = IsoCamera.getScreenLeft(playerIndex), top = IsoCamera.getScreenTop(playerIndex);
        float sh = IsoCamera.getScreenHeight(playerIndex);
        float tile = (IsoUtils.XToScreen(1f, 0f, 0f, 0) - IsoUtils.XToScreen(0f, 1f, 0f, 0)) / zoom;
        float[] out = new float[n * 4];
        for (int i = 0; i < n; i++) {
            int o = i * F;
            out[i * 4] = (left + cam.XToScreenExact(eyes[o], eyes[o + 1], eyes[o + 2], 0) / zoom) / texW;
            out[i * 4 + 1] = (top + sh - cam.YToScreenExact(eyes[o], eyes[o + 1], eyes[o + 2], 0) / zoom) / texH;
            out[i * 4 + 2] = eyes[o + 3];
            // The eyes sit across the facing; a head turned side-on shows them close together. x on screen is (x - y)
            // times half a tile.
            float acrossX = -eyes[o + 5], acrossY = eyes[o + 4];
            out[i * 4 + 3] = Math.max(0.6f, Math.abs(0.045f * (acrossX - acrossY) * 0.5f * tile));
        }
        return out;
    }

    static String stateText() {
        return "eyeShine=" + count;
    }
}
