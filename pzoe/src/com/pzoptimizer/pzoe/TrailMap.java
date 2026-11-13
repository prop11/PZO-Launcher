package com.pzoptimizer.pzoe;

import com.pzoptimizer.PZOLogger;

import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;

/**
 * The ground trail map: marks left on the ground, for shader patches that ask for it (PZO Enhancements' Footprints
 * and Wet Tracks, Vehicle Splashes and Wakes). One RGBA8 texture tied to the world, 8 texels a square, wrapping with
 * the loaded chunk map like the light map:
 *   R = snow pressed down (footprints, tyre ruts), G = wet marks (wet shoe prints, tyre lines on wet ground),
 *   B = water just stirred (wakes behind tyres), A = grass pressed down (bodies and vehicles going through it).
 *
 * Marks are stamped as soft ovals on the game thread (a few a frame at most) and drawn into the texture on the render
 * thread with a MAX blend, so overlapping steps don't build up. Every half second one pass fades each channel at its
 * own rate (snow fills back in, faster while it snows; wet marks dry; wakes settle in a second or two). A chunk slot that
 * gets a new chunk is cleared. Player 0's chunk map decides the ring (split screen players beyond it see no marks).
 * Shaders read it at mod(world * 8, ring) with REPEAT wrap. Nothing runs until a shader declares PZOTrail.
 *
 * Grass (A) is held at full while something stands in it and springs back over about a second after it leaves, so it
 * fades every frame, but only in a box around player 0 (grass is only stamped inside it) and only for a while after
 * the last grass stamp. Its stamps are cones, not flat ovals, so the composite gets a slope to bend away from.
 * All stamps of a frame go up in one instanced draw.
 */
public final class TrailMap {

    static final int TEXELS_PER_SQUARE = 8;
    private static final int MAX_STAMPS = 128, MAX_GRASS_STAMPS = 96, FLOATS = 10, BATCH = 64;
    // Seconds grass takes to spring back once nothing presses it any more
    static final float GRASS_LIFE = 1.0f;
    // Grass is only stamped this close to player 0, and fades in a box a little bigger than that
    static final float GRASS_RANGE = 30f;
    private static final int GRASS_BOX = 34;

    // Set from the uniform lookups (chunk shader / puddle shader)
    static volatile boolean chunkWanted, puddleWanted, grassWanted;

    static boolean active() {
        return chunkWanted || puddleWanted || grassWanted;
    }

    // Game thread
    private static float[] stamps = new float[MAX_STAMPS * FLOATS];
    private static int stampCount, grassCount;
    private static double grassFadeAcc, sinceGrass = 1e9;
    private static int grid, ring;
    private static long[] slotKey;
    private static double fadeTimer;

    // Render thread
    private static int texId, fboId, texRing, stampProg, fadeProg, vao;
    private static int locStamps, locSize, locFade;
    private static final java.nio.FloatBuffer stampBuf = ByteBuffer.allocateDirect(BATCH * 12 * 4)
        .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();

    private TrailMap() {}

    /** Ring size in texels, or 0 before the first frame. */
    static int ringTexels() {
        return texRing * TEXELS_PER_SQUARE;
    }

    static int texture() {
        return texId;
    }

    /**
     * One soft oval at world square (x, y), long axis along (dirX, dirY) (normalised here), half sizes in squares;
     * r/g/b are the values written (the MAX of what's there and this). Game thread.
     */
    static void stamp(float x, float y, float dirX, float dirY, float halfLen, float halfWid, float r, float g, float b) {
        put(x, y, dirX, dirY, halfLen, halfWid, r, g, b, 0f);
    }

    /** Grass pressed down under an oval body footprint (a cone: full in the middle, nothing at the rim). Game thread. */
    static void stampGrass(float x, float y, float dirX, float dirY, float halfLen, float halfWid) {
        if (!grassWanted || grassCount >= MAX_GRASS_STAMPS) return;
        if (put(x, y, dirX, dirY, halfLen, halfWid, 0f, 0f, 0f, 1f)) {
            grassCount++;
            sinceGrass = 0;
        }
    }

