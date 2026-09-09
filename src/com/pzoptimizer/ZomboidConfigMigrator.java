package com.pzoptimizer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Migrates launcher configuration while preserving custom heap sizes and agent entries. */
public final class ZomboidConfigMigrator {

    public static final String TARGET_JSON_NAME = "ProjectZomboid64.json";
    public static final String TARGET_MAIN_CLASS = "com/pzoptimizer/PZOEntrypoint";
    public static final String TARGET_AGENTLIB = "-agentlib:pzo_native64";

    public static void main(String[] args) {
        File targetDir = args != null && args.length > 0 ? new File(args[0]) : new File(".").getAbsoluteFile();
        checkAndMigrateCurrentInstallation(targetDir);
    }

    private static final String[] REQUIRED_VM_ARGS = {
        TARGET_AGENTLIB,
        "--enable-native-access=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "-XX:-CreateCoredumpOnCrash",
        "-XX:-OmitStackTraceInFastThrow",
        "-XX:+PerfDisableSharedMem",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseCompactObjectHeaders",
        "-XX:+UseSuperWord",
        "-XX:MaxInlineLevel=15",
        "-XX:InlineSmallCode=2500",
        "-XX:+UseNUMA",
        "-XX:+AlwaysPreTouch"
    };

    public static boolean isMigrationNeeded(File jsonFile) {
        if (jsonFile == null || !jsonFile.exists() || jsonFile.length() == 0) {
            return false;
        }

        try {
            String content = Files.readString(jsonFile.toPath(), StandardCharsets.UTF_8);
            Object parsed = JsonMini.parse(content);
            if (!(parsed instanceof Map)) {
                return false;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) parsed;

            Object mainClass = map.get("mainClass");
            if (mainClass == null || !TARGET_MAIN_CLASS.equals(mainClass.toString().trim())) {
                return true;
            }

            Object cpObj = map.get("classpath");
            if (!(cpObj instanceof List)) {
                return true;
            }
            List<?> cpList = (List<?>) cpObj;
            boolean hasJar = false;
            boolean hasDot = false;
            for (Object item : cpList) {
                if (item != null) {
                    String s = item.toString().trim();
                    if ("PZOptimEngine.jar".equalsIgnoreCase(s)) hasJar = true;
                    if (".".equals(s)) hasDot = true;
                }
            }
            if (!hasJar || !hasDot) {
                return true;
            }

            Object vmObj = map.get("vmArgs");
            if (!(vmObj instanceof List)) {
                return true;
            }
            List<?> vmList = (List<?>) vmObj;
            List<String> vmStrings = new ArrayList<>();
            for (Object item : vmList) {
                if (item != null) vmStrings.add(item.toString().trim());
            }

            for (String req : REQUIRED_VM_ARGS) {
                if (!vmStrings.contains(req)) {
                    return true;
                }
            }

            boolean hasLibPath = false;
            String os = System.getProperty("os.name", "").toLowerCase();
            for (String arg : vmStrings) {
                if (arg.startsWith("-Djava.library.path=")) {
                    if (os.contains("win") && arg.contains("win64")) hasLibPath = true;
                    else if (os.contains("linux") && arg.contains("linux64")) hasLibPath = true;
                    else if (os.contains("mac")) hasLibPath = true;
                    else hasLibPath = true;
                }
            }
            if (!hasLibPath) {
                return true;
            }

            for (String arg : vmStrings) {
                if (arg.startsWith("-Djava.awt.headless")) {
                    return true;
                }
            }

            return false;
        } catch (Throwable t) {
            PZOLogger.warn("[ZomboidConfigMigrator] Notice checking " + jsonFile.getName() + ": " + t.getMessage());
            return false;
        }
    }

