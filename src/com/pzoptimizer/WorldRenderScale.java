package com.pzoptimizer;

import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import zombie.core.Core;
import zombie.core.textures.TextureFBO;

/**
 * Render-scale virtualization for the world offscreen buffer (FSR 1.0 input stage).
 *
 * Build 42 rasterizes the world into Core.offscreenBuffer with a screen-sized viewport and a zoom-scaled
 * ortho projection. Rendering at a lower resolution therefore only requires shrinking every pixel-space
 * GL call made while that FBO is bound, keeping the projection untouched: the whole frame lands in the
 * bottom-left (W*s x H*s) sub-rectangle, which WorldUpscaleRenderer then upscales back to W x H.
 *
 * The agent redirects these LWJGL calls in every zombie.* class to the static wrappers below (identical
 * descriptors, so the swap is verifier-neutral). Each wrapper tracks the "logical" (unscaled) state the
 * game asked for and applies the scaled value only while the world FBO is the draw target:
 *   glViewport / glScissor          - scaled; logical value re-applied on every FBO switch
 *   glPushAttrib / glPopAttrib      - logical viewport/scissor mirrored so pops restore correctly
 *   glGetIntegerv(VIEWPORT|SCISSOR) - reports the logical value so save/restore pairs do not double-scale
 *   glBlitFramebuffer               - rectangles touching the world FBO are scaled (depth/stencil use NEAREST)
 *   glShaderSource                  - fragment shaders get gl_FragCoord rewritten to full-resolution pixels
 *   glUseProgram / glLinkProgram    - keep the rewritten shaders' PZO_FragCoordScale uniform current
 *
 * All state is touched only on the render thread (the only thread that issues GL in Project Zomboid).
 */
public final class WorldRenderScale {

    private static final int GL_VIEWPORT = 0x0BA2;
    private static final int GL_SCISSOR_BOX = 0x0C10;
    private static final int GL_FRAMEBUFFER = 0x8D40;
    private static final int GL_READ_FRAMEBUFFER = 0x8CA8;
    private static final int GL_DRAW_FRAMEBUFFER = 0x8CA9;
    private static final int GL_DRAW_FRAMEBUFFER_BINDING = 0x8CA6;
    private static final int GL_READ_FRAMEBUFFER_BINDING = 0x8CAA;
    private static final int GL_VIEWPORT_BIT = 0x00000800;
    private static final int GL_SCISSOR_BIT = 0x00080000;
    private static final int GL_DEPTH_BUFFER_BIT = 0x00000100;
    private static final int GL_STENCIL_BUFFER_BIT = 0x00000400;
    private static final int GL_NEAREST = 0x2600;
    private static final int GL_SHADER_TYPE = 0x8B4F;
    private static final int GL_FRAGMENT_SHADER = 0x8B30;

    private static volatile boolean installed = false;

    // Render-thread latched scale; changed only by WorldUpscaleRenderer between frames
    private static float rtScale = 1.0f;

    private static int drawFbo = 0;
    private static int readFbo = 0;
    private static boolean bindingsSynced = false;

    private static final int[] logicalViewport = new int[4];
    private static final int[] logicalScissor = new int[4];
    private static boolean haveViewport = false;
    private static boolean haveScissor = false;
    private static boolean worldDirty = false;

    private static final ArrayDeque<int[]> attribStack = new ArrayDeque<>();
    private static final int[] scratch4 = new int[4];

    private static int currentProgram = 0;
    private static int[] uniformLocCache = new int[1024];
    private static float[] uniformLastSet = new float[1024];

    private static final String FRAG_SCALE_UNIFORM = "PZO_FragCoordScale";
    private static final Pattern FRAG_COORD = Pattern.compile("\\bgl_FragCoord\\b");
    public static int shadersRewritten = 0;

    static {
        java.util.Arrays.fill(uniformLocCache, -2);
        java.util.Arrays.fill(uniformLastSet, -1.0f);
    }

    private WorldRenderScale() {}

