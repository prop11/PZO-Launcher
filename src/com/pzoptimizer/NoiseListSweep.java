package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import zombie.WorldSoundManager;

/**
 * Only clears expired noises when one actually expired (JVM_NoiseSweep).
 *
 * Every frame WorldSoundManager.update takes the sound lock once per loaded chunk (361 at 1080p) and walks each chunk's
 * noise list removing expired entries one by one. But a noise only expires in WorldSoundManager's own list (that's
 * where its life counts down), and every noise goes into its chunks and that list together. So the chunk lists can only
 * have an expired noise if the manager's list does. If it doesn't, I skip the per-chunk walk; if it does, each chunk
 * list is compacted in one pass, same order.
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
