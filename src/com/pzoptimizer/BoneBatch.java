package com.pzoptimizer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Zombie bone math on the frame workers.
 *
 * Stock runs all of updateAnimation_StandardAnimation per zombie on the game thread: track blending, body angles,
 * deferred movement, twist bones, reparenting, model and skin transforms. The rest of the zombie's update only needs
 * the blend weights, angles and deferred movement, and none of those read the bone results. So during the scheduler's
 * postupdate an eligible zombie does those three in place and queues the rest, and the queue runs across the workers
 * when postupdate returns. Rendering still gets this frame's pose. Anything reading a queued zombie's bones during the
 * same loop sees last frame's pose, which is fine.
 *
 * Eligible means no parent player, no animation recorder and no ragdoll track. Everything else, and any call off the
 * game thread or outside postupdate, goes the stock way. A second update of a queued player finishes the queued one
 * first, and reset/release/setModel drop it. Workers use PerThreadScratch. If a worker fails this turns itself off
 * for the session and those zombies are redone on the game thread.
 *
 * AnimationPlayer gets no new members, because ZombieBuddy drops a whole batch of its mods' patches when one class in
 * it changes shape. So I call its methods through handles, skip the blend call with a per-thread flag and keep the
 * queue here.
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
    public static final AtomicLong checks = new AtomicLong(), mismatches = new AtomicLong();

    // The bone pass itself can't be re-run to compare (nextFrame overwrites each bone's previous transform), but the
    // skin matrices a worker precomputed are a pure function of the model transforms, so one queued zombie in
    // CHECK_EVERY gets them recomputed on the game thread after the batch. After MAX_MISMATCHES the batch switches off.
    private static final int CHECK_EVERY = 64, MAX_MISMATCHES = 3;
    private static final Object[] checkSample = new Object[64];

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
            int sampled = 0;
            long b = batches.get();
            for (int i = (int) (b % CHECK_EVERY); i < n && sampled < checkSample.length; i += CHECK_EVERY) {
                if (live[i]) checkSample[sampled++] = q[i];
            }
            failure = FrameWorkers.run(n, i -> runOne(q, dt, i));
            for (int i = 0; i < sampled; i++) {
                if (failure == null) checkSkins(checkSample[i]);
                checkSample[i] = null;
            }
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
     * Replaces UpdateSkinTransforms() at the end of the deferred bone work. Stock clears the skin cache here and the
     * renderer rebuilds each entry on the game thread. The skeletons cached before the clear are the ones drawn last
     * frame, so rebuild those here on the worker; anything new is still built by the renderer like stock.
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

    private static final class CheckHandles {
        static final java.lang.invoke.VarHandle MODEL, TRANSFORMS, DIRTY, BONE_MAP;
        static {
            java.lang.invoke.VarHandle m = null, t = null, d = null, bm = null;
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                Class<?> ap = zombie.core.skinnedmodel.animation.AnimationPlayer.class;
                Class<?> std = Class.forName("zombie.core.skinnedmodel.animation.AnimationPlayer$SkinTransformData");
                m = MethodHandles.privateLookupIn(ap, l).findVarHandle(ap, "modelTransforms", org.lwjgl.util.vector.Matrix4f[].class);
                MethodHandles.Lookup sl = MethodHandles.privateLookupIn(std, l);
                t = sl.findVarHandle(std, "transforms", org.lwjgl.util.vector.Matrix4f[].class);
                d = sl.findVarHandle(std, "dirty", boolean.class);
                bm = sl.findVarHandle(std, "boneMap", int[].class);
            } catch (Throwable e) {
                PZOLogger.warn("[BoneBatch] skin check handles not found, check off: " + e);
            }
            MODEL = m;
            TRANSFORMS = t;
            DIRTY = d;
            BONE_MAP = bm;
        }
    }

    // Game thread, after the batch: every skin the worker built must match getSkinTransforms' maths exactly
    private static void checkSkins(Object player) {
        if (player == null || !skinPrecompute || SkinHandles.DATA == null || CheckHandles.BONE_MAP == null) return;
        try {
            org.lwjgl.util.vector.Matrix4f[] model = (org.lwjgl.util.vector.Matrix4f[]) CheckHandles.MODEL.get(player);
            org.lwjgl.util.vector.Matrix4f expect = new org.lwjgl.util.vector.Matrix4f();
            for (Object d = SkinHandles.DATA.get(player); d != null; d = SkinHandles.NEXT.get(d)) {
                zombie.core.skinnedmodel.model.SkinningData to = (zombie.core.skinnedmodel.model.SkinningData) SkinHandles.SKINNED_TO.get(d);
                int[] map = (int[]) CheckHandles.BONE_MAP.get(d);
                if (to == null || map == null || (boolean) CheckHandles.DIRTY.get(d)) continue;
                org.lwjgl.util.vector.Matrix4f[] got = (org.lwjgl.util.vector.Matrix4f[]) CheckHandles.TRANSFORMS.get(d);
                checks.incrementAndGet();
                for (int bone = 0; bone < model.length && bone < map.length; bone++) {
                    int boneTo = map[bone];
                    if (boneTo == -1) continue;
                    if (to.boneOffset != null && to.boneOffset.get(boneTo) != null) {
                        org.lwjgl.util.vector.Matrix4f.mul(to.boneOffset.get(boneTo), model[bone], expect);
                    } else {
                        expect.setIdentity();
                    }
                    if (!sameMatrix(expect, got[boneTo])) {
                        long m = mismatches.incrementAndGet();
                        PZOLogger.warn("[BoneBatch] precomputed skin matrix differs from a game-thread recompute (bone " + bone + ")"
                            + (m >= MAX_MISMATCHES ? ", bone batch off for this session" : ""));
                        if (m >= MAX_MISMATCHES) active = false;
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            PZOLogger.warn("[BoneBatch] skin check failed: " + t);
        }
    }

    private static boolean sameMatrix(org.lwjgl.util.vector.Matrix4f a, org.lwjgl.util.vector.Matrix4f b) {
        return Float.floatToIntBits(a.m00) == Float.floatToIntBits(b.m00) && Float.floatToIntBits(a.m01) == Float.floatToIntBits(b.m01)
            && Float.floatToIntBits(a.m02) == Float.floatToIntBits(b.m02) && Float.floatToIntBits(a.m03) == Float.floatToIntBits(b.m03)
            && Float.floatToIntBits(a.m10) == Float.floatToIntBits(b.m10) && Float.floatToIntBits(a.m11) == Float.floatToIntBits(b.m11)
            && Float.floatToIntBits(a.m12) == Float.floatToIntBits(b.m12) && Float.floatToIntBits(a.m13) == Float.floatToIntBits(b.m13)
            && Float.floatToIntBits(a.m20) == Float.floatToIntBits(b.m20) && Float.floatToIntBits(a.m21) == Float.floatToIntBits(b.m21)
            && Float.floatToIntBits(a.m22) == Float.floatToIntBits(b.m22) && Float.floatToIntBits(a.m23) == Float.floatToIntBits(b.m23)
            && Float.floatToIntBits(a.m30) == Float.floatToIntBits(b.m30) && Float.floatToIntBits(a.m31) == Float.floatToIntBits(b.m31)
            && Float.floatToIntBits(a.m32) == Float.floatToIntBits(b.m32) && Float.floatToIntBits(a.m33) == Float.floatToIntBits(b.m33);
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
