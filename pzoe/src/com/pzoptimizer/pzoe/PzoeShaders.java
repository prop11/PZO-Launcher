package com.pzoptimizer.pzoe;

import com.pzoptimizer.PZOLogger;
import org.lwjgl.opengl.GL20;

/**
 * Builds PZOE's own GL programs through PZO's shader cache, so they come from disk after the first time. With an older
 * PZO engine that has no buildProgram they compile here as before. Render thread.
 */
final class PzoeShaders {

    private static volatile boolean noCache;

    private PzoeShaders() {}

    static int program(String vertex, String fragment, String what) {
        if (!noCache) {
            try {
                return com.pzoptimizer.ShaderCache.buildProgram(vertex, fragment, what);
            } catch (LinkageError e) {
                noCache = true;
            }
        }
        return compile(vertex, fragment, what);
    }

    private static int compile(String vertex, String fragment, String what) {
        int p = GL20.glCreateProgram();
        for (int k = 0; k < 2; k++) {
            int s = GL20.glCreateShader(k == 0 ? GL20.GL_VERTEX_SHADER : GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(s, k == 0 ? vertex : fragment);
            GL20.glCompileShader(s);
            if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
                PZOLogger.warn(what + " shader didn't compile: " + GL20.glGetShaderInfoLog(s, 4096).trim());
                GL20.glDeleteShader(s);
                GL20.glDeleteProgram(p);
                return 0;
            }
            GL20.glAttachShader(p, s);
            GL20.glDeleteShader(s);
        }
        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
            PZOLogger.warn(what + " shader didn't link: " + GL20.glGetProgramInfoLog(p, 4096).trim());
            GL20.glDeleteProgram(p);
            return 0;
        }
        return p;
    }
}