    private static boolean put(float x, float y, float dirX, float dirY, float halfLen, float halfWid, float r, float g, float b, float a) {
        if (!active() || stampCount >= MAX_STAMPS) return false;
        float len = (float) Math.sqrt(dirX * dirX + dirY * dirY);
        if (len < 1e-5f) {
            dirX = 1f;
            dirY = 0f;
        } else {
            dirX /= len;
            dirY /= len;
        }
        int o = stampCount * FLOATS;
        stamps[o] = x;
        stamps[o + 1] = y;
        stamps[o + 2] = dirX;
        stamps[o + 3] = dirY;
        stamps[o + 4] = halfLen;
        stamps[o + 5] = halfWid;
        stamps[o + 6] = r;
        stamps[o + 7] = g;
        stamps[o + 8] = b;
        stamps[o + 9] = a;
        stampCount++;
        return true;
    }

    private static long key(int wx, int wy) {
        return ((long) wx << 32) ^ (wy & 0xffffffffL);
    }

    /** Once a frame on the game thread, after the stamps of that frame. */
    static void frame(double dt, boolean snowing) {
        if (!active()) {
            stampCount = 0;
            grassCount = 0;
            return;
        }
        zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.currentCell;
        IsoChunkMap map = cell == null ? null : cell.getChunkMap(0);
        if (map == null) return;
        int g = IsoChunkMap.chunkGridWidth;
        boolean resized = false;
        if (g != grid) {
            grid = g;
            ring = g * 8;
            slotKey = new long[g * g];
            java.util.Arrays.fill(slotKey, Long.MIN_VALUE);
            resized = true;
        }
        // Slots whose chunk changed get cleared, so old marks don't show up on newly loaded ground
        int[] clears = new int[g * g * 2];
        int clearCount = 0;
        if (!resized) {
            for (int y = 0; y < g; y++) {
                for (int x = 0; x < g; x++) {
                    IsoChunk c = map.getChunk(x, y);
                    if (c == null) continue;
                    int slot = Math.floorMod(c.wy, g) * g + Math.floorMod(c.wx, g);
                    long k = key(c.wx, c.wy);
                    if (slotKey[slot] != k) {
                        slotKey[slot] = k;
                        clears[clearCount * 2] = Math.floorMod(c.wx, g);
                        clears[clearCount * 2 + 1] = Math.floorMod(c.wy, g);
                        clearCount++;
                    }
                }
            }
        }
        float[] fade = null;
        fadeTimer += dt;
        if (fadeTimer >= 0.5) {
            // Per half second: snow fills a print in about 2 minutes (25 s while snowing), wet marks dry in about a
            // minute, wakes settle in about 2 seconds
            fade = new float[] {snowing ? 0.02f : 0.004f, 0.008f, 0.25f};
            fadeTimer = 0;
        }
        // Grass springs back: A loses dt / GRASS_LIFE a frame, sent once it's worth 2 steps of the 8-bit channel so very
        // high frame rates don't round it away. Nothing to fade once the last grass stamp has had time to die out.
        float grassFade = 0f;
        int boxX = 0, boxY = 0;
        sinceGrass += dt;
        if (grassWanted && sinceGrass < GRASS_LIFE * 1.5) {
            grassFadeAcc += dt / GRASS_LIFE;
            zombie.characters.IsoPlayer p0 = zombie.characters.IsoPlayer.players[0];
            if (grassFadeAcc >= 2.0 / 255.0 && p0 != null) {
                grassFade = (float) grassFadeAcc;
                grassFadeAcc = 0;
                boxX = (int) Math.floor(p0.getX());
                boxY = (int) Math.floor(p0.getY());
            }
        } else {
            grassFadeAcc = 0;
        }
        if (stampCount == 0 && clearCount == 0 && fade == null && !resized && grassFade == 0f) return;
        float[] batch = java.util.Arrays.copyOf(stamps, stampCount * FLOATS);
        zombie.core.SpriteRenderer.instance.drawGeneric(new Batch(ring, resized, java.util.Arrays.copyOf(clears, clearCount * 2), fade, batch,
            grassFade, boxX, boxY));
        stampCount = 0;
        grassCount = 0;
    }

