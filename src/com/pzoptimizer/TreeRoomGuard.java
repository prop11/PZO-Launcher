package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.awt.Rectangle;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps IsoTree.isPlayerInsideARoom from throwing when the player's square has no IsoRoom (GitHub issue #64).
 *
 * isInARoom() is also true inside a player-built region, which has no IsoRoom, and some 42.22 builds then call
 * getSquare().getRoom().getRectsBounds() with no null check. Every tree on screen asks this each frame, so standing in
 * or near a player-built room throws over and over from the render pass. A null room gets bounds past the map edge, so
 * the method's first comparison returns false, the same answer builds with the null check give.
 */
public final class TreeRoomGuard {

    private static final Rectangle NO_ROOM = new Rectangle(Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0);
    public static final AtomicLong caught = new AtomicLong();

    private TreeRoomGuard() {}

    /** Stands in for room.getRectsBounds() inside isPlayerInsideARoom. */
    public static Rectangle bounds(zombie.iso.areas.IsoRoom room) {
        if (room != null) return room.getRectsBounds();
        caught.incrementAndGet();
        return NO_ROOM;
    }

    static boolean patchTree(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "isPlayerInsideARoom", "(Lzombie/characters/IsoPlayer;)Z");
        if (m == null) return false;
        return AsmSupport.redirectCalls(m, "zombie/iso/areas/IsoRoom", "getRectsBounds", "()Ljava/awt/Rectangle;",
            "com/pzoptimizer/TreeRoomGuard", "bounds", "(Lzombie/iso/areas/IsoRoom;)Ljava/awt/Rectangle;") > 0;
    }
}
