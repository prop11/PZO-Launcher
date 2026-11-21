package com.pzoptimizer.pzoe;

import com.pzoptimizer.PZOLogger;
import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * The history buffer, and PZO Enhancements' Temporal Smoothing card on it.
 *
 * Each frame, before the final screen pass reads the world image, last frame's image is shifted by exactly how far the
 * camera moved and blended in, clamped to the colour range of each pixel's own neighbours (so edges and anything that
 * really changed don't smear). That calms shimmer from rain streaks, fog noise, swaying sprites and sub-pixel camera
 * moves. In this view the camera only ever slides (zoom changes in steps), so moving the history is one offset for the
 * whole image; a zoom change or a jump starts it over. The result goes back into the world image and is kept as next
 * frame's history (two textures, ping-pong), so every later effect (bloom, eye adaptation, grading) works on it.
 *
 * The camera's move comes from ScreenPass.main: one fixed world point projected with each frame's camera, in world
 * texture pixels. Player 0's view only. Render thread, from ScreenPass.render, all GL state put back.
 */
final class TemporalPass {

    // From the card (Lua)
    static volatile boolean on;
    static volatile float blend = 0.5f;
    // Puddle scene reflections read the history: kept (unblended, the world image left alone) while they ask
    static volatile long historyAskedNs;
    // Where last frame's history sits: ref world x, y, its uv, then du/dx, du/dy, du/dz, dv/dx, dv/dy, dv/dz
    private static volatile float[] proj;

    static boolean historyAsked() {
        return System.nanoTime() - historyAskedNs < 1_000_000_000L;
    }

    /** Render thread: the history's camera, for placing world points in it; null until it has run. */
    static float[] projection() {
        return valid ? proj : null;
    }

    private static int fbo, readFbo, vao, prog, locCur, locHist, locInfo, locDelta;
    private static final int[] hist = new int[2];
    private static int histW, histH, write, sizeTex = -1, texW, texH;
    private static long sizeCheckedNs;
    private static boolean valid, failed;
    private static float lastX, lastY, lastZoom, lastRefX = Float.NaN, lastRefY;
    private static int lastFrame = Integer.MIN_VALUE;
    static volatile long runs, resets;

    private TemporalPass() {}

