package com.pzoptimizer;

import java.lang.reflect.Field;

/**
 * WorldStreamer Thread Priority & Chunk Bandwidth Booster.
 * Elevates the "World Streamer" and "Lighting" thread priorities so the OS never starves
 * disk and chunk decompression workers while the player is driving fast in vehicles.
 */
public class WorldStreamerBooster {

    public static void startDaemon() {
        // Start predictive vehicle trajectory streaming daemon
        VehicleTrajectoryStreamer.start();

        Thread monitor = new Thread(() -> {
            while (true) {
                try {
                    // 0. Enforce IsoChunkMap parity and array capacity
                    ChunkCrashShield.enforceChunkGridSanity();
                    ChunkIngestionPacer.installPacer();
                    EngineFeaturesTuner.reapplyRuntimeTuning();

                    // 1. Boost WorldStreamer.instance.worldStreamer thread & prevent 140ms sleeps
                    Class<?> wsClass = Class.forName("zombie.iso.WorldStreamer");
                    Field instField = wsClass.getField("instance");
                    Object wsInstance = instField.get(null);
                    if (wsInstance != null) {
                        Field threadField = wsClass.getField("worldStreamer");
                        Thread wsThread = (Thread) threadField.get(wsInstance);
                        if (wsThread != null && wsThread.isAlive()) {
                            if (wsThread.getPriority() < Thread.NORM_PRIORITY + 2) {
                                wsThread.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 2)); // Priority 7
                            }
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
                                    if (th.getPriority() < Thread.NORM_PRIORITY + 2) {
                                        th.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 2));
                                    }
                                } else if (name.contains("Lighting") || name.contains("LightingThread")) {
                                    if (th.getPriority() < Thread.NORM_PRIORITY + 1) {
                                        th.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 1));
                                    }
                                }
                            }
                        }
                    }

                    Thread.sleep(1000);
                } catch (Throwable ignored) {
                    try { Thread.sleep(1500); } catch (Throwable ignored2) {}
                }
            }
        });
        monitor.setName("PZO-WorldStreamerBooster");
        monitor.setDaemon(true);
        monitor.setPriority(Thread.MIN_PRIORITY);
        monitor.start();
    }
}
