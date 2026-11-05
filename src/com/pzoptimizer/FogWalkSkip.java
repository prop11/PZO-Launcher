package com.pzoptimizer;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Skips the square walk that only paces the fog row iterator (JVM_FogWalkSkip).
 *
 * In fog, FBORenderCell.renderFog walks every square of every on-screen chunk on levels 0 and 1, every frame, and for
 * each square with a standing object calls ImprovedFog.renderRowsBehind(square). That call only advances the fog row
 * iterator up to the square's row, starting and ending the same fog strips the iterator would anyway; endRender then
 * runs it to the end, and the drawer just collects the strips until endFrame. So the strips, and their order, are the
 * same with no walk at all. The walk now sees no chunks and endRender does all of it.
 *
 * ImprovedFog's debug renderEndOnly mode does depend on the walk (rows passed during it get no strip), so the walk
 * stays while that's on. Names no FBORenderCell type; the patch is in FogWalkSkipAsm.
 */
public final class FogWalkSkip {

    public static volatile boolean active = true;
    public static final AtomicLong walksSkipped = new AtomicLong();

    private static final ArrayList<Object> NONE = new ArrayList<>(0);

    private static final class H {
        static final VarHandle END_ONLY;
        static {
            VarHandle v = null;
            try {
                Class<?> fog = Class.forName("zombie.iso.weather.fog.ImprovedFog");
                v = MethodHandles.privateLookupIn(fog, MethodHandles.lookup()).findStaticVarHandle(fog, "renderEndOnly", boolean.class);
            } catch (Throwable t) {
                PZOLogger.warn("[FogWalkSkip] fog settings not readable, stock walk: " + t);
            }
            END_ONLY = v;
        }
    }

    private FogWalkSkip() {}

    /** Replaces the onScreenChunks reads in renderFog's walk. */
    public static ArrayList<?> chunks(ArrayList<?> onScreen) {
        if (!active || H.END_ONLY == null || (boolean) H.END_ONLY.get()) return onScreen;
        if (!onScreen.isEmpty()) walksSkipped.incrementAndGet();
        return NONE;
    }
}
