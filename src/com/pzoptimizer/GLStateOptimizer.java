package com.pzoptimizer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Project Zomboid Build 42 - Advanced OpenGL & Shader State Optimizer.
 * Eliminates thousands of redundant GPU uniform, matrix, texture, alpha, and depth calls per frame.
 */
public class GLStateOptimizer {
    public static volatile boolean enabled = true;

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    // Live Optimization Telemetry Counters
    public static final AtomicLong glCallsFiltered = new AtomicLong(0);
    public static final AtomicLong matricesSkipped = new AtomicLong(0);
    public static final AtomicLong uniformsSkipped = new AtomicLong(0);

    // 1. Texture & Color Caches
    private static int currentTexture = -1;
    private static float currentR = -1f, currentG = -1f, currentB = -1f, currentA = -1f;
    private static int currentSrcBlend = -1, currentDstBlend = -1;

    // 2. Alpha & Depth Caching (IndieGL hot loops)
    private static int lastAlphaFunc = -1;
    private static float lastAlphaRef = -1.0f;
    private static int lastDepthFunc = -1;
    private static int lastDepthMask = -1; // 0=false, 1=true

    // 3. Chunk Depth Shader Uniform Caching (DefaultShader)
    private static int chunkDepthLoc = -2;
    private static float cachedChunkDepth = Float.NaN;

    // 4. General Shader Uniform State Caching (256-entry uniform table)
    private static final int UNIFORM_TABLE_SIZE = 256;
    private static final float[] cachedUniform1f = new float[UNIFORM_TABLE_SIZE];
    private static final int[] cachedUniform1i = new int[UNIFORM_TABLE_SIZE];
    private static final float[][] cachedUniform4f = new float[UNIFORM_TABLE_SIZE][4];
    private static final boolean[] uniform1fValid = new boolean[UNIFORM_TABLE_SIZE];
    private static final boolean[] uniform1iValid = new boolean[UNIFORM_TABLE_SIZE];
    private static final boolean[] uniform4fValid = new boolean[UNIFORM_TABLE_SIZE];

    // 5. Skinned 3D Model Matrix Uniform Cache (1024-entry shader table)
    public static class ShaderMatrixState {
        public int uniformLoc = -2;
        public float[] lastMatrix = new float[16];
        public boolean initialized = false;
    }

    private static final ShaderMatrixState[] shaderCache = new ShaderMatrixState[1024];

    public static boolean shouldBindTexture(int textureId) {
        if (!enabled) return true;
        if (textureId == currentTexture) {
            glCallsFiltered.incrementAndGet();
            return false;
        }
        currentTexture = textureId;
        return true;
    }

    public static boolean shouldSetColor(float r, float g, float b, float a) {
        if (!enabled) return true;
        if (r == currentR && g == currentG && b == currentB && a == currentA) {
            glCallsFiltered.incrementAndGet();
            return false;
        }
        currentR = r;
        currentG = g;
        currentB = b;
        currentA = a;
        return true;
    }

    public static boolean shouldSetBlendFunc(int src, int dst) {
        if (!enabled) return true;
        if (src == currentSrcBlend && dst == currentDstBlend) {
            glCallsFiltered.incrementAndGet();
            return false;
        }
        currentSrcBlend = src;
        currentDstBlend = dst;
        return true;
    }

    public static boolean shouldSetAlphaFunc(int func, float ref) {
        if (!enabled) return true;
        if (func == lastAlphaFunc && ref == lastAlphaRef) {
            glCallsFiltered.incrementAndGet();
            return false;
        }
        lastAlphaFunc = func;
        lastAlphaRef = ref;
        return true;
    }

    public static boolean shouldSetDepthFunc(int func) {
        if (!enabled) return true;
        if (func == lastDepthFunc) {
            glCallsFiltered.incrementAndGet();
            return false;
        }
        lastDepthFunc = func;
        return true;
    }

    public static boolean shouldSetDepthMask(boolean mask) {
        if (!enabled) return true;
        int m = mask ? 1 : 0;
        if (m == lastDepthMask) {
            glCallsFiltered.incrementAndGet();
            return false;
        }
        lastDepthMask = m;
        return true;
    }

