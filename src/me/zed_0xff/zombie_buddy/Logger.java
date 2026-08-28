package me.zed_0xff.zombie_buddy;

import com.pzoptimizer.PZOLogger;

/**
 * ZombieBuddy API Emulation Bridge - Full Logger with Varargs / String format.
 */
public class Logger {
    public static void info(String format, Object... args) {
        log("INFO", format, args, null);
    }

    public static void warn(String format, Object... args) {
        log("WARN", format, args, null);
    }

    public static void error(String format, Object... args) {
        log("ERROR", format, args, null);
    }

    public static void error(String msg, Throwable t, Object... args) {
        log("ERROR", msg, args, t);
    }

    public static void debug(String format, Object... args) {
        log("DEBUG", format, args, null);
    }

    public static void trace(String format, Object... args) {
        log("TRACE", format, args, null);
    }

    private static void log(String level, String format, Object[] args, Throwable t) {
        String msg = format;
        if (args != null && args.length > 0 && format != null) {
            try {
                msg = String.format(format, args);
            } catch (Throwable ignored) {
                StringBuilder sb = new StringBuilder(format);
                for (Object arg : args) {
                    sb.append(" ").append(arg);
                }
                msg = sb.toString();
            }
        }
        if (msg == null) msg = "";

        if ("ERROR".equals(level)) {
            PZOLogger.error("[ZB-Mod] " + msg, t);
        } else if ("WARN".equals(level)) {
            PZOLogger.warn("[ZB-Mod] " + msg);
        } else {
            PZOLogger.info("[ZB-Mod] " + msg);
        }
    }
}
