package com.pzoptimizer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import zombie.pathfind.VehiclePoly;
import zombie.pathfind.nativeCode.PathFindRequest;
import zombie.pathfind.nativeCode.PathfindNative;
import zombie.vehicles.BaseVehicle;

/**
 * Throughput fixes for the native pathfinder (zombie.pathfind.nativeCode + PZPathFind64.dll).
 *
 * PathfindNativeThread runs at 20 Hz and vanilla answers only two path requests per iteration, so the
 * whole world gets about 40 paths a second. On top of that, any chunk, square or vehicle update since
 * the last search makes the DLL rebuild its entire navigation graph inside the next findPath, so while
 * vehicles move or doors change nearly every pair of paths pays for a full rebuild.
 *
 * - request budget: requests are answered until a time budget is spent (never fewer than vanilla's
 *   two), so one rebuild serves many paths
 * - square merge: repeated updates of one square within an iteration collapse to the latest
 * - vehicle throttle: a vehicle's navmesh polygon is resent after it moves 0.25 tiles or every
 *   250 ms, instead of on every tiny change
 * - native A*: replaces the DLL's linear-scan decrease-key with an indexed one (Windows, exact DLL
 *   build only, see pzo_native.c)
 *
 * updateThread() is a prefix hook on PathfindNativeThread.updateThread(); when it returns true the
 * vanilla body is skipped. Its loop mirrors vanilla's order exactly: chunk tasks (10 chunk updates
 * max), squares, vehicles, request tasks, PathfindNative.update(), then the requests.
 */
public final class PathfindGovernor {

    private static volatile boolean budgetActive = true;
    private static volatile boolean squareMerge = true;
    private static volatile boolean vehicleThrottle = true;
    private static volatile boolean nativeAStarWanted = false;
    private static volatile int budgetMs = 8;

    private static final int VANILLA_REQUESTS_PER_UPDATE = 2;
    private static final int MAX_REQUESTS_PER_UPDATE = 512;
    private static final float VEHICLE_MOVE_TILES = 0.25F;
    private static final long VEHICLE_MAX_STALE_NS = 250_000_000L;
    private static final long STATS_WINDOW_NS = 30_000_000_000L;

    private static volatile boolean threadReflectionFailed = false;
    private static boolean threadReflected = false;
    private static VarHandle chunkTaskQueue, squareTaskQueue, vehicleTaskQueue, requestTaskQueue, taskReturnQueue;
    private static VarHandle requestsField, requestToMain, playerQ, aggroZombieQ, otherQ;
    private static VarHandle requestCancel, requestPath, squareX, squareY, squareZ;
    private static MethodHandle taskExecute, findPath, findShortestPathOfMultiple, pathClear;
    private static Class<?> chunkUpdateTaskClass;

    private static volatile boolean vehicleReflectionFailed = false;
    private static volatile boolean vehicleReflected = false;
    private static VarHandle stateVehicle, statePoly;
    private static MethodHandle vanillaCheck;
    private static final IdentityHashMap<Object, long[]> vehicleLastSent = new IdentityHashMap<>();

    private static boolean dirtySinceSearch = false;
    private static final ArrayList<Object> squareScratch = new ArrayList<>();
    private static long[] squareKeys = new long[1024];
    private static boolean[] squareKeep = new boolean[1024];

    // Native A* hook state, touched only on the pathfinding thread
    private static boolean nativeAStarInstalled = false;
    private static boolean nativeAStarUnavailable = false;
    private static long nativeAStarRetryAt = 0L;

    // Counters are plain longs: telemetry only, written by a single thread each
    private static long statPaths, statFindNanos, statMaxFindNanos, statRebuilds;
    private static long statSquares, statSquaresMerged, statBacklogMax;
    private static long statVehicleSent, statVehicleSkipped;
    private static long statWindowStart = System.nanoTime();
    private static final long[] nativeStats = new long[3];
    private static final long[] nativeStatsWindowStart = new long[3];

    private PathfindGovernor() {}

