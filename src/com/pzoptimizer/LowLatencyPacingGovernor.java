package com.pzoptimizer;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenGL Low-Latency & Reflex-Style Frame Pacing Governor.
 * Without explicit queue management, GPU-bound or VSync-capped OpenGL engines allow
 * the simulation thread to queue multiple complete command buffers ahead of the GPU,
 * resulting in severe input latency.
 * 
 * LowLatencyPacingGovernor introduces:
 * 1. OpenGL Sync Fences: Restricts outstanding unrendered GPU frames to at most 1 frame.
 * 2. Just-In-Time (JIT) Input Pacing: Delays input polling until immediately before the
 *    render thread has capacity, guaranteeing fresh input delivery with identical throughput.
 */
public final class LowLatencyPacingGovernor {

    private static volatile boolean active = true;
    private static volatile boolean reflexSleepActive = true;
    private static volatile int maxQueuedFrames = 1;

    public static final AtomicLong fencesSynced = new AtomicLong(0);
    public static final AtomicLong inputPacingDelaysApplied = new AtomicLong(0);

    private static final int RING_SIZE = 4;
    private static final long[] frameFences = new long[RING_SIZE];
    private static int fenceIndex = 0;

    private static Method glFenceSyncMethod = null;
    private static Method glClientWaitSyncMethod = null;
    private static Method glDeleteSyncMethod = null;
    private static boolean glSyncInitialized = false;

    private static synchronized void initSyncReflection() {
        if (glSyncInitialized) return;
        try {
            Class<?> gl32Class = Class.forName("org.lwjgl.opengl.GL32");
            // GL_SYNC_GPU_COMMANDS_COMPLETE = 0x9117 (37143)
            glFenceSyncMethod = gl32Class.getMethod("glFenceSync", int.class, int.class);
            // glClientWaitSync(sync, flags, timeoutNs)
            glClientWaitSyncMethod = gl32Class.getMethod("glClientWaitSync", long.class, int.class, long.class);
            glDeleteSyncMethod = gl32Class.getMethod("glDeleteSync", long.class);
        } catch (Throwable ignored) {}
        glSyncInitialized = true;
    }

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    public static void setReflexSleepActive(boolean value) {
        reflexSleepActive = value;
    }

    /**
     * Called on the render thread immediately after buffer swap.
     * Inserts an OpenGL sync fence and waits until the GPU has drained older queued frames.
     */
    public static void onPostSwapRenderThread() {
        if (!active) return;
        initSyncReflection();

        try {
            if (glFenceSyncMethod != null) {
                // Delete previous fence in this ring slot
                long oldFence = frameFences[fenceIndex];
                if (oldFence != 0L && glDeleteSyncMethod != null) {
                    glDeleteSyncMethod.invoke(null, oldFence);
                }

                // Insert new fence
                Object syncObj = glFenceSyncMethod.invoke(null, 37143, 0);
                if (syncObj instanceof Number) {
                    frameFences[fenceIndex] = ((Number) syncObj).longValue();
                }

                // Wait on oldest fence in the ring if queue exceeds maxQueuedFrames
                int waitSlot = (fenceIndex + RING_SIZE - maxQueuedFrames) % RING_SIZE;
                long fenceToWait = frameFences[waitSlot];
                if (fenceToWait != 0L && glClientWaitSyncMethod != null) {
                    // Wait up to 50ms with GL_SYNC_FLUSH_COMMANDS_BIT = 1
                    glClientWaitSyncMethod.invoke(null, fenceToWait, 1, 50_000_000L);
                    fencesSynced.incrementAndGet();
                }

                fenceIndex = (fenceIndex + 1) % RING_SIZE;
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Paces the game simulation thread to sample input as late as possible before the next frame.
     */
    public static void paceSimulationInput(long targetFrameNanos, long elapsedFrameNanos) {
        if (!active || !reflexSleepActive) return;
        long headroom = targetFrameNanos - elapsedFrameNanos;
        if (headroom > 1_500_000L) { // Sleep only if headroom > 1.5ms
            long sleepNanos = headroom - 1_000_000L; // Leave 1ms buffer
            long ms = sleepNanos / 1_000_000L;
            int ns = (int) (sleepNanos % 1_000_000L);
            try {
                Thread.sleep(ms, ns);
                inputPacingDelaysApplied.incrementAndGet();
            } catch (InterruptedException ignored) {}
        }
    }
}
