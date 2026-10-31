package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;
import zombie.iso.IsoChunk;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.fboRenderChunk.FBORenderLevels;

/**
 * Settings screen "Keep chunk images just off screen" (JVM_ChunkTextureKeep).
 *
 * Each chunk's two-level group is drawn once into a texture and re-drawn only when something in it changes. Stock
 * FBORenderCell.checkNewlyOnScreenChunks frees that texture the moment the group leaves the screen and, when it comes
 * back, marks it for a full re-draw (DIRTY_REDRAW) in that same frame. Walking back and forth, turning a corner or
 * driving along the screen edge re-draws the same chunks over and over, and those re-draws land in the frames that
 * are already busy streaming the world.
 *
 * Here a group leaving the screen keeps its texture, oldest dropped first past a video memory budget. While it is off
 * screen every change still marks it dirty (lighting, objects, items, blood, occlusion and neighbour seams all
 * invalidate regardless of the screen), so when it comes back it is re-drawn only if something changed, as it would
 * have been on screen. The stock re-draw is kept when the texture was dropped, the chunk object was reused for another
 * place, the zoom picked another texture, or a graphics option that stock re-applies to on-screen chunks only (wind
 * sway, water shader, no-lighting debug) changed meanwhile.
 *
 * Game thread only: every call comes from checkNewlyOnScreenChunks.
 */
public final class ChunkTextureKeep {

    public static volatile boolean active = true;
    public static final AtomicLong kept = new AtomicLong();
    public static final AtomicLong reused = new AtomicLong();
    public static final AtomicLong redrawn = new AtomicLong();
    public static final AtomicLong evicted = new AtomicLong();

    private static final class Kept {
        final FBORenderLevels levels;
        final int minLevel;
        final FBORenderChunk fbo1, fbo2;
        final int wx, wy, options;
        final long bytes;

        Kept(FBORenderLevels levels, int minLevel, FBORenderChunk fbo1, FBORenderChunk fbo2, int wx, int wy, int options) {
            this.levels = levels;
            this.minLevel = minLevel;
            this.fbo1 = fbo1;
            this.fbo2 = fbo2;
            this.wx = wx;
            this.wy = wy;
            this.options = options;
            this.bytes = size(fbo1) + (fbo2 != fbo1 ? size(fbo2) : 0L);
        }

        // Colour plus depth attachment
        private static long size(FBORenderChunk c) {
            return c == null ? 0L : (long) c.w * c.h * 8L;
        }

        boolean stillOurs() {
            IsoChunk chunk = levels.getChunk();
            if (chunk == null || chunk.wx != wx || chunk.wy != wy) return false;
            return levels.getFBOForLevel(minLevel, 1.0F) == fbo1 && levels.getFBOForLevel(minLevel, 0.5F) == fbo2;
        }
    }

