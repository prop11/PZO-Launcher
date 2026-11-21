package com.pzoptimizer.pzoe;

import com.pzoptimizer.PZOLogger;
import com.pzoptimizer.pzoe.gore.GoreCore;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoFloorBloodSplat;

/**
 * Ground Blood's map (PZOE Gore): blood on floors and walls of levels 0-3 as one RGBA8 texture tied to the world,
 * drawn by the chunk shader's post pass, so blood never needs a chunk bake. Same ring as TrailMap (player 0's chunk
 * map), in 12 bands stacked top to bottom: floors of levels 0-3, then per level the north and the west wall faces
 * (a square's face gets T texels along it and T up it). T texels a square from the card's quality (4, 6 or 8).
 *   R = blood (soft, the shader cuts the edge with noise), G = how fresh (1 wet red .. 0 dried brown),
 *   B = how thick (pools darker in the middle), A = not yet washed (rain and snow take it down outdoors).
 *
 * The game's floor splats stay the record: every frame each loaded chunk's splat list is checked for new entries at its
 * tail (one compare per chunk), and a chunk that comes into a slot, or whose blood was cleaned, is cleared and stamped
 * again from its list, with its neighbours' splats that reach over the edge. Each splat becomes a stretched blob plus a
 * few droplets, from a hash of the splat so a re-stamp looks the same. GoreFx adds sprays, pools, prints, smears and
 * wall hits through the stamp calls here. Drying and washing run on game time.
 */
public final class GoreMap {

    static final int LEVELS = GoreCore.LEVELS, BANDS = LEVELS * 3;
    private static final int MAX_STAMPS = 6144, FLOATS = 11, BATCH = 64;
    // Fresh blood is dark and matte after this many game hours; the stain itself stays
    static final double DRY_HOURS = 8.0;
    // Heavy rain washes outdoor blood away over this many game hours
    private static final double WASH_HOURS = 1.5;
    private static final float REACH = 1.7f;

    static volatile boolean wanted;
    private static volatile int quality = 2;
    private static volatile int maxTexSize;

    // Game thread
    private static final float[] stamps = new float[MAX_STAMPS * FLOATS];
    private static int stampCount;
    private static int[] clears = new int[256];
    private static int clearCount;
    private static int grid, ring, tps;
    private static long[] slotKey;
    private static Object[] slotTail;
    private static boolean[] restamp;
    private static boolean drawing, recreate, everything;
    private static int bloodOption = -1;
    private static double lastHours = -1, dryAcc, washAcc, washTotal, washedBefore = -1, lastRainHours = -1;
    private static long stampedTotal, reportedStamps, chunkStamps, reportedChunks;
    private static boolean warned;
    // Squares with fresh blood on them (key -> world hours it fell), for bloody footprints and tyres
    private static final Map<Long, Float> fresh = new HashMap<>();
    private static long lastPruneNs;

