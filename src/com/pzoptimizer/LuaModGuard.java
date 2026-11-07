package com.pzoptimizer;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The Java engine needs the Lua mod (Workshop 3787481250, mod id MPOptimizer, folder MPOptimiser): it carries the
 * settings screen and the Lua half of most features. Without it PZO stands down for the launch the same way it does for
 * PZ_Optimization, and a small notice is put on the main menu through the game's own Lua.
 *
 * Decided at startup from files, before any patch goes in, since patches can't be taken out later. Turning PZO off
 * by mistake costs a working setup its whole launch, so it only stands down when every check agrees the mod isn't
 * there:
 *  - not listed in Zomboid/mods/default.txt (the main menu's list; a save or a server can still turn it on, so being
 *    unlisted alone is not enough)
 *  - and not found as a Workshop item in any Steam library (libraryfolders.vdf), nor as a local copy under
 *    Zomboid/mods or Zomboid/Workshop
 *  - and at least one Workshop folder was there to look in
 * Anything else runs normally; if the mod's Lua then never loads, the main menu says so (noticeLua "notloaded").
 * Anything I can't read counts as "fine".
 *
 * Off with -Dpzo.requireLua=false or "require_lua_mod":false in pzo_config.json.
 */
public final class LuaModGuard {

    public static final String WORKSHOP_ID = "3787481250";
    static final String MOD_ID = "MPOptimizer";
    static final String MOD_FOLDER = "MPOptimiser";
    static final String WORKSHOP_URL = "https://steamcommunity.com/sharedfiles/filedetails/?id=" + WORKSHOP_ID;

    public enum State { OK, NOT_INSTALLED, NOT_ENABLED }

    private static volatile State cached;
    private static boolean reported;

    private LuaModGuard() {}

    /** True when PZO should stay off for this launch. */
    public static boolean shouldStandDown() {
        return state() == State.NOT_INSTALLED;
    }

    // Runs before the game window, so it gets a hard limit. Past it (a sleeping drive or a network library in
    // libraryfolders.vdf can take seconds to answer) the answer is "fine": a slow disk must never turn PZO off.
    static final long CHECK_LIMIT_MS = 2000;

    public static synchronized State state() {
        if (cached != null) return cached;
        State s = State.OK;
        try {
            if (isRequired()) s = detectWithin(CHECK_LIMIT_MS);
        } catch (Throwable t) {
            PZOLogger.warn("[LuaModGuard] check failed, PZO stays on: " + t);
        }
        cached = s;
        return s;
    }

    private static State detectWithin(long limitMs) throws InterruptedException {
        long start = System.nanoTime();
        State[] result = { null };
        Thread t = new Thread(() -> {
            try {
                result[0] = detect();
            } catch (Throwable e) {
                PZOLogger.warn("[LuaModGuard] check failed, PZO stays on: " + e);
                result[0] = State.OK;
            }
        }, "PZO-LuaModCheck");
        t.setDaemon(true);
        t.start();
        t.join(limitMs);
        long ms = (System.nanoTime() - start) / 1_000_000;
        State s = result[0];
        if (s == null) {
            PZOLogger.warn("[LuaModGuard] mod check still running after " + ms + " ms (slow disk?), PZO stays on");
            return State.OK;
        }
        if (ms >= 200) PZOLogger.info("[LuaModGuard] mod check took " + ms + " ms");
        return s;
    }

    private static boolean isRequired() {
        String prop = System.getProperty("pzo.requireLua");
        if (prop != null && prop.trim().equalsIgnoreCase("false")) return false;
        return PZOConfig.isLuaModRequired();
    }

    private static State detect() {
        File zomboid = PZOEngineBridge.getZomboidDir();
        Boolean enabled = isEnabled(new File(zomboid, "mods" + File.separator + "default.txt"));
        // Listed means the player has it, wherever it is installed; unreadable means I can't tell
        if (enabled == null || enabled) return State.OK;

        Set<File> workshopDirs = workshopItemDirs();
        boolean installed = false;
        for (File dir : workshopDirs) {
            if (holdsMod(new File(dir, WORKSHOP_ID + File.separator + "mods"))) {
                installed = true;
                break;
            }
        }
        if (!installed) installed = holdsMod(new File(zomboid, "mods"));
        if (!installed) {
            File[] uploads = new File(zomboid, "Workshop").listFiles(File::isDirectory);
            if (uploads != null) {
                for (File up : uploads) {
                    if (holdsMod(new File(up, "Contents" + File.separator + "mods"))) {
                        installed = true;
                        break;
                    }
                }
            }
        }

        // Without a Workshop folder to look in I can't tell "not installed" apart from "installed somewhere else"
        if (!installed && !workshopDirs.isEmpty()) {
            PZOLogger.info("[LuaModGuard] mod " + MOD_ID + " not in default.txt and not found in " + workshopDirs);
            return State.NOT_INSTALLED;
        }
        // Installed but not on the main menu's list: a save or server may still turn it on, so PZO runs
        if (installed) PZOLogger.info("[LuaModGuard] mod installed but not in default.txt; PZO runs, a save or server may enable it");
        return State.OK;
    }

