package com.pzoptimizer;

import java.io.File;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/**
 * Detects physical cores and hybrid layouts (Intel P/E cores, AMD Zen 5 + Zen 5c) via Linux sysfs or the
 * native library, so frame-synchronous worker pools are not spread onto efficiency cores or SMT siblings,
 * which only adds barrier tail latency.
 */
public final class CpuTopologyGovernor {

    public static final int PHYSICAL_CORES;
    public static final int PERFORMANCE_CORES;
    public static final boolean IS_HYBRID_CPU;

    static {
        int phys = 0;
        int perfCores = 0;
        boolean hybrid = false;
        try {
            File cpuRoot = new File("/sys/devices/system/cpu");
            File[] cpus = cpuRoot.listFiles((d, name) -> name.matches("cpu\\d+"));
            if (cpus != null && cpus.length > 0) {
                Set<Long> uniqueCores = new HashSet<>();
                long[] perfPerCpu = new long[cpus.length];
                long minPerf = Long.MAX_VALUE;
                long maxPerf = Long.MIN_VALUE;
                boolean complete = true;

                for (int idx = 0; idx < cpus.length; idx++) {
                    File c = cpus[idx];
                    long coreId = readSysfsLong(c, "topology/core_id");
                    long pkgId = Math.max(0L, readSysfsLong(c, "topology/physical_package_id"));
                    if (coreId >= 0) {
                        uniqueCores.add((pkgId << 32) | (coreId & 0xFFFFFFFFL));
                    } else {
                        uniqueCores.add((long) idx);
                    }

                    long perf = readSysfsLong(c, "cpufreq/amd_pstate_highest_perf");
                    if (perf < 0) perf = readSysfsLong(c, "acpi_cppc/highest_perf");
                    if (perf < 0) perf = readSysfsLong(c, "cpu_capacity");
                    if (perf < 0) perf = readSysfsLong(c, "cpufreq/cpuinfo_max_freq");
                    perfPerCpu[idx] = perf;
                    if (perf > 0) {
                        minPerf = Math.min(minPerf, perf);
                        maxPerf = Math.max(maxPerf, perf);
                    }
                }
                if (complete && !uniqueCores.isEmpty()) {
                    phys = uniqueCores.size();
                    hybrid = new File("/sys/devices/cpu_atom/cpus").isFile()
                          || (minPerf != Long.MAX_VALUE && maxPerf >= (long) (minPerf * 1.15));
                    if (hybrid && minPerf != Long.MAX_VALUE) {
                        int bigCount = 0;
                        for (long p : perfPerCpu) {
                            if (p > minPerf) bigCount++;
                        }
                        if (bigCount > 0) perfCores = bigCount;
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (phys <= 0) {
            int avail = Runtime.getRuntime().availableProcessors();
            int nativePCores = PZONative.isLoaded() ? PZONative.getPerformanceCores() : avail;
            phys = Math.max(2, nativePCores);
            perfCores = phys;
            hybrid = (nativePCores > 0 && nativePCores < avail);
        }
        if (perfCores <= 0) {
            int nativePCores = PZONative.isLoaded() ? PZONative.getPerformanceCores() : 0;
            perfCores = (nativePCores > 0 && nativePCores < phys) ? nativePCores : phys;
        }

        PHYSICAL_CORES = phys;
        PERFORMANCE_CORES = perfCores;
        IS_HYBRID_CPU = hybrid;
    }

    private CpuTopologyGovernor() {}

    /**
     * Worker count for frame-synchronous parallel batches. On mobile ARM64 big.LITTLE SoCs, this is capped
     * at min(4, PERFORMANCE_CORES) to avoid Cortex-A5xx LITTLE-core barrier stragglers and thermal throttling.
     * On desktop hybrid CPUs with more than 9 physical cores it caps at min(11, physicalCores - 1).
     */
    public static int getOptimalWorkerCount() {
        // Leave two cores for the game's main and render threads
        if (PZONative.isLowCoreCpu()) {
            return Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
        }
        if (EngineGLStateGovernor.isMobileGLEnvironment()) {
            int pCores = PZONative.isLoaded() ? PZONative.getPerformanceCores() : PERFORMANCE_CORES;
            if (IS_HYBRID_CPU && PERFORMANCE_CORES > 0 && PERFORMANCE_CORES < pCores) {
                pCores = PERFORMANCE_CORES;
            }
            return Math.max(2, Math.min(pCores, 4));
        }
        if (IS_HYBRID_CPU && PHYSICAL_CORES > 9) {
            return Math.min(11, PHYSICAL_CORES - 1);
        }
        int base = PZONative.isLoaded() ? PZONative.getPerformanceCores() : PERFORMANCE_CORES;
        return Math.max(2, Math.min(base, 12));
    }

    private static long readSysfsLong(File dir, String relPath) {
        try {
            File f = new File(dir, relPath);
            if (!f.isFile()) return -1L;
            return Long.parseLong(Files.readString(f.toPath()).trim());
        } catch (Throwable e) {
            return -1L;
        }
    }
}