    // Render thread
    private static int texId, fboId, texW, texH, stampProg, fadeProg, vao;
    private static int locStamps, locSize, locFade;
    private static volatile int liveRingTexels, liveTps;
    private static final FloatBuffer stampBuf = ByteBuffer.allocateDirect(BATCH * 12 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    private GoreMap() {}

    static void configure(int q) {
        quality = Math.max(1, Math.min(3, q));
    }

    static boolean live() {
        return drawing;
    }

    static int texture() {
        return texId;
    }

    static int ringTexels() {
        return liveRingTexels;
    }

    static int texelsPerSquare() {
        return liveTps;
    }

    static String stateText() {
        return GoreCore.stateText() + ";goreTexels=" + liveTps + ";goreFresh=" + fresh.size();
    }

    private static long key(int wx, int wy) {
        return ((long) wx << 32) ^ (wy & 0xffffffffL);
    }

    private static long sqKey(int x, int y, int z) {
        return ((long) x << 36) ^ ((long) (y & 0xFFFFFFF) << 4) ^ (z & 0xF);
    }

    private static int texelsFor(int q, int g) {
        int t = q <= 1 ? 4 : q >= 3 ? 8 : 6;
        int max = maxTexSize > 0 ? maxTexSize : 4096;
        // Also kept under about 40 MB: at full view distance and 8 texels the 12 bands would be over 70 MB
        while (t > 2 && (g * 8 * t * BANDS > max || (long) (g * 8 * t) * (g * 8 * t) * BANDS * 4L > 40L * 1024 * 1024)) t--;
        return t;
    }

    static double hours() {
        zombie.GameTime gt = zombie.GameTime.getInstance();
        return gt == null ? 0 : gt.getWorldAgeHours();
    }

    /** How fresh the blood on a square is (0 none or dry .. 1 just fell). */
    static float freshAt(int x, int y, int z) {
        Float h = fresh.get(sqKey(x, y, z));
        if (h == null) return 0f;
        return (float) Math.max(0.0, 1.0 - (hours() - h) / (DRY_HOURS * 0.4));
    }

    static void markFresh(float x, float y, int z, double when) {
        long k = sqKey((int) Math.floor(x), (int) Math.floor(y), z);
        Float old = fresh.get(k);
        if (old == null || old < when) fresh.put(k, (float) when);
    }

    /** Once a frame on the game thread (PixelLight.globalFrame). */
    static void frame() {
        try {
            frameInner();
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                PZOLogger.warn("[PZOE gore] map frame failed: " + t);
            }
        }
    }