    public static void setBudgetActive(boolean active) { budgetActive = active; }
    public static void setSquareMerge(boolean active) { squareMerge = active; }
    public static void setVehicleThrottle(boolean active) { vehicleThrottle = active; }
    public static void setNativeAStar(boolean active) { nativeAStarWanted = active; }
    public static void setBudgetMs(int ms) { budgetMs = Math.max(1, Math.min(40, ms)); }
    public static boolean isNativeAStarInstalled() { return nativeAStarInstalled; }

    /** Prefix hook on PathfindNativeThread.updateThread(); true skips the vanilla body. */
    public static boolean updateThread(Object thread) {
        syncNativeAStar();
        if (!budgetActive && !squareMerge) return false;
        if (threadReflectionFailed || !reflectThread(thread)) return false;
        try {
            runIteration(thread);
        } catch (Throwable t) {
            // Same as vanilla: the exception reaches PathfindNativeThread.run(), which logs it and loops
            throw sneaky(t);
        }
        return true;
    }

    private static void runIteration(Object thread) throws Throwable {
        ConcurrentLinkedQueue<Object> returnQ = queue(taskReturnQueue, thread);

        ConcurrentLinkedQueue<Object> chunks = queue(chunkTaskQueue, thread);
        int update = 10;
        for (Object task = chunks.poll(); task != null; task = chunks.poll()) {
            taskExecute.invoke(task);
            returnQ.add(task);
            dirtySinceSearch = true;
            if (chunkUpdateTaskClass.isInstance(task) && --update <= 0) break;
        }

        ConcurrentLinkedQueue<Object> squares = queue(squareTaskQueue, thread);
        if (squareMerge) {
            drainSquaresMerged(squares, returnQ);
        } else {
            for (Object task = squares.poll(); task != null; task = squares.poll()) {
                taskExecute.invoke(task);
                returnQ.add(task);
                statSquares++;
                dirtySinceSearch = true;
            }
        }

        ConcurrentLinkedQueue<Object> vehicles = queue(vehicleTaskQueue, thread);
        for (Object task = vehicles.poll(); task != null; task = vehicles.poll()) {
            taskExecute.invoke(task);
            returnQ.add(task);
            dirtySinceSearch = true;
        }

        ConcurrentLinkedQueue<Object> requestTasks = queue(requestTaskQueue, thread);
        for (Object task = requestTasks.poll(); task != null; task = requestTasks.poll()) {
            taskExecute.invoke(task);
            returnQ.add(task);
        }

        PathfindNative.update();

        Object requests = requestsField.get(thread);
        ArrayDeque<?> pq = (ArrayDeque<?>) playerQ.get(requests);
        ArrayDeque<?> aq = (ArrayDeque<?>) aggroZombieQ.get(requests);
        ArrayDeque<?> oq = (ArrayDeque<?>) otherQ.get(requests);
        long backlog = (long) pq.size() + aq.size() + oq.size();
        if (backlog > statBacklogMax) statBacklogMax = backlog;

        ConcurrentLinkedQueue<Object> toMain = queue(requestToMain, thread);
        boolean budgeted = budgetActive;
        long deadline = System.nanoTime() + budgetMs * 1_000_000L;
        int served = 0;
        while (!pq.isEmpty() || !aq.isEmpty() || !oq.isEmpty()) {
            // RequestQueue.removeFirst(): players, then aggro zombies, then everything else
            PathFindRequest request = (PathFindRequest) (!pq.isEmpty() ? pq.removeFirst()
                : !aq.isEmpty() ? aq.removeFirst() : oq.removeFirst());
            if ((boolean) requestCancel.get(request)) {
                toMain.add(request);
                continue;
            }
            if (dirtySinceSearch) {
                statRebuilds++;
                dirtySinceSearch = false;
            }
            long t0 = System.nanoTime();
            try {
                findPath.invoke(thread, request);
                if (!request.targetXyz.isEmpty()) {
                    findShortestPathOfMultiple.invoke(thread, request);
                }
            } catch (Throwable t) {
                zombie.core.logger.ExceptionLogger.logException(t);
                pathClear.invoke(requestPath.get(request));
            }
            long t1 = System.nanoTime();
            long took = t1 - t0;
            statFindNanos += took;
            if (took > statMaxFindNanos) statMaxFindNanos = took;
            statPaths++;
            toMain.add(request);
            served++;
            if (!budgeted) {
                if (served == VANILLA_REQUESTS_PER_UPDATE) break;
            } else if ((served >= VANILLA_REQUESTS_PER_UPDATE && t1 >= deadline) || served >= MAX_REQUESTS_PER_UPDATE) {
                break;
            }
        }

        maybeLogStats();
    }

