package com.pzoptimizer.pzoe;

import com.pzoptimizer.PZOLogger;
import java.util.concurrent.ConcurrentHashMap;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;

/**
 * Re-bakes for every loaded chunk. A card flipped on or off redraws the world at once (now): the short hitch shows the
 * change straight away, so nobody thinks the card does nothing. Re-bakes nobody asked for while playing (Natural Ground
 * measuring new ground textures, its daily season change) go a few chunks a frame nearest the player first instead
 * (addAll): invalidating all of those at once made every on-screen chunk bake in the same frame, since PZO's bake
 * budget never holds redraws back. A queued chunk isn't marked dirty until its turn, so it stays a normal, clean chunk
 * drawing its old texture meanwhile; the order is worked out against the map every frame, so walking still does the
 * nearest first. Asking again for a chunk already waiting only adds flags.
 */
final class RebakeQueue {

    static final int CHUNKS_PER_FRAME = 4;

    private static final ConcurrentHashMap<IsoChunk, Long> pending = new ConcurrentHashMap<>();
    private static long queuedTotal, bakedTotal;

    private RebakeQueue() {}

    static boolean contains(IsoChunk c) {
        return c != null && !pending.isEmpty() && pending.containsKey(c);
    }

    static boolean isEmpty() {
        return pending.isEmpty();
    }

    static int size() {
        return pending.size();
    }

    /** Main thread: every chunk loaded for any player is invalidated now (a card was flipped). */
    static void now(long flags) {
        zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.currentCell;
        if (cell == null) return;
        int g = IsoChunkMap.chunkGridWidth;
        for (int p = 0; p < 4; p++) {
            IsoChunkMap map = cell.getChunkMap(p);
            if (map == null) continue;
            for (int y = 0; y < g; y++) {
                for (int x = 0; x < g; x++) {
                    IsoChunk c = map.getChunk(x, y);
                    if (c == null) continue;
                    Long waiting = pending.remove(c);
                    c.invalidateRenderChunkLevels(waiting == null ? flags : flags | waiting);
                }
            }
        }
    }

    /** Main thread: every chunk loaded for any player gets these dirty flags, a few at a time. */
    static void addAll(long flags) {
        zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.currentCell;
        if (cell == null) return;
        int g = IsoChunkMap.chunkGridWidth;
        for (int p = 0; p < 4; p++) {
            IsoChunkMap map = cell.getChunkMap(p);
            if (map == null) continue;
            for (int y = 0; y < g; y++) {
                for (int x = 0; x < g; x++) {
                    IsoChunk c = map.getChunk(x, y);
                    if (c == null) continue;
                    pending.merge(c, flags, (a, b) -> a | b);
                    queuedTotal++;
                }
            }
        }
    }

    /** Main thread, once a frame. */
    static void drain() {
        if (pending.isEmpty()) return;
        zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance == null ? null : zombie.iso.IsoWorld.instance.currentCell;
        if (cell == null) {
            pending.clear();
            return;
        }
        int g = IsoChunkMap.chunkGridWidth;
        int[] order = PixelLight.spiralOrder(g);
        int budget = CHUNKS_PER_FRAME, seen = 0;
        for (int p = 0; p < 4 && budget > 0; p++) {
            IsoChunkMap map = cell.getChunkMap(p);
            if (map == null) continue;
            for (int idx : order) {
                IsoChunk c = map.getChunk(idx % g, idx / g);
                if (c == null) continue;
                Long flags = pending.get(c);
                if (flags == null) continue;
                seen++;
                if (budget <= 0) continue;
                // An unlit bake needs its light in the map first; the column catch-up gets there within a few frames
                if (PixelLight.deferred && !PixelLight.columnCurrent(p, c)) continue;
                pending.remove(c);
                try {
                    c.invalidateRenderChunkLevels(flags);
                    bakedTotal++;
                } catch (Throwable t) {
                    PZOLogger.warn("[RebakeQueue] invalidate: " + t);
                }
                budget--;
            }
        }
        // Chunks that left every map (walked away, pooled) would wait forever; whatever loads there bakes fresh anyway
        if (seen == 0 && budget == CHUNKS_PER_FRAME) pending.clear();
    }

    static String stateText() {
        return "rebakeQueue=" + pending.size() + ";rebakeQueued=" + queuedTotal + ";rebakeDone=" + bakedTotal;
    }
}
