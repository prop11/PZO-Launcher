package com.pzoptimizer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps ProjectZomboid64.json launching through PZO: main class, classpath and JVM arguments.
 *
 * One function, buildTarget, says what the file should contain for this PC; a file needs updating exactly when that
 * differs from what is on disk, so the check and the update can never disagree. The user's own arguments (heap
 * size, Steam flags, other agents) are kept; only what PZO needs is added, and a few broken or superseded arguments are
 * dropped. In compatibility mode (CompatMode) the heap is not pre-touched, starts small and the collector is G1.
 */
public final class ZomboidConfigMigrator {

    public static final String TARGET_JSON_NAME = "ProjectZomboid64.json";
    public static final String TARGET_MAIN_CLASS = "com/pzoptimizer/PZOEntrypoint";
    public static final String TARGET_AGENTLIB = "-agentlib:pzo_native64";

    private static final String PRE_TOUCH = "-XX:+AlwaysPreTouch";

    // Added when missing; PRE_TOUCH is added separately, except in compatibility mode
    private static final String[] PERFORMANCE_ARGS = {
        "-XX:-CreateCoredumpOnCrash",
        "-XX:-OmitStackTraceInFastThrow",
        "-XX:+PerfDisableSharedMem",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseSuperWord",
        "-XX:MaxInlineLevel=15",
        "-XX:InlineSmallCode=2500",
        "-XX:+UseNUMA",
    };

    // ZGC-only tuning, dropped with ZGC in compatibility mode
    private static final String[] ZGC_ONLY_PREFIXES = {"-XX:+ZGenerational", "-XX:-ZGenerational", "-XX:ZAllocationSpikeTolerance",
        "-XX:ZCollectionInterval", "-XX:+ZUncommit", "-XX:-ZUncommit", "-XX:ZUncommitDelay", "-XX:SoftMaxHeapSize"};

    public static void main(String[] args) {
        File targetDir = args != null && args.length > 0 ? new File(args[0]) : new File(".").getAbsoluteFile();
        checkAndMigrateCurrentInstallation(targetDir);
    }

