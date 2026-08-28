package com.pzoptimizer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Project Zomboid Build 42 - Embedded Java & ZombieBuddy Mod Loader.
 * Automatically discovers, classloads, and hooks 3rd-party Java Workshop mods
 * across all mounted Steam libraries and custom mod directories.
 */
public class JavaModLoader {
    private static final Set<String> LOADED_MODS = new HashSet<>();
    private static int successCount = 0;
    private static int errorCount = 0;

    public static void loadMods(Instrumentation inst) {
        PZOLogger.info("--------------------------------------------------------------------------------");
        PZOLogger.info("[JavaModLoader] Scanning all Steam libraries & mod directories for Java/ZombieBuddy mods...");
        List<File> candidateJars = findJavaModJars();

        if (candidateJars.isEmpty()) {
            PZOLogger.info("[JavaModLoader] No external Java Workshop mods detected.");
            PZOLogger.info("--------------------------------------------------------------------------------");
            return;
        }

        PZOLogger.info(String.format("[JavaModLoader] Discovered %d candidate Java mod package(s).", candidateJars.size()));

        for (File jarFile : candidateJars) {
            loadSingleMod(jarFile, inst);
        }

        PZOLogger.info(String.format("[JavaModLoader] Mod Loading Finished: %d loaded successfully, %d error(s).", successCount, errorCount));
        PZOLogger.info("--------------------------------------------------------------------------------");
    }

