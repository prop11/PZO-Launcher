package com.pzoptimizer;

import sun.misc.Unsafe;
import zombie.inventory.ItemConfigurator;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Project Zomboid Build 42 - Modded Container & ItemConfigurator Dynamic Registration Guard.
 * In Build 42, custom modded vehicles (KI5, DAMN, etc.) define custom container types
 */
public final class ContainerConfiguratorGuard {

    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static volatile boolean installed = false;
    private static volatile Unsafe unsafeInstance = null;

    public static void initialize() {
        if (!initialized.compareAndSet(false, true)) {
            return;
        }

        obtainUnsafe();
        installGuard();

        // Background daemon to ensure guard persists across world reloads
        Thread guardDaemon = new Thread(ContainerConfiguratorGuard::daemonLoop, "PZO-ContainerGuard");
        guardDaemon.setDaemon(true);
        guardDaemon.setPriority(Thread.MIN_PRIORITY);
        guardDaemon.start();
    }

    public static synchronized boolean installGuard() {
        if (installed) return true;
        obtainUnsafe();
        if (unsafeInstance == null) return false;

        try {
            Class<?> configClass = ItemConfigurator.class;
            Field mapField = configClass.getDeclaredField("STRING_INTEGER_HASH_MAP");
            mapField.setAccessible(true);

            Object base = unsafeInstance.staticFieldBase(mapField);
            long offset = unsafeInstance.staticFieldOffset(mapField);

            @SuppressWarnings("unchecked")
            HashMap<String, Object> existing = (HashMap<String, Object>) unsafeInstance.getObject(base, offset);
            if (existing instanceof AutoRegisteringContainerMap) {
                installed = true;
                return true;
            }

            AutoRegisteringContainerMap wrappedMap = new AutoRegisteringContainerMap(existing);
            unsafeInstance.putObject(base, offset, wrappedMap);
            installed = true;

            // Pre-register common modded vehicle container names
            preRegisterCommonModContainers();

            PZOLogger.success("[ContainerConfiguratorGuard] Armed: Dynamic Modded Container Registration Guard (Zero ItemPickInfo Log Contention)");
            return true;
        } catch (Throwable t) {
            // May fail early before ItemConfigurator is loaded; daemon will retry
            return false;
        }
    }

    private static void daemonLoop() {
        int backoff = 200;
        while (true) {
            try {
                if (!installed) {
                    installGuard();
                } else {
                    checkAndRehook();
                }

                Thread.sleep(installed ? 5000 : backoff);
                backoff = Math.min(backoff + 500, 5000);
            } catch (InterruptedException e) {
                break;
            } catch (Throwable ignored) {
                try { Thread.sleep(2000); } catch (Throwable ignored2) {}
            }
        }
    }

    private static void checkAndRehook() {
        if (unsafeInstance == null) return;
        try {
            Class<?> configClass = ItemConfigurator.class;
            Field mapField = configClass.getDeclaredField("STRING_INTEGER_HASH_MAP");
            Object base = unsafeInstance.staticFieldBase(mapField);
            long offset = unsafeInstance.staticFieldOffset(mapField);
            Object current = unsafeInstance.getObject(base, offset);
            if (current != null && !(current instanceof AutoRegisteringContainerMap)) {
                @SuppressWarnings("unchecked")
                HashMap<String, Object> existing = (HashMap<String, Object>) current;
                AutoRegisteringContainerMap wrapped = new AutoRegisteringContainerMap(existing);
                unsafeInstance.putObject(base, offset, wrapped);
                preRegisterCommonModContainers();
            }
        } catch (Throwable ignored) {}
    }

    private static void obtainUnsafe() {
        if (unsafeInstance != null) return;
        try {
            Field theUnsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            unsafeInstance = (Unsafe) theUnsafeField.get(null);
        } catch (Throwable t) {
            PZOLogger.warn("[ContainerConfiguratorGuard] Unsafe unavailable: " + t.getMessage());
        }
    }

    private static void preRegisterCommonModContainers() {
        String[] common = {
            "Trunk", "Roofrack", "GloveBox", "TruckBed", "TrailerTrunk",
            "CAM69Trunk", "CAM69Roofrack", "CVPI92Trunk", "CHE70Trunk", "CHE70Roofrack",
            "CAP85Trunk", "KI5TRCSTrunk", "E150Trunk", "E150Roofrack"
        };
        for (String c : common) {
            try {
                ItemConfigurator.registerZone(c);
            } catch (Throwable ignored) {}
        }
    }

    public static final class AutoRegisteringContainerMap extends HashMap<String, Object> {
        private static final long serialVersionUID = 42001L;

        public AutoRegisteringContainerMap(Map<String, ?> existing) {
            super();
            if (existing != null && !existing.isEmpty()) {
                this.putAll(existing);
            }
        }

        @Override
        public Object get(Object key) {
            Object val = super.get(key);
            if (val == null && key instanceof String && !((String) key).isEmpty()) {
                String str = (String) key;
                try {
                    ItemConfigurator.registerZone(str);
                    val = super.get(key);
                } catch (Throwable ignored) {}
            }
            return val;
        }
    }
}