    public static long parseHeapArgMb(String arg) {
        if (arg == null || arg.length() <= 4) return -1;
        try {
            String val = arg.substring(4).trim().toLowerCase(Locale.ROOT);
            long mult = 1;
            if (val.endsWith("g")) {
                mult = 1024;
                val = val.substring(0, val.length() - 1);
            } else if (val.endsWith("m")) {
                val = val.substring(0, val.length() - 1);
            } else if (val.endsWith("k")) {
                return 1;
            }
            return Long.parseLong(val) * mult;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Maximum heap for this PC's memory: 4, 6, 8 or 12 GB. */
    public static int getRecommendedHeapMb() {
        double gb = CompatMode.totalRamGb();
        if (gb <= 0) return 6144;
        if (gb >= 28.0) return 12288;
        if (gb >= 14.0) return 8192;
        if (gb >= 7.0) return 6144;
        return 4096;
    }

    /** Starting heap: half the maximum, or CompatMode's small start in compatibility mode. */
    public static int getRecommendedInitialHeapMb(int maxMb) {
        if (CompatMode.isActive()) return Math.min(CompatMode.COMPAT_INITIAL_HEAP_MB, maxMb);
        if (maxMb >= 12288) return 6144;
        if (maxMb >= 8192) return 4096;
        if (maxMb >= 6144) return 3072;
        return 2048;
    }

    /** Whether the file differs from what buildTarget wants for this PC. */
    public static boolean isMigrationNeeded(File jsonFile) {
        if (jsonFile == null || !jsonFile.exists() || jsonFile.length() == 0) return false;
        try {
            Map<String, Object> current = parseRoot(Files.readString(jsonFile.toPath(), StandardCharsets.UTF_8));
            if (current == null) return false;
            Map<String, Object> target = parseRoot(JsonMini.format(current));
            buildTarget(target, jsonFile.getParentFile());
            return !Objects.equals(current.get("mainClass"), target.get("mainClass"))
                || !Objects.equals(strings(current.get("classpath")), strings(target.get("classpath")))
                // Order of JVM arguments does not matter, so a hand-edited order is not rewritten
                || !new LinkedHashSet<>(strings(current.get("vmArgs"))).equals(new LinkedHashSet<>(strings(target.get("vmArgs"))));
        } catch (Throwable t) {
            PZOLogger.warn("[ZomboidConfigMigrator] Notice checking " + jsonFile.getName() + ": " + t.getMessage());
            return false;
        }
    }

    /** The file content buildTarget wants, or the input unchanged if it is not a JSON object. */
    public static String migrateJsonContent(String originalJson, File gameDir) {
        Map<String, Object> root = parseRoot(originalJson);
        if (root == null) return originalJson;
        buildTarget(root, gameDir);
        return JsonMini.format(root);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseRoot(String json) {
        Object parsed = JsonMini.parse(json);
        return parsed instanceof Map ? (Map<String, Object>) parsed : null;
    }

    private static List<String> strings(Object listObj) {
        List<String> out = new ArrayList<>();
        if (listObj instanceof List) {
            for (Object item : (List<?>) listObj) {
                if (item != null) out.add(item.toString().trim());
            }
        }
        return out;
    }

    private static void buildTarget(Map<String, Object> root, File gameDir) {
        root.put("mainClass", TARGET_MAIN_CLASS);
        root.put("classpath", targetClasspath(strings(root.get("classpath"))));
        root.put("vmArgs", targetVmArgs(strings(root.get("vmArgs")), gameDir));
    }

    // ".", then PZOptimEngine.jar, ahead of the game's jar
    private static List<String> targetClasspath(List<String> current) {
        List<String> cp = new ArrayList<>();
        for (String s : current) {
            if (!cp.contains(s)) cp.add(s);
        }
        if (!cp.contains(".")) cp.add(0, ".");
        if (!cp.contains("PZOptimEngine.jar")) cp.add(cp.indexOf(".") + 1, "PZOptimEngine.jar");
        if (!cp.contains("projectzomboid.jar")) cp.add("projectzomboid.jar");
        return cp;
    }

    private static List<String> targetVmArgs(List<String> rawArgs, File gameDir) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean isWin = os.contains("win");
        boolean isLinux = os.contains("linux");
        boolean compat = CompatMode.isActive();

        // On Linux and macOS, -agentlib:zbNative with no loadable library stops the game from starting (issue #40),
        // and a Windows zbNative.dll left in the folder is not one
        boolean zbInArgs = rawArgs.stream().anyMatch(a -> a.contains("zbNative") || a.contains("ZombieBuddy"));
        boolean zbLibFound = false;
        if (gameDir != null) {
            String lib = isWin ? "zbNative.dll" : isLinux ? "libzbNative.so" : "libzbNative.dylib";
            for (String dir : new String[] {isWin ? "win64" : isLinux ? "linux64" : ".", "natives", "."}) {
                if (new File(new File(gameDir, dir), lib).isFile()) {
                    zbLibFound = true;
                    break;
                }
            }
        }
        boolean zbActive = zbLibFound || (zbInArgs && (isWin || gameDir == null));

        // The user's arguments minus what is rebuilt below, broken, or superseded
        List<String> user = new ArrayList<>();
        for (String arg : rawArgs) {
            // Arguments once joined with spaces by hand are split back up
            for (String tok : arg.split("\\s+")) {
                if (tok.isEmpty() || user.contains(tok)) continue;
                if (tok.startsWith("-Djava.awt.headless")) continue; // PZO needs AWT for its dialogs
                if (tok.startsWith("-Djava.library.path=")) continue;
                if (tok.equals(TARGET_AGENTLIB) || tok.contains("pzo_native64")) continue;
                if (tok.contains("zbNative")) continue;
                if (tok.contains("UseCompactObjectHeaders")) continue; // not a Java 17 option
                if (compat && tok.equals(PRE_TOUCH)) continue;
                if (compat && isZgcOnly(tok)) continue;
                user.add(compat && tok.equals("-XX:+UseZGC") ? "-XX:+UseG1GC" : tok);
            }
        }

        List<String> args = new ArrayList<>();
        args.add(TARGET_AGENTLIB);
        if (zbActive) {
            args.add("-agentlib:zbNative");
            PZOLogger.info("[ZomboidConfigMigrator] Preserved ZombieBuddy bridge (-agentlib:zbNative)");
        }
        args.add("--enable-native-access=ALL-UNNAMED");
        args.add("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED");
        if (isWin) {
            args.add("-Djava.library.path=win64/;.;natives/");
        } else if (isLinux) {
            args.add("-Djava.library.path=linux64/:natives/:.");
        } else {
            args.add("-Djava.library.path=.:ProjectZomboid.app/Contents/MacOS/:ProjectZomboid.app/Contents/Java/");
        }

        // Heap: the user's sizes are kept; the game's stock 3 GB maximum is raised to suit this PC, and in
        // compatibility mode the starting heap is lowered so nothing large is committed up front
        int recMax = getRecommendedHeapMb();
        int recStart = getRecommendedInitialHeapMb(recMax);
        boolean hasXms = false, hasXmx = false;
        for (String arg : user) {
            if (arg.startsWith("-Xms")) {
                hasXms = true;
                long mb = parseHeapArgMb(arg);
                if (compat && mb > recStart) {
                    arg = "-Xms" + recStart + "m";
                } else if (!compat && mb > 0 && mb <= 1800 && recStart > 1800) {
                    arg = "-Xms" + recStart + "m";
                }
            } else if (arg.startsWith("-Xmx")) {
                hasXmx = true;
                long mb = parseHeapArgMb(arg);
                if (mb > 0 && mb <= 3072 && recMax > 3072) arg = "-Xmx" + recMax + "m";
            }
            if (!args.contains(arg)) args.add(arg);
        }
        if (!hasXms) args.add("-Xms" + recStart + "m");
        if (!hasXmx) args.add("-Xmx" + recMax + "m");

        for (String pf : PERFORMANCE_ARGS) {
            if (!args.contains(pf)) args.add(pf);
        }
        if (!compat && !args.contains(PRE_TOUCH)) args.add(PRE_TOUCH);
        return args;
    }

    private static boolean isZgcOnly(String arg) {
        for (String p : ZGC_ONLY_PREFIXES) {
            if (arg.startsWith(p)) return true;
        }
        return false;
    }

    /** Rewrites the file in place (first run keeps a .bak of the original). */
    public static boolean migrateFileInPlace(File jsonFile) {
        if (jsonFile == null || !jsonFile.exists()) return false;
        try {
            if (!isMigrationNeeded(jsonFile)) return false;
            backupOnce(jsonFile);
            String migrated = migrateJsonContent(Files.readString(jsonFile.toPath(), StandardCharsets.UTF_8), jsonFile.getParentFile());
            Files.writeString(jsonFile.toPath(), migrated, StandardCharsets.UTF_8);
            PZOLogger.success("[ZomboidConfigMigrator] Updated " + jsonFile.getName() + " for PZO " + UpdateChecker.CURRENT_VERSION
                + (CompatMode.isActive() ? " (compatibility mode)" : ""));
            return true;
        } catch (Throwable t) {
            PZOLogger.error("[ZomboidConfigMigrator] Error migrating " + jsonFile.getName() + ": " + t.getMessage(), t);
            return false;
        }
    }

    /**
     * For the auto-updater (UpdateDialog): writes the updated file next to the current one as .new, so the updater
     * script can swap it in once the game has exited.
     */
    public static boolean prepareStagedUpdate(File gameDir) {
        if (gameDir == null) return false;
        try {
            File jsonFile = new File(gameDir, TARGET_JSON_NAME);
            if (!jsonFile.exists()) {
                File candidate = new File(new File(".").getAbsoluteFile(), TARGET_JSON_NAME);
                if (candidate.exists()) jsonFile = candidate;
            }
            if (!jsonFile.exists() || !isMigrationNeeded(jsonFile)) return false;
            backupOnce(jsonFile);
            String migrated = migrateJsonContent(Files.readString(jsonFile.toPath(), StandardCharsets.UTF_8), gameDir);
            File stagedFile = new File(jsonFile.getParentFile(), TARGET_JSON_NAME + ".new");
            Files.writeString(stagedFile.toPath(), migrated, StandardCharsets.UTF_8);
            PZOLogger.success("[ZomboidConfigMigrator] Staged " + stagedFile.getName() + " for replacement on game exit.");
            return true;
        } catch (Throwable t) {
            PZOLogger.warn("[ZomboidConfigMigrator] Notice staging config update: " + t.getMessage());
            return false;
        }
    }

    private static void backupOnce(File jsonFile) throws java.io.IOException {
        File backup = new File(jsonFile.getAbsolutePath() + ".bak");
        if (!backup.exists()) {
            Files.copy(jsonFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            PZOLogger.info("[ZomboidConfigMigrator] Created configuration backup: " + backup.getAbsolutePath());
        }
    }

    /** Early in PZOEntrypoint: brings the game folder's ProjectZomboid64.json up to date for the next launch. */
    public static void checkAndMigrateCurrentInstallation(File gameDir) {
        try {
            if (gameDir == null) gameDir = new File(".").getAbsoluteFile();
            File jsonFile = new File(gameDir, TARGET_JSON_NAME);
            if (jsonFile.exists() && isMigrationNeeded(jsonFile)) {
                PZOLogger.info("[ZomboidConfigMigrator] " + TARGET_JSON_NAME + " differs from what PZO " + UpdateChecker.CURRENT_VERSION
                    + " needs on this PC. Updating (applies on the next launch)...");
                migrateFileInPlace(jsonFile);
            }
        } catch (Throwable t) {
            PZOLogger.warn("[ZomboidConfigMigrator] Non-fatal notice during startup configuration check: " + t.getMessage());
        }
    }

    /** Minimal recursive JSON parser and serializer. */
    public static class JsonMini {
        public static Object parse(String json) {
            if (json == null) return null;
            json = stripBOM(json).trim();
            int[] pos = new int[]{0};
            return parseValue(json, pos);
        }

        private static String stripBOM(String s) {
            if (s.startsWith("\uFEFF")) return s.substring(1);
            return s;
        }

        private static Object parseValue(String s, int[] pos) {
            skipWhitespace(s, pos);
            if (pos[0] >= s.length()) return null;
            char c = s.charAt(pos[0]);
            if (c == '{') return parseObject(s, pos);
            if (c == '[') return parseArray(s, pos);
            if (c == '"') return parseString(s, pos);
            if (c == 't' || c == 'f') return parseBoolean(s, pos);
            if (c == 'n') return parseNull(s, pos);
            return parseNumber(s, pos);
        }

        private static Map<String, Object> parseObject(String s, int[] pos) {
            Map<String, Object> map = new LinkedHashMap<>();
            pos[0]++; // skip '{'
            while (pos[0] < s.length()) {
                skipWhitespace(s, pos);
                if (pos[0] >= s.length() || s.charAt(pos[0]) == '}') {
                    pos[0]++;
                    break;
                }
                String key = parseString(s, pos);
                skipWhitespace(s, pos);
                if (pos[0] < s.length() && s.charAt(pos[0]) == ':') {
                    pos[0]++;
                }
                Object val = parseValue(s, pos);
                map.put(key, val);
                skipWhitespace(s, pos);
                if (pos[0] < s.length() && s.charAt(pos[0]) == ',') {
                    pos[0]++;
                }
            }
            return map;
        }

        private static List<Object> parseArray(String s, int[] pos) {
            List<Object> list = new ArrayList<>();
            pos[0]++; // skip '['
            while (pos[0] < s.length()) {
                skipWhitespace(s, pos);
                if (pos[0] >= s.length() || s.charAt(pos[0]) == ']') {
                    pos[0]++;
                    break;
                }
                Object val = parseValue(s, pos);
                list.add(val);
                skipWhitespace(s, pos);
                if (pos[0] < s.length() && s.charAt(pos[0]) == ',') {
                    pos[0]++;
                }
            }
            return list;
        }

        private static String parseString(String s, int[] pos) {
            StringBuilder sb = new StringBuilder();
            pos[0]++; // skip opening '"'
            while (pos[0] < s.length()) {
                char c = s.charAt(pos[0]++);
                if (c == '"') break;
                if (c == '\\' && pos[0] < s.length()) {
                    char esc = s.charAt(pos[0]++);
                    if (esc == '"') sb.append('"');
                    else if (esc == '\\') sb.append('\\');
                    else if (esc == '/') sb.append('/');
                    else if (esc == 'n') sb.append('\n');
                    else if (esc == 'r') sb.append('\r');
                    else if (esc == 't') sb.append('\t');
                    else sb.append(esc);
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private static Boolean parseBoolean(String s, int[] pos) {
            if (s.startsWith("true", pos[0])) {
                pos[0] += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos[0])) {
                pos[0] += 5;
                return Boolean.FALSE;
            }
            return Boolean.FALSE;
        }

        private static Object parseNull(String s, int[] pos) {
            if (s.startsWith("null", pos[0])) pos[0] += 4;
            return null;
        }

        private static Object parseNumber(String s, int[] pos) {
            int start = pos[0];
            while (pos[0] < s.length() && (Character.isDigit(s.charAt(pos[0])) || s.charAt(pos[0]) == '.' || s.charAt(pos[0]) == '-')) {
                pos[0]++;
            }
            String num = s.substring(start, pos[0]);
            try {
                if (num.contains(".")) return Double.parseDouble(num);
                return Long.parseLong(num);
            } catch (Exception e) {
                return num;
            }
        }

        private static void skipWhitespace(String s, int[] pos) {
            while (pos[0] < s.length() && Character.isWhitespace(s.charAt(pos[0]))) {
                pos[0]++;
            }
        }

        public static String format(Object obj) {
            StringBuilder sb = new StringBuilder();
            formatValue(obj, 0, sb);
            sb.append("\n");
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        private static void formatValue(Object obj, int indent, StringBuilder sb) {
            if (obj == null) {
                sb.append("null");
            } else if (obj instanceof String) {
                sb.append("\"").append(escape((String) obj)).append("\"");
            } else if (obj instanceof Boolean || obj instanceof Number) {
                sb.append(obj.toString());
            } else if (obj instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) obj;
                if (map.isEmpty()) {
                    sb.append("{}");
                    return;
                }
                sb.append("{\n");
                int size = map.size();
                int idx = 0;
                for (Map.Entry<String, Object> e : map.entrySet()) {
                    indent(indent + 4, sb);
                    sb.append("\"").append(escape(e.getKey())).append("\": ");
                    formatValue(e.getValue(), indent + 4, sb);
                    if (++idx < size) sb.append(",");
                    sb.append("\n");
                }
                indent(indent, sb);
                sb.append("}");
            } else if (obj instanceof List) {
                List<?> list = (List<?>) obj;
                if (list.isEmpty()) {
                    sb.append("[]");
                    return;
                }
                sb.append("[\n");
                int size = list.size();
                for (int i = 0; i < size; i++) {
                    indent(indent + 4, sb);
                    formatValue(list.get(i), indent + 4, sb);
                    if (i < size - 1) sb.append(",");
                    sb.append("\n");
                }
                indent(indent, sb);
                sb.append("]");
            }
        }

        private static void indent(int count, StringBuilder sb) {
            for (int i = 0; i < count; i++) sb.append(" ");
        }

        private static String escape(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        }
    }
}