    /**
     * squareChanged() queues the square and its 8 neighbours, so busy areas send the same square many
     * times per iteration. Each task snapshots the square when queued, so only the newest one per
     * square matters; it is executed in its original queue position relative to the other squares.
     */
    private static void drainSquaresMerged(ConcurrentLinkedQueue<Object> squares, ConcurrentLinkedQueue<Object> returnQ) throws Throwable {
        ArrayList<Object> tasks = squareScratch;
        tasks.clear();
        for (Object task = squares.poll(); task != null; task = squares.poll()) {
            tasks.add(task);
        }
        int n = tasks.size();
        if (n == 0) return;
        int cap = Math.max(16, Integer.highestOneBit(n * 2 - 1) << 1);
        if (squareKeys.length < cap) squareKeys = new long[cap];
        if (squareKeep.length < n) squareKeep = new boolean[Math.max(n, squareKeep.length * 2)];
        long[] keys = squareKeys;
        Arrays.fill(keys, 0, cap, Long.MIN_VALUE);
        int mask = cap - 1;
        int kept = 0;
        for (int i = n - 1; i >= 0; i--) {
            Object task = tasks.get(i);
            long key = (((long) ((int) squareX.get(task)) & 0xFFFFFFL) << 40)
                | (((long) ((int) squareY.get(task)) & 0xFFFFFFL) << 16)
                | ((long) ((int) squareZ.get(task)) & 0xFFFFL);
            int h = (int) ((key * 0x539B5387C0D72013L) >>> 40) & mask;
            boolean seen = false;
            while (keys[h] != Long.MIN_VALUE) {
                if (keys[h] == key) { seen = true; break; }
                h = (h + 1) & mask;
            }
            if (!seen) {
                keys[h] = key;
                kept++;
            }
            squareKeep[i] = !seen;
        }
        for (int i = 0; i < n; i++) {
            if (squareKeep[i]) taskExecute.invoke(tasks.get(i));
        }
        // Released only after every execute, so the main thread cannot recycle a task we still read
        for (int i = 0; i < n; i++) returnQ.add(tasks.get(i));
        tasks.clear();
        statSquares += n;
        statSquaresMerged += n - kept;
        dirtySinceSearch = true;
    }

    /** Redirect target for VehicleState.check() inside PathfindNative.updateMain (main thread). */
    public static boolean vehicleCheck(Object state) {
        boolean reflected = !vehicleReflectionFailed && reflectVehicle(state);
        if (!vehicleThrottle || !reflected) {
            // Without a handle to the vanilla check, report a change: extra updates only cost rebuilds
            if (vanillaCheck == null) return true;
            try {
                boolean changed = (boolean) vanillaCheck.invoke(state);
                if (changed) statVehicleSent++;
                return changed;
            } catch (Throwable t) {
                throw sneaky(t);
            }
        }
        BaseVehicle vehicle = (BaseVehicle) stateVehicle.get(state);
        VehiclePoly sent = (VehiclePoly) statePoly.get(state);
        VehiclePoly now = vehicle.getPolyPlusRadius();
        if (sent.isEqual(now)) return false;

        long t = System.nanoTime();
        long[] last = vehicleLastSent.get(state);
        if (last == null) {
            last = new long[] { t };
            vehicleLastSent.put(state, last);
            if (vehicleLastSent.size() > 4096) vehicleLastSent.clear();
        }
        float moved = Math.max(
            Math.max(Math.max(Math.abs(sent.x1 - now.x1), Math.abs(sent.y1 - now.y1)),
                     Math.max(Math.abs(sent.x2 - now.x2), Math.abs(sent.y2 - now.y2))),
            Math.max(Math.max(Math.abs(sent.x3 - now.x3), Math.abs(sent.y3 - now.y3)),
                     Math.max(Math.abs(sent.x4 - now.x4), Math.abs(sent.y4 - now.y4))));
        if (moved >= VEHICLE_MOVE_TILES || Math.abs(sent.z - now.z) > 0.01F || t - last[0] >= VEHICLE_MAX_STALE_NS) {
            sent.init(now);
            last[0] = t;
            statVehicleSent++;
            return true;
        }
        statVehicleSkipped++;
        return false;
    }

