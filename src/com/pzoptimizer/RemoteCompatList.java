package com.pzoptimizer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extra mod exemptions read from compat.json on GitHub, so a mod that breaks under pacing can be let off without a PZO
 * release. It only ever adds to the lists built into the jar. The last good copy is kept next to pzo_config.json and
 * used straight away at launch (and offline); the download runs on its own thread and never holds up the game.
 *
 * compat.json: {"version":1,"pacedExempt":["/3171167894/","/damnlib"],"mapLoadExempt":[]}
 * Entries are matched like the built-in ones: a piece of the lower-cased script path.
 */
public final class RemoteCompatList {

    private static final String URL_TEXT = "https://raw.githubusercontent.com/prop11/PZO-Launcher/main/compat.json";
    private static final String CACHE_FILE = "pzo_compat_cache.json";
    private static final int TIMEOUT_MS = 5000;
    private static final int MAX_BYTES = 64 * 1024;
    private static final int MAX_ENTRIES = 256;
    // A path piece: letters, digits and a few separators, with a slash so it can't match half the world (e.g. "a")
    private static final Pattern ENTRY = Pattern.compile("[a-z0-9/_.\\- ]{3,80}");
    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static volatile String[] paced = new String[0];
    private static volatile String[] mapLoad = new String[0];
    // "online", "cache" or "none"
    private static volatile String source = "none";
    private static volatile String fetchedOn = "";
    private static volatile boolean fetchStarted;

    static {
        if (PZOConfig.isRemoteCompatListEnabled()) {
            loadCache();
            startFetch();
        }
    }

    private RemoteCompatList() {}

    static boolean isPacedExempt(String lowerPath) {
        return matches(paced, lowerPath);
    }

    static boolean isMapLoadExempt(String lowerPath) {
        return matches(mapLoad, lowerPath);
    }

    private static boolean matches(String[] list, String path) {
        for (String p : list) {
            if (path.contains(p)) return true;
        }
        return false;
    }

    /** From the PZO System tab. Off drops the extra entries at once (the cache file stays for when it comes back on). */
    static synchronized void setEnabled(boolean on) {
        PZOConfig.setRemoteCompatListEnabled(on);
        if (on) {
            if (source.equals("none")) loadCache();
            startFetch();
        } else if (paced.length > 0 || mapLoad.length > 0) {
            apply(new String[0], new String[0], "none", "");
        }
    }

    /**
     * For the setting's status line: on=1;paced=2;mapLoad=0;source=online;date=2026-11-15. The counts leave out
     * entries the jar already covers, so the line only claims what the online list really added.
     */
    static String status() {
        boolean on = PZOConfig.isRemoteCompatListEnabled();
        return "on=" + (on ? 1 : 0) + ";paced=" + countNew(paced, LuaEventPatches.EXEMPT_MOD_PATHS)
            + ";mapLoad=" + countNew(mapLoad, MapLoadScriptPacer.LET_ME_DRIVE_PATHS)
            + ";source=" + (on ? source : "none") + ";date=" + fetchedOn;
    }

    // A built-in piece inside an entry means every path the entry matches is already exempt
    private static int countNew(String[] list, String[] builtIn) {
        int n = 0;
        outer:
        for (String e : list) {
            for (String b : builtIn) {
                if (e.contains(b)) continue outer;
            }
            n++;
        }
        return n;
    }

    private static synchronized void startFetch() {
        if (fetchStarted) return;
        fetchStarted = true;
        Thread t = new Thread(RemoteCompatList::fetch, "PZO-CompatList");
        t.setDaemon(true);
        t.start();
    }

    private static void fetch() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(URL_TEXT).openConnection();
            conn.setRequestProperty("User-Agent", "PZO-UpdateChecker");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            if (conn.getResponseCode() != 200) {
                PZOLogger.info("[CompatList] Online list not available (HTTP " + conn.getResponseCode() + "), using " + source);
                return;
            }
            String json;
            try (InputStream in = conn.getInputStream()) {
                json = readCapped(in);
            }
            if (json == null) {
                PZOLogger.warn("[CompatList] Online list is over " + MAX_BYTES + " bytes, ignored");
                return;
            }
            String today = LocalDate.now().toString();
            if (!PZOConfig.isRemoteCompatListEnabled()) return;
            if (parseAndApply(json, "online", today)) {
                writeCache(json, today);
            } else {
                PZOLogger.warn("[CompatList] Online list could not be read, kept " + source);
            }
        } catch (Throwable t) {
            PZOLogger.info("[CompatList] Online list not reached (" + t.getClass().getSimpleName() + "), using " + source);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readCapped(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BYTES) return null;
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void loadCache() {
        try {
            File f = cacheFile();
            if (!f.isFile()) return;
            String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            // First line is the date it was fetched, the rest is compat.json as downloaded
            int nl = text.indexOf('\n');
            if (nl < 0) return;
            parseAndApply(text.substring(nl + 1), "cache", text.substring(0, nl).trim());
        } catch (Throwable t) {
            PZOLogger.info("[CompatList] Cached list not read: " + t);
        }
    }

    private static void writeCache(String json, String date) {
        try {
            Files.writeString(cacheFile().toPath(), date + "\n" + json, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            PZOLogger.info("[CompatList] Cached list not saved: " + t);
        }
    }

    private static File cacheFile() {
        return new File(PZOConfig.getConfigFile().getParentFile(), CACHE_FILE);
    }

    /** False when the text isn't a compat.json this build understands; nothing changes then. */
    private static boolean parseAndApply(String json, String from, String date) {
        if (json == null || !json.trim().startsWith("{")) return false;
        Matcher v = Pattern.compile("\"version\"\\s*:\\s*(\\d+)").matcher(json);
        if (!v.find() || !"1".equals(v.group(1))) return false;
        String[] p = readList(json, "pacedExempt");
        String[] m = readList(json, "mapLoadExempt");
        if (p == null || m == null) return false;
        apply(p, m, from, date);
        PZOLogger.info("[CompatList] " + p.length + " paced and " + m.length + " map-load exemptions from the " + from
            + " list" + (date.isEmpty() ? "" : " (" + date + ")"));
        return true;
    }

    // Missing key = empty list; a key that isn't an array of strings = null (the whole file is refused)
    private static String[] readList(String json, String key) {
        Matcher k = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        if (!k.find()) {
            return json.contains("\"" + key + "\"") ? null : new String[0];
        }
        List<String> out = new ArrayList<>();
        Matcher s = STRING.matcher(k.group(1));
        while (s.find() && out.size() < MAX_ENTRIES) {
            String e = s.group(1).replace('\\', '/').toLowerCase(Locale.ROOT).trim();
            if (ENTRY.matcher(e).matches() && e.indexOf('/') >= 0 && !e.contains("mpoptimiser") && !out.contains(e)) {
                out.add(e);
            } else {
                PZOLogger.warn("[CompatList] Skipped entry in " + key + ": " + e);
            }
        }
        return out.toArray(new String[0]);
    }

    private static void apply(String[] p, String[] m, String from, String date) {
        paced = p;
        mapLoad = m;
        source = from;
        fetchedOn = date;
        LuaEventPatches.clearModCache();
    }
}
