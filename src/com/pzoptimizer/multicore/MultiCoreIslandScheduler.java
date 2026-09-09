package com.pzoptimizer.multicore;

import com.pzoptimizer.FastChunkKey;
import com.pzoptimizer.PZOLogger;
import com.pzoptimizer.PZONative;
import zombie.GameTime;
import zombie.MovingObjectUpdateScheduler;
import zombie.MovingObjectUpdateSchedulerUpdateBucket;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.iso.IsoMovingObject;
import zombie.iso.objects.IsoDeadBody;
import zombie.iso.IsoWorld;
import zombie.vehicles.BaseVehicle;
import zombie.util.Type;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class MultiCoreIslandScheduler {

    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static volatile boolean active = false;

    public static final int ISLAND_SIZE = 32; // 32x32 tiles per island
    public static final float SAFETY_MARGIN = 2.0f; // 2 tiles boundary buffer
    public static final float PLAYER_SAFETY_RADIUS_SQ = 12.0f * 12.0f; // 144 tiles^2

    public static final AtomicLong totalParallelUpdates = new AtomicLong(0);
    public static final AtomicInteger lastSimulatedIslands = new AtomicInteger(0);

    public static class IslandBucket {
        public final int ix;
        public final int iy;
        public final List<IsoMovingObject> objects = new ArrayList<>(64);

        public IslandBucket(int ix, int iy) {
            this.ix = ix;
            this.iy = iy;
        }
    }

    public static synchronized void initialize() {
        if (initialized.get()) return;

        // Keep simulation on the main thread: updateAlpha() and movingObjects mutations
        // caused visibility failures and square-transfer races on worker threads.
        initialized.set(true);
        active = false;
        PZOLogger.info("[MultiCoreIslandScheduler] Preserving main-thread simulation for 100% zombie visibility & collision safety");
    }

    @SuppressWarnings("unchecked")
    private static void installSchedulerHook() {
        try {
            MovingObjectUpdateScheduler scheduler = MovingObjectUpdateScheduler.instance;
            if (scheduler == null) return;

            Field simLevelsField = MovingObjectUpdateScheduler.class.getDeclaredField("simulationLevels");
            simLevelsField.setAccessible(true);
            MovingObjectUpdateSchedulerUpdateBucket[] simLevels = 
                (MovingObjectUpdateSchedulerUpdateBucket[]) simLevelsField.get(scheduler);

            if (simLevels == null) return;

            Field bucketsField = MovingObjectUpdateSchedulerUpdateBucket.class.getDeclaredField("buckets");
            bucketsField.setAccessible(true);

            Field simLevelField = MovingObjectUpdateSchedulerUpdateBucket.class.getDeclaredField("simulationLevel");
            simLevelField.setAccessible(true);

            Field frameModField = MovingObjectUpdateSchedulerUpdateBucket.class.getDeclaredField("frameMod");
            frameModField.setAccessible(true);

            for (MovingObjectUpdateSchedulerUpdateBucket bucketObj : simLevels) {
                if (bucketObj == null) continue;
                List<IsoMovingObject>[] originalBuckets = (List<IsoMovingObject>[]) bucketsField.get(bucketObj);
                UpdateSchedulerSimulationLevel simLevel = (UpdateSchedulerSimulationLevel) simLevelField.get(bucketObj);
                int frameMod = frameModField.getInt(bucketObj);

                if (originalBuckets != null) {
                    List<IsoMovingObject>[] enhancedBuckets = new List[originalBuckets.length];
                    for (int b = 0; b < originalBuckets.length; b++) {
                        enhancedBuckets[b] = new IslandAwareBucketList(simLevel, frameMod);
                    }
                    setFieldUnsafeOrReflect(bucketObj, bucketsField, enhancedBuckets);
                }
            }

            PZOLogger.success("[MultiCoreIslandScheduler] Successfully replaced scheduler buckets with IslandAwareBucketLists");
        } catch (Throwable t) {
            PZOLogger.warn("[MultiCoreIslandScheduler] Non-fatal hook notice: " + t.getMessage());
        }
    }

    private static void setFieldUnsafeOrReflect(Object target, Field field, Object value) {
        try {
            Field theUnsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            sun.misc.Unsafe u = (sun.misc.Unsafe) theUnsafeField.get(null);
            long offset = u.objectFieldOffset(field);
            u.putObject(target, offset, value);
        } catch (Throwable t) {
            try {
                field.setAccessible(true);
                field.set(target, value);
            } catch (Throwable ignored) {}
        }
    }

    public static class IslandAwareBucketList extends ArrayList<IsoMovingObject> {
        private final UpdateSchedulerSimulationLevel simulationLevel;
        private final int frameMod;

        private final Map<Long, IslandBucket> redIslands = new HashMap<>();
        private final Map<Long, IslandBucket> blackIslands = new HashMap<>();
        private final List<IsoMovingObject> boundaryAndPlayerObjects = new ArrayList<>(64);

        private boolean parallelSweepExecuted = false;
        private boolean parallelPostUpdateExecuted = false;

        public IslandAwareBucketList(UpdateSchedulerSimulationLevel simLevel, int frameMod) {
            super(128);
            this.simulationLevel = simLevel;
            this.frameMod = frameMod;
        }

        @Override
        public boolean add(IsoMovingObject obj) {
            if (obj == null) return false;

            // Vehicles and players can execute Lua and must stay on the main simulation thread.
            if (obj instanceof BaseVehicle || obj instanceof IsoPlayer) {
                boundaryAndPlayerObjects.add(obj);
                return super.add(obj);
            }

            float x = obj.getX();
            float y = obj.getY();

            IsoPlayer player = IsoPlayer.getInstance();
            if (player != null) {
                float dx = x - player.getX();
                float dy = y - player.getY();
                if ((dx * dx + dy * dy) <= PLAYER_SAFETY_RADIUS_SQ) {
                    boundaryAndPlayerObjects.add(obj);
                    return super.add(obj);
                }
            }

            int ix = (int) Math.floor(x / ISLAND_SIZE);
            int iy = (int) Math.floor(y / ISLAND_SIZE);

            float localX = x - (ix * ISLAND_SIZE);
            float localY = y - (iy * ISLAND_SIZE);

            if (localX < SAFETY_MARGIN || localX > (ISLAND_SIZE - SAFETY_MARGIN) ||
                localY < SAFETY_MARGIN || localY > (ISLAND_SIZE - SAFETY_MARGIN)) {
                boundaryAndPlayerObjects.add(obj);
                return super.add(obj);
            }

            long key = FastChunkKey.pack(ix, iy);
            int phase = (ix + iy) & 1;

            if (phase == 0) {
                redIslands.computeIfAbsent(key, k -> new IslandBucket(ix, iy)).objects.add(obj);
            } else {
                blackIslands.computeIfAbsent(key, k -> new IslandBucket(ix, iy)).objects.add(obj);
            }

            return super.add(obj);
        }

        @Override
        public int size() {
            // When MovingObjectUpdateSchedulerUpdateBucket.update() or postupdate() reads size():
            if (!parallelSweepExecuted && (!redIslands.isEmpty() || !blackIslands.isEmpty())) {
                executeParallelSimulation();
                parallelSweepExecuted = true;
                // Expose only boundary entities to the vanilla caller.
                return boundaryAndPlayerObjects.size();
            } else if (parallelSweepExecuted && !parallelPostUpdateExecuted && (!redIslands.isEmpty() || !blackIslands.isEmpty())) {
                executeParallelPostUpdate();
                parallelPostUpdateExecuted = true;
                return boundaryAndPlayerObjects.size();
            }

            return boundaryAndPlayerObjects.isEmpty() ? super.size() : boundaryAndPlayerObjects.size();
        }

        @Override
        public IsoMovingObject get(int index) {
            if (parallelSweepExecuted && !boundaryAndPlayerObjects.isEmpty()) {
                if (index >= 0 && index < boundaryAndPlayerObjects.size()) {
                    return boundaryAndPlayerObjects.get(index);
                }
            }
            return super.get(index);
        }

        @Override
        public void clear() {
            super.clear();
            redIslands.clear();
            blackIslands.clear();
            boundaryAndPlayerObjects.clear();
            parallelSweepExecuted = false;
            parallelPostUpdateExecuted = false;
        }

        private void executeParallelSimulation() {
            int totalSimulated = 0;
            lastSimulatedIslands.set(redIslands.size() + blackIslands.size());

            if (!redIslands.isEmpty()) {
                List<CompletableFuture<Void>> redFutures = new ArrayList<>(redIslands.size());
                for (IslandBucket island : redIslands.values()) {
                    totalSimulated += island.objects.size();
                    redFutures.add(CompletableFuture.runAsync(() -> {
                        PZONative.bindCallingThreadToPCores();
                        simulateIslandObjects(island.objects, simulationLevel, frameMod);
                    }, PZOMultiCoreEngine.getExecutor()));
                }
                CompletableFuture.allOf(redFutures.toArray(new CompletableFuture[0])).join();
            }

            if (!blackIslands.isEmpty()) {
                List<CompletableFuture<Void>> blackFutures = new ArrayList<>(blackIslands.size());
                for (IslandBucket island : blackIslands.values()) {
                    totalSimulated += island.objects.size();
                    blackFutures.add(CompletableFuture.runAsync(() -> {
                        PZONative.bindCallingThreadToPCores();
                        simulateIslandObjects(island.objects, simulationLevel, frameMod);
                    }, PZOMultiCoreEngine.getExecutor()));
                }
                CompletableFuture.allOf(blackFutures.toArray(new CompletableFuture[0])).join();
            }

            totalParallelUpdates.addAndGet(totalSimulated);
        }

        private void executeParallelPostUpdate() {
            if (!redIslands.isEmpty()) {
                List<CompletableFuture<Void>> redFutures = new ArrayList<>(redIslands.size());
                for (IslandBucket island : redIslands.values()) {
                    redFutures.add(CompletableFuture.runAsync(() -> {
                        PZONative.bindCallingThreadToPCores();
                        postUpdateIslandObjects(island.objects, frameMod);
                    }, PZOMultiCoreEngine.getExecutor()));
                }
                CompletableFuture.allOf(redFutures.toArray(new CompletableFuture[0])).join();
            }

            if (!blackIslands.isEmpty()) {
                List<CompletableFuture<Void>> blackFutures = new ArrayList<>(blackIslands.size());
                for (IslandBucket island : blackIslands.values()) {
                    blackFutures.add(CompletableFuture.runAsync(() -> {
                        PZONative.bindCallingThreadToPCores();
                        postUpdateIslandObjects(island.objects, frameMod);
                    }, PZOMultiCoreEngine.getExecutor()));
                }
                CompletableFuture.allOf(blackFutures.toArray(new CompletableFuture[0])).join();
            }
        }

        private static void simulateIslandObjects(List<IsoMovingObject> list, UpdateSchedulerSimulationLevel level, int frameMod) {
            for (int i = 0; i < list.size(); i++) {
                IsoMovingObject obj = list.get(i);
                if (obj == null) continue;

                if (obj instanceof IsoDeadBody) {
                    try {
                        IsoWorld.instance.getCell().getRemoveList().add(obj);
                    } catch (Throwable ignored) {}
                    continue;
                }

                if (obj instanceof BaseVehicle || obj instanceof IsoPlayer) {
                    continue;
                }

                try {
                    obj.setCurrentSimulationLevel(level);
                    obj.preupdate();
                    obj.frameStep();
                    obj.update();
                } catch (Throwable ignored) {}
            }
        }

        private static void postUpdateIslandObjects(List<IsoMovingObject> list, int frameMod) {
            for (int i = 0; i < list.size(); i++) {
                IsoMovingObject obj = list.get(i);
                if (obj == null) continue;
                try {
                    obj.postupdate();
                } catch (Throwable ignored) {}
            }
        }
    }

    public static boolean isActive() {
        return active;
    }

    public static long getTotalParallelUpdates() {
        return totalParallelUpdates.get();
    }
}
