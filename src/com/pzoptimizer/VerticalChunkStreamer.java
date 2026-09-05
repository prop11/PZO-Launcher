package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * PZO Build 42 32-Level Vertical (Z-Index) Chunk Pre-caching Streamer.
 * Build 42 expands vertical world height from 8 levels to 32 levels (-16 to +16).
 * 
 * This streamer tracks player vertical velocity and staircase transitions,
 * predictively pre-warming vertical chunk slices and grid square lookups
 * to eliminate stair climbing and high-rise elevator hitching.
 */
public final class VerticalChunkStreamer {

    private static volatile boolean active = false;
    private static Thread verticalThread = null;
    private static int lastPlayerZ = 0;
    private static long lastZChangeTime = 0;

    public static void initialize() {
        if (active) return;
        active = true;

        verticalThread = new Thread(() -> {
            PZOLogger.success("VerticalChunkStreamer: Active (Build 42 32-Level Z-Index Predictive Pre-warming)");

            while (active) {
                try {
                    trackVerticalTransitions();
                } catch (Throwable ignored) {}

                try {
                    Thread.sleep(50); // 20 Hz tracking rate
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }, "PZO-VerticalChunkStreamer");

        verticalThread.setDaemon(true);
        verticalThread.setPriority(Thread.NORM_PRIORITY - 1);
        verticalThread.start();
    }

    private static void trackVerticalTransitions() {
        try {
            Class<?> playerClass = Class.forName("zombie.characters.IsoPlayer");
            Field playersField = playerClass.getField("players");
            Object[] players = (Object[]) playersField.get(null);
            if (players == null || players.length == 0 || players[0] == null) return;

            Object player = players[0];
            Method getZ = player.getClass().getMethod("getZ");
            int currentZ = (int) Math.floor(((Number) getZ.invoke(player)).floatValue());

            if (currentZ != lastPlayerZ) {
                int zDelta = currentZ - lastPlayerZ;
                lastPlayerZ = currentZ;
                lastZChangeTime = System.currentTimeMillis();

                // Predictively warm adjacent vertical slices (currentZ + zDelta, currentZ + 2*zDelta)
                int targetZ1 = currentZ + zDelta;
                int targetZ2 = currentZ + (zDelta * 2);

                // Ensure within Build 42 bounds (-16 to +16)
                if (targetZ1 >= -16 && targetZ1 <= 16) {
                    prewarmVerticalLevel(player, targetZ1);
                }
                if (targetZ2 >= -16 && targetZ2 <= 16) {
                    prewarmVerticalLevel(player, targetZ2);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void prewarmVerticalLevel(Object player, int targetZ) {
        try {
            Class<?> worldClass = Class.forName("zombie.iso.IsoWorld");
            Field instField = worldClass.getField("instance");
            Object world = instField.get(null);
            if (world == null) return;

            Field cellField = null;
            try {
                cellField = worldClass.getField("currentCell");
            } catch (Throwable t) {
                cellField = worldClass.getField("CurrentCell");
            }
            Object cell = cellField.get(world);
            if (cell == null) return;

            Method getX = player.getClass().getMethod("getX");
            Method getY = player.getClass().getMethod("getY");
            int px = (int) ((Number) getX.invoke(player)).floatValue();
            int py = (int) ((Number) getY.invoke(player)).floatValue();

            // Query grid squares in 5x5 column around player on target Z level
            Method getGridSquare = cell.getClass().getMethod("getGridSquare", int.class, int.class, int.class);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    getGridSquare.invoke(cell, px + dx, py + dy, targetZ);
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void shutdown() {
        active = false;
        if (verticalThread != null) {
            verticalThread.interrupt();
        }
    }
}
