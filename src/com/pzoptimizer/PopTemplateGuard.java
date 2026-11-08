package com.pzoptimizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Guards HumanVisual and CharacterCreationMain against out-of-range PopTemplateManager lists. */
public class PopTemplateGuard {

    private static volatile boolean initialized = false;
    private static volatile sun.misc.Unsafe unsafeInstance = null;

    private static final String[] SKIN_FIELDS = {
        "maleSkins", "femaleSkins", "maleSkinsZombie1", "femaleSkinsZombie1", "maleSkinsZombie2", "femaleSkinsZombie2",
        "maleSkinsZombie3", "femaleSkinsZombie3", "skeletonMaleSkinsZombie", "skeletonFemaleSkinsZombie", "cowSkins", "ratSkins"
    };
    private static final Map<String, List<String>> earlyFill = new HashMap<>();
    private static boolean guardCallingInit = false;
    private static boolean gameInitStarted = false;

    // PopTemplateManager.init only appends, so anything I filled early would be doubled by the game's own call.
    // 10 skins against the character screen's 5 colours is the disableBtn error on the Random button.
    public static boolean patchPopTemplateManager(com.pzoptimizer.shaded.asm.tree.ClassNode node) {
        com.pzoptimizer.shaded.asm.tree.MethodNode init = AsmSupport.findMethod(node, "init", "()V");
        if (init == null) return false;
        AsmSupport.prependCall(init, "com/pzoptimizer/PopTemplateGuard", "beforeGameInit", "(Ljava/lang/Object;)V");
        return true;
    }

    @SuppressWarnings("unchecked")
    public static synchronized void beforeGameInit(Object ptm) {
        if (guardCallingInit) return;
        gameInitStarted = true;
        try {
            // Takes back only what I added, one entry each, so skins from mods stay
            for (Map.Entry<String, List<String>> e : earlyFill.entrySet()) {
                List<String> list = (List<String>) ptm.getClass().getField(e.getKey()).get(ptm);
                if (list != null) {
                    for (String skin : e.getValue()) list.remove(skin);
                }
            }
        } catch (Throwable t) {
            PZOLogger.warn("[PopTemplateGuard] Could not undo the early skin fill: " + t);
        }
        earlyFill.clear();
    }

    @SuppressWarnings("unchecked")
    private static void rememberEarlyFill(Object ptm) throws ReflectiveOperationException {
        for (String field : SKIN_FIELDS) {
            List<String> list = (List<String>) ptm.getClass().getField(field).get(ptm);
            if (list != null) earlyFill.put(field, new ArrayList<>(list));
        }
    }

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

                if (!gameInitStarted && (maleSkins == null || maleSkins.isEmpty())) {
                    guardCallingInit = true;
                    try {
                        Method initMethod = ptmClass.getMethod("init");
                        initMethod.invoke(ptm);
                        rememberEarlyFill(ptm);
                    } catch (Throwable t) {
                        PZOLogger.warn("[PopTemplateGuard] PopTemplateManager.init() fallback: " + t.getMessage());
                    } finally {
                        guardCallingInit = false;
                    }
                }

                // maleSkins and femaleSkins need exactly 5 entries (MaleBody01..05), otherwise
                // CharacterCreationMain.lua:652 indexes self.skinColors[1..5] out of range
                fillIfEmpty(ptm, "maleSkins", 5, "MaleBody01");
                fillIfEmpty(ptm, "femaleSkins", 5, "FemaleBody01");
                fillIfEmpty(ptm, "maleSkinsZombie1", 4, "MaleZombie01");
                fillIfEmpty(ptm, "femaleSkinsZombie1", 4, "FemaleZombie01");
                fillIfEmpty(ptm, "maleSkinsZombie2", 4, "MaleZombie01");
                fillIfEmpty(ptm, "femaleSkinsZombie2", 4, "FemaleZombie01");
                fillIfEmpty(ptm, "maleSkinsZombie3", 4, "MaleZombie01");
                fillIfEmpty(ptm, "femaleSkinsZombie3", 4, "FemaleZombie01");
                fillIfEmpty(ptm, "skeletonMaleSkinsZombie", 3, "Skeleton");
                fillIfEmpty(ptm, "skeletonFemaleSkinsZombie", 3, "Skeleton");
                fillIfEmpty(ptm, "cowSkins", 2, "Cow_Black");
                fillIfEmpty(ptm, "ratSkins", 1, "Rat");

                PZOLogger.success(String.format("[PopTemplateGuard] PopTemplateManager verified (maleSkins: %d items, femaleSkins: %d items, FBO state clean)", 
                    maleSkins != null ? maleSkins.size() : 0,
                    ptmClass.getField("femaleSkins").get(ptm) != null ? ((List<?>) ptmClass.getField("femaleSkins").get(ptm)).size() : 0));
            }
        } catch (ClassNotFoundException e) {
            // Not running inside the PZ client
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
    // Only fill an empty list with the vanilla defaults, never remove skins a mod added
    private static void fillIfEmpty(Object ptm, String fieldName, int expectedSize, String defaultPrefix) {
        try {
            Field f = ptm.getClass().getField(fieldName);
            List<String> list = (List<String>) f.get(ptm);
            if (list == null) {
                list = new ArrayList<>();
                f.set(ptm, list);
            }
            if (list.isEmpty() && !gameInitStarted) {
                List<String> added = new ArrayList<>();
                for (int i = 1; i <= expectedSize; i++) {
                    String val = defaultPrefix.endsWith("01") ? defaultPrefix.substring(0, defaultPrefix.length() - 2) + String.format("%02d", i) : defaultPrefix;
                    list.add(val);
                    added.add(val);
                }
                earlyFill.put(fieldName, added);
            }
        } catch (Throwable ignored) {}
    }
}
