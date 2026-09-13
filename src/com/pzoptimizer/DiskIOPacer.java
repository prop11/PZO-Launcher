package com.pzoptimizer;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.concurrent.ConcurrentHashMap;
import zombie.Lua.LuaManager;

/**
 * Project Zomboid Build 42 - Disk I/O & Heartbeat Throttling Governor.
 * Throttles high-frequency file I/O operations from mods that write to disk on every frame or tick
 * (e.g. PZ_Map and PZ_Pulse creating/writing/closing heartbeat.txt at 120 writes/sec).
 * On Windows NTFS, opening, creating, and committing file journals 120 times per second triggers
 * severe I/O stalls and microstutters on the main thread.
 * This governor intercepts rapid writes to heartbeat/pulse files and rapid non-append overwrites,
 * returning a zero-overhead dummy LuaFileWriter backed by OutputStream.nullOutputStream().
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

        // Check if heartbeat / pulse / high-frequency monitor file
        if (lower.contains("heartbeat") || lower.contains("pulse") || lower.contains("temp_print_log") || lower.contains("ping.txt")) {
            long now = System.currentTimeMillis();
            Long last = lastWriteTimestamps.get(lower);
            if (last != null && (now - last) < HEARTBEAT_THROTTLE_MS) {
                return true;
            }
            lastWriteTimestamps.put(lower, now);
            return false;
        }

        // Generic safeguard: throttle non-append rapid full-file overwrites (<500ms)
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
