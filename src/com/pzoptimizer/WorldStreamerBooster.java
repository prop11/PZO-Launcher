package com.pzoptimizer;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

public class WorldStreamerBooster {

    private static volatile boolean streamBoosterInstalled = false;

    public static void startDaemon() {
        VehicleTrajectoryStreamer.start();

        try {
            ChunkCrashShield.enforceChunkGridSanity();
        } catch (Throwable ignored) {}

        Thread monitor = new Thread(() -> {
            while (true) {
                try {
                    ChunkIngestionPacer.installPacer();
                    ChunkIngestionPacer.upgradeChunkMapLock();
                    VehicleTravelOptimizer.checkAndMaintain();
                    RainAndWeatherOptimizer.checkAndMaintain();
                    EngineFeaturesTuner.reapplyRuntimeTuning();
                    installStreamBooster();

                    // Keep WorldStreamer at normal priority to avoid preempting rendering.
                    Class<?> wsClass = Class.forName("zombie.iso.WorldStreamer");
                    Field instField = wsClass.getField("instance");
                    Object wsInstance = instField.get(null);
                    if (wsInstance != null) {
                        Field threadField = wsClass.getField("worldStreamer");
                        Thread wsThread = (Thread) threadField.get(wsInstance);
                        if (wsThread != null && wsThread.isAlive()) {
                            if (wsThread.getPriority() != Thread.NORM_PRIORITY) {
                                wsThread.setPriority(Thread.NORM_PRIORITY);
                            }
                        }
                    }

                    // Apply the same priority policy to other background workers.
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
                                if (name.contains("World Streamer") || name.contains("WorldReuser") || name.contains("Lighting")) {
                                    if (th.getPriority() > Thread.NORM_PRIORITY) {
                                        th.setPriority(Thread.NORM_PRIORITY);
                                    }
                                }
                            }
                        }
                    }

                    int sleepMs = (streamBoosterInstalled && ChunkIngestionPacer.isPacerInstalled()) ? 5000 : 1000;
                    Thread.sleep(sleepMs);
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

    public static synchronized boolean installStreamBooster() {
        if (streamBoosterInstalled) return true;

        try {
            Class<?> wsClass = Class.forName("zombie.iso.WorldStreamer");
            Field instField = wsClass.getField("instance");
            Object wsInstance = instField.get(null);
            if (wsInstance == null) return false;

            if (PZONative.isLoaded()) {
                Field decompField = wsClass.getDeclaredField("decompressor");
                decompField.setAccessible(true);
                Object curDecomp = decompField.get(wsInstance);
                if (!(curDecomp instanceof NativeInflater)) {
                    setField(wsInstance, decompField, new NativeInflater());
                }
            }

            Field readBufField = wsClass.getDeclaredField("readBuf");
            readBufField.setAccessible(true);
            byte[] curReadBuf = (byte[]) readBufField.get(wsInstance);
            if (curReadBuf == null || curReadBuf.length < 262144) {
                setField(wsInstance, readBufField, new byte[262144]);
            }

            Field zipField = wsClass.getDeclaredField("inMemoryZip");
            zipField.setAccessible(true);
            ByteBuffer curZip = (ByteBuffer) zipField.get(wsInstance);
            if (curZip == null || curZip.capacity() < 524288) {
                setField(wsInstance, zipField, ByteBuffer.allocate(524288));
            }

            streamBoosterInstalled = true;
            PZOLogger.success("[WorldStreamerBooster] High-Speed Stream Booster Armed: NativeInflater (256KB readBuf | 512KB zipBB)");
            return true;
        } catch (Throwable t) {
            // WorldStreamer not yet initialized; will retry on next daemon cycle
            return false;
        }
    }

    private static void setField(Object instance, Field field, Object value) {
        try {
            field.setAccessible(true);
            field.set(instance, value);
        } catch (Throwable t1) {
            try {
                Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                sun.misc.Unsafe u = (sun.misc.Unsafe) theUnsafe.get(null);
                long offset = u.objectFieldOffset(field);
                u.putObject(instance, offset, value);
            } catch (Throwable ignored) {}
        }
    }
}
