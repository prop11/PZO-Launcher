package com.pzoptimizer;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import zombie.core.skinnedmodel.animation.AnimationMultiTrack;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.animation.AnimationTrack;

/**
 * Replaces AnimationPlayer.Update in BaseVehicle.updateAnimationPlayer. A vehicle part whose tracks are all stopped
 * (closed doors, trunks, hoods and windows at rest) never advances its track time, so every Update recomputes the
 * exact same pose. Each player's track state is fingerprinted, and Update is skipped while the fingerprint is the
 * same as at the last two real updates. Any change (a track starting, stopping, being added or removed, a window
 * moving, a blend weight changing) forces a real Update, as does anything playing.
 */
public final class VehicleAnimSkipGovernor {

    private static volatile boolean active = true;
    public static final AtomicLong updatesSkipped = new AtomicLong();
    public static final AtomicLong updatesRun = new AtomicLong();

    // Vehicles update on the main thread only. Weak keys so unloaded vehicles' players are not retained.
    private static final WeakHashMap<AnimationPlayer, long[]> state = new WeakHashMap<>();

    // Set by AnimationPlayer resets (new model, skeleton change); the next Update must run to rebuild the pose
    private static final VarHandle NEED_FIRST_FRAME = lookupNeedFirstFrame();

    private VehicleAnimSkipGovernor() {}

    private static VarHandle lookupNeedFirstFrame() {
        try {
            return MethodHandles.privateLookupIn(AnimationPlayer.class, MethodHandles.lookup())
                .findVarHandle(AnimationPlayer.class, "boneTransformsNeedFirstFrame", boolean.class);
        } catch (Throwable t) {
            PZOLogger.warn("[VehicleAnimSkipGovernor] boneTransformsNeedFirstFrame unavailable, governor disabled: " + t);
            active = false;
            return null;
        }
    }

    public static void setActive(boolean value) {
        active = value;
        if (!value) {
            synchronized (state) {
                state.clear();
            }
        }
    }

    public static boolean isActive() {
        return active;
    }

    public static void update(AnimationPlayer player, float deltaT) {
        if (!active) {
            player.Update(deltaT);
            return;
        }
        long fp = fingerprint(player);
        synchronized (state) {
            long[] s = state.get(player);
            // s[0] = fingerprint after the last real update, s[1] = how many consecutive real updates saw it
            if (fp != Long.MIN_VALUE && s != null && s[0] == fp && s[1] >= 2) {
                updatesSkipped.incrementAndGet();
                return;
            }
            player.Update(deltaT);
            updatesRun.incrementAndGet();
            long after = fingerprint(player);
            if (s == null) {
                s = new long[2];
                state.put(player, s);
            }
            if (after != Long.MIN_VALUE && after == s[0]) {
                s[1]++;
            } else {
                s[0] = after;
                s[1] = 1;
            }
        }
    }

    /** Long.MIN_VALUE means "must update": not ready, ragdolling, or a track is playing. */
    private static long fingerprint(AnimationPlayer player) {
        if (!player.isReady() || player.isRagdolling() || (boolean) NEED_FIRST_FRAME.get(player)) return Long.MIN_VALUE;
        AnimationMultiTrack multi = player.getMultiTrack();
        List<AnimationTrack> tracks = multi.getTracks();
        long h = 1469598103934665603L ^ tracks.size();
        for (int i = 0; i < tracks.size(); i++) {
            AnimationTrack t = tracks.get(i);
            if (t.isPlaying) return Long.MIN_VALUE;
            h = (h ^ System.identityHashCode(t)) * 1099511628211L;
            h = (h ^ Float.floatToIntBits(t.getCurrentTimeValue())) * 1099511628211L;
            h = (h ^ Float.floatToIntBits(t.getBlendWeight())) * 1099511628211L;
            h = (h ^ (t.reverse ? 1 : 0)) * 1099511628211L;
        }
        return h == Long.MIN_VALUE ? h + 1 : h;
    }
}
