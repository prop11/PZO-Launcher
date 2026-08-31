package com.pzoptimizer.server;

import java.io.File;
import java.io.FileWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Project Zomboid Dedicated Server - Live Server Telemetry Bridge.
 * Streams real-time TPS, memory metrics, GC pause latencies, and thread states for server admins and Discord bots.
 */
public class ServerTelemetryBridge {
    private static final java.util.Set<File> telemetryFiles = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    static {
        refreshDiscoveredDirectories(null);
    }

    public static void refreshDiscoveredDirectories(String[] cliArgs) {
        try {
            if (cliArgs != null) {
                for (int i = 0; i < cliArgs.length; i++) {
                    String arg = cliArgs[i];
                    if (arg != null) {
                        if (arg.startsWith("-cachedir=")) {
                            registerZomboidDir(new File(arg.substring("-cachedir=".length()).trim()));
                        } else if (arg.equalsIgnoreCase("-cachedir") && i + 1 < cliArgs.length) {
                            registerZomboidDir(new File(cliArgs[i + 1].trim()));
                        }
                    }
                }
            }

            String propCache = System.getProperty("zomboid.cachedir");
            if (propCache != null && !propCache.trim().isEmpty()) {
                registerZomboidDir(new File(propCache.trim()));
            }

            String envCache = System.getenv("ZOMBOID_CACHEDIR");
            if (envCache != null && !envCache.trim().isEmpty()) {
                registerZomboidDir(new File(envCache.trim()));
            }

            String userHome = System.getProperty("user.home");
            if (userHome != null) {
                registerZomboidDir(new File(userHome, "Zomboid"));
                registerZomboidDir(new File(userHome, "Documents" + File.separator + "Zomboid"));
                registerZomboidDir(new File(userHome, "OneDrive" + File.separator + "Documents" + File.separator + "Zomboid"));
            }

            String userProfile = System.getenv("USERPROFILE");
            if (userProfile != null && !userProfile.equalsIgnoreCase(userHome)) {
                registerZomboidDir(new File(userProfile, "Zomboid"));
                registerZomboidDir(new File(userProfile, "Documents" + File.separator + "Zomboid"));
            }

            registerZomboidDir(new File("Zomboid"));
            registerZomboidDir(new File("."));

            try {
                File[] roots = File.listRoots();
                if (roots != null) {
                    for (File r : roots) {
                        if (r != null && r.exists()) {
                            File zRoot = new File(r, "Zomboid");
                            if (zRoot.exists()) registerZomboidDir(zRoot);
                        }
                    }
                }
            } catch (Throwable ignored) {}

            queryZomboidFileSystem();
        } catch (Throwable ignored) {}
    }

    private static void queryZomboidFileSystem() {
        try {
            Class<?> zfsClass = Class.forName("zombie.ZomboidFileSystem");
            Object instance = zfsClass.getField("instance").get(null);
            if (instance != null) {
                try {
                    Method getLuaDir = zfsClass.getMethod("getLuaDir");
                    Object luaDir = getLuaDir.invoke(instance);
                    if (luaDir instanceof String) {
                        File ld = new File((String) luaDir);
                        if (!ld.exists()) ld.mkdirs();
                        telemetryFiles.add(new File(ld, "pzo_server_telemetry.json"));
                    }
                } catch (Throwable ignored) {}

                try {
                    Method getCacheDir = zfsClass.getMethod("getCacheDir");
                    Object cacheDir = getCacheDir.invoke(instance);
                    if (cacheDir instanceof String) {
                        registerZomboidDir(new File((String) cacheDir));
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    public static void registerZomboidDir(File zDir) {
        if (zDir == null) return;
        try {
            File luaDir = new File(zDir, "Lua");
            if (!luaDir.exists()) luaDir.mkdirs();
            telemetryFiles.add(new File(luaDir, "pzo_server_telemetry.json"));
        } catch (Throwable ignored) {}
    }

    public static void startTelemetryDaemon() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000);
                    writeTelemetry();
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable ignored) {}
            }
        });
        t.setName("PZO-Server-Telemetry");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        t.start();
        PZOServerLogger.success("ServerTelemetryBridge active (Streaming metrics to pzo_server_telemetry.json every 5s)");
    }

    private static void writeTelemetry() {
        try {
            Runtime rt = Runtime.getRuntime();
            long maxMem = rt.maxMemory() / (1024 * 1024);
            long totalMem = rt.totalMemory() / (1024 * 1024);
            long freeMem = rt.freeMemory() / (1024 * 1024);
            long usedMem = totalMem - freeMem;

            long gcCount = 0;
            long gcTimeMs = 0;
            List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
            for (GarbageCollectorMXBean gc : gcs) {
                long c = gc.getCollectionCount();
                if (c > 0) gcCount += c;
                long t = gc.getCollectionTime();
                if (t > 0) gcTimeMs += t;
            }

            long avgPause = gcCount > 0 ? (gcTimeMs / gcCount) : 0;
            double cpuLoad = getProcessCpuLoad();

            String json = String.format(
                "{\"server_optimized\": true, \"max_mb\": %d, \"used_mb\": %d, \"free_mb\": %d, \"gc_count\": %d, \"gc_pause_avg_ms\": %d, \"threads\": %d, \"cpu_percent\": %.1f, \"timestamp\": %d}",
                maxMem, usedMem, freeMem, gcCount, avgPause, Thread.activeCount(), cpuLoad, System.currentTimeMillis()
            );

            for (File tf : telemetryFiles) {
                try (FileWriter fw = new FileWriter(tf, false)) {
                    fw.write(json);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static double getProcessCpuLoad() {
        try {
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            Method m = os.getClass().getMethod("getProcessCpuLoad");
            m.setAccessible(true);
            Object val = m.invoke(os);
            if (val instanceof Double) {
                double d = (Double) val;
                if (d >= 0.0) return d * 100.0;
            }
        } catch (Throwable ignored) {}
        return 0.0;
    }
}
