package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;

/**
 * Chunk load distance setting (JVM_ChunkLoadDistance, 0 = Auto).
 *
 * IsoChunkMap.CalcChunkWidth sizes the loaded chunk grid from the screen once per world load (19x19 at 1080p and up,
 * fewer below), and every chunk in it is streamed, kept and simulated whether it's on screen or not. This can only
 * shrink the grid, never grow it past the game's own width, so Auto or anything at/above it changes nothing. It runs
 * before the chunk map, swap buffers and LosUtil are sized, so it takes effect on the next world load.
 */
public final class ChunkLoadDistance {

    public static final int MIN_WIDTH = 11;
    private static volatile int setting = 0;
    // Set by a benchmark run, which overrides the settings screen
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
        // Grid is centred on the player, so the width has to be odd (see ChunkCrashShield)
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
