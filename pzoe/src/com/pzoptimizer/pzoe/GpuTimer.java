package com.pzoptimizer.pzoe;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import zombie.iso.IsoWater;

/**
 * GPU time of the visual effects, only with the game's -debug, for the settings cards. Render thread only.
 *
 * World: a timestamp at the first chunk draw of a frame and one where the final screen pass starts, so it covers the
 * chunk composite (lighting, shadows, fog...), puddles and everything else drawn into the world. Toggling a card and
 * watching this number is how to see what that card costs. Bake passes: elapsed time around them, summed per second.
 * Results are read a few frames later, only once the GPU says they're ready, so nothing waits on the GPU.
 */
public final class GpuTimer {
    static final String WATER_RENDERER = "zombie/core/skinnedmodel/ModelManager";

    private static final int RING = 4, BAKE_RING = 64, WATER_RING = 16;
    private static int[] qWorld, qScreen, qBake, qWaterStart, qWaterEnd;
    private static final boolean[] pending = new boolean[RING];
    private static final boolean[] bakePending = new boolean[BAKE_RING];
    private static final boolean[] waterPending = new boolean[WATER_RING];
    private static int frame, bakeNext, waterNext;
    private static boolean worldMarked, failed;
    private static long sumWorldNs, sumBakeNs, sumWaterNs, windowStart;
    private static int worldFrames;
    static volatile float worldMs, bakeMsPerSec, waterMsPerSec;
    // The array overloads: LWJGL's convenience getters wrap a new native segment on every call
    private static final int[] intOut = new int[1];
    private static final long[] longOut = new long[1];

    private GpuTimer() {}

    private static boolean ready() {
        if (failed || !zombie.core.Core.debug) return false;
        if (qWorld != null) return true;
        try {
            qWorld = new int[RING];
            qScreen = new int[RING];
            qBake = new int[BAKE_RING];
            qWaterStart = new int[WATER_RING];
            qWaterEnd = new int[WATER_RING];
            GL15.glGenQueries(qWorld);
            GL15.glGenQueries(qScreen);
            GL15.glGenQueries(qBake);
            GL15.glGenQueries(qWaterStart);
            GL15.glGenQueries(qWaterEnd);
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

    public static void waterGeometry(IsoWater water, int first, int count, boolean shore) {
        int slot = waterNext;
        if (!ready() || waterPending[slot]) {
            water.waterGeometry(first, count, shore);
            return;
        }
        GL33.glQueryCounter(qWaterStart[slot], GL33.GL_TIMESTAMP);
        try {
            water.waterGeometry(first, count, shore);
        } finally {
            GL33.glQueryCounter(qWaterEnd[slot], GL33.GL_TIMESTAMP);
            waterPending[slot] = true;
            waterNext = (slot + 1) % WATER_RING;
        }
    }

    static boolean patchWater(ClassNode node) {
        var method = AsmSupport.findMethod(node, "RenderWater", "(Lzombie/core/textures/TextureDraw;IIIIZ)V");
        if (method == null) return false;
        for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals("zombie/iso/IsoWater")
                && call.name.equals("waterGeometry") && call.desc.equals("(IIZ)V")) {
                method.instructions.set(call, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, "com/pzoptimizer/pzoe/GpuTimer", "waterGeometry",
                    "(Lzombie/iso/IsoWater;IIZ)V", false));
                return true;
            }
        }
        return false;
    }

    private static boolean available(int query) {
        GL15.glGetQueryObjectiv(query, GL15.GL_QUERY_RESULT_AVAILABLE, intOut);
        return intOut[0] != 0;
    }

    private static long result(int query) {
        GL33.glGetQueryObjecti64v(query, GL15.GL_QUERY_RESULT, longOut);
        return longOut[0];
    }

    private static void collect() {
        for (int i = 0; i < RING; i++) {
            if (!pending[i] || !available(qScreen[i])) continue;
            long t0 = result(qWorld[i]);
            long t1 = result(qScreen[i]);
            pending[i] = false;
            if (t1 > t0) {
                sumWorldNs += t1 - t0;
                worldFrames++;
            }
        }
        for (int i = 0; i < BAKE_RING; i++) {
            if (!bakePending[i] || !available(qBake[i])) continue;
            sumBakeNs += result(qBake[i]);
            bakePending[i] = false;
        }
        for (int i = 0; i < WATER_RING; i++) {
            if (!waterPending[i] || !available(qWaterEnd[i])) continue;
            sumWaterNs += Math.max(0, result(qWaterEnd[i]) - result(qWaterStart[i]));
            waterPending[i] = false;
        }
        long now = System.nanoTime();
        if (now - windowStart >= 1_000_000_000L) {
            double secs = (now - windowStart) / 1e9;
            worldMs = worldFrames > 0 ? (float) (sumWorldNs / 1e6 / worldFrames) : 0f;
            bakeMsPerSec = (float) (sumBakeNs / 1e6 / secs);
            waterMsPerSec = (float) (sumWaterNs / 1e6 / secs);
            sumWorldNs = sumBakeNs = sumWaterNs = 0;
            worldFrames = 0;
            windowStart = now;
        }
    }

    /** key=value pairs, microseconds, for PZOApi.getChunkEffects(); zeros outside -debug. */
    static String stateText() {
        return "gpuWorldUs=" + Math.round(worldMs * 1000f) + ";gpuBakeUs=" + Math.round(bakeMsPerSec * 1000f)
            + ";gpuWaterUs=" + Math.round(waterMsPerSec * 1000f);
    }
}