    private static List<File> findJavaModJars() {
        List<File> result = new ArrayList<>();
        Set<String> scannedDirs = new HashSet<>();
        List<File> searchRoots = new ArrayList<>();

        // 1. User Zomboid mods directory (%USERPROFILE%/Zomboid/mods/)
        try {
            String userHome = System.getProperty("user.home");
            File zomboidMods = new File(userHome, "Zomboid" + File.separator + "mods");
            if (zomboidMods.exists() && zomboidMods.isDirectory()) {
                searchRoots.add(zomboidMods);
            }
        } catch (Throwable ignored) {}

        // 2. Relative paths from working directory
        try {
            File currentDir = new File(".").getAbsoluteFile();
            File ws1 = new File(currentDir, "../../workshop/content/108600");
            if (ws1.exists() && ws1.isDirectory()) searchRoots.add(ws1);

            File ws2 = new File(currentDir, "../../../workshop/content/108600");
            if (ws2.exists() && ws2.isDirectory()) searchRoots.add(ws2);
        } catch (Throwable ignored) {}

        // 3. Scan all mounted drive roots for Steam libraries (C:, D:, E:, K:, etc.)
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

                        // Also parse libraryfolders.vdf if present
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

        // 4. Unix standard paths
        try {
            String userHome = System.getProperty("user.home");
            File linuxWs = new File(userHome, ".local/share/Steam/steamapps/workshop/content/108600".replace('/', File.separatorChar));
            if (linuxWs.exists() && linuxWs.isDirectory()) searchRoots.add(linuxWs);

            File macWs = new File(userHome, "Library/Application Support/Steam/steamapps/workshop/content/108600".replace('/', File.separatorChar));
            if (macWs.exists() && macWs.isDirectory()) searchRoots.add(macWs);
        } catch (Throwable ignored) {}

        // 5. Deep scan all discovered search roots (Depth up to 12 levels)
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

    private static void loadSingleMod(File jarFile, Instrumentation inst) {
        String canonicalPath = getCanonicalPath(jarFile);

        if (LOADED_MODS.contains(canonicalPath)) return;
        LOADED_MODS.add(canonicalPath);

        long sizeKB = Math.max(1, jarFile.length() / 1024);
        PZOLogger.info(String.format("[JavaModLoader] Inspecting Java mod: %s (%d KB) at %s", jarFile.getName(), sizeKB, jarFile.getPath()));

        try (JarFile jar = new JarFile(jarFile)) {
            // 1. Add JAR to System ClassLoader search path
            if (inst != null) {
                try {
                    inst.appendToSystemClassLoaderSearch(jar);
                    PZOLogger.info("[JavaModLoader] Appended " + jarFile.getName() + " to System ClassLoader");
                } catch (Throwable t) {
                    PZOLogger.warn("[JavaModLoader] Notice: Could not append to system classloader search: " + t.getMessage());
                }
            }

            // 2. Check MANIFEST.MF for Premain-Class, Main-Class, or ZB-Preload
            Manifest manifest = jar.getManifest();
            String agentClass = null;
            if (manifest != null) {
                Attributes attrs = manifest.getMainAttributes();
                if (attrs != null) {
                    agentClass = attrs.getValue("Premain-Class");
                    if (agentClass == null) agentClass = attrs.getValue("Agent-Class");
                    if (agentClass == null) agentClass = attrs.getValue("Main-Class");
                    if (agentClass == null) agentClass = attrs.getValue("ZBPatch-Class");
                    if (agentClass == null) agentClass = attrs.getValue("Plugin-Class");
                }
            }

            boolean hooked = false;
            if (agentClass != null && !agentClass.trim().isEmpty()) {
                PZOLogger.info(String.format("[JavaModLoader] Manifest specified entrypoint: %s", agentClass.trim()));
                hooked = invokeEntrypoint(jarFile, agentClass.trim(), inst);
            }

            // 3. Scan class entries for candidate entrypoints (e.g. lugli.optimizations.Main, *Patch, *Plugin)
            if (!hooked) {
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

                // Prioritize Main, Plugin, Agent, Patch
                for (String className : candidateClasses) {
                    if (className.toLowerCase().endsWith(".main") ||
                        className.toLowerCase().contains("plugin") ||
                        className.toLowerCase().contains("agent") ||
                        className.toLowerCase().contains("patch") ||
                        className.toLowerCase().contains("optim")) {
                        if (invokeEntrypoint(jarFile, className, inst)) {
                            hooked = true;
                            break;
                        }
                    }
                }

                // If still not hooked, try remaining top-level classes
                if (!hooked) {
                    for (String className : candidateClasses) {
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

            // 1. Try premain(String, Instrumentation)
            if (inst != null) {
                try {
                    Method m = clazz.getDeclaredMethod("premain", String.class, Instrumentation.class);
                    m.setAccessible(true);
                    m.invoke(null, "", inst);
                    PZOLogger.info(String.format("[JavaModLoader] Invoked premain(String, Instrumentation) on %s", className));
                    return true;
                } catch (NoSuchMethodException ignored) {}

                // 2. Try premain(String)
                try {
                    Method m = clazz.getDeclaredMethod("premain", String.class);
                    m.setAccessible(true);
                    m.invoke(null, "");
                    PZOLogger.info(String.format("[JavaModLoader] Invoked premain(String) on %s", className));
                    return true;
                } catch (NoSuchMethodException ignored) {}

                // 3. Try agentmain(String, Instrumentation)
                try {
                    Method m = clazz.getDeclaredMethod("agentmain", String.class, Instrumentation.class);
                    m.setAccessible(true);
                    m.invoke(null, "", inst);
                    PZOLogger.info(String.format("[JavaModLoader] Invoked agentmain(String, Instrumentation) on %s", className));
                    return true;
                } catch (NoSuchMethodException ignored) {}

                // 4. Try init(Instrumentation)
                try {
                    Method m = clazz.getDeclaredMethod("init", Instrumentation.class);
                    m.setAccessible(true);
                    m.invoke(null, inst);
                    PZOLogger.info(String.format("[JavaModLoader] Invoked init(Instrumentation) on %s", className));
                    return true;
                } catch (NoSuchMethodException ignored) {}
            }

            // 5. Try init()
            try {
                Method m = clazz.getDeclaredMethod("init");
                m.setAccessible(true);
                m.invoke(null);
                PZOLogger.info(String.format("[JavaModLoader] Invoked init() on %s", className));
                return true;
            } catch (NoSuchMethodException ignored) {}

            // 6. Try load()
            try {
                Method m = clazz.getDeclaredMethod("load");
                m.setAccessible(true);
                m.invoke(null);
                PZOLogger.info(String.format("[JavaModLoader] Invoked load() on %s", className));
                return true;
            } catch (NoSuchMethodException ignored) {}

            // 7. Try main(String[])
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

    private static String getCanonicalPath(File f) {
        try {
            return f.getCanonicalPath();
        } catch (Exception e) {
            return f.getAbsolutePath();
        }
    }
}
