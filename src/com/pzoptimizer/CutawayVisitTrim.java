package com.pzoptimizer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import zombie.iso.BuildingDef;
import zombie.iso.IsoGridSquare;
import zombie.iso.fboRenderChunk.FBORenderCutaways;

/**
 * Walks the cut-away walls once per frame instead of once per point of interest (JVM_CutawayVisitTrim).
 *
 * FBORenderCutaways.doCutawayVisitSquares collects every wall on the player's level in every on-screen chunk, then
 * runs a visit over all of them for each point of interest (the player and every visible square around them) whose
 * square it hasn't seen yet, which is nearly all of them. IsCutawaySquare only uses the point for its level, which is
 * the same for all of them, so the first visit marks every wall square visited and finds everything; the rest only
 * re-hash the same squares. Now only the first visit runs.
 *
 * Before it, walls that can't give a result are dropped from the list (it's only used by this visit): with no cut-away
 * rooms only walls of buildings being collapsed can cut, otherwise only walls whose occluded rooms include one of the
 * cut-away rooms. While the player peeks through a window or climbs, every wall stays.
 *
 * The patch is in CutawayVisitTrimAsm, since this class names FBORenderCutaways.
 */
public final class CutawayVisitTrim {

    public static volatile boolean active = true;
    public static final AtomicLong visitsSkipped = new AtomicLong();
    public static final AtomicLong wallsDropped = new AtomicLong();

    // Main thread only
    private static boolean visited;

    private static final class H {
        static final MethodHandle VISIT, PART_OF;
        static final VarHandle PER_PLAYER, WALLS, BTC, BTC_LIST, ROOM_IDS;
        static {
            MethodHandle visit = null, partOf = null;
            VarHandle per = null, walls = null, btc = null, list = null, ids = null;
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                Class<?> pp = Class.forName("zombie.iso.fboRenderChunk.FBORenderCutaways$PerPlayerData");
                Class<?> wall = Class.forName("zombie.iso.fboRenderChunk.FBORenderCutaways$CutawayWall");
                Class<?> btcClass = Class.forName("zombie.iso.fboRenderChunk.FBORenderCutaways$BuildingsToCollapse");
                MethodHandles.Lookup c = MethodHandles.privateLookupIn(FBORenderCutaways.class, l);
                visit = c.findVirtual(FBORenderCutaways.class, "doCutawayVisitSquares",
                    MethodType.methodType(void.class, IsoGridSquare.class, long.class, ArrayList.class));
                per = c.findVarHandle(FBORenderCutaways.class, "perPlayerData", pp.arrayType());
                MethodHandles.Lookup p = MethodHandles.privateLookupIn(pp, l);
                walls = p.findVarHandle(pp, "cutawayWalls", ArrayList.class);
                btc = p.findVarHandle(pp, "buildingsToCollapse", btcClass);
                list = MethodHandles.privateLookupIn(btcClass, l).findVarHandle(btcClass, "buildingsToCollapse", ArrayList.class);
                MethodHandles.Lookup w = MethodHandles.privateLookupIn(wall, l);
                partOf = w.findVirtual(wall, "isPartOfBuilding", MethodType.methodType(boolean.class, BuildingDef.class))
                    .asType(MethodType.methodType(boolean.class, Object.class, BuildingDef.class));
                ids = w.findVarHandle(wall, "occludedRoomIds", gnu.trove.list.array.TLongArrayList.class);
            } catch (Throwable t) {
                PZOLogger.warn("[CutawayVisitTrim] cut-away data not as expected, stock visits: " + t);
            }
            VISIT = visit;
            PART_OF = partOf;
            PER_PLAYER = per;
            WALLS = walls;
            BTC = btc;
            BTC_LIST = list;
            ROOM_IDS = ids;
        }
    }

    private CutawayVisitTrim() {}

    /** Start of doCutawayVisitSquares(playerIndex, chunks). */
    public static void begin() {
        visited = false;
    }

    /** Replaces the per-point call of the private doCutawayVisitSquares(square, time, chunks). */
    public static void visit(Object self, Object square, long time, Object chunks) throws Throwable {
        if (H.VISIT == null) return;
        if (active) {
            if (visited) {
                visitsSkipped.incrementAndGet();
                return;
            }
            visited = true;
            try {
                dropWallsThatCannotCut((FBORenderCutaways) self);
            } catch (Throwable t) {
                active = false;
                PZOLogger.warn("[CutawayVisitTrim] Stood down, stock visits: " + t);
            }
        }
        H.VISIT.invoke((FBORenderCutaways) self, (IsoGridSquare) square, time, (ArrayList<?>) chunks);
    }

    private static void dropWallsThatCannotCut(FBORenderCutaways c) throws Throwable {
        int playerIndex = zombie.iso.IsoCamera.frameState.playerIndex;
        if (c.cell == null || playerIndex < 0) return;
        Object perPlayer = ((Object[]) H.PER_PLAYER.get(c))[playerIndex];
        ArrayList<?> walls = (ArrayList<?>) H.WALLS.get(perPlayer);
        if (walls.isEmpty()) return;
        zombie.characters.IsoPlayer player = zombie.characters.IsoPlayer.players[playerIndex];
        // These test walls in ways I don't mirror, so keep everything
        if (c.cell.playerWindowPeekingRoomId[playerIndex] != -1L || player == null || player.isClimbing()) return;
        ArrayList<Long> roomIds = c.cell.tempPlayerCutawayRoomIds.get(playerIndex);
        int before = walls.size();
        if (roomIds.isEmpty()) {
            ArrayList<?> collapse = (ArrayList<?>) H.BTC_LIST.get(H.BTC.get(perPlayer));
            if (collapse.isEmpty()) {
                walls.clear();
            } else {
                walls.removeIf(wall -> {
                    try {
                        for (int i = 0; i < collapse.size(); i++) {
                            if ((boolean) H.PART_OF.invokeExact(wall, (BuildingDef) collapse.get(i))) return false;
                        }
                        return true;
                    } catch (Throwable t) {
                        return false;
                    }
                });
            }
        } else {
            walls.removeIf(wall -> {
                gnu.trove.list.array.TLongArrayList ids = (gnu.trove.list.array.TLongArrayList) H.ROOM_IDS.get(wall);
                for (int i = 0; i < roomIds.size(); i++) {
                    if (ids.contains(roomIds.get(i))) return false;
                }
                return true;
            });
        }
        wallsDropped.addAndGet(before - walls.size());
    }
}
