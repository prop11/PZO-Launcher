package com.pzoptimizer.pzoe;

import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;

/**
 * How bright the scene is, for PZO Enhancements' Eye Adaptation card (screen.frag declares PZOEye):
 *
 *   uniform vec4 PZOEye;   x = log of the scene's brightness now, y = log of what the eyes are used to,
 *                          z = how dark-adapted the eyes are 0..1 (night vision shift), w = 1 when live
 *
 * The shader turns x - y into an exposure, so in steady light nothing changes: only the moments after the light
 * changes do (stepping into a dark house starts darker and opens up, stepping out starts blown out). Gameplay
 * brightness stays the game's.
 *
 * The brightness is read from the world image's own mipmaps (the same ones bloom uses): one small level, about 32
 * texels across the visible part, copied into a pixel buffer and read three frames later, so nothing waits on the
 * GPU. Centre-weighted log average, like a camera's metering. Render thread only.
 */
final class EyeAdapt {

    private static final int RING = 3;
    private static final int TARGET = 32;

    private static final int[] pbo = new int[RING];
    private static final int[] pboW = new int[RING], pboH = new int[RING], pboPlayer = new int[RING];
    private static final float[][] pboRect = new float[RING][4];
    private static final boolean[] pending = new boolean[RING];
    private static int slot;

    private static final float[] cur = new float[4], adapted = new float[4], dark = new float[4];
    private static final boolean[] seeded = new boolean[4];
    private static final long[] lastNs = new long[4];
    private static final int[] lastFrame = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};

    private static int sizeTex = -1, texW, texH;
    private static long sizeCheckedNs;
    private static final float[] toLinear = new float[256];
    private static ByteBuffer mapped;
    static volatile long reads;

    static {
        for (int i = 0; i < 256; i++) toLinear[i] = (float) Math.pow(i / 255.0, 2.2);
    }

    private EyeAdapt() {}

    /**
     * Once per screen draw. worldTex 0 = nothing to read this frame (only the uniform's last value goes up).
     * rect = visible part of the world texture in UV (u0, v0, u1, v1).
     */
    static void render(int loc, int worldTex, int frame, int player, float[] rect) {
        if (player < 0 || player >= 4) player = 0;
        if (worldTex != 0 && rect != null && frame != lastFrame[player]) {
            lastFrame[player] = frame;
            try {
                collect();
                request(worldTex, frame, player, rect);
            } catch (Throwable t) {
                // A driver without pixel buffers just leaves the card at the game's exposure
            }
            step(player);
        }
        GL20.glUniform4f(loc, cur[player], adapted[player], dark[player], seeded[player] ? 1f : 0f);
    }

    private static void step(int p) {
        if (!seeded[p]) return;
        long now = System.nanoTime();
        float dt = lastNs[p] == 0 ? 0f : Math.min(0.1f, (now - lastNs[p]) / 1e9f);
        lastNs[p] = now;
        if (zombie.GameTime.isGamePaused()) dt = 0f;
        // Eyes catch up with brighter light in about half a second and take a couple of seconds in the dark
        float tau = cur[p] > adapted[p] ? 0.5f : 2.0f;
        adapted[p] += (cur[p] - adapted[p]) * (1f - (float) Math.exp(-dt / tau));
        // Dark adapted below a scene brightness of about 0.1 (linear), fully by about 0.015; follows the adapted
        // value, so the blue shift creeps in the way rod vision does
        float d = (-2.3f - adapted[p]) / 1.9f;
        dark[p] = d < 0f ? 0f : d > 1f ? 1f : d;
    }

    // The oldest buffer, written RING frames ago, read just before it's written again
    private static void collect() {
        int s = slot;
        if (!pending[s]) return;
        pending[s] = false;
        int prev = GlGet.integer(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[s]);
        try {
            mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY, mapped);
            if (mapped == null) return;
            float lum = average(mapped, pboW[s], pboH[s], pboRect[s]);
            GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            int p = pboPlayer[s];
            // A lightning flash or a muzzle flash shouldn't swing the exposure on its own: a short smoothing first
            float l = (float) Math.log(lum + 0.001f);
            if (!seeded[p]) {
                cur[p] = l;
                adapted[p] = l;
                seeded[p] = true;
            } else {
                cur[p] += (l - cur[p]) * 0.35f;
            }
            reads++;
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prev);
        }
    }

    private static float average(ByteBuffer b, int w, int h, float[] r) {
        int x0 = Math.max(0, (int) Math.floor(r[0] * w)), x1 = Math.min(w, (int) Math.ceil(r[2] * w));
        int y0 = Math.max(0, (int) Math.floor(r[1] * h)), y1 = Math.min(h, (int) Math.ceil(r[3] * h));
        if (x1 <= x0 || y1 <= y0) return 0.1f;
        float cx = (x0 + x1) * 0.5f, cy = (y0 + y1) * 0.5f;
        float rx = Math.max(1f, (x1 - x0) * 0.5f), ry = Math.max(1f, (y1 - y0) * 0.5f);
        double sum = 0, wsum = 0;
        for (int y = y0; y < y1; y++) {
            int row = y * w * 4;
            float dy = (y + 0.5f - cy) / ry;
            for (int x = x0; x < x1; x++) {
                int o = row + x * 4;
                float lin = 0.2126f * toLinear[b.get(o) & 255] + 0.7152f * toLinear[b.get(o + 1) & 255]
                    + 0.0722f * toLinear[b.get(o + 2) & 255];
                float dx = (x + 0.5f - cx) / rx;
                float wt = Math.max(0.15f, 1f - 0.7f * (dx * dx + dy * dy));
                sum += wt * Math.log(lin + 0.001f);
                wsum += wt;
            }
        }
        return (float) Math.exp(sum / wsum);
    }

    // The world texture's mipmaps are made by ScreenPass for this frame already; copy one level into the next buffer
    private static void request(int worldTex, int frame, int player, float[] rect) {
        int prevActive = GlGet.integer(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        int prevTex = GlGet.integer(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, worldTex);
        int prevPack = GlGet.integer(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        try {
            ScreenPass.mipmaps(worldTex, frame);
            long now = System.nanoTime();
            if (worldTex != sizeTex || now - sizeCheckedNs > 2_000_000_000L) {
                sizeTex = worldTex;
                sizeCheckedNs = now;
                texW = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                texH = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            }
            if (texW <= 0 || texH <= 0) return;
            float visW = (rect[2] - rect[0]) * texW, visH = (rect[3] - rect[1]) * texH;
            int level = 0;
            while (Math.max(visW, visH) / (1 << (level + 1)) >= TARGET) level++;
            int w = Math.max(1, texW >> level), h = Math.max(1, texH >> level);
            int s = slot;
            if (pbo[s] == 0) pbo[s] = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[s]);
            if (pboW[s] != w || pboH[s] != h) {
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long) w * h * 4, GL15.GL_STREAM_READ);
                pboW[s] = w;
                pboH[s] = h;
            }
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, level, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            System.arraycopy(rect, 0, pboRect[s], 0, 4);
            pboPlayer[s] = player;
            pending[s] = true;
            slot = (s + 1) % RING;
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPack);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
            GL13.glActiveTexture(prevActive);
        }
    }

    static String stateText() {
        return "eyeReads=" + reads + ";eyeLum=" + String.format(java.util.Locale.ROOT, "%.3f", Math.exp(cur[0]))
            + ";eyeAdapted=" + String.format(java.util.Locale.ROOT, "%.3f", Math.exp(adapted[0]));
    }
}
