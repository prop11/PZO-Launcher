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
 * Render thread timing for benchmarks and the profiler. Each render frame in RenderThread.lockStepRenderStep waits for
 * the main thread's frame state, submits it (SpriteRenderer.postRender) and presents (Display.update). With vsync off
 * the driver only blocks in present when the GPU is behind, so a scene dominated by present time is GPU-bound and one
 * dominated by the wait is main-thread-bound.
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
        if (!recording) {
            renderer.postRender();
            return;
        }
        submitStart = System.nanoTime();
        renderer.postRender();
        if (recording) {
            submitNs += System.nanoTime() - submitStart;
            waitNs += RenderThread.getWaitTime();
        }
    }

    public static void displayUpdate(boolean processMessages) {
        // Installed for everyone; with no benchmark or profiler running it's just the stock call
        if (!recording && !LiveProfiler.on) {
            org.lwjglx.opengl.Display.update(processMessages);
            return;
        }
        long t = System.nanoTime();
        org.lwjglx.opengl.Display.update(processMessages);
        if (LiveProfiler.on) {
            // Only the render thread writes; the profiler reads the pair on the game thread
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

    // Main thread calls this; the render thread only reads the flag, and the totals are read after it's cleared
    public static synchronized void start() {
        frames = 0;
        waitNs = submitNs = presentNs = 0;
        recording = true;
    }

    /** Stops recording and returns per-frame averages as JSON, or null if no frames came in. */
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
