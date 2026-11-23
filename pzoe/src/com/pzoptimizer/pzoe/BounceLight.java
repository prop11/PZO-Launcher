package com.pzoptimizer.pzoe;

import com.pzoptimizer.PZOLogger;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;

/**
 * Bounce light (PZO Enhancements' Bounce Light card): one bounce of indirect light worked out with radiance cascades
 * (as in Path of Exile 2's 2D GI) on the world-pinned light map, levels 0-3, and handed to the chunk shader, which
 * adds it to the per-pixel light (uniform sampler2D PZOGI; vec4 PZOGIInfo = ring, 1 when ready, strength, 0).
 *
 * The scene, per level, is the floor plan seen from above: every square gives off the light that falls on it times
 * its floor's colour (a lamp on a red carpet lights the room red), and walls, closed edges and solid objects stop it;
 * see-through fences and trees let some through. The air takes a little of it per square (the card's Reach), so the
 * bounce stays local. Outdoors by day the sky lights everything and a bounce would only wash it out, so outdoor
 * squares give off light by night only.
 *
 * Cascades (cell space, wrapped by the loaded area like the light map, so moving needs no copy):
 *   C2: a probe every 4 squares, 32 directions, rays from 6 to 24 squares
 *   C1: a probe every 2 squares, 16 directions, 1.5 to 6, each ray carried on by the 2 matching C2 rays of the 4
 *       nearest C2 probes (bilinear)
 *   C0: 2 x 2 probes a square, 8 directions, 0 to 1.5, carried on by C1 the same way; the average of its 8 rays is
 *       the bounce there. The chunk shader reads it kept inside the pixel's own square, so it never leaks through a
 *       wall between two squares.
 * Each frame a quarter of every cascade's rows is redone in turn, and C0 is blended into what was there (its own
 * history in world space, nothing to reproject), so a moving torch's bounce follows smoothly.
 *
 * Floor colours: the mean colour of each floor texture, read back once (NaturalGround.measure, a few per frame),
 * kept per square in an RGBA8 texture (ring x ring per level), refreshed around player 0 a slice a frame.
 *
 * Player 0 only. Needs Per-Pixel Lighting (the bake is unlit, so the light map holds all the light).
 */
final class BounceLight {

    static final int LEVELS = 4, BANDS = 4;
    private static final int WINDOW = 48;

    // Render thread sets it from the chunk shader's uniform lookup
    static volatile boolean wanted;
    // Card strength 100 percent in raw bounce units
    static final float BASE = 0.35f;
    // Card reach 100 percent in raw reach units (user: 100 should sit where 40 was)
    static final float REACH_BASE = 0.4f;
    // From the card (Lua): strength 0..2 times BASE, reach 0..2
    static volatile float strength = BASE, reach = REACH_BASE;

    // Main thread
    private static final ConcurrentHashMap<Texture, float[]> albedoOf = new ConcurrentHashMap<>();
    private static final java.util.Set<Texture> queued = ConcurrentHashMap.newKeySet();
    private static final java.util.ArrayDeque<Texture> toMeasure = new java.util.ArrayDeque<>();
    private static byte[] albedo;
    private static int albedoRing, scanCursor;
    private static boolean albedoDirty;
    private static long lastAlbedoSend;

    // Render thread
    private static int fbo, vao, progC2, progC1, progC0;
    private static int texC2, texC1, texGI, texAlbedo, ring = -1, band;
    private static boolean ready, failed;
    private static final int[][] loc = new int[3][10];
    private static volatile long updates;

    private BounceLight() {}

    static int texture() {
        return texGI;
    }

    static int ringSize() {
        return ring;
    }

    static boolean ready() {
        return ready && !failed;
    }

    /** Main thread, once a frame (PixelLight.globalFrame). */
    static void frame(float dayLight) {
        if (!wanted || failed) return;
        zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.currentCell;
        zombie.characters.IsoPlayer pl = zombie.characters.IsoPlayer.players[0];
        int r = PixelLight.lightRing(0);
        if (cell == null || pl == null || r <= 0) return;
        if (albedo == null || albedoRing != r) {
            albedo = new byte[r * r * LEVELS * 4];
            albedoRing = r;
            scanCursor = 0;
        }
        scan(cell, pl, r);
        ByteBuffer up = null;
        long now = System.currentTimeMillis();
        if (albedoDirty && now - lastAlbedoSend > 250) {
            albedoDirty = false;
            lastAlbedoSend = now;
            up = ByteBuffer.allocateDirect(albedo.length);
            up.put(albedo).flip();
        }
        Texture[] measure = null;
        synchronized (toMeasure) {
            int n = Math.min(4, toMeasure.size());
            if (n > 0) {
                measure = new Texture[n];
                for (int i = 0; i < n; i++) measure[i] = toMeasure.poll();
            }
        }
        float night = Math.max(0f, Math.min(1f, 1f - dayLight * 1.5f));
        // Air absorption per square: raw reach 1 fades a bounce to a third in about 3 squares
        float sigma = 0.35f / Math.max(0.05f, reach);
        zombie.core.SpriteRenderer.instance.drawGeneric(new Step(r, up, measure, night, sigma));
    }

