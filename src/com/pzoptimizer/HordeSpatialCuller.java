package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PZO SIMD AVX2 Batch Horde Spatial Culler & Vectorized Entity Processor (Phase 3).
 * 
 * Vectorizes spatial proximity checks, camera frustum AABB culling, and multi-tier LOD classification
 * for 500 to 2,000+ active zombies in a single native SIMD pass.
 * 
 * Uses off-heap page-aligned direct NIO buffers from SpatialBufferPool with ZERO garbage collection overhead.
 */
public final class HordeSpatialCuller {

    private static volatile boolean active = false;
    private static Thread cullerThread = null;

    // Telemetry
    public static final AtomicInteger lastTrackedZombieCount = new AtomicInteger(0);
    public static final AtomicInteger lastCulledOffscreenCount = new AtomicInteger(0);
    public static final AtomicInteger lastHibernatingCount = new AtomicInteger(0);
    public static final AtomicLong totalDistanceCalculations = new AtomicLong(0);
    public static final AtomicLong totalBoneTransformsSaved = new AtomicLong(0);

    // Cached Reflection Handles
    private static Field fieldX = null;
    private static Field fieldY = null;
    private static Field fieldZ = null;
    private static Method methodGetX = null;
    private static Method methodGetY = null;
    private static Method methodGetZ = null;

    private static Class<?> playerClass = null;
    private static Method playerGetInstMethod = null;
    private static Class<?> worldClass = null;
    private static Field worldInstField = null;
    private static Field cellField = null;
    private static Method cellGetCellMethod = null;
    private static Method cellGetZombiesMethod = null;

    private static boolean reflectionResolved = false;
    private static boolean reflectionNoticeLogged = false;

    // Internal snapshot storage
    private static final int MAX_SNAPSHOT = SpatialBufferPool.MAX_ENTITIES;
    private static final byte[] SNAPSHOT_TIERS = new byte[MAX_SNAPSHOT];
    private static final byte[] SNAPSHOT_MASK = new byte[MAX_SNAPSHOT];
    private static final float[] SNAPSHOT_DISTANCES = new float[MAX_SNAPSHOT];
    private static volatile int snapshotCount = 0;

    // Thresholds
    public static final float TIER_CLOSE_SQ = 16.0f * 16.0f;     // 256 tiles^2  (LOD 0)
    public static final float TIER_MEDIUM_SQ = 32.0f * 32.0f;   // 1024 tiles^2 (LOD 1)
    public static final float TIER_FAR_SQ = 50.0f * 50.0f;      // 2500 tiles^2 (LOD 2)
    public static final float CAMERA_HALF_SPAN = 50.0f;         // 100x100 tile camera safety AABB

    public static void initialize() {
        if (active) return;
        SpatialBufferPool.initialize();
        resolveReflection();

        active = true;
        cullerThread = new Thread(HordeSpatialCuller::cullerLoop, "PZO-HordeSpatialCuller");
        cullerThread.setDaemon(true);
        cullerThread.setPriority(Thread.NORM_PRIORITY - 1);
        cullerThread.start();

        PZOLogger.success("HordeSpatialCuller: Active (SIMD AVX2 Batch Horde Spatial Culler & Vectorized Entity Processor)");
    }

    private static void resolveReflection() {
        if (reflectionResolved) return;
        try {
            Class<?> movingObjClass = Class.forName("zombie.iso.IsoMovingObject");
            try {
                fieldX = movingObjClass.getDeclaredField("x");
                fieldX.setAccessible(true);
                fieldY = movingObjClass.getDeclaredField("y");
                fieldY.setAccessible(true);
                fieldZ = movingObjClass.getDeclaredField("z");
                fieldZ.setAccessible(true);
            } catch (Throwable t) {
                // Fallback to public getters
                methodGetX = movingObjClass.getMethod("getX");
                methodGetY = movingObjClass.getMethod("getY");
                methodGetZ = movingObjClass.getMethod("getZ");
            }

            playerClass = Class.forName("zombie.characters.IsoPlayer");
            playerGetInstMethod = playerClass.getMethod("getInstance");

            worldClass = Class.forName("zombie.iso.IsoWorld");
            worldInstField = worldClass.getField("instance");
            try {
                cellField = worldClass.getField("currentCell");
            } catch (Throwable t1) {
                try {
                    cellField = worldClass.getField("CurrentCell");
                } catch (Throwable t2) {
                    cellGetCellMethod = worldClass.getMethod("getCell");
                }
            }

            Class<?> cellClass = Class.forName("zombie.iso.IsoCell");
            cellGetZombiesMethod = cellClass.getMethod("getZombieList");

            reflectionResolved = true;
        } catch (Throwable t) {
            if (!reflectionNoticeLogged) {
                PZOLogger.warn("HordeSpatialCuller: Reflection resolution notice: " + t.getMessage());
                reflectionNoticeLogged = true;
            }
        }
    }

    public static float getObjectX(Object obj) {
        if (obj == null) return 0.0f;
        try {
            if (fieldX != null) return fieldX.getFloat(obj);
            if (methodGetX != null) return ((Number) methodGetX.invoke(obj)).floatValue();
        } catch (Throwable ignored) {}
        return 0.0f;
    }

    public static float getObjectY(Object obj) {
        if (obj == null) return 0.0f;
        try {
            if (fieldY != null) return fieldY.getFloat(obj);
            if (methodGetY != null) return ((Number) methodGetY.invoke(obj)).floatValue();
        } catch (Throwable ignored) {}
        return 0.0f;
    }

