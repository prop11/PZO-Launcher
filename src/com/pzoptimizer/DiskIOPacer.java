package com.pzoptimizer;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.concurrent.ConcurrentHashMap;
import zombie.Lua.LuaManager;

/**
 * Throttles mods that rewrite a file every frame or tick (e.g. PZ_Map and PZ_Pulse rewriting heartbeat.txt
 * ~120 times a second), which stalls the main thread on NTFS. Rapid writes to heartbeat/pulse files and
 * non-append overwrites within 500 ms get a LuaFileWriter backed by OutputStream.nullOutputStream().
 */
public final class DiskIOPacer {

    private static final ConcurrentHashMap<String, Long> lastWriteTimestamps = new ConcurrentHashMap<>();
    private static volatile LuaManager.GlobalObject.LuaFileWriter cachedDummyWriter = null;
    private static final long HEARTBEAT_THROTTLE_MS = 2000L;
    private static final long RAPID_OVERWRITE_THROTTLE_MS = 500L;

    public static void initialize() {
        PZOLogger.success("[DiskIOPacer] Armed: File I/O Throttle & Heartbeat Journal Contention Defense");
    }

    public static LuaManager.GlobalObject.LuaFileWriter getDummyFileWriter() {
        if (cachedDummyWriter == null) {
            try {
                PrintWriter pw = new PrintWriter(OutputStream.nullOutputStream());
                cachedDummyWriter = new LuaManager.GlobalObject.LuaFileWriter(pw);
            } catch (Throwable t) {
                PZOLogger.warn("[DiskIOPacer] Notice creating dummy writer: " + t.getMessage());
            }
        }
        return cachedDummyWriter;
    }

    public static boolean shouldThrottle(String filename, boolean append) {
        if (filename == null) return false;
        String lower = filename.toLowerCase().replace('\\', '/');

        if (lower.contains("heartbeat") || lower.contains("pulse") || lower.contains("temp_print_log") || lower.contains("ping.txt")) {
            long now = System.currentTimeMillis();
            Long last = lastWriteTimestamps.get(lower);
            if (last != null && (now - last) < HEARTBEAT_THROTTLE_MS) {
                return true;
            }
            lastWriteTimestamps.put(lower, now);
            return false;
        }

        // Any non-append overwrite of the same file within 500 ms
        if (!append) {
            long now = System.currentTimeMillis();
            Long last = lastWriteTimestamps.get(lower);
            if (last != null && (now - last) < RAPID_OVERWRITE_THROTTLE_MS) {
                return true;
            }
            lastWriteTimestamps.put(lower, now);
        }

        return false;
    }
}
