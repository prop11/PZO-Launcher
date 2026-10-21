package com.pzoptimizer;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import zombie.core.Core;
import zombie.core.ShaderHelper;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureFBO;

/**
 * AMD FidelityFX Super Resolution 1.0 pass for the scaled world buffer.
 *
 * Queued on the render thread just before Core.RenderOffScreenBuffer() composites the world. It upscales
 * the (W*s x H*s) sub-rectangle WorldRenderScale rendered into back to W x H:
 *   1. EASU (edge-adaptive spatial upsampling, 12-tap)      world FBO sub-rect -> PZO temp FBO
 *   2. RCAS (robust contrast-adaptive sharpening)           PZO temp FBO      -> world FBO (full rect)
 * The vanilla composite (screen.frag weather shader, reticle, cursor) then runs on a full-resolution world
 * texture exactly as it would without FSR, and the UI is never scaled.
 *
 * Shaders are ports of ffx_fsr1.h (MIT, Advanced Micro Devices) to GLSL 3.30 using texelFetch with
 * manual edge clamping, so the unscaled remainder of the world texture is never sampled.
 * Any GL or compile failure disables FSR (scale latches back to 1.0) instead of corrupting the frame.
 */
public final class WorldUpscaleRenderer extends TextureDraw.GenericDrawer {

    static final WorldUpscaleRenderer INSTANCE = new WorldUpscaleRenderer();

    private static boolean initialized = false;
    private static boolean failed = false;

    private static int easuProgram = 0;
    private static int bilinearProgram = 0;
    private static int rcasProgram = 0;
    private static int easuCon0 = -1, easuInputMax = -1, easuTex = -1;
    private static int bilCon0 = -1, bilInputMax = -1, bilTex = -1;
    private static int rcasSharp = -1, rcasInputMax = -1, rcasTex = -1;
    private static int vao = 0;

    private static int tempFbo = 0;
    private static int tempTex = 0;
    private static int tempW = 0;
    private static int tempH = 0;

    private WorldUpscaleRenderer() {}

    public static boolean isFailed() {
        return failed;
    }

    @Override
    public void render() {
        float used = WorldRenderScale.getRenderThreadScale();
        boolean dirty = WorldRenderScale.consumeWorldDirty();
        try {
            if (!failed && used < 0.999f && dirty) {
                upscale(used);
            }
        } catch (Throwable t) {
            failed = true;
            WorldUpscalerGovernor.markPipelineFailed();
            PZOLogger.warn("[WorldUpscaler] FSR pass failed, reverting to native resolution: " + t);
        }
        // Next frame renders at the latest requested scale; FSR is only allowed once its shaders exist
        float next = (!failed && initialized) ? WorldUpscalerGovernor.getEffectiveScale() : 1.0f;
        if (!initialized && !failed && WorldUpscalerGovernor.getEffectiveScale() < 0.999f) {
            try {
                init();
                next = WorldUpscalerGovernor.getEffectiveScale();
            } catch (Throwable t) {
                failed = true;
                WorldUpscalerGovernor.markPipelineFailed();
                next = 1.0f;
                PZOLogger.warn("[WorldUpscaler] FSR shader setup failed, staying at native resolution: " + t.getMessage());
            }
        }
        WorldRenderScale.latchScale(next);
    }

