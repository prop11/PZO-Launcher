package com.pzoptimizer;

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
 *   B = water just stirred (wakes behind tyres).
 *
 * Marks are stamped as soft ovals on the game thread (a few a frame at most) and drawn into the texture on the render
 * thread with a MAX blend, so overlapping steps don't build up. Every half second one pass fades each channel at its
 * own rate (snow fills back in, faster while it snows; wet marks dry; wakes settle in a second or two). A chunk slot that
 * gets a new chunk is cleared. Player 0's chunk map decides the ring (split screen players beyond it see no marks).
 * Shaders read it at mod(world * 8, ring) with REPEAT wrap. Nothing runs until a shader declares PZOTrail.
 */
public final class TrailMap {

    static final int TEXELS_PER_SQUARE = 8;
    private static final int MAX_STAMPS = 64;

    // Set from the uniform lookups (chunk shader / puddle shader)
    static volatile boolean chunkWanted, puddleWanted;

    static boolean active() {
        return chunkWanted || puddleWanted;
    }

    // Game thread
    private static float[] stamps = new float[MAX_STAMPS * 9];
    private static int stampCount;
    private static int grid, ring;
    private static long[] slotKey;
    private static double fadeTimer;

    // Render thread
    private static int texId, fboId, texRing, stampProg, fadeProg, vao;
    private static int locCenter, locAxisU, locAxisV, locSize, locValue, locFade;

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
        if (!active() || stampCount >= MAX_STAMPS) return;
        float len = (float) Math.sqrt(dirX * dirX + dirY * dirY);
        if (len < 1e-5f) {
            dirX = 1f;
            dirY = 0f;
        } else {
            dirX /= len;
            dirY /= len;
        }
        int o = stampCount * 9;
        stamps[o] = x;
        stamps[o + 1] = y;
        stamps[o + 2] = dirX;
        stamps[o + 3] = dirY;
        stamps[o + 4] = halfLen;
        stamps[o + 5] = halfWid;
        stamps[o + 6] = r;
        stamps[o + 7] = g;
        stamps[o + 8] = b;
        stampCount++;
    }

    private static long key(int wx, int wy) {
        return ((long) wx << 32) ^ (wy & 0xffffffffL);
    }

    /** Once a frame on the game thread, after the stamps of that frame. */
    static void frame(double dt, boolean snowing) {
        if (!active()) {
            stampCount = 0;
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
        if (stampCount == 0 && clearCount == 0 && fade == null && !resized) return;
        float[] batch = java.util.Arrays.copyOf(stamps, stampCount * 9);
        zombie.core.SpriteRenderer.instance.drawGeneric(new Batch(ring, resized, java.util.Arrays.copyOf(clears, clearCount * 2), fade, batch));
        stampCount = 0;
    }

    private static final class Batch extends TextureDraw.GenericDrawer {
        final int ring;
        final boolean resized;
        final int[] clears;
        final float[] fade, stamps;

        Batch(int ring, boolean resized, int[] clears, float[] fade, float[] stamps) {
            this.ring = ring;
            this.resized = resized;
            this.clears = clears;
            this.fade = fade;
            this.stamps = stamps;
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

    private static final String VERTEX_STAMP = "#version 130\n"
        + "uniform vec2 uCenter;\nuniform vec2 uAxisU;\nuniform vec2 uAxisV;\nuniform vec2 uSize;\nout vec2 vLocal;\n"
        + "void main()\n{\n"
        + "    vec2 c[6] = vec2[6](vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0));\n"
        + "    vLocal = c[gl_VertexID];\n"
        + "    vec2 p = uCenter + uAxisU * vLocal.x + uAxisV * vLocal.y;\n"
        + "    gl_Position = vec4(p / uSize * 2.0 - 1.0, 0.0, 1.0);\n}\n";
    // Soft-edged oval
    private static final String FRAGMENT_STAMP = "#version 130\nuniform vec4 uValue;\nin vec2 vLocal;\n"
        + "void main()\n{\n    float e = dot(vLocal, vLocal);\n    gl_FragColor = uValue * (1.0 - smoothstep(0.55, 1.0, e));\n}\n";
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

    private static void draw(Batch b) {
        if (stampProg == 0) {
            stampProg = program(VERTEX_STAMP, FRAGMENT_STAMP);
            fadeProg = program(VERTEX_FULL, FRAGMENT_FADE);
            vao = GL30.glGenVertexArrays();
            if (stampProg != 0) {
                locCenter = GL20.glGetUniformLocation(stampProg, "uCenter");
                locAxisU = GL20.glGetUniformLocation(stampProg, "uAxisU");
                locAxisV = GL20.glGetUniformLocation(stampProg, "uAxisV");
                locSize = GL20.glGetUniformLocation(stampProg, "uSize");
                locValue = GL20.glGetUniformLocation(stampProg, "uValue");
            }
            if (fadeProg != 0) locFade = GL20.glGetUniformLocation(fadeProg, "uFade");
        }
        if (stampProg == 0 || fadeProg == 0) return;
        int size = b.ring * TEXELS_PER_SQUARE;
        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_VIEWPORT_BIT
            | GL11.GL_SCISSOR_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        try {
            boolean fresh = false;
            if (texId == 0 || texRing != b.ring || b.resized) {
                if (texId != 0) GL11.glDeleteTextures(texId);
                if (fboId != 0) GL30.glDeleteFramebuffers(fboId);
                int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
                GL13.glActiveTexture(GL13.GL_TEXTURE5);
                int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
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
            if (b.fade != null) {
                GL14.glBlendEquation(GL14.GL_FUNC_REVERSE_SUBTRACT);
                GL20.glUseProgram(fadeProg);
                GL20.glUniform4f(locFade, b.fade[0], b.fade[1], b.fade[2], 0f);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            }
            if (b.stamps.length > 0) {
                GL14.glBlendEquation(GL14.GL_MAX);
                GL20.glUseProgram(stampProg);
                GL20.glUniform2f(locSize, size, size);
                float perSquare = TEXELS_PER_SQUARE;
                for (int o = 0; o < b.stamps.length; o += 9) {
                    float cx = (float) (Math.floorMod((long) Math.floor(b.stamps[o] * perSquare), (long) size)
                        + (b.stamps[o] * perSquare - Math.floor(b.stamps[o] * perSquare)));
                    float cy = (float) (Math.floorMod((long) Math.floor(b.stamps[o + 1] * perSquare), (long) size)
                        + (b.stamps[o + 1] * perSquare - Math.floor(b.stamps[o + 1] * perSquare)));
                    float dx = b.stamps[o + 2], dy = b.stamps[o + 3];
                    float hl = b.stamps[o + 4] * perSquare, hw = b.stamps[o + 5] * perSquare;
                    GL20.glUniform2f(locCenter, cx, cy);
                    GL20.glUniform2f(locAxisU, dx * hl, dy * hl);
                    GL20.glUniform2f(locAxisV, -dy * hw, dx * hw);
                    GL20.glUniform4f(locValue, b.stamps[o + 6], b.stamps[o + 7], b.stamps[o + 8], 0f);
                    GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
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
