package com.pzoptimizer;

import java.lang.reflect.Field;

/**
 * WorldStreamer Thread Priority & Chunk Bandwidth Booster.
 * Elevates the "World Streamer" and "Lighting" thread priorities so the OS never starves
 * disk and chunk decompression workers while the player is driving fast in vehicles.
 */
public class WorldStreamerBooster {

    public static void startDaemon() {
        Thread monitor = new Thread(() -> {
            boolean streamerBoosted = false;
            for (int i = 0; i < 60; i++) {
                try {
                    // 1. Boost WorldStreamer.instance.worldStreamer thread
                    Class<?> wsClass = Class.forName("zombie.iso.WorldStreamer");
                    Field instField = wsClass.getField("instance");
                    Object wsInstance = instField.get(null);
                    if (wsInstance != null) {
                        Field threadField = wsClass.getField("worldStreamer");
                        Thread wsThread = (Thread) threadField.get(wsInstance);
                        if (wsThread != null && wsThread.isAlive()) {
                            wsThread.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 3)); // Priority 8
                            streamerBoosted = true;
                            PZOLogger.success("WorldStreamerBooster: Elevated 'World Streamer' thread priority to " + wsThread.getPriority());
                        }
                    }

                    // 2. Scan all thread groups for Lighting and Worker threads
                    ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
                    while (rootGroup.getParent() != null) {
                        rootGroup = rootGroup.getParent();
                    }
                    Thread[] threads = new Thread[rootGroup.activeCount() + 32];
                    int count = rootGroup.enumerate(threads, true);
                    for (int t = 0; t < count; t++) {
                        Thread th = threads[t];
                        if (th != null && th.isAlive()) {
                            String name = th.getName();
                            if (name != null) {
                                if (name.contains("World Streamer") || name.contains("WorldReuser")) {
                                    th.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 3));
                                } else if (name.contains("Lighting") || name.contains("LightingThread")) {
                                    th.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 2));
                                }
                            }
                        }
                    }

                    if (streamerBoosted) {
                        // After boosting once, sleep in longer intervals (every 10 seconds)
                        Thread.sleep(10000);
                    } else {
                        Thread.sleep(1000);
                    }
                } catch (Throwable ignored) {
                    try { Thread.sleep(2000); } catch (Throwable ignored2) {}
                }
            }
        });
        monitor.setName("PZO-WorldStreamerBooster");
        monitor.setDaemon(true);
        monitor.setPriority(Thread.MIN_PRIORITY);
        monitor.start();
    }
}
