package com.pzoptimizer;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * One zip with everything a bug report needs, made from the bug report popup: console.txt, pzo_engine.log (and the one from the session before), the PZO
 * settings and status files, the game's launch config and a summary (Lua's mod list and engine state plus the
 * diagnostics report), the mod lists, plus any JVM crash logs (hs_err_pid*.log) from the last day. Saved in the Zomboid folder and
 * shown in the file manager so it can be dragged into an issue.
 *
 * Reports get posted in public, so every file is scrubbed on the way in: the user folder name in paths, Steam IDs,
 * the Steam name, and the environment variable block of a crash log.
 */
public final class ReportBundle {

    // Logs past this keep only their end, where the problem usually is
    private static final long MAX_LOG_BYTES = 24L * 1024 * 1024;

    private static final long CRASH_LOG_MAX_AGE_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_CRASH_LOGS = 5;
    private static final Pattern CRASH_LOG_NAME = Pattern.compile("hs_err_pid\\d+\\.log");

    // C:\Users\name, /home/name, /Users/name: the folder name goes, the rest of the path stays
    private static final Pattern USER_DIR =
        Pattern.compile("(?i)([\\\\/]+(?:Users|home|Documents and Settings)[\\\\/]+)[^\\\\/\\s\"'<>|:;,]+");
    private static final Pattern STEAM_ID = Pattern.compile("(?<!\\d)7656119\\d{10}(?!\\d)");

    private ReportBundle() {}

    /** Returns the zip's path, or null when it couldn't be written. */
    public static String create(String luaSummary) {
        return create(luaSummary, true);
    }

    /**
     * The report made from the crash window, before the game has started: console.txt is still the crashed session's,
     * and nothing of the game is loaded yet, so the engine state and the Steam name are left out.
     */
    static String createBeforeGame(String summary) {
        return create(summary, false);
    }

