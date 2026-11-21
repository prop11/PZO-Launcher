package com.pzoptimizer.pzoe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.IdentityHashMap;
import org.lwjgl.opengl.GL20;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoLightSource;
import zombie.iso.IsoWorld;

/**
 * Muzzle flashes, explosions and molotovs lighting up what's around them for a moment, for the chunk shader patch that
 * declares PZOBurst (PZO Enhancements' Muzzle Flashes and Explosions).
 *
 *   uniform vec4 PZOBurst[32];     per flash: (world x, y, level, reach in squares), (r, g, b already times its fade, 0)
 *   uniform vec4 PZOBurstInfo;     count, 1 = height map bound (walls stop the light), 0, 1 = live
 *
 * The game gives each shot a light that lasts a few frames (EffectsManager.startMuzzleFlash adds a timed light to
 * the cell's lamppost list), but it goes through the light map, which a lit chunk takes a while to catch, so it often
 * shows late or not at all. Here every new timed light in that list is a flash, drawn per pixel the frame it starts.
 * Explosions and molotovs come from Lua (OnThrowableExplode, modApi "burst").
 *
 * Main thread, once a frame: the 16 nearest to player 0 within 40 squares. Render thread: one upload per chunk draw.
 */
final class FlashLights {

    static final int MAX = 16;
    private static final float RANGE_SQ = 40f * 40f;
    static final int KIND_SHOT = 0, KIND_EXPLOSION = 1, KIND_MOLOTOV = 2;

    // Render thread sets it from the uniform lookup
    static volatile boolean wanted;
    // Card strength (modApi "bursts"), 1 = 100 percent
    static volatile float amount = 1f;

    // Main thread: x, y, z, reach, r, g, b, start (s), length (s), kind
    private static final int F = 10;
    private static final float[] live = new float[MAX * F];
    private static int liveCount;
    private static IdentityHashMap<IsoLightSource, Boolean> seen = new IdentityHashMap<>(), seenNext = new IdentityHashMap<>();
    // Lua events wait here until the next frame (x, y, z, kind)
    private static final java.util.concurrent.ConcurrentLinkedQueue<float[]> events = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static volatile float[] published = new float[0];
    private static final long startNs = System.nanoTime();

    // Render thread
    private static final FloatBuffer buf = ByteBuffer.allocateDirect(MAX * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static float[] uploaded;

    private FlashLights() {}

    /** Lua (main thread): an explosion or molotov went off. */
    static void event(int kind, float x, float y, int z) {
        if (wanted) events.offer(new float[] { x, y, z, kind });
    }

    /** Main thread, once a frame (PixelLight.frame). */
    static void frame(float dayLight) {
        if (!wanted) {
            if (published.length > 0) published = new float[0];
            liveCount = 0;
            seen.clear();
            events.clear();
            return;
        }
        float now = (System.nanoTime() - startNs) / 1e9f;
        try {
            gather(now);
        } catch (Throwable t) {
            liveCount = 0;
        }
        // By day a flash barely shows outdoors; indoors the shader keeps more of it
        float dark = Math.max(0f, Math.min(1f, 1f - dayLight * 1.4f));
        float env = amount * (0.3f + 0.7f * dark);
        float[] out = new float[liveCount * 8];
        int n = 0;
        for (int i = 0; i < liveCount; i++) {
            int o = i * F;
            float t = (now - live[o + 7]) / live[o + 8];
            if (t >= 1f) continue;
            // A shot is a blink; a blast flares and dies away
            float k = live[o + 9] == KIND_SHOT ? 1f - t : (float) Math.exp(-3.5 * t) * (1f - t);
            int d = n * 8;
            out[d] = live[o];
            out[d + 1] = live[o + 1];
            out[d + 2] = live[o + 2];
            out[d + 3] = live[o + 3];
            out[d + 4] = live[o + 4] * k * env;
            out[d + 5] = live[o + 5] * k * env;
            out[d + 6] = live[o + 6] * k * env;
            n++;
        }
        published = n * 8 == out.length ? out : java.util.Arrays.copyOf(out, n * 8);
    }

    private static void gather(float now) {
        // Drop the finished ones
        int keep = 0;
        for (int i = 0; i < liveCount; i++) {
            int o = i * F;
            if (now - live[o + 7] >= live[o + 8]) continue;
            if (keep != i) System.arraycopy(live, o, live, keep * F, F);
            keep++;
        }
        liveCount = keep;
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        IsoPlayer p = IsoPlayer.players[0];
        if (cell == null || p == null) return;
        float px = p.getX(), py = p.getY();
        // New timed lights (shots); a light seen last frame is the same flash
        java.util.Stack<IsoLightSource> lights = cell.getLamppostPositions();
        seenNext.clear();
        for (int i = 0, n = lights.size(); i < n; i++) {
            IsoLightSource l = lights.get(i);
            // The game's muzzle flash light: radius 18, a few frames long. Built lights with fuel are timed too.
            if (l == null || l.startlife <= 0 || l.startlife > 12 || l.life <= 0 || l.radius != 18) continue;
            seenNext.put(l, Boolean.TRUE);
            if (seen.containsKey(l)) continue;
            add(px, py, l.x + 0.5f, l.y + 0.5f, l.z, 7f, 1.0f, 0.82f, 0.5f, now, 0.09f, KIND_SHOT);
        }
        IdentityHashMap<IsoLightSource, Boolean> swap = seen;
        seen = seenNext;
        seenNext = swap;
        for (float[] e; (e = events.poll()) != null; ) {
            if (e[3] == KIND_EXPLOSION) add(px, py, e[0], e[1], e[2], 13f, 3.2f, 2.3f, 1.3f, now, 0.6f, KIND_EXPLOSION);
            else add(px, py, e[0], e[1], e[2], 8f, 2.0f, 1.1f, 0.4f, now, 0.7f, KIND_MOLOTOV);
        }
    }

    // The MAX nearest; a full list drops its farthest for a nearer one
    private static void add(float px, float py, float x, float y, float z, float reach, float r, float g, float b, float now, float len, int kind) {
        float dx = x - px, dy = y - py, d = dx * dx + dy * dy;
        if (d > RANGE_SQ) return;
        int at = liveCount;
        if (liveCount >= MAX) {
            at = -1;
            float far = d;
            for (int i = 0; i < MAX; i++) {
                float ex = live[i * F] - px, ey = live[i * F + 1] - py, ed = ex * ex + ey * ey;
                if (ed > far) {
                    far = ed;
                    at = i;
                }
            }
            if (at < 0) return;
        } else {
            liveCount++;
        }
        int o = at * F;
        live[o] = x;
        live[o + 1] = y;
        live[o + 2] = z;
        live[o + 3] = reach;
        live[o + 4] = r;
        live[o + 5] = g;
        live[o + 6] = b;
        live[o + 7] = now;
        live[o + 8] = len;
        live[o + 9] = kind;
    }

    /** Render thread, per chunk draw. */
    static void upload(int locArray, int locInfo, boolean heightsBound) {
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
        GL20.glUniform4f(locInfo, n, heightsBound ? 1f : 0f, 0f, n > 0 ? 1f : 0f);
    }

    /** A new or rebuilt chunk program: send the flashes again. */
    static void invalidate() {
        uploaded = null;
    }

    static String stateText() {
        return "burstWanted=" + (wanted ? 1 : 0) + ";bursts=" + (published.length / 8);
    }
}
