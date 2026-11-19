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
 *
 * In game the vanilla 50 MB wait stays, except it no longer waits forever when the pixels never drain (see waitInGame).
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
        if (active && loading()) {
            while (zombie.core.utils.DirectBufferAllocator.getBytesAllocated() > LOAD_LIMIT) {
                try {
                    Thread.sleep(2L);
                } catch (InterruptedException e) {
                    break;
                }
            }
            return true;
        }
        waitInGame();
        return true;
    }

    private static final long STALL_NS = 2_000_000_000L;
    private static volatile boolean stallLogged;
    private static final java.util.concurrent.atomic.AtomicLong stallsSkipped = new java.util.concurrent.atomic.AtomicLong();

    // Vanilla's 50 MB wait, but it gives up once the total stops dropping for 2 s. Vanilla waits forever, so anything
    // holding over 50 MB of pixels (issue #75, after world map use) froze every texture load and hung quit on
    // ImagePyramid.destroy. Below the cap this is the same single getBytesAllocated call vanilla makes.
    private static void waitInGame() {
        long bytes = zombie.core.utils.DirectBufferAllocator.getBytesAllocated();
        if (bytes <= VANILLA_LIMIT) return;
        long lowest = bytes;
        long since = System.nanoTime();
        while (bytes > VANILLA_LIMIT) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                return;
            }
            bytes = zombie.core.utils.DirectBufferAllocator.getBytesAllocated();
            if (bytes < lowest) {
                lowest = bytes;
                since = System.nanoTime();
            } else if (System.nanoTime() - since > STALL_NS) {
                onStall(bytes);
                return;
            }
        }
    }

    private static void onStall(long bytes) {
        long n = stallsSkipped.incrementAndGet();
        if (stallLogged) return;
        stallLogged = true;
        PZOLogger.warn("[TextureHandoff] Texture loads were stuck behind " + (bytes >> 20)
            + " MB of undrained pixel buffers (vanilla cap 50 MB); letting loads through. Live buffers: " + liveBuffers()
            + " (stall " + n + ", later ones not logged)");
    }

    // Size histogram of DirectBufferAllocator's live buffers, to see who holds them
    private static String liveBuffers() {
        try {
            Class<?> c = zombie.core.utils.DirectBufferAllocator.class;
            java.lang.reflect.Field lockField = c.getDeclaredField("LOCK");
            java.lang.reflect.Field allField = c.getDeclaredField("ALL");
            lockField.setAccessible(true);
            allField.setAccessible(true);
            java.util.TreeMap<Integer, Integer> sizes = new java.util.TreeMap<>(java.util.Collections.reverseOrder());
            synchronized (lockField.get(null)) {
                for (Object o : (java.util.List<?>) allField.get(null)) {
                    zombie.core.utils.WrappedBuffer w = (zombie.core.utils.WrappedBuffer) o;
                    if (w != null && !w.isDisposed()) sizes.merge(w.capacity(), 1, Integer::sum);
                }
            }
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (java.util.Map.Entry<Integer, Integer> e : sizes.entrySet()) {
                if (shown++ == 12) {
                    sb.append(", ...");
                    break;
                }
                if (sb.length() > 0) sb.append(", ");
                sb.append(e.getValue()).append(" x ").append(e.getKey() >> 10).append(" KB");
            }
            return sb.length() == 0 ? "none" : sb.toString();
        } catch (Throwable t) {
            return "unavailable (" + t + ")";
        }
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