    private static String create(String luaSummary, boolean gameRunning) {
        try {
            File zDir = PZOEngineBridge.getZomboidDir();
            if (zDir == null) zDir = new File(System.getProperty("user.home"), "Zomboid");
            File luaDir = new File(zDir, "Lua");
            File gameDir = new File(".").getAbsoluteFile();
            String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new java.util.Date());
            File out = new File(zDir, "PZO_Report_" + stamp + ".zip");
            String steamName = gameRunning ? steamName() : null;
            try (ZipOutputStream z = new ZipOutputStream(new java.io.FileOutputStream(out))) {
                String summary = (luaSummary == null ? "" : luaSummary) + (gameRunning ? "\n\n" + PZOEngineBridge.getDiagnosticsReport() : "");
                addText(z, "summary.txt", scrubText(summary, steamName));
                addFile(z, new File(zDir, "console.txt"), "console.txt", steamName);
                String log = PZOLogger.getLogFilePath();
                if (log != null) addFile(z, new File(log), "pzo_engine.log", steamName);
                addFile(z, new File(luaDir, PZOLogger.PREVIOUS_LOG_NAME), PZOLogger.PREVIOUS_LOG_NAME, steamName);
                addText(z, "mod_lists.txt", scrubText(ModLists.describe(), steamName));
                for (String n : new String[] {"MPOptim_Settings.ini", "MPOptim_CustomPresets.ini", "pzo_config.json",
                        "pzo_status.json", "pzo_update.json", "PZO_mod_compat.txt"}) {
                    addFile(z, new File(luaDir, n), "Lua/" + n, steamName);
                }
                addFile(z, new File(zDir, "PZO_mod_compat.txt"), "PZO_mod_compat.txt", steamName);
                addFile(z, new File(gameDir, ZomboidConfigMigrator.TARGET_JSON_NAME), ZomboidConfigMigrator.TARGET_JSON_NAME, steamName);
                for (File crash : recentCrashLogs(gameDir)) {
                    addFile(z, crash, "crash/" + crash.getName(), steamName);
                }
            }
            PZOLogger.info("[ReportBundle] Wrote " + out.getAbsolutePath());
            reveal(out);
            return out.getAbsolutePath();
        } catch (Throwable t) {
            PZOLogger.warn("[ReportBundle] Could not write the report: " + t);
            return null;
        }
    }

    // The JVM writes hs_err_pid<pid>.log to its working folder (the game folder), or to the temp folder when it can't
    static java.util.List<File> recentCrashLogs(File gameDir) {
        return crashLogsSince(gameDir, System.currentTimeMillis() - CRASH_LOG_MAX_AGE_MS);
    }

    static java.util.List<File> crashLogsSince(File gameDir, long oldest) {
        java.util.List<File> found = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String tmp = System.getProperty("java.io.tmpdir");
        for (File dir : new File[] {gameDir, tmp == null ? null : new File(tmp)}) {
            File[] files = dir == null ? null : dir.listFiles((d, name) -> CRASH_LOG_NAME.matcher(name).matches());
            if (files == null) continue;
            for (File f : files) {
                if (f.isFile() && f.lastModified() >= oldest && seen.add(f.getName())) found.add(f);
            }
        }
        found.sort((x, y) -> Long.compare(y.lastModified(), x.lastModified()));
        return found.size() > MAX_CRASH_LOGS ? found.subList(0, MAX_CRASH_LOGS) : found;
    }

    // null when Steam isn't running the game, or the name is too short to replace safely
    private static String steamName() {
        try {
            if (!zombie.core.znet.SteamUtils.isSteamModeEnabled()) return null;
            String name = zombie.core.znet.SteamFriends.GetPersonaName();
            return name == null || name.trim().length() < 3 ? null : name.trim();
        } catch (Throwable t) {
            return null;
        }
    }

    // This PC's own home folder, as it shows up in logs, JSON (doubled backslashes) and Java URLs (forward slashes).
    // Done before USER_DIR because that one stops at a space and a Windows user name can have one.
    private static final Pattern HOME_DIR = homeDirPattern();

    private static Pattern homeDirPattern() {
        try {
            File home = new File(System.getProperty("user.home"));
            String name = home.getName();
            if (name.isEmpty() || home.getParentFile() == null) return null;
            return Pattern.compile("(?i)([\\\\/]+" + Pattern.quote(home.getParentFile().getName()) + "[\\\\/]+)" + Pattern.quote(name)
                + "(?![^\\\\/\\s\"'<>|:;,])");
        } catch (Throwable t) {
            return null;
        }
    }

    static String scrub(String line, String steamName) {
        String out = HOME_DIR == null ? line : HOME_DIR.matcher(line).replaceAll("$1<user>");
        out = USER_DIR.matcher(out).replaceAll("$1<user>");
        out = STEAM_ID.matcher(out).replaceAll("<steamid>");
        if (steamName != null && out.contains(steamName)) out = out.replace(steamName, "<steam name>");
        return out;
    }

    private static String scrubText(String text, String steamName) {
        StringBuilder b = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            if (b.length() > 0) b.append('\n');
            b.append(scrub(line, steamName));
        }
        return b.toString();
    }

    private static void addText(ZipOutputStream z, String name, String text) throws java.io.IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static void addFile(ZipOutputStream z, File f, String name, String steamName) {
        if (f == null || !f.isFile()) return;
        try {
            long len = f.length();
            z.putNextEntry(new ZipEntry(name));
            // the game keeps console.txt open, so read it shared instead of copying it
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                if (len > MAX_LOG_BYTES) {
                    raf.seek(len - MAX_LOG_BYTES);
                    z.write(("[first " + (len - MAX_LOG_BYTES) + " bytes left out]\n").getBytes(StandardCharsets.UTF_8));
                }
                BufferedReader in = new BufferedReader(
                    new InputStreamReader(Channels.newInputStream(raf.getChannel()), StandardCharsets.UTF_8), 65536);
                boolean crashLog = name.startsWith("crash/");
                boolean inEnv = false;
                String line;
                while ((line = in.readLine()) != null) {
                    if (crashLog) {
                        // PATH, USERNAME, TEMP and whatever else the JVM was started with
                        if (inEnv) {
                            if (!line.trim().isEmpty()) continue;
                            inEnv = false;
                        } else if (line.startsWith("Environment Variables:")) {
                            inEnv = true;
                            line = "Environment Variables: [left out of the report]";
                        }
                    }
                    z.write(scrub(line, steamName).getBytes(StandardCharsets.UTF_8));
                    z.write('\n');
                }
            }
            z.closeEntry();
        } catch (Throwable t) {
            PZOLogger.warn("[ReportBundle] Left out " + name + ": " + t);
        }
    }

    private static void reveal(File f) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            if (os.contains("win")) {
                new ProcessBuilder("explorer.exe", "/select," + f.getAbsolutePath()).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", "-R", f.getAbsolutePath()).start();
            } else {
                new ProcessBuilder("xdg-open", f.getParentFile().getAbsolutePath()).start();
            }
        } catch (Throwable ignored) {}
    }
}