    // A slice of the window around player 0 each frame: floor colour per square and level
    private static void scan(zombie.iso.IsoCell cell, zombie.characters.IsoPlayer pl, int r) {
        int side = Math.min(2 * WINDOW, r), total = side * side * LEVELS;
        int x0 = (int) Math.floor(pl.getX()) - side / 2, y0 = (int) Math.floor(pl.getY()) - side / 2;
        // 512 a frame: the 96 x 96 x 4 window goes round in about 70 frames. Floors rarely change, and 2048 a frame was
        // about 3.9% of the main thread (JFR 2026-11-21).
        for (int k = 0; k < 512; k++) {
            int i = scanCursor;
            scanCursor = (scanCursor + 1) % total;
            int lvl = i / (side * side), rest = i % (side * side);
            int x = x0 + rest % side, y = y0 + rest / side;
            int o = ((lvl * r + Math.floorMod(y, r)) * r + Math.floorMod(x, r)) * 4;
            IsoGridSquare sq = cell.getGridSquare(x, y, lvl);
            byte cr = 0, cg = 0, cb = 0, ca = 0;
            if (sq != null) {
                IsoObject floor = sq.getFloor();
                Texture tex = floor == null || floor.sprite == null ? null : floor.sprite.getTextureForCurrentFrame(zombie.iso.IsoDirections.N);
                if (tex != null) {
                    float[] st = albedoOf.get(tex);
                    if (st == null && queued.add(tex)) {
                        synchronized (toMeasure) {
                            toMeasure.add(tex);
                        }
                    }
                    float ar = st == null ? 0.4f : st[0], ag = st == null ? 0.4f : st[1], ab = st == null ? 0.4f : st[2];
                    cr = (byte) Math.round(Math.min(1f, ar) * 255f);
                    cg = (byte) Math.round(Math.min(1f, ag) * 255f);
                    cb = (byte) Math.round(Math.min(1f, ab) * 255f);
                    ca = (byte) 255;
                } else if (lvl == 0) {
                    // Ground with no floor object (bare map ground): a dull grey
                    cr = cg = cb = (byte) 90;
                    ca = (byte) 255;
                }
            }
            if (albedo[o] != cr || albedo[o + 1] != cg || albedo[o + 2] != cb || albedo[o + 3] != ca) {
                albedo[o] = cr;
                albedo[o + 1] = cg;
                albedo[o + 2] = cb;
                albedo[o + 3] = ca;
                albedoDirty = true;
            }
        }
    }

    private static final class Step extends TextureDraw.GenericDrawer {
        final int r;
        final ByteBuffer albedo;
        final Texture[] measure;
        final float night, sigma;

        Step(int r, ByteBuffer albedo, Texture[] measure, float night, float sigma) {
            this.r = r;
            this.albedo = albedo;
            this.measure = measure;
            this.night = night;
            this.sigma = sigma;
        }

        @Override
        public void render() {
            try {
                if (measure != null) {
                    for (Texture t : measure) {
                        float[] st = null;
                        try {
                            st = NaturalGround.measure(t);
                        } catch (Throwable ignored) {
                        }
                        albedoOf.put(t, st != null ? st : new float[] {0.4f, 0.4f, 0.4f});
                    }
                }
                run(this);
            } catch (Throwable t) {
                failed = true;
                PZOLogger.warn("[BounceLight] off for this session: " + t);
            }
        }
    }

