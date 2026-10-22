package com.pzoptimizer;

import java.io.File;
import java.io.FileWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Measures frame times and logs frame drops and stutters with their likely cause. Only active when the
 * game is launched with -debug; for normal players onFrameTick just drives the governors that hang off it.
 */
public final class FrameDropDiagnosticEngine {

    private static volatile boolean running = false;
    // -1 until the first frame tick, when Core.debug has been parsed from the launch arguments
    private static int debugState = -1;
    private static boolean profilerRequested = false;

    private static long frameGcTimeMs = 0;
    private static int lastZombieCount = -1;
    private static int zombieSpawnsThisFrame = 0;

    private static long windowStartNanos = 0;
    private static int windowFrames = 0;
    private static double windowFrameMsSum = 0;
    private static double windowWorstMs = 0;
    private static long windowGcMs = 0;
    private static int windowZombieSpawns = 0;
    private static boolean windowHadPause = false;
    private static int windowsSampled = 0;
    private static double baselineFps = 0;
    private static long lastFrameTimeNanos = System.nanoTime();
    private static final double[] frameTimeHistory = new double[240]; // 4 seconds at 60 FPS
    private static int historyIndex = 0;
    private static int totalFramesSampled = 0;
    private static int stutterCount = 0;
    private static double lastStutterMs = 0.0;
    private static String lastStutterCause = "None";

    private static long lastGcCount = 0;
    private static long lastGcTimeMs = 0;
    private static int lastDiagnosedChunkX = Integer.MIN_VALUE;
    private static int lastDiagnosedChunkY = Integer.MIN_VALUE;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static final ConcurrentLinkedQueue<String> pendingDiagnosticLogs = new ConcurrentLinkedQueue<>();
    private static long lastDiskFlushTime = 0;

    private static volatile String latestStatusJson = null;

    private static boolean reflectionResolved = false;
    private static Method playerGetX;
    private static Method playerGetY;
    private static Method playerGetVehicle;
    private static Method vehicleGetSpeed;
    private static Field isoPlayerPlayersField;
    private static Field isoWorldInstanceField;
    private static Field isoWorldCellField;
    private static Method cellGetZombieList;
    private static Object deadBodyObjType;
    private static Method deadBodyGetObjects;
    private static Field wsInstanceField;
    private static Field wsMainThreadQField;
    private static Field cswInstanceField;
    private static Field cswToSaveQField;
    private static Field lgsField;

    /** The logger thread starts on the first frame tick, and only for a debug launch. */
    public static void initialize() {
    }

    private static boolean isDebugSession() {
        if (debugState < 0) {
            boolean dbg = false;
            try { dbg = zombie.core.Core.debug; } catch (Throwable ignored) {}
            debugState = dbg ? 1 : 0;
            if (dbg) {
                startDebugDiagnostics();
            } else {
                PZOLogger.info("FrameDropDiagnosticEngine: inactive (launch with -debug to enable stutter diagnostics)");
            }
        }
        return debugState == 1;
    }

    private static void startDebugDiagnostics() {
        if (running) return;
        running = true;

        updateGcStats();
        pendingDiagnosticLogs.offer("=== [" + DATE_FORMAT.format(new Date()) + "] PZO debug diagnostics session " + UpdateChecker.CURRENT_VERSION + " ===");

        Thread loggerThread = new Thread(() -> {
            PZOLogger.success("FrameDropDiagnosticEngine: Active (debug launch, stutter and frame dip diagnostics)");

            while (running) {
                try {
                    Thread.sleep(1000);
                    flushDiagnosticsToDisk();

                    String statusJson = latestStatusJson;
                    if (statusJson != null) {
                        TelemetryReporter.writeStatusFile(statusJson);
                    }
                } catch (Throwable ignored) {
                    try { Thread.sleep(2000); } catch (Throwable ignored2) {}
                }
            }
        });

        loggerThread.setName("PZO-FrameDiagnostics");
        loggerThread.setDaemon(true);
        loggerThread.setPriority(Thread.MIN_PRIORITY);
        loggerThread.start();
    }

