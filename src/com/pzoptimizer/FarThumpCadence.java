package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.util.concurrent.atomic.AtomicLong;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.iso.IsoGridSquare;

/**
 * Far zombies check for doors every other frame (JVM_FarThumpCadence).
 *
 * Every walking zombie calls tryThump(null) each frame to look for a door, window, frame or fence on the tile ahead.
 * Zombies further than FAR_TILES from every player now only do that every other tick, alternating so the work is spread
 * evenly. They reach a door at most one frame late, which nobody can see from that far away. Closer zombies are
 * unchanged.
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
            if (tick >= 0 && ((System.identityHashCode(z) ^ tick) & 1L) != 0L && farFromPlayers(z) && !ModApi.isFullRate(z)) {
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
