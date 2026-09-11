package com.pzoptimizer;

import sun.misc.Unsafe;
import zombie.Lua.Event;
import zombie.Lua.LuaEventManager;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Project Zomboid Build 42 - Container & Door Toggle Event Governor.
 * Coalesces rapid synchronous OnContainerUpdate Lua event bursts triggered
 * during door opening/closing, container looting, and spatial tile updates.
 * In heavy modpacks (300+ mods), multiple mods hook OnContainerUpdate simultaneously,
 * executing full inventory and container sort loops on the main thread.
 * This governor ensures the initial update triggers with zero latency, debounces bursts
 * within 250ms, and guarantees a trailing dispatch for the final state.
 */
public final class ContainerEventGovernor {

    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static volatile boolean installed = false;
    private static volatile Unsafe unsafeInstance = null;
    private static volatile GovernedEventMap governedMapRef = null;

    public static void initialize() {
        if (!initialized.compareAndSet(false, true)) {
            return;
        }

        obtainUnsafe();
        installGovernor();

        Thread daemon = new Thread(ContainerEventGovernor::daemonLoop, "PZO-ContainerEventGovernor");
        daemon.setDaemon(true);
        daemon.setPriority(Thread.MIN_PRIORITY);
        daemon.start();
    }

    public static synchronized boolean installGovernor() {
        if (installed && governedMapRef != null) return true;
        obtainUnsafe();
        if (unsafeInstance == null) return false;

        try {
            Class<?> lemClass = LuaEventManager.class;
            Field eventMapField = lemClass.getDeclaredField("EventMap");
            eventMapField.setAccessible(true);

            Object base = unsafeInstance.staticFieldBase(eventMapField);
            long offset = unsafeInstance.staticFieldOffset(eventMapField);

            @SuppressWarnings("unchecked")
            HashMap<String, Event> existing = (HashMap<String, Event>) unsafeInstance.getObject(base, offset);
            if (existing instanceof GovernedEventMap) {
                governedMapRef = (GovernedEventMap) existing;
                installed = true;
                return true;
            }

            GovernedEventMap wrapped = new GovernedEventMap(existing);
            unsafeInstance.putObject(base, offset, wrapped);
            governedMapRef = wrapped;
            installed = true;

            PZOLogger.success("[ContainerEventGovernor] Armed: OnContainerUpdate Burst Coalescing & Door Stutter Defense");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void checkAndMaintain() {
        if (!installed || governedMapRef == null) {
            installGovernor();
        }
    }

    private static void daemonLoop() {
        int backoff = 100;
        while (true) {
            try {
                if (!installed || governedMapRef == null) {
                    installGovernor();
                } else {
                    governedMapRef.checkTrailingDispatch();
                }

                Thread.sleep(backoff);
            } catch (InterruptedException ie) {
                break;
            } catch (Throwable ignored) {
                try { Thread.sleep(500); } catch (Throwable ignored2) {}
            }
        }
    }

    private static void obtainUnsafe() {
        if (unsafeInstance != null) return;
        try {
            Field theUnsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            unsafeInstance = (Unsafe) theUnsafeField.get(null);
        } catch (Throwable t) {
            PZOLogger.warn("[ContainerEventGovernor] Unsafe unavailable: " + t.getMessage());
        }
    }

    public static final class GovernedEventMap extends HashMap<String, Event> {
        private static final long serialVersionUID = 4201L;

        private final Event emptyContainerEvent;
        private volatile long lastContainerUpdateTime = 0;
        private volatile boolean hasPendingUpdate = false;
        private static final long DEBOUNCE_WINDOW_MS = 250L;

        public GovernedEventMap(Map<String, Event> existing) {
            super();
            this.emptyContainerEvent = new Event("OnContainerUpdate", -1);
            if (existing != null) {
                super.putAll(existing);
            }
        }

        @Override
        public Event get(Object key) {
            if ("OnContainerUpdate".equals(key)) {
                long now = System.currentTimeMillis();
                if (now - lastContainerUpdateTime < DEBOUNCE_WINDOW_MS) {
                    // Coalesce rapid bursts within 250ms
                    hasPendingUpdate = true;
                    return emptyContainerEvent;
                }
                lastContainerUpdateTime = now;
                hasPendingUpdate = false;
            }
            return super.get(key);
        }

        public void checkTrailingDispatch() {
            if (!hasPendingUpdate) return;
            long now = System.currentTimeMillis();
            if (now - lastContainerUpdateTime >= DEBOUNCE_WINDOW_MS) {
                hasPendingUpdate = false;
                // Allow the trailing dispatch to pass through get() immediately
                lastContainerUpdateTime = 0;
                try {
                    LuaEventManager.triggerEvent("OnContainerUpdate");
                } catch (Throwable ignored) {}
            }
        }
    }
}
