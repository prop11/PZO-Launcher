package com.pzoptimizer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import sun.misc.Unsafe;

/**
 * Project Zomboid Build 42 - FileSystem Whitelist & Secondary-Drive Protection Shield.
 * 
 * In Build 42, ZomboidFileSystem.validatePrefix(path) enforces an internal whitelist
 * of allowed base directories (allowedPrefixes). When Steam Workshop mods are installed
 * on a secondary drive (e.g. K:\SteamLibrary, D:\SteamLibrary), the vanilla engine can
 * fail to register these paths in time, or wipe them during ResetMods(), causing
 * validatePrefix to throw IllegalArgumentException and failing animation/model loading.
 * 
 * This shield intercepts allowedPrefixes via a permanent wrapped Supplier and runs
 * an active background watchdog to guarantee that all mounted drives and workshop
 * libraries are 100% permanently whitelisted.
 */
public final class FileSystemWhitelistShield {

    private static volatile boolean initialized = false;
    private static volatile boolean applied = false;
    private static final Set<String> discoveredRoots = new HashSet<>();
    private static volatile Unsafe unsafeInstance = null;

    private static void obtainUnsafe() {
        if (unsafeInstance != null) return;
        try {
            Field theUnsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafeField.setAccessible(true);
            unsafeInstance = (Unsafe) theUnsafeField.get(null);
        } catch (Throwable t) {
            PZOLogger.warn("[FileSystemWhitelistShield] Unsafe unavailable: " + t.getMessage());
        }
    }

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        obtainUnsafe();
        collectAllSearchRoots();

        // Continuous lifecycle daemon: maintains whitelist throughout game boot, main menu, and save loading
        Thread daemon = new Thread(() -> {
            while (true) {
                try {
                    tryApplyShield();
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                    break;
                } catch (Throwable ignored) {}
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
                        addRootPath(root);

                        String[] commonPaths = new String[]{
                            "SteamLibrary",
                            "SteamLibrary/steamapps",
                            "SteamLibrary/steamapps/workshop",
                            "SteamLibrary/steamapps/workshop/content/108600",
                            "Program Files (x86)/Steam/steamapps/workshop/content/108600",
                            "Program Files/Steam/steamapps/workshop/content/108600",
                            "Steam/steamapps/workshop/content/108600",
                            "Games/SteamLibrary/steamapps/workshop/content/108600",
                            "SteamLibrary/steamapps/common/ProjectZomboid/mods"
                        };

                        for (String cp : commonPaths) {
                            File f = new File(root, cp.replace('/', File.separatorChar));
                            if (f.exists()) {
                                addRootPath(f);
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
                addRootPath(zomboidDir);
            }
        } catch (Throwable ignored) {}

        // 3. Current working directory / execution directory roots
        try {
            File cwd = new File(".").getAbsoluteFile();
            addRootPath(cwd);
            File parent = cwd.getParentFile();
            while (parent != null) {
                addRootPath(parent);
                File ws = new File(parent, "workshop/content/108600".replace('/', File.separatorChar));
                if (ws.exists()) {
                    addRootPath(ws);
                }
                parent = parent.getParentFile();
            }
        } catch (Throwable ignored) {}
    }

    private static void addRootPath(File f) {
        if (f == null) return;
        try {
            discoveredRoots.add(normalize(f.getAbsolutePath()));
            discoveredRoots.add(normalize(f.getCanonicalPath()));
        } catch (Throwable ignored) {
            discoveredRoots.add(f.getAbsolutePath());
        }
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
                        File libDir = new File(libPath);
                        if (libDir.exists()) {
                            addRootPath(libDir);
                            File wsDir = new File(libDir, "steamapps/workshop/content/108600".replace('/', File.separatorChar));
                            if (wsDir.exists()) {
                                addRootPath(wsDir);
                            }
                        }
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

            // 1. Hook the ResettableLazyValue supplier permanently so resets NEVER drop discovered roots
            Field allowedField = fsClass.getDeclaredField("allowedPrefixes");
            allowedField.setAccessible(true);
            Object lazy = allowedField.get(fsInstance);
            if (lazy != null) {
                hookLazySupplier(lazy);
            }

            // 2. Populate and maintain modFolders
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

                if (added > 0 && lazy != null) {
                    try {
                        Method resetMethod = lazy.getClass().getMethod("reset");
                        resetMethod.invoke(lazy);
                    } catch (Throwable ignored) {}
                }

                if (!applied) {
                    applied = true;
                    PZOLogger.success(String.format("[FileSystemWhitelistShield] Successfully injected %d drive/workshop roots into ZomboidFileSystem whitelist (Secondary drive crash protected)", discoveredRoots.size()));
                }
                return true;
            }
        } catch (Throwable t) {
            PZOLogger.warn("[FileSystemWhitelistShield] Notice during shield application: " + t.getMessage());
        }
        return false;
    }

    private static void hookLazySupplier(Object lazyObj) {
        obtainUnsafe();
        if (unsafeInstance == null) return;

        try {
            Field supplierField = null;
            Class<?> cur = lazyObj.getClass();
            while (cur != null && cur != Object.class) {
                try {
                    supplierField = cur.getDeclaredField("supplier");
                    break;
                } catch (NoSuchFieldException e) {
                    cur = cur.getSuperclass();
                }
            }

            if (supplierField != null) {
                long offset = unsafeInstance.objectFieldOffset(supplierField);
                @SuppressWarnings("unchecked")
                Supplier<List<Path>> currentSupplier = (Supplier<List<Path>>) unsafeInstance.getObject(lazyObj, offset);
                if (!(currentSupplier instanceof PZOShieldSupplier)) {
                    PZOShieldSupplier shieldSupplier = new PZOShieldSupplier(currentSupplier, discoveredRoots);
                    unsafeInstance.putObject(lazyObj, offset, shieldSupplier);
                    try {
                        Method resetMethod = lazyObj.getClass().getMethod("reset");
                        resetMethod.invoke(lazyObj);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Unbreakable supplier wrapper: always ensures discovered secondary drive roots are included.
     */
    private static final class PZOShieldSupplier implements Supplier<List<Path>> {
        private final Supplier<List<Path>> delegate;
        private final Set<String> roots;

        PZOShieldSupplier(Supplier<List<Path>> delegate, Set<String> roots) {
            this.delegate = delegate;
            this.roots = roots;
        }

        @Override
        public List<Path> get() {
            List<Path> base = null;
            if (delegate != null) {
                try {
                    base = delegate.get();
                } catch (Throwable ignored) {}
            }
            List<Path> list = new ArrayList<>(base != null ? base : Collections.emptyList());
            for (String r : roots) {
                try {
                    File rf = new File(r);
                    Path rp = rf.toPath();
                    if (!list.contains(rp)) {
                        list.add(rp);
                    }
                    Path canP = rf.getCanonicalFile().toPath();
                    if (!list.contains(canP)) {
                        list.add(canP);
                    }
                } catch (Throwable ignored) {}
            }
            return Collections.unmodifiableList(list);
        }
    }

    public static void checkAndMaintain() {
        tryApplyShield();
    }
}
