package com.pzoptimizer.pzoe;

import java.util.concurrent.ConcurrentHashMap;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoGridSquare;

/**
 * Which squares have burnt, for PZO Enhancements' Scorched Ground bake pass (it declares PZOBakeScorch).
 *
 * Main thread: a few of player 0's loaded chunks a frame, in turn, get a 64-bit mask of their burnt squares (burnt
 * floor, burnt walls, or the burntOut flag) on the ground and first floor. A mask that changes asks for a re-bake,
 * spread over frames. Render thread: BakePass reads the masks of a chunk and its neighbours when it bakes it, as the
 * 12 x 12 squares around the chunk (2 squares of border), 1 = burnt.
 */
final class Scorch {

    static volatile boolean wanted;
    private static final ConcurrentHashMap<Long, Long> masks = new ConcurrentHashMap<>();
    private static int cursor;
    private static long lastRebakeNs;
    private static final int PER_FRAME = 12;

    private Scorch() {}

    private static long key(int wx, int wy) {
        return ((long) wx << 32) ^ (wy & 0xffffffffL);
    }

    /** Main thread, once a frame (PixelLight.frame). */
    static void frame() {
        wanted = BakePass.anyUses("PZOBakeScorch");
        if (!wanted) {
            if (!masks.isEmpty()) masks.clear();
            return;
        }
        zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.currentCell;
        IsoChunkMap map = cell == null ? null : cell.getChunkMap(0);
        if (map == null) return;
        int g = IsoChunkMap.chunkGridWidth;
        if (g <= 0) return;
        boolean changed = false;
        for (int k = 0; k < PER_FRAME; k++) {
            cursor = (cursor + 1) % (g * g);
            IsoChunk c = map.getChunk(cursor % g, cursor / g);
            if (c == null) continue;
            long m = 0L;
            for (int i = 0; i < 64; i++) {
                if (burnt(c.getGridSquare(i & 7, i >> 3, 0)) || burnt(c.getGridSquare(i & 7, i >> 3, 1))) m |= 1L << i;
            }
            Long was = masks.put(key(c.wx, c.wy), m);
            if (was != null ? was != m : m != 0L) changed = true;
        }
        if (masks.size() > g * g * 2) masks.clear();
        long now = System.nanoTime();
        if (changed && now - lastRebakeNs > 5_000_000_000L) {
            lastRebakeNs = now;
            BakePass.rebakeSoon = true;
        }
    }

    private static boolean burnt(IsoGridSquare sq) {
        if (sq == null) return false;
        zombie.core.properties.PropertyContainer pr = sq.getProperties();
        if (pr != null && pr.has(zombie.iso.SpriteDetails.IsoFlagType.burntOut)) return true;
        zombie.iso.IsoObject floor = sq.getFloor();
        String fn = floor == null || floor.sprite == null ? null : floor.sprite.getName();
        if (fn != null && fn.startsWith(IsoGridSquare.FLOORS_BURNT_SPRITE_PREFIX)) return true;
        java.util.List<zombie.iso.IsoObject> objs = com.pzoptimizer.GameCompat.objects(sq);
        for (int i = 0, n = objs.size(); i < n; i++) {
            zombie.iso.IsoObject o = objs.get(i);
            if (o != null && o.sprite != null && "walls_burnt_01".equals(o.sprite.tilesetName)) return true;
        }
        return false;
    }

    /** Render thread: the 12 x 12 squares around chunk (wx, wy), x fastest, into out; false if none burnt. */
    static boolean around(int wx, int wy, float[] out) {
        java.util.Arrays.fill(out, 0f);
        boolean any = false;
        for (int cy = -1; cy <= 1; cy++) {
            for (int cx = -1; cx <= 1; cx++) {
                Long m = masks.get(key(wx + cx, wy + cy));
                if (m == null || m == 0L) continue;
                for (int i = 0; i < 64; i++) {
                    if ((m >>> i & 1L) == 0L) continue;
                    int x = cx * 8 + (i & 7) + 2, y = cy * 8 + (i >> 3) + 2;
                    if (x < 0 || x >= 12 || y < 0 || y >= 12) continue;
                    out[y * 12 + x] = 1f;
                    any = true;
                }
            }
        }
        return any;
    }
}
