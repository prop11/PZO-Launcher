package com.pzoptimizer;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * One zip with everything a bug report needs, made from the bug report popup: console.txt, pzo_engine.log, the PZO
 * settings and status files, the game's launch config and a summary (Lua's mod list and engine state plus the
 * diagnostics report). Saved in the Zomboid folder and shown in the file manager so it can be dragged into an issue.
 */
public final class ReportBundle {

    // Logs past this keep only their end, where the problem usually is
    private static final long MAX_LOG_BYTES = 24L * 1024 * 1024;

    private ReportBundle() {}

    /** Returns the zip's path, or null when it couldn't be written. */
    public static String create(String luaSummary) {
        try {
            File zDir = PZOEngineBridge.getZomboidDir();
            if (zDir == null) zDir = new File(System.getProperty("user.home"), "Zomboid");
            File luaDir = new File(zDir, "Lua");
            File gameDir = new File(".").getAbsoluteFile();
            String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new java.util.Date());
            File out = new File(zDir, "PZO_Report_" + stamp + ".zip");
            try (ZipOutputStream z = new ZipOutputStream(new java.io.FileOutputStream(out))) {
                addText(z, "summary.txt", (luaSummary == null ? "" : luaSummary) + "\n\n" + PZOEngineBridge.getDiagnosticsReport());
                addFile(z, new File(zDir, "console.txt"), "console.txt");
                String log = PZOLogger.getLogFilePath();
                if (log != null) addFile(z, new File(log), "pzo_engine.log");
                for (String n : new String[] {"MPOptim_Settings.ini", "MPOptim_CustomPresets.ini", "pzo_config.json",
                        "pzo_status.json", "pzo_update.json", "PZO_mod_compat.txt"}) {
                    addFile(z, new File(luaDir, n), "Lua/" + n);
                }
                addFile(z, new File(gameDir, ZomboidConfigMigrator.TARGET_JSON_NAME), ZomboidConfigMigrator.TARGET_JSON_NAME);
            }
            PZOLogger.info("[ReportBundle] Wrote " + out.getAbsolutePath());
            reveal(out);
            return out.getAbsolutePath();
        } catch (Throwable t) {
            PZOLogger.warn("[ReportBundle] Could not write the report: " + t);
            return null;
        }
    }

    private static void addText(ZipOutputStream z, String name, String text) throws java.io.IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static void addFile(ZipOutputStream z, File f, String name) {
        if (f == null || !f.isFile()) return;
        try {
            long len = f.length();
            z.putNextEntry(new ZipEntry(name));
            if (len > MAX_LOG_BYTES) {
                // the game keeps console.txt open, so read it shared instead of copying it
                try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                    raf.seek(len - MAX_LOG_BYTES);
                    z.write(("[first " + (len - MAX_LOG_BYTES) + " bytes left out]\n").getBytes(StandardCharsets.UTF_8));
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = raf.read(buf)) > 0) z.write(buf, 0, r);
                }
            } else {
                try (InputStream in = new FileInputStream(f)) {
                    in.transferTo(z);
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
