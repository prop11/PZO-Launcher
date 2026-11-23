package com.pzoptimizer.pzoe;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import zombie.iso.IsoCell;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.areas.IsoRoom;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoWindow;
import zombie.iso.objects.IsoWindowFrame;

final class SmokeOpenings {
    private static final IsoDirections[] SIDES = {IsoDirections.N, IsoDirections.W, IsoDirections.S, IsoDirections.E};
    static final int MAX_PORTALS = 16;
    private static final int SQUARES_PER_REFRESH = 64;
    private static final long REFRESH_NS = 2_000_000_000L;
    private static final int MAX_CACHED_ROOMS = 64;
    private static final int REFRESHES_PER_TICK = 6;
    private static final IdentityHashMap<IsoRoom, Topology> cache = new IdentityHashMap<>();
    private static IsoCell world;
    private static long lastBudgetWindow;
    private static int refreshed;

    private SmokeOpenings() {}

    record Portal(IsoGridSquare from, IsoGridSquare to, IsoObject object, float height, float aperture) {
        float weight(float windX, float windY) {
            if (object != null && object.getObjectIndex() < 0) return 0f;
            if (object instanceof IsoDoor door && (door.isBarricaded() || !(door.isOpen() || door.isDestroyed()))) return 0f;
            if (object instanceof IsoWindow window && (window.isBarricaded()
                || !(window.isOpen() || window.isSmashed() || window.isDestroyed()))) return 0f;
            if (object instanceof IsoThumpable door && (door.isBarricaded()
                || (door.isDoor() && !(door.isOpen() || door.isDestroyed())))) return 0f;
            if (object instanceof IsoWindowFrame frame && frame.isBarricaded()) return 0f;
            if (from.getZ() != to.getZ()) return aperture * (to.getZ() > from.getZ() ? 1.2f : 0.55f);
            if (!to.isOutside()) return aperture;
            float along = (to.getX() - from.getX()) * windX + (to.getY() - from.getY()) * windY;
            return aperture * (0.85f + 0.35f * along);
        }

        IsoRoom receivingRoom() {
            return to.isOutside() ? null : to.getRoom();
        }
    }

    private static final class Topology {
        final ArrayList<Portal> portals = new ArrayList<>(MAX_PORTALS);
        int count, cursor;
        long refresh;
    }

    static void invalidate(IsoGridSquare square) {
        if (cache.isEmpty() || square == null) return;
        cache.remove(square.getRoom());
        for (IsoDirections side : SIDES) {
            IsoGridSquare adjacent = square.getAdjacentSquare(side);
            if (adjacent != null) cache.remove(adjacent.getRoom());
        }
    }

    static ArrayList<Portal> of(IsoRoom room, IsoCell cell, long now) {
        if (world != cell) {
            world = cell;
            cache.clear();
        }
        Topology topology = cache.get(room);
        if (topology == null) {
            if (cache.size() >= MAX_CACHED_ROOMS) cache.clear();
            topology = new Topology();
            cache.put(room, topology);
        }
        var squares = room.getSquares();
        int size = squares.size();
        if (topology.count != size) {
            topology.portals.clear();
            topology.cursor = 0;
            topology.count = size;
            topology.refresh = 0;
        }
        if (topology.refresh > now || size == 0) return topology.portals;
        long window = now / 50_000_000L;
        if (window != lastBudgetWindow) {
            lastBudgetWindow = window;
            refreshed = 0;
        }
        if (refreshed >= REFRESHES_PER_TICK) return topology.portals;
        refreshed++;
        topology.refresh = now + REFRESH_NS;
        int end = Math.min(size, topology.cursor + SQUARES_PER_REFRESH);
        for (int i = topology.cursor; i < end; i++) {
            IsoGridSquare from = squares.get(i);
            for (int p = topology.portals.size() - 1; p >= 0; p--) {
                if (topology.portals.get(p).from == from) topology.portals.remove(p);
            }
            for (IsoDirections side : SIDES) {
                IsoGridSquare to = from.getAdjacentSquare(side);
                if (to == null) to = cell.getGridSquare(from.getX() + side.dx(), from.getY() + side.dy(), from.getZ());
                inspectEdge(room, topology.portals, from, to);
            }
            if (from.HasStairTop()) {
                IsoDirections side = from.getStairsDirection();
                stairs(room, topology.portals, from, cell.getGridSquare(from.getX() + side.dx(),
                    from.getY() + side.dy(), from.getZ() + 1));
            }
            IsoGridSquare southBelow = cell.getGridSquare(from.getX(), from.getY() + 1, from.getZ() - 1);
            if (southBelow != null && southBelow.HasStairTopNorth()) stairs(room, topology.portals, from, southBelow);
            IsoGridSquare eastBelow = cell.getGridSquare(from.getX() + 1, from.getY(), from.getZ() - 1);
            if (eastBelow != null && eastBelow.HasStairTopWest()) stairs(room, topology.portals, from, eastBelow);
        }
        topology.cursor = end == size ? 0 : end;
        return topology.portals;
    }

    private static void inspectEdge(IsoRoom room, ArrayList<Portal> portals, IsoGridSquare from, IsoGridSquare to) {
        if (to == null || to.getRoom() == room || portals.size() >= MAX_PORTALS) return;
        IsoObject door = from.getDoorTo(to);
        if (door != null) {
            portals.add(new Portal(from, to, door, 0.62f, 1f));
            return;
        }
        IsoWindow window = from.getWindowTo(to);
        if (window != null) {
            portals.add(new Portal(from, to, window, 0.51f, 0.58f));
            return;
        }
        IsoThumpable built = from.getWindowThumpableTo(to);
        IsoWindowFrame frame = from.getWindowFrameTo(to);
        if (built != null) portals.add(new Portal(from, to, built, 0.51f, 0.48f));
        else if (frame != null) portals.add(new Portal(from, to, frame, 0.51f, 0.48f));
        else if ((to.getRoom() != null || to.isOutside()) && !from.isSomethingTo(to))
            portals.add(new Portal(from, to, null, 0.62f, 0.8f));
    }

    private static void stairs(IsoRoom room, ArrayList<Portal> portals, IsoGridSquare from, IsoGridSquare to) {
        if (to != null && to.getRoom() != null && to.getRoom() != room && portals.size() < MAX_PORTALS)
            portals.add(new Portal(from, to, null, 0.75f, 0.85f));
    }
}
