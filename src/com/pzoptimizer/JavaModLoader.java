package com.pzoptimizer;

import java.io.File;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
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
 * (ZombieBuddy mods, ByteBuddy transformers, and standalone engine plugins)
 * with zero-configuration required from the player.
 */
public class JavaModLoader {
    private static final Set<String> LOADED_MODS = new HashSet<>();

    public static void loadMods(Instrumentation inst) {
        PZOLogger.info("[JavaModLoader] Scanning for ZombieBuddy & Java Workshop mods...");
        List<File> candidateJars = findJavaModJars();

        if (candidateJars.isEmpty()) {
            PZOLogger.info("[JavaModLoader] No external Java Workshop mods detected.");
            return;
        }

        PZOLogger.info(String.format("[JavaModLoader] Discovered %d candidate Java mod package(s).", candidateJars.size()));

        for (File jarFile : candidateJars) {
            loadSingleMod(jarFile, inst);
        }
    }

    private static List<File> findJavaModJars() {
        List<File> result = new ArrayList<>();
        List<File> searchRoots = new ArrayList<>();

        // 1. User Zomboid mods directory (%USERPROFILE%/Zomboid/mods/)
        try {
            String userHome = System.getProperty("user.home");
            File zomboidMods = new File(userHome, "Zomboid" + File.separator + "mods");
            if (zomboidMods.exists() && zomboidMods.isDirectory()) {
                searchRoots.add(zomboidMods);
            }
        } catch (Throwable ignored) {}

        // 2. Steam Workshop content directory (../workshop/content/108600/)
        try {
            File currentDir = new File(".").getAbsoluteFile();
            // Try relative path from ProjectZomboid directory to workshop
            File ws1 = new File(currentDir, "../../workshop/content/108600");
            if (ws1.exists() && ws1.isDirectory()) searchRoots.add(ws1);

            File ws2 = new File(currentDir, "../../../workshop/content/108600");
            if (ws2.exists() && ws2.isDirectory()) searchRoots.add(ws2);
        } catch (Throwable ignored) {}

        // 3. Local game ./mods/ and ./java/ folders
        File localMods = new File("mods");
        if (localMods.exists() && localMods.isDirectory()) searchRoots.add(localMods);

        File localJava = new File("java");
        if (localJava.exists() && localJava.isDirectory()) searchRoots.add(localJava);

        // Search recursively for java/*.jar or *.jar in mod folders
        for (File root : searchRoots) {
            scanDirectoryForJars(root, result, 0, 5);
        }

        return result;
    }

