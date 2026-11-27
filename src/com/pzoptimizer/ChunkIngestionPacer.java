package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentLinkedQueue;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoWorld;

/** Caps how much chunk integration the main thread does per frame. A B42 chunk is 2,048 squares (32 levels). */
public final class ChunkIngestionPacer {

    private static volatile boolean active = false;
    private static volatile boolean pacerInstalled = false;
    // Set if this game build doesn't have the fields I hook; otherwise the daemon retries every second
    private static volatile boolean pacerUnavailable = false;
    private static volatile boolean pacerWarned = false;

    // 3.5 ms per frame
    public static final long FRAME_BUDGET_NANOS = 3_500_000L;
    public static final int MAX_CHUNKS_PER_FRAME = 3;
    private static volatile int chunkBudgetMs = 6;
    // JVM_ChunkHeadingOrder: heading-first while driving, no chunk held long in multiplayer
    public static volatile boolean headingOrder = true;
    // In multiplayer the server's square updates for a chunk the client hasn't taken in yet are lost, so none waits
    // past this
    static final long MP_MAX_WAIT_NANOS = 150_000_000L;

    public static void setChunkBudgetMs(int ms) {
        chunkBudgetMs = Math.max(1, Math.min(32, ms));
    }

    public static int getChunkBudgetMs() {
        return chunkBudgetMs;
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
        if (pacerUnavailable) return false;

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

            pacerUnavailable = true;
            PZOLogger.warn("ChunkIngestionPacer disabled: unexpected loadGridSquare queue type " + (origQ == null ? "null" : origQ.getClass().getName()));
        } catch (NoSuchFieldException | ClassCastException e) {
            pacerUnavailable = true;
            PZOLogger.warn("ChunkIngestionPacer disabled: incompatible game build (" + e + ")");
        } catch (Throwable t) {
            if (!pacerWarned) {
                pacerWarned = true;
                PZOLogger.warn("ChunkIngestionPacer install notice (retrying quietly): " + t);
            }
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
            // IsoChunkMap isn't loaded yet, try again next check
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

    public static boolean isPacerSettled() {
        return pacerInstalled || pacerUnavailable;
    }

    public static void onFrameBoundary(int frameCount) {
        processPendingUnloads();
        PacedConcurrentQueue.onFrameBoundary(frameCount);
    }

    private static volatile int lastUnloadFrameCount = -1;

    /**
     * Spreads out tearing down the orphaned chunks left in IsoChunkMap.SharedChunks. Vanilla tears down the ~13
     * trailing chunks of a boundary crossing in one frame (50-80 ms at driving speed). With the Up/Down/Left/Right
     * patch the shift itself is cheap and those chunks stay in SharedChunks, so I unload one per frame on frames that
     * didn't ingest anything.
     */
    public static boolean isPlayerDriving() {
        return PacedConcurrentQueue.isPlayerDriving();
    }

    public static void processPendingUnloads() {
        if (isEngineLoading()) return;
        if (isPlayerDriving()) return; // no unloading while driving, it leaves collision/mesh gaps

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
                return; // already done this tick
            }
            lastUnloadFrameCount = currentFrame;

            // tryLock: just skip this tick if bSettingChunk is held
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

                    // Orphaned = no longer referenced by any player's IsoChunkMap
                    if (chunk.refs == null || chunk.refs.isEmpty()) {
                        if (ChunkRetentionRing.isRetained(chunk.wx, chunk.wy)) {
                            continue;
                        }
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
                        break; // one chunk per slack frame
                    }
                }
            } finally {
                zombie.iso.IsoChunkMap.bSettingChunk.unlock();
            }
        } catch (Throwable ignored) {}
    }

    /** Drops every orphaned chunk in SharedChunks right away (shutdown, cell unloads). */
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

    /** ConcurrentLinkedQueue that meters poll() calls on the main thread. */
    public static final class PacedConcurrentQueue extends ConcurrentLinkedQueue<Object> {
        private static final long serialVersionUID = 42L;
        private static final int ZOOM_LOOKAHEAD = 16;

        private static volatile long lastFrameCount = -1;
        private static volatile long frameStartTime = 0;
        private static volatile int chunksThisFrame = 0;
        // Budget runs from the first chunk taken this frame. Counting from the frame start used it up on busy frames
        // before any chunk came in, so only the one guaranteed chunk got through and driving outran loading.
        private static volatile long ingestStartTime = 0;
        private static volatile long lastDrivingCheckTime = 0;
        private static volatile boolean playerIsDriving = false;

        private final java.util.concurrent.atomic.AtomicInteger approximateSize = new java.util.concurrent.atomic.AtomicInteger(0);
        private boolean takeOldestNext;
        // When each waiting chunk came in (multiplayer only)
        private final java.util.concurrent.ConcurrentHashMap<Object, Long> arrived = new java.util.concurrent.ConcurrentHashMap<>();
        // The car's heading, from where it was a moment ago
        private static float headX, headY, lastX = Float.NaN, lastY = Float.NaN;
        private static long lastHeadNanos;

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
            if (added) {
                approximateSize.incrementAndGet();
                noteArrival(e);
            }
            return added;
        }

        @Override
        public boolean offer(Object e) {
            boolean offered = super.offer(e);
            if (offered) {
                approximateSize.incrementAndGet();
                noteArrival(e);
            }
            return offered;
        }

        private void noteArrival(Object e) {
            if (e != null && headingOrder && zombie.network.GameClient.client) arrived.putIfAbsent(e, System.nanoTime());
        }

        private Object taken(Object e) {
            if (e != null && !arrived.isEmpty()) arrived.remove(e);
            return e;
        }

        // The oldest chunk has waited too long for the server's updates to it to survive
        private boolean oldestOverdue(long now) {
            if (arrived.isEmpty() || !zombie.network.GameClient.client) return false;
            Object head = super.peek();
            Long at = head == null ? null : arrived.get(head);
            return at != null && now - at > MP_MAX_WAIT_NANOS;
        }

        @Override
        public void clear() {
            super.clear();
            arrived.clear();
            approximateSize.set(0);
        }

        @Override
        public int size() {
            return Math.max(0, approximateSize.get());
        }

        @Override
        public boolean isEmpty() {
            return approximateSize.get() <= 0;
        }

        @Override
        public boolean remove(Object o) {
            boolean removed = super.remove(o);
            if (removed) {
                approximateSize.decrementAndGet();
                taken(o);
            }
            return removed;
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

            // Only reset after a gap over 500 ms (debugger pause, external stall). Resetting on smaller gaps shoved the
            // next chunks into the same frame.
            if (now - frameStartTime > 500_000_000L) {
                frameStartTime = now;
                chunksThisFrame = 0;
            }
        }

        @Override
        public Object poll() {
            // Background callers (WorldStreamer, WorldReuser) aren't metered
            String threadName = Thread.currentThread().getName();
            if (threadName != null && (threadName.contains("WorldStreamer") || threadName.contains("Reuser"))) {
                Object chunk = super.poll();
                if (chunk != null) approximateSize.decrementAndGet();
                return taken(chunk);
            }

            // No limit while loading (boot, teleports, cell loads)
            if (isEngineLoading()) {
                Object chunk = super.poll();
                if (chunk != null) approximateSize.decrementAndGet();
                return taken(chunk);
            }

            long now = System.nanoTime();

            checkFrameBoundary(now);

            // Driving across a chunk boundary needs 13-26 chunks, so take up to 32 under a 12 ms ceiling. Any lower and
            // the car outruns ingestion onto missing floor.
            boolean driving = isPlayerDriving();
            int backlog = approximateSize.get();
            int maxChunks;
            long budgetNanos;

            long baseBudgetNanos = chunkBudgetMs * 1_000_000L;
            if (driving) {
                maxChunks = Math.max(32, backlog);
                budgetNanos = Math.max(baseBudgetNanos * 2, 12_000_000L);
            } else {
                maxChunks = (backlog > 8) ? 8 : 4;
                budgetNanos = baseBudgetNanos;
            }

            if (chunksThisFrame > 0 && (chunksThisFrame >= maxChunks || (now - ingestStartTime) >= budgetNanos)) {
                if (oldestOverdue(now)) {
                    Object late = super.poll();
                    if (late != null) {
                        approximateSize.decrementAndGet();
                        chunksThisFrame++;
                    }
                    return taken(late);
                }
                // Out of budget, the rest goes next frame
                return null;
            }

            Object chunk = pollForView(driving, backlog);
            if (chunk != null) {
                approximateSize.decrementAndGet();
                if (chunksThisFrame == 0) ingestStartTime = now;
                chunksThisFrame++;
            }
            return taken(chunk);
        }

        private Object pollForView(boolean driving, int backlog) {
            if (takeOldestNext) {
                takeOldestNext = false;
                return super.poll();
            }
            if (driving && headingOrder && backlog >= 2) {
                Object ahead = pollAhead();
                if (ahead != null) return ahead;
            }
            if (driving || backlog < 2 || IsoWorld.instance == null || IsoWorld.instance.currentCell == null) {
                return super.poll();
            }

            var cell = IsoWorld.instance.currentCell;
            for (int player = 0; player < IsoPlayer.numPlayers; player++) {
                if (IsoPlayer.players[player] == null || Core.getInstance().getZoom(player) <= 1.0f) continue;
                IsoChunkMap map = cell.getChunkMap(player);
                if (map == null || map.ignore) continue;

                // A loaded neighbor's last render tells us which ground borders the player can see.
                var candidates = super.iterator();
                for (int i = 0; i < ZOOM_LOOKAHEAD && candidates.hasNext(); i++) {
                    Object entry = candidates.next();
                    if (!(entry instanceof IsoChunk chunk) || !chunk.refs.contains(map)) continue;
                    int x = chunk.wx * 8;
                    int y = chunk.wy * 8;
                    if (!visibleGround(map, player, x - 1, y) && !visibleGround(map, player, x + 8, y)
                            && !visibleGround(map, player, x, y - 1) && !visibleGround(map, player, x, y + 8)) {
                        continue;
                    }
                    if (i == 0) return super.poll();
                    if (super.remove(chunk)) {
                        // Give older work a turn even when the time budget admits only one chunk per frame.
                        takeOldestNext = true;
                        return chunk;
                    }
                }
            }
            return super.poll();
        }

        // Of the next chunks waiting, the nearest one in front of the car (within 60 degrees of its heading). Older work
        // still gets every other turn through takeOldestNext.
        private Object pollAhead() {
            IsoPlayer p = IsoPlayer.getInstance();
            long now = System.nanoTime();
            if (p == null || p.getVehicle() == null || oldestOverdue(now)) return null;
            float px = p.getX(), py = p.getY();
            if (Float.isNaN(lastX) || now - lastHeadNanos > 1_000_000_000L) {
                lastX = px;
                lastY = py;
                lastHeadNanos = now;
                headX = headY = 0.0f;
                return null;
            }
            float mx = px - lastX, my = py - lastY;
            float moved = (float) Math.sqrt(mx * mx + my * my);
            if (moved > 2.0f) {
                headX = mx / moved;
                headY = my / moved;
                lastX = px;
                lastY = py;
                lastHeadNanos = now;
            }
            if (headX == 0.0f && headY == 0.0f) return null;
            Object best = null;
            float bestDist = Float.MAX_VALUE;
            var it = super.iterator();
            for (int i = 0; i < 32 && it.hasNext(); i++) {
                Object e = it.next();
                if (!(e instanceof IsoChunk c)) continue;
                float dx = c.wx * 8 + 4 - px, dy = c.wy * 8 + 4 - py;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d < 1.0f || (dx * headX + dy * headY) / d < 0.5f) continue;
                // The oldest one is already ahead: plain order does the same
                if (i == 0) return null;
                if (d < bestDist) {
                    bestDist = d;
                    best = e;
                }
            }
            if (best != null && super.remove(best)) {
                takeOldestNext = true;
                return taken(best);
            }
            return null;
        }

        private static boolean visibleGround(IsoChunkMap map, int player, int x, int y) {
            IsoChunk neighbor = map.getChunkForGridSquare(x, y);
            return neighbor != null && neighbor.loaded && !neighbor.lightingNeverDone[player]
                && neighbor.minLevel <= 0 && neighbor.maxLevel >= 0 && neighbor.getRenderLevels(player).isOnScreen(0);
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