    private static void upscale(float scale) {
        TextureFBO world = Core.getInstance().offscreenBuffer.current;
        if (world == null) return;
        Texture worldTex = (Texture) world.getTexture();
        if (worldTex == null) return;

        int outW = Core.getInstance().getScreenWidth();
        int outH = Core.getInstance().getScreenHeight();
        int inW = Math.max(1, Math.round(outW * scale));
        int inH = Math.max(1, Math.round(outH * scale));
        if (outW <= 0 || outH <= 0) return;

        int prevDraw = GL11C.glGetInteger(0x8CA6 /* GL_DRAW_FRAMEBUFFER_BINDING */);
        int prevRead = GL11C.glGetInteger(0x8CAA /* GL_READ_FRAMEBUFFER_BINDING */);
        int prevVao = GL11C.glGetInteger(0x85B5 /* GL_VERTEX_ARRAY_BINDING */);
        try {
            ensureTemp(outW, outH);

            GL11C.glDisable(0x0BE2 /* GL_BLEND */);
            GL11C.glDisable(0x0B71 /* GL_DEPTH_TEST */);
            GL11C.glDisable(0x0C11 /* GL_SCISSOR_TEST */);
            GL11C.glDisable(0x0B90 /* GL_STENCIL_TEST */);
            GL11C.glDepthMask(false);
            GL11C.glColorMask(true, true, true, true);
            GL30C.glBindVertexArray(vao);
            GL13C.glActiveTexture(0x84C0 /* GL_TEXTURE0 */);

            boolean fsr = "fsr1".equals(WorldUpscalerGovernor.getUpscalerMode());

            // Pass 1: world sub-rect -> temp (EASU, or plain bilinear for the non-FSR mode)
            GL30C.glBindFramebuffer(0x8D40, tempFbo);
            GL11C.glViewport(0, 0, outW, outH);
            int prog = fsr ? easuProgram : bilinearProgram;
            GL20C.glUseProgram(prog);
            WorldRenderScale.notifyRawProgram(prog);
            GL11C.glBindTexture(0x0DE1, worldTex.getID());
            float rx = (float) inW / outW;
            float ry = (float) inH / outH;
            if (fsr) {
                GL20C.glUniform1i(easuTex, 0);
                GL20C.glUniform4f(easuCon0, rx, ry, 0.5f * rx - 0.5f, 0.5f * ry - 0.5f);
                GL20C.glUniform2i(easuInputMax, inW - 1, inH - 1);
            } else {
                GL20C.glUniform1i(bilTex, 0);
                GL20C.glUniform4f(bilCon0, rx, ry, worldTex.getWidthHW(), worldTex.getHeightHW());
                GL20C.glUniform2i(bilInputMax, inW - 1, inH - 1);
            }
            GL11C.glDrawArrays(0x0004 /* GL_TRIANGLES */, 0, 3);

            // Pass 2: temp -> world full rect (RCAS; sharpness 0 is an exact copy for the non-FSR mode)
            GL30C.glBindFramebuffer(0x8D40, world.getBufferId());
            GL11C.glViewport(0, 0, outW, outH);
            GL20C.glUseProgram(rcasProgram);
            WorldRenderScale.notifyRawProgram(rcasProgram);
            GL11C.glBindTexture(0x0DE1, tempTex);
            GL20C.glUniform1i(rcasTex, 0);
            float stops = (1.0f - WorldUpscalerGovernor.getSharpness()) * 2.0f;
            GL20C.glUniform1f(rcasSharp, fsr ? (float) Math.pow(2.0, -stops) : 0.0f);
            GL20C.glUniform2i(rcasInputMax, outW - 1, outH - 1);
            GL11C.glDrawArrays(0x0004, 0, 3);

            WorldUpscalerGovernor.framesUpscaled.incrementAndGet();
        } finally {
            GL30C.glBindVertexArray(prevVao);
            GL30C.glBindFramebuffer(0x8CA9 /* DRAW */, prevDraw);
            GL30C.glBindFramebuffer(0x8CA8 /* READ */, prevRead);
            GL11C.glBindTexture(0x0DE1, 0);
            GL11C.glDepthMask(true);
            WorldRenderScale.reapplyViewportAndScissor();
            resyncEngineState();
        }
    }

    // Same resync as FogQuarterBufferGovernor: the engine caches bound texture/shader/GL state
    private static void resyncEngineState() {
        try {
            Texture.lastTextureID = -1;
            SpriteRenderer.ringBuffer.restoreBoundTextures = true;
            SpriteRenderer.ringBuffer.restoreVbos = true;
            ShaderHelper.forgetCurrentlyBound();
            ShaderHelper.glUseProgramObjectARB(0);
            GLStateRenderThread.restore();
        } catch (Throwable t) {
            failed = true;
            WorldUpscalerGovernor.markPipelineFailed();
            PZOLogger.warn("[WorldUpscaler] Engine GL state resync unavailable, disabling FSR: " + t.getMessage());
        }
    }

