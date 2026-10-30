package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;

/**
 * Settings screen "Chunk load distance" (JVM_ChunkLoadDistance, 0 = Auto).
 *
 * IsoChunkMap.CalcChunkWidth sizes the loaded chunk grid from the screen once per world load: 19x19 chunks at 1080p
 * and above, fewer below. Every loaded chunk is streamed, kept in memory and simulated (objects, lighting, zombies),
 * whether it is on screen or not. A smaller grid only shrinks that area; it never grows past the game's own width, so
 * Auto and any value at or above it leave the game as is. Runs before the chunk map, its swap buffers and LosUtil
 * are sized, so everything is built for the new width. Applies on the next world load.
 */
public final class ChunkLoadDistance {

    public static final int MIN_WIDTH = 11;
    private static volatile int setting = 0;
    // Set by a benchmark run; the settings screen's value is then ignored
    private static volatile boolean forced = false;
    public static volatile int vanillaWidth = 0;
    public static volatile int appliedWidth = 0;

    private ChunkLoadDistance() {}

    public static void setWidth(int value) {
        if (!forced) store(value);
    }

    public static void force(int value) {
        forced = true;
        store(value);
    }

    private static void store(int value) {
        if (value <= 0) {
            setting = 0;
            return;
        }
        int w = Math.max(MIN_WIDTH, Math.min(19, value));
        // The grid is centred on the player, so its width must be odd (see ChunkCrashShield)
        setting = (w % 2 == 0) ? w + 1 : w;
    }

    /** Appended before each return of IsoChunkMap.CalcChunkWidth. */
    public static void afterCalc() {
        int vanilla = zombie.iso.IsoChunkMap.chunkGridWidth;
        vanillaWidth = vanilla;
        int want = setting;
        if (want <= 0 || want >= vanilla) {
            appliedWidth = vanilla;
            return;
        }
        zombie.iso.IsoChunkMap.chunkGridWidth = want;
        zombie.iso.IsoChunkMap.chunkWidthInTiles = want * 8;
        appliedWidth = want;
        PZOLogger.info(String.format("[ChunkLoadDistance] Chunk grid %dx%d (game chose %dx%d for this screen)", want, want, vanilla, vanilla));
    }

    static boolean patchChunkMap(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "CalcChunkWidth", "()V");
        if (m == null) return false;
        boolean found = false;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                m.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "com/pzoptimizer/ChunkLoadDistance", "afterCalc", "()V", false));
                found = true;
            }
        }
        return found;
    }
}
