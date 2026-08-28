package com.pzoptimizer;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Project Zomboid Build 42 - Dedicated PZO Engine Logger.
 * Writes real-time initialization statuses, subsystem diagnostics, and crash traces
 * to %USERPROFILE%/Zomboid/Lua/pzo_engine.log and System.out.
 */
public class PZOLogger {
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static File logFile = null;
    private static PrintWriter logWriter = null;
    private static final Object LOCK = new Object();

    static {
        try {
            String userHome = System.getProperty("user.home");
            File luaDir = new File(userHome, "Zomboid" + File.separator + "Lua");
            if (!luaDir.exists()) {
                luaDir.mkdirs();
            }
            logFile = new File(luaDir, "pzo_engine.log");
            logWriter = new PrintWriter(new FileWriter(logFile, false), true); // auto-flush
        } catch (Exception e) {
            System.err.println("[PZO-LOGGER-ERR] Failed to initialize log file: " + e.getMessage());
        }

        // Install Global Uncaught Exception Handler for crash capture
        try {
            Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                error("CRITICAL UNCAUGHT EXCEPTION in thread [" + thread.getName() + "]", throwable);
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, throwable);
                }
            });
        } catch (Throwable ignored) {}
    }

    public static void info(String message) {
        log("INFO", message, null);
    }

    public static void success(String message) {
        log("SUCCESS", message, null);
    }

    public static void warn(String message) {
        log("WARN", message, null);
    }

    public static void error(String message) {
        log("ERROR", message, null);
    }

    public static void error(String message, Throwable t) {
        log("ERROR", message, t);
    }

    private static void log(String level, String message, Throwable t) {
        String timestamp = DATE_FORMAT.format(new Date());
        String formatted = String.format("[%s] [%s] %s", timestamp, level, message);

        System.out.println(formatted);

        synchronized (LOCK) {
            if (logWriter != null) {
                try {
                    logWriter.println(formatted);
                    if (t != null) {
                        StringWriter sw = new StringWriter();
                        t.printStackTrace(new PrintWriter(sw));
                        logWriter.println(sw.toString());
                    }
                    logWriter.flush();
                } catch (Exception ignored) {}
            }
        }
    }

    public static String getLogFilePath() {
        return logFile != null ? logFile.getAbsolutePath() : "Unknown";
    }
}
