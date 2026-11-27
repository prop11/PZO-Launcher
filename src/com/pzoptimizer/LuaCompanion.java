package com.pzoptimizer;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaCallFrame;
import zombie.ZomboidFileSystem;
import zombie.Lua.LuaManager;
import zombie.core.Language;
import zombie.core.Translator;
import zombie.gameStates.ChooseGameInfo;
import zombie.network.GameClient;
import zombie.network.GameServer;

/**
 * A server without the PZO Lua mod switches it off for the session (the client loads the server's mod list), so
 * players lost the F10 panel while the engine kept running. Here the engine loads the panel itself: after the client
 * Lua is in, if MPOptimizer isn't part of it, the files named in the mod's 42/pzo_companion.txt run straight from the
 * installed mod folder. Only that list loads (UI and engine settings), never the server folder or anything that
 * touches the world. They run through RunLua, outside LoadDirBase's list, so they stay out of the server's Lua
 * checksum.
 */
public final class LuaCompanion {

    static final String LIST_FILE = "pzo_companion.txt";
    private static final String[] TRANSLATE_FILES = { "UI", "ContextMenu", "Tooltip" };

    private static volatile boolean activeThisState;
    private static boolean failLogged;

    private LuaCompanion() {}

    /** True while the current Lua state holds the companion UI (the server has no PZO Lua mod). */
    public static boolean isActive() {
        return activeThisState;
    }

    /** End of LuaManager.LoadDirBase(): shared and client Lua are loaded, OnGameBoot hasn't fired yet. */
    public static void afterBaseLua() {
        activeThisState = false;
        try {
            load();
        } catch (Throwable t) {
            if (!failLogged) {
                failLogged = true;
                PZOLogger.warn("[LuaCompanion] could not load the PZO panel for this server: " + t);
            }
        }
    }

    private static void load() throws Exception {
        if (!GameClient.client || GameServer.server) return;
        if ("false".equalsIgnoreCase(System.getProperty("pzo.companionUi", "").trim())) return;
        if (LuaModGuard.shouldStandDown()) return;
        KahluaTable env = LuaManager.env;
        if (env == null) return;
        if (env.rawget("MPOptim") != null || modActive()) return;

        ChooseGameInfo.Mod mod = ChooseGameInfo.getAvailableModDetails(LuaModGuard.MOD_ID);
        File root = mod == null ? null : luaRoot(mod);
        if (root == null) {
            PZOLogger.info("[LuaCompanion] server has no PZO Lua mod and it isn't installed here, no panel this session");
            return;
        }
        File listFile = new File(root, LIST_FILE);
        if (!listFile.isFile()) {
            PZOLogger.info("[LuaCompanion] installed PZO Lua mod predates the companion list, update it from the Workshop");
            return;
        }

        Map<String, String> byName = new HashMap<>();
        List<String> order = new ArrayList<>();
        for (String line : Files.readAllLines(listFile.toPath(), StandardCharsets.UTF_8)) {
            String rel = line.trim();
            if (rel.isEmpty() || rel.startsWith("#")) continue;
            // Only shared/ and client/ Lua of this mod; a bad line can't reach outside it
            if (rel.contains("..") || !(rel.startsWith("media/lua/shared/") || rel.startsWith("media/lua/client/"))
                || !rel.endsWith(".lua")) continue;
            File f = new File(root, rel);
            if (!f.isFile()) continue;
            String abs = f.getCanonicalPath().replace('\\', '/');
            String name = rel.substring(rel.lastIndexOf('/') + 1, rel.length() - 4);
            byName.put(name, abs);
            order.add(abs);
        }
        if (order.isEmpty()) return;

        String dir = root.getCanonicalPath().replace('\\', '/');
        env.rawset("PZOCompanion", Boolean.TRUE);
        env.rawset("PZOCompanionDir", dir);
        int keys = loadTranslations(root);

        // The files require each other by name, which the game can't resolve for a mod that isn't loaded
        Object origRequire = env.rawget("require");
        env.rawset("require", requireShim(byName, origRequire));
        int ran = 0;
        try {
            for (String abs : order) {
                try {
                    LuaManager.RunLua(abs);
                    ran++;
                } catch (Throwable t) {
                    PZOLogger.warn("[LuaCompanion] " + abs.substring(abs.lastIndexOf('/') + 1) + " failed: " + t);
                }
            }
        } finally {
            env.rawset("require", origRequire);
        }
        activeThisState = true;
        PZOLogger.success("[LuaCompanion] server has no PZO Lua mod: loaded the panel myself (" + ran + " files, "
            + keys + " translation keys) from " + dir);
    }