    private static void resolveReflection() {
        if (reflectionResolved) return;
        try {
            Class<?> pClass = Class.forName("zombie.characters.IsoPlayer");
            try { isoPlayerPlayersField = pClass.getField("players"); } catch (Throwable ignored) {}
            try { playerGetX = pClass.getMethod("getX"); } catch (Throwable ignored) {}
            try { playerGetY = pClass.getMethod("getY"); } catch (Throwable ignored) {}
            try { playerGetVehicle = pClass.getMethod("getVehicle"); } catch (Throwable ignored) {}

            try {
                Class<?> vClass = Class.forName("zombie.vehicles.BaseVehicle");
                vehicleGetSpeed = vClass.getMethod("getCurrentSpeedKmHour");
            } catch (Throwable ignored) {}

            try {
                Class<?> wClass = Class.forName("zombie.iso.IsoWorld");
                isoWorldInstanceField = wClass.getField("instance");
                isoWorldCellField = wClass.getField("currentCell");
            } catch (Throwable ignored) {}

            try {
                Class<?> cClass = Class.forName("zombie.iso.IsoCell");
                try { cellGetZombieList = cClass.getMethod("getZombieList"); } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}

            try {
                Class<?> objIdClass = Class.forName("zombie.network.id.ObjectIDType");
                Field dbField = objIdClass.getField("DeadBody");
                deadBodyObjType = dbField.get(null);
                if (deadBodyObjType != null) {
                    deadBodyGetObjects = deadBodyObjType.getClass().getMethod("getObjects");
                }
            } catch (Throwable ignored) {}

            try {
                Class<?> wsClass = Class.forName("zombie.iso.WorldStreamer");
                wsInstanceField = wsClass.getField("instance");
                wsMainThreadQField = wsClass.getDeclaredField("mainThreadRequestQueue");
                wsMainThreadQField.setAccessible(true);
            } catch (Throwable ignored) {}

            try {
                Class<?> cswClass = Class.forName("zombie.iso.ChunkSaveWorker");
                cswInstanceField = cswClass.getField("instance");
                cswToSaveQField = cswClass.getField("toSaveQueue");
            } catch (Throwable ignored) {}

            try {
                Class<?> chunkClass = Class.forName("zombie.iso.IsoChunk");
                lgsField = chunkClass.getField("loadGridSquare");
            } catch (Throwable ignored) {}

            reflectionResolved = true;
        } catch (Throwable ignored) {}
    }

    public static void onFrameTick() {
        long now = System.nanoTime();
        long deltaNanos = now - lastFrameTimeNanos;
        lastFrameTimeNanos = now;

        if (deltaNanos <= 0 || deltaNanos > 2_000_000_000L) {
            return;
        }

        ZombieLodGovernor.recordFrameStep(deltaNanos);
        BodyAtlasPrewarmGovernor.onFrameTick();
        WorldUpscalerGovernor.updateDynamicScale(deltaNanos, ZombieLodGovernor.getTargetFps());

        double frameTimeMs = deltaNanos / 1_000_000.0;

        frameTimeHistory[historyIndex] = frameTimeMs;
        historyIndex = (historyIndex + 1) % frameTimeHistory.length;
        totalFramesSampled++;

        if ((totalFramesSampled & 63) == 0) {
            WorkerRandGovernor.ensureInstalled();
        }

        if (!isDebugSession()) {
            return;
        }

        Object player = getActivePlayer();
        if (player == null) {
            PhaseProfiler.onFrameBoundary();
            resetWindow(0);
            lastZombieCount = -1;
            return;
        }

        if (!profilerRequested) {
            profilerRequested = true;
            PhaseProfiler.enable();
        }
        PhaseProfiler.onFrameBoundary();

        long gcBefore = lastGcTimeMs;
        updateGcStats();
        frameGcTimeMs = lastGcTimeMs - gcBefore;

        int zombies = getZombieCount();
        zombieSpawnsThisFrame = (lastZombieCount >= 0 && zombies > lastZombieCount) ? zombies - lastZombieCount : 0;
        lastZombieCount = zombies;

        double avgFrameTime = getAverageFrameTime();
        if (frameTimeMs > 28.0 && frameTimeMs > avgFrameTime * 1.65) {
            diagnoseFrameDrop(player, frameTimeMs, avgFrameTime);
        }

        trackWindow(now, frameTimeMs, zombies);

        if (totalFramesSampled % 60 == 0) {
            updateLiveTelemetry(frameTimeMs, avgFrameTime);
        }
    }

