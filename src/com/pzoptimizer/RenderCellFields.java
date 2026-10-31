package com.pzoptimizer;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import zombie.iso.IsoChunk;

/** Read access to FBORenderCell's private per-player data (on-screen chunks, occlusion grid bounds). */
final class RenderCellFields {

    private static VarHandle perPlayerData, onScreenChunks, gridX1, gridY1, gridX2, gridY2;
    private static boolean bound;

    private RenderCellFields() {}

    private static void bind() throws ReflectiveOperationException {
        if (bound) return;
        Class<?> cell = zombie.iso.fboRenderChunk.FBORenderCell.class;
        Class<?> ppd = Class.forName("zombie.iso.fboRenderChunk.FBORenderCell$PerPlayerData", false, cell.getClassLoader());
        MethodHandles.Lookup cellLookup = MethodHandles.privateLookupIn(cell, MethodHandles.lookup());
        MethodHandles.Lookup ppdLookup = MethodHandles.privateLookupIn(ppd, MethodHandles.lookup());
        onScreenChunks = ppdLookup.findVarHandle(ppd, "onScreenChunks", ArrayList.class);
        gridX1 = ppdLookup.findVarHandle(ppd, "occludedGridX1", int.class);
        gridY1 = ppdLookup.findVarHandle(ppd, "occludedGridY1", int.class);
        gridX2 = ppdLookup.findVarHandle(ppd, "occludedGridX2", int.class);
        gridY2 = ppdLookup.findVarHandle(ppd, "occludedGridY2", int.class);
        perPlayerData = cellLookup.findVarHandle(cell, "perPlayerData", ppd.arrayType());
        bound = true;
    }

    static Object perPlayer(int playerIndex) throws ReflectiveOperationException {
        bind();
        return ((Object[]) perPlayerData.get(zombie.iso.fboRenderChunk.FBORenderCell.instance))[playerIndex];
    }

    @SuppressWarnings("unchecked")
    static ArrayList<IsoChunk> onScreenChunks(Object perPlayer) {
        return (ArrayList<IsoChunk>) onScreenChunks.get(perPlayer);
    }

    /** x1, y1, x2, y2 of the occlusion grid. */
    static int gridBound(Object perPlayer, int which) {
        return switch (which) {
            case 0 -> (int) gridX1.get(perPlayer);
            case 1 -> (int) gridY1.get(perPlayer);
            case 2 -> (int) gridX2.get(perPlayer);
            default -> (int) gridY2.get(perPlayer);
        };
    }
}
