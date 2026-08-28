package me.zed_0xff.zombie_buddy;

import com.pzoptimizer.PZOLogger;

/**
 * ZombieBuddy API Emulation Bridge - Logger.
 * Seamlessly routes 3rd-party ZombieBuddy mod log calls to pzo_engine.log.
 */
public class Logger {
    public static void info(String msg) {
        PZOLogger.info("[ZB-Mod] " + msg);
    }

    public static void warn(String msg) {
        PZOLogger.warn("[ZB-Mod] " + msg);
    }

    public static void error(String msg) {
        PZOLogger.error("[ZB-Mod] " + msg);
    }

    public static void error(String msg, Throwable t) {
        PZOLogger.error("[ZB-Mod] " + msg, t);
    }

    public static void debug(String msg) {
        PZOLogger.info("[ZB-Mod] " + msg);
    }
}
