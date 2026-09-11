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
 * PZO Real-Time Frame-Time Profiler & Stutter Diagnostic Engine.
 * Measures nanosecond frame times, detects frame drops / micro-stutters,
 */
public final class FrameDropDiagnosticEngine {

    private static volatile boolean running = false;
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

    public static void initialize() {
        if (running) return;
        running = true;

        updateGcStats();

        Thread loggerThread = new Thread(() -> {
            PZOLogger.success("FrameDropDiagnosticEngine: Active (Real-Time Stutter Diagnostics & Root Cause Telemetry)");

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

    /**
     * Called on each rendered frame to compute frame time and detect stutter anomalies.
     */
    public static void onFrameTick() {
        long now = System.nanoTime();
        long deltaNanos = now - lastFrameTimeNanos;
        lastFrameTimeNanos = now;

        if (deltaNanos <= 0 || deltaNanos > 2_000_000_000L) {
            return;
        }

        double frameTimeMs = deltaNanos / 1_000_000.0;

        frameTimeHistory[historyIndex] = frameTimeMs;
        historyIndex = (historyIndex + 1) % frameTimeHistory.length;
        totalFramesSampled++;

        Object player = getActivePlayer();
        if (player == null) {
            return;
        }

        double avgFrameTime = getAverageFrameTime();
        if (frameTimeMs > 28.0 && frameTimeMs > avgFrameTime * 1.65) {
            diagnoseFrameDrop(player, frameTimeMs, avgFrameTime);
        }

        if (totalFramesSampled % 60 == 0) {
            updateLiveTelemetry(frameTimeMs, avgFrameTime);
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

        long gcCountBefore = lastGcCount;
        long gcTimeBefore = lastGcTimeMs;
        updateGcStats();
        long gcDeltaCount = lastGcCount - gcCountBefore;
        long gcDeltaTimeMs = lastGcTimeMs - gcTimeBefore;

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
                chunkX = (int) (playerX / 8.0f);
                chunkY = (int) (playerY / 8.0f);
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
                    if (q != null) wsQueueSize = q.size();
                }
            }

            if (cswInstanceField != null && cswToSaveQField != null) {
                Object cswInst = cswInstanceField.get(null);
                if (cswInst != null) {
                    Queue<?> sq = (Queue<?>) cswToSaveQField.get(cswInst);
                    if (sq != null) saveQueueSize = sq.size();
                }
            }

            if (lgsField != null) {
                Object lgsObj = lgsField.get(null);
                if (lgsObj instanceof java.util.Collection) {
                    ingestionQueueSize = ((java.util.Collection<?>) lgsObj).size();
                }
            }
        } catch (Throwable ignored) {}

        boolean chunkCrossing = (lastDiagnosedChunkX != Integer.MIN_VALUE) && (chunkX != lastDiagnosedChunkX || chunkY != lastDiagnosedChunkY);
        lastDiagnosedChunkX = chunkX;
        lastDiagnosedChunkY = chunkY;

        String cause;
        if (gcDeltaTimeMs > 5) {
            cause = "GC_STW_PAUSE (" + gcDeltaTimeMs + "ms)";
        } else if (chunkCrossing) {
            if (isDriving) {
                cause = "VEHICLE_CHUNK_CROSSING (Chunk: " + chunkX + "," + chunkY + " | Speed: " + String.format("%.1f", vehicleSpeed) + " km/h)";
            } else {
                cause = "FOOT_CHUNK_CROSSING (Chunk: " + chunkX + "," + chunkY + ")";
            }
        } else if (ingestionQueueSize > 0) {
            cause = "CHUNK_INGESTION_BACKLOG (" + ingestionQueueSize + " chunks remaining)";
        } else if (isDriving && (Math.abs(vehicleSpeed) > 15.0f || wsQueueSize > 2)) {
            cause = "VEHICLE_CHUNK_STREAMING (Speed: " + String.format("%.1f", vehicleSpeed) + " km/h, WS Queue: " + wsQueueSize + ")";
        } else if (saveQueueSize > 5) {
            cause = "DISK_AUTOSAVE_SPIKE (SaveQueue: " + saveQueueSize + " chunks)";
        } else if (activeCorpses > 50) {
            cause = "CORPSE_DENSITY_BURDEN (" + activeCorpses + " corpses)";
        } else if (activeZombies > 100) {
            cause = "HORDE_PHYSICS_DENSITY (" + activeZombies + " zombies)";
        } else {
            cause = "RENDER_OR_LOCK_CONTENTION";
        }

        lastStutterCause = cause;

        String timestamp = DATE_FORMAT.format(new Date());
        String logEntry = String.format(
            "[%s] STUTTER: %.1f ms (Avg: %.1f ms | FPS: %.0f) -> ROOT CAUSE: [%s] | Pos: (%.0f, %.0f | Ch: %d,%d) | Driving: %b | Zombies: %d | Corpses: %d | WSQueue: %d | SaveQueue: %d | GC: %dms",
            timestamp, frameTimeMs, avgFrameTime, (1000.0 / Math.max(1.0, frameTimeMs)), cause, playerX, playerY, chunkX, chunkY, isDriving, activeZombies, activeCorpses, wsQueueSize, saveQueueSize, gcDeltaTimeMs
        );

        pendingDiagnosticLogs.offer(logEntry);
    }

    private static void updateGcStats() {
        try {
            long count = 0;
            long timeMs = 0;
            List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
            for (GarbageCollectorMXBean gc : gcs) {
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
