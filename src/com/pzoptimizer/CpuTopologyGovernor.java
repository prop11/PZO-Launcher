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
    public static final boolean IS_HYBRID_CPU;

    static {
        int phys = 0;
        boolean hybrid = false;
        try {
            File cpuRoot = new File("/sys/devices/system/cpu");
            File[] cpus = cpuRoot.listFiles((d, name) -> name.matches("cpu\\d+"));
            if (cpus != null && cpus.length > 0) {
                Set<Long> uniqueCores = new HashSet<>();
                long minPerf = Long.MAX_VALUE;
                long maxPerf = Long.MIN_VALUE;
                boolean complete = true;

                for (File c : cpus) {
                    long coreId = readSysfsLong(c, "topology/core_id");
                    long pkgId = Math.max(0L, readSysfsLong(c, "topology/physical_package_id"));
                    if (coreId < 0) {
                        complete = false;
                        break;
                    }
                    uniqueCores.add((pkgId << 32) | (coreId & 0xFFFFFFFFL));

                    long perf = readSysfsLong(c, "cpufreq/amd_pstate_highest_perf");
                    if (perf < 0) perf = readSysfsLong(c, "acpi_cppc/highest_perf");
                    if (perf < 0) perf = readSysfsLong(c, "cpu_capacity");
                    if (perf < 0) perf = readSysfsLong(c, "cpufreq/cpuinfo_max_freq");
                    if (perf > 0) {
                        minPerf = Math.min(minPerf, perf);
                        maxPerf = Math.max(maxPerf, perf);
                    }
                }
                if (complete && !uniqueCores.isEmpty()) {
                    phys = uniqueCores.size();
                    hybrid = new File("/sys/devices/cpu_atom/cpus").isFile()
                          || (minPerf != Long.MAX_VALUE && maxPerf >= (long) (minPerf * 1.15));
                }
            }
        } catch (Throwable ignored) {}

        if (phys <= 0) {
            int avail = Runtime.getRuntime().availableProcessors();
            int nativePCores = PZONative.isLoaded() ? PZONative.getPerformanceCores() : avail;
            phys = Math.max(2, nativePCores);
            hybrid = (nativePCores > 0 && nativePCores < avail);
        }

        PHYSICAL_CORES = phys;
        IS_HYBRID_CPU = hybrid;
    }

    private CpuTopologyGovernor() {}

    /**
     * Worker count for frame-synchronous parallel batches. On hybrid CPUs with more than 9 physical cores
     * (Ryzen AI 9 HX 370, Core Ultra) this caps at min(11, physicalCores - 1) to avoid E-core/SMT stragglers.
     * On 4 or fewer logical CPUs it is logical - 2, minimum 1.
     */
    public static int getOptimalWorkerCount() {
        // Leave two cores for the game's main and render threads
        if (PZONative.isLowCoreCpu()) {
            return Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
        }
        if (IS_HYBRID_CPU && PHYSICAL_CORES > 9) {
            return Math.min(11, PHYSICAL_CORES - 1);
        }
        int base = PZONative.isLoaded() ? PZONative.getPerformanceCores() : PHYSICAL_CORES;
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