    public static String migrateJsonContent(String originalJson, File gameDir) {
        Object parsed = JsonMini.parse(originalJson);
        if (!(parsed instanceof Map)) {
            return originalJson;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) parsed;

        root.put("mainClass", TARGET_MAIN_CLASS);

        List<String> cpList = new ArrayList<>();
        Object cpObj = root.get("classpath");
        if (cpObj instanceof List) {
            for (Object item : (List<?>) cpObj) {
                if (item != null) {
                    String s = item.toString().trim();
                    if (!cpList.contains(s)) cpList.add(s);
                }
            }
        }
        if (!cpList.contains(".")) {
            cpList.add(0, ".");
        }
        if (!cpList.contains("PZOptimEngine.jar")) {
            int dotIdx = cpList.indexOf(".");
            if (dotIdx != -1) {
                cpList.add(dotIdx + 1, "PZOptimEngine.jar");
            } else {
                cpList.add(0, "PZOptimEngine.jar");
            }
        }
        if (!cpList.contains("projectzomboid.jar")) {
            cpList.add("projectzomboid.jar");
        }
        root.put("classpath", cpList);

        List<String> rawArgs = new ArrayList<>();
        Object vmObj = root.get("vmArgs");
        if (vmObj instanceof List) {
            for (Object item : (List<?>) vmObj) {
                if (item != null) rawArgs.add(item.toString().trim());
            }
        }

        boolean zbActive = false;
        for (String arg : rawArgs) {
            if (arg.contains("zbNative") || arg.contains("ZombieBuddy")) {
                zbActive = true;
                break;
            }
        }
        if (!zbActive && gameDir != null) {
            File zbDll = new File(gameDir, "zbNative.dll");
            File zbWin64 = new File(gameDir, "win64" + File.separator + "zbNative.dll");
            File zbSo = new File(gameDir, "linux64" + File.separator + "zbNative.so");
            File zbDylib = new File(gameDir, "zbNative.dylib");
            if (zbDll.exists() || zbWin64.exists() || zbSo.exists() || zbDylib.exists()) {
                zbActive = true;
            }
        }

        List<String> userCleanArgs = new ArrayList<>();
        for (String arg : rawArgs) {
            if (arg.startsWith("-Djava.awt.headless")) continue; // PZO requires GUI/AWT
            if (arg.startsWith("-Djava.library.path=")) continue; // will normalize below
            if (arg.equals(TARGET_AGENTLIB)) continue; // will place deterministically
            if (arg.contains("zbNative")) continue; // will preserve deterministically
            userCleanArgs.add(arg);
        }

        String os = System.getProperty("os.name", "").toLowerCase();
        boolean isWin = os.contains("win");
        boolean isLinux = os.contains("linux");

        List<String> targetArgs = new ArrayList<>();

        targetArgs.add(TARGET_AGENTLIB);
        if (zbActive) {
            targetArgs.add("-agentlib:zbNative");
            PZOLogger.info("[ZomboidConfigMigrator] Preserved ZombieBuddy bridge (-agentlib:zbNative)");
        }

        targetArgs.add("--enable-native-access=ALL-UNNAMED");
        targetArgs.add("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED");

        if (isWin) {
            targetArgs.add("-Djava.library.path=win64/;.;natives/");
        } else if (isLinux) {
            targetArgs.add("-Djava.library.path=linux64/:natives/:.");
        } else {
            targetArgs.add("-Djava.library.path=.:ProjectZomboid.app/Contents/MacOS/:ProjectZomboid.app/Contents/Java/");
        }

        boolean hasXms = false;
        boolean hasXmx = false;
        for (String arg : userCleanArgs) {
            if (arg.startsWith("-Xms")) hasXms = true;
            if (arg.startsWith("-Xmx")) hasXmx = true;
            if (!targetArgs.contains(arg)) {
                targetArgs.add(arg);
            }
        }

        if (!hasXms) targetArgs.add(3, "-Xms2048m");
        if (!hasXmx) targetArgs.add(4, "-Xmx4096m");

        for (String pf : REQUIRED_VM_ARGS) {
            if (!targetArgs.contains(pf)) {
                targetArgs.add(pf);
            }
        }

        root.put("vmArgs", targetArgs);

        return JsonMini.format(root);
    }

