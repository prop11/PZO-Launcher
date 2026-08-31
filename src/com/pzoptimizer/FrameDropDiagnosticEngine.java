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
 * and snapshots multi-subsystem engine metrics to identify the exact root cause.
 * 100% thread-safe, low-overhead, and cross-platform.
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
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static final ConcurrentLinkedQueue<String> pendingDiagnosticLogs = new ConcurrentLinkedQueue<>();
    private static long lastDiskFlushTime = 0;

    public static void initialize() {
        if (running) return;
        running = true;

        // Initialize baseline GC stats
        updateGcStats();

        // Background file logger daemon
        Thread loggerThread = new Thread(() -> {
            PZOLogger.success("FrameDropDiagnosticEngine: Active (Real-Time Stutter Diagnostics & Root Cause Telemetry)");

            while (running) {
                try {
                    Thread.sleep(1000); // Flush logs every second
                    flushDiagnosticsToDisk();
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

    /**
     * Called on each rendered frame to compute frame time and detect stutter anomalies.
     */
    public static void onFrameTick() {
        long now = System.nanoTime();
        long deltaNanos = now - lastFrameTimeNanos;
        lastFrameTimeNanos = now;

        if (deltaNanos <= 0 || deltaNanos > 2_000_000_000L) {
            // First frame or pause menu resume anomaly
            return;
        }

        double frameTimeMs = deltaNanos / 1_000_000.0;

        // Store rolling history
        frameTimeHistory[historyIndex] = frameTimeMs;
        historyIndex = (historyIndex + 1) % frameTimeHistory.length;
        totalFramesSampled++;

        // Stutter detection threshold: >28ms (<35 FPS) or >1.8x average frame time
        double avgFrameTime = getAverageFrameTime();
        if (frameTimeMs > 28.0 && frameTimeMs > avgFrameTime * 1.65) {
            diagnoseFrameDrop(frameTimeMs, avgFrameTime);
        }

        // Periodically update live telemetry file (every 60 frames)
        if (totalFramesSampled % 60 == 0) {
            updateLiveTelemetry(frameTimeMs, avgFrameTime);
        }
    }

    private static void diagnoseFrameDrop(double frameTimeMs, double avgFrameTime) {
        stutterCount++;
        lastStutterMs = frameTimeMs;

        // 1. Check GC Pause Delta
        long gcCountBefore = lastGcCount;
        long gcTimeBefore = lastGcTimeMs;
        updateGcStats();
        long gcDeltaCount = lastGcCount - gcCountBefore;
        long gcDeltaTimeMs = lastGcTimeMs - gcTimeBefore;

        // 2. Query Game State via reflection (100% crash-proof)
        boolean isDriving = false;
        float vehicleSpeed = 0.0f;
        float playerX = 0.0f, playerY = 0.0f;
        int chunkX = 0, chunkY = 0;
        int activeZombies = 0;
        int activeCorpses = 0;
        int wsQueueSize = 0;
        int saveQueueSize = 0;

        try {
            Class<?> playerClass = Class.forName("zombie.characters.IsoPlayer");
            Method getInstMethod = playerClass.getMethod("getInstance");
            Object player = getInstMethod.invoke(null);

            if (player != null) {
                Method getX = playerClass.getMethod("getX");
                Method getY = playerClass.getMethod("getY");
                playerX = ((Number) getX.invoke(player)).floatValue();
                playerY = ((Number) getY.invoke(player)).floatValue();
                chunkX = (int) (playerX / 8.0f);
                chunkY = (int) (playerY / 8.0f);

                Method getVehicle = playerClass.getMethod("getVehicle");
                Object vehicle = getVehicle.invoke(player);
                if (vehicle != null) {
                    isDriving = true;
                    Method getSpeed = vehicle.getClass().getMethod("getCurrentSpeedKmHour");
                    vehicleSpeed = ((Number) getSpeed.invoke(vehicle)).floatValue();
                }
            }

            // Query Cell entities
            Class<?> worldClass = Class.forName("zombie.iso.IsoWorld");
            Field instField = worldClass.getField("instance");
            Object worldInst = instField.get(null);
            if (worldInst != null) {
                Field cellField = worldClass.getField("currentCell");
                Object cell = cellField.get(worldInst);
                if (cell != null) {
                    Field zListField = cell.getClass().getField("ZombieList");
                    List<?> zList = (List<?>) zListField.get(cell);
                    if (zList != null) activeZombies = zList.size();
                }
            }

            // Query WorldStreamer queue
            Class<?> wsClass = Class.forName("zombie.iso.WorldStreamer");
            Object wsInst = wsClass.getField("instance").get(null);
            if (wsInst != null) {
                Field reqQueueField = wsClass.getDeclaredField("mainThreadRequestQueue");
                reqQueueField.setAccessible(true);
                Queue<?> q = (Queue<?>) reqQueueField.get(wsInst);
                if (q != null) wsQueueSize = q.size();
            }

            // Query ChunkSaveWorker queue
            Class<?> cswClass = Class.forName("zombie.iso.ChunkSaveWorker");
            Object cswInst = cswClass.getField("instance").get(null);
            if (cswInst != null) {
                Field saveQField = cswClass.getField("toSaveQueue");
                Queue<?> sq = (Queue<?>) saveQField.get(cswInst);
                if (sq != null) saveQueueSize = sq.size();
            }

        } catch (Throwable ignored) {}

        // 3. Classify Root Cause
        String cause;
        if (gcDeltaTimeMs > 10 || gcDeltaCount > 0) {
            cause = "GC_STW_PAUSE (" + gcDeltaTimeMs + "ms)";
        } else if (isDriving && (Math.abs(vehicleSpeed) > 15.0f || wsQueueSize > 2)) {
            cause = "VEHICLE_CHUNK_STREAMING (Speed: " + String.format("%.1f", vehicleSpeed) + " km/h, WS Queue: " + wsQueueSize + ")";
        } else if (saveQueueSize > 5) {
            cause = "DISK_AUTOSAVE_SPIKE (SaveQueue: " + saveQueueSize + " chunks)";
        } else if (activeZombies > 100) {
            cause = "HORDE_PHYSICS_DENSITY (" + activeZombies + " zombies)";
        } else {
            cause = "RENDER_OR_LOCK_CONTENTION";
        }

        lastStutterCause = cause;

        // 4. Log Stutter Report
        String timestamp = DATE_FORMAT.format(new Date());
        String logEntry = String.format(
            "[%s] STUTTER: %.1f ms (Avg: %.1f ms | FPS: %.0f) -> ROOT CAUSE: [%s] | Pos: (%.0f, %.0f | Ch: %d,%d) | Driving: %b | Zombies: %d | WSQueue: %d | SaveQueue: %d | GC: %dms",
            timestamp, frameTimeMs, avgFrameTime, (1000.0 / Math.max(1.0, frameTimeMs)), cause, playerX, playerY, chunkX, chunkY, isDriving, activeZombies, wsQueueSize, saveQueueSize, gcDeltaTimeMs
        );

        pendingDiagnosticLogs.offer(logEntry);
        PZOLogger.warn(logEntry);
    }

    private static void updateGcStats() {
        try {
            long count = 0;
            long timeMs = 0;
            List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
            for (GarbageCollectorMXBean gc : gcs) {
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

            String json = String.format(
                "{\"fps\": %.1f, \"avg_fps\": %.1f, \"frame_time_ms\": %.2f, \"low_1_pct_fps\": %.1f, \"low_1_pct_ms\": %.2f, \"low_01_pct_ms\": %.2f, \"stutter_count\": %d, \"last_stutter_ms\": %.1f, \"last_stutter_cause\": \"%s\"}",
                fps, avgFps, currentFrameMs, low1PercentFps, low1PercentMs, low01PercentMs, stutterCount, lastStutterMs, lastStutterCause.replace("\"", "\\\"")
            );

            TelemetryReporter.writeStatusFile(json);
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
