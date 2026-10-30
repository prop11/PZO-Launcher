package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import zombie.WorldSoundManager;

/**
 * Settings screen "Clear expired noises only when one expired" (JVM_NoiseSweep).
 *
 * Every frame WorldSoundManager.update takes the sound lock once per loaded chunk (361 at 1080p) and walks that
 * chunk's noise list for entries whose life ran out, removing them one by one. A noise only runs out in
 * WorldSoundManager's own list (life counts down there, and the expired one is released one update later), and each
 * noise is added to its chunks and to that list together. So at the start of an update the chunk lists can only hold
 * an expired noise if the manager's list holds one; when it does not, the per-chunk walk is skipped. When it does,
 * each chunk list is compacted in one pass, in the same order.
 */
public final class NoiseListSweep {

    public static volatile boolean active = true;
    public static final AtomicLong framesSkipped = new AtomicLong();

    private static boolean expiredThisUpdate = true;

    private NoiseListSweep() {}

    private static boolean expired(WorldSoundManager.WorldSound s) {
        return s == null || s.life <= 0;
    }

    /** Start of WorldSoundManager.update. */
    public static void begin(WorldSoundManager mgr) {
        if (!active) {
            expiredThisUpdate = true;
            return;
        }
        java.util.List<WorldSoundManager.WorldSound> list = mgr.soundList;
        boolean any = false;
        synchronized (list) {
            for (int i = 0, n = list.size(); i < n; i++) {
                if (expired(list.get(i))) {
                    any = true;
                    break;
                }
            }
        }
        expiredThisUpdate = any;
        if (!any) framesSkipped.incrementAndGet();
    }

    /** Replaces chunk.updateSounds() in WorldSoundManager.update. */
    public static void sweep(zombie.iso.IsoChunk chunk) {
        if (!active) {
            chunk.updateSounds();
            return;
        }
        if (!expiredThisUpdate) return;
        synchronized (WorldSoundManager.instance.soundList) {
            ArrayList<WorldSoundManager.WorldSound> list = chunk.soundList;
            if (!list.isEmpty()) list.removeIf(NoiseListSweep::expired);
        }
    }

    static boolean patchManager(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "update", "()V");
        if (m == null) return false;
        if (AsmSupport.redirectCalls(m, "zombie/iso/IsoChunk", "updateSounds", "()V",
                "com/pzoptimizer/NoiseListSweep", "sweep", "(Lzombie/iso/IsoChunk;)V") != 1) return false;
        AsmSupport.prependCall(m, "com/pzoptimizer/NoiseListSweep", "begin", "(Lzombie/WorldSoundManager;)V");
        return true;
    }
}
