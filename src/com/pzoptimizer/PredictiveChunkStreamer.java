package com.pzoptimizer;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashSet;
import java.util.Set;

/**
 * PZO Universal Predictive Chunk Streamer (Next-Gen Chunk Streaming Engine).
 * 
 * Replaces reactive on-demand disk reading with proactive, continuous vector-based pre-caching.
 * Automatically monitors player travel mode:
 * - High-speed driving (pre-warms 4-6 chunks ahead along vehicle velocity vector)
 * - Sprinting / Running (pre-warms 2-3 chunks ahead along foot direction)
 * - Walking / Aiming (pre-warms 1-2 chunks ahead)
 * 
 * Pre-reads chunk binary files (.bin) directly into OS page cache and off-heap memory
 * using zero-allocation NIO direct buffers, eliminating cold NVMe/SSD seek latency when
 * crossing chunk borders.
 */
public final class PredictiveChunkStreamer {

    private static volatile boolean running = false;
    private static Thread streamerThread = null;
    private static final ByteBuffer PREWARM_BUFFER = ByteBuffer.allocateDirect(65536); // 64KB Direct NIO Buffer
    private static final Set<Long> PREWARMED_KEYS = new HashSet<>(512);
    private static volatile long lastPrewarmClearTime = 0;

    public static void initialize() {
        start();
    }

    public static void start() {
        if (running) return;
        running = true;

        streamerThread = new Thread(() -> {
            PZOLogger.success("PredictiveChunkStreamer: Active (Universal Multi-Modal Predictive Chunk Streaming Engine)");

            while (running) {
                try {
                    Thread.sleep(60); // 16.6 Hz high-cadence trajectory tracking

                    long now = System.currentTimeMillis();
                    if (now - lastPrewarmClearTime > 30_000L) {
                        PREWARMED_KEYS.clear();
                        lastPrewarmClearTime = now;
                    }

                    // 1. Discover active player via reflection
                    Class<?> playerClass = Class.forName("zombie.characters.IsoPlayer");
                    Method getInstMethod = playerClass.getMethod("getInstance");
                    Object player = getInstMethod.invoke(null);

                    if (player == null) {
                        Thread.sleep(600);
                        continue;
                    }

                    Method getXMethod = playerClass.getMethod("getX");
                    Method getYMethod = playerClass.getMethod("getY");
                    float px = ((Number) getXMethod.invoke(player)).floatValue();
                    float py = ((Number) getYMethod.invoke(player)).floatValue();

                    int currentChunkX = (int) (px / 8.0f);
                    int currentChunkY = (int) (py / 8.0f);
                    ChunkRetentionRing.touch(currentChunkX, currentChunkY);

                    // 2. Check if player is operating a vehicle
                    Method getVehicleMethod = playerClass.getMethod("getVehicle");
                    Object vehicle = getVehicleMethod.invoke(player);

                    if (vehicle != null) {
                        handleVehicleStreaming(player, vehicle, px, py);
                    } else {
                        handleOnFootStreaming(playerClass, player, px, py);
                    }

                } catch (Throwable ignored) {
                    try { Thread.sleep(1000); } catch (Throwable ignored2) {}
                }
            }
        }, "PZO-PredictiveChunkStreamer");

        streamerThread.setDaemon(true);
        streamerThread.setPriority(Thread.NORM_PRIORITY + 1); // Priority 6
        streamerThread.start();
    }

    private static void handleVehicleStreaming(Object player, Object vehicle, float px, float py) {
        try {
            Method getSpeedMethod = vehicle.getClass().getMethod("getCurrentSpeedKmHour");
            float speed = ((Number) getSpeedMethod.invoke(vehicle)).floatValue();

            if (Math.abs(speed) < 8.0f) {
                return;
            }

            // Dynamic WorldStreamer Priority Boost during driving
            boostWorldStreamerPriority(8);

            Class<?> playerClass = player.getClass();
            Method getDirXMethod = playerClass.getMethod("getForwardDirectionX");
            Method getDirYMethod = playerClass.getMethod("getForwardDirectionY");
            float dirX = ((Number) getDirXMethod.invoke(player)).floatValue();
            float dirY = ((Number) getDirYMethod.invoke(player)).floatValue();

            // Lookahead distance scaled to vehicle velocity
            float lookaheadTiles = Math.min(140.0f, Math.abs(speed) * 1.6f);

            for (int step = 1; step <= 5; step++) {
                float targetX = px + (dirX * lookaheadTiles * (step / 5.0f));
                float targetY = py + (dirY * lookaheadTiles * (step / 5.0f));

                int targetChunkX = (int) (targetX / 8.0f);
                int targetChunkY = (int) (targetY / 8.0f);

                prewarmChunkInOSCache(targetChunkX, targetChunkY);
                ChunkRetentionRing.touch(targetChunkX, targetChunkY);
            }
        } catch (Throwable ignored) {}
    }

