package com.pzoptimizer;

import java.io.File;
import java.nio.file.Files;

/**
 * Project Zomboid Build 42 - Zero-Stall Disk I/O & Log Truncation Guard.
 * Cleans up runaway multi-gigabyte console.txt logs from previous modded sessions to keep startup instant.
 */
public class LogRotationGuard {
    private static final long MAX_LOG_SIZE_BYTES = 15 * 1024 * 1024; // 15 MB

    public static void checkAndRotateLogs() {
        try {
            String userHome = System.getProperty("user.home");
            if (userHome == null) return;

            String[] zPaths = new String[]{
                userHome + File.separator + "Zomboid",
                userHome + File.separator + "Documents" + File.separator + "Zomboid",
                userHome + File.separator + "OneDrive" + File.separator + "Documents" + File.separator + "Zomboid"
            };

            for (String zp : zPaths) {
                File logFile = new File(zp, "console.txt");
                if (logFile.exists() && logFile.length() > MAX_LOG_SIZE_BYTES) {
                    File bakFile = new File(zp, "console.txt.old");
                    if (bakFile.exists()) bakFile.delete();
                    logFile.renameTo(bakFile);
                    PZOLogger.info("LogRotationGuard: Archived bloated console.txt (" + (logFile.length() / (1024 * 1024)) + "MB) -> console.txt.old");
                }
            }
        } catch (Throwable ignored) {}
    }
}