    private static void ensureTemp(int w, int h) {
        if (tempFbo != 0 && tempW == w && tempH == h) return;
        if (tempFbo != 0) {
            GL30C.glDeleteFramebuffers(tempFbo);
            GL11C.glDeleteTextures(tempTex);
        }
        tempTex = GL11C.glGenTextures();
        GL11C.glBindTexture(0x0DE1, tempTex);
        GL11C.glTexImage2D(0x0DE1, 0, 0x8058 /* GL_RGBA8 */, w, h, 0, 0x1908 /* GL_RGBA */, 0x1401, (java.nio.ByteBuffer) null);
        GL11C.glTexParameteri(0x0DE1, 0x2801, 0x2600 /* NEAREST */);
        GL11C.glTexParameteri(0x0DE1, 0x2800, 0x2600);
        GL11C.glTexParameteri(0x0DE1, 0x2802, 0x812F /* CLAMP_TO_EDGE */);
        GL11C.glTexParameteri(0x0DE1, 0x2803, 0x812F);
        tempFbo = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(0x8D40, tempFbo);
        GL30C.glFramebufferTexture2D(0x8D40, 0x8CE0 /* COLOR_ATTACHMENT0 */, 0x0DE1, tempTex, 0);
        int status = GL30C.glCheckFramebufferStatus(0x8D40);
        if (status != 0x8CD5) {
            throw new IllegalStateException("FSR temp FBO incomplete: 0x" + Integer.toHexString(status));
        }
        tempW = w;
        tempH = h;
    }

    private static void init() {
        int vs = compile(0x8B31 /* VERTEX */, FULLSCREEN_VERT);
        easuProgram = link(vs, compile(0x8B30, EASU_FRAG));
        bilinearProgram = link(vs, compile(0x8B30, BILINEAR_FRAG));
        rcasProgram = link(vs, compile(0x8B30, RCAS_FRAG));
        GL20C.glDeleteShader(vs);

        easuTex = GL20C.glGetUniformLocation(easuProgram, "InputTex");
        easuCon0 = GL20C.glGetUniformLocation(easuProgram, "Con0");
        easuInputMax = GL20C.glGetUniformLocation(easuProgram, "InputMax");
        bilTex = GL20C.glGetUniformLocation(bilinearProgram, "InputTex");
        bilCon0 = GL20C.glGetUniformLocation(bilinearProgram, "Con0");
        bilInputMax = GL20C.glGetUniformLocation(bilinearProgram, "InputMax");
        rcasTex = GL20C.glGetUniformLocation(rcasProgram, "InputTex");
        rcasSharp = GL20C.glGetUniformLocation(rcasProgram, "Sharp");
        rcasInputMax = GL20C.glGetUniformLocation(rcasProgram, "InputMax");
        vao = GL30C.glGenVertexArrays();
        initialized = true;
        PZOLogger.success("[WorldUpscaler] AMD FSR 1.0 EASU + RCAS pipeline ready");
    }

    private static int compile(int type, String src) {
        int s = GL20C.glCreateShader(type);
        GL20C.glShaderSource(s, src);
        GL20C.glCompileShader(s);
        if (GL20C.glGetShaderi(s, 0x8B81 /* COMPILE_STATUS */) == 0) {
            String log = GL20C.glGetShaderInfoLog(s, 4096);
            GL20C.glDeleteShader(s);
            throw new IllegalStateException("FSR shader compile failed: " + log);
        }
        return s;
    }

    private static int link(int vs, int fs) {
        int p = GL20C.glCreateProgram();
        GL20C.glAttachShader(p, vs);
        GL20C.glAttachShader(p, fs);
        GL20C.glLinkProgram(p);
        GL20C.glDeleteShader(fs);
        if (GL20C.glGetProgrami(p, 0x8B82 /* LINK_STATUS */) == 0) {
            String log = GL20C.glGetProgramInfoLog(p, 4096);
            GL20C.glDeleteProgram(p);
            throw new IllegalStateException("FSR program link failed: " + log);
        }
        return p;
    }

