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
 * B42's ZomboidFileSystem.validatePrefix(path) only allows whitelisted roots. This adds the Project Zomboid
 * workshop folder of every Steam library it finds, so Workshop mods on secondary drives are not rejected.
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

        // Retries until ZomboidFileSystem exists; the wrapper then stays in its final allowedPrefixes field for good
        // (reset() only clears the cached list), so the thread ends after the first success
        Thread daemon = new Thread(() -> {
            while (true) {
                try {
                    if (tryApplyShield()) break;
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                    break;
                } catch (Throwable ignored) {}
            }
        }, "PZO-FileSystem-Whitelist-Shield");
        daemon.setDaemon(true);
        daemon.start();
    }

    // Only folders the game legitimately reads mods and saves from. validatePrefix is a startsWith check, so a
    // drive root or a whole Steam library here would let any Lua mod read and write anywhere under it.
    private static void collectAllSearchRoots() {
        try {
            File[] roots = File.listRoots();
            if (roots != null) {
                for (File root : roots) {
                    if (!root.exists()) continue;
                    String[] steamDirs = new String[]{
                        "Program Files (x86)/Steam", "Program Files/Steam", "Steam", "SteamLibrary", "Games/SteamLibrary"
                    };
                    for (String sd : steamDirs) {
                        File steam = new File(root, sd.replace('/', File.separatorChar));
                        addWorkshopDir(steam);
                        File vdf = new File(steam, "steamapps" + File.separator + "libraryfolders.vdf");
                        if (vdf.isFile()) parseVdf(vdf);
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            String userHome = System.getProperty("user.home");
            File zomboidDir = new File(userHome, "Zomboid");
            if (zomboidDir.exists()) {
                addRootPath(zomboidDir);
            }
        } catch (Throwable ignored) {}

        try {
            File gameDir = new File(".").getAbsoluteFile().getCanonicalFile();
            addRootPath(gameDir);
            // <library>/steamapps/common/ProjectZomboid -> <library>/steamapps/workshop/content/108600
            File common = gameDir.getParentFile();
            File steamapps = common != null ? common.getParentFile() : null;
            if (steamapps != null) {
                File ws = new File(steamapps, "workshop/content/108600".replace('/', File.separatorChar));
                if (ws.isDirectory()) addRootPath(ws);
            }
        } catch (Throwable ignored) {}
    }

    private static void addWorkshopDir(File steamLibrary) {
        File ws = new File(steamLibrary, "steamapps/workshop/content/108600".replace('/', File.separatorChar));
        if (ws.isDirectory()) addRootPath(ws);
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
                        addWorkshopDir(new File(libPath));
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

            Field allowedField = fsClass.getDeclaredField("allowedPrefixes");
            allowedField.setAccessible(true);
            Object lazy = allowedField.get(fsInstance);
            if (lazy != null) {
                if (!hookLazySupplier(lazy)) return false;
                if (!applied) {
                    applied = true;
                    PZOLogger.success(String.format("[FileSystemWhitelistShield] Added %d game/workshop folders to the ZomboidFileSystem whitelist", discoveredRoots.size()));
                }
                return true;
            }
        } catch (Throwable t) {
            PZOLogger.warn("[FileSystemWhitelistShield] Notice during shield application: " + t.getMessage());
        }
        return false;
    }

    private static boolean hookLazySupplier(Object lazyObj) {
        obtainUnsafe();
        if (unsafeInstance == null) return false;

        try {
            Field supplierField = null;
            for (Class<?> cur = lazyObj.getClass(); supplierField == null && cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                for (Field f : cur.getDeclaredFields()) {
                    if (f.getName().equals("supplier")) {
                        supplierField = f;
                        break;
                    }
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
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** Supplier wrapper that always adds the discovered secondary-drive roots. */
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
