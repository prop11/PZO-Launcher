package com.pzoptimizer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.IdentityHashMap;
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
 *
 * The patched AnimationPlayer gains no members, since ZombieBuddy drops a whole batch of its mods' patches when one
 * class in it was reshaped: both halves call the player's own methods through method handles, the bone pass skips
 * its blend-weight call through a per-thread switch, and queue membership is kept here.
 */
public final class BoneBatch {

    private static volatile boolean active = true;
    private static boolean collecting;
    private static Thread gameThread;

    private static final int MIN_PARALLEL = 8;

    // AnimationPlayer's own steps; loaded on first use, after the class rewrite is done
    private static final class Steps {
        static final MethodHandle BLEND, ANGLES, DEFERRED_MOVEMENT, READY, BONES, TWIST, REPARENT, MODEL;
        static {
            MethodHandle[] h = new MethodHandle[8];
            try {
                Class<?> ap = zombie.core.skinnedmodel.animation.AnimationPlayer.class;
                MethodHandles.Lookup l = MethodHandles.privateLookupIn(ap, MethodHandles.lookup());
                MethodType v = MethodType.methodType(void.class), vf = MethodType.methodType(void.class, float.class);
                MethodType ov = MethodType.methodType(void.class, Object.class), ofv = MethodType.methodType(void.class, Object.class, float.class);
                h[0] = l.findVirtual(ap, "updateLayerBlendWeightings", v).asType(ov);
                h[1] = l.findVirtual(ap, "DoAngles", vf).asType(ofv);
                h[2] = l.findVirtual(ap, "calculateDeferredMovement", v).asType(ov);
                h[3] = l.findVirtual(ap, "isReady", MethodType.methodType(boolean.class)).asType(MethodType.methodType(boolean.class, Object.class));
                h[4] = l.findVirtual(ap, "updateMultiTrackBoneTransformsInternal", vf).asType(ofv);
                h[5] = l.findVirtual(ap, "updateTwistBone", v).asType(ov);
                h[6] = l.findVirtual(ap, "applyBoneReParenting", v).asType(ov);
                h[7] = l.findVirtual(ap, "updateModelTransforms", v).asType(ov);
            } catch (Throwable t) {
                PZOLogger.warn("[BoneBatch] AnimationPlayer steps not as expected, bone batch off: " + t);
                h = new MethodHandle[8];
            }
            BLEND = h[0];
            ANGLES = h[1];
            DEFERRED_MOVEMENT = h[2];
            READY = h[3];
            BONES = h[4];
            TWIST = h[5];
            REPARENT = h[6];
            MODEL = h[7];
        }
    }

    // Set while a queued player's bone pass runs: the game thread already did its blend weights
    private static final ThreadLocal<boolean[]> SKIP_BLEND = ThreadLocal.withInitial(() -> new boolean[1]);

    // Game thread only, except that workers read entries while a batch runs; live[i]: entry i still owns its player
    private static Object[] queue = new Object[256];
    private static float[] deltas = new float[256];
    private static boolean[] live = new boolean[256];
    private static int count;
    private static final IdentityHashMap<Object, Integer> queued = new IdentityHashMap<>();

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
    public static boolean tryDefer(Object player, float deltaT) throws Throwable {
        if (!collecting || !active || Thread.currentThread() != gameThread || Steps.MODEL == null) return false;
        zombie.core.skinnedmodel.animation.AnimationPlayer p = (zombie.core.skinnedmodel.animation.AnimationPlayer) player;
        if (p.parentPlayer != null || p.isRecording() || p.isRagdolling()) return false;
        if (p.getMultiTrack().containsAnyRagdollTracks()) return false;
        if (!(p.getIsoGameCharacter() instanceof zombie.characters.IsoZombie z) || RealLimbsCompat.isBody(z)) return false;
        Integer slot = queued.get(player);
        if (slot != null && live[slot]) {
            // Updated twice in one loop: finish the queued frame first, as stock would have
            live[slot] = false;
            deferBones(player, deltas[slot]);
        }
        Steps.BLEND.invokeExact(player);
        Steps.ANGLES.invokeExact(player, deltaT);
        Steps.DEFERRED_MOVEMENT.invokeExact(player);
        if (count == queue.length) {
            queue = java.util.Arrays.copyOf(queue, count * 2);
            deltas = java.util.Arrays.copyOf(deltas, count * 2);
            live = java.util.Arrays.copyOf(live, count * 2);
        }
        queue[count] = player;
        deltas[count] = deltaT;
        live[count] = true;
        queued.put(player, count);
        count++;
        return true;
    }

    // Prefix of AnimationPlayer.reset/onReleased/setModel: the player is being reused, its queued work is void
    public static void cancel(Object player) {
        if (count == 0) return;
        Integer slot = queued.remove(player);
        if (slot != null) live[slot] = false;
    }

    /** Replaces updateLayerBlendWeightings() inside updateMultiTrackBoneTransformsInternal. */
    public static void blendWeightings(Object player) throws Throwable {
        if (SKIP_BLEND.get()[0]) return;
        Steps.BLEND.invokeExact(player);
    }

