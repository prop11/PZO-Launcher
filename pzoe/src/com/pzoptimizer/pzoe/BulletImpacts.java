package com.pzoptimizer.pzoe;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.lwjgl.opengl.GL20;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoUtils;
import zombie.iso.PlayerCamera;

/**
 * Bullet impacts by surface, for PZO Enhancements' Bullet Impacts. When a tracer reaches the square that stopped it,
 * vanilla calls CombatManager.hitIsoGridSquare (any shooter, local or remote); a hook at its head hands the spot and
 * the square here. The square's own MaterialType (the one vanilla picks its impact sound by) says what was hit.
 *
 * Burst (screen.frag declares PZOImpactFx): sparks off metal, splinters off wood, a dust puff and chips off brick,
 * concrete and plaster, glittering shards off glass, a brown puff off earth. Up to 16 at once, under a second each.
 *   uniform vec4 PZOImpactFx[16];   xy = spot in the world image's UV, z = one square's width in pixels,
 *                                   w = kind + age (age 0..1 of its life)
 *   uniform vec4 PZOImpactFxInfo;   count, clock, 0, 1 = live
 *
 * Holes (chunk shader declares PZOImpact): a small dark hole where the bullet went in, with a chipped pale rim in
 * masonry, splinters in wood, a bright dented ring in metal and a crack star in glass, fading out over 3 game days.
 * Kept for the session only (not saved). The 24 nearest player 0 within 25 squares go to the chunk shader.
 *   uniform vec4 PZOImpact[24];     xyz = world spot (squares, levels), w = kind + fade (1 fresh .. 0 gone)
 *   uniform vec4 PZOImpactInfo;     count, 0, 0, 1 = live
 */
public final class BulletImpacts {
    public static final String COMBAT = "zombie/CombatManager";
    private static final String OWNER = "com/pzoptimizer/pzoe/BulletImpacts";

    static final int METAL = 0, WOOD = 1, MASONRY = 2, GLASS = 3, EARTH = 4, SOFT = 5;
    static final int FX_MAX = 16, HOLE_MAX = 24;
    private static final int STORE = 256, F = 5;
    private static final float HOLE_DAYS = 3f, HOLE_RANGE_SQ = 25f * 25f;
    // Burst lifetimes in seconds, by kind
    private static final float[] LIFE = {0.35f, 0.55f, 1.2f, 0.7f, 0.9f, 0.45f};

    // Render thread sets them from the uniform lookups
    static volatile boolean fxWanted, holesWanted;
    static volatile long fxAskedNanos;
    static volatile float amount = 1f;

    // x, y, z, kind, game hours when it hit; fire time (ns) beside it
    private static final float[] store = new float[STORE * F];
    private static final long[] storeNanos = new long[STORE];
    private static final double[] storeHours = new double[STORE];
    private static int head, size;
    // For the -debug status line: hook calls, and where the last one landed
    private static volatile int calls;
    private static volatile String last = "none";

