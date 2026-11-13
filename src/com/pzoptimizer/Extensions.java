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
 * Loads engine extensions at premain. Right now that's one: PZO Enhancements' PZOE.jar, which carries the Java side of
 * its visuals (per-pixel lighting, splashes, trails, bake passes, screen pass, model snow). It only loads when the mod
 * is installed and on (default.txt, a save's mods.txt or a server's Mods=), so without it none of that code or its
 * class patches exist.
 *
 * Code only ever comes from the mod's own folders: Workshop item PZOE_WORKSHOP_ID, or a local copy under
 * Zomboid/mods or Zomboid/Workshop (where the author keeps what gets uploaded). Never from "any mod with a jar".
 */
public final class Extensions {
    static final String PZOE_MOD_ID = "PZOEnhancements";
    static final String PZOE_WORKSHOP_ID = "3838675606";
    // Inside the mod folder, outside media/ so neither the game nor other Java mod loaders go looking there
    static final String PZOE_JAR = "42" + File.separator + "pzoe" + File.separator + "PZOE.jar";
    static final String ATTR_CLASS = "PZO-Extension-Class", ATTR_API = "PZO-Extension-Api";

    private static final List<Extension> loaded = new ArrayList<>();
    private static volatile Extension[] active = new Extension[0];
    private static final Set<String> early = new HashSet<>();
    // For the PZOE cards: loaded, off, missing, api=<n>, error
    private static volatile String pzoeStatus = "missing";

    private Extensions() {}

    static synchronized void load(Instrumentation inst) {
        File jar = findPzoeJar();
        if (jar == null) {
            pzoeStatus = "missing";
            return;
        }
        if (!isEnabled(PZOE_MOD_ID)) {
            pzoeStatus = "off";
            PZOLogger.info("[Extensions] PZO Enhancements installed but not enabled anywhere: its engine stays unloaded");
            return;
        }
        try (JarFile jf = new JarFile(jar)) {
            Attributes a = jf.getManifest() == null ? null : jf.getManifest().getMainAttributes();
            String cls = a == null ? null : a.getValue(ATTR_CLASS);
            String api = a == null ? null : a.getValue(ATTR_API);
            if (cls == null || api == null) {
                pzoeStatus = "error";
                PZOLogger.warn("[Extensions] " + jar + " has no extension manifest, not loaded");
                return;
            }
            // Checked before any of its classes load, so a jar for another PZO never runs
            if (!api.trim().equals(Integer.toString(Extension.API))) {
                pzoeStatus = "api=" + api.trim();
                PZOLogger.warn("[Extensions] " + jar + " is built for engine API " + api.trim() + ", this PZO has "
                    + Extension.API + ": not loaded (update " + (Integer.parseInt(api.trim()) > Extension.API ? "PZO" : "PZO Enhancements") + ")");
                return;
            }
            inst.appendToSystemClassLoaderSearch(new JarFile(jar));
            Class<?> c = Class.forName(cls, true, ClassLoader.getSystemClassLoader());
            Extension e = (Extension) c.getDeclaredConstructor().newInstance();
            if (e.apiVersion() != Extension.API) {
                pzoeStatus = "api=" + e.apiVersion();
                return;
            }
            e.init();
            loaded.add(e);
            early.addAll(e.earlyTargets());
            active = loaded.toArray(new Extension[0]);
            pzoeStatus = "loaded";
            PZOLogger.success("[Extensions] loaded " + e.name() + " from " + jar);
        } catch (Throwable t) {
            pzoeStatus = "error";
            PZOLogger.warn("[Extensions] PZO Enhancements engine failed to load from " + jar + ": " + t);
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
        File zomboid = new File(System.getProperty("user.home"), "Zomboid");
        File[] items = new File(zomboid, "Workshop").listFiles(File::isDirectory);
        if (items != null) {
            for (File item : items) {
                File f = new File(item, "Contents" + File.separator + "mods" + File.separator + PZOE_MOD_ID + File.separator + PZOE_JAR);
                if (f.isFile()) return f;
            }
        }
        File local = new File(zomboid, "mods" + File.separator + PZOE_MOD_ID + File.separator + PZOE_JAR);
        if (local.isFile()) return local;
        for (File ws : LuaModGuard.workshopItemDirs()) {
            File f = new File(ws, PZOE_WORKSHOP_ID + File.separator + "mods" + File.separator + PZOE_MOD_ID + File.separator + PZOE_JAR);
            if (f.isFile()) return f;
        }
        return null;
    }
}
