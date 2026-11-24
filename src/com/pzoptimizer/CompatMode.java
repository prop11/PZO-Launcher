package com.pzoptimizer;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compatibility mode for older or low-end PCs (issue #45). Decided once per launch; off everywhere else, and then
 * nothing in PZO behaves differently.
 *
 * On Auto it kicks in for any of:
 *  - an x86-64 CPU without AVX2 (the native library falls back to its plain loops)
 *  - 4 or fewer hardware threads
 *  - less than LOW_RAM_GB of RAM
 * It lowers the starting heap to PZO's default when necessary, uses G1, and skips native MMCSS and priority tuning.
 *
 * Can be forced on or off from the settings screen (pzo_config.json "compat_mode": auto / on / off), with
 * -Dpzo.compat=on|off, or with the launch option -pzo_compat / -pzo_compat=off.
 */
public final class CompatMode {

    public static final double LOW_RAM_GB = 7.5;
    public static final int LOW_THREADS = 4;

    private static boolean decided, deciding, active;
    private static String reasons = "";
    private static boolean nativeAvx2Known, nativeAvx2;

    private CompatMode() {}

    /** Called by PZONative once its CPU check has run, before it applies the power shield. */
    static synchronized void noteNativeAvx2(boolean avx2) {
        nativeAvx2Known = true;
        nativeAvx2 = avx2;
    }

    public static synchronized boolean isActive() {
        if (!decided) decide();
        return active;
    }

    /** Why it is on, e.g. "no AVX2, 4 threads", or "" when off. */
    public static synchronized String getReasons() {
        if (!decided) decide();
        return reasons;
    }

    private static void decide() {
        if (!nativeAvx2Known && !deciding) {
            deciding = true;
            try {
                // Loads the native library, which reports AVX2 back through noteNativeAvx2 when it starts
                PZONative.isLoaded();
            } catch (Throwable ignored) {
            } finally {
                deciding = false;
            }
        }
        String override = override();
        List<String> why = new ArrayList<>();
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean x86 = arch.equals("amd64") || arch.equals("x86_64");
        if (x86 && nativeAvx2Known && !nativeAvx2) why.add("no AVX2");
        int threads = Runtime.getRuntime().availableProcessors();
        if (threads <= LOW_THREADS) why.add(threads + " threads");
        double ramGb = totalRamGb();
        if (ramGb > 0 && ramGb < LOW_RAM_GB) why.add(String.format(Locale.ROOT, "%.1f GB RAM", ramGb));

        if ("on".equals(override)) {
            active = true;
            reasons = why.isEmpty() ? "forced on" : "forced on; " + String.join(", ", why);
        } else if ("off".equals(override)) {
            active = false;
            reasons = "";
        } else {
            active = !why.isEmpty();
            reasons = String.join(", ", why);
        }
        // Only keep a final answer; if the native library is still loading, ask again later
        decided = nativeAvx2Known || !deciding;
        if (decided) {
            if (active) {
                PZOLogger.warn("[CompatMode] Compatibility mode ON (" + reasons + "): small starting heap, G1");
            } else if ("off".equals(override)) {
                PZOLogger.info("[CompatMode] Compatibility mode forced off");
            }
        }
    }

    // Launch option first, then the JVM property, then the settings screen's choice
    private static String override() {
        String cmd = System.getProperty("sun.java.command", "");
        for (String tok : cmd.split("\\s+")) {
            String t = tok.toLowerCase(Locale.ROOT);
            if (t.equals("-pzo_compat") || t.equals("-pzo_compat=on")) return "on";
            if (t.equals("-pzo_compat=off")) return "off";
        }
        String prop = System.getProperty("pzo.compat");
        if (prop != null) {
            prop = prop.trim().toLowerCase(Locale.ROOT);
            if (prop.equals("on") || prop.equals("true") || prop.equals("1")) return "on";
            if (prop.equals("off") || prop.equals("false") || prop.equals("0")) return "off";
        }
        return PZOConfig.getCompatMode();
    }

    /**
     * Physical RAM in GB, or -1 if unknown. Through the exported com.sun.management interface: reflection on the
     * implementation class (com.sun.management.internal.OperatingSystemImpl) throws InaccessibleObjectException on the
     * game's Java 25, which left this at -1 on every PC.
     */
    static double totalRamGb() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
                long bytes = os.getTotalMemorySize();
                if (bytes > 0) return bytes / (1024.0 * 1024.0 * 1024.0);
            }
        } catch (Throwable ignored) {}
        return -1;
    }
}
