package com.pzoptimizer;

import java.io.File;
import java.nio.file.Files;

/** Persistent preferences (beta opt-in, ignored versions, Java mod approvals) in pzo_config.json. */
public final class PZOConfig {
    private static final String CONFIG_FILE = "pzo_config.json";
    public static final String CURRENT_NOTICE_VERSION = "1.0.2.1";
    private static volatile boolean betaOptIn = false;
    private static volatile String ignoredVersion = "";
    private static volatile boolean isolateConflictingFpsMods = true;
    private static volatile String multithreadingNoticeAckVersion = "";
    private static volatile String viewpointStanddownMode = "AUTO";
    private static volatile boolean javaModsEnabled = true;
    private static final java.util.Set<String> approvedJavaMods = new java.util.LinkedHashSet<>();
    private static final java.util.Set<String> blockedJavaMods = new java.util.LinkedHashSet<>();
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
                String vpMode = extractJsonField(content, "viewpoint_standdown_mode");
                if (vpMode != null && !vpMode.isEmpty()) {
                    viewpointStanddownMode = vpMode.toUpperCase();
                } else {
                    viewpointStanddownMode = "AUTO";
                }
                javaModsEnabled = !(content.contains("\"java_mods_enabled\":false") || content.contains("\"java_mods_enabled\": false"));
                parseHashList(extractJsonField(content, "approved_java_mods"), approvedJavaMods);
                parseHashList(extractJsonField(content, "blocked_java_mods"), blockedJavaMods);
            }
        } catch (Throwable ignored) {}
    }

    public static synchronized void save() {
        try {
            File cfg = getConfigFile();
            String json = String.format("{\"beta_opt_in\":%b,\"ignored_version\":\"%s\",\"isolate_conflicting_fps_mods\":%b,\"multithreading_notice_ack_version\":\"%s\",\"viewpoint_standdown_mode\":\"%s\",\"java_mods_enabled\":%b,\"approved_java_mods\":\"%s\",\"blocked_java_mods\":\"%s\"}",
                betaOptIn, ignoredVersion != null ? ignoredVersion : "", isolateConflictingFpsMods,
                multithreadingNoticeAckVersion != null ? multithreadingNoticeAckVersion : "",
                viewpointStanddownMode != null ? viewpointStanddownMode : "AUTO",
                javaModsEnabled, String.join(",", approvedJavaMods), String.join(",", blockedJavaMods));
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

    public static String getViewpointStanddownMode() {
        load();
        return viewpointStanddownMode != null ? viewpointStanddownMode : "AUTO";
    }

    public static void setViewpointStanddownMode(String mode) {
        load();
        viewpointStanddownMode = mode != null ? mode.toUpperCase() : "AUTO";
        save();
        PZOLogger.info("[PZO Config] Viewpoint standdown mode set to: " + viewpointStanddownMode);
    }

    public static boolean isJavaModsEnabled() {
        load();
        return javaModsEnabled;
    }

    public static synchronized void setJavaModsEnabled(boolean enabled) {
        load();
        if (javaModsEnabled == enabled) return;
        javaModsEnabled = enabled;
        save();
        PZOLogger.info("[PZO Config] Third-party Java mod loading set to: " + enabled + " (applies on next launch)");
    }

    /** Returns "APPROVED", "BLOCKED" or null when the user has not decided on this jar hash yet. */
    public static synchronized String getJavaModDecision(String sha256) {
        load();
        if (approvedJavaMods.contains(sha256)) return "APPROVED";
        if (blockedJavaMods.contains(sha256)) return "BLOCKED";
        return null;
    }

    public static synchronized void setJavaModDecision(String sha256, boolean approved) {
        load();
        approvedJavaMods.remove(sha256);
        blockedJavaMods.remove(sha256);
        (approved ? approvedJavaMods : blockedJavaMods).add(sha256);
        save();
    }

    private static void parseHashList(String value, java.util.Set<String> dest) {
        if (value == null) return;
        for (String h : value.split(",")) {
            h = h.trim().toLowerCase();
            if (h.matches("[0-9a-f]{64}")) dest.add(h);
        }
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

    public static boolean isMobileZomdroid() {
        return EngineGLStateGovernor.isMobileGLEnvironment();
    }
}