    private static void scanDirectoryForJars(File dir, List<File> result, int depth, int maxDepth) {
        if (dir == null || !dir.exists() || depth > maxDepth) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                scanDirectoryForJars(f, result, depth + 1, maxDepth);
            } else if (f.isFile() && f.getName().toLowerCase().endsWith(".jar")) {
                // Ignore self
                if (f.getName().equalsIgnoreCase("PZOptimEngine.jar") ||
                    f.getName().equalsIgnoreCase("projectzomboid.jar")) {
                    continue;
                }

                // Check if inside a java folder or mod folder
                String parentName = f.getParentFile() != null ? f.getParentFile().getName().toLowerCase() : "";
                if (parentName.equals("java") || parentName.equals("mods") || parentName.equals("42") ||
                    f.getName().toLowerCase().contains("mod") || f.getName().toLowerCase().contains("zb")) {
                    if (!result.contains(f)) {
                        result.add(f);
                    }
                }
            }
        }
    }

    private static void loadSingleMod(File jarFile, Instrumentation inst) {
        String canonicalPath;
        try {
            canonicalPath = jarFile.getCanonicalPath();
        } catch (Exception e) {
            canonicalPath = jarFile.getAbsolutePath();
        }

        if (LOADED_MODS.contains(canonicalPath)) return;
        LOADED_MODS.add(canonicalPath);

        PZOLogger.info("[JavaModLoader] Loading Java mod: " + jarFile.getName() + " (" + jarFile.getPath() + ")");

        try (JarFile jar = new JarFile(jarFile)) {
            // 1. Add JAR to System ClassLoader search path if Instrumentation is active
            if (inst != null) {
                try {
                    inst.appendToSystemClassLoaderSearch(jar);
                } catch (Throwable t) {
                    PZOLogger.warn("[JavaModLoader] Could not append to system classloader search: " + t.getMessage());
                }
            }

            // 2. Check MANIFEST.MF for Premain-Class or Main-Class
            Manifest manifest = jar.getManifest();
            String agentClass = null;
            if (manifest != null) {
                Attributes attrs = manifest.getMainAttributes();
                if (attrs != null) {
                    agentClass = attrs.getValue("Premain-Class");
                    if (agentClass == null) agentClass = attrs.getValue("Agent-Class");
                    if (agentClass == null) agentClass = attrs.getValue("Main-Class");
                    if (agentClass == null) agentClass = attrs.getValue("ZBPatch-Class");
                }
            }

            // 3. If manifest specifies class, execute its entrypoint
            boolean hooked = false;
            if (agentClass != null && !agentClass.trim().isEmpty()) {
                hooked = invokeEntrypoint(jarFile, agentClass.trim(), inst);
            }

            // 4. If not hooked via manifest, scan class entries for premain / init entrypoints
            if (!hooked) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements() && !hooked) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.endsWith(".class") && !name.contains("$")) {
                        String className = name.substring(0, name.length() - 6).replace('/', '.');
                        if (className.toLowerCase().contains("plugin") ||
                            className.toLowerCase().contains("agent") ||
                            className.toLowerCase().contains("patch") ||
                            className.toLowerCase().contains("mod") ||
                            className.toLowerCase().contains("main")) {
                            hooked = invokeEntrypoint(jarFile, className, inst);
                        }
                    }
                }
            }

            if (hooked) {
                PZOLogger.success("[JavaModLoader] Successfully initialized Java mod: " + jarFile.getName());
            } else {
                PZOLogger.info("[JavaModLoader] Added " + jarFile.getName() + " to classpath (No active agent hook required).");
            }

        } catch (Throwable t) {
            PZOLogger.warn(String.format("[JavaModLoader] Error loading %s (Non-fatal, continuing): %s", jarFile.getName(), t.getMessage()));
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

            // Try premain(String, Instrumentation)
            if (inst != null) {
                try {
                    Method m = clazz.getDeclaredMethod("premain", String.class, Instrumentation.class);
                    m.setAccessible(true);
                    m.invoke(null, "", inst);
                    return true;
                } catch (NoSuchMethodException ignored) {}

                // Try agentmain(String, Instrumentation)
                try {
                    Method m = clazz.getDeclaredMethod("agentmain", String.class, Instrumentation.class);
                    m.setAccessible(true);
                    m.invoke(null, "", inst);
                    return true;
                } catch (NoSuchMethodException ignored) {}

                // Try init(Instrumentation)
                try {
                    Method m = clazz.getDeclaredMethod("init", Instrumentation.class);
                    m.setAccessible(true);
                    m.invoke(null, inst);
                    return true;
                } catch (NoSuchMethodException ignored) {}
            }

            // Try init()
            try {
                Method m = clazz.getDeclaredMethod("init");
                m.setAccessible(true);
                m.invoke(null);
                return true;
            } catch (NoSuchMethodException ignored) {}

            // Try load()
            try {
                Method m = clazz.getDeclaredMethod("load");
                m.setAccessible(true);
                m.invoke(null);
                return true;
            } catch (NoSuchMethodException ignored) {}

        } catch (Throwable t) {
            PZOLogger.warn(String.format("[JavaModLoader] Could not invoke entrypoint on %s: %s", className, t.getMessage()));
        }
        return false;
    }
}
