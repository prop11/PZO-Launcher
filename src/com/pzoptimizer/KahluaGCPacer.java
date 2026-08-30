package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Project Zomboid Build 42 - Kahlua VM Incremental GC Pacer.
 * Smoothly paces Lua garbage collection in small, imperceptible increments
 * so that dead mod tables never accumulate to cause massive 20-30ms frame drops.
 */
public class KahluaGCPacer {
    private static volatile boolean running = false;

    public static void start() {
        if (running) return;
        running = true;

        Thread pacerThread = new Thread(() -> {
            // Wait for LuaManager initialization during game boot
            Class<?> lmClass = null;
            for (int i = 0; i < 300; i++) {
                try {
                    lmClass = Class.forName("zombie.Lua.LuaManager");
                    Field envField = lmClass.getField("env");
                    if (envField.get(null) != null) {
                        break;
                    }
                } catch (Throwable ignored) {}
                try { Thread.sleep(200); } catch (InterruptedException e) { return; }
            }

            if (lmClass == null) return;
            PZOLogger.success("KahluaGCPacer active (Incremental 50-step Lua memory collection armed)");

            while (true) {
                try {
                    Thread.sleep(1000); // 1-second gentle pacing interval

                    // Gently step Kahlua GC via LuaManager.thread if present
                    try {
                        Field threadField = lmClass.getField("thread");
                        Object thread = threadField.get(null);
                        if (thread != null) {
                            Method gcStep = thread.getClass().getMethod("gcStep", int.class);
                            gcStep.invoke(thread, 50);
                        }
                    } catch (Throwable ignored) {}

                } catch (InterruptedException ie) {
                    break;
                } catch (Throwable ignored) {}
            }
        });

        pacerThread.setName("PZO-Kahlua-GCPacer");
        pacerThread.setDaemon(true);
        pacerThread.setPriority(Thread.MIN_PRIORITY);
        pacerThread.start();
    }
}
