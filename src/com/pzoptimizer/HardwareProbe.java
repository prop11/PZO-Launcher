package com.pzoptimizer;

import java.lang.reflect.Method;
import java.util.List;

/**
 * This PC's CPU, RAM and main GPU for the settings screen's per-PC verdicts, read once on a background thread through
 * the OSHI library the game itself uses for its "System specs" log. OSHI is reached by reflection: its FFM build needs a
 * newer Java than PZO compiles for. Until the probe finishes (or if it fails) the summary is empty and Lua falls back.
 */
public final class HardwareProbe {

    private static volatile String summary = "";
    private static volatile boolean started = false;

    private HardwareProbe() {}

    /**
     * "cpu=..;cores=N;threads=N;ramMb=N;gpu=..;vramMb=N", or "" while still probing. With a hardware override set
     * (settings screen developer mode, pzo_config.json "hardware_override"), its keys replace the real ones and
     * "spoofed=1" is added, so the recommendations can be tried for other PCs.
     */
    public static String getSummary() {
        start();
        String override = PZOConfig.getHardwareOverride();
        if (override == null || override.isEmpty()) return summary;
        java.util.LinkedHashMap<String, String> merged = new java.util.LinkedHashMap<>();
        for (String part : (summary + ";" + override).split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) merged.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        merged.put("spoofed", "1");
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : merged.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** The detected hardware, ignoring any override. */
    public static String getRealSummary() {
        start();
        return summary;
    }

    public static synchronized void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(HardwareProbe::probe, "PZO-HardwareProbe");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void probe() {
        StringBuilder sb = new StringBuilder();
        try {
            ClassLoader cl = HardwareProbe.class.getClassLoader();
            Object info = Class.forName("oshi.SystemInfoFFM", true, cl).getConstructor().newInstance();
            Object hal = call(info, "getHardware");

            Object cpu = call(hal, "getProcessor");
            Object cpuId = call(cpu, "getProcessorIdentifier");
            append(sb, "cpu", String.valueOf(call(cpuId, "getName")).trim());
            append(sb, "cores", String.valueOf(call(cpu, "getPhysicalProcessorCount")));
            append(sb, "threads", String.valueOf(call(cpu, "getLogicalProcessorCount")));

            long ram = (Long) call(call(hal, "getMemory"), "getTotal");
            append(sb, "ramMb", String.valueOf(ram / (1024L * 1024L)));

            // Laptops and desktops with an iGPU list two cards; the game renders on the one with the most video memory
            List<?> gpus = (List<?>) call(hal, "getGraphicsCards");
            Object best = null;
            long bestVram = -1;
            for (Object g : gpus) {
                long v = (Long) call(g, "getVRam");
                if (v > bestVram) {
                    bestVram = v;
                    best = g;
                }
            }
            if (best != null) {
                append(sb, "gpu", String.valueOf(call(best, "getName")).trim());
                append(sb, "vramMb", String.valueOf(bestVram / (1024L * 1024L)));
            }
            summary = sb.toString();
            PZOLogger.info("[HardwareProbe] " + summary);
        } catch (Throwable t) {
            PZOLogger.warn("[HardwareProbe] Hardware details not readable, verdicts fall back to RAM only: " + t);
        }
    }

    private static Object call(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static void append(StringBuilder sb, String key, String value) {
        if (sb.length() > 0) sb.append(';');
        sb.append(key).append('=').append(value.replace(';', ',').replace('=', '-'));
    }
}
