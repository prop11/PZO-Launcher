package com.pzoptimizer;

import java.io.File;
import java.nio.file.Files;

/**
 * PZO Persistent Configuration Manager.
 * Stores user preferences (such as Beta / Unstable build opt-in and ignored versions)
 * into pzo_config.json across Windows, macOS, and Linux.
 */
public final class PZOConfig {
    private static final String CONFIG_FILE = "pzo_config.json";
    public static final String CURRENT_NOTICE_VERSION = "0.9.7";
    private static volatile boolean betaOptIn = false;
    private static volatile String ignoredVersion = "";
    private static volatile boolean isolateConflictingFpsMods = true;
    private static volatile String multithreadingNoticeAckVersion = "";
    private static volatile boolean loaded = false;

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            File cfg = getConfigFile();
            if (cfg.exists()) {
                String content = Files.readString(cfg.toPath());
                betaOptIn = content.contains("\"beta_opt_in\":true") || content.contains("\"beta_opt_in\": true") || content.contains("\"beta_opt_in\":1");
                ignoredVersion = extractJsonField(content, "ignored_version");
                if (ignoredVersion == null) ignoredVersion = "";
                multithreadingNoticeAckVersion = extractJsonField(content, "multithreading_notice_ack_version");
                if (multithreadingNoticeAckVersion == null) multithreadingNoticeAckVersion = "";
                if (multithreadingNoticeAckVersion.isEmpty() && (content.contains("\"multithreading_notice_ack\":true") || content.contains("\"multithreading_notice_ack\": true"))) {
                    multithreadingNoticeAckVersion = CURRENT_NOTICE_VERSION;
                }
                if (content.contains("\"isolate_conflicting_fps_mods\":false") || content.contains("\"isolate_conflicting_fps_mods\": false") || content.contains("\"isolate_conflicting_fps_mods\":0")) {
                    isolateConflictingFpsMods = false;
                } else {
                    isolateConflictingFpsMods = true;
                }
            }
        } catch (Throwable ignored) {}
    }

    public static synchronized void save() {
        try {
            File cfg = getConfigFile();
            String json = String.format("{\"beta_opt_in\":%b,\"ignored_version\":\"%s\",\"isolate_conflicting_fps_mods\":%b,\"multithreading_notice_ack_version\":\"%s\"}",
                betaOptIn, ignoredVersion != null ? ignoredVersion : "", isolateConflictingFpsMods,
                multithreadingNoticeAckVersion != null ? multithreadingNoticeAckVersion : "");
            Files.writeString(cfg.toPath(), json);
        } catch (Throwable ignored) {}
    }

    public static boolean isBetaOptIn() {
        load();
        if (betaOptIn) return true;

        if (UpdateChecker.isUnstableIdentifier(UpdateChecker.CURRENT_VERSION)) {
            return true;
        }

        try {
            Class<?> coreClass = Class.forName("zombie.core.Core");
            java.lang.reflect.Method getInst = coreClass.getMethod("getInstance");
            Object core = getInst.invoke(null);
            if (core != null) {
                java.lang.reflect.Method getVer = coreClass.getMethod("getVersionNumber");
                Object verObj = getVer.invoke(core);
                if (verObj != null) {
                    String verStr = verObj.toString().toLowerCase();
                    if (verStr.contains("unstable") || verStr.contains("beta")) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}

        String steamBranch = System.getProperty("zomboid.steam");
        if (steamBranch != null && (steamBranch.toLowerCase().contains("unstable") || steamBranch.toLowerCase().contains("beta"))) {
            return true;
        }

        return false;
    }

    public static void setBetaOptIn(boolean optIn) {
        load();
        betaOptIn = optIn;
        save();
        PZOLogger.info("[PZO Config] Beta/Unstable build channel opt-in set to: " + optIn);
    }

    public static String getIgnoredVersion() {
        load();
        return ignoredVersion;
    }

    public static void setIgnoredVersion(String version) {
        load();
        ignoredVersion = version != null ? version : "";
        save();
    }

    public static boolean isVersionIgnored(String version) {
        load();
        return ignoredVersion != null && !ignoredVersion.isEmpty() && ignoredVersion.equalsIgnoreCase(version);
    }

    public static boolean isIsolateConflictingFpsMods() {
        load();
        return isolateConflictingFpsMods;
    }

    public static void setIsolateConflictingFpsMods(boolean isolate) {
        load();
        isolateConflictingFpsMods = isolate;
        save();
        PZOLogger.info("[PZO Config] Isolate conflicting FPS mods set to: " + isolate);
    }

    public static boolean isMultithreadingNoticeAcknowledged() {
        load();
        return CURRENT_NOTICE_VERSION.equals(multithreadingNoticeAckVersion);
    }

    public static void setMultithreadingNoticeAcknowledged(boolean ack) {
        load();
        multithreadingNoticeAckVersion = ack ? CURRENT_NOTICE_VERSION : "";
        save();
        PZOLogger.info("[PZO Config] Multithreading notice acknowledged set to: " + ack + " (" + multithreadingNoticeAckVersion + ")");
    }

    public static String getMultithreadingNoticeAckVersion() {
        load();
        return multithreadingNoticeAckVersion;
    }

    private static String extractJsonField(String json, String fieldName) {
        String key = "\"" + fieldName + "\":";
        int idx = json.indexOf(key);
        if (idx == -1) return null;
        int start = json.indexOf("\"", idx + key.length());
        if (start == -1) return null;
        int end = json.indexOf("\"", start + 1);
        if (end == -1) return null;
        return json.substring(start + 1, end);
    }

    public static File getConfigFile() {
        File zDir = PZOEngineBridge.getZomboidDir();
        if (zDir != null && zDir.exists()) {
            File luaDir = new File(zDir, "Lua");
            if (!luaDir.exists()) luaDir.mkdirs();
            return new File(luaDir, CONFIG_FILE);
        }
        String userHome = System.getProperty("user.home");
        File luaDir = new File(userHome, "Zomboid" + File.separator + "Lua");
        if (!luaDir.exists()) luaDir.mkdirs();
        return new File(luaDir, CONFIG_FILE);
    }
}
