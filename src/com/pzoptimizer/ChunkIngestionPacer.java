package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class ChunkIngestionPacer {

    private static volatile boolean active = false;
    private static volatile boolean pacerInstalled = false;

    public static final long FRAME_BUDGET_NANOS = 3_500_000L;
    public static final int MAX_CHUNKS_PER_FRAME = 3;
    private static volatile int maxCachedChunks = 1000;

    public static void setMaxCachedChunks(int max) {
        maxCachedChunks = Math.max(100, max);
    }

    public static int getMaxCachedChunks() {
        return maxCachedChunks;
    }

    public static void initialize() {
        if (active) return;
        active = true;

        try {
            installPacer();
            upgradeChunkMapLock();
            installShutdownHook();
        } catch (Throwable t) {
            PZOLogger.warn("ChunkIngestionPacer initialization notice: " + t.getMessage());
        }
    }

    public static synchronized boolean installPacer() {
        upgradeChunkMapLock();
        if (pacerInstalled) return true;

        try {
            Class<?> chunkClass = Class.forName("zombie.iso.IsoChunk");
            Field loadGridField = chunkClass.getField("loadGridSquare");
            Object cappedQueue = loadGridField.get(null);

            if (cappedQueue == null) return false;

            Field innerQField = cappedQueue.getClass().getDeclaredField("queue");
            innerQField.setAccessible(true);
            Object origQ = innerQField.get(cappedQueue);

            if (origQ instanceof PacedConcurrentQueue) {
                pacerInstalled = true;
                return true;
            }

            if (origQ instanceof ConcurrentLinkedQueue) {
                @SuppressWarnings("unchecked")
                ConcurrentLinkedQueue<Object> typedQ = (ConcurrentLinkedQueue<Object>) origQ;
                PacedConcurrentQueue pacedQ = new PacedConcurrentQueue(typedQ);
                innerQField.set(cappedQueue, pacedQ);
                pacerInstalled = true;
                PZOLogger.success("ChunkIngestionPacer: Active (Frame-Budgeted Chunk Integration Governor Armed)");
                return true;
            }
        } catch (Throwable t) {
            PZOLogger.warn("ChunkIngestionPacer install notice: " + t.getMessage());
        }
        return false;
    }

    private static volatile boolean lockUpgraded = false;

    public static void upgradeChunkMapLock() {
        if (lockUpgraded) return;
        try {
            Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            sun.misc.Unsafe u = (sun.misc.Unsafe) theUnsafe.get(null);

            Class<?> chunkMapClass = Class.forName("zombie.iso.IsoChunkMap");
            Field lockField = chunkMapClass.getDeclaredField("bSettingChunk");
            Object base = u.staticFieldBase(lockField);
            long offset = u.staticFieldOffset(lockField);

            java.util.concurrent.locks.ReentrantLock oldLock = (java.util.concurrent.locks.ReentrantLock) u.getObject(base, offset);
            if (oldLock != null && oldLock.isFair()) {
                u.putObject(base, offset, new java.util.concurrent.locks.ReentrantLock(false));
                lockUpgraded = true;
                PZOLogger.success("[ChunkIngestionPacer] Upgraded IsoChunkMap.bSettingChunk to high-throughput unfair ReentrantLock (0ms kernel contention)");
            } else if (oldLock != null && !oldLock.isFair()) {
                lockUpgraded = true;
            }
        } catch (Throwable t) {
            // IsoChunkMap not yet loaded; will retry on next check
        }
    }

    private static volatile boolean shutdownHookInstalled = false;

    private static void installShutdownHook() {
        if (shutdownHookInstalled) return;
        shutdownHookInstalled = true;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                flushAllUnloads();
            }, "PZO-ShutdownChunkFlusher"));
        } catch (Throwable ignored) {}
    }

    public static boolean isPacerInstalled() {
        return pacerInstalled;
    }

    public static void onFrameBoundary(int frameCount) {
        processPendingUnloads();
        PacedConcurrentQueue.onFrameBoundary(frameCount);
    }

    private static volatile int lastUnloadFrameCount = -1;

    public static boolean isPlayerDriving() {
        return PacedConcurrentQueue.isPlayerDriving();
    }

    public static void processPendingUnloads() {
        if (isEngineLoading()) return;
        if (isPlayerDriving()) return; // Do not unload chunks while driving to avoid collision/mesh gaps

        try {
            if (zombie.iso.IsoWorld.instance == null || zombie.iso.IsoWorld.instance.currentCell == null) return;
            if (zombie.iso.IsoChunkMap.SharedChunks == null || zombie.iso.IsoChunkMap.SharedChunks.isEmpty()) return;
            if (zombie.iso.IsoChunkMap.bSettingChunk == null) return;

            int currentFrame = -1;
            try {
                if (zombie.iso.IsoCamera.frameState != null) {
                    currentFrame = zombie.iso.IsoCamera.frameState.frameCount;
                }
            } catch (Throwable ignored) {}

            if (currentFrame != -1 && currentFrame == lastUnloadFrameCount) {
                return; // Already processed for this frame tick
            }
            lastUnloadFrameCount = currentFrame;

            // Skip this tick if another thread holds bSettingChunk.
            if (!zombie.iso.IsoChunkMap.bSettingChunk.tryLock()) {
                return;
            }

            try {
                int totalShared = zombie.iso.IsoChunkMap.SharedChunks.size();
                if (totalShared <= 0) return;

                long now = System.nanoTime();
                int chunksIngested = PacedConcurrentQueue.getChunksIngestedThisFrame();
                int maxUnloads = (chunksIngested > 0) ? 1 : 2;
                long budgetNanos = (chunksIngested > 0) ? 1_500_000L : 2_500_000L;

                int unloadsThisFrame = 0;
                int checkedEntries = 0;
                var iterator = zombie.iso.IsoChunkMap.SharedChunks.entrySet().iterator();
                while (iterator.hasNext()) {
                    if (unloadsThisFrame >= maxUnloads || checkedEntries >= 16 || (System.nanoTime() - now) >= budgetNanos) {
                        break;
                    }

                    var entry = iterator.next();
                    checkedEntries++;
                    zombie.iso.IsoChunk chunk = entry.getValue();
                    if (chunk == null) {
                        iterator.remove();
                        continue;
                    }

                    // An orphaned chunk is one no longer referenced by any active player IsoChunkMap
                    if (chunk.refs == null || chunk.refs.isEmpty()) {
                        iterator.remove();
                        if (chunk.loaded) {
                            try {
                                chunk.removeFromWorld();
                                if (zombie.iso.ChunkSaveWorker.instance != null) {
                                    zombie.iso.ChunkSaveWorker.instance.Add(chunk);
                                }
                            } catch (Throwable t) {
                            }
                        }
                        unloadsThisFrame++;
                        break; // Process at most 1 chunk per slack frame, then yield immediately
                    }
                }
            } finally {
                zombie.iso.IsoChunkMap.bSettingChunk.unlock();
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Drains all orphaned chunks in SharedChunks immediately (used on game shutdown or cell unloads).
     */
    public static void flushAllUnloads() {
        try {
            if (zombie.iso.IsoChunkMap.SharedChunks == null || zombie.iso.IsoChunkMap.SharedChunks.isEmpty()) return;
            if (zombie.iso.IsoChunkMap.bSettingChunk != null) {
                zombie.iso.IsoChunkMap.bSettingChunk.lock();
            }
            try {
                var iterator = zombie.iso.IsoChunkMap.SharedChunks.entrySet().iterator();
                while (iterator.hasNext()) {
                    var entry = iterator.next();
                    zombie.iso.IsoChunk chunk = entry.getValue();
                    if (chunk == null) {
                        iterator.remove();
                        continue;
                    }
                    if (chunk.refs == null || chunk.refs.isEmpty()) {
                        iterator.remove();
                        if (chunk.loaded) {
                            try {
                                chunk.removeFromWorld();
                                if (zombie.iso.ChunkSaveWorker.instance != null) {
                                    zombie.iso.ChunkSaveWorker.instance.Add(chunk);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            } finally {
                if (zombie.iso.IsoChunkMap.bSettingChunk != null && zombie.iso.IsoChunkMap.bSettingChunk.isHeldByCurrentThread()) {
                    zombie.iso.IsoChunkMap.bSettingChunk.unlock();
                }
            }
        } catch (Throwable ignored) {}
    }

    public static boolean isEngineLoading() {
        try {
            return zombie.gameStates.IngameState.loading;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Specialized ConcurrentLinkedQueue that meters poll() invocations on the main thread.
     */
    public static final class PacedConcurrentQueue extends ConcurrentLinkedQueue<Object> {
        private static final long serialVersionUID = 42L;

        private static volatile long lastFrameCount = -1;
        private static volatile long frameStartTime = 0;
        private static volatile int chunksThisFrame = 0;
        private static volatile long lastDrivingCheckTime = 0;
        private static volatile boolean playerIsDriving = false;

        private final java.util.concurrent.atomic.AtomicInteger approximateSize = new java.util.concurrent.atomic.AtomicInteger(0);

        public PacedConcurrentQueue(ConcurrentLinkedQueue<Object> existing) {
            super();
            if (existing != null && !existing.isEmpty()) {
                this.addAll(existing);
                this.approximateSize.set(existing.size());
            }
        }

        @Override
        public boolean add(Object e) {
            boolean added = super.add(e);
            if (added) approximateSize.incrementAndGet();
            return added;
        }

        @Override
        public boolean offer(Object e) {
            boolean offered = super.offer(e);
            if (offered) approximateSize.incrementAndGet();
            return offered;
        }

        @Override
        public void clear() {
            super.clear();
            approximateSize.set(0);
        }

        public static boolean isPlayerDriving() {
            long now = System.currentTimeMillis();
            if (now - lastDrivingCheckTime < 100L) {
                return playerIsDriving;
            }
            lastDrivingCheckTime = now;
            try {
                zombie.characters.IsoPlayer player = zombie.characters.IsoPlayer.getInstance();
                if (player != null) {
                    playerIsDriving = (player.getVehicle() != null);
                    return playerIsDriving;
                }
            } catch (Throwable ignored) {}
            playerIsDriving = false;
            return false;
        }

        public static int getChunksIngestedThisFrame() {
            return chunksThisFrame;
        }

        public static void onFrameBoundary(int frameCount) {
            if (frameCount != lastFrameCount) {
                lastFrameCount = frameCount;
                chunksThisFrame = 0;
                frameStartTime = System.nanoTime();
            }
        }

        private static void checkFrameBoundary(long now) {
            try {
                if (zombie.iso.IsoCamera.frameState != null) {
                    int fc = zombie.iso.IsoCamera.frameState.frameCount;
                    if (fc != lastFrameCount) {
                        lastFrameCount = fc;
                        chunksThisFrame = 0;
                        frameStartTime = now;
                        return;
                    }
                }
            } catch (Throwable ignored) {}

            // Reset after a stall longer than 500 ms, not after ordinary frame delays.
            if (now - frameStartTime > 500_000_000L) {
                frameStartTime = now;
                chunksThisFrame = 0;
            }
        }

        @Override
        public Object poll() {
            // If called from background threads (e.g. WorldStreamer, WorldReuser), pass through immediately
            String threadName = Thread.currentThread().getName();
            if (threadName != null && (threadName.contains("WorldStreamer") || threadName.contains("Reuser"))) {
                Object chunk = super.poll();
                if (chunk != null) approximateSize.decrementAndGet();
                return chunk;
            }

            // If game is in loading state (initial boot, teleports, cell loading), drain with zero limit
            if (isEngineLoading()) {
                Object chunk = super.poll();
                if (chunk != null) approximateSize.decrementAndGet();
                return chunk;
            }

            long now = System.nanoTime();

            checkFrameBoundary(now);

            // Allow a larger ingestion budget while driving so the vehicle does not outrun loaded chunks.
            boolean driving = isPlayerDriving();
            int backlog = approximateSize.get();
            int maxChunks;
            long budgetNanos;

            if (driving) {
                maxChunks = Math.max(32, backlog);
                budgetNanos = 12_000_000L; // 12.0 ms budget ceiling
            } else {
                maxChunks = (backlog > 8) ? 8 : 4;
                budgetNanos = 6_000_000L; // 6.0 ms budget ceiling
            }

            if (chunksThisFrame > 0 && (chunksThisFrame >= maxChunks || (now - frameStartTime) >= budgetNanos)) {
                // Yield to renderer for this frame; remaining chunks are smoothly integrated next frame
                return null;
            }

            Object chunk = super.poll();
            if (chunk != null) {
                approximateSize.decrementAndGet();
                chunksThisFrame++;
            }
            return chunk;
        }

        private boolean isEngineLoading() {
            try {
                return zombie.gameStates.IngameState.loading;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }
}
