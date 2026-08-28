package com.pzoptimizer;

import java.io.File;
import java.io.FileWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.util.List;

public class TelemetryReporter {
    private static File telemetryFile = null;

    static {
        try {
            String userHome = System.getProperty("user.home");
            File luaDir = new File(userHome, "Zomboid" + File.separator + "Lua");
            if (!luaDir.exists()) {
                luaDir.mkdirs();
            }
            telemetryFile = new File(luaDir, "pzo_telemetry.json");
        } catch (Exception ignored) {}
    }

    public static void updateTelemetry() {
        if (telemetryFile == null) return;
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
                "{\"max_mb\": %d, \"used_mb\": %d, \"free_mb\": %d, \"gc_count\": %d, \"gc_pause_avg_ms\": %d, \"threads\": %d, \"cpu_percent\": %.1f, \"intern_pool\": %d}",
                maxMem, usedMem, freeMem, gcCount, avgPause, Thread.activeCount(), cpuLoad, ResourceInterner.getPoolSize()
            );

            FileWriter fw = new FileWriter(telemetryFile, false);
            fw.write(json);
            fw.close();
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
