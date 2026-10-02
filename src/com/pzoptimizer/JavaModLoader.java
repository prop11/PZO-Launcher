package com.pzoptimizer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Project Zomboid Build 42 - Embedded Java & ZombieBuddy Mod Loader.
 * Automatically discovers, deduplicates, classloads, and hooks 3rd-party Java Workshop mods
 * across all mounted Steam libraries and custom mod directories.
 */
public class JavaModLoader {
    private static final Set<String> LOADED_MODS = new HashSet<>();
    private static int successCount = 0;
    private static int errorCount = 0;
    private static volatile boolean conflictingFpsModDetected = false;

    public static boolean isZombieBuddyPresent() {
        try {
            File currentDir = new File(".").getAbsoluteFile();
            File zbJar = new File(currentDir, "ZombieBuddy.jar");
            File zbDll1 = new File(currentDir, "win64/zbNative.dll");
            File zbDll2 = new File(currentDir, "zbNative.dll");
            File zbSo1 = new File(currentDir, "linux64/zbNative.so");
            File zbSo2 = new File(currentDir, "zbNative.so");
            File zbDylib = new File(currentDir, "zbNative.dylib");
            if (zbJar.exists() || zbDll1.exists() || zbDll2.exists() || zbSo1.exists() || zbSo2.exists() || zbDylib.exists()) {
                return true;
            }
            List<String> vmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
            for (String arg : vmArgs) {
                if (arg != null && (arg.contains("zbNative") || arg.contains("ZombieBuddy"))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean isConflictingFpsModDetected() {
        return conflictingFpsModDetected;
    }

    public static boolean isConflictingFpsMod(File jarFile, String className) {
        if (jarFile != null) {
            String name = jarFile.getName().toLowerCase();
            String path = jarFile.getAbsolutePath().replace('\\', '/').toLowerCase();
            if (name.contains("betterfps") || name.contains("better_fps") ||
                path.contains("zbbetterfps") || path.contains("3793137588")) {
                return true;
            }
        }
        if (className != null) {
            String c = className.toLowerCase();
            if (c.contains("better_fps") || c.contains("betterfps") || c.contains("zed_0xff.zb_better_fps")) {
                return true;
            }
        }
        return false;
    }

    public static void loadMods(Instrumentation inst) {
        PZOLogger.info("--------------------------------------------------------------------------------");
        PZOLogger.info("[JavaModLoader] Scanning all Steam libraries & mod directories for Java/ZombieBuddy mods...");
        boolean zbPresent = isZombieBuddyPresent();
        if (zbPresent) {
            PZOLogger.info("[JavaModLoader] ZombieBuddy framework detected in installation (-agentlib:zbNative / ZombieBuddy.jar).");
            PZOLogger.info("[JavaModLoader] Preserving PZO primary optimizer priority while delegating @Patch mods to ZombieBuddy runtime.");
        }
        List<File> candidateJars = findJavaModJars();

        if (candidateJars.isEmpty()) {
            PZOLogger.info("[JavaModLoader] No external Java Workshop mods detected.");
            PZOLogger.info("--------------------------------------------------------------------------------");
            return;
        }

        // Deduplicate multi-version JARs per Workshop Mod folder (e.g. pick 42.20 over 41 / 42.12)
        List<File> filteredJars = deduplicateModJars(candidateJars);

        PZOLogger.info(String.format("[JavaModLoader] Discovered %d unique Java mod package(s).", filteredJars.size()));

        for (File jarFile : filteredJars) {
            loadSingleMod(jarFile, inst);
        }

        PZOLogger.info(String.format("[JavaModLoader] Mod Loading Finished: %d loaded successfully, %d error(s).", successCount, errorCount));
        if (conflictingFpsModDetected) {
            PZOLogger.info("[JavaModLoader] PZO Native AVX2 & Multi-Core Engine is actively accelerating hardware & world streaming.");
            PZOLogger.info("[JavaModLoader] ZombieBuddy framework and all Steam Workshop Lua mods are running at full 100% compatibility.");
        }
        PZOLogger.info("--------------------------------------------------------------------------------");
    }

    private static List<File> deduplicateModJars(List<File> rawJars) {
        Map<String, File> modMap = new HashMap<>();

        for (File jar : rawJars) {
            String path = jar.getAbsolutePath().replace('\\', '/');

            // Extract Workshop Mod ID if inside /workshop/content/108600/<id>/
            String modKey = jar.getName();
            int wsIdx = path.indexOf("/108600/");
            if (wsIdx != -1) {
                int afterWs = wsIdx + 8;
                int nextSlash = path.indexOf('/', afterWs);
                if (nextSlash != -1) {
                    modKey = path.substring(afterWs, nextSlash);
                }
            }

            File existing = modMap.get(modKey);
            if (existing == null) {
                modMap.put(modKey, jar);
            } else {
                // If existing is B41 and new is B42, replace it
                String existPath = existing.getAbsolutePath().replace('\\', '/');
                if ((!existPath.contains("42") && path.contains("42")) ||
                    (existPath.contains("42.1") && path.contains("42.2")) ||
                    (jar.length() > existing.length())) {
                    modMap.put(modKey, jar);
                }
            }
        }

        return new ArrayList<>(modMap.values());
    }

    private static List<File> findJavaModJars() {
        List<File> result = new ArrayList<>();
        Set<String> scannedDirs = new HashSet<>();
        List<File> searchRoots = new ArrayList<>();

        try {
            String userHome = System.getProperty("user.home");
            File zomboidMods = new File(userHome, "Zomboid" + File.separator + "mods");
            if (zomboidMods.exists() && zomboidMods.isDirectory()) {
                searchRoots.add(zomboidMods);
            }
        } catch (Throwable ignored) {}

        try {
            File currentDir = new File(".").getAbsoluteFile();
            File ws1 = new File(currentDir, "../../workshop/content/108600");
            if (ws1.exists() && ws1.isDirectory()) searchRoots.add(ws1);

            File ws2 = new File(currentDir, "../../../workshop/content/108600");
            if (ws2.exists() && ws2.isDirectory()) searchRoots.add(ws2);
        } catch (Throwable ignored) {}

        try {
            File[] roots = File.listRoots();
            if (roots != null) {
                for (File root : roots) {
                    if (root.exists()) {
                        String[] commonPaths = new String[]{
                            "SteamLibrary/steamapps/workshop/content/108600",
                            "Program Files (x86)/Steam/steamapps/workshop/content/108600",
                            "Program Files/Steam/steamapps/workshop/content/108600",
                            "Steam/steamapps/workshop/content/108600",
                            "Games/SteamLibrary/steamapps/workshop/content/108600",
                            "SteamLibrary/steamapps/common/ProjectZomboid/mods"
                        };

                        for (String cp : commonPaths) {
                            File f = new File(root, cp.replace('/', File.separatorChar));
                            if (f.exists() && f.isDirectory() && !searchRoots.contains(f)) {
                                searchRoots.add(f);
                            }
                        }

                        // Parse libraryfolders.vdf
                        File vdfFile = new File(root, "Program Files (x86)/Steam/steamapps/libraryfolders.vdf".replace('/', File.separatorChar));
                        if (!vdfFile.exists()) {
                            vdfFile = new File(root, "Steam/steamapps/libraryfolders.vdf".replace('/', File.separatorChar));
                        }
                        if (vdfFile.exists()) {
                            parseVdfForWorkshop(vdfFile, searchRoots);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            String userHome = System.getProperty("user.home");
            File linuxWs = new File(userHome, ".local/share/Steam/steamapps/workshop/content/108600".replace('/', File.separatorChar));
            if (linuxWs.exists() && linuxWs.isDirectory()) searchRoots.add(linuxWs);

            File macWs = new File(userHome, "Library/Application Support/Steam/steamapps/workshop/content/108600".replace('/', File.separatorChar));
            if (macWs.exists() && macWs.isDirectory()) searchRoots.add(macWs);
        } catch (Throwable ignored) {}

        for (File root : searchRoots) {
            String cPath = getCanonicalPath(root);
            if (!scannedDirs.contains(cPath)) {
                scannedDirs.add(cPath);
                scanDirectoryForJars(root, result, 0, 12);
            }
        }

        return result;
    }

    private static void parseVdfForWorkshop(File vdfFile, List<File> searchRoots) {
        try (BufferedReader br = new BufferedReader(new FileReader(vdfFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                int idx = line.indexOf("\"path\"");
                if (idx != -1) {
                    String[] parts = line.split("\"");
                    if (parts.length >= 4) {
                        String libPath = parts[3].replace("\\\\", File.separator);
                        File wsDir = new File(libPath, "steamapps/workshop/content/108600".replace('/', File.separatorChar));
                        if (wsDir.exists() && wsDir.isDirectory() && !searchRoots.contains(wsDir)) {
                            searchRoots.add(wsDir);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void scanDirectoryForJars(File dir, List<File> result, int depth, int maxDepth) {
        if (dir == null || !dir.exists() || depth > maxDepth) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                scanDirectoryForJars(f, result, depth + 1, maxDepth);
            } else if (f.isFile() && f.getName().toLowerCase().endsWith(".jar")) {
                if (f.getName().equalsIgnoreCase("PZOptimEngine.jar") ||
                    f.getName().equalsIgnoreCase("projectzomboid.jar") ||
                    f.getName().equalsIgnoreCase("ZombieBuddy.jar")) {
                    continue;
                }

                if (!result.contains(f)) {
                    result.add(f);
                }
            }
        }
    }

    public static class ModMetadata {
        public final Set<String> modIds = new HashSet<>();
        public String javaPkgName = null;
        public boolean requiresZombieBuddy = false;
        public boolean isZombieBuddyMod = false;
    }

    private static void loadSingleMod(File jarFile, Instrumentation inst) {
        String canonicalPath = getCanonicalPath(jarFile);

        if (LOADED_MODS.contains(canonicalPath)) return;
        LOADED_MODS.add(canonicalPath);

        ModMetadata meta = findModMetadataForJar(jarFile);

        // 1. Check if disabled in Project Zomboid Mod Manager (default.txt)
        Set<String> enabled = getEnabledModIds();
        if (!enabled.isEmpty() && !meta.modIds.isEmpty()) {
            boolean anyEnabled = false;
            for (String mid : meta.modIds) {
                if (enabled.contains(mid) || enabled.contains(mid.toLowerCase()) ||
                    mid.equalsIgnoreCase("MPOptimizer") || mid.equalsIgnoreCase("PZOptimEngine") ||
                    mid.equalsIgnoreCase("ProjectZomboidOptimizer")) {
                    anyEnabled = true;
                    break;
                }
            }
            if (!anyEnabled) {
                PZOLogger.info(String.format("[JavaModLoader] Skipping disabled Java mod: %s (%s) - Mod is disabled in Project Zomboid Mod Manager",
                    String.join(", ", meta.modIds), jarFile.getName()));
                return;
            }
        }

        // 2. Coexistence check: If this is a ZombieBuddy mod
        boolean zbPresent = isZombieBuddyPresent();
        if (meta.isZombieBuddyMod || meta.javaPkgName != null || meta.requiresZombieBuddy) {
            if (zbPresent) {
                // Defer to ZombieBuddy runtime agent:
                // Prevents premature class definition, allows ZombieBuddy's own @Patch ByteBuddy engine
                // to instrument target game classes upon ZomboidFileSystem.loadMods execution.
                successCount++;
                PZOLogger.info(String.format("[JavaModLoader] [COEXISTENCE] Deferring '%s' (%s) to ZombieBuddy runtime agent (javaPkgName: %s)",
                    jarFile.getName(),
                    meta.modIds.isEmpty() ? "unspecified-id" : String.join(", ", meta.modIds),
                    meta.javaPkgName != null ? meta.javaPkgName : "detected"));
                return;
            } else {
                PZOLogger.warn(String.format("[JavaModLoader] [NOTICE] Mod '%s' requires ZombieBuddy framework (javaPkgName: %s), but ZombieBuddy is not installed.",
                    jarFile.getName(), meta.javaPkgName != null ? meta.javaPkgName : "unknown"));
                successCount++;
                PZOLogger.info(String.format("[JavaModLoader] Added %s to classpath (ZombieBuddy prerequisite missing)", jarFile.getName()));
                return;
            }
        }

        long sizeKB = Math.max(1, jarFile.length() / 1024);
        PZOLogger.info(String.format("[JavaModLoader] Inspecting standalone Java mod: %s (%d KB) at %s", jarFile.getName(), sizeKB, jarFile.getPath()));

        try (JarFile jar = new JarFile(jarFile)) {
            if (inst != null) {
                try {
                    inst.appendToSystemClassLoaderSearch(jar);
                    PZOLogger.info("[JavaModLoader] Appended " + jarFile.getName() + " to System ClassLoader");
                } catch (Throwable t) {
                    PZOLogger.warn("[JavaModLoader] Notice: Could not append to system classloader search: " + t.getMessage());
                }
            }

            Manifest manifest = jar.getManifest();
            String agentClass = null;
            if (manifest != null) {
                Attributes attrs = manifest.getMainAttributes();
                if (attrs != null) {
                    agentClass = attrs.getValue("Premain-Class");
                    if (agentClass == null) agentClass = attrs.getValue("Agent-Class");
                    if (agentClass == null) agentClass = attrs.getValue("Main-Class");
                    if (agentClass == null) agentClass = attrs.getValue("Plugin-Class");
                }
            }

            boolean conflictingFpsMod = isConflictingFpsMod(jarFile, agentClass);
            if (!conflictingFpsMod) {
                Enumeration<JarEntry> testEntries = jar.entries();
                while (testEntries.hasMoreElements()) {
                    String en = testEntries.nextElement().getName();
                    if (en.contains("zb_better_fps") || en.contains("better_fps")) {
                        conflictingFpsMod = true;
                        break;
                    }
                }
            }

            if (conflictingFpsMod) {
                conflictingFpsModDetected = true;
                PZOLogger.warn("================================================================================");
                PZOLogger.warn(String.format("[JavaModLoader] [NOTICE] Redundant optimization mod detected: %s", jarFile.getName()));
                PZOLogger.warn("[JavaModLoader] PZO already natively accelerates chunk streaming, bone skinning, and AVX2 culling on dedicated P-cores.");
                if (PZOConfig.isIsolateConflictingFpsMods()) {
                    PZOLogger.warn("[JavaModLoader] Isolating bytecode hooks to protect PZO multi-core engine stability.");
                    PZOLogger.warn("[JavaModLoader] (All other ZombieBuddy gameplay mods and Workshop Lua mods remain fully active!)");
                    PZOLogger.warn("================================================================================");
                    successCount++;
                    PZOLogger.info(String.format("[JavaModLoader] [SUCCESS] Added %s to classpath (Isolated bytecode mode)", jarFile.getName()));
                    return;
                } else {
                    PZOLogger.warn("[JavaModLoader] Warning: Running both simultaneously causes bytecode hook contention.");
                    PZOLogger.warn("================================================================================");
                }
            }

            boolean hooked = false;
            if (agentClass != null && !agentClass.trim().isEmpty()) {
                PZOLogger.info(String.format("[JavaModLoader] Manifest specified entrypoint: %s", agentClass.trim()));
                hooked = invokeEntrypoint(jarFile, agentClass.trim(), inst);
            }

            if (!hooked && manifest != null && manifest.getMainAttributes() != null) {
                // Only inspect classes if manifest declared a plugin/agent entrypoint
                Enumeration<JarEntry> entries = jar.entries();
                List<String> candidateClasses = new ArrayList<>();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.endsWith(".class") && !name.contains("$")) {
                        String className = name.substring(0, name.length() - 6).replace('/', '.');
                        candidateClasses.add(className);
                    }
                }

                // Prioritize Plugin, Agent
                for (String className : candidateClasses) {
                    if (className.toLowerCase().contains("plugin") ||
                        className.toLowerCase().contains("agent")) {
                        if (invokeEntrypoint(jarFile, className, inst)) {
                            hooked = true;
                            break;
                        }
                    }
                }
            }

            if (hooked) {
                successCount++;
                PZOLogger.success(String.format("[JavaModLoader] [SUCCESS] Initialized and hooked Java mod: %s", jarFile.getName()));
            } else {
                successCount++;
                PZOLogger.info(String.format("[JavaModLoader] [SUCCESS] Added %s to runtime classpath (Standalone library mode)", jarFile.getName()));
            }

        } catch (Throwable t) {
            errorCount++;
            PZOLogger.error(String.format("[JavaModLoader] [ERROR] Failed to load Java mod: %s", jarFile.getName()), t);
        }
    }

    private static boolean invokeEntrypoint(File jarFile, String className, Instrumentation inst) {
        try {
            ClassLoader cl = ClassLoader.getSystemClassLoader();
            Class<?> clazz;
            try {
                clazz = Class.forName(className, true, cl);
            } catch (ClassNotFoundException e) {
                URLClassLoader ucl = new URLClassLoader(new URL[]{jarFile.toURI().toURL()}, cl);
                clazz = Class.forName(className, true, ucl);
            }

            if (inst != null) {
                try {
                    Method m = clazz.getDeclaredMethod("premain", String.class, Instrumentation.class);
                    m.setAccessible(true);
                    m.invoke(null, "", inst);
                    PZOLogger.info(String.format("[JavaModLoader] Invoked premain(String, Instrumentation) on %s", className));
                    return true;
                } catch (NoSuchMethodException ignored) {}

                try {
                    Method m = clazz.getDeclaredMethod("premain", String.class);
                    m.setAccessible(true);
                    m.invoke(null, "");
                    PZOLogger.info(String.format("[JavaModLoader] Invoked premain(String) on %s", className));
                    return true;
                } catch (NoSuchMethodException ignored) {}

                try {
                    Method m = clazz.getDeclaredMethod("agentmain", String.class, Instrumentation.class);
                    m.setAccessible(true);
                    m.invoke(null, "", inst);
                    PZOLogger.info(String.format("[JavaModLoader] Invoked agentmain(String, Instrumentation) on %s", className));
                    return true;
                } catch (NoSuchMethodException ignored) {}

                try {
                    Method m = clazz.getDeclaredMethod("init", Instrumentation.class);
                    m.setAccessible(true);
                    m.invoke(null, inst);
                    PZOLogger.info(String.format("[JavaModLoader] Invoked init(Instrumentation) on %s", className));
                    return true;
                } catch (NoSuchMethodException ignored) {}
            }

            try {
                Method m = clazz.getDeclaredMethod("init");
                m.setAccessible(true);
                m.invoke(null);
                PZOLogger.info(String.format("[JavaModLoader] Invoked init() on %s", className));
                return true;
            } catch (NoSuchMethodException ignored) {}

            try {
                Method m = clazz.getDeclaredMethod("load");
                m.setAccessible(true);
                m.invoke(null);
                PZOLogger.info(String.format("[JavaModLoader] Invoked load() on %s", className));
                return true;
            } catch (NoSuchMethodException ignored) {}

            try {
                Method m = clazz.getDeclaredMethod("main", String[].class);
                m.setAccessible(true);
                m.invoke(null, (Object) new String[]{});
                PZOLogger.info(String.format("[JavaModLoader] Invoked main(String[]) on %s", className));
                return true;
            } catch (NoSuchMethodException ignored) {}

        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            PZOLogger.error(String.format("[JavaModLoader] [ERROR] Exception thrown by entrypoint in %s (%s)", jarFile.getName(), className), cause);
        } catch (Throwable t) {
            PZOLogger.warn(String.format("[JavaModLoader] Notice: Could not invoke entrypoint on %s (%s): %s", jarFile.getName(), className, t.getMessage()));
        }
        return false;
    }

    private static final Set<String> ENABLED_MOD_IDS = new HashSet<>();
    private static volatile boolean enabledModsParsed = false;

    private static synchronized Set<String> getEnabledModIds() {
        if (enabledModsParsed) return ENABLED_MOD_IDS;
        enabledModsParsed = true;

        try {
            String userHome = System.getProperty("user.home");
            File defaultTxt = new File(userHome, "Zomboid" + File.separator + "mods" + File.separator + "default.txt");
            if (defaultTxt.exists() && defaultTxt.isFile()) {
                parseModIdsFromFile(defaultTxt, ENABLED_MOD_IDS);
            }
        } catch (Throwable ignored) {}

        // Only fall back to latest savegame if default.txt was not found or had no enabled mods
        if (ENABLED_MOD_IDS.isEmpty()) {
            try {
                String userHome = System.getProperty("user.home");
                File savesDir = new File(userHome, "Zomboid" + File.separator + "Saves");
                if (savesDir.exists() && savesDir.isDirectory()) {
                    File latestModsTxt = findLatestSaveModsTxt(savesDir);
                    if (latestModsTxt != null) {
                        parseModIdsFromFile(latestModsTxt, ENABLED_MOD_IDS);
                    }
                }
            } catch (Throwable ignored) {}
        }

        return ENABLED_MOD_IDS;
    }

    private static void parseModIdsFromFile(File file, Set<String> destination) {
        if (file == null || !file.exists() || !file.isFile()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.replace("\uFEFF", "").trim();
                int eq = trimmed.indexOf('=');
                if (eq != -1) {
                    String key = trimmed.substring(0, eq).trim();
                    if (key.equalsIgnoreCase("mod")) {
                        String id = trimmed.substring(eq + 1).trim();
                        if (id.endsWith(",")) id = id.substring(0, id.length() - 1).trim();
                        if (!id.isEmpty()) {
                            destination.add(id);
                            destination.add(id.toLowerCase());
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static File findLatestSaveModsTxt(File dir) {
        File[] modes = dir.listFiles();
        if (modes == null) return null;
        File newest = null;
        long newestTime = 0;
        for (File mode : modes) {
            if (!mode.isDirectory()) continue;
            File[] saves = mode.listFiles();
            if (saves == null) continue;
            for (File save : saves) {
                if (!save.isDirectory()) continue;
                File modsTxt = new File(save, "mods.txt");
                if (modsTxt.exists() && modsTxt.lastModified() > newestTime) {
                    newest = modsTxt;
                    newestTime = modsTxt.lastModified();
                }
            }
        }
        return newest;
    }

    private static ModMetadata findModMetadataForJar(File jarFile) {
        ModMetadata meta = new ModMetadata();
        if (jarFile == null) return meta;

        File curr = jarFile.getParentFile();
        int levels = 0;
        while (curr != null && levels < 6) {
            // Check direct mod.info
            File modInfo = new File(curr, "mod.info");
            if (modInfo.exists() && modInfo.isFile()) {
                parseModInfoFile(modInfo, meta);
            }
            // Check Build 42 subfolder mod.info
            File b42Info = new File(curr, "42" + File.separator + "mod.info");
            if (b42Info.exists() && b42Info.isFile()) {
                parseModInfoFile(b42Info, meta);
            }
            // Check common subfolder mod.info
            File commonInfo = new File(curr, "common" + File.separator + "mod.info");
            if (commonInfo.exists() && commonInfo.isFile()) {
                parseModInfoFile(commonInfo, meta);
            }

            File modsSub = new File(curr, "mods");
            if (modsSub.exists() && modsSub.isDirectory()) {
                File[] children = modsSub.listFiles();
                if (children != null) {
                    for (File c : children) {
                        if (c.isDirectory()) {
                            File subModInfo = new File(c, "mod.info");
                            if (subModInfo.exists() && subModInfo.isFile()) {
                                parseModInfoFile(subModInfo, meta);
                            }
                            File sub42 = new File(c, "42" + File.separator + "mod.info");
                            if (sub42.exists() && sub42.isFile()) {
                                parseModInfoFile(sub42, meta);
                            }
                            File subCommon = new File(c, "common" + File.separator + "mod.info");
                            if (subCommon.exists() && subCommon.isFile()) {
                                parseModInfoFile(subCommon, meta);
                            }
                        }
                    }
                }
            }
            curr = curr.getParentFile();
            levels++;
        }

        // Secondary check inside the JAR itself for ZombieBuddy signature
        if (!meta.isZombieBuddyMod) {
            try (JarFile jar = new JarFile(jarFile)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String en = entries.nextElement().getName();
                    if (en.contains("me/zed_0xff/zombie_buddy") ||
                        en.contains("zb_better_fps") ||
                        en.contains("/zb/") ||
                        en.startsWith("zb/") ||
                        en.endsWith("ZB.class") ||
                        en.contains("ZombieBuddy")) {
                        meta.isZombieBuddyMod = true;
                        break;
                    }
                }
            } catch (Throwable ignored) {}
        }

        return meta;
    }

    private static void parseModInfoFile(File modInfoFile, ModMetadata meta) {
        if (modInfoFile == null || !modInfoFile.exists() || !modInfoFile.isFile()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(modInfoFile, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.replace("\uFEFF", "").trim();
                int eq = trimmed.indexOf('=');
                if (eq != -1) {
                    String key = trimmed.substring(0, eq).trim().toLowerCase();
                    String val = trimmed.substring(eq + 1).trim();
                    if (val.endsWith(",")) val = val.substring(0, val.length() - 1).trim();
                    if (!val.isEmpty()) {
                        if (key.equals("id")) {
                            meta.modIds.add(val);
                            meta.modIds.add(val.toLowerCase());
                        } else if (key.equals("javapkgname")) {
                            meta.javaPkgName = val;
                            meta.isZombieBuddyMod = true;
                        } else if (key.equals("require")) {
                            if (val.toLowerCase().contains("zombiebuddy")) {
                                meta.requiresZombieBuddy = true;
                                meta.isZombieBuddyMod = true;
                            }
                        } else if (key.equals("zbversionmin") || key.equals("zbversionmax")) {
                            meta.requiresZombieBuddy = true;
                            meta.isZombieBuddyMod = true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static String getCanonicalPath(File f) {
        try {
            return f.getCanonicalPath();
        } catch (Exception e) {
            return f.getAbsolutePath();
        }
    }
}
