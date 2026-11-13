package com.pzoptimizer.pzoe;

import com.pzoptimizer.ShaderPatchHost;
import com.pzoptimizer.PZOLogger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.iso.fboRenderChunk.FBORenderChunk;

/**
 * Bake passes (PZOApi.addBakePass): fragment shaders run once over a chunk texture right after the chunk is baked, for
 * effects that don't change frame to frame (ambient occlusion, weathering). They cost one full-texture pass per bake
 * instead of their work on every pixel of every frame; with per-pixel lighting on, chunks rarely re-bake at all.
 *
 * The pass runs at the end of a full bake (one that began with a clear; the item and corpse passes that draw on top
 * later don't repeat it), with the chunk's framebuffer still bound. Each shader's output multiplies the colour (blend
 * dst * src, alpha kept). It reads the chunk's depth texture, which is attached to that framebuffer, so the depth
 * attachment comes off for the pass and goes back after; reading it while attached is undefined in GL. Empty texels
 * have the cleared depth of 1.0, so the colour texture is never read. All GL state it touches is saved and restored.
 *
 * Nothing runs until a mod registers a pass and its setting is on.
 */
public final class BakePass {

    private static final class Pass {
        final String id, frag;
        boolean on;

        Pass(String id, String frag) {
            this.id = id;
            this.frag = frag;
        }
    }

    // Game thread (Lua)
    private static final LinkedHashMap<String, Pass> staged = new LinkedHashMap<>();
    // {id, source with defines filled}
    private static volatile List<String[]> live = List.of();
    private static volatile int version;
    /** Set when the live passes change; the main thread re-bakes the loaded chunks once. */
    static volatile boolean rebakeWanted;
    private static final ConcurrentHashMap<String, String> status = new ConcurrentHashMap<>();

    // Render thread
    private static int builtVersion = -1, vertexShader, emptyVao;
    private static int[] programs = new int[0], locDepth = new int[0], locSize = new int[0], locChunk = new int[0];

    private BakePass() {}

    static void reset() {
        staged.clear();
    }

    static boolean add(String id, String frag) {
        if (id == null || id.isEmpty() || frag == null || frag.isEmpty()) return false;
        Pass old = staged.get(id);
        Pass p = new Pass(id, frag.replace("\r\n", "\n"));
        p.on = old != null && old.on;
        staged.put(id, p);
        return true;
    }

    static boolean setOn(String id, boolean on) {
        Pass p = staged.get(id);
        if (p == null) return false;
        p.on = on;
        return true;
    }

    /** After ShaderPatchHost.commit, so the defines are the committed ones. */
    static void commit() {
        List<String[]> next = new ArrayList<>();
        for (Pass p : staged.values()) {
            if (p.on) next.add(new String[] {p.id, ShaderPatchHost.fillCommitted(p.frag)});
        }
        if (signature(next).equals(signature(live))) return;
        live = List.copyOf(next);
        version++;
        status.clear();
        rebakeWanted = true;
        PZOLogger.info("[BakePass] live passes: " + ids(next) + ", loaded chunks re-bake");
    }

    private static String signature(List<String[]> list) {
        StringBuilder sb = new StringBuilder();
        for (String[] s : list) sb.append(s[0]).append('\u0001').append(s[1]).append('\u0002');
        return sb.toString();
    }

    private static String ids(List<String[]> list) {
        StringBuilder sb = new StringBuilder();
        for (String[] s : list) sb.append(sb.length() > 0 ? ", " : "").append(s[0]);
        return sb.length() == 0 ? "none" : sb.toString();
    }

    static String statusOf(String id) {
        if (id == null) return ShaderPatchHost.UNKNOWN;
        Pass p = staged.get(id);
        if (p == null) return ShaderPatchHost.UNKNOWN;
        if (!p.on) return ShaderPatchHost.OFF;
        return status.getOrDefault(id, ShaderPatchHost.WAITING);
    }

    // Covers the whole target with one triangle and no vertex data
    private static final String VERTEX = "#version 130\nvoid main()\n{\n"
        + "    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n"
        + "    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n}\n";