    private static boolean modActive() {
        for (String id : ZomboidFileSystem.instance.getModIDs()) {
            if (id != null && id.replaceFirst("^\\\\+", "").equals(LuaModGuard.MOD_ID)) return true;
        }
        return false;
    }

    // The Build 42 folder (42/) of the mod, else its common folder
    private static File luaRoot(ChooseGameInfo.Mod mod) {
        for (String d : new String[] { mod.getVersionDir(), mod.getCommonDir() }) {
            if (d == null) continue;
            File f = new File(d);
            if (new File(f, LIST_FILE).isFile() || new File(f, "media/lua").isDirectory()) return f;
        }
        return null;
    }

    private static Object requireShim(Map<String, String> byName, Object orig) {
        return (JavaFunction) (LuaCallFrame frame, int nArgs) -> {
            Object arg = nArgs > 0 ? frame.get(0) : null;
            String name = arg == null ? "" : arg.toString().replace('\\', '/');
            if (name.endsWith(".lua")) name = name.substring(0, name.length() - 4);
            String abs = byName.get(name);
            Object ret;
            if (abs != null) {
                ret = LuaManager.RunLua(abs);
            } else if (orig instanceof JavaFunction jf) {
                return jf.call(frame, nArgs);
            } else {
                ret = LuaManager.GlobalObject.require(arg == null ? "" : arg.toString());
            }
            frame.push(ret);
            return 1;
        };
    }

    // The game only reads Translate/ of loaded mods. Same parser and language stack as Translator.loadFiles: the
    // default language first, the player's last so it wins.
    private static int loadTranslations(File root) {
        int[] count = { 0 };
        try {
            Method fill = Translator.class.getDeclaredMethod("tryFillMapFromFile", String.class, String.class, Map.class,
                Language.class, Function.class);
            fill.setAccessible(true);
            Method fixer = Translator.class.getDeclaredMethod("formatFixer", String.class);
            fixer.setAccessible(true);
            Function<String, String> fix = s -> {
                try {
                    return (String) fixer.invoke(null, s);
                } catch (Exception e) {
                    return s;
                }
            };
            String dir = root.getPath();
            Translator.forLanguageStack(lang -> {
                for (String name : TRANSLATE_FILES) {
                    Map<String, String> map = Translator.BY_NAME.get(name);
                    if (map == null) continue;
                    try {
                        Map<String, String> tmp = new HashMap<>();
                        fill.invoke(null, dir, name, tmp, lang, fix);
                        for (Map.Entry<String, String> e : tmp.entrySet()) {
                            // an empty string in a later language doesn't hide the English one
                            if (e.getValue() == null || (e.getValue().isEmpty() && map.containsKey(e.getKey()))) continue;
                            map.put(e.getKey(), e.getValue());
                            count[0]++;
                        }
                    } catch (Exception e) {
                        PZOLogger.warn("[LuaCompanion] " + name + ".json (" + lang + ") not read: " + e);
                    }
                }
            });
        } catch (Throwable t) {
            PZOLogger.warn("[LuaCompanion] translations not loaded, the panel shows English: " + t);
        }
        return count[0];
    }
}