    private static void cullerLoop() {
        while (active) {
            try {
                boolean driving = VehicleTravelOptimizer.isPlayerDriving();
                processSpatialSweep();
                // Driving throttle: 4 Hz while driving (zero contention with chunk decompression/vehicle physics), ~20 Hz on foot
                Thread.sleep(driving ? 250 : 50);
            } catch (InterruptedException ie) {
                break;
            } catch (Throwable ignored) {}
        }
    }

    public static void processSpatialSweep() {
        if (!SpatialBufferPool.isInitialized()) return;

        try {
            if (!reflectionResolved) {
                resolveReflection();
                if (!reflectionResolved) return;
            }

            if (playerGetInstMethod == null || worldInstField == null) return;

            // 1. Discover local player
            Object player = playerGetInstMethod.invoke(null);
            if (player == null) {
                lastTrackedZombieCount.set(0);
                lastCulledOffscreenCount.set(0);
                return;
            }

            float px = getObjectX(player);
            float py = getObjectY(player);

            // 2. Discover active zombie list from IsoWorld.instance.currentCell
            Object worldInst = worldInstField.get(null);
            if (worldInst == null) return;

            Object cell = null;
            if (cellField != null) {
                cell = cellField.get(worldInst);
            } else if (cellGetCellMethod != null) {
                cell = cellGetCellMethod.invoke(worldInst);
            }
            if (cell == null || cellGetZombiesMethod == null) return;

            @SuppressWarnings("unchecked")
            ArrayList<Object> zombies = (ArrayList<Object>) cellGetZombiesMethod.invoke(cell);
            if (zombies == null || zombies.isEmpty()) {
                lastTrackedZombieCount.set(0);
                lastCulledOffscreenCount.set(0);
                snapshotCount = 0;
                return;
            }

            int count = Math.min(zombies.size(), MAX_SNAPSHOT);
            FloatBuffer coordBuf = SpatialBufferPool.getCoordBuffer();
            FloatBuffer distBuf = SpatialBufferPool.getDistanceBuffer();
            FloatBuffer distSqBuf = SpatialBufferPool.getDistSqBuffer();
            ByteBuffer maskBuf = SpatialBufferPool.getCullMaskBuffer();
            ByteBuffer tiersBuf = SpatialBufferPool.getTiersBuffer();

            // 3. Populate contiguous coordinate buffer
            coordBuf.rewind();
            for (int i = 0; i < count; i++) {
                if (i >= zombies.size()) break;
                Object z = zombies.get(i);
                if (z != null) {
                    float zx = getObjectX(z);
                    float zy = getObjectY(z);
                    coordBuf.put(i * 2, zx);
                    coordBuf.put(i * 2 + 1, zy);
                } else {
                    coordBuf.put(i * 2, 0.0f);
                    coordBuf.put(i * 2 + 1, 0.0f);
                }
            }

            // 4. Vectorized AVX2 Batch Calculations
            // Distance calculation
            PZONative.calculateDistancesAVX2(coordBuf, count, px, py, distBuf);

            // Multi-Tier classification (Tier 0: <=256, Tier 1: <=1024, Tier 2: <=2500, Tier 3: >2500)
            PZONative.classifyTiersAVX2(coordBuf, count, px, py, TIER_CLOSE_SQ, TIER_MEDIUM_SQ, TIER_FAR_SQ, tiersBuf);

            // Camera AABB Frustum Culling
            float minX = px - CAMERA_HALF_SPAN;
            float minY = py - CAMERA_HALF_SPAN;
            float maxX = px + CAMERA_HALF_SPAN;
            float maxY = py + CAMERA_HALF_SPAN;
            int insideAABB = PZONative.cullAABBAVX2(coordBuf, count, minX, minY, maxX, maxY, maskBuf);

            // 5. Transfer to snapshot arrays for atomic thread-safe access
            distBuf.rewind();
            distBuf.get(SNAPSHOT_DISTANCES, 0, count);

            tiersBuf.rewind();
            tiersBuf.get(SNAPSHOT_TIERS, 0, count);

            maskBuf.rewind();
            maskBuf.get(SNAPSHOT_MASK, 0, count);

            snapshotCount = count;

            int culledOffscreen = count - insideAABB;
            int hibernating = 0;
            for (int i = 0; i < count; i++) {
                if (SNAPSHOT_TIERS[i] >= 2) hibernating++;
            }

            lastTrackedZombieCount.set(count);
            lastCulledOffscreenCount.set(culledOffscreen);
            lastHibernatingCount.set(hibernating);
            totalDistanceCalculations.addAndGet(count);

        } catch (Throwable ignored) {}
    }

    public static int getZombieCount() {
        return snapshotCount;
    }

    public static float getDistance(int zombieIndex) {
        if (zombieIndex >= 0 && zombieIndex < snapshotCount) {
            return SNAPSHOT_DISTANCES[zombieIndex];
        }
        return 999.0f;
    }

    public static int getLODTier(int zombieIndex) {
        if (zombieIndex >= 0 && zombieIndex < snapshotCount) {
            return SNAPSHOT_TIERS[zombieIndex];
        }
        return 3;
    }

    public static boolean isOffscreen(int zombieIndex) {
        if (zombieIndex >= 0 && zombieIndex < snapshotCount) {
            return SNAPSHOT_MASK[zombieIndex] == 0;
        }
        return true;
    }

    public static void shutdown() {
        active = false;
        if (cullerThread != null) {
            cullerThread.interrupt();
        }
    }
}
