package com.pzoptimizer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Project Zomboid Build 42 - FileSystem Whitelist & Secondary-Drive Protection Shield.
 * 
 * In Build 42, ZomboidFileSystem.validatePrefix(path) enforces an internal whitelist
 * of allowed base directories (allowedPrefixes). When Steam Workshop mods are installed
 * on a secondary drive (e.g. K:\SteamLibrary, D:\SteamLibrary), the vanilla engine can
 * fail to register these paths in time, causing validatePrefix to throw IllegalArgumentException.
 * 
 * When textures, animations, or models from these mods are loaded, the exception causes
 * Texture.getSharedTexture to return null, triggering game-killing NullPointerExceptions
 * during world and weather loading (e.g. RainParticle / WeatherParticle).
 * 
 * This shield automatically discovers all mounted drives and Steam Workshop libraries,
 * injects them into ZomboidFileSystem.instance.modFolders, and maintains allowedPrefixes
 * so that all 3rd-party mods load seamlessly without crashes.
 */
public final class FileSystemWhitelistShield {

    private static volatile boolean initialized = false;
    private static volatile boolean applied = false;
    private static final Set<String> discoveredRoots = new HashSet<>();

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        collectAllSearchRoots();

        // Start background daemon to hook ZomboidFileSystem as soon as it is instantiated
        Thread daemon = new Thread(() -> {
            for (int i = 0; i < 600; i++) { // Poll up to 60 seconds
                if (tryApplyShield()) {
                    break;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }, "PZO-FileSystem-Whitelist-Shield");
        daemon.setDaemon(true);
        daemon.start();
    }

    private static void collectAllSearchRoots() {
        // 1. All mounted drive roots (C:\, D:\, K:\, etc.)
        try {
            File[] roots = File.listRoots();
            if (roots != null) {
                for (File root : roots) {
                    if (root.exists()) {
                        discoveredRoots.add(normalize(root.getAbsolutePath()));

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
                            if (f.exists() && f.isDirectory()) {
                                discoveredRoots.add(normalize(f.getAbsolutePath()));
                            }
                        }

                        // Parse libraryfolders.vdf
                        File vdf1 = new File(root, "Program Files (x86)/Steam/steamapps/libraryfolders.vdf".replace('/', File.separatorChar));
                        if (!vdf1.exists()) {
                            vdf1 = new File(root, "Steam/steamapps/libraryfolders.vdf".replace('/', File.separatorChar));
                        }
                        if (vdf1.exists()) {
                            parseVdf(vdf1);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 2. User Zomboid directory
        try {
            String userHome = System.getProperty("user.home");
            File zomboidDir = new File(userHome, "Zomboid");
            if (zomboidDir.exists()) {
                discoveredRoots.add(normalize(zomboidDir.getAbsolutePath()));
            }
        } catch (Throwable ignored) {}
    }

    private static void parseVdf(File vdfFile) {
        try (BufferedReader br = new BufferedReader(new FileReader(vdfFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                int idx = line.indexOf("\"path\"");
                if (idx != -1) {
                    String[] parts = line.split("\"");
                    if (parts.length >= 4) {
                        String libPath = parts[3].replace("\\\\", File.separator);
                        File wsDir = new File(libPath, "steamapps/workshop/content/108600".replace('/', File.separatorChar));
                        if (wsDir.exists() && wsDir.isDirectory()) {
                            discoveredRoots.add(normalize(wsDir.getAbsolutePath()));
                        }
                        discoveredRoots.add(normalize(libPath));
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static String normalize(String path) {
        try {
            return new File(path).getCanonicalPath();
        } catch (Throwable t) {
            return new File(path).getAbsolutePath();
        }
    }

    public static synchronized boolean tryApplyShield() {
        try {
            Class<?> fsClass = Class.forName("zombie.ZomboidFileSystem");
            Field instField = fsClass.getField("instance");
            Object fsInstance = instField.get(null);
            if (fsInstance == null) {
                return false;
            }

            // Force getAllModFolders to populate modFolders if null
            try {
                Method getAllModFoldersMethod = fsClass.getMethod("getAllModFolders", List.class);
                getAllModFoldersMethod.invoke(fsInstance, new ArrayList<>());
            } catch (Throwable ignored) {}

            Field modFoldersField = fsClass.getDeclaredField("modFolders");
            modFoldersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            ArrayList<String> modFolders = (ArrayList<String>) modFoldersField.get(fsInstance);

            if (modFolders != null) {
                int added = 0;
                for (String r : discoveredRoots) {
                    if (!modFolders.contains(r)) {
                        modFolders.add(r);
                        added++;
                    }
                }

                // Reset allowedPrefixes lazy value so it recomputes with all roots included
                try {
                    Field allowedField = fsClass.getDeclaredField("allowedPrefixes");
                    allowedField.setAccessible(true);
                    Object lazy = allowedField.get(fsInstance);
                    if (lazy != null) {
                        Method resetMethod = lazy.getClass().getMethod("reset");
                        resetMethod.invoke(lazy);
                    }
                } catch (Throwable ignored) {}

                if (!applied) {
                    applied = true;
                    PZOLogger.success(String.format("[FileSystemWhitelistShield] Successfully injected %d drive/workshop roots into ZomboidFileSystem whitelist (Secondary drive crash protected)", added));
                }
                return true;
            }
        } catch (Throwable t) {
            PZOLogger.warn("[FileSystemWhitelistShield] Notice during shield application: " + t.getMessage());
        }
        return false;
    }

    /**
     * Periodic watchdog call during game loading to maintain whitelist integrity.
     */
    public static void checkAndMaintain() {
        tryApplyShield();
    }
}