    // Attribute-less fullscreen triangle
    static final String FULLSCREEN_VERT = String.join("\n",
        "#version 330",
        "void main() {",
        "    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));",
        "    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);",
        "}");

    static final String EASU_FRAG = String.join("\n",
        "#version 330",
        "uniform sampler2D InputTex;",
        "uniform vec4 Con0;",
        "uniform ivec2 InputMax;",
        "out vec4 FragColor;",
        "vec3 T(ivec2 p) { return texelFetch(InputTex, clamp(p, ivec2(0), InputMax), 0).rgb; }",
        "float Lum(vec3 c) { return c.b * 0.5 + (c.r * 0.5 + c.g); }",
        "float Rcp(float x) { return 1.0 / max(x, 1e-10); }",
        "void SetF(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {",
        "    float dc = lD - lC;",
        "    float cb = lC - lB;",
        "    float lenX = Rcp(max(abs(dc), abs(cb)));",
        "    float dirX = lD - lB;",
        "    dir.x += dirX * w;",
        "    lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);",
        "    lenX *= lenX;",
        "    len += lenX * w;",
        "    float ec = lE - lC;",
        "    float ca = lC - lA;",
        "    float lenY = Rcp(max(abs(ec), abs(ca)));",
        "    float dirY = lE - lA;",
        "    dir.y += dirY * w;",
        "    lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);",
        "    lenY *= lenY;",
        "    len += lenY * w;",
        "}",
        "void TapF(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {",
        "    vec2 v;",
        "    v.x = (off.x * dir.x) + (off.y * dir.y);",
        "    v.y = (off.x * (-dir.y)) + (off.y * dir.x);",
        "    v *= len;",
        "    float d2 = min(v.x * v.x + v.y * v.y, clp);",
        "    float wB = (2.0 / 5.0) * d2 - 1.0;",
        "    float wA = lob * d2 - 1.0;",
        "    wB *= wB;",
        "    wA *= wA;",
        "    wB = (25.0 / 16.0) * wB - (25.0 / 16.0 - 1.0);",
        "    float w = wB * wA;",
        "    aC += c * w;",
        "    aW += w;",
        "}",
        "void main() {",
        "    vec2 pp = floor(gl_FragCoord.xy) * Con0.xy + Con0.zw;",
        "    vec2 fp = floor(pp);",
        "    pp -= fp;",
        "    ivec2 o = ivec2(fp);",
        // 12-tap kernel:   b c / e f g h / i j k l / n o
        "    vec3 b = T(o + ivec2(0, -1)); vec3 c = T(o + ivec2(1, -1));",
        "    vec3 e = T(o + ivec2(-1, 0)); vec3 f = T(o); vec3 g = T(o + ivec2(1, 0)); vec3 h = T(o + ivec2(2, 0));",
        "    vec3 i = T(o + ivec2(-1, 1)); vec3 j = T(o + ivec2(0, 1)); vec3 k = T(o + ivec2(1, 1)); vec3 l = T(o + ivec2(2, 1));",
        "    vec3 n = T(o + ivec2(0, 2)); vec3 oo = T(o + ivec2(1, 2));",
        "    float bL = Lum(b), cL = Lum(c), eL = Lum(e), fL = Lum(f), gL = Lum(g), hL = Lum(h);",
        "    float iL = Lum(i), jL = Lum(j), kL = Lum(k), lL = Lum(l), nL = Lum(n), oL = Lum(oo);",
        "    vec2 dir = vec2(0.0);",
        "    float len = 0.0;",
        "    SetF(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);",
        "    SetF(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);",
        "    SetF(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);",
        "    SetF(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);",
        "    vec2 dir2 = dir * dir;",
        "    float dirR = dir2.x + dir2.y;",
        "    bool zro = dirR < (1.0 / 32768.0);",
        "    dirR = inversesqrt(max(dirR, 1e-20));",
        "    dirR = zro ? 1.0 : dirR;",
        "    dir.x = zro ? 1.0 : dir.x;",
        "    dir *= vec2(dirR);",
        "    len = len * 0.5;",
        "    len *= len;",
        "    float stretch = (dir.x * dir.x + dir.y * dir.y) * Rcp(max(abs(dir.x), abs(dir.y)));",
        "    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);",
        "    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;",
        "    float clp = Rcp(lob);",
        "    vec3 min4 = min(min(f, g), min(j, k));",
        "    vec3 max4 = max(max(f, g), max(j, k));",
        "    vec3 aC = vec3(0.0);",
        "    float aW = 0.0;",
        "    TapF(aC, aW, vec2( 0.0, -1.0) - pp, dir, len2, lob, clp, b);",
        "    TapF(aC, aW, vec2( 1.0, -1.0) - pp, dir, len2, lob, clp, c);",
        "    TapF(aC, aW, vec2(-1.0,  1.0) - pp, dir, len2, lob, clp, i);",
        "    TapF(aC, aW, vec2( 0.0,  1.0) - pp, dir, len2, lob, clp, j);",
        "    TapF(aC, aW, vec2( 0.0,  0.0) - pp, dir, len2, lob, clp, f);",
        "    TapF(aC, aW, vec2(-1.0,  0.0) - pp, dir, len2, lob, clp, e);",
        "    TapF(aC, aW, vec2( 1.0,  1.0) - pp, dir, len2, lob, clp, k);",
        "    TapF(aC, aW, vec2( 2.0,  1.0) - pp, dir, len2, lob, clp, l);",
        "    TapF(aC, aW, vec2( 2.0,  0.0) - pp, dir, len2, lob, clp, h);",
        "    TapF(aC, aW, vec2( 1.0,  0.0) - pp, dir, len2, lob, clp, g);",
        "    TapF(aC, aW, vec2( 1.0,  2.0) - pp, dir, len2, lob, clp, oo);",
        "    TapF(aC, aW, vec2( 0.0,  2.0) - pp, dir, len2, lob, clp, n);",
        "    vec3 pix = min(max4, max(min4, aC / max(aW, 1e-10)));",
        "    FragColor = vec4(pix, 1.0);",
        "}");

