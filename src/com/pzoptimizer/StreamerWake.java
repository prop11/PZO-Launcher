package com.pzoptimizer;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Wakes WorldStreamer.threadLoop() from its 140 ms idle sleep as soon as PZO queues or ingests chunks. */
public final class StreamerWake {

    private static volatile Thread streamerThread = null;
    private static volatile boolean active = true;
    private static volatile boolean reflectionAttempted = false;

    private StreamerWake() {}

    public static void setActive(boolean value) {
        active = value;
    }

    public static boolean isActive() {
        return active;
    }

    public static void register(Thread thread) {
        streamerThread = (thread != null) ? thread : Thread.currentThread();
    }

    private static void resolveStreamerThreadIfNeeded() {
        if (streamerThread != null || reflectionAttempted) return;
        reflectionAttempted = true;

        try {
            Class<?> wsClass = Class.forName("zombie.iso.WorldStreamer");
            Field instField = wsClass.getField("instance");
            Object wsInstance = instField.get(null);
            if (wsInstance != null) {
                Field threadField = wsClass.getDeclaredField("worldStreamer");
                threadField.setAccessible(true);
                streamerThread = (Thread) threadField.get(wsInstance);
            }
        } catch (Throwable ignored) {}
    }

    /** Interruptible replacement for the streamer's fixed sleep. */
    public static void idle(long millis) throws InterruptedException {
        if (!active || millis <= 0) {
            Thread.sleep(millis);
            return;
        }

        if (streamerThread == null) {
            register(Thread.currentThread());
        }

        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(millis));
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
    }

    /** Unparks the WorldStreamer thread. Uses unpark rather than interrupt, which would close its NIO file channels. */
    public static void signal() {
        if (!active) return;

        resolveStreamerThreadIfNeeded();
        Thread t = streamerThread;
        if (t != null && t.isAlive()) {
            LockSupport.unpark(t);
        }
    }
}
