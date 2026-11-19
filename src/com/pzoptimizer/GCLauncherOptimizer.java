package com.pzoptimizer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Launch arg cleanup in ProjectZomboid64.json: drops bad and outdated args and makes sure the PZO agent is in. The
 * garbage collector is picked by ZomboidConfigMigrator (one place, so the two never fight over the file).
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

            for (Object item : rawList) {
                if (item == null) continue;
                String s = item.toString().trim();
                if (s.contains("UseCompactObjectHeaders")) {
                    changed = true;
                    continue; // drop the bad flag
                }
                if (ZomboidConfigMigrator.isPzoAgentArg(s) && !s.equals(ZomboidConfigMigrator.TARGET_AGENTLIB)) {
                    changed = true;
                    continue; // the old agent; TARGET_AGENTLIB goes back in below
                }
                List<String> tokens = ZomboidConfigMigrator.splitJoinedArgs(s);
                if (tokens.size() > 1) {
                    for (String tok : tokens) {
                        if (!tok.isEmpty() && !tok.contains("UseCompactObjectHeaders") && !ZomboidConfigMigrator.isPzoAgentArg(tok) && !vmArgs.contains(tok)) {
                            vmArgs.add(tok);
                        }
                    }
                    changed = true;
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

            // The Windows-specific blocks too, if there are any
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
                PZOLogger.success("[GCLauncherOptimizer] Cleaned up launcher JVM args");
            }
        } catch (Throwable t) {
            PZOLogger.info("[GCLauncherOptimizer] GC auto-tune notice: " + t.getMessage());
        }
    }
}
