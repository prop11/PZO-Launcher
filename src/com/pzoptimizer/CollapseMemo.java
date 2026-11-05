package com.pzoptimizer;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import zombie.iso.IsoCell;
import zombie.iso.fboRenderChunk.FBORenderCutaways;

/**
 * Reuses the buildings-to-collapse answer while nothing it reads changed (JVM_CollapseMemo).
 *
 * FBORenderCutaways.CalculateBuildingsToCollapse runs every frame: for the player and every visible square around
 * them it walks the occlusion data of up to seven squares, twice when the first side finds nothing, into a freshly
 * allocated list. The answer only depends on those points (CalculatePointsOfInterest rebuilds them just before), the
 * room the player peeks into through a window, and the squares' occlusion data, which only changes with the buildings
 * themselves. So when the points and the peeked room match the last run, it returns "no change" and puts back the
 * orphan-structure flag that run left on the cell, at most 8 frames in a row; then it runs in full again.
 *
 * The patch itself is in CollapseMemoAsm, since this class names FBORenderCutaways.
 */
public final class CollapseMemo {

    public static volatile boolean active = true;
    public static final AtomicLong skipped = new AtomicLong();
    public static final AtomicLong computed = new AtomicLong();

    private static final int MAX_REUSE_FRAMES = 8;

    private static final class State {
        long signature = Long.MIN_VALUE;
        long pending;
        boolean orphanFlag;
        int reused;
    }

    // Main thread only; one per split-screen player
    private static final State[] states = {new State(), new State(), new State(), new State()};

    private static final class H {
        static final VarHandle POINTS;
        static {
            VarHandle p = null;
            try {
                p = MethodHandles.privateLookupIn(FBORenderCutaways.class, MethodHandles.lookup())
                    .findVarHandle(FBORenderCutaways.class, "pointOfInterest", ArrayList.class);
            } catch (Throwable t) {
                PZOLogger.warn("[CollapseMemo] points of interest not readable, stock every frame: " + t);
            }
            POINTS = p;
        }
    }

    private CollapseMemo() {}

    private static long signature(FBORenderCutaways c, int playerIndex) {
        ArrayList<?> points = (ArrayList<?>) H.POINTS.get(c);
        long h = 1469598103934665603L;
        h = (h ^ playerIndex) * 1099511628211L;
        h = (h ^ c.cell.playerWindowPeekingRoomId[playerIndex]) * 1099511628211L;
        for (int i = 0; i < points.size(); i++) {
            FBORenderCutaways.PointOfInterest p = (FBORenderCutaways.PointOfInterest) points.get(i);
            h = (h ^ p.x) * 1099511628211L;
            h = (h ^ p.y) * 1099511628211L;
            h = (h ^ p.z) * 1099511628211L;
            h = (h ^ (p.mousePointer ? 1 : 0)) * 1099511628211L;
        }
        return h ^ points.size();
    }

    /** Start of CalculateBuildingsToCollapse(): true returns "no change" without walking anything. */
    public static boolean skip(Object self) {
        if (!active || H.POINTS == null) return false;
        try {
            FBORenderCutaways c = (FBORenderCutaways) self;
            int playerIndex = zombie.iso.IsoCamera.frameState.playerIndex;
            if (playerIndex < 0 || playerIndex >= states.length || c.cell == null) return false;
            State s = states[playerIndex];
            long sig = signature(c, playerIndex);
            if (sig == s.signature && s.reused < MAX_REUSE_FRAMES) {
                s.reused++;
                c.cell.occludedByOrphanStructureFlag = s.orphanFlag;
                skipped.incrementAndGet();
                return true;
            }
            s.pending = sig;
            return false;
        } catch (Throwable t) {
            active = false;
            PZOLogger.warn("[CollapseMemo] Stood down, stock every frame: " + t);
            return false;
        }
    }

    /** Before each return of the full run: remember what it left. */
    public static void after(Object self) {
        if (!active) return;
        try {
            FBORenderCutaways c = (FBORenderCutaways) self;
            int playerIndex = zombie.iso.IsoCamera.frameState.playerIndex;
            if (playerIndex < 0 || playerIndex >= states.length || c.cell == null) return;
            State s = states[playerIndex];
            s.signature = s.pending;
            s.orphanFlag = c.cell.occludedByOrphanStructureFlag;
            s.reused = 0;
            computed.incrementAndGet();
        } catch (Throwable ignored) {
        }
    }

    /** JVM_CollapseMemo off: the next frame runs in full. */
    public static void setActive(boolean value) {
        active = value;
        for (State s : states) s.signature = Long.MIN_VALUE;
    }
}
