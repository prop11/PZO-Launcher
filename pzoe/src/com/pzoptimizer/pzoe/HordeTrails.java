package com.pzoptimizer.pzoe;

import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoWorld;

/**
 * Horde trails, for PZO Enhancements' Horde Trails (the chunk shader declares PZOWear): where the dead keep walking,
 * the ground wears into a path. Each square a zombie steps into gets a little wear; one walker leaves nothing you'd
 * see, a horde going through tramples grass flat into a dirt track and churns snow to grey slush, so you can tell
 * where they've been heading and follow or avoid it. Wear grows back over about three game days. Kept for the
 * session (not saved).
 *
 *   uniform sampler2D PZOWear;    one texel a square, wrapping with the loaded chunk map (REPEAT), R = wear 0..1
 *   uniform vec4 PZOWearInfo;     ring in squares, strength, 0, 1 = live
 *
 * Main thread, twice a second: zombies within 70 squares of player 0 are checked for a new square. The texture is
 * rebuilt from the wear map only when something changed, at most once a second, and uploaded on the render thread.
 */
final class HordeTrails {
    static final int UNIT = 17;
    private static final float STEP = 0.035f, REGROW_PER_HOUR = 0.0125f, RANGE_SQ = 70f * 70f;
    private static final int MAX_SQUARES = 60000;

    static volatile boolean wanted;
    static volatile float amount = 1f;

    // Main thread
    private static final java.util.HashMap<Long, float[]> wear = new java.util.HashMap<>();
    private static final java.util.WeakHashMap<IsoZombie, Long> lastSquare = new java.util.WeakHashMap<>();
    private static long lastSample, lastBuild;
    private static double lastHours = -1.0;
    private static boolean dirty;
    private static IsoCell cell;

    // Main thread builds, render thread uploads
    private static volatile byte[] built;
    private static volatile int builtRing, builtX, builtY;

    // Render thread
    private static int tex, texRing;
    private static byte[] uploaded;
    private static ByteBuffer buf;

    private HordeTrails() {}

    /** Main thread, once a frame (PixelLight.frame). */
    static void frame() {
        if (!wanted) {
            if (!wear.isEmpty()) {
                wear.clear();
                lastSquare.clear();
                built = null;
            }
            return;
        }
        IsoCell c = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (c != cell) {
            cell = c;
            wear.clear();
            lastSquare.clear();
            dirty = true;
        }
        IsoPlayer p = IsoPlayer.players[0];
        if (c == null || p == null) return;
        long now = System.currentTimeMillis();
        if (now - lastSample >= 500) {
            lastSample = now;
            sample(c, p);
            regrow();
        }
        if (dirty && now - lastBuild >= 1000) {
            lastBuild = now;
            dirty = false;
            build(c);
        }
    }

    private static void sample(IsoCell c, IsoPlayer p) {
        float px = p.getX(), py = p.getY();
        var zombies = c.getZombieList();
        for (int i = 0, n = zombies.size(); i < n; i++) {
            IsoZombie z = zombies.get(i);
            if (z == null || z.isDead() || z.getCurrentSquare() == null) continue;
            float dx = z.getX() - px, dy = z.getY() - py;
            if (dx * dx + dy * dy > RANGE_SQ) continue;
            var sq = z.getCurrentSquare();
            // Ground level only: upstairs floors don't wear into paths
            if (sq.getZ() != 0) continue;
            long key = ((long) sq.getX() << 32) ^ (sq.getY() & 0xFFFFFFFFL);
            Long was = lastSquare.put(z, key);
            if (was != null && was == key) continue;
            if (was == null) continue;
            float[] w = wear.get(key);
            if (w == null) {
                if (wear.size() >= MAX_SQUARES) continue;
                w = new float[1];
                wear.put(key, w);
            }
            float before = w[0];
            w[0] = Math.min(1f, w[0] + STEP);
            if (w[0] != before) dirty = true;
        }
    }

    private static void regrow() {
        double hours = zombie.GameTime.getInstance().getWorldAgeHours();
        if (lastHours < 0.0 || hours < lastHours) {
            lastHours = hours;
            return;
        }
        double dh = hours - lastHours;
        if (dh < 0.25) return;
        lastHours = hours;
        float k = (float) Math.min(dh, 72.0) * REGROW_PER_HOUR;
        var it = wear.values().iterator();
        while (it.hasNext()) {
            float[] w = it.next();
            w[0] -= k;
            if (w[0] <= 0.01f) it.remove();
        }
        dirty = true;
    }

    private static void build(IsoCell c) {
        IsoChunkMap map = c.getChunkMap(0);
        if (map == null) return;
        int ring = IsoChunkMap.chunkGridWidth * 8;
        int x0 = map.getWorldXMinTiles(), y0 = map.getWorldYMinTiles();
        byte[] out = new byte[ring * ring];
        boolean any = false;
        for (var e : wear.entrySet()) {
            long key = e.getKey();
            int x = (int) (key >> 32), y = (int) key;
            if (x < x0 || y < y0 || x >= x0 + ring || y >= y0 + ring) continue;
            int v = Math.round(Math.min(1f, e.getValue()[0]) * 255f);
            out[Math.floorMod(y, ring) * ring + Math.floorMod(x, ring)] = (byte) v;
            any |= v > 0;
        }
        builtRing = ring;
        builtX = x0;
        builtY = y0;
        built = any ? out : null;
    }

    /** Render thread, per chunk draw: binds the wear texture and sets PZOWearInfo. */
    static void bind(int locTex, int locInfo, boolean valid) {
        if (locInfo < 0) return;
        byte[] data = valid ? built : null;
        if (data == null) {
            GL20.glUniform4f(locInfo, 1f, 0f, 0f, 0f);
            return;
        }
        int ring = builtRing;
        if (tex == 0 || texRing != ring) {
            if (tex != 0) GL11.glDeleteTextures(tex);
            tex = GL11.glGenTextures();
            texRing = ring;
            uploaded = null;
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL30.GL_R8, ring, ring, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        if (data != uploaded) {
            if (buf == null || buf.capacity() < data.length) buf = BufferUtils.createByteBuffer(data.length);
            buf.clear();
            buf.put(data).flip();
            int align = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, ring, ring, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, buf);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, align);
            uploaded = data;
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        if (locTex >= 0) GL20.glUniform1i(locTex, UNIT);
        GL20.glUniform4f(locInfo, ring, amount, 0f, 1f);
    }

    static String stateText() {
        return "wearSquares=" + wear.size();
    }
}
