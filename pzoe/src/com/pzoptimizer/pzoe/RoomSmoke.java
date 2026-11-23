package com.pzoptimizer.pzoe;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.areas.IsoRoom;
import zombie.iso.objects.IsoFire;

final class RoomSmoke {
    private static final int MAX_ROOMS = 48;
    private static final float MIN_DENSITY = 0.008f;
    private final IdentityHashMap<IsoRoom, Room> byRoom = new IdentityHashMap<>();
    private final ArrayList<Room> active = new ArrayList<>(MAX_ROOMS);
    private final float[] flows = new float[SmokeOpenings.MAX_PORTALS];
    private IsoCell cell;
    private long now;

    static final class Room {
        final IsoRoom room;
        IsoGridSquare anchor;
        float source, density, incoming;

        Room(IsoGridSquare anchor) {
            this.anchor = anchor;
            this.room = anchor.getRoom();
        }
    }

    void begin(IsoCell cell, long now) {
        if (this.cell != cell) {
            this.cell = cell;
            active.clear();
            byRoom.clear();
        }
        this.now = now;
        for (int i = 0; i < active.size(); i++) {
            Room room = active.get(i);
            room.source = 0f;
            room.incoming = 0f;
        }
    }

    static float intensity(IsoFire fire) {
        return fire.smoke ? 0.7f : 0.55f + 0.45f * Math.min(1f, Math.max(0, fire.getEnergy()) / 80f);
    }

    void addFire(IsoGridSquare square, float intensity) {
        Room room = getOrAdd(square);
        if (room == null) return;
        if (room.source == 0f) room.anchor = square;
        room.source += intensity;
    }

    private Room getOrAdd(IsoGridSquare square) {
        IsoRoom current = square.getRoom();
        if (current == null) return null;
        Room room = byRoom.get(current);
        if (room == null && active.size() < MAX_ROOMS) {
            room = new Room(square);
            byRoom.put(room.room, room);
            active.add(room);
        }
        return room;
    }

    void advance(float dt, float windX, float windY) {
        dt = Math.min(dt, 2f);
        int initialSize = active.size();
        for (int i = 0; i < initialSize; i++) {
            Room room = active.get(i);
            float old = room.density;
            float outflow = 0f;
            if (old > MIN_DENSITY) {
                var portals = portals(room);
                float total = 0f;
                for (int p = 0; p < portals.size(); p++) {
                    var portal = portals.get(p);
                    float aperture = portal.weight(windX, windY);
                    var receiving = portal.receivingRoom();
                    float gradient = old - density(receiving);
                    float flow = (receiving != null || portal.to().isOutside()) ? gradient * dt * aperture * 0.26f : 0f;
                    flows[p] = flow > 0.002f ? flow : 0f;
                    total += flows[p];
                }
                float limit = old * Math.min(0.45f, dt * 0.30f);
                float scale = total > limit ? limit / total : 1f;
                for (int p = 0; p < portals.size(); p++) {
                    float flow = flows[p] * scale;
                    if (flow <= 0f) continue;
                    var portal = portals.get(p);
                    var receiving = portal.receivingRoom();
                    if (receiving != null) {
                        Room destination = getOrAdd(portal.to());
                        if (destination == null) continue;
                        destination.incoming += flow;
                    }
                    outflow += flow;
                }
            }
            float volume = 1.25f + 0.015f * room.room.getSquares().size();
            float source = room.source / (room.source + volume);
            room.incoming += dt * (source * 0.62f * (1f - old) - old * 0.13f) - outflow;
        }
        for (int i = active.size() - 1; i >= 0; i--) {
            Room room = active.get(i);
            room.density = Math.max(0f, Math.min(1f, room.density + room.incoming));
            if (room.source == 0f && room.density < MIN_DENSITY) {
                active.remove(i);
                byRoom.remove(room.room);
            }
        }
    }

    float density(IsoRoom room) {
        Room state = byRoom.get(room);
        return state == null ? 0f : state.density;
    }

    ArrayList<Room> rooms() {
        return active;
    }

    ArrayList<SmokeOpenings.Portal> portals(Room room) {
        return SmokeOpenings.of(room.room, cell, now);
    }
}
