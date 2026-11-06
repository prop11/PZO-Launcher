package com.pzoptimizer;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import zombie.iso.IsoChunk;

/** Read access to FBORenderCell's private per-player data (on-screen chunks, occlusion grid bounds). */
final class RenderCellFields {

    // static final in a holder, so the JIT turns them into plain field accesses; a missing field fails the holder, which
    // bind() reports as a ReflectiveOperationException as before
    private static final class F {
        static final VarHandle perPlayerData, onScreenChunks, gridX1, gridY1, gridX2, gridY2, occlusionChanged;

        static {
            try {
                Class<?> cell = zombie.iso.fboRenderChunk.FBORenderCell.class;
                Class<?> ppd = Class.forName("zombie.iso.fboRenderChunk.FBORenderCell$PerPlayerData", false, cell.getClassLoader());
                MethodHandles.Lookup cellLookup = MethodHandles.privateLookupIn(cell, MethodHandles.lookup());
                MethodHandles.Lookup ppdLookup = MethodHandles.privateLookupIn(ppd, MethodHandles.lookup());
                onScreenChunks = ppdLookup.findVarHandle(ppd, "onScreenChunks", ArrayList.class);
                gridX1 = ppdLookup.findVarHandle(ppd, "occludedGridX1", int.class);
                gridY1 = ppdLookup.findVarHandle(ppd, "occludedGridY1", int.class);
                gridX2 = ppdLookup.findVarHandle(ppd, "occludedGridX2", int.class);
                gridY2 = ppdLookup.findVarHandle(ppd, "occludedGridY2", int.class);
                occlusionChanged = ppdLookup.findVarHandle(ppd, "occlusionChanged", boolean.class);
                perPlayerData = cellLookup.findVarHandle(cell, "perPlayerData", ppd.arrayType());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private RenderCellFields() {}

    private static void bind() throws ReflectiveOperationException {
        try {
            if (F.perPlayerData == null) throw new ReflectiveOperationException("no perPlayerData");
        } catch (LinkageError e) {
            throw new ReflectiveOperationException(e.getCause() != null ? e.getCause() : e);
        }
    }

    static Object perPlayer(int playerIndex) throws ReflectiveOperationException {
        bind();
        return ((Object[]) F.perPlayerData.get(zombie.iso.fboRenderChunk.FBORenderCell.instance))[playerIndex];
    }

    @SuppressWarnings("unchecked")
    static ArrayList<IsoChunk> onScreenChunks(Object perPlayer) {
        return (ArrayList<IsoChunk>) F.onScreenChunks.get(perPlayer);
    }

    /** True in a frame where every on-screen level recounts its visible squares. */
    static boolean occlusionChanged(Object perPlayer) {
        return (boolean) F.occlusionChanged.get(perPlayer);
    }

    /** x1, y1, x2, y2 of the occlusion grid. */
    static int gridBound(Object perPlayer, int which) {
        return switch (which) {
            case 0 -> (int) F.gridX1.get(perPlayer);
            case 1 -> (int) F.gridY1.get(perPlayer);
            case 2 -> (int) F.gridX2.get(perPlayer);
            default -> (int) F.gridY2.get(perPlayer);
        };
    }
}
