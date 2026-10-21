package com.pzoptimizer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * B42 launches with ZGC by default. On 4-8 core CPUs its concurrent threads compete with PZO's chunk and
 * simulation workers and cause stutter, so on those systems this switches ProjectZomboid64.json to G1GC
 * with low pause targets, leaving cores free for the workers.
 */
public final class GCLauncherOptimizer {

    private static volatile boolean active = true;
    private static volatile boolean tunedApplied = false;

    private static final String JSON_FILENAME = "ProjectZomboid64.json";
    private static final String BACKUP_FILENAME = "ProjectZomboid64.json.pzo-bak";

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean value) {
        active = value;
    }

    public static boolean isTunedApplied() {
        return tunedApplied;
    }

    public static synchronized void tuneLauncherGcIfBeneficial() {
        if (!active) return;
        try {
            int cores = Runtime.getRuntime().availableProcessors();
            File gameDir = new File(System.getProperty("user.dir", "."));
            File jsonFile = new File(gameDir, JSON_FILENAME);

            if (!jsonFile.exists() || !jsonFile.canWrite()) {
                return;
            }

            String content = Files.readString(jsonFile.toPath(), StandardCharsets.UTF_8);
            Object parsed = ZomboidConfigMigrator.JsonMini.parse(content);
            if (!(parsed instanceof Map)) {
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) parsed;
            Object vmObj = root.get("vmArgs");
            if (!(vmObj instanceof List)) {
                return;
            }

            @SuppressWarnings("unchecked")
            List<?> rawList = (List<?>) vmObj;
            List<String> vmArgs = new ArrayList<>();
            boolean changed = false;
            double totalRamGb = 16.0;
            try {
                java.lang.management.OperatingSystemMXBean osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                java.lang.reflect.Method m = null;
                try {
                    m = osBean.getClass().getMethod("getTotalMemorySize");
                } catch (NoSuchMethodException e) {
                    try {
                        m = osBean.getClass().getMethod("getTotalPhysicalMemorySize");
                    } catch (NoSuchMethodException ignored) {}
                }
                if (m != null) {
                    m.setAccessible(true);
                    Object val = m.invoke(osBean);
                    if (val instanceof Number) {
                        totalRamGb = ((Number) val).doubleValue() / (1024.0 * 1024.0 * 1024.0);
                    }
                }
            } catch (Throwable ignored) {}

            boolean wantG1 = cores <= 8 || totalRamGb < 20.0;

            for (Object item : rawList) {
                if (item == null) continue;
                String s = item.toString().trim();
                if (s.contains("UseCompactObjectHeaders")) {
                    changed = true;
                    continue; // Strip invalid flag
                }
                if (s.contains(" ")) {
                    // Re-split arguments that were joined with spaces
                    String[] tokens = s.split("\\s+");
                    for (String tok : tokens) {
                        if (!tok.isEmpty() && !tok.contains("UseCompactObjectHeaders") && !vmArgs.contains(tok)) {
                            vmArgs.add(tok);
                        }
                    }
                    changed = true;
                    continue;
                }
                if (wantG1 && "-XX:+UseZGC".equals(s)) {
                    changed = true;
                    if (!vmArgs.contains("-XX:+UseG1GC")) {
                        vmArgs.add("-XX:+UseG1GC");
                    }
                    if (!vmArgs.contains("-XX:MaxGCPauseMillis=16")) {
                        vmArgs.add("-XX:MaxGCPauseMillis=16");
                    }
                    continue;
                }
                if (!vmArgs.contains(s)) {
                    vmArgs.add(s);
                }
            }

            if (!vmArgs.contains(ZomboidConfigMigrator.TARGET_AGENTLIB)) {
                vmArgs.add(0, ZomboidConfigMigrator.TARGET_AGENTLIB);
                changed = true;
            }
            if (!vmArgs.contains("-XX:+IgnoreUnrecognizedVMOptions")) {
                vmArgs.add("-XX:+IgnoreUnrecognizedVMOptions");
                changed = true;
            }
            if (!vmArgs.contains("-XX:-UseTypeSpeculation") && !vmArgs.contains("-XX:+UseTypeSpeculation")) {
                vmArgs.add("-XX:-UseTypeSpeculation");
                changed = true;
            }

            // Also inspect windows platform-specific blocks if present
            Object winObj = root.get("windows");
            if (winObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> winMap = (Map<String, Object>) winObj;
                for (Map.Entry<String, Object> entry : winMap.entrySet()) {
                    if (entry.getValue() instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> verMap = (Map<String, Object>) entry.getValue();
                        Object verVmObj = verMap.get("vmArgs");
                        if (verVmObj instanceof List) {
                            List<?> verRawList = (List<?>) verVmObj;
                            List<String> verArgs = new ArrayList<>();
                            boolean verChanged = false;
                            for (Object vItem : verRawList) {
                                if (vItem == null) continue;
                                String vs = vItem.toString().trim();
                                if (vs.contains("pzo_native64") || vs.contains("UseCompactObjectHeaders") || vs.contains("zbNative")) {
                                    verChanged = true;
                                    continue;
                                }
                                if (wantG1 && "-XX:+UseZGC".equals(vs)) {
                                    verChanged = true;
                                    if (!verArgs.contains("-XX:+UseG1GC")) {
                                        verArgs.add("-XX:+UseG1GC");
                                    }
                                    continue;
                                }
                                verArgs.add(vs);
                            }
                            if (verChanged) {
                                verMap.put("vmArgs", verArgs);
                                changed = true;
                            }
                        }
                    }
                }
            }

            if (changed) {
                File backupFile = new File(gameDir, BACKUP_FILENAME);
                if (!backupFile.exists()) {
                    Files.copy(jsonFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                root.put("vmArgs", vmArgs);
                String formatted = ZomboidConfigMigrator.JsonMini.format(root);
                Files.writeString(jsonFile.toPath(), formatted, StandardCharsets.UTF_8);
                tunedApplied = true;
                PZOLogger.success("[GCLauncherOptimizer] Auto-tuned launcher JVM args (Enforced G1GC on " + String.format("%.1f", totalRamGb) + "GB RAM / " + cores + "-core system)");
            }
        } catch (Throwable t) {
            PZOLogger.info("[GCLauncherOptimizer] GC auto-tune notice: " + t.getMessage());
        }
    }
}