    /**
     * Sustained dips (165 -> 60 fps, say) never cross the single-frame stutter threshold, so frame times
     * are also averaged per second and compared against a slow moving baseline of normal play.
     */
    private static void trackWindow(long now, double frameTimeMs, int zombies) {
        if (windowStartNanos == 0) {
            resetWindow(now);
        }
        windowFrames++;
        windowFrameMsSum += frameTimeMs;
        if (frameTimeMs > windowWorstMs) windowWorstMs = frameTimeMs;
        windowGcMs += frameGcTimeMs;
        windowZombieSpawns += zombieSpawnsThisFrame;
        if (isGamePaused()) windowHadPause = true;

        if (now - windowStartNanos < 1_000_000_000L) return;

        double avgMs = windowFrameMsSum / Math.max(1, windowFrames);
        double fps = 1000.0 / Math.max(0.1, avgMs);
        String areas = PhaseProfiler.describeAndResetWindow(windowFrames);

        if (!windowHadPause) {
            windowsSampled++;
            if (baselineFps <= 0) {
                baselineFps = fps;
            } else if (windowsSampled > 5 && baselineFps >= 40 && fps < baselineFps * 0.7) {
                pendingDiagnosticLogs.offer(String.format(
                    "[%s] DIP: %.0f fps for 1s (baseline %.0f fps | avg %.1f ms | worst %.1f ms) | Zombies: %d (+%d spawned) | GC: %dms | Main thread per frame: %s",
                    DATE_FORMAT.format(new Date()), fps, baselineFps, avgMs, windowWorstMs, zombies, windowZombieSpawns, windowGcMs, areas));
            } else {
                // Only normal seconds move the baseline, so a long dip doesn't become the new normal
                baselineFps = baselineFps * 0.9 + fps * 0.1;
            }
        }

        resetWindow(now);
    }

    private static void resetWindow(long now) {
        windowStartNanos = now;
        windowFrames = 0;
        windowFrameMsSum = 0;
        windowWorstMs = 0;
        windowGcMs = 0;
        windowZombieSpawns = 0;
        windowHadPause = false;
        PhaseProfiler.resetWindow();
    }

