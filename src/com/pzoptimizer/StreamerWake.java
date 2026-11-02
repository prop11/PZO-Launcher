package com.pzoptimizer;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Wakes the WorldStreamer as soon as a chunk job is submitted, instead of leaving it asleep for up to 140 ms in its
 * fixed idle sleep.
 */
public final class StreamerWake {

    private static volatile Thread streamerThread = null;
    private static volatile boolean active = true;
    private static volatile boolean reflectionAttempted = false;
    public static final AtomicLong streamerWakes = new AtomicLong(0);

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

    /** Redirect for WorldStreamer.addJob: submits the job and unparks the streamer thread. */
    @SuppressWarnings("unchecked")
    public static boolean enqueueChunk(ConcurrentLinkedQueue queue, Object chunk) {
        boolean result = queue.add(chunk);
        signal();
        return result;
    }

    /** Replaces WorldStreamer's fixed sleep with a park I can wake. */
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

    /** Unparks the WorldStreamer thread without Thread.interrupt(), which would close its NIO file channels. */
    public static void signal() {
        if (!active) return;

        resolveStreamerThreadIfNeeded();
        Thread t = streamerThread;
        if (t != null && t.isAlive()) {
            streamerWakes.incrementAndGet();
            LockSupport.unpark(t);
        }
    }
}