    private static void run(Step s) {
        int light = PixelLight.lightTexture(0), height = PixelLight.heightTexture(0);
        if (light == 0 || height == 0 || PixelLight.lightRing(0) != s.r) return;
        int prevFbo = GlGet.integer(GL30.GL_FRAMEBUFFER_BINDING);
        int prevProgram = GlGet.integer(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GlGet.integer(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActive = GlGet.integer(GL13.GL_ACTIVE_TEXTURE);
        int[] prevTex = new int[5];
        for (int u = 0; u < 5; u++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE8 + u);
            prevTex[u] = GlGet.integer(GL11.GL_TEXTURE_BINDING_2D);
        }
        GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_VIEWPORT_BIT
            | GL11.GL_SCISSOR_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        try {
            if (progC0 == 0 && !init()) {
                failed = true;
                return;
            }
            if (ring != s.r) allocate(s.r);
            if (s.albedo != null) {
                GL13.glActiveTexture(GL13.GL_TEXTURE8);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texAlbedo);
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, ring, ring * LEVELS, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, s.albedo);
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glColorMask(true, true, true, true);
            GL30.glBindVertexArray(vao);
            bind(GL13.GL_TEXTURE8, light);
            bind(GL13.GL_TEXTURE9, height);
            bind(GL13.GL_TEXTURE10, texAlbedo);
            bind(GL13.GL_TEXTURE11, texC2);
            bind(GL13.GL_TEXTURE12, texC1);
            int n2 = ring / 4, n1 = ring / 2;
            pass(0, progC2, texC2, n2 * 32, n2 * LEVELS, s);
            pass(1, progC1, texC1, n1 * 16, n1 * LEVELS, s);
            // C0 keeps a share of what it had: the bounce's own history, pinned to the world like the rest
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendColor(0f, 0f, 0f, ready ? 0.5f : 1f);
            GL11.glBlendFunc(GL14.GL_CONSTANT_ALPHA, GL14.GL_ONE_MINUS_CONSTANT_ALPHA);
            pass(2, progC0, texGI, ring * 2, ring * 2 * LEVELS, s);
            GL11.glDisable(GL11.GL_BLEND);
            band = (band + 1) % BANDS;
            if (band == 0) ready = true;
            updates++;
        } finally {
            GL30.glBindVertexArray(prevVao);
            GL20.glUseProgram(prevProgram);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL11.glPopAttrib();
            for (int u = 0; u < 5; u++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE8 + u);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex[u]);
            }
            GL13.glActiveTexture(prevActive);
        }
    }

    private static void bind(int unit, int tex) {
        GL13.glActiveTexture(unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
    }

    // One band of rows of one cascade
    private static void pass(int k, int prog, int target, int w, int h, Step s) {
        int rows = (h + BANDS - 1) / BANDS;
        int y0 = band * rows, hh = Math.min(rows, h - y0);
        if (!ready) {
            // The first time round every row, so the cascades above are whole before anything reads them
            y0 = 0;
            hh = h;
        }
        if (hh <= 0) return;
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, target, 0);
        GL11.glViewport(0, y0, w, hh);
        GL20.glUseProgram(prog);
        int[] l = loc[k];
        GL20.glUniform1i(l[0], 8);
        GL20.glUniform1i(l[1], 9);
        GL20.glUniform1i(l[2], 10);
        if (l[3] >= 0) GL20.glUniform1i(l[3], 11);
        if (l[4] >= 0) GL20.glUniform1i(l[4], 12);
        GL20.glUniform4f(l[5], ring, PixelLight.BASE_LEVEL, PixelLight.LEVELS, LEVELS);
        GL20.glUniform4f(l[6], s.night, s.sigma, 0f, 0f);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    private static void allocate(int r) {
        for (int t : new int[] {texC2, texC1, texGI, texAlbedo}) if (t != 0) GL11.glDeleteTextures(t);
        ring = r;
        texC2 = texture(r / 4 * 32, r / 4 * LEVELS, GL11.GL_NEAREST, GL30.GL_RGBA16F, GL11.GL_FLOAT);
        texC1 = texture(r / 2 * 16, r / 2 * LEVELS, GL11.GL_NEAREST, GL30.GL_RGBA16F, GL11.GL_FLOAT);
        texGI = texture(r * 2, r * 2 * LEVELS, GL11.GL_LINEAR, GL30.GL_RGBA16F, GL11.GL_FLOAT);
        texAlbedo = texture(r, r * LEVELS, GL11.GL_NEAREST, GL11.GL_RGBA8, GL11.GL_UNSIGNED_BYTE);
        ready = false;
        band = 0;
        // Everything the scan had goes up again into the new texture
        albedoDirty = true;
    }

    private static int texture(int w, int h, int filter, int internal, int type) {
        GL13.glActiveTexture(GL13.GL_TEXTURE8);
        int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, w, h, 0, GL11.GL_RGBA, type, (ByteBuffer) null);
        return t;
    }

    private static boolean init() {
        progC2 = SkyCache.compile(SkyCache.VERTEX, COMMON + C2, "bounce C2");
        progC1 = SkyCache.compile(SkyCache.VERTEX, COMMON + C1, "bounce C1");
        progC0 = SkyCache.compile(SkyCache.VERTEX, COMMON + C0, "bounce C0");
        if (progC2 == 0 || progC1 == 0 || progC0 == 0) return false;
        int[] progs = {progC2, progC1, progC0};
        String[] names = {"uLight", "uHeight", "uAlbedo", "uC2", "uC1", "uInfo", "uParams"};
        for (int k = 0; k < 3; k++) {
            for (int i = 0; i < names.length; i++) loc[k][i] = GL20.glGetUniformLocation(progs[k], names[i]);
        }
        fbo = GL30.glGenFramebuffers();
        vao = GL30.glGenVertexArrays();
        return true;
    }

    // uInfo = ring, light map base level, light map levels, GI levels; uParams = night 0..1, absorption per square.
    // Everything is in cell space: world squares mod ring, the same wrap the light and height maps use.
    static final String COMMON = """
        #version 330
        uniform sampler2D uLight;
        uniform sampler2D uHeight;
        uniform sampler2D uAlbedo;
        uniform sampler2D uC2;
        uniform sampler2D uC1;
        uniform vec4 uInfo;
        uniform vec4 uParams;
        out vec4 o;
        const float TAU = 6.2831853;

        vec2 cellOf(vec2 p)
        {
            return mod(floor(p), uInfo.x);
        }

        // Light falling on a square's floor (the mean of its 4 floor corners) times its floor colour; outdoors only
        // by night
        vec3 emit(vec2 c, float lvl)
        {
            float R = uInfo.x;
            vec4 a = texture(uAlbedo, (vec2(c.x, c.y + lvl * R) + 0.5) / vec2(R, R * uInfo.w));
            if (a.a < 0.5) return vec3(0.0);
            float lz = clamp(lvl - uInfo.y, 0.0, uInfo.z - 1.0);
            float tile = 2.0 * R;
            float sl = 2.0 * lz;
            vec4 L = texture(uLight, (vec2(mod(sl, 8.0), floor(sl / 8.0)) * tile + 2.0 * c + 1.0) / (vec2(8.0, 6.0) * tile));
            float outdoors = step(63.5, mod(floor(L.a * 255.0 + 0.5), 128.0));
            return L.rgb * a.rgb * mix(1.0, uParams.x, outdoors);
        }

        // Heights in levels (content, north wall, west wall) and opacity, low bits see-through flags (PixelLight.heights)
        vec4 heightSee(vec2 c, out vec2 see)
        {
            vec4 t = texture(uHeight, (c + 0.5) / uInfo.x);
            vec4 r = floor(t * 255.0 + 0.5);
            vec4 bits = mod(r, 2.0);
            see = bits.gb;
            r -= bits;
            return vec4(r.rgb / 32.0, r.a / 254.0);
        }

        // How much gets from cell a into the next cell b on this level. Walls are kept as the highest wall over any
        // level, so a wall counts here when it reaches above this level's middle. Whole-level content is a floor
        // slab above (not in the way); solid objects sit 0.7 up their level; trees let some through.
        float pass(vec2 a, vec2 b, float lvl)
        {
            float tr = 1.0;
            vec2 see;
            if (b.x != a.x) {
                vec4 h = heightSee(b.x > a.x || abs(b.x - a.x) > 1.5 ? b : a, see);
                if (h.b > lvl + 0.5) tr *= see.y > 0.5 ? 0.7 : 0.0;
            }
            if (b.y != a.y) {
                vec4 h = heightSee(b.y > a.y || abs(b.y - a.y) > 1.5 ? b : a, see);
                if (h.g > lvl + 0.5) tr *= see.x > 0.5 ? 0.7 : 0.0;
            }
            vec4 h = heightSee(b, see);
            float top = h.r - lvl;
            if (h.a > 0.9 && top > 0.5 && top < 0.95) tr *= 0.15;
            else if (h.a < 0.6 && top > 0.5) tr *= 0.7;
            return tr;
        }

        // Emission picked up along a ray from p over [t0, t1], one sample a square step, stopped by what's in the way.
        // rgb = light gathered, a = how much gets through to the far end.
        vec4 march(vec2 p, vec2 dir, float t0, float t1, float lvl)
        {
            vec3 rad = vec3(0.0);
            float T = 1.0;
            float stp = 0.7;
            vec2 prev = cellOf(p + dir * t0);
            float t = t0 + stp * 0.5;
            float fade = exp(-uParams.y * stp);
            for (int i = 0; i < 32; i++) {
                if (t > t1) break;
                vec2 c = cellOf(p + dir * t);
                if (c != prev) {
                    T *= pass(prev, c, lvl);
                    prev = c;
                    if (T < 0.01) break;
                }
                rad += T * emit(c, lvl) * stp;
                T *= fade;
                t += stp;
            }
            return vec4(rad, T);
        }

        // One cascade's ray (direction d of n) at probe (px, py) of spacing s, read from its texture of
        // probes x directions per row, levels stacked
        vec4 ray(sampler2D tex, vec2 pr, float d, float n, float np, float lvl)
        {
            pr = mod(pr, np);
            return texelFetch(tex, ivec2(int(pr.x * n + d), int(pr.y + lvl * np)), 0);
        }

        // The upper cascade carried on from point p (cell space) in directions 2d and 2d+1, bilinear over its 4
        // nearest probes (spacing s, centres at s * i + s / 2)
        vec4 upper(sampler2D tex, vec2 p, float d, float n, float s, float lvl)
        {
            float np = uInfo.x / s;
            vec2 g = p / s - 0.5;
            vec2 i = floor(g);
            vec2 f = g - i;
            vec4 acc = vec4(0.0);
            for (int k = 0; k < 4; k++) {
                vec2 o = vec2(float(k & 1), float(k >> 1));
                float w = (o.x > 0.5 ? f.x : 1.0 - f.x) * (o.y > 0.5 ? f.y : 1.0 - f.y);
                acc += w * 0.5 * (ray(tex, i + o, 2.0 * d, n, np, lvl) + ray(tex, i + o, 2.0 * d + 1.0, n, np, lvl));
            }
            return acc;
        }
        """;

    static final String C2 = """
        void main()
        {
            vec2 fc = floor(gl_FragCoord.xy);
            float np = uInfo.x / 4.0;
            float lvl = floor(fc.y / np);
            vec2 pr = vec2(floor(fc.x / 32.0), fc.y - lvl * np);
            float d = fc.x - pr.x * 32.0;
            float a = (d + 0.5) / 32.0 * TAU;
            o = march(pr * 4.0 + 2.0, vec2(cos(a), sin(a)), 6.0, 24.0, lvl);
        }
        """;

    static final String C1 = """
        void main()
        {
            vec2 fc = floor(gl_FragCoord.xy);
            float np = uInfo.x / 2.0;
            float lvl = floor(fc.y / np);
            vec2 pr = vec2(floor(fc.x / 16.0), fc.y - lvl * np);
            float d = fc.x - pr.x * 16.0;
            float a = (d + 0.5) / 16.0 * TAU;
            vec2 p = pr * 2.0 + 1.0;
            vec4 near = march(p, vec2(cos(a), sin(a)), 1.5, 6.0, lvl);
            vec4 far = upper(uC2, p, d, 32.0, 4.0, lvl);
            o = vec4(near.rgb + near.a * far.rgb, near.a * far.a);
        }
        """;

    static final String C0 = """
        void main()
        {
            vec2 fc = floor(gl_FragCoord.xy);
            float np = uInfo.x * 2.0;
            float lvl = floor(fc.y / np);
            vec2 p = (vec2(fc.x, fc.y - lvl * np) + 0.5) * 0.5;
            vec3 sum = vec3(0.0);
            // A square with no floor (open air above the ground) needs no bounce
            vec4 a = texture(uAlbedo, (vec2(floor(p.x), floor(p.y) + lvl * uInfo.x) + 0.5) / vec2(uInfo.x, uInfo.x * uInfo.w));
            if (a.a > 0.5) {
                for (int i = 0; i < 8; i++) {
                    float ang = (float(i) + 0.5) / 8.0 * TAU;
                    vec4 near = march(p, vec2(cos(ang), sin(ang)), 0.0, 1.5, lvl);
                    vec4 far = upper(uC1, p, float(i), 16.0, 2.0, lvl);
                    sum += near.rgb + near.a * far.rgb;
                }
            }
            o = vec4(sum / 8.0, 1.0);
        }
        """;

    static String stateText() {
        return "giWanted=" + (wanted ? 1 : 0) + ";giReady=" + (ready ? 1 : 0) + ";giRing=" + ring + ";giUpdates=" + updates
            + ";giFloors=" + albedoOf.size() + ";giFailed=" + (failed ? 1 : 0);
    }
}
