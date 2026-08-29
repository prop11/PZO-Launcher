package com.pzoptimizer.server;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

public class PZOServerLogger {
    private static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private static File logFile = null;

    static {
        try {
            String userHome = System.getProperty("user.home");
            File zDir = new File(userHome, "Zomboid");
            File luaDir = new File(zDir, "Lua");
            if (!luaDir.exists()) luaDir.mkdirs();
            logFile = new File(luaDir, "pzo_server_engine.log");
        } catch (Throwable ignored) {}
    }

    public static void info(String msg) { log("INFO", msg); }
    public static void success(String msg) { log("SUCCESS", msg); }
    public static void warn(String msg) { log("WARN", msg); }
    public static void error(String msg, Throwable t) {
        log("ERROR", msg);
        if (t != null && logFile != null) {
            try (PrintWriter pw = new PrintWriter(new FileWriter(logFile, true))) {
                t.printStackTrace(pw);
            } catch (Throwable ignored) {}
        }
    }

    private static synchronized void log(String level, String msg) {
        String ts = SDF.format(new Date());
        String out = String.format("[%s] [%s] [PZO-Server] %s", ts, level, msg);
        System.out.println(out);

        if (logFile != null) {
            try (FileWriter fw = new FileWriter(logFile, true)) {
                fw.write(out + System.lineSeparator());
            } catch (Throwable ignored) {}
        }
    }
}
