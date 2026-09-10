package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Project Zomboid Build 42 - PopTemplateManager Crash Guard & Visual Bounds Shield.
 * Prevents game-ending exceptions in HumanVisual and CharacterCreationMain:
 */
public class PopTemplateGuard {

    private static volatile boolean initialized = false;
    private static volatile sun.misc.Unsafe unsafeInstance = null;

    private static void obtainUnsafe() {
        if (unsafeInstance != null) return;
        try {
            Field theUnsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            unsafeInstance = (sun.misc.Unsafe) theUnsafeField.get(null);
        } catch (Throwable t) {
            PZOLogger.warn("[PopTemplateGuard] Unsafe unavailable: " + t.getMessage());
        }
    }

    public static synchronized void initialize() {
        if (initialized) return;
        obtainUnsafe();
        ensurePopulated();
        startStartupVisualMonitor();
        initialized = true;
    }

    public static void resetFBOState() {
        try {
            Class<?> fboClass = Class.forName("zombie.core.textures.TextureFBO");
            Field checkedField = fboClass.getDeclaredField("checked");
            checkedField.setAccessible(true);
            checkedField.setBoolean(null, false);

            Field funcsField = fboClass.getDeclaredField("funcs");
            funcsField.setAccessible(true);
            funcsField.set(null, null);

            Field suppField = fboClass.getDeclaredField("fboSupported");
            suppField.setAccessible(true);
            suppField.setBoolean(null, false);
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    public static synchronized void ensurePopulated() {
        resetFBOState();
        obtainUnsafe();

        try {
            Class<?> ptmClass = Class.forName("zombie.core.skinnedmodel.population.PopTemplateManager");
            Field instField = ptmClass.getField("instance");
            Object ptm = instField.get(null);
            if (ptm != null) {
                Field maleField = ptmClass.getField("maleSkins");
                List<String> maleSkins = (List<String>) maleField.get(ptm);

                if (maleSkins == null || maleSkins.isEmpty()) {
                    try {
                        Method initMethod = ptmClass.getMethod("init");
                        initMethod.invoke(ptm);
                    } catch (Throwable t) {
                        PZOLogger.warn("[PopTemplateGuard] PopTemplateManager.init() fallback: " + t.getMessage());
                    }
                }

                // Enforce exact vanilla list sizes:
                // maleSkins & femaleSkins MUST be exactly 5 items (MaleBody01..05)
                // so CharacterCreationMain.lua:652 self.skinColors[1..5] is never indexed out-of-bounds!
                trimToSize(ptm, "maleSkins", 5, "MaleBody01");
                trimToSize(ptm, "femaleSkins", 5, "FemaleBody01");
                trimToSize(ptm, "maleSkinsZombie1", 4, "MaleZombie01");
                trimToSize(ptm, "femaleSkinsZombie1", 4, "FemaleZombie01");
                trimToSize(ptm, "maleSkinsZombie2", 4, "MaleZombie01");
                trimToSize(ptm, "femaleSkinsZombie2", 4, "FemaleZombie01");
                trimToSize(ptm, "maleSkinsZombie3", 4, "MaleZombie01");
                trimToSize(ptm, "femaleSkinsZombie3", 4, "FemaleZombie01");
                trimToSize(ptm, "skeletonMaleSkinsZombie", 3, "Skeleton");
                trimToSize(ptm, "skeletonFemaleSkinsZombie", 3, "Skeleton");
                trimToSize(ptm, "cowSkins", 2, "Cow_Black");
                trimToSize(ptm, "ratSkins", 1, "Rat");

                PZOLogger.success(String.format("[PopTemplateGuard] PopTemplateManager verified (maleSkins: %d items, femaleSkins: %d items, FBO state clean)", 
                    maleSkins != null ? maleSkins.size() : 0,
                    ptmClass.getField("femaleSkins").get(ptm) != null ? ((List<?>) ptmClass.getField("femaleSkins").get(ptm)).size() : 0));
            }
        } catch (ClassNotFoundException e) {
            // Outside PZ client context
        } catch (Throwable t) {
            PZOLogger.warn("[PopTemplateGuard] Non-fatal notice on PopTemplateManager: " + t.getMessage());
        }

        try {
            Class<?> sfClass = Class.forName("zombie.characters.SurvivorFactory");
            Field ff = sfClass.getField("FemaleForenames");
            Field mf = sfClass.getField("MaleForenames");
            Field sf = sfClass.getField("Surnames");
            List<String> female = (List<String>) ff.get(null);
            List<String> male = (List<String>) mf.get(null);
            List<String> surnames = (List<String>) sf.get(null);
            if (female != null && female.isEmpty()) female.add("Jane");
            if (male != null && male.isEmpty()) male.add("John");
            if (surnames != null && surnames.isEmpty()) surnames.add("Doe");
        } catch (Throwable ignored) {}

        armSurvivorMapGuard();

        armIsoWorldDescriptorsGuard();

        sanitizeLoadedSurvivors();
    }

    private static void armSurvivorMapGuard() {
        if (unsafeInstance == null) return;
        try {
            Class<?> charClass = Class.forName("zombie.characters.IsoGameCharacter");
            Field mapField = charClass.getDeclaredField("SurvivorMap");
            mapField.setAccessible(true);

            Object base = unsafeInstance.staticFieldBase(mapField);
            long offset = unsafeInstance.staticFieldOffset(mapField);
            Object existing = unsafeInstance.getObject(base, offset);

            if (existing instanceof java.util.Map && !(existing instanceof GuardedSurvivorMap)) {
                GuardedSurvivorMap guarded = new GuardedSurvivorMap((java.util.Map<?, ?>) existing);
                unsafeInstance.putObject(base, offset, guarded);
                PZOLogger.success("[PopTemplateGuard] GuardedSurvivorMap armed on IsoGameCharacter.SurvivorMap");
            }
        } catch (Throwable t) {
            PZOLogger.warn("[PopTemplateGuard] Notice on SurvivorMap arming: " + t.getMessage());
        }
    }

    private static void armIsoWorldDescriptorsGuard() {
        try {
            Class<?> worldClass = Class.forName("zombie.iso.IsoWorld");
            Field instField = worldClass.getField("instance");
            Object world = instField.get(null);
            if (world != null) {
                Field descField = worldClass.getField("survivorDescriptors");
                Object descriptors = descField.get(world);
                if (descriptors instanceof java.util.Map && !(descriptors instanceof GuardedSurvivorMap)) {
                    GuardedSurvivorMap guarded = new GuardedSurvivorMap((java.util.Map<?, ?>) descriptors);
                    descField.set(world, guarded);
                    PZOLogger.success("[PopTemplateGuard] GuardedSurvivorMap armed on IsoWorld.survivorDescriptors");
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void sanitizeSurvivorDesc(Object desc) {
        if (desc == null) return;
        try {
            Method getHv = desc.getClass().getMethod("getHumanVisual");
            Object hv = getHv.invoke(desc);
            if (hv != null) {
                Method getIndex = hv.getClass().getMethod("getSkinTextureIndex");
                int idx = (Integer) getIndex.invoke(hv);
                if (idx < 0) {
                    Method setIndex = hv.getClass().getMethod("setSkinTextureIndex", int.class);
                    setIndex.invoke(hv, 0);
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void sanitizeLoadedSurvivors() {
        try {
            Class<?> charClass = Class.forName("zombie.characters.IsoGameCharacter");
            Method getMap = charClass.getMethod("getSurvivorMap");
            Object mapObj = getMap.invoke(null);
            if (mapObj instanceof java.util.Map) {
                java.util.Map<?, ?> map = (java.util.Map<?, ?>) mapObj;
                for (Object desc : map.values()) {
                    sanitizeSurvivorDesc(desc);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void startStartupVisualMonitor() {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 15; i++) {
                try {
                    Thread.sleep(1000);
                    ensurePopulated();
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable ignored) {}
            }
        });
        t.setDaemon(true);
        t.setName("PZO-VisualGuard");
        t.start();
    }

    public static class GuardedSurvivorMap extends java.util.HashMap<Object, Object> {
        public GuardedSurvivorMap(java.util.Map<?, ?> initial) {
            if (initial != null) {
                for (java.util.Map.Entry<?, ?> entry : initial.entrySet()) {
                    sanitizeSurvivorDesc(entry.getValue());
                    super.put(entry.getKey(), entry.getValue());
                }
            }
        }

        @Override
        public Object put(Object key, Object value) {
            sanitizeSurvivorDesc(value);
            return super.put(key, value);
        }

        @Override
        public void putAll(java.util.Map<?, ?> m) {
            if (m != null) {
                for (java.util.Map.Entry<?, ?> entry : m.entrySet()) {
                    sanitizeSurvivorDesc(entry.getValue());
                }
            }
            super.putAll(m);
        }
    }

    @SuppressWarnings("unchecked")
    private static void trimToSize(Object ptm, String fieldName, int expectedSize, String defaultPrefix) {
        try {
            Field f = ptm.getClass().getField(fieldName);
            List<String> list = (List<String>) f.get(ptm);
            if (list == null) {
                list = new ArrayList<>();
                f.set(ptm, list);
            }
            if (list.isEmpty()) {
                for (int i = 1; i <= expectedSize; i++) {
                    String val = defaultPrefix.endsWith("01") ? defaultPrefix.substring(0, defaultPrefix.length() - 2) + String.format("%02d", i) : defaultPrefix;
                    list.add(val);
                }
            } else if (list.size() > expectedSize) {
                while (list.size() > expectedSize) {
                    list.remove(list.size() - 1);
                }
            }
        } catch (Throwable ignored) {}
    }
}
