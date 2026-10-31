package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldInsnNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.fboRenderChunk.FBORenderLevels;

/**
 * Low puddle quality: settings screen "Re-draw only chunks with puddles" (JVM_PuddleRedrawFilter) and "Spread
 * puddle re-draws over frames" (JVM_PuddleRedrawPacing).
 *
 * At the game's lowest puddle quality the puddles are drawn into the cached chunk textures. While rain fills or dries
 * them, FBORenderCell.renderTilesInternal re-bakes every texture of every loaded chunk (all levels, roofs and
 * basements included) each time the puddle size or wetness moves by 1/500, at most once a second: a long frame every
 * second through every rain shower, longest on the slow machines that pick this quality.
 *
 * Filter (exact): each bake of a chunk level records whether it drew puddles. A level whose last bake gathered its
 * puddle squares and drew none (indoors, upper floors, water) has no puddle in its picture and gets none with any
 * other size or wetness, since which squares take puddles does not depend on those values; it is left alone. A level
 * that drew puddles, or whose last bake did not get as far as gathering them (no rain then, snow, off screen), is
 * re-baked as before. Whatever else would change a level's puddle squares (objects, cutaways) re-bakes it anyway,
 * which records it again.
 *
 * Pacing (not exact): the on-screen levels to re-bake are marked dirty a few per frame, all within about 15 frames,
 * instead of all in one frame. Until its turn a level shows the puddles one 1/500 step behind. Off-screen levels are
 * marked at once, which costs nothing until they come into view.
 */
public final class PuddleRedraw {

    public static volatile boolean filterActive = true;
    public static volatile boolean pacingActive = true;
    public static final AtomicLong levelsRedrawn = new AtomicLong();
    public static final AtomicLong levelsSkipped = new AtomicLong();
    public static final AtomicLong levelsPaced = new AtomicLong();

    private static final long DIRTY_CUTAWAYS = zombie.iso.fboRenderChunk.FBORenderChunk.DIRTY_CUTAWAYS;
    private static final byte UNKNOWN = 0, NONE = 1, DREW = 2;
    private static final int SPREAD_FRAMES = 15, MIN_PER_FRAME = 4;

    // Main thread only: bakes, the redraw and the pacing all run on it
    private static final WeakHashMap<FBORenderLevels, byte[]> records = new WeakHashMap<>();
    private static int recordsPerfPuddles = Integer.MIN_VALUE, recordsQuality = Integer.MIN_VALUE;
    private static FBORenderLevels bakeLevels;
    private static int bakeZ;
    private static boolean bakeGathered, bakeDrew;

    private static final IdentityHashMap<FBORenderLevels, long[]> pending = new IdentityHashMap<>();
    private static final ArrayList<FBORenderLevels> pendingOrder = new ArrayList<>();
    private static int pendingCount, perFrame;

    private PuddleRedraw() {}

    public static void setPacing(boolean value) {
        pacingActive = value;
        if (!value) flushPending();
    }

    private static byte[] recordsOf(FBORenderLevels renderLevels) {
        int perf = zombie.core.Core.getInstance().getPerfPuddles(), quality = zombie.core.PerformanceSettings.puddlesQuality;
        if (perf != recordsPerfPuddles || quality != recordsQuality) {
            // Which squares take puddles indoors depends on the puddle setting; start over
            records.clear();
            recordsPerfPuddles = perf;
            recordsQuality = quality;
        }
        byte[] r = records.get(renderLevels);
        if (r == null) records.put(renderLevels, r = new byte[64]);
        return r;
    }

    /** Start of FBORenderCell.renderPuddlesToChunkTexture(playerIndex, z, chunk), called for each level of a bake. */
    public static void bakeBegin(int playerIndex, int z, IsoChunk chunk) {
        bakeLevels = null;
        if (chunk == null || z < -32 || z > 31) return;
        bakeLevels = chunk.getRenderLevels(playerIndex);
        bakeZ = z;
        bakeGathered = false;
        bakeDrew = false;
        // Stays unknown if the bake throws before its end
        recordsOf(bakeLevels)[z + 32] = UNKNOWN;
    }

    /** Start of IsoPuddles.renderToChunkTexture: the level's puddle squares were gathered. */
    public static void gathered(zombie.iso.IsoPuddles puddles) {
        bakeGathered = true;
    }

    /** Replaces SpriteRenderer.drawGeneric in IsoPuddles.renderToChunkTexture, reached only when puddles are drawn. */
    public static zombie.core.textures.TextureDraw drew(zombie.core.SpriteRenderer renderer, zombie.core.textures.TextureDraw.GenericDrawer drawer) {
        bakeDrew = true;
        return renderer.drawGeneric(drawer);
    }

    /** Before each return of renderPuddlesToChunkTexture. */
    public static void bakeEnd() {
        FBORenderLevels renderLevels = bakeLevels;
        if (renderLevels == null) return;
        bakeLevels = null;
        recordsOf(renderLevels)[bakeZ + 32] = !bakeGathered ? UNKNOWN : bakeDrew ? DREW : NONE;
    }

