package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.util.Arrays;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.RenderThread;

/**
 * Benchmark-only render thread timing. Each render frame in RenderThread.lockStepRenderStep is: wait for the main
 * thread's frame state, submit it to OpenGL (SpriteRenderer.postRender), then present (Display.update). With vsync
 * off the driver blocks in present only when the GPU is behind, so present time is the GPU-bound signal: a scene
 * where it dominates is limited by the GPU, one where the wait dominates is limited by the main thread.
 */
public final class GpuFrameTimer {

    private static volatile boolean recording;
    private static long submitStart;
    private static int frames;
    private static long waitNs, submitNs, presentNs;
    private static long[] presentSamples = new long[4096];
    // Present time for LiveProfiler, kept while it measures
    static volatile long presentTotalNs, presentCount;

    private GpuFrameTimer() {}

    public static void postRender(SpriteRenderer renderer) {
        submitStart = System.nanoTime();
        renderer.postRender();
        if (recording) {
            submitNs += System.nanoTime() - submitStart;
            waitNs += RenderThread.getWaitTime();
        }
    }

    public static void displayUpdate(boolean processMessages) {
        long t = System.nanoTime();
        org.lwjglx.opengl.Display.update(processMessages);
        if (LiveProfiler.on) {
            // Render thread is the only writer; the profiler reads the pair on the game thread
            presentTotalNs += System.nanoTime() - t;
            presentCount++;
        }
        if (recording) {
            long d = System.nanoTime() - t;
            presentNs += d;
            if (frames == presentSamples.length) presentSamples = Arrays.copyOf(presentSamples, frames * 2);
            presentSamples[frames++] = d;
        }
    }

    // Called from the main thread; the render thread only reads the flag, and totals are read after it is cleared
    public static synchronized void start() {
        frames = 0;
        waitNs = submitNs = presentNs = 0;
        recording = true;
    }

    /** Stops recording and returns a JSON object with per-frame averages, or null if no frames were seen. */
    public static synchronized String stopJson() {
        recording = false;
        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {
        }
        int n = frames;
        if (n == 0) return null;
        long[] p = Arrays.copyOf(presentSamples, n);
        Arrays.sort(p);
        return String.format(java.util.Locale.ROOT,
            "{\"frames\": %d, \"wait_ms\": %.3f, \"submit_ms\": %.3f, \"present_ms\": %.3f, \"present_p99_ms\": %.3f}",
            n, waitNs / 1e6 / n, submitNs / 1e6 / n, presentNs / 1e6 / n, p[Math.min(n - 1, (int) (n * 0.99))] / 1e6);
    }

    static boolean patch(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "lockStepRenderStep", "()Z");
        if (m == null) return false;
        int n = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode mi)) continue;
            MethodInsnNode repl = null;
            if (mi.getOpcode() == Opcodes.INVOKESTATIC && "org/lwjglx/opengl/Display".equals(mi.owner)
                    && "update".equals(mi.name) && "(Z)V".equals(mi.desc)) {
                repl = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/GpuFrameTimer", "displayUpdate", "(Z)V", false);
            } else if (mi.getOpcode() == Opcodes.INVOKEVIRTUAL && "zombie/core/SpriteRenderer".equals(mi.owner)
                    && "postRender".equals(mi.name) && "()V".equals(mi.desc)) {
                repl = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/GpuFrameTimer", "postRender",
                    "(Lzombie/core/SpriteRenderer;)V", false);
            }
            if (repl != null) {
                m.instructions.set(mi, repl);
                insn = repl;
                n++;
            }
        }
        return n == 2;
    }
}
