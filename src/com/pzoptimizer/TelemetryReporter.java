package com.pzoptimizer;

import java.io.File;
import java.io.FileWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class TelemetryReporter {
    private static final Set<File> discoveredLuaDirs = Collections.synchronizedSet(new LinkedHashSet<>());

    static {
        refreshDiscoveredDirectories(null);
    }

    public static void refreshDiscoveredDirectories(String[] cliArgs) {
        try {
            // 1. Check CLI args for -cachedir
            if (cliArgs != null) {
                for (int i = 0; i < cliArgs.length; i++) {
                    String arg = cliArgs[i];
                    if (arg != null) {
                        if (arg.startsWith("-cachedir=")) {
                            String cDir = arg.substring("-cachedir=".length()).trim();
                            registerZomboidDir(new File(cDir));
                        } else if (arg.equalsIgnoreCase("-cachedir") && i + 1 < cliArgs.length) {
                            registerZomboidDir(new File(cliArgs[i + 1].trim()));
                        }
                    }
                }
            }

            // 2. System properties and environment variables
            String propCache = System.getProperty("zomboid.cachedir");
            if (propCache != null && !propCache.trim().isEmpty()) {
                registerZomboidDir(new File(propCache.trim()));
            }

            String envCache = System.getenv("ZOMBOID_CACHEDIR");
            if (envCache != null && !envCache.trim().isEmpty()) {
                registerZomboidDir(new File(envCache.trim()));
            }

            // 3. User Home, UserProfile, Documents & OneDrive
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
                registerZomboidDir(new File(userProfile, "OneDrive" + File.separator + "Documents" + File.separator + "Zomboid"));
            }

            // 4. Working directory
            registerZomboidDir(new File("Zomboid"));
            registerZomboidDir(new File("."));

            // 5. Scan all mounted drive roots (D:\, E:\, F:\, etc.)
            try {
                File[] roots = File.listRoots();
                if (roots != null) {
                    for (File r : roots) {
                        if (r != null && r.exists()) {
                            File zRoot = new File(r, "Zomboid");
                            if (zRoot.exists()) registerZomboidDir(zRoot);
                            File gamesZ = new File(r, "Games" + File.separator + "Zomboid");
                            if (gamesZ.exists()) registerZomboidDir(gamesZ);
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // 6. Dynamic Reflection into zombie.ZomboidFileSystem
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
                        discoveredLuaDirs.add(ld);
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
            if (!luaDir.exists()) {
                luaDir.mkdirs();
            }
            discoveredLuaDirs.add(luaDir);
        } catch (Throwable ignored) {}
    }

    public static void writeStatusFile(String json) {
        refreshDiscoveredDirectories(null);
        for (File luaDir : new ArrayList<>(discoveredLuaDirs)) {
            try {
                if (!luaDir.exists()) luaDir.mkdirs();
                File statusFile = new File(luaDir, "pzo_status.json");
                try (FileWriter fw = new FileWriter(statusFile, false)) {
                    fw.write(json);
                }
            } catch (Throwable ignored) {}
        }
    }

    public static void updateTelemetry() {
        queryZomboidFileSystem();
        if (discoveredLuaDirs.isEmpty()) return;

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
                "{\"max_mb\": %d, \"used_mb\": %d, \"free_mb\": %d, \"gc_count\": %d, \"gc_pause_avg_ms\": %d, \"threads\": %d, \"cpu_percent\": %.1f, \"intern_pool\": %d, \"gl_skipped\": %d, \"matrix_skipped\": %d, \"uniforms_skipped\": %d}",
                maxMem, usedMem, freeMem, gcCount, avgPause, Thread.activeCount(), cpuLoad, ResourceInterner.getPoolSize(),
                GLStateOptimizer.getGlCallsFiltered(), GLStateOptimizer.getMatricesSkipped(), GLStateOptimizer.getUniformsSkipped()
            );

            for (File luaDir : new ArrayList<>(discoveredLuaDirs)) {
                try {
                    File tf = new File(luaDir, "pzo_telemetry.json");
                    try (FileWriter fw = new FileWriter(tf, false)) {
                        fw.write(json);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Exception ignored) {}
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