    private static void frameInner() {
        zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.currentCell;
        IsoChunkMap map = cell == null ? null : cell.getChunkMap(0);
        int opt = zombie.core.Core.getInstance().getOptionBloodDecals();
        // Split screen players see other ground than player 0's ring holds: they keep the game's own splats
        boolean draws = wanted && map != null && zombie.core.PerformanceSettings.fboRenderChunk
            && zombie.characters.IsoPlayer.numPlayers <= 1 && opt != 0;
        if (draws != drawing) {
            drawing = draws;
            GoreCore.mapDraws = draws;
            if (draws) everything = true;
            else GoreCore.dropCleaned();
            if (zombie.core.Core.debug) PZOLogger.info("[PZOE gore] ground blood map " + (draws ? "on" : "off") + ", re-baking loaded chunks");
            // The game's splat sprites are baked into the chunks: re-bake so they go (or come back)
            RebakeQueue.now(zombie.iso.fboRenderChunk.FBORenderChunk.DIRTY_REDRAW);
        }
        if (!draws) {
            stampCount = 0;
            clearCount = 0;
            return;
        }
        int g = IsoChunkMap.chunkGridWidth;
        int t = texelsFor(quality, g);
        if (g != grid || t != tps) {
            grid = g;
            ring = g * 8;
            tps = t;
            slotKey = new long[g * g];
            slotTail = new Object[g * g];
            restamp = new boolean[g * g];
            recreate = true;
            everything = true;
        }
        if (opt != bloodOption) {
            bloodOption = opt;
            everything = true;
        }
        if (everything) {
            everything = false;
            Arrays.fill(slotKey, Long.MIN_VALUE);
        }
        double hours = hours();
        float dry = 0f, wash = 0f;
        if (lastHours >= 0 && hours > lastHours) {
            double dh = Math.min(1.0, hours - lastHours);
            dryAcc += dh / DRY_HOURS;
            float rain = GoreFx.rainNow();
            if (rain > 0.05f) {
                washAcc += dh * rain / WASH_HOURS;
                washTotal += dh * rain / WASH_HOURS;
                lastRainHours = hours;
                if (washTotal >= 1.0) {
                    washedBefore = hours;
                    washTotal = 0;
                }
            } else if (lastRainHours >= 0 && hours - lastRainHours > 3.0) {
                washTotal = 0;
            }
        }
        lastHours = hours;
        if (dryAcc >= 2.0 / 255.0) {
            dry = (float) Math.min(1.0, dryAcc);
            dryAcc = 0;
        }
        if (washAcc >= 2.0 / 255.0) {
            wash = (float) Math.min(1.0, washAcc);
            washAcc = 0;
        }
        int[] c;
        while ((c = GoreCore.pollCleaned()) != null) {
            fresh.remove(sqKey(c[0], c[1], c[2]));
            for (int dy = -1; dy <= 1; dy += 2) {
                for (int dx = -1; dx <= 1; dx += 2) {
                    int wx = Math.floorDiv(c[0] + dx * 3, 8), wy = Math.floorDiv(c[1] + dy * 3, 8);
                    int slot = Math.floorMod(wy, g) * g + Math.floorMod(wx, g);
                    if (slotKey[slot] == key(wx, wy)) restamp[slot] = true;
                }
            }
        }
        for (int y = 0; y < g; y++) {
            for (int x = 0; x < g; x++) {
                IsoChunk ch = map.getChunk(x, y);
                if (ch == null) continue;
                int slot = Math.floorMod(ch.wy, g) * g + Math.floorMod(ch.wx, g);
                long k = key(ch.wx, ch.wy);
                if (slotKey[slot] != k) {
                    slotKey[slot] = k;
                    restamp[slot] = true;
                }
            }
        }
        for (int y = 0; y < g; y++) {
            for (int x = 0; x < g; x++) {
                IsoChunk ch = map.getChunk(x, y);
                if (ch == null) continue;
                int slot = Math.floorMod(ch.wy, g) * g + Math.floorMod(ch.wx, g);
                if (restamp[slot]) {
                    // A chunk's whole list goes in one frame or waits for the next, so a slot is never half drawn
                    int need = (ch.floorBloodSplats.size() + 8) * 4;
                    if (stampCount > 0 && stampCount + need > MAX_STAMPS) continue;
                    restamp[slot] = false;
                    addSlotClear(Math.floorMod(ch.wx, g), Math.floorMod(ch.wy, g));
                    stampAll(ch, hours, opt, null);
                    stampNeighbours(cell, ch, hours, opt);
                    slotTail[slot] = tail(ch);
                    chunkStamps++;
                } else {
                    stampNew(ch, slot, hours, opt);
                }
            }
        }
        long now = System.nanoTime();
        if (now - lastPruneNs > 5_000_000_000L) {
            lastPruneNs = now;
            for (Iterator<Float> it = fresh.values().iterator(); it.hasNext(); ) {
                if (hours - it.next() > DRY_HOURS * 0.4) it.remove();
            }
        }
        if (stampCount == 0 && clearCount == 0 && dry == 0f && wash == 0f && !recreate) return;
        zombie.core.SpriteRenderer.instance.drawGeneric(new Batch(ring * tps, tps, recreate, Arrays.copyOf(clears, clearCount), dry, wash,
            Arrays.copyOf(stamps, stampCount * FLOATS)));
        recreate = false;
        stampCount = 0;
        clearCount = 0;
    }

    static String counters() {
        String s = (stampedTotal - reportedStamps) + " stamps, " + (chunkStamps - reportedChunks) + " chunks re-stamped, " + tps + " texels a square";
        reportedStamps = stampedTotal;
        reportedChunks = chunkStamps;
        return s;
    }

    private static Object tail(IsoChunk ch) {
        int n = ch.floorBloodSplats.size();
        return n == 0 ? null : ch.floorBloodSplats.get(n - 1);
    }

    private static void stampNew(IsoChunk ch, int slot, double hours, int opt) {
        var q = ch.floorBloodSplats;
        int n = q.size();
        Object last = n == 0 ? null : q.get(n - 1);
        if (last == slotTail[slot]) return;
        Object seen = slotTail[slot];
        slotTail[slot] = last;
        for (int i = n - 1, done = 0; i >= 0 && done < 256; i--, done++) {
            IsoFloorBloodSplat b = q.get(i);
            if (b == seen) break;
            stampSplat(ch, b, hours, opt);
        }
    }