    // Con0 = (in/out ratio x, y, texture width, texture height)
    static final String BILINEAR_FRAG = String.join("\n",
        "#version 330",
        "uniform sampler2D InputTex;",
        "uniform vec4 Con0;",
        "uniform ivec2 InputMax;",
        "out vec4 FragColor;",
        "void main() {",
        "    vec2 src = clamp(gl_FragCoord.xy * Con0.xy, vec2(0.5), vec2(InputMax) + 0.5);",
        "    FragColor = vec4(texture(InputTex, src / Con0.zw).rgb, 1.0);",
        "}");

    static final String RCAS_FRAG = String.join("\n",
        "#version 330",
        "uniform sampler2D InputTex;",
        "uniform float Sharp;",
        "uniform ivec2 InputMax;",
        "out vec4 FragColor;",
        "vec3 T(ivec2 p) { return texelFetch(InputTex, clamp(p, ivec2(0), InputMax), 0).rgb; }",
        "void main() {",
        "    ivec2 sp = ivec2(gl_FragCoord.xy);",
        //   b / d e f / h
        "    vec3 b = T(sp + ivec2(0, -1));",
        "    vec3 d = T(sp + ivec2(-1, 0));",
        "    vec3 e = T(sp);",
        "    vec3 f = T(sp + ivec2(1, 0));",
        "    vec3 h = T(sp + ivec2(0, 1));",
        "    vec3 mn4 = min(min(b, d), min(f, h));",
        "    vec3 mx4 = max(max(b, d), max(f, h));",
        "    vec3 hitMin = min(mn4, e) / max(4.0 * mx4, vec3(1e-5));",
        "    vec3 hitMax = (1.0 - max(mx4, e)) / min(4.0 * mn4 - 4.0, vec3(-1e-5));",
        "    vec3 lobeRGB = max(-hitMin, hitMax);",
        "    float lobe = max(-(0.25 - 1.0 / 16.0), min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0)) * Sharp;",
        "    float rcpL = 1.0 / (4.0 * lobe + 1.0);",
        "    vec3 c = (lobe * b + lobe * d + lobe * h + lobe * f + e) * rcpL;",
        "    FragColor = vec4(c, 1.0);",
        "}");
}
