package com.pzoptimizer.pzoe;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import zombie.interfaces.ITexture;
import zombie.iso.fboRenderChunk.FBORenderChunk;

final class ChunkNormals {
    private static final int UNIT = 17, MAX_BYTES = 48 * 1024 * 1024;
    private record Target(int id, int w, int h) {
        int bytes() { return w * h * 4; }
    }
    private static final LinkedHashMap<ITexture, Target> cache = new LinkedHashMap<>(64, 0.75f, true);
    private static int bytes, fbo, vao, program, sizeLoc;
    private static boolean tried, wanted;

    private static final String FRAGMENT = """
        #version 130
        uniform sampler2D depthSource;
        uniform sampler2D artSource;
        uniform vec4 size;
        out vec4 result;

        vec3 positionAt(vec2 uv, float depth)
        {
            vec2 p = uv * size.xy;
            float sd = 20.0 - depth / 0.0014433542;
            float dr = p.x / 64.0;
            float z = (sd - p.y / 32.0) * 0.125;
            float s = sd - 2.0 * z;
            return vec3((s + dr) * 0.5, (s - dr) * 0.5, z * 2.45);
        }

        void main()
        {
            vec2 uv = gl_FragCoord.xy * size.zw;
            vec2 x = vec2(1.0 / size.x, 0.0), y = vec2(0.0, 1.0 / size.y);
            float d = texture2D(depthSource, uv).r;
            float dx1 = texture2D(depthSource, uv + x).r, dx0 = texture2D(depthSource, uv - x).r;
            float dy1 = texture2D(depthSource, uv + y).r, dy0 = texture2D(depthSource, uv - y).r;
            float sx = abs(dx1 - d) <= abs(dx0 - d) ? 1.0 : -1.0;
            float sy = abs(dy1 - d) <= abs(dy0 - d) ? 1.0 : -1.0;
            vec3 p = positionAt(uv, d);
            vec3 qx = (positionAt(uv + sx * x, sx > 0.0 ? dx1 : dx0) - p) * sx;
            vec3 qy = (positionAt(uv + sy * y, sy > 0.0 ? dy1 : dy0) - p) * sy;
            vec3 n = cross(qx, qy);
            if (dot(n, n) < 1e-9) n = vec3(0.0, 0.0, 1.0);
            if (dot(n, vec3(1.0)) < 0.0) n = -n;
            vec2 oct = n.xy / (abs(n.x) + abs(n.y) + abs(n.z));
            if (n.z < 0.0) oct = (1.0 - abs(oct.yx)) * mix(vec2(-1.0), vec2(1.0), step(vec2(0.0), oct));

            vec3 w = vec3(0.3, 0.59, 0.11);
            float l0 = dot(texture2D(artSource, uv).rgb, w);
            float lx = dot(texture2D(artSource, uv + sx * x).rgb, w);
            float ly = dot(texture2D(artSource, uv + sy * y).rgb, w);
            vec2 g = vec2((lx - l0) * sx, (ly - l0) * sy);
            g *= 1.0 - step(vec2(0.001), vec2(min(abs(dx1 - d), abs(dx0 - d)), min(abs(dy1 - d), abs(dy0 - d))));
            result = vec4(oct * 0.5 + 0.5, g * 0.5 + 0.5);
        }
        """;

    private ChunkNormals() {}

    static void setWanted(boolean value) {
        if (wanted == value) return;
        wanted = value;
        if (value) {
            BakePass.rebakeSoon = true;
        } else {
            for (Target target : cache.values()) GL11.glDeleteTextures(target.id);
            cache.clear();
            bytes = 0;
        }
    }

    static void bind(int sampler, int ready, ITexture depth) {
        Target target = depth == null ? null : cache.get(depth);
        GL20.glUniform1i(sampler, UNIT);
        GL20.glUniform1f(ready, target == null ? 0f : 1f);
        if (target == null) return;
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + UNIT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.id);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    static void bake(FBORenderChunk chunk) {
        if (!wanted || chunk.depth == null || chunk.tex == null || chunk.depth.getID() <= 0) return;
        if (!tried) build();
        if (program == 0) return;
        int sourceWidth = chunk.tex.getWidthHW(), sourceHeight = chunk.tex.getHeightHW();
        int w = Math.max(1, sourceWidth / 2), h = Math.max(1, sourceHeight / 2);
        if (w * h * 4 > MAX_BYTES) return;
        int oldFbo = GlGet.integer(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int oldVao = GlGet.integer(GL30.GL_VERTEX_ARRAY_BINDING);
        int oldProgram = GlGet.integer(GL20.GL_CURRENT_PROGRAM);
        int oldActive = GlGet.integer(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int old0 = GlGet.integer(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        int old1 = GlGet.integer(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_ENABLE_BIT | GL11.GL_VIEWPORT_BIT);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
        try {
            Target target = cache.get(chunk.depth);
            if (target != null && (target.w != w || target.h != h)) {
                discard(chunk.depth);
                target = null;
            }
            if (target == null) {
                while (bytes + w * h * 4 > MAX_BYTES) discard(cache.keySet().iterator().next());
                target = new Target(GL11.glGenTextures(), w, h);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.id);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                cache.put(chunk.depth, target);
                bytes += target.bytes();
            }
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, target.id, 0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                discard(chunk.depth);
                return;
            }
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glColorMask(true, true, true, true);
            GL11.glViewport(0, 0, w, h);
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vao);
            float scale = chunk.highRes ? 2f : 1f;
            GL20.glUniform4f(sizeLoc, sourceWidth / scale, sourceHeight / scale, 1f / w, 1f / h);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, chunk.tex.getID());
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, chunk.depth.getID());
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } finally {
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, oldFbo);
            GL30.glBindVertexArray(oldVao);
            GL20.glUseProgram(oldProgram);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, old0);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, old1);
            GL13.glActiveTexture(oldActive);
            GL11.glPopAttrib();
        }
    }

    private static void discard(ITexture depth) {
        Target old = cache.remove(depth);
        bytes -= old.bytes();
        GL11.glDeleteTextures(old.id);
    }

    private static void build() {
        tried = true;
        int vs = BakePass.compile(GL20.GL_VERTEX_SHADER, BakePass.VERTEX, "chunk normal vertex");
        int fs = BakePass.compile(GL20.GL_FRAGMENT_SHADER, FRAGMENT, "chunk normal fragment");
        if (vs != 0 && fs != 0) {
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vs);
            GL20.glAttachShader(program, fs);
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
                GL20.glDeleteProgram(program);
                program = 0;
            }
        }
        if (vs != 0) GL20.glDeleteShader(vs);
        if (fs != 0) GL20.glDeleteShader(fs);
        if (program == 0) return;
        fbo = GL30.glGenFramebuffers();
        vao = GL30.glGenVertexArrays();
        int old = GlGet.integer(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(program);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "depthSource"), 0);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "artSource"), 1);
        sizeLoc = GL20.glGetUniformLocation(program, "size");
        GL20.glUseProgram(old);
    }
}
