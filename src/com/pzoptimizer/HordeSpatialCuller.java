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
    private static boolean reflectionResolved = false;

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
            fieldX = movingObjClass.getField("x");
            fieldY = movingObjClass.getField("y");
            fieldZ = movingObjClass.getField("z");
            reflectionResolved = true;
        } catch (Throwable t) {
            PZOLogger.warn("HordeSpatialCuller: Reflection resolution notice: " + t.getMessage());
        }
    }

    private static void cullerLoop() {
        while (active) {
            try {
                processSpatialSweep();
            } catch (Throwable ignored) {}

            try {
                Thread.sleep(20); // ~50 Hz spatial update cycle
            } catch (InterruptedException ie) {
                break;
            }
        }
    }

    public static void processSpatialSweep() {
        if (!SpatialBufferPool.isInitialized()) return;

        try {
            // 1. Discover local player
            Class<?> playerClass = Class.forName("zombie.characters.IsoPlayer");
            Method getInst = playerClass.getMethod("getInstance");
            Object player = getInst.invoke(null);
            if (player == null) {
                lastTrackedZombieCount.set(0);
                lastCulledOffscreenCount.set(0);
                return;
            }

            if (!reflectionResolved) {
                resolveReflection();
                if (!reflectionResolved) return;
            }

            float px = fieldX.getFloat(player);
            float py = fieldY.getFloat(player);

            // 2. Discover active zombie list from IsoWorld.instance.currentCell
            Class<?> worldClass = Class.forName("zombie.iso.IsoWorld");
            Field instField = worldClass.getField("instance");
            Object worldInst = instField.get(null);
            if (worldInst == null) return;

            Field cellField = worldClass.getField("currentCell");
            Object cell = cellField.get(worldInst);
            if (cell == null) return;

            Method getZombies = cell.getClass().getMethod("getZombieList");
            @SuppressWarnings("unchecked")
            ArrayList<Object> zombies = (ArrayList<Object>) getZombies.invoke(cell);
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
                Object z = zombies.get(i);
                if (z != null) {
                    float zx = fieldX.getFloat(z);
                    float zy = fieldY.getFloat(z);
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
