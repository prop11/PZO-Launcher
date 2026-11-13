package com.pzoptimizer.pzoe;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/**
 * GPU time of the visual effects, only with the game's -debug, for the settings cards. Render thread only.
 *
 * World: a timestamp at the first chunk draw of a frame and one where the final screen pass starts, so it covers the
 * chunk composite (lighting, shadows, fog...), puddles and everything else drawn into the world. Toggling a card and
 * watching this number is how to see what that card costs. Bake passes: elapsed time around them, summed per second.
 * Results are read a few frames later, only once the GPU says they're ready, so nothing waits on the GPU.
 */
final class GpuTimer {

    private static final int RING = 4, BAKE_RING = 64;
    private static int[] qWorld, qScreen, qBake;
    private static final boolean[] pending = new boolean[RING];
    private static final boolean[] bakePending = new boolean[BAKE_RING];
    private static int frame, bakeNext;
    private static boolean worldMarked, failed;
    private static long sumWorldNs, sumBakeNs, windowStart;
    private static int worldFrames;
    static volatile float worldMs, bakeMsPerSec;

    private GpuTimer() {}

    private static boolean ready() {
        if (failed || !zombie.core.Core.debug) return false;
        if (qWorld != null) return true;
        try {
            qWorld = new int[RING];
            qScreen = new int[RING];
            qBake = new int[BAKE_RING];
            GL15.glGenQueries(qWorld);
            GL15.glGenQueries(qScreen);
            GL15.glGenQueries(qBake);
            windowStart = System.nanoTime();
            return true;
        } catch (Throwable t) {
            failed = true;
            return false;
        }
    }

    /** Start of a chunk draw (PixelLight.chunkDraw). */
    static void chunkDraw() {
        if (worldMarked || !ready() || pending[frame]) return;
        GL33.glQueryCounter(qWorld[frame], GL33.GL_TIMESTAMP);
        worldMarked = true;
    }

    /** Start of the final screen pass (ScreenPass.render). */
    static void screenPass() {
        if (!ready()) return;
        if (worldMarked) {
            GL33.glQueryCounter(qScreen[frame], GL33.GL_TIMESTAMP);
            pending[frame] = true;
            worldMarked = false;
            frame = (frame + 1) % RING;
        }
        collect();
    }

    static int bakeBegin() {
        if (!ready()) return -1;
        int i = bakeNext;
        if (bakePending[i]) return -1;
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, qBake[i]);
        bakeNext = (i + 1) % BAKE_RING;
        return i;
    }

    static void bakeEnd(int i) {
        if (i < 0) return;
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        bakePending[i] = true;
    }

    private static void collect() {
        for (int i = 0; i < RING; i++) {
            if (!pending[i] || GL15.glGetQueryObjecti(qScreen[i], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) continue;
            long t0 = GL33.glGetQueryObjecti64(qWorld[i], GL15.GL_QUERY_RESULT);
            long t1 = GL33.glGetQueryObjecti64(qScreen[i], GL15.GL_QUERY_RESULT);
            pending[i] = false;
            if (t1 > t0) {
                sumWorldNs += t1 - t0;
                worldFrames++;
            }
        }
        for (int i = 0; i < BAKE_RING; i++) {
            if (!bakePending[i] || GL15.glGetQueryObjecti(qBake[i], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) continue;
            sumBakeNs += GL33.glGetQueryObjecti64(qBake[i], GL15.GL_QUERY_RESULT);
            bakePending[i] = false;
        }
        long now = System.nanoTime();
        if (now - windowStart >= 1_000_000_000L) {
            double secs = (now - windowStart) / 1e9;
            worldMs = worldFrames > 0 ? (float) (sumWorldNs / 1e6 / worldFrames) : 0f;
            bakeMsPerSec = (float) (sumBakeNs / 1e6 / secs);
            sumWorldNs = sumBakeNs = 0;
            worldFrames = 0;
            windowStart = now;
        }
    }

    /** key=value pairs, microseconds, for PZOApi.getChunkEffects(); zeros outside -debug. */
    static String stateText() {
        return "gpuWorldUs=" + Math.round(worldMs * 1000f) + ";gpuBakeUs=" + Math.round(bakeMsPerSec * 1000f);
    }
}