    /** Migrates the JSON file in place after saving a .bak copy. */
    public static boolean migrateFileInPlace(File jsonFile) {
        if (jsonFile == null || !jsonFile.exists()) return false;
        try {
            if (!isMigrationNeeded(jsonFile)) return false;

            File backup = new File(jsonFile.getAbsolutePath() + ".bak");
            if (!backup.exists()) {
                Files.copy(jsonFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                PZOLogger.info("[ZomboidConfigMigrator] Created configuration backup: " + backup.getAbsolutePath());
            }

            String original = Files.readString(jsonFile.toPath(), StandardCharsets.UTF_8);
            String migrated = migrateJsonContent(original, jsonFile.getParentFile());

            Files.writeString(jsonFile.toPath(), migrated, StandardCharsets.UTF_8);
            PZOLogger.success("[ZomboidConfigMigrator] Successfully migrated " + jsonFile.getName() + " to v0.9.* configuration.");
            return true;
        } catch (Throwable t) {
            PZOLogger.error("[ZomboidConfigMigrator] Error migrating " + jsonFile.getName() + ": " + t.getMessage(), t);
            return false;
        }
    }

    /** Stages ProjectZomboid64.json.new for the updater to replace after the game exits. */
    public static boolean prepareStagedUpdate(File gameDir) {
        if (gameDir == null) return false;
        try {
            File jsonFile = new File(gameDir, TARGET_JSON_NAME);
            if (!jsonFile.exists()) {
                File currentDir = new File(".").getAbsoluteFile();
                File candidate = new File(currentDir, TARGET_JSON_NAME);
                if (candidate.exists()) {
                    jsonFile = candidate;
                }
            }

            if (!jsonFile.exists() || !isMigrationNeeded(jsonFile)) {
                return false;
            }

            File backup = new File(jsonFile.getAbsolutePath() + ".bak");
            if (!backup.exists()) {
                Files.copy(jsonFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                PZOLogger.info("[ZomboidConfigMigrator] Created configuration backup: " + backup.getAbsolutePath());
            }

            String original = Files.readString(jsonFile.toPath(), StandardCharsets.UTF_8);
            String migrated = migrateJsonContent(original, gameDir);

            File stagedFile = new File(jsonFile.getParentFile(), TARGET_JSON_NAME + ".new");
            Files.writeString(stagedFile.toPath(), migrated, StandardCharsets.UTF_8);
            PZOLogger.success("[ZomboidConfigMigrator] Staged " + stagedFile.getName() + " for atomic replacement on game exit.");
            return true;
        } catch (Throwable t) {
            PZOLogger.warn("[ZomboidConfigMigrator] Notice staging config update: " + t.getMessage());
            return false;
        }
    }

    public static void checkAndMigrateCurrentInstallation(File gameDir) {
        try {
            if (gameDir == null) gameDir = new File(".").getAbsoluteFile();
            File jsonFile = new File(gameDir, TARGET_JSON_NAME);
            if (jsonFile.exists() && isMigrationNeeded(jsonFile)) {
                PZOLogger.info("[ZomboidConfigMigrator] Outdated or missing 0.9.6 JVM arguments detected in " + TARGET_JSON_NAME + ". Upgrading...");
                boolean ok = migrateFileInPlace(jsonFile);
                if (ok) {
                    PZOLogger.success("[ZomboidConfigMigrator] Configuration successfully aligned with v0.9.6 (native access, JVMTI agent, and B42 JVM tuning active on next launch).");
                }
            }
        } catch (Throwable t) {
            PZOLogger.warn("[ZomboidConfigMigrator] Non-fatal notice during startup configuration check: " + t.getMessage());
        }
    }

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
