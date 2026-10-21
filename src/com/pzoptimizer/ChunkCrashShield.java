package com.pzoptimizer;

import java.lang.reflect.Field;

/** Keeps truncated or corrupt map_X_Y.bin chunk files (crashed saves, removed map mods) from crashing the game. */
public class ChunkCrashShield {
    private static volatile int recoveredChunks = 0;

    public static void logCorruptChunk(int wx, int wy, Throwable t) {
        recoveredChunks++;
        PZOLogger.warn(String.format("[ChunkCrashShield] Caught and mitigated corrupt chunk data at [%d, %d]: %s", wx, wy, t.getMessage()));
    }

    public static int getRecoveredChunkCount() {
        return recoveredChunks;
    }

    /**
     * The chunk grid is centered (2*R + 1), so its width must be odd. An even chunkGridWidth (e.g. 16) causes
     * "ArrayIndexOutOfBoundsException: Index 271 out of bounds for length 256"; this rounds the width up to the
     * next odd value and grows the swap buffers to match.
     */
    public static void enforceChunkGridSanity() {
        try {
            PopTemplateGuard.ensurePopulated();
            Class<?> chunkMapClass = Class.forName("zombie.iso.IsoChunkMap");
            Field widthField = chunkMapClass.getField("chunkGridWidth");
            int width = widthField.getInt(null);

            if (width > 0) {
                if (width % 2 != 0) {
                    return; // already odd
                }
                int safeWidth = width + 1; // e.g. 16 -> 17
                widthField.setInt(null, safeWidth);

                try {
                    Field tilesField = chunkMapClass.getField("chunkWidthInTiles");
                    tilesField.setInt(null, safeWidth * 8);
                } catch (Throwable ignored) {}

                PZOLogger.warn(String.format("[ChunkCrashShield] Corrected invalid even chunkGridWidth (%d -> %d) to prevent IndexOutOfBoundsException", width, safeWidth));
                width = safeWidth;
            } else {
                return;
            }

            // The cell's chunkMap buffers must hold at least width * width chunks
            int requiredLen = width * width;
            try {
                Class<?> worldClass = Class.forName("zombie.iso.IsoWorld");
                Field instField = worldClass.getField("instance");
                Object world = instField.get(null);
                if (world != null) {
                    Field cellField = null;
                    try {
                        cellField = worldClass.getField("currentCell");
                    } catch (Throwable t) {
                        cellField = worldClass.getField("CurrentCell");
                    }
                    Object cell = cellField.get(world);
                    if (cell != null) {
                        Field mapsField = cell.getClass().getField("chunkMap");
                        Object[] maps = (Object[]) mapsField.get(cell);
                        if (maps != null) {
                            Field chunksAField = chunkMapClass.getDeclaredField("chunksSwapA");
                            chunksAField.setAccessible(true);
                            Field chunksBField = chunkMapClass.getDeclaredField("chunksSwapB");
                            chunksBField.setAccessible(true);

                            for (Object map : maps) {
                                if (map == null) continue;
                                Object[] arrA = (Object[]) chunksAField.get(map);
                                if (arrA != null && arrA.length < requiredLen) {
                                    Object newArrA = java.lang.reflect.Array.newInstance(arrA.getClass().getComponentType(), requiredLen);
                                    System.arraycopy(arrA, 0, newArrA, 0, arrA.length);
                                    chunksAField.set(map, newArrA);
                                }
                                Object[] arrB = (Object[]) chunksBField.get(map);
                                if (arrB != null && arrB.length < requiredLen) {
                                    Object newArrB = java.lang.reflect.Array.newInstance(arrB.getClass().getComponentType(), requiredLen);
                                    System.arraycopy(arrB, 0, newArrB, 0, arrB.length);
                                    chunksBField.set(map, newArrB);
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }
}
