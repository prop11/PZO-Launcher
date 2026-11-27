package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.ArrayList;
import java.util.List;
import zombie.characters.IsoPlayer;
import zombie.inventory.ItemContainer;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoWorldInventoryObject;

/**
 * Spreads the game's loot rolls over frames (JVM_LootRollPacer, single player).
 *
 * When a chunk comes in, the game fills every unexplored container in it right away (ItemPickerJava.fillContainer:
 * the loot tables, plus every mod's OnFillContainer handler) inside the frame that took the chunk in. A town chunk
 * with a few shops and houses is a lot of rolls in one frame. Squares with an unexplored container are now held and
 * rolled after the chunk update, nearest to the player first, about 1 ms a frame.
 *
 * Nothing is held within 12 tiles of a player, and a held square is rolled at once when a player comes that close, so
 * no container is opened before it's filled. A square waits while the map load pacer still holds mods' LoadGridsquare
 * calls for its chunk, which keeps the game's order (those handlers first, then the roll). A square whose chunk went
 * away is dropped; it never got marked done, so it's rolled when the chunk loads again. Not on a client or a server:
 * there the server fills containers.
 */
public final class LootRollPacer {

    public static volatile boolean active = true;

    private static final long BUDGET_NS = 1_000_000L;
    private static final float GUARD_TILES = 12.0f;
    private static final int MAX_HELD = 20_000;

    // Game thread only
    private static final ArrayList<IsoGridSquare> held = new ArrayList<>();
    private static boolean rolling;
    private static boolean warned;

    public static long heldTotal, rolled, rolledNear, droppedGone;
    public static int peak;

    private LootRollPacer() {}

    /** LoadGridsquarePerformanceWorkaround.LoadGridsquare entry: true holds the square back for now. */
    public static boolean defer(IsoGridSquare sq) {
        if (!active || rolling || sq == null || zombie.network.GameClient.client || zombie.network.GameServer.server) return false;
        if (Thread.currentThread() != zombie.GameWindow.gameThread || zombie.gameStates.IngameState.loading) return false;
        if (sq.isOverlayDone() || held.size() >= MAX_HELD || !hasUnexplored(sq) || nearPlayer(sq)) return false;
        held.add(sq);
        heldTotal++;
        if (held.size() > peak) peak = held.size();
        return true;
    }

    // The same test the game's roll makes before it fills a container
    private static boolean hasUnexplored(IsoGridSquare sq) {
        List<IsoObject> objects = sq.getObjects();
        for (int i = 0, n = objects.size(); i < n; i++) {
            IsoObject o = objects.get(i);
            if (o instanceof IsoWorldInventoryObject) continue;
            ItemContainer c = o.getContainer();
            if (c != null && !c.isExplored() && o.getSprite() != null && o.getSprite().getName() != null) return true;
        }
        return false;
    }

    private static float nearestPlayer2(IsoGridSquare sq) {
        float best = Float.MAX_VALUE;
        for (int i = 0; i < IsoPlayer.numPlayers; i++) {
            IsoPlayer p = IsoPlayer.players[i];
            if (p == null || p.isDead()) continue;
            float dx = p.getX() - (sq.x + 0.5f), dy = p.getY() - (sq.y + 0.5f);
            float d = dx * dx + dy * dy;
            if (d < best) best = d;
        }
        return best;
    }

    private static boolean nearPlayer(IsoGridSquare sq) {
        return nearestPlayer2(sq) < GUARD_TILES * GUARD_TILES;
    }

    /** Game thread, after each chunk map update. */
    public static void drain() {
        if (held.isEmpty()) return;
        if (IsoWorld.instance == null || IsoWorld.instance.currentCell == null) {
            held.clear();
            return;
        }
        float guard2 = GUARD_TILES * GUARD_TILES;
        // Anything a player walked up to goes now, whatever the budget
        for (int i = held.size() - 1; i >= 0; i--) {
            IsoGridSquare sq = held.get(i);
            if (!stillThere(sq)) {
                swapRemove(i);
                droppedGone++;
            } else if (!active || nearestPlayer2(sq) < guard2) {
                swapRemove(i);
                roll(sq);
                rolledNear++;
            }
        }
        long until = System.nanoTime() + BUDGET_NS;
        while (!held.isEmpty() && System.nanoTime() < until) {
            int pick = -1;
            float best = Float.MAX_VALUE;
            for (int i = 0, n = held.size(); i < n; i++) {
                IsoGridSquare sq = held.get(i);
                if (sq.chunk != null && MapLoadScriptPacer.holds(sq.chunk.wx, sq.chunk.wy)) continue;
                float d = nearestPlayer2(sq);
                if (d < best) {
                    best = d;
                    pick = i;
                }
            }
            if (pick < 0) break;
            IsoGridSquare sq = held.get(pick);
            swapRemove(pick);
            roll(sq);
        }
    }

    private static boolean stillThere(IsoGridSquare sq) {
        return sq.chunk != null && !sq.isOverlayDone()
            && IsoWorld.instance.currentCell.getGridSquare(sq.x, sq.y, sq.z) == sq;
    }

    private static void swapRemove(int i) {
        int last = held.size() - 1;
        if (i != last) held.set(i, held.get(last));
        held.remove(last);
    }

    private static void roll(IsoGridSquare sq) {
        rolling = true;
        try {
            if (!sq.isOverlayDone()) zombie.LoadGridsquarePerformanceWorkaround.LoadGridsquare(sq);
            rolled++;
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                PZOLogger.warn("[LootRollPacer] a held loot roll failed: " + t);
            }
        } finally {
            rolling = false;
        }
    }

    public static String summary() {
        return "held=" + held.size() + " heldTotal=" + heldTotal + " rolled=" + rolled + " near=" + rolledNear
            + " gone=" + droppedGone + " peak=" + peak;
    }

    static boolean patchWorkaround(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "LoadGridsquare", "(Lzombie/iso/IsoGridSquare;)V");
        if (m == null) return false;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && "com/pzoptimizer/LootRollPacer".equals(mi.owner)) return false;
        }
        InsnList pre = new InsnList();
        LabelNode stock = new LabelNode();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/pzoptimizer/LootRollPacer", "defer", "(Lzombie/iso/IsoGridSquare;)Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, stock));
        pre.add(new InsnNode(Opcodes.RETURN));
        pre.add(stock);
        m.instructions.insert(pre);
        return true;
    }
}