    private static void stampAll(IsoChunk ch, double hours, int opt, float[] within) {
        var q = ch.floorBloodSplats;
        for (int i = 0, n = q.size(); i < n; i++) {
            IsoFloorBloodSplat b = q.get(i);
            if (within != null) {
                float x = ch.wx * 8 + b.x, y = ch.wy * 8 + b.y;
                if (x < within[0] - REACH || x > within[2] + REACH || y < within[1] - REACH || y > within[3] + REACH) continue;
            }
            stampSplat(ch, b, hours, opt);
        }
    }

    private static void stampNeighbours(zombie.iso.IsoCell cell, IsoChunk ch, double hours, int opt) {
        float[] box = {ch.wx * 8, ch.wy * 8, ch.wx * 8 + 8, ch.wy * 8 + 8};
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                IsoChunk n = cell.getChunk(ch.wx + dx, ch.wy + dy);
                if (n != null && !n.floorBloodSplats.isEmpty()) stampAll(n, hours, opt, box);
            }
        }
    }

    private static void addSlotClear(int sx, int sy) {
        int block = 8 * tps, rt = ring * tps;
        for (int l = 0; l < BANDS; l++) {
            if (clearCount + 4 > clears.length) clears = Arrays.copyOf(clears, clears.length * 2);
            clears[clearCount++] = sx * block;
            clears[clearCount++] = sy * block + l * rt;
            clears[clearCount++] = block;
            clears[clearCount++] = block;
        }
    }

    private static int hash(int h) {
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return h;
    }

    private static float unit(int h) {
        return (h >>> 8) / 16777216f;
    }

    private static void stampSplat(IsoChunk ch, IsoFloorBloodSplat b, double hours, int opt) {
        int lvl = (int) Math.floor(b.z);
        if (lvl < 0 || lvl >= LEVELS || b.type < 0) return;
        if (b.index >= 1 && b.index <= 10 && opt >= 1 && opt <= IsoChunk.renderByIndex.length
            && IsoChunk.renderByIndex[opt - 1][b.index - 1] == 0) return;
        float x = ch.wx * 8 + b.x, y = ch.wy * 8 + b.y;
        float fr = (float) Math.max(0.0, Math.min(1.0, 1.0 - (hours - b.worldAge) / DRY_HOURS));
        if (fr > 0.4f) markFresh(x, y, lvl, b.worldAge);
        // Outdoor blood older than the last real downpour is already washed away
        float unwashed = 1f;
        if (washedBefore >= 0 && b.worldAge < washedBefore) {
            zombie.iso.IsoGridSquare sq = ch.getGridSquare((int) Math.floor(b.x), (int) Math.floor(b.y), lvl);
            if (sq != null && sq.isOutside()) unwashed = 0f;
        }
        int h = hash(Float.floatToIntBits(x) * 31 + Float.floatToIntBits(y) * 17 + b.type);
        float size, thick;
        int drops;
        if (b.type < 8) {
            size = 0.09f + 0.07f * unit(h);
            thick = 0.25f;
            drops = unit(hash(h + 1)) > 0.6f ? 1 : 0;
        } else if (b.type < 16) {
            size = 0.18f + 0.1f * unit(h);
            thick = 0.55f;
            drops = 2;
        } else {
            size = 0.28f + 0.14f * unit(h);
            thick = 0.85f;
            drops = 3;
        }
        double ang = unit(hash(h + 2)) * Math.PI * 2;
        float dx = (float) Math.cos(ang), dy = (float) Math.sin(ang);
        float stretch = 1f + 0.6f * unit(hash(h + 3));
        put(x, y, lvl, dx, dy, size * stretch, size, 1f, fr, thick, unwashed);
        for (int i = 0; i < drops; i++) {
            int hi = hash(h + 10 + i);
            double a = ang + (unit(hi) - 0.5) * 1.4;
            float ax = (float) Math.cos(a), ay = (float) Math.sin(a);
            float dist = size * stretch * (1.15f + 0.9f * unit(hash(hi + 1)));
            float r = 0.03f + 0.05f * unit(hash(hi + 2)) * (0.5f + size);
            put(x + ax * dist, y + ay * dist, lvl, ax, ay, r * 1.7f, r, 0.9f, fr, 0.1f, unwashed);
        }
    }

    // Stamps from GoreFx (game thread). Levels outside 0-3 are left to the game's own splats.

    /** A round-ish blob on the floor: a landed drop, a gib's mark, a pool (radius in squares). */
    static void blob(float x, float y, int lvl, float r, float freshness, float thick) {
        if (!drawing || lvl < 0 || lvl >= LEVELS) return;
        put(x, y, lvl, 1f, 0f, r, r, 1f, freshness, thick, 1f);
        if (freshness > 0.4f && r > 0.12f) markFresh(x, y, lvl, hours());
    }

    /** A streak along (dx, dy): footprints, drag smears, tyre lines, spray landing at speed. */
    static void streak(float x, float y, int lvl, float dx, float dy, float halfLen, float halfWid, float amount, float freshness) {
        if (!drawing || lvl < 0 || lvl >= LEVELS) return;
        put(x, y, lvl, dx, dy, halfLen, halfWid, amount, freshness, 0.15f, 1f);
    }

    /**
     * Blood on a wall face: face 0 = the north edge of square row 'row', 1 = the west edge of column 'row'; along =
     * world x (north) or y (west), h = height up the wall 0..1.
     */
    static void wall(float along, int row, float h, int lvl, int face, float r, float freshness) {
        if (!drawing || lvl < 0 || lvl >= LEVELS) return;
        put(along, row + Math.max(0.02f, Math.min(0.98f, h)), LEVELS + lvl * 2 + face, 0f, 1f, r * 1.3f, r, 1f, freshness, 0.3f, 1f);
    }

    private static void put(float x, float y, int band, float dx, float dy, float halfLen, float halfWid, float r, float g, float b, float a) {
        if (stampCount >= MAX_STAMPS) return;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-5f) {
            dx = 1f;
            dy = 0f;
        } else {
            dx /= len;
            dy /= len;
        }
        int o = stampCount * FLOATS;
        stamps[o] = x;
        stamps[o + 1] = y;
        stamps[o + 2] = band;
        stamps[o + 3] = dx;
        stamps[o + 4] = dy;
        stamps[o + 5] = halfLen;
        stamps[o + 6] = halfWid;
        stamps[o + 7] = r;
        stamps[o + 8] = g;
        stamps[o + 9] = b;
        stamps[o + 10] = a;
        stampCount++;
        stampedTotal++;
    }

    private static final class Batch extends TextureDraw.GenericDrawer {
        final int ringTexels, tps;
        final boolean recreate;
        final int[] clears;
        final float dry, wash;
        final float[] stamps;

        Batch(int ringTexels, int tps, boolean recreate, int[] clears, float dry, float wash, float[] stamps) {
            this.ringTexels = ringTexels;
            this.tps = tps;
            this.recreate = recreate;
            this.clears = clears;
            this.dry = dry;
            this.wash = wash;
            this.stamps = stamps;
        }

        @Override
        public void render() {
            try {
                draw(this);
            } catch (Throwable t) {
                PZOLogger.warn("[PZOE gore] " + t);
            }
        }
    }

    private static final String VERTEX_STAMP = "#version 140\n"
        + "uniform vec4 uStamps[" + (BATCH * 3) + "];\nuniform vec2 uSize;\nout vec2 vLocal;\nout vec4 vValue;\n"
        + "void main()\n{\n"
        + "    vec2 c[6] = vec2[6](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0));\n"
        + "    vec4 a = uStamps[gl_InstanceID * 3];\n"
        + "    vec2 v = uStamps[gl_InstanceID * 3 + 1].xy;\n"
        + "    vValue = uStamps[gl_InstanceID * 3 + 2];\n"
        + "    vLocal = c[gl_VertexID] * 1.25;\n"
        + "    vec2 p = a.xy + a.zw * vLocal.x + v * vLocal.y;\n"
        + "    gl_Position = vec4(p / uSize * 2.0 - 1.0, 0.0, 1.0);\n}\n";
    // A soft blob; A (unwashed) is the stamp's value wherever it covers, so new blood is never shown washed
    private static final String FRAGMENT_STAMP = "#version 140\nin vec2 vLocal;\nin vec4 vValue;\nout vec4 fragColor;\n"
        + "void main()\n{\n    float e = length(vLocal);\n"
        + "    float m = 1.0 - smoothstep(0.3, 1.2, e);\n"
        + "    float cover = smoothstep(0.0, 0.15, m);\n"
        + "    fragColor = vec4(vValue.r * m, vValue.g * cover, vValue.b * m, vValue.a * cover);\n}\n";
    private static final String VERTEX_FULL = "#version 130\nvoid main()\n{\n"
        + "    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n"
        + "    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n}\n";
    private static final String FRAGMENT_FADE = "#version 130\nuniform vec4 uFade;\nvoid main()\n{\n    gl_FragColor = uFade;\n}\n";

    private static int program(String vs, String fs) {
        int prog = GL20.glCreateProgram();
        for (int k = 0; k < 2; k++) {
            int s = GL20.glCreateShader(k == 0 ? GL20.GL_VERTEX_SHADER : GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(s, k == 0 ? vs : fs);
            GL20.glCompileShader(s);
            if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
                PZOLogger.warn("[PZOE gore] shader didn't compile: " + GL20.glGetShaderInfoLog(s, 2048).trim());
                GL20.glDeleteShader(s);
                GL20.glDeleteProgram(prog);
                return 0;
            }
            GL20.glAttachShader(prog, s);
            GL20.glDeleteShader(s);
        }
        GL20.glLinkProgram(prog);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == 0) {
            PZOLogger.warn("[PZOE gore] shader didn't link: " + GL20.glGetProgramInfoLog(prog, 2048).trim());
            GL20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    private static final int GL12_CLAMP_TO_EDGE = 0x812F;

    private static void draw(Batch b) {
        if (stampProg == 0) {
            maxTexSize = GlGet.integer(GL11.GL_MAX_TEXTURE_SIZE);
            stampProg = program(VERTEX_STAMP, FRAGMENT_STAMP);
            fadeProg = program(VERTEX_FULL, FRAGMENT_FADE);
            vao = GL30.glGenVertexArrays();
            if (stampProg != 0) {
                locStamps = GL20.glGetUniformLocation(stampProg, "uStamps");
                locSize = GL20.glGetUniformLocation(stampProg, "uSize");
            }
            if (fadeProg != 0) locFade = GL20.glGetUniformLocation(fadeProg, "uFade");
        }
        if (stampProg == 0 || fadeProg == 0) return;
        int rt = b.ringTexels, w = rt, h = rt * BANDS;
        int prevFbo = GlGet.integer(GL30.GL_FRAMEBUFFER_BINDING);
        int prevProgram = GlGet.integer(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GlGet.integer(GL30.GL_VERTEX_ARRAY_BINDING);
        GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_VIEWPORT_BIT
            | GL11.GL_SCISSOR_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        try {
            boolean fresh = false;
            if (texId == 0 || b.recreate || texW != w || texH != h) {
                if (texId != 0) GL11.glDeleteTextures(texId);
                if (fboId != 0) GL30.glDeleteFramebuffers(fboId);
                int prevActive = GlGet.integer(GL13.GL_ACTIVE_TEXTURE);
                GL13.glActiveTexture(GL13.GL_TEXTURE15);
                int prevTex = GlGet.integer(GL11.GL_TEXTURE_BINDING_2D);
                texId = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
                // Bands are stacked in y: the shader keeps its reads inside one band itself
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12_CLAMP_TO_EDGE);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
                GL13.glActiveTexture(prevActive);
                fboId = GL30.glGenFramebuffers();
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texId, 0);
                texW = w;
                texH = h;
                fresh = true;
            } else {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
            }
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glColorMask(true, true, true, true);
            GL11.glViewport(0, 0, w, h);
            GL11.glClearColor(0f, 0f, 0f, 0f);
            if (fresh) {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            } else if (b.clears.length > 0) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                for (int i = 0; i < b.clears.length; i += 4) {
                    GL11.glScissor(b.clears[i], b.clears[i + 1], b.clears[i + 2], b.clears[i + 3]);
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                }
            }
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL30.glBindVertexArray(vao);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
            if ((b.dry > 0f || b.wash > 0f) && !fresh) {
                // Drying takes G down, rain and snow take A down; the shader only applies A outdoors
                GL14.glBlendEquation(GL14.GL_FUNC_REVERSE_SUBTRACT);
                GL20.glUseProgram(fadeProg);
                GL11.glColorMask(false, b.dry > 0f, false, b.wash > 0f);
                GL20.glUniform4f(locFade, 0f, b.dry, 0f, b.wash);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                GL11.glColorMask(true, true, true, true);
            }
            if (b.stamps.length > 0 && locStamps >= 0) {
                GL14.glBlendEquation(GL14.GL_MAX);
                GL20.glUseProgram(stampProg);
                GL20.glUniform2f(locSize, w, h);
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                for (int l = 0; l < BANDS; l++) {
                    GL11.glScissor(0, l * rt, w, rt);
                    drawBand(b, l, rt);
                }
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
            GL14.glBlendEquation(GL14.GL_FUNC_ADD);
            liveRingTexels = rt;
            liveTps = b.tps;
        } finally {
            GL30.glBindVertexArray(prevVao);
            GL20.glUseProgram(prevProgram);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL11.glPopAttrib();
        }
    }

    // Stamps of one band, with a copy one ring over wherever a stamp reaches past the ring's edge (the map wraps)
    private static void drawBand(Batch b, int band, int rt) {
        float t = b.tps;
        int n = b.stamps.length / FLOATS, inBatch = 0;
        stampBuf.clear();
        for (int i = 0; i < n; i++) {
            int o = i * FLOATS;
            if ((int) b.stamps[o + 2] != band) continue;
            double fx = b.stamps[o] * t, fy = b.stamps[o + 1] * t;
            float cx = (float) (Math.floorMod((long) Math.floor(fx), (long) rt) + (fx - Math.floor(fx)));
            float cy = (float) (Math.floorMod((long) Math.floor(fy), (long) rt) + (fy - Math.floor(fy)));
            float dx = b.stamps[o + 3], dy = b.stamps[o + 4];
            float hl = b.stamps[o + 5] * t, hw = b.stamps[o + 6] * t;
            float value = b.stamps[o + 7];
            // Under a texel a droplet can't show: keep it a texel wide but fainter, the shader's cut then drops it
            if (hw < 0.75f) {
                value *= hw / 0.75f;
                hl = Math.max(hl, 0.75f);
                hw = 0.75f;
            }
            float reach = Math.max(hl, hw) * 1.25f + 1f;
            for (int sy = -1; sy <= 1; sy++) {
                float py = cy + sy * rt;
                if (py + reach < 0 || py - reach > rt) continue;
                for (int sx = -1; sx <= 1; sx++) {
                    float px = cx + sx * rt;
                    if (px + reach < 0 || px - reach > rt) continue;
                    stampBuf.put(px).put(py + band * rt).put(dx * hl).put(dy * hl);
                    stampBuf.put(-dy * hw).put(dx * hw).put(0f).put(0f);
                    stampBuf.put(value).put(b.stamps[o + 8]).put(b.stamps[o + 9]).put(b.stamps[o + 10]);
                    if (++inBatch == BATCH) {
                        flush(inBatch);
                        inBatch = 0;
                    }
                }
            }
        }
        if (inBatch > 0) flush(inBatch);
    }

    private static void flush(int count) {
        stampBuf.flip();
        GL20.glUniform4fv(locStamps, stampBuf);
        org.lwjgl.opengl.GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, 6, count);
        stampBuf.clear();
    }
}