    /** Replaces this.invalidateAll(playerIndex) where renderTilesInternal reacts to a puddle size or wetness change. */
    public static void redraw(zombie.iso.fboRenderChunk.FBORenderCell cell, int playerIndex) {
        IsoChunkMap chunkMap = zombie.iso.IsoWorld.instance.currentCell.chunkMap[playerIndex];
        boolean filter = filterActive, pacing = pacingActive;
        for (int xx = 0; xx < IsoChunkMap.chunkGridWidth; xx++) {
            for (int yy = 0; yy < IsoChunkMap.chunkGridWidth; yy++) {
                IsoChunk c = chunkMap.getChunk(xx, yy);
                if (c == null || c.lightingNeverDone[playerIndex]) continue;
                FBORenderLevels renderLevels = c.getRenderLevels(playerIndex);
                if (!filter && !pacing) {
                    renderLevels.invalidateAll(DIRTY_CUTAWAYS);
                    continue;
                }
                byte[] r = filter ? recordsOf(renderLevels) : null;
                for (int z = c.minLevel; z <= c.maxLevel; z++) {
                    if (z < -32 || z > 31) {
                        mark(renderLevels, z);
                        continue;
                    }
                    if (r != null && r[z + 32] == NONE) {
                        levelsSkipped.incrementAndGet();
                        continue;
                    }
                    if (pacing && renderLevels.isOnScreen(z)) {
                        long[] bits = pending.get(renderLevels);
                        if (bits == null) {
                            pending.put(renderLevels, bits = new long[1]);
                            pendingOrder.add(renderLevels);
                        }
                        if ((bits[0] & (1L << (z + 32))) == 0) {
                            bits[0] |= 1L << (z + 32);
                            pendingCount++;
                        }
                    } else {
                        mark(renderLevels, z);
                    }
                }
            }
        }
        perFrame = Math.max(MIN_PER_FRAME, (pendingCount + SPREAD_FRAMES - 1) / SPREAD_FRAMES);
        drain();
    }

    /** Start of renderTilesInternal. */
    public static void tick(zombie.iso.fboRenderChunk.FBORenderCell cell) {
        if (pendingCount > 0) drain();
    }

    private static void drain() {
        int budget = perFrame;
        int i = 0;
        while (budget > 0 && i < pendingOrder.size()) {
            FBORenderLevels renderLevels = pendingOrder.get(i);
            long[] bits = pending.get(renderLevels);
            while (budget > 0 && bits[0] != 0) {
                int slot = Long.numberOfTrailingZeros(bits[0]);
                bits[0] &= ~(1L << slot);
                mark(renderLevels, slot - 32);
                pendingCount--;
                budget--;
                levelsPaced.incrementAndGet();
            }
            if (bits[0] == 0) {
                pending.remove(renderLevels);
                pendingOrder.remove(i);
            } else {
                i++;
            }
        }
    }

    private static void flushPending() {
        for (FBORenderLevels renderLevels : pendingOrder) {
            long bits = pending.get(renderLevels)[0];
            while (bits != 0) {
                int slot = Long.numberOfTrailingZeros(bits);
                bits &= ~(1L << slot);
                mark(renderLevels, slot - 32);
            }
        }
        pending.clear();
        pendingOrder.clear();
        pendingCount = 0;
    }

    // What the stock invalidateAll does to one level: dirty, cached square lists kept
    private static void mark(FBORenderLevels renderLevels, int z) {
        boolean clear = FBORenderLevels.clearCachedSquares;
        FBORenderLevels.clearCachedSquares = false;
        try {
            renderLevels.invalidateLevel(z, DIRTY_CUTAWAYS);
        } finally {
            FBORenderLevels.clearCachedSquares = clear;
        }
        levelsRedrawn.incrementAndGet();
    }

    static boolean patchRenderCell(ClassNode node) {
        String self = "com/pzoptimizer/PuddleRedraw";
        MethodNode tiles = AsmSupport.findMethod(node, "renderTilesInternal", "(I)V");
        MethodNode bake = AsmSupport.findMethod(node, "renderPuddlesToChunkTexture", "(IILzombie/iso/IsoChunk;)V");
        if (tiles == null || bake == null) return false;
        // The puddle redraw is the invalidateAll right after this.puddlesRedrawTimeMs = ...
        int sites = 0;
        boolean afterStamp = false;
        for (AbstractInsnNode insn = tiles.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && "puddlesRedrawTimeMs".equals(f.name)) {
                afterStamp = true;
            } else if (afterStamp && insn instanceof MethodInsnNode mi && node.name.equals(mi.owner)
                    && "invalidateAll".equals(mi.name) && "(I)V".equals(mi.desc)) {
                MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC, self, "redraw",
                    "(Lzombie/iso/fboRenderChunk/FBORenderCell;I)V", false);
                tiles.instructions.set(mi, call);
                insn = call;
                afterStamp = false;
                sites++;
            } else if (afterStamp && insn instanceof MethodInsnNode) {
                return false;
            }
        }
        if (sites != 1) return false;
        int returns = 0;
        for (AbstractInsnNode insn = bake.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                bake.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, self, "bakeEnd", "()V", false));
                returns++;
            }
        }
        if (returns == 0) return false;
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ILOAD, 1));
        pre.add(new VarInsnNode(Opcodes.ILOAD, 2));
        pre.add(new VarInsnNode(Opcodes.ALOAD, 3));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, self, "bakeBegin", "(IILzombie/iso/IsoChunk;)V", false));
        bake.instructions.insert(pre);
        AsmSupport.prependCall(tiles, self, "tick", "(Lzombie/iso/fboRenderChunk/FBORenderCell;)V");
        return true;
    }

    static boolean patchPuddles(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "renderToChunkTexture", "(Ljava/util/ArrayList;I)V");
        if (m == null) return false;
        String self = "com/pzoptimizer/PuddleRedraw";
        int draws = AsmSupport.redirectCalls(m, "zombie/core/SpriteRenderer", "drawGeneric",
            "(Lzombie/core/textures/TextureDraw$GenericDrawer;)Lzombie/core/textures/TextureDraw;",
            self, "drew", "(Lzombie/core/SpriteRenderer;Lzombie/core/textures/TextureDraw$GenericDrawer;)Lzombie/core/textures/TextureDraw;");
        if (draws != 1) return false;
        AsmSupport.prependCall(m, self, "gathered", "(Lzombie/iso/IsoPuddles;)V");
        return true;
    }
}