    private static int getZombieCount() {
        try {
            if (isoWorldInstanceField != null && isoWorldCellField != null && cellGetZombieList != null) {
                Object worldInst = isoWorldInstanceField.get(null);
                if (worldInst != null) {
                    Object cell = isoWorldCellField.get(worldInst);
                    if (cell != null) {
                        List<?> zList = (List<?>) cellGetZombieList.invoke(cell);
                        if (zList != null) return zList.size();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static boolean isGamePaused() {
        try {
            return zombie.GameTime.isGamePaused();
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object getActivePlayer() {
        try {
            if (!reflectionResolved) resolveReflection();
            if (isoPlayerPlayersField != null) {
                Object[] players = (Object[]) isoPlayerPlayersField.get(null);
                if (players != null && players.length > 0 && players[0] != null) {
                    return players[0];
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void diagnoseFrameDrop(Object player, double frameTimeMs, double avgFrameTime) {
        stutterCount++;
        lastStutterMs = frameTimeMs;

        long gcDeltaTimeMs = frameGcTimeMs;

        boolean isDriving = false;
        float vehicleSpeed = 0.0f;
        float playerX = 0.0f, playerY = 0.0f;
        int chunkX = 0, chunkY = 0;
        int activeZombies = 0;
        int activeCorpses = 0;
        int wsQueueSize = 0;
        int saveQueueSize = 0;
        int ingestionQueueSize = 0;

        try {
            if (playerGetX != null && playerGetY != null) {
                playerX = ((Number) playerGetX.invoke(player)).floatValue();
                playerY = ((Number) playerGetY.invoke(player)).floatValue();
                chunkX = (int) Math.floor(playerX / 8.0f);
                chunkY = (int) Math.floor(playerY / 8.0f);
            }

            if (playerGetVehicle != null) {
                Object vehicle = playerGetVehicle.invoke(player);
                if (vehicle != null) {
                    isDriving = true;
                    if (vehicleGetSpeed != null) {
                        vehicleSpeed = ((Number) vehicleGetSpeed.invoke(vehicle)).floatValue();
                    }
                }
            }

            if (isoWorldInstanceField != null && isoWorldCellField != null) {
                Object worldInst = isoWorldInstanceField.get(null);
                if (worldInst != null) {
                    Object cell = isoWorldCellField.get(worldInst);
                    if (cell != null && cellGetZombieList != null) {
                        List<?> zList = (List<?>) cellGetZombieList.invoke(cell);
                        if (zList != null) activeZombies = zList.size();
                    }
                }
            }

            if (deadBodyObjType != null && deadBodyGetObjects != null) {
                Object objs = deadBodyGetObjects.invoke(deadBodyObjType);
                if (objs instanceof java.util.Collection) {
                    activeCorpses = ((java.util.Collection<?>) objs).size();
                }
            }

            if (wsInstanceField != null && wsMainThreadQField != null) {
                Object wsInst = wsInstanceField.get(null);
                if (wsInst != null) {
                    Queue<?> q = (Queue<?>) wsMainThreadQField.get(wsInst);
                    if (q != null && !q.isEmpty()) wsQueueSize = q.size();
                }
            }

            if (cswInstanceField != null && cswToSaveQField != null) {
                Object cswInst = cswInstanceField.get(null);
                if (cswInst != null) {
                    Queue<?> sq = (Queue<?>) cswToSaveQField.get(cswInst);
                    if (sq != null && !sq.isEmpty()) saveQueueSize = sq.size();
                }
            }

            if (lgsField != null) {
                Object lgsObj = lgsField.get(null);
                if (lgsObj instanceof java.util.Collection) {
                    java.util.Collection<?> col = (java.util.Collection<?>) lgsObj;
                    if (!col.isEmpty()) ingestionQueueSize = col.size();
                }
            }
        } catch (Throwable ignored) {}

        boolean chunkCrossing = (lastDiagnosedChunkX != Integer.MIN_VALUE) && (chunkX != lastDiagnosedChunkX || chunkY != lastDiagnosedChunkY);
        lastDiagnosedChunkX = chunkX;
        lastDiagnosedChunkY = chunkY;

        double mainMs = PhaseProfiler.lastFrameAccountedMs();
        boolean haveProfile = PhaseProfiler.isActive() && mainMs > 0;

        String cause;
        if (gcDeltaTimeMs > 5 && gcDeltaTimeMs >= frameTimeMs * 0.25) {
            cause = "GC_STW_PAUSE (" + gcDeltaTimeMs + "ms)";
        } else if (chunkCrossing) {
            if (isDriving) {
                cause = "VEHICLE_CHUNK_CROSSING (Chunk: " + chunkX + "," + chunkY + " | Speed: " + String.format("%.1f", vehicleSpeed) + " km/h)";
            } else {
                cause = "FOOT_CHUNK_CROSSING (Chunk: " + chunkX + "," + chunkY + ")";
            }
        } else if (ingestionQueueSize > 0) {
            cause = "CHUNK_INGESTION_BACKLOG (" + ingestionQueueSize + " chunks remaining)";
        } else if (wsQueueSize > 0) {
            if (isDriving) {
                cause = "VEHICLE_CHUNK_STREAMING (Speed: " + String.format("%.1f", vehicleSpeed) + " km/h, WS Queue: " + wsQueueSize + ")";
            } else {
                cause = "CHUNK_STREAMING_IO (WS Queue: " + wsQueueSize + " chunks)";
            }
        } else if (saveQueueSize > 0) {
            cause = "DISK_AUTOSAVE_SPIKE (SaveQueue: " + saveQueueSize + " chunks)";
        } else if (activeCorpses > 50) {
            cause = "CORPSE_DENSITY_BURDEN (" + activeCorpses + " corpses)";
        } else if (zombieSpawnsThisFrame >= 5) {
            cause = "ZOMBIE_SPAWN_IN (+" + zombieSpawnsThisFrame + " zombies this frame)";
        } else if (activeZombies > 100) {
            cause = "HORDE_PHYSICS_DENSITY (" + activeZombies + " zombies)";
        } else if (haveProfile && mainMs < frameTimeMs * 0.4) {
            // The main thread was mostly idle or outside any profiled area: waiting on the render thread, GPU or a lock
            cause = String.format("RENDER_THREAD_OR_GPU_WAIT (main thread profiled %.1f of %.1f ms)", mainMs, frameTimeMs);
        } else if (haveProfile) {
            cause = "MAIN_THREAD_WORK";
        } else {
            cause = "UNCLASSIFIED";
        }

        lastStutterCause = cause;

        String timestamp = DATE_FORMAT.format(new Date());
        String logEntry = String.format(
            "[%s] STUTTER: %.1f ms (Avg: %.1f ms | FPS: %.0f) -> ROOT CAUSE: [%s] | Pos: (%.0f, %.0f | Ch: %d,%d) | Driving: %b | Zombies: %d (+%d) | Corpses: %d | WSQueue: %d | SaveQueue: %d | GC: %dms | Main thread: %s",
            timestamp, frameTimeMs, avgFrameTime, (1000.0 / Math.max(1.0, frameTimeMs)), cause, playerX, playerY, chunkX, chunkY, isDriving, activeZombies, zombieSpawnsThisFrame, activeCorpses, wsQueueSize, saveQueueSize, gcDeltaTimeMs,
            haveProfile ? String.format("%.1fms profiled: %s", mainMs, PhaseProfiler.describeLastFrame()) : "n/a"
        );

        pendingDiagnosticLogs.offer(logEntry);
    }

    private static final List<GarbageCollectorMXBean> GC_BEANS = ManagementFactory.getGarbageCollectorMXBeans();

    private static void updateGcStats() {
        try {
            long count = 0;
            long timeMs = 0;
            for (int i = 0; i < GC_BEANS.size(); i++) {
                GarbageCollectorMXBean gc = GC_BEANS.get(i);
                String name = gc.getName();
                if (name != null && name.contains("Cycles")) {
                    continue;
                }
                long c = gc.getCollectionCount();
                if (c > 0) count += c;
                long t = gc.getCollectionTime();
                if (t > 0) timeMs += t;
            }
            lastGcCount = count;
            lastGcTimeMs = timeMs;
        } catch (Throwable ignored) {}
    }

    private static double getAverageFrameTime() {
        int count = Math.min(totalFramesSampled, frameTimeHistory.length);
        if (count == 0) return 16.66;
        double sum = 0;
        for (int i = 0; i < count; i++) {
            sum += frameTimeHistory[i];
        }
        return sum / count;
    }

    private static double getPercentileFrameTime(double percentile) {
        int count = Math.min(totalFramesSampled, frameTimeHistory.length);
        if (count == 0) return 16.66;
        double[] sorted = new double[count];
        System.arraycopy(frameTimeHistory, 0, sorted, 0, count);
        java.util.Arrays.sort(sorted);
        int index = (int) Math.ceil(percentile * count) - 1;
        return sorted[Math.max(0, Math.min(count - 1, index))];
    }

    private static void updateLiveTelemetry(double currentFrameMs, double avgFrameMs) {
        try {
            double fps = 1000.0 / Math.max(0.1, currentFrameMs);
            double avgFps = 1000.0 / Math.max(0.1, avgFrameMs);
            double low1PercentMs = getPercentileFrameTime(0.99);
            double low01PercentMs = getPercentileFrameTime(0.999);
            double low1PercentFps = 1000.0 / Math.max(0.1, low1PercentMs);
            int ramGb = PZOEngineBridge.getOptimizedRAM();

            latestStatusJson = String.format(
                "{\"optimized\": true, \"ram_gb\": %d, \"channel\": \"Stable\", \"fps\": %.1f, \"avg_fps\": %.1f, \"frame_time_ms\": %.2f, \"low_1_pct_fps\": %.1f, \"low_1_pct_ms\": %.2f, \"low_01_pct_ms\": %.2f, \"stutter_count\": %d, \"last_stutter_ms\": %.1f, \"last_stutter_cause\": \"%s\"}",
                ramGb, fps, avgFps, currentFrameMs, low1PercentFps, low1PercentMs, low01PercentMs, stutterCount, lastStutterMs, lastStutterCause.replace("\"", "\\\"")
            );
        } catch (Throwable ignored) {}
    }

    private static void flushDiagnosticsToDisk() {
        if (pendingDiagnosticLogs.isEmpty()) return;

        List<File> targetDirs = new ArrayList<>();
        // Check discovered Zomboid directories
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            targetDirs.add(new File(userHome, "Zomboid" + File.separator + "Logs"));
            targetDirs.add(new File(userHome, "Zomboid"));
        }
        targetDirs.add(new File("Logs"));
        targetDirs.add(new File("."));

        List<String> logsToWrite = new ArrayList<>();
        String entry;
        while ((entry = pendingDiagnosticLogs.poll()) != null) {
            logsToWrite.add(entry);
        }

        if (logsToWrite.isEmpty()) return;

        for (File dir : targetDirs) {
            if (dir.exists() && dir.isDirectory()) {
                File logFile = new File(dir, "pzo_stutter_diagnostics.log");
                try (FileWriter fw = new FileWriter(logFile, true)) {
                    for (String line : logsToWrite) {
                        fw.write(line + System.lineSeparator());
                    }
                    fw.flush();
                } catch (Throwable ignored) {}
                break; // Written to primary log directory
            }
        }
    }
}
