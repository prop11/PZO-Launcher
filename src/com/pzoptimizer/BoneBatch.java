package com.pzoptimizer;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Zombie bone math on the frame workers.
 *
 * Stock AnimationPlayer.updateAnimation_StandardAnimation runs, per zombie and on the game thread: the per-bone
 * blend of every live track (updateMultiTrackBoneTransforms), the body angles, the deferred movement, the twist
 * bones, bone re-parenting, the model transforms and the skin transforms. Only the blend weights, the angles and the
 * deferred movement feed the rest of the zombie's update (movement reads them right away); they read the tracks and
 * never the bone results. So while the scheduler's postupdate runs, an eligible zombie does those three parts in
 * place as stock and queues the rest; when postupdate returns, the queue runs across the workers and the game thread.
 * Rendering then sees this frame's pose as before. Gameplay code that reads a queued zombie's bone pose during the
 * same postupdate loop sees the previous frame's pose (accepted trade-off).
 *
 * Eligible: a zombie with no parent player, no animation recorder and no ragdoll track. Anything else, and every
 * call outside the scheduler's postupdate or off the game thread, takes the stock path. A second update of a queued
 * player in the same loop first finishes the queued one inline; reset, release and setModel drop it from the queue.
 * Workers use their own scratch objects (PerThreadScratch). A failure on a worker turns this off for the session and
 * the affected zombies are redone on the game thread.
 */
public final class BoneBatch {

    /** Implemented by the patched AnimationPlayer with generated methods. */
    public interface Deferrable {
        int pzo$queueSlot();

        void pzo$setQueueSlot(int slot);

        void pzo$deferMain(float deltaT);

        void pzo$deferBones(float deltaT);
    }

    private static volatile boolean active = true;
    private static boolean collecting;
    private static Thread gameThread;

    private static final int MIN_PARALLEL = 8;

    // Game thread only, except that workers read entries while a batch runs
    private static Deferrable[] queue = new Deferrable[256];
    private static float[] deltas = new float[256];
    private static int count;

    public static final AtomicLong deferred = new AtomicLong();
    public static final AtomicLong batches = new AtomicLong();

    private BoneBatch() {}

    public static void setActive(boolean value) {
        active = value;
    }

    public static boolean isActive() {
        return active;
    }

    // MovingObjectUpdateScheduler.postupdate entry
    public static void begin() {
        if (collecting) flush();
        if (!active) return;
        if (gameThread == null) gameThread = Thread.currentThread();
        collecting = Thread.currentThread() == gameThread;
    }

    /** Prefix of updateAnimation_StandardAnimation: true when the bone work was queued and stock must return. */
    public static boolean tryDefer(Object player, float deltaT) {
        if (!collecting || !active || Thread.currentThread() != gameThread) return false;
        zombie.core.skinnedmodel.animation.AnimationPlayer p = (zombie.core.skinnedmodel.animation.AnimationPlayer) player;
        if (p.parentPlayer != null || p.isRecording() || p.isRagdolling()) return false;
        if (p.getMultiTrack().containsAnyRagdollTracks()) return false;
        if (!(p.getIsoGameCharacter() instanceof zombie.characters.IsoZombie)) return false;
        Deferrable d = (Deferrable) player;
        int slot = d.pzo$queueSlot();
        if (slot > 0) {
            // Updated twice in one loop: finish the queued frame first, as stock would have
            d.pzo$setQueueSlot(0);
            d.pzo$deferBones(deltas[slot - 1]);
        }
        d.pzo$deferMain(deltaT);
        if (count == queue.length) {
            queue = java.util.Arrays.copyOf(queue, count * 2);
            deltas = java.util.Arrays.copyOf(deltas, count * 2);
        }
        queue[count] = d;
        deltas[count] = deltaT;
        count++;
        d.pzo$setQueueSlot(count);
        return true;
    }

    // Prefix of AnimationPlayer.reset/onReleased/setModel: the player is being reused, its queued work is void
    public static void cancel(Object player) {
        Deferrable d = (Deferrable) player;
        if (d.pzo$queueSlot() > 0) d.pzo$setQueueSlot(0);
    }

    // MovingObjectUpdateScheduler.postupdate exit
    public static void flush() {
        collecting = false;
        int n = count;
        if (n == 0) return;
        Deferrable[] q = queue;
        float[] dt = deltas;
        if (n < MIN_PARALLEL || !active) {
            for (int i = 0; i < n; i++) runOne(q, dt, i);
        } else {
            Throwable failure = FrameWorkers.run(n, i -> runOne(q, dt, i));
            if (failure != null) {
                active = false;
                PZOLogger.warn("[BoneBatch] bone work failed on a worker, finishing on the game thread and switching off: " + failure);
                for (int i = 0; i < n; i++) {
                    if (q[i] != null) runOne(q, dt, i);
                }
            }
        }
        for (int i = 0; i < n; i++) q[i] = null;
        count = 0;
        batches.incrementAndGet();
        deferred.addAndGet(n);
    }

    private static void runOne(Deferrable[] q, float[] dt, int i) {
        Deferrable d = q[i];
        if (d == null) return;
        // A cancelled or re-queued player no longer owns this entry
        if (d.pzo$queueSlot() == i + 1) {
            d.pzo$deferBones(dt[i]);
            d.pzo$setQueueSlot(0);
        }
        q[i] = null;
    }
}
