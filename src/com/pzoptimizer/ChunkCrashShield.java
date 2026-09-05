package com.pzoptimizer;

import java.lang.reflect.Field;

/**
 * Project Zomboid Build 42 - Corrupt Chunk & Savegame Recovery Shield.
 * Prevents game-ending crashes when encountering truncated, corrupted, or
 * invalid map chunk files (map_X_Y.bin) from crashed saves or removed map mods.
 */
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
     * Project Zomboid Build 42 - IsoChunkMap Parity & Coordinate Sanity Shield.
     * Prevents fatal 'ArrayIndexOutOfBoundsException: Index 271 out of bounds for length 256'
     * caused by even chunkGridWidth values (e.g. 16 instead of odd 13, 15, 17, 19).
     * 
     * In PZ's centered chunk grid (2*R + 1), width must always be odd.
     * If an even width is detected or swap buffers are undersized, this guard
     * corrects the grid parity and transparently expands swap buffers.
     */
    public static void enforceChunkGridSanity() {
        try {
            Class<?> chunkMapClass = Class.forName("zombie.iso.IsoChunkMap");
            Field widthField = chunkMapClass.getField("chunkGridWidth");
            int width = widthField.getInt(null);

            if (width > 0 && width % 2 == 0) {
                int safeWidth = width + 1; // Force to safe odd number (e.g. 16 -> 17)
                widthField.setInt(null, safeWidth);

                try {
                    Field tilesField = chunkMapClass.getField("chunkWidthInTiles");
                    tilesField.setInt(null, safeWidth * 8);
                } catch (Throwable ignored) {}

                PZOLogger.warn(String.format("[ChunkCrashShield] Corrected invalid even chunkGridWidth (%d -> %d) to prevent IndexOutOfBoundsException", width, safeWidth));
                width = safeWidth;
            }

            // Ensure active IsoCell chunkMap buffers match or exceed required capacity (width * width)
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