    /**
     * worldTex = this frame's world image; ref = reference point (world x, y) and where it lands in the world texture
     * this frame (pixels, v up); zoom to see zoom changes.
     */
    static void render(int worldTex, int frame, float[] ref, float zoom) {
        boolean smooth = on;
        if (!(smooth || historyAsked()) || failed || worldTex == 0 || ref == null || frame == lastFrame) return;
        lastFrame = frame;
        int prevDraw = GlGet.integer(GL30.GL_DRAW_FRAMEBUFFER_BINDING), prevRead = GlGet.integer(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevProgram = GlGet.integer(GL20.GL_CURRENT_PROGRAM);
        int prevVao = GlGet.integer(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActive = GlGet.integer(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE8);
        int prev8 = GlGet.integer(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE9);
        int prev9 = GlGet.integer(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_VIEWPORT_BIT
            | GL11.GL_SCISSOR_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        try {
            if (prog == 0 && !init()) {
                failed = true;
                return;
            }
            long now = System.nanoTime();
            if (worldTex != sizeTex || now - sizeCheckedNs > 2_000_000_000L) {
                sizeTex = worldTex;
                sizeCheckedNs = now;
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, worldTex);
                texW = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                texH = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            }
            if (texW <= 0 || texH <= 0) return;
            if (histW != texW || histH != texH) allocate(texW, texH);
            // Same reference point and zoom as last frame: the history moves by the difference; otherwise start over
            float dx = 0f, dy = 0f;
            boolean keep = valid && ref[0] == lastRefX && ref[1] == lastRefY && zoom == lastZoom;
            if (keep) {
                dx = ref[2] - lastX;
                dy = ref[3] - lastY;
                // A teleport or a cut: more than a fifth of the view in one frame
                if (Math.abs(dx) > texW * 0.2f || Math.abs(dy) > texH * 0.2f) keep = false;
            }
            if (!keep) resets++;
            lastRefX = ref[0];
            lastRefY = ref[1];
            lastX = ref[2];
            lastY = ref[3];
            lastZoom = zoom;

            int read = 1 - write;
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, hist[write], 0);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glColorMask(true, true, true, true);
            GL11.glViewport(0, 0, texW, texH);
            GL30.glBindVertexArray(vao);
            GL20.glUseProgram(prog);
            GL13.glActiveTexture(GL13.GL_TEXTURE8);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, worldTex);
            GL13.glActiveTexture(GL13.GL_TEXTURE9);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, hist[read]);
            GL20.glUniform1i(locCur, 8);
            GL20.glUniform1i(locHist, 9);
            GL20.glUniform4f(locInfo, texW, texH, keep && smooth ? Math.max(0f, Math.min(0.9f, blend)) : 0f, 0f);
            GL20.glUniform2f(locDelta, dx, dy);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            // Back into the world image for the screen pass and everything after it (only a copy without smoothing)
            if (smooth) {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, readFbo);
                GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, worldTex, 0);
                GL30.glBlitFramebuffer(0, 0, texW, texH, 0, 0, texW, texH, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
                GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
            }
            // The iso view is affine: a world point's place is the reference point's plus fixed steps per square
            // and per level for this zoom (texture v runs up, screen y down)
            float x0 = zombie.iso.IsoUtils.XToScreen(0f, 0f, 0f, 0), y0 = zombie.iso.IsoUtils.YToScreen(0f, 0f, 0f, 0);
            float su = 1f / (zoom * texW), sv = -1f / (zoom * texH);
            proj = new float[] {ref[0], ref[1], ref[2] / texW, ref[3] / texH,
                (zombie.iso.IsoUtils.XToScreen(1f, 0f, 0f, 0) - x0) * su, (zombie.iso.IsoUtils.XToScreen(0f, 1f, 0f, 0) - x0) * su,
                (zombie.iso.IsoUtils.XToScreen(0f, 0f, 1f, 0) - x0) * su,
                (zombie.iso.IsoUtils.YToScreen(1f, 0f, 0f, 0) - y0) * sv, (zombie.iso.IsoUtils.YToScreen(0f, 1f, 0f, 0) - y0) * sv,
                (zombie.iso.IsoUtils.YToScreen(0f, 0f, 1f, 0) - y0) * sv};
            write = read;
            valid = true;
            runs++;
        } catch (Throwable t) {
            failed = true;
            PZOLogger.warn("[TemporalPass] off for this session: " + t);
        } finally {
            GL30.glBindVertexArray(prevVao);
            GL20.glUseProgram(prevProgram);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
            GL11.glPopAttrib();
            GL13.glActiveTexture(GL13.GL_TEXTURE8);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev8);
            GL13.glActiveTexture(GL13.GL_TEXTURE9);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev9);
            GL13.glActiveTexture(prevActive);
        }
    }

    /** The history (last frame's smoothed world image), for later effects; 0 until it runs. */
    static int history() {
        return valid ? hist[1 - write] : 0;
    }

    /** The card went off: start over next time. */
    static void reset() {
        valid = false;
    }

    private static void allocate(int w, int h) {
        for (int i = 0; i < 2; i++) {
            if (hist[i] != 0) GL11.glDeleteTextures(hist[i]);
            GL13.glActiveTexture(GL13.GL_TEXTURE8);
            hist[i] = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, hist[i]);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        }
        histW = w;
        histH = h;
        valid = false;
    }

    private static boolean init() {
        prog = SkyCache.compile(SkyCache.VERTEX, FRAGMENT, "temporal");
        if (prog == 0) return false;
        locCur = GL20.glGetUniformLocation(prog, "uCur");
        locHist = GL20.glGetUniformLocation(prog, "uHist");
        locInfo = GL20.glGetUniformLocation(prog, "uInfo");
        locDelta = GL20.glGetUniformLocation(prog, "uDelta");
        fbo = GL30.glGenFramebuffers();
        readFbo = GL30.glGenFramebuffers();
        vao = GL30.glGenVertexArrays();
        return true;
    }

    // uInfo = width, height, blend (0 = no history this frame); uDelta = how far the image moved since last frame, in
    // texels. The history is clamped to the box of this pixel's own neighbourhood (3 x 3, as in Karis' TAA), with the
    // box tightened toward the mean so slow drifts don't pass as real.
    static final String FRAGMENT = """
        #version 330
        uniform sampler2D uCur;
        uniform sampler2D uHist;
        uniform vec4 uInfo;
        uniform vec2 uDelta;
        out vec4 o;

        void main()
        {
            ivec2 ip = ivec2(gl_FragCoord.xy);
            ivec2 mx = ivec2(uInfo.xy) - 1;
            vec4 cur = texelFetch(uCur, ip, 0);
            if (uInfo.z <= 0.0) {
                o = cur;
                return;
            }
            vec3 m1 = vec3(0.0), m2 = vec3(0.0);
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    vec3 c = texelFetch(uCur, clamp(ip + ivec2(x, y), ivec2(0), mx), 0).rgb;
                    m1 += c;
                    m2 += c * c;
                }
            }
            vec3 mean = m1 / 9.0;
            vec3 sd = sqrt(max(m2 / 9.0 - mean * mean, 0.0));
            vec2 huv = (gl_FragCoord.xy - uDelta) / uInfo.xy;
            if (huv.x < 0.0 || huv.y < 0.0 || huv.x > 1.0 || huv.y > 1.0) {
                o = cur;
                return;
            }
            vec3 h = texture(uHist, huv).rgb;
            h = clamp(h, mean - sd * 1.25, mean + sd * 1.25);
            o = vec4(mix(cur.rgb, h, uInfo.z), cur.a);
        }
        """;

    static String stateText() {
        return "temporal=" + (on ? 1 : 0) + ";temporalRuns=" + runs + ";temporalResets=" + resets + ";temporalFailed=" + (failed ? 1 : 0);
    }
}
