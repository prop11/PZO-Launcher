package com.pzoptimizer;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

/**
 * Loads PZO's companion engines from their designated mod folders at premain.
 * The Lua add-on enables its gameplay hooks in the current Lua state.
 */
public final class Extensions {
    static final String PZOE_MOD_ID = "PZOEnhancements";
    static final String PZOE_WORKSHOP_ID = "3838675606";
    // Inside the mod folder, outside media/ so neither the game nor other Java mod loaders go looking there
    static final String PZOE_JAR = "42" + File.separator + "pzoe" + File.separator + "PZOE.jar";
    private static final String ALIFE_MOD_ID = "PZOAmodOptim";
    private static final String ALIFE_JAR = "42" + File.separator + "pzoalife" + File.separator + "PZOAmodOptim.jar";
    private static final String ALIFE_CLASS = "com.pzoptimizer.alife.ALifeExtension";
    static final String ATTR_CLASS = "PZO-Extension-Class", ATTR_API = "PZO-Extension-Api";

    private static final List<Extension> loaded = new ArrayList<>();
    private static volatile Extension[] active = new Extension[0];
    private static final Set<String> early = new HashSet<>();
    // For the PZOE cards: loaded, off, missing, api=<n>, error
    private static volatile String pzoeStatus = "missing";

    private Extensions() {}

    static synchronized void load(Instrumentation inst) {
        pzoeStatus = loadPzoe(inst);
        File alife = findCompanionJar(ALIFE_MOD_ID, ALIFE_JAR, null, isEnabled(ALIFE_MOD_ID));
        if (alife != null) loadJar(inst, alife, ALIFE_CLASS, ALIFE_MOD_ID);
    }

    private static String loadPzoe(Instrumentation inst) {
        File jar = findPzoeJar();
        if (jar == null) return "missing";
        if (!isEnabled(PZOE_MOD_ID)) {
            PZOLogger.info("[Extensions] PZO Enhancements installed but not enabled anywhere: its engine stays unloaded");
            return "off";
        }
        return loadJar(inst, jar, null, "PZO Enhancements");
    }

    private static String loadJar(Instrumentation inst, File jar, String expectedClass, String name) {
        try (JarFile jf = new JarFile(jar)) {
            Attributes a = jf.getManifest() == null ? null : jf.getManifest().getMainAttributes();
            String cls = a == null ? null : a.getValue(ATTR_CLASS);
            String api = a == null ? null : a.getValue(ATTR_API);
            if (cls == null || api == null || (expectedClass != null && !expectedClass.equals(cls))) {
                PZOLogger.warn("[Extensions] " + jar + " has an unexpected extension manifest");
                return "error";
            }
            // Checked before any of its classes load, so a jar for another PZO never runs
            if (!api.trim().equals(Integer.toString(Extension.API))) {
                PZOLogger.warn("[Extensions] " + jar + " uses API " + api.trim() + ", PZO uses " + Extension.API);
                return "api=" + api.trim();
            }
            inst.appendToSystemClassLoaderSearch(new JarFile(jar));
            Class<?> c = Class.forName(cls, true, ClassLoader.getSystemClassLoader());
            Extension e = (Extension) c.getDeclaredConstructor().newInstance();
            if (e.apiVersion() != Extension.API) return "api=" + e.apiVersion();
            e.init();
            loaded.add(e);
            early.addAll(e.earlyTargets());
            active = loaded.toArray(new Extension[0]);
            PZOLogger.success("[Extensions] loaded " + e.name() + " from " + jar);
            return "loaded";
        } catch (Throwable t) {
            PZOLogger.warn("[Extensions] " + name + " engine failed to load from " + jar + ": " + t);
            return "error";
        }
    }

    static String pzoeStatus() {
        return pzoeStatus;
    }

    static Set<String> earlyTargets() {
        return early;
    }

    /** After PZO's own transform; null when no extension changed the class. */
    static byte[] postTransform(ClassLoader loader, String className, byte[] bytes) {
        Extension[] es = active;
        if (es.length == 0 || className == null || bytes == null) return null;
        byte[] out = null;
        for (Extension e : es) {
            try {
                byte[] r = e.transform(loader, className, out != null ? out : bytes);
                if (r != null) out = r;
            } catch (Throwable t) {
                PZOLogger.warn("[Extensions] " + e.name() + " transform of " + className + ": " + t);
            }
        }
        return out;
    }

    static Extension luaSourceReader(String path) {
        for (Extension e : active) {
            if (e.readsLuaSource(path)) return e;
        }
        return null;
    }

    static Object modApi(String op, Object a, Object b, Object c) {
        for (Extension e : active) {
            try {
                Object r = e.modApi(op, a, b, c);
                if (r != Extension.UNHANDLED) return r;
            } catch (Throwable t) {
                PZOLogger.warn("[Extensions] " + e.name() + " op " + op + ": " + t);
                return null;
            }
        }
        return Extension.UNHANDLED;
    }

    static void commit() {
        for (Extension e : active) {
            try {
                e.commit();
            } catch (Throwable t) {
                PZOLogger.warn("[Extensions] " + e.name() + " commit: " + t);
            }
        }
    }

    static void reset() {
        for (Extension e : active) {
            try {
                e.reset();
            } catch (Throwable t) {
                PZOLogger.warn("[Extensions] " + e.name() + " reset: " + t);
            }
        }
    }

    private static boolean isEnabled(String modId) {
        for (String id : JavaModLoader.getEnabledModIds()) {
            if (id.equalsIgnoreCase(modId)) return true;
        }
        return false;
    }

    // Local copies first (the author's working copy beats the Workshop download), then the Workshop item
    static File findPzoeJar() {
        String forced = System.getProperty("pzo.pzoe.jar");
        if (forced != null && new File(forced).isFile()) return new File(forced);
        return findCompanionJar(PZOE_MOD_ID, PZOE_JAR, PZOE_WORKSHOP_ID, true);
    }

    private static File findCompanionJar(String modId, String jarPath, String workshopId, boolean searchWorkshop) {
        File zomboid = ZomboidDir.get();
        File[] items = new File(zomboid, "Workshop").listFiles(File::isDirectory);
        if (items != null) {
            for (File item : items) {
                File f = new File(item, "Contents" + File.separator + "mods" + File.separator + modId + File.separator + jarPath);
                if (f.isFile()) return f;
            }
        }
        File local = new File(zomboid, "mods" + File.separator + modId + File.separator + jarPath);
        if (local.isFile()) return local;
        if (!searchWorkshop) return null;
        String relativeJar = "mods" + File.separator + modId + File.separator + jarPath;
        for (File ws : LuaModGuard.workshopItemDirs()) {
            if (workshopId != null) {
                File jar = new File(new File(ws, workshopId), relativeJar);
                if (jar.isFile()) return jar;
                continue;
            }
            File[] workshopItems = ws.listFiles(f -> f.isDirectory() && f.getName().matches("[0-9]+"));
            if (workshopItems == null) continue;
            for (File item : workshopItems) {
                File jar = new File(item, relativeJar);
                if (jar.isFile()) return jar;
            }
        }
        return null;
    }
}