    // null when the list exists but can't be read
    private static Boolean isEnabled(File defaultTxt) {
        if (!defaultTxt.exists()) return false;
        try {
            for (String line : Files.readAllLines(defaultTxt.toPath(), StandardCharsets.UTF_8)) {
                String t = line.replace("\uFEFF", "").trim();
                int eq = t.indexOf('=');
                if (eq < 0 || !t.substring(0, eq).trim().equalsIgnoreCase("mod")) continue;
                String id = t.substring(eq + 1).replace("\\", "").trim();
                if (id.endsWith(",")) id = id.substring(0, id.length() - 1).trim();
                if (id.equalsIgnoreCase(MOD_ID)) return true;
            }
            return false;
        } catch (IOException | RuntimeException e) {
            PZOLogger.warn("[LuaModGuard] could not read " + defaultTxt + ": " + e);
            return null;
        }
    }

    // <library>/steamapps/workshop/content/108600 for every Steam library I can find: the one above the game folder
    // (any depth, so the macOS .app layout works too), every library in libraryfolders.vdf, and Steam's usual homes
    private static Set<File> workshopItemDirs() {
        Set<File> steamapps = new LinkedHashSet<>();
        for (File gameDir : IncompatibleModGuard.candidateGameDirs()) {
            for (File f = gameDir; f != null; f = f.getParentFile()) {
                if (f.getName().equalsIgnoreCase("steamapps")) {
                    steamapps.add(f);
                    break;
                }
            }
        }
        String home = System.getProperty("user.home", "");
        String[] roots = {
            System.getenv("ProgramFiles(x86)") == null ? null : System.getenv("ProgramFiles(x86)") + File.separator + "Steam",
            System.getenv("ProgramFiles") == null ? null : System.getenv("ProgramFiles") + File.separator + "Steam",
            home + "/.steam/steam", home + "/.local/share/Steam", home + "/.var/app/com.valvesoftware.Steam/.local/share/Steam",
            home + "/Library/Application Support/Steam",
        };
        for (String r : roots) {
            if (r == null) continue;
            File sa = new File(r, "steamapps");
            if (sa.isDirectory()) steamapps.add(sa);
        }
        for (File sa : new java.util.ArrayList<>(steamapps)) {
            for (File lib : libraryFolders(new File(sa, "libraryfolders.vdf"))) {
                File more = new File(lib, "steamapps");
                if (more.isDirectory()) steamapps.add(more);
            }
        }
        Set<File> dirs = new LinkedHashSet<>();
        for (File sa : steamapps) {
            File ws = new File(sa, "workshop" + File.separator + "content" + File.separator + "108600");
            if (ws.isDirectory()) dirs.add(ws);
        }
        return dirs;
    }

    // The "path" entries of Steam's libraryfolders.vdf
    private static Set<File> libraryFolders(File vdf) {
        Set<File> out = new LinkedHashSet<>();
        if (!vdf.isFile()) return out;
        try {
            String text = Files.readString(vdf.toPath(), StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"path\"\\s+\"([^\"]+)\"").matcher(text);
            while (m.find()) out.add(new File(m.group(1).replace("\\\\", "\\")));
        } catch (Throwable ignored) {}
        return out;
    }

