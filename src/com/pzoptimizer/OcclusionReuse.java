package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import zombie.iso.IsoChunk;
import zombie.iso.fboRenderChunk.FBORenderLevels;

/**
 * Settings screen "Keep the occlusion grid when nothing that shapes it changed" (JVM_OcclusionReuse).
 *
 * Every frame in which any on-screen chunk texture is dirty, FBORenderCell.renderTilesInternal rebuilds the occluded
 * squares grid (which squares upper floors hide) and every on-screen chunk level recounts its visible squares
 * against it. Lighting drift marks textures dirty nearly every frame, so this runs nearly every frame.
 *
 * The grid and the counts are a function of: the on-screen chunk levels, the grid bounds (from the camera), the
 * zoom, each level's renderable-square flags (ChunkLevelData.squareFlags) and the squares' floors. The flags are only
 * rewritten by prepareChunkForUpdating, which runs for every dirty level just before the rebuild; it is wrapped here
 * so a flag that actually changed is seen, whatever the dirty reason (room visibility changes are invalidated as
 * lighting too, and do change the flags). Floors change only with object, creation or redraw invalidations. So the
 * rebuild is skipped only when every dirty level is dirty for lighting, blood or corpses alone, no flag changed, and
 * the on-screen set, bounds and zoom are those of the last rebuild; the grid and counts it would make are then the
 * ones already there.
 * Single player only: split screen shares one occlusion grid between players.
 */
public final class OcclusionReuse {

    public static volatile boolean active = true;
    public static final AtomicLong rebuilds = new AtomicLong();
    public static final AtomicLong rebuildsSkipped = new AtomicLong();

    // Dirty reasons that never touch a floor; ChunkBakeBudget holds the blood and corpse ones for a few frames
    private static final long SHAPE_NEUTRAL = zombie.iso.fboRenderChunk.FBORenderChunk.DIRTY_LIGHTING
        | zombie.iso.fboRenderChunk.FBORenderChunk.DIRTY_BLOOD | zombie.iso.fboRenderChunk.FBORenderChunk.DIRTY_CORPSE;

    // Game thread only
    private static boolean flagsChanged = true;
    private static boolean built;
    private static long lastSignature;
    private static IsoChunk prepChunk;
    private static int prepPlayer, prepMin, prepMax;
    private static final byte[][] snapshot = new byte[2][64];

    private static volatile boolean broken;

    private OcclusionReuse() {}

    public static void setActive(boolean value) {
        active = value;
        built = false;
    }

    /** Start of FBORenderCell.prepareChunkForUpdating(playerIndex, chunk, z). */
    public static void beforePrepare(int playerIndex, IsoChunk chunk, int z) {
        prepChunk = null;
        if (!active || chunk == null) return;
        FBORenderLevels renderLevels = chunk.getRenderLevels(playerIndex);
        if (!renderLevels.isOnScreen(z)) return;
        int min = renderLevels.getMinLevel(z), max = renderLevels.getMaxLevel(z);
        if (max - min + 1 > snapshot.length) {
            flagsChanged = true;
            return;
        }
        for (int z2 = min; z2 <= max; z2++) {
            System.arraycopy(chunk.getCutawayDataForLevel(z2).squareFlags[playerIndex], 0, snapshot[z2 - min], 0, 64);
        }
        prepChunk = chunk;
        prepPlayer = playerIndex;
        prepMin = min;
        prepMax = max;
    }

    /** Before each return of prepareChunkForUpdating. */
    public static void afterPrepare() {
        IsoChunk chunk = prepChunk;
        if (chunk == null) return;
        prepChunk = null;
        for (int z2 = prepMin; z2 <= prepMax && !flagsChanged; z2++) {
            if (!java.util.Arrays.equals(chunk.getCutawayDataForLevel(z2).squareFlags[prepPlayer], snapshot[z2 - prepMin])) {
                flagsChanged = true;
            }
        }
    }

    /** Wraps the hasAnyDirtyChunkTextures(playerIndex) result that gates the rebuild; false skips it. */
    public static boolean filter(boolean dirty) {
        if (!dirty) return false;
        if (!active || broken || zombie.characters.IsoPlayer.numPlayers != 1) {
            built = false;
            return true;
        }
        try {
            Object perPlayer = RenderCellFields.perPlayer(0);
            ArrayList<IsoChunk> onScreen = RenderCellFields.onScreenChunks(perPlayer);
            float zoom = zombie.core.Core.getInstance().getZoom(0);
            long sig = Float.floatToIntBits(zoom);
            for (int b = 0; b < 4; b++) sig = sig * 31 + RenderCellFields.gridBound(perPlayer, b);
            boolean structural = false;
            for (int i = 0; i < onScreen.size(); i++) {
                IsoChunk c = onScreen.get(i);
                FBORenderLevels renderLevels = c.getRenderLevels(0);
                for (int z = c.minLevel; z <= c.maxLevel; z++) {
                    if (!renderLevels.isOnScreen(z)) continue;
                    sig = sig * 31 + System.identityHashCode(c);
                    sig = sig * 31 + z;
                    // A freed texture is dirty with no reason bits; it has to be drawn and counted afresh
                    if (!structural && renderLevels.isDirty(z, zoom)
                            && (renderLevels.isDirty(z, ~SHAPE_NEUTRAL, zoom) || !renderLevels.isDirty(z, -1L, zoom))) {
                        structural = true;
                    }
                }
            }
            boolean rebuild = !built || structural || flagsChanged || sig != lastSignature;
            flagsChanged = false;
            lastSignature = sig;
            built = true;
            if (rebuild) {
                rebuilds.incrementAndGet();
                return true;
            }
            rebuildsSkipped.incrementAndGet();
            return false;
        } catch (Throwable t) {
            broken = true;
            PZOLogger.warn("[OcclusionReuse] Stood down, occlusion grid rebuilt as in the stock game: " + t);
            return true;
        }
    }

    static boolean patchRenderCell(ClassNode node) {
        String self = "com/pzoptimizer/OcclusionReuse";
        MethodNode tiles = AsmSupport.findMethod(node, "renderTilesInternal", "(I)V");
        MethodNode prep = AsmSupport.findMethod(node, "prepareChunkForUpdating", "(ILzombie/iso/IsoChunk;I)V");
        if (tiles == null || prep == null) return false;
        int gates = 0;
        for (AbstractInsnNode insn = tiles.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && node.name.equals(mi.owner) && "hasAnyDirtyChunkTextures".equals(mi.name)
                    && "(I)Z".equals(mi.desc)) {
                MethodInsnNode f = new MethodInsnNode(Opcodes.INVOKESTATIC, self, "filter", "(Z)Z", false);
                tiles.instructions.insert(mi, f);
                insn = f;
                gates++;
            }
        }
        if (gates != 1) return false;
        int returns = 0;
        for (AbstractInsnNode insn = prep.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                prep.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, self, "afterPrepare", "()V", false));
                returns++;
            }
        }
        if (returns == 0) return false;
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ILOAD, 1));
        pre.add(new VarInsnNode(Opcodes.ALOAD, 2));
        pre.add(new VarInsnNode(Opcodes.ILOAD, 3));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, self, "beforePrepare", "(ILzombie/iso/IsoChunk;I)V", false));
        prep.instructions.insert(pre);
        return true;
    }
}