    // The bone half of updateAnimation_StandardAnimation: bones without the blend call, twist, reparent, model, skin
    private static void deferBones(Object player, float deltaT) throws Throwable {
        if (!(boolean) Steps.READY.invokeExact(player)) return;
        boolean[] skip = SKIP_BLEND.get();
        skip[0] = true;
        try {
            Steps.BONES.invokeExact(player, deltaT);
        } finally {
            skip[0] = false;
        }
        Steps.TWIST.invokeExact(player);
        Steps.REPARENT.invokeExact(player);
        Steps.MODEL.invokeExact(player);
        updateSkinTransforms(player);
    }

    // MovingObjectUpdateScheduler.postupdate exit
    public static void flush() {
        collecting = false;
        int n = count;
        if (n == 0) return;
        Object[] q = queue;
        float[] dt = deltas;
        Throwable failure = null;
        if (n < MIN_PARALLEL || !active) {
            try {
                for (int i = 0; i < n; i++) runOne(q, dt, i);
            } catch (Throwable t) {
                failure = t;
            }
        } else {
            failure = FrameWorkers.run(n, i -> runOne(q, dt, i));
        }
        if (failure != null) {
            active = false;
            PZOLogger.warn("[BoneBatch] bone work failed, finishing on the game thread and switching off: " + failure);
            for (int i = 0; i < n; i++) {
                if (q[i] == null) continue;
                try {
                    runOne(q, dt, i);
                } catch (Throwable t) {
                    PZOLogger.warn("[BoneBatch] bone work failed on the game thread too: " + t);
                    q[i] = null;
                }
            }
        }
        for (int i = 0; i < n; i++) {
            q[i] = null;
            live[i] = false;
        }
        queued.clear();
        count = 0;
        batches.incrementAndGet();
        deferred.addAndGet(n);
    }

    public static volatile boolean skinPrecompute = true;
    public static final java.util.concurrent.atomic.LongAdder skinsPrecomputed = new java.util.concurrent.atomic.LongAdder();
    private static final int MAX_SKINS = 8;

    // AnimationPlayer's skin cache, read reflectively; loaded on first use, after the class rewrites are done
    private static final class SkinHandles {
        static final java.lang.invoke.VarHandle DATA, SKINNED_TO, NEXT;
        static {
            java.lang.invoke.VarHandle d = null, s = null, n = null;
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                Class<?> ap = zombie.core.skinnedmodel.animation.AnimationPlayer.class;
                Class<?> std = Class.forName("zombie.core.skinnedmodel.animation.AnimationPlayer$SkinTransformData");
                d = MethodHandles.privateLookupIn(ap, l).findVarHandle(ap, "skinTransformData", std);
                MethodHandles.Lookup sl = MethodHandles.privateLookupIn(std, l);
                s = sl.findVarHandle(std, "skinnedTo", zombie.core.skinnedmodel.model.SkinningData.class);
                n = sl.findVarHandle(std, "next", std);
            } catch (Throwable t) {
                PZOLogger.warn("[BoneBatch] skin cache not as expected, skin precompute off: " + t);
                skinPrecompute = false;
            }
            DATA = d;
            SKINNED_TO = s;
            NEXT = n;
        }
    }

    /**
     * Replaces UpdateSkinTransforms() at the end of the deferred bone work. Stock resets the player's per-skeleton
     * skin cache here and the renderer rebuilds each entry on the game thread (getSkinTransforms: one matrix
     * multiply per bone) when it prepares the model. The skeletons cached before the reset are the ones the
     * renderer asked for last frame, so they are rebuilt right here on the worker; the renderer then finds them
     * current. A skeleton not seen last frame is still built by the renderer as stock.
     */
    public static void updateSkinTransforms(Object player) {
        zombie.core.skinnedmodel.animation.AnimationPlayer p = (zombie.core.skinnedmodel.animation.AnimationPlayer) player;
        if (!skinPrecompute || SkinHandles.DATA == null) {
            p.UpdateSkinTransforms();
            return;
        }
        Object[] skins = new Object[MAX_SKINS];
        int n = 0;
        for (Object d = SkinHandles.DATA.get(p); d != null && n < MAX_SKINS; d = SkinHandles.NEXT.get(d)) {
            Object s = SkinHandles.SKINNED_TO.get(d);
            if (s != null) skins[n++] = s;
        }
        p.UpdateSkinTransforms();
        for (int i = 0; i < n; i++) {
            p.getSkinTransforms((zombie.core.skinnedmodel.model.SkinningData) skins[i]);
        }
        if (n > 0) skinsPrecomputed.add(n);
    }

    private static void runOne(Object[] q, float[] dt, int i) throws Throwable {
        Object d = q[i];
        if (d == null) return;
        // A cancelled or re-queued player no longer owns this entry
        if (live[i]) {
            deferBones(d, dt[i]);
            live[i] = false;
        }
        q[i] = null;
    }
}
