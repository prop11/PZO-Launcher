package com.pzoptimizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java side of the PZOApi Lua table that other mods use to stay compatible with PZO (docs/MODDING_API.md).
 *
 * The Lua side owns the registrations: every Lua (re)load rebuilds them from the mods that are enabled now, and
 * pushes them here after reset(), so a mod disabled in game leaves nothing behind. Everything here is read on the
 * game thread in hot paths, so each check starts with a plain field test that is false when no mod used the API.
 */
public final class ModApi {

    public static final int API_VERSION = 1;
    /** modData key a mod sets to true on a zombie that PZO must update like one next to the player. */
    public static final String FULL_RATE_KEY = "PZOFullRate";

    private static volatile String[] exemptPaths = new String[0];
    private static final Set<Object> exemptPrototypes = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private static volatile boolean anyExemptPrototype;
    // Setting key -> "modId: reason"
    private static final Map<String, String> standdowns = new ConcurrentHashMap<>();
    static volatile boolean zombieMarks;

    private ModApi() {}

    /** PZOEngine.modApi(op, a, b, c) from the Lua PZOApi. */
    static Object dispatch(String op, Object a, Object b, Object c) {
        switch (op) {
            case "reset":
                reset();
                return Boolean.TRUE;
            case "exemptPath":
                if (a == null) return Boolean.FALSE;
                addExemptPath(a.toString());
                return Boolean.TRUE;
            case "exemptHandler":
                return Boolean.valueOf(addExemptHandler(a));
            case "standdown":
                if (a == null) return Boolean.FALSE;
                standdowns.put(a.toString(), (b == null ? "?" : b.toString()) + (c == null || c.toString().isEmpty() ? "" : ": " + c));
                PZOLogger.info("[ModApi] " + a + " stood down for " + standdowns.get(a.toString()));
                return Boolean.TRUE;
            case "release":
                return Boolean.valueOf(a != null && standdowns.remove(a.toString()) != null);
            case "zombieMarks":
                zombieMarks = Boolean.TRUE.equals(a);
                return Boolean.TRUE;
            case "invalidate":
                return Boolean.valueOf(invalidate(a == null ? "" : a.toString()));
            case "apiVersion":
                return Double.valueOf(API_VERSION);
            default:
                return Boolean.FALSE;
        }
    }

    static synchronized void reset() {
        exemptPaths = new String[0];
        exemptPrototypes.clear();
        anyExemptPrototype = false;
        standdowns.clear();
        zombieMarks = false;
        LuaEventPatches.clearModCache();
    }

    private static synchronized void addExemptPath(String path) {
        String p = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (p.isEmpty()) return;
        for (String e : exemptPaths) if (e.equals(p)) return;
        List<String> next = new ArrayList<>(List.of(exemptPaths));
        next.add(p);
        exemptPaths = next.toArray(new String[0]);
        LuaEventPatches.clearModCache();
    }

    private static boolean addExemptHandler(Object fn) {
        if (!(fn instanceof se.krka.kahlua.vm.LuaClosure lc) || lc.prototype == null) return false;
        exemptPrototypes.add(lc.prototype);
        anyExemptPrototype = true;
        LuaEventPatches.clearModCache();
        return true;
    }

    /** True when a mod asked for the script at this lower-cased path to run on every event. */
    static boolean isExemptPath(String lowerPath) {
        for (String p : exemptPaths) {
            if (lowerPath.contains(p)) return true;
        }
        return false;
    }

    static boolean isExemptPrototype(Object prototype) {
        return anyExemptPrototype && exemptPrototypes.contains(prototype);
    }

    /** The mod and reason holding this setting off, or null. */
    public static String standdownOf(String key) {
        return standdowns.isEmpty() ? null : standdowns.get(key);
    }

    /** True when a mod marked this zombie through PZOApi.markZombie (game thread only: reads its modData). */
    public static boolean isFullRate(zombie.characters.IsoZombie z) {
        if (!zombieMarks || z == null || !z.hasModData()) return false;
        try {
            return Boolean.TRUE.equals(z.getModData().rawget(FULL_RATE_KEY));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean invalidate(String what) {
        switch (what.toLowerCase(Locale.ROOT)) {
            case "walls":
                CrowdEdgeMemo.invalidate(null);
                return true;
            case "scripts":
                ScriptLookupCache.invalidate();
                return true;
            default:
                return false;
        }
    }
}