    private static void syncNativeAStar() {
        boolean wanted = nativeAStarWanted;
        if (wanted == nativeAStarInstalled || nativeAStarUnavailable) return;
        if (!PZONative.isLoaded()) return;
        long now = System.nanoTime();
        if (wanted && now < nativeAStarRetryAt) return;
        int result;
        try {
            result = PZONative.setPathfindAStarHook(wanted);
        } catch (UnsatisfiedLinkError e) {
            nativeAStarUnavailable = true;
            PZOLogger.warn("[PathfindGovernor] Native A* needs an updated pzo_native library; staying on the game's search");
            return;
        }
        if (result == 1) {
            nativeAStarInstalled = true;
            PZOLogger.success("[PathfindGovernor] Native A* indexed decrease-key installed in PZPathFind64.dll");
        } else if (result == 0) {
            nativeAStarInstalled = false;
            if (!wanted) PZOLogger.info("[PathfindGovernor] Native A* restored to the game's original search");
        } else if (result == -1) {
            nativeAStarRetryAt = now + 5_000_000_000L;
        } else {
            nativeAStarUnavailable = true;
            String why = result == -2 ? "PZPathFind64.dll is a different build than the one this was made for"
                : result == -4 ? "only the Windows build is supported" : "the code page could not be written";
            PZOLogger.warn("[PathfindGovernor] Native A* not installed: " + why);
        }
    }

    private static void maybeLogStats() {
        long now = System.nanoTime();
        if (now - statWindowStart < STATS_WINDOW_NS) return;
        double secs = (now - statWindowStart) / 1e9;
        if (statPaths > 0 || statSquares > 0) {
            StringBuilder sb = new StringBuilder(256);
            sb.append(String.format("[PathfindGovernor] last %.0fs: %d paths (%.1f/s, avg %.2f ms, max %.1f ms), ",
                secs, statPaths, statPaths / secs,
                statPaths > 0 ? statFindNanos / 1e6 / statPaths : 0.0, statMaxFindNanos / 1e6));
            sb.append(String.format("%d graph rebuilds (%.1f paths each), backlog max %d, ",
                statRebuilds, statRebuilds > 0 ? (double) statPaths / statRebuilds : (double) statPaths, statBacklogMax));
            sb.append(String.format("squares %d (%d merged), vehicle updates %d sent / %d skipped",
                statSquares, statSquaresMerged, statVehicleSent, statVehicleSkipped));
            if (nativeAStarInstalled) {
                try {
                    PZONative.getPathfindAStarStats(nativeStats);
                    long searches = nativeStats[0] - nativeStatsWindowStart[0];
                    long expanded = nativeStats[1] - nativeStatsWindowStart[1];
                    long decreases = nativeStats[2] - nativeStatsWindowStart[2];
                    System.arraycopy(nativeStats, 0, nativeStatsWindowStart, 0, 3);
                    sb.append(String.format(", native A*: %d searches, %d nodes expanded, %d indexed decrease-keys",
                        searches, expanded, decreases));
                } catch (Throwable ignored) {}
            }
            PZOLogger.info(sb.toString());
        }
        statWindowStart = now;
        statPaths = statFindNanos = statMaxFindNanos = statRebuilds = 0;
        statSquares = statSquaresMerged = statBacklogMax = statVehicleSent = statVehicleSkipped = 0;
    }

