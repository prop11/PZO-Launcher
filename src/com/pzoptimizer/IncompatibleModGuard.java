package com.pzoptimizer;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Detects PZ_Optimization (Workshop 3805285544). It drops recompiled zombie.* classes as loose files in the game
 * folder, and the launcher classpath has "." before projectzomboid.jar, so those replace the game's own classes. PZO
 * patches the stock classes and can't safely run on top of those, so it stands down while they're there.
 */
public final class IncompatibleModGuard {

    public static final String PZ_OPTIMIZATION_WORKSHOP_ID = "3805285544";

    private static final Pattern PZOPT_AGENT = Pattern.compile(
        "(?i)(?:^|\")-javaagent:(?:[^\"\r\n]*[/\\\\])?pzopt-agent\\.jar(?:[\"=\\s]|$)");

    private static volatile Boolean cachedDetected = null;
    private static volatile File detectedMarker = null;
    private static volatile boolean warningShown = false;
    private static volatile boolean scanned = false;
    private static final Object CLEARANCE = new Object();
    private static volatile Object granted = null;

    private IncompatibleModGuard() {}

    static synchronized Object clearance() {
        Object g = granted;
        if (g != null) return g;
        if (isPzOptimizationInstalled() || !StartupIntegrity.verify()) return null;
        granted = CLEARANCE;
        return CLEARANCE;
    }

    static boolean cleared(Object token) {
        return token != null && token == CLEARANCE && granted == CLEARANCE;
    }

    static boolean hasScanned() {
        return scanned;
    }

    static boolean detectsIn(File dir) {
        return findPzOptimizationMarker(dir) != null;
    }

    public static boolean isPzOptimizationInstalled() {
        scanned = true;
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
            if (!found && (hasPzOptimizationAgentArgument()
                    || ClassLoader.getSystemResource("pzopt/BootRepair.class") != null)) {
                found = true;
            }
        } catch (Throwable ignored) {}

        cachedDetected = found;
        return found;
    }

    static Set<File> candidateGameDirs() {
        Set<File> dirs = new LinkedHashSet<>();
        try {
            dirs.add(new File(".").getAbsoluteFile().getCanonicalFile());
        } catch (Throwable ignored) {}
        try {
            File engine = new File(IncompatibleModGuard.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (engine.isFile()) dirs.add(engine.getCanonicalFile().getParentFile());
        } catch (Exception ignored) {}
        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(File.pathSeparator)) {
            if (entry.toLowerCase(Locale.ROOT).endsWith("projectzomboid.jar")) {
                try {
                    File parent = new File(entry).getAbsoluteFile().getCanonicalFile().getParentFile();
                    if (parent != null) dirs.add(parent);
                } catch (Throwable ignored) {}
            }
        }
        return dirs;
    }

    private static boolean hasPzOptimizationAgentArgument() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
            .anyMatch(arg -> arg.startsWith("-javaagent:") && PZOPT_AGENT.matcher(arg).find());
    }

    // pzopt-installed.txt is its install manifest; the pzopt folder has its boot repair and helper classes
    private static File findPzOptimizationMarker(File gameDir) {
        if (gameDir == null || !gameDir.isDirectory()) return null;
        File manifest = new File(gameDir, "pzopt-installed.txt");
        if (manifest.isFile()) return manifest;
        File bootRepair = new File(gameDir, "pzopt/BootRepair.class");
        if (bootRepair.isFile()) return bootRepair;
        File agent = new File(gameDir, "pzopt/agent/pzopt-agent.jar");
        if (agent.isFile()) return agent;
        File config = new File(gameDir, ZomboidConfigMigrator.TARGET_JSON_NAME);
        if (config.isFile()) {
            try {
                if (PZOPT_AGENT.matcher(Files.readString(config.toPath())).find()) return config;
            } catch (IOException ignored) {}
        }
        File pzoptDir = new File(gameDir, "pzopt");
        if (pzoptDir.isDirectory()) {
            File[] classes = pzoptDir.listFiles((d, name) -> name.endsWith(".class"));
            if (classes != null && classes.length > 0) return pzoptDir;
        }
        File launcher = new File(gameDir, "pzopt-launch.cmd");
        if (launcher.isFile()) return launcher;
        File uninstaller = new File(gameDir, "Uninstall-PZ-Optimization.cmd");
        if (uninstaller.isFile()) return uninstaller;
        return null;
    }

    /** Logs the conflict, tells the Lua side PZO is off and warns the player once per launch. */
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
            File luaDir = ZomboidDir.sub("Lua");
            Files.createDirectories(luaDir.toPath());
            Files.writeString(new File(luaDir, "pzo_status.json").toPath(), String.format(
                "{\"optimized\":false,\"disabled\":\"incompatible_mod\",\"incompatible_mod\":\"PZ_Optimization\",\"version\":\"%s\"}",
                UpdateChecker.CURRENT_VERSION));
        } catch (IOException | SecurityException e) {
            PZOLogger.warn("[PZO] Could not write incompatible mod status: " + e);
        }

        UpdateDialog.showIncompatibleModNotice(warningMessage());
    }

    /**
     * PZOptPreloader is a separate Workshop mod that a lot of people add thinking it's part of PZO. It works alongside
     * PZO (ZomboidConfigMigrator keeps its class path pin pointing at PZO's jars), so this only says what it is, once.
     */
    public static void noticePzOptPreloaderOnce() {
        try {
            boolean found = System.getProperty("pzoptpreloader.aotManaged") != null
                || new File(System.getProperty("user.home", ""), ".zombie_buddy/PZOptPreloader").isDirectory();
            if (!found) return;
            File seen = ZomboidDir.sub("PZO_notice_pzoptpreloader.txt");
            if (seen.exists()) return;
            PZOLogger.info("[PZO] PZOptPreloader detected. It is a separate Workshop mod, not part of PZO; showing a one-time notice.");
            UpdateDialog.showWarningNotice("Project Zomboid Optimiser - Separate Mod Detected",
                "PZOptPreloader is installed. It is a separate Steam Workshop mod and is not part of PZO "
                    + "(Project Zomboid Optimiser).\n\n"
                    + "PZO does not need it. If you added it thinking it was part of PZO, unsubscribe from it on the "
                    + "Steam Workshop.\n\n"
                    + "If you use it on purpose, you can keep it: PZO keeps its launch settings working alongside PZO.\n\n"
                    + "This notice is shown once.");
            Files.writeString(seen.toPath(), "PZOptPreloader notice shown for PZO " + UpdateChecker.CURRENT_VERSION + "\n");
        } catch (Throwable t) {
            PZOLogger.warn("[PZO] PZOptPreloader notice: " + t.getMessage());
        }
    }

    static String warningMessage() {
        return "PZ_Optimization (Steam Workshop " + PZ_OPTIMIZATION_WORKSHOP_ID + ") files or launch settings were detected.\n\n"
            + "PZO is disabled for this launch. Leftover PZ_Optimization files or launch options can still prevent "
            + "Project Zomboid from starting.\n\n"
            + "Run Uninstall-PZ-Optimization.cmd in the game folder, or use PZ_Optimization's in-game uninstall option "
            + "if you can reach the menu. Remove any pzopt-launch.cmd entry from Steam's Launch Options, then restart.\n\n"
            + "Unsubscribing or verifying game files in Steam can leave these files behind.";
    }
}