    private static volatile float[] holes = new float[0];
    private static long lastGather;
    private static final FloatBuffer holeBuf = ByteBuffer.allocateDirect(HOLE_MAX * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static float[] uploaded;

    private BulletImpacts() {}

    /** Hook: head of CombatManager.hitIsoGridSquare(square, hitLocation, isLocalShooter). */
    public static void hit(IsoGridSquare square, org.joml.Vector3f at) {
        calls++;
        if ((!fxWanted && !holesWanted) || square == null || at == null) return;
        try {
            int kind = kind(square);
            double hours = zombie.GameTime.getInstance().getWorldAgeHours();
            synchronized (store) {
                int o = head * F;
                store[o] = at.x();
                store[o + 1] = at.y();
                store[o + 2] = at.z();
                store[o + 3] = kind;
                storeNanos[head] = System.nanoTime();
                storeHours[head] = hours;
                head = (head + 1) % STORE;
                if (size < STORE) size++;
            }
            last = String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.2f kind %d", at.x(), at.y(), at.z(), kind);
        } catch (Throwable ignored) {
            // Never in the way of a shot
        }
    }

    private static int kind(IsoGridSquare square) {
        String m = square.getProperties().get("MaterialType");
        if (m == null) return MASONRY;
        if (m.startsWith("Metal")) return METAL;
        if (m.startsWith("Wood")) return WOOD;
        if (m.startsWith("Glass")) return GLASS;
        return switch (m) {
            case "Dirt", "Grass", "Gravel", "Sand", "Snow" -> EARTH;
            case "Fabric", "Carpet", "Rubber", "Plastic", "Flesh", "Flesh_Hollow" -> SOFT;
            default -> MASONRY;
        };
    }

    /** Main thread, once a frame (PixelLight.frame): the holes near player 0, 4 times a second. */
    static void frame() {
        if (!holesWanted) {
            if (holes.length > 0) holes = new float[0];
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastGather < 250) return;
        lastGather = now;
        IsoPlayer p = IsoPlayer.players[0];
        if (p == null || zombie.GameTime.getInstance() == null) {
            holes = new float[0];
            return;
        }
        double hours = zombie.GameTime.getInstance().getWorldAgeHours();
        float px = p.getX(), py = p.getY();
        float[] pick = new float[HOLE_MAX * 4];
        float[] dist = new float[HOLE_MAX];
        int n = 0;
        synchronized (store) {
            for (int k = 0; k < size; k++) {
                int i = Math.floorMod(head - 1 - k, STORE), o = i * F;
                float fade = 1f - (float) ((hours - storeHours[i]) / (HOLE_DAYS * 24.0));
                if (fade <= 0f || store[o + 3] == EARTH) continue;
                float dx = store[o] - px, dy = store[o + 1] - py, d = dx * dx + dy * dy;
                if (d > HOLE_RANGE_SQ) continue;
                int at = n < HOLE_MAX ? n++ : -1;
                if (at < 0) {
                    float far = d;
                    for (int j = 0; j < HOLE_MAX; j++) {
                        if (dist[j] > far) {
                            far = dist[j];
                            at = j;
                        }
                    }
                    if (at < 0) continue;
                }
                dist[at] = d;
                pick[at * 4] = store[o];
                pick[at * 4 + 1] = store[o + 1];
                pick[at * 4 + 2] = store[o + 2];
                pick[at * 4 + 3] = store[o + 3] + Math.min(0.999f, fade);
            }
        }
        float[] out = new float[n * 4];
        System.arraycopy(pick, 0, out, 0, n * 4);
        holes = out;
    }

    /** Render thread, per chunk draw. */
    static void upload(int locArray, int locInfo) {
        if (locInfo < 0) return;
        float[] d = holes;
        int n = d.length / 4;
        if (n > 0 && locArray >= 0 && d != uploaded) {
            holeBuf.clear();
            holeBuf.put(d);
            holeBuf.flip();
            GL20.glUniform4fv(locArray, holeBuf);
        }
        uploaded = d;
        GL20.glUniform4f(locInfo, n, 0f, 0f, n > 0 ? 1f : 0f);
    }

    static void invalidate() {
        uploaded = null;
    }

    /** This frame's bursts for one player's camera, 4 floats each, or null with none. */
    static float[] projectFx(int playerIndex, float zoom, int texW, int texH) {
        if (!fxWanted || size == 0) return null;
        PlayerCamera cam = IsoCamera.cameras[playerIndex];
        if (cam == null || texW <= 0 || texH <= 0 || zoom <= 0f) return null;
        float left = IsoCamera.getScreenLeft(playerIndex), top = IsoCamera.getScreenTop(playerIndex);
        float sh = IsoCamera.getScreenHeight(playerIndex);
        float tile = (IsoUtils.XToScreen(1f, 0f, 0f, 0) - IsoUtils.XToScreen(0f, 1f, 0f, 0)) / zoom;
        long now = System.nanoTime();
        float[] out = null;
        int n = 0;
        synchronized (store) {
            for (int k = 0; k < size && n < FX_MAX; k++) {
                int i = Math.floorMod(head - 1 - k, STORE), o = i * F;
                int kind = (int) store[o + 3];
                float age = (now - storeNanos[i]) / 1e9f / LIFE[kind];
                // Newest first: once one is past its life, all older ones are too, except longer-lived kinds
                if (age >= 1f || age < 0f) {
                    if ((now - storeNanos[i]) / 1e9f > 1.5f) break;
                    continue;
                }
                if (out == null) out = new float[FX_MAX * 4];
                float sx = cam.XToScreenExact(store[o], store[o + 1], store[o + 2], 0) / zoom;
                float sy = cam.YToScreenExact(store[o], store[o + 1], store[o + 2], 0) / zoom;
                out[n * 4] = (left + sx) / texW;
                out[n * 4 + 1] = (top + sh - sy) / texH;
                out[n * 4 + 2] = tile * amount;
                out[n * 4 + 3] = kind + Math.min(0.999f, age);
                n++;
            }
        }
        if (n == 0) return null;
        float[] trimmed = new float[n * 4];
        System.arraycopy(out, 0, trimmed, 0, n * 4);
        return trimmed;
    }

    static boolean patch(ClassNode node) {
        var method = AsmSupport.findMethod(node, "hitIsoGridSquare", "(Lzombie/iso/IsoGridSquare;Lorg/joml/Vector3f;Z)Z");
        if (method == null) return false;
        var hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "hit", "(Lzombie/iso/IsoGridSquare;Lorg/joml/Vector3f;)V", false));
        method.instructions.insert(hook);
        return true;
    }

    static String stateText() {
        return "impactCalls=" + calls + ";impacts=" + size + ";impactLast=" + last + ";impactFx=" + (fxWanted ? 1 : 0)
            + ";impactHoles=" + (holesWanted ? 1 : 0) + ";holes=" + holes.length / 4;
    }
}