    /** Within GRASS_RANGE of player 0: grass stamped further out would sit outside the fade box and never spring back. */
    static boolean inGrassRange(float x, float y) {
        zombie.characters.IsoPlayer p0 = zombie.characters.IsoPlayer.players[0];
        if (p0 == null) return false;
        float dx = x - p0.getX(), dy = y - p0.getY();
        return dx * dx + dy * dy < GRASS_RANGE * GRASS_RANGE;
    }

    private static final class Batch extends TextureDraw.GenericDrawer {
        final int ring;
        final boolean resized;
        final int[] clears;
        final float[] fade, stamps;
        final float grassFade;
        final int boxX, boxY;

        Batch(int ring, boolean resized, int[] clears, float[] fade, float[] stamps, float grassFade, int boxX, int boxY) {
            this.ring = ring;
            this.resized = resized;
            this.clears = clears;
            this.fade = fade;
            this.stamps = stamps;
            this.grassFade = grassFade;
            this.boxX = boxX;
            this.boxY = boxY;
        }

        @Override
        public void render() {
            try {
                draw(this);
            } catch (Throwable t) {
                PZOLogger.warn("[TrailMap] " + t);
            }
        }
    }

    // One instance per stamp: centre and long axis, short axis, value (3 vec4 each)
    private static final String VERTEX_STAMP = "#version 140\n"
        + "uniform vec4 uStamps[" + (BATCH * 3) + "];\nuniform vec2 uSize;\nout vec2 vLocal;\nout vec4 vValue;\n"
        + "void main()\n{\n"
        + "    vec2 c[6] = vec2[6](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0));\n"
        + "    vLocal = c[gl_VertexID];\n"
        + "    vec4 a = uStamps[gl_InstanceID * 3];\n"
        + "    vec2 v = uStamps[gl_InstanceID * 3 + 1].xy;\n"
        + "    vValue = uStamps[gl_InstanceID * 3 + 2];\n"
        + "    vec2 p = a.xy + a.zw * vLocal.x + v * vLocal.y;\n"
        + "    gl_Position = vec4(p / uSize * 2.0 - 1.0, 0.0, 1.0);\n}\n";
    // Soft-edged oval for the marks, a cone for grass (A) so it has a slope everywhere
    private static final String FRAGMENT_STAMP = "#version 140\nin vec2 vLocal;\nin vec4 vValue;\nout vec4 fragColor;\n"
        + "void main()\n{\n    float e = dot(vLocal, vLocal);\n"
        + "    fragColor = vec4(vValue.rgb * (1.0 - smoothstep(0.55, 1.0, e)), vValue.a * clamp(1.0 - sqrt(e), 0.0, 1.0));\n}\n";
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
                PZOLogger.warn("[TrailMap] shader didn't compile: " + GL20.glGetShaderInfoLog(s, 2048).trim());
                GL20.glDeleteShader(s);
                GL20.glDeleteProgram(prog);
                return 0;
            }
            GL20.glAttachShader(prog, s);
            GL20.glDeleteShader(s);
        }
        GL20.glLinkProgram(prog);
        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == 0) {
            PZOLogger.warn("[TrailMap] shader didn't link: " + GL20.glGetProgramInfoLog(prog, 2048).trim());
            GL20.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    // The grass box around player 0, split where it wraps over the texture's edge. Only A is written.
    private static void fadeGrass(Batch b, int size) {
        GL20.glUniform4f(locFade, 0f, 0f, 0f, b.grassFade);
        GL11.glColorMask(false, false, false, true);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        int span = Math.min(size, 2 * GRASS_BOX * TEXELS_PER_SQUARE);
        int x0 = Math.floorMod((b.boxX - GRASS_BOX) * TEXELS_PER_SQUARE, size), y0 = Math.floorMod((b.boxY - GRASS_BOX) * TEXELS_PER_SQUARE, size);
        int wA = Math.min(span, size - x0), hA = Math.min(span, size - y0);
        for (int j = 0; j < 2; j++) {
            int y = j == 0 ? y0 : 0, h = j == 0 ? hA : span - hA;
            if (h <= 0) continue;
            for (int i = 0; i < 2; i++) {
                int x = i == 0 ? x0 : 0, w = i == 0 ? wA : span - wA;
                if (w <= 0) continue;
                GL11.glScissor(x, y, w, h);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            }
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glColorMask(true, true, true, true);
    }

    private static void draw(Batch b) {
        if (stampProg == 0) {
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
        int size = b.ring * TEXELS_PER_SQUARE;
        int prevFbo = GlGet.integer(GL30.GL_FRAMEBUFFER_BINDING);
        int prevProgram = GlGet.integer(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GlGet.integer(GL30.GL_VERTEX_ARRAY_BINDING);
        GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_VIEWPORT_BIT
            | GL11.GL_SCISSOR_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        try {
            boolean fresh = false;
            if (texId == 0 || texRing != b.ring || b.resized) {
                if (texId != 0) GL11.glDeleteTextures(texId);
                if (fboId != 0) GL30.glDeleteFramebuffers(fboId);
                int prevActive = GlGet.integer(GL13.GL_ACTIVE_TEXTURE);
                GL13.glActiveTexture(GL13.GL_TEXTURE5);
                int prevTex = GlGet.integer(GL11.GL_TEXTURE_BINDING_2D);
                texId = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, size, size, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
                GL13.glActiveTexture(prevActive);
                fboId = GL30.glGenFramebuffers();
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texId, 0);
                texRing = b.ring;
                fresh = true;
            } else {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
            }
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glColorMask(true, true, true, true);
            GL11.glViewport(0, 0, size, size);
            GL11.glClearColor(0f, 0f, 0f, 0f);
            if (fresh) {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            } else if (b.clears.length > 0) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
                int block = 8 * TEXELS_PER_SQUARE;
                for (int i = 0; i < b.clears.length; i += 2) {
                    GL11.glScissor(b.clears[i] * block, b.clears[i + 1] * block, block, block);
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
                }
            }
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL30.glBindVertexArray(vao);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
            if (b.fade != null || b.grassFade > 0f) {
                GL14.glBlendEquation(GL14.GL_FUNC_REVERSE_SUBTRACT);
                GL20.glUseProgram(fadeProg);
                if (b.fade != null) {
                    GL20.glUniform4f(locFade, b.fade[0], b.fade[1], b.fade[2], 0f);
                    GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                }
                if (b.grassFade > 0f) fadeGrass(b, size);
            }
            if (b.stamps.length > 0 && locStamps >= 0) {
                GL14.glBlendEquation(GL14.GL_MAX);
                GL20.glUseProgram(stampProg);
                GL20.glUniform2f(locSize, size, size);
                float perSquare = TEXELS_PER_SQUARE;
                int n = b.stamps.length / FLOATS;
                for (int first = 0; first < n; first += BATCH) {
                    int count = Math.min(BATCH, n - first);
                    stampBuf.clear();
                    for (int i = first; i < first + count; i++) {
                        int o = i * FLOATS;
                        double fx = b.stamps[o] * perSquare, fy = b.stamps[o + 1] * perSquare;
                        float cx = (float) (Math.floorMod((long) Math.floor(fx), (long) size) + (fx - Math.floor(fx)));
                        float cy = (float) (Math.floorMod((long) Math.floor(fy), (long) size) + (fy - Math.floor(fy)));
                        float dx = b.stamps[o + 2], dy = b.stamps[o + 3];
                        float hl = b.stamps[o + 4] * perSquare, hw = b.stamps[o + 5] * perSquare;
                        stampBuf.put(cx).put(cy).put(dx * hl).put(dy * hl);
                        stampBuf.put(-dy * hw).put(dx * hw).put(0f).put(0f);
                        stampBuf.put(b.stamps[o + 6]).put(b.stamps[o + 7]).put(b.stamps[o + 8]).put(b.stamps[o + 9]);
                    }
                    stampBuf.flip();
                    GL20.glUniform4fv(locStamps, stampBuf);
                    org.lwjgl.opengl.GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, 6, count);
                }
            }
            GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        } finally {
            GL30.glBindVertexArray(prevVao);
            GL20.glUseProgram(prevProgram);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL11.glPopAttrib();
        }
    }
}
