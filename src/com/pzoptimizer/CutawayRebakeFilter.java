package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.fboRenderChunk.FBORenderLevels;

/**
 * Settings screen "Re-draw cutaway walls only when they change" (JVM_CutawayRebakeFilter).
 *
 * FBORenderCutaways.doCutawayVisitSquares runs whenever the cutaway state may have moved on (the player walks onto
 * another square, a room is entered, a wall is built). It clears the cutaway flag of every square it cut last time,
 * sets it again on every square it cuts now, and marks the chunk texture of each chunk holding either kind for a full
 * re-bake (DIRTY_CUTAWAYS). Walking along a house cuts mostly the same walls as one square ago, so most of those
 * chunks are re-baked into the same picture.
 *
 * The visit's three re-bake calls are held until it ends. Each square's flags are recorded before the visit first
 * touches them (a square cut both times goes 1 -> 0 -> 1); a held re-bake runs only when a square of its chunk ended
 * with different flags. In the FBO renderer the bake reads exactly those target flags, so the skipped textures would
 * have come out the same. The visit's neighbour-edge pass, which re-bakes the chunk next door when an edge changes,
 * is left as it is.
 */
public final class CutawayRebakeFilter {

    public static volatile boolean active = true;
    public static final AtomicLong rebakesKept = new AtomicLong();
    public static final AtomicLong rebakesSkipped = new AtomicLong();

    // Game thread only: the visit runs inside FBORenderCell.renderTilesInternal
    private static final IdentityHashMap<IsoGridSquare, Integer> before = new IdentityHashMap<>();
    private static final ArrayList<Object[]> held = new ArrayList<>();
    private static final Set<IsoChunk> changed = Collections.newSetFromMap(new IdentityHashMap<>());
    private static IsoGridSquare lastSquare;
    private static int lastPlayer;
    private static boolean holding;

    private CutawayRebakeFilter() {}

    /** Start of doCutawayVisitSquares(int, ArrayList). */
    public static void begin(zombie.iso.fboRenderChunk.FBORenderCutaways cutaways) {
        // A visit that threw before its end left re-bakes behind; run them all, as vanilla would have
        flushAll();
        holding = active && zombie.core.PerformanceSettings.fboRenderChunk;
        lastSquare = null;
    }

    /** Replaces square.setPlayerCutawayFlag(playerIndex, flags, time) in the visit. */
    public static void setFlag(IsoGridSquare square, int playerIndex, int flags, long time) {
        remember(square, playerIndex);
        square.setPlayerCutawayFlag(playerIndex, flags, time);
    }

    /** Replaces square.addPlayerCutawayFlag(playerIndex, flag, time) in the visit. */
    public static void addFlag(IsoGridSquare square, int playerIndex, int flag, long time) {
        remember(square, playerIndex);
        square.addPlayerCutawayFlag(playerIndex, flag, time);
    }

    private static void remember(IsoGridSquare square, int playerIndex) {
        if (holding && !before.containsKey(square)) {
            before.put(square, square.getPlayerCutawayFlag(playerIndex, 0L));
        }
        lastSquare = square;
        lastPlayer = playerIndex;
    }

    /** Replaces renderLevels.invalidateLevel(z, flags) in the visit; it always follows a flag write on that chunk's square. */
    public static void invalidate(FBORenderLevels renderLevels, int level, long dirtyFlags) {
        IsoChunk chunk = lastSquare == null ? null : lastSquare.getChunk();
        if (!holding || chunk == null) {
            renderLevels.invalidateLevel(level, dirtyFlags);
            return;
        }
        held.add(new Object[] {renderLevels, level, dirtyFlags, chunk});
    }

    /** Before each return of the visit. */
    public static void end() {
        if (!holding) {
            flushAll();
            return;
        }
        changed.clear();
        for (java.util.Map.Entry<IsoGridSquare, Integer> e : before.entrySet()) {
            IsoGridSquare square = e.getKey();
            if (square.getPlayerCutawayFlag(lastPlayer, 0L) != e.getValue() && square.getChunk() != null) {
                changed.add(square.getChunk());
            }
        }
        for (Object[] h : held) {
            if (changed.contains(h[3])) {
                ((FBORenderLevels) h[0]).invalidateLevel((Integer) h[1], (Long) h[2]);
                rebakesKept.incrementAndGet();
            } else {
                rebakesSkipped.incrementAndGet();
            }
        }
        held.clear();
        before.clear();
        changed.clear();
        holding = false;
        lastSquare = null;
    }

    private static void flushAll() {
        for (Object[] h : held) {
            ((FBORenderLevels) h[0]).invalidateLevel((Integer) h[1], (Long) h[2]);
        }
        held.clear();
        before.clear();
    }

    static boolean patchCutaways(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "doCutawayVisitSquares", "(ILjava/util/ArrayList;)V");
        if (m == null) return false;
        String self = "com/pzoptimizer/CutawayRebakeFilter";
        int sets = AsmSupport.redirectCalls(m, "zombie/iso/IsoGridSquare", "setPlayerCutawayFlag", "(IIJ)V",
            self, "setFlag", "(Lzombie/iso/IsoGridSquare;IIJ)V");
        int adds = AsmSupport.redirectCalls(m, "zombie/iso/IsoGridSquare", "addPlayerCutawayFlag", "(IIJ)V",
            self, "addFlag", "(Lzombie/iso/IsoGridSquare;IIJ)V");
        int invalidates = AsmSupport.redirectCalls(m, "zombie/iso/fboRenderChunk/FBORenderLevels", "invalidateLevel", "(IJ)V",
            self, "invalidate", "(Lzombie/iso/fboRenderChunk/FBORenderLevels;IJ)V");
        // 42.22 shape: one clear, two sets, three re-bakes; anything else is a different visit
        if (sets != 1 || adds != 2 || invalidates != 3) return false;
        int returns = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                m.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, self, "end", "()V", false));
                returns++;
            }
        }
        if (returns == 0) return false;
        AsmSupport.prependCall(m, self, "begin", "(Lzombie/iso/fboRenderChunk/FBORenderCutaways;)V");
        return true;
    }
}
