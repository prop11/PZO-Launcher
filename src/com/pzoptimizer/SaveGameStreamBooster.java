package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Project Zomboid Build 42 - Zero-Stutter Chunk & Savegame I/O Accelerator.
 * Enhances background disk streaming for world save files (map_*.bin, zpop_*.bin)
 * and tunes SQLite databases (players.db, vehicles.db) with Write-Ahead Logging (WAL)
 * and 256MB memory-mapped I/O (mmap_size).
 */
public class SaveGameStreamBooster {
    private static final int OPTIMAL_BUFFER_SIZE = 131072; // 128 KB high-throughput buffer
    private static volatile boolean dbTuned = false;

    public static int getOptimalBufferSize() {
        return OPTIMAL_BUFFER_SIZE;
    }

    public static void tuneSaveEngine() {
        try {
            // Tune standard I/O buffer properties
            System.setProperty("zomboid.io.buffersize", String.valueOf(OPTIMAL_BUFFER_SIZE));
            PopTemplateGuard.ensurePopulated();
        } catch (Throwable ignored) {}

        // Launch background daemon to apply SQLite WAL mode and memory-mapping when database connections open
        startDbTuningDaemon();
    }

    private static void startDbTuningDaemon() {
        Thread daemon = new Thread(() -> {
            for (int i = 0; i < 180; i++) { // Check for up to 3 minutes during startup and save loading
                try {
                    boolean allTuned = tuneSqliteDatabases();
                    if (allTuned && i > 30) {
                        break;
                    }
                } catch (Throwable ignored) {}

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "PZO-SaveDbTuner");
        daemon.setDaemon(true);
        daemon.setPriority(Thread.MIN_PRIORITY);
        daemon.start();
    }

    public static synchronized boolean tuneSqliteDatabases() {
        boolean tunedAny = false;

        // 1. Tune VehiclesDB2
        try {
            Class<?> vdbClass = Class.forName("zombie.vehicles.VehiclesDB2");
            Field instField = vdbClass.getField("instance");
            Object vdbInst = instField.get(null);
            if (vdbInst != null) {
                Field wsField = vdbClass.getDeclaredField("worldStreamer");
                wsField.setAccessible(true);
                Object ws = wsField.get(vdbInst);
                if (ws != null) {
                    Field storeField = ws.getClass().getDeclaredField("store");
                    storeField.setAccessible(true);
                    Object store = storeField.get(ws);
                    if (store != null && store.getClass().getName().contains("SQLStore")) {
                        Field connField = store.getClass().getDeclaredField("conn");
                        connField.setAccessible(true);
                        Connection conn = (Connection) connField.get(store);
                        if (conn != null && !conn.isClosed()) {
                            applySqlitePragmas(conn, "VehiclesDB2");
                            tunedAny = true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 2. Tune PlayerDB
        try {
            Class<?> pdbClass = Class.forName("zombie.savefile.PlayerDB");
            Method getInst = pdbClass.getMethod("getInstance");
            Object pdbInst = getInst.invoke(null);
            if (pdbInst != null) {
                Field storeField = pdbClass.getDeclaredField("store");
                storeField.setAccessible(true);
                Object store = storeField.get(pdbInst);
                if (store != null && store.getClass().getName().contains("SQLPlayerStore")) {
                    Field connField = store.getClass().getDeclaredField("conn");
                    connField.setAccessible(true);
                    Connection conn = (Connection) connField.get(store);
                    if (conn != null && !conn.isClosed()) {
                        applySqlitePragmas(conn, "PlayerDB");
                        tunedAny = true;
                    }
                }
            }
        } catch (Throwable ignored) {}

        return tunedAny;
    }

    private static void applySqlitePragmas(Connection conn, String dbName) {
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode = WAL;");
            s.execute("PRAGMA synchronous = NORMAL;");
            s.execute("PRAGMA mmap_size = 268435456;"); // 256MB memory-mapped disk I/O
            s.execute("PRAGMA cache_size = -64000;");   // 64MB memory page cache
            s.execute("PRAGMA busy_timeout = 5000;");
            PZOLogger.success("[SaveGameStreamBooster] " + dbName + " optimized: WAL mode, 256MB mmap, 64MB cache");
        } catch (Throwable t) {
            PZOLogger.info("[SaveGameStreamBooster] Notice tuning " + dbName + ": " + t.getMessage());
        }
    }
}