    // A mods folder holding the mod, by folder name or by the id in any mod.info one level down (B41 root or 42/)
    private static boolean holdsMod(File modsDir) {
        File[] mods = modsDir.listFiles(File::isDirectory);
        if (mods == null) return false;
        for (File mod : mods) {
            if (mod.getName().equalsIgnoreCase(MOD_FOLDER) && (new File(mod, "mod.info").isFile() || new File(mod, "42" + File.separator + "mod.info").isFile())) {
                return true;
            }
            if (modInfoIdMatches(new File(mod, "mod.info")) || modInfoIdMatches(new File(mod, "42" + File.separator + "mod.info"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean modInfoIdMatches(File info) {
        if (!info.isFile()) return false;
        try {
            for (String line : Files.readAllLines(info.toPath(), StandardCharsets.UTF_8)) {
                String t = line.replace("\uFEFF", "").trim();
                if (t.toLowerCase(Locale.ROOT).startsWith("id=") && t.substring(3).trim().equalsIgnoreCase(MOD_ID)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** Logs why PZO is off, writes the status file the Lua mod reads and queues the main menu notice. Once per launch. */
    public static synchronized void reportAndStandDown() {
        if (reported) return;
        reported = true;
        State s = state();
        String reason = s == State.NOT_INSTALLED ? "lua_mod_missing" : "lua_mod_disabled";
        PZOLogger.warn("[PZO] Project Zomboid Optimiser mod (Workshop " + WORKSHOP_ID + ") is "
            + (s == State.NOT_INSTALLED ? "not installed" : "not enabled in Zomboid/mods/default.txt")
            + ". PZO is DISABLED for this launch: no patches, governors or Java mod loading.");
        try {
            TelemetryReporter.writeStatusFile(String.format(
                "{\"optimized\":false,\"disabled\":\"%s\",\"version\":\"%s\"}", reason, UpdateChecker.CURRENT_VERSION));
        } catch (Throwable ignored) {}
        startNoticeInjector(s == State.NOT_INSTALLED ? "missing" : "disabled");
    }

    // With PZO off nothing else runs Lua for me, so this waits for each new Lua state (the game rebuilds it when the mod
    // list changes) and adds the notice to it on the game thread
    private static void startNoticeInjector(String reason) {
        EngineThreadGovernor.initializeQueueOnly("Lua mod notice");
        Thread t = new Thread(() -> {
            Object injected = null;
            String code = noticeLua(reason);
            while (true) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    Object env = Class.forName("zombie.Lua.LuaManager").getField("env").get(null);
                    if (env == null || env == injected || !PZOEngineBridge.areLuaEventsRegistered(env)) continue;
                    if (Boolean.TRUE.equals(GameThreadQueue.runOnGameThread(() -> PZOEngineBridge.runLua(env, code, "PZOLuaModNotice")))) {
                        injected = env;
                        PZOLogger.info("[LuaModGuard] main menu notice added (" + reason + ")");
                    }
                } catch (Throwable ignored) {}
            }
        }, "PZO-LuaModNotice");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Lua that shows the notice on the main menu. "missing" and "disabled" are for a launch where PZO stood down;
     * "notloaded" goes in on normal launches and only shows when the mod's Lua never defined MPOptim.Version, i.e. the
     * mod is on but failed to load.
     */
    static String noticeLua(String reason) {
        StringBuilder strings = new StringBuilder("{");
        for (String[] row : TEXT) {
            strings.append(row[0]).append("={title=").append(luaQuote(row[1]))
                .append(",body=").append(luaQuote(row["missing".equals(reason) ? 2 : "disabled".equals(reason) ? 3 : 4]))
                .append(",workshop=").append(luaQuote(row[5])).append(",close=").append(luaQuote(row[6])).append("},");
        }
        strings.append("}");
        return "local REASON = " + luaQuote(reason) + "\n"
            + "local STRINGS = " + strings + "\n"
            + "local function pzoLuaModNotice()\n"
            + "    local ms = MainScreen and MainScreen.instance\n"
            + "    if not ms or ms.inGame or ms.pzoLuaModNoticeShown or not ISModalRichText then return end\n"
            + "    if REASON == 'notloaded' and MPOptim and MPOptim.Version then return end\n"
            + "    ms.pzoLuaModNoticeShown = true\n"
            + "    local lang = 'EN'\n"
            + "    pcall(function() lang = Translator.getLanguage():name() end)\n"
            + "    local s = STRINGS[lang] or STRINGS.EN\n"
            + "    local body = string.gsub(s.body, '\\n', ' <LINE> ')\n"
            + "    local text = ' <CENTRE> <SIZE:medium> <RGB:1,0.8,0.3> ' .. s.title .. ' <LINE> <LINE> <LEFT> <SIZE:small> <RGB:0.9,0.9,0.9> ' .. body\n"
            + "    local sw, sh = getCore():getScreenWidth(), getCore():getScreenHeight()\n"
            + "    local w, h = math.min(620, sw - 40), math.min(280, sh - 40)\n"
            + "    local modal = ISModalRichText:new((sw - w) / 2, (sh - h) / 2, w, h, text, true, nil, function(_, button)\n"
            + "        if button and button.internal == 'YES' and openUrl then openUrl(" + luaQuote(WORKSHOP_URL) + ") end\n"
            + "    end)\n"
            + "    modal.yesText = s.workshop\n"
            + "    modal.noText = s.close\n"
            + "    modal:initialise()\n"
            + "    local tm = getTextManager()\n"
            + "    for _, b in ipairs({ modal.yes, modal.no }) do\n"
            + "        if b then b:setWidth(math.max(100, tm:MeasureStringX(UIFont.Small, b.title or '') + 24)) end\n"
            + "    end\n"
            + "    if modal.yes and modal.no then\n"
            + "        modal.yes:setX(w / 2 - modal.yes:getWidth() - 5)\n"
            + "        modal.no:setX(w / 2 + 5)\n"
            + "    end\n"
            + "    modal.destroyOnClick = true\n"
            + "    modal:addToUIManager()\n"
            + "    modal:setAlwaysOnTop(true)\n"
            + "end\n"
            + "if MainScreen and MainScreen.instance and not MainScreen.instance.inGame then pzoLuaModNotice() end\n"
            + "if Events and Events.OnMainMenuEnter then Events.OnMainMenuEnter.Add(pzoLuaModNotice) end\n";
    }

    private static String luaQuote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                default -> b.append(c);
            }
        }
        return b.append('"').toString();
    }

    // Language code, title, missing, disabled, notloaded, Workshop button, close button. Kahlua reads the Lua source as
    // Java strings, so these go in as they are
    private static final String[][] TEXT = {
        {"EN", "Project Zomboid Optimiser: mod missing", "The PZO Java engine is installed, but the Project Zomboid Optimiser mod from the Steam Workshop is not. The engine needs the mod, so it is switched off for this launch and the game runs without it.\n\nSubscribe to the mod on the Steam Workshop, turn it on under Mods, then restart the game.", "The PZO Java engine is installed, but the Project Zomboid Optimiser mod is not turned on. The engine needs the mod, so it is switched off for this launch and the game runs without it.\n\nTurn on Project Zomboid Optimiser under Mods, then restart the game.", "The Project Zomboid Optimiser mod is turned on, but its Lua did not load, so its settings are missing. The PZO Java engine is still running.\n\nLook in console.txt for errors from MPOptimiser, or unsubscribe from the mod and subscribe again. If it keeps happening, send console.txt with a bug report.", "Open Workshop page", "Close"},
        {"DE", "Project Zomboid Optimiser: Mod fehlt", "Die PZO-Java-Engine ist installiert, aber die Mod Project Zomboid Optimiser aus dem Steam Workshop nicht. Die Engine braucht die Mod, deshalb ist sie f\u00fcr diesen Start ausgeschaltet und das Spiel l\u00e4uft ohne sie.\n\nAbonniere die Mod im Steam Workshop, aktiviere sie unter Mods und starte das Spiel dann neu.", "Die PZO-Java-Engine ist installiert, aber die Mod Project Zomboid Optimiser ist nicht aktiviert. Die Engine braucht die Mod, deshalb ist sie f\u00fcr diesen Start ausgeschaltet und das Spiel l\u00e4uft ohne sie.\n\nAktiviere Project Zomboid Optimiser unter Mods und starte das Spiel dann neu.", "Die Mod Project Zomboid Optimiser ist aktiviert, aber ihr Lua-Code wurde nicht geladen, deshalb fehlen ihre Einstellungen. Die PZO-Java-Engine l\u00e4uft weiter.\n\nSuche in console.txt nach Fehlern von MPOptimiser oder deabonniere die Mod und abonniere sie erneut. Wenn es weiter passiert, schick console.txt mit einem Fehlerbericht.", "Workshop-Seite \u00f6ffnen", "Schlie\u00dfen"},
        {"ES", "Project Zomboid Optimiser: falta el mod", "El motor Java de PZO est\u00e1 instalado, pero el mod Project Zomboid Optimiser del Steam Workshop no. El motor necesita el mod, as\u00ed que est\u00e1 desactivado en este inicio y el juego funciona sin \u00e9l.\n\nSuscr\u00edbete al mod en el Steam Workshop, act\u00edvalo en Mods y reinicia el juego.", "El motor Java de PZO est\u00e1 instalado, pero el mod Project Zomboid Optimiser no est\u00e1 activado. El motor necesita el mod, as\u00ed que est\u00e1 desactivado en este inicio y el juego funciona sin \u00e9l.\n\nActiva Project Zomboid Optimiser en Mods y reinicia el juego.", "El mod Project Zomboid Optimiser est\u00e1 activado, pero su c\u00f3digo Lua no se carg\u00f3, as\u00ed que faltan sus ajustes. El motor Java de PZO sigue funcionando.\n\nBusca errores de MPOptimiser en console.txt, o cancela la suscripci\u00f3n al mod y vuelve a suscribirte. Si sigue pasando, env\u00eda console.txt con un informe de error.", "Abrir la p\u00e1gina del Workshop", "Cerrar"},
        {"FR", "Project Zomboid Optimiser : mod manquant", "Le moteur Java PZO est install\u00e9, mais pas le mod Project Zomboid Optimiser du Steam Workshop. Le moteur a besoin du mod, il est donc d\u00e9sactiv\u00e9 pour ce lancement et le jeu tourne sans lui.\n\nAbonnez-vous au mod sur le Steam Workshop, activez-le dans Mods, puis red\u00e9marrez le jeu.", "Le moteur Java PZO est install\u00e9, mais le mod Project Zomboid Optimiser n'est pas activ\u00e9. Le moteur a besoin du mod, il est donc d\u00e9sactiv\u00e9 pour ce lancement et le jeu tourne sans lui.\n\nActivez Project Zomboid Optimiser dans Mods, puis red\u00e9marrez le jeu.", "Le mod Project Zomboid Optimiser est activ\u00e9, mais son code Lua ne s'est pas charg\u00e9, ses r\u00e9glages sont donc absents. Le moteur Java PZO fonctionne toujours.\n\nCherchez les erreurs de MPOptimiser dans console.txt, ou d\u00e9sabonnez-vous du mod puis r\u00e9abonnez-vous. Si cela continue, envoyez console.txt avec un rapport de bug.", "Ouvrir la page du Workshop", "Fermer"},
        {"RU", "Project Zomboid Optimiser: \u043d\u0435\u0442 \u043c\u043e\u0434\u0430", "Java-\u0434\u0432\u0438\u0436\u043e\u043a PZO \u0443\u0441\u0442\u0430\u043d\u043e\u0432\u043b\u0435\u043d, \u043d\u043e \u043c\u043e\u0434 Project Zomboid Optimiser \u0438\u0437 Steam Workshop \u043d\u0435 \u0443\u0441\u0442\u0430\u043d\u043e\u0432\u043b\u0435\u043d. \u0414\u0432\u0438\u0436\u043a\u0443 \u043d\u0443\u0436\u0435\u043d \u044d\u0442\u043e\u0442 \u043c\u043e\u0434, \u043f\u043e\u044d\u0442\u043e\u043c\u0443 \u043f\u0440\u0438 \u044d\u0442\u043e\u043c \u0437\u0430\u043f\u0443\u0441\u043a\u0435 \u043e\u043d \u0432\u044b\u043a\u043b\u044e\u0447\u0435\u043d \u0438 \u0438\u0433\u0440\u0430 \u0440\u0430\u0431\u043e\u0442\u0430\u0435\u0442 \u0431\u0435\u0437 \u043d\u0435\u0433\u043e.\n\n\u041f\u043e\u0434\u043f\u0438\u0448\u0438\u0442\u0435\u0441\u044c \u043d\u0430 \u043c\u043e\u0434 \u0432 Steam Workshop, \u0432\u043a\u043b\u044e\u0447\u0438\u0442\u0435 \u0435\u0433\u043e \u0432 \u0440\u0430\u0437\u0434\u0435\u043b\u0435 Mods \u0438 \u043f\u0435\u0440\u0435\u0437\u0430\u043f\u0443\u0441\u0442\u0438\u0442\u0435 \u0438\u0433\u0440\u0443.", "Java-\u0434\u0432\u0438\u0436\u043e\u043a PZO \u0443\u0441\u0442\u0430\u043d\u043e\u0432\u043b\u0435\u043d, \u043d\u043e \u043c\u043e\u0434 Project Zomboid Optimiser \u043d\u0435 \u0432\u043a\u043b\u044e\u0447\u0451\u043d. \u0414\u0432\u0438\u0436\u043a\u0443 \u043d\u0443\u0436\u0435\u043d \u044d\u0442\u043e\u0442 \u043c\u043e\u0434, \u043f\u043e\u044d\u0442\u043e\u043c\u0443 \u043f\u0440\u0438 \u044d\u0442\u043e\u043c \u0437\u0430\u043f\u0443\u0441\u043a\u0435 \u043e\u043d \u0432\u044b\u043a\u043b\u044e\u0447\u0435\u043d \u0438 \u0438\u0433\u0440\u0430 \u0440\u0430\u0431\u043e\u0442\u0430\u0435\u0442 \u0431\u0435\u0437 \u043d\u0435\u0433\u043e.\n\n\u0412\u043a\u043b\u044e\u0447\u0438\u0442\u0435 Project Zomboid Optimiser \u0432 \u0440\u0430\u0437\u0434\u0435\u043b\u0435 Mods \u0438 \u043f\u0435\u0440\u0435\u0437\u0430\u043f\u0443\u0441\u0442\u0438\u0442\u0435 \u0438\u0433\u0440\u0443.", "\u041c\u043e\u0434 Project Zomboid Optimiser \u0432\u043a\u043b\u044e\u0447\u0451\u043d, \u043d\u043e \u0435\u0433\u043e Lua-\u043a\u043e\u0434 \u043d\u0435 \u0437\u0430\u0433\u0440\u0443\u0437\u0438\u043b\u0441\u044f, \u043f\u043e\u044d\u0442\u043e\u043c\u0443 \u0435\u0433\u043e \u043d\u0430\u0441\u0442\u0440\u043e\u0435\u043a \u043d\u0435\u0442. Java-\u0434\u0432\u0438\u0436\u043e\u043a PZO \u043f\u0440\u043e\u0434\u043e\u043b\u0436\u0430\u0435\u0442 \u0440\u0430\u0431\u043e\u0442\u0430\u0442\u044c.\n\n\u041f\u043e\u0438\u0449\u0438\u0442\u0435 \u043e\u0448\u0438\u0431\u043a\u0438 MPOptimiser \u0432 console.txt \u0438\u043b\u0438 \u043e\u0442\u043f\u0438\u0448\u0438\u0442\u0435\u0441\u044c \u043e\u0442 \u043c\u043e\u0434\u0430 \u0438 \u043f\u043e\u0434\u043f\u0438\u0448\u0438\u0442\u0435\u0441\u044c \u0441\u043d\u043e\u0432\u0430. \u0415\u0441\u043b\u0438 \u044d\u0442\u043e \u043f\u043e\u0432\u0442\u043e\u0440\u044f\u0435\u0442\u0441\u044f, \u043e\u0442\u043f\u0440\u0430\u0432\u044c\u0442\u0435 console.txt \u0432\u043c\u0435\u0441\u0442\u0435 \u0441 \u043e\u0442\u0447\u0451\u0442\u043e\u043c \u043e\u0431 \u043e\u0448\u0438\u0431\u043a\u0435.", "\u041e\u0442\u043a\u0440\u044b\u0442\u044c \u0441\u0442\u0440\u0430\u043d\u0438\u0446\u0443 \u0432 Workshop", "\u0417\u0430\u043a\u0440\u044b\u0442\u044c"},
        {"CN", "Project Zomboid Optimiser\uff1a\u7f3a\u5c11\u6a21\u7ec4", "PZO Java \u5f15\u64ce\u5df2\u5b89\u88c5\uff0c\u4f46\u6ca1\u6709\u5b89\u88c5 Steam \u521b\u610f\u5de5\u574a\u91cc\u7684 Project Zomboid Optimiser \u6a21\u7ec4\u3002\u5f15\u64ce\u9700\u8981\u8fd9\u4e2a\u6a21\u7ec4\uff0c\u6240\u4ee5\u672c\u6b21\u542f\u52a8\u65f6\u5f15\u64ce\u5df2\u5173\u95ed\uff0c\u6e38\u620f\u5728\u6ca1\u6709\u5b83\u7684\u60c5\u51b5\u4e0b\u8fd0\u884c\u3002\n\n\u8bf7\u5728 Steam \u521b\u610f\u5de5\u574a\u8ba2\u9605\u8be5\u6a21\u7ec4\uff0c\u5728\u6a21\u7ec4\u83dc\u5355\u4e2d\u542f\u7528\u5b83\uff0c\u7136\u540e\u91cd\u542f\u6e38\u620f\u3002", "PZO Java \u5f15\u64ce\u5df2\u5b89\u88c5\uff0c\u4f46 Project Zomboid Optimiser \u6a21\u7ec4\u6ca1\u6709\u542f\u7528\u3002\u5f15\u64ce\u9700\u8981\u8fd9\u4e2a\u6a21\u7ec4\uff0c\u6240\u4ee5\u672c\u6b21\u542f\u52a8\u65f6\u5f15\u64ce\u5df2\u5173\u95ed\uff0c\u6e38\u620f\u5728\u6ca1\u6709\u5b83\u7684\u60c5\u51b5\u4e0b\u8fd0\u884c\u3002\n\n\u8bf7\u5728\u6a21\u7ec4\u83dc\u5355\u4e2d\u542f\u7528 Project Zomboid Optimiser\uff0c\u7136\u540e\u91cd\u542f\u6e38\u620f\u3002", "Project Zomboid Optimiser \u6a21\u7ec4\u5df2\u542f\u7528\uff0c\u4f46\u5b83\u7684 Lua \u4ee3\u7801\u6ca1\u6709\u52a0\u8f7d\uff0c\u6240\u4ee5\u5b83\u7684\u8bbe\u7f6e\u4e0d\u53ef\u7528\u3002PZO Java \u5f15\u64ce\u4ecd\u5728\u8fd0\u884c\u3002\n\n\u8bf7\u5728 console.txt \u4e2d\u67e5\u627e\u6765\u81ea MPOptimiser \u7684\u9519\u8bef\uff0c\u6216\u8005\u53d6\u6d88\u8ba2\u9605\u8be5\u6a21\u7ec4\u540e\u91cd\u65b0\u8ba2\u9605\u3002\u5982\u679c\u95ee\u9898\u4ecd\u7136\u51fa\u73b0\uff0c\u8bf7\u5728\u9519\u8bef\u62a5\u544a\u4e2d\u9644\u4e0a console.txt\u3002", "\u6253\u5f00\u521b\u610f\u5de5\u574a\u9875\u9762", "\u5173\u95ed"},
        {"CH", "Project Zomboid Optimiser\uff1a\u7f3a\u5c11\u6a21\u7ec4", "PZO Java \u5f15\u64ce\u5df2\u5b89\u88c5\uff0c\u4f46\u6ca1\u6709\u5b89\u88c5 Steam \u521b\u610f\u5de5\u574a\u91cc\u7684 Project Zomboid Optimiser \u6a21\u7ec4\u3002\u5f15\u64ce\u9700\u8981\u8fd9\u4e2a\u6a21\u7ec4\uff0c\u6240\u4ee5\u672c\u6b21\u542f\u52a8\u65f6\u5f15\u64ce\u5df2\u5173\u95ed\uff0c\u6e38\u620f\u5728\u6ca1\u6709\u5b83\u7684\u60c5\u51b5\u4e0b\u8fd0\u884c\u3002\n\n\u8bf7\u5728 Steam \u521b\u610f\u5de5\u574a\u8ba2\u9605\u8be5\u6a21\u7ec4\uff0c\u5728\u6a21\u7ec4\u83dc\u5355\u4e2d\u542f\u7528\u5b83\uff0c\u7136\u540e\u91cd\u542f\u6e38\u620f\u3002", "PZO Java \u5f15\u64ce\u5df2\u5b89\u88c5\uff0c\u4f46 Project Zomboid Optimiser \u6a21\u7ec4\u6ca1\u6709\u542f\u7528\u3002\u5f15\u64ce\u9700\u8981\u8fd9\u4e2a\u6a21\u7ec4\uff0c\u6240\u4ee5\u672c\u6b21\u542f\u52a8\u65f6\u5f15\u64ce\u5df2\u5173\u95ed\uff0c\u6e38\u620f\u5728\u6ca1\u6709\u5b83\u7684\u60c5\u51b5\u4e0b\u8fd0\u884c\u3002\n\n\u8bf7\u5728\u6a21\u7ec4\u83dc\u5355\u4e2d\u542f\u7528 Project Zomboid Optimiser\uff0c\u7136\u540e\u91cd\u542f\u6e38\u620f\u3002", "Project Zomboid Optimiser \u6a21\u7ec4\u5df2\u542f\u7528\uff0c\u4f46\u5b83\u7684 Lua \u4ee3\u7801\u6ca1\u6709\u52a0\u8f7d\uff0c\u6240\u4ee5\u5b83\u7684\u8bbe\u7f6e\u4e0d\u53ef\u7528\u3002PZO Java \u5f15\u64ce\u4ecd\u5728\u8fd0\u884c\u3002\n\n\u8bf7\u5728 console.txt \u4e2d\u67e5\u627e\u6765\u81ea MPOptimiser \u7684\u9519\u8bef\uff0c\u6216\u8005\u53d6\u6d88\u8ba2\u9605\u8be5\u6a21\u7ec4\u540e\u91cd\u65b0\u8ba2\u9605\u3002\u5982\u679c\u95ee\u9898\u4ecd\u7136\u51fa\u73b0\uff0c\u8bf7\u5728\u9519\u8bef\u62a5\u544a\u4e2d\u9644\u4e0a console.txt\u3002", "\u6253\u5f00\u521b\u610f\u5de5\u574a\u9875\u9762", "\u5173\u95ed"},
        {"TC", "Project Zomboid Optimiser\uff1a\u7f3a\u5c11\u6a21\u7d44", "PZO Java \u5f15\u64ce\u5df2\u5b89\u88dd\uff0c\u4f46\u6c92\u6709\u5b89\u88dd Steam \u5de5\u4f5c\u574a\u88e1\u7684 Project Zomboid Optimiser \u6a21\u7d44\u3002\u5f15\u64ce\u9700\u8981\u9019\u500b\u6a21\u7d44\uff0c\u6240\u4ee5\u672c\u6b21\u555f\u52d5\u6642\u5f15\u64ce\u5df2\u95dc\u9589\uff0c\u904a\u6232\u5728\u6c92\u6709\u5b83\u7684\u60c5\u6cc1\u4e0b\u57f7\u884c\u3002\n\n\u8acb\u5728 Steam \u5de5\u4f5c\u574a\u8a02\u95b1\u8a72\u6a21\u7d44\uff0c\u5728\u6a21\u7d44\u9078\u55ae\u4e2d\u555f\u7528\u5b83\uff0c\u7136\u5f8c\u91cd\u65b0\u555f\u52d5\u904a\u6232\u3002", "PZO Java \u5f15\u64ce\u5df2\u5b89\u88dd\uff0c\u4f46 Project Zomboid Optimiser \u6a21\u7d44\u6c92\u6709\u555f\u7528\u3002\u5f15\u64ce\u9700\u8981\u9019\u500b\u6a21\u7d44\uff0c\u6240\u4ee5\u672c\u6b21\u555f\u52d5\u6642\u5f15\u64ce\u5df2\u95dc\u9589\uff0c\u904a\u6232\u5728\u6c92\u6709\u5b83\u7684\u60c5\u6cc1\u4e0b\u57f7\u884c\u3002\n\n\u8acb\u5728\u6a21\u7d44\u9078\u55ae\u4e2d\u555f\u7528 Project Zomboid Optimiser\uff0c\u7136\u5f8c\u91cd\u65b0\u555f\u52d5\u904a\u6232\u3002", "Project Zomboid Optimiser \u6a21\u7d44\u5df2\u555f\u7528\uff0c\u4f46\u5b83\u7684 Lua \u7a0b\u5f0f\u78bc\u6c92\u6709\u8f09\u5165\uff0c\u6240\u4ee5\u5b83\u7684\u8a2d\u5b9a\u7121\u6cd5\u4f7f\u7528\u3002PZO Java \u5f15\u64ce\u4ecd\u5728\u57f7\u884c\u3002\n\n\u8acb\u5728 console.txt \u4e2d\u5c0b\u627e\u4f86\u81ea MPOptimiser \u7684\u932f\u8aa4\uff0c\u6216\u8005\u53d6\u6d88\u8a02\u95b1\u8a72\u6a21\u7d44\u5f8c\u91cd\u65b0\u8a02\u95b1\u3002\u5982\u679c\u554f\u984c\u4ecd\u7136\u51fa\u73fe\uff0c\u8acb\u5728\u932f\u8aa4\u56de\u5831\u4e2d\u9644\u4e0a console.txt\u3002", "\u958b\u555f\u5de5\u4f5c\u574a\u9801\u9762", "\u95dc\u9589"},
    };
}