    public static void markInstalled() {
        installed = true;
    }

    public static boolean isInstalled() {
        return installed;
    }

    public static float getRenderThreadScale() {
        return rtScale;
    }

    private static int worldFboId() {
        try {
            TextureFBO fbo = Core.getInstance().offscreenBuffer.current;
            return fbo != null ? fbo.getBufferId() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void syncBindingsOnce() {
        if (bindingsSynced) return;
        bindingsSynced = true;
        drawFbo = GL11C.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        readFbo = GL11C.glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
    }

    private static boolean scalingDraw() {
        if (rtScale >= 0.999f) return false;
        syncBindingsOnce();
        int world = worldFboId();
        return world != 0 && drawFbo == world;
    }

    private static boolean scalingRead() {
        if (rtScale >= 0.999f) return false;
        syncBindingsOnce();
        int world = worldFboId();
        return world != 0 && readFbo == world;
    }

    /** Scale used by PZO's own passes when rendering into the given FBO (1.0 unless it is the scaled world). */
    public static float scaleForTarget(int fbo) {
        if (!installed || rtScale >= 0.999f) return 1.0f;
        int world = worldFboId();
        return (world != 0 && fbo == world) ? rtScale : 1.0f;
    }

    private static int sc(int v) {
        return Math.round(v * rtScale);
    }

    /** True if the world FBO received scaled rendering since the last upscale. */
    static boolean consumeWorldDirty() {
        boolean d = worldDirty;
        worldDirty = false;
        return d;
    }

    /** Called by WorldUpscaleRenderer after the upscale so the next frame renders at the new scale. */
    static void latchScale(float scale) {
        float s = Math.max(0.25f, Math.min(1.0f, scale));
        if (s >= 0.995f) s = 1.0f;
        if (s != rtScale) {
            rtScale = s;
            // Re-issue the logical state so the change is coherent if the world FBO is still bound
            reapplyViewportAndScissor();
            updateFragScaleUniform();
        }
    }

    static void reapplyViewportAndScissor() {
        boolean scale = scalingDraw();
        if (haveViewport) applyViewport(logicalViewport, scale);
        if (haveScissor) applyScissor(logicalScissor, scale);
    }

    private static void applyViewport(int[] v, boolean scale) {
        if (scale) {
            int x0 = sc(v[0]), y0 = sc(v[1]);
            int x1 = sc(v[0] + v[2]), y1 = sc(v[1] + v[3]);
            GL11C.glViewport(x0, y0, Math.max(v[2] > 0 ? 1 : 0, x1 - x0), Math.max(v[3] > 0 ? 1 : 0, y1 - y0));
            worldDirty = true;
        } else {
            GL11C.glViewport(v[0], v[1], v[2], v[3]);
        }
    }

    private static void applyScissor(int[] v, boolean scale) {
        if (scale) {
            int x0 = sc(v[0]), y0 = sc(v[1]);
            int x1 = sc(v[0] + v[2]), y1 = sc(v[1] + v[3]);
            GL11C.glScissor(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
        } else {
            GL11C.glScissor(v[0], v[1], v[2], v[3]);
        }
    }

    public static void glViewport(int x, int y, int w, int h) {
        logicalViewport[0] = x;
        logicalViewport[1] = y;
        logicalViewport[2] = w;
        logicalViewport[3] = h;
        haveViewport = true;
        applyViewport(logicalViewport, scalingDraw());
    }

    public static void glScissor(int x, int y, int w, int h) {
        logicalScissor[0] = x;
        logicalScissor[1] = y;
        logicalScissor[2] = w;
        logicalScissor[3] = h;
        haveScissor = true;
        applyScissor(logicalScissor, scalingDraw());
    }

    private static void onBindingChanged(boolean drawChanged, boolean wasScaling) {
        if (drawChanged) {
            boolean nowScaling = scalingDraw();
            if (nowScaling != wasScaling) {
                reapplyViewportAndScissor();
                updateFragScaleUniform();
            }
        }
    }

    private static void trackBind(int target, int fbo) {
        bindingsSynced = true;
        if (target == GL_FRAMEBUFFER) {
            drawFbo = fbo;
            readFbo = fbo;
        } else if (target == GL_DRAW_FRAMEBUFFER) {
            drawFbo = fbo;
        } else if (target == GL_READ_FRAMEBUFFER) {
            readFbo = fbo;
        }
    }

    public static void glBindFramebuffer(int target, int fbo) {
        boolean wasScaling = scalingDraw();
        GL30C.glBindFramebuffer(target, fbo);
        trackBind(target, fbo);
        onBindingChanged(target != GL_READ_FRAMEBUFFER, wasScaling);
    }

    public static void glBindFramebufferEXT(int target, int fbo) {
        boolean wasScaling = scalingDraw();
        EXTFramebufferObject.glBindFramebufferEXT(target, fbo);
        trackBind(target, fbo);
        onBindingChanged(target != GL_READ_FRAMEBUFFER, wasScaling);
    }

    public static void glPushAttrib(int mask) {
        GL11.glPushAttrib(mask);
        attribStack.push(new int[] {
            mask,
            haveViewport ? 1 : 0, logicalViewport[0], logicalViewport[1], logicalViewport[2], logicalViewport[3],
            haveScissor ? 1 : 0, logicalScissor[0], logicalScissor[1], logicalScissor[2], logicalScissor[3]
        });
    }

    public static void glPopAttrib() {
        GL11.glPopAttrib();
        int[] e = attribStack.poll();
        if (e == null) return;
        boolean scale = scalingDraw();
        if ((e[0] & GL_VIEWPORT_BIT) != 0 && e[1] == 1) {
            System.arraycopy(e, 2, logicalViewport, 0, 4);
            haveViewport = true;
            applyViewport(logicalViewport, scale);
        }
        if ((e[0] & GL_SCISSOR_BIT) != 0 && e[6] == 1) {
            System.arraycopy(e, 7, logicalScissor, 0, 4);
            haveScissor = true;
            applyScissor(logicalScissor, scale);
        }
    }

    public static void glGetIntegerv(int pname, int[] params) {
        if (scalingDraw() && params != null && params.length >= 4) {
            if (pname == GL_VIEWPORT && haveViewport) {
                System.arraycopy(logicalViewport, 0, params, 0, 4);
                return;
            }
            if (pname == GL_SCISSOR_BOX && haveScissor) {
                System.arraycopy(logicalScissor, 0, params, 0, 4);
                return;
            }
        }
        GL11C.glGetIntegerv(pname, params);
    }

    public static void glGetIntegerv(int pname, IntBuffer params) {
        if (scalingDraw() && params != null && params.remaining() >= 4) {
            if (pname == GL_VIEWPORT && haveViewport) {
                int p = params.position();
                for (int i = 0; i < 4; i++) params.put(p + i, logicalViewport[i]);
                return;
            }
            if (pname == GL_SCISSOR_BOX && haveScissor) {
                int p = params.position();
                for (int i = 0; i < 4; i++) params.put(p + i, logicalScissor[i]);
                return;
            }
        }
        GL11C.glGetIntegerv(pname, params);
    }

    public static void glBlitFramebuffer(int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1, int mask, int filter) {
        boolean rs = scalingRead();
        boolean ds = scalingDraw();
        if (rs) {
            sx0 = sc(sx0); sy0 = sc(sy0); sx1 = sc(sx1); sy1 = sc(sy1);
        }
        if (ds) {
            dx0 = sc(dx0); dy0 = sc(dy0); dx1 = sc(dx1); dy1 = sc(dy1);
            worldDirty = true;
        }
        // Scaled depth/stencil blits must be NEAREST or GL rejects them
        if ((rs != ds) && (mask & (GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT)) != 0) {
            filter = GL_NEAREST;
        }
        GL30C.glBlitFramebuffer(sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, mask, filter);
    }

    public static void glShaderSource(int shader, CharSequence source) {
        CharSequence out = source;
        try {
            if (source != null && GL20C.glGetShaderi(shader, GL_SHADER_TYPE) == GL_FRAGMENT_SHADER) {
                String s = source.toString();
                if (FRAG_COORD.matcher(s).find() && !s.contains(FRAG_SCALE_UNIFORM)) {
                    out = rewriteFragCoord(s);
                    shadersRewritten++;
                }
            }
        } catch (Throwable t) {
            out = source;
        }
        GL20C.glShaderSource(shader, out);
    }

    /**
     * gl_FragCoord inside the scaled world FBO is in reduced pixels; every game shader expects full-resolution
     * pixels (puddles/water WViewport, fog screenInfo, visibility blur screenSize). The uniform defaults to 0,
     * which leaves gl_FragCoord untouched, so an unset uniform can never corrupt a shader.
     */
    static String rewriteFragCoord(String src) {
        String replaced = FRAG_COORD.matcher(src).replaceAll(Matcher.quoteReplacement("pzo_FragCoord()"));
        String helper = "uniform vec2 " + FRAG_SCALE_UNIFORM + ";\n"
            + "vec4 pzo_FragCoord() { return " + FRAG_SCALE_UNIFORM + ".y > 0.5 ? vec4(gl_FragCoord.xy / "
            + FRAG_SCALE_UNIFORM + ".x, gl_FragCoord.zw) : gl_FragCoord; }\n";

        // Insert after the leading #version / #extension directives (both must precede other code)
        String[] lines = replaced.split("\n", -1);
        int insertAt = 0;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.startsWith("#version") || t.startsWith("#extension")) {
                insertAt = i + 1;
            } else if (!t.isEmpty() && !t.startsWith("//")) {
                break;
            }
        }
        StringBuilder sb = new StringBuilder(replaced.length() + helper.length() + 16);
        for (int i = 0; i < lines.length; i++) {
            if (i == insertAt) sb.append(helper);
            sb.append(lines[i]);
            if (i < lines.length - 1) sb.append('\n');
        }
        if (insertAt >= lines.length) sb.append('\n').append(helper);
        return sb.toString();
    }

    public static void glUseProgram(int program) {
        GL20C.glUseProgram(program);
        currentProgram = program;
        updateFragScaleUniform();
    }

    public static void glLinkProgram(int program) {
        GL20C.glLinkProgram(program);
        if (program >= 0 && program < uniformLocCache.length) {
            uniformLocCache[program] = -2;
            uniformLastSet[program] = -1.0f;
        }
    }

    private static void updateFragScaleUniform() {
        int p = currentProgram;
        if (!installed || p <= 0) return;
        if (p >= uniformLocCache.length) {
            int n = Math.max(p + 1, uniformLocCache.length * 2);
            int[] loc = java.util.Arrays.copyOf(uniformLocCache, n);
            float[] last = java.util.Arrays.copyOf(uniformLastSet, n);
            java.util.Arrays.fill(loc, uniformLocCache.length, n, -2);
            java.util.Arrays.fill(last, uniformLastSet.length, n, -1.0f);
            uniformLocCache = loc;
            uniformLastSet = last;
        }
        int loc = uniformLocCache[p];
        if (loc == -2) {
            loc = GL20C.glGetUniformLocation(p, FRAG_SCALE_UNIFORM);
            uniformLocCache[p] = loc;
        }
        if (loc < 0) return;
        float want = scalingDraw() ? rtScale : 0.0f;
        if (uniformLastSet[p] != want) {
            uniformLastSet[p] = want;
            GL20C.glUniform2f(loc, want > 0.0f ? want : 1.0f, want > 0.0f ? 1.0f : 0.0f);
        }
    }

    /** Lets PZO's own raw GL passes report a program switch so the uniform cache stays honest. */
    static void notifyRawProgram(int program) {
        currentProgram = program;
    }
}
