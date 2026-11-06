package com.pzoptimizer;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
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
 * It drops -XX:+AlwaysPreTouch (which touches the whole starting heap before the game starts), starts with a small heap
 * that grows as needed, sticks to G1, and the native power shield leaves the process priority, MMCSS and the working
 * set lock alone.
 *
 * Can be forced on or off from the settings screen (pzo_config.json "compat_mode": auto / on / off), with
 * -Dpzo.compat=on|off, or with the launch option -pzo_compat / -pzo_compat=off.
 */
public final class CompatMode {

    public static final double LOW_RAM_GB = 7.5;
    public static final int LOW_THREADS = 4;
    /** Starting heap in compatibility mode; the heap still grows to -Xmx. */
    public static final int COMPAT_INITIAL_HEAP_MB = 1024;

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
                PZOLogger.warn("[CompatMode] Compatibility mode ON (" + reasons + "): no heap pre-touch, small starting heap, G1, no raised priority or memory lock");
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

    // Through the exported com.sun.management interface. Reflecting on the bean's own class (the old way) hits the JDK's
    // internal OperatingSystemImpl, which module rules won't open, so this always came back -1 and the low-RAM check
    // never fired.
    static double totalRamGb() {
        try {
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                return sun.getTotalMemorySize() / (1024.0 * 1024.0 * 1024.0);
            }
        } catch (Throwable ignored) {}
        return -1;
    }
}