    private static void handleOnFootStreaming(Class<?> playerClass, Object player, float px, float py) {
        try {
            Method isMovingMethod = playerClass.getMethod("isPlayerMoving");
            boolean isMoving = (Boolean) isMovingMethod.invoke(player);

            if (!isMoving) return;

            Method isSprintingMethod = playerClass.getMethod("isSprinting");
            boolean isSprinting = (Boolean) isSprintingMethod.invoke(player);

            Method getDirXMethod = playerClass.getMethod("getForwardDirectionX");
            Method getDirYMethod = playerClass.getMethod("getForwardDirectionY");
            float dirX = ((Number) getDirXMethod.invoke(player)).floatValue();
            float dirY = ((Number) getDirYMethod.invoke(player)).floatValue();

            float lookaheadTiles = isSprinting ? 32.0f : 16.0f;
            int steps = isSprinting ? 3 : 2;

            for (int step = 1; step <= steps; step++) {
                float targetX = px + (dirX * lookaheadTiles * ((float) step / steps));
                float targetY = py + (dirY * lookaheadTiles * ((float) step / steps));

                int targetChunkX = (int) (targetX / 8.0f);
                int targetChunkY = (int) (targetY / 8.0f);

                prewarmChunkInOSCache(targetChunkX, targetChunkY);
                ChunkRetentionRing.touch(targetChunkX, targetChunkY);
            }
        } catch (Throwable ignored) {}
    }

    private static void prewarmChunkInOSCache(int wx, int wy) {
        long key = FastChunkKey.pack(wx, wy);
        if (PREWARMED_KEYS.contains(key)) {
            return;
        }
        PREWARMED_KEYS.add(key);

        try {
            Class<?> zfsClass = Class.forName("zombie.ZomboidFileSystem");
            Field instField = zfsClass.getField("instance");
            Object zfsInstance = instField.get(null);

            File chunkFile = null;
            if (zfsInstance != null) {
                try {
                    Method getFileMethod = zfsClass.getMethod("getFileInCurrentSave", String.class);
                    // Build 42 format: wx/wy.bin
                    chunkFile = (File) getFileMethod.invoke(zfsInstance, wx + File.separator + wy + ".bin");
                } catch (Throwable ignored) {}
            }

            if (chunkFile == null || !chunkFile.exists()) {
                // Fallback to legacy Build 41 naming (map_X_Y.bin)
                Class<?> coreClass = Class.forName("zombie.core.Core");
                Method getSaveWorldMethod = coreClass.getMethod("getMyDocumentFolder");
                String saveDir = (String) getSaveWorldMethod.invoke(null);
                if (saveDir != null && !saveDir.isEmpty()) {
                    File legacyFile = new File(saveDir, "map_" + wx + "_" + wy + ".bin");
                    if (legacyFile.exists()) {
                        chunkFile = legacyFile;
                    }
                }
            }

            if (chunkFile != null && chunkFile.exists() && chunkFile.canRead()) {
                try (FileInputStream fis = new FileInputStream(chunkFile);
                     FileChannel ch = fis.getChannel()) {
                    PREWARM_BUFFER.clear();
                    ch.read(PREWARM_BUFFER); // Reads chunk binary header into OS page cache
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void boostWorldStreamerPriority(int targetPriority) {
        try {
            Class<?> wsClass = Class.forName("zombie.iso.WorldStreamer");
            Field instField = wsClass.getField("instance");
            Object wsInstance = instField.get(null);
            if (wsInstance != null) {
                Field threadField = wsClass.getField("worldStreamer");
                Thread wsThread = (Thread) threadField.get(wsInstance);
                if (wsThread != null && wsThread.isAlive() && wsThread.getPriority() < targetPriority) {
                    wsThread.setPriority(targetPriority);
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void stop() {
        running = false;
        if (streamerThread != null) {
            streamerThread.interrupt();
        }
    }
}