    private record Key(FBORenderLevels levels, int minLevel) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && k.levels == levels && k.minLevel == minLevel;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(levels) * 31 + minLevel;
        }
    }

    // Insertion order = age; the oldest is dropped first
    private static final LinkedHashMap<Key, Kept> keptGroups = new LinkedHashMap<>();
    private static long keptBytes;
    private static long budgetBytes = -1L;

    private ChunkTextureKeep() {}

    /** Replaces renderLevels.freeFBOsForLevel(z) in checkNewlyOnScreenChunks. */
    public static void free(FBORenderLevels levels, int level) {
        if (!active) {
            if (!keptGroups.isEmpty()) dropAll();
            levels.freeFBOsForLevel(level);
            return;
        }
        FBORenderChunk fbo1 = levels.getFBOForLevel(level, 1.0F);
        FBORenderChunk fbo2 = levels.getFBOForLevel(level, 0.5F);
        if (fbo1 == null && fbo2 == null) {
            levels.freeFBOsForLevel(level);
            return;
        }
        int minLevel = FBORenderLevels.calculateMinLevel(level);
        Key key = new Key(levels, minLevel);
        Kept k = keptGroups.get(key);
        if (k != null) {
            if (k.fbo1 == fbo1 && k.fbo2 == fbo2) return;
            keptGroups.remove(key);
            keptBytes -= k.bytes;
        }
        IsoChunk chunk = levels.getChunk();
        if (chunk == null) {
            levels.freeFBOsForLevel(level);
            return;
        }
        Kept nk = new Kept(levels, minLevel, fbo1, fbo2, chunk.wx, chunk.wy, optionsStamp());
        if (nk.bytes > budget()) {
            levels.freeFBOsForLevel(level);
            return;
        }
        keptGroups.put(key, nk);
        keptBytes += nk.bytes;
        kept.incrementAndGet();
        trimToBudget(nk);
    }

    /** Replaces renderLevels.invalidateLevel(z, flags) in checkNewlyOnScreenChunks. */
    public static void invalidate(FBORenderLevels levels, int level, long flags) {
        if (flags != FBORenderChunk.DIRTY_REDRAW || keptGroups.isEmpty()) {
            levels.invalidateLevel(level, flags);
            return;
        }
        Kept k = keptGroups.remove(new Key(levels, FBORenderLevels.calculateMinLevel(level)));
        if (k == null) {
            levels.invalidateLevel(level, flags);
            return;
        }
        keptBytes -= k.bytes;
        float zoom = zombie.core.Core.getInstance().getZoom(levels.getPlayerIndex());
        FBORenderChunk current = levels.getFBOForLevel(level, zoom);
        boolean sameSize = current != null
            && current.w == FBORenderLevels.calculateTextureWidthForLevels(current.getMinLevel(), current.getTopLevel(), zoom)
            && current.h == FBORenderLevels.calculateTextureHeightForLevels(current.getMinLevel(), current.getTopLevel(), zoom);
        if (active && sameSize && k.stillOurs() && k.options == optionsStamp()) {
            reused.incrementAndGet();
            return;
        }
        redrawn.incrementAndGet();
        levels.invalidateLevel(level, flags);
    }

    private static void trimToBudget(Kept newest) {
        if (keptBytes <= budget()) return;
        // Entries freed behind our back (chunk unloaded, manager reset) only hold budget; clear them first
        ArrayList<Key> stale = new ArrayList<>();
        for (java.util.Map.Entry<Key, Kept> e : keptGroups.entrySet()) {
            if (!e.getValue().stillOurs()) stale.add(e.getKey());
        }
        for (Key key : stale) keptBytes -= keptGroups.remove(key).bytes;
        Iterator<Kept> it = keptGroups.values().iterator();
        while (keptBytes > budget() && it.hasNext()) {
            Kept k = it.next();
            if (k == newest) break;
            it.remove();
            keptBytes -= k.bytes;
            release(k);
        }
    }

    private static void release(Kept k) {
        // A group that came back on screen without passing through invalidate is in use: just forget it
        if (k.stillOurs() && !k.levels.isOnScreen(k.minLevel)) {
            k.levels.freeFBOsForLevel(k.minLevel);
            evicted.incrementAndGet();
        }
    }

    private static void dropAll() {
        for (Kept k : keptGroups.values()) release(k);
        keptGroups.clear();
        keptBytes = 0L;
    }

    // Options stock re-applies by invalidating on-screen chunks only
    private static int optionsStamp() {
        int s = 0;
        try {
            if (zombie.core.Core.getInstance().getOptionDoWindSpriteEffects()) s |= 1;
            if (zombie.iso.IsoWater.getInstance().getShaderEnable()) s |= 2;
            if (zombie.debug.DebugOptions.instance.fboRenderChunk.nolighting.getValue()) s |= 4;
            if (zombie.debug.DebugOptions.instance.fboRenderChunk.highResChunkTextures.getValue()) s |= 8;
        } catch (Throwable t) {
            s = -1;
        }
        return s;
    }

    // 96 MB on cards reporting 4 GB or less (or nothing), 192 MB above; a level-0 group is 16 MB at normal zoom
    private static long budget() {
        if (budgetBytes < 0L) {
            long vramMb = -1L;
            String summary = HardwareProbe.getSummary();
            int i = summary == null ? -1 : summary.indexOf("vramMb=");
            if (i >= 0) {
                int end = summary.indexOf(';', i);
                try {
                    vramMb = Long.parseLong(summary.substring(i + 7, end < 0 ? summary.length() : end).trim());
                } catch (NumberFormatException ignored) {}
            } else if (summary == null || summary.isEmpty()) {
                return 96L << 20; // probe not finished: decide later
            }
            budgetBytes = (vramMb > 4096L ? 192L : 96L) << 20;
        }
        return budgetBytes;
    }

    static boolean patchRenderCell(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "checkNewlyOnScreenChunks", "(I)Z");
        if (m == null) return false;
        String levels = "zombie/iso/fboRenderChunk/FBORenderLevels";
        int frees = AsmSupport.redirectCalls(m, levels, "freeFBOsForLevel", "(I)V",
            "com/pzoptimizer/ChunkTextureKeep", "free", "(L" + levels + ";I)V");
        int invalidates = AsmSupport.redirectCalls(m, levels, "invalidateLevel", "(IJ)V",
            "com/pzoptimizer/ChunkTextureKeep", "invalidate", "(L" + levels + ";IJ)V");
        return frees == 2 && invalidates == 2;
    }
}
