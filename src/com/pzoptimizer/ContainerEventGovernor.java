package com.pzoptimizer;

import sun.misc.Unsafe;
import zombie.Lua.Event;
import zombie.Lua.LuaEventManager;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coalesces OnContainerUpdate bursts from door toggles, looting and tile updates. In large modpacks several
 * mods hook OnContainerUpdate and each runs full inventory sorts on the main thread. The first update fires
 * immediately, bursts within 250 ms are debounced, and a trailing dispatch delivers the final state.
 */
public final class ContainerEventGovernor {

    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static volatile boolean installed = false;
    private static volatile Unsafe unsafeInstance = null;
    private static volatile GovernedEventMap governedMapRef = null;

    public static void initialize() {
        // Disabled: firing Lua events from a background thread and intercepting OnContainerUpdate caused
        // contention and dropped events during multi-corpse drops.
    }

    public static synchronized boolean installGovernor() {
        return false;
    }

    public static void checkAndMaintain() {
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
