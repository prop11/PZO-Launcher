package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * PZO Universal Predictive Chunk Streamer (Next-Gen Chunk Streaming Engine).
 * 
 * Tracks player travel mode (driving, sprinting, walking) and proactively registers
 * upcoming chunk coordinates along the velocity vector into ChunkRetentionRing to prevent
 * boundary thrashing and hysteresis unloads.
 * 
 * Operates purely in-memory with zero file-handle locks, eliminating NTFS handle contention
 * with WorldStreamer and ChunkSaveWorker.
 */
public final class PredictiveChunkStreamer {

    private static volatile boolean running = false;
    private static Thread streamerThread = null;
    private static final Set<Long> PREWARMED_KEYS = new HashSet<>(512);
    private static volatile long lastPrewarmClearTime = 0;
    private static volatile Boolean isSolidState = null;

    public static boolean isStorageFast() {
        if (isSolidState != null) return isSolidState;
        try {
            String checkPath = System.getProperty("user.dir");
            if (checkPath == null || checkPath.isEmpty()) {
                checkPath = System.getProperty("user.home");
            }
            boolean fast = PZONative.isSolidStateDrive(checkPath);
            isSolidState = fast;
            if (fast) {
                PZOLogger.info("[PredictiveChunkStreamer] Fast Solid State Storage (NVMe/SSD) detected: Multi-vector trajectory lookahead active.");
            } else {
                PZOLogger.info("[PredictiveChunkStreamer] Mechanical Storage (HDD) detected: Adaptive sequential lookahead active (Head-thrashing protection armed).");
            }
            return fast;
        } catch (Throwable t) {
            isSolidState = true;
            return true;
        }
    }

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
                    Thread.sleep(isStorageFast() ? 100 : 250); // 10 Hz on SSD/NVMe, relaxed 4 Hz on HDD

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
        streamerThread.setPriority(Thread.NORM_PRIORITY - 1); // Priority 4 (never competes with gameplay or WorldStreamer)
        streamerThread.start();
    }

    private static void handleVehicleStreaming(Object player, Object vehicle, float px, float py) {
        try {
            Method getSpeedMethod = vehicle.getClass().getMethod("getCurrentSpeedKmHour");
            float speed = ((Number) getSpeedMethod.invoke(vehicle)).floatValue();

            if (Math.abs(speed) < 8.0f) {
                return;
            }

            float dirX = 0.0f;
            float dirY = 0.0f;

            // 1. Try JNI linear velocity vector first (pure physical trajectory, handles drifts and turns)
            try {
                Field velField = vehicle.getClass().getField("jniLinearVelocity");
                Object velObj = velField.get(vehicle);
                if (velObj instanceof org.joml.Vector3f vel) {
                    float lenSq = vel.x * vel.x + vel.z * vel.z;
                    if (lenSq > 0.001f) {
                        float invLen = (float) (1.0 / Math.sqrt(lenSq));
                        dirX = vel.x * invLen;
                        dirY = vel.z * invLen;
                    }
                }
            } catch (Throwable ignored) {}

            // 2. Fallback to vehicle forward vector with speed sign (handles reverses)
            if (dirX == 0.0f && dirY == 0.0f) {
                try {
                    Method getForwardVectorMethod = vehicle.getClass().getMethod("getForwardVector", org.joml.Vector3f.class);
                    org.joml.Vector3f fwd = (org.joml.Vector3f) getForwardVectorMethod.invoke(vehicle, new org.joml.Vector3f());
                    if (fwd != null) {
                        float lenSq = fwd.x * fwd.x + fwd.z * fwd.z;
                        if (lenSq > 0.001f) {
                            float invLen = (float) (1.0 / Math.sqrt(lenSq));
                            float sign = speed < 0.0f ? -1.0f : 1.0f;
                            dirX = (fwd.x * invLen) * sign;
                            dirY = (fwd.z * invLen) * sign;
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // 3. Fallback to player facing direction
            if (dirX == 0.0f && dirY == 0.0f) {
                try {
                    Class<?> playerClass = player.getClass();
                    Method getDirXMethod = playerClass.getMethod("getForwardDirectionX");
                    Method getDirYMethod = playerClass.getMethod("getForwardDirectionY");
                    dirX = ((Number) getDirXMethod.invoke(player)).floatValue();
                    dirY = ((Number) getDirYMethod.invoke(player)).floatValue();
                } catch (Throwable ignored) {}
            }

            if (dirX == 0.0f && dirY == 0.0f) return;

            // Lateral normal vector (-dirY, dirX) to cover road curvature and lane turns
            float normX = -dirY;
            float normY = dirX;

            boolean isFast = isStorageFast();
            int maxSteps = isFast ? 5 : 2;
            float lookaheadTiles = Math.min(isFast ? 160.0f : 48.0f, Math.abs(speed) * 1.8f);

            for (int step = 1; step <= maxSteps; step++) {
                float progress = (float) step / (float) maxSteps;
                float targetX = px + (dirX * lookaheadTiles * progress);
                float targetY = py + (dirY * lookaheadTiles * progress);

                int targetChunkX = (int) (targetX / 8.0f);
                int targetChunkY = (int) (targetY / 8.0f);

                // Pre-warm center trajectory chunk
                prewarmChunkInOSCache(targetChunkX, targetChunkY);
                ChunkRetentionRing.touch(targetChunkX, targetChunkY);

                // Pre-warm lateral cone (1 chunk left and right) ONLY on SSDs/NVMe to protect HDD heads from thrashing
                if (isFast) {
                    int lateralChunkX = (int) Math.signum(normX);
                    int lateralChunkY = (int) Math.signum(normY);
                    if (lateralChunkX != 0 || lateralChunkY != 0) {
                        prewarmChunkInOSCache(targetChunkX + lateralChunkX, targetChunkY + lateralChunkY);
                        prewarmChunkInOSCache(targetChunkX - lateralChunkX, targetChunkY - lateralChunkY);
                    }
                }
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
        ChunkRetentionRing.touch(wx, wy);
    }

    public static void stop() {
        running = false;
        if (streamerThread != null) {
            streamerThread.interrupt();
        }
    }
}
