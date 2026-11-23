package com.pzoptimizer.pzoe;

import java.util.concurrent.ConcurrentHashMap;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.network.GameClient;
import zombie.network.GameServer;

public final class Scorch {
    private static final String SOOT = "PZOE.FireSoot";
    private static final String HEAT = "PZOE.FireHeat";
    private static final String ORIGIN = "PZOE.FireOrigin";
    private static final IsoDirections[] NEIGHBORS = {IsoDirections.N, IsoDirections.S, IsoDirections.W, IsoDirections.E};
    private record Marks(long soot, long heat, long charred, long origin) {}
    private static final ConcurrentHashMap<Long, Marks> masks = new ConcurrentHashMap<>();
    private static IsoCell world;
    private static int cursor, level = Integer.MIN_VALUE;
    private static long lastRebakeNs;
    private static boolean rebakePending;

    private Scorch() {}

    private static long key(int wx, int wy, int z) {
        return ((long) wx & 0xffffffL) << 40 | ((long) wy & 0xffffffL) << 16 | (z & 0xffffL);
    }

    /** Main thread. Spread the initial scan over frames even for tall buildings. */
    static void frame() {
        if (!BakePass.anyUses("PZOBakeScorch")) {
            if (!masks.isEmpty()) masks.clear();
            rebakePending = false;
            return;
        }
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (world != cell) {
            world = cell;
            masks.clear();
            cursor = 0;
            level = Integer.MIN_VALUE;
            rebakePending = false;
        }
        IsoChunkMap map = cell == null ? null : cell.getChunkMap(0);
        int width = IsoChunkMap.chunkGridWidth;
        if (map == null || width <= 0) return;
        if (cursor >= width * width) cursor = 0;
        var chunk = map.getChunk(cursor % width, cursor / width);
        if (chunk != null) {
            if (level < chunk.getMinLevel() || level > chunk.getMaxLevel()) level = chunk.getMinLevel();
            scan(chunk, level++);
        }
        if (chunk == null || level > chunk.getMaxLevel()) {
            level = Integer.MIN_VALUE;
            cursor = (cursor + 1) % (width * width);
        }
        if (masks.size() > width * width * 6) {
            masks.keySet().removeIf(key -> cell.getChunk((int)(key >> 40), (int)(key << 24 >> 40)) == null);
        }
        if (!rebakePending) return;
        long now = System.nanoTime();
        if (now - lastRebakeNs >= 2_000_000_000L) {
            lastRebakeNs = now;
            rebakePending = false;
            BakePass.rebakeSoon = true;
        }
    }

    private static void scan(zombie.iso.IsoChunk chunk, int z) {
        long soot = 0L, heat = 0L, charred = 0L, origin = 0L;
        for (int i = 0; i < 64; i++) {
            IsoGridSquare square = chunk.getGridSquare(i & 7, i >> 3, z);
            if (square == null) continue;
            long bit = 1L << i;
            if (marked(square, ORIGIN)) origin |= bit;
            else if (burnt(square)) charred |= bit;
            else if (marked(square, HEAT)) heat |= bit;
            else if (marked(square, SOOT)) soot |= bit;
        }
        long key = key(chunk.wx, chunk.wy, z);
        Marks old = masks.get(key);
        if ((soot | heat | charred | origin) == 0L) {
            if (old != null) {
                masks.remove(key);
                rebakePending = true;
            }
        } else if (old == null || old.soot != soot || old.heat != heat || old.charred != charred || old.origin != origin) {
            masks.put(key, new Marks(soot, heat, charred, origin));
            rebakePending = true;
        }
    }

    static void depositSoot(IsoGridSquare square) {
        if (GameClient.client || square.getRoom() == null || square.isOutside()) return;
        mark(square, SOOT);
        for (IsoDirections direction : NEIGHBORS) {
            IsoGridSquare next = square.getAdjacentSquare(direction);
            if (next != null && next.getRoom() == square.getRoom()) mark(next, SOOT);
        }
    }

    static void damage(IsoGridSquare square, boolean ignition, int energy) {
        if (GameClient.client || square.getRoom() == null || square.isOutside()) return;
        if (ignition) mark(square, ORIGIN);
        if (energy < 55) return;
        for (IsoDirections direction : NEIGHBORS) {
            IsoGridSquare next = square.getAdjacentSquare(direction);
            if (next != null && next.getRoom() == square.getRoom() && !square.isSomethingTo(next)) mark(next, HEAT);
        }
    }

    private static void mark(IsoGridSquare square, String key) {
        if (marked(square, key)) return;
        square.getModData().rawset(key, Boolean.TRUE);
        if (GameServer.server) square.transmitModdata();
    }

    private static boolean marked(IsoGridSquare square, String key) {
        return square.hasModData() && Boolean.TRUE.equals(square.getModData().rawget(key));
    }

    public static boolean hasSoot(boolean vanilla, IsoGridSquare square) {
        return vanilla || marked(square, SOOT);
    }

    public static void clean(IsoGridSquare square) {
        if (GameClient.client || !marked(square, SOOT)) return;
        square.getModData().rawset(SOOT, null);
        if (GameServer.server) square.transmitModdata();
    }

    private static boolean burnt(IsoGridSquare square) {
        if (square.getProperties().has(IsoFlagType.burntOut)) return true;
        var floor = square.getFloor();
        String name = floor == null || floor.sprite == null ? null : floor.sprite.getName();
        if (name != null && name.startsWith(IsoGridSquare.FLOORS_BURNT_SPRITE_PREFIX)) return true;
        var objects = com.pzoptimizer.GameCompat.objects(square);
        for (int i = 0, n = objects.size(); i < n; i++) {
            var object = objects.get(i);
            if (object != null && object.sprite != null && "walls_burnt_01".equals(object.sprite.tilesetName)) return true;
        }
        return false;
    }

    /** Render thread. The chunk FBO contains at most two levels, packed into the same float per tile. */
    static boolean around(int wx, int wy, int minLevel, int topLevel, float[] out) {
        java.util.Arrays.fill(out, 0f);
        boolean any = writeLevel(wx, wy, minLevel, 1, out);
        if (topLevel > minLevel) any |= writeLevel(wx, wy, minLevel + 1, 8, out);
        return any;
    }

    private static boolean writeLevel(int wx, int wy, int z, int place, float[] out) {
        boolean any = false;
        for (int cy = -1; cy <= 1; cy++) {
            for (int cx = -1; cx <= 1; cx++) {
                Marks marks = masks.get(key(wx + cx, wy + cy, z));
                if (marks == null) continue;
                long bits = marks.soot | marks.heat | marks.charred | marks.origin;
                while (bits != 0L) {
                    int i = Long.numberOfTrailingZeros(bits);
                    long bit = 1L << i;
                    bits &= bits - 1L;
                    int x = cx * 8 + (i & 7) + 2, y = cy * 8 + (i >> 3) + 2;
                    if (x < 0 || x >= 12 || y < 0 || y >= 12) continue;
                    int type = (marks.origin & bit) != 0L ? 4 : (marks.charred & bit) != 0L ? 3 : (marks.heat & bit) != 0L ? 2 : 1;
                    out[y * 12 + x] += type * place;
                    any = true;
                }
            }
        }
        return any;
    }
}
