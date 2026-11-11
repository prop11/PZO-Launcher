package com.pzoptimizer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The mod lists a crash could have been running with, for the report: the main menu's list (Zomboid/mods/default.txt),
 * the most recently played save's own list (Saves/mode/save/mods.txt) and each server config's Mods= line. Which one
 * was live isn't recorded anywhere, so all three are given and labelled.
 */
final class ModLists {

    private ModLists() {}

    static String describe() {
        StringBuilder b = new StringBuilder();
        try {
            File zomboid = PZOEngineBridge.getZomboidDir();

            File def = new File(zomboid, "mods" + File.separator + "default.txt");
            b.append("== Main menu list (mods/default.txt) ==\n");
            append(b, def.isFile() ? activeModsFile(def) : null);

            File newest = newestSaveList(new File(zomboid, "Saves"));
            b.append("\n== Most recently played save");
            if (newest != null) {
                File save = newest.getParentFile();
                b.append(" (").append(save.getParentFile().getName()).append('/').append(save.getName()).append(", ")
                    .append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(newest.lastModified()))).append(')');
            }
            b.append(" ==\n");
            append(b, newest == null ? null : activeModsFile(newest));

            File[] inis = new File(zomboid, "Server").listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".ini"));
            if (inis != null) {
                for (File ini : inis) {
                    List<String> mods = serverMods(ini);
                    if (mods == null) continue;
                    b.append("\n== Server config ").append(ini.getName()).append(" (Mods=) ==\n");
                    append(b, mods);
                }
            }
        } catch (Throwable t) {
            b.append("\n[mod lists incomplete: ").append(t).append("]\n");
        }
        return b.toString();
    }

    private static void append(StringBuilder b, List<String> mods) {
        if (mods == null) {
            b.append("(not found)\n");
        } else if (mods.isEmpty()) {
            b.append("(no mods)\n");
        } else {
            b.append(mods.size()).append(" mods: ").append(String.join(", ", mods)).append('\n');
        }
    }

    // "mod = id," lines inside the mods { } block, the format ActiveModsFile writes
    static List<String> activeModsFile(File f) {
        List<String> out = new ArrayList<>();
        try {
            boolean inMods = false;
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String t = line.replace("﻿", "").trim();
                if (t.equals("mods")) inMods = true;
                else if (t.equals("}")) inMods = false;
                if (!inMods) continue;
                int eq = t.indexOf('=');
                if (eq < 0 || !t.substring(0, eq).trim().equalsIgnoreCase("mod")) continue;
                String id = t.substring(eq + 1).replace("\\", "").trim();
                if (id.endsWith(",")) id = id.substring(0, id.length() - 1).trim();
                if (!id.isEmpty()) out.add(id);
            }
        } catch (Throwable t) {
            return null;
        }
        return out;
    }

    private static File newestSaveList(File saves) {
        File best = null;
        File[] modes = saves.listFiles(File::isDirectory);
        if (modes == null) return null;
        for (File mode : modes) {
            File[] games = mode.listFiles(File::isDirectory);
            if (games == null) continue;
            for (File game : games) {
                File list = new File(game, "mods.txt");
                if (list.isFile() && (best == null || list.lastModified() > best.lastModified())) best = list;
            }
        }
        return best;
    }

    // null when the file has no Mods= line
    private static List<String> serverMods(File ini) {
        try {
            for (String line : Files.readAllLines(ini.toPath(), StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (!t.regionMatches(true, 0, "Mods=", 0, 5)) continue;
                List<String> out = new ArrayList<>();
                for (String id : t.substring(5).split(";")) {
                    id = id.replace("\\", "").trim();
                    if (!id.isEmpty()) out.add(id);
                }
                return out;
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
