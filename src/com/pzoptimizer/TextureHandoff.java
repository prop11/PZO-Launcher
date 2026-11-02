package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;

/**
 * Faster texture loading (JVM_TextureHandoff). Only while no world is being played (boot, main menu, loading screen);
 * in game it's all vanilla.
 *
 * Textures are decoded on the file workers and the pixels then wait for the render thread to upload them. Two vanilla
 * limits leave the workers idle during a load:
 * - TextureIDAssetManager.waitFileTask makes every worker sleep in 20 ms steps while over 50 MB of decoded pixels is
 *   waiting. I raise the cap (128 MB with 8 GB of RAM, 256 MB with 16 GB) and wait in 2 ms steps.
 * - The render thread only uploads queued work when it wakes up for a new frame (SpriteRenderer.waitForReadyState waits
 *   on the frame states with no timeout), so a slow loading screen also means slow uploads. I give that wait a 2 ms
 *   timeout so the queue gets flushed as work comes in.
 * Pixels, upload order and the textures themselves don't change.
 */
public final class TextureHandoff {

    public static volatile boolean active = PZOConfig.isTextureHandoffEnabled();

    private static final long VANILLA_LIMIT = 52_428_800L;
    private static final long LOAD_LIMIT = loadLimit();

    private TextureHandoff() {}

    private static long loadLimit() {
        try {
            com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            long ramMb = os.getTotalMemorySize() >> 20;
            if (ramMb >= 15_000) return 256L << 20;
            if (ramMb >= 7_500) return 128L << 20;
        } catch (Throwable ignored) {}
        return VANILLA_LIMIT;
    }

    static boolean loading() {
        try {
            zombie.gameStates.GameStateMachine sm = zombie.GameWindow.states;
            return sm == null || !(sm.current instanceof zombie.gameStates.IngameState);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Prepended to TextureIDAssetManager.waitFileTask; true means I handled it and vanilla is skipped. */
    public static boolean waitFileTask(Object manager) {
        if (!active || !loading()) return false;
        while (zombie.core.utils.DirectBufferAllocator.getBytesAllocated() > LOAD_LIMIT) {
            try {
                Thread.sleep(2L);
            } catch (InterruptedException e) {
                break;
            }
        }
        return true;
    }

    /** Replaces states.wait() in SpriteRenderer.waitForReadyState (the caller holds the monitor). */
    public static void waitStates(Object states) throws InterruptedException {
        boolean loading = loading();
        if (active && loading) {
            states.wait(2L);
            return;
        }
        if (!loading && !statsLogged) {
            statsLogged = true;
            AnimImportCache.logStats();
            DepthMapParallel.logStats();
        }
        states.wait();
    }

    private static boolean statsLogged;

    static boolean patchAssetManager(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "waitFileTask", "()V");
        if (m == null) return false;
        AsmSupport.prependSkip(m, "com/pzoptimizer/TextureHandoff", "waitFileTask", "(Ljava/lang/Object;)Z");
        return true;
    }

    static boolean patchSpriteRenderer(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "waitForReadyState", "(Ljava/util/function/BooleanSupplier;)Z");
        if (m == null) return false;
        return AsmSupport.redirectCalls(m, "java/lang/Object", "wait", "()V",
            "com/pzoptimizer/TextureHandoff", "waitStates", "(Ljava/lang/Object;)V") == 1;
    }

    static {
        PZOLogger.info("[TextureHandoff] Pixels waiting for upload while loading: up to " + (LOAD_LIMIT >> 20) + " MB");
    }
}
