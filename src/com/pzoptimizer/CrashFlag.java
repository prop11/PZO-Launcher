package com.pzoptimizer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Tells whether the last session ended cleanly.
 *
 * Zomboid/Lua/pzo_session.txt says "running" from the start of main until the JVM's shutdown hooks run, where it
 * becomes "clean". Finding "running" at the next launch means the game crashed, froze and was killed, or the PC went
 * down with it open. A JVM crash log (hs_err_pid*.log) newer than the last session's start counts too.
 */
final class CrashFlag {

    static final class Status {
        boolean crashed;
        /** hs_err logs written since the last session started, newest first. */
        List<File> crashLogs = new ArrayList<>();
        String previousVersion = "";
    }

    private static final String FILE_NAME = "pzo_session.txt";

    private CrashFlag() {}

    /** Reads how the last session ended, then marks this one as running. Never throws. */
    static Status begin() {
        Status st = new Status();
        try {
            File zomboid = PZOEngineBridge.getZomboidDir();
            File lua = new File(zomboid, "Lua");
            if (!lua.isDirectory() && !lua.mkdirs()) return st;
            File flag = new File(lua, FILE_NAME);

            String state = "", pid = "";
            long time = 0;
            if (flag.isFile()) {
                for (String line : Files.readAllLines(flag.toPath(), StandardCharsets.UTF_8)) {
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                    if (k.equals("state")) state = v;
                    else if (k.equals("pid")) pid = v;
                    else if (k.equals("version")) st.previousVersion = v;
                    else if (k.equals("time")) {
                        try {
                            time = Long.parseLong(v);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            // A second copy of the game started while the first is still open is not a crash
            boolean otherInstance = state.equals("running") && isAlive(pid);
            if (otherInstance) return st;

            if (time > 0) {
                for (File f : ReportBundle.crashLogsSince(new File(".").getAbsoluteFile(), time)) st.crashLogs.add(f);
            }
            st.crashed = state.equals("running") || !st.crashLogs.isEmpty();

            write(flag, "running");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> write(flag, "clean"), "PZO-CleanExit"));
        } catch (Throwable t) {
            PZOLogger.warn("[CrashFlag] could not track this session: " + t);
        }
        return st;
    }

    private static boolean isAlive(String pid) {
        try {
            long p = Long.parseLong(pid);
            return p != ProcessHandle.current().pid() && ProcessHandle.of(p).map(ProcessHandle::isAlive).orElse(false);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void write(File flag, String state) {
        try {
            String text = "state=" + state + "\ntime=" + System.currentTimeMillis() + "\npid=" + ProcessHandle.current().pid()
                + "\nversion=" + UpdateChecker.CURRENT_VERSION + "\n";
            Files.writeString(flag.toPath(), text, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {}
    }
}