    public static boolean shouldUpdateChunkDepth(float depth) {
        if (!enabled) return true;
        if (depth == cachedChunkDepth) {
            uniformsSkipped.incrementAndGet();
            return false;
        }
        cachedChunkDepth = depth;
        return true;
    }

    public static boolean shouldSetUniform1f(int location, float val) {
        if (location < 0 || location >= UNIFORM_TABLE_SIZE) return true;
        if (uniform1fValid[location] && cachedUniform1f[location] == val) {
            uniformsSkipped.incrementAndGet();
            return false;
        }
        cachedUniform1f[location] = val;
        uniform1fValid[location] = true;
        return true;
    }

    public static boolean shouldSetUniform1i(int location, int val) {
        if (location < 0 || location >= UNIFORM_TABLE_SIZE) return true;
        if (uniform1iValid[location] && cachedUniform1i[location] == val) {
            uniformsSkipped.incrementAndGet();
            return false;
        }
        cachedUniform1i[location] = val;
        uniform1iValid[location] = true;
        return true;
    }

    public static boolean shouldSetUniform4f(int location, float x, float y, float z, float w) {
        if (location < 0 || location >= UNIFORM_TABLE_SIZE) return true;
        float[] c = cachedUniform4f[location];
        if (uniform4fValid[location] && c[0] == x && c[1] == y && c[2] == z && c[3] == w) {
            uniformsSkipped.incrementAndGet();
            return false;
        }
        c[0] = x; c[1] = y; c[2] = z; c[3] = w;
        uniform4fValid[location] = true;
        return true;
    }

    public static boolean shouldUpdateMatrix(int shaderId, float[] newMatrix) {
        if (shaderId < 0 || shaderId >= shaderCache.length || newMatrix == null || newMatrix.length < 16) {
            return true;
        }

        ShaderMatrixState state = shaderCache[shaderId];
        if (state == null) {
            state = new ShaderMatrixState();
            shaderCache[shaderId] = state;
        }

        if (!state.initialized) {
            System.arraycopy(newMatrix, 0, state.lastMatrix, 0, 16);
            state.initialized = true;
            return true;
        }

        // Fast float-by-float unrolled matrix comparison (Zero memory allocation)
        float[] last = state.lastMatrix;
        if (last[0] == newMatrix[0] && last[1] == newMatrix[1] && last[2] == newMatrix[2] && last[3] == newMatrix[3] &&
            last[4] == newMatrix[4] && last[5] == newMatrix[5] && last[6] == newMatrix[6] && last[7] == newMatrix[7] &&
            last[8] == newMatrix[8] && last[9] == newMatrix[9] && last[10] == newMatrix[10] && last[11] == newMatrix[11] &&
            last[12] == newMatrix[12] && last[13] == newMatrix[13] && last[14] == newMatrix[14] && last[15] == newMatrix[15]) {
            matricesSkipped.incrementAndGet();
            return false; // Matrix matches cached state, skip redundant GPU upload
        }

        System.arraycopy(newMatrix, 0, state.lastMatrix, 0, 16);
        return true;
    }

    public static void resetState() {
        currentTexture = -1;
        currentR = -1f;
        currentG = -1f;
        currentB = -1f;
        currentA = -1f;
        currentSrcBlend = -1;
        currentDstBlend = -1;
        lastAlphaFunc = -1;
        lastAlphaRef = -1.0f;
        lastDepthFunc = -1;
        lastDepthMask = -1;
        cachedChunkDepth = Float.NaN;
        for (int i = 0; i < UNIFORM_TABLE_SIZE; i++) {
            uniform1fValid[i] = false;
            uniform1iValid[i] = false;
            uniform4fValid[i] = false;
        }
    }

    public static long getGlCallsFiltered() { return glCallsFiltered.get(); }
    public static long getMatricesSkipped() { return matricesSkipped.get(); }
    public static long getUniformsSkipped() { return uniformsSkipped.get(); }
}
