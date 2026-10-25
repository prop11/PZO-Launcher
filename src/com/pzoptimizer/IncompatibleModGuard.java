package com.pzoptimizer;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Detects PZ_Optimization (Steam Workshop 3805285544). It installs recompiled zombie.* classes as loose files in the
 * game folder, and the launcher classpath lists "." before projectzomboid.jar, so those copies replace the game's own
 * classes. PZO patches the stock classes and cannot run safely on top of them, so it stands down while they are there.
 */
public final class IncompatibleModGuard {

    public static final String PZ_OPTIMIZATION_WORKSHOP_ID = "3805285544";

    private static volatile Boolean cachedDetected = null;
    private static volatile File detectedMarker = null;
    private static volatile boolean warningShown = false;

    private IncompatibleModGuard() {}

    public static boolean isPzOptimizationInstalled() {
        Boolean cached = cachedDetected;
        if (cached != null) return cached;

        boolean found = false;
        try {
            for (File dir : candidateGameDirs()) {
                File marker = findPzOptimizationMarker(dir);
                if (marker != null) {
                    detectedMarker = marker;
                    found = true;
                    break;
                }
            }
            if (!found && ClassLoader.getSystemResource("pzopt/BootRepair.class") != null) {
                found = true;
            }
        } catch (Throwable ignored) {}

        cachedDetected = found;
        return found;
    }

    private static Set<File> candidateGameDirs() {
        Set<File> dirs = new LinkedHashSet<>();
        try {
            dirs.add(new File(".").getAbsoluteFile().getCanonicalFile());
        } catch (Throwable ignored) {}
        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(File.pathSeparator)) {
            if (entry.toLowerCase().endsWith("projectzomboid.jar")) {
                try {
                    File parent = new File(entry).getAbsoluteFile().getCanonicalFile().getParentFile();
                    if (parent != null) dirs.add(parent);
                } catch (Throwable ignored) {}
            }
        }
        return dirs;
    }

    // pzopt-installed.txt is its install manifest; the pzopt folder holds its boot repair and helper classes.
    private static File findPzOptimizationMarker(File gameDir) {
        if (gameDir == null || !gameDir.isDirectory()) return null;
        File manifest = new File(gameDir, "pzopt-installed.txt");
        if (manifest.isFile()) return manifest;
        File bootRepair = new File(gameDir, "pzopt/BootRepair.class");
        if (bootRepair.isFile()) return bootRepair;
        File pzoptDir = new File(gameDir, "pzopt");
        if (pzoptDir.isDirectory()) {
            File[] classes = pzoptDir.listFiles((d, name) -> name.endsWith(".class"));
            if (classes != null && classes.length > 0) return pzoptDir;
        }
        File uninstaller = new File(gameDir, "Uninstall-PZ-Optimization.cmd");
        if (uninstaller.isFile()) return uninstaller;
        return null;
    }

    /** Logs the conflict, marks PZO inactive for the Lua side and warns the player once per launch. */
    public static synchronized void reportAndWarn() {
        if (warningShown) return;
        warningShown = true;

        File marker = detectedMarker;
        PZOLogger.warn("================================================================================");
        PZOLogger.warn("[PZO] PZ_Optimization (Workshop " + PZ_OPTIMIZATION_WORKSHOP_ID + ") is installed in the game folder"
            + (marker != null ? " (found " + marker.getAbsolutePath() + ")" : "") + ".");
        PZOLogger.warn("[PZO] It replaces core game classes, which PZO is incompatible with. PZO is DISABLED for this launch:");
        PZOLogger.warn("[PZO] no bytecode patches, engine governors, Lua bridge or Java mod loading will run.");
        PZOLogger.warn("================================================================================");

        try {
            TelemetryReporter.writeStatusFile(String.format(
                "{\"optimized\":false,\"disabled\":\"incompatible_mod\",\"incompatible_mod\":\"PZ_Optimization\",\"version\":\"%s\"}",
                UpdateChecker.CURRENT_VERSION));
        } catch (Throwable ignored) {}

        UpdateDialog.showWarningNotice("Project Zomboid Optimiser - Incompatible Mod Detected", warningMessage());
    }

    static String warningMessage() {
        return "PZ_Optimization (Steam Workshop " + PZ_OPTIMIZATION_WORKSHOP_ID + ") is installed in your Project Zomboid game folder.\n\n"
            + "This mod is not recommended: it replaces core game files with its own modified copies, "
            + "and PZO is incompatible with it.\n\n"
            + "PZO has been DISABLED for this launch and will stay disabled while PZ_Optimization is detected. "
            + "The game will now start without PZO.\n\n"
            + "To use PZO again, remove PZ_Optimization with its uninstaller "
            + "(Uninstall-PZ-Optimization.cmd in the game folder, or its in-game uninstall option), then restart the game. "
            + "Unsubscribing from the Workshop item or verifying game files in Steam does not remove its files.";
    }
}
