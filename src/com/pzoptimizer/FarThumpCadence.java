package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.util.concurrent.atomic.AtomicLong;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.iso.IsoGridSquare;

/**
 * Settings screen "Far zombies probe doors every other frame" (JVM_FarThumpCadence).
 *
 * Each frame every walking zombie calls tryThump(null) from updateInternal: it finds the tile ahead of it and tests
 * that edge for a door, window, window frame or fence to thump or climb. A zombie farther than FAR_TILES from every
 * local player now runs that probe on every other game tick, alternating by zombie so the work is spread evenly. It
 * reaches a door or window at most one frame later, which nobody can see at that distance; within FAR_TILES nothing
 * changes.
 */
public final class FarThumpCadence {

    public static volatile boolean active = true;
    public static final AtomicLong skipped = new AtomicLong();

    private static final float FAR_TILES = 20.0f;

    private FarThumpCadence() {}

    /** Replaces zombie.tryThump(square) in IsoZombie.updateInternal. */
    public static boolean probe(IsoZombie z, IsoGridSquare square) {
        if (active && square == null && FrameStamp.onGameThread()) {
            long tick = FrameStamp.tick();
            if (tick >= 0 && ((System.identityHashCode(z) ^ tick) & 1L) != 0L && farFromPlayers(z)) {
                skipped.incrementAndGet();
                return false;
            }
        }
        return z.tryThump(square);
    }

    private static boolean farFromPlayers(IsoZombie z) {
        IsoPlayer[] players = IsoPlayer.players;
        boolean any = false;
        for (int i = 0; i < IsoPlayer.numPlayers; i++) {
            IsoPlayer p = players[i];
            if (p == null) continue;
            any = true;
            float dx = p.getX() - z.getX(), dy = p.getY() - z.getY();
            if (dx * dx + dy * dy <= FAR_TILES * FAR_TILES) return false;
        }
        return any;
    }

    static boolean patchZombie(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "updateInternal", "()V");
        if (m == null) return false;
        return AsmSupport.redirectCalls(m, "zombie/characters/IsoZombie", "tryThump", "(Lzombie/iso/IsoGridSquare;)Z",
            "com/pzoptimizer/FarThumpCadence", "probe", "(Lzombie/characters/IsoZombie;Lzombie/iso/IsoGridSquare;)Z") == 1;
    }
}
