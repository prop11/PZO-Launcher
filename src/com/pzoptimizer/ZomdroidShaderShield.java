package com.pzoptimizer;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Prevents the desktop MPOptimiser Workshop mod's Build 42 replacement shaders from overriding
 * vanilla shaders on Zomdroid (Build 41 / Build 42 over GL4ES or Turnip).
 *
 * On Build 41 with GL4ES, MPOptimiser's #include "util/..." shader headers are compiled by
 * B41's ShaderUnit as separate .glsl ShaderUnit attachments lacking main(), which GL4ES cannot
 * compile or link (breaking vehicle, wheel, tree, puddle, skybox, and floor programs).
 */
public final class ZomdroidShaderShield {

    private static final AtomicBoolean loggedOnce = new AtomicBoolean(false);
    public static final AtomicInteger blockedShaders = new AtomicInteger(0);
    private static volatile File cachedBaseDir = null;
    private static volatile boolean baseDirProbed = false;

    private ZomdroidShaderShield() {}

    /**
     * Redirect target for ZomboidFileSystem.getString(String) inside IndieFileLoader.
     */
    public static String resolveFilePath(zombie.ZomboidFileSystem zfs, String path) {
        if (zfs == null) return path;
        String resolved = zfs.getString(path);
        if (resolved == null || path == null) {
            return resolved;
        }

        String normResolved = resolved.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (!isMpOptimiserPath(normResolved)) {
            return resolved;
        }

        String normPath = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (!isShaderPath(normPath)) {
            return resolved;
        }

        String stockPath = findVanillaShaderFile(zfs, path);
        if (stockPath != null) {
            int count = blockedShaders.incrementAndGet();
            if (loggedOnce.compareAndSet(false, true)) {
                PZOLogger.info("[ZomdroidShaderShield] Intercepted MPOptimiser Workshop shader override on Zomdroid; routing all media/shaders/ requests to stock game shaders.");
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> activeMap = zfs.activeFileMap;
                if (activeMap != null) {
                    activeMap.put(normPath, stockPath);
                }
            } catch (Throwable ignored) {}
            return stockPath;
        }

        return resolved;
    }

    private static boolean isMpOptimiserPath(String lowerResolved) {
        return lowerResolved.contains("/mpoptimiser/")
            || lowerResolved.contains("/mp_optim/")
            || lowerResolved.contains("/3787481250/")
            || lowerResolved.contains("/3438344604/");
    }

    private static boolean isShaderPath(String lowerPath) {
        return lowerPath.startsWith("media/shaders/")
            || lowerPath.contains("/media/shaders/")
            || lowerPath.endsWith(".vert")
            || lowerPath.endsWith(".frag")
            || lowerPath.endsWith(".glsl")
            || lowerPath.endsWith(".h");
    }

    private static String findVanillaShaderFile(zombie.ZomboidFileSystem zfs, String relPath) {
        try {
            File direct = new File(relPath);
            if (direct.isFile()) {
                return direct.getAbsolutePath();
            }
        } catch (Throwable ignored) {}

        File baseDir = getGameBaseDir(zfs);
        if (baseDir != null) {
            try {
                File candidate = new File(baseDir, relPath);
                if (candidate.isFile()) {
                    return candidate.getAbsolutePath();
                }
            } catch (Throwable ignored) {}
        }

        return null;
    }

    private static File getGameBaseDir(zombie.ZomboidFileSystem zfs) {
        if (baseDirProbed) return cachedBaseDir;
        synchronized (ZomdroidShaderShield.class) {
            if (baseDirProbed) return cachedBaseDir;
            try {
                Field baseField = zfs.getClass().getField("base");
                Object baseObj = baseField.get(zfs);
                if (baseObj instanceof File) {
                    cachedBaseDir = (File) baseObj;
                } else if (baseObj != null) {
                    try {
                        Field canonField = baseObj.getClass().getField("canonicalFile");
                        Object canon = canonField.get(baseObj);
                        if (canon instanceof File) {
                            cachedBaseDir = (File) canon;
                        }
                    } catch (Throwable ignored) {}
                    if (cachedBaseDir == null) {
                        try {
                            Field absField = baseObj.getClass().getField("absoluteFile");
                            Object abs = absField.get(baseObj);
                            if (abs instanceof File) {
                                cachedBaseDir = (File) abs;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
            baseDirProbed = true;
            return cachedBaseDir;
        }
    }
}