    private static void build(List<String[]> passes) {
        for (int p : programs) if (p != 0) GL20.glDeleteProgram(p);
        if (vertexShader == 0) vertexShader = compile(GL20.GL_VERTEX_SHADER, VERTEX, "vertex");
        if (emptyVao == 0) emptyVao = GL30.glGenVertexArrays();
        int n = passes.size();
        programs = new int[n];
        locDepth = new int[n];
        locSize = new int[n];
        locChunk = new int[n];
        for (int i = 0; i < n; i++) {
            String id = passes.get(i)[0];
            int fs = compile(GL20.GL_FRAGMENT_SHADER, passes.get(i)[1], id);
            if (fs == 0 || vertexShader == 0) {
                status.put(id, ShaderPatchHost.COMPILE_FAILED);
                continue;
            }
            int prog = GL20.glCreateProgram();
            GL20.glAttachShader(prog, vertexShader);
            GL20.glAttachShader(prog, fs);
            GL20.glLinkProgram(prog);
            GL20.glDeleteShader(fs);
            if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == 0) {
                PZOLogger.warn("[BakePass] " + id + " didn't link: " + GL20.glGetProgramInfoLog(prog, 4096).trim());
                GL20.glDeleteProgram(prog);
                status.put(id, ShaderPatchHost.COMPILE_FAILED);
                continue;
            }
            programs[i] = prog;
            locDepth[i] = GL20.glGetUniformLocation(prog, "PZOBakeDepth");
            locSize[i] = GL20.glGetUniformLocation(prog, "PZOBakeSize");
            locChunk[i] = GL20.glGetUniformLocation(prog, "PZOBakeChunk");
            status.put(id, ShaderPatchHost.APPLIED);
        }
    }

    private static int compile(int type, String src, String what) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
            PZOLogger.warn("[BakePass] " + what + " didn't compile: " + GL20.glGetShaderInfoLog(s, 4096).trim());
            GL20.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    /** End of a full chunk bake (FBORenderChunk.endRenderThread, framebuffer still bound), render thread. */
    static void afterBake(FBORenderChunk rc) {
        List<String[]> passes = live;
        if (passes.isEmpty() || rc == null || rc.fbo == null || rc.depth == null || rc.tex == null || rc.chunk == null) return;
        if (builtVersion != version) {
            builtVersion = version;
            build(passes);
        }
        int depthId = rc.depth.getID(), w = rc.tex.getWidthHW(), h = rc.tex.getHeightHW();
        if (depthId <= 0 || w <= 0 || h <= 0) return;
        zombie.core.textures.IGLFramebufferObject funcs = zombie.core.textures.TextureFBO.getFuncs();
        int prevProgram = GlGet.integer(GL20.GL_CURRENT_PROGRAM);
        int prevActive = GlGet.integer(GL13.GL_ACTIVE_TEXTURE);
        int prevVao = GlGet.integer(GL30.GL_VERTEX_ARRAY_BINDING);
        GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_VIEWPORT_BIT
            | GL11.GL_SCISSOR_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        funcs.glFramebufferTexture2D(funcs.GL_FRAMEBUFFER(), funcs.GL_DEPTH_ATTACHMENT(), GL11.GL_TEXTURE_2D, 0, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE6);
        int prevTex6 = GlGet.integer(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthId);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_ZERO, GL11.GL_SRC_COLOR, GL11.GL_ZERO, GL11.GL_ONE);
            GL11.glColorMask(true, true, true, true);
            GL11.glViewport(0, 0, w, h);
            GL30.glBindVertexArray(emptyVao);
            float scale = rc.highRes ? 2f : 1f;
            int min = rc.getMinLevel(), top = rc.getTopLevel();
            float topOffset = 192f * (top - min + 1) + zombie.iso.fboRenderChunk.FBORenderLevels.extraHeightForJumboTrees(min, top);
            int timer = GpuTimer.bakeBegin();
            for (int i = 0; i < programs.length; i++) {
                if (programs[i] == 0) continue;
                GL20.glUseProgram(programs[i]);
                if (locDepth[i] >= 0) GL20.glUniform1i(locDepth[i], 6);
                if (locSize[i] >= 0) GL20.glUniform4f(locSize[i], w, h, scale, 0f);
                if (locChunk[i] >= 0) GL20.glUniform4f(locChunk[i], rc.chunk.wx * 8, rc.chunk.wy * 8, min, topOffset);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            }
            GpuTimer.bakeEnd(timer);
            passesRun++;
        } finally {
            GL30.glBindVertexArray(prevVao);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex6);
            GL13.glActiveTexture(prevActive);
            GL20.glUseProgram(prevProgram);
            funcs.glFramebufferTexture2D(funcs.GL_FRAMEBUFFER(), funcs.GL_DEPTH_ATTACHMENT(), GL11.GL_TEXTURE_2D, depthId, 0);
            GL11.glPopAttrib();
        }
    }

    static volatile long passesRun;
}