    private static boolean reflectThread(Object thread) {
        if (threadReflected) return true;
        try {
            Class<?> tc = thread.getClass();
            ClassLoader cl = tc.getClassLoader();
            MethodHandles.Lookup lk = MethodHandles.privateLookupIn(tc, MethodHandles.lookup());
            chunkTaskQueue = lk.findVarHandle(tc, "chunkTaskQueue", ConcurrentLinkedQueue.class);
            squareTaskQueue = lk.findVarHandle(tc, "squareTaskQueue", ConcurrentLinkedQueue.class);
            vehicleTaskQueue = lk.findVarHandle(tc, "vehicleTaskQueue", ConcurrentLinkedQueue.class);
            requestTaskQueue = lk.findVarHandle(tc, "requestTaskQueue", ConcurrentLinkedQueue.class);
            taskReturnQueue = lk.findVarHandle(tc, "taskReturnQueue", ConcurrentLinkedQueue.class);
            requestToMain = lk.findVarHandle(tc, "requestToMain", ConcurrentLinkedQueue.class);
            Class<?> rq = Class.forName("zombie.pathfind.nativeCode.RequestQueue", false, cl);
            requestsField = lk.findVarHandle(tc, "requests", rq);
            MethodHandles.Lookup rqLk = MethodHandles.privateLookupIn(rq, MethodHandles.lookup());
            playerQ = rqLk.findVarHandle(rq, "playerQ", ArrayDeque.class);
            aggroZombieQ = rqLk.findVarHandle(rq, "aggroZombieQ", ArrayDeque.class);
            otherQ = rqLk.findVarHandle(rq, "otherQ", ArrayDeque.class);
            findPath = lk.findVirtual(tc, "findPath", MethodType.methodType(void.class, PathFindRequest.class));
            findShortestPathOfMultiple = lk.findVirtual(tc, "findShortestPathOfMultiple", MethodType.methodType(void.class, PathFindRequest.class));

            Class<?> task = Class.forName("zombie.pathfind.nativeCode.IPathfindTask", false, cl);
            taskExecute = MethodHandles.privateLookupIn(task, MethodHandles.lookup())
                .findVirtual(task, "execute", MethodType.methodType(void.class));
            chunkUpdateTaskClass = Class.forName("zombie.pathfind.nativeCode.ChunkUpdateTask", false, cl);

            Class<?> sq = Class.forName("zombie.pathfind.nativeCode.SquareUpdateTask", false, cl);
            MethodHandles.Lookup sqLk = MethodHandles.privateLookupIn(sq, MethodHandles.lookup());
            squareX = sqLk.findVarHandle(sq, "x", int.class);
            squareY = sqLk.findVarHandle(sq, "y", int.class);
            squareZ = sqLk.findVarHandle(sq, "z", int.class);

            MethodHandles.Lookup reqLk = MethodHandles.privateLookupIn(PathFindRequest.class, MethodHandles.lookup());
            requestCancel = reqLk.findVarHandle(PathFindRequest.class, "cancel", boolean.class);
            requestPath = reqLk.findVarHandle(PathFindRequest.class, "path", zombie.pathfind.Path.class);
            pathClear = MethodHandles.lookup().findVirtual(zombie.pathfind.Path.class, "clear", MethodType.methodType(void.class));

            threadReflected = true;
            PZOLogger.success("[PathfindGovernor] Pathfinding thread hooked (request budget " + budgetMs + " ms, square merge "
                + (squareMerge ? "on" : "off") + ")");
            return true;
        } catch (Throwable t) {
            threadReflectionFailed = true;
            PZOLogger.warn("[PathfindGovernor] Pathfinding thread layout differs from this build; using the game's loop: " + t);
            return false;
        }
    }

    private static boolean reflectVehicle(Object state) {
        if (vehicleReflected) return true;
        synchronized (PathfindGovernor.class) {
            if (vehicleReflected) return true;
            try {
                Class<?> sc = state.getClass();
                MethodHandles.Lookup lk = MethodHandles.privateLookupIn(sc, MethodHandles.lookup());
                vanillaCheck = lk.findVirtual(sc, "check", MethodType.methodType(boolean.class))
                    .asType(MethodType.methodType(boolean.class, Object.class));
                stateVehicle = lk.findVarHandle(sc, "vehicle", BaseVehicle.class);
                statePoly = lk.findVarHandle(sc, "poly", VehiclePoly.class);
                vehicleReflected = true;
                return true;
            } catch (Throwable t) {
                vehicleReflectionFailed = true;
                PZOLogger.warn("[PathfindGovernor] VehicleState layout differs from this build; vehicle throttle off: " + t);
                return false;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentLinkedQueue<Object> queue(VarHandle h, Object thread) {
        return (ConcurrentLinkedQueue<Object>) h.get(thread);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }
}
