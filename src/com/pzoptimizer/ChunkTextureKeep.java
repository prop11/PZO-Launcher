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
 * Keeps chunk images that just went off screen (JVM_ChunkTextureKeep).
 *
 * Each chunk's two-level group is drawn once into a texture and only redrawn when something in it changes. Stock
 * checkNewlyOnScreenChunks frees that texture the moment the group leaves the screen and fully redraws it the frame it
 * comes back, so walking back and forth, turning a corner or driving along the screen edge keeps redrawing the same
 * chunks, right in the frames that are already busy streaming.
 *
 * Now a group leaving the screen keeps its texture (oldest dropped first past a VRAM budget, none at all on integrated
 * graphics or cards with 4 GB or less). Every change still marks it dirty while it's off screen (lighting, objects,
 * items, blood, occlusion and seams all invalidate regardless), so when it comes back it's only redrawn if something
 * changed. I fall back to the stock redraw if the texture was dropped, the chunk object got reused somewhere else, the
 * zoom picked another texture, or an option that stock only re-applies to on-screen chunks (wind sway, water shader,
 * no-lighting debug) changed in the meantime.
 *
 * Game thread only, everything comes from checkNewlyOnScreenChunks.
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
        long budget = budget();
        if (budget == 0L) {
            if (!keptGroups.isEmpty()) dropAll();
            levels.freeFBOsForLevel(level);
            return;
        }
        Kept nk = new Kept(levels, minLevel, fbo1, fbo2, chunk.wx, chunk.wy, optionsStamp());
        if (nk.bytes > budget) {
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
        // Entries freed behind my back (chunk unloaded, manager reset) just eat budget, clear them first
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
        // Came back on screen without going through invalidate, so it's in use; just forget it
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

    // 1/32 of VRAM, at most 256 MB (6 GB card -> 192 MB). 0 = keep nothing: integrated graphics, 4 GB or less, or VRAM
    // unknown. Those cards are already tight and spilling over pushes the driver into swapping, which is a slideshow.
    // A level-0 group is 16 MB at normal zoom.
    private static long budget() {
        if (budgetBytes < 0L) {
            String summary = HardwareProbe.getSummary();
            if (summary == null || summary.isEmpty()) {
                // Probe still running: stock behaviour until it's done, then decide once
                if (HardwareProbe.getGpuCount() != 0) return 0L;
                budgetBytes = 0L;
                return 0L;
            }
            long vramMb = parseLong(field(summary, "vramMb"));
            boolean integrated = isIntegratedGpu(field(summary, "gpu"));
            budgetBytes = integrated || vramMb <= 4096L ? 0L : Math.min(256L << 20, (vramMb << 20) / 32L);
            PZOLogger.info("[PZO ChunkTextureKeep] " + (budgetBytes == 0L
                ? "off for this GPU (integrated or 4 GB VRAM or less)"
                : "budget " + (budgetBytes >> 20) + " MB of " + vramMb + " MB VRAM"));
        }
        return budgetBytes;
    }

    private static String field(String summary, String key) {
        for (String part : summary.split(";")) {
            if (part.startsWith(key + "=")) return part.substring(key.length() + 1).trim();
        }
        return "";
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    // Same list as the settings screen's isIntegratedGpu, so the verdict and the engine agree
    static boolean isIntegratedGpu(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.contains("uhd") || n.contains("iris") || n.contains("intel(r) hd") || n.contains("radeon(tm) graphics")
            || n.matches(".*vega \\d graphics.*") || n.contains("680m") || n.contains("780m") || n.contains("890m")
            || n.contains("steam deck");
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
